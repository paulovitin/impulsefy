package com.impulsefy;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class SpotifyApi {
    private final AuthManager auth;
    interface SessionReader { JSONObject read(String path) throws Exception; }
    private final SessionReader reader;

    public static final class ApiException extends Exception {
        public final int status;
        public final long retryAfterSeconds;

        public ApiException(int status, long retryAfterSeconds, String message) {
            super(message);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    SpotifyApi(AuthManager auth, SessionReader reader) { this.auth = auth; this.reader = reader; }

    public JSONObject get(String relativePath) throws Exception { return request("GET", relativePath, null); }

    /** Blocking: call from a worker, never from the main/UI thread. Paths are relative to /v1/. */
    public JSONObject request(String method, String relativePath, JSONObject body) throws Exception {
        if (!method.matches("GET|POST|PUT|DELETE")) throw new IllegalArgumentException("Método inválido.");
        URI path;
        try { path = new URI(relativePath); }
        catch (Exception error) { throw new IllegalArgumentException("Caminho Spotify inválido."); }
        if (path.isAbsolute() || path.getRawAuthority() != null || path.getFragment() != null || path.getRawPath() == null
                || !path.getRawPath().matches("/?[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/*")) {
            throw new IllegalArgumentException("Use um caminho relativo da API Spotify.");
        }
        if (auth.usesNativeCatalog()) {
            if (!"GET".equals(method)) throw new IllegalArgumentException("Operação indisponível.");
            return reader.read(relativePath.startsWith("/") ? relativePath : "/" + relativePath);
        }
        String url = "https://api.spotify.com/v1/" + (relativePath.startsWith("/") ? relativePath.substring(1) : relativePath);
        String access = auth.accessToken();
        Http.Response response = Http.request(method, url, body == null ? null : body.toString(), "application/json", access);
        if (response.status == 401) {
            access = auth.refreshAfterUnauthorized(access);
            response = Http.request(method, url, body == null ? null : body.toString(), "application/json", access);
        }
        if (response.status >= 200 && response.status < 300) return response.json();
        String message;
        if (response.status == 429) message = "Spotify pediu uma pausa. Tente novamente em " + response.retryAfterSeconds + " segundos.";
        else if (response.status == 401) message = "Sua sessão Spotify expirou. Entre novamente.";
        else if (response.status == 403) message = "Spotify não permitiu esta ação. Confira a conta, o plano e as permissões do aplicativo.";
        else if (response.status == 404) message = "Este conteúdo ou dispositivo não está disponível no Spotify.";
        else message = "Spotify está indisponível agora (" + response.status + "). Tente novamente.";
        throw new ApiException(response.status, response.retryAfterSeconds, message);
    }
}

/** Shared transport deliberately refuses redirects so credentials stay on the requested origin. */
final class Http {
    static final class Response {
        final int status;
        final String body;
        final long retryAfterSeconds;
        Response(int status, String body, long retryAfterSeconds) {
            this.status = status;
            this.body = body;
            this.retryAfterSeconds = retryAfterSeconds;
        }
        JSONObject json() throws Exception {
            if (body.trim().isEmpty()) return new JSONObject();
            try { return new JSONObject(body); }
            catch (Exception error) { throw new Exception("O serviço retornou uma resposta inválida."); }
        }
    }

    static Response request(String method, String url, String body, String contentType, String bearer) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType + "; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            long retry = status == 429 ? 30 : 0;
            if (status == 429) {
                try { retry = Math.max(1, Math.min(3600, Long.parseLong(connection.getHeaderField("Retry-After")))); }
                catch (Exception ignored) { }
            }
            InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (source != null) {
                try (InputStream in = source) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = in.read(buffer)) != -1) {
                        if (bytes.size() + length > 4 * 1024 * 1024) throw new Exception("Resposta do serviço muito grande.");
                        bytes.write(buffer, 0, length);
                    }
                }
            }
            return new Response(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8), retry);
        } catch (java.io.IOException error) {
            throw new Exception("Não foi possível conectar. Confira a internet e tente novamente.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
