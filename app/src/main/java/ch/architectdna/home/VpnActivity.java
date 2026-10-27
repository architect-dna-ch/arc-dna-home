package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * VPN, ARC-DNA's face for Proton VPN: is the connection protected, since when, which IP and country
 * the internet sees. Connecting and disconnecting stays in Proton (it lets no other app do that),
 * so the buttons lead there, or to Android's VPN settings (always-on, kill switch).
 */
public class VpnActivity extends Activity {

    static final String PROTON = "ch.protonvpn.android";

    private final Handler main = new Handler(Looper.getMainLooper());
    private int bg = 0xFF0E0E0D, card = 0xFF1A1A18, line = 0xFF2C2C2A, ink = 0xFFF4F2EC, ink2 = 0xFFA5A5A0,
        ink3 = 0xFF6F6E69, rust = 0xFFD9472F, green = 0xFF2BD96B;
    private SharedPreferences prefs;
    private Shield shield;
    private TextView state, since, ip, where, org;
    private ConnectivityManager cm;
    private boolean on;
    private long lastLookup;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        themeColours();
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        prefs = getSharedPreferences("vpn", MODE_PRIVATE);
        cm = getSystemService(ConnectivityManager.class);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(16), dp(22), dp(22));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dash = new View(this);
        dash.setBackgroundColor(rust);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(24), dp(2));
        dl.rightMargin = dp(12);
        head.addView(dash, dl);
        TextView name = mono(11, ink3);
        name.setLetterSpacing(0.24f);
        name.setText("VPN · ÜBER PROTON");
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        shield = new Shield();
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(240));
        sl.topMargin = dp(18);
        col.addView(shield, sl);

        state = text(26, ink, true);
        state.setGravity(Gravity.CENTER);
        col.addView(state, new LinearLayout.LayoutParams(-1, -2));
        since = mono(11, ink3);
        since.setGravity(Gravity.CENTER);
        since.setPadding(0, dp(6), 0, 0);
        col.addView(since, new LinearLayout.LayoutParams(-1, -2));

        // What the internet sees.
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackground(box(card, line, 16));
        info.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = dp(22);
        col.addView(info, il);
        TextView ih = mono(10, ink3);
        ih.setText("SO SIEHT DICH DAS INTERNET");
        info.addView(ih);
        where = text(20, ink, true);
        where.setPadding(0, dp(8), 0, 0);
        info.addView(where);
        ip = mono(13, ink2);
        ip.setPadding(0, dp(4), 0, 0);
        info.addView(ip);
        org = text(13, ink3, false);
        org.setSingleLine(true);
        org.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(org);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(20);
        col.addView(actions, al);
        TextView proton = chip("PROTON ÖFFNEN ↗", rust, true);
        proton.setOnClickListener(v -> openProton());
        actions.addView(proton);
        TextView sys = chip("VPN-EINSTELLUNGEN", ink2, false);
        sys.setOnClickListener(v -> open(new Intent(Settings.ACTION_VPN_SETTINGS)));
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(-2, -2);
        xl.leftMargin = dp(8);
        actions.addView(sys, xl);

        TextView again = mono(10, ink3);
        again.setText("IP NEU PRÜFEN ↻");
        again.setGravity(Gravity.CENTER);
        again.setPadding(0, dp(18), 0, 0);
        again.setOnClickListener(v -> lookup(true));
        col.addView(again, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text(12, ink3, false);
        note.setText("Verbinden und Trennen macht Proton – es lässt das keine andere App tun. "
            + "Ein eigenes ARC-DNA-VPN mit deinen Proton-Servern kommt als eigene App.");
        note.setPadding(0, dp(20), 0, 0);
        col.addView(note);

        setContentView(root);
        barIcons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
        }
        refresh();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            cm.unregisterNetworkCallback(netCallback);
        } catch (RuntimeException ignored) {
        }
        main.removeCallbacks(tick);
    }

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            main.post(() -> refresh());
        }

        @Override
        public void onLost(Network n) {
            main.post(() -> refresh());
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            paintSince();
            shield.invalidate();
            main.postDelayed(this, 1000);
        }
    };

    /** A VPN carries the traffic? Then protected (and since when). */
    static boolean vpnOn(ConnectivityManager cm) {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private void refresh() {
        boolean was = on;
        on = vpnOn(cm);
        if (on && prefs.getLong("since", 0) == 0) prefs.edit().putLong("since", System.currentTimeMillis()).apply();
        if (!on) prefs.edit().remove("since").apply();
        state.setText(on ? "Geschützt" : "Nicht geschützt");
        state.setTextColor(on ? green : rust);
        paintSince();
        shield.invalidate();
        if (was != on || SystemClock.uptimeMillis() - lastLookup > 60_000) lookup(false);
    }

    private void paintSince() {
        long s = prefs.getLong("since", 0);
        if (!on) {
            since.setText(installed() ? "PROTON ÖFFNEN UND VERBINDEN" : "PROTON VPN IST NICHT INSTALLIERT");
            return;
        }
        long m = (System.currentTimeMillis() - s) / 60_000;
        since.setText(("VPN AKTIV" + (installed() ? " · PROTON" : "") + " · SEIT " + (m >= 60 ? m / 60 + " H " + m % 60 + " MIN" : m + " MIN")));
    }

    /** Public IP and its place, from ipinfo.io (only this one request, only when asked or the state changes). */
    private void lookup(boolean manual) {
        lastLookup = SystemClock.uptimeMillis();
        if (manual) ip.setText("…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://ipinfo.io/json").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/json");
                String body;
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) != -1; ) bo.write(buf, 0, n);
                    body = bo.toString("UTF-8");
                }
                JSONObject o = new JSONObject(body);
                String cc = o.optString("country", ""), city = o.optString("city", ""), addr = o.optString("ip", "–");
                String place = (flag(cc) + " " + (city.isEmpty() ? "" : city + ", ") + countryName(cc)).trim();
                String isp = o.optString("org", "").replaceFirst("^AS\\d+\\s*", "");
                main.post(() -> {
                    where.setText(place.isEmpty() ? "Unbekannt" : place);
                    ip.setText(addr);
                    org.setText(isp);
                });
            } catch (Exception e) {
                main.post(() -> {
                    where.setText("Kein Internet");
                    ip.setText("–");
                    org.setText("");
                });
            }
        }).start();
    }

    private static String flag(String cc) {
        if (cc == null || cc.length() != 2) return "🌐";
        int a = Character.codePointAt(cc.toUpperCase(Locale.ROOT), 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(cc.toUpperCase(Locale.ROOT), 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    private static String countryName(String cc) {
        if (cc == null || cc.isEmpty()) return "";
        return new Locale("", cc).getDisplayCountry(Locale.GERMAN);
    }

    private boolean installed() {
        try {
            getPackageManager().getPackageInfo(PROTON, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openProton() {
        Intent i = getPackageManager().getLaunchIntentForPackage(PROTON);
        if (i != null) open(i);
        else open(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + PROTON)));
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    /** The shield: a ring that glows green and slowly breathes when protected, rust and open when not. */
    private final class Shield extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Shield() {
            super(VpnActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) * 0.36f;
            float t = SystemClock.uptimeMillis() / 1000f, breathe = 1 + 0.04f * (float) Math.sin(t * 1.6f);
            int col = on ? green : rust;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.RadialGradient(cx, cy, r * 1.6f, new int[]{(col & 0x00FFFFFF) | 0x44000000, 0x00000000},
                null, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 1.6f * breathe, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(10));
            p.setColor(line);
            c.drawCircle(cx, cy, r, p);
            p.setColor(col);
            android.graphics.RectF o = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
            if (on) c.drawArc(o, -90, 360, false, p);
            else c.drawArc(o, -60, 300, false, p);           // open ring: the gap is the unprotected way out
            // Shield symbol.
            android.graphics.Path s = new android.graphics.Path();
            float u = r / 50f;
            s.moveTo(cx, cy - 26 * u);
            s.lineTo(cx + 20 * u, cy - 18 * u);
            s.quadTo(cx + 20 * u, cy + 14 * u, cx, cy + 28 * u);
            s.quadTo(cx - 20 * u, cy + 14 * u, cx - 20 * u, cy - 18 * u);
            s.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(card);
            c.drawPath(s, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(col);
            c.drawPath(s, p);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(18));
            c.drawText(on ? "✓" : "!", cx, cy + dp(7), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private TextView mono(float sp, int colour) {
        TextView t = text(sp, colour, false);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.12f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView chip(String s, int colour, boolean filled) {
        TextView c = mono(11, filled ? 0xFFFFFFFF : colour);
        c.setText(s);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(11), dp(16), dp(11));
        c.setBackground(box(filled ? colour : card, colour, 22));
        return c;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
    /** Day or night like the system (they were always dark). */
    private void themeColours() {
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!night) {
            bg = 0xFFFAFAF8;
            card = 0xFFFFFFFF;
            line = 0xFFE0E0D8;
            ink = 0xFF1A1A1A;
            ink2 = 0xFF5A5A58;
            ink3 = 0xFF9A9A98;
            rust = 0xFFB5271E;
            green = 0xFF1A9A4C;
        }
    }

    /** Dark bar icons on the light day background (after setContentView). */
    private void barIcons() {
        if (Build.VERSION.SDK_INT < 30 || getWindow().getInsetsController() == null) return;
        boolean light = bg != 0xFF0E0E0D && bg != 0xFF141413;
        int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(light ? bits : 0, bits);
    }

}
