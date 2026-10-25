package ch.architectdna.home;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.RoundedCorner;
import android.view.View;

/**
 * Mirror LEDs while head / eye scrolling runs: warm white light in the four curved display
 * corners and a thin line along the edge (no brightness boost), always on while the camera
 * tracks, so the front camera sees your face without glaring into your eyes. It comes on below ~12 lux, or when the camera loses your face
 * in a dim room (below ~60 lux). The Pixel's light sensor sits under the screen and sees the rim
 * itself, so once on it stays on at least 8 s and only goes off above ~90 lux with a face in view.
 */
final class EdgeLight extends View implements SensorEventListener {

    interface Listener {
        void onEdgeLight(boolean on);
    }

    private static final float ON_LUX = 12f, DIM_LUX = 60f, OFF_LUX = 90f;
    private static final long MIN_ON_MS = 8000, NO_FACE_MS = 1500;
    private float lux = -1;
    private long litSince, faceGoneSince = -1;

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint led = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wash = new Paint();
    private final RectF rect = new RectF();
    private final SensorManager sensors;
    private final Listener listener;
    private boolean watching, lit;
    private float level;            // 0..1, animated
    private long lastFrame;

    EdgeLight(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        sensors = c.getSystemService(SensorManager.class);
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(0xFFFFEBD6);      // warm white, kinder to the eyes than blue-white
        rim.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setColor(0xFFFFDDB8);
        glow.setMaskFilter(new BlurMaskFilter(dp(10), BlurMaskFilter.Blur.NORMAL));
        led.setColor(0xFFFFF3E6);
        led.setMaskFilter(new BlurMaskFilter(dp(8), BlurMaskFilter.Blur.NORMAL));
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // blur mask filters need a software layer
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    /** On for as long as head / eye scrolling runs (any light level), off when it stops. */
    void watch(boolean on) {
        if (on == watching) return;
        watching = on;
        setLit(on);
    }

    boolean isLit() {
        return lit;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        lux = e.values[0];
        decide();
    }

    /** The camera's view: no face for a while in a dim room also means "too dark". */
    void face(boolean seen) {
        long now = SystemClock.uptimeMillis();
        if (seen) {
            faceGoneSince = -1;
            if (faceSeenSince < 0) faceSeenSince = now;
        } else {
            faceSeenSince = -1;
            if (faceGoneSince < 0) faceGoneSince = now;
        }
        // No face for ~0.8 s: too dark for the camera, so the light grows; once the face has
        // been found again for ~3 s it shrinks back to the corners.
        float before = boostTarget;
        if (faceGoneSince >= 0 && now - faceGoneSince > 800) boostTarget = 1f;
        else if (faceSeenSince >= 0 && now - faceSeenSince > 3000) boostTarget = 0f;
        if (boostTarget != before) postInvalidateOnAnimation();
        decide();
    }

    private long faceSeenSince = -1;
    private float boost, boostTarget;   // 0 = corners only, 1 = wide light along the edges

    private void decide() {
        if (!watching || lux < 0) return;
        long now = SystemClock.uptimeMillis();
        boolean noFace = faceGoneSince >= 0 && now - faceGoneSince > NO_FACE_MS;
        if (!lit) {
            if (lux < ON_LUX || (noFace && lux < DIM_LUX)) setLit(true);
        } else if (now - litSince > MIN_ON_MS && lux > OFF_LUX && !noFace) {
            setLit(false);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void setLit(boolean on) {
        if (on == lit) return;
        lit = on;
        litSince = SystemClock.uptimeMillis();
        DiagLog.log("edge light " + (on ? "on" : "off") + ", lux=" + Math.round(lux));
        if (listener != null) listener.onEdgeLight(on);
        lastFrame = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** The display's own corner radius (Pixel 9a ≈ 45 dp), so the rim follows the glass. */
    private float cornerRadius() {
        if (Build.VERSION.SDK_INT >= 31) {
            Display d = getDisplay();
            RoundedCorner rc = d == null ? null : d.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
            if (rc != null && rc.getRadius() > 0) return rc.getRadius();
        }
        return dp(44);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float target = lit ? 1f : 0f;
        level += Math.signum(target - level) * Math.min(Math.abs(target - level), dt * 2.5f);
        if (level <= 0.001f) return;
        if (level != target) postInvalidateOnAnimation();

        float w = getWidth(), h = getHeight(), r = cornerRadius();
        int a = (int) (255 * level);
        // Just a thin warm line along the glass while the camera watches: a quiet "tracking on"
        // sign. (Corner lights and the wide wash are gone: they covered content, and the screen
        // can't shine brighter than its own brightness anyway.)
        float in = dp(2);
        rect.set(in, in, w - in, h - in);
        rim.setStrokeWidth(dp(2));
        rim.setAlpha((int) (a * 0.7f));
        c.drawRoundRect(rect, r * 1.1f - in, r * 1.1f - in, rim);
    }
}
