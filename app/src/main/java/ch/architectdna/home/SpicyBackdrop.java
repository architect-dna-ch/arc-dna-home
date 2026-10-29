package ch.architectdna.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The "Spicy" home background: warm beige (deep green at night) with big soft pink and green
 * glows. They drift a little against the scroll (parallax), flare up with the speed of a
 * flick – pink and green speed lines shoot past – and breathe with the music's bass.
 * Still when nothing moves, so it costs nothing at rest.
 */
final class SpicyBackdrop extends View {

    static final int PINK = 0xFFFF3D7F, PINK_NIGHT = 0xFFFF5C93, GREEN = 0xFF1FB86E, GREEN_NIGHT = 0xFF3DDC8F;

    private final boolean night, quiet;
    private final int base, pink, green;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RadialGradient[] glow = new RadialGradient[3];
    private final android.graphics.Matrix m = new android.graphics.Matrix();
    private final Paint streak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] grid;
    private float scrollY, speed, bass, bassAvg = 1;
    private long lastFrame;
    private boolean tapped;

    // Blobs: x, y (0..1 of the screen), radius (× width), parallax, colour index (0 pink, 1 green, 2 pale pink).
    private static final float[][] BLOBS = {
        {0.92f, 0.10f, 0.80f, 0.35f, 0},
        {0.05f, 0.42f, 0.75f, 0.55f, 1},
        {0.85f, 0.78f, 0.70f, 0.25f, 2},
        {0.20f, 1.05f, 0.65f, 0.45f, 1},
    };

    private final AudioTap.Sink sink = fft -> {
        float b = 0;
        for (int k = 1; k < Math.min(7, fft.length / 2); k++) b += (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
        bassAvg = bassAvg * 0.95f + b * 0.05f;
        float hit = Math.max(0, Math.min(1, (b - bassAvg) / (bassAvg + 20)));
        if (hit > bass) {
            bass = hit;
            postInvalidateOnAnimation();
        }
    };

    /** quiet = "Professionell": the glows stay, no speed lines and no beat. */
    SpicyBackdrop(Context c, boolean night, boolean quiet) {
        super(c);
        this.night = night;
        this.quiet = quiet;
        base = night ? 0xFF0E1A14 : 0xFFF2E8D5;
        pink = night ? PINK_NIGHT : PINK;
        green = night ? GREEN_NIGHT : GREEN;
        streak.setStrokeCap(Paint.Cap.ROUND);
        dots.setColor(night ? 0x14F2E8D5 : 0x1413261D);
    }

    /** From the home's scroll listener: position and change since the last call. */
    void scrolled(int y, int dy) {
        scrollY = y;
        speed = Math.max(-1, Math.min(1, speed + dy / (getResources().getDisplayMetrics().density * 400f)));
        postInvalidateOnAnimation();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        boolean want = visibility == VISIBLE && getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
        if (quiet) want = false;
        if (want && !tapped) AudioTap.add(sink);
        else if (!want && tapped) AudioTap.remove(sink);
        tapped = want;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (tapped) AudioTap.remove(sink);
        tapped = false;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        c.drawColor(base);

        // Paper dots: a quiet grid that slides with the page.
        float step = w / 14f, off = -(scrollY * 0.5f) % step;
        int cols = (int) Math.ceil(w / step), rows = (int) Math.ceil(h / step) + 1;
        if (grid == null || grid.length != cols * rows * 2) grid = new float[cols * rows * 2];
        int n = 0;
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                grid[n++] = step / 2 + i * step;
                grid[n++] = off + j * step;
            }
        }
        dots.setStrokeWidth(3.2f);
        dots.setStrokeCap(Paint.Cap.ROUND);
        c.drawPoints(grid, 0, n, dots);

        float flare = Math.abs(speed), pulse = bass;
        for (float[] b : BLOBS) {
            int col = b[4] == 0 ? pink : b[4] == 1 ? green : (night ? 0xFF7A2E4C : 0xFFFFB3CB);
            float cx = b[0] * w;
            float cy = b[1] * h - scrollY * b[3] % (h * 1.4f);
            if (cy < -h * 0.4f) cy += h * 1.4f;
            float r = b[2] * w * (1 + 0.12f * flare + 0.10f * pulse);
            int a = (int) ((night ? 70 : 92) + 80 * flare + 50 * pulse);
            // One unit gradient per colour, moved and scaled by a matrix (no new shader per frame).
            RadialGradient g = glow[(int) b[4]];
            if (g == null) g = glow[(int) b[4]] = new RadialGradient(0, 0, 1, new int[]{col, withAlpha(col, 0)}, null, Shader.TileMode.CLAMP);
            m.setScale(r, r);
            m.postTranslate(cx, cy);
            g.setLocalMatrix(m);
            fill.setShader(g);
            fill.setAlpha(Math.min(200, a));
            c.drawCircle(cx, cy, r, fill);
            fill.setAlpha(255);
        }
        fill.setShader(null);

        // A flick shoots pink and green speed lines across, against the scroll direction.
        if (flare > 0.05f && !quiet) {
            streak.setStrokeWidth(getResources().getDisplayMetrics().density * 3);
            for (int i = 0; i < 9; i++) {
                float seed = (i * 0.618f) % 1f;
                float x = (seed * 1.3f - 0.15f) * w;
                float y = ((seed * 7.3f + now / (speed > 0 ? -900f : 900f)) % 1f + 1f) % 1f * h;
                float len = h * 0.18f * flare;
                streak.setColor(withAlpha(i % 2 == 0 ? pink : green, (int) (170 * flare)));
                c.drawLine(x, y, x, y + (speed > 0 ? len : -len), streak);
            }
        }

        // Settle: speed and beat fade out, then the view stops drawing.
        speed *= (float) Math.pow(0.04, dt);
        bass *= (float) Math.pow(0.02, dt);
        if (Math.abs(speed) > 0.01f || bass > 0.01f) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    private static int withAlpha(int c, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)), Color.red(c), Color.green(c), Color.blue(c));
    }
}
