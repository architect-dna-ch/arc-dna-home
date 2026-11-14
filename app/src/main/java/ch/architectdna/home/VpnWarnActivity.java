package ch.architectdna.home;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ARC·DNA VPN guard. When Tor Browser comes to the front and no VPN is connected, the phone
 * "rings": a full-screen, incoming-call style warning plus a notification. Answer = open Proton VPN
 * (or Android's VPN settings); decline = snooze for 10 minutes. The screen closes by itself the
 * moment a VPN comes up.
 *
 * Triggered from ScrollAssistService (the accessibility service already knows which app is in
 * front). Tor itself encrypts the browser's traffic; without a VPN the network still sees that
 * you use Tor, and other apps' traffic is not covered.
 */
public class VpnWarnActivity extends Activity {

    private static final String CHANNEL = "vpnwarn";
    private static final int NOTE_ID = 7301;
    private static final long SNOOZE_MS = 10 * 60_000L, REPEAT_GAP_MS = 2 * 60_000L;
    private static long lastWarnAt;

    /** Apps that should never run without a VPN. */
    static boolean guarded(String pkg) {
        switch (pkg) {
            case "org.torproject.torbrowser":
            case "org.torproject.torbrowser_alpha":
            case "info.guardianproject.orfox":
                return true;
            default:
                return false;
        }
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

    /** Called whenever an app comes to the front. Cheap when nothing is wrong. */
    static void check(Context ctx, String pkg) {
        if (!guarded(pkg)) return;
        SharedPreferences prefs = ctx.getSharedPreferences("home", MODE_PRIVATE);
        if (!prefs.getBoolean("vpnGuard", true)) return;
        if (System.currentTimeMillis() < prefs.getLong("vpnWarnSnoozeUntil", 0)) return;
        if (SystemClock.uptimeMillis() - lastWarnAt < REPEAT_GAP_MS && lastWarnAt != 0) return;
        if (vpnOn(ctx)) return;
        lastWarnAt = SystemClock.uptimeMillis();
        DiagLog.log("vpn guard: " + pkg + " in front without VPN");
        Intent ring = new Intent(ctx, VpnWarnActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        notifyWarning(ctx, ring);
        try { ctx.startActivity(ring); } catch (RuntimeException ignored) { }   // overlay permission lets us start from the background
    }

    private static void notifyWarning(Context ctx, Intent ring) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "VPN-Warnung", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Warnt, wenn Tor Browser ohne VPN läuft.");
                nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, ring, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Ungeschützt: kein VPN aktiv")
                .setContentText("Tor Browser ist offen, aber die VPN-Verbindung steht nicht.")
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build();
            nm.notify(NOTE_ID, n);
        } catch (RuntimeException ignored) { }   // no notification permission: the screen still rings
    }

    private static void cancelNote(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTE_ID);
    }

    // ================= the "incoming call" screen =================

    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback watch;
    private Vibrator vib;
    private Pulse pulse;
    private TextView title, sub;
    private boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        int bg = 0xFF0E0B0A, red = 0xFFE5482F, green = 0xFF2BD96B, ink = 0xFFF4F2EC, ink2 = 0xFFA5A5A0;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(28), dp(70), dp(28), dp(56));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView caller = mono("ARC·DNA VPN · EINGEHEND", red, 12);
        caller.setLetterSpacing(0.22f);
        col.addView(caller);

        pulse = new Pulse(red);
        col.addView(pulse, lp(dp(230), dp(230), dp(34)));

        title = new TextView(this);
        title.setText("Ungeschützte Verbindung");
        title.setTextColor(ink);
        title.setTextSize(30);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        col.addView(title, lp(-1, -2, dp(28)));

        sub = new TextView(this);
        sub.setText("Tor Browser ist offen, aber kein VPN ist verbunden. Dein Netz sieht, dass du Tor nutzt, und andere Apps laufen unverschlüsselt am VPN vorbei.");
        sub.setTextColor(ink2);
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(0, 1.2f);
        col.addView(sub, lp(-1, -2, dp(12)));

        col.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(callButton("✕", "Ignorieren\n10 Min", red, v -> snooze()));
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(70), 1));
        buttons.addView(callButton("🛡", "VPN\nverbinden", green, v -> openVpn()));
        col.addView(buttons);

        setContentView(root);

        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        ring();

        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        watch = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) main.post(() -> protectedNow());
            }
        };
        try { cm.registerDefaultNetworkCallback(watch); } catch (RuntimeException ignored) { watch = null; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vpnOn(this)) protectedNow();   // came back from Proton with the VPN up
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRing();
        if (watch != null) try { cm.unregisterNetworkCallback(watch); } catch (RuntimeException ignored) { }
    }

    @Override
    public void onBackPressed() {
        snooze();
    }

    private void ring() {
        if (vib == null || !vib.hasVibrator()) return;
        long[] pattern = {0, 700, 500, 700, 1400};   // ring-ring … pause, like a call
        try { vib.vibrate(VibrationEffect.createWaveform(pattern, 0)); } catch (RuntimeException ignored) { }
        main.postDelayed(this::stopRing, 30_000);    // don't buzz forever
    }

    private void stopRing() {
        if (vib != null) try { vib.cancel(); } catch (RuntimeException ignored) { }
    }

    private void snooze() {
        getSharedPreferences("home", MODE_PRIVATE).edit()
            .putLong("vpnWarnSnoozeUntil", System.currentTimeMillis() + SNOOZE_MS).apply();
        cancelNote(this);
        finish();
    }

    private void openVpn() {
        stopRing();
        Intent proton = getPackageManager().getLaunchIntentForPackage(VpnActivity.PROTON);
        try {
            startActivity(proton != null ? proton : new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); } catch (RuntimeException ignored) { }
        }
    }

    private void protectedNow() {
        if (done) return;
        done = true;
        stopRing();
        cancelNote(this);
        pulse.setColour(0xFF2BD96B);
        title.setText("Geschützt ✓");
        sub.setText("Das VPN steht. Weiter geht's.");
        main.postDelayed(this::finish, 1400);
    }

    // ================= small views =================

    /** A shield with expanding rings, like a ringing phone. */
    private final class Pulse extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int colour;
        private final long start = SystemClock.uptimeMillis();

        Pulse(int colour) { super(VpnWarnActivity.this); this.colour = colour; }

        void setColour(int c) { colour = c; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy);
            float t = ((SystemClock.uptimeMillis() - start) % 1800) / 1800f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            for (int i = 0; i < 3; i++) {
                float f = (t + i / 3f) % 1f;
                p.setColor(colour);
                p.setAlpha((int) (180 * (1 - f)));
                c.drawCircle(cx, cy, max * (0.42f + 0.58f * f), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(colour);
            p.setAlpha(255);
            c.drawCircle(cx, cy, max * 0.4f, p);
            // shield glyph
            android.graphics.Path s = new android.graphics.Path();
            float r = max * 0.2f;
            s.moveTo(cx, cy - r * 1.15f);
            s.lineTo(cx + r, cy - r * 0.7f);
            s.quadTo(cx + r, cy + r * 0.6f, cx, cy + r * 1.2f);
            s.quadTo(cx - r, cy + r * 0.6f, cx - r, cy - r * 0.7f);
            s.close();
            p.setColor(0xFF0E0B0A);
            c.drawPath(s, p);
            if (!done) postInvalidateOnAnimation();
        }
    }

    private View callButton(String glyph, String label, int colour, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView circle = new TextView(this);
        circle.setText(glyph);
        circle.setTextSize(28);
        circle.setTextColor(0xFFFFFFFF);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colour);
        circle.setBackground(g);
        circle.setOnClickListener(l);
        b.addView(circle, new LinearLayout.LayoutParams(dp(78), dp(78)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFF4F2EC);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        b.addView(t, lp(-2, -2, dp(10)));
        return b;
    }

    private TextView mono(String s, int colour, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        return p;
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
