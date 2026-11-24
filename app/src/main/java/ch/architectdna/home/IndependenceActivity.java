package ch.architectdna.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;

/**
 * Unabhängig — emotional independence, one button at a time.
 *
 * For the pull to text someone again, check whether they replied, look for reassurance, or feel empty
 * alone. The method is urge surfing (CBT/DBT): an urge is a wave, not a command — it rises, peaks and
 * falls within minutes if you don't act on it, and each wave you ride out makes the next one weaker.
 *
 * Flow: "Ich spüre den Drang" → what it pulls you to + how strong (1–10) → ride the wave (a breathing
 * wave, 4 s in / 6 s out, with prompts for that kind of urge and your own sentences) → how strong now →
 * your choice, without shame → "Was brauchst du wirklich?" and how to give it to yourself.
 * Every wave is logged on the phone only, so you see the proof: how many, and how far they fell.
 */
public class IndependenceActivity extends Activity {

    // calm, deep water — not the launcher's rust
    private static final int BG = 0xFF0B1620, CARD = 0xFF132331, LINE = 0xFF1F3446,
        INK = 0xFFEFE9DF, INK2 = 0xFF9FB2C1, INK3 = 0xFF627789, SEA = 0xFF5CC3C9, SAND = 0xFFE8C99B;

    private static final String[][] URGES = {
        {"schreiben", "💬", "Der Person schreiben"},
        {"checken", "📱", "Checken, ob sie geantwortet hat"},
        {"bestaetigung", "🪞", "Bestätigung suchen"},
        {"denken", "🌀", "Ständig an die Person denken"},
        {"leer", "🕳", "Allein fühlt sich leer an"},
        {"anderes", "·", "Etwas anderes"},
    };

    private static final String[] COMMON = {
        "Ein Gefühl ist eine Welle, kein Befehl. Sie steigt — und sie fällt.",
        "Atme mit der Welle: ein, wenn sie steigt. Aus, wenn sie fällt.",
        "Du musst jetzt nichts entscheiden. Nur atmen.",
        "Spür deine Füsse am Boden. Drück sie kurz fest auf.",
        "Der Drang wird oft stärker, bevor er schwächer wird. Das ist normal.",
        "Benenne fünf Dinge, die du gerade siehst.",
    };

    private static String[] promptsFor(String type) {
        switch (type) {
            case "schreiben": return new String[]{
                "Die Nachricht läuft nicht weg. Nach der Welle kannst du immer noch entscheiden.",
                "Was soll die Nachricht in dir auslösen? Ruhe? Nähe? Kannst du dir das gerade selbst geben?",
                "Schreib die Nachricht in Gedanken fertig — und schick sie nicht. Nur anschauen."};
            case "checken": return new String[]{
                "Checken bringt kurz Erleichterung — und dann den nächsten Drang. Du unterbrichst gerade den Kreis.",
                "Leg das Handy umgedreht hin, bis die Welle fällt. Hier läuft die Zeit weiter.",
                "Ob jemand antwortet, sagt nichts über deinen Wert."};
            case "bestaetigung": return new String[]{
                "Was würdest du einem guten Freund in genau dieser Lage sagen? Sag es dir.",
                "Dein Wert stand heute Morgen fest. Er steht noch.",
                "Bestätigung von aussen ist schön. Gebraucht wird sie nicht."};
            case "denken": return new String[]{
                "Gedanken an einen Menschen sind kein Auftrag. Lass sie vorbeiziehen wie Wolken.",
                "Du musst den Gedanken nicht wegdrücken. Nur nicht mitlaufen.",
                "Wo bist du gerade — dieser Raum, diese Minute? Bleib hier."};
            case "leer": return new String[]{
                "Allein sein ist nicht leer sein. Was tut dir jetzt gut: Musik, Dusche, kurz raus?",
                "Nähe ist gut. Sie darf von mehreren Menschen kommen — nicht nur von einem.",
                "Du bist gerade mit dir. Das ist auch Gesellschaft."};
            default: return COMMON;
        }
    }

