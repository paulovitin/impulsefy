use futures_util::StreamExt;
use jni::{
    objects::{GlobalRef, JClass, JObject, JString, JValue},
    sys::jlong,
    JNIEnv, JavaVM,
};
use librespot_connect::{
    ConnectConfig, LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack,
    Spirc,
};
use librespot_core::{
    authentication::Credentials,
    config::{DeviceType, SessionConfig},
    session::Session,
    SpotifyUri,
};
use librespot_discovery::Discovery;
use librespot_metadata::audio::UniqueFields;
use librespot_playback::{
    audio_backend::{Sink, SinkError, SinkResult},
    config::{Bitrate, PlayerConfig},
    convert::Converter,
    decoder::AudioPacket,
    mixer::{softmixer::SoftMixer, Mixer, MixerConfig},
    player::{Player, PlayerEvent, PlayerEventChannel},
};
use librespot_protocol::authentication::AuthenticationType;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicBool, AtomicI64, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::{Duration, Instant},
};
use tokio::{sync::mpsc, task::JoinHandle};

#[derive(Default, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct State {
    connected: bool,
    playing: bool,
    loading: bool,
    title: String,
    artist: String,
    uri: String,
    duration_ms: u32,
    position_ms: u32,
    cover_url: String,
    error: String,
    device_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    credential: Option<String>,
}

#[derive(Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
enum Command {
    Connect {
        access_token: String,
        credential: String,
        device_id: String,
    },
    Load {
        uris: Vec<String>,
        index: usize,
    },
    Toggle,
    Play,
    Pause,
    Next,
    Previous,
    Seek {
        milliseconds: u32,
    },
    Shutdown,
}

#[derive(Clone)]
struct Login {
    access_token: String,
    credential: String,
    device_id: String,
}

struct Bridge {
    vm: JavaVM,
    object: GlobalRef,
    tmp_dir: std::path::PathBuf,
    closed: AtomicBool,
}
impl Bridge {
    fn publish(&self, state: &State) {
        if self.closed.load(Ordering::Acquire) {
            return;
        }
        let Ok(json) = serde_json::to_string(state) else {
            return;
        };
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return;
        };
        let _ = env.with_local_frame(4, |env| -> jni::errors::Result<()> {
            let value = env.new_string(json)?;
            env.call_method(
                self.object.as_obj(),
                "onNativeState",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&value)],
            )?;
            Ok(())
        });
        let _ = env.exception_clear();
    }
    fn audio_state(&self, method: &str) -> SinkResult<()> {
        if self.closed.load(Ordering::Acquire) && method == "startAudio" {
            return Err(SinkError::NotConnected("Player encerrado".into()));
        }
        let result = (|| -> jni::errors::Result<bool> {
            let mut env = self.vm.attach_current_thread()?;
            let result = env
                .call_method(self.object.as_obj(), method, "()Z", &[])
                .and_then(|r| r.z());
            let _ = env.exception_clear();
            result
        })();
        match result {
            Ok(true) => Ok(()),
            _ => Err(SinkError::StateChange(
                "Saída de áudio indisponível ou ocupada".into(),
            )),
        }
    }
}
struct AndroidSink(Arc<Bridge>);
impl Sink for AndroidSink {
    fn start(&mut self) -> SinkResult<()> {
        self.0.audio_state("startAudio")
    }
    fn stop(&mut self) -> SinkResult<()> {
        self.0.audio_state("stopAudio")
    }
    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        if self.0.closed.load(Ordering::Acquire) {
            return Err(SinkError::NotConnected("Player encerrado".into()));
        }
        let AudioPacket::Samples(samples) = packet else {
            return Err(SinkError::InvalidParams("Formato de áudio inválido".into()));
        };
        let pcm = converter.f64_to_s16(&samples);
        let result = (|| -> jni::errors::Result<bool> {
            let mut env = self.0.vm.attach_current_thread()?;
            let result = env.with_local_frame(4, |env| -> jni::errors::Result<bool> {
                let array = env.new_short_array(pcm.len() as i32)?;
                env.set_short_array_region(&array, 0, &pcm)?;
                env.call_method(
                    self.0.object.as_obj(),
                    "writePcm",
                    "([S)Z",
                    &[JValue::Object(&array)],
                )?
                .z()
            });
            let _ = env.exception_clear();
            result
        })();
        match result {
            Ok(true) => Ok(()),
            _ => Err(SinkError::OnWrite("Falha na saída de áudio Android".into())),
        }
    }
}

