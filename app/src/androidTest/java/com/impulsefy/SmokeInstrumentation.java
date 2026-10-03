package com.impulsefy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;

/** Runs on the real Android VM, Keystore and JNI loader. No production test backdoors. */
public final class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        AuthManager auth = null;
        try {
            verifyDeviceAuthorization();
            verifySeparatePlaybackGrant();
            System.loadLibrary("impulsefy");
            CountDownLatch nativeReply = new CountDownLatch(1);
            AtomicReference<String> audioError = new AtomicReference<>();
            try (NativePlayer output = new NativePlayer(getTargetContext(), new NativePlayer.Callback() {
                public void onState(JSONObject state) { if (!state.optBoolean("connected") && !state.optString("error").isEmpty()) nativeReply.countDown(); }
                public boolean requestAudioFocus() { return true; }
                public void onAudioError(String message) { audioError.set(message); }
            })) {
                output.command(new JSONObject().put("command", "pause"));
                require(nativeReply.await(10, TimeUnit.SECONDS), "Rust -> Java state callback failed");
                boolean disconnectedRead = false;
                try { output.read("/me"); }
                catch (Exception expected) { disconnectedRead = expected.getMessage().startsWith("Conectando ao Spotify"); }
                require(disconnectedRead, "catalog JNI did not report disconnected session");
                // Silence checks the actual PCM sink without needing a Spotify token or audible tone.
                java.lang.reflect.Method start = NativePlayer.class.getDeclaredMethod("startAudio");
                java.lang.reflect.Method write = NativePlayer.class.getDeclaredMethod("writePcm", short[].class);
                java.lang.reflect.Method stop = NativePlayer.class.getDeclaredMethod("stopAudio");
                start.setAccessible(true); write.setAccessible(true); stop.setAccessible(true);
                require((Boolean) start.invoke(output), "AudioTrack start failed");
                require((Boolean) write.invoke(output, (Object) new short[4096]), "PCM write failed");
                require((Boolean) stop.invoke(output), "AudioTrack pause/flush failed");
                require((Boolean) start.invoke(output), "AudioTrack resume failed");
                require((Boolean) stop.invoke(output), "AudioTrack second stop failed");
                waitForIdleSync();
                require(audioError.get() == null, "unexpected audio error: " + audioError.get());
                // Simulate the platform invalidating its output. A failed write must
                // retire that object so the next user retry creates a working track.
                java.lang.reflect.Field audioField = NativePlayer.class.getDeclaredField("audio");
                audioField.setAccessible(true);
                android.media.AudioTrack failed = (android.media.AudioTrack) audioField.get(output);
                failed.release();
                require(!(Boolean) write.invoke(output, (Object) new short[4096]), "invalid output accepted PCM");
                require(audioField.get(output) == null, "failed AudioTrack retained for retry");
                waitForIdleSync();
                require(audioError.getAndSet(null) != null, "output failure was not reported");
                require((Boolean) start.invoke(output), "AudioTrack recreation failed");
                require(audioField.get(output) != failed, "retry reused the failed AudioTrack");
                require((Boolean) write.invoke(output, (Object) new short[4096]), "recreated AudioTrack rejected PCM");
                require((Boolean) stop.invoke(output), "recreated AudioTrack did not stop");
            }
            verifyFocusGainWhileLoading();
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("demo", true));
            waitForIdleSync();
            Activity screen = activity;
            AtomicReference<String> visible = new AtomicReference<>("");
            runOnMainSync(() -> visible.set(text(screen.getWindow().getDecorView())));
            require(visible.get().contains("PRÉVIA") || visible.get().contains("Prévia"), "preview must identify itself");
            require(visible.get().contains("Buscar"), "search navigation missing");
            runOnMainSync(() -> clickText(screen.getWindow().getDecorView(), "Buscar"));
            awaitText(screen, "Encontre seu som");
            runOnMainSync(() -> clickText(screen.getWindow().getDecorView(), "Biblioteca"));
            awaitText(screen, "Sua biblioteca");
            runOnMainSync(() -> {
                android.widget.ListView list = (android.widget.ListView) find(screen.getWindow().getDecorView(), android.widget.ListView.class);
                list.performItemClick(list.getChildAt(0), 0, 0);
            });
            awaitText(screen, "Artista de exemplo");
            runOnMainSync(screen::finish);
            activity = null;

            // Exercise the actual QR view with a deterministic Spotify device response.
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Field authField = MainActivity.class.getDeclaredField("auth");
            authField.setAccessible(true);
            ((AuthManager) authField.get(activity)).close();
            authField.set(activity, new AuthManager(getTargetContext(), (method, endpoint, body, type, bearer) -> {
                if (endpoint.endsWith("/device/authorize")) return deviceResponse();
                require(body.contains("device_code=private-device-code"), "device code missing in token poll");
                return new Http.Response(400, "{\"error\":\"access_denied\"}", 0);
            }));
            Activity login = activity;
            runOnMainSync(() -> clickText(login.getWindow().getDecorView(), "Conectar Spotify"));
            awaitText(login, "Gerar outro QR");
            AtomicReference<Bitmap> rendered = new AtomicReference<>();
            runOnMainSync(() -> {
                ImageView qr = (ImageView) find(login.getWindow().getDecorView(), ImageView.class);
                Bitmap image = Bitmap.createBitmap(qr.getWidth(), qr.getHeight(), Bitmap.Config.ARGB_8888);
                qr.draw(new Canvas(image)); rendered.set(image);
            });
            Bitmap qr = rendered.get();
            int[] pixels = new int[qr.getWidth() * qr.getHeight()];
            qr.getPixels(pixels, 0, qr.getWidth(), 0, 0, qr.getWidth(), qr.getHeight());
            String url = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(qr.getWidth(), qr.getHeight(), pixels)))).getText();
            qr.recycle();
            require("https://spotify.com/pair?code=ABC123".equals(url), "QR does not point to Spotify pairing");
            require(!url.contains("secret") && !url.contains("verifier"), "secret present in QR");
            // Let the OS activity transition finish before recording the rendered surface.
            Thread.sleep(600);
            waitForIdleSync();
            Bitmap capture = getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream file = getTargetContext().openFileOutput("qr-smoke.png", 0)) {
                capture.compress(Bitmap.CompressFormat.PNG, 100, file);
            }
            capture.recycle();
            awaitText(login, "O login foi cancelado");
            auth = new AuthManager(getTargetContext());
            require(!auth.isSignedIn(), "declined consent created a session");
            runOnMainSync(login::finish);
            activity = null;

            // Exercise the real device Keystore without inventing a successful Spotify login.
            SecureStore secure = new SecureStore(getTargetContext());
            secure.put("smoke_only", "private-value");
            SecureStore reopened = new SecureStore(getTargetContext());
            require("private-value".equals(reopened.get("smoke_only")), "encrypted value did not survive reopen");
            String ciphertext = getTargetContext().getSharedPreferences("encrypted_credentials", 0).getString("smoke_only", "");
            require(!ciphertext.isEmpty() && !ciphertext.contains("private-value"), "value stored as plaintext");
            reopened.remove("smoke_only");
            require(reopened.get("smoke_only") == null, "secure deletion failed");
            boolean rejected = false;
            try { auth.savePlaybackCredential("late-result"); } catch (Exception expected) { rejected = true; }
            require(rejected, "signed-out session accepted a late playback credential");
            auth.logout();
            require(auth.playbackCredential() == null, "logout retained credential");
            result.putString("stream", "\nPASS: Android navigation, Rust JNI roundtrip, PCM AudioTrack lifecycle and failed-output recovery, focus gain while loading, rendered Spotify QR decode, device consent denial, device polling/backoff/cancellation/refresh, encrypted persistence, separate playback grant restoration, signed-out write protection.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (auth != null) auth.close();
            if (activity != null) { Activity screen = activity; runOnMainSync(screen::finish); }
        }
    }

    private void verifySeparatePlaybackGrant() throws Exception {
        SecureStore secure = new SecureStore(getTargetContext());
        JSONObject web = new JSONObject().put("client_id", AuthManager.WEB_CLIENT_ID).put("access_token", "web-only")
                .put("refresh_token", "web-refresh").put("expires_at", System.currentTimeMillis() + 600_000);
        JSONObject audio = new JSONObject().put("client_id", AuthManager.PLAYBACK_CLIENT_ID).put("access_token", "audio-only")
                .put("refresh_token", "audio-refresh").put("expires_at", System.currentTimeMillis() + 600_000).put("username", "same-account");
        secure.put("spotify_oauth", web.put("playback", audio).toString());
        try (AuthManager restored = new AuthManager(getTargetContext())) {
            require(restored.usesPlaybackOAuth(), "separate playback grant lost on restart");
            require("web-only".equals(restored.accessToken()), "Web API received playback token");
            require("audio-only".equals(restored.playbackAccessToken()), "player received Web API token");
            require("same-account".equals(restored.playbackUsername()), "playback account binding lost");
            restored.logout();
        }
        web.remove("playback"); secure.put("spotify_oauth", web.toString());
        try (AuthManager legacy = new AuthManager(getTargetContext())) {
            require(!legacy.usesPlaybackOAuth(), "legacy session incorrectly migrated");
            require("web-only".equals(legacy.playbackAccessToken()), "legacy Connect grant broken");
            legacy.logout();
        }
    }

    private static Http.Response deviceResponse() {
        return new Http.Response(200, "{\"device_code\":\"private-device-code\",\"user_code\":\"ABC123\",\"verification_uri\":\"https://spotify.com/pair\",\"expires_in\":600,\"interval\":5}", 0);
    }

    private void verifyDeviceAuthorization() throws Exception {
        new SecureStore(getTargetContext()).remove("spotify_oauth");
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch paired = new CountDownLatch(1), connected = new CountDownLatch(1);
        AuthManager.Listener listener = new AuthManager.Listener() {
            public void onPairing(String url, String code, long expiry) { paired.countDown(); }
            public void onConnected() { connected.countDown(); }
            public void onError(String message) { failure.set(message); }
        };
        try (AuthManager manager = new AuthManager(getTargetContext(), (method, url, body, type, bearer) -> {
            require(url.startsWith("https://accounts.spotify.com/"), "wrong authority");
            if (url.endsWith("/device/authorize")) return deviceResponse();
            int count = requests.incrementAndGet();
            if (count <= 2) return new Http.Response(400, "{\"error\":\"" + (count == 1 ? "authorization_pending" : "slow_down") + "\"}", 0);
            if (count > 3) require(body.contains("grant_type=refresh_token"), "refresh was not independent of pairing");
            return new Http.Response(200, "{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"expires_in\":60,\"scope\":\"streaming\",\"token_type\":\"Bearer\"}", 0);
        })) {
            manager.start(listener);
            require(paired.await(5, TimeUnit.SECONDS), "device code not delivered");
            java.lang.reflect.Field pairing = AuthManager.class.getDeclaredField("pairing"), attempt = AuthManager.class.getDeclaredField("attempt");
            pairing.setAccessible(true); attempt.setAccessible(true);
            Object flow = pairing.get(manager); long revision = attempt.getLong(manager);
            java.lang.reflect.Method poll = AuthManager.class.getDeclaredMethod("poll", long.class, flow.getClass(), AuthManager.Listener.class);
            poll.setAccessible(true);
            poll.invoke(manager, revision, flow, listener);
            require(!manager.isSignedIn(), "pending poll created credentials");
            poll.invoke(manager, revision, flow, listener);
            java.lang.reflect.Field interval = flow.getClass().getDeclaredField("interval"); interval.setAccessible(true);
            require(interval.getLong(flow) == 10, "slow_down ignored");
            poll.invoke(manager, revision, flow, listener);
            require(connected.await(5, TimeUnit.SECONDS) && failure.get() == null, "device authorization did not complete");
            require(manager.usesNativeCatalog() && manager.usesPlaybackOAuth(), "device grant was misclassified");
            require("test-access".equals(manager.playbackAccessToken()), "device refresh failed");
            require(requests.get() == 4, "token was not refreshed");
            manager.cancel();
            poll.invoke(manager, revision, flow, listener);
            require(requests.get() == 4, "cancelled device code was polled");
            try (AuthManager restored = new AuthManager(getTargetContext())) {
                require(restored.usesNativeCatalog(), "device grant lost on restart");
                restored.logout();
            }
            manager.logout();
        }
        // Reject a substituted verification origin; its device code must never become a QR.
        CountDownLatch rejected = new CountDownLatch(1);
        try (AuthManager invalid = new AuthManager(getTargetContext(), (method, url, body, type, bearer) ->
                new Http.Response(200, deviceResponse().body.replace("https://spotify.com/pair", "https://example.com/pair"), 0))) {
            invalid.start(new AuthManager.Listener() {
                public void onPairing(String url, String code, long expiry) { failure.set("untrusted QR accepted"); }
                public void onConnected() { failure.set("untrusted grant accepted"); }
                public void onError(String message) { rejected.countDown(); }
            });
            require(rejected.await(5, TimeUnit.SECONDS) && failure.get() == null, "verification origin was not checked");
        }
    }

    private void verifyFocusGainWhileLoading() throws Exception {
        AtomicReference<PlayerService> bound = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                bound.set(((PlayerService.LocalBinder) binder).getService()); ready.countDown();
            }
            public void onServiceDisconnected(android.content.ComponentName name) { }
        };
        require(getTargetContext().bindService(new Intent(getTargetContext(), PlayerService.class), connection, android.content.Context.BIND_AUTO_CREATE), "player service bind failed");
        try {
            require(ready.await(10, TimeUnit.SECONDS), "player service bind timed out");
            PlayerService service = bound.get();
            java.lang.reflect.Field playerField = PlayerService.class.getDeclaredField("player");
            java.lang.reflect.Field volumeField = NativePlayer.class.getDeclaredField("volume");
            java.lang.reflect.Method publish = PlayerService.class.getDeclaredMethod("publish", JSONObject.class);
            playerField.setAccessible(true); volumeField.setAccessible(true); publish.setAccessible(true);
            try (NativePlayer output = new NativePlayer(getTargetContext(), new NativePlayer.Callback() {
                public void onState(JSONObject state) { }
                public boolean requestAudioFocus() { return true; }
                public void onAudioError(String message) { }
            })) {
                playerField.set(service, output);
                runOnMainSync(() -> service.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK));
                require(volumeField.getFloat(output) == 0.2f, "duck did not reduce output volume");
                JSONObject loading = service.snapshot().put("playing", false).put("loading", true);
                runOnMainSync(() -> {
                    try { publish.invoke(service, loading); }
                    catch (Exception error) { throw new RuntimeException(error); }
                    service.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN);
                });
                require(volumeField.getFloat(output) == 1f, "focus gain while loading left output ducked");
            }
        } finally {
            if (bound.get() != null) runOnMainSync(() -> bound.get().logout());
            getTargetContext().unbindService(connection);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private void awaitText(Activity screen, String expected) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
        AtomicReference<String> value = new AtomicReference<>("");
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync(() -> value.set(text(screen.getWindow().getDecorView())));
            if (value.get().contains(expected)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Expected visible text: " + expected + "\n" + value.get());
    }
    private static View find(View root, Class<?> type) {
        if (type.isInstance(root)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }
    private static boolean clickText(View root, String expected) {
        if (root instanceof TextView && expected.contentEquals(((TextView) root).getText())) {
            View target = root;
            while (!target.isClickable() && target.getParent() instanceof View) target = (View) target.getParent();
            return target.performClick();
        }
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            if (clickText(((ViewGroup) root).getChildAt(i), expected)) return true;
        }
        return false;
    }
    private static String text(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.append(text(((ViewGroup) view).getChildAt(i)));
        return out.toString();
    }
}
