package com.impulsefy;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONException;
import org.json.JSONObject;

/** JNI bridge. Networking and decoding live on native workers, never the UI thread. */
final class NativePlayer implements AutoCloseable {
    interface Callback {
        void onState(JSONObject state);
        boolean requestAudioFocus();
        void onAudioError(String message);
    }
    static { System.loadLibrary("impulsefy"); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private volatile boolean closed;
    private volatile AudioTrack audio;
    private float volume = 1f, focusGain = 1f;
    private boolean muted;
    private long handle;

    NativePlayer(Context context, Callback callback) {
        this.callback = callback;
        handle = nativeCreate(this, context.getCacheDir().getAbsolutePath());
        if (handle == 0) throw new IllegalStateException("O mecanismo de áudio não iniciou");
    }

    synchronized void command(JSONObject command) {
        if (!closed && handle != 0) nativeCommand(handle, command.toString());
    }

    JSONObject read(String path) throws Exception {
        return request("GET", path, null);
    }

    JSONObject request(String method, String path, JSONObject body) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Use o worker de rede.");
        final long current;
        synchronized (this) { if (closed || handle == 0) throw new IllegalStateException("Player encerrado"); current = handle; }
        JSONObject value = new JSONObject(nativeRequest(current, method, path, body == null ? "{}" : body.toString()));
        if (value.has("error")) throw new Exception(value.getString("error"));
        return value;
    }

    // Called by native worker threads; methods must keep their JNI names.
    @SuppressWarnings("unused")
    private void onNativeState(String json) {
        if (closed) return;
        try {
            JSONObject value = new JSONObject(json);
            main.post(() -> { if (!closed) callback.onState(value); });
        } catch (JSONException ignored) {
            reportError("O mecanismo de áudio retornou um estado inválido");
        }
    }

    @SuppressWarnings("unused")
    private synchronized boolean startAudio() {
        if (closed) return false;
        if (!callback.requestAudioFocus()) {
            reportError("O áudio está ocupado. Finalize a chamada ou outro áudio e toque em reproduzir.");
            return false;
        }
        try {
            if (audio == null) {
                int minimum = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException("A saída não aceita áudio de 44,1 kHz");
                AudioTrack next = new AudioTrack(
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                        new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                        Math.max(minimum * 2, 35280), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                if (next.getState() != AudioTrack.STATE_INITIALIZED) {
                    next.release();
                    throw new IllegalStateException("Falha ao iniciar a saída de áudio");
                }
                audio = next;
            }
            muted = false;
            applyVolume();
            audio.play();
            return true;
        } catch (RuntimeException error) {
            reportError("Não foi possível abrir a saída de áudio: " + error.getMessage());
            releaseAudio();
            return false;
        }
    }

    @SuppressWarnings("unused")
    private synchronized boolean stopAudio() {
        if (audio == null) return true;
        try { audio.pause(); audio.flush(); return true; }
        catch (RuntimeException error) { return false; }
    }

    @SuppressWarnings("unused")
    private boolean writePcm(short[] pcm) {
        AudioTrack target = audio;
        if (closed || target == null) return false;
        try {
            int offset = 0;
            // A bounded write lets shutdown stop the AudioTrack even if the device stalls.
            while (offset < pcm.length && !closed && target == audio) {
                int count = target.write(pcm, offset, Math.min(4096, pcm.length - offset), AudioTrack.WRITE_BLOCKING);
                if (count <= 0) {
                    releaseFailedAudio(target);
                    if (!closed) reportError("A saída de áudio parou (código " + count + "). Toque em reproduzir para tentar novamente.");
                    return false;
                }
                offset += count;
            }
            return offset == pcm.length;
        } catch (RuntimeException error) {
            releaseFailedAudio(target);
            if (!closed) reportError("Falha na saída de áudio: " + error.getMessage());
            return false;
        }
    }

    synchronized void muteOutput() {
        muted = true;
        focusGain = 1f;
        applyVolume();
    }

    synchronized void setVolume(float next) {
        volume = Math.max(0f, Math.min(1f, next));
        applyVolume();
    }

    synchronized void setFocusGain(float next) {
        focusGain = Math.max(0f, Math.min(1f, next));
        applyVolume();
    }

    private void applyVolume() {
        if (audio != null) {
            try { audio.setVolume(muted ? 0f : volume * focusGain); } catch (IllegalStateException ignored) { }
        }
    }

    private void reportError(String message) {
        main.post(() -> { if (!closed) callback.onAudioError(message); });
    }

    private synchronized void releaseFailedAudio(AudioTrack target) {
        // A failed write may race with shutdown/restart; only retire its own track.
        if (audio == target) releaseAudio();
    }

    private synchronized void releaseAudio() {
        AudioTrack old = audio;
        audio = null;
        if (old != null) {
            try { old.pause(); old.flush(); } catch (IllegalStateException ignored) { }
            old.release();
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        nativeDestroy(handle);
        handle = 0;
        releaseAudio();
        main.removeCallbacksAndMessages(null);
    }

    private static native long nativeCreate(NativePlayer callback, String cacheDir);
    private static native void nativeCommand(long handle, String json);
    private static native String nativeRequest(long handle, String method, String path, String body);
    private static native void nativeDestroy(long handle);
}
