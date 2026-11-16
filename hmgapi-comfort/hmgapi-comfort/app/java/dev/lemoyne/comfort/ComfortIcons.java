package dev.lemoyne.comfort;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Crisp anti-aliased Material 3 vector icons drawn natively on Canvas (24x24 virtual viewport)
 * so Android 4.4 (API 19) renders sharp geometry at any DPI with zero font/PNG artifacts.
 */
final class ComfortIcons extends Drawable {
    static final int SEAT_WAVE = 1;
    static final int SHIELD_CHECK = 2;
    static final int THERMOMETER = 3;
    static final int WHEEL = 4;
    static final int HEAT_WAVES = 5;
    static final int BREEZE = 6;
    static final int TIMER = 7;
    static final int BELL = 8;
    static final int GRID = 9;
    static final int PLAY_BOLT = 10;
    static final int CHECK = 11;
    static final int CLOSE = 12;
    static final int PLUS = 13;
    static final int MINUS = 14;
    static final int RESET = 15;
    static final int AUTO_CLIMATE = 16;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final int kind;
    private int color;

    ComfortIcons(int kind, int color) {
        this.kind = kind;
        this.color = color;
    }

    void setColor(int c) {
        if (this.color != c) {
            this.color = c;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float size = Math.min(b.width(), b.height());
        float s = size / 24f;
        float ox = b.left + (b.width() - size) * 0.5f;
        float oy = b.top + (b.height() - size) * 0.5f;

        c.save();
        c.translate(ox, oy);

        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.0f * s);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();

        switch (kind) {
            case SEAT_WAVE: {
                // Contoured automotive seat + 2 radiant comfort waves
                path.moveTo(6.8f * s, 7.2f * s);
                path.quadTo(5.3f * s, 11.8f * s, 6.3f * s, 15.8f * s);
                path.quadTo(6.7f * s, 17.4f * s, 8.6f * s, 17.4f * s);
                path.lineTo(15.5f * s, 17.4f * s);
                c.drawPath(path, p);
                c.drawLine(6.6f * s, 20.2f * s, 15.3f * s, 20.2f * s, p);

                // Distinct filled headrest capsule
                p.setStyle(Paint.Style.FILL);
                oval.set(5.4f * s, 2.4f * s, 9.8f * s, 5.0f * s);
                c.drawRoundRect(oval, 1.3f * s, 1.3f * s, p);
                p.setStyle(Paint.Style.STROKE);

                // Two S-curve comfort waves on right
                drawWave(c, s, 14.3f, 6.2f, 13.8f);
                drawWave(c, s, 18.3f, 6.2f, 13.8f);
                break;
            }
            case SHIELD_CHECK: {
                path.moveTo(12f * s, 3.0f * s);
                path.lineTo(19f * s, 6.0f * s);
                path.lineTo(19f * s, 11.5f * s);
                path.quadTo(19f * s, 17.5f * s, 12f * s, 21.0f * s);
                path.quadTo(5f * s, 17.5f * s, 5f * s, 11.5f * s);
                path.lineTo(5f * s, 6.0f * s);
                path.close();
                c.drawPath(path, p);

                path.reset();
                path.moveTo(8.8f * s, 12.2f * s);
                path.lineTo(11.1f * s, 14.5f * s);
                path.lineTo(15.5f * s, 9.8f * s);
                c.drawPath(path, p);
                break;
            }
            case THERMOMETER: {
                // Stem + bulb
                path.moveTo(10f * s, 13.8f * s);
                path.lineTo(10f * s, 5.5f * s);
                oval.set(10f * s, 3.5f * s, 14f * s, 7.5f * s);
                path.arcTo(oval, 180f, 180f, false);
                path.lineTo(14f * s, 13.8f * s);
                oval.set(8.2f * s, 13.2f * s, 15.8f * s, 20.8f * s);
                path.arcTo(oval, -55f, 290f, false);
                path.close();
                c.drawPath(path, p);

                // Mercury dot + ticks
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12f * s, 17.0f * s, 1.7f * s, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(12f * s, 9.5f * s, 12f * s, 16.0f * s, p);
                c.drawLine(16.5f * s, 6.5f * s, 19.0f * s, 6.5f * s, p);
                c.drawLine(16.5f * s, 10.0f * s, 18.2f * s, 10.0f * s, p);
                break;
            }
            case WHEEL: {
                c.drawCircle(12f * s, 12f * s, 8.5f * s, p);
                c.drawCircle(12f * s, 12f * s, 2.3f * s, p);
                c.drawLine(3.8f * s, 10.8f * s, 9.7f * s, 11.5f * s, p);
                c.drawLine(20.2f * s, 10.8f * s, 14.3f * s, 11.5f * s, p);
                c.drawLine(12f * s, 14.3f * s, 12f * s, 20.3f * s, p);
                break;
            }
            case HEAT_WAVES: {
                drawWave(c, s, 6.5f, 4.5f, 19.5f);
                drawWave(c, s, 12.0f, 4.5f, 19.5f);
                drawWave(c, s, 17.5f, 4.5f, 19.5f);
                break;
            }
            case BREEZE: {
                // Three smooth airflow curls
                path.moveTo(3.5f * s, 9.0f * s);
                path.lineTo(15.5f * s, 9.0f * s);
                oval.set(13.5f * s, 5.0f * s, 17.5f * s, 9.0f * s);
                path.arcTo(oval, 90f, -250f, false);

                path.moveTo(3.5f * s, 13.0f * s);
                path.lineTo(18.5f * s, 13.0f * s);
                oval.set(16.5f * s, 13.0f * s, 20.5f * s, 17.0f * s);
                path.arcTo(oval, 270f, 250f, false);

                path.moveTo(3.5f * s, 17.0f * s);
                path.lineTo(12.5f * s, 17.0f * s);
                oval.set(10.5f * s, 17.0f * s, 14.5f * s, 21.0f * s);
                path.arcTo(oval, 270f, 230f, false);
                c.drawPath(path, p);
                break;
            }
            case TIMER: {
                c.drawLine(9.5f * s, 2.8f * s, 14.5f * s, 2.8f * s, p);
                c.drawCircle(12f * s, 13.2f * s, 7.8f * s, p);
                c.drawLine(12f * s, 13.2f * s, 15.2f * s, 10.2f * s, p);
                c.drawLine(17.8f * s, 6.8f * s, 19.4f * s, 5.2f * s, p);
                break;
            }
            case BELL: {
                path.moveTo(18f * s, 16.5f * s);
                path.lineTo(6f * s, 16.5f * s);
                path.quadTo(7.2f * s, 15.0f * s, 7.2f * s, 10.5f * s);
                oval.set(7.2f * s, 5.2f * s, 16.8f * s, 14.8f * s);
                path.arcTo(oval, 180f, 180f, false);
                path.quadTo(16.8f * s, 15.0f * s, 18f * s, 16.5f * s);
                c.drawPath(path, p);
                oval.set(10.2f * s, 17.2f * s, 13.8f * s, 20.5f * s);
                c.drawArc(oval, 0f, 180f, false, p);
                break;
            }
            case GRID: {
                float r = 1.8f * s;
                oval.set(4f * s, 4f * s, 10.5f * s, 10.5f * s);
                c.drawRoundRect(oval, r, r, p);
                oval.set(13.5f * s, 4f * s, 20f * s, 10.5f * s);
                c.drawRoundRect(oval, r, r, p);
                oval.set(4f * s, 13.5f * s, 10.5f * s, 20f * s);
                c.drawRoundRect(oval, r, r, p);
                oval.set(13.5f * s, 13.5f * s, 20f * s, 20f * s);
                c.drawRoundRect(oval, r, r, p);
                break;
            }
            case PLAY_BOLT: {
                p.setStyle(Paint.Style.FILL);
                path.moveTo(13.2f * s, 3.0f * s);
                path.lineTo(5.8f * s, 13.2f * s);
                path.lineTo(11.4f * s, 13.2f * s);
                path.lineTo(10.4f * s, 21.0f * s);
                path.lineTo(18.2f * s, 10.8f * s);
                path.lineTo(12.6f * s, 10.8f * s);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case CHECK: {
                p.setStrokeWidth(2.3f * s);
                path.moveTo(5.2f * s, 12.5f * s);
                path.lineTo(9.8f * s, 17.0f * s);
                path.lineTo(19.0f * s, 7.2f * s);
                c.drawPath(path, p);
                break;
            }
            case CLOSE: {
                c.drawLine(6.5f * s, 6.5f * s, 17.5f * s, 17.5f * s, p);
                c.drawLine(17.5f * s, 6.5f * s, 6.5f * s, 17.5f * s, p);
                break;
            }
            case PLUS: {
                p.setStrokeWidth(2.2f * s);
                c.drawLine(6.0f * s, 12.0f * s, 18.0f * s, 12.0f * s, p);
                c.drawLine(12.0f * s, 6.0f * s, 12.0f * s, 18.0f * s, p);
                break;
            }
            case MINUS: {
                p.setStrokeWidth(2.2f * s);
                c.drawLine(6.0f * s, 12.0f * s, 18.0f * s, 12.0f * s, p);
                break;
            }
            case RESET: {
                oval.set(5.0f * s, 5.0f * s, 19.0f * s, 19.0f * s);
                c.drawArc(oval, -135f, 285f, false, p);
                path.moveTo(4.8f * s, 4.8f * s);
                path.lineTo(4.8f * s, 9.8f * s);
                path.lineTo(9.8f * s, 9.8f * s);
                c.drawPath(path, p);
                break;
            }
            case AUTO_CLIMATE: {
                // 4-point M3 sparkle star + small accent star
                path.moveTo(11.0f * s, 3.5f * s);
                path.quadTo(11.2f * s, 9.8f * s, 17.5f * s, 10.0f * s);
                path.quadTo(11.2f * s, 10.2f * s, 11.0f * s, 16.5f * s);
                path.quadTo(10.8f * s, 10.2f * s, 4.5f * s, 10.0f * s);
                path.quadTo(10.8f * s, 9.8f * s, 11.0f * s, 3.5f * s);
                path.close();
                c.drawPath(path, p);

                c.drawLine(18.0f * s, 15.0f * s, 18.0f * s, 20.0f * s, p);
                c.drawLine(15.5f * s, 17.5f * s, 20.5f * s, 17.5f * s, p);
                break;
            }
        }
        c.restore();
    }

    private void drawWave(Canvas c, float s, float x, float yTop, float yBot) {
        float mid = (yTop + yBot) * 0.5f;
        Path w = new Path();
        w.moveTo(x * s, yBot * s);
        w.quadTo((x - 1.8f) * s, (mid + yBot) * 0.5f * s, x * s, mid * s);
        w.quadTo((x + 1.8f) * s, (yTop + mid) * 0.5f * s, x * s, yTop * s);
        c.drawPath(w, p);
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
