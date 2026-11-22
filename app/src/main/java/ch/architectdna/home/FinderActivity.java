package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static ch.architectdna.home.ArcUi.t;

/**
 * ARC Finder — a green radar to find your other ARC device (or a paired earbud) at home.
 * The reliable part: an ARC device (your tablet) can be rung — a loud alarm + buzz over the Bluetooth
 * link, so you can hear it. The radar shows the pick getting warmer/colder from Bluetooth signal when
 * Android lets us scan. Earbuds can't be made to beep (no API) — only proximity, and only while they're
 * still connected.
 */
public class FinderActivity extends Activity {

    private ArcUi ui;
    private RadarView radar;
    private TextView status, ringBtn;
    private LinearLayout chipRow;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;

    private String targetMac = "", targetName = "";
    private boolean targetIsArc = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        adapter = BluetoothAdapter.getDefaultAdapter();

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF07140C);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        col.setPadding(pad, dp(26), pad, dp(30));
        sv.addView(col);
        setContentView(sv);
        getWindow().setStatusBarColor(0xFF07140C);

        TextView eye = text(12, 0xFF34E07A, true); eye.setText("— ARC · FINDER"); eye.setLetterSpacing(0.22f);
        col.addView(eye);
        TextView h = text(30, 0xFFEAF7EE, true); h.setText(t("Find it.", "Finde es.")); h.setPadding(0, dp(6), 0, dp(4));
        col.addView(h);
        status = text(14, 0xFF8FB89E, false);
        status.setText(t("Pick a device, then ring it or walk toward the blip.", "Gerät wählen, dann klingeln oder dem Punkt folgen."));
        col.addView(status);

        radar = new RadarView(this);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, dp(300));
        rl.topMargin = dp(16);
        col.addView(radar, rl);

        ringBtn = new TextView(this);
        ringBtn.setText(t("📢  Ring it", "📢  Klingeln"));
        ringBtn.setTextColor(0xFF07140C);
        ringBtn.setTextSize(16);
        ringBtn.setTypeface(ringBtn.getTypeface(), android.graphics.Typeface.BOLD);
        ringBtn.setGravity(Gravity.CENTER);
        ringBtn.setBackground(pill(0xFF34E07A));
        ringBtn.setPadding(0, dp(15), 0, dp(15));
        ringBtn.setOnClickListener(v -> ring());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(16);
        col.addView(ringBtn, bl);

        TextView lbl = text(11, 0xFF6E9078, true); lbl.setText(t("YOUR DEVICES", "DEINE GERÄTE")); lbl.setLetterSpacing(0.14f); lbl.setPadding(0, dp(20), 0, dp(4));
        col.addView(lbl);
        chipRow = new LinearLayout(this); chipRow.setOrientation(LinearLayout.VERTICAL);
        col.addView(chipRow);

        TextView note = text(12, 0xFF5F8169, false);
        note.setText(t("Earbuds can't be rung by a phone — only shown closer/farther while connected.",
            "Kopfhörer lassen sich vom Handy nicht klingeln — nur näher/weiter, solange verbunden."));
        note.setPadding(0, dp(18), 0, 0);
        col.addView(note);

        ensurePerms();
        buildDevices();
    }

    /* ---------- devices ---------- */
    private void buildDevices() {
        chipRow.removeAllViews();
        List<BluetoothDevice> arc = new ArrayList<>(), audio = new ArrayList<>();
        try {
            if (adapter != null) for (BluetoothDevice d : adapter.getBondedDevices()) {
                int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                if (major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.COMPUTER) arc.add(d);
                else if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) audio.add(d);
            }
        } catch (SecurityException e) {
            TextView warn = text(14, 0xFFE3B341, false);
            warn.setText(t("Allow the Bluetooth permission to see your devices.", "Bluetooth-Berechtigung erlauben, um Geräte zu sehen."));
            chipRow.addView(warn); return;
        }
        for (BluetoothDevice d : arc) chipRow.addView(deviceChip(d, true));
        for (BluetoothDevice d : audio) chipRow.addView(deviceChip(d, false));
        if (arc.isEmpty() && audio.isEmpty()) {
            TextView none = text(14, 0xFF8FB89E, false);
            none.setText(t("No paired devices. Pair your tablet / earbuds in Bluetooth settings.", "Keine gekoppelten Geräte. Tablet/Kopfhörer erst in den Bluetooth-Einstellungen koppeln."));
            chipRow.addView(none);
        }
        if (!arc.isEmpty()) select(arc.get(0), true);         // default: the tablet (an ARC device)
        else if (!audio.isEmpty()) select(audio.get(0), false);
    }

    private View deviceChip(final BluetoothDevice d, final boolean isArc) {
        String name;
        try { name = d.getName() == null ? d.getAddress() : d.getName(); } catch (SecurityException e) { name = d.getAddress(); }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(13), dp(14), dp(13));
        r.setBackground(pillStroke(d.getAddress().equals(targetMac)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(9);
        r.setLayoutParams(lp);
        TextView ic = text(18, 0xFFEAF7EE, false); ic.setText(isArc ? "📟" : "🎧"); ic.setPadding(0, 0, dp(12), 0);
        r.addView(ic);
        LinearLayout tc = new LinearLayout(this); tc.setOrientation(LinearLayout.VERTICAL);
        TextView nm = text(15, 0xFFEAF7EE, true); nm.setText(name); tc.addView(nm);
        TextView sub = text(11, 0xFF6E9078, false); sub.setText(isArc ? t("ARC device · can be rung", "ARC-Gerät · klingelbar") : t("earbud / speaker · proximity only", "Kopfhörer / Box · nur Nähe")); tc.addView(sub);
        r.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));
        final String nmF = name;
        r.setOnClickListener(v -> { select(d, isArc); nm2(nmF); buildDevices(); });
        return r;
    }
    private void nm2(String n){ /* keep name for status */ }

    private void select(BluetoothDevice d, boolean isArc) {
        targetMac = d.getAddress();
        try { targetName = d.getName() == null ? targetMac : d.getName(); } catch (SecurityException e) { targetName = targetMac; }
        targetIsArc = isArc;
        radar.setTarget(targetMac);
        ringBtn.setVisibility(isArc ? View.VISIBLE : View.GONE);
        status.setText(isArc ? (targetName + t(" — ring it, listen, and watch the blip.", " — klingeln, hinhören, dem Punkt folgen."))
                             : (targetName + t(" — walk around; the blip warms as you near it.", " — herumgehen; der Punkt wird wärmer, je näher.")));
    }

    /* ---------- ring an ARC device ---------- */
    private void ring() {
        if (!targetIsArc) return;
        Toast.makeText(this, t("Ringing your ARC device …", "ARC-Gerät klingelt …"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = BtRemote.sendFind(this, targetMac);
            main.post(() -> Toast.makeText(this, ok ? t("It should be ringing — follow the sound.", "Es klingelt – folge dem Ton.")
                : t("Couldn't reach it (is ARC open on the tablet / in range?).", "Nicht erreichbar (ist ARC auf dem Tablet offen / in Reichweite?)."), Toast.LENGTH_LONG).show());
        }, "arc-find-ring").start();
    }

    /* ---------- bluetooth proximity (best effort) ---------- */
    private final BroadcastReceiver scan = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!BluetoothDevice.ACTION_FOUND.equals(i.getAction())) return;
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            short rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) -127);
            if (d != null && d.getAddress().equals(targetMac) && rssi > -127) radar.setSignal(rssi);
        }
    };
    private boolean scanning;

    private void ensurePerms() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!need.isEmpty()) try { requestPermissions(need.toArray(new String[0]), 9); } catch (Exception ignored) { }
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        buildDevices(); startScan();
    }

    private void startScan() {
        if (adapter == null || scanning) return;
        try {
            registerReceiver(scan, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            scanning = adapter.startDiscovery();              // repeats below; needs BLUETOOTH_SCAN
        } catch (SecurityException e) {
            status.setText(t("Allow Bluetooth scanning for the warmer/colder radar — ringing still works.",
                "Bluetooth-Suche erlauben für das Wärmer/Kälter-Radar — Klingeln geht trotzdem."));
        } catch (Exception ignored) { }
    }

    private final Runnable rescan = new Runnable() {
        @Override public void run() {
            try { if (adapter != null && !adapter.isDiscovering()) adapter.startDiscovery(); } catch (Exception ignored) { }
            main.postDelayed(this, 14000);
        }
    };

    @Override protected void onResume() { super.onResume(); startScan(); main.postDelayed(rescan, 14000); }
    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(rescan);
        try { unregisterReceiver(scan); } catch (Exception ignored) { }
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception ignored) { }
        scanning = false;
    }

    /* ---------- helpers ---------- */
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }
    private android.graphics.drawable.GradientDrawable pill(int c) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(c); g.setCornerRadius(dp(14)); return g;
    }
    private android.graphics.drawable.GradientDrawable pillStroke(boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(on ? 0xFF103A22 : 0xFF0C2415); g.setCornerRadius(dp(14)); g.setStroke(Math.max(1, dp(on ? 2 : 1)), on ? 0xFF34E07A : 0xFF1C3D29); return g;
    }

    /* ================= the radar ================= */
    static final class RadarView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), sweepP = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final int GREEN = 0xFF34E07A;
        private float sweep = 0, blipR = 0.62f, targetR = 0.62f, blipAng = 60;
        private boolean haveSignal = false;
        private int lastRssi = -127;

        RadarView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(2); ring.setColor(0x3334E07A);
            line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(1.5f); line.setColor(0x2634E07A);
            dot.setColor(GREEN);
            txt.setColor(0xFF8FB89E); txt.setTextAlign(Paint.Align.CENTER);
        }

        void setTarget(String mac) {
            int h = Math.abs(mac == null ? 0 : mac.hashCode());
            blipAng = h % 360;
            haveSignal = false; targetR = 0.62f; lastRssi = -127;
            invalidate();
        }
        void setSignal(int rssi) {
            lastRssi = rssi;
            float r = (-rssi - 45f) / 50f;         // -45dBm→center, -95dBm→edge
            targetR = Math.max(0.05f, Math.min(0.96f, r));
            haveSignal = true;
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f - 6;
            cv.drawColor(0xFF07140C);
            for (int i = 1; i <= 4; i++) cv.drawCircle(cx, cy, R * i / 4f, ring);
            cv.drawLine(cx - R, cy, cx + R, cy, line);
            cv.drawLine(cx, cy - R, cx, cy + R, line);
            // sweep wedge
            sweepP.setShader(new android.graphics.SweepGradient(cx, cy, new int[]{0x0034E07A, 0x0034E07A, 0x5534E07A, 0x0034E07A}, new float[]{0f, 0.72f, 0.97f, 1f}));
            cv.save(); cv.rotate(sweep, cx, cy);
            cv.drawCircle(cx, cy, R, sweepP);
            cv.restore();
            // ease blip toward target radius
            blipR += (targetR - blipR) * 0.08f;
            double a = Math.toRadians(blipAng);
            float bx = cx + (float) Math.cos(a) * R * blipR, by = cy + (float) Math.sin(a) * R * blipR;
            // blip only lights strongly as the sweep passes it (radar feel), always faintly visible
            float da = Math.abs(((sweep - blipAng) % 360 + 360) % 360);
            float near = da < 40 ? 1 - da / 40f : (da > 320 ? (da - 320) / 40f : 0.18f);
            int glow = (int) (90 + 150 * near);
            dot.setShader(new RadialGradient(bx, by, dp(22), (glow << 24) | 0x34E07A, 0x0034E07A, Shader.TileMode.CLAMP));
            cv.drawCircle(bx, by, dp(22), dot);
            dot.setShader(null);
            dot.setColor((0xFF << 24) | 0x34E07A);
            cv.drawCircle(bx, by, dp(5.5f), dot);
            // center = you
            dot.setColor(0xFFEAF7EE); cv.drawCircle(cx, cy, dp(4.5f), dot);
            // label
            txt.setTextSize(dp(12));
            txt.setColor(haveSignal ? GREEN : 0xFF5F8169);
            cv.drawText(haveSignal ? (warm(blipR) + "  ·  " + lastRssi + " dBm") : labelSearching(), cx, h - dp(8), txt);
            sweep = (sweep + 2.6f) % 360;
            if (Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation(); else invalidate();
        }
        private String labelSearching() { return "· · ·"; }
        private String warm(float r) {
            if (r < 0.22f) return "SEHR NAH";
            if (r < 0.42f) return "NAH";
            if (r < 0.66f) return "IN DER NÄHE";
            return "WEIT";
        }
        private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    }
}
