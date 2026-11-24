package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * ARC Move — your motion as raw numbers, as precise and as often as the phone can deliver them.
 *
 * GPS straight from the GNSS chip at its fastest rate (0 ms, 0 m), with the rate it actually achieves
 * in Hz: speed in m/s and km/h (with its accuracy), direction of travel (GPS bearing, 8-point: N, NO, O
 * …) and where the phone points (compass from the rotation sensor), altitude, horizontal accuracy and
 * satellites used. From the motion sensors: linear acceleration in m/s² (gravity removed) at the
 * sensor's fastest rate, with the peak. Trip: distance, max and average speed. Screen stays on,
 * numbers are big — a glance, not a stare.
 */
public class MoveActivity extends Activity implements SensorEventListener {

    private static final int BG = 0xFF07090B, CARD = 0xFF10151A, LINE = 0xFF1E2830, INK = 0xFFECF2F5,
        INK2 = 0xFF93A4B0, INK3 = 0xFF5B6B76, CYAN = 0xFF3FD0E0, LIME = 0xFFB6F04A, AMBER = 0xFFF2B544;
    private static final String[] DIRS = {"N", "NO", "O", "SO", "S", "SW", "W", "NW"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private SensorManager sm;
    private TextView speedBig, speedKmh, speedAcc, travelDir, facingDir, accBig, accPeak, hz, accHz,
        alt, acc, sats, dist, vmax, vavg, fixAge, status;
    private Compass compass;

    // GPS
    private Location last;
    private long lastFixElapsed;
    private final long[] fixTimes = new long[20];
    private int fixN;
    private int satsUsed, satsSeen;
    // trip
    private double tripM, maxV;
    private long tripStart;
    // sensors
    private final float[] rot = new float[9], orient = new float[3];
    private float azimuth = Float.NaN, linAcc, peak;
    private long accWindowStart; private int accCount; private float accRate;

    private final LocationListener gps = this::onFix;
    private GnssStatus.Callback gnss;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lm = getSystemService(LocationManager.class);
        sm = getSystemService(SensorManager.class);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(16), dp(18), dp(28));
        sv.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView eye = mono(11, CYAN); eye.setText("— ARC · MOVE"); eye.setLetterSpacing(0.22f);
        head.addView(eye, new LinearLayout.LayoutParams(0, -2, 1f));
        hz = mono(11, INK3); head.addView(hz);
        col.addView(head);

        // speed — the biggest number
        speedBig = num(84, INK);
        speedBig.setText("–");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.topMargin = dp(6);
        LinearLayout sp = new LinearLayout(this);
        sp.setGravity(Gravity.BOTTOM);
        sp.addView(speedBig);
        TextView unit = mono(18, INK2); unit.setText(" m/s"); unit.setPadding(0, 0, 0, dp(16));
        sp.addView(unit);
        col.addView(sp, bl);
        LinearLayout sr = new LinearLayout(this);
        speedKmh = mono(16, INK2);
        speedAcc = mono(13, INK3);
        sr.addView(speedKmh, new LinearLayout.LayoutParams(0, -2, 1f));
        sr.addView(speedAcc);
        col.addView(sr);

        // direction: compass + two big labels
        LinearLayout dirRow = new LinearLayout(this);
        dirRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dr = new LinearLayout.LayoutParams(-1, -2); dr.topMargin = dp(16);
        col.addView(dirRow, dr);
        compass = new Compass(this);
        dirRow.addView(compass, new LinearLayout.LayoutParams(dp(150), dp(150)));
        LinearLayout dc = new LinearLayout(this); dc.setOrientation(LinearLayout.VERTICAL);
        dc.setPadding(dp(16), 0, 0, 0);
        dc.addView(label("UNTERWEGS NACH"));
        travelDir = num(34, LIME); travelDir.setText("–"); dc.addView(travelDir);
        TextView gap = new TextView(this); gap.setHeight(dp(10)); dc.addView(gap);
        dc.addView(label("HANDY ZEIGT NACH"));
        facingDir = num(26, CYAN); facingDir.setText("–"); dc.addView(facingDir);
        dirRow.addView(dc, new LinearLayout.LayoutParams(0, -2, 1f));

