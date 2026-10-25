package ch.architectdna.home;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * VOID FREQ, rebuilt as a native ARC-DNA app: the channel big on top, a spectrum card with
 * play / stop, the five channels as cards, volume and a focus timer. The sound comes from
 * FreqEngine (VOID FREQ's engine ported to Java) inside FreqService, so it keeps playing in
 * the background. Light or dark like the launcher.
 */
public class FreqActivity extends Activity {

    private static final String[] WHAT = {"Lange Arbeitsphasen", "Ruhig werden, lesen", "Abschalten, tief", "Locker dranbleiben", "Mit Kopfhörern"};
    private static final String[] SUB = {"Brown noise · 40 Hz Gamma", "Gefiltertes Rauschen · Tropfen", "Tiefer Drone · Obertöne",
        "Am7 Loop · Beat · Vinyl", "Binaural Alpha 10 Hz"};
    private static final int[] COLOURS = {0, 0xFF3C8DFF, 0xFFA77BFF, 0xFFF29A1F, 0xFF2BBF6A};   // 0 = rust

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean night;
    private int bg, card, line, ink, ink2, ink3, rust, active;
    private TextView status, title, sub, play, clock, timerState, sessions;
    private View progress;
    private FrameLayout progressTrack;
    private final LinearLayout[] rows = new LinearLayout[5];
    private final TextView[] states = new TextView[5];
    private final TextView[] chips = new TextView[4];
    private SeekBar volume;
    private Spectrum spectrum;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("voidfreq", MODE_PRIVATE);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFEDEDE8 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        active = night ? 0xFF262624 : 0xFFF0EFEB;
        COLOURS[0] = rust;

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackground(new Grid());
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(40));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        // Header: label, status, close.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("VOID FREQ · Fokus"), new LinearLayout.LayoutParams(0, -2, 1f));
        status = mono(11, ink3);
        head.addView(status);
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setContentDescription("VOID FREQ schliessen");
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        title = text(54, ink, true);
        title.setLetterSpacing(-0.05f);
        title.setLineSpacing(0, 0.9f);
        title.setPadding(0, dp(22), 0, 0);
        col.addView(title);
        sub = mono(12, ink2);
        sub.setPadding(0, dp(8), 0, 0);
        col.addView(sub);

        // Spectrum card with play / stop.
        FrameLayout viz = new FrameLayout(this);
        viz.setBackground(box(card, line, 8));
        viz.setPadding(dp(14), dp(14), dp(14), dp(14));
        spectrum = new Spectrum();
        viz.addView(spectrum, new FrameLayout.LayoutParams(-1, dp(150)));
        play = text(20, 0xFFFFFFFF, true);
        play.setGravity(Gravity.CENTER);
        play.setOnClickListener(v -> toggle());
        viz.addView(play, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP | Gravity.END));
        col.addView(viz, margins(0, dp(22)));

        // Channels.
        col.addView(label("Kanäle"), margins(0, dp(30)));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(box(card, line, 8));
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                View div = new View(this);
                div.setBackgroundColor(line);
                list.addView(div, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));
            }
            int k = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(15), dp(16), dp(15));
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(COLOURS[i]);
            dot.setBackground(d);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(12), dp(12));
            dl.rightMargin = dp(14);
            row.addView(dot, dl);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(17, ink, true);
            name.setText(FreqService.NAMES[i]);
            texts.addView(name);
            TextView what = mono(11, ink3);
            what.setText(WHAT[i].toUpperCase(Locale.GERMAN));
            texts.addView(what);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            states[i] = mono(11, ink3);
            row.addView(states[i]);
            row.setOnClickListener(v -> choose(FreqEngine.KEYS[k]));
            rows[i] = row;
            list.addView(row);
        }
        col.addView(list);

        // Volume.
        col.addView(label("Lautstärke"), margins(0, dp(30)));
        LinearLayout vol = new LinearLayout(this);
        vol.setGravity(Gravity.CENTER_VERTICAL);
        vol.setBackground(box(card, line, 8));
        vol.setPadding(dp(16), dp(14), dp(8), dp(14));
        TextView vl = mono(11, ink3);
        vl.setText("VOL");
        vol.addView(vl);
        volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(Math.round(prefs.getFloat("volume", 0.7f) * 100));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                FreqService.engine().setVolume(p / 100f);
                prefs.edit().putFloat("volume", p / 100f).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        // Room between the label and the slider (the system slider draws past its own edge).
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -2, 1f);
        sl.leftMargin = dp(14);
        volume.setPadding(dp(14), 0, dp(14), 0);
        vol.addView(volume, sl);
        col.addView(vol);
        FreqService.engine().setVolume(prefs.getFloat("volume", 0.7f));

        // Timer.
        LinearLayout th = new LinearLayout(this);
        th.setGravity(Gravity.CENTER_VERTICAL);
        th.addView(label("Timer"), new LinearLayout.LayoutParams(0, -2, 1f));
        timerState = mono(11, rust);
        th.addView(timerState);
        col.addView(th, margins(0, dp(30)));
        LinearLayout timer = new LinearLayout(this);
        timer.setOrientation(LinearLayout.VERTICAL);
        timer.setBackground(box(card, line, 8));
        timer.setPadding(dp(16), dp(16), dp(16), dp(16));
        clock = text(64, ink, true);
        clock.setLetterSpacing(-0.05f);
        clock.setIncludeFontPadding(false);
        timer.addView(clock);
        progressTrack = new FrameLayout(this);
        progressTrack.setBackground(box(line, line, 2));
        progress = new View(this);
        progressTrack.addView(progress, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(4));
        pl.topMargin = dp(14);
        pl.bottomMargin = dp(16);
        timer.addView(progressTrack, pl);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        String[] chipText = {"5 MIN", "25 MIN", "50 MIN", "■ STOP"};
        int[] mins = {5, 25, 50, 0};
        for (int i = 0; i < 4; i++) {
            TextView c = mono(12, ink2);
            c.setText(chipText[i]);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(10), 0, dp(10));
            int m = mins[i];
            c.setOnClickListener(v -> {
                if (m == 0) FreqService.stopTimer();
                else {
                    FreqService.startTimer(m);
                    if (FreqService.engine().channel().isEmpty()) choose(prefs.getString("last", "focus"));
                }
                render();
            });
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(i, 1f));
            gl.width = 0;
            gl.setMargins(i == 0 ? 0 : dp(4), 0, i == 3 ? 0 : dp(4), 0);
            chips[i] = c;
            grid.addView(c, gl);
        }
        timer.addView(grid);
        col.addView(timer);
        sessions = mono(11, ink3);
        sessions.setPadding(0, dp(22), 0, 0);
        col.addView(sessions);

        setContentView(root);
        // Bar icon colours only once the window exists (Android 16 crashes otherwise).
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : bits, bits);
        }
        render();
    }

    private void choose(String key) {
        String now = FreqService.engine().channel();
        if (key.equals(now)) {
            FreqService.play(this, "");
        } else {
            prefs.edit().putString("last", key).apply();
            FreqService.play(this, key);
        }
        render();
    }

    private void toggle() {
        choose(FreqService.engine().channel().isEmpty() ? prefs.getString("last", "focus") : FreqService.engine().channel());
    }

    private int index(String key) {
        for (int i = 0; i < FreqEngine.KEYS.length; i++) if (FreqEngine.KEYS[i].equals(key)) return i;
        return -1;
    }

    private void render() {
        String ch = FreqService.engine().channel();
        int i = index(ch), shown = i >= 0 ? i : Math.max(0, index(prefs.getString("last", "focus")));
        int accent = i >= 0 ? COLOURS[i] : rust;
        title.setText(i >= 0 ? FreqService.NAMES[i] : "Wähl einen\nKanal.");
        sub.setText((i >= 0 ? SUB[i] : "Brown noise · Regen · Drone · Lo-Fi · Binaural").toUpperCase(Locale.GERMAN));
        status.setText(i >= 0 ? "● LÄUFT" : "STILL");
        status.setTextColor(i >= 0 ? accent : ink3);
        GradientDrawable pb = new GradientDrawable();
        pb.setShape(GradientDrawable.OVAL);
        pb.setColor(i >= 0 ? accent : ink);
        play.setBackground(pb);
        play.setText(i >= 0 ? "■" : "▶");
        play.setTextColor(i >= 0 ? 0xFFFFFFFF : bg);
        for (int k = 0; k < 5; k++) {
            boolean on = k == i;
            rows[k].setBackgroundColor(on ? active : 0);
            states[k].setText(on ? "■ LÄUFT" : "▶");
            states[k].setTextColor(on ? COLOURS[k] : ink3);
        }
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        volume.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        volume.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(line));
        long end = FreqService.timerEnd(), mins = FreqService.timerMins();
        for (int k = 0; k < 4; k++) {
            boolean on = k < 3 && mins == new int[]{5, 25, 50}[k];
            chips[k].setBackground(box(card, on ? accent : line, 20));
            chips[k].setTextColor(on ? accent : ink2);
        }
        progress.setBackgroundColor(accent);
        if (end > 0) {
            long rem = Math.max(0, end - System.currentTimeMillis());
            clock.setText(String.format(Locale.ROOT, "%02d:%02d", rem / 60000, (rem % 60000) / 1000));
            clock.setTextColor(ink);
            timerState.setText(mins + " MIN");
            float done = 1f - rem / (float) (mins * 60_000L);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progress.getLayoutParams();
            lp.width = Math.round(progressTrack.getWidth() * done);
            progress.setLayoutParams(lp);
        } else {
            clock.setText("--:--");
            clock.setTextColor(ink3);
            timerState.setText("");
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progress.getLayoutParams();
            lp.width = 0;
            progress.setLayoutParams(lp);
        }
        int s = prefs.getInt("sessions", 0);
        sessions.setText(s > 0 ? (s + " FOKUS-SESSION" + (s > 1 ? "S" : "") + " GESCHAFFT") : "");
        spectrum.accent = accent;
        spectrum.playing = i >= 0;
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            render();
            spectrum.invalidate();
            main.postDelayed(this, 250);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        main.post(frame);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(frame);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    /* ---------------- Spectrum: bars like the ARC-DNA music card ---------------- */

    private final class Spectrum extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float[] buf = new float[512];
        private final float[] level = new float[28];
        int accent;
        boolean playing;

        Spectrum() {
            super(FreqActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            int bars = level.length;
            float gap = dp(4), bw = (getWidth() - (bars - 1) * gap) / bars, h = getHeight();
            if (playing) FreqService.engine().copyScope(buf);
            long t = System.currentTimeMillis();
            for (int i = 0; i < bars; i++) {
                float v;
                if (playing) {
                    // Goertzel at log-spaced frequencies (40 Hz … 8 kHz) over the last 512 samples.
                    double f = 40 * Math.pow(200, i / (double) (bars - 1)), w = 2 * Math.PI * f / FreqEngine.RATE;
                    double coeff = 2 * Math.cos(w), s1 = 0, s2 = 0;
                    for (float x : buf) {
                        double s0 = x + coeff * s1 - s2;
                        s2 = s1;
                        s1 = s0;
                    }
                    double mag = Math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) / buf.length;
                    float db = (float) (20 * Math.log10(mag + 1e-6));
                    v = Math.max(0f, Math.min(1f, (db + 70) / 55f));
                } else {
                    v = 0.05f + 0.03f * (float) Math.sin(t / 300.0 + i * 0.5);
                }
                level[i] += (v - level[i]) * 0.35f;
                float bh = Math.max(dp(4), level[i] * (h - dp(8)));
                p.setColor(i % 4 == 0 ? (playing ? accent : rust) : ink3);
                p.setAlpha(playing ? 255 : 130);
                r.set(i * (bw + gap), h - bh, i * (bw + gap) + bw, h);
                c.drawRoundRect(r, dp(2), dp(2), p);
            }
            if (playing || isShown()) postInvalidateOnAnimation();
        }
    }

    /** The launcher's grid paper behind everything. */
    private final class Grid extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint();

        @Override
        public void draw(Canvas c) {
            c.drawColor(bg);
            p.setColor(night ? 0x09FFFFFF : 0x09000000);
            float cell = dp(32);
            android.graphics.Rect b = getBounds();
            for (float x = 0; x < b.width(); x += cell) c.drawRect(x, 0, x + Math.max(1, dp(1) / 2f), b.height(), p);
            for (float y = 0; y < b.height(); y += cell) c.drawRect(0, y, b.width(), y + Math.max(1, dp(1) / 2f), p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    /* ---------------- Small ARC-DNA view helpers ---------------- */

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

    /** Section label like on the home screen: rust dash, then the name in spaced mono capitals. */
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

    private LinearLayout.LayoutParams margins(int bottom, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }
}