struct Engine {
    session: Session,
    player: Arc<Player>,
    spirc: Spirc,
    task: JoinHandle<()>,
    events: PlayerEventChannel,
}
impl Drop for Engine {
    fn drop(&mut self) {
        let _ = self.spirc.shutdown();
        self.player.stop();
        self.session.shutdown();
        self.task.abort();
    }
}

fn credentials(login: &Login) -> Result<Credentials, String> {
    if !login.credential.is_empty() {
        if let Ok(credential) = serde_json::from_str::<Credentials>(&login.credential) {
            if credential.auth_type == AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS
                && credential.username.as_ref().is_some_and(|s| !s.is_empty())
                && !credential.auth_data.is_empty()
            {
                return Ok(credential);
            }
        }
    }
    if login.access_token.is_empty() {
        return Err("Entre novamente no Spotify: a autorização de reprodução não é válida".into());
    }
    Ok(Credentials::with_access_token(login.access_token.clone()))
}

fn playback_credential_for(credential: &Credentials, username: &str) -> bool {
    !username.is_empty()
        && credential.username.as_deref() == Some(username)
        && credential.auth_type == AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS
        && !credential.auth_data.is_empty()
}

async fn playback_credentials(
    login: &Login,
    config: &SessionConfig,
    bridge: &Bridge,
) -> Result<Credentials, String> {
    let credential = credentials(login)?;
    if credential.auth_type == AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS {
        return Ok(credential);
    }

    // Web API OAuth identifies the account, but login5 needs a separate playback
    // grant. Verify the account before accepting a local Spotify Connect grant.
    let probe = Session::new(config.clone(), None);
    let verified = tokio::select! {
        result = tokio::time::timeout(Duration::from_secs(30), probe.connect(credential, false)) => match result {
            Ok(Ok(())) => Ok(()),
            Ok(Err(error)) => Err(format!("Não foi possível verificar sua conta Spotify ({:?}). Tente novamente.", error.kind)),
            Err(_) => Err("O Spotify não respondeu a tempo. Verifique a rede.".into()),
        },
        _ = async { while !bridge.closed.load(Ordering::Acquire) { tokio::time::sleep(Duration::from_millis(100)).await; } } => Err("Player encerrado".into()),
    };
    let username = probe.username();
    probe.shutdown();
    verified?;

    let mut discovery = Discovery::builder(config.device_id.clone(), config.client_id.clone())
        .name("Impulsefy")
        .device_type(DeviceType::Automobile)
        .launch()
        .map_err(|_| "Não foi possível anunciar o carro no Spotify. Verifique o Wi-Fi e tente novamente.")?;
    let mut waiting = State {
        loading: true,
        device_id: login.device_id.clone(),
        error: "No Spotify do celular, abra Dispositivos e escolha Impulsefy. Use a mesma conta e rede Wi-Fi do carro.".into(),
        ..State::default()
    };
    bridge.publish(&waiting);
    let result = tokio::select! {
        result = tokio::time::timeout(Duration::from_secs(300), async {
            while let Some(credential) = discovery.next().await {
                if playback_credential_for(&credential, &username) {
                    return Ok(credential);
                }
                waiting.error = "Use no Spotify do celular a mesma conta conectada ao Impulsefy e selecione o carro novamente.".into();
                bridge.publish(&waiting);
            }
            Err("A conexão local foi interrompida. Verifique o Wi-Fi e tente novamente.".into())
        }) => result.unwrap_or_else(|_| Err("A autorização de áudio expirou. Toque em reproduzir e escolha Impulsefy no Spotify do celular.".into())),
        _ = async { while !bridge.closed.load(Ordering::Acquire) { tokio::time::sleep(Duration::from_millis(100)).await; } } => Err("Player encerrado".into()),
    };
    discovery.shutdown().await;
    result
}

