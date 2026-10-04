package com.impulsefy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small native icons keep the interface sharp at the head unit's density. */
final class GlyphView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String glyph;
    private int color;
    private float iconScale = .58f;

    GlyphView(Context context, String glyph, int color) {
        super(context);
        this.glyph = glyph;
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setGlyph(String value) { glyph = value; invalidate(); }
    void setColor(int value) { color = value; invalidate(); }
    void setIconScale(float value) { iconScale = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) * iconScale;
        canvas.save();
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 24f, size / 24f);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        switch (glyph) {
            case "alert":
                path.moveTo(8, 2); path.lineTo(16, 2); path.lineTo(22, 8); path.lineTo(22, 16);
                path.lineTo(16, 22); path.lineTo(8, 22); path.lineTo(2, 16); path.lineTo(2, 8); path.close();
                canvas.drawPath(path, paint); canvas.drawLine(12, 7, 12, 12, paint); canvas.drawPoint(12, 17, paint); break;
            case "home":
                path.moveTo(3, 10); path.lineTo(12, 3); path.lineTo(21, 10);
                path.moveTo(5, 9); path.lineTo(5, 21); path.lineTo(10, 21);
                path.lineTo(10, 14); path.lineTo(14, 14); path.lineTo(14, 21);
                path.lineTo(19, 21); path.lineTo(19, 9); canvas.drawPath(path, paint); break;
            case "library":
                canvas.drawRoundRect(3, 4, 7, 21, 1, 1, paint);
                canvas.drawLine(11, 4, 11, 21, paint);
                canvas.drawLine(16, 4, 21, 20, paint); break;
            case "search":
                canvas.drawCircle(10, 10, 6.5f, paint); canvas.drawLine(15, 15, 21, 21, paint); break;
            case "heart": case "heart_filled":
                if (glyph.equals("heart_filled")) paint.setStyle(Paint.Style.FILL);
                path.moveTo(12, 21); path.cubicTo(8, 17, 2, 12, 2, 7);
                path.cubicTo(2, 1, 9, 1, 12, 6); path.cubicTo(15, 1, 22, 1, 22, 7);
                path.cubicTo(22, 12, 16, 17, 12, 21); canvas.drawPath(path, paint); break;
            case "play":
                path.moveTo(8, 4); path.lineTo(21, 12); path.lineTo(8, 20); path.close();
                canvas.drawPath(path, paint); break;
            case "pause":
                canvas.drawRoundRect(6, 4, 10, 20, 1, 1, paint);
                canvas.drawRoundRect(14, 4, 18, 20, 1, 1, paint); break;
            case "previous": case "next":
                if (glyph.equals("previous")) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                path.moveTo(5, 5); path.lineTo(17, 12); path.lineTo(5, 19); path.close();
                canvas.drawPath(path, paint); canvas.drawRoundRect(18, 5, 20, 19, 1, 1, paint); break;
            case "back":
                canvas.drawLine(20, 12, 4, 12, paint); path.moveTo(10, 5); path.lineTo(3, 12);
                path.lineTo(10, 19); canvas.drawPath(path, paint); break;
            case "chevron":
                path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18); canvas.drawPath(path, paint); break;
            case "settings":
                canvas.drawLine(3, 7, 6, 7, paint); canvas.drawLine(12, 7, 21, 7, paint);
                canvas.drawCircle(9, 7, 3, paint);
                canvas.drawLine(3, 17, 12, 17, paint); canvas.drawLine(18, 17, 21, 17, paint);
                canvas.drawCircle(15, 17, 3, paint); break;
            case "music":
                canvas.drawLine(9, 17, 9, 5, paint); canvas.drawLine(9, 5, 20, 3, paint);
                canvas.drawLine(20, 3, 20, 15, paint);
                canvas.drawOval(3, 16, 9, 21, paint); canvas.drawOval(14, 14, 20, 19, paint); break;
            case "signal":
                for (int i = 0; i < 4; i++) canvas.drawLine(5 + i * 5, 20, 5 + i * 5, 16 - i * 4, paint);
                break;
            case "shuffle":
                path.moveTo(3, 5); path.lineTo(6, 5); path.cubicTo(10, 5, 14, 19, 18, 19); path.lineTo(21, 19);
                path.moveTo(18, 16); path.lineTo(21, 19); path.lineTo(18, 22);
                path.moveTo(3, 19); path.lineTo(6, 19); path.lineTo(9, 15);
                path.moveTo(15, 9); path.lineTo(18, 5); path.lineTo(21, 5); path.moveTo(18, 2); path.lineTo(21, 5); path.lineTo(18, 8); canvas.drawPath(path, paint); break;
            case "repeat": case "repeat_one":
                path.moveTo(3, 11); path.lineTo(3, 8); path.quadTo(3, 5, 6, 5); path.lineTo(21, 5); path.moveTo(18, 2); path.lineTo(21, 5); path.lineTo(18, 8);
                path.moveTo(21, 13); path.lineTo(21, 16); path.quadTo(21, 19, 18, 19); path.lineTo(3, 19); path.moveTo(6, 16); path.lineTo(3, 19); path.lineTo(6, 22); canvas.drawPath(path, paint);
                if (glyph.equals("repeat_one")) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(9); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); canvas.drawText("1", 10, 15, paint); } break;
            case "volume":
                path.moveTo(3, 9); path.lineTo(7, 9); path.lineTo(12, 5); path.lineTo(12, 19); path.lineTo(7, 15); path.lineTo(3, 15); path.close(); canvas.drawPath(path, paint);
                canvas.drawArc(9, 7, 20, 17, -60, 120, false, paint); canvas.drawArc(7, 3, 26, 21, -55, 110, false, paint); break;
            case "queue":
                canvas.drawLine(3, 5, 21, 5, paint); canvas.drawLine(3, 10, 14, 10, paint); canvas.drawLine(3, 15, 10, 15, paint);
                canvas.drawLine(19, 10, 19, 19, paint); canvas.drawLine(19, 10, 22, 11, paint); canvas.drawOval(14, 17, 19, 21, paint); break;
            case "history":
                canvas.drawArc(4, 3, 22, 21, -140, 320, false, paint); path.moveTo(3, 3); path.lineTo(3, 9); path.lineTo(9, 9); canvas.drawPath(path, paint); canvas.drawLine(13, 7, 13, 12, paint); canvas.drawLine(13, 12, 16, 14, paint); break;
            case "mic":
                canvas.drawRoundRect(9, 2, 15, 15, 3, 3, paint); canvas.drawArc(6, 8, 18, 19, 0, 180, false, paint); canvas.drawLine(12, 19, 12, 22, paint); canvas.drawLine(8, 22, 16, 22, paint); break;
            case "close":
                canvas.drawLine(6, 6, 18, 18, paint); canvas.drawLine(18, 6, 6, 18, paint); break;
            case "car":
                path.moveTo(4, 10); path.lineTo(7, 4); path.lineTo(17, 4); path.lineTo(20, 10); path.close(); canvas.drawPath(path, paint); canvas.drawRoundRect(3, 10, 21, 19, 2, 2, paint);
                canvas.drawLine(6, 19, 6, 22, paint); canvas.drawLine(18, 19, 18, 22, paint); canvas.drawLine(6, 14, 8, 14, paint); canvas.drawLine(16, 14, 18, 14, paint); break;
            case "wifi":
                canvas.drawArc(1, 4, 23, 24, 225, 90, false, paint); canvas.drawArc(5, 9, 19, 23, 225, 90, false, paint); canvas.drawArc(9, 14, 15, 20, 225, 90, false, paint); canvas.drawPoint(12, 21, paint); break;
            case "shield":
                path.moveTo(12, 2); path.lineTo(21, 6); path.lineTo(20, 14); path.quadTo(19, 18, 12, 22); path.quadTo(5, 18, 4, 14); path.lineTo(3, 6); path.close();
                path.moveTo(8, 12); path.lineTo(11, 15); path.lineTo(17, 9); canvas.drawPath(path, paint); break;
            case "logout":
                path.moveTo(10, 3); path.lineTo(4, 3); path.lineTo(4, 21); path.lineTo(10, 21); path.moveTo(9, 12); path.lineTo(22, 12); path.moveTo(17, 7); path.lineTo(22, 12); path.lineTo(17, 17); canvas.drawPath(path, paint); break;
            case "sun":
                canvas.drawCircle(12, 12, 4, paint); for (int i = 0; i < 8; i++) { canvas.drawLine(12, 1, 12, 4, paint); canvas.rotate(45, 12, 12); } break;
            case "zap":
                path.moveTo(14, 2); path.lineTo(4, 14); path.lineTo(11, 14); path.lineTo(10, 22); path.lineTo(20, 10); path.lineTo(13, 10); path.close(); canvas.drawPath(path, paint); break;
            case "smile":
                canvas.drawCircle(12, 12, 9, paint); canvas.drawPoint(8, 9, paint); canvas.drawPoint(16, 9, paint); canvas.drawArc(7, 10, 17, 18, 20, 140, false, paint); break;
            case "sparkles":
                path.moveTo(12, 2); path.lineTo(15, 9); path.lineTo(22, 12); path.lineTo(15, 15); path.lineTo(12, 22); path.lineTo(9, 15); path.lineTo(2, 12); path.lineTo(9, 9); path.close(); canvas.drawPath(path, paint); break;
            case "church":
                path.moveTo(4, 11); path.lineTo(12, 5); path.lineTo(20, 11); path.lineTo(20, 22); path.lineTo(4, 22); path.close(); path.moveTo(12, 1); path.lineTo(12, 5); path.moveTo(10, 3); path.lineTo(14, 3); canvas.drawPath(path, paint);
                canvas.drawRoundRect(9, 15, 15, 22, 2, 2, paint); break;
            case "guitar":
                canvas.drawOval(2, 11, 13, 22, paint); canvas.drawCircle(9, 15, 2, paint); canvas.drawLine(11, 13, 19, 5, paint); canvas.drawLine(13, 15, 21, 7, paint); canvas.drawRoundRect(17, 2, 22, 7, 1, 1, paint); break;
            default: canvas.drawCircle(12, 12, 5, paint);
        }
        canvas.restore();
    }
}
