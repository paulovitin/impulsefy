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

    GlyphView(Context context, String glyph, int color) {
        super(context);
        this.glyph = glyph;
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setGlyph(String value) { glyph = value; invalidate(); }
    void setColor(int value) { color = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) * .58f;
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
            case "heart":
                path.moveTo(12, 21); path.cubicTo(8, 17, 2, 12, 2, 7);
                path.cubicTo(2, 1, 9, 1, 12, 6); path.cubicTo(15, 1, 22, 1, 22, 7);
                path.cubicTo(22, 12, 16, 17, 12, 21); canvas.drawPath(path, paint); break;
            case "play":
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(8, 4); path.lineTo(21, 12); path.lineTo(8, 20); path.close();
                canvas.drawPath(path, paint); break;
            case "pause":
                paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(6, 4, 10, 20, 1, 1, paint);
                canvas.drawRoundRect(14, 4, 18, 20, 1, 1, paint); break;
            case "previous": case "next":
                if (glyph.equals("previous")) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(5, 5); path.lineTo(17, 12); path.lineTo(5, 19); path.close();
                canvas.drawPath(path, paint); canvas.drawRoundRect(18, 5, 20, 19, 1, 1, paint); break;
            case "back":
                canvas.drawLine(20, 12, 4, 12, paint); path.moveTo(10, 5); path.lineTo(3, 12);
                path.lineTo(10, 19); canvas.drawPath(path, paint); break;
            case "chevron":
                path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18); canvas.drawPath(path, paint); break;
            case "settings":
                canvas.drawCircle(12, 12, 7, paint); canvas.drawCircle(12, 12, 2.5f, paint);
                for (int i = 0; i < 8; i++) {
                    canvas.save(); canvas.rotate(i * 45, 12, 12);
                    canvas.drawLine(12, 2, 12, 5, paint); canvas.restore();
                } break;
            case "music":
                canvas.drawLine(9, 17, 9, 5, paint); canvas.drawLine(9, 5, 20, 3, paint);
                canvas.drawLine(20, 3, 20, 15, paint);
                canvas.drawOval(3, 16, 9, 21, paint); canvas.drawOval(14, 14, 20, 19, paint); break;
            case "signal":
                for (int i = 0; i < 4; i++) canvas.drawLine(5 + i * 5, 20, 5 + i * 5, 16 - i * 4, paint);
                break;
            case "impulse":
                paint.setStrokeWidth(2.2f);
                path.moveTo(1, 13); path.lineTo(6, 13); path.lineTo(10, 4);
                path.lineTo(14, 20); path.lineTo(18, 10); path.lineTo(23, 10);
                canvas.drawPath(path, paint); break;
            default: canvas.drawCircle(12, 12, 5, paint);
        }
        canvas.restore();
    }
}