async fn connect(login: &Login, bridge: Arc<Bridge>) -> Result<Engine, String> {
    let result = connect_once(login, bridge.clone()).await;
    if result.is_err()
        && !login.credential.is_empty()
        && !login.access_token.is_empty()
        && !bridge.closed.load(Ordering::Acquire)
    {
        // A revoked saved blob must not mask a newly refreshed OAuth token.
        let fresh = Login {
            credential: String::new(),
            ..login.clone()
        };
        return connect_once(&fresh, bridge).await;
    }
    result
}

async fn connect_once(login: &Login, bridge: Arc<Bridge>) -> Result<Engine, String> {
    let config = SessionConfig {
        device_id: login.device_id.clone(),
        tmp_dir: bridge.tmp_dir.clone(),
        autoplay: Some(false),
        ..SessionConfig::default()
    };
    let credential = playback_credentials(login, &config, &bridge).await?;
    // No cache at all: no plain-text credentials, and no unbounded disk audio.
    let session = Session::new(config, None);
    let mixer: Arc<dyn Mixer> = Arc::new(
        SoftMixer::open(MixerConfig::default())
            .map_err(|_| "Não foi possível iniciar o mixer de áudio")?,
    );
    mixer.set_volume(u16::MAX);
    let output = bridge.clone();
    let player = Player::new(
        PlayerConfig {
            bitrate: Bitrate::Bitrate160,
            normalisation: true,
            gapless: true,
            position_update_interval: Some(Duration::from_secs(1)),
            ..PlayerConfig::default()
        },
        session.clone(),
        mixer.get_soft_volume(),
        move || Box::new(AndroidSink(output)),
    );
    let events = player.get_player_event_channel();
    let init = Spirc::new(
        ConnectConfig {
            name: "Impulsefy".into(),
            device_type: DeviceType::Automobile,
            initial_volume: u16::MAX,
            disable_volume: true,
            ..ConnectConfig::default()
        },
        session.clone(),
        credential,
        player.clone(),
        mixer,
    );
    let result = tokio::select! {
        result = tokio::time::timeout(Duration::from_secs(30), init) => match result {
            Ok(Ok(pair)) => Ok(pair),
            Ok(Err(error)) => Err(format!("Falha ao conectar ao Spotify ({:?}). Verifique a rede, a assinatura Premium ou entre novamente.", error.kind)),
            Err(_) => Err("O Spotify não respondeu a tempo. Verifique a rede e tente novamente.".into()),
        },
        _ = async { while !bridge.closed.load(Ordering::Acquire) { tokio::time::sleep(Duration::from_millis(100)).await; } } => Err("Player encerrado".into()),
    };
    match result {
        Ok((spirc, task)) => Ok(Engine {
            session,
            player,
            spirc,
            task: tokio::spawn(task),
            events,
        }),
        Err(error) => {
            player.stop();
            session.shutdown();
            Err(error)
        }
    }
}

fn queue_request(
    uris: Vec<String>,
    index: usize,
    play: bool,
    position: u32,
) -> Result<LoadRequest, String> {
    if uris.is_empty() || uris.len() > 2000 || index >= uris.len() {
        return Err("Escolha um item em uma fila de 1 a 2000 faixas".into());
    }
    for uri in &uris {
        if uri.len() > 128
            || !matches!(
                SpotifyUri::from_uri(uri),
                Ok(SpotifyUri::Track { .. } | SpotifyUri::Episode { .. })
            )
        {
            return Err("A fila contém uma faixa ou episódio inválido do Spotify".into());
        }
    }
    Ok(LoadRequest::from_tracks(
        uris,
        LoadRequestOptions {
            start_playing: play,
            seek_to: position,
            playing_track: Some(PlayingTrack::Index(index as u32)),
            context_options: Some(LoadContextOptions::Options(Options::default())),
        },
    ))
}

