package com.impulsefy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Spotify public-client PKCE. Passwords and PKCE verifiers never pass through the relay. */
public final class AuthManager implements AutoCloseable {
    private static final String TOKEN_KEY = "spotify_oauth";
    private static final String PLAYBACK_KEY = "spotify_playback";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = "streaming user-read-private user-library-read playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state user-read-recently-played";
    private final Object lock = new Object();
    private final Object refreshLock = new Object();
    private final SecureStore store;
    private final String relayBase;
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
        final String id, secret, verifier, state, clientId, redirectUri;
        final long expiresAt;
        Pairing(JSONObject value, String verifier, String state, String clientId, String redirectUri) throws Exception {
            id = value.getString("id");
            secret = value.getString("pollSecret");
            expiresAt = value.getLong("expiresAt");
            this.verifier = verifier;
            this.state = state;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            if (!id.matches("[A-Za-z0-9_-]{32}") || !secret.matches("[A-Za-z0-9_-]{43}")
                    || expiresAt <= System.currentTimeMillis() || expiresAt > System.currentTimeMillis() + 10 * 60_000) {
                throw new Exception("O relay retornou um pareamento inválido.");
            }
        }
    }

    public AuthManager(Context context, String relayBase) {
        store = new SecureStore(context);
        this.relayBase = relayBase == null ? "" : relayBase.trim().replaceAll("/+$", "");
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
        Pairing created = null;
        try {
            validateOrigin(relayBase);
            Http.Response configuration = Http.request("GET", relayBase + "/v1/config", null, null, null);
            requireRelay(configuration, 200);
            JSONObject config = configuration.json();
            String clientId = config.getString("clientId");
            String redirectUri = config.getString("redirectUri");
            if (!clientId.matches("[a-fA-F0-9]{32}") || !redirectUri.equals(relayBase + "/callback") || !hasScopes(config.getString("scopes"))) {
                throw new Exception("Configuração inválida no relay. Confira o Client ID e o endereço HTTPS.");
            }
            String verifier = random();
            String state = random();
            String challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            JSONObject request = new JSONObject().put("state", state).put("challenge", challenge);
            if (!active(current)) return;
            Http.Response response = Http.request("POST", relayBase + "/v1/pair", request.toString(), "application/json", null);
            requireRelay(response, 201);
            JSONObject data = response.json();
            created = new Pairing(data, verifier, state, clientId, redirectUri);
            String url = data.getString("url");
            String code = data.getString("displayCode");
            if (!url.equals(relayBase + "/pair/" + created.id) || !code.matches("[A-F0-9]{4}-[A-F0-9]{4}")) throw new Exception("O relay retornou um QR inválido.");
            boolean accepted;
            synchronized (lock) {
                accepted = active(current);
                if (accepted) pairing = created;
            }
            if (!accepted) { deletePairing(created); return; }
            final Pairing flow = created;
            callback(current, () -> listener.onPairing(url, code, flow.expiresAt));
            schedulePoll(current, flow, listener, 2);
        } catch (Exception error) {
            if (created != null) deletePairing(created);
            fail(current, listener, safeMessage(error));
        }
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
            Http.Response response = Http.request("POST", relayBase + "/v1/pair/" + flow.id, null, null, flow.secret);
            if (!active(current)) return;
            if (response.status == 202) { schedulePoll(current, flow, listener, 2); return; }
            if (response.status == 429) { schedulePoll(current, flow, listener, response.retryAfterSeconds); return; }
            requireRelay(response, 200);
            JSONObject result = response.json();
            if (!flow.state.equals(result.optString("state"))) throw new Exception("A confirmação do login não corresponde a este carro. Gere outro QR.");
            if (result.has("error")) throw new Exception("O login foi cancelado no Spotify. Tente novamente.");
            String code = result.getString("code");
            if (code.isEmpty() || code.length() > 2048) throw new Exception("Spotify não retornou um código válido.");
            Http.Response exchange = Http.request("POST", TOKEN_URL, form("grant_type", "authorization_code", "client_id", flow.clientId,
                    "redirect_uri", flow.redirectUri, "code", code, "code_verifier", flow.verifier), "application/x-www-form-urlencoded", null);
            JSONObject fresh = decodeToken(exchange, flow.clientId, null);
            synchronized (lock) {
                if (!active(current)) return;
                store.put(TOKEN_KEY, fresh.toString());
                store.remove(PLAYBACK_KEY);
                token = fresh;
                revision++;
                pairing = null;
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
            Pairing old = pairing;
            pairing = null;
            if (old != null) worker.execute(() -> deletePairing(old));
        }
        callback(current, () -> listener.onError(message));
    }

    public void cancel() {
        synchronized (lock) {
            attempt++;
            Pairing old = pairing;
            pairing = null;
            if (old != null && !worker.isShutdown()) worker.execute(() -> deletePairing(old));
        }
    }

    private void deletePairing(Pairing flow) {
        try { Http.request("DELETE", relayBase + "/v1/pair/" + flow.id, null, null, flow.secret); }
        catch (Exception ignored) { /* The relay independently expires the session. */ }
    }

    /** Blocking: call only from background threads. Serializes refresh and protects against logout races. */
    public String accessToken() throws Exception { return accessToken(null); }

    String refreshAfterUnauthorized(String rejectedToken) throws Exception { return accessToken(rejectedToken); }

    private String accessToken(String rejectedToken) throws Exception {
        synchronized (refreshLock) {
            final JSONObject previous;
            final long currentRevision;
            synchronized (lock) {
                if (closed || token == null) throw new Exception("Entre na sua conta Spotify para continuar.");
                previous = new JSONObject(token.toString());
                currentRevision = revision;
                String currentAccess = previous.optString("access_token");
                if (rejectedToken == null && previous.optLong("expires_at") > System.currentTimeMillis() + 90_000 && !currentAccess.isEmpty()) return currentAccess;
                if (rejectedToken != null && !rejectedToken.equals(currentAccess)) return currentAccess;
            }
            Http.Response response = Http.request("POST", TOKEN_URL, form("grant_type", "refresh_token", "client_id", previous.getString("client_id"),
                    "refresh_token", previous.getString("refresh_token")), "application/x-www-form-urlencoded", null);
            if (response.status == 400 || response.status == 401) {
                String reason = response.json().optString("error");
                if ("invalid_grant".equals(reason)) {
                    synchronized (lock) {
                        if (revision == currentRevision) { token = null; revision++; store.remove(TOKEN_KEY); store.remove(PLAYBACK_KEY); }
                    }
                }
            }
            JSONObject fresh = decodeToken(response, previous.getString("client_id"), previous);
            synchronized (lock) {
                if (closed || revision != currentRevision || token == null) throw new Exception("A sessão mudou. Entre novamente para continuar.");
                store.put(TOKEN_KEY, fresh.toString());
                token = fresh;
                return fresh.getString("access_token");
            }
        }
    }

    private static JSONObject decodeToken(Http.Response response, String clientId, JSONObject previous) throws Exception {
        if (response.status == 429) throw new SpotifyApi.ApiException(429, response.retryAfterSeconds, "Spotify pediu uma pausa. Tente novamente em " + response.retryAfterSeconds + " segundos.");
        if (response.status < 200 || response.status >= 300) {
            if (response.status == 400 || response.status == 401) throw new Exception("Spotify recusou a sessão. Entre novamente e confira o Client ID do relay.");
            throw new Exception("Não foi possível renovar a sessão Spotify agora. Tente novamente.");
        }
        JSONObject data = response.json();
        String access = data.optString("access_token");
        String refresh = data.optString("refresh_token", previous == null ? "" : previous.optString("refresh_token"));
        String scope = data.optString("scope", previous == null ? SCOPES : previous.optString("scope"));
        long expires = data.optLong("expires_in", 0);
        if (access.isEmpty() || refresh.isEmpty() || !"Bearer".equalsIgnoreCase(data.optString("token_type", "Bearer")) || expires <= 0 || expires > 86400 || !hasScopes(scope)) {
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

    private static void validateOrigin(String value) throws Exception {
        try {
            URI url = new URI(value);
            boolean loopback = "127.0.0.1".equals(url.getHost()) || "[::1]".equals(url.getHost()) || "::1".equals(url.getHost());
            if ((!"https".equals(url.getScheme()) && !("http".equals(url.getScheme()) && loopback)) || url.getHost() == null
                    || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null || !url.getPath().isEmpty()) throw new Exception();
        } catch (Exception error) { throw new Exception("Configure o endereço HTTPS do relay para gerar o QR."); }
    }

    private static boolean hasScopes(String scope) {
        Set<String> granted = new HashSet<>(Arrays.asList(scope.split(" +")));
        return granted.containsAll(Arrays.asList(SCOPES.split(" ")));
    }

    private static void requireRelay(Http.Response response, int expected) throws Exception {
        if (response.status == expected) return;
        if (response.status == 404 || response.status == 410) throw new Exception("Este QR expirou ou foi usado. Gere outro para conectar.");
        if (response.status == 429) throw new Exception("Muitas tentativas. Aguarde " + response.retryAfterSeconds + " segundos e gere outro QR.");
        throw new Exception("O relay não está disponível. Confira o endereço e tente novamente.");
    }

    private static String safeMessage(Exception error) {
        // JSON/transport errors can include input; only our explicit human messages leave this class.
        if (error instanceof org.json.JSONException || error instanceof java.security.GeneralSecurityException) return "Não foi possível concluir o login com segurança. Tente novamente.";
        String message = error.getMessage();
        return message != null && (message.startsWith("O ") || message.startsWith("A ") || message.startsWith("Este ") || message.startsWith("Spotify ")
                || message.startsWith("Não ") || message.startsWith("Configure ") || message.startsWith("Configuração ") || message.startsWith("Muitas "))
                ? message : "Não foi possível concluir o login. Tente novamente.";
    }

    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return encode(bytes); }
    private static String encode(byte[] bytes) { return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String form(String... values) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i += 2) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(values[i], "UTF-8")).append('=').append(URLEncoder.encode(values[i + 1], "UTF-8"));
        }
        return result.toString();
    }
}
