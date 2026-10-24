package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * "Fuchsjagd": a small landscape runner in the ARC-DNA style. The fox chases the rabbit across a
 * blueprint landscape; tap to jump over the cube obstacles. A crash ("Eeek!") restarts the run.
 * Tap three times quickly to leave (the launcher also leaves on two Home presses).
 */
final class FoxRunView extends View {

    interface Listener {
        void onExit();
    }

    private final int ink, ink3, rust, bg;
    private final Drawable fox, rabbit;
    private final FoxVoice voice;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random random = new Random();
    private final List<float[]> obstacles = new ArrayList<>();   // x, size
    private final long[] taps = new long[3];

    private boolean running;
    private long lastFrame;
    private float t, speed, scroll, distance, foxLift, foxVy, crash;
    private int best;

    FoxRunView(Context c, int[] colours, FoxVoice voice, SharedPreferences prefs, Listener listener) {
        super(c);
        ink = colours[0];
        ink3 = colours[2];
        rust = colours[3];
        bg = colours[6];
        this.voice = voice;
        this.prefs = prefs;
        this.listener = listener;
        fox = c.getDrawable(R.drawable.fox);
        rabbit = c.getDrawable(R.drawable.rabbit);
        best = prefs.getInt("runBest", 0);
        setClickable(true);
    }

    void resume() {
        if (running) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        postOnAnimation(frame);
    }

    void pause() {
        running = false;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float ground() {
        return getHeight() * 0.80f;
    }

    private float foxW() {
        return getHeight() * 0.24f;
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running || !isAttachedToWindow()) {
                running = false;
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            step(dt);
            invalidate();
            postOnAnimation(this);
        }
    };

    private void step(float dt) {
        float w = getWidth();
        if (w == 0) return;
        t += dt;
        if (crash > 0) {
            crash -= dt;
            if (crash <= 0) restart();
            return;
        }
        if (speed == 0) speed = w * 0.42f;
        speed += dp(7) * dt;
        scroll += speed * dt;
        distance += speed * dt / dp(48);

        // Jump physics.
        foxVy += getHeight() * 3.2f * dt;
        foxLift -= foxVy * dt;
        if (foxLift <= 0) {
            foxLift = 0;
            foxVy = 0;
        }

        // Obstacles: little ARC-DNA cubes.
        for (float[] o : obstacles) o[0] -= speed * dt;
        if (!obstacles.isEmpty() && obstacles.get(0)[0] < -dp(80)) obstacles.remove(0);
        float lastX = obstacles.isEmpty() ? 0 : obstacles.get(obstacles.size() - 1)[0];
        if (obstacles.isEmpty() || lastX < w - w * (0.55f + random.nextFloat() * 0.6f)) {
            obstacles.add(new float[]{w + dp(40), getHeight() * (0.09f + random.nextFloat() * 0.06f)});
        }

        // Collision with the fox's body.
        float fx = w * 0.18f, fw = foxW();
        for (float[] o : obstacles) {
            boolean overlap = o[0] < fx + fw * 0.85f && o[0] + o[1] > fx + fw * 0.2f;
            if (overlap && foxLift < o[1] * 0.85f) {
                crash = 1.3f;
                voice.say("Eeek!");
                int d = (int) distance;
                if (d > best) {
                    best = d;
                    prefs.edit().putInt("runBest", best).apply();
                }
                break;
            }
        }
    }

