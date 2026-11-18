package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * ARC·Tap — physically touch the phone to the tablet to sync them.
 *
 * There is no "these two devices are touching" sensor on Android, so this reads two it does have:
 * the magnetometer and the accelerometer. Put a small magnet on one device; when the other is
 * tapped against that spot, its compass field jumps far above the Earth's ~50 µT baseline (a sharp,
 * unmistakable spike) and the accelerometer feels the bump at the same instant. Either a clear
 * magnet press, or a spike together with a bump, counts as a deliberate tap and kicks off an
 * immediate phone↔tablet exchange over the Bluetooth link that already exists (BtSync).
 *
 * Runs inside the always-on MediaListener service, so it works on both devices with the home's
 * background service alive. The magnetometer is a low-power sensor sampled slowly here, so leaving
 * it on costs very little battery.
 */
final class ArcTap implements SensorEventListener {

    private static ArcTap live;

    private final Context app;
    private final SensorManager sm;
    private final Sensor mag, acc;
    private final Vibrator vibe;

    private float baseline = -1f;          // slow-moving Earth-field magnitude (µT)
    private float prevAcc = 9.81f;         // last total acceleration (m/s²)
    private long lastSpike, lastJolt, lastTap;

    private static final long DEBOUNCE_MS = 4_000;   // ignore repeats right after a tap
    private static final long PAIR_MS = 900;         // spike and bump must land within this
    private static final float JOLT = 7f;            // sudden Δacceleration that reads as a bump

    private ArcTap(Context c) {
        app = c.getApplicationContext();
        sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        mag = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibe = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("arctap", Context.MODE_PRIVATE);
    }

    /** Field jump over baseline (µT) that counts as a magnet nearby; the user can tune sensitivity. */
    static int threshold(Context c) {
        return prefs(c).getInt("thresh", 140);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("on", true);
    }

    static synchronized void start(Context c) {
        if (!enabled(c)) { stop(); return; }
        if (live != null) return;
        ArcTap t = new ArcTap(c);
        if (t.mag == null) { DiagLog.log("arc·tap: kein Magnetometer auf diesem Gerät"); return; }
        try {
            t.sm.registerListener(t, t.mag, SensorManager.SENSOR_DELAY_NORMAL);
            if (t.acc != null) t.sm.registerListener(t, t.acc, SensorManager.SENSOR_DELAY_NORMAL);
            live = t;
            DiagLog.log("arc·tap: bereit (Schwelle " + threshold(c) + " µT)");
        } catch (RuntimeException e) {
            DiagLog.log("arc·tap: Sensor nicht verfügbar: " + e.getMessage());
        }
    }

    static synchronized void stop() {
        if (live == null) return;
        try { live.sm.unregisterListener(live); } catch (RuntimeException ignored) { }
        live = null;
    }

    /** React to a settings change without waiting for the service to restart. */
    static synchronized void refresh(Context c) {
        stop();
        start(c);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = System.currentTimeMillis();
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float a = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
            if (Math.abs(a - prevAcc) > JOLT) lastJolt = now;
            prevAcc = a;
            return;
        }
        // magnetometer
        float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        if (baseline < 0) baseline = m;
        float delta = m - baseline;
        int thresh = threshold(app);

        if (delta > thresh) {
            lastSpike = now;
            boolean strongMagnet = delta > thresh * 2.3f;              // a magnet pressed right on the spot
            boolean bumpedTogether = now - lastJolt < PAIR_MS;         // spike + a physical bump
            if ((strongMagnet || bumpedTogether) && now - lastTap > DEBOUNCE_MS) {
                lastTap = now;
                fire(Math.round(delta));
            }
        } else {
            // let the baseline drift only while the field is calm, so a real spike never gets absorbed
            if (delta < thresh * 0.5f) baseline += 0.05f * delta;
        }
    }

    private void fire(int deltaUT) {
        DiagLog.log("arc·tap: Tipp erkannt (+" + deltaUT + " µT) → Abgleich");
        buzz();
        BtSync.syncSoon(app, 0, name -> {
            if (name != null) {
                buzz();
                DiagLog.log("arc·tap: abgeglichen mit " + name);
            } else {
                DiagLog.log("arc·tap: kein Partner erreichbar – ist ARC-DNA auf dem anderen Gerät offen?");
            }
        });
    }

    private void buzz() {
        if (vibe == null || !vibe.hasVibrator()) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) vibe.vibrate(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE));
            else legacyBuzz();
        } catch (RuntimeException ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void legacyBuzz() { vibe.vibrate(55); }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
