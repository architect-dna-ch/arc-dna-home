package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Race day over the whole home: while the F1 board is open and the buddy is the racer, a circuit
 * runs round the screen (kerbs, a chicane, the start line) and five cars go wild on it — each its
 * own pace that keeps changing, overtakes, slides wide in the corners with sparks and streaks.
 * Touches pass straight through to the home underneath.
 */
final class RaceOverlay extends View {

    private static final int[][] LIVERY = {      // body, accent
        {0xFFB5271E, 0xFFF4F2EC}, {0xFFFF8000, 0xFF1A1A1A}, {0xFF00A19C, 0xFFC4C4BE}, {0xFF1E41FF, 0xFFFFD600}, {0xFFF4F2EC, 0xFFB5271E}};
    private static final String[] TAG = {"ARC", "PAP", "SIL", "BUL", "DNA"};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path track = new Path(), car = new Path();
    private PathMeasure measure;
    private float length;
    private final Random rnd = new Random();
    private final List<Car> cars = new ArrayList<>();
    private final List<float[]> sparks = new ArrayList<>();      // x, y, vx, vy, age
    private final float[] pos = new float[2], tan = new float[2];
    private long last;
    private boolean running;

    /*
     * A race of 5 laps the buddy (ARC, the rust car) wins: he rolls in from the middle of the screen
     * to the back of the grid and says hello, five red lights, lights out, he fights his way to the
     * front and takes the flag; then the next race. Now and then, when he's right behind someone,
     * the view dives into a full-screen chase cam behind him for the overtake.
     */
    private static final int INTRO = 0, RACE = 1, FINISH = 2;
    private static final int LAPS = 5;
    private static final long ROLL_IN = 1400, HELLO = 2600, LIGHT = 450, ZOOM = 3400;
    private int phase = INTRO;
    private long phaseAt, zoomAt = -1, nextZoom, lightsOutAt;
    private int passes;
    private float zoomAngle;

    private static final class Car {
        int livery;
        float s, speed, target, wide, wideTarget, lane, turn;
        float x, y, angle;
        long nextChange;
    }

