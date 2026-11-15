package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import static ch.architectdna.home.ArcUi.t;

/**
 * Anchor, native: a companion that knows your situation. Daily log → an honest reflection and
 * one question; emergency mode → one physical action to break a loop. Same service and credits
 * as anchor.architect-dna.ch, in the ARC Player look. Profile and logs stay on the phone.
 */
public class AnchorActivity extends Activity {

    static final String ORIGIN = "https://anchor.architect-dna.ch";

    private ArcUi ui;
    private SharedPreferences prefs;
    private ScrollView scroll;
    private String screen = "";

    private int ob;
    private final String[] answers = new String[4];
    private String entry = "", reflection = "", followUp = "";

    private static String[][] questions() {
        return new String[][]{
            {"name", t("What's your name?", "Wie heisst du?"), t("First name works", "Vorname reicht")},
            {"situation", t("What does your life look like right now?", "Wie sieht dein Leben gerade aus?"), t("Work, school, daily structure. A typical day.", "Arbeit, Schule, Tagesstruktur. Ein typischer Tag.")},
            {"loops", t("What do you fall into when things get hard?", "Worin rutschst du, wenn es schwer wird?"), t("Overthinking, isolating, scrolling, numbing …", "Grübeln, Rückzug, Scrollen, Betäuben …")},
            {"building", t("What are you building toward?", "Worauf baust du hin?"), t("A business, a skill, a relationship, a version of yourself", "Ein Business, ein Skill, eine Beziehung, eine Version von dir")},
        };
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        prefs = getSharedPreferences("anchor", MODE_PRIVATE);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFitsSystemWindows(true);
        setContentView(scroll);
        if (profile() == null) welcome(); else home();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCredits();
    }

    @Override
    public void onBackPressed() {
        if ("onboarding".equals(screen) && ob > 0) { ob--; onboarding(); }
        else if (!"home".equals(screen) && !"welcome".equals(screen) && profile() != null) home();
        else super.onBackPressed();
    }

