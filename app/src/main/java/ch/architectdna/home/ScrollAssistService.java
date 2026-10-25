package ch.architectdna.home;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

/**
 * Head / eye scrolling in other apps (e.g. Chrome), switched on per app in the launcher's
 * Mediathek → "Kopf-Scroll pro App". When such an app comes to the front this service starts
 * the front camera (HeadTracker, on-device), shows a small pill overlay with the state, and
 * scrolls the app with gentle swipe gestures. Tap the pill to pause; any tap or typing in the
 * app also pauses eye scrolling for 4 seconds.
 */
public class ScrollAssistService extends AccessibilityService implements HeadTracker.Listener {

    private static final String CHANNEL = "scroll";
    private static final long PAUSE_AFTER_TOUCH_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager windows;
    private HeadTracker head;
    private TextView pill;
    private EdgeLight edge;
    private WindowManager.LayoutParams edgeParams;
    private String currentPkg = "", mode = "off";
    private boolean paused, scrolling, foreground, gotFrame;

    /** If the camera never delivers a reading, say so instead of sitting there silently. */
    private final Runnable frameWatchdog = () -> {
        if (!gotFrame && !mode.equals("off")) {
            DiagLog.log("no camera readings 6 s after start");
            showPill("○ KEINE KAMERA-BILDER · GESICHT IM BILD?");
        }
    };

    private int framesN, lostN, swipesN, overN;
    private float pitchMin = 999, pitchMax = -999;
    private long lastStat;

    private void frameArrived() {
        framesN++;
        long now = SystemClock.uptimeMillis();
        if (now - lastStat > 10000 && !mode.equals("off")) {
            // Numbers only: readings per 10 s, face lost, swipes, and the head-tilt range seen.
            DiagLog.log(String.format(java.util.Locale.ROOT, "scroll stats: %d readings, %d lost, %d swipes, tilt %.1f..%.1f°",
                framesN, lostN, swipesN, pitchMin == 999 ? 0 : pitchMin, pitchMax == -999 ? 0 : pitchMax));
            framesN = lostN = swipesN = 0;
            pitchMin = 999;
            pitchMax = -999;
            lastStat = now;
        }
        if (gotFrame) return;
        gotFrame = true;
        DiagLog.log("first camera reading");
    }
    private long pauseUntil, ownGestureUntil;
    private int calibFrames;
    private float calibSum, base, smooth, speed;

    private final Runnable scroller = new Runnable() {
        @Override
        public void run() {
            if (speed == 0 || mode.equals("off")) {
                scrolling = false;
                return;
            }
            swipe(speed);
            main.postDelayed(this, 420);
        }
    };

    @Override
    protected void onServiceConnected() {
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        DiagLog.init(this);
        DiagLog.log("scroll service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (ignored(pkg) || pkg.equals(currentPkg)) return;
            currentPkg = pkg;
            String m = pkg.equals(getPackageName()) ? "off" : prefs.getString("appScroll:" + pkg, "off");
            // Package names of other apps are not logged, only whether scrolling is on for them.
            DiagLog.log("front app changed, scroll mode=" + m);
            apply(m);
        } else if (pkg.equals(currentPkg)) {
            // A tap, long-press or typing in the app: rest for a moment.
            userTouched();
        }
    }

    /** Any real touch (or typing) pauses head / eye scrolling for 4 s, so your finger wins. */
    private void userTouched() {
        if (mode.equals("off")) return;
        pauseUntil = SystemClock.uptimeMillis() + PAUSE_AFTER_TOUCH_MS;
        speed = 0;
        main.removeCallbacks(scroller);
        scrolling = false;
        showPill(null);
        main.removeCallbacks(refreshPill);
        main.postDelayed(refreshPill, PAUSE_AFTER_TOUCH_MS + 50);
    }

    private final Runnable refreshPill = () -> showPill(null);

    /** System UI, keyboards and the like pop up over apps; they shouldn't switch the mode. */
    private static boolean ignored(String pkg) {
        return pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.contains("inputmethod")
            || pkg.equals("com.google.android.permissioncontroller");
    }