    RaceOverlay(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);
        for (int i = 0; i < 5; i++) {
            Car k = new Car();
            k.livery = i;
            cars.add(k);
        }
        grid();
    }

    /** Everyone back on the grid; the buddy starts last so he has to work for it. */
    /*
     * The dino during a race: no big visit on top of it all, he comes as a small dino, walks onto the
     * track ahead of the buddy and gets run over: CRASH, he spins off the screen.
     */
    private float dinoS = -1, dinoSide, dinoX, dinoY, dinoVX, dinoVY, dinoSpin;
    private long dinoAt, dinoHit;

    /** From the home instead of the dino visit while the race is on. */
    void miniDino() {
        if (dinoS >= 0 || phase != RACE) return;
        dinoS = buddy().s + 0.22f;
        dinoSide = rnd.nextBoolean() ? 1 : -1;
        dinoAt = SystemClock.uptimeMillis();
        dinoHit = 0;
        dinoSpin = 0;
    }

    private void updateDino(long now, float dt) {
        if (dinoS < 0) return;
        if (dinoHit == 0) {
            float at = ((dinoS % 1) + 1) % 1 * length;
            measure.getPosTan(at, pos, tan);
            float walk = Math.min(1, (now - dinoAt) / 1200f);
            float side = dinoSide * dp(70) * (1 - walk) + dinoSide * dp(4) * walk;
            if (now - dinoAt > 15000) side = dinoSide * dp(4) + dinoSide * dp(90) * Math.min(1, (now - dinoAt - 15000) / 1200f);      // gives up, walks off
            dinoX = pos[0] - tan[1] * side;
            dinoY = pos[1] + tan[0] * side;
            Car me = buddy();
            if (walk >= 1 && Math.hypot(me.x - dinoX, me.y - dinoY) < dp(26)) {
                dinoHit = now;
                float sp = Math.max(dp(300), me.speed);
                dinoVX = tan[0] * sp * 0.8f + (rnd.nextFloat() - 0.5f) * dp(200);
                dinoVY = tan[1] * sp * 0.8f - dp(260);
                me.speed *= 0.6f;
                for (int i = 0; i < 26; i++) {
                    sparks.add(new float[]{dinoX, dinoY, (rnd.nextFloat() - 0.5f) * dp(320), (rnd.nextFloat() - 0.5f) * dp(320), 0});
                }
            }
            if (now - dinoAt > 16500) dinoS = -1;
        } else {
            dinoX += dinoVX * dt;
            dinoY += dinoVY * dt;
            dinoVY += dp(700) * dt;
            dinoSpin += 720 * dt;
            if (now - dinoHit > 1800) dinoS = -1;
        }
    }

    /** The small dino: green body, rust back plates, little legs walking; dizzy stars once hit. */
    private void drawMiniDino(Canvas c, long now) {
        if (dinoS < 0) return;
        float k = dp(1) * 0.9f;
        c.save();
        c.translate(dinoX, dinoY);
        c.rotate(dinoSpin);
        if (dinoX > getWidth() / 2f) c.scale(-1, 1);
        p.setStyle(Paint.Style.FILL);
        float step = dinoHit == 0 ? (float) Math.sin(now / 90.0) * 3 * k : 0;
        p.setColor(0xFFF2F0EA);
        c.drawRect(-6 * k + step, 4 * k, -3 * k + step, 14 * k, p);
        c.drawRect(4 * k - step, 4 * k, 7 * k - step, 14 * k, p);
        p.setColor(0xFFD9472F);
        car.reset();
        for (int i = 0; i < 4; i++) {
            float x0 = -12 * k + i * 6 * k;
            car.moveTo(x0, -4 * k);
            car.lineTo(x0 + 3 * k, -13 * k);
            car.lineTo(x0 + 6 * k, -4 * k);
        }
        c.drawPath(car, p);
        p.setColor(0xFF5A7A55);
        c.drawOval(-14 * k, -7 * k, 12 * k, 7 * k, p);
        car.reset();
        car.moveTo(-12 * k, 0);
        car.lineTo(-26 * k, 4 * k);
        car.lineTo(-12 * k, 4 * k);
        car.close();
        c.drawPath(car, p);
        c.drawRoundRect(8 * k, -16 * k, 20 * k, -8 * k, 4 * k, 4 * k, p);
        c.drawRect(9 * k, -10 * k, 13 * k, 0, p);
        p.setColor(0xFFF2F0EA);
        c.drawCircle(16 * k, -13 * k, 2 * k, p);
        c.restore();
        if (dinoHit != 0) {
            float t = (now - dinoHit) / 1800f;
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(26));
            p.setColor(0xFFE8B931);
            p.setAlpha((int) (255 * Math.max(0, 1 - t)));
            c.drawText("CRASH!", buddy().x, buddy().y - dp(34), p);
            p.setAlpha(255);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void grid() {
        dinoS = -1;
        for (Car k : cars) {
            int slot = k.livery == 0 ? 4 : k.livery - 1;
            k.s = -slot * 0.045f;
            k.lane = (slot % 2 == 0 ? -1 : 1) * dp(6);
            k.speed = 0;
            k.target = 0;
            k.nextChange = 0;
        }
        phase = INTRO;
        phaseAt = 0;
        lightsOutAt = 0;
        variant = (variant + 1) % TRACKS.length;       // a different circuit each race
        build(getWidth(), getHeight());
        zoomAt = -1;
        passes = 0;
        sparks.clear();
    }

    void start() {
        if (!running) grid();
        running = true;
        last = 0;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(400).start();
        invalidate();
    }

    void stop() {
        running = false;
        animate().alpha(0f).setDuration(300).withEndAction(() -> setVisibility(GONE)).start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return false;        // the home stays usable underneath
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int topInset;

    @Override
    public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets in) {
        int t = in.getSystemWindowInsetTop();
        float cx = -1, cy = -1, cr = 0;
        if (android.os.Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
            for (android.graphics.Rect b : in.getDisplayCutout().getBoundingRects()) {
                if (b.top <= dp(4) && b.width() < getResources().getDisplayMetrics().widthPixels / 3) {
                    cx = b.exactCenterX();
                    cy = b.exactCenterY();
                    cr = Math.max(b.width(), b.height()) / 2f;
                }
            }
        }
        if (t != topInset || cx != camX || cy != camY) {
            topInset = t;
            camX = cx;
            camY = cy;
            camR = cr;
            if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
        }
        return super.onApplyWindowInsets(in);
    }

    /** The front camera (punch hole) in view coordinates, or -1 without one (then: top centre, in the bezel). */
    private float camX = -1, camY = -1, camR;
    private float pillY;
    /** Which circuit: changes every race. */
    private int variant;
    private static final String[] TRACKS = {"ARC RING", "MONACO", "SPA", "MONZA"};

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        build(w, h);
    }

    /*
     * The circuits: round the edges of the screen, clockwise, each with its own right side, bottom and
     * left side; the top straight always makes a loop round the front camera (an Ω with the lens in
     * the middle). The top straight stays below the status bar (cars vanished under the icons).
     */
    private void build(float w, float h) {
        if (w <= 0 || h <= 0) return;
        float m = dp(26), r = dp(Math.min(70, Math.min(w, h) / 10 / getResources().getDisplayMetrics().density + 30));
        float top = Math.max(m, topInset + dp(20));
        float cx = camX >= 0 ? camX : w / 2, cy = camX >= 0 ? camY : dp(14);
        float R = Math.max(dp(26), camR + dp(18));
        pillY = top + dp(26);
        track.reset();
        float start = m + r + (cx - dp(70) - m - r) * 0.35f;
        track.moveTo(start, top);
        // The Ω round the camera: in at 160°, over the top, out at 20°.
        double a1 = Math.toRadians(160), a2 = Math.toRadians(20);
        float inX = cx + R * (float) Math.cos(a1), inY = cy + R * (float) Math.sin(a1);
        float outX = cx + R * (float) Math.cos(a2), outY = cy + R * (float) Math.sin(a2);
        float k = dp(30);
        track.lineTo(cx - dp(70), top);
        track.cubicTo(cx - dp(30), top, inX + 0.34f * k, inY + 0.94f * k, inX, inY);
        track.arcTo(new RectF(cx - R, cy - R, cx + R, cy + R), 160, 220, false);
        track.cubicTo(outX - 0.34f * k, outY + 0.94f * k, cx + dp(30), top, cx + dp(70), top);
        track.lineTo(w - m - r, top);
        track.quadTo(w - m, top, w - m, top + r);
        // Right side.
        switch (variant) {
            case 0:
                track.lineTo(w - m, h * 0.55f);
                track.cubicTo(w - m, h * 0.62f, w * 0.62f, h * 0.6f, w * 0.6f, h * 0.68f);      // swings inwards
                track.cubicTo(w * 0.58f, h * 0.76f, w - m, h * 0.74f, w - m, h * 0.82f);
                break;
            case 2:                                                                             // esses
                track.lineTo(w - m, h * 0.3f);
                track.cubicTo(w - m, h * 0.38f, w * 0.72f, h * 0.36f, w * 0.72f, h * 0.45f);
                track.cubicTo(w * 0.72f, h * 0.54f, w - m, h * 0.52f, w - m, h * 0.6f);
                track.cubicTo(w - m, h * 0.68f, w * 0.78f, h * 0.66f, w * 0.78f, h * 0.73f);
                track.cubicTo(w * 0.78f, h * 0.8f, w - m, h * 0.78f, w - m, h * 0.85f);
                break;
            default:
                break;
        }
        track.lineTo(w - m, h - m - r);
        track.quadTo(w - m, h - m, w - m - r, h - m);
        // Bottom.
        if (variant == 1) {                                                                    // the hairpin up into the middle
            track.lineTo(w * 0.66f, h - m);
            track.cubicTo(w * 0.56f, h - m, w * 0.62f, h * 0.5f, w * 0.5f, h * 0.5f);
            track.cubicTo(w * 0.38f, h * 0.5f, w * 0.44f, h - m, w * 0.34f, h - m);
        } else if (variant == 3) {                                                             // chicane
            track.lineTo(w * 0.6f, h - m);
            track.cubicTo(w * 0.55f, h - m, w * 0.55f, h - m - dp(36), w * 0.5f, h - m - dp(36));
            track.cubicTo(w * 0.45f, h - m - dp(36), w * 0.45f, h - m, w * 0.4f, h - m);
        }
        track.lineTo(m + r, h - m);
        track.quadTo(m, h - m, m, h - m - r);
        // Left side.
        if (variant == 0) {
            track.lineTo(m, h * 0.6f);
            track.cubicTo(m, h * 0.5f, w * 0.3f, h * 0.48f, w * 0.3f, h * 0.4f);              // the chicane
            track.cubicTo(w * 0.3f, h * 0.32f, m, h * 0.3f, m, h * 0.22f);
        } else if (variant == 3) {                                                             // a long sweep inwards
            track.lineTo(m, h * 0.72f);
            track.cubicTo(m, h * 0.6f, w * 0.38f, h * 0.62f, w * 0.38f, h * 0.48f);
            track.cubicTo(w * 0.38f, h * 0.34f, m, h * 0.36f, m, h * 0.26f);
        } else if (variant == 1) {
            track.lineTo(m, h * 0.5f);
            track.cubicTo(m, h * 0.42f, w * 0.16f, h * 0.4f, w * 0.16f, h * 0.33f);
            track.cubicTo(w * 0.16f, h * 0.26f, m, h * 0.26f, m, h * 0.2f);
        }
        track.lineTo(m, top + r);
        track.quadTo(m, top, m + r, top);
        track.lineTo(start, top);
        track.close();
        measure = new PathMeasure(track, true);
        length = measure.getLength();
    }

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Something big above the race (the dino fight, the black hole, the crumple …): no chase cam then. */
    private boolean covered() {
        if (!(getParent() instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup g = (android.view.ViewGroup) getParent();
        for (int i = g.indexOfChild(this) + 1; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() == VISIBLE && v.getAlpha() > 0.05f
                && v.getWidth() > g.getWidth() / 2 && v.getHeight() > g.getHeight() / 3) return true;
        }
        return false;
    }

    private Car buddy() {
        return cars.get(0);
    }

    /** 1 = leading … 5. */
    private int place(Car k) {
        int n = 1;
        for (Car o : cars) if (o != k && o.s > k.s) n++;
        return n;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (measure == null || length <= 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        if (phaseAt == 0) phaseAt = now;
        float w = getWidth(), h = getHeight();
        long inPhase = now - phaseAt;
        Car me = buddy();

        // Start lights: one every LIGHT ms after the hello, out after a short random wait.
        if (phase == INTRO && lightsOutAt == 0) lightsOutAt = HELLO + 5 * LIGHT + 300 + rnd.nextInt(700);
        if (phase == INTRO && inPhase > lightsOutAt) {
            phase = RACE;
            phaseAt = now;
            lightsOutAt = 0;
            nextZoom = now + 6000 + rnd.nextInt(5000);
            inPhase = 0;
        }
        boolean racing = running && phase == RACE;
        int myPlace = place(me);
        int myLap = (int) Math.floor(Math.max(0, me.s));

        // Chase cam: when he's closing in on someone, now and then.
        boolean busy = covered();
        if (busy && zoomAt >= 0 && now - zoomAt < ZOOM - 450) zoomAt = now - (ZOOM - 450);      // something else on top: fade out
        if (racing && !busy && zoomAt < 0 && now > nextZoom && myPlace > 1) {
            float gap = Float.MAX_VALUE;
            for (Car o : cars) if (o != me) gap = Math.min(gap, ((o.s - me.s) % 1 + 1) % 1 * length);
            if (gap < dp(140)) {
                zoomAt = now;
                zoomAngle = me.angle;
            }
        }
        if (zoomAt >= 0 && now - zoomAt > ZOOM) {
            zoomAt = -1;
            nextZoom = now + 14000 + rnd.nextInt(12000);
        }
        boolean zooming = zoomAt >= 0;

        // Cars: each keeps changing its pace (that's the overtaking), and in tight corners runs wide.
        float maxOther = -9;
        for (Car o : cars) if (o != me) maxOther = Math.max(maxOther, o.s);
        int placeBefore = myPlace;
        for (Car k : cars) {
            if (racing) {
                // Real racing: everyone about the same pace (small swings), braking for corners, and the
                // pack stays together (no one lapped). He works his way up one place at a time: P5 at the
                // start, the lead only on the last lap.
                if (now > k.nextChange) {
                    k.target = dp(360) + rnd.nextFloat() * dp(70);
                    k.nextChange = now + 1200 + rnd.nextInt(2000);
                }
                float want = k.target * (1 - Math.min(0.4f, Math.abs(k.turn) / 110f));
                float lead = -9;
                for (Car o : cars) lead = Math.max(lead, o.s);
                float behind = (lead - k.s) * length;
                if (behind > dp(260)) want += Math.min(dp(90), (behind - dp(260)) * 0.25f);      // catch the pack up
                if (k == me) {
                    float progress = Math.max(0, me.s) / LAPS;
                    int aim = progress > 0.82f ? 1 : Math.max(2, 5 - (int) (progress * 4.2f));
                    if (myPlace > aim) want += dp(progress > 0.82f ? 70 : 38);                    // push to the next place
                    else if (myPlace < aim) want -= dp(30);                                         // not too early
                    if (zooming && myPlace > aim) want += dp(30);
                }
                k.speed += (want - k.speed) * Math.min(1, dt * 1.6f);
            } else if (phase == FINISH) {
                k.speed += (dp(120) - k.speed) * Math.min(1, dt * 0.8f);      // cool-down lap
            } else {
                k.speed *= 0.9f;
            }
            k.s += k.speed * dt / length;
            float at = ((k.s % 1) + 1) % 1 * length;
            measure.getPosTan(at, pos, tan);
            float ang = (float) Math.toDegrees(Math.atan2(tan[1], tan[0]));
            // Curvature: how fast the direction turns just ahead.
            float[] p2 = new float[2], t2 = new float[2];
            measure.getPosTan((at + dp(40)) % length, p2, t2);
            float turn = (float) Math.toDegrees(Math.atan2(t2[1], t2[0])) - ang;
            while (turn > 180) turn -= 360;
            while (turn < -180) turn += 360;
            k.turn = turn;
            k.wideTarget = -Math.signum(turn) * Math.min(dp(11), Math.abs(turn) * k.speed / dp(400) * dp(0.4f));
            k.wide += (k.wideTarget - k.wide) * Math.min(1, dt * 4);
            // Someone just ahead: pull out to the side and pass (no more cars on top of each other).
            float ahead = Float.MAX_VALUE, aheadLane = 0;
            for (Car o : cars) {
                if (o == k) continue;
                float gap = ((o.s - k.s) % 1 + 1) % 1 * length;
                if (gap < ahead) {
                    ahead = gap;
                    aheadLane = o.lane;
                }
            }
            if (phase != INTRO) {
                float laneTarget = ahead < dp(46) ? (aheadLane > 0 ? -dp(9) : dp(9)) : (k.livery - 2) * dp(2);
                k.lane += (laneTarget - k.lane) * Math.min(1, dt * 5);
                if (ahead < dp(20) && k != me) k.speed = Math.max(k.speed * 0.98f, 0);       // brakes rather than drive through
            }
            float nx = -tan[1], ny = tan[0];
            k.x = pos[0] + nx * (k.wide + k.lane);
            k.y = pos[1] + ny * (k.wide + k.lane);
            float slide = turn * 0.6f * Math.min(1, k.speed / dp(400));      // drift angle in the corner
            k.angle = ang + slide;
            if (racing && Math.abs(turn) > 18 && k.speed > dp(330) && rnd.nextFloat() < 0.5f) {
                sparks.add(new float[]{k.x - tan[0] * dp(14), k.y - tan[1] * dp(14),
                    -tan[0] * dp(60) + (rnd.nextFloat() - 0.5f) * dp(80), -tan[1] * dp(60) + (rnd.nextFloat() - 0.5f) * dp(80), 0});
            }
        }
        myPlace = place(me);
        if (zooming && myPlace < placeBefore) passes = 1400;          // "ÜBERHOLT!" flash (ms)
        if (passes > 0) passes -= (int) (dt * 1000);
        updateDino(now, dt);
        if (racing && Math.floor(me.s) >= LAPS) {
            phase = FINISH;
            phaseAt = now;
            zoomAt = -1;
            sparks.clear();
            for (int i = 0; i < 60; i++) {            // confetti
                sparks.add(new float[]{w / 2, h * 0.3f, (rnd.nextFloat() - 0.5f) * dp(500), -rnd.nextFloat() * dp(400), -rnd.nextFloat() * 0.6f});
            }
        }
        if (phase == FINISH && now - phaseAt > 6000) grid();

        // The rolling-in: from the middle of the screen to his grid slot, bigger, then settles.
        float roll = phase == INTRO ? ease(inPhase / (float) ROLL_IN) : 1;
        if (roll < 1) {
            me.x = w / 2 + (me.x - w / 2) * roll;
            me.y = h * 0.5f + (me.y - h * 0.5f) * roll;
        }

        // Chase cam amount: dives in and out.
        float z = 0;
        if (zooming) {
            long t = now - zoomAt;
            z = ease(Math.min(t, ZOOM - t) / 450f);
            float d = me.angle - zoomAngle;
            while (d > 180) d -= 360;
            while (d < -180) d += 360;
            zoomAngle += d * Math.min(1, dt * 3);      // the camera swings after him, smoothly
        }

        c.save();
        if (z > 0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2A28);
            p.setAlpha((int) (255 * z));
            c.drawRect(0, 0, w, h, p);
            p.setAlpha(255);
            c.translate(me.x + (w / 2 - me.x) * z, me.y + (h * 0.68f - me.y) * z);
            float rot = -90 - zoomAngle;
            while (rot > 180) rot -= 360;
            while (rot < -180) rot += 360;
            c.rotate(rot * z);
            c.scale(1 + 3.2f * z, 1 + 3.2f * z);
            c.translate(-me.x, -me.y);
        }

        // Track: tarmac band, red-white kerbs on the edges, dashed middle, start line.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setPathEffect(null);
        if (z > 0) {            // up close: grass beside the road
            p.setStrokeWidth(dp(90));
            p.setColor(0xFF1F3A22);
            p.setAlpha((int) (255 * z));
            c.drawPath(track, p);
        }
        p.setStrokeWidth(dp(34));
        p.setColor(0x38303030);
        c.drawPath(track, p);
        p.setStrokeWidth(dp(36));
        p.setPathEffect(new DashPathEffect(new float[]{dp(10), dp(10)}, 0));
        p.setColor(z > 0 ? 0xFFB5271E : 0x44B5271E);
        c.drawPath(track, p);
        p.setStrokeWidth(dp(30));
        p.setPathEffect(null);
        p.setColor(z > 0 ? 0xFF3A3A38 : 0x5C242424);
        c.drawPath(track, p);
        p.setStrokeWidth(dp(1.5f));
        p.setPathEffect(new DashPathEffect(new float[]{dp(12), dp(14)}, 0));
        p.setColor(0x66F4F2EC);
        c.drawPath(track, p);
        p.setPathEffect(null);
        measure.getPosTan(0, pos, tan);
        chequer(c, pos[0], pos[1]);

        // Sparks and confetti.
        p.setStyle(Paint.Style.FILL);
        for (int i = sparks.size() - 1; i >= 0; i--) {
            float[] sp = sparks.get(i);
            sp[4] += dt;
            if (sp[4] > (phase == FINISH ? 2.5f : 0.45f)) {
                sparks.remove(i);
                continue;
            }
            if (sp[4] < 0) continue;
            sp[0] += sp[2] * dt;
            sp[1] += sp[3] * dt;
            if (phase == FINISH) sp[3] += dp(500) * dt;     // confetti falls
            if (phase == FINISH) {
                p.setColor(LIVERY[i % 5][0]);
                c.drawRect(sp[0] - dp(3), sp[1] - dp(2), sp[0] + dp(3), sp[1] + dp(2), p);
            } else {
                p.setColor(sp[4] < 0.15f ? 0xFFFFF27A : 0xFFFF9A2B);
                p.setAlpha((int) (255 * (1 - sp[4] / 0.45f)));
                c.drawCircle(sp[0], sp[1], dp(1.8f), p);
            }
        }
        p.setAlpha(255);

        // Him: a soft rust glow underneath so he stands out, then the cars (him last, on top, a size bigger).
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 160.0);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFD9472F);
        p.setAlpha((int) (60 + (phase == INTRO ? 80 * pulse : 30)));
        c.drawCircle(me.x, me.y, dp(phase == INTRO ? 30 + 6 * pulse : 24), p);
        p.setAlpha(255);
        drawMiniDino(c, now);
        for (Car k : cars) if (k != me) drawCar(c, k, 1f);
        drawCar(c, me, phase == INTRO ? 1.25f + 0.5f * (1 - roll) : 1.2f);
        c.restore();

        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC));
        if (z > 0.05f) {
            cockpit(c, w, h, z, myPlace);
        } else {
            // His tag above him.
            p.setTextSize(dp(10));
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(0xFFD9472F);
            c.drawText("ARC · P" + myPlace, me.x, me.y - dp(22), p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        if (phase == INTRO) intro(c, w, h, me, inPhase, roll);

        // The pill under the camera (like the head-scroll notice): his speed, place and lap.
        String board = phase == INTRO ? "ARC GP · " + TRACKS[variant] + " · " + LAPS + " RUNDEN"
            : phase == FINISH ? "🏁 ARC GEWINNT · " + TRACKS[variant]
            : (int) (me.speed / dp(1) * 0.62f) + " KM/H · P" + myPlace + " · RUNDE " + Math.min(LAPS, myLap + 1) + "/" + LAPS;
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(dp(13));
        float tw = p.measureText(board);
        float pcx = camX >= 0 ? camX : w / 2;
        RectF r = new RectF(pcx - tw / 2 - dp(16), pillY, pcx + tw / 2 + dp(16), pillY + dp(34));
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF0121211);
        c.drawRoundRect(r, dp(17), dp(17), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(phase == FINISH || myPlace == 1 && phase == RACE ? 0xFF2BBF6A : 0xFFE0552F);
        c.drawRoundRect(r, dp(17), dp(17), p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawText(board, pcx - tw / 2, pillY + dp(22), p);

        if (phase == FINISH) {
            long t = now - phaseAt;
            float in = ease(t / 500f) * (1 - ease((t - 5200) / 600f));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(40));
            p.setColor(0xFFD9472F);
            p.setAlpha((int) (255 * in));
            c.drawText("SIEG!", w / 2, h * 0.36f, p);
            p.setTextSize(dp(13));
            p.setColor(0xFFF4F2EC);
            p.setAlpha((int) (255 * in));
            c.drawText("ARC · " + LAPS + " RUNDEN · P1", w / 2, h * 0.36f + dp(26), p);
            p.setAlpha(255);
            p.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i < 2; i++) chequer(c, w / 2 + (i == 0 ? -1 : 1) * dp(110), h * 0.34f + dp(8) * (float) Math.sin(t / 120.0 + i));
        }

        if (running || cars.get(0).speed > 1) postInvalidateOnAnimation();
    }

    /** The hello: a speech bubble next to him, then the five red lights. */
    private void intro(Canvas c, float w, float h, Car me, long t, float roll) {
        if (t > ROLL_IN * 0.6f && t < HELLO + 5 * LIGHT) {
            String a = "Ciao! Ich bin ARC.", b = "Startplatz 5 – Sieg in " + LAPS + " Runden.";
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(12));
            float tw = Math.max(p.measureText(a), p.measureText(b)) + dp(20);
            float bx = Math.max(dp(8), Math.min(w - tw - dp(8), me.x - tw / 2)), byy = me.y + (me.y > h / 2 ? -dp(84) : dp(34));
            float in = ease((t - ROLL_IN * 0.6f) / 250f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFF4F2EC);
            p.setAlpha((int) (240 * in));
            c.drawRoundRect(bx, byy, bx + tw, byy + dp(48), dp(10), dp(10), p);
            p.setColor(0xFFB5271E);
            p.setAlpha((int) (255 * in));
            c.drawText(a, bx + dp(10), byy + dp(19), p);
            p.setColor(0xFF1A1A1A);
            p.setAlpha((int) (255 * in));
            p.setTextSize(dp(10));
            c.drawText(b, bx + dp(10), byy + dp(37), p);
            p.setAlpha(255);
        }
        if (t > HELLO) {
            float s = dp(9), gap = dp(26), x0 = w / 2 - 2 * gap, y = pillY + dp(62);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xDD111110);
            c.drawRoundRect(x0 - dp(16), y - dp(14), x0 + 4 * gap + dp(16), y + dp(14), dp(8), dp(8), p);
            int lit = (int) Math.min(5, (t - HELLO) / LIGHT + 1);
            for (int i = 0; i < 5; i++) {
                p.setColor(i < lit ? 0xFFE8231A : 0xFF3A1412);
                c.drawCircle(x0 + i * gap, y, s, p);
            }
        }
    }

    /** Full-screen chase cam: speed lines, a vignette, the steering wheel with his place and speed. */
    private void cockpit(Canvas c, float w, float h, float z, int place) {
        Car me = buddy();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        for (int i = 0; i < 14; i++) {
            float a = (float) (i / 14.0 * Math.PI * 2 + (SystemClock.uptimeMillis() / 90 % 7) * 0.05);
            float r0 = Math.max(w, h) * (0.42f + rnd.nextFloat() * 0.1f), r1 = Math.max(w, h) * 0.75f;
            p.setColor(0xFFF4F2EC);
            p.setAlpha((int) (60 * z));
            c.drawLine(w / 2 + (float) Math.cos(a) * r0, h * 0.6f + (float) Math.sin(a) * r0,
                w / 2 + (float) Math.cos(a) * r1, h * 0.6f + (float) Math.sin(a) * r1, p);
        }
        // Steering wheel at the bottom.
        float cx = w / 2, cy = h - dp(40) + dp(120) * (1 - z), ww = Math.min(w * 0.8f, dp(330));
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF111110);
        p.setAlpha((int) (240 * z));
        c.drawRoundRect(cx - ww / 2, cy - dp(52), cx + ww / 2, cy + dp(60), dp(26), dp(26), p);
        p.setColor(0xFF1E1E1C);
        c.drawRoundRect(cx - ww * 0.28f, cy - dp(44), cx + ww * 0.28f, cy + dp(10), dp(8), dp(8), p);
        // Shift lights.
        for (int i = 0; i < 9; i++) {
            float f = Math.min(1, me.speed / dp(560));
            p.setColor(i < f * 9 ? (i < 3 ? 0xFF2BD96B : i < 6 ? 0xFFE8231A : 0xFF3C8CFF) : 0xFF2A2A28);
            c.drawCircle(cx - ww * 0.24f + i * ww * 0.06f, cy - dp(36), dp(4), p);
        }
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(dp(22));
        p.setColor(0xFFF4F2EC);
        c.drawText((int) (180 + me.speed / dp(1) * 0.28f) + "", cx - ww * 0.12f, cy - dp(6), p);
        p.setTextSize(dp(9));
        p.setColor(0xFF9A9A98);
        c.drawText("KM/H", cx - ww * 0.12f, cy + dp(6), p);
        p.setTextSize(dp(22));
        p.setColor(place == 1 ? 0xFF2BD96B : 0xFFD9472F);
        c.drawText("P" + place, cx + ww * 0.14f, cy - dp(6), p);
        // Grips.
        p.setColor(0xFFB5271E);
        p.setAlpha((int) (255 * z));
        c.drawRoundRect(cx - ww / 2 - dp(6), cy - dp(40), cx - ww / 2 + dp(18), cy + dp(50), dp(10), dp(10), p);
        c.drawRoundRect(cx + ww / 2 - dp(18), cy - dp(40), cx + ww / 2 + dp(6), cy + dp(50), dp(10), dp(10), p);
        if (passes > 0) {
            p.setTextSize(dp(30));
            p.setColor(0xFFD9472F);
            p.setAlpha((int) (255 * Math.min(1, passes / 300f)));
            c.drawText("ÜBERHOLT!", w / 2, h * 0.3f, p);
        }
        p.setAlpha(255);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** Top-down F1 car: nose forward along the track, wings, four wheels, halo, number. */
    private void drawCar(Canvas c, Car k, float scale) {
        int body = LIVERY[k.livery][0], accent = LIVERY[k.livery][1];
        float L = dp(32), W = dp(11);
        c.save();
        c.translate(k.x, k.y);
        c.rotate(k.angle);
        c.scale(scale, scale);
        // Speed streaks behind.
        if (k.speed > dp(300)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(0x55F4F2EC);
            float len = (k.speed - dp(300)) / dp(220) * dp(40);
            c.drawLine(-L * 0.6f, -W * 0.3f, -L * 0.6f - len, -W * 0.3f, p);
            c.drawLine(-L * 0.6f, W * 0.3f, -L * 0.6f - len * 0.8f, W * 0.3f, p);
        }
        p.setStyle(Paint.Style.FILL);
        // Wheels.
        p.setColor(0xFF111110);
        c.drawRoundRect(-L * 0.42f, -W * 0.95f, -L * 0.18f, -W * 0.5f, dp(2), dp(2), p);
        c.drawRoundRect(-L * 0.42f, W * 0.5f, -L * 0.18f, W * 0.95f, dp(2), dp(2), p);
        c.drawRoundRect(L * 0.16f, -W * 0.85f, L * 0.34f, -W * 0.45f, dp(2), dp(2), p);
        c.drawRoundRect(L * 0.16f, W * 0.45f, L * 0.34f, W * 0.85f, dp(2), dp(2), p);
        // Body: a long nose, wider sidepods.
        car.reset();
        car.moveTo(L * 0.5f, 0);
        car.lineTo(L * 0.18f, -W * 0.18f);
        car.lineTo(-L * 0.05f, -W * 0.42f);
        car.lineTo(-L * 0.4f, -W * 0.36f);
        car.lineTo(-L * 0.5f, -W * 0.2f);
        car.lineTo(-L * 0.5f, W * 0.2f);
        car.lineTo(-L * 0.4f, W * 0.36f);
        car.lineTo(-L * 0.05f, W * 0.42f);
        car.lineTo(L * 0.18f, W * 0.18f);
        car.close();
        p.setColor(body);
        c.drawPath(car, p);
        // Wings.
        p.setColor(accent);
        c.drawRect(L * 0.44f, -W * 0.6f, L * 0.52f, W * 0.6f, p);
        c.drawRect(-L * 0.56f, -W * 0.55f, -L * 0.46f, W * 0.55f, p);
        // Stripe and the driver's helmet under the halo.
        c.drawRect(-L * 0.35f, -W * 0.06f, L * 0.4f, W * 0.06f, p);
        p.setColor(0xFF111110);
        c.drawCircle(-L * 0.08f, 0, W * 0.2f, p);
        p.setColor(accent);
        c.drawCircle(-L * 0.08f, 0, W * 0.11f, p);
        c.restore();
    }

    private void chequer(Canvas c, float x, float y) {
        float s = dp(5);
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 2; j++) {
                p.setStyle(Paint.Style.FILL);
                p.setColor((i + j) % 2 == 0 ? 0xCCF4F2EC : 0xCC111110);
                c.drawRect(x - s + j * s, y - 3 * s + i * s, x + j * s, y - 2 * s + i * s, p);
            }
        }
    }
}
