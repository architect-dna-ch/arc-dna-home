package ch.architectdna.home;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Bücher: every e-book in one place, across edubase, RW-Lehrmittel and kv-edupool. Tap a book to
 * open the right platform (you're already logged in there); the activation code sits with each
 * book so the not-yet-activated ones are one tap from redeeming. Codes printed only inside the
 * paper book are marked "im Buch".
 */
public class BuecherActivity extends Activity {

    // {subject, platform, openUrl, redeemUrl, spineColorHex}
    private static final String[][] META = {
        {"Rechnungswesen", "RW-Lehrmittel", "https://ebook.rw-lehrmittel.ch/my-books", "https://ebook.rw-lehrmittel.ch", "2E66D6"},
        {"Mathematik", "edubase", "https://app.edubase.ch/#library", "", "B5271E"},
        {"Deutsch", "edubase", "https://app.edubase.ch/#library", "", "C8643B"},
        {"Französisch", "edubase", "https://app.edubase.ch/#library", "", "3E7C7B"},
        {"Wirtschaft & Gesellschaft", "kv-edupool", "https://kv-edupool.ch", "https://timelinekeys.kv-edupool.ch/redeem", "6A4FA0"},
    };
    // per subject: {title, subtitle, code}  (code "im Buch" = printed in the paper book)
    private static final String[][][] BOOKS = {
        {
            {"Finanzbuchhaltung Grundlagen", "Theorie · Aufgaben · Lösungen", "im Buch"},
            {"FiBu Repetition — Theorie", "Repetition & Vertiefung", "F211DEL1-UZPWYA"},
            {"FiBu Repetition — Lösungen", "Repetition & Vertiefung", "F212DEL1-ZMWVCO"},
            {"Mittelflussrechnung — Theorie", "Aufbau & Analyse", "M151DEL1-XBAOCR"},
            {"Mittelflussrechnung — Lösungen", "Aufbau & Analyse", "M152DEL1-RKZPOF"},
            {"Betriebsbuchhaltung — Theorie", "Grundlagen", "B161K1-BKOWHX"},
            {"Betriebsbuchhaltung — Lösungen", "Grundlagen", "B162K1-PALOXI"},
        },
        {
            {"Mathe Wirtschaftsschule — Band 1", "Aufgaben", "im Buch"},
            {"Mathe Wirtschaftsschule — Band 2", "Aufgaben", "im Buch"},
        },
        {
            {"Deutsch für die BM", "Grundlagenbuch", "im Buch"},
        },
        {
            {"abc DELF Junior B2", "3e édition · inkl. eBook", "im Buch"},
        },
        {
            {"W&G BM Sonderedition", "Recht · VWL · BWL · Digitale LM · 3 Jahre", "LIC-NHPX2PJROQKNDEVPRNN1"},
        },
    };

    private SharedPreferences prefs;
    private int bg, card, line, ink, ink2, ink3, rust;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView();
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF14110E : 0xFFF5F1E8;
        card = night ? 0xFF1C1915 : 0xFFFBF8F1;
        line = night ? 0xFF2E2820 : 0xFFE2D9C8;
        ink = night ? 0xFFEDE7DA : 0xFF1B1613;
        ink2 = night ? 0xFFADA293 : 0xFF6A5F54;
        ink3 = night ? 0xFF6E6456 : 0xFFA99E8E;
        rust = night ? 0xFFD1472E : 0xFFB5271E;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(20), dp(22), dp(40));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            sv.setOnApplyWindowInsetsListener((v, in) -> {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                col.setPadding(dp(22) + i.left, dp(20) + i.top, dp(22) + i.right, dp(40) + i.bottom);
                return in;
            });
        }
        sv.addView(col);

        TextView eye = mono("— BIBLIOTHEK", rust);
        eye.setLetterSpacing(0.22f);
        col.addView(eye);
        TextView h1 = new TextView(this);
        h1.setText("Bücher");
        h1.setTextColor(ink);
        h1.setTextSize(34);
        h1.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        h1.setPadding(0, dp(6), 0, dp(4));
        col.addView(h1);
        TextView sub = new TextView(this);
        sub.setText("Alle E-Books über edubase, RW-Lehrmittel und kv-edupool. Tippen öffnet die richtige Plattform – du bist dort schon eingeloggt.");
        sub.setTextColor(ink2);
        sub.setTextSize(14);
        sub.setPadding(0, 0, 0, dp(10));
        col.addView(sub);

        for (int g = 0; g < META.length; g++) addGroup(col, META[g], BOOKS[g]);
        setContentView(sv);
    }

    private void addGroup(LinearLayout col, String[] meta, String[][] books) {
        String subject = meta[0], platform = meta[1], openUrl = meta[2], redeemUrl = meta[3];
        final int spine = 0xFF000000 | Integer.parseInt(meta[4], 16);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(24), 0, dp(10));
        TextView t = new TextView(this);
        t.setText(subject);
        t.setTextColor(ink);
        t.setTextSize(19);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView plat = mono(platform.toUpperCase(java.util.Locale.GERMAN), ink3);
        head.addView(plat);
        col.addView(head);

        for (String[] bk : books) {
            final String title = bk[0], subtitle = bk[1], code = bk[2];
            boolean inBook = "im Buch".equals(code);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackground(box(card, line, dp(14)));
            row.setPadding(dp(13), dp(13), dp(15), dp(13));
            row.setClickable(true);
            // Tap the book = copy its code (not just show it). "Öffnen" opens the platform.
            row.setOnClickListener(v -> {
                if (inBook) { open(openUrl); return; }
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Aktivierungscode", code));
                toast("✓ Code kopiert: " + code);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.bottomMargin = dp(10);
            row.setLayoutParams(clp);

            LinearLayout.LayoutParams cvp = new LinearLayout.LayoutParams(dp(54), dp(76));
            cvp.rightMargin = dp(13);
            row.addView(makeCover(subject, spine), cvp);

            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            row.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView tt = new TextView(this);
            tt.setText(title);
            tt.setTextColor(ink);
            tt.setTextSize(15.5f);
            tt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            c.addView(tt);
            TextView st = new TextView(this);
            st.setText(subtitle + "  ·  " + platform);
            st.setTextColor(ink2);
            st.setTextSize(12.5f);
            st.setPadding(0, dp(2), 0, dp(8));
            c.addView(st);

            if (inBook) {
                TextView note = mono("📖  Code steht im gedruckten Buch", ink3);
                note.setTextSize(11.5f);
                c.addView(note);
            } else {
                boolean done = prefs.getBoolean("bookAkt:" + code, false);
                TextView codeRow = mono("CODE  " + code, ink);
                codeRow.setTextSize(12.5f);
                codeRow.setClickable(true);
                codeRow.setOnClickListener(v -> {
                    ClipboardManager cm = getSystemService(ClipboardManager.class);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Aktivierungscode", code));
                    toast("Code kopiert: " + code);
                });
                c.addView(codeRow);

                LinearLayout actions = new LinearLayout(this);
                actions.setPadding(0, dp(9), 0, 0);
                if (!redeemUrl.isEmpty()) {
                    TextView red = pill(done ? "Nochmal einlösen ↗" : "Einlösen ↗", rust, 0xFFFFFFFF);
                    red.setOnClickListener(v -> open(redeemUrl));
                    actions.addView(red);
                }
                final TextView akt = pill(done ? "✓ aktiviert" : "Als aktiviert markieren", 0x00000000, done ? rust : ink2);
                akt.setOnClickListener(v -> {
                    boolean now = !prefs.getBoolean("bookAkt:" + code, false);
                    prefs.edit().putBoolean("bookAkt:" + code, now).apply();
                    akt.setText(now ? "✓ aktiviert" : "Als aktiviert markieren");
                    akt.setTextColor(now ? rust : ink2);
                });
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
                alp.leftMargin = redeemUrl.isEmpty() ? 0 : dp(8);
                actions.addView(akt, alp);
                c.addView(actions);
            }

            TextView openRow = mono("ÖFFNEN  ↗", rust);
            openRow.setTextSize(11f);
            openRow.setLetterSpacing(0.08f);
            openRow.setPadding(0, dp(10), dp(8), dp(4));
            openRow.setClickable(true);
            openRow.setOnClickListener(v -> open(openUrl));
            c.addView(openRow);

            col.addView(row);
        }

        if (!redeemUrl.isEmpty()) {
            TextView all = mono("Alle " + subject + " öffnen ↗", ink3);
            all.setTextSize(11.5f);
            all.setPadding(dp(2), 0, 0, dp(2));
            all.setOnClickListener(v -> open(openUrl));
            col.addView(all);
        }
    }

    /** A little book cover like the mockup: coloured spine + light face with a bar and the subject.
     *  Kept graphic and short on purpose — the full title sits to the right of the cover. */
    private View makeCover(String subject, int spine) {
        LinearLayout cov = new LinearLayout(this);
        cov.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFF1EFF4);
        g.setCornerRadius(dp(5));
        g.setStroke(Math.round(getResources().getDisplayMetrics().density), 0x22000000);
        cov.setBackground(g);
        cov.setClipToOutline(true);

        View sp = new View(this);
        sp.setBackgroundColor(spine);
        cov.addView(sp, new LinearLayout.LayoutParams(dp(8), -1));

        LinearLayout face = new LinearLayout(this);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setPadding(dp(6), dp(8), dp(5), dp(8));

        View bar = new View(this);
        bar.setBackgroundColor(0xFF1C1A1F);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(20), dp(5));
        bp.bottomMargin = dp(6);
        face.addView(bar, bp);

        TextView subj = new TextView(this);
        subj.setText(subject);
        subj.setTextColor(spine);
        subj.setTextSize(8.5f);
        subj.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        subj.setLineSpacing(0, 1.0f);
        subj.setMaxLines(3);
        subj.setEllipsize(android.text.TextUtils.TruncateAt.END);
        face.addView(subj);

        cov.addView(face, new LinearLayout.LayoutParams(0, -1, 1f));
        return cov;
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast("Kein Browser gefunden");
        }
    }

    private TextView mono(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12);
        return t;
    }

    private TextView pill(String s, int bgColor, int fg) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(fg);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(40));
        g.setColor(bgColor);
        if (bgColor == 0) g.setStroke(dp(1), line);
        t.setBackground(g);
        t.setClickable(true);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.round(getResources().getDisplayMetrics().density), stroke);
        g.setCornerRadius(radius);
        return g;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
