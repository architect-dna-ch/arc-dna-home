package ch.architectdna.home;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * ARC Flow — one screen for "the music follows the device in my hand".
 *
 * The reliable part is the button: tap it on the device you're holding and whatever is playing on the
 * paired ARC device pauses there and continues here (best-effort position). Auto-Flow adds the magic —
 * a pickup on this device does the same on its own via the accelerometer — but that's a heuristic, so
 * it's a toggle, off by default, with the button always there as the sure way.
 */
public class FlowActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private Switch autoSw, syncSw;
    private volatile boolean busy;
    private boolean binding;            // true while we set the switch ourselves — not a user tap

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0A1016);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        col.setPadding(pad, dp(26), pad, dp(30));
        sv.addView(col);
        setContentView(sv);
        getWindow().setStatusBarColor(0xFF0A1016);

        TextView eye = text(12, 0xFF46C7FF, true); eye.setText("— ARC · FLOW"); eye.setLetterSpacing(0.22f);
        col.addView(eye);
        TextView h = text(30, 0xFFEAF2F8, true); h.setText(L.t("Die Musik folgt dir.")); h.setPadding(0, dp(6), 0, dp(4));
        col.addView(h);
        TextView sub = text(14, 0xFF93AEC2, false);
        sub.setText(L.t("Nimm das Gerät in die Hand, auf dem du weiterhören willst. Der Song springt vom anderen ARC-Gerät hierher – ungefähr an der Stelle, an der er war."));
        col.addView(sub);

        TextView pull = new TextView(this);
        pull.setText(L.t("🎧  Musik hierher holen"));
        pull.setTextColor(0xFF07121B);
        pull.setTextSize(16);
        pull.setTypeface(pull.getTypeface(), android.graphics.Typeface.BOLD);
        pull.setGravity(Gravity.CENTER);
        pull.setBackground(pill(0xFF46C7FF));
        pull.setPadding(0, dp(16), 0, dp(16));
        pull.setOnClickListener(v -> pullNow());
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.topMargin = dp(22);
        col.addView(pull, pl);

        status = text(13, 0xFF93AEC2, false);
        status.setPadding(0, dp(12), 0, 0);
        status.setText(L.t("Bereit. Auf dem anderen Gerät läuft die Musik – tipp hier, um sie zu übernehmen."));
        col.addView(status);

        // Auto-Flow toggle
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(card());
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(26);
        col.addView(row, rl);
        LinearLayout tc = new LinearLayout(this); tc.setOrientation(LinearLayout.VERTICAL);
        TextView tt = text(15, 0xFFEAF2F8, true); tt.setText(L.t("Automatisch übernehmen")); tc.addView(tt);
        TextView ts = text(12, 0xFF7E97AB, false);
        ts.setText(L.t("Beta: Sobald du dieses Gerät hochnimmst, holt ARC die Musik von selbst her."));
        tc.addView(ts);
        row.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));
        autoSw = new Switch(this);
        autoSw.setChecked(FlowService.isOn(this));
        autoSw.setOnCheckedChangeListener((v, on) -> {
            if (on && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                try { requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 11); } catch (Exception ignored) { }
            FlowService.setOn(this, on);
            status.setText(on ? L.t("Auto-Flow ist an. Das Gerät in deiner Hand bekommt die Musik.")
                              : L.t("Auto-Flow ist aus. Mit dem Knopf holst du die Musik jederzeit her."));
        });
        row.addView(autoSw);

        // ARC Sync toggle: same second on both devices, the one in your hand loud, the other a whisper
        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        srow.setBackground(card());
        srow.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, -2); srl.topMargin = dp(12);
        col.addView(srow, srl);
        LinearLayout sc = new LinearLayout(this); sc.setOrientation(LinearLayout.VERTICAL);
        TextView st = text(15, 0xFFEAF2F8, true); st.setText(L.t("Synchron auf beiden")); sc.addView(st);
        TextView ss = text(12, 0xFF7E97AB, false);
        ss.setText(L.t("Beta: Beide Geräte spielen dieselbe Sekunde. Das in deiner Hand ist laut, das andere ganz leise — gehst du rüber und nimmst es hoch, wird es laut. Hier starten, wo die Musik gerade läuft."));
        sc.addView(ss);
        srow.addView(sc, new LinearLayout.LayoutParams(0, -2, 1f));
        syncSw = new Switch(this);
        syncSw.setChecked(FlowService.isSync(this));
        syncSw.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            if (on) {
                if (!LocalMedia.isPlayingHere(this)) {
                    status.setText(L.t("Starte Sync auf dem Gerät, auf dem gerade Musik läuft."));
                    binding = true; v.setChecked(false); binding = false; return;
                }
                FlowService.startSync(this);
                status.setText(L.t("Sync an: Das andere Gerät spielt jetzt leise mit."));
            } else {
                FlowService.stopSync(this);
                status.setText(L.t("Sync aus. Beide Geräte wieder normal laut."));
            }
        });
        srow.addView(syncSw);

        TextView note = text(12, 0xFF6C879C, false);
        note.setText(L.t("Ehrlich dazu: Die Stelle im Song trifft ARC auf ein paar Sekunden genau – die Musik-App muss den Titel erst laden. Beide ARC-Geräte müssen gekoppelt und in Bluetooth-Reichweite sein, und auf dem Zielgerät spielt der Song über die dortige Musik-App (YouTube Music)."));
        note.setPadding(0, dp(22), 0, 0);
        col.addView(note);
    }

    private void pullNow() {
        if (busy) return;
        busy = true;
        status.setText(L.t("Verbinde mit dem anderen Gerät …"));
        // Watchdog: RFCOMM can be slow; tell the user instead of looking frozen.
        final Runnable watchdog = () -> { if (busy) status.setText(L.t("Keine Verbindung – ist ARC auf dem anderen Gerät offen und Bluetooth an?")); };
        main.postDelayed(watchdog, 9000);
        new Thread(() -> {
            String[] t = null;
            try { t = BtRemote.pullFromPeer(this); } catch (RuntimeException ignored) { }
            final String[] track = t;
            main.post(() -> {
                main.removeCallbacks(watchdog);
                if (track != null) {
                    LocalMedia.playHere(this, track[0], track[1], parse(track[2]));
                    status.setText(L.t("Läuft jetzt hier: ") + track[0]);
                } else {
                    status.setText(L.t("Nichts zu übernehmen – auf dem anderen Gerät läuft gerade nichts, oder es ist nicht in Reichweite."));
                }
                busy = false;
            });
        }, "arc-flow-manual").start();
    }

    @Override protected void onResume() {
        super.onResume();
        if (autoSw != null) autoSw.setChecked(FlowService.isOn(this));
        if (syncSw != null) { binding = true; syncSw.setChecked(FlowService.isSync(this)); binding = false; }
    }

    private static long parse(String s) { try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; } }

    /* helpers */
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }
    private android.graphics.drawable.GradientDrawable pill(int c) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(c); g.setCornerRadius(dp(14)); return g;
    }
    private android.graphics.drawable.GradientDrawable card() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF10202C); g.setCornerRadius(dp(14)); g.setStroke(Math.max(1, dp(1)), 0xFF1E3647); return g;
    }
}
