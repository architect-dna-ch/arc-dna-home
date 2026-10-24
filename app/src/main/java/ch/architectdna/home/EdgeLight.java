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
 * A ring light for head / eye scrolling in the dark: a few pixels of bright white along the
 * phone's curved display edge, with brighter "LED" spots in the four corners, so the front
 * camera can still see your face. It comes on below ~12 lux, or when the camera loses your face
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
        rim.setColor(0xFFFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        glow.setColor(0xFFFFF4E8);
        glow.setMaskFilter(new BlurMaskFilter(dp(10), BlurMaskFilter.Blur.NORMAL));
        led.setColor(0xFFFFFFFF);
        led.setMaskFilter(new BlurMaskFilter(dp(8), BlurMaskFilter.Blur.NORMAL));
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // blur mask filters need a software layer
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    /** Follow the light sensor while head / eye scrolling runs. */
    void watch(boolean on) {
        if (on == watching) return;
        watching = on;
        Sensor light = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (on && light != null) {
            sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            if (sensors != null) sensors.unregisterListener(this);
            lux = -1;
            faceGoneSince = -1;
            setLit(false);
        }
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
        if (seen) faceGoneSince = -1;
        else if (faceGoneSince < 0) faceGoneSince = now;
        decide();
    }

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
        // Soft glow inside, then a crisp few-pixel rim right on the edge.
        glow.setStrokeWidth(dp(16));
        glow.setAlpha((int) (a * 0.55f));
        rect.set(dp(2), dp(2), w - dp(2), h - dp(2));
        c.drawRoundRect(rect, r, r, glow);
        rim.setStrokeWidth(dp(4));
        rim.setAlpha(a);
        rect.set(dp(2), dp(2), w - dp(2), h - dp(2));
        c.drawRoundRect(rect, r - dp(2), r - dp(2), rim);

        // LED spots in the corners, on the curve.
        float k = r * 0.29f;    // a point on the corner arc, 45°
        led.setAlpha(a);
        float s = dp(14);
        c.drawCircle(k, k, s, led);
        c.drawCircle(w - k, k, s, led);
        c.drawCircle(k, h - k, s, led);
        c.drawCircle(w - k, h - k, s, led);
    }
}