    private LinearLayout page(String right, View.OnClickListener r) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(44));
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(ui.label("Anchor", true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView rt = ui.mono(right, ui.ink2, 13);
        rt.setPadding(ui.dp(12), ui.dp(6), 0, ui.dp(6));
        rt.setOnClickListener(r);
        h.addView(rt);
        col.addView(h);
        scroll.removeAllViews();
        scroll.addView(col);
        scroll.scrollTo(0, 0);
        return col;
    }

    /* ================= welcome & onboarding ================= */

    private void welcome() {
        screen = "welcome";
        LinearLayout col = page("✕", v -> finish());
        col.addView(new Rings(), ui.lp(-1, ui.dp(150), ui.dp(20)));
        col.addView(ui.title(t("A companion that\nknows your situation.", "Ein Begleiter, der\ndeine Lage kennt."), 38), ui.lp(-1, -2, ui.dp(16)));
        col.addView(ui.body(t("Log your day, get an honest reflection that connects it to what you're building. Stuck in a loop? One physical action, in ten seconds.",
            "Schreib deinen Tag auf, bekomm eine ehrliche Antwort, die ihn mit deinem Ziel verbindet. In einer Schleife? Eine körperliche Handlung, in zehn Sekunden."), ui.ink2, 16), ui.lp(-1, -2, ui.dp(14)));
        String[][] how = {
            {"1", t("Four questions", "Vier Fragen"), t("So it knows you. Two minutes.", "Damit er dich kennt. Zwei Minuten.")},
            {"2", t("Daily log", "Tageslog"), t("A few lines, then one sharp question.", "Ein paar Zeilen, dann eine gute Frage.")},
            {"3", t("Anchor", "Anker"), t("For the bad moments. Always free.", "Für die schweren Momente. Immer gratis.")}};
        for (String[] s : how) col.addView(step(s[0], s[1], s[2]));
        col.addView(ui.mono(t("5 FREE SESSIONS A DAY · THEN 10 FOR CHF 4.90 · NO SUBSCRIPTION", "5 GRATIS PRO TAG · DANN 10 FÜR CHF 4.90 · KEIN ABO"), ui.ink3, 10.5f), ui.lp(-1, -2, ui.dp(22)));
        col.addView(ui.button(t("Start", "Los geht's"), 1, v -> { ob = 0; onboarding(); }), ui.lp(-1, ui.dp(56), ui.dp(18)));
        TextView priv = ui.body(t("Your profile and logs stay on this phone. Anchor is not therapy. In a crisis call 143 (Die Dargebotene Hand) or 144.",
            "Profil und Logs bleiben auf diesem Handy. Anchor ist keine Therapie. In einer Krise: 143 (Die Dargebotene Hand) oder 144."), ui.ink3, 12.5f);
        priv.setOnClickListener(v -> call("143"));
        col.addView(priv, ui.lp(-1, -2, ui.dp(22)));
    }

    private View step(String n, String head, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, ui.dp(12), 0, ui.dp(4));
        r.addView(ui.mono(n, ui.rust, 14), new LinearLayout.LayoutParams(ui.dp(30), -2));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView h = ui.body(head, ui.ink, 16);
        h.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.addView(h);
        c.addView(ui.body(sub, ui.ink2, 14));
        r.addView(c);
        return r;
    }

    private void onboarding() {
        screen = "onboarding";
        String[][] q = questions();
        LinearLayout col = page(t("← Back", "← Zurück"), v -> { if (ob == 0) welcome(); else onBackPressed(); });
        col.addView(ui.segments(q.length, ob), ui.lp(-1, -2, ui.dp(22)));
        for (int i = 0; i < ob; i++) {
            TextView prev = ui.body(answers[i], ui.ink3, 14);
            prev.setMaxLines(1);
            prev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(prev, ui.lp(-1, -2, ui.dp(i == 0 ? 22 : 4)));
        }
        col.addView(ui.title(q[ob][1], 30), ui.lp(-1, -2, ui.dp(24)));
        EditText e = ui.input(q[ob][2], ob > 0, ob == 0 ? 60 : 700);
        if (ob > 0) e.setMinLines(4);
        if (answers[ob] != null) e.setText(answers[ob]);
        col.addView(e, ui.lp(-1, -2, ui.dp(18)));
        e.requestFocus();
        col.addView(ui.button(ob < q.length - 1 ? t("Continue →", "Weiter →") : t("Done", "Fertig"), 0, v -> {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) { e.setError(t("A few words are enough.", "Ein paar Worte reichen.")); return; }
            answers[ob] = s;
            if (ob < q.length - 1) { ob++; onboarding(); return; }
            try {
                JSONObject p = new JSONObject();
                for (int i = 0; i < q.length; i++) p.put(q[i][0], answers[i]);
                prefs.edit().putString("profile", p.toString()).apply();
            } catch (Exception ignored) { }
            home();
        }), ui.lp(-1, ui.dp(56), ui.dp(16)));
    }

    private JSONObject profile() {
        try {
            String s = prefs.getString("profile", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) { return null; }
    }

    /* ================= home ================= */

    private void home() {
        screen = "home";
        LinearLayout col = page("✕", v -> finish());
        JSONObject p = profile();
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String tod = h < 12 ? t("Morning", "Guten Morgen") : h < 17 ? t("Afternoon", "Hallo") : t("Evening", "Guten Abend");
        TextView hi = ui.body(tod + ", " + (p == null ? "" : p.optString("name")) + ".", ui.ink2, 16);
        hi.setOnLongClickListener(v -> { editProfile(); return true; });
        col.addView(hi, ui.lp(-1, -2, ui.dp(34)));
        col.addView(ui.title(t("What do you need?", "Was brauchst du?"), 40), ui.lp(-1, -2, ui.dp(4)));

        JSONArray logs = logs();
        col.addView(ui.row("📝", t("Daily log", "Tageslog"), t("What happened today", "Was heute passiert ist"), v -> log()), ui.lp(-1, -2, ui.dp(26)));
        col.addView(ui.row("⚓", t("Anchor", "Anker"), t("Stuck in a loop. Out in 10 seconds.", "In einer Schleife. In 10 Sekunden raus."), v -> anchor()), ui.lp(-1, -2, ui.dp(8)));
        boolean weekOk = logs.length() >= 3;
        LinearLayout week = ui.row("🌙", t("This week", "Diese Woche"),
            weekOk ? t("Your recent logs, side by side", "Deine letzten Logs nebeneinander")
                : t("Log " + (3 - logs.length()) + " more to unlock", "Noch " + (3 - logs.length()) + " Logs bis hier"), weekOk ? v -> week() : null);
        week.setAlpha(weekOk ? 1f : 0.4f);
        col.addView(week, ui.lp(-1, -2, ui.dp(8)));

        int left = prefs.getString("token", "").isEmpty() ? 0 : prefs.getInt("left", 0);
        col.addView(ui.mono(left > 0 ? t("SESSIONS LEFT · ", "SESSIONS ÜBRIG · ") + left : t("5 FREE SESSIONS A DAY", "5 GRATIS SESSIONS PRO TAG"), ui.ink3, 10.5f), ui.lp(-1, -2, ui.dp(22)));
        if (logs.length() > 0) {
            col.addView(ui.label(t("Streak", "Verlauf"), false), ui.lp(-1, -2, ui.dp(26)));
            col.addView(new Dots(logs), ui.lp(-1, ui.dp(30), ui.dp(10)));
        }
        LinearLayout sos = new LinearLayout(this);
        sos.addView(ui.button("143", 2, v -> call("143")), new LinearLayout.LayoutParams(0, ui.dp(48), 1f));
        View g = new View(this);
        sos.addView(g, new LinearLayout.LayoutParams(ui.dp(8), 1));
        sos.addView(ui.button("144", 2, v -> call("144")), new LinearLayout.LayoutParams(0, ui.dp(48), 1f));
        col.addView(ui.label(t("If it's too much", "Wenn es zu viel ist"), false), ui.lp(-1, -2, ui.dp(30)));
        col.addView(sos, ui.lp(-1, -2, ui.dp(10)));
    }

    /* ================= daily log ================= */

    private void log() {
        screen = "log";
        entry = ""; reflection = ""; followUp = "";
        LinearLayout col = page(t("← Back", "← Zurück"), v -> home());
        col.addView(ui.title(t("What happened today?", "Was ist heute passiert?"), 32), ui.lp(-1, -2, ui.dp(30)));
        col.addView(ui.body(t("No structure needed. The good, the bad, what you avoided.", "Ohne Struktur. Das Gute, das Schlechte, was du vermieden hast."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(8)));
        EditText e = ui.input(t("Today I …", "Heute habe ich …"), true, 3000);
        e.setMinLines(8);
        col.addView(e, ui.lp(-1, -2, ui.dp(18)));
        e.requestFocus();
        col.addView(ui.button(t("Reflect", "Spiegeln"), 1, v -> {
            String s = e.getText().toString().trim();
            if (s.length() < 5) { e.setError(t("A bit more.", "Etwas mehr.")); return; }
            entry = s;
            reflect(null);
        }), ui.lp(-1, ui.dp(56), ui.dp(16)));
    }

    private void reflect(String answer) {
        LinearLayout col = page("", null);
        col.addView(ui.title(answer == null ? t("Reading …", "Liest …") : t("One more thought …", "Noch ein Gedanke …"), 30), ui.lp(-1, -2, ui.dp(60)));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.rust));
        col.addView(pb, ui.lp(-1, ui.dp(6), ui.dp(24)));

        JSONObject b = new JSONObject();
        try {
            b.put("profile", profile());
            b.put("entry", entry);
            if (answer != null) b.put("followUpResponse", answer);
            String token = prefs.getString("token", "");
            if (!token.isEmpty()) b.put("token", token);
        } catch (Exception ignored) { }
        ui.post(ORIGIN, "/api/reflect", b, r -> {
            if (r.code == 200) {
                if (r.json.has("remaining")) prefs.edit().putInt("left", r.json.optInt("remaining")).apply();
                if (answer == null) {
                    reflection = r.json.optString("reflection");
                    followUp = r.json.optString("followUp");
                    saveLog();
                    reflected();
                } else {
                    closing(answer, r.json.optString("reflection"));
                }
            } else if (r.code == 402) {
                paywall();
            } else {
                Toast.makeText(this, r.code == 429 ? t("Too many in a short time. Try again in an hour.", "Zu viele in kurzer Zeit. In einer Stunde wieder.")
                    : r.code == 0 ? t("No connection.", "Keine Verbindung.") : t("That didn't work. Try again.", "Hat nicht geklappt. Nochmal."), Toast.LENGTH_LONG).show();
                if (answer == null) log(); else reflected();
            }
        });
    }

    private void reflected() {
        screen = "reflected";
        LinearLayout col = page(t("Done", "Fertig"), v -> home());
        col.addView(ui.label(t("Reflection", "Spiegel"), true), ui.lp(-1, -2, ui.dp(26)));
        col.addView(prose(reflection), ui.lp(-1, -2, ui.dp(12)));
        if (!followUp.isEmpty()) {
            TextView q = ui.title(followUp, 22);
            q.setTextColor(ui.rust);
            col.addView(q, ui.lp(-1, -2, ui.dp(24)));
            EditText e = ui.input(t("Your answer", "Deine Antwort"), true, 1500);
            e.setMinLines(3);
            col.addView(e, ui.lp(-1, -2, ui.dp(14)));
            col.addView(ui.button(t("Answer", "Antworten"), 0, v -> {
                String s = e.getText().toString().trim();
                if (s.isEmpty()) return;
                reflect(s);
            }), ui.lp(-1, ui.dp(52), ui.dp(12)));
            col.addView(ui.button(t("Leave it there", "So lassen"), 2, v -> home()), ui.lp(-1, ui.dp(52), ui.dp(8)));
        }
    }

    private void closing(String answer, String text) {
        screen = "closing";
        LinearLayout col = page(t("Done", "Fertig"), v -> home());
        col.addView(ui.label(t("You said", "Du hast gesagt"), false), ui.lp(-1, -2, ui.dp(26)));
        col.addView(ui.body(answer, ui.ink2, 15), ui.lp(-1, -2, ui.dp(8)));
        col.addView(ui.label("Anchor", true), ui.lp(-1, -2, ui.dp(24)));
        col.addView(prose(text), ui.lp(-1, -2, ui.dp(10)));
        col.addView(ui.button(t("Back home", "Zurück"), 0, v -> home()), ui.lp(-1, ui.dp(52), ui.dp(28)));
    }

    private TextView prose(String s) {
        TextView t = ui.body(s, ui.ink, 18);
        t.setTypeface(Typeface.SERIF);
        t.setLineSpacing(0, 1.35f);
        t.setTextIsSelectable(true);
        return t;
    }

    private void paywall() {
        screen = "paywall";
        LinearLayout col = page(t("← Back", "← Zurück"), v -> home());
        col.addView(ui.title(t("Today's free sessions are used.", "Die Gratis-Sessions von heute sind weg."), 32), ui.lp(-1, -2, ui.dp(40)));
        col.addView(ui.body(t("10 more sessions for CHF 4.90. One time, no subscription. Anchor mode stays free.", "10 weitere Sessions für CHF 4.90. Einmalig, kein Abo. Der Anker bleibt gratis."), ui.ink2, 16), ui.lp(-1, -2, ui.dp(12)));
        col.addView(ui.button(t("Continue · CHF 4.90", "Weiter · CHF 4.90"), 1, v -> buy()), ui.lp(-1, ui.dp(56), ui.dp(24)));
        col.addView(ui.button(t("Use Anchor instead", "Lieber den Anker"), 2, v -> anchor()), ui.lp(-1, ui.dp(52), ui.dp(10)));
    }

    /* ================= anchor (emergency) ================= */

    private void anchor() {
        screen = "anchor";
        LinearLayout col = page(t("← Back", "← Zurück"), v -> home());
        col.addView(ui.title(t("What loop are you in?", "In welcher Schleife steckst du?"), 32), ui.lp(-1, -2, ui.dp(30)));
        EditText e = ui.input(t("e.g. replaying the conversation, can't stop scrolling", "z.B. das Gespräch geht mir nicht aus dem Kopf"), true, 600);
        e.setMinLines(3);
        col.addView(e, ui.lp(-1, -2, ui.dp(18)));
        e.requestFocus();
        col.addView(ui.button("⚓  " + t("Anchor me", "Anker werfen"), 1, v -> {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) s = t("I'm stuck.", "Ich hänge fest.");
            fetchAnchor(s);
        }), ui.lp(-1, ui.dp(60), ui.dp(16)));
    }

    private void fetchAnchor(String loop) {
        LinearLayout col = page("", null);
        col.addView(new Rings(), ui.lp(-1, ui.dp(200), ui.dp(60)));
        JSONObject b = new JSONObject();
        try {
            b.put("loop", loop);
            JSONObject p = profile();
            b.put("profile", new JSONObject().put("name", p == null ? "" : p.optString("name")));
        } catch (Exception ignored) { }
        ui.post(ORIGIN, "/api/anchor", b, r -> {
            screen = "anchored";
            LinearLayout c = page(t("Done", "Fertig"), v -> home());
            String a = r.code == 200 ? r.json.optString("anchor") : t("Feet flat on the floor. Breathe out slowly. Name five things you can see.",
                "Füsse flach auf den Boden. Langsam ausatmen. Nenne fünf Dinge, die du siehst.");
            c.addView(new Rings(), ui.lp(-1, ui.dp(130), ui.dp(30)));
            TextView big = ui.title(a.replace("\"", ""), 34);
            big.setGravity(Gravity.CENTER);
            c.addView(big, ui.lp(-1, -2, ui.dp(24)));
            c.addView(ui.button(t("Done it", "Gemacht"), 0, v -> home()), ui.lp(-1, ui.dp(56), ui.dp(40)));
            c.addView(ui.button(t("Another one", "Noch einer"), 2, v -> fetchAnchor(loop)), ui.lp(-1, ui.dp(52), ui.dp(8)));
        });
    }

    /* ================= week ================= */

    private void week() {
        screen = "week";
        LinearLayout col = page(t("← Back", "← Zurück"), v -> home());
        col.addView(ui.title(t("This week", "Diese Woche"), 36), ui.lp(-1, -2, ui.dp(30)));
        JSONArray logs = logs();
        DateFormat df = new java.text.SimpleDateFormat("EEE d. MMM", ArcUi.de() ? Locale.GERMAN : Locale.ENGLISH);
        for (int i = 0; i < Math.min(7, logs.length()); i++) {
            JSONObject o = logs.optJSONObject(i);
            if (o == null) continue;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(ui.dp(14), 0, 0, 0);
            box.addView(ui.mono(df.format(new Date(o.optLong("at"))).toUpperCase(Locale.ROOT), ui.ink3, 10.5f));
            String en = o.optString("entry");
            box.addView(ui.body(en.length() > 140 ? en.substring(0, 140) + "…" : en, ui.ink2, 14), ui.lp(-1, -2, ui.dp(6)));
            String rf = o.optString("reflection");
            if (!rf.isEmpty()) {
                TextView r = ui.body(rf.length() > 180 ? rf.substring(0, 180) + "…" : rf, ui.ink, 15);
                r.setTypeface(Typeface.SERIF);
                box.addView(r, ui.lp(-1, -2, ui.dp(6)));
            }
            LinearLayout rowL = new LinearLayout(this);
            View rule = new View(this);
            rule.setBackgroundColor(ui.line);
            rowL.addView(rule, new LinearLayout.LayoutParams(ui.dp(1.5f), -1));
            rowL.addView(box, new LinearLayout.LayoutParams(0, -2, 1f));
            col.addView(rowL, ui.lp(-1, -2, ui.dp(22)));
        }
    }

    /* ================= storage, credits ================= */

    private JSONArray logs() {
        try { return new JSONArray(prefs.getString("logs", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void saveLog() {
        JSONArray old = logs(), out = new JSONArray();
        try {
            out.put(new JSONObject().put("at", System.currentTimeMillis()).put("entry", entry).put("reflection", reflection));
            for (int i = 0; i < Math.min(old.length(), 59); i++) out.put(old.get(i));
        } catch (Exception ignored) { }
        prefs.edit().putString("logs", out.toString()).apply();
    }

    private void editProfile() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("Your profile", "Dein Profil"))
            .setItems(new String[]{t("Answer the four questions again", "Die vier Fragen neu beantworten"), t("Delete everything on this phone", "Alles auf diesem Handy löschen")}, (d, i) -> {
                if (i == 0) {
                    JSONObject p = profile();
                    String[][] q = questions();
                    for (int k = 0; k < q.length; k++) answers[k] = p == null ? "" : p.optString(q[k][0]);
                    ob = 0;
                    onboarding();
                } else {
                    String token = prefs.getString("token", ""), left = String.valueOf(prefs.getInt("left", 0));
                    prefs.edit().clear().putString("token", token).putInt("left", Integer.parseInt(left)).apply();
                    welcome();
                }
            })
            .show();
    }

    private void call(String n) {
        try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + n))); } catch (RuntimeException ignored) { }
    }

    private void buy() {
        ui.post(ORIGIN, "/api/checkout", new JSONObject(), r -> {
            String url = r.json.optString("url");
            if (url.isEmpty()) { Toast.makeText(this, t("Payment isn't available right now.", "Zahlung gerade nicht möglich."), Toast.LENGTH_LONG).show(); return; }
            String sid = ArcUi.sessionIdFrom(url);
            if (sid != null) prefs.edit().putString("pending", sid).putLong("pendingAt", System.currentTimeMillis()).apply();
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException ignored) { }
        });
    }

    private void refreshCredits() {
        String pending = prefs.getString("pending", "");
        if (!pending.isEmpty() && System.currentTimeMillis() - prefs.getLong("pendingAt", 0) > 86_400_000L) {
            prefs.edit().remove("pending").apply();
            pending = "";
        }
        String sid = !pending.isEmpty() ? pending : prefs.getString("token", "");
        if (sid.isEmpty()) return;
        boolean wasPending = !pending.isEmpty();
        JSONObject b = new JSONObject();
        try { b.put("session_id", sid); } catch (Exception ignored) { }
        ui.post(ORIGIN, "/api/webhook", b, r -> {
            if (r.code == 200 && r.json.optInt("granted") > 0) {
                prefs.edit().putString("token", sid).putInt("left", r.json.optInt("granted")).remove("pending").apply();
                if (wasPending) Toast.makeText(this, t("Thanks! ", "Danke! ") + r.json.optInt("granted") + t(" sessions ready.", " Sessions bereit."), Toast.LENGTH_LONG).show();
                if ("home".equals(screen)) home();
            } else if (r.code == 402 && !wasPending) {
                prefs.edit().putInt("left", 0).apply();
                if ("home".equals(screen)) home();
            }
        });
    }

    /* ================= small visuals ================= */

    /** Calm concentric rings breathing slowly: the anchor's mark. */
    private final class Rings extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long t0 = SystemClock.uptimeMillis();

        Rings() { super(AnchorActivity.this); p.setStyle(Paint.Style.STROKE); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy) * 0.95f;
            float t = ((SystemClock.uptimeMillis() - t0) % 8000) / 8000f;
            float breath = (float) (0.5 - 0.5 * Math.cos(t * 2 * Math.PI));
            for (int i = 0; i < 4; i++) {
                float r = max * (0.35f + 0.18f * i) * (0.9f + 0.1f * breath);
                p.setStrokeWidth(ui.dp(i == 0 ? 2 : 1));
                p.setColor(i == 0 ? ui.rust : ui.line);
                c.drawCircle(cx, cy, r, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(ui.rust);
            c.drawCircle(cx, cy, ui.dp(4) + ui.dp(3) * breath, p);
            p.setStyle(Paint.Style.STROKE);
            postInvalidateOnAnimation();
        }
    }

    /** The last 21 days as dots: filled where you logged. */
    private final class Dots extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean[] on = new boolean[21];

        Dots(JSONArray logs) {
            super(AnchorActivity.this);
            long day = 86_400_000L;
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            long today = c.getTimeInMillis();
            for (int i = 0; i < logs.length(); i++) {
                long at = logs.optJSONObject(i) == null ? 0 : logs.optJSONObject(i).optLong("at");
                long ago = at >= today ? 0 : (today - at) / day + 1;
                if (ago < 21) on[20 - (int) ago] = true;
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cy = getHeight() / 2f, step = w / 21f, r = Math.min(step * 0.32f, ui.dp(6));
            for (int i = 0; i < 21; i++) {
                p.setColor(on[i] ? ui.rust : ui.line);
                c.drawCircle(step * (i + 0.5f), cy, i == 20 ? r * 1.25f : r, p);
            }
        }
    }
}