fn apply_event(state: &mut State, current_request: &mut Option<u64>, event: PlayerEvent) -> bool {
    if let PlayerEvent::PlayRequestIdChanged { play_request_id } = event {
        *current_request = Some(play_request_id);
        return false;
    }
    // Upstream omits PositionChanged from get_play_request_id; filter it too.
    let incoming = match &event {
        PlayerEvent::PositionChanged {
            play_request_id, ..
        } => Some(*play_request_id),
        _ => event.get_play_request_id(),
    };
    if current_request.is_some() && incoming.is_some() && *current_request != incoming {
        return false;
    }
    match event {
        PlayerEvent::Loading {
            position_ms,
            track_id,
            ..
        } => {
            state.loading = true;
            state.playing = false;
            state.position_ms = position_ms;
            let uri = track_id.to_uri().unwrap_or_default();
            if uri != state.uri {
                state.title.clear();
                state.artist.clear();
                state.cover_url.clear();
                state.duration_ms = 0;
            }
            state.uri = uri;
            state.error.clear();
        }
        PlayerEvent::Playing { position_ms, .. } => {
            state.playing = true;
            state.loading = false;
            state.position_ms = position_ms;
            state.error.clear();
        }
        PlayerEvent::Paused { position_ms, .. } => {
            state.playing = false;
            state.loading = false;
            state.position_ms = position_ms;
        }
        // Gapless EndOfTrack is a transition, not a stopped sink. Spirc emits
        // Loading/Playing next, or Stopped when the queue is exhausted.
        PlayerEvent::EndOfTrack { .. } => return false,
        PlayerEvent::Stopped { .. } => {
            state.playing = false;
            state.loading = false;
        }
        PlayerEvent::PositionChanged { position_ms, .. }
        | PlayerEvent::PositionCorrection { position_ms, .. }
        | PlayerEvent::Seeked { position_ms, .. } => state.position_ms = position_ms,
        PlayerEvent::TrackChanged { audio_item: item } => {
            state.title = item.name;
            state.uri = item.uri;
            state.duration_ms = item.duration_ms;
            state.artist = match &item.unique_fields {
                UniqueFields::Track { artists, .. } => artists
                    .iter()
                    .map(|a| a.name.as_str())
                    .collect::<Vec<_>>()
                    .join(", "),
                UniqueFields::Episode { show_name, .. } => show_name.clone(),
                UniqueFields::Local { artists, .. } => artists.clone().unwrap_or_default(),
            };
            state.cover_url = item
                .covers
                .iter()
                .min_by_key(|c| (c.width - 300).abs())
                .map(|c| c.url.clone())
                .unwrap_or_default();
        }
        PlayerEvent::Unavailable { track_id, .. } => {
            // A failed preload carries the current request ID but the NEXT URI.
            // It must not interrupt the current track or release Android focus.
            if track_id.to_uri().unwrap_or_default() != state.uri {
                return false;
            }
            // Spirc skips unavailable tracks, then reports Loading or Stopped.
            state.error = "Não foi possível reproduzir este item. Ele pode estar indisponível para esta conta ou região; é necessário ter Spotify Premium.".into();
        }
        _ => return false,
    }
    true
}