    private void restart() {
        obstacles.clear();
        distance = 0;
        speed = 0;
        foxLift = 0;
        foxVy = 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        long now = SystemClock.uptimeMillis();
        System.arraycopy(taps, 1, taps, 0, 2);
        taps[2] = now;
        if (taps[0] > 0 && now - taps[0] < 700) {
            listener.onExit();
            return true;
        }
        if (crash <= 0 && foxLift == 0) {
            foxVy = -getHeight() * 1.45f;
            voice.say("Yip!");
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), g = ground();
        c.drawColor(bg);

        // Blueprint grid, slow parallax.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor((ink & 0x00FFFFFF) | 0x10000000);
        float cell = dp(32), off = (scroll * 0.25f) % cell;
        for (float x = -off; x < w; x += cell) c.drawLine(x, 0, x, h, paint);
        for (float y = 0; y < h; y += cell) c.drawLine(0, y, w, y, paint);

        // Skyline of cube outlines, mid parallax.
        paint.setStrokeWidth(dp(2));
        paint.setColor((ink & 0x00FFFFFF) | 0x26000000);
        float span = dp(150), soff = (scroll * 0.5f) % span;
        for (float x = -soff - span; x < w + span; x += span) {
            float size = dp(70) + (float) (Math.abs(Math.sin((x + scroll * 0.5f) / span)) * dp(40));
            cube(c, x, g - size * 1.05f, size, false);
        }

        // Ground: the seismograph line, wiggling a little with the run.
        paint.setColor((ink & 0x00FFFFFF) | 0x80000000);
        paint.setStrokeWidth(dp(2));
        path.reset();
        for (float x = 0; x <= w; x += dp(6)) {
            float y = g + (float) Math.sin((x + scroll) / dp(18)) * dp(1.5f) * (crash > 0 ? 4 : 1);
            if (x == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, paint);

        // Obstacles.
        for (float[] o : obstacles) cube(c, o[0], g - o[1], o[1], true);

        // Rabbit ahead, hopping.
        float rw = h * 0.16f, rh = rw * 40 / 48f;
        float rx = w * 0.58f + (float) Math.sin(t * 0.7f) * w * 0.05f;
        float ry = g - rh - (float) Math.abs(Math.sin(t * 8)) * dp(22);
        rabbit.setBounds((int) rx, (int) ry, (int) (rx + rw), (int) (ry + rh));
        rabbit.draw(c);

        // Fox: running bob, tumbling on a crash.
        float fw = foxW(), fh = fw * 64 / 96f, fx = w * 0.18f;
        float fy = g - fh + dp(2) - foxLift - (crash > 0 ? 0 : (float) Math.abs(Math.sin(t * 14)) * dp(4));
        c.save();
        if (crash > 0) c.rotate((1.3f - crash) * 240, fx + fw / 2, fy + fh / 2);
        else if (foxLift > 0) c.rotate(-12, fx + fw / 2, fy + fh / 2);
        fox.setBounds((int) fx, (int) fy, (int) (fx + fw), (int) (fy + fh));
        fox.draw(c);
        c.restore();

        // HUD.
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextSize(dp(12));
        paint.setLetterSpacing(0.14f);
        paint.setColor(rust);
        c.drawText("FUCHSJAGD", dp(20), dp(30), paint);
        paint.setColor(ink);
        c.drawText(String.format(Locale.ROOT, "%05d M   REKORD %05d M", (int) distance, best), dp(20), dp(50), paint);
        paint.setColor(ink3);
        paint.setTextSize(dp(10));
        String hint = crash > 0 ? "EEEK! NOCHMAL …" : "TIPPEN = SPRINGEN · 3× TIPPEN ODER 2× HOME = ZURÜCK";
        c.drawText(hint, w - dp(20) - paint.measureText(hint), dp(30), paint);
    }

    /** ARC-DNA cube outline; obstacles get the rust edge. */
    private void cube(Canvas c, float x, float y, float size, boolean obstacle) {
        float u = size / 14f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (obstacle) {
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(ink);
        }
        path.reset();
        path.moveTo(x + 7 * u, y + u);
        path.lineTo(x + u, y + 4 * u);
        path.lineTo(x + u, y + 10 * u);
        path.lineTo(x + 7 * u, y + 13 * u);
        path.lineTo(x + 13 * u, y + 10 * u);
        path.lineTo(x + 13 * u, y + 4 * u);
        path.close();
        path.moveTo(x + 7 * u, y + u);
        path.lineTo(x + 7 * u, y + 13 * u);
        path.moveTo(x + u, y + 4 * u);
        path.lineTo(x + 7 * u, y + 7 * u);
        c.drawPath(path, paint);
        if (obstacle) {
            paint.setColor(rust);
            c.drawLine(x + 7 * u, y + 7 * u, x + 13 * u, y + 4 * u, paint);
        }
    }
}
