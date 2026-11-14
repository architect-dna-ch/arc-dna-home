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
import android.view.animation.AlphaAnimation;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Fokus — the decision funnel. Instead of a wall of 10+ apps, two choices at a time trim the
 * situation down (Freizeit/Fokus → Planen/Machen → Schule/Arbeit …) until only the 2–3 apps that
 * actually fit right now are left. Self-contained: never touches the home screen's own code.
 */
public class FokusActivity extends Activity {

    // ---- the tree ----
    private static final class Node {
        String la, ha, lb, hb;   // split: two labels + hints
        Node a, b;               // split children
        String[] apps;           // leaf: app tokens
        String crumb;            // breadcrumb word
        boolean leaf() { return apps != null; }
    }
    private static Node split(String crumb, String la, String ha, Node a, String lb, String hb, Node b) {
        Node n = new Node(); n.crumb = crumb; n.la = la; n.ha = ha; n.a = a; n.lb = lb; n.hb = hb; n.b = b; return n;
    }
    private static Node leaf(String crumb, String... apps) { Node n = new Node(); n.crumb = crumb; n.apps = apps; return n; }

    private Node root() {
        return split("Start",
            "Freizeit", "abschalten, geniessen",
                split("Freizeit",
                    "Ruhe", "runterkommen", leaf("Ruhe", "player", "globus", "freq"),
                    "Spiel", "Spass, Wettkampf", leaf("Spiel", "race", "nexus", "board")),
            "Fokus", "etwas schaffen",
                split("Fokus",
                    "Planen", "ordnen, vorbereiten",
                        split("Planen",
                            "Schule", "", leaf("Schule", "wecker", "buecher", "lern"),
                            "Arbeit", "", leaf("Arbeit", "board", "wecker", "recorder")),
                    "Machen", "jetzt umsetzen",
                        split("Machen",
                            "Schule", "", leaf("Schule", "lern", "buecher", "recorder"),
                            "Arbeit", "", leaf("Arbeit", "board", "recorder", "vpn"))));
    }

