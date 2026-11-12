package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Werkzeuge: the ARC·DNA AI tools that run on claude.ai (Lernbegleiter, Antrieb, Puls, Bär, Takt,
 * Fundament). They're web apps, so each opens in the browser where you're already signed in.
 */
public class WerkzeugeActivity extends Activity {

    // {name, subtitle, url, accentHex}
    private static final String[][] TOOLS = {
        {"Lernbegleiter", "Übt mit dir — Theorie erst nach dem Fehler", "https://claude.ai/artifact/KjKSrDurQdhPhGEzZQtUhY", "B5271E"},
        {"Antrieb", "Checkt bei dir ein & wirft dir Funken", "https://claude.ai/artifact/4r5tzLALZwBnjcZrjMcrmQ", "C8643B"},
        {"Puls", "Wachheit im Unterricht — nicht absitzen", "https://claude.ai/artifact/Bt4Jkje111WSshedwsBqN2", "3E7C7B"},
        {"Bär", "Zwei feste Säulen: Schlaf & Aufwachen", "https://claude.ai/artifact/98afjmwppVJkPpVMXBLjp2", "2E3A52"},
        {"Takt", "Dein Tag, um deinen Körper geplant · in der App", "app:takt", "2E9AA6"},
        {"Fundament", "Grundlinie, Anker & deine Wurzeln", "https://claude.ai/artifact/Gm9Vpr61vHwaNa9EmKzN7y", "A7853F"},
    };

    private int bg, card, line, ink, ink2, ink3, rust;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView();
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
        calmBars(bg);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(20), dp(22), dp(40));
        if (Build.VERSION.SDK_INT >= 30) {
            sv.setOnApplyWindowInsetsListener((v, in) -> {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                col.setPadding(dp(22) + i.left, dp(20) + i.top, dp(22) + i.right, dp(40) + i.bottom);
                return in;
            });
        }
        sv.addView(col);

        TextView eye = mono("— ARC·DNA WERKZEUGE", rust);
        eye.setLetterSpacing(0.22f);
        col.addView(eye);
        TextView h1 = new TextView(this);
        h1.setText("Werkzeuge");
        h1.setTextColor(ink);
        h1.setTextSize(34);
        h1.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        h1.setPadding(0, dp(6), 0, dp(4));
        col.addView(h1);
        TextView sub = new TextView(this);
        sub.setText("Deine KI-Begleiter. Takt läuft direkt in der App. Die anderen laufen auf claude.ai – Tippen öffnet sie im Browser, wo du schon eingeloggt bist.");
        sub.setTextColor(ink2);
        sub.setTextSize(14);
        sub.setPadding(0, 0, 0, dp(12));
        col.addView(sub);

        for (String[] t : TOOLS) col.addView(toolCard(t));
        setContentView(sv);
    }

    private View toolCard(String[] t) {
        final String name = t[0], subtitle = t[1], url = t[2];
        final int accent = 0xFF000000 | Integer.parseInt(t[3], 16);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(box(card, line, dp(14)));
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        row.setClickable(true);
        row.setOnClickListener(v -> open(url));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = dp(10);
        row.setLayoutParams(rlp);

        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(accent);
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dlp.rightMargin = dp(14);
        row.addView(dot, dlp);

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView tt = new TextView(this);
        tt.setText(name);
        tt.setTextColor(ink);
        tt.setTextSize(17);
        tt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.addView(tt);
        TextView st = new TextView(this);
        st.setText(subtitle);
        st.setTextColor(ink2);
        st.setTextSize(13);
        st.setPadding(0, dp(2), 0, 0);
        c.addView(st);
        row.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arr = mono(url.startsWith("app:") ? "→" : "↗", rust);
        arr.setTextSize(16);
        row.addView(arr);
        return row;
    }

    private void open(String url) {
        if ("app:takt".equals(url)) {                 // Takt is built in — no browser
            startActivity(new Intent(this, TaktActivity.class));
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_SHORT).show();
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

    private GradientDrawable box(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.round(getResources().getDisplayMetrics().density), stroke);
        g.setCornerRadius(radius);
        return g;
    }

    /** Calm the system bars: no forced contrast scrim, icons tinted to match the background. */
    private void calmBars(int bg) {
        if (android.os.Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
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
