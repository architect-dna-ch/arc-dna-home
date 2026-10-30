package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.Locale;

/**
 * SOS · Zwang stoppen – not a phone line, a loop-breaker. OCD / scrupulosity throws a doubt
 * ("is that a sin? may I? only because she's a woman?"); the trained move is NOT to answer it, NOT
 * to check, NOT to neutralise – to name it as the compulsion, let the uncertainty stand, turn
 * toward the harmless thing the doubt forbids, and ride the urge until it falls (it always falls).
 * This screen walks that exposure + response-prevention cycle and pins the screen so nothing pulls
 * you back into the loop. The handle is always there: hold three seconds to leave.
 */
public class CrisisActivity extends Activity {

    private static final int PICK = 0, NAME = 1, EXPOSE = 2, RIDE = 3, DONE = 4;

    // Seeded loops (editable idea of what the doubt attacks); the user adds their own.
    private static final String[] SEED = {
        "„Ist das Sünde? Richtig oder falsch?“",
        "„Darf diese Freundschaft sein – nur weil sie eine Frau ist?“",
        "„Darf ich das geniessen (Film, Ruhe, YouTube)?“",
        "„Muss ich etwas wiedergutmachen / nachholen?“",
        "„Bin ich gut genug / ein guter Mensch?“",
    };
    // The exposure line per loop: agreeing to the uncertainty instead of solving it.
    private static final String EXPO =
        "Vielleicht. Ich weiss es nicht, und ich lasse es offen.\n\n"
            + "Ich beantworte die Frage NICHT. Ich google nicht, ich bete sie nicht weg, ich frage niemanden, "
            + "ich checke nicht.\n\nDer Zweifel darf da sein. Er entscheidet nicht. Ich lebe weiter – jetzt, "
            + "mit genau dem, was er mir verbieten will.";

