package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Dezibel: how loud is it here? The microphone level (nothing is recorded or stored) as an
 * approximate sound level: big number on a gauge, min / average / max, the last minute as a
 * curve, and where that sits between whisper and pain. Above 85 dB it says: protect your ears.
 * Phone mics differ, so ± calibrates the scale (saved).
 */
public class DecibelActivity extends Activity {

    private static final int REQ_MIC = 1, RATE = 44_100, HISTORY = 600;     // 60 s at 10 per second

    private boolean night;
    private int bg, card, line, ink, ink2, ink3, rust;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private volatile boolean running;
    private volatile int generation;
    private Thread reader;
    private Gauge gauge;
    private Curve curve;
    private TextView minT, avgT, maxT, status, offsetT, pauseB;
    private LinearLayout scale;
    private final float[] history = new float[HISTORY];
    private int count;
    private float level = Float.NaN, min = Float.NaN, max = Float.NaN, peak = Float.NaN;
    private double sum;
    private long sumN, peakAt;
    private boolean paused;

    private static final String[][] LEVELS = {
        {"30", "Flüstern"}, {"45", "Ruhiges Zimmer"}, {"60", "Gespräch"}, {"70", "Staubsauger"},
        {"85", "Verkehr · ab hier Gehörschutz"}, {"100", "Konzert, Club"}, {"120", "Schmerzgrenze"}};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        prefs = getSharedPreferences("decibel", MODE_PRIVATE);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFEDEDE8 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        ScrollView sv = new ScrollView(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(28));
        sv.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Dezibel"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        // Gauge card.
        LinearLayout gc = new LinearLayout(this);
        gc.setOrientation(LinearLayout.VERTICAL);
        gc.setBackground(box(card, line, 20));
        gc.setPadding(dp(16), dp(18), dp(16), dp(18));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = dp(16);
        col.addView(gc, gl);
        gauge = new Gauge();
        gc.addView(gauge, new LinearLayout.LayoutParams(-1, dp(250)));
        status = mono(11, ink3);
        status.setGravity(Gravity.CENTER);
        status.setText("MIKROFON STARTET …");
        gc.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout stats = new LinearLayout(this);
        stats.setPadding(0, dp(16), 0, 0);
        minT = stat(stats, "MIN");
        avgT = stat(stats, "Ø");
        maxT = stat(stats, "MAX");
        gc.addView(stats);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(16), 0, 0);
        pauseB = chip("PAUSE", rust);
        pauseB.setOnClickListener(v -> {
            paused = !paused;
            pauseB.setText(paused ? "WEITER" : "PAUSE");
            paint();
        });
        actions.addView(pauseB);
        TextView reset = chip("ZURÜCKSETZEN", ink3);
        reset.setOnClickListener(v -> resetStats());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.leftMargin = dp(8);
        actions.addView(reset, rl);
        gc.addView(actions);

        // Last minute.
        LinearLayout lh = new LinearLayout(this);
        lh.setPadding(0, dp(22), 0, dp(10));
        lh.addView(label("Letzte Minute"));
        col.addView(lh);
        curve = new Curve();
        curve.setBackground(box(card, line, 16));
        col.addView(curve, new LinearLayout.LayoutParams(-1, dp(130)));

        // Reference scale.
        LinearLayout sh = new LinearLayout(this);
        sh.setPadding(0, dp(22), 0, dp(10));
        sh.addView(label("Wie laut ist das?"));
        col.addView(sh);
        scale = new LinearLayout(this);
        scale.setOrientation(LinearLayout.VERTICAL);
        scale.setBackground(box(card, line, 16));
        scale.setPadding(dp(16), dp(6), dp(16), dp(6));
        col.addView(scale, new LinearLayout.LayoutParams(-1, -2));
        for (String[] l : LEVELS) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(10), 0, dp(10));
            TextView n = mono(14, ink2);
            n.setText(l[0] + " dB");
            r.addView(n, new LinearLayout.LayoutParams(dp(78), -2));
            TextView t = text(15, ink2, false);
            t.setText(l[1]);
            r.addView(t);
            scale.addView(r);
        }

        // Calibration.
        LinearLayout ch = new LinearLayout(this);
        ch.setPadding(0, dp(22), 0, dp(10));
        ch.addView(label("Kalibrieren"));
        col.addView(ch);
        LinearLayout cal = new LinearLayout(this);
        cal.setGravity(Gravity.CENTER_VERTICAL);
        cal.setBackground(box(card, line, 16));
        cal.setPadding(dp(16), dp(10), dp(10), dp(10));
        TextView ct = text(14, ink2, false);
        ct.setText("Handy-Mikrofone sind verschieden. Mit einem echten Messgerät vergleichen und anpassen.");
        cal.addView(ct, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView minus = chip("−", ink);
        minus.setOnClickListener(v -> shiftOffset(-1));
        cal.addView(minus);
        offsetT = mono(13, rust);
        offsetT.setGravity(Gravity.CENTER);
        cal.addView(offsetT, new LinearLayout.LayoutParams(dp(56), -2));
        TextView plus = chip("+", ink);
        plus.setOnClickListener(v -> shiftOffset(1));
        cal.addView(plus);
        col.addView(cal, new LinearLayout.LayoutParams(-1, -2));
        TextView note = mono(10, ink3);
        note.setSingleLine(false);
        note.setText("NUR PEGEL, NICHTS WIRD AUFGENOMMEN ODER GESPEICHERT.");
        note.setPadding(0, dp(14), 0, 0);
        col.addView(note);

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : bits, bits);
        }
        paintOffset();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else {
            status.setText("OHNE MIKROFON KEINE MESSUNG");
            Toast.makeText(this, "Dezibel braucht das Mikrofon (nur der Pegel, nichts wird gespeichert).", Toast.LENGTH_LONG).show();
        }
    }

    /* ---------------- measuring ---------------- */

    private float offset() {
        return prefs.getFloat("offset", 92f);
    }

    private void shiftOffset(int d) {
        prefs.edit().putFloat("offset", Math.max(60, Math.min(130, offset() + d))).apply();
        paintOffset();
    }

    private void paintOffset() {
        float o = offset() - 92f;
        offsetT.setText((o >= 0 ? "+" : "") + Math.round(o));
    }

    private void resetStats() {
        min = max = peak = Float.NaN;
        sum = 0;
        sumN = 0;
        count = 0;
        java.util.Arrays.fill(history, 0);
        paint();
    }

    @SuppressWarnings("MissingPermission")
    private void start() {
        if (running) return;
        running = true;
        int gen = ++generation;          // a reader from before a quick pause/resume stops on its own
        reader = new Thread(() -> {
            int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(minBuf, RATE / 10 * 2);
            AudioRecord rec = null;
            // UNPROCESSED (no automatic gain) where the phone has it; else voice recognition, the next most raw.
            int[] sources = {MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
            for (int src : sources) {
                try {
                    AudioRecord r = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        rec = r;
                        break;
                    }
                    r.release();
                } catch (RuntimeException ignored) {
                }
            }
            if (rec == null) {
                main.post(() -> status.setText("MIKROFON NICHT VERFÜGBAR"));
                running = false;
                return;
            }
            short[] buf = new short[RATE / 10];          // 100 ms blocks
            try {
                rec.startRecording();
                while (running && gen == generation) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    double acc = 0;
                    for (int i = 0; i < n; i++) acc += (double) buf[i] * buf[i];
                    double rms = Math.sqrt(acc / n) / 32768.0;
                    float db = (float) (20 * Math.log10(Math.max(rms, 1e-7)) + offset());
                    float clamped = Math.max(0, Math.min(140, db));
                    main.post(() -> onLevel(clamped));
                }
            } catch (RuntimeException e) {
                main.post(() -> status.setText("MESSUNG UNTERBROCHEN"));
            } finally {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                }
                rec.release();
            }
        }, "arc-decibel");
        reader.start();
    }

    private void stop() {
        running = false;
        reader = null;
    }

    private void onLevel(float db) {
        if (paused) return;
        // A light smoothing like a "fast" sound level meter (125 ms).
        level = Float.isNaN(level) ? db : level + (db - level) * 0.55f;
        min = Float.isNaN(min) ? level : Math.min(min, level);
        max = Float.isNaN(max) ? level : Math.max(max, level);
        long now = SystemClock.uptimeMillis();
        if (Float.isNaN(peak) || level >= peak || now - peakAt > 2000) {
            peak = level;
            peakAt = now;
        }
        // Energy average, not the average of dB numbers.
        sum += Math.pow(10, level / 10);
        sumN++;
        System.arraycopy(history, 1, history, 0, HISTORY - 1);
        history[HISTORY - 1] = level;
        count = Math.min(HISTORY, count + 1);
        paint();
    }

    private void paint() {
        gauge.invalidate();
        curve.invalidate();
        minT.setText(Float.isNaN(min) ? "–" : String.valueOf(Math.round(min)));
        maxT.setText(Float.isNaN(max) ? "–" : String.valueOf(Math.round(max)));
        avgT.setText(sumN == 0 ? "–" : String.valueOf(Math.round(10 * Math.log10(sum / sumN))));
        if (paused) status.setText("PAUSE");
        else if (Float.isNaN(level)) status.setText("MIKROFON STARTET …");
        else status.setText(describe(level).toUpperCase(Locale.GERMAN));
        status.setTextColor(!Float.isNaN(level) && level >= 85 && !paused ? rust : ink3);
        // Highlight the nearest line of the reference scale.
        int near = -1;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < LEVELS.length; i++) {
            float d = Math.abs(Float.parseFloat(LEVELS[i][0]) - (Float.isNaN(level) ? -100 : level));
            if (d < best) {
                best = d;
                near = i;
            }
        }
        for (int i = 0; i < scale.getChildCount(); i++) {
            LinearLayout r = (LinearLayout) scale.getChildAt(i);
            boolean on = i == near && best < 8;
            ((TextView) r.getChildAt(0)).setTextColor(on ? rust : ink3);
            TextView t = (TextView) r.getChildAt(1);
            t.setTextColor(on ? ink : ink3);
            t.setTypeface(on ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.DEFAULT);
        }
    }

    private static String describe(float db) {
        if (db < 35) return "Sehr still";
        if (db < 50) return "Ruhig";
        if (db < 65) return "Gespräch";
        if (db < 80) return "Laut";
        if (db < 85) return "Sehr laut";
        if (db < 100) return "Gehörschutz empfohlen";
        return "Gefährlich laut – Ohren schützen!";
    }

    /** Colour along the scale: green → yellow → rust. */
    private int colourFor(float db) {
        if (db < 60) return 0xFF2BD96B;
        if (db < 85) return 0xFFFFC400;
        return rust;
    }

    /* ---------------- views ---------------- */

    /** A 240° arc from 20 to 130 dB, the number in the middle, a peak tick. */
    private final class Gauge extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();

        Gauge() {
            super(DecibelActivity.this);
        }

        private float angle(float db) {
            return 150 + 240 * Math.max(0, Math.min(1, (db - 20) / 110f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w / 2, h * 0.58f) - dp(14), cx = w / 2, cy = h * 0.56f;
            arc.set(cx - r, cy - r, cx + r, cy + r);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(14));
            p.setColor(line);
            c.drawArc(arc, 150, 240, false, p);
            // Zone marks: 60 and 85 dB.
            p.setStrokeWidth(dp(2));
            p.setColor(ink3);
            for (float mark : new float[]{20, 40, 60, 85, 100, 130}) {
                double a = Math.toRadians(angle(mark));
                float x1 = cx + (float) Math.cos(a) * (r + dp(11)), y1 = cy + (float) Math.sin(a) * (r + dp(11));
                float x2 = cx + (float) Math.cos(a) * (r + dp(17)), y2 = cy + (float) Math.sin(a) * (r + dp(17));
                c.drawLine(x1, y1, x2, y2, p);
            }
            if (!Float.isNaN(level)) {
                p.setStrokeWidth(dp(14));
                p.setColor(colourFor(level));
                c.drawArc(arc, 150, angle(level) - 150, false, p);
                if (!Float.isNaN(peak)) {
                    double a = Math.toRadians(angle(peak));
                    p.setStrokeWidth(dp(3));
                    p.setColor(ink);
                    c.drawLine(cx + (float) Math.cos(a) * (r - dp(10)), cy + (float) Math.sin(a) * (r - dp(10)),
                        cx + (float) Math.cos(a) * (r + dp(10)), cy + (float) Math.sin(a) * (r + dp(10)), p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(ink);
            p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            p.setTextSize(dp(72));
            c.drawText(Float.isNaN(level) ? "–" : String.valueOf(Math.round(level)), cx, cy + dp(18), p);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(12));
            p.setColor(ink3);
            c.drawText("dB (ca.)", cx, cy + dp(44), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** The last minute as a filled curve with the 85 dB line. */
    private final class Curve extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Curve() {
            super(DecibelActivity.this);
        }

        private float y(float db, float top, float hh) {
            return top + hh * (1 - Math.max(0, Math.min(1, (db - 20) / 100f)));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = dp(12), top = pad, hh = h - 2 * pad;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(rust);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(5), dp(5)}, 0));
            float y85 = y(85, top, hh);
            c.drawLine(pad, y85, w - pad, y85, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(9));
            p.setColor(rust);
            c.drawText("85", pad + dp(2), y85 - dp(3), p);
            if (count < 2) return;
            float step = (w - 2 * pad) / (HISTORY - 1);
            path.reset();
            int start = HISTORY - count;
            path.moveTo(pad + start * step, top + hh);
            for (int i = start; i < HISTORY; i++) path.lineTo(pad + i * step, y(history[i], top, hh));
            path.lineTo(pad + (HISTORY - 1) * step, top + hh);
            path.close();
            p.setColor(Float.isNaN(level) ? ink3 : colourFor(level));
            p.setAlpha(60);
            c.drawPath(path, p);
            p.setAlpha(255);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            path.reset();
            for (int i = start; i < HISTORY; i++) {
                float x = pad + i * step, yy = y(history[i], top, hh);
                if (i == start) path.moveTo(x, yy);
                else path.lineTo(x, yy);
            }
            c.drawPath(path, p);
        }
    }

    private TextView stat(LinearLayout row, String name) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView v = text(24, ink, true);
        v.setText("–");
        b.addView(v);
        TextView n = mono(10, ink3);
        n.setText(name);
        b.addView(n);
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        return v;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private TextView mono(float sp, int colour) {
        TextView t = text(sp, colour, false);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.12f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView chip(String s, int colour) {
        TextView c = mono(12, colour);
        c.setText(s);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        c.setBackground(box(card, colour, 20));
        return c;
    }

    private View label(String s) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        View dash = new View(this);
        dash.setBackgroundColor(rust);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(24), dp(2));
        dl.rightMargin = dp(14);
        l.addView(dash, dl);
        TextView t = mono(11, ink3);
        t.setLetterSpacing(0.24f);
        t.setText(s.toUpperCase(Locale.GERMAN));
        l.addView(t);
        return l;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
}
