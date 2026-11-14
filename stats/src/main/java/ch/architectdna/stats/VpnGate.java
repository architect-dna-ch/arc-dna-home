package ch.architectdna.stats;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tor Browser without a VPN: warn before it opens. Works without any accessibility service. */
final class VpnGate {
    private VpnGate() { }

    static final String PROTON = "ch.protonvpn.android";

    static boolean guarded(String pkg) {
        return "org.torproject.torbrowser".equals(pkg)
            || "org.torproject.torbrowser_alpha".equals(pkg)
            || "info.guardianproject.orfox".equals(pkg);
    }

    static boolean vpnOn(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /** True when the launch was intercepted (dialog shown); the caller then does nothing. */
    static boolean intercept(Activity a, String pkg, Intent launch) {
        if (pkg == null || !guarded(pkg) || vpnOn(a)) return false;
        try {
            Vibrator v = (Vibrator) a.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 250, 400}, -1));
        } catch (RuntimeException ignored) { }

        float d = a.getResources().getDisplayMetrics().density;
        int bg = 0xFF0E0E0D, ink = 0xFFF4F2EC, ink2 = 0xFFA5A5A0, rust = 0xFFD9472F, line = 0xFF2C2C2A;
        Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (24 * d);
        col.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg); g.setCornerRadius(8 * d); g.setStroke((int) d, line);
        col.setBackground(g);

        TextView lab = tv(a, "— ARC·DNA VPN", rust, 11);
        lab.setTypeface(Typeface.MONOSPACE); lab.setLetterSpacing(0.24f);
        col.addView(lab);
        TextView t = tv(a, "Ungeschützte Verbindung", ink, 24);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setPadding(0, (int) (10 * d), 0, (int) (6 * d));
        col.addView(t);
        TextView s = tv(a, "Tor Browser startet, aber kein VPN ist verbunden. Dein Netz sieht, dass du Tor nutzt.", ink2, 15);
        s.setLineSpacing(0, 1.2f);
        col.addView(s);

        TextView vpn = btn(a, "🛡  VPN verbinden", 0xFFFFFFFF, rust, d);
        vpn.setOnClickListener(x -> {
            dlg.dismiss();
            Intent pi = a.getPackageManager().getLaunchIntentForPackage(PROTON);
            try { a.startActivity(pi != null ? pi : new Intent(Settings.ACTION_VPN_SETTINGS)); }
            catch (RuntimeException e) { try { a.startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); } catch (RuntimeException ignored) { } }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (50 * d));
        lp.topMargin = (int) (20 * d);
        col.addView(vpn, lp);
        TextView anyway = btn(a, "Trotzdem öffnen", ink2, 0, d);
        anyway.setOnClickListener(x -> {
            dlg.dismiss();
            try { a.startActivity(launch); } catch (RuntimeException ignored) { }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, (int) (46 * d));
        lp2.topMargin = (int) (6 * d);
        col.addView(anyway, lp2);

        dlg.setContentView(col);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            dlg.getWindow().setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.88f), -2);
            dlg.getWindow().setDimAmount(0.6f);
        }
        dlg.show();
        return true;
    }

    private static TextView tv(Context c, String s, int colour, float sp) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextColor(colour); t.setTextSize(sp);
        return t;
    }

    private static TextView btn(Context c, String s, int colour, int fill, float d) {
        TextView b = tv(c, s, colour, 15);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        if (fill != 0) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill); g.setCornerRadius(6 * d);
            b.setBackground(g);
        }
        return b;
    }
}
