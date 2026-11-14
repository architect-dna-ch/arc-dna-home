package ch.architectdna.home;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takt — the day planned around your body, inside the launcher (no browser, no account, offline).
 * Fixed anchors (wake, sleep, school/appointments) stay put; the tasks you type flow into the free
 * time around them, ordered by your energy right now. Fall behind → "Neu ab jetzt" re-plans what
 * is left instead of leaving a list of failures.
 *
 * The planning is a small rule set that runs on the phone: heavy/thinking tasks go into the
 * freshest window (or first, when the room is warm), light ones when energy is low, with a refuel
 * break after a long fixed block, short breaks between heavy blocks and a wind-down before sleep.
 */
public class TaktActivity extends Activity {

    private static final String[] ENERGY = {"🪫 leer", "😮‍💨 müde", "🙂 ok", "⚡ auf Zug", "🔥 brennt"};
    private static final Pattern DUR = Pattern.compile("(\\d{1,3})\\s*(min|m|h|std)\\b", Pattern.CASE_INSENSITIVE);
    private static final String[] HEAVY = {"lern", "prüf", "pruef", "mathe", "rechn", "schreib", "bau", "code", "programm",
        "aufsatz", "hausaufg", "projekt", "präsent", "praesent", "franz", "englisch", "deutsch", "bewerb", "steuer", "vortrag", "bms", "w&g", "wug"};
    private static final String[] BODY = {"sport", "box", "gym", "lauf", "jogg", "spazier", "train", "velo", "schwimm", "dehn", "fitness", "yoga"};
    private static final String[] FUN = {"spiel", "game", "film", "serie", "freund", "treff", "kino", "essen gehen", "ausgang"};

    private int bg, card, line, ink, ink2, ink3, rust, blue;
    private SharedPreferences prefs;
    private JSONObject P;                        // {wake, sleep, fixes:[{s,e,label}], dump, energy, warm, plan:[…], note}
    private LinearLayout fixBox, energyBox, timeline, resultCard;
    private TextView wakeBtn, sleepBtn, warmChip, noteBox;
    private EditText dump;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF0E0E0D : 0xFFFAFAF8;
        card = night ? 0xFF1A1A18 : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFF4F2EC : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        blue = night ? 0xFF8EA6D8 : 0xFF3F5C9A;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        calmBars(bg);

