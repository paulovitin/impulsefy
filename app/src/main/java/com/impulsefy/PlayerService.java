package com.impulsefy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

/** Owns local streaming independently of the activity and exposes native media controls. */
public final class PlayerService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public interface Listener { void onPlayerState(JSONObject state); }
    public final class LocalBinder extends Binder { public PlayerService getService() { return PlayerService.this; } }
    private static final int NOTIFICATION_ID = 41;
    private static final String CHANNEL = "impulsefy_playback";
    private static final String ACTION_PREFIX = "com.impulsefy.playback.";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final Object stateLock = new Object();
    private JSONObject state = emptyState();
    private Listener listener;
    private String pendingCredential;
    private NativePlayer player;
    private AudioManager audioManager;
    private MediaSession session;
    private boolean foreground;
    private volatile boolean hasFocus;
    private volatile boolean destroyed;
    private long mediaUpdatedAt, mediaPosition;
    private int mediaStatus = -1;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.MulticastLock discoveryLock;
    private long wakeAcquiredAt;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "impulsefy:playback");
        wakeLock.setReferenceCounted(false);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Reprodução de música", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        session = new MediaSession(this, "Impulsefy");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seek((int) Math.min(Integer.MAX_VALUE, Math.max(0, position))); }
            @Override public boolean onMediaButtonEvent(Intent intent) { return handleMediaButton(intent); }
        }, main);
        session.setPlaybackToLocal(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        session.setActive(true);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) session.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        publish(state);
        ensureForeground();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();
        if (intent != null) {
            String action = intent.getAction();
            if ((ACTION_PREFIX + "TOGGLE").equals(action)) toggle();
            else if ((ACTION_PREFIX + "NEXT").equals(action)) next();
            else if ((ACTION_PREFIX + "PREVIOUS").equals(action)) previous();
            else if (Intent.ACTION_MEDIA_BUTTON.equals(action)) handleMediaButton(intent);
        }
        // Never resurrect a killed service and start music without a user action.
        return START_NOT_STICKY;
    }

    public void setListener(Listener next) {
        onMain(() -> { listener = next; if (next != null) { JSONObject value = snapshot(); if (pendingCredential != null) { put(value, "credential", pendingCredential); pendingCredential = null; } next.onPlayerState(value); } });
    }

    public void connect(String accessToken, String credentialJson, String deviceId) {
        onMain(() -> {
            if (destroyed) return;
            startService(new Intent(this, PlayerService.class));
            ensureForeground();
            if (player != null) player.close();
            abandonFocus(); pendingCredential = null;
            session.setActive(true);
            JSONObject connecting = emptyState();
            put(connecting, "loading", true); put(connecting, "deviceId", deviceId == null ? "" : deviceId);
            publish(connecting);
            try {
                if (discoveryLock == null) {
                    discoveryLock = getSystemService(WifiManager.class).createMulticastLock("impulsefy:pairing");
                    discoveryLock.setReferenceCounted(false);
                }
                discoveryLock.acquire();
                player = new NativePlayer(this, new NativePlayer.Callback() {
                    @Override public void onState(JSONObject value) { publish(value); }
                    @Override public boolean requestAudioFocus() { return acquireFocus(); }
                    @Override public void onAudioError(String message) { pause(); error(message); }
                });
                JSONObject command = command("connect");
                put(command, "access_token", accessToken == null ? "" : accessToken);
                put(command, "credential", credentialJson == null ? "" : credentialJson);
                put(command, "device_id", deviceId == null ? "" : deviceId);
                send(command);
            } catch (RuntimeException | LinkageError failure) {
                player = null;
                error("O mecanismo de áudio não iniciou: " + failure.getMessage());
            }
        });
    }

    public void play(List<String> uris, int index) {
        JSONArray queue = new JSONArray(uris);
        onMain(() -> {
            if (!acquireFocus()) { error("O áudio está sendo usado. Tente novamente após a chamada."); return; }
            if (player != null) player.setVolume(1f);
            JSONObject request = command("load");
            put(request, "uris", queue); put(request, "index", index);
            send(request);
        });
    }
    public void toggle() { onMain(() -> { if (snapshot().optBoolean("playing") || snapshot().optBoolean("loading")) pause(); else resume(); }); }
    public void next() { onMain(() -> send(command("next"))); }
    public void previous() { onMain(() -> send(command("previous"))); }
    public void seek(int milliseconds) { onMain(() -> { JSONObject request = command("seek"); put(request, "milliseconds", Math.max(0, milliseconds)); send(request); }); }

    private void resume() {
        onMain(() -> {
            if (!acquireFocus()) { error("O áudio está sendo usado. Tente novamente após a chamada."); return; }
            if (player != null) player.setVolume(1f);
            send(command("play"));
        });
    }
    private void pause() { onMain(() -> { if (player != null) player.muteOutput(); send(command("pause")); abandonFocus(); }); }

    public void logout() {
        onMain(() -> {
            if (player != null) { player.close(); player = null; }
            abandonFocus();
            pendingCredential = null;
            session.setActive(false);
            publish(emptyState());
            stopForeground(true); foreground = false;
            stopSelf();
        });
    }

    public JSONObject snapshot() {
        synchronized (stateLock) {
            try { return new JSONObject(state.toString()); }
            catch (JSONException ignored) { return emptyState(); }
        }
    }

    @SuppressWarnings("deprecation")
    private synchronized boolean acquireFocus() {
        if (destroyed) return false;
        if (hasFocus) return true;
        hasFocus = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

        return hasFocus;
    }

    @SuppressWarnings("deprecation")
    private synchronized void abandonFocus() {
        audioManager.abandonAudioFocus(this);
        hasFocus = false;
    }

    @Override public void onAudioFocusChange(int change) {
        onMain(() -> {
            if (change == AudioManager.AUDIOFOCUS_GAIN) { hasFocus = true; if (player != null) player.setVolume(1f); }
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { if (player != null) player.setVolume(0.2f); }
            else { hasFocus = false; pause(); }
        });
    }

    private void send(JSONObject command) {
        if (player == null) {
            if (!"pause".equals(command.optString("command"))) error("Conecte sua conta Spotify para reproduzir.");
            return;
        }
        try { player.command(command); }
        catch (RuntimeException failure) { error("Falha no player: " + failure.getMessage()); }
    }

    private void publish(JSONObject value) {
        if (destroyed) return;
        JSONObject publicState;
        try { publicState = new JSONObject(value.toString()); }
        catch (JSONException ignored) { return; }
        // Credential is delivered once to the activity's encrypted store, never metadata or notifications.
        if (value.has("credential")) pendingCredential = value.optString("credential");
        publicState.remove("credential");
        JSONObject previous;
        synchronized (stateLock) { previous = state; state = publicState; }
        if (discoveryLock != null && discoveryLock.isHeld() && (publicState.optBoolean("connected") || !publicState.optBoolean("loading"))) discoveryLock.release();
        updateMediaSession(publicState, previous);
        boolean active = publicState.optBoolean("playing") || publicState.optBoolean("loading");
        long now = SystemClock.elapsedRealtime();
        if (active && (!wakeLock.isHeld() || now - wakeAcquiredAt > 10 * 60_000L)) {
            wakeLock.acquire(30 * 60_000L); wakeAcquiredAt = now;
        } else if (!active && wakeLock.isHeld()) wakeLock.release();
        if (hasFocus && !publicState.optBoolean("playing") && !publicState.optBoolean("loading")) abandonFocus();
        if (foreground && changed(publicState, previous, "title", "artist", "uri", "playing", "loading", "connected", "error")) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(publicState));
        if (listener != null) {
            if (pendingCredential != null) { put(value, "credential", pendingCredential); pendingCredential = null; }
            listener.onPlayerState(value);
        }
    }

    private void error(String message) {
        JSONObject next = snapshot();
        put(next, "error", message); put(next, "loading", false);
        publish(next);
    }

    private void updateMediaSession(JSONObject value, JSONObject previous) {
        int status = value.optBoolean("loading") ? PlaybackState.STATE_BUFFERING : value.optBoolean("playing") ? PlaybackState.STATE_PLAYING : value.optString("uri").isEmpty() ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED;
        long now = SystemClock.elapsedRealtime();
        long position = value.optLong("positionMs");
        long predictedPosition = mediaPosition + (mediaStatus == PlaybackState.STATE_PLAYING ? now - mediaUpdatedAt : 0);
        boolean correctPosition = Math.abs(position - predictedPosition) > 1200;
        boolean metadataChanged = mediaUpdatedAt == 0 || changed(value, previous, "uri", "title", "artist", "coverUrl", "durationMs");
        if (status != mediaStatus || correctPosition || metadataChanged || changed(value, previous, "error")) {
            PlaybackState.Builder playback = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                    .setState(status, value.optLong("positionMs"), value.optBoolean("playing") ? 1f : 0f, SystemClock.elapsedRealtime());
            if (!value.optString("error").isEmpty()) playback.setErrorMessage(value.optString("error"));
            session.setPlaybackState(playback.build());
            mediaUpdatedAt = now; mediaPosition = position; mediaStatus = status;
        }
        if (metadataChanged) session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, value.optString("title", "Impulsefy"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, value.optString("artist"))
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, value.optString("uri"))
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, value.optString("coverUrl"))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, value.optLong("durationMs")).build());
    }

    private static boolean changed(JSONObject current, JSONObject previous, String... keys) {
        for (String key : keys) if (!current.optString(key).equals(previous.optString(key))) return true;
        return false;
    }

    private Notification notification(JSONObject value) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        boolean playing = value.optBoolean("playing");
        String title = value.optString("title");
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "Impulsefy" : title)
                .setContentText(value.optString("artist").isEmpty() ? value.optBoolean("connected") ? "Spotify conectado" : "Player Spotify" : value.optString("artist"))
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "Anterior", action("PREVIOUS"))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pausar" : "Reproduzir", action("TOGGLE"))
                .addAction(android.R.drawable.ic_media_next, "Próxima", action("NEXT"))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        PendingIntent launch = session.getController().getSessionActivity();
        if (launch != null) builder.setContentIntent(launch);
        return builder.build();
    }

    private PendingIntent action(String name) {
        Intent intent = new Intent(this, PlayerService.class).setAction(ACTION_PREFIX + name);
        return PendingIntent.getService(this, name.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureForeground() {
        if (!foreground) { startForeground(NOTIFICATION_ID, notification(snapshot())); foreground = true; }
    }

    private boolean handleMediaButton(Intent intent) {
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY: resume(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP: pause(); return true;
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: toggle(); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT: next(); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: previous(); return true;
            default: return false;
        }
    }

    private void onMain(Runnable task) { if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else main.post(task); }
    private static JSONObject command(String name) { JSONObject json = new JSONObject(); put(json, "command", name); return json; }
    private static void put(JSONObject json, String key, Object value) { try { json.put(key, value); } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); } }
    private static JSONObject emptyState() {
        JSONObject json = new JSONObject();
        for (String key : new String[]{"connected", "playing", "loading"}) put(json, key, false);
        for (String key : new String[]{"title", "artist", "uri", "coverUrl", "error", "deviceId"}) put(json, key, "");
        put(json, "positionMs", 0); put(json, "durationMs", 0); return json;
    }

    @Override public void onDestroy() {
        destroyed = true;
        listener = null;
        if (player != null) { player.close(); player = null; }
        abandonFocus();
        if (wakeLock.isHeld()) wakeLock.release();
        if (discoveryLock != null && discoveryLock.isHeld()) discoveryLock.release();
        unregisterReceiver(noisy);
        session.setActive(false); session.release();
        main.removeCallbacksAndMessages(null);
        stopForeground(true); foreground = false;
        super.onDestroy();
    }
}
