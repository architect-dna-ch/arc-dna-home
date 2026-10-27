package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.widget.ImageView;

/**
 * The home fox with a living eye: it looks at you (and follows your head tilt while the camera
 * runs), and as soon as you touch the screen it watches your finger; a moment after you let go
 * it looks back at you. The pupil is drawn over the drawable's own eye.
 */
final class FoxView extends ImageView {

    /** The eye in the drawable's 96 × 64 viewport (set per buddy). */
    private float EYE_X = 75f, EYE_Y = 31f, EYE_R = 3.6f, PUPIL_R = 1.8f;
    private static final float REACH = 1.6f;
    private static final long BACK_TO_YOU_MS = 1500;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int ink, paper;
    private final int[] at = new int[2];
    private float fingerX, fingerY, userTilt;
    private boolean touching;
    private long releasedAt = -1;
    private float px, py;     // current pupil offset, in viewport units

    FoxView(Context c, int ink, int paper) {
        super(c);
        this.ink = ink;
        this.paper = paper;
        setImageResource(R.drawable.fox);
    }

    /** Swap the buddy: drawable (96 × 64 frame) and where its eye sits. */
    void buddy(int drawable, float eyeX, float eyeY, float eyeR, float pupilR) {
        setImageResource(drawable);
        EYE_X = eyeX;
        EYE_Y = eyeY;
        EYE_R = eyeR;
        PUPIL_R = pupilR;
        invalidate();
    }

    /* ---- Watching the F1 car: the eye follows it, a little behind; too fast and it gets dizzy ---- */

    private float watchX, watchY;
    private long watchUntil, dizzyUntil;
    private final android.graphics.Path spiral = new android.graphics.Path();

    /** Something to look at (raw screen coordinates), for the next moment. */
    void watch(float rawX, float rawY) {
        watchX = rawX;
        watchY = rawY;
        watchUntil = SystemClock.uptimeMillis() + 250;
        postInvalidateOnAnimation();
    }

    /** Lost it: the pupil spins round and round for a while. */
    void dizzy(long ms) {
        dizzyUntil = SystemClock.uptimeMillis() + ms;
        postInvalidateOnAnimation();
    }

    /** A finger on the screen (raw screen coordinates); null-touch = released. */
    void finger(float rawX, float rawY, boolean down) {
        touching = down;
        if (down) {
            fingerX = rawX;
            fingerY = rawY;
        } else {
            releasedAt = SystemClock.uptimeMillis();
        }
        postInvalidateOnAnimation();
    }

    /** Your head tilt from the front camera, in degrees (positive = looking up). */
    void userTilt(float degrees) {
        userTilt = degrees;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float s = getWidth() / 96f;
        if (s <= 0 || EYE_R <= 0) return;         // no eye (the racer)
        float ex = EYE_X * s, ey = EYE_Y * s;

        // Dizzy: the pupil whirls round the eye with a spiral inside, then settles.
        long now = SystemClock.uptimeMillis();
        if (now < dizzyUntil) {
            float spin = now / 45f, left = (dizzyUntil - now) / 1500f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(paper);
            c.drawCircle(ex, ey, (EYE_R - 0.4f) * s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.5f * s);
            paint.setColor(ink);
            spiral.reset();
            for (int i = 0; i <= 40; i++) {
                double a = spin + i * 0.45, rr = (EYE_R - 0.6f) * s * i / 40f;
                float x = ex + (float) (Math.cos(a) * rr), y = ey + (float) (Math.sin(a) * rr);
                if (i == 0) spiral.moveTo(x, y);
                else spiral.lineTo(x, y);
            }
            c.drawPath(spiral, paint);
            paint.setStyle(Paint.Style.FILL);
            float orbit = REACH * s * Math.min(1f, left + 0.3f);
            c.drawCircle(ex + (float) Math.cos(spin * 1.7) * orbit, ey + (float) Math.sin(spin * 1.7) * orbit, PUPIL_R * 0.8f * s, paint);
            px = py = 0;
            postInvalidateOnAnimation();
            return;
        }
        // Where to look: the car, the finger, or back at you (straight out, nudged by your head tilt).
        float tx = 0, ty;
        boolean onFinger = touching || (releasedAt >= 0 && SystemClock.uptimeMillis() - releasedAt < BACK_TO_YOU_MS);
        boolean onCar = !touching && now < watchUntil;
        if (onCar) {
            getLocationOnScreen(at);
            float dx = watchX - (at[0] + ex), dy = watchY - (at[1] + ey);
            float d = (float) Math.hypot(dx, dy), k = REACH / Math.max(1f, d);
            tx = dx * k;
            ty = dy * k;
        } else if (onFinger) {
            getLocationOnScreen(at);
            float dx = fingerX - (at[0] + ex), dy = fingerY - (at[1] + ey);
            float d = (float) Math.hypot(dx, dy);
            float k = Math.min(1f, d / (60 * s)) * REACH / Math.max(1f, d);
            tx = dx * k;
            ty = dy * k;
        } else {
            ty = Math.max(-REACH, Math.min(REACH, -userTilt * 0.12f));
        }
        // Ease towards the target so the eye glides (a bit slower when chasing the car).
        float follow = onCar ? 0.22f : 0.35f;
        px += (tx - px) * follow;
        py += (ty - py) * follow;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(paper);
        c.drawCircle(ex, ey, (EYE_R - 0.4f) * s, paint);
        paint.setColor(ink);
        c.drawCircle(ex + px * s, ey + py * s, PUPIL_R * s, paint);

        if (Math.abs(tx - px) > 0.02f || Math.abs(ty - py) > 0.02f || onFinger || onCar) postInvalidateOnAnimation();
    }
}
