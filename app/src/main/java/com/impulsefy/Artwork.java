package com.impulsefy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded thumbnails; recycled rows only receive the image they currently request. */
final class Artwork implements AutoCloseable {
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(48), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File directory;
    private volatile boolean closed;

    Artwork(Context context) {
        directory = new File(context.getCacheDir(), "covers");
        directory.mkdirs();
        workers.allowCoreThreadTimeOut(true);
    }

    void bind(ImageView view, String url, int seed) {
        String key = url == null ? "" : url;
        String requestTag = key.isEmpty() ? "placeholder:" + seed : key;
        if (requestTag.equals(view.getTag())) return;
        view.setTag(requestTag);
        view.setImageDrawable(new Placeholder(seed));
        if (key.isEmpty() || closed) return;
        Bitmap cached = cache.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        workers.execute(() -> {
            Bitmap bitmap = load(key);
            if (bitmap != null && !closed) {
                cache.put(key, bitmap);
                main.post(() -> { if (!closed && key.equals(view.getTag())) view.setImageBitmap(bitmap); });
            }
        });
    }

    private Bitmap load(String value) {
        HttpURLConnection connection = null;
        File temporary = null;
        try {
            URL url = new URL(value);
            if (!"https".equals(url.getProtocol())) return null;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : digest) name.append(String.format("%02x", b & 255));
            File file = new File(directory, name.toString());
            if (!file.exists()) {
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                if (connection.getResponseCode() != 200 || connection.getContentLength() > 3 * 1024 * 1024) return null;
                temporary = File.createTempFile("cover-", ".part", directory);
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[8192]; int count; int total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 3 * 1024 * 1024 || closed) return null;
                        output.write(buffer, 0, count);
                    }
                }
                if (!temporary.renameTo(file) && !file.exists()) return null;
                trimDisk();
            }
            file.setLastModified(System.currentTimeMillis());
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) { file.delete(); return null; }
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
            if (temporary != null) temporary.delete();
        }
    }

    private synchronized void trimDisk() {
        File[] files = directory.listFiles((dir, name) -> !name.endsWith(".part"));
        if (files == null) return;
        long bytes = 0; for (File file : files) bytes += file.length();
        if (bytes <= 40 * 1024 * 1024) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) {
            long size = file.length();
            if (file.delete()) bytes -= size;
            if (bytes <= 32 * 1024 * 1024) break;
        }
    }

    @Override public void close() { closed = true; workers.shutdownNow(); cache.evictAll(); }

    static final class Placeholder extends Drawable {
        private static final int[][] COLORS = {
                {0xff164852, 0xff0a222d}, {0xff594334, 0xff231c22},
                {0xff444563, 0xff1a2036}, {0xff3c584c, 0xff132b28}
        };
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int seed;
        private LinearGradient gradient;
        Placeholder(int seed) { this.seed = (seed & 0x7fffffff) % COLORS.length; }
        @Override protected void onBoundsChange(Rect bounds) {
            gradient = new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    COLORS[seed][0], COLORS[seed][1], Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds(); float w = bounds.width(), h = bounds.height();
            canvas.save(); canvas.translate(bounds.left, bounds.top);
            paint.setStyle(Paint.Style.FILL); paint.setShader(gradient);
            canvas.drawRect(0, 0, w, h, paint); paint.setShader(null);
            paint.setColor(0x184fd6e8); canvas.drawCircle(w * .74f, h * .25f, w * .47f, paint);
            paint.setColor(0x147ff2e2); canvas.drawCircle(w * .72f, h * .25f, w * .32f, paint);
            paint.setColor(0x1affffff); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1, w / 150));
            for (int i = 0; i < 5; i++) {
                path.reset(); path.moveTo(-w * .1f, h * (.59f + i * .105f));
                path.cubicTo(w * .3f, h * (.18f + i * .09f), w * .7f, h * (1.1f + i * .03f), w * 1.15f, h * (.61f + i * .06f));
                canvas.drawPath(path, paint);
            }
            paint.setColor(0xcceaf2f8); paint.setStrokeWidth(Math.max(1.7f, w / 85));
            paint.setStrokeCap(Paint.Cap.ROUND);
            float s = Math.min(w, h) * .23f, x = w * .5f, y = h * .5f;
            canvas.drawLine(x - s * .16f, y + s * .48f, x - s * .16f, y - s * .55f, paint);
            canvas.drawLine(x - s * .16f, y - s * .55f, x + s * .6f, y - s * .72f, paint);
            canvas.drawLine(x + s * .6f, y - s * .72f, x + s * .6f, y + s * .25f, paint);
            canvas.drawOval(x - s * .65f, y + s * .24f, x - s * .16f, y + s * .63f, paint);
            canvas.drawOval(x + s * .11f, y, x + s * .6f, y + s * .4f, paint);
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