    private void apply(String m) {
        if (m.equals(mode)) return;
        stopTracking();
        mode = m;
        if (m.equals("off")) {
            hidePill();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showPill("○ KAMERA FEHLT – IM LAUNCHER ERLAUBEN");
            return;
        }
        // Show the overlay first: a visible window lets Android grant the camera while another app is in front.
        showPill("◉ KAMERA STARTET …");
        if (!goForeground()) {
            showPill("○ KAMERA IM HINTERGRUND BLOCKIERT");
            return;
        }
        if (head == null) head = new HeadTracker(this, this);
        calibFrames = 0;
        calibSum = 0;
        smooth = m.equals("gaze") ? prefs.getFloat("gazeMid", 0f) : 0;
        paused = false;
        gotFrame = false;
        head.start(m.equals("gaze") ? HeadTracker.Mode.GAZE : HeadTracker.Mode.NOD);
        DiagLog.log("head tracking started, mode=" + m);
        showEdge();
        main.removeCallbacks(frameWatchdog);
        main.postDelayed(frameWatchdog, 6000);
    }

    private void stopTracking() {
        if (head != null) head.stop();
        hideEdge();
        speed = 0;
        main.removeCallbacks(scroller);
        scrolling = false;
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
    }

    /** Camera use while another app is in front needs a camera foreground service + notification. */
    private boolean goForeground() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Kopf-Scroll", NotificationManager.IMPORTANCE_MIN));
            }
            Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cube)
                .setContentTitle("ARC-DNA Kopf-Scroll aktiv")
                .setContentText("Kamera liest Kopf/Blick, nur auf dem Handy.")
                .setOngoing(true)
                .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            else startForeground(1, n);
            foreground = true;
            DiagLog.log("camera foreground service ok");
        } catch (RuntimeException e) {
            foreground = false;
            DiagLog.log("camera foreground service refused: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        return foreground;
    }

    /* ---------------- Readings → speed (same feel as in the Mediathek) ---------------- */

    @Override
    public void onTilt(float pitch) {
        frameArrived();
        if (calibFrames < 6) {
            calibSum += pitch;
            if (++calibFrames == 6) base = calibSum / 6f;
            return;
        }
        if (edge != null) edge.face(true);
        smooth = smooth * 0.65f + (pitch - base) * 0.35f;
        float d = smooth;
        pitchMin = Math.min(pitchMin, d);
        pitchMax = Math.max(pitchMax, d);
        // Hysteresis: start beyond 5°, keep going until back under 3°; two readings in a row to start.
        float dead = speed != 0 ? 3f : 5f;
        float over = Math.abs(d) - dead;
        if (over <= 0) {
            overN = 0;
            base = base * 0.99f + pitch * 0.01f;
            setSpeed(0);
            return;
        }
        if (speed == 0 && ++overN < 2) return;
        float s = 0.25f + 0.75f * Math.min(1f, (Math.abs(d) - 3f) / 9f);
        setSpeed(d > 0 ? -s : s);   // looking up scrolls up
    }

    @Override
    public void onGaze(float vertical) {
        frameArrived();
        if (edge != null) edge.face(true);
        float mid = prefs.getFloat("gazeMid", 0f);
        float down = Math.max(0.03f, prefs.getFloat("gazeBottom", mid + 0.08f) - mid);
        float up = Math.max(0.03f, mid - prefs.getFloat("gazeTop", mid - 0.08f));
        smooth = smooth * 0.6f + vertical * 0.4f;
        float pos = smooth - mid;
        float frac = pos > 0 ? pos / down : -pos / up;
        int dir = pos > 0 ? 1 : -1;
        boolean sameWay = speed != 0 && Math.signum(speed) == dir;
        if (frac < (sameWay ? 0.35f : 0.55f)) {
            setSpeed(0);
            return;
        }
        setSpeed(dir * (0.4f + 0.6f * Math.min(1f, (frac - 0.35f) / 0.65f)));
    }

    @Override
    public void onLost() {
        frameArrived();
        lostN++;
        overN = 0;
        if (edge != null) edge.face(false);
        setSpeed(0);
    }

    @Override
    public void onError(String message) {
        DiagLog.log("head tracker error: " + message);
        stopTracking();
        showPill("○ " + message.toUpperCase());
    }

    private void setSpeed(float s) {
        boolean resting = paused || SystemClock.uptimeMillis() < pauseUntil;
        if (resting) s = 0;
        if (s != 0 && speed == 0) DiagLog.log("scrolling " + (s > 0 ? "down" : "up"));
        speed = s;
        showPill(null);
        if (s != 0 && !scrolling) {
            scrolling = true;
            main.post(scroller);
        }
    }

    /** One gentle swipe in the middle of the screen; slow enough not to fling. */
    private void swipe(float s) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float h = dm.heightPixels, x = dm.widthPixels * 0.5f, y = h * 0.55f;
        float dist = h * (0.08f + 0.16f * Math.abs(s));
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, s > 0 ? y - dist : y + dist);   // finger up = content scrolls down
        // Our own swipe also shows up as a touch outside the pill; don't let it pause us.
        ownGestureUntil = SystemClock.uptimeMillis() + 380 + 120;
        swipesN++;
        try {
            boolean ok = dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 380)).build(), new GestureResultCallback() {
                    @Override
                    public void onCancelled(GestureDescription g) {
                        DiagLog.log("swipe cancelled by the system");
                    }
                }, null);
            if (!ok) DiagLog.log("swipe rejected (gestures not allowed?)");
        } catch (RuntimeException e) {
            DiagLog.log("swipe failed: " + e);
        }
    }

    /* ---------------- Overlay pill ---------------- */

    private void showPill(String message) {
        if (windows == null) return;
        if (pill == null) {
            pill = new TextView(this);
            pill.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pill.setLetterSpacing(0.1f);
            pill.setTextColor(0xFFFAFAF8);
            int p = dp(10);
            pill.setPadding(p + dp(2), p - dp(3), p + dp(2), p - dp(3));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE61A1A1A);
            bg.setStroke(dp(1), 0xFFB5271E);
            bg.setCornerRadius(dp(20));
            pill.setBackground(bg);
            pill.setOnClickListener(v -> {
                paused = !paused;
                setSpeed(0);
            });
            // Long-press: switch this app between NICKEN and BLICK (saved for the app).
            pill.setOnLongClickListener(v -> {
                if (mode.equals("off") || currentPkg.isEmpty()) return false;
                String next = mode.equals("gaze") ? "nod" : "gaze";
                prefs.edit().putString("appScroll:" + currentPkg, next).apply();
                DiagLog.log("pill: switched to " + next);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                apply(next);
                return true;
            });
            // Watch every touch on the screen (outside the pill) to pause while the finger is in charge.
            pill.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_OUTSIDE) {
                    if (SystemClock.uptimeMillis() > ownGestureUntil) userTouched();
                    return true;
                }
                return false;
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
            // Floating bottom-left, above the navigation bar: clear of address bars and toolbars.
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.x = dp(14);
            lp.y = dp(72);
            try {
                windows.addView(pill, lp);
            } catch (RuntimeException e) {
                pill = null;
                return;
            }
        }
        if (message != null) {
            pill.setText(message);
            return;
        }
        String name = mode.equals("gaze") ? "BLICK" : "NICKEN";
        boolean resting = SystemClock.uptimeMillis() < pauseUntil;
        String state = paused ? "‖ PAUSE" : resting ? "‖ FINGER" : speed < 0 ? "↑" : speed > 0 ? "↓" : "·";
        pill.setText("◉ " + name + " " + state);
    }

    /** Full-screen, untouchable overlay for the ring light; it lights up only in the dark. */
    private void showEdge() {
        if (windows == null || edge != null) return;
        // Corner LEDs only; the screen brightness is left alone.
        edge = new EdgeLight(this, null);
        edgeParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) {
            edgeParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        try {
            windows.addView(edge, edgeParams);
            edge.watch(true);
        } catch (RuntimeException e) {
            edge = null;
        }
    }

    private void hideEdge() {
        if (edge == null) return;
        edge.watch(false);
        try {
            windows.removeView(edge);
        } catch (RuntimeException ignored) {
        }
        edge = null;
    }

    private void hidePill() {
        if (pill != null && windows != null) {
            try {
                windows.removeView(pill);
            } catch (RuntimeException ignored) {
            }
        }
        pill = null;
    }

    @Override
    public void onInterrupt() {
        stopTracking();
    }

    @Override
    public void onDestroy() {
        stopTracking();
        hidePill();
        if (head != null) head.close();
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
