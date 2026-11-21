package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static ch.architectdna.home.ArcUi.t;

/**
 * ARC Headphones: pick which device your Bluetooth headphones play to. Lists paired audio devices
 * (WH-CH520 and the like); tap one to connect it HERE (audio moves to this tablet) or disconnect it
 * (frees it for your phone/Mac). Uses the A2DP + Headset profiles; needs the Bluetooth permission.
 */
public class BtAudioActivity extends Activity {

    private ArcUi ui;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private BluetoothHeadset headset;
    private LinearLayout list;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        BluetoothManager m = getSystemService(BluetoothManager.class);
        adapter = m == null ? null : m.getAdapter();
        build();
        if (adapter != null) {
            adapter.getProfileProxy(this, proxy(BluetoothProfile.A2DP), BluetoothProfile.A2DP);
            adapter.getProfileProxy(this, proxy(BluetoothProfile.HEADSET), BluetoothProfile.HEADSET);
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 50);
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) { paint(); }

    private BluetoothProfile.ServiceListener proxy(int wanted) {
        return new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile p) {
                if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) p;
                if (profile == BluetoothProfile.HEADSET) headset = (BluetoothHeadset) p;
                paint();
            }
            @Override public void onServiceDisconnected(int profile) { }
        };
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.bg);
        sv.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(44));
        sv.addView(col);
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(ui.label("ARC " + t("Headphones", "Kopfhörer"), true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = ui.mono("✕", ui.ink2, 13);
        x.setOnClickListener(v -> finish());
        h.addView(x);
        col.addView(h);
        col.addView(ui.title(t("Send sound where\nyou want it.", "Ton dahin,\nwo du willst."), 36), ui.lp(-1, -2, ui.dp(18)));
        col.addView(ui.body(t("Tap your headphones to connect them here, or disconnect to free them for your phone or Mac. The WH-CH520 holds two devices at once — this picks which one plays.",
            "Tipp deine Kopfhörer an, um sie hierher zu holen, oder trenne sie, um sie fürs Handy oder den Mac freizugeben. Die WH-CH520 hält zwei Geräte gleichzeitig — das wählt, welches spielt."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(14)));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, ui.lp(-1, -2, ui.dp(8)));
        setContentView(sv);
        paint();
    }

    private void paint() {
        list.removeAllViews();
        if (adapter == null || !adapter.isEnabled()) {
            list.addView(ui.body(t("Bluetooth is off.", "Bluetooth ist aus."), ui.rust, 15), ui.lp(-1, -2, ui.dp(10)));
            return;
        }
        List<BluetoothDevice> audio = new ArrayList<>();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                if (major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) audio.add(d);
            }
        } catch (SecurityException e) {
            list.addView(ui.body(t("Allow the Bluetooth permission.", "Bluetooth-Berechtigung erlauben."), ui.rust, 15), ui.lp(-1, -2, ui.dp(10)));
            return;
        }
        if (audio.isEmpty())
            list.addView(ui.body(t("No paired headphones. Pair them in Bluetooth settings first.", "Keine gekoppelten Kopfhörer. Erst in den Bluetooth-Einstellungen koppeln."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(10)));
        for (BluetoothDevice d : audio) {
            boolean conn = isConnected(d);
            String name;
            try { name = d.getName() == null ? d.getAddress() : d.getName(); } catch (SecurityException e) { name = d.getAddress(); }
            android.view.View r = ui.row(conn ? "🎧" : "🔇", name,
                conn ? t("Connected here · tap to free it · hold → other device", "Hier verbunden · tippen zum Freigeben · halten → anderes Gerät")
                     : t("Not here · tap to bring it here · hold → other device", "Nicht hier · tippen zum Holen · halten → anderes Gerät"),
                v -> { toggle(d, conn); main.postDelayed(this::paint, 900); });
            r.setOnLongClickListener(v -> { sendToOther(d, conn); return true; });
            list.addView(r, ui.lp(-1, -2, ui.dp(8)));
        }
    }

    private boolean isConnected(BluetoothDevice d) {
        try {
            if (a2dp != null && a2dp.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) return true;
            if (headset != null && headset.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) return true;
        } catch (SecurityException ignored) { }
        return false;
    }

    /** Long-press: tell the paired ARC device (phone↔tablet) to grab or free these headphones on ITS
     *  side — it must already be paired with them. Runs over the ARC Remote link. */
    private void sendToOther(BluetoothDevice d, boolean connected) {
        final String mac = d.getAddress();
        Toast.makeText(this, t("Asking the other ARC device …", "Frage das andere ARC-Gerät …"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = BtRemote.sendAudio(this, !connected, mac);
            runOnUiThread(() -> Toast.makeText(this, ok
                ? t("Sent — the other device is grabbing it.", "Gesendet — das andere Gerät holt ihn.")
                : t("No other ARC device reachable (open ARC there).", "Kein anderes ARC-Gerät erreichbar (dort ARC öffnen)."), Toast.LENGTH_LONG).show());
        }, "arc-audio-send").start();
    }

    private void toggle(BluetoothDevice d, boolean connected) {
        String action = connected ? "disconnect" : "connect";
        boolean any = false;
        for (BluetoothProfile p : new BluetoothProfile[]{a2dp, headset}) {
            if (p == null) continue;
            try {
                Method mth = p.getClass().getMethod(action, BluetoothDevice.class);
                mth.setAccessible(true);
                Object r = mth.invoke(p, d);
                if (Boolean.TRUE.equals(r)) any = true;
            } catch (Exception ignored) { }
        }
        if (!any)
            Toast.makeText(this, t("This phone doesn't allow switching it from here — do it in Bluetooth settings.",
                "Dieses Gerät erlaubt das Umschalten hier nicht — mach es in den Bluetooth-Einstellungen."), Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) {
            if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            if (headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
        }
    }
}
