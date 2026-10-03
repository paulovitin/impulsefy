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
            System.loadLibrary("impulsefy");
            CountDownLatch nativeReply = new CountDownLatch(1);
            try (NativePlayer output = new NativePlayer(getTargetContext(), new NativePlayer.Callback() {
                public void onState(JSONObject state) { if (!state.optBoolean("connected") && !state.optString("error").isEmpty()) nativeReply.countDown(); }
                public boolean requestAudioFocus() { return true; }
                public void onAudioError(String message) { throw new AssertionError(message); }
            })) {
                output.command(new JSONObject().put("command", "pause"));
                require(nativeReply.await(10, TimeUnit.SECONDS), "Rust -> Java state callback failed");
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
            }
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

            // Drive the real login view and decode its rendered QR, not the underlying URL.
            // This preference is test-only; adb reverse routes it to the local relay.
            getTargetContext().getSharedPreferences("impulsefy_ui", 0).edit().putString("relay", "http://127.0.0.1:8787").commit();
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
            require(url.startsWith("http://127.0.0.1:8787/pair/"), "QR does not point to pairing page");
            require(!url.contains("secret") && !url.contains("verifier"), "secret present in QR");
            // Let the OS activity transition finish before recording the rendered surface.
            Thread.sleep(600);
            waitForIdleSync();
            Bitmap capture = getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream file = getTargetContext().openFileOutput("qr-smoke.png", 0)) {
                capture.compress(Bitmap.CompressFormat.PNG, 100, file);
            }
            capture.recycle();
            String phonePage = request(url, null, null);
            java.util.regex.Matcher consent = java.util.regex.Pattern.compile("name=\"consent\" value=\"([^\"]+)\"").matcher(phonePage);
            require(consent.find(), "mobile consent form missing");
            String target = request(url + "/authorize", "consent=" + consent.group(1), "Location");
            android.net.Uri authorize = android.net.Uri.parse(target);
            require("accounts.spotify.com".equals(authorize.getHost()), "unexpected OAuth authority");
            require("S256".equals(authorize.getQueryParameter("code_challenge_method")), "PKCE S256 missing");
            require(!target.contains("code_verifier"), "verifier leaked to phone");
            request("http://127.0.0.1:8787/callback?error=access_denied&state=" + authorize.getQueryParameter("state"), null, null);
            awaitText(login, "O login foi cancelado");
            auth = new AuthManager(getTargetContext(), "http://127.0.0.1:8787");
            require(!auth.isSignedIn(), "declined consent created a session");
            runOnMainSync(login::finish);
            activity = null;
            getTargetContext().getSharedPreferences("impulsefy_ui", 0).edit().remove("relay").commit();

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
            result.putString("stream", "\nPASS: Android navigation, Rust JNI roundtrip, PCM AudioTrack start/write/pause/resume, rendered QR decode, S256, phone consent denial, encrypted persistence and signed-out write protection.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            getTargetContext().getSharedPreferences("impulsefy_ui", 0).edit().remove("relay").commit();
            if (auth != null) auth.close();
            if (activity != null) { Activity screen = activity; runOnMainSync(screen::finish); }
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
    private static String request(String address, String body, String header) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            require(status >= 200 && status < 400, "phone HTTP " + status);
            if (header != null) return connection.getHeaderField(header);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            try (java.io.InputStream input = connection.getInputStream()) {
                int count;
                while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            }
            return buffer.toString("UTF-8");
        } finally { connection.disconnect(); }
    }
}
