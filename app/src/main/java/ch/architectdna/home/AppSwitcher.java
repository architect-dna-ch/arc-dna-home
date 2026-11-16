package ch.architectdna.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows-style app switcher driven by ARC Link: hold ⌥, press Tab to step through recent apps
 * (this overlay highlights the one selected), release ⌥ to switch to it. Shown over everything via
 * the ARC-DNA Scroll service. ⌥Tab = next, ⇧⌥Tab = previous, release = pick, Esc/other = cancel.
 */
final class AppSwitcher {
    private AppSwitcher() { }

    private static WindowManager wm;
    private static View panel;
    private static final List<String> apps = new ArrayList<>();
    private static int sel;
    private static final Handler H = new Handler(Looper.getMainLooper());

    static void step(AccessibilityService s, int dir) {
        H.post(() -> {
            if (panel == null || panel.getParent() == null) build(s);
            if (apps.isEmpty()) return;
            sel = ((sel + dir) % apps.size() + apps.size()) % apps.size();
            paint(s);
        });
    }

    static void pick(AccessibilityService s) {
        H.post(() -> {
            String pkg = (!apps.isEmpty() && sel >= 0 && sel < apps.size()) ? apps.get(sel) : null;
            hide();
            if (pkg == null) return;
            Intent i = s.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) try { s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); } catch (RuntimeException ignored) { }
        });
    }

    static void cancel() { H.post(AppSwitcher::hide); }

    private static void build(AccessibilityService s) {
        apps.clear();
        synchronized (ScrollAssistService.recent) { apps.addAll(ScrollAssistService.recent); }
        apps.remove(s.getPackageName());
        sel = Math.min(1, Math.max(0, apps.size() - 1));   // start on the previous app, like Alt+Tab
        if (wm == null) wm = s.getSystemService(WindowManager.class);
        boolean dark = (s.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        float d = s.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(s);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (14 * d), (int) (14 * d), (int) (14 * d), (int) (14 * d));
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark ? 0xF21A1A18 : 0xF2FFFFFF);
        g.setCornerRadius(18 * d);
        g.setStroke((int) d, dark ? 0xFF2C2C2A : 0xFFE0E0D8);
        row.setBackground(g);
        row.setElevation(16 * d);
        panel = row;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        try { wm.addView(panel, p); } catch (RuntimeException ignored) { }
        paint(s);
    }

    private static void paint(AccessibilityService s) {
        LinearLayout row = (LinearLayout) panel;
        row.removeAllViews();
        boolean dark = (s.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int ink = dark ? 0xFFF4F2EC : 0xFF1A1A1A, ink3 = dark ? 0xFF6F6E69 : 0xFF9A9A98, rust = dark ? 0xFFD9472F : 0xFFB5271E;
        float d = s.getResources().getDisplayMetrics().density;
        PackageManager pm = s.getPackageManager();
        for (int i = 0; i < apps.size(); i++) {
            LinearLayout cell = new LinearLayout(s);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding((int) (10 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
            if (i == sel) {
                GradientDrawable hl = new GradientDrawable();
                hl.setColor(dark ? 0xFF2A2522 : 0xFFF3ECE6);
                hl.setCornerRadius(12 * d);
                hl.setStroke((int) (1.5f * d), rust);
                cell.setBackground(hl);
            }
            ImageView icon = new ImageView(s);
            try {
                Drawable ic = pm.getApplicationIcon(apps.get(i));
                icon.setImageDrawable(ic);
            } catch (Exception e) { }
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams((int) (52 * d), (int) (52 * d));
            cell.addView(icon, il);
            TextView name = new TextView(s);
            name.setText(label(pm, apps.get(i)));
            name.setTextColor(i == sel ? ink : ink3);
            name.setTextSize(11);
            name.setMaxWidth((int) (72 * d));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            if (i == sel) name.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            name.setPadding(0, (int) (6 * d), 0, 0);
            cell.addView(name);
            row.addView(cell);
        }
    }

    private static String label(PackageManager pm, String pkg) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { return pkg; }
    }

    private static void hide() {
        if (panel != null && panel.getParent() != null) try { wm.removeView(panel); } catch (RuntimeException ignored) { }
        panel = null;
    }
}
