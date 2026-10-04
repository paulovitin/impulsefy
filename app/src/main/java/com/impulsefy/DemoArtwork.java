package com.impulsefy;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Original vector album art for fictional preview music; no network or real covers. */
final class DemoArtwork extends Drawable {
    static final String[] TITLES = {"Maré de Luz", "Costa Azul", "Tempo Suspenso", "Solar", "Gravidade", "Aurora", "Cartas da Estrada", "Cidade Jardim", "Norte", "Âmbar", "Outubro", "Atlas"};
    private static final String[][] LETTERING = {{"MARÉ", "DE LUZ"}, {"COSTA", "AZUL"}, {"TEMPO", "SUSPENSO"}, {"SOLAR"}, {"GRAVI", "DADE"}, {"AURORA"}, {"CARTAS", "DA ESTRADA"}, {"CIDADE", "JARDIM"}, {"NORTE"}, {"ÂMBAR"}, {"OUTUBRO"}, {"ATLAS"}};
    private static final int[][] PALETTES = {
        {0xff201a50,0xffc05f77,0xffffc090}, {0xff063e51,0xff147b8f,0xff8be4cc},
        {0xffdbbbaa,0xff805665,0xfffae9cd}, {0xffc34527,0xfff3983d,0xffffdc82},
        {0xff102b36,0xff326a6c,0xffbcf17c}, {0xff5d4185,0xffc489a0,0xffffd4b5},
        {0xff274554,0xff74876c,0xffffd29c}, {0xff133b31,0xff538c65,0xffdae29a},
        {0xff153458,0xff586e95,0xffb6dff2}, {0xff583337,0xffbb6850,0xfff3c582},
        {0xff492f50,0xff9e5563,0xffead5af}, {0xff143747,0xff417976,0xffbee6c6}
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int index;
    DemoArtwork(int index) { if (index < 0 || index >= TITLES.length) throw new IllegalArgumentException(); this.index = index; }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds(); int[] colors = PALETTES[index];
        canvas.save(); canvas.translate(bounds.left, bounds.top); canvas.scale(bounds.width() / 512f, bounds.height() / 512f);
        paint.setStyle(Paint.Style.FILL); paint.setShader(new LinearGradient(0, 0, 450, 512, colors[0], colors[1], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 512, 512, paint); paint.setShader(null);
        switch (index % 6) {
            case 0: // Sun, sea and a ribbon of reflected light.
                paint.setColor(colors[2]); canvas.drawCircle(365, 215, 100, paint);
                paint.setColor(colors[0]); canvas.drawRect(0, 267, 512, 512, paint);
                for (int i = 0; i < 18; i++) { paint.setColor(colors[2]); paint.setAlpha(180 - i * 8); float width = 150 - i * 5; canvas.drawRect(365 - width / 2, 275 + i * 10, 365 + width / 2, 278 + i * 10, paint); }
                paint.setAlpha(255); break;
            case 1: // Flowing coastal shapes.
                paint.setColor(colors[2]); canvas.drawCircle(366, 188, 75, paint);
                for (int i = 0; i < 5; i++) {
                    Path wave = new Path(); wave.moveTo(-40, 240 + i * 45); wave.cubicTo(130, 60 + i * 60, 260, 530 - i * 10, 555, 210 + i * 48); wave.lineTo(555, 530); wave.lineTo(-40, 530); wave.close();
                    paint.setColor(i % 2 == 0 ? colors[0] : colors[2]); paint.setAlpha(190); canvas.drawPath(wave, paint);
                }
                paint.setAlpha(255); break;
            case 2: // Architectural light and long shadows.
                canvas.save(); canvas.rotate(-24, 310, 270);
                for (int i = 0; i < 5; i++) { paint.setColor(i % 2 == 0 ? colors[2] : colors[0]); canvas.drawRect(165 + i * 55, 100, 205 + i * 55, 520, paint); }
                canvas.restore(); break;
            case 3: // Oversized solar discs.
                for (int i = 5; i > 0; i--) { paint.setColor(i % 2 == 0 ? colors[0] : colors[2]); canvas.drawCircle(365, 345, i * 51, paint); }
                break;
            case 4: // Midnight skyline and orbital paths.
                paint.setColor(colors[2]); canvas.drawCircle(392, 166, 57, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
                for (int i = 0; i < 4; i++) canvas.drawOval(110 - i * 40, 100 + i * 24, 600, 340 + i * 40, paint);
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 12; i++) { float top = 320 - (i * 37 % 90); paint.setColor(colors[0]); canvas.drawRect(i * 46, top, i * 46 + 34, 512, paint); paint.setColor(colors[2]); for (int j = 0; j < 4; j++) canvas.drawRect(i * 46 + 9, top + 17 + j * 24, i * 46 + 15, top + 22 + j * 24, paint); }
                break;
            default: // Layered, luminous arches.
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(22);
                for (int i = 0; i < 9; i++) { paint.setColor(i % 2 == 0 ? colors[2] : colors[0]); canvas.drawOval(220 - i * 26, 122 - i * 18, 540 + i * 26, 570 + i * 30, paint); }
                paint.setStyle(Paint.Style.FILL); break;
        }
        paint.setShader(new LinearGradient(0, 0, 0, 230, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)); canvas.drawRect(0, 0, 512, 230, paint); paint.setShader(null);
        paint.setColor(0xfff8eedc); paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        String[] lines = LETTERING[index];
        for (int i = 0; i < lines.length; i++) { paint.setTextSize(lines[i].length() > 8 ? 43 : 64); canvas.drawText(lines[i], 34, 115 + i * 62, paint); }
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(12);
        canvas.drawText("IMPULSE SESSIONS    /    VOL. " + String.format(java.util.Locale.US, "%02d", index + 1), 34, 446, paint);
        canvas.restore();
    }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter colorFilter) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    @Override public int getIntrinsicWidth() { return 512; }
    @Override public int getIntrinsicHeight() { return 512; }
}
