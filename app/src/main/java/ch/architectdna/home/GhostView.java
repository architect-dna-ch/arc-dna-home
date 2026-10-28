package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Geister-Level: the home screen goes almost black and ghosts drift through the dark. Touching
 * turns your finger into a torch: a soft circle 2.5× your thumb's contact size shows the apps
 * underneath (tap, hold and drag work as usual; the view only watches). Big ghosts shy away from
 * the light; mini ghosts zip across and can only be seen inside it.
 */
final class GhostView extends View {

    private static final float DARK = 0.87f;   // just enough of the page shows outside the torch to know where you are

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random random = new Random();
    private final List<float[]> ghosts = new ArrayList<>();   // x, y, vx, vy, size, phase, mini(1/0)
    private float lightX, lightY, lightR, light;              // light = 0..1, fades
    private boolean touching, running;
    private long lastFrame, nextMini;
    private final float pxPerMm;

    GhostView(Context c) {
        super(c);
        pxPerMm = c.getResources().getDisplayMetrics().xdpi / 25.4f;
        hole.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setLayerType(LAYER_TYPE_HARDWARE, null);   // the hole is cut out of this view's own layer
        setClickable(false);
        setFocusable(false);
        setAlpha(0f);
    }

    void start() {
        animate().alpha(1f).setDuration(900).start();
        if (running) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        postOnAnimation(frame);
    }

