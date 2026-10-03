package com.impulsefy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Spotify device authorization. Device codes and tokens stay on the car. */
public final class AuthManager implements AutoCloseable {
    private static final String TOKEN_KEY = "spotify_oauth";
    private static final String PLAYBACK_KEY = "spotify_playback";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    static final String WEB_CLIENT_ID = "d420a117a32841c2b3474932e49fb54b";
    static final String PLAYBACK_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd";
    private static final String SCOPES = "user-read-private user-library-read playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state user-read-recently-played";
    private final Object lock = new Object();
    private final Object refreshLock = new Object();
    private final SecureStore store;
    interface Transport { Http.Response request(String method, String url, String body, String type, String bearer) throws Exception; }
    private final Transport transport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(2);
    private JSONObject token;
    private Pairing pairing;
    private long attempt;
    private long revision;
    private boolean closed;

    public interface Listener {
        void onPairing(String url, String displayCode, long expiresAt);
        void onConnected();
        void onError(String message);
    }

    private static final class Pairing {
        final String secret, url, code;
        final long expiresAt;
        long interval;
        Pairing(JSONObject data) throws Exception {
            secret = data.getString("device_code");
            code = data.getString("user_code");
            if (secret.isEmpty() || secret.length() > 4096 || !code.matches("[A-Za-z0-9-]{4,16}")
                    || !"https://spotify.com/pair".equals(data.getString("verification_uri"))) {
                throw new Exception("Spotify retornou um código de pareamento inválido.");
            }
            long seconds = data.getLong("expires_in");
            if (seconds <= 0 || seconds > 3600) throw new Exception("Spotify retornou um prazo inválido.");
            expiresAt = System.currentTimeMillis() + seconds * 1000;
            interval = Math.max(5, Math.min(60, data.optLong("interval", 5)));
            url = "https://spotify.com/pair?code=" + code;
        }
    }

    public AuthManager(Context context) { this(context, Http::request); }

    AuthManager(Context context, Transport transport) {
        store = new SecureStore(context);
        this.transport = transport;
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.setRemoveOnCancelPolicy(true);
        try {
            String stored = store.get(TOKEN_KEY);
            if (stored != null) {
                JSONObject parsed = new JSONObject(stored);
                if (parsed.getString("client_id").matches("[a-fA-F0-9]{32}") && !parsed.getString("refresh_token").isEmpty()) token = parsed;
            }
        } catch (Exception error) { store.remove(TOKEN_KEY); store.remove(PLAYBACK_KEY); }
    }

    public boolean isSignedIn() { synchronized (lock) { return token != null; } }

    public void start(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener obrigatório.");
        cancel();
        final long current;
        synchronized (lock) {
            if (closed) return;
            current = attempt;
            worker.execute(() -> begin(current, listener));
        }
    }

    private void begin(long current, Listener listener) {
        try {
            Http.Response response = transport.request("POST", "https://accounts.spotify.com/oauth2/device/authorize",
                    form("client_id", PLAYBACK_CLIENT_ID, "scope", "streaming"), "application/x-www-form-urlencoded", null);
            if (response.status != 200) throw new Exception("Não foi possível gerar o código no Spotify. Tente novamente.");
            Pairing flow = new Pairing(response.json());
            synchronized (lock) { if (!active(current)) return; pairing = flow; }
            callback(current, () -> listener.onPairing(flow.url, flow.code, flow.expiresAt));
            schedulePoll(current, flow, listener, flow.interval);
        } catch (Exception error) { fail(current, listener, safeMessage(error)); }
    }

    private void schedulePoll(long current, Pairing flow, Listener listener, long seconds) {
        synchronized (lock) {
            if (active(current)) worker.schedule(() -> poll(current, flow, listener), seconds, TimeUnit.SECONDS);
        }
    }

