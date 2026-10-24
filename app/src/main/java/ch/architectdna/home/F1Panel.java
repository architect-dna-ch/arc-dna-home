package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * F1 mode on the home screen: a half-screen timing board. Live time to the millisecond, a lap
 * counter and three sector bars (yellow → green → purple) that fill over each 5-second lap,
 * in step with the clock (laps end at :00, :05 … :55); at every lap end an F1 car shoots across the track strip with motion streaks and a whoosh.
 */
final class F1Panel extends View {

    private static final long LAP_MS = 5000, CAR_MS = 750;
    private static final int YELLOW = 0xFFF2C200, GREEN = 0xFF1FB45A, PURPLE = 0xFF9B4DDB;

    private final int ink, ink3, rust, card, line;
    private final FoxVoice voice;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    /** Laps follow the wall clock (…:45, :50, :55, :00); start = the 5-second slot F1 began in. */
    private long start, lastLap = -1, carStart = -1, carFor = -1, soundFor = -1;
    /** The whoosh is loudest 45 % into its 0.9 s; the car is mid-track halfway through its pass. */
    private static final long SOUND_LEAD_MS = 405, CAR_LEAD_MS = CAR_MS / 2;
    private boolean running;

    F1Panel(Context c, int[] colours, FoxVoice voice) {
        super(c);
        ink = colours[0];
        ink3 = colours[2];
        rust = colours[3];
        card = colours[4];
        line = colours[5];
        this.voice = voice;
    }

    void begin() {
        start = System.currentTimeMillis() / LAP_MS;
        lastLap = -1;
        resume();
    }

    void resume() {
        if (start == 0) start = System.currentTimeMillis() / LAP_MS;
        if (running) return;
        running = true;
        postOnAnimation(frame);
    }