    private final Deque<Node> stack = new ArrayDeque<>();
    private final Deque<String> trail = new ArrayDeque<>();
    private Node cur;
    private int bg, card, line, ink, ink2, ink3, rust;
    private LinearLayout rootView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView();
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF0E0E0D : 0xFFFAFAF8;
        card = night ? 0xFF1A1A18 : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFF4F2EC : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        calmBars(bg);
        overlayWindow();

        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(bg);
        float r = dp(24);
        panel.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});   // rounded left edge of the overlay panel
        rootView.setBackground(panel);
        rootView.setElevation(dp(16));
        rootView.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 30) {
            rootView.setOnApplyWindowInsetsListener((v, in) -> {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                rootView.setPadding(i.left, i.top, i.right, i.bottom);
                return in;
            });
        }
        // Shell: a blurred tap-out gutter on the left (15%), the funnel panel on the right (85%).
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        View gutter = new View(this);
        gutter.setOnClickListener(v -> closeOverlay());
        shell.addView(gutter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.15f));
        shell.addView(rootView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.85f));
        setContentView(shell);
        rootView.post(() -> {                                       // slide the panel in from the right
            rootView.setTranslationX(rootView.getWidth());
            rootView.animate().translationX(0).setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        });
        cur = root();
        show();
    }

    /** Translucent overlay window: dim + (API 31+) blur the home showing behind the panel. */
    private void overlayWindow() {
        android.view.Window w = getWindow();
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0.22f;
        if (Build.VERSION.SDK_INT >= 31) {
            lp.setBlurBehindRadius(dp(22));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
        w.setAttributes(lp);
    }

    /** Slide the panel back out to the right, then finish — no window animation. */
    private void closeOverlay() {
        if (rootView == null) { finish(); return; }
        rootView.animate().translationX(rootView.getWidth()).setDuration(220)
            .setInterpolator(new android.view.animation.AccelerateInterpolator())
            .withEndAction(() -> { finish(); overridePendingTransition(0, 0); }).start();
    }

    private void go(Node next, String crumb) {
        stack.push(cur);
        if (crumb != null) trail.push(crumb);
        cur = next;
        show();
    }

    @Override
    public void onBackPressed() {
        if (!stack.isEmpty()) { cur = stack.pop(); if (!trail.isEmpty()) trail.pop(); show(); }
        else closeOverlay();
    }

    private void show() {
        rootView.removeAllViews();
        rootView.addView(header());
        if (cur.leaf()) rootView.addView(leafView(), grow());
        else rootView.addView(splitView(), grow());
        AlphaAnimation a = new AlphaAnimation(0f, 1f); a.setDuration(220); rootView.startAnimation(a);
    }

    private LinearLayout.LayoutParams grow() { return new LinearLayout.LayoutParams(-1, 0, 1f); }

    // ---- header: cube mark + breadcrumb ----
    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(22), dp(18), dp(22), dp(10));

        // the ARC·DNA overlapping-square mark
        View cube = new View(this);
        GradientDrawable g = new GradientDrawable(); g.setStroke(dp(1.6f), rust); g.setColor(0);
        cube.setBackground(g);
        LinearLayout box = new LinearLayout(this);
        box.addView(cube, new LinearLayout.LayoutParams(dp(14), dp(14)));
        h.addView(box, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView crumb = new TextView(this);
        StringBuilder sb = new StringBuilder("ARC·DNA");
        java.util.Iterator<String> it = trail.descendingIterator();
        while (it.hasNext()) sb.append("  ›  ").append(it.next());
        crumb.setText(sb.toString());
        crumb.setTypeface(Typeface.MONOSPACE);
        crumb.setTextSize(11.5f);
        crumb.setLetterSpacing(0.04f);
        crumb.setTextColor(ink3);
        crumb.setPadding(dp(10), 0, dp(10), 0);
        h.addView(crumb, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!stack.isEmpty()) {
            TextView back = chip("↑ zurück");
            back.setOnClickListener(v -> onBackPressed());
            h.addView(back);
        }
        TextView all = chip("Alle Apps");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2); alp.leftMargin = dp(8);
        all.setOnClickListener(v -> finish()); // back to the full home
        h.addView(all, alp);
        return h;
    }

    // ---- a split: two big halves, tap to go deeper ----
    private View splitView() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(6), dp(16), dp(20));
        col.addView(half(cur.la, cur.ha, true), halfLp());
        View gap = new View(this); col.addView(gap, new LinearLayout.LayoutParams(-1, dp(14)));
        col.addView(half(cur.lb, cur.hb, false), halfLp());
        return col;
    }
    private LinearLayout.LayoutParams halfLp() { return new LinearLayout.LayoutParams(-1, 0, 1f); }

    private View half(String label, String hint, boolean first) {
        android.widget.FrameLayout c = new android.widget.FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(first ? card : bg);
        g.setStroke(dp(first ? 1.8f : 1.2f), first ? rust : line);
        g.setCornerRadius(dp(22));
        c.setBackground(g);
        c.setClickable(true);

        // faint overlapping-square motif, top-right — texture, not noise
        android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP | Gravity.END);
        clp.topMargin = dp(16); clp.rightMargin = dp(16);
        c.addView(ghostCube(first), clp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(dp(26), dp(22), dp(26), dp(22));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(ink);
        t.setTextSize(36);
        t.setLetterSpacing(-0.035f);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        col.addView(t);
        if (hint != null && !hint.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(hint);
            s.setTextColor(ink2);
            s.setTextSize(15);
            s.setPadding(0, dp(5), 0, 0);
            col.addView(s);
        }
        final Node target = first ? cur.a : cur.b;
        TextView p = new TextView(this);
        p.setText(previewOf(target));
        p.setTextColor(ink3);
        p.setTextSize(12);
        p.setTypeface(Typeface.MONOSPACE);
        p.setLetterSpacing(0.02f);
        p.setMaxLines(2);
        p.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.setPadding(0, dp(16), 0, 0);
        col.addView(p);
        c.addView(col, new android.widget.FrameLayout.LayoutParams(-1, -1));

        TextView arr = new TextView(this);
        arr.setText("→");
        arr.setTextColor(first ? rust : ink3);
        arr.setTextSize(26);
        android.widget.FrameLayout.LayoutParams alp = new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        alp.rightMargin = dp(24); alp.bottomMargin = dp(18);
        c.addView(arr, alp);

        final String crumb = label;
        c.setOnClickListener(v -> go(target, crumb));
        return c;
    }

    /** A preview of what's behind a choice: the next two options, or the apps if it's a leaf. */
    private String previewOf(Node n) {
        if (n == null) return "";
        if (n.leaf()) {
            StringBuilder b = new StringBuilder("↳  ");
            for (int i = 0; i < n.apps.length; i++) { if (i > 0) b.append("  ·  "); b.append(appLabel(n.apps[i])); }
            return b.toString();
        }
        return "↳  " + n.la + "  ·  " + n.lb;
    }

    /** The ARC·DNA overlapping-square mark, faint, as card texture. */
    private View ghostCube(boolean accent) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        f.setAlpha(0.10f);
        int col = accent ? rust : ink;
        View s1 = new View(this);
        GradientDrawable g1 = new GradientDrawable(); g1.setStroke(dp(2.4f), col); g1.setColor(0); s1.setBackground(g1);
        android.widget.FrameLayout.LayoutParams p1 = new android.widget.FrameLayout.LayoutParams(dp(62), dp(62));
        p1.leftMargin = dp(12); p1.topMargin = dp(16);
        f.addView(s1, p1);
        View s2 = new View(this);
        GradientDrawable g2 = new GradientDrawable(); g2.setStroke(dp(2.4f), col); g2.setColor(0); s2.setBackground(g2);
        f.addView(s2, new android.widget.FrameLayout.LayoutParams(dp(62), dp(62)));
        return f;
    }

    // ---- a leaf: the 2–3 apps that fit ----
    private View leafView() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(22), dp(12), dp(22), dp(24));

        TextView lead = new TextView(this);
        lead.setText("Dafür brauchst du jetzt:");
        lead.setTextColor(ink2);
        lead.setTextSize(15);
        lead.setPadding(0, dp(6), 0, dp(16));
        lead.setGravity(Gravity.CENTER);
        col.addView(lead);

        for (String tok : cur.apps) col.addView(appCard(tok), cardLp());

        TextView hint = new TextView(this);
        hint.setText("Nicht dabei? → Alle Apps");
        hint.setTextColor(ink3);
        hint.setTextSize(12.5f);
        hint.setPadding(0, dp(14), 0, 0);
        hint.setGravity(Gravity.CENTER);
        hint.setOnClickListener(v -> finish());
        col.addView(hint);
        return col;
    }
    private LinearLayout.LayoutParams cardLp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); return p; }

    private View appCard(String tok) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(card); g.setStroke(dp(1.4f), rust); g.setCornerRadius(dp(16));
        c.setBackground(g);
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        c.setClickable(true);

        TextView t = new TextView(this);
        t.setText(appLabel(tok));
        t.setTextColor(ink);
        t.setTextSize(22);
        t.setLetterSpacing(-0.02f);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arr = new TextView(this);
        arr.setText("→"); arr.setTextColor(rust); arr.setTextSize(22);
        c.addView(arr);

        c.setOnClickListener(v -> launch(tok));
        return c;
    }

    // ---- launching ----
    private void launch(String tok) {
        try {
            Intent i;
            if ("lern".equals(tok)) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/artifact/KjKSrDurQdhPhGEzZQtUhY"));
            } else {
                Class<?> c = cls(tok);
                if (c == null) return;
                i = new Intent(this, c);
            }
            startActivity(i);
        } catch (RuntimeException ignored) { }
    }

    private Class<?> cls(String t) {
        switch (t) {
            case "freq": return FreqActivity.class;
            case "globus": return GlobusActivity.class;
            case "nexus": return NexusActivity.class;
            case "board": return BoardActivity.class;
            case "recorder": return RecorderActivity.class;
            case "player": return PlayerActivity.class;
            case "wecker": return WeckerActivity.class;
            case "buecher": return BuecherActivity.class;
            case "race": return RaceActivity.class;
            case "vpn": return VpnActivity.class;
            default: return null;
        }
    }

    private String appLabel(String t) {
        switch (t) {
            case "freq": return "VOID FREQ";
            case "globus": return "Globus";
            case "nexus": return "Nexus";
            case "board": return "Board";
            case "recorder": return "Aufnahme";
            case "player": return "Musik";
            case "wecker": return "Wecker";
            case "buecher": return "Bücher";
            case "race": return "Fuchsjagd";
            case "vpn": return "VPN";
            case "lern": return "Lernbegleiter";
            default: return t;
        }
    }

    private TextView chip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ink2);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setPadding(dp(13), dp(8), dp(13), dp(8));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(40)); g.setColor(0); g.setStroke(dp(1), line);
        t.setBackground(g);
        t.setClickable(true);
        return t;
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

    private int dp(float v) { return Math.round(getResources().getDisplayMetrics().density * v); }
}