    private static final String[][] NEEDS = {
        {"Gesehen werden", "Ich sehe mich.", "Schreib einen Satz: Was hast du heute geschafft, egal wie klein?"},
        {"Sicherheit", "Ich bin jetzt sicher. Nichts muss jetzt geklärt werden.", "Atme dreimal lang aus — länger aus als ein."},
        {"Wert", "Mein Wert hängt nicht an einer Antwort.", "Nenn eine Sache, die du heute gut gemacht hast."},
        {"Nähe", "Ich darf Nähe wollen, ohne sie jetzt zu brauchen.", "Melde dich bei jemand anderem: Familie, ein Kollege, ein Freund."},
        {"Ruhe", "Es darf jetzt still sein.", "Fünf Minuten ohne Bildschirm. Fenster auf, Wasser trinken."},
    };

    private static final String[] DEFAULT_ANCHORS = {
        "Mein Wert hängt nicht an einer Antwort.",
        "Ich kann Nähe wollen, ohne sie jetzt zu brauchen.",
        "Ein Gefühl ist eine Welle, kein Befehl.",
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private SharedPreferences prefs;
    private FrameLayout stage;
    private String screen = "home";

    // the wave in progress
    private String urgeType;
    private int before;
    private long waveStart, waveLen = 10 * 60_000L;
    private int waveId;                      // each wave's loops stop as soon as a newer wave (or screen) takes over

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        stage = new FrameLayout(this);
        stage.setBackgroundColor(BG);
        stage.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(), ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins.consumeSystemWindowInsets();
        });
        setContentView(stage, new ViewGroup.LayoutParams(-1, -1));
        showHome();
    }

    @Override public void onBackPressed() {
        if ("wave".equals(screen)) { showAfter(); return; }     // leaving the wave = it's over; still rate it
        if (!"home".equals(screen)) { showHome(); return; }
        super.onBackPressed();
    }

    /* ======================= home: one button ======================= */

    private void showHome() {
        screen = "home";
        keepScreenOn(false);
        LinearLayout c = page();
        c.addView(eyebrow("— ARC · UNABHÄNGIG"));
        TextView h = title("Du entscheidest.\nNicht der Drang.");
        c.addView(h);
        TextView sub = body("Wenn es dich zieht — schreiben, checken, Bestätigung suchen — reite die Welle, statt ihr zu folgen. Sie fällt von selbst.", INK2);
        sub.setPadding(0, dp(10), 0, 0);
        c.addView(sub);

        TextView go = bigButton("🌊   Ich spüre den Drang");
        go.setOnClickListener(v -> showName());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(84)); gl.topMargin = dp(28);
        c.addView(go, gl);

        // the proof
        JSONArray log = log();
        LinearLayout proof = card();
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.topMargin = dp(16);
        c.addView(proof, pl);
        if (log.length() == 0) {
            proof.addView(body("Hier siehst du bald deinen Beweis: wie viele Wellen du überstanden hast — und wie stark sie danach noch waren.", INK2));
        } else {
            int n = log.length(), held = 0, week = 0; double sb = 0, sa = 0;
            long weekAgo = System.currentTimeMillis() - 7L * 24 * 3600_000L;
            for (int i = 0; i < n; i++) {
                JSONObject o = log.optJSONObject(i); if (o == null) continue;
                sb += o.optInt("before"); sa += o.optInt("after");
                if ("held".equals(o.optString("outcome"))) held++;
                if (o.optLong("at") > weekAgo) week++;
            }
            TextView big = text(30, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
            big.setText(n + (n == 1 ? " Welle überstanden" : " Wellen überstanden"));
            proof.addView(big);
            TextView avg = text(16, SEA, Typeface.DEFAULT_BOLD);
            avg.setText(String.format(java.util.Locale.GERMAN, "Im Schnitt von %.1f auf %.1f gefallen", sb / n, sa / n));
            avg.setPadding(0, dp(4), 0, 0);
            proof.addView(avg);
            TextView more = body(held + " davon losgelassen · " + week + " diese Woche", INK2);
            more.setPadding(0, dp(6), 0, 0);
            proof.addView(more);
        }

        LinearLayout row = new LinearLayout(this);
        TextView needs = chip("Was brauche ich?");
        needs.setOnClickListener(v -> showNeeds(false));
        TextView anchors = chip("Meine Sätze");
        anchors.setOnClickListener(v -> showAnchors());
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(0, -2, 1f); r1.rightMargin = dp(8);
        row.addView(needs, r1);
        row.addView(anchors, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(12);
        c.addView(row, rl);

        TextView help = body("Wenn es zu schwer wird: Die Dargebotene Hand, Tel. 143 — rund um die Uhr, anonym. Diese App ersetzt keine Therapie.", INK3);
        help.setTextSize(12);
        help.setPadding(0, dp(28), 0, 0);
        c.addView(help);
        show(c);
    }

    /* ======================= 1 · name it ======================= */

    private void showName() {
        screen = "name";
        urgeType = null; before = 0;
        LinearLayout c = page();
        c.addView(eyebrow("— 1 / 3 · BENENNEN"));
        c.addView(title("Wozu zieht es dich?"));
        final TextView[] chosen = {null};
        final TextView start = bigButton("Welle reiten  →");
        start.setAlpha(0.35f);

        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = dp(16);
        c.addView(list, ll);
        for (final String[] u : URGES) {
            final TextView t = option(u[1] + "   " + u[2]);
            t.setOnClickListener(v -> {
                if (chosen[0] != null) select(chosen[0], false);
                chosen[0] = t; select(t, true); urgeType = u[0];
                start.setAlpha(urgeType != null && before > 0 ? 1f : 0.35f);
            });
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2); op.topMargin = dp(8);
            list.addView(t, op);
        }

        TextView q = text(17, INK, Typeface.DEFAULT_BOLD); q.setText("Wie stark gerade?");
        q.setPadding(0, dp(24), 0, dp(10));
        c.addView(q);
        c.addView(scale(v -> { before = v; start.setAlpha(urgeType != null ? 1f : 0.35f); }));

        start.setOnClickListener(v -> { if (urgeType != null && before > 0) showWave(); });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(72)); sl.topMargin = dp(26);
        c.addView(start, sl);
        show(c);
    }

    /* ======================= 2 · ride the wave ======================= */

    private void showWave() {
        screen = "wave";
        keepScreenOn(true);
        waveStart = SystemClock.elapsedRealtime();
        final int id = ++waveId;
        main.postDelayed(() -> { if (id == waveId && "wave".equals(screen)) showAfter(); }, waveLen);
        FrameLayout f = new FrameLayout(this);
        final WaveView wave = new WaveView(this);
        f.addView(wave, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout over = new LinearLayout(this);
        over.setOrientation(LinearLayout.VERTICAL);
        over.setPadding(dp(26), dp(26), dp(26), dp(26));
        f.addView(over, new FrameLayout.LayoutParams(-1, -1));
        over.addView(eyebrow("— 2 / 3 · DIE WELLE REITEN"));
        final TextView prompt = text(24, INK, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        prompt.setLineSpacing(0, 1.12f);
        prompt.setPadding(0, dp(14), 0, 0);
        over.addView(prompt);
        View spacer = new View(this);
        over.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        final TextView anchor = text(15, SAND, Typeface.create("serif", Typeface.ITALIC));
        anchor.setGravity(Gravity.CENTER);
        over.addView(anchor, new LinearLayout.LayoutParams(-1, -2));
        TextView end = bigButton("Die Welle ist gefallen");
        end.setOnClickListener(v -> showAfter());
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, dp(64)); el.topMargin = dp(16);
        over.addView(end, el);
        TextView later = body("Du kannst jederzeit beenden. Am meisten bringt es, bis der Timer abläuft.", INK3);
        later.setTextSize(12); later.setGravity(Gravity.CENTER); later.setPadding(0, dp(8), 0, 0);
        over.addView(later);

        final String[] specific = promptsFor(urgeType);
        final String[] anchors = anchors();
        Runnable rotate = new Runnable() {
            int i = 0;
            @Override public void run() {
                if (id != waveId || !"wave".equals(screen)) return;
                String p = (i % 2 == 0 ? specific[(i / 2) % specific.length] : COMMON[rnd.nextInt(COMMON.length)]);
                fadeTo(prompt, p);
                if (anchors.length > 0) fadeTo(anchor, "«" + anchors[i % anchors.length] + "»");
                i++;
                main.postDelayed(this, 40_000);
            }
        };
        rotate.run();
        show(f);
    }

    /** The breathing wave: rises 4 s (in), falls 6 s (out), with the time left. */
    private final class WaveView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        WaveView(Context c) {
            super(c);
            txt.setColor(INK); txt.setTextAlign(Paint.Align.CENTER); txt.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            small.setColor(INK2); small.setTextAlign(Paint.Align.CENTER); small.setTypeface(Typeface.MONOSPACE);
        }
        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            long t = SystemClock.elapsedRealtime() - waveStart;
            double cyc = (t % 10_000) / 1000.0;                               // 0..10 s
            boolean in = cyc < 4;
            double lvl = in ? ease(cyc / 4.0) : 1 - ease((cyc - 4) / 6.0);    // 0..1
            float base = (float) (h * (0.86 - 0.36 * lvl));
            for (int layer = 0; layer < 3; layer++) {
                path.reset();
                float amp = dp(10 + layer * 6), phase = (float) (t / (1400.0 + layer * 500)) + layer;
                path.moveTo(0, h);
                for (int x = 0; x <= w; x += 8) {
                    double y = base + layer * dp(14) + Math.sin(x / (float) dp(70 + layer * 30) + phase) * amp;
                    path.lineTo(x, (float) y);
                }
                path.lineTo(w, h); path.close();
                int a = 70 + layer * 50;
                fill.setShader(new LinearGradient(0, base, 0, h, (a << 24) | (SEA & 0xFFFFFF), (a << 24) | 0x0B2A3A, Shader.TileMode.CLAMP));
                cv.drawPath(path, fill);
            }
            txt.setTextSize(dp(26));
            cv.drawText(in ? "ein" : "aus", w / 2f, base - dp(22), txt);
            long left = Math.max(0, waveLen - t);
            small.setTextSize(dp(13));
            cv.drawText(String.format(java.util.Locale.ROOT, "%d:%02d", left / 60_000, (left / 1000) % 60), w / 2f, h - dp(150), small);
            postInvalidateOnAnimation();
        }
        private double ease(double x) { return 0.5 - 0.5 * Math.cos(Math.PI * Math.max(0, Math.min(1, x))); }
    }

    /* ======================= 3 · after ======================= */

    private void showAfter() {
        if ("after".equals(screen)) return;
        screen = "after";
        keepScreenOn(false);
        final long minutes = Math.max(1, (SystemClock.elapsedRealtime() - waveStart) / 60_000);
        LinearLayout c = page();
        c.addView(eyebrow("— 3 / 3 · DANACH"));
        c.addView(title("Wie stark jetzt?"));
        final int[] after = {0};
        final LinearLayout choice = new LinearLayout(this);
        choice.setOrientation(LinearLayout.VERTICAL);
        choice.setVisibility(View.GONE);
        final TextView result = text(22, SEA, Typeface.create("sans-serif-black", Typeface.NORMAL));

        c.addView(space(16));
        c.addView(scale(v -> {
            after[0] = v;
            int drop = before - v;
            result.setText(before + " → " + v + (drop > 0 ? "   ·   gefallen" : drop == 0 ? "   ·   gleich geblieben" : "   ·   gestiegen"));
            choice.setVisibility(View.VISIBLE);
        }));
        result.setPadding(0, dp(18), 0, 0);
        c.addView(result);

        TextView q = body("Was machst du jetzt? Beides ist okay — wichtig ist, dass du entscheidest, nicht der Drang.", INK2);
        q.setPadding(0, dp(14), 0, dp(4));
        choice.addView(q);
        TextView held = bigButton("Ich lasse es los");
        held.setOnClickListener(v -> { save(after[0], "held", minutes); showNeeds(true); });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, dp(64)); hl.topMargin = dp(8);
        choice.addView(held, hl);
        TextView chose = option("Ich entscheide mich bewusst dafür");
        chose.setGravity(Gravity.CENTER);
        chose.setOnClickListener(v -> { save(after[0], "chose", minutes); showNeeds(true); });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(8);
        choice.addView(chose, cl);
        TextView again = body("Noch zu stark? Reite eine zweite Welle — das ist normal.", INK3);
        again.setTextSize(13);
        again.setPadding(0, dp(14), 0, 0);
        again.setOnClickListener(v -> { save(after[0], "again", minutes); before = Math.max(1, after[0]); showWave(); });
        choice.addView(again);
        c.addView(choice);
        show(c);
    }

    /* ======================= what do I really need ======================= */

    private void showNeeds(boolean afterWave) {
        screen = "needs";
        LinearLayout c = page();
        c.addView(eyebrow(afterWave ? "— GUT GEMACHT" : "— SELBST GEBEN"));
        c.addView(title("Was brauchst du wirklich?"));
        TextView sub = body("Hinter dem Drang steckt fast immer ein Bedürfnis. Du kannst es dir selbst geben.", INK2);
        sub.setPadding(0, dp(8), 0, 0);
        c.addView(sub);
        final LinearLayout answer = card();
        answer.setVisibility(View.GONE);
        final TextView aLine = text(22, SAND, Typeface.create("serif", Typeface.ITALIC));
        final TextView aDo = body("", INK);
        aDo.setPadding(0, dp(10), 0, 0);
        answer.addView(aLine); answer.addView(aDo);

        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2); gl.topMargin = dp(16);
        c.addView(grid, gl);
        final TextView[] sel = {null};
        for (final String[] n : NEEDS) {
            final TextView t = option(n[0]);
            t.setOnClickListener(v -> {
                if (sel[0] != null) select(sel[0], false);
                sel[0] = t; select(t, true);
                aLine.setText("«" + n[1] + "»");
                aDo.setText("→ " + n[2]);
                answer.setVisibility(View.VISIBLE);
            });
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2); op.topMargin = dp(8);
            grid.addView(t, op);
        }
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(16);
        c.addView(answer, al);
        TextView done = bigButton("Fertig");
        done.setOnClickListener(v -> showHome());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(64)); dl.topMargin = dp(22);
        c.addView(done, dl);
        show(c);
    }

    /* ======================= my sentences ======================= */

    private void showAnchors() {
        screen = "anchors";
        LinearLayout c = page();
        c.addView(eyebrow("— MEINE SÄTZE"));
        c.addView(title("Was du dir sagen willst."));
        TextView sub = body("Sie erscheinen während der Welle. In deinen eigenen Worten wirken sie am stärksten.", INK2);
        sub.setPadding(0, dp(8), 0, dp(8));
        c.addView(sub);
        String[] cur = anchors();
        final EditText[] fields = new EditText[3];
        for (int i = 0; i < 3; i++) {
            EditText e = new EditText(this);
            e.setText(i < cur.length ? cur[i] : "");
            e.setHint("Satz " + (i + 1));
            e.setTextColor(INK); e.setHintTextColor(INK3); e.setTextSize(17);
            e.setBackground(round(CARD, LINE));
            e.setPadding(dp(16), dp(14), dp(16), dp(14));
            fields[i] = e;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(10);
            c.addView(e, p);
        }
        TextView save = bigButton("Speichern");
        save.setOnClickListener(v -> {
            JSONArray a = new JSONArray();
            for (EditText e : fields) { String s = e.getText().toString().trim(); if (!s.isEmpty()) a.put(s); }
            prefs.edit().putString("indep_anchors", a.toString()).apply();
            showHome();
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(64)); sl.topMargin = dp(20);
        c.addView(save, sl);
        show(c);
    }

    /* ======================= data (on the phone only) ======================= */

    private JSONArray log() {
        try { return new JSONArray(prefs.getString("indep_log", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void save(int after, String outcome, long minutes) {
        JSONArray a = log();
        try { a.put(new JSONObject().put("at", System.currentTimeMillis()).put("type", urgeType)
                .put("before", before).put("after", after).put("outcome", outcome).put("min", minutes)); }
        catch (Exception ignored) { }
        JSONArray keep = new JSONArray();
        for (int i = Math.max(0, a.length() - 500); i < a.length(); i++) keep.put(a.opt(i));
        prefs.edit().putString("indep_log", keep.toString()).apply();
    }

    private String[] anchors() {
        try {
            JSONArray a = new JSONArray(prefs.getString("indep_anchors", ""));
            String[] out = new String[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.optString(i);
            return out.length == 0 ? DEFAULT_ANCHORS : out;
        } catch (Exception e) { return DEFAULT_ANCHORS; }
    }

    /* ======================= ui kit ======================= */

    private interface IntPick { void on(int v); }

    /** 1–10 as ten big tappable circles: faster and clearer than a slider. */
    private View scale(final IntPick pick) {
        LinearLayout row = new LinearLayout(this);
        final TextView[] dots = new TextView[10];
        for (int i = 0; i < 10; i++) {
            final int v = i + 1;
            TextView d = text(15, INK2, Typeface.DEFAULT_BOLD);
            d.setText(String.valueOf(v));
            d.setGravity(Gravity.CENTER);
            d.setBackground(round(CARD, LINE));
            d.setOnClickListener(x -> {
                for (int k = 0; k < 10; k++) {
                    boolean on = k < v;
                    dots[k].setBackground(round(on ? blend(SEA, SAND, k / 9f) : CARD, on ? 0 : LINE));
                    dots[k].setTextColor(on ? BG : INK2);
                }
                pick.on(v);
            });
            dots[i] = d;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (i < 9) p.rightMargin = dp(4);
            row.addView(d, p);
        }
        return row;
    }

    private LinearLayout page() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(24), dp(26), dp(24), dp(34));
        return c;
    }

    private void show(View content) {
        stage.removeAllViews();
        if (content instanceof LinearLayout) {
            ScrollView sv = new ScrollView(this);
            sv.addView(content);
            stage.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            stage.addView(content, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private void keepScreenOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void fadeTo(final TextView t, final String s) {
        t.animate().alpha(0f).setDuration(350).withEndAction(() -> { t.setText(s); t.animate().alpha(1f).setDuration(500).start(); }).start();
    }

    private TextView eyebrow(String s) {
        TextView t = text(11, SEA, Typeface.create("monospace", Typeface.BOLD));
        t.setText(s); t.setLetterSpacing(0.2f);
        return t;
    }

    private TextView title(String s) {
        TextView t = text(32, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setText(s); t.setLetterSpacing(-0.03f); t.setLineSpacing(0, 1.02f);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private TextView body(String s, int color) {
        TextView t = text(15, color, Typeface.DEFAULT);
        t.setText(s); t.setLineSpacing(0, 1.25f);
        return t;
    }

    private TextView bigButton(String s) {
        TextView t = text(18, BG, Typeface.DEFAULT_BOLD);
        t.setText(s); t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{SEA, 0xFF8FD6C8});
        g.setCornerRadius(dp(18));
        t.setBackground(g);
        return t;
    }

    private TextView option(String s) {
        TextView t = text(16, INK, Typeface.DEFAULT);
        t.setText(s);
        t.setPadding(dp(16), dp(15), dp(16), dp(15));
        t.setBackground(round(CARD, LINE));
        return t;
    }

    private void select(TextView t, boolean on) {
        t.setBackground(round(on ? 0xFF1D3A4A : CARD, on ? SEA : LINE));
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private TextView chip(String s) {
        TextView t = option(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        l.setBackground(round(CARD, LINE));
        return l;
    }

    private View space(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return v; }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(14));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private static int blend(int a, int b, float f) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | Math.round(ar + (br - ar) * f) << 16 | Math.round(ag + (bg - ag) * f) << 8 | Math.round(ab + (bb - ab) * f);
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setTypeface(tf);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
