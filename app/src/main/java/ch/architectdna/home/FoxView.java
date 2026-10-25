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
        if (s <= 0) return;
        float ex = EYE_X * s, ey = EYE_Y * s;

        // Where to look: the finger, or back at you (straight out, nudged by your head tilt).
        float tx = 0, ty;
        boolean onFinger = touching || (releasedAt >= 0 && SystemClock.uptimeMillis() - releasedAt < BACK_TO_YOU_MS);
        if (onFinger) {
            getLocationOnScreen(at);
            float dx = fingerX - (at[0] + ex), dy = fingerY - (at[1] + ey);
            float d = (float) Math.hypot(dx, dy);
            float k = Math.min(1f, d / (60 * s)) * REACH / Math.max(1f, d);
            tx = dx * k;
            ty = dy * k;
        } else {
            ty = Math.max(-REACH, Math.min(REACH, -userTilt * 0.12f));
        }
        // Ease towards the target so the eye glides.
        px += (tx - px) * 0.35f;
        py += (ty - py) * 0.35f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(paper);
        c.drawCircle(ex, ey, (EYE_R - 0.4f) * s, paint);
        paint.setColor(ink);
        c.drawCircle(ex + px * s, ey + py * s, PUPIL_R * s, paint);

        if (Math.abs(tx - px) > 0.02f || Math.abs(ty - py) > 0.02f || onFinger) postInvalidateOnAnimation();
    }
}
