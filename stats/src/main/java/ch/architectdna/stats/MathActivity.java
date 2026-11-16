package ch.architectdna.stats;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import static ch.architectdna.stats.ArcUi.t;

/**
 * ARC Math: type a problem, get the solution in about a second. Steps, just the result, a simple
 * explanation, or a check of your own attempt. Runs on Groq (gpt-oss-120b), one of the fastest
 * ways to run a strong model; no account, problems stay in a small history on this phone.
 */
public class MathActivity extends Activity {

    private static final String ORIGIN = "https://einspruch.architect-dna.ch";
    private static final String[] SYMBOLS = {"²", "³", "√", "π", "×", "÷", "(", ")", "%", "≈", "CHF "};

    private ArcUi ui;
    private SharedPreferences prefs;
    private LinearLayout col, out;
    private EditText input;
    private String mode = "steps";
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        prefs = getSharedPreferences("math", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFitsSystemWindows(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(44));
        scroll.addView(col);
        setContentView(scroll);
        build();
    }

    private void build() {
        col.removeAllViews();
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(ui.label("ARC Math", true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = ui.mono("✕", ui.ink2, 13);
        x.setPadding(ui.dp(12), ui.dp(6), 0, ui.dp(6));
        x.setOnClickListener(v -> finish());
        h.addView(x);
        col.addView(h);
        col.addView(ui.title(t("Solve it.\nGet it.", "Rechnen.\nVerstehen."), 40), ui.lp(-1, -2, ui.dp(22)));

        input = ui.input(t("Type the problem, e.g. 1'200 CHF at 2.5 % for 3 years, compound interest?",
            "Aufgabe eingeben, z.B. 1'200 CHF zu 2.5 % während 3 Jahren, Zinseszins?"), true, 2000);
        input.setMinLines(4);
        input.setText(prefs.getString("draft", ""));
        col.addView(input, ui.lp(-1, -2, ui.dp(22)));

        // Symbols the phone keyboard hides
        ArcUi.Wrap sym = new ArcUi.Wrap(this, ui.dp(6));
        for (String s : SYMBOLS) {
            TextView c = ui.chip(s.trim(), false, v -> {
                int p = Math.max(0, input.getSelectionStart());
                input.getText().insert(p, s);
            });
            c.setTypeface(Typeface.MONOSPACE);
            sym.addView(c);
        }
        col.addView(sym, ui.lp(-1, -2, ui.dp(10)));

        col.addView(ui.label(t("How", "Wie"), false), ui.lp(-1, -2, ui.dp(22)));
        ArcUi.Wrap modes = new ArcUi.Wrap(this, ui.dp(8));
        String[][] ms = {{"steps", t("🪜 Steps", "🪜 Schritte")}, {"answer", t("⚡ Just the result", "⚡ Nur Ergebnis")},
            {"explain", t("🧒 Explain simply", "🧒 Einfach erklärt")}, {"check", t("✓ Check my attempt", "✓ Meine Lösung prüfen")}};
        for (String[] m : ms) modes.addView(ui.chip(m[1], m[0].equals(mode), v -> { mode = m[0]; keepDraft(); build(); }));
        col.addView(modes, ui.lp(-1, -2, ui.dp(10)));

        col.addView(ui.button(t("Solve →", "Lösen →"), 1, v -> solve()), ui.lp(-1, ui.dp(56), ui.dp(20)));

        out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        col.addView(out, ui.lp(-1, -2, ui.dp(8)));

        JSONArray hist = history();
        if (hist.length() > 0) {
            col.addView(ui.label(t("Recent", "Zuletzt"), false), ui.lp(-1, -2, ui.dp(30)));
            for (int i = 0; i < Math.min(hist.length(), 12); i++) {
                JSONObject o = hist.optJSONObject(i);
                if (o == null) continue;
                String p = o.optString("p"), r = o.optString("r");
                col.addView(ui.row(null, p.length() > 70 ? p.substring(0, 70) + "…" : p, r.isEmpty() ? "" : "→ " + r,
                    v -> { input.setText(p); input.setSelection(p.length()); show(o.optString("t"), r, 0); }), ui.lp(-1, -2, ui.dp(8)));
            }
        }
    }

    private void keepDraft() {
        if (input != null) prefs.edit().putString("draft", input.getText().toString()).apply();
    }

    private void solve() {
        String p = input.getText().toString().trim();
        if (p.isEmpty() || busy) return;
        busy = true;
        keepDraft();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        out.removeAllViews();
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.rust));
        out.addView(pb, ui.lp(-1, ui.dp(6), ui.dp(14)));
        JSONObject body = new JSONObject();
        try {
            body.put("problem", p);
            body.put("mode", mode);
            body.put("lang", ArcUi.de() ? "de" : "en");
        } catch (Exception ignored) { }
        long t0 = System.currentTimeMillis();
        ui.post(ORIGIN, "/api/math", body, r -> {
            busy = false;
            out.removeAllViews();
            if (r.code == 200 && !r.json.optString("text").isEmpty()) {
                String text = r.json.optString("text"), res = r.json.optString("result");
                save(p, text, res);
                show(text, res, System.currentTimeMillis() - t0);
            } else {
                String msg = r.code == 429 ? t("Lots of problems in a short time. Try again in a bit.", "Viele Aufgaben in kurzer Zeit. Gleich nochmal.")
                    : r.code == 0 ? t("No connection.", "Keine Verbindung.") : t("Didn't work. Try again.", "Hat nicht geklappt. Nochmal.");
                out.addView(ui.body(msg, ui.rust, 15), ui.lp(-1, -2, ui.dp(12)));
            }
        });
    }