    private SharedPreferences sos;
    private int state = PICK;
    private String loop = "";
    private int minutes = 20;
    private long rideEnd;
    private int spikes;
    private LinearLayout col;
    private long holdStart;
    private boolean pinned;
    private final android.os.Handler h = new android.os.Handler();
    // ARC-DNA palette, from the picked theme.
    private static int cBg, cInk, cInk2, cInk3, cAccent, cCard, cLine, cGreen = 0xFF2BBF6A, cAmber = 0xFFE8B931;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        sos = getSharedPreferences("sos", MODE_PRIVATE);
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int[] pl = Themes.current(getSharedPreferences("home", MODE_PRIVATE)).pal(night);
        cBg = pl[Themes.BG] | 0xFF000000;
        cInk = pl[Themes.INK];
        cInk2 = pl[Themes.INK2];
        cInk3 = pl[Themes.INK3];
        cAccent = pl[Themes.ACCENT];
        cCard = pl[Themes.PANEL] | 0xFF000000;
        cLine = pl[Themes.LINE];
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(cBg);
        getWindow().setNavigationBarColor(cBg);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(cBg);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(dp(26), top + dp(34), dp(26), bottom + dp(26));
            return insets;
        });
        col.setPadding(dp(26), dp(70), dp(26), dp(26));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        render();
    }

    private void render() {
        col.removeAllViews();
        if (state == PICK) renderPick();
        else if (state == NAME) renderName();
        else if (state == EXPOSE) renderExpose();
        else if (state == RIDE) renderRide();
        else renderDone();
        addExit();
    }

    /* 1. What is the loop right now? */
    private void renderPick() {
        head("SOS · Zwang stoppen", "Welcher Gedanke dreht sich gerade?");
        int wins = sos.getInt("wins", 0);
        if (wins > 0) col.addView(small("Schon " + wins + "× nicht gehorcht. Jedes Mal zählt."));
        boolean pin = sos.getBoolean("pin", false);
        TextView pinToggle = small((pin ? "🔒 Ablenkung gesperrt" : "🔓 Ablenkung frei") + " · antippen zum Umschalten");
        pinToggle.setOnClickListener(v -> {
            boolean now = !sos.getBoolean("pin", false);
            sos.edit().putBoolean("pin", now).apply();
            if (now && Build.VERSION.SDK_INT >= 21 && !pinned) {
                try {
                    startLockTask();
                    pinned = true;
                } catch (RuntimeException ignored) {
                }
            } else if (!now && pinned && Build.VERSION.SDK_INT >= 21) {
                try {
                    stopLockTask();
                } catch (RuntimeException ignored) {
                }
                pinned = false;
            }
            render();
        });
        col.addView(pinToggle);
        for (String custom : customLoops()) col.addView(bigButton(custom, cCard, cLine, () -> choose(custom)));
        for (String sd : SEED) col.addView(bigButton(sd, cCard, cLine, () -> choose(sd)));
        EditText in = new EditText(this);
        in.setHint("Eigener Gedanke …");
        in.setHintTextColor(cInk3);
        in.setTextColor(cInk);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        in.setBackground(box(cCard, cLine, 14));
        in.setPadding(dp(16), dp(14), dp(16), dp(14));
        col.addView(in, lp(dp(10)));
        col.addView(bigButton("→ Diesen Gedanken stoppen", cCard, cAccent, () -> {
            String t = in.getText().toString().trim();
            if (t.isEmpty()) return;
            saveCustom(t);
            choose(t);
        }));
    }

    private void choose(String l) {
        loop = l;
        state = NAME;
        render();
    }

    /* 2. Name it as the compulsion. */
    private void renderName() {
        head("Das ist der Zwang.", loop);
        col.addView(para("Nicht du. Nicht Gott. Der Zwang.\n\n"
            + "Er stellt eine Frage, auf die es keine sichere Antwort gibt, und tut so, als müsstest du sie "
            + "JETZT lösen. Musst du nicht. Die Frage ist die Falle, nicht die Lösung.", cInk, 17));
        col.addView(para("Wenn dein einziger naher Mensch eine Frau ist, dann ist das ein Geschenk – kein Fall "
            + "fürs Gericht. Der Zwang macht „weil Frau“ zum Problem. Du nicht.", cGreen, 15));
        col.addView(bigButton("Weiter – ich beantworte sie NICHT", cCard, cAccent, () -> {
            state = EXPOSE;
            render();
        }));
    }

    /* 3. Exposure: agree to the uncertainty, turn toward the forbidden-wanted thing. */
    private void renderExpose() {
        head("Unsicherheit stehen lassen", null);
        col.addView(para(EXPO, cInk, 17));
        col.addView(small("Sag es einmal langsam – laut oder im Kopf."));
        TextView howLong = small("Wie lange hältst du durch, ohne zu checken? Die Welle sinkt meist in 20 Min.");
        col.addView(howLong);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (int m : new int[]{10, 20, 30}) {
            TextView b = chip(m + " Min", m == minutes);
            b.setOnClickListener(v -> {
                minutes = m;
                renderExpose();
            });
            row.addView(b);
        }
        col.addView(row, lp(dp(10)));
        col.addView(bigButton("Jetzt aushalten (" + minutes + " Min)", cCard, cGreen, () -> {
            rideEnd = SystemClock.uptimeMillis() + minutes * 60_000L;
            spikes = 0;
            state = RIDE;
            render();
            tick();
        }));
    }

    /* 4. Response prevention: ride the urge, the wave falls. */
    private UrgeWave wave;
    private TextView clock, waveNote;

    private void renderRide() {
        head("Nicht gehorchen. Nur warten.", loop);
        col.addView(para("Nicht googeln · nicht wegbeten · nicht nachfragen · nicht checken.\n"
            + "Der Drang ist eine Welle. Du musst nichts tun. Sie sinkt von allein.", cGreen, 15));
        wave = new UrgeWave(this);
        col.addView(wave, new LinearLayout.LayoutParams(-1, dp(120)));
        clock = text(40, cInk, true);
        clock.setGravity(Gravity.CENTER);
        col.addView(clock, lp(dp(8)));
        waveNote = small("Jeder Drang, dem du nicht folgst, macht den Zwang kleiner.");
        col.addView(waveNote);
        col.addView(bigButton("Ein Drang kam hoch", cCard, cAmber, () -> {
            spikes++;
            if (wave != null) wave.spike();
            if (waveNote != null) waveNote.setText("Drang #" + spikes + ". Nicht reagiert = Welle bricht. Schau zu, wie sie sinkt.");
        }));
        col.addView(bigButton("Ich halte durch – Film/Ruhe JETZT", cCard, cLine, this::toward));
    }

    private void toward() {
        // You decided to go do the harmless thing the doubt forbids: unpin and let you out.
        leave.run();
    }

    private void tick() {
        if (state != RIDE) return;
        long left = rideEnd - SystemClock.uptimeMillis();
        if (left <= 0) {
            sos.edit().putInt("wins", sos.getInt("wins", 0) + 1).apply();
            logWin();
            state = DONE;
            render();
            return;
        }
        if (clock != null) clock.setText(String.format(Locale.ROOT, "%d:%02d", left / 60000, left / 1000 % 60));
        h.postDelayed(this::tick, 250);
    }

    private void logWin() {
        try {
            JSONArray a = new JSONArray(sos.getString("log", "[]"));
            a.put(new org.json.JSONObject().put("at", System.currentTimeMillis()).put("loop", loop).put("spikes", spikes));
            while (a.length() > 100) a.remove(0);
            sos.edit().putString("log", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /* 5. Done. */
    private void renderDone() {
        head("Geschafft.", null);
        col.addView(para("Du hast dem Zwang nicht gehorcht. Die Frage ist noch offen – und du lebst trotzdem "
            + "weiter. Genau das schwächt ihn, jedes Mal ein bisschen mehr.", cInk, 17));
        col.addView(small(spikes > 0 ? spikes + " Drang-Wellen – alle vorbei." : "Ruhig geblieben."));
        col.addView(bigButton("Nochmal einen Gedanken stoppen", cCard, cLine, () -> {
            state = PICK;
            loop = "";
            render();
        }));
    }

    /* ---- custom loops ---- */

    private String[] customLoops() {
        try {
            JSONArray a = new JSONArray(sos.getString("custom", "[]"));
            String[] out = new String[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.getString(i);
            return out;
        } catch (Exception e) {
            return new String[0];
        }
    }

    private void saveCustom(String t) {
        try {
            JSONArray a = new JSONArray(sos.getString("custom", "[]"));
            for (int i = 0; i < a.length(); i++) if (t.equals(a.getString(i))) return;
            a.put(t);
            while (a.length() > 8) a.remove(0);
            sos.edit().putString("custom", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /* ---- pinning + gentle exit ---- */

    @Override
    protected void onResume() {
        super.onResume();
        if (wave != null) wave.start();
        if (state == RIDE) tick();
        // Pin only if the user asked for it (otherwise Android pops its scary "App is pinned" dialog).
        if (sos.getBoolean("pin", false) && Build.VERSION.SDK_INT >= 21 && !pinned) {
            try {
                startLockTask();
                pinned = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wave != null) wave.stop();
    }

    @Override
    public void onBackPressed() {
        // Only the deliberate hold leaves.
    }

    private void addExit() {
        TextView exit = text(14, cInk2, false);
        exit.setText("Raus – 3 Sekunden halten");
        exit.setGravity(Gravity.CENTER);
        exit.setPadding(dp(16), dp(14), dp(16), dp(14));
        exit.setBackground(box(cCard, cLine, 16));
        exit.setOnTouchListener((v, e) -> {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                holdStart = SystemClock.uptimeMillis();
                v.animate().alpha(0.5f).setDuration(3000).start();
                v.postDelayed(leave, 3000);
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                v.removeCallbacks(leave);
                v.animate().cancel();
                v.setAlpha(1f);
            }
            return true;
        });
        col.addView(exit, lp(dp(22)));
        TextView line = text(12, cInk3, false);
        line.setGravity(Gravity.CENTER);
        line.setText("In echter Gefahr für dich oder andere: 143 halten.");
        line.setOnClickListener(v -> {
            if (pinned && Build.VERSION.SDK_INT >= 21) {
                try {
                    stopLockTask();
                } catch (RuntimeException ignored) {
                }
                pinned = false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:143")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
            finish();
        });
        col.addView(line, lp(dp(8)));
    }

    private final Runnable leave = () -> {
        if (pinned && Build.VERSION.SDK_INT >= 21) {
            try {
                stopLockTask();
            } catch (RuntimeException ignored) {
            }
            pinned = false;
        }
        finish();
    };

    /* ---- view helpers ---- */

    private void head(String title, String sub) {
        TextView t = text(25, cInk, true);
        t.setText(title);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(16, cInk2, false);
            s.setText(sub);
            s.setGravity(Gravity.CENTER);
            s.setLineSpacing(0, 1.15f);
            col.addView(s, lp(dp(6)));
        }
        col.addView(space(dp(14)));
    }

    private TextView para(String txt, int colour, float sp) {
        TextView t = text(sp, colour, false);
        t.setText(txt);
        t.setLineSpacing(0, 1.28f);
        LinearLayout.LayoutParams p = lp(dp(14));
        t.setLayoutParams(p);
        return t;
    }

    private TextView small(String txt) {
        TextView t = text(13, cInk2, false);
        t.setText(txt);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.2f);
        t.setLayoutParams(lp(dp(12)));
        return t;
    }

    private TextView bigButton(String label, int fill, int stroke, Runnable act) {
        TextView t = text(16, cInk, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(16), dp(18), dp(16));
        t.setBackground(box(fill, stroke, 16));
        t.setLayoutParams(lp(dp(10)));
        if (act != null) t.setOnClickListener(v -> act.run());
        return t;
    }

    private TextView chip(String label, boolean on) {
        TextView t = text(14, on ? cBg : cInk, true);
        t.setText(label);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setBackground(box(on ? cGreen : cCard, on ? cGreen : cLine, 20));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(dp(5), 0, dp(5), 0);
        t.setLayoutParams(p);
        return t;
    }

    private View space(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px));
        return v;
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, top, 0, 0);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    private android.graphics.drawable.GradientDrawable box(int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The urge as a wave: a new spike rises, then always falls back – you watch it pass. */
    private static final class UrgeWave extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level, target;
        private long last;
        private boolean running;

        UrgeWave(android.content.Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            t.setTextAlign(Paint.Align.CENTER);
        }

        void start() {
            running = true;
            last = 0;
            postInvalidateOnAnimation();
        }

        void stop() {
            running = false;
        }

        void spike() {
            target = 1f;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), dp = getResources().getDisplayMetrics().density;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0 : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            target *= Math.pow(0.4, dt);              // the urge always decays
            level += (target - level) * Math.min(1, dt * 6);
            // baseline + wave
            p.setColor(cLine);
            p.setStrokeWidth(2 * dp);
            c.drawLine(0, h * 0.5f, w, h * 0.5f, p);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(0, h * 0.85f);
            for (float x = 0; x <= w; x += dp * 4) {
                float ph = x / w;
                float env = (float) Math.exp(-Math.pow((ph - 0.5f) * 3.2f, 2));
                float y = h * 0.85f - level * env * h * 0.62f * (0.85f + 0.15f * (float) Math.sin(now / 160.0 + ph * 10));
                path.lineTo(x, y);
            }
            p.setColor(level > 0.55f ? cAmber : cGreen);
            p.setStrokeWidth(3 * dp);
            c.drawPath(path, p);
            t.setColor(cInk2);
            t.setTextSize(13 * dp);
            c.drawText(level > 0.55f ? "Drang hoch – nichts tun, zuschauen" : level > 0.12f ? "sinkt …" : "ruhig", w / 2, h - 6 * dp, t);
            if (running) postInvalidateOnAnimation();
        }
    }
}
