package ch.architectdna.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opener: a slim vertical toolbar on the right edge, only while you type a message in WhatsApp.
 * It reads the draft as you type (on the phone, nothing is sent anywhere) and shows a traffic
 * light: green = nothing in the way, amber = could be tighter, red = sounds like begging.
 * Tap the light for the reasons, ✓ to apply the safe fixes (doubled ?? / !!, extra spaces),
 * ✕ to hide it until you leave WhatsApp. Coach, not script: it never rewrites your words.
 */
final class OpenerOverlay {

    private static final int GO = 0xFF5DBB85, MID = 0xFFE0A94A, STOP = 0xFFE2614A,
        BG = 0xF21A1712, LINE = 0xFF2B261E, INK = 0xFFECE4D4, INK2 = 0xFFA79B89;

    private static LinearLayout bar, card;
    private static TextView light, count;
    private static AccessibilityNodeInfo field;
    private static String draft = "";
    private static boolean inWhatsApp, dismissed, cardOpen;

    private static boolean whatsapp(String pkg) {
        return pkg.equals("com.whatsapp") || pkg.equals("com.whatsapp.w4b");
    }

    /** Called for every accessibility event the service gets. Cheap outside WhatsApp. */
    static void onEvent(AccessibilityService s, AccessibilityEvent e, String pkg) {
        if (!s.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("openerBar", true)) return;
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (whatsapp(pkg)) { inWhatsApp = true; return; }
            if (switchedAway(s, pkg)) { inWhatsApp = false; dismissed = false; hide(s); }
            return;
        }
        if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || !whatsapp(pkg)) return;
        inWhatsApp = true;
        CharSequence cn = e.getClassName();
        if (cn == null || !cn.toString().contains("EditText")) return;
        StringBuilder sb = new StringBuilder();
        for (CharSequence t : e.getText()) if (t != null) sb.append(t);
        draft = sb.toString();
        try {
            AccessibilityNodeInfo src = e.getSource();
            if (src != null) field = src;
        } catch (RuntimeException ignored) { }
        if (dismissed) return;
        if (draft.trim().isEmpty()) { hide(s); return; }
        show(s);
        update(s);
    }

    /** A real app came to the front (not the keyboard, not the system UI, not ARC-DNA itself). */
    private static boolean switchedAway(Context c, String pkg) {
        if (pkg.equals("com.android.systemui") || pkg.equals(c.getPackageName())) return false;
        String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.startsWith(pkg + "/")) return false;
        return c.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    // ================= the rules (same spirit as the Opener page) =================

    private static List<String[]> check(String d) {
        List<String[]> out = new ArrayList<>();
        String low = d.toLowerCase(Locale.ROOT);
        int q = count(d, '?');
        int lines = d.split("\n+").length;
        if (low.matches("(?s).*(bitte (antworte|schreib)|warum (antwortest|schreibst) du nicht|meldest du dich|bist du sauer|ignorierst du|why (aren't|arent|don't|dont) you (answer|reply)).*"))
            out.add(new String[]{"stop", "Drängt auf Antwort", "Wirkt bedürftig. Lass Raum — wer will, antwortet."});
        int sorry = 0;
        for (String w : new String[]{"sorry", "tut mir leid", "entschuldig"}) sorry += occurrences(low, w);
        if (sorry > 1) out.add(new String[]{"mid", "Mehrfach entschuldigt", "Eine Entschuldigung reicht, wenn überhaupt."});
        if (d.length() > 320) out.add(new String[]{"mid", "Lang", d.length() + " Zeichen — kürzer wirkt entspannter."});
        if (q > 2) out.add(new String[]{"mid", q + " Fragen", "Eine gute Frage schlägt drei."});
        if (lines > 4) out.add(new String[]{"mid", lines + " Absätze", "Eher eine Nachricht, kein Brief."});
        if (d.contains("??") || d.contains("!!")) out.add(new String[]{"mid", "Doppelte Satzzeichen", "Kann drängend wirken. ✓ räumt es auf."});
        String letters = d.replaceAll("[^A-Za-zÄÖÜäöü]", "");
        if (letters.length() >= 12 && letters.equals(letters.toUpperCase(Locale.ROOT))) out.add(new String[]{"mid", "Alles in GROSS", "Liest sich wie Schreien."});
        return out;
    }

    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }
    private static int occurrences(String s, String w) { int n = 0, i = 0; while ((i = s.indexOf(w, i)) >= 0) { n++; i += w.length(); } return n; }

    /** Safe, mechanical fixes only — never touches the words. */
    private static String tidy(String d) {
        return d.replaceAll("\\?{2,}", "?").replaceAll("!{2,}", "!").replaceAll("[ \\t]{2,}", " ")
            .replaceAll("\\n{3,}", "\n\n").trim();
    }

    // ================= the toolbar =================

    private static int dp(Context c, float v) { return Math.round(c.getResources().getDisplayMetrics().density * v); }

    private static GradientDrawable pill(int fill, int stroke, Context c, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(c, 1)), stroke);
        g.setCornerRadius(dp(c, r));
        return g;
    }

    private static WindowManager.LayoutParams params(Context c, int gravity, int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.x = x;
        p.y = y;
        return p;
    }

    private static void show(AccessibilityService s) {
        if (bar != null) return;
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        bar = new LinearLayout(s);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(dp(s, 6), dp(s, 10), dp(s, 6), dp(s, 10));
        bar.setBackground(pill(BG, LINE, s, 22));
        bar.setElevation(dp(s, 6));

        light = new TextView(s);
        light.setWidth(dp(s, 30));
        light.setHeight(dp(s, 30));
        light.setGravity(Gravity.CENTER);
        light.setTextColor(0xFF0E0B0A);
        light.setTypeface(Typeface.DEFAULT_BOLD);
        light.setTextSize(13);
        light.setContentDescription("Opener: Hinweise");
        light.setOnClickListener(v -> { cardOpen = !cardOpen; update(s); });
        bar.addView(light);

        count = new TextView(s);
        count.setTextColor(INK2);
        count.setTextSize(9.5f);
        count.setTypeface(Typeface.MONOSPACE);
        count.setPadding(0, dp(s, 6), 0, dp(s, 6));
        bar.addView(count);

        bar.addView(button(s, "✓", "Aufräumen", v -> applyTidy(s)));
        bar.addView(button(s, "✕", "Ausblenden", v -> { dismissed = true; hide(s); }));

        try {
            wm.addView(bar, params(s, Gravity.END | Gravity.CENTER_VERTICAL, dp(s, 4), -dp(s, 150)));
        } catch (RuntimeException e) {
            bar = null;
        }
    }

    private static TextView button(Context c, String glyph, String desc, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(glyph);
        t.setTextColor(INK);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setWidth(dp(c, 34));
        t.setHeight(dp(c, 38));
        t.setContentDescription(desc);
        t.setOnClickListener(l);
        return t;
    }

    private static void update(AccessibilityService s) {
        if (bar == null) return;
        List<String[]> issues = check(draft);
        boolean stop = false;
        for (String[] i : issues) if (i[0].equals("stop")) stop = true;
        int colour = issues.isEmpty() ? GO : stop ? STOP : MID;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(colour);
        light.setBackground(dot);
        light.setText(issues.isEmpty() ? "" : String.valueOf(issues.size()));
        count.setText(String.valueOf(draft.length()));
        if (cardOpen) showCard(s, issues); else hideCard(s);
    }

    private static void showCard(AccessibilityService s, List<String[]> issues) {
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        boolean add = card == null;
        if (add) {
            card = new LinearLayout(s);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(s, 14), dp(s, 12), dp(s, 14), dp(s, 12));
            card.setBackground(pill(BG, LINE, s, 16));
            card.setElevation(dp(s, 6));
            card.setOnClickListener(v -> { cardOpen = false; hideCard(s); });
        }
        card.removeAllViews();
        if (issues.isEmpty()) issues.add(new String[]{"go", "Nichts im Weg", "Klingt nach dir. Senden."});
        for (String[] i : issues) {
            TextView t = new TextView(s);
            int c = i[0].equals("stop") ? STOP : i[0].equals("go") ? GO : MID;
            t.setText("● " + i[1]);
            t.setTextColor(c);
            t.setTextSize(13.5f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(t);
            TextView w = new TextView(s);
            w.setText(i[2]);
            w.setTextColor(INK2);
            w.setTextSize(12.5f);
            w.setMaxWidth(dp(s, 220));
            w.setPadding(dp(s, 14), 0, 0, dp(s, 8));
            card.addView(w);
        }
        if (add) {
            try {
                wm.addView(card, params(s, Gravity.END | Gravity.CENTER_VERTICAL, dp(s, 58), -dp(s, 150)));
            } catch (RuntimeException e) {
                card = null;
            }
        }
    }

    private static void hideCard(Context c) {
        if (card == null) return;
        try { ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeView(card); } catch (RuntimeException ignored) { }
        card = null;
    }

    static void hide(Context c) {
        hideCard(c);
        cardOpen = false;
        if (bar == null) return;
        try { ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeView(bar); } catch (RuntimeException ignored) { }
        bar = null;
    }

    private static void applyTidy(AccessibilityService s) {
        String clean = tidy(draft);
        if (clean.equals(draft)) {
            Toast.makeText(s, "Schon sauber.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean done = false;
        try {
            if (field != null) {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, clean);
                done = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            }
        } catch (RuntimeException ignored) { }
        if (!done) {
            ClipboardManager cm = (ClipboardManager) s.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Opener", clean));
            Toast.makeText(s, "Aufgeräumt & kopiert — einfügen.", Toast.LENGTH_SHORT).show();
            return;
        }
        draft = clean;
        update(s);
    }
}
