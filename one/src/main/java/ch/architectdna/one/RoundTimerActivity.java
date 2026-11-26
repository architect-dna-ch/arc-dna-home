package ch.architectdna.one;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * ARC Ring — a boxing round timer like the gym box: big red digits, round time, rest time, number
 * of rounds, a warning clack before the round ends (INT BEEP), a bell to start and three to end.
 * Start/Pause, Stop/Reset; − / + set the times while stopped (kept for next time). The screen stays
 * on; time is counted from the clock, so it never drifts.
 */
public class RoundTimerActivity extends Activity {

    private static final int RED = 0xFFFF2B1C, DIM = 0xFF4A1410, INK = 0xFFF4F1EC, INK2 = 0xFFA9A39A,
        BG = 0xFF000000, CARD = 0xFF141210, LINE = 0xFF2B2824, GREEN = 0xFF56E07A;
    private static final int PREP = 10;                  // seconds to get your gloves up

    private enum Phase { STOPPED, PREP, ROUND, REST, DONE }

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ToneGenerator tone;
    private int roundSec, restSec, rounds, warnSec;
    private Phase phase = Phase.STOPPED;
    private int round;
    private long endsAt, pausedLeft = -1;
    private boolean warned;

    private TextView digits, label, sub, startBtn;
    private TextView[] values = new TextView[4];

    private final Runnable tick = new Runnable() {
        @Override public void run() { step(); main.postDelayed(this, 100); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ring", MODE_PRIVATE);
        roundSec = prefs.getInt("round", 180);
        restSec = prefs.getInt("rest", 60);
        rounds = prefs.getInt("rounds", 12);
        warnSec = prefs.getInt("warn", 10);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        try { tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100); } catch (RuntimeException e) { tone = null; }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(20), dp(28), dp(20), dp(20));

        TextView name = text(11, RED, Typeface.create("monospace", Typeface.BOLD));
        name.setText("— ARC RING · RUNDENTIMER");
        name.setLetterSpacing(0.2f);
        col.addView(name);