    private void poll(long current, Pairing flow, Listener listener) {
        if (!active(current)) return;
        if (System.currentTimeMillis() >= flow.expiresAt) { fail(current, listener, "Este QR expirou. Gere outro para conectar."); return; }
        try {
            Http.Response response = transport.request("POST", TOKEN_URL, form("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id", PLAYBACK_CLIENT_ID, "device_code", flow.secret), "application/x-www-form-urlencoded", null);
            if (!active(current)) return;
            if (response.status == 429) { schedulePoll(current, flow, listener, Math.max(flow.interval, response.retryAfterSeconds)); return; }
            if (response.status >= 500) { schedulePoll(current, flow, listener, Math.max(flow.interval, 10)); return; }
            JSONObject result = response.json();
            String error = result.optString("error");
            if ("authorization_pending".equals(error) || "slow_down".equals(error)) {
                if ("slow_down".equals(error)) flow.interval += 5;
                schedulePoll(current, flow, listener, flow.interval); return;
            }
            if ("access_denied".equals(error)) throw new Exception("O login foi cancelado no Spotify. Tente novamente.");
            if ("expired_token".equals(error)) throw new Exception("Este QR expirou. Gere outro para conectar.");
            JSONObject fresh = decodeToken(response, PLAYBACK_CLIENT_ID, null, true).put("native_session", true);
            synchronized (lock) {
                if (!active(current)) return;
                store.put(TOKEN_KEY, fresh.toString()); store.remove(PLAYBACK_KEY);
                token = fresh; revision++; pairing = null;
            }
            callback(current, listener::onConnected);
        } catch (Exception error) { fail(current, listener, safeMessage(error)); }
    }

    private boolean active(long current) { synchronized (lock) { return !closed && attempt == current; } }

    private void callback(long current, Runnable action) {
        main.post(() -> { if (active(current)) action.run(); });
    }

    private void fail(long current, Listener listener, String message) {
        synchronized (lock) {
            if (!active(current)) return;
            pairing = null;
        }
        callback(current, () -> listener.onError(message));
    }

    public void cancel() {
        synchronized (lock) {
            attempt++;
            pairing = null;
        }
    }

    /** Blocking: call only from background threads. Serializes refresh and protects against logout races. */
    public String accessToken() throws Exception { return accessToken(null, false); }

    public String playbackAccessToken() throws Exception { return accessToken(null, usesPlaybackOAuth() && !usesNativeCatalog()); }
    public boolean usesNativeCatalog() { synchronized (lock) { return token != null && token.optBoolean("native_session"); } }
    public boolean usesPlaybackOAuth() { synchronized (lock) { return token != null && (token.has("playback") || token.optBoolean("native_session")); } }
    public String playbackUsername() { synchronized (lock) { return token != null && token.has("playback") ? token.optJSONObject("playback").optString("username") : ""; } }

    String refreshAfterUnauthorized(String rejectedToken) throws Exception { return accessToken(rejectedToken, false); }

    private String accessToken(String rejectedToken, boolean playback) throws Exception {
        synchronized (refreshLock) {
            final JSONObject previous;
            final long currentRevision;
            synchronized (lock) {
                if (closed || token == null) throw new Exception("Entre na sua conta Spotify para continuar.");
                previous = new JSONObject((playback ? token.getJSONObject("playback") : token).toString());
                currentRevision = revision;
                String currentAccess = previous.optString("access_token");
                if (rejectedToken == null && previous.optLong("expires_at") > System.currentTimeMillis() + 90_000 && !currentAccess.isEmpty()) return currentAccess;
                if (rejectedToken != null && !rejectedToken.equals(currentAccess)) return currentAccess;
            }
            Http.Response response = transport.request("POST", TOKEN_URL, form("grant_type", "refresh_token", "client_id", previous.getString("client_id"),
                    "refresh_token", previous.getString("refresh_token")), "application/x-www-form-urlencoded", null);
            if (response.status == 400 || response.status == 401) {
                String reason = response.json().optString("error");
                if ("invalid_grant".equals(reason)) {
                    synchronized (lock) {
                        if (revision == currentRevision) { token = null; revision++; store.remove(TOKEN_KEY); store.remove(PLAYBACK_KEY); }
                    }
                }
            }
            JSONObject fresh = decodeToken(response, previous.getString("client_id"), previous, playback || previous.optBoolean("native_session"));
            if (previous.optBoolean("native_session")) fresh.put("native_session", true);
            synchronized (lock) {
                if (closed || revision != currentRevision || token == null) throw new Exception("A sessão mudou. Entre novamente para continuar.");
                JSONObject updated;
                if (playback) { updated = new JSONObject(token.toString()); fresh.put("username", previous.getString("username")); updated.put("playback", fresh); }
                else { updated = fresh; if (token.has("playback")) updated.put("playback", token.getJSONObject("playback")); }
                store.put(TOKEN_KEY, updated.toString());
                token = updated;
                return fresh.getString("access_token");
            }
        }
    }

