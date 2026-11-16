package ch.architectdna.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * The 🔑 key of ARC Passwords. When a login field gets focus in any app or browser, a small key
 * docks right above the keyboard (like a key in its toolbar row). Tap = pick a login and fill it;
 * ✕ = gone for this field; hold ✕ = never in this app. Runs inside the ARC-DNA Scroll service.
 */
final class PassKey {
    private PassKey() { }

    private static View chip;
    private static WindowManager wm;
    static AccessibilityNodeInfo target;      // the focused login field
    static String targetPkg = "", targetHost = "";
    private static long dismissedAt;
    private static int dismissedField;

    private static final String[] BROWSERS = {"com.android.chrome", "ch.architectdna.browser", "com.mi.globalbrowser",
        "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser"};

    static void onEvent(AccessibilityService s, AccessibilityEvent e, String pkg) {
        if (!Vault.enabled(s)) return;
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !pkg.equals(targetPkg)
            && !pkg.equals(s.getPackageName()) && !pkg.contains("inputmethod")) { hide(s); return; }
        if (type != AccessibilityEvent.TYPE_VIEW_FOCUSED && type != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        if (pkg.equals(s.getPackageName()) || Vault.mutedIn(s, pkg)) return;
        AccessibilityNodeInfo n = e.getSource();
        if (n == null || !n.isEditable()) { if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) hide(s); return; }
        if (!isLoginField(n)) { hide(s); return; }
        int id = n.hashCode();
        if (id == dismissedField && System.currentTimeMillis() - dismissedAt < 10 * 60_000L) return;   // you said no
        target = n;
        targetPkg = pkg;
        targetHost = hostOf(s, pkg);
        show(s);
    }

    private static boolean isLoginField(AccessibilityNodeInfo n) {
        if (n.isPassword()) return true;
        String hint = (String.valueOf(n.getHintText()) + " " + String.valueOf(n.getViewIdResourceName()) + " "
            + String.valueOf(n.getContentDescription())).toLowerCase(Locale.ROOT);
        return hint.contains("pass") || hint.contains("passw") || hint.contains("kennwort") || hint.contains("e-mail")
            || hint.contains("email") || hint.contains("user") || hint.contains("benutzer") || hint.contains("login")
            || hint.contains("anmelde");
    }

    /** In a browser: the site from the address bar, so the right logins come up. */
    private static String hostOf(AccessibilityService s, String pkg) {
        boolean browser = false;
        for (String b : BROWSERS) if (b.equals(pkg)) browser = true;
        if (!browser) return "";
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return "";
            for (String id : new String[]{pkg + ":id/url_bar", pkg + ":id/url", pkg + ":id/mozac_browser_toolbar_url_view"}) {
                List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
                if (l != null && !l.isEmpty() && l.get(0).getText() != null) return cleanHost(l.get(0).getText().toString());
            }
            AccessibilityNodeInfo first = firstEditText(root);           // ARC Browser and others: the address field
            if (first != null && first.getText() != null) return cleanHost(first.getText().toString());
        } catch (RuntimeException ignored) { }
        return "";
    }

    private static String cleanHost(String t) {
        String h = t.replace("🔒", "").replace("⚠", "").trim().replaceFirst("^https?://", "");
        int sl = h.indexOf('/');
        return (sl > 0 ? h.substring(0, sl) : h).trim();
    }

    private static AccessibilityNodeInfo firstEditText(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstEditText(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    private static void show(AccessibilityService s) {
        if (wm == null) wm = s.getSystemService(WindowManager.class);
        float d = s.getResources().getDisplayMetrics().density;
        if (chip == null) {
            LinearLayout pill = new LinearLayout(s);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding((int) (12 * d), (int) (7 * d), (int) (6 * d), (int) (7 * d));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xF01A1A18);
            g.setCornerRadius(20 * d);
            g.setStroke((int) d, 0xFF2C2C2A);
            pill.setBackground(g);
            pill.setElevation(8 * d);
            TextView key = new TextView(s);
            key.setText("🔑  " + ArcUi.t("Fill login", "Login einfügen"));
            key.setTextColor(0xFFF4F2EC);
            key.setTextSize(14);
            key.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            key.setOnClickListener(v -> pick(s));
            pill.addView(key);
            TextView x = new TextView(s);
            x.setText("  ✕");
            x.setTextColor(0xFF9A9A98);
            x.setTextSize(15);
            x.setPadding((int) (8 * d), 0, (int) (6 * d), 0);
            x.setOnClickListener(v -> { dismissedField = target == null ? 0 : target.hashCode(); dismissedAt = System.currentTimeMillis(); hide(s); });
            x.setOnLongClickListener(v -> { Vault.mute(s, targetPkg); hide(s); return true; });
            pill.addView(x);
            chip = pill;
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = (int) (16 * d);
        p.y = anchorY(s, d);
        try {
            if (chip.getParent() == null) wm.addView(chip, p);
            else wm.updateViewLayout(chip, p);
        } catch (RuntimeException ignored) { }
    }

    /** Just above the keyboard if it's up, else just below the field. */
    private static int anchorY(AccessibilityService s, float d) {
        int screenH = s.getResources().getDisplayMetrics().heightPixels;
        try {
            for (AccessibilityWindowInfo w : s.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    if (r.top > screenH / 3) return r.top - (int) (46 * d);
                }
            }
        } catch (RuntimeException ignored) { }
        Rect f = new Rect();
        if (target != null) target.getBoundsInScreen(f);
        return Math.min(screenH - (int) (120 * d), f.bottom + (int) (6 * d));
    }

    static void hide(AccessibilityService s) {
        if (chip != null && chip.getParent() != null) try { wm.removeView(chip); } catch (RuntimeException ignored) { }
    }

    private static void pick(AccessibilityService s) {
        hide(s);
        Intent i = new Intent(s, VaultActivity.class).setAction(VaultActivity.PICK)
            .putExtra("pkg", targetPkg).putExtra("host", targetHost)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { s.startActivity(i); } catch (RuntimeException ignored) { }
    }

    /** After a pick: username into the field above the password, password into the password field. */
    static boolean fill(AccessibilityService s, Vault.Entry e) {
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        java.util.List<AccessibilityNodeInfo> fields = new java.util.ArrayList<>();
        collect(root, fields);
        AccessibilityNodeInfo pass = null, user = null;
        for (AccessibilityNodeInfo f : fields) if (f.isPassword()) { pass = f; break; }
        for (AccessibilityNodeInfo f : fields) {
            if (f.isPassword()) break;
            user = f;                                                  // the last text field before the password
        }
        if (pass == null && target != null) {                          // username-only page (password comes next)
            target.refresh();
            user = target;
        }
        boolean ok = false;
        if (user != null && !e.user.isEmpty()) ok |= setText(user, e.user);
        if (pass != null && !e.pass.isEmpty()) ok |= setText(pass, e.pass);
        return ok;
    }

    private static void collect(AccessibilityNodeInfo n, java.util.List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        if (n.isEditable() && n.isVisibleToUser()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out);
    }

    private static boolean setText(AccessibilityNodeInfo n, String t) {
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }
}