async fn run(bridge: Arc<Bridge>, mut commands: mpsc::UnboundedReceiver<Command>) {
    let mut engine: Option<Engine> = None;
    let mut state = State::default();
    let mut login: Option<Login> = None;
    let mut queue: Vec<String> = Vec::new();
    let mut current_request = None;
    let mut retry_at: Option<Instant> = None;
    let mut retry_count = 0;
    let mut ticker = tokio::time::interval(Duration::from_secs(1));
    loop {
        if bridge.closed.load(Ordering::Acquire) {
            break;
        }
        tokio::select! {
            command = commands.recv() => {
                let Some(command) = command else { break };
                match command {
                    Command::Shutdown => break,
                    Command::Connect { access_token, credential, device_id } => {
                        engine.take(); current_request = None; retry_at = None; retry_count = 0;
                        state = State { device_id: device_id.clone(), loading: true, ..State::default() };
                        queue.clear(); bridge.publish(&state);
                        login = Some(Login { access_token, credential, device_id });
                        match connect(login.as_ref().unwrap(), bridge.clone()).await {
                            Ok(next) => {
                                let saved = Credentials { username: Some(next.session.username()), auth_type: AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS, auth_data: next.session.auth_data() };
                                let json = serde_json::to_string(&saved).ok();
                                if let Some(value) = &json { login.as_mut().unwrap().credential = value.clone(); }
                                state.credential = json; state.connected = true; state.loading = false; engine = Some(next);
                            }
                            Err(error) => { state.error = error; state.loading = false; }
                        }
                        bridge.publish(&state); state.credential = None;
                    }
                    command => {
                        if let Some(active) = &engine {
                            let result = match command {
                                Command::Load { uris, index } => queue_request(uris.clone(), index, true, 0).and_then(|request| {
                                    active.spirc.activate().and_then(|_| active.spirc.load(request)).map_err(|e| format!("Spotify: {:?}", e.kind))?;
                                    queue = uris; Ok(())
                                }),
                                Command::Toggle => active.spirc.play_pause().map_err(|e| format!("Spotify: {:?}", e.kind)),
                                Command::Play => active.spirc.play().map_err(|e| format!("Spotify: {:?}", e.kind)),
                                Command::Pause => { active.player.pause(); active.spirc.pause().map_err(|e| format!("Não foi possível pausar ({:?})", e.kind)) },
                                Command::Next => active.spirc.next().map_err(|e| format!("Spotify: {:?}", e.kind)),
                                Command::Previous => active.spirc.prev().map_err(|e| format!("Spotify: {:?}", e.kind)),
                                Command::Seek { milliseconds } => active.spirc.set_position_ms(milliseconds.min(state.duration_ms)).map_err(|e| e.to_string()),
                                _ => Ok(()),
                            };
                            if let Err(error) = result { state.error = format!("Falha no comando de reprodução: {error}"); bridge.publish(&state); }
                        } else { state.error = "O player está desconectado. Reconecte ao Spotify e tente novamente.".into(); bridge.publish(&state); }
                    }
                }
            }
            event = async { match &mut engine { Some(active) => active.events.recv().await, None => std::future::pending().await } } => {
                if let Some(event) = event {
                    if apply_event(&mut state, &mut current_request, event) { bridge.publish(&state); }
                } else { engine.take(); state.connected = false; state.playing = false; state.loading = false; state.error = "Spotify desconectado. Reconectando…".into(); retry_at = Some(Instant::now() + Duration::from_secs(2)); retry_count = 0; current_request = None; bridge.publish(&state); }
            }
            _ = ticker.tick() => {
                if engine.as_ref().is_some_and(|e| e.session.is_invalid() || e.task.is_finished()) {
                    engine.take(); current_request = None;
                    state.connected = false; state.playing = false; state.loading = false;
                    state.error = "Spotify desconectado. Reconectando…".into();
                    retry_at = Some(Instant::now() + Duration::from_secs(2)); retry_count = 0; bridge.publish(&state);
                }
                if retry_at.is_some_and(|at| Instant::now() >= at) {
                    retry_at = None;
                    if let Some(auth) = &mut login {
                        match connect(auth, bridge.clone()).await {
                            Ok(next) => {
                                state.connected = true; state.error.clear();
                                let saved = Credentials { username: Some(next.session.username()), auth_type: AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS, auth_data: next.session.auth_data() };
                                if let Ok(json) = serde_json::to_string(&saved) { auth.credential = json.clone(); state.credential = Some(json); }
                                // Reconnect restores the last locally selected queue paused. Never resume audio unexpectedly.
                                if let Some(index) = queue.iter().position(|uri| uri == &state.uri) {
                                    if let Ok(request) = queue_request(queue.clone(), index, false, state.position_ms) {
                                        let _ = next.spirc.activate().and_then(|_| next.spirc.load(request));
                                    }
                                }
                                engine = Some(next);
                            }
                            Err(error) => {
                                retry_count += 1;
                                state.error = error;
                                if retry_count < 3 { retry_at = Some(Instant::now() + Duration::from_secs(2 << retry_count)); }
                            }
                        }
                        bridge.publish(&state); state.credential = None;
                    }
                }
            }
        }
    }
    drop(engine);
    let _ = bridge.audio_state("stopAudio");
}