        // acceleration
        LinearLayout ac = card();
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2); al.topMargin = dp(16);
        col.addView(ac, al);
        ac.addView(label("BESCHLEUNIGUNG (OHNE SCHWERKRAFT)"));
        LinearLayout ar = new LinearLayout(this); ar.setGravity(Gravity.BOTTOM);
        accBig = num(44, AMBER); accBig.setText("0.00");
        ar.addView(accBig);
        TextView au = mono(16, INK2); au.setText(" m/s²"); au.setPadding(0, 0, 0, dp(9));
        ar.addView(au, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout ap = new LinearLayout(this); ap.setOrientation(LinearLayout.VERTICAL); ap.setGravity(Gravity.END);
        accPeak = mono(13, INK2); accHz = mono(11, INK3);
        ap.addView(accPeak); ap.addView(accHz);
        ar.addView(ap);
        ac.addView(ar);

        // GPS quality + trip, as a 2-column grid
        LinearLayout g1 = gridRow(), g2 = gridRow(), g3 = gridRow();
        alt = cell(g1, "HÖHE"); acc = cell(g1, "GENAUIGKEIT");
        sats = cell(g2, "SATELLITEN"); fixAge = cell(g2, "LETZTER FIX");
        dist = cell(g3, "STRECKE"); vmax = cell(g3, "MAX");
        LinearLayout g4 = gridRow();
        vavg = cell(g4, "SCHNITT");
        TextView reset = cell(g4, "TRIP");
        reset.setText(L.t("Neu starten"));
        reset.setTextColor(CYAN);
        ((View) reset.getParent()).setOnClickListener(v -> resetTrip());
        for (LinearLayout g : new LinearLayout[]{g1, g2, g3, g4}) {
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2); gl.topMargin = dp(10);
            col.addView(g, gl);
        }

        status = text(13, INK3);
        status.setPadding(0, dp(18), 0, 0);
        status.setText(L.t("Unter freiem Himmel ist GPS am genausten. Drinnen dauert der erste Fix länger."));
        col.addView(status);

        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        resetTrip();
    }

    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
        else startGps();
        Sensor lin = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        Sensor rv = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (lin != null) sm.registerListener(this, lin, SensorManager.SENSOR_DELAY_FASTEST);
        if (rv != null) sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME);
        if (lin == null) accBig.setText("–");
        main.post(ui);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(ui);
        if (sm != null) sm.unregisterListener(this);
        try { lm.removeUpdates(gps); } catch (RuntimeException ignored) { }
        if (gnss != null) try { lm.unregisterGnssStatusCallback(gnss); } catch (RuntimeException ignored) { }
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startGps();
        else status.setText(L.t("Ohne Standort-Erlaubnis gibt es keine GPS-Daten — Beschleunigung und Kompass laufen trotzdem."));
    }

    private void startGps() {
        if (lm == null) return;
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { status.setText(L.t("GPS ist aus — in den Schnelleinstellungen «Standort» einschalten.")); return; }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gps, Looper.getMainLooper());   // as fast as the chip goes
            gnss = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus s) {
                    int used = 0;
                    for (int i = 0; i < s.getSatelliteCount(); i++) if (s.usedInFix(i)) used++;
                    satsUsed = used; satsSeen = s.getSatelliteCount();
                }
            };
            lm.registerGnssStatusCallback(gnss, main);
        } catch (SecurityException e) {
            status.setText(L.t("Standort-Erlaubnis fehlt."));
        }
    }

    private void onFix(Location l) {
        long now = SystemClock.elapsedRealtime();
        fixTimes[fixN % fixTimes.length] = now;
        fixN++;
        if (last != null && l.getAccuracy() < 30) {
            float d = last.distanceTo(l);
            if (d > l.getAccuracy() * 0.3f && d < 500) tripM += d;    // ignore jitter while standing still
        }
        if (l.hasSpeed() && l.getSpeed() > maxV) maxV = l.getSpeed();
        last = l;
        lastFixElapsed = now;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float a = (float) Math.sqrt(x * x + y * y + z * z);
            linAcc = linAcc * 0.85f + a * 0.15f;                      // light smoothing so the digits are readable
            if (a > peak) peak = a;
            long t = SystemClock.elapsedRealtime();
            if (accWindowStart == 0) accWindowStart = t;
            accCount++;
            if (t - accWindowStart >= 1000) { accRate = accCount * 1000f / (t - accWindowStart); accCount = 0; accWindowStart = t; }
        } else if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rot, e.values);
            SensorManager.getOrientation(rot, orient);
            float deg = (float) ((Math.toDegrees(orient[0]) + 360) % 360);
            azimuth = Float.isNaN(azimuth) ? deg : smoothAngle(azimuth, deg, 0.2f);
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    /** Redraw the numbers ~8× a second (the data itself arrives faster). */
    private final Runnable ui = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            Location l = last;
            boolean fresh = l != null && now - lastFixElapsed < 4000;
            if (fresh && l.hasSpeed()) {
                float v = l.getSpeed();
                speedBig.setText(String.format(Locale.ROOT, "%.2f", v));
                speedKmh.setText(String.format(Locale.ROOT, "%.1f km/h", v * 3.6f));
                speedAcc.setText(l.hasSpeedAccuracy() ? String.format(Locale.ROOT, "± %.2f m/s", l.getSpeedAccuracyMetersPerSecond()) : "");
            } else {
                speedBig.setText(l == null ? "–" : "0.00");
                speedKmh.setText(l == null ? L.t("warte auf GPS …") : "");
                speedAcc.setText("");
            }
            boolean bearingOk = fresh && l.hasBearing() && l.hasSpeed() && l.getSpeed() > 0.7f;
            travelDir.setText(bearingOk ? dir(l.getBearing()) + String.format(Locale.ROOT, "  %03.0f°", l.getBearing()) : "–");
            facingDir.setText(Float.isNaN(azimuth) ? "–" : dir(azimuth) + String.format(Locale.ROOT, "  %03.0f°", azimuth));
            compass.set(Float.isNaN(azimuth) ? 0 : azimuth, bearingOk ? l.getBearing() : Float.NaN);

            accBig.setText(String.format(Locale.ROOT, "%.2f", linAcc));
            accPeak.setText(String.format(Locale.ROOT, "Spitze %.1f m/s²", peak));
            accHz.setText(accRate > 0 ? String.format(Locale.ROOT, "%.0f Hz Sensor", accRate) : "");

            int n = Math.min(fixN, fixTimes.length);
            if (n >= 3) {
                long newest = fixTimes[(fixN - 1) % fixTimes.length], oldest = fixTimes[(fixN - n) % fixTimes.length];
                float rate = newest > oldest ? (n - 1) * 1000f / (newest - oldest) : 0;
                hz.setText(String.format(Locale.ROOT, "GPS %.1f Hz", rate));
            } else hz.setText(l == null ? "GPS …" : "GPS");

            alt.setText(l != null && l.hasAltitude() ? String.format(Locale.ROOT, "%.0f m", l.getAltitude()) : "–");
            acc.setText(l != null ? String.format(Locale.ROOT, "± %.1f m", l.getAccuracy()) : "–");
            acc.setTextColor(l == null ? INK : l.getAccuracy() <= 5 ? LIME : l.getAccuracy() <= 15 ? INK : AMBER);
            sats.setText(satsSeen > 0 ? satsUsed + " / " + satsSeen : "–");
            fixAge.setText(l == null ? "–" : String.format(Locale.ROOT, "%.1f s", (now - lastFixElapsed) / 1000f));
            dist.setText(tripM < 1000 ? String.format(Locale.ROOT, "%.0f m", tripM) : String.format(Locale.ROOT, "%.2f km", tripM / 1000));
            vmax.setText(String.format(Locale.ROOT, "%.1f km/h", maxV * 3.6));
            double secs = Math.max(1, (now - tripStart) / 1000.0);
            vavg.setText(String.format(Locale.ROOT, "%.1f km/h", tripM / secs * 3.6));
            main.postDelayed(this, 125);
        }
    };

    private void resetTrip() { tripM = 0; maxV = 0; peak = 0; tripStart = SystemClock.elapsedRealtime(); }

    private static String dir(float deg) { return DIRS[Math.round(((deg % 360) + 360) % 360 / 45f) % 8]; }

    private static float smoothAngle(float from, float to, float k) {
        float d = ((to - from + 540) % 360) - 180;
        return (from + d * k + 360) % 360;
    }

    /** Compass ring: where the phone points (top), and a lime arrow for where you're actually going. */
    private final class Compass extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG),
            txt = new Paint(Paint.ANTI_ALIAS_FLAG), arrow = new Paint(Paint.ANTI_ALIAS_FLAG), me = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float az, travel = Float.NaN;
        Compass(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(dp(2)); ring.setColor(LINE);
            tick.setColor(INK3); tick.setStrokeWidth(dp(1.5f));
            txt.setColor(INK2); txt.setTextAlign(Paint.Align.CENTER); txt.setTypeface(Typeface.DEFAULT_BOLD); txt.setTextSize(dp(12));
            arrow.setColor(LIME); arrow.setStrokeWidth(dp(4)); arrow.setStrokeCap(Paint.Cap.ROUND);
            me.setColor(CYAN);
        }
        void set(float azimuth, float travelBearing) { az = azimuth; travel = travelBearing; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(4);
            c.drawCircle(cx, cy, r, ring);
            c.save();
            c.rotate(-az, cx, cy);                                   // the dial turns so the top is where the phone points
            for (int i = 0; i < 36; i++) {
                float a = (float) Math.toRadians(i * 10), len = i % 9 == 0 ? dp(10) : dp(5);
                c.drawLine(cx + (float) Math.sin(a) * (r - len), cy - (float) Math.cos(a) * (r - len),
                    cx + (float) Math.sin(a) * r, cy - (float) Math.cos(a) * r, tick);
            }
            String[] cardinal = {"N", "O", "S", "W"};
            for (int i = 0; i < 4; i++) {
                float a = (float) Math.toRadians(i * 90);
                txt.setColor(i == 0 ? AMBER : INK2);
                c.drawText(cardinal[i], cx + (float) Math.sin(a) * (r - dp(22)), cy - (float) Math.cos(a) * (r - dp(22)) + dp(4), txt);
            }
            if (!Float.isNaN(travel)) {
                float a = (float) Math.toRadians(travel);
                c.drawLine(cx, cy, cx + (float) Math.sin(a) * (r - dp(34)), cy - (float) Math.cos(a) * (r - dp(34)), arrow);
            }
            c.restore();
            c.drawCircle(cx, cy - r + dp(2), dp(4), me);            // fixed marker: straight ahead of the phone
            c.drawCircle(cx, cy, dp(4), me);
        }
    }

    /* ---------------- ui kit ---------------- */

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(14)); g.setStroke(dp(1), LINE);
        l.setBackground(g);
        return l;
    }

    private LinearLayout gridRow() { return new LinearLayout(this); }

    private TextView cell(LinearLayout row, String name) {
        LinearLayout c = card();
        c.addView(label(name));
        TextView v = num(22, INK); v.setText("–");
        c.addView(v);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        if (row.getChildCount() == 0) p.rightMargin = dp(10);
        row.addView(c, p);
        return v;
    }

    private TextView label(String s) {
        TextView t = mono(10, INK3); t.setText(s); t.setLetterSpacing(0.16f);
        return t;
    }

    private TextView num(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setFontFeatureSettings("tnum");
        return t;
    }

    private TextView mono(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
