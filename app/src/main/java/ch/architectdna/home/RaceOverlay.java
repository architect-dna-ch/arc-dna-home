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
    private int laps;

    private static final class Car {
        int livery;
        float s, speed, target, wide, wideTarget, lane;
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
            k.s = -i * 0.045f;          // on the grid, one behind the other
            k.lane = (i % 2 == 0 ? -1 : 1) * dp(6);
            k.speed = 0;
            cars.add(k);
        }
    }

    void start() {
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
        if (t != topInset) {
            topInset = t;
            if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), 0, 0);
        }
        return super.onApplyWindowInsets(in);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // The circuit: round the edges of the screen with a chicane on the left and a hairpin at the bottom.
        // The top straight stays below the status bar (cars vanished under the icons).
        float m = dp(26), r = dp(70), top = Math.max(m, topInset + dp(20));
        track.reset();
        track.moveTo(w * 0.5f, top);
        track.lineTo(w - m - r, top);
        track.quadTo(w - m, top, w - m, top + r);
        track.lineTo(w - m, h * 0.55f);
        track.cubicTo(w - m, h * 0.62f, w * 0.62f, h * 0.6f, w * 0.6f, h * 0.68f);      // swings inwards
        track.cubicTo(w * 0.58f, h * 0.76f, w - m, h * 0.74f, w - m, h * 0.82f);
        track.lineTo(w - m, h - m - r);
        track.quadTo(w - m, h - m, w - m - r, h - m);
        track.lineTo(m + r, h - m);
        track.quadTo(m, h - m, m, h - m - r);
        track.lineTo(m, h * 0.6f);
        track.cubicTo(m, h * 0.5f, w * 0.3f, h * 0.48f, w * 0.3f, h * 0.4f);              // the chicane
        track.cubicTo(w * 0.3f, h * 0.32f, m, h * 0.3f, m, h * 0.22f);
        track.lineTo(m, top + r);
        track.quadTo(m, top, m + r, top);
        track.close();
        measure = new PathMeasure(track, true);
        length = measure.getLength();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (measure == null || length <= 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        float w = getWidth(), h = getHeight();

        // Track: tarmac band, red-white kerbs on the edges, dashed middle, start line.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setPathEffect(null);
        p.setStrokeWidth(dp(34));
        p.setColor(0x38303030);
        c.drawPath(track, p);
        p.setStrokeWidth(dp(36));
        p.setPathEffect(new DashPathEffect(new float[]{dp(10), dp(10)}, 0));
        p.setColor(0x44B5271E);
        c.drawPath(track, p);
        p.setStrokeWidth(dp(30));
        p.setPathEffect(null);
        p.setColor(0x5C242424);
        c.drawPath(track, p);
        p.setStrokeWidth(dp(1.5f));
        p.setPathEffect(new DashPathEffect(new float[]{dp(12), dp(14)}, 0));
        p.setColor(0x66F4F2EC);
        c.drawPath(track, p);
        p.setPathEffect(null);
        measure.getPosTan(0, pos, tan);
        chequer(c, pos[0], pos[1]);

        // Cars: each keeps changing its pace (that's the overtaking), and in tight corners runs wide.
        for (Car k : cars) {
            if (running) {
                if (now > k.nextChange) {
                    k.target = dp(260) + rnd.nextFloat() * dp(260);
                    k.nextChange = now + 700 + rnd.nextInt(1800);
                }
                k.speed += (k.target - k.speed) * Math.min(1, dt * 1.6f);
            } else {
                k.speed *= 0.95f;
            }
            float before = k.s;
            k.s += k.speed * dt / length;
            if (k.livery == 0 && Math.floor(k.s) > Math.floor(before)) laps++;
            float at = ((k.s % 1) + 1) % 1 * length;
            measure.getPosTan(at, pos, tan);
            float ang = (float) Math.toDegrees(Math.atan2(tan[1], tan[0]));
            // Curvature: how fast the direction turns just ahead.
            float[] p2 = new float[2], t2 = new float[2];
            measure.getPosTan((at + dp(40)) % length, p2, t2);
            float turn = (float) Math.toDegrees(Math.atan2(t2[1], t2[0])) - ang;
            while (turn > 180) turn -= 360;
            while (turn < -180) turn += 360;
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
            float laneTarget = ahead < dp(46) ? (aheadLane > 0 ? -dp(9) : dp(9)) : (k.livery - 2) * dp(2);
            k.lane += (laneTarget - k.lane) * Math.min(1, dt * 5);
            if (ahead < dp(20)) k.speed = Math.max(k.speed * 0.98f, 0);       // brakes rather than drive through
            float nx = -tan[1], ny = tan[0];
            k.x = pos[0] + nx * (k.wide + k.lane);
            k.y = pos[1] + ny * (k.wide + k.lane);
            float slide = turn * 0.6f * Math.min(1, k.speed / dp(400));      // drift angle in the corner
            k.angle = ang + slide;
            if (running && Math.abs(turn) > 18 && k.speed > dp(330) && rnd.nextFloat() < 0.5f) {
                sparks.add(new float[]{k.x - tan[0] * dp(14), k.y - tan[1] * dp(14),
                    -tan[0] * dp(60) + (rnd.nextFloat() - 0.5f) * dp(80), -tan[1] * dp(60) + (rnd.nextFloat() - 0.5f) * dp(80), 0});
            }
        }
        // Sparks.
        p.setStyle(Paint.Style.FILL);
        for (int i = sparks.size() - 1; i >= 0; i--) {
            float[] s = sparks.get(i);
            s[4] += dt;
            if (s[4] > 0.45f) {
                sparks.remove(i);
                continue;
            }
            s[0] += s[2] * dt;
            s[1] += s[3] * dt;
            p.setColor(s[4] < 0.15f ? 0xFFFFF27A : 0xFFFF9A2B);
            p.setAlpha((int) (255 * (1 - s[4] / 0.45f)));
            c.drawCircle(s[0], s[1], dp(1.8f), p);
        }
        p.setAlpha(255);
        for (Car k : cars) drawCar(c, k);

        // Leader board, top centre.
        Car lead = cars.get(0);
        for (Car k : cars) if (k.s > lead.s) lead = k;
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC));
        p.setTextSize(dp(11));
        String board = "P1 " + TAG[lead.livery] + " · LAP " + Math.max(1, laps + 1);
        float tw = p.measureText(board);
        RectF r = new RectF(w / 2 - tw / 2 - dp(10), dp(60), w / 2 + tw / 2 + dp(10), dp(82));
        p.setColor(0xCC111110);
        c.drawRoundRect(r, dp(6), dp(6), p);
        p.setColor(LIVERY[lead.livery][0] == 0xFFF4F2EC ? 0xFFB5271E : LIVERY[lead.livery][0]);
        c.drawText(board, w / 2 - tw / 2, dp(76), p);

        if (running || cars.get(0).speed > 1) postInvalidateOnAnimation();
    }

    /** Top-down F1 car: nose forward along the track, wings, four wheels, halo, number. */
    private void drawCar(Canvas c, Car k) {
        int body = LIVERY[k.livery][0], accent = LIVERY[k.livery][1];
        float L = dp(32), W = dp(11);
        c.save();
        c.translate(k.x, k.y);
        c.rotate(k.angle);
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