struct NativeHandle {
    commands: mpsc::UnboundedSender<Command>,
    bridge: Arc<Bridge>,
}
static HANDLES: OnceLock<Mutex<HashMap<i64, NativeHandle>>> = OnceLock::new();
static NEXT_ID: AtomicI64 = AtomicI64::new(1);
fn handles() -> &'static Mutex<HashMap<i64, NativeHandle>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_impulsefy_NativePlayer_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    object: JObject,
    cache_dir: JString,
) -> jlong {
    let result = (|| -> Result<i64, String> {
        let bridge = Arc::new(Bridge {
            vm: env.get_java_vm().map_err(|e| e.to_string())?,
            object: env.new_global_ref(object).map_err(|e| e.to_string())?,
            tmp_dir: std::path::PathBuf::from(String::from(
                env.get_string(&cache_dir).map_err(|e| e.to_string())?,
            )),
            closed: AtomicBool::new(false),
        });
        let (sender, receiver) = mpsc::unbounded_channel();
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| e.to_string())?;
        let target = bridge.clone();
        std::thread::Builder::new()
            .name("impulsefy-engine".into())
            .spawn(move || {
                runtime.block_on(run(target, receiver));
                runtime.shutdown_timeout(Duration::from_secs(2));
            })
            .map_err(|e| e.to_string())?;
        handles()
            .lock()
            .map_err(|_| "Falha ao acessar o mecanismo de áudio")?
            .insert(
                id,
                NativeHandle {
                    commands: sender,
                    bridge,
                },
            );
        Ok(id)
    })();
    match result {
        Ok(id) => id,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_impulsefy_NativePlayer_nativeCommand(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    json: JString,
) {
    let result = (|| -> Result<(), String> {
        let text: String = env.get_string(&json).map_err(|e| e.to_string())?.into();
        if text.len() > 512_000 {
            return Err("A fila de reprodução é grande demais".into());
        }
        let command =
            serde_json::from_str::<Command>(&text).map_err(|_| "Comando de reprodução inválido")?;
        let guard = handles()
            .lock()
            .map_err(|_| "Falha ao acessar o mecanismo de áudio")?;
        guard
            .get(&handle)
            .ok_or("Mecanismo de áudio encerrado")?
            .commands
            .send(command)
            .map_err(|_| "Mecanismo de áudio interrompido".into())
    })();
    if let Err(message) = result {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_impulsefy_NativePlayer_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Ok(mut guard) = handles().lock() {
        if let Some(entry) = guard.remove(&handle) {
            entry.bridge.closed.store(true, Ordering::Release);
            let _ = entry.commands.send(Command::Shutdown);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const A: &str = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";
    const B: &str = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b";
    #[test]
    fn queue_validates_before_sending_and_retains_selected_offset() {
        let request = queue_request(vec![A.into(), B.into()], 1, true, 1234).unwrap();
        assert!(matches!(
            request.playing_track,
            Some(PlayingTrack::Index(1))
        ));
        assert_eq!(request.seek_to, 1234);
        assert!(request.start_playing);
        assert!(queue_request(vec![], 0, true, 0).is_err());
        assert!(queue_request(vec![A.into()], 1, true, 0).is_err());
        assert!(queue_request(
            vec!["spotify:album:4uLU6hMCjMI75M1A2tKUQC".into()],
            0,
            true,
            0
        )
        .is_err());
        assert!(queue_request(vec!["file:///tmp/audio".into()], 0, true, 0).is_err());
    }
    #[test]
    fn stale_track_events_cannot_change_current_playback() {
        let mut state = State::default();
        let mut request = None;
        let id = SpotifyUri::from_uri(A).unwrap();
        apply_event(
            &mut state,
            &mut request,
            PlayerEvent::PlayRequestIdChanged { play_request_id: 2 },
        );
        assert!(!apply_event(
            &mut state,
            &mut request,
            PlayerEvent::Playing {
                play_request_id: 1,
                track_id: id.clone(),
                position_ms: 9000
            }
        ));
        assert!(!apply_event(
            &mut state,
            &mut request,
            PlayerEvent::PositionChanged {
                play_request_id: 1,
                track_id: id.clone(),
                position_ms: 9000
            }
        ));
        assert!(apply_event(
            &mut state,
            &mut request,
            PlayerEvent::Playing {
                play_request_id: 2,
                track_id: id.clone(),
                position_ms: 2000
            }
        ));
        assert!(state.playing);
        assert_eq!(state.position_ms, 2000);
        apply_event(
            &mut state,
            &mut request,
            PlayerEvent::Paused {
                play_request_id: 2,
                track_id: id,
                position_ms: 2200,
            },
        );
        assert!(!state.playing);
        assert_eq!(state.position_ms, 2200);
    }
    #[test]
    fn gapless_transitions_and_failed_preloads_keep_audio_active_until_stopped() {
        let mut state = State {
            connected: true,
            playing: true,
            uri: A.into(),
            ..State::default()
        };
        let mut request = Some(1);
        let track = SpotifyUri::from_uri(A).unwrap();
        let next = SpotifyUri::from_uri(B).unwrap();
        assert!(!apply_event(
            &mut state,
            &mut request,
            PlayerEvent::Unavailable {
                play_request_id: 1,
                track_id: next.clone(),
            }
        ));
        assert!(state.playing && state.error.is_empty());
        assert!(!apply_event(
            &mut state,
            &mut request,
            PlayerEvent::EndOfTrack {
                play_request_id: 1,
                track_id: track,
            }
        ));
        assert!(state.playing);
        apply_event(
            &mut state,
            &mut request,
            PlayerEvent::PlayRequestIdChanged { play_request_id: 2 },
        );
        apply_event(
            &mut state,
            &mut request,
            PlayerEvent::Loading {
                play_request_id: 2,
                track_id: next.clone(),
                position_ms: 0,
            },
        );
        assert!(state.loading);
        apply_event(
            &mut state,
            &mut request,
            PlayerEvent::Unavailable {
                play_request_id: 2,
                track_id: next.clone(),
            },
        );
        assert!(state.loading && !state.error.is_empty());
        apply_event(
            &mut state,
            &mut request,
            PlayerEvent::Stopped {
                play_request_id: 2,
                track_id: next,
            },
        );
        assert!(!state.playing && !state.loading);
    }
    #[test]
    fn playback_pairing_only_accepts_the_signed_in_account() {
        let mut credential = Credentials {
            username: Some("signed-in-user".into()),
            auth_type: AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS,
            auth_data: vec![1, 2, 3],
        };
        assert!(playback_credential_for(&credential, "signed-in-user"));
        assert!(!playback_credential_for(&credential, "another-user"));
        assert!(!playback_credential_for(&credential, ""));
        credential.auth_type = AuthenticationType::AUTHENTICATION_SPOTIFY_TOKEN;
        assert!(!playback_credential_for(&credential, "signed-in-user"));
        credential.auth_type = AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS;
        credential.auth_data.clear();
        assert!(!playback_credential_for(&credential, "signed-in-user"));
    }

    #[test]
    fn invalid_stored_credentials_fall_back_to_access_token() {
        let login = Login {
            access_token: "test-token".into(),
            credential: "not-json".into(),
            device_id: "test".into(),
        };
        assert_eq!(
            credentials(&login).unwrap().auth_type,
            AuthenticationType::AUTHENTICATION_SPOTIFY_TOKEN
        );
        assert!(credentials(&Login {
            access_token: String::new(),
            ..login
        })
        .is_err());
        assert!(
            serde_json::from_str::<Command>(r#"{"command":"seek","milliseconds":-1}"#).is_err()
        );
    }
}
