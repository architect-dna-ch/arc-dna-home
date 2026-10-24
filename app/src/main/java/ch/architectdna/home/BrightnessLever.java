package ch.architectdna.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * A three-position lever for screen brightness: HI at the top, MID, LO at the bottom.
 * Drag the rust knob or tap a position; it snaps with a small overshoot and a haptic click.
 * Long-press switches to automatic brightness (the knob turns into an outline with "A").
 */
final class BrightnessLever extends View {

    interface Listener {
        /** 0 = LO, 1 = MID, 2 = HI. */
        void onLevel(int level);

        void onAuto();
    }

    private static final String[] LABELS = {"LO", "MID", "HI"};

    private final int ink3, rust, card, line;
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int level = 1;
    private boolean auto, dragging, longPressed;
    private float knob = 1f;           // 0..2, animated towards level
    private ValueAnimator anim;
    private final Runnable longPress = () -> {
        longPressed = true;
        dragging = false;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        setAuto(true);
        BrightnessLever.this.listener.onAuto();
    };

    BrightnessLever(Context c, int[] colours, Listener listener) {
        super(c);
        ink3 = colours[2];
        rust = colours[3];
        card = colours[4];
        line = colours[5];
        this.listener = listener;
        setContentDescription("Helligkeit: LO, MID, HI – lange drücken für automatisch");
    }

    void setLevel(int l, boolean animate) {
        level = Math.max(0, Math.min(2, l));
        auto = false;
        moveKnob(level, animate);
    }

    void setAuto(boolean a) {
        auto = a;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float trackTop() {
        return dp(14);
    }

    private float trackBottom() {
        return getHeight() - dp(14);
    }

    /** y of a position 0 (LO, bottom) .. 2 (HI, top). */
    private float yOf(float pos) {
        return trackBottom() - (trackBottom() - trackTop()) * pos / 2f;
    }

    private void moveKnob(float to, boolean animate) {
        if (anim != null) anim.cancel();
        if (!animate) {
            knob = to;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(knob, to);
        anim.setDuration(260);
        anim.setInterpolator(new OvershootInterpolator(2.2f));
        anim.addUpdateListener(a -> {
            knob = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float pos = Math.max(0f, Math.min(2f, 2f * (trackBottom() - e.getY()) / (trackBottom() - trackTop())));
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            dragging = true;
            longPressed = false;
            postDelayed(longPress, 550);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!dragging) return true;
            if (Math.abs(pos - knob) > 0.15f) removeCallbacks(longPress);
            if (anim != null) anim.cancel();
            knob = pos;
            auto = false;
            invalidate();
        } else if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(longPress);
            if (longPressed) return true;
            dragging = false;
            int snapped = Math.round(pos);
            level = snapped;
            auto = false;
            moveKnob(snapped, true);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            listener.onLevel(snapped);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress);
            dragging = false;
            moveKnob(level, true);
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), trackW = dp(26), tx = w - trackW;
        // Track.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(card);
        rect.set(tx, trackTop() - dp(12), w, trackBottom() + dp(12));
        c.drawRoundRect(rect, trackW / 2, trackW / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(line);
        c.drawRoundRect(rect, trackW / 2, trackW / 2, paint);

        // Notches and labels.
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextSize(dp(9));
        paint.setLetterSpacing(0.1f);
        for (int i = 0; i < 3; i++) {
            float y = yOf(i);
            paint.setStyle(Paint.Style.FILL);
            boolean on = !auto && i == level;
            paint.setColor(on ? rust : ink3);
            String label = LABELS[i];
            c.drawText(label, tx - dp(6) - paint.measureText(label), y + dp(3), paint);
            c.drawCircle(tx + trackW / 2, y, dp(2), paint);
        }

        // Knob: solid rust, or an outline with "A" for automatic.
        float ky = yOf(knob), kx = tx + trackW / 2, r = dp(10);
        if (auto) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(card);
            c.drawCircle(kx, yOf(1), r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(rust);
            c.drawCircle(kx, yOf(1), r, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(10));
            c.drawText("A", kx - paint.measureText("A") / 2, yOf(1) + dp(3.5f), paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(rust);
            c.drawCircle(kx, ky, r, paint);
            paint.setColor(card);
            c.drawCircle(kx, ky, dp(3), paint);
        }
    }
}