    void pause() {
        running = false;
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running || !isAttachedToWindow() || getVisibility() != VISIBLE) {
                running = false;
                return;
            }
            // Time the pass so the car is exactly mid-track, and the whoosh at its loudest,
            // on every round fifth second of the clock (…:45.000, :50.000, :55.000 …).
            long wall = System.currentTimeMillis();
            long next = (wall / LAP_MS + 1) * LAP_MS, remaining = next - wall;
            if (remaining <= SOUND_LEAD_MS && soundFor != next) {
                soundFor = next;
                voice.whoosh();
            }
            if (remaining <= CAR_LEAD_MS && carFor != next) {
                carFor = next;
                carStart = SystemClock.uptimeMillis() - (CAR_LEAD_MS - remaining);
            }
            lastLap = wall / LAP_MS;
            invalidate();
            postOnAnimation(this);
        }
    };

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        long wall = System.currentTimeMillis();
        long inLap = wall % LAP_MS;
        long lap = wall / LAP_MS - start + 1;

        // Board.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(card);
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, dp(8), dp(8), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(line);
        c.drawRoundRect(rect, dp(8), dp(8), paint);

        float pad = dp(14);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC));
        paint.setTextSize(dp(11));
        paint.setLetterSpacing(0.14f);
        paint.setColor(rust);
        c.drawText("F1 · LIVE TIMING", pad, pad + dp(10), paint);
        paint.setColor(ink3);
        String lapText = String.format(Locale.ROOT, "LAP %02d", lap);
        c.drawText(lapText, w - pad - paint.measureText(lapText), pad + dp(10), paint);

        // Big time readout, sized to fit the width.
        Calendar cal = Calendar.getInstance();
        String time = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND), System.currentTimeMillis() % 1000);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setLetterSpacing(-0.02f);
        float size = Math.min(h * 0.24f, dp(64));
        paint.setTextSize(size);
        while (paint.measureText(time) > w - 2 * pad && size > dp(12)) paint.setTextSize(size -= dp(1));
        paint.setColor(ink);
        float timeY = pad + dp(22) + size;
        c.drawText(time, pad, timeY, paint);

        // Lap time and sector bars.
        paint.setTextSize(dp(13));
        paint.setLetterSpacing(0.08f);
        paint.setColor(ink3);
        c.drawText(String.format(Locale.ROOT, "LAP TIME 0:%02d.%03d", inLap / 1000, inLap % 1000), pad, timeY + dp(24), paint);
        float barsY = timeY + dp(36), gap = dp(6), bw = (w - 2 * pad - 2 * gap) / 3f, bh = dp(8);
        int[] colours = {YELLOW, GREEN, PURPLE};
        for (int s = 0; s < 3; s++) {
            float x = pad + s * (bw + gap);
            float fill = Math.max(0f, Math.min(1f, (inLap - s * LAP_MS / 3f) / (LAP_MS / 3f)));
            paint.setColor(line);
            rect.set(x, barsY, x + bw, barsY + bh);
            c.drawRoundRect(rect, bh / 2, bh / 2, paint);
            paint.setColor(colours[s]);
            rect.set(x, barsY, x + bw * fill, barsY + bh);
            c.drawRoundRect(rect, bh / 2, bh / 2, paint);
        }

        // Track strip with kerbs, scrolling.
        float trackY = h - pad - dp(30), trackH = dp(30);
        paint.setColor(line);
        rect.set(pad, trackY, w - pad, trackY + trackH);
        c.drawRoundRect(rect, dp(4), dp(4), paint);
        float kerb = dp(14), shift = (now / 6f) % (2 * kerb);
        paint.setColor(rust);
        c.save();
        c.clipRect(pad, trackY, w - pad, trackY + trackH);   // stripes never leave the track
        for (float x = pad - 2 * kerb + shift; x < w - pad; x += 2 * kerb) {
            c.drawRect(x, trackY + trackH - dp(4), x + kerb, trackY + trackH, paint);
        }
        c.restore();

        // The car pass.
        if (carStart >= 0) {
            float k = (now - carStart) / (float) CAR_MS;
            if (k >= 1) {
                carStart = -1;
            } else {
                float ease = k < 0.5f ? 2 * k * k : 1 - (float) Math.pow(-2 * k + 2, 2) / 2;
                float carW = dp(84), carH = dp(24);
                float cx = -carW + ease * (w + 2 * carW);
                float cy = trackY + trackH - dp(5) - carH;
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 4; i++) {
                    paint.setStrokeWidth(dp(2));
                    paint.setColor((ink & 0x00FFFFFF) | ((int) (90 - i * 20) << 24));
                    float sy = cy + carH * (0.35f + i * 0.15f);
                    c.drawLine(cx - dp(30) - i * dp(14), sy, cx - dp(2), sy, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                drawCar(c, cx, cy, carW, carH);
            }
        }
    }

    /** A side-on F1 car in ink with a rust stripe. */
    private void drawCar(Canvas c, float x, float y, float w, float h) {
        paint.setColor(ink);
        path.reset();
        path.moveTo(x + w * 0.08f, y + h * 0.72f);
        path.lineTo(x + w * 0.16f, y + h * 0.42f);
        path.lineTo(x + w * 0.46f, y + h * 0.40f);
        path.lineTo(x + w * 0.56f, y + h * 0.22f);
        path.lineTo(x + w * 0.64f, y + h * 0.40f);
        path.lineTo(x + w * 0.98f, y + h * 0.60f);
        path.lineTo(x + w * 0.98f, y + h * 0.72f);
        path.close();
        c.drawPath(path, paint);
        c.drawRect(x, y + h * 0.10f, x + w * 0.12f, y + h * 0.30f, paint);           // rear wing
        c.drawRect(x + w * 0.05f, y + h * 0.30f, x + w * 0.08f, y + h * 0.55f, paint);
        c.drawRect(x + w * 0.86f, y + h * 0.72f, x + w * 1.02f, y + h * 0.80f, paint); // front wing
        paint.setColor(rust);
        c.drawRect(x + w * 0.20f, y + h * 0.50f, x + w * 0.80f, y + h * 0.58f, paint);
        paint.setColor(ink);
        c.drawCircle(x + w * 0.24f, y + h * 0.78f, h * 0.22f, paint);
        c.drawCircle(x + w * 0.80f, y + h * 0.78f, h * 0.20f, paint);
    }
}
