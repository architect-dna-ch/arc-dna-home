package ch.architectdna.home;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * "This device was just picked up." The one in your hand shows a burst of motion after a rest; the one
 * left on the table stays flat and still. We low-pass the accelerometer to track gravity, high-pass it
 * to get linear motion, and fire once when a device that had been resting is clearly lifted/handled.
 *
 * It's a heuristic, not certainty: a knock on the table or handing the device over can also trip it,
 * so the fire is debounced (~6 s) and requires a genuine still period first. ARC Flow uses the fire to
 * decide the music should follow to this device.
 */
final class PickupDetector implements SensorEventListener {

    interface OnPickup { void onPickedUp(); }

    private final SensorManager sm;
    private final Sensor accel;
    private final OnPickup cb;

    private final float[] grav = {0, 0, SensorManager.GRAVITY_EARTH};
    private boolean seeded;
    private long stillSince, lastFire;
    private boolean wasStill;

    PickupDetector(Context c, OnPickup cb) {
        sm = c.getSystemService(SensorManager.class);
        accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.cb = cb;
    }

    boolean available() { return accel != null; }

    void start() { if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI); }
    void stop() { if (sm != null) try { sm.unregisterListener(this); } catch (RuntimeException ignored) { } }

    @Override public void onSensorChanged(SensorEvent e) {
        final float a = 0.8f;
        if (!seeded) { grav[0] = e.values[0]; grav[1] = e.values[1]; grav[2] = e.values[2]; seeded = true; }
        grav[0] = a * grav[0] + (1 - a) * e.values[0];
        grav[1] = a * grav[1] + (1 - a) * e.values[1];
        grav[2] = a * grav[2] + (1 - a) * e.values[2];
        float lx = e.values[0] - grav[0], ly = e.values[1] - grav[1], lz = e.values[2] - grav[2];
        double mag = Math.sqrt(lx * lx + ly * ly + lz * lz);   // m/s², gravity removed
        long now = SystemClock.elapsedRealtime();

        if (mag < 0.25) {                                      // nearly still (resting)
            if (stillSince == 0) stillSince = now;
            if (now - stillSince > 1200) wasStill = true;
        } else {
            stillSince = 0;
            if (wasStill && mag > 2.2 && now - lastFire > 6000) {   // lifted after a rest
                wasStill = false;
                lastFire = now;
                cb.onPickedUp();
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int acc) { }
}