    private static JSONObject decodeToken(Http.Response response, String clientId, JSONObject previous, boolean playback) throws Exception {
        if (response.status == 429) throw new SpotifyApi.ApiException(429, response.retryAfterSeconds, "Spotify pediu uma pausa. Tente novamente em " + response.retryAfterSeconds + " segundos.");
        if (response.status < 200 || response.status >= 300) {
            if (response.status == 400 || response.status == 401) throw new Exception("Spotify recusou a sessão. Entre novamente para autorizar o aplicativo.");
            throw new Exception("Não foi possível renovar a sessão Spotify agora. Tente novamente.");
        }
        JSONObject data = response.json();
        String access = data.optString("access_token");
        String refresh = data.optString("refresh_token", previous == null ? "" : previous.optString("refresh_token"));
        String scope = data.optString("scope", previous == null ? (playback ? "streaming" : SCOPES) : previous.optString("scope"));
        long expires = data.optLong("expires_in", 0);
        if (access.isEmpty() || refresh.isEmpty() || !"Bearer".equalsIgnoreCase(data.optString("token_type", "Bearer")) || expires <= 0 || expires > 86400 || !(playback ? Arrays.asList(scope.split(" +")).contains("streaming") : hasScopes(scope))) {
            throw new Exception("Spotify não retornou todas as permissões necessárias. Entre novamente.");
        }
        return new JSONObject().put("client_id", clientId).put("access_token", access).put("refresh_token", refresh)
                .put("expires_at", System.currentTimeMillis() + expires * 1000).put("scope", scope);
    }

    public String playbackCredential() throws Exception {
        synchronized (lock) { return token == null || closed ? null : store.get(PLAYBACK_KEY); }
    }

    public void savePlaybackCredential(String json) throws Exception {
        synchronized (lock) {
            if (token == null || closed) throw new Exception("A sessão Spotify foi encerrada.");
            store.put(PLAYBACK_KEY, json);
        }
    }

    public void logout() {
        cancel();
        synchronized (lock) { token = null; revision++; store.remove(TOKEN_KEY); store.remove(PLAYBACK_KEY); }
    }

    @Override public void close() {
        cancel();
        synchronized (lock) { closed = true; revision++; worker.shutdown(); }
    }

    private static boolean hasScopes(String scope) {
        Set<String> granted = new HashSet<>(Arrays.asList(scope.split(" +")));
        return granted.containsAll(Arrays.asList(SCOPES.split(" ")));
    }

    private static String safeMessage(Exception error) {
        // JSON/transport errors can include input; only our explicit human messages leave this class.
        if (error instanceof org.json.JSONException || error instanceof java.security.GeneralSecurityException) return "Não foi possível concluir o login com segurança. Tente novamente.";
        String message = error.getMessage();
        return message != null && (message.startsWith("O ") || message.startsWith("A ") || message.startsWith("Este ") || message.startsWith("Spotify ")
                || message.startsWith("Não ") || message.startsWith("Configure ") || message.startsWith("Configuração ") || message.startsWith("Muitas "))
                ? message : "Não foi possível concluir o login. Tente novamente.";
    }

    private static String form(String... values) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i += 2) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(values[i], "UTF-8")).append('=').append(URLEncoder.encode(values[i + 1], "UTF-8"));
        }
        return result.toString();
    }
}