    void stop(Runnable done) {
        animate().alpha(0f).setDuration(600).withEndAction(() -> {
            running = false;
            if (done != null) done.run();
        }).start();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && running) {
            lastFrame = SystemClock.uptimeMillis();
            removeCallbacks(frame);
            postOnAnimation(frame);
        }
    }

    /** Fed from the activity's dispatchTouchEvent: the view never takes touches itself. */
    void touch(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            touching = false;
            return;
        }
        touching = true;
        lightX = e.getX();
        lightY = e.getY();
        // 2.5× the thumb's contact diameter (Android measures it), at least a 12 mm thumb.
        float thumb = Math.max(e.getTouchMajor(), 12 * pxPerMm);
        // Big screens (tablet) get a bigger torch: the thumb is the same, the page isn't.
        float big = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 2.2f : 1f;
        lightR = Math.min(getWidth() * 0.45f, thumb * 2.5f / 2f * big);
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running || !isAttachedToWindow()) {
                running = false;
                return;
            }
            if (getWindowVisibility() != VISIBLE) return;   // home in the background: rest until visible

            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            step(dt, now);
            invalidate();
            postOnAnimation(this);
        }
    };

    private void step(float dt, long now) {
        float w = getWidth(), h = getHeight();
        if (w == 0) return;
        light += ((touching ? 1f : 0f) - light) * Math.min(1f, dt * (touching ? 14f : 3.5f));
        while (countBig() < 6) ghosts.add(newGhost(w, h, false));
        if (now > nextMini) {
            nextMini = now + 900 + random.nextInt(1600);
            ghosts.add(newGhost(w, h, true));
        }
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            float[] g = ghosts.get(i);
            if (g[6] == 0 && light > 0.2f) {
                // Big ghosts drift away from the torch.
                float dx = g[0] - lightX, dy = g[1] - lightY, d = (float) Math.hypot(dx, dy);
                if (d < lightR * 2.2f && d > 1) {
                    g[2] += dx / d * 260 * dt;
                    g[3] += dy / d * 260 * dt;
                }
            }
            float max = g[6] > 0 ? 520 : 90;
            float sp = (float) Math.hypot(g[2], g[3]);
            if (sp > max) {
                g[2] *= max / sp;
                g[3] *= max / sp;
            }
            g[0] += g[2] * dt;
            g[1] += g[3] * dt + (float) Math.sin(now / 600.0 + g[5]) * 12 * dt;
            boolean out = g[0] < -g[4] * 2 || g[0] > w + g[4] * 2 || g[1] < -g[4] * 2 || g[1] > h + g[4] * 2;
            if (g[6] > 0 && out) ghosts.remove(i);
            else if (g[6] == 0) {
                // Big ghosts wrap around the edges.
                if (g[0] < -g[4] * 2) g[0] = w + g[4];
                if (g[0] > w + g[4] * 2) g[0] = -g[4];
                if (g[1] < -g[4] * 2) g[1] = h + g[4];
                if (g[1] > h + g[4] * 2) g[1] = -g[4];
            }
        }
    }

    private int countBig() {
        int n = 0;
        for (float[] g : ghosts) if (g[6] == 0) n++;
        return n;
    }

    private float[] newGhost(float w, float h, boolean mini) {
        float size = (mini ? 9 : 22 + random.nextFloat() * 18) * getResources().getDisplayMetrics().density;
        if (mini) {
            // Mini ghosts zip in from one side, across the screen.
            boolean left = random.nextBoolean();
            float y = h * (0.1f + random.nextFloat() * 0.8f);
            float v = 260 + random.nextFloat() * 220;
            return new float[]{left ? -size : w + size, y, left ? v : -v, (random.nextFloat() - 0.5f) * 80, size,
                random.nextFloat() * 6.3f, 1};
        }
        return new float[]{random.nextFloat() * w, random.nextFloat() * h, (random.nextFloat() - 0.5f) * 70,
            (random.nextFloat() - 0.5f) * 50, size, random.nextFloat() * 6.3f, 0};
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        c.drawColor((int) (255 * DARK) << 24);

        // Big ghosts glow faintly in the dark.
        for (float[] g : ghosts) if (g[6] == 0) ghost(c, g, 0x2EFFFFFF, now);

        // The torch: a soft round hole in the dark.
        if (light > 0.01f && lightR > 0) {
            int a = (int) (255 * light);
            hole.setShader(new RadialGradient(lightX, lightY, lightR,
                new int[]{a << 24, a << 24, 0}, new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(lightX, lightY, lightR, hole);
            // Mini ghosts only show up inside the light.
            c.save();
            path.reset();
            path.addCircle(lightX, lightY, lightR * 0.95f, Path.Direction.CW);
            c.clipPath(path);
            for (float[] g : ghosts) if (g[6] > 0) ghost(c, g, ((int) (220 * light) << 24) | 0xFFFFFF, now);
            c.restore();
            // A warm torch rim.
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f);
            paint.setColor(((int) (70 * light) << 24) | 0xFFD9A0);
            c.drawCircle(lightX, lightY, lightR * 0.98f, paint);
        }
    }

    /** A little sheet ghost: round head, wavy hem, two dark eyes, bobbing. */
    private void ghost(Canvas c, float[] g, int colour, long now) {
        float x = g[0], y = g[1], s = g[4];
        float bob = (float) Math.sin(now / 350.0 + g[5]) * s * 0.08f;
        y += bob;
        path.reset();
        path.moveTo(x - s, y + s * 1.1f);
        path.lineTo(x - s, y);
        path.arcTo(x - s, y - s, x + s, y + s, 180, 180, false);
        path.lineTo(x + s, y + s * 1.1f);
        float wave = (float) Math.sin(now / 180.0 + g[5]) * s * 0.12f;
        for (int k = 0; k < 4; k++) {
            float x0 = x + s - k * s / 2f, x1 = x0 - s / 2f;
            path.quadTo((x0 + x1) / 2f, y + s * (k % 2 == 0 ? 0.8f : 1.35f) + wave, x1, y + s * 1.1f);
        }
        path.close();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colour);
        c.drawPath(path, paint);
        int alpha = colour >>> 24;
        paint.setColor((Math.min(255, alpha * 3) << 24));
        float ex = g[2] >= 0 ? s * 0.12f : -s * 0.12f;   // eyes look where it's going
        c.drawCircle(x - s * 0.35f + ex, y - s * 0.1f, s * 0.14f, paint);
        c.drawCircle(x + s * 0.35f + ex, y - s * 0.1f, s * 0.14f, paint);
    }
}
