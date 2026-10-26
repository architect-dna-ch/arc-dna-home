package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Energie: drops down over whatever is open like a control centre. Battery now (level, charging or
 * time left, temperature, watts), quick system switches (energy saver, Wi-Fi, internet, Bluetooth,
 * torch, volume, do-not-disturb, display), a brightness slider, the last 24 hours as a curve, and
 * from ARC-DNA's own battery diary: what really drains — per app, music, and per signal.
 */
public class EnergyActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final int bg = 0xFF141413, card = 0xFF1E1E1C, line = 0xFF2E2E2B, ink = 0xFFF4F2EC, ink2 = 0xFFA5A5A0,
        ink3 = 0xFF6F6E69, rust = 0xFFD9472F, green = 0xFF2BD96B, amber = 0xFFE8B931;
    private FrameLayout root;
    private LinearLayout panel;
    private TextView big, state, details, saverState, torchState;
    private Curve curve;
    private LinearLayout drains;
    private boolean torchOn;
    private String torchId;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        overridePendingTransition(0, 0);
        getWindow().setBackgroundDrawable(new ColorDrawable(0));
        root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> close());
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(false);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(bg);
        float r = dp(28);
        pb.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        panel.setBackground(pb);
        panel.setPadding(dp(20), dp(44), dp(20), dp(18));
        // Below the status bar (and the camera cut-out), whatever its height.
        panel.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            v.setPadding(dp(20), top + dp(14), dp(20), dp(18));
            return insets;
        });
        sv.addView(panel);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Battery now.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.BOTTOM);
        big = text(56, ink, false);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        top.addView(big);
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(14), 0, 0, dp(10));
        state = mono(12, green);
        side.addView(state);
        details = mono(10, ink3);
        details.setSingleLine(false);
        side.addView(details);
        top.addView(side, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = text(20, ink, true);
        x.setText("✕");
        x.setPadding(dp(12), 0, dp(4), dp(18));
        x.setOnClickListener(v -> close());
        top.addView(x);
        panel.addView(top);

        // Switches.
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = dp(14);
        panel.addView(grid, gl);
        saverState = tile(grid, "🔋", "Sparen", v -> open(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)));
        tile(grid, "📶", "WLAN", v -> panelOr(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : null, Settings.ACTION_WIFI_SETTINGS));
        tile(grid, "🌐", "Internet", v -> panelOr(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : null, Settings.ACTION_WIRELESS_SETTINGS));
        tile(grid, "ᛒ", "Bluetooth", v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        torchState = tile(grid, "🔦", "Lampe", v -> torch());
        tile(grid, "🔊", "Laut", v -> panelOr(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_VOLUME : null, Settings.ACTION_SOUND_SETTINGS));
        tile(grid, "🌙", "Ruhe", v -> open(new Intent("android.settings.ZEN_MODE_SETTINGS")));
        tile(grid, "📊", "System", v -> open(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));

        // Brightness.
        LinearLayout bl = new LinearLayout(this);
        bl.setGravity(Gravity.CENTER_VERTICAL);
        bl.setPadding(dp(4), dp(14), 0, 0);
        TextView sun = text(18, ink2, false);
        sun.setText("☀");
        bl.addView(sun);
        SeekBar bright = new SeekBar(this);
        bright.setMax(255);
        try {
            bright.setProgress(Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS));
        } catch (Settings.SettingNotFoundException ignored) {
        }
        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (!user) return;
                if (!Settings.System.canWrite(EnergyActivity.this)) {
                    open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
                    return;
                }
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        bl.addView(bright, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(bl);

        // Last 24 h.
        panel.addView(head("Letzte 24 Stunden"));
        curve = new Curve();
        curve.setBackground(box(card, line, 16));
        panel.addView(curve, new LinearLayout.LayoutParams(-1, dp(110)));

        // What drains.
        panel.addView(head("Was zieht Akku"));
        drains = new LinearLayout(this);
        drains.setOrientation(LinearLayout.VERTICAL);
        panel.addView(drains);

        setContentView(root);
        panel.setTranslationY(-dp(500));
        panel.animate().translationY(0).setDuration(320).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        findTorch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private void close() {
        panel.animate().translationY(-panel.getHeight()).setDuration(220).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
        root.animate().alpha(0f).setDuration(220).start();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            paintNow();
            main.postDelayed(this, 2000);
        }
    };

    private void refresh() {
        paintNow();
        new Thread(() -> {
            List<BatteryLog.Sample> all = BatteryLog.read(this);
            List<List<BatteryLog.Drain>> d = BatteryLog.analyse(all, this::label);
            runOnUiThread(() -> {
                curve.set(all);
                paintDrains(d, all);
            });
        }).start();
    }

    /** Level, charging / time left, temperature, watts, saver. */
    private void paintNow() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int lvl = Math.round(b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100f / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)));
        boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        float temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        BatteryManager bm = getSystemService(BatteryManager.class);
        long ua = bm == null ? 0 : bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        // Some phones report mA, most µA.
        double ma = Math.abs(ua) > 20_000 ? Math.abs(ua) / 1000.0 : Math.abs(ua);
        double watts = ma * mv / 1_000_000.0;
        big.setText(lvl + "%");
        big.setTextColor(lvl <= 15 && !plugged ? rust : ink);
        String st;
        if (plugged) {
            long left = Build.VERSION.SDK_INT >= 28 && bm != null ? bm.computeChargeTimeRemaining() : -1;
            st = "⚡ LÄDT" + (left > 0 ? " · VOLL IN " + hm(left) : "");
        } else {
            double rate = recentRate();
            st = rate > 0.2 ? "NOCH CA. " + hm((long) (lvl / rate * 3_600_000)) : "ENTLÄDT";
        }
        state.setText(st);
        state.setTextColor(plugged ? green : lvl <= 15 ? rust : ink2);
        details.setText(String.format(Locale.GERMAN, "%.1f °C · %.1f W · %d mA", temp, watts, Math.round(ma)));
        PowerManager pm = getSystemService(PowerManager.class);
        boolean saver = pm != null && pm.isPowerSaveMode();
        saverState.setTextColor(saver ? amber : ink2);
        saverState.setText(saver ? "SPAREN AN" : "SPAREN");
        torchState.setTextColor(torchOn ? amber : ink2);
    }

    private double lastRate = -1;

    /** % per hour over the last 2 h unplugged (from the diary). */
    private double recentRate() {
        if (lastRate >= 0) return lastRate;
        List<BatteryLog.Sample> all = BatteryLog.read(this);
        long since = System.currentTimeMillis() - 2 * 3600_000L;
        BatteryLog.Sample first = null, last = null;
        for (BatteryLog.Sample s : all) {
            if (s.time < since || s.plugged) continue;
            if (first == null) first = s;
            last = s;
        }
        lastRate = first == null || last == first ? 0 : Math.max(0, first.level - last.level) / ((last.time - first.time) / 3_600_000.0);
        return lastRate;
    }

    private void paintDrains(List<List<BatteryLog.Drain>> d, List<BatteryLog.Sample> all) {
        drains.removeAllViews();
        if (all.size() < 4) {
            TextView t = text(14, ink3, false);
            t.setText("ARC-DNA schreibt ab jetzt bei jedem Prozent mit, was gerade läuft. Nach ein paar Stunden steht hier, "
                + "was am meisten Akku zieht: Apps, Musik, schwaches Netz.");
            drains.addView(t);
            return;
        }
        String[] heads = {"APPS & BILDSCHIRM", "MUSIK", "NETZ & SIGNAL"};
        for (int k = 0; k < 3; k++) {
            List<BatteryLog.Drain> list = d.get(k);
            if (list.isEmpty()) continue;
            TextView h = mono(10, ink3);
            h.setText(heads[k]);
            h.setPadding(0, dp(k == 0 ? 2 : 14), 0, dp(6));
            drains.addView(h);
            double max = 0;
            for (BatteryLog.Drain x : list) max = Math.max(max, x.rate());
            int n = 0;
            for (BatteryLog.Drain x : list) {
                if (n++ >= (k == 0 ? 6 : 4)) break;
                drains.addView(drainRow(x, max, n == 1 && x.rate() > 4));
            }
        }
    }

    private View drainRow(BatteryLog.Drain d, double max, boolean worst) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(8));
        r.setBackground(box(card, worst ? rust : line, 12));
        LinearLayout top = new LinearLayout(this);
        TextView n = text(14, ink, worst);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setText((worst ? "⚠ " : "") + d.key);
        top.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = mono(11, worst ? rust : ink2);
        v.setText(String.format(Locale.GERMAN, "%.1f %%/h · %s", d.rate(), hm((long) (d.hours * 3_600_000))));
        top.addView(v);
        r.addView(top);
        View bar = new View(this);
        bar.setBackgroundColor(worst ? rust : amber);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(3));
        blp.topMargin = dp(6);
        r.addView(bar, blp);
        r.post(() -> {
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            lp.width = (int) ((r.getWidth() - dp(24)) * (max > 0 ? d.rate() / max : 0));
            bar.setLayoutParams(lp);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String hm(long ms) {
        long m = ms / 60_000;
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }

    /* ---------------- switches ---------------- */

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    private void panelOr(String panelAction, String fallback) {
        if (panelAction != null) {
            try {
                startActivity(new Intent(panelAction));
                return;
            } catch (RuntimeException ignored) {
            }
        }
        open(new Intent(fallback));
    }

    private void findTorch() {
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    torchId = id;
                    break;
                }
            }
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean enabled) {
                    if (id.equals(torchId)) {
                        torchOn = enabled;
                        paintNow();
                    }
                }
            }, main);
        } catch (Exception ignored) {
        }
    }

    private void torch() {
        if (torchId == null) return;
        try {
            getSystemService(CameraManager.class).setTorchMode(torchId, !torchOn);
        } catch (Exception ignored) {
        }
    }

    /** A control-centre tile: round icon, label underneath; returns the label (to show state). */
    private TextView tile(GridLayout grid, String icon, String name, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, dp(8), 0, dp(8));
        TextView i = text(22, ink, false);
        i.setText(icon);
        i.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(card);
        g.setStroke(dp(1), line);
        i.setBackground(g);
        t.addView(i, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView n = mono(9, ink2);
        n.setText(name.toUpperCase(Locale.GERMAN));
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(6), 0, 0);
        t.addView(n, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(l);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        grid.addView(t, lp);
        return n;
    }

    /* ---------------- the 24 h curve ---------------- */

    private final class Curve extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private List<BatteryLog.Sample> list;

        Curve() {
            super(EnergyActivity.this);
        }

        void set(List<BatteryLog.Sample> l) {
            list = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = dp(12);
            long now = System.currentTimeMillis(), from = now - 24 * 3600_000L;
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(9));
            p.setColor(ink3);
            c.drawText("−24 H", pad, h - dp(4), p);
            c.drawText("JETZT", w - pad - p.measureText("JETZT"), h - dp(4), p);
            if (list == null || list.size() < 2) {
                c.drawText("NOCH KEINE DATEN", w / 2 - p.measureText("NOCH KEINE DATEN") / 2, h / 2, p);
                return;
            }
            float top = pad, bottom = h - dp(18);
            path.reset();
            boolean started = false;
            for (BatteryLog.Sample s : list) {
                if (s.time < from) continue;
                float x = pad + (w - 2 * pad) * (s.time - from) / (float) (now - from);
                float y = bottom - (bottom - top) * s.level / 100f;
                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
                }
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(green);
            c.drawPath(path, p);
            // Charging stretches as a thin band underneath.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x662BD96B);
            for (int i = 0; i + 1 < list.size(); i++) {
                BatteryLog.Sample a = list.get(i), b = list.get(i + 1);
                if (!a.plugged || b.time < from) continue;
                float x1 = pad + (w - 2 * pad) * (Math.max(a.time, from) - from) / (float) (now - from);
                float x2 = pad + (w - 2 * pad) * (b.time - from) / (float) (now - from);
                c.drawRect(x1, bottom + dp(2), x2, bottom + dp(5), p);
            }
        }
    }

    /* ---------------- helpers ---------------- */

    private View head(String s) {
        TextView t = mono(11, ink3);
        t.setLetterSpacing(0.22f);
        t.setText("— " + s.toUpperCase(Locale.GERMAN));
        t.setPadding(0, dp(20), 0, dp(8));
        return t;
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
        t.setLetterSpacing(0.1f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
}
