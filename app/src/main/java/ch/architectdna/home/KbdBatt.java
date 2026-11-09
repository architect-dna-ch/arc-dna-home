package ch.architectdna.home;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A tiny floating chip on the tablet showing the connected laptop keyboard's battery.
 * ARC Link (on the Mac) pushes the level over USB with:
 *   adb shell am broadcast -a ch.architectdna.home.KBD_BATT --ei level 72 --ez charging 0
 * Shows only while updates keep arriving (auto-hides 90 s after the last one = cable unplugged).
 */
final class KbdBatt {
    private static WindowManager wm;
    private static TextView chip;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = KbdBatt::hide;

    static boolean canShow(Context ctx) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
    }

    static void show(Context ctx, int level, boolean charging) {
        if (!canShow(ctx)) return;
        Context app = ctx.getApplicationContext();
        if (wm == null) wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (chip == null) chip = build(app);
        boolean low = level >= 0 && level <= 15;
        chip.setText("⌨ " + level + "%" + (charging ? " ⚡" : ""));
        chip.setTextColor(low ? 0xFFFF6B5A : 0xFFFFFFFF);
        GradientDrawable g = (GradientDrawable) chip.getBackground();
        g.setStroke(px(app, 1.5f), low ? 0xFFD1472E : 0x55FFFFFF);
        try { if (chip.getParent() == null) wm.addView(chip, params(app)); } catch (Exception ignored) { }
        H.removeCallbacks(HIDE);
        H.postDelayed(HIDE, 90000);
    }

    private static TextView build(Context app) {
        TextView t = new TextView(app);
        t.setTextSize(13);
        t.setPadding(px(app, 12), px(app, 6), px(app, 12), px(app, 6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xDC141210);
        g.setCornerRadius(px(app, 20));
        g.setStroke(px(app, 1.5f), 0x55FFFFFF);
        t.setBackground(g);
        return t;
    }

    private static WindowManager.LayoutParams params(Context app) {
        int type = Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = px(app, 14);
        p.y = px(app, 10);
        return p;
    }

    static void hide() {
        try { if (chip != null && chip.getParent() != null && wm != null) wm.removeView(chip); } catch (Exception ignored) { }
    }

    private static int px(Context c, float v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    private KbdBatt() { }
}
