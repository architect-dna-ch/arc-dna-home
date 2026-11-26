package ch.architectdna.home;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * "Tap the phone twice against the tablet." Two sharp jolts (gravity removed, well above hand movement)
 * 120–700 ms apart fire once. Walking, putting the phone down or a single bump don't: it needs the
 * double tap. Debounced 4 s. Uses the raw accelerometer at game rate, so a short knock isn't missed.
 */
final class KnockDetector implements SensorEventListener {

    interface OnKnock { void onDoubleKnock(); }

    private static final float SPIKE = 11f;          // m/s² of linear jolt — a tap against something hard
    private final SensorManager sm;
    private final Sensor accel;
    private final OnKnock cb;
    private final float[] grav = new float[3];
    private boolean seeded;
    private long firstAt, lastSpikeAt, lastFire;

    KnockDetector(Context c, OnKnock cb) {
        sm = c.getSystemService(SensorManager.class);
        accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.cb = cb;
    }

    void start() { if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME); }
    void stop() { if (sm != null) try { sm.unregisterListener(this); } catch (RuntimeException ignored) { } }

    @Override public void onSensorChanged(SensorEvent e) {
        final float a = 0.9f;
        if (!seeded) { System.arraycopy(e.values, 0, grav, 0, 3); seeded = true; return; }
        for (int i = 0; i < 3; i++) grav[i] = a * grav[i] + (1 - a) * e.values[i];
        float lx = e.values[0] - grav[0], ly = e.values[1] - grav[1], lz = e.values[2] - grav[2];
        double mag = Math.sqrt(lx * lx + ly * ly + lz * lz);
        long now = SystemClock.elapsedRealtime();
        if (mag < SPIKE || now - lastSpikeAt < 90) return;           // not a jolt, or the same jolt ringing on
        lastSpikeAt = now;
        if (firstAt > 0 && now - firstAt >= 120 && now - firstAt <= 700) {
            firstAt = 0;
            if (now - lastFire > 4000) { lastFire = now; cb.onDoubleKnock(); }
        } else {
            firstAt = now;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int acc) { }
}
