package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Faktencheck – not an oracle, a shield. It can't tell you if something is true offline, but it does
 * the thing that actually protects you: it scans a claim/headline for the tricks fake news uses
 * (outrage words, "they don't want you to know", all-caps, "share before it's deleted", absolute
 * claims), forces the three questions that matter (source, date, second source), and sends you to
 * real fact-check sites and a reverse image search with one tap. Share any text to it, or paste it.
 */
public class FaktenActivity extends Activity {

    private int bg, card, line, ink, ink2, ink3, accent, green = 0xFF2BBF6A, amber = 0xFFE8B931, red = 0xFFE0552F;
    private EditText input;
    private LinearLayout result;

    // Manipulation markers (lowercased substrings) → why.
    private static final String[][] FLAGS = {
        {"teilen bevor", "„Teilen, bevor es gelöscht wird“ – Dringlichkeit ist ein Trick, kein Beweis."},
        {"bevor es gelöscht", "Künstliche Dringlichkeit soll dich zum Teilen drängen, ohne zu prüfen."},
        {"verschweigen", "„Die Medien verschweigen …“ – spielt mit Misstrauen statt mit Belegen."},
        {"mainstream", "„Mainstream-Medien“ als Feindbild ersetzt oft echte Quellen."},
        {"sie wollen nicht", "„Sie wollen nicht, dass du das weißt“ – klassischer Verschwörungs-Haken."},
        {"wacht auf", "Aufrufe wie „Wacht auf!“ zielen auf Emotion, nicht auf Fakten."},
        {"schockierend", "Schock-Wörter sollen dich empören, bevor du nachdenkst."},
        {"unglaublich", "Reiz-Wörter ersetzen keine Belege."},
        {"skandal", "„Skandal“ ohne benannte Quelle ist ein Warnsignal."},
        {"100%", "Absolute Sicherheit („100 %“) gibt es bei echten Nachrichten selten."},
        {"beweist endlich", "„Beweist endlich …“ – große Behauptung, prüf die Quelle."},
        {"ärzte hassen", "„Ärzte/Experten hassen diesen Trick“ ist ein Werbe-/Fake-Muster."},
        {"geheim", "„Geheim“ / „verheimlicht“ – appelliert an Misstrauen."},
        {"!!!", "Mehrere Ausrufezeichen = Emotion, nicht Information."},
    };
    private static final String[] ABS = {"immer", "nie", "alle", "niemand", "jeder", "komplett", "total"};

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int[] pl = Themes.current(getSharedPreferences("home", MODE_PRIVATE)).pal(night);
        bg = pl[Themes.BG] | 0xFF000000;
        ink = pl[Themes.INK];
        ink2 = pl[Themes.INK2];
        ink3 = pl[Themes.INK3];
        accent = pl[Themes.ACCENT];
        card = pl[Themes.PANEL] | 0xFF000000;
        line = pl[Themes.LINE];
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (android.graphics.Color.luminance(bg) > 0.5f && android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(bits, bits);
        }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(44), dp(20), dp(36));
        col.setOnApplyWindowInsetsListener((v, in) -> {
            int t = in.getSystemWindowInsetTop(), b = in.getSystemWindowInsetBottom();
            v.setPadding(dp(20), t + dp(16), dp(20), b + dp(24));
            return in;
        });
        sv.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView tag = mono(11, accent);
        tag.setText("— FAKTENCHECK");
        tag.setLetterSpacing(0.2f);
        head.addView(tag, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = text(20, ink, true);
        x.setText("✕");
        x.setPadding(dp(12), dp(4), dp(4), dp(4));
        x.setOnClickListener(v -> finish());
        head.addView(x);
        col.addView(head);

        TextView sub = text(14, ink2, false);
        sub.setText("Kein Orakel – ein Schutz. Ich prüfe nicht, ob etwas wahr ist, aber ich zeige dir die Tricks und schicke dich zu echten Faktenchecks.");
        sub.setLineSpacing(0, 1.25f);
        col.addView(sub, mt(dp(6)));

        input = new EditText(this);
        input.setHint("Schlagzeile / Behauptung / Nachricht hier einfügen …");
        input.setHintTextColor(ink3);
        input.setTextColor(ink);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(box(card, line, 14));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        col.addView(input, mt(dp(14)));

        col.addView(button("Prüfen", true, () -> analyse()), mt(dp(10)));

        result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        col.addView(result);

        setContentView(sv);

        // Shared text ("Teilen → Faktencheck") lands here.
        Intent in = getIntent();
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                input.setText(t.toString());
                col.post(this::analyse);
            }
        }
    }

    private void analyse() {
        result.removeAllViews();
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) return;
        String t = raw.toLowerCase(Locale.GERMAN);

        List<String> found = new ArrayList<>();
        for (String[] f : FLAGS) if (t.contains(f[0])) found.add(f[1]);
        // ALL-CAPS words (manipulative shouting).
        int caps = 0;
        for (String w : raw.split("\\s+")) if (w.length() >= 4 && w.equals(w.toUpperCase(Locale.GERMAN)) && w.matches(".*[A-ZÄÖÜ].*")) caps++;
        if (caps >= 2) found.add(caps + " Wörter in GROSSBUCHSTABEN – Schreien statt Belegen.");
        int excl = raw.length() - raw.replace("!", "").length();
        if (excl >= 3) found.add(excl + " Ausrufezeichen – zielt auf Emotion.");
        List<String> absHit = new ArrayList<>();
        for (String a : ABS) if (t.matches(".*\\b" + a + "\\b.*")) absHit.add(a);
        if (!absHit.isEmpty()) found.add("Absolute Wörter (" + String.join(", ", absHit) + ") – echte Nachrichten sind selten so eindeutig.");
        boolean looksUrl = t.contains("http") || t.contains("www.");

        // Risk meter.
        int score = Math.min(5, found.size());
        int col = score >= 3 ? red : score >= 1 ? amber : green;
        String verdict = score >= 3 ? "VIELE WARNZEICHEN · besonders vorsichtig"
            : score >= 1 ? "EINZELNE WARNZEICHEN · gegenprüfen" : "KEINE TYPISCHEN TRICKS · trotzdem Quelle prüfen";
        LinearLayout meter = cardBox();
        TextView mv = text(16, col, true);
        mv.setText("● " + verdict);
        meter.addView(mv);
        TextView dots = text(22, col, true);
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 5; i++) d.append(i < score ? "◆ " : "◇ ");
        dots.setText(d.toString());
        meter.addView(dots, mt(dp(4)));
        result.addView(meter);

        if (!found.isEmpty()) {
            LinearLayout fl = cardBox();
            fl.addView(cardTag("GEFUNDENE TRICKS"));
            for (String f : found) {
                TextView tv = text(13, ink2, false);
                tv.setText("• " + f);
                tv.setLineSpacing(0, 1.25f);
                fl.addView(tv, mt(dp(5)));
            }
            result.addView(fl);
        }

        // The three questions.
        LinearLayout q = cardBox();
        q.addView(cardTag("DIE 3 FRAGEN · HAK AB"));
        q.addView(check("Wer sagt das? Eine Quelle, die ich kenne und nachschlagen kann?"));
        q.addView(check("Wann? Ist es aktuell – oder alte Nachricht neu aufgewärmt?"));
        q.addView(check("Zweite Quelle? Berichtet ein unabhängiges, seriöses Medium dasselbe?"));
        result.addView(q);

        // Cross-check buttons.
        String query = Uri.encode(raw.length() > 120 ? raw.substring(0, 120) : raw);
        LinearLayout checks = cardBox();
        checks.addView(cardTag("GEGENCHECK · ANTIPPEN"));
        checks.addView(button("🔎 Behauptung googeln", false, () -> web("https://www.google.com/search?q=" + query)));
        checks.addView(button("✓ Correctiv Faktencheck", false, () -> web("https://correctiv.org/?s=" + query)), mt(dp(8)));
        checks.addView(button("✓ Mimikama", false, () -> web("https://www.mimikama.org/?s=" + query)), mt(dp(8)));
        checks.addView(button("✓ AFP Faktencheck", false, () -> web("https://faktencheck.afp.com/list/all/all/all/" + query)), mt(dp(8)));
        checks.addView(button("🖼 Bild rückwärts suchen (Google Lens)", false, () -> web("https://images.google.com/")), mt(dp(8)));
        result.addView(checks);

        LinearLayout tipc = cardBox();
        tipc.addView(cardTag("MERKSATZ"));
        tipc.addView(info("Empörung ist ein Gefühl, kein Beweis – genau wie bei Zweifeln. Je stärker etwas dich sofort wütend oder ängstlich macht, desto eher will es dich packen, nicht informieren. Kurz durchatmen, dann prüfen."));
        result.addView(tipc);
    }

    private void web(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    /* ---- views ---- */

    private View check(String s) {
        TextView t = text(13, ink, false);
        boolean[] on = {false};
        t.setText("○  " + s);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, dp(7), 0, dp(7));
        t.setOnClickListener(v -> {
            on[0] = !on[0];
            t.setText((on[0] ? "✓  " : "○  ") + s);
            t.setTextColor(on[0] ? green : ink);
        });
        return t;
    }

    private LinearLayout cardBox() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(box(card, line, 18));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setLayoutParams(mt(dp(12)));
        return b;
    }

    private TextView cardTag(String s) {
        TextView t = mono(10, accent);
        t.setText("— " + s);
        t.setLetterSpacing(0.14f);
        t.setLayoutParams(mb(dp(8)));
        return t;
    }

    private TextView info(String s) {
        TextView t = text(13, ink2, false);
        t.setText(s);
        t.setLineSpacing(0, 1.3f);
        return t;
    }

    private View button(String label, boolean primary, Runnable act) {
        TextView t = text(15, primary ? 0xFFFFFFFF : ink, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackground(box(primary ? accent : card, primary ? accent : line, 14));
        t.setOnClickListener(v -> act.run());
        if (t.getLayoutParams() == null) t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private LinearLayout.LayoutParams mt(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top;
        return p;
    }

    private LinearLayout.LayoutParams mb(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = bottom;
        return p;
    }

    private TextView text(float sp, int col, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col);
        t.setTypeface(android.graphics.Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        return t;
    }

    private TextView mono(float sp, int col) {
        TextView t = text(sp, col, true);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        return t;
    }

    private android.graphics.drawable.GradientDrawable box(int fill, int stroke, int rad) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(rad));
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
