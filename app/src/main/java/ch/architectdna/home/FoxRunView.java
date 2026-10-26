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
 * With the bunny as buddy it turns into "Hasenausflug": the bunny hops through scenes (garden,
 * forest, lake), jumps fences, logs and water, nibbles carrots and watches the butterfly ahead.
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
    private final android.graphics.RectF rectF = new android.graphics.RectF();
    private final Random random = new Random();
    private final List<float[]> obstacles = new ArrayList<>();   // x, size, kind (0 cube/fence, 1 log, 2 water, 3 stone)
    private final List<float[]> carrots = new ArrayList<>();     // x, height above ground, taken
    private final boolean bunny;
    private static final String[] SCENES = {"GARTEN", "WALD", "SEE"};
    private int scene = -1, carrotCount;
    private float sceneBanner;
    private final long[] taps = new long[3];

    private boolean running;
    private long lastFrame;
    private float t, speed, scroll, distance, foxLift, foxVy, crash;
    private int best;

    FoxRunView(Context c, int[] colours, FoxVoice voice, SharedPreferences prefs, Listener listener, boolean bunny) {
        super(c);
        this.bunny = bunny;
        ink = colours[0];
        ink3 = colours[2];
        rust = colours[3];
        bg = colours[6];
        this.voice = voice;
        this.prefs = prefs;
        this.listener = listener;
        fox = c.getDrawable(R.drawable.fox);
        rabbit = c.getDrawable(R.drawable.rabbit);
        best = prefs.getInt(bunny ? "hopBest" : "runBest", 0);
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

        // Bunny trip: a new scene every 250 m.
        if (bunny) {
            int sc = (int) (distance / 250) % SCENES.length;
            if (sc != scene) {
                scene = sc;
                sceneBanner = 2.2f;
                if (distance > 1) voice.say(sc == 0 ? "Mümmel!" : sc == 1 ? "Snff?" : "Platsch?");
            }
            if (sceneBanner > 0) sceneBanner -= dt;
        }

        // Obstacles: little ARC-DNA cubes (bunny: fences, logs, water, stones by scene).
        for (float[] o : obstacles) o[0] -= speed * dt;
        if (!obstacles.isEmpty() && obstacles.get(0)[0] < -dp(160)) obstacles.remove(0);
        float lastX = obstacles.isEmpty() ? 0 : obstacles.get(obstacles.size() - 1)[0];
        if (obstacles.isEmpty() || lastX < w - w * (0.55f + random.nextFloat() * 0.6f)) {
            int kind = 0;
            if (bunny && scene == 1) kind = random.nextFloat() < 0.6f ? 1 : 3;
            else if (bunny && scene == 2) kind = random.nextFloat() < 0.65f ? 2 : 3;
            float size = getHeight() * (0.09f + random.nextFloat() * 0.06f);
            if (kind == 2) size = getHeight() * (0.16f + random.nextFloat() * 0.08f);   // water: width
            obstacles.add(new float[]{w + dp(40), size, kind});
            if (bunny && random.nextFloat() < 0.7f) {
                carrots.add(new float[]{w + dp(40) + size / 2, random.nextBoolean() ? getHeight() * 0.3f : getHeight() * 0.06f, 0});
            }
        }
        for (int i = carrots.size() - 1; i >= 0; i--) {
            float[] k = carrots.get(i);
            k[0] -= speed * dt;
            if (k[0] < -dp(40)) carrots.remove(i);
        }

        // Collision with the fox's body.
        float fx = w * 0.18f, fw = foxW();
        for (float[] k : carrots) {
            float ky = k[1];
            if (k[2] == 0 && k[0] > fx && k[0] < fx + fw && Math.abs(foxLift + fw * 0.25f - ky) < getHeight() * 0.14f) {
                k[2] = 1;
                carrotCount++;
                distance += 10;
                if (carrotCount % 5 == 0) voice.say("Mmmh!");
            }
        }
        for (float[] o : obstacles) {
            boolean overlap = o[0] < fx + fw * 0.85f && o[0] + o[1] > fx + fw * 0.2f;
            boolean hit = o[2] == 2 ? overlap && foxLift == 0 && o[0] < fx + fw * 0.5f && o[0] + o[1] > fx + fw * 0.5f
                : overlap && foxLift < o[1] * (o[2] == 1 ? 0.5f : 0.85f);
            if (hit) {
                crash = 1.3f;
                voice.say(o[2] == 2 ? "Platsch!" : "Eeek!");
                int d = (int) distance;
                if (d > best) {
                    best = d;
                    prefs.edit().putInt(bunny ? "hopBest" : "runBest", best).apply();
                }
                break;
            }
        }
    }

    private void restart() {
        obstacles.clear();
        carrots.clear();
        carrotCount = 0;
        scene = -1;
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
        jump();
        return true;
    }

    /** Tap or a nod of the head (the launcher feeds those in). */
    void jump() {
        if (crash <= 0 && foxLift == 0) {
            foxVy = -getHeight() * 1.45f;
            if (!bunny) voice.say("Yip!");
        }
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

        if (bunny) drawScene(c, w, h, g);
        // Skyline of cube outlines, mid parallax.
        else drawSkyline(c, w, g);
        drawGroundAndRest(c, w, h, g);
    }

    private void drawSkyline(Canvas c, float w, float g) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor((ink & 0x00FFFFFF) | 0x26000000);
        float span = dp(150), soff = (scroll * 0.5f) % span;
        for (float x = -soff - span; x < w + span; x += span) {
            float size = dp(70) + (float) (Math.abs(Math.sin((x + scroll * 0.5f) / span)) * dp(40));
            cube(c, x, g - size * 1.05f, size, false);
        }
    }

    private int alpha(int col, int a) {
        return (col & 0x00FFFFFF) | (a << 24);
    }

    /** Bunny trip backdrops, line drawings in ink with a few colour accents. */
    private void drawScene(Canvas c, float w, float h, float g) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        int sc = Math.max(0, scene);
        // Sun / moon high up.
        paint.setStrokeWidth(dp(2));
        paint.setColor(alpha(rust, 0x80));
        c.drawCircle(w * 0.8f, h * 0.22f, h * 0.07f, paint);
        float far = scroll * 0.3f, mid = scroll * 0.55f;
        if (sc == 0) {
            // Garden: hedge hills far, picket rows and flowers near.
            paint.setColor(alpha(ink, 0x26));
            path.reset();
            for (float x = 0; x <= w; x += dp(8)) {
                float y = g - h * 0.22f - (float) Math.sin((x + far) / dp(90)) * h * 0.05f;
                if (x == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            c.drawPath(path, paint);
            float span = dp(46), off = mid % span;
            paint.setColor(alpha(ink, 0x33));
            for (float x = -off; x < w + span; x += span) {
                c.drawLine(x, g, x, g - h * 0.1f, paint);
                c.drawLine(x - dp(4), g - h * 0.1f + dp(4), x, g - h * 0.1f, paint);
                c.drawLine(x, g - h * 0.1f, x + dp(4), g - h * 0.1f + dp(4), paint);
            }
            c.drawLine(0, g - h * 0.06f, w, g - h * 0.06f, paint);
            float fspan = dp(120), foff = (scroll * 0.8f) % fspan;
            for (float x = -foff; x < w + fspan; x += fspan) {
                paint.setColor(alpha(ink, 0x55));
                c.drawLine(x + dp(30), g, x + dp(30), g - dp(18), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(alpha(rust, 0xB0));
                c.drawCircle(x + dp(30), g - dp(20), dp(4), paint);
                paint.setColor(alpha(0xFFF2C200, 0xC0));
                c.drawCircle(x + dp(30), g - dp(20), dp(1.6f), paint);
                paint.setStyle(Paint.Style.STROKE);
            }
        } else if (sc == 1) {
            // Forest: two layers of pines.
            for (int layer = 0; layer < 2; layer++) {
                float sp = layer == 0 ? dp(70) : dp(110), o = (layer == 0 ? far : mid) % sp;
                paint.setColor(alpha(layer == 0 ? ink : 0xFF2BBF6A, layer == 0 ? 0x22 : 0x55));
                paint.setStrokeWidth(dp(layer == 0 ? 1.5f : 2));
                for (float x = -o - sp; x < w + sp; x += sp) {
                    float base = layer == 0 ? g - h * 0.05f : g;
                    float th = (layer == 0 ? h * 0.35f : h * 0.5f) * (0.8f + 0.2f * (float) Math.abs(Math.sin((x + (layer == 0 ? far : mid)) / sp)));
                    path.reset();
                    path.moveTo(x, base - th);
                    for (int k = 1; k <= 3; k++) {
                        float yy = base - th + th * 0.28f * k, ww = sp * 0.12f * k;
                        path.lineTo(x + ww, yy);
                        path.moveTo(x - ww, yy);
                        path.lineTo(x, base - th + th * 0.28f * (k - 1) + dp(2));
                        path.moveTo(x, base - th + th * 0.28f * k - dp(6));
                    }
                    c.drawPath(path, paint);
                    c.drawLine(x, base - th, x, base, paint);
                }
            }
        } else {
            // Lake: far shore, water lines, reeds and a duck that drifts.
            float wl = g - h * 0.14f;
            paint.setColor(alpha(ink, 0x26));
            path.reset();
            for (float x = 0; x <= w; x += dp(8)) {
                float y = wl - h * 0.05f - (float) Math.abs(Math.sin((x + far) / dp(140))) * h * 0.08f;
                if (x == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            c.drawPath(path, paint);
            paint.setColor(alpha(0xFF3C8DFF, 0x55));
            for (int k = 0; k < 4; k++) {
                float yy = wl + k * h * 0.035f, o = (mid * (1 + k * 0.2f)) % dp(60);
                for (float x = -o; x < w; x += dp(60)) c.drawLine(x, yy, x + dp(26), yy, paint);
            }
            float dx = w - ((scroll * 0.2f + t * dp(10)) % (w + dp(80))), dy = wl + dp(4) + (float) Math.sin(t * 2) * dp(1.5f);
            paint.setColor(alpha(ink, 0x99));
            paint.setStrokeWidth(dp(2));
            path.reset();
            path.moveTo(dx - dp(14), dy);
            path.quadTo(dx, dy + dp(10), dx + dp(14), dy - dp(2));
            path.quadTo(dx + dp(6), dy - dp(4), dx + dp(6), dy - dp(10));
            c.drawPath(path, paint);
            c.drawCircle(dx + dp(8), dy - dp(13), dp(4), paint);
            paint.setColor(rust);
            c.drawLine(dx + dp(12), dy - dp(13), dx + dp(17), dy - dp(12), paint);
            float rs = dp(90), ro = (scroll * 0.8f) % rs;
            paint.setColor(alpha(0xFF2BBF6A, 0x70));
            for (float x = -ro; x < w + rs; x += rs) {
                for (int k = -1; k <= 1; k++) c.drawLine(x + k * dp(5), g, x + k * dp(9), g - dp(26 + 6 * (1 - Math.abs(k))), paint);
            }
        }
        // The butterfly the bunny is watching, fluttering ahead.
        float bx = w * 0.6f + (float) Math.sin(t * 0.6f) * w * 0.12f, by = g - h * 0.35f + (float) Math.sin(t * 1.7f) * h * 0.08f;
        float flap = Math.abs((float) Math.sin(t * 14));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(rust, 0xD0));
        rectF.set(bx - dp(9) * flap, by - dp(7), bx, by + dp(2));
        c.drawOval(rectF, paint);
        rectF.set(bx, by - dp(7), bx + dp(9) * flap, by + dp(2));
        c.drawOval(rectF, paint);
        paint.setColor(ink);
        c.drawRect(bx - dp(1), by - dp(6), bx + dp(1), by + dp(4), paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void drawGroundAndRest(Canvas c, float w, float h, float g) {
        paint.setStyle(Paint.Style.STROKE);

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
        for (float[] o : obstacles) {
            if (o[2] == 0 && bunny) fence(c, o[0], g, o[1]);
            else if (o[2] == 0) cube(c, o[0], g - o[1], o[1], true);
            else if (o[2] == 1) log(c, o[0], g, o[1]);
            else if (o[2] == 2) water(c, o[0], g, o[1]);
            else stone(c, o[0], g, o[1]);
        }
        for (float[] k : carrots) if (k[2] == 0) carrot(c, k[0], g - k[1]);

        // Rabbit ahead, hopping (fox mode only; the bunny is the runner in its own trip).
        if (bunny) {
            drawRunner(c, w, h, g, rabbit, 48 / 40f);
            drawHud(c, w);
            return;
        }
        float rw = h * 0.16f, rh = rw * 40 / 48f;
        float rx = w * 0.58f + (float) Math.sin(t * 0.7f) * w * 0.05f;
        float ry = g - rh - (float) Math.abs(Math.sin(t * 8)) * dp(22);
        rabbit.setBounds((int) rx, (int) ry, (int) (rx + rw), (int) (ry + rh));
        rabbit.draw(c);

        drawRunner(c, w, h, g, fox, 96 / 64f);
        drawHud(c, w);
    }

    /** The player: running bob (bunny: hops), tumbling on a crash. */
    private void drawRunner(Canvas c, float w, float h, float g, Drawable art, float ratio) {
        float fw = foxW(), fh = fw / ratio, fx = w * 0.18f;
        if (bunny) {
            fw *= 0.8f;
            fh = fw / ratio;
        }
        float bob = bunny ? (float) Math.abs(Math.sin(t * 9)) * dp(10) : (float) Math.abs(Math.sin(t * 14)) * dp(4);
        float fy = g - fh + dp(2) - foxLift - (crash > 0 ? 0 : bob);
        c.save();
        if (crash > 0) c.rotate((1.3f - crash) * 240, fx + fw / 2, fy + fh / 2);
        else if (foxLift > 0) c.rotate(-12, fx + fw / 2, fy + fh / 2);
        art.setBounds((int) fx, (int) fy, (int) (fx + fw), (int) (fy + fh));
        art.draw(c);
        c.restore();
    }

    private void drawHud(Canvas c, float w) {
        // HUD: below the status bar and clear of the camera / rounded corners, score big.
        float top = dp(14), left = dp(24), right = dp(24);
        android.view.WindowInsets wi = getRootWindowInsets();
        if (wi != null && android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets in = wi.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            top += in.top;
            left += in.left;
            right += in.right;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setLetterSpacing(0.16f);
        paint.setTextSize(dp(13));
        paint.setColor(rust);
        c.drawText(bunny ? "HASENAUSFLUG" + (scene >= 0 ? " · " + SCENES[scene] : "") : "FUCHSJAGD", left, top + dp(13), paint);
        paint.setColor(ink3);
        paint.setTextSize(dp(11));
        String hint = crash > 0 ? "EEEK! NOCHMAL …" : "TIPPEN ODER KOPF HOCH = SPRINGEN · 3× TIPPEN ODER 2× HOME = ZURÜCK";
        float hintW = paint.measureText(hint);
        // One left-aligned column: title, distance, then record (and carrots) under it.
        paint.setLetterSpacing(0.02f);
        paint.setTextSize(dp(34));
        paint.setColor(ink);
        c.drawText(String.format(Locale.ROOT, "%05d M", (int) distance), left, top + dp(52), paint);
        paint.setLetterSpacing(0.14f);
        paint.setTextSize(dp(12));
        paint.setColor(distance > best && best > 0 ? rust : ink3);
        String rec = distance > best && best > 0 ? "NEUER REKORD!" : String.format(Locale.ROOT, "REKORD %05d M", best);
        c.drawText(rec, left, top + dp(72), paint);
        if (bunny) {
            float rw = paint.measureText(rec + "   ");
            paint.setColor(0xFFF29A1F);
            c.drawText("▼ " + carrotCount + " KAROTTEN", left + rw, top + dp(72), paint);
        }
        paint.setColor(ink3);
        paint.setTextSize(dp(11));
        paint.setLetterSpacing(0.16f);
        // Narrow (portrait) screen: the hint goes under the column instead of top right.
        if (w - right - hintW < left + dp(320)) c.drawText(hint, left, top + dp(92), paint);
        else c.drawText(hint, w - right - hintW, top + dp(13), paint);
        if (bunny && sceneBanner > 0) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(28));
            paint.setColor(alpha(ink, (int) (255 * Math.min(1, sceneBanner))));
            c.drawText("— " + SCENES[Math.max(0, scene)] + " —", w / 2, getHeight() * 0.42f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void fence(Canvas c, float x, float g, float size) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(ink);
        float wdt = size * 0.9f;
        for (int k = 0; k < 3; k++) {
            float px = x + k * wdt / 2;
            c.drawLine(px, g, px, g - size, paint);
            c.drawLine(px - dp(3), g - size + dp(4), px, g - size, paint);
            c.drawLine(px, g - size, px + dp(3), g - size + dp(4), paint);
        }
        paint.setColor(rust);
        c.drawLine(x, g - size * 0.65f, x + wdt, g - size * 0.65f, paint);
        c.drawLine(x, g - size * 0.3f, x + wdt, g - size * 0.3f, paint);
    }

    private void log(Canvas c, float x, float g, float size) {
        float hh = size * 0.55f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(ink);
        rectF.set(x, g - hh, x + size * 1.4f, g);
        c.drawRoundRect(rectF, hh / 2, hh / 2, paint);
        paint.setColor(rust);
        c.drawCircle(x + size * 1.4f - hh / 2, g - hh / 2, hh * 0.25f, paint);
    }

    private void stone(Canvas c, float x, float g, float size) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(ink);
        path.reset();
        path.moveTo(x, g);
        path.lineTo(x + size * 0.15f, g - size * 0.7f);
        path.lineTo(x + size * 0.55f, g - size);
        path.lineTo(x + size * 0.95f, g - size * 0.6f);
        path.lineTo(x + size, g);
        c.drawPath(path, paint);
        paint.setColor(rust);
        c.drawLine(x + size * 0.55f, g - size, x + size * 0.95f, g - size * 0.6f, paint);
    }

    private void water(Canvas c, float x, float g, float wdt) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bg);
        c.drawRect(x, g - dp(4), x + wdt, g + dp(30), paint);
        paint.setColor(alpha(0xFF3C8DFF, 0x40));
        c.drawRect(x, g + dp(3), x + wdt, g + dp(30), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(alpha(0xFF3C8DFF, 0xC0));
        for (int k = 0; k < 3; k++) {
            float yy = g + dp(6) + k * dp(7), o = (float) Math.sin(t * 3 + k) * dp(4);
            c.drawLine(x + dp(8) + o, yy, x + wdt - dp(8) + o, yy, paint);
        }
        paint.setColor(ink);
        c.drawLine(x, g - dp(3), x, g + dp(12), paint);
        c.drawLine(x + wdt, g - dp(3), x + wdt, g + dp(12), paint);
    }

    private void carrot(Canvas c, float x, float y) {
        float s = dp(11), wob = (float) Math.sin(t * 4 + x / dp(50)) * dp(2);
        y += wob;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF29A1F);
        path.reset();
        path.moveTo(x - s * 0.45f, y - s);
        path.lineTo(x + s * 0.45f, y - s);
        path.lineTo(x, y + s);
        path.close();
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFF2BBF6A);
        c.drawLine(x, y - s, x - dp(4), y - s - dp(7), paint);
        c.drawLine(x, y - s, x + dp(4), y - s - dp(7), paint);
        c.drawLine(x, y - s, x, y - s - dp(8), paint);
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