    private void show(String text, String result, long ms) {
        out.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        card.setBackground(ui.box(ui.card, ui.line, 8));
        if (!result.isEmpty()) {
            card.addView(ui.label(t("Result", "Ergebnis"), true));
            TextView big = ui.title(result, 28);
            big.setTextIsSelectable(true);
            card.addView(big, ui.lp(-1, -2, ui.dp(8)));
        }
        String steps = text;
        int cut = steps.lastIndexOf("\n→");
        if (!result.isEmpty() && cut > 0) steps = steps.substring(0, cut).trim();
        if (!steps.isEmpty() && !(result.isEmpty() ? false : steps.equals("→ " + result))) {
            TextView s = ui.body(steps, ui.ink, 15.5f);
            s.setTypeface(Typeface.MONOSPACE);
            s.setLineSpacing(0, 1.35f);
            s.setTextIsSelectable(true);
            card.addView(s, ui.lp(-1, -2, ui.dp(result.isEmpty() ? 0 : 16)));
        }
        LinearLayout acts = new LinearLayout(this);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        TextView speed = ui.mono(ms > 0 ? String.format(Locale.ROOT, "⚡ %.1f S", ms / 1000f) : "", ui.ink3, 10.5f);
        acts.addView(speed, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView copy = ui.mono(t("COPY", "KOPIEREN"), ui.rust, 11);
        copy.setPadding(ui.dp(12), ui.dp(8), 0, ui.dp(8));
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ARC Math", text));
            Toast.makeText(this, t("Copied", "Kopiert"), Toast.LENGTH_SHORT).show();
        });
        acts.addView(copy);
        card.addView(acts, ui.lp(-1, -2, ui.dp(14)));
        out.addView(card, ui.lp(-1, -2, ui.dp(16)));
        out.addView(ui.button(t("New problem", "Neue Aufgabe"), 2, v -> { input.setText(""); keepDraft(); out.removeAllViews(); input.requestFocus(); }),
            ui.lp(-1, ui.dp(50), ui.dp(10)));
    }

    private JSONArray history() {
        try { return new JSONArray(prefs.getString("hist", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void save(String p, String text, String r) {
        JSONArray old = history(), n = new JSONArray();
        try {
            n.put(new JSONObject().put("p", p).put("t", text).put("r", r));
            for (int i = 0; i < Math.min(old.length(), 29); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !p.equals(o.optString("p"))) n.put(o);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString("hist", n.toString()).apply();
    }
}