        label = text(22, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        label.setPadding(0, dp(26), 0, 0);
        col.addView(label);
        digits = text(112, RED, Typeface.create("monospace", Typeface.BOLD));
        digits.setLetterSpacing(-0.04f);
        digits.setShadowLayer(dp(14), 0, 0, 0x88FF2B1C);       // the glow of an LED segment display
        col.addView(digits);
        sub = text(14, INK2, Typeface.create("monospace", Typeface.NORMAL));
        sub.setPadding(0, 0, 0, dp(26));
        col.addView(sub);

        LinearLayout ctl = new LinearLayout(this);
        startBtn = pill("START", INK, BG, 20);
        startBtn.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); startPause(); });
        TextView stop = pill("STOP / RESET", 0xFFE0573B, 0xFFFFFFFF, 16);
        stop.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); reset(); });
        ctl.addView(startBtn, new LinearLayout.LayoutParams(0, dp(64), 1f));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(64), 1f); sl.leftMargin = dp(10);
        ctl.addView(stop, sl);
        col.addView(ctl, new LinearLayout.LayoutParams(-1, -2));

        String[] names = {"RUNDENZEIT", "PAUSE", "RUNDEN", "WARNUNG VOR ENDE"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(10), dp(10));
            GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(14)); g.setStroke(dp(1), LINE);
            row.setBackground(g);
            TextView n = text(12, INK2, Typeface.create("monospace", Typeface.BOLD));
            n.setText(names[i]);
            n.setGravity(Gravity.START);
            row.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView minus = pill("−", BG, INK, 20), plus = pill("+", BG, INK, 20);
            values[i] = text(20, INK, Typeface.create("monospace", Typeface.BOLD));
            values[i].setMinWidth(dp(84));
            minus.setOnClickListener(v -> adjust(k, -1));
            plus.setOnClickListener(v -> adjust(k, +1));
            row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(44)));
            row.addView(values[i]);
            row.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(44)));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(i == 0 ? 22 : 8);
            col.addView(row, rl);
        }
        TextView presets = text(13, INK2, Typeface.DEFAULT_BOLD);
        presets.setText("Vorlagen:   3:00 / 1:00   ·   2:00 / 0:30   ·   1:00 / 0:15 Tabata");
        presets.setPadding(0, dp(16), 0, 0);
        presets.setOnClickListener(v -> cyclePreset());
        col.addView(presets);

        setContentView(col, new ViewGroup.LayoutParams(-1, -1));
        render(roundSec * 1000L);
        main.post(tick);
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(tick);
        if (tone != null) tone.release();
        super.onDestroy();
    }

    /* ---------------- the clock ---------------- */

    private void startPause() {
        long now = SystemClock.elapsedRealtime();
        if (phase == Phase.STOPPED || phase == Phase.DONE) {
            round = 1; warned = false; pausedLeft = -1;
            phase = Phase.PREP; endsAt = now + PREP * 1000L;
            startBtn.setText("PAUSE");
            return;
        }
        if (pausedLeft >= 0) { endsAt = now + pausedLeft; pausedLeft = -1; startBtn.setText("PAUSE"); }
        else { pausedLeft = Math.max(0, endsAt - now); startBtn.setText("WEITER"); }
    }

    private void reset() {
        phase = Phase.STOPPED; pausedLeft = -1; round = 0;
        startBtn.setText("START");
        render(roundSec * 1000L);
    }

    private void step() {
        if (phase == Phase.STOPPED || phase == Phase.DONE) return;
        long now = SystemClock.elapsedRealtime();
        long left = pausedLeft >= 0 ? pausedLeft : endsAt - now;
        if (phase == Phase.ROUND && !warned && warnSec > 0 && left <= warnSec * 1000L && pausedLeft < 0) {
            warned = true;
            beep(ToneGenerator.TONE_PROP_BEEP2, 160);                  // the "10 seconds" clack
        }
        if (left <= 0 && pausedLeft < 0) {
            switch (phase) {
                case PREP:
                    go(Phase.ROUND, roundSec, now); bell(1); break;
                case ROUND:
                    if (round >= rounds) { phase = Phase.DONE; bell(3); startBtn.setText("START"); render(0); return; }
                    if (restSec > 0) { go(Phase.REST, restSec, now); bell(3); }
                    else { round++; go(Phase.ROUND, roundSec, now); bell(1); }
                    break;
                case REST:
                    round++; go(Phase.ROUND, roundSec, now); bell(1); break;
                default: break;
            }
            left = endsAt - now;
        }
        render(left);
    }

    private void go(Phase p, int seconds, long now) {
        phase = p; endsAt = now + seconds * 1000L; warned = false;
        digits.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void render(long leftMs) {
        long s = Math.max(0, (leftMs + 999) / 1000);
        digits.setText(String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60));
        boolean paused = pausedLeft >= 0;
        switch (phase) {
            case PREP: label.setText(paused ? "PAUSIERT" : "BEREIT MACHEN"); digits.setTextColor(0xFFFFC23A); break;
            case ROUND: label.setText((paused ? "PAUSIERT · " : "") + "RUNDE " + round + " / " + rounds);
                digits.setTextColor(warned ? 0xFFFFC23A : RED); break;
            case REST: label.setText((paused ? "PAUSIERT · " : "") + "PAUSE · NÄCHSTE RUNDE " + (round + 1)); digits.setTextColor(GREEN); break;
            case DONE: label.setText("FERTIG · " + rounds + " RUNDEN"); digits.setTextColor(GREEN); break;
            default: label.setText("BEREIT"); digits.setTextColor(RED);
        }
        long total = (long) rounds * roundSec + (long) Math.max(0, rounds - 1) * restSec;
        sub.setText(rounds + " × " + mmss(roundSec) + "  ·  Pause " + mmss(restSec) + "  ·  total " + mmss((int) total));
        values[0].setText(mmss(roundSec));
        values[1].setText(mmss(restSec));
        values[2].setText(String.valueOf(rounds));
        values[3].setText(warnSec == 0 ? "aus" : warnSec + " s");
    }

    /* ---------------- settings ---------------- */

    private void adjust(int k, int dir) {
        if (phase != Phase.STOPPED && phase != Phase.DONE) return;          // like the box: set while stopped
        switch (k) {
            case 0: roundSec = clamp(roundSec + dir * (roundSec >= 60 ? 15 : 5), 10, 15 * 60); break;
            case 1: restSec = clamp(restSec + dir * (restSec >= 60 ? 15 : 5), 0, 10 * 60); break;
            case 2: rounds = clamp(rounds + dir, 1, 99); break;
            default: warnSec = clamp(warnSec + dir * 5, 0, 60);
        }
        save();
        phase = Phase.STOPPED;
        render(roundSec * 1000L);
    }

    private int preset;

    private void cyclePreset() {
        if (phase != Phase.STOPPED && phase != Phase.DONE) return;
        int[][] p = {{180, 60, 12}, {120, 30, 10}, {60, 15, 8}};
        preset = (preset + 1) % p.length;
        roundSec = p[preset][0]; restSec = p[preset][1]; rounds = p[preset][2];
        save();
        phase = Phase.STOPPED;
        render(roundSec * 1000L);
    }

    private void save() {
        prefs.edit().putInt("round", roundSec).putInt("rest", restSec).putInt("rounds", rounds).putInt("warn", warnSec).apply();
    }

    /* ---------------- sound ---------------- */

    private void bell(int times) {
        for (int i = 0; i < times; i++) main.postDelayed(() -> beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 380), i * 520L);
    }

    private void beep(int t, int ms) {
        if (tone != null) try { tone.startTone(t, ms); } catch (RuntimeException ignored) { }
    }

    /* ---------------- bits ---------------- */

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String mmss(int s) { return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60); }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setTypeface(tf); t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView pill(String s, int fill, int ink, float sp) {
        TextView t = text(sp, ink, Typeface.DEFAULT_BOLD);
        t.setText(s);
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(18));
        if (fill == BG) g.setStroke(dp(1), LINE);
        t.setBackground(g);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
