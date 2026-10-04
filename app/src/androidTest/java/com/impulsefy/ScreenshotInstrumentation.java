package com.impulsefy;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Captures the real app with offline preview data, never a signed-in account. */
public final class ScreenshotInstrumentation extends Instrumentation {
    private Activity screen;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!android.os.Build.FINGERPRINT.contains("generic")) throw new IllegalStateException("Use the screenshot emulator");
            try (AuthManager auth = new AuthManager(getTargetContext())) {
                if (auth.isSignedIn()) throw new IllegalStateException("Use an emulator without a Spotify account");
            }
            screen = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("demo", true));
            capture("02-home");
            click("Biblioteca"); capture("03-library");
            runOnMainSync(() -> { AbsListView list = (AbsListView) find(screen.getWindow().getDecorView(), AbsListView.class); list.performItemClick(list.getChildAt(0), 0, 0); });
            capture("04-playlist");
            click("Curtidas"); capture("05-liked-songs");
            click("Buscar"); capture("06-search");
            click("Rock"); capture("07-search-results");
            click("Início"); click("Configurações da conta"); capture("08-account");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            click("Fila"); capture("09-queue");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            AuthManager fake = new AuthManager(getTargetContext(), (method, url, body, type, bearer) -> {
                if (url.endsWith("/device/authorize")) return new Http.Response(200, "{\"device_code\":\"screenshot-only\",\"user_code\":\"DEMO42\",\"verification_uri\":\"https://spotify.com/pair\",\"expires_in\":600,\"interval\":5}", 0);
                return new Http.Response(400, "{\"error\":\"authorization_pending\"}", 0);
            });
            Field auth = field("auth"); Method login = MainActivity.class.getDeclaredMethod("showLogin"); login.setAccessible(true);
            runOnMainSync(() -> {
                try { ((AuthManager) auth.get(screen)).close(); auth.set(screen, fake); login.invoke(screen); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                waitForIdleSync();
                if (((android.widget.ImageView) field("qrImage").get(screen)).getDrawable() != null) break;
                Thread.sleep(50);
            }
            if (((android.widget.ImageView) field("qrImage").get(screen)).getDrawable() == null) throw new AssertionError("Mock QR did not render");
            runOnMainSync(() -> {
                try { ((TextView) field("qrStatus").get(screen)).setText("QR ilustrativo · conta de demonstração"); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            capture("01-login");
            result.putString("stream", "\nPASS: 9 screenshots with fictional data, original covers and a mock pairing QR.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure)); finish(Activity.RESULT_CANCELED, result);
        } finally { if (screen != null) runOnMainSync(screen::finish); }
    }

    private void capture(String name) throws Exception {
        waitForIdleSync();
        runOnMainSync(() -> {
            try { ((Handler) field("main").get(screen)).removeCallbacks((Runnable) field("ticker").get(screen)); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        Thread.sleep(400); waitForIdleSync();
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) throw new AssertionError("No screenshot: " + name);
        File directory = new File(getTargetContext().getFilesDir(), "screenshots"); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, output); }
        image.recycle();
    }
    private static Field field(String name) throws Exception { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private void click(String label) {
        runOnMainSync(() -> { if (!click(screen.getWindow().getDecorView(), label)) throw new AssertionError("Missing control: " + label); });
        waitForIdleSync();
    }
    private static boolean click(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()) || view instanceof TextView && label.contentEquals(((TextView) view).getText())) {
            while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
            return view.performClick();
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (click(((ViewGroup) view).getChildAt(i), label)) return true;
        return false;
    }
    private static View find(View view, Class<?> type) {
        if (type.isInstance(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = find(((ViewGroup) view).getChildAt(i), type); if (found != null) return found; }
        return null;
    }
}
