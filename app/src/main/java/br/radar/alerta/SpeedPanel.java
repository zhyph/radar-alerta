package br.radar.alerta;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Current speed on the left; on the right the limit of the next camera, drawn as a road sign. */
final class SpeedPanel extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;

    private int speedKmh;
    private int limitKmh;                 // 0 = camera has no known limit
    private boolean camera;               // a camera is ahead
    private CameraStore.Kind kind = CameraStore.Kind.SPEED;
    private int distM;

    SpeedPanel(Context context) {
        super(context);
        d = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** limitKmh 0 = unknown; camera false hides the sign. */
    void set(int speedKmh, boolean camera, CameraStore.Kind kind, int limitKmh, int distM) {
        this.speedKmh = speedKmh;
        this.camera = camera;
        this.kind = kind;
        this.limitKmh = limitKmh;
        this.distM = distM;
        invalidate();
    }

    /** True when a known limit is exceeded by more than a small margin. */
    private boolean over() {
        return camera && limitKmh > 0 && speedKmh > limitKmh + 2;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension((int) (camera ? 236 * d : 112 * d), (int) (84 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        fill.setColor(0xE60E1116);
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, 22 * d, 22 * d, fill);

        text.setColor(over() ? Ui.FAIL : Ui.TEXT);
        text.setTextSize(34 * d);
        c.drawText(String.valueOf(speedKmh), 52 * d, h / 2 + 8 * d, text);
        text.setColor(Ui.TEXT_2);
        text.setTextSize(11 * d);
        c.drawText("km/h", 52 * d, h / 2 + 26 * d, text);
        if (!camera) return;

        float cx = 160 * d, cy = h / 2 - 4 * d, r = 26 * d;
        if (kind == CameraStore.Kind.RED_LIGHT) {
            fill.setColor(0xFF1B1F27);
            rect.set(cx - 17 * d, cy - r, cx + 17 * d, cy + r);
            c.drawRoundRect(rect, 8 * d, 8 * d, fill);
            int[] colors = {Ui.FAIL, 0xFF3A3F4B, 0xFF3A3F4B};
            for (int i = 0; i < 3; i++) {
                fill.setColor(colors[i]);
                c.drawCircle(cx, cy - 15 * d + i * 15 * d, 6 * d, fill);
            }
        } else {
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, r, fill);
            stroke.setColor(over() ? 0xFFFF1744 : 0xFFD32F2F);
            stroke.setStrokeWidth(6 * d);
            c.drawCircle(cx, cy, r - 3 * d, stroke);
            text.setColor(0xFF111111);
            text.setTextSize((limitKmh >= 100 ? 20 : 24) * d);
            c.drawText(limitKmh > 0 ? String.valueOf(limitKmh) : "?", cx, cy + 8 * d, text);
        }
        text.setColor(Ui.TEXT_2);
        text.setTextSize(11 * d);
        String sub = (distM >= 1000 ? String.format(java.util.Locale.US, "%.1f km", distM / 1000.0) : distM / 10 * 10 + " m")
                + (kind == CameraStore.Kind.AVERAGE ? " · avg" : "");
        c.drawText(sub, cx, h - 6 * d, text);
        if (limitKmh == 0 && kind != CameraStore.Kind.RED_LIGHT) {
            text.setTextSize(9 * d);
            c.drawText("limit unknown", cx, cy + r + 9 * d, text);
        }
    }
}