        prefs = getSharedPreferences("takt", MODE_PRIVATE);
        try { P = new JSONObject(prefs.getString("state", "{}")); } catch (JSONException e) { P = new JSONObject(); }
        try {
            if (!P.has("wake")) P.put("wake", "07:00");
            if (!P.has("sleep")) P.put("sleep", "23:00");
            if (!P.has("energy")) P.put("energy", 2);
            if (!P.has("fixes")) P.put("fixes", new JSONArray().put(new JSONObject().put("s", "08:00").put("e", "12:00").put("label", "Schule")));
        } catch (JSONException ignored) { }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(40));
        if (Build.VERSION.SDK_INT >= 30) {
            sv.setOnApplyWindowInsetsListener((v, in) -> {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                col.setPadding(dp(20) + i.left, dp(20) + i.top, dp(20) + i.right, dp(40) + i.bottom);
                return in;
            });
        }
        sv.addView(col);

        col.addView(mono("ARC·DNA · TAG", rust, 11));
        TextView h1 = text(36, ink, true);
        h1.setText("Takt");
        col.addView(h1);
        TextView sub = text(14.5f, ink2, false);
        sub.setText("Kein starres Raster. Feste Anker — dann fliesst der Rest deines Tages drumherum, nach deiner Energie. Und wenn du hinterherhängst, plant Takt neu.");
        col.addView(sub);

        // ---- anchors ----
        LinearLayout anchors = cardBox();
        anchors.addView(mono("FESTE ANKER", ink3, 11));
        LinearLayout ws = new LinearLayout(this);
        ws.setGravity(Gravity.CENTER_VERTICAL);
        wakeBtn = timeButton("wake");
        sleepBtn = timeButton("sleep");
        ws.addView(mono("☀️ AUF", ink3, 11));
        ws.addView(wakeBtn, lp(-2, -2, dp(8), 0, dp(18), 0));
        ws.addView(mono("🌙 SCHLAF", ink3, 11));
        ws.addView(sleepBtn, lp(-2, -2, dp(8), 0, 0, 0));
        anchors.addView(ws, lp(-1, -2, 0, dp(10), 0, 0));
        anchors.addView(mono("FIXE BLÖCKE (SCHULE, TERMINE)", ink3, 11), lp(-1, -2, 0, dp(16), 0, 0));
        fixBox = new LinearLayout(this);
        fixBox.setOrientation(LinearLayout.VERTICAL);
        anchors.addView(fixBox);
        col.addView(anchors, lp(-1, -2, 0, dp(16), 0, 0));
        renderFixes();

        // ---- tasks + energy ----
        LinearLayout day = cardBox();
        day.addView(mono("WAS WILLST / MUSST DU HEUTE?", ink3, 11));
        dump = new EditText(this);
        dump.setText(P.optString("dump", ""));
        dump.setHint("Eine Sache pro Zeile.\nz.B. Mathe lernen 45min");
        dump.setHintTextColor(ink3);
        dump.setTextColor(ink);
        dump.setTextSize(16);
        dump.setMinLines(3);
        dump.setGravity(Gravity.TOP | Gravity.START);
        dump.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        dump.setPadding(dp(14), dp(12), dp(14), dp(12));
        dump.setBackground(box(bg, line, 14));
        dump.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { put("dump", e.toString()); save(); }
        });
        day.addView(dump, lp(-1, -2, 0, dp(10), 0, 0));
        TextView tip = mono("„!“ vor einer Zeile = schwer / Denkarbeit · „45min“ oder „2h“ = Dauer", ink3, 10);
        day.addView(tip, lp(-1, -2, 0, dp(6), 0, 0));

        day.addView(mono("ENERGIE JETZT", ink3, 11), lp(-1, -2, 0, dp(16), 0, dp(8)));
        energyBox = new LinearLayout(this);
        energyBox.setOrientation(LinearLayout.VERTICAL);
        day.addView(energyBox);
        renderEnergy();
        warmChip = chip("🌡️ Raum warm");
        warmChip.setOnClickListener(v -> { put("warm", !P.optBoolean("warm", false)); save(); styleWarm(); });
        day.addView(warmChip, lp(-2, -2, 0, dp(10), 0, 0));
        styleWarm();
        day.addView(mono("(Wärme macht müde – Takt legt Schweres dann früher)", ink3, 10), lp(-1, -2, 0, dp(6), 0, 0));

        TextView plan = text(17, 0xFFFFFFFF, true);
        plan.setText("Plan mir den Tag ↓");
        plan.setGravity(Gravity.CENTER);
        plan.setPadding(dp(20), dp(15), dp(20), dp(15));
        plan.setBackground(box(rust, rust, 40));
        plan.setOnClickListener(v -> planDay(false));
        day.addView(plan, lp(-1, -2, 0, dp(16), 0, 0));

        noteBox = text(15, ink, false);
        noteBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        noteBox.setBackground(box(bg, rust, 14));
        noteBox.setVisibility(View.GONE);
        day.addView(noteBox, lp(-1, -2, 0, dp(12), 0, 0));
        col.addView(day, lp(-1, -2, 0, dp(14), 0, 0));

        // ---- the plan ----
        resultCard = cardBox();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(mono("DEIN TAKT HEUTE", ink3, 11), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView reflow = mono("↻ Neu ab jetzt", ink2, 12);
        reflow.setPadding(dp(10), dp(8), 0, dp(8));
        reflow.setOnClickListener(v -> planDay(true));
        head.addView(reflow);
        resultCard.addView(head);
        timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.VERTICAL);
        resultCard.addView(timeline);
        resultCard.setVisibility(View.GONE);
        col.addView(resultCard, lp(-1, -2, 0, dp(14), 0, 0));

        setContentView(sv);
        renderPlan();
    }

    // ================= state =================

    private void put(String k, Object v) { try { P.put(k, v); } catch (JSONException ignored) { } }
    private void save() { prefs.edit().putString("state", P.toString()).apply(); }

    private static int toMin(String t) {
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (RuntimeException e) { return 0; }
    }
    private static String fmt(int m) {
        m = ((m % 1440) + 1440) % 1440;
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }
    private static int nowMin() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    // ================= the planner =================

    private static final class Task {
        String title; int dur; String kind;   // fokus | leicht | körper
        boolean fun;
    }

    private Task parse(String line) {
        Task t = new Task();
        String s = line.trim();
        boolean bang = s.startsWith("!");
        if (bang) s = s.substring(1).trim();
        Matcher m = DUR.matcher(s);
        int dur = 0;
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String u = m.group(2).toLowerCase(Locale.ROOT);
            dur = (u.startsWith("h") || u.startsWith("s")) ? n * 60 : n;
            s = (s.substring(0, m.start()) + s.substring(m.end())).trim().replaceAll("[,·\\-–]+$", "").trim();
        }
        String low = s.toLowerCase(Locale.ROOT);
        t.fun = has(low, FUN);
        t.kind = bang || has(low, HEAVY) ? "fokus" : has(low, BODY) ? "körper" : "leicht";
        t.dur = dur > 0 ? Math.max(10, Math.min(240, dur))
            : t.kind.equals("fokus") ? 50 : t.kind.equals("körper") ? 45 : t.fun ? 90 : 30;
        t.title = s.isEmpty() ? line.trim() : s;
        return t;
    }
    private static boolean has(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private void planDay(boolean reflow) {
        int wake = toMin(P.optString("wake", "07:00")), sleep = toMin(P.optString("sleep", "23:00"));
        if (sleep <= wake) sleep += 1440;                       // going to bed after midnight
        int now = nowMin();
        if (now < wake - 180) now += 1440;                      // it's past midnight but still "today"
        int energy = P.optInt("energy", 2);
        boolean warm = P.optBoolean("warm", false);
        JSONArray old = P.optJSONArray("plan");

        // what to plan: on a reflow only what is still open, otherwise the text field
        List<Task> tasks = new ArrayList<>();
        List<JSONObject> keep = new ArrayList<>();
        if (reflow && old != null) {
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null || o.optBoolean("anchor")) continue;
                if (o.optBoolean("done")) { keep.add(o); continue; }
                if (o.optBoolean("auto")) continue;
                Task t = parse(o.optString("title"));
                t.dur = Math.max(10, o.optInt("e") - o.optInt("s"));
                t.kind = o.optString("kind", t.kind);
                tasks.add(t);
            }
        }
        if (tasks.isEmpty()) {
            for (String ln : dump.getText().toString().split("\n")) if (!ln.trim().isEmpty()) tasks.add(parse(ln));
        }
        if (tasks.isEmpty()) { dump.requestFocus(); return; }

        // order by energy: fresh → heavy first; low → easy first and heavy cut to a short start
        List<Task> heavy = new ArrayList<>(), light = new ArrayList<>(), body = new ArrayList<>(), fun = new ArrayList<>();
        for (Task t : tasks) (t.kind.equals("fokus") ? heavy : t.kind.equals("körper") ? body : t.fun ? fun : light).add(t);
        boolean low = energy <= 1;
        if (low) for (Task t : heavy) t.dur = Math.min(t.dur, 25);
        List<Task> order = new ArrayList<>();
        if (warm || !low) { order.addAll(heavy); order.addAll(body); order.addAll(light); }
        else { order.addAll(light); order.addAll(body); order.addAll(heavy); }
        order.addAll(fun);                                      // the relaxed things close the day

        // free windows between the fixed blocks, from now until the wind-down
        int from = roundUp5(Math.max(now, wake)), windDown = sleep - 45;
        List<int[]> fixes = new ArrayList<>();
        List<String> fixNames = new ArrayList<>();
        JSONArray fx = P.optJSONArray("fixes");
        if (fx != null) for (int i = 0; i < fx.length(); i++) {
            JSONObject f = fx.optJSONObject(i);
            if (f == null) continue;
            int s = toMin(f.optString("s")), e = toMin(f.optString("e"));
            if (e <= s) continue;
            int at = 0;
            while (at < fixes.size() && fixes.get(at)[0] < s) at++;
            fixes.add(at, new int[]{s, e});
            fixNames.add(at, f.optString("label", "").isEmpty() ? "dem fixen Block" : f.optString("label"));
        }

        List<JSONObject> out = new ArrayList<>();
        List<String> tomorrow = new ArrayList<>();
        int cursor = from, fi = 0, placed = 0;
        boolean moved = body.isEmpty();                          // add a little movement if none is planned
        while (fi < fixes.size() && fixes.get(fi)[1] <= cursor) fi++;
        // just out of a long fixed block → refuel first
        if (fi > 0 && cursor - fixes.get(fi - 1)[1] <= 20 && fixes.get(fi - 1)[1] - fixes.get(fi - 1)[0] >= 180
            && cursor + 30 <= windDown) {
            out.add(block(cursor, cursor + 30, "In Ruhe essen & auftanken", "pause",
                "Nach " + fixNames.get(fi - 1) + " zuerst auftanken, bevor etwas Neues kommt.", true));
            cursor += 30;
        }
        for (int k = 0; k < order.size(); k++) {
            Task t = order.get(k);
            boolean ok = false;
            while (true) {
                int limit = fi < fixes.size() ? Math.min(fixes.get(fi)[0], windDown) : windDown;
                if (cursor < limit && cursor + t.dur <= limit) { ok = true; break; }
                if (cursor < limit && limit - cursor >= 20 && t.dur > 30) {   // squeeze a shorter version in
                    t.dur = (limit - cursor) / 5 * 5;
                    ok = true;
                    break;
                }
                if (fi >= fixes.size()) break;                   // no window left today
                cursor = Math.max(cursor, fixes.get(fi)[1]);
                boolean longFix = fixes.get(fi)[1] - fixes.get(fi)[0] >= 180;
                String name = fixNames.get(fi);
                fi++;
                if (longFix && cursor + 30 <= windDown) {
                    out.add(block(cursor, cursor + 30, "In Ruhe essen & auftanken", "pause",
                        "Nach " + name + " zuerst auftanken, bevor etwas Neues kommt.", true));
                    cursor += 30;
                }
            }
            if (!ok) { tomorrow.add(t.title); continue; }
            out.add(block(cursor, cursor + t.dur, t.title, t.kind, why(t, energy, warm, low), false));
            cursor += t.dur;
            placed++;
            boolean more = k + 1 < order.size();
            if (t.kind.equals("fokus") && more && cursor + 10 <= windDown) {
                out.add(block(cursor, cursor + 10, "Kurze Pause — weg vom Bildschirm", "pause", "Zehn Minuten, dann ist der Kopf wieder da.", true));
                cursor += 10;
            } else if (!moved && more && placed == 1 && cursor + 20 <= windDown) {
                out.add(block(cursor, cursor + 20, "Kurzer Spaziergang oder Dehnen", "körper", "Etwas Bewegung macht den Kopf frei.", true));
                cursor += 20;
                moved = true;
            }
        }
        if (from < sleep - 5) {
            int s = Math.max(Math.max(windDown, cursor), from);
            if (s < sleep) out.add(block(s, sleep, "Runterfahren — Bildschirm aus", "pause",
                "Bildschirmarm ausklingen lassen, damit du um " + fmt(sleep) + " gut einschläfst.", true));
        }

        // anchors + kept (already done) blocks + the new plan, in time order
        List<JSONObject> all = new ArrayList<>(keep);
        all.add(anchor(wake, wake, "Aufstehen ☀️"));
        for (int i = 0; i < fixes.size(); i++) {
            String nm = fixNames.get(i);
            all.add(anchor(fixes.get(i)[0], fixes.get(i)[1], nm.equals("dem fixen Block") ? "Fixer Block" : nm));
        }
        all.add(anchor(sleep, sleep, "In die Höhle 🌙"));
        all.addAll(out);
        Collections.sort(all, (x, y) -> x.optInt("s") != y.optInt("s") ? Integer.compare(x.optInt("s"), y.optInt("s"))
            : Boolean.compare(y.optBoolean("anchor"), x.optBoolean("anchor")));
        JSONArray plan = new JSONArray();
        for (JSONObject o : all) plan.put(o);

        // the honest note
        StringBuilder note = new StringBuilder();
        if (placed == 0) note.append("Heute ist kein freies Fenster mehr dafür. ");
        else if (reflow) note.append("Neu geplant ab ").append(fmt(from)).append(": ").append(placed).append(placed == 1 ? " Sache" : " Sachen").append(" für den Rest des Tages. ");
        else note.append(placed).append(placed == 1 ? " Sache" : " Sachen").append(" eingeplant, mit Luft dazwischen. ");
        if (warm && !heavy.isEmpty()) note.append("Der Raum ist warm – das Schwere kommt deshalb zuerst. ");
        else if (low && !heavy.isEmpty()) note.append("Wenig Energie: Leichtes zuerst, vom Schweren nur ein 25-Minuten-Anfang. ");
        else if (energy >= 3 && !heavy.isEmpty()) note.append("Du bist auf Zug – Denkarbeit zuerst. ");
        if (!tomorrow.isEmpty()) note.append("Auf morgen: ").append(android.text.TextUtils.join(", ", tomorrow)).append(".");
        put("plan", plan);
        put("note", note.toString().trim());
        save();
        renderPlan();
    }

    private static int roundUp5(int m) { return (m + 4) / 5 * 5; }

    private static String why(Task t, int energy, boolean warm, boolean low) {
        if (t.kind.equals("fokus")) {
            if (warm) return "Wärme macht müde – Schweres zuerst, solange es geht.";
            if (low) return "Nur ein kurzer Anfang – bei wenig Energie zählt der Start.";
            if (energy >= 3) return "Du bist auf Zug – das Schwere, solange der Kopf frisch ist.";
            return "Denkarbeit ins frischeste freie Fenster.";
        }
        if (t.kind.equals("körper")) return "Bewegung bringt Energie zurück.";
        if (t.fun) return "Entspannt – passt gut ans Ende des Tages.";
        return low ? "Leicht – passt zu wenig Energie." : "Leicht – läuft gut nebenher.";
    }

    private static JSONObject block(int s, int e, String title, String kind, String why, boolean auto) {
        JSONObject o = new JSONObject();
        try { o.put("s", s).put("e", e).put("title", title).put("kind", kind).put("why", why).put("auto", auto); } catch (JSONException ignored) { }
        return o;
    }
    private static JSONObject anchor(int s, int e, String title) {
        JSONObject o = new JSONObject();
        try { o.put("s", s).put("e", e).put("title", title).put("anchor", true); } catch (JSONException ignored) { }
        return o;
    }

    // ================= rendering =================

    private void renderPlan() {
        String note = P.optString("note", "");
        noteBox.setText(note);
        noteBox.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        final JSONArray plan = P.optJSONArray("plan");
        timeline.removeAllViews();
        if (plan == null || plan.length() == 0) { resultCard.setVisibility(View.GONE); return; }
        resultCard.setVisibility(View.VISIBLE);
        for (int i = 0; i < plan.length(); i++) {
            final JSONObject o = plan.optJSONObject(i);
            if (o == null) continue;
            boolean anchor = o.optBoolean("anchor"), done = o.optBoolean("done");
            int s = o.optInt("s"), e = o.optInt("e");
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(13), 0, dp(13));
            if (done) row.setAlpha(0.5f);

            TextView time = mono(s == e ? fmt(s) : fmt(s) + "–" + fmt(e), ink3, 12);
            row.addView(time, new LinearLayout.LayoutParams(dp(98), -2));

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(anchor ? 17 : 16, anchor ? blue : ink, true);
            title.setText(o.optString("title"));
            if (done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            body.addView(title);
            if (!anchor) {
                TextView kind = mono(o.optString("kind", "").toUpperCase(Locale.ROOT), ink3, 9.5f);
                kind.setPadding(dp(8), dp(2), dp(8), dp(2));
                kind.setBackground(box(0, line, 30));
                body.addView(kind, lp(-2, -2, 0, dp(4), 0, 0));
                String why = o.optString("why", "");
                if (!why.isEmpty()) {
                    TextView w = text(13, ink2, false);
                    w.setText(why);
                    body.addView(w, lp(-1, -2, 0, dp(4), 0, 0));
                }
            }
            row.addView(body, new LinearLayout.LayoutParams(0, -2, 1f));

            if (!anchor) {
                TextView mark = text(13, rust, true);
                mark.setText(done ? "↺" : "erledigt ✓");
                mark.setPadding(dp(10), dp(4), 0, dp(10));
                mark.setOnClickListener(v -> { try { o.put("done", !o.optBoolean("done")); } catch (JSONException ignored) { } save(); renderPlan(); });
                row.addView(mark);
            }
            timeline.addView(row);
            if (i < plan.length() - 1) {
                View sep = new View(this);
                sep.setBackgroundColor(line);
                timeline.addView(sep, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
            }
        }
    }

    private void renderFixes() {
        fixBox.removeAllViews();
        JSONArray fx = P.optJSONArray("fixes");
        if (fx == null) { fx = new JSONArray(); put("fixes", fx); }
        final JSONArray fixes = fx;
        for (int i = 0; i < fixes.length(); i++) {
            final JSONObject f = fixes.optJSONObject(i);
            if (f == null) continue;
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(fixTime(f, "s"));
            row.addView(mono("–", ink3, 13), lp(-2, -2, dp(6), 0, dp(6), 0));
            row.addView(fixTime(f, "e"));
            EditText label = new EditText(this);
            label.setText(f.optString("label", ""));
            label.setHint("Label");
            label.setHintTextColor(ink3);
            label.setTextColor(ink);
            label.setTextSize(15);
            label.setSingleLine(true);
            label.setPadding(dp(12), dp(9), dp(12), dp(9));
            label.setBackground(box(bg, line, 12));
            label.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
                @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
                @Override public void afterTextChanged(Editable e) { try { f.put("label", e.toString()); } catch (JSONException ignored) { } save(); }
            });
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1f);
            llp.leftMargin = dp(8);
            row.addView(label, llp);
            TextView x = text(16, ink3, false);
            x.setText("✕");
            x.setPadding(dp(12), dp(8), dp(4), dp(8));
            x.setOnClickListener(v -> { fixes.remove(idx); save(); renderFixes(); });
            row.addView(x);
            fixBox.addView(row, lp(-1, -2, 0, dp(8), 0, 0));
        }
        TextView add = chip("+ Block");
        add.setOnClickListener(v -> {
            try { fixes.put(new JSONObject().put("s", "13:00").put("e", "14:00").put("label", "")); } catch (JSONException ignored) { }
            save();
            renderFixes();
        });
        fixBox.addView(add, lp(-2, -2, 0, dp(10), 0, 0));
    }

    private TextView fixTime(final JSONObject f, final String key) {
        final TextView t = mono(f.optString(key, "08:00"), ink, 15);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        t.setBackground(box(bg, line, 12));
        t.setOnClickListener(v -> pickTime(t.getText().toString(), val -> {
            try { f.put(key, val); } catch (JSONException ignored) { }
            save();
            t.setText(val);
        }));
        return t;
    }

    private TextView timeButton(final String key) {
        final TextView t = mono(P.optString(key, "07:00"), ink, 20);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(box(bg, line, 12));
        t.setOnClickListener(v -> pickTime(t.getText().toString(), val -> { put(key, val); save(); t.setText(val); }));
        return t;
    }

    private interface TimeSet { void set(String hhmm); }

    private void pickTime(String current, final TimeSet cb) {
        int m = toMin(current);
        new TimePickerDialog(this, (view, h, min) -> cb.set(String.format(Locale.ROOT, "%02d:%02d", h, min)), m / 60, m % 60, true).show();
    }

    private void renderEnergy() {
        energyBox.removeAllViews();
        int sel = P.optInt("energy", 2);
        LinearLayout row = null;
        for (int i = 0; i < ENERGY.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                energyBox.addView(row, lp(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            final int idx = i;
            TextView c = chip(ENERGY[i]);
            if (i == sel) { c.setBackground(box(rust, rust, 40)); c.setTextColor(0xFFFFFFFF); }
            c.setOnClickListener(v -> { put("energy", idx); save(); renderEnergy(); });
            row.addView(c, lp(-2, -2, 0, 0, dp(8), 0));
        }
    }

    private void styleWarm() {
        boolean on = P.optBoolean("warm", false);
        warmChip.setBackground(box(on ? rust : 0, on ? rust : line, 40));
        warmChip.setTextColor(on ? 0xFFFFFFFF : ink);
    }

    // ================= small helpers =================

    private TextView chip(String s) {
        TextView t = text(15, ink, true);
        t.setText(s);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(box(0, line, 40));
        return t;
    }

    private LinearLayout cardBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(18), dp(18), dp(18));
        l.setBackground(box(card, line, 18));
        return l;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    private TextView mono(String s, int color, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(sp);
        t.setLetterSpacing(sp <= 12 ? 0.12f : 0.04f);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** Calm the system bars: no forced contrast scrim, icons tinted to match the background. */
    private void calmBars(int bg) {
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                boolean light = (((bg >> 16) & 255) + ((bg >> 8) & 255) + (bg & 255)) > 384;
                int f = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                wic.setSystemBarsAppearance(light ? f : 0, f);
            }
        }
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
