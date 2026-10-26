package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/**
 * Just unlocked: like Face ID, a lock under the front camera springs open while the camera ring
 * turns from the lock screen's rainbow into the ARC-DNA rust, and the home screen comes into focus
 * (the blur is driven by the launcher through progress()). Touches pass through; removes itself.
 */
final class UnlockView extends View {

    static final long DURATION = 1500;
    private static final int[] HUES = {0xFFFF3B3B, 0xFFFF9A1F, 0xFFFFE31F, 0xFF2BD96B, 0xFF1FA8FF, 0xFF8A4BFF, 0xFFFF3BB0, 0xFFFF3B3B};

    interface Progress {
        void at(float k);          // 0 … 1 over the whole animation
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();
    private final float cx, cy, holeR;
    private final int ink, rust;
    private final Progress progress;
    private final Runnable done;
    private long start = -1;
    private boolean ticked;

    UnlockView(Context c, float cx, float cy, float holeR, int ink, int rust, Progress progress, Runnable done) {
        super(c);
        this.cx = cx;
        this.cy = cy;
        this.holeR = holeR;
        this.ink = ink;
        this.rust = rust;
        this.progress = progress;
        this.done = done;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        setClickable(false);
        setFocusable(false);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (start < 0) start = now;
        float t = (now - start) / (float) DURATION;
        if (progress != null) progress.at(clamp(t));
        if (t >= 1) {
            if (done != null) post(done);
            return;
        }
        // Ring: rainbow, spinning, then melting into rust; it fades at the end.
        float ringR = holeR + dp(5) + dp(3) * (float) Math.sin(clamp(t / 0.3f) * Math.PI);
        float toRust = clamp((t - 0.35f) / 0.3f), fade = 1 - clamp((t - 0.8f) / 0.2f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3));
        SweepGradient sweep = new SweepGradient(cx, cy, HUES, null);
        m.setRotate(-t * 540, cx, cy);
        sweep.setLocalMatrix(m);
        p.setShader(sweep);
        p.setAlpha((int) (255 * (1 - toRust) * fade));
        c.drawCircle(cx, cy, ringR, p);
        p.setShader(null);
        p.setColor(rust);
        p.setAlpha((int) (255 * toRust * fade));
        c.drawCircle(cx, cy, ringR, p);

        // The lock under the camera: pops in, the shackle springs open, then it floats up and fades.
        float pop = clamp(t / 0.18f), open = clamp((t - 0.28f) / 0.2f), out = clamp((t - 0.62f) / 0.3f);
        if (open > 0 && !ticked) {
            ticked = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        }
        float s = dp(13) * (0.6f + 0.4f * pop) * (1 + 0.15f * (float) Math.sin(open * Math.PI));
        float lx = cx, ly = cy + ringR + dp(26) - out * dp(16);
        int a = (int) (255 * pop * (1 - out));
        // Body.
        p.setStyle(Paint.Style.FILL);
        p.setColor(open >= 1 ? rust : ink);
        p.setAlpha(a);
        r.set(lx - s, ly - s * 0.2f, lx + s, ly + s * 1.3f);
        c.drawRoundRect(r, s * 0.3f, s * 0.3f, p);
        p.setColor(Color.WHITE);
        p.setAlpha(a);
        c.drawCircle(lx, ly + s * 0.5f, s * 0.18f, p);
        // Shackle: lifts and swings open around its right leg.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.28f);
        p.setColor(open >= 1 ? rust : ink);
        p.setAlpha(a);
        c.save();
        c.translate(0, -open * s * 0.35f);
        c.rotate(-open * 35, lx + s * 0.62f, ly - s * 0.2f);
        r.set(lx - s * 0.62f, ly - s * 1.15f, lx + s * 0.62f, ly + s * 0.1f);
        c.drawArc(r, 180, 180, false, p);
        c.drawLine(lx - s * 0.62f, ly - s * 0.52f, lx - s * 0.62f, ly - s * 0.2f, p);
        c.drawLine(lx + s * 0.62f, ly - s * 0.52f, lx + s * 0.62f, ly - s * 0.2f, p);
        c.restore();
        postInvalidateOnAnimation();
    }
}
