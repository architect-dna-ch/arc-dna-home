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
 * the front camera (HeadTracker, on-device), shows an orange ring around the camera with the state,
 * and scrolls the app with gentle swipe gestures. Triple-tap the camera to pause; any tap or typing in the
 * app also pauses eye scrolling for 4 seconds.
 */
public class ScrollAssistService extends AccessibilityService implements HeadTracker.Listener {

    private static final String CHANNEL = "scroll";
    private static final long PAUSE_AFTER_TOUCH_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager windows;
    private HeadTracker head;
    private android.view.View pill;
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
            // Faster = bigger swipes, closer together (650 ms apart when creeping, 270 ms flat out).
            long every = Math.round(650 - 380 * Math.min(1f, Math.abs(speed)));
            swipe(speed, Math.min(380, every - 40));
            main.postDelayed(this, every);
        }
    };

    /** Same process as the launcher, so it can say "home is in front" without relying on events. */
    static ScrollAssistService instance;

    /** The launcher came to the front: no pill and no camera here (it frees the camera for home). */
    static void homeInFront(String pkg) {
        ScrollAssistService s = instance;
        if (s == null) return;
        s.main.removeCallbacks(s.settle);
        s.pendingPkg = pkg;
        s.currentPkg = pkg;
        s.apply("off");
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        DiagLog.init(this);
        DiagLog.log("scroll service connected");
        android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF);
        f.addAction(android.content.Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
    }

    /**
     * Screen off / locked: the camera stops, no swipes on the lock screen or the shade. After
     * unlocking, the app that was in front decides again.
     */
    private final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            if (android.content.Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                if (!currentPkg.isEmpty()) lockedPkg = currentPkg;
                main.removeCallbacks(settle);
                currentPkg = "";
                if (!mode.equals("off")) DiagLog.log("screen off: head scroll stopped");
                apply("off");
            } else if (android.content.Intent.ACTION_USER_PRESENT.equals(i.getAction())) {
                rainbowUntil = SystemClock.uptimeMillis() + 2600;    // the ring starts in the lock screen's rainbow
                if (pill != null) pill.invalidate();
            }
            if (android.content.Intent.ACTION_USER_PRESENT.equals(i.getAction()) && !lockedPkg.isEmpty()) {
                pendingPkg = lockedPkg;
                lockedPkg = "";
                main.removeCallbacks(settle);
                main.postDelayed(settle, 500);
            }
        }
    };
    private String lockedPkg = "";
    /** Until when the shade / a system panel counts as open (it doesn't always say when it closes). */
    private long shadeUntil;

    /** Lock screen showing, or the notification shade / a system panel on top: hands off. */
    private boolean systemOnTop() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        return SystemClock.uptimeMillis() < shadeUntil || (km != null && km.isKeyguardLocked());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Overlays, dialogs and keyboards report windows too, for a moment; only real apps
            // (with a launcher icon) that stay in front ~0.5 s may switch the mode.
            if (pkg.equals("com.android.systemui")) {
                // Notification shade, quick settings, lock screen: no swiping into those.
                shadeUntil = SystemClock.uptimeMillis() + 6000;
                setSpeed(0);
            }
            if (ignored(pkg) || !launchable(pkg)) {
                main.removeCallbacks(settle);
                return;
            }
            shadeUntil = 0;
            pendingPkg = pkg;
            main.removeCallbacks(settle);
            main.postDelayed(settle, 500);
        } else if (pkg.equals(currentPkg)) {
            // A tap, long-press or typing in the app: rest for a moment (and the shade is gone).
            shadeUntil = 0;
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

    private String pendingPkg = "";
    private final java.util.Map<String, Boolean> launchableCache = new java.util.HashMap<>();

    private final Runnable settle = () -> {
        String pkg = pendingPkg;
        if (pkg.isEmpty() || pkg.equals(currentPkg)) return;
        currentPkg = pkg;
        // Main switch first: unless you allowed it, the camera never starts outside the launcher.
        boolean allowed = prefs.getBoolean("scrollOutside", false);
        String m = !allowed || pkg.equals(getPackageName()) ? "off"
            : prefs.getString("appScroll:" + pkg, prefs.getString("appScrollAll", "off")).replace("gaze", "nod");
        // Package names of other apps are not logged, only whether scrolling is on for them.
        DiagLog.log("front app changed" + (pkg.equals(getPackageName()) ? " (launcher)" : "") + ", scroll mode=" + m);
        apply(m);
    };

    private boolean launchable(String pkg) {
        if (pkg.equals(getPackageName())) return true;
        Boolean known = launchableCache.get(pkg);
        if (known == null) {
            known = getPackageManager().getLaunchIntentForPackage(pkg) != null;
            launchableCache.put(pkg, known);
        }
        return known;
    }

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
        if (m.equals("gaze") && !prefs.contains("gazeMid")) {
            android.widget.Toast.makeText(this, "BLICK ist noch nicht kalibriert: ARC-DNA ⚙ Einstellungen → Kopf-Scroll → Blick kalibrieren",
                android.widget.Toast.LENGTH_LONG).show();
        }
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
                .setContentText("Kamera liest die Kopfneigung, nur auf dem Handy.")
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
        // Your own nod (calibrated in the launcher): start at half of it, keep going until back
        // within 30 %; two readings in a row to start. Neutral follows posture only slowly.
        float frac = MainActivity.nodFraction(prefs, d);
        boolean sameWay = speed != 0 && Math.signum(speed) == (d > 0 ? -1 : 1);
        if (frac < (sameWay ? 0.4f : 0.6f)) {
            overN = 0;
            if (frac < 0.4f) base = base * 0.997f + pitch * 0.003f;
            setSpeed(0);
            return;
        }
        if (speed == 0 && ++overN < 2) return;
        float s = MainActivity.nodSpeed(frac);
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
        // Wide calm middle: start only at 70 % of the way to your calibrated top / bottom,
        // stop again under 45 %.
        if (frac < (sameWay ? 0.45f : 0.7f)) {
            setSpeed(0);
            return;
        }
        setSpeed(dir * (0.4f + 0.6f * Math.min(1f, (frac - 0.45f) / 0.55f)));
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
    private void swipe(float s, long ms) {
        if (systemOnTop()) {
            setSpeed(0);
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float h = dm.heightPixels, x = dm.widthPixels * 0.5f, y = h * 0.55f;
        float dist = h * (0.04f + 0.26f * Math.min(1f, Math.abs(s)));
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, s > 0 ? y - dist : y + dist);   // finger up = content scrolls down
        // Our own swipe also shows up as a touch outside the pill; don't let it pause us.
        ownGestureUntil = SystemClock.uptimeMillis() + ms + 120;
        swipesN++;
        try {
            boolean ok = dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, ms)).build(), new GestureResultCallback() {
                    @Override
                    public void onCancelled(GestureDescription g) {
                        // Your finger landed while our swipe ran (the system cancels ours then):
                        // that's you scrolling yourself, so rest like after any touch.
                        DiagLog.log("swipe cancelled by a touch: pause");
                        main.post(() -> userTouched());
                    }
                }, null);
            if (!ok) DiagLog.log("swipe rejected (gestures not allowed?)");
        } catch (RuntimeException e) {
            DiagLog.log("swipe failed: " + e);
        }
    }

    /* ---------------- Status ring around the front camera ---------------- */

    /**
     * Instead of a pill (which ended up in the navigation bar), the state is an orange ring around
     * the front camera: solid = ready, a running arc = scrolling (up / down), dim = paused or your
     * finger is in charge, two rings = BLICK. Triple-tap the camera to pause / resume, long-press
     * to switch NICKEN ↔ BLICK. Errors come as a short message.
     */
    private final class CameraRing extends android.view.View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF arc = new android.graphics.RectF();
        float holeR;

        CameraRing() {
            super(ScrollAssistService.this);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = holeR + dp(5);
            long now = SystemClock.uptimeMillis();
            boolean resting = now < pauseUntil;
            int rust = 0xFFE0552F;
            if (error) {
                p.setColor(0x99A0A0A0);
                p.setStrokeWidth(dp(2));
                p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3), dp(3)}, 0));
                c.drawCircle(cx, cy, r, p);
                p.setPathEffect(null);
                return;
            }
            float breathe = 0.75f + 0.25f * (float) Math.sin(now / 420.0);
            int alpha = paused ? 80 : resting ? 140 : (int) (255 * breathe);
            p.setColor((alpha << 24) | (rust & 0xFFFFFF));
            p.setStrokeWidth(dp(2.5f));
            c.drawCircle(cx, cy, r, p);
            // Just unlocked: the lock screen's rainbow ring first, melting into the orange.
            if (now < rainbowUntil) {
                float k = Math.min(1f, (rainbowUntil - now) / 800f);
                android.graphics.SweepGradient sw = new android.graphics.SweepGradient(cx, cy,
                    new int[]{0xFFFF3B3B, 0xFFFF9A1F, 0xFFFFE31F, 0xFF2BD96B, 0xFF1FA8FF, 0xFF8A4BFF, 0xFFFF3BB0, 0xFFFF3B3B}, null);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setRotate(-(now % 2000) * 0.18f * 2, cx, cy);
                sw.setLocalMatrix(m);
                p.setShader(sw);
                p.setAlpha((int) (255 * k));
                p.setStrokeWidth(dp(3));
                c.drawCircle(cx, cy, r, p);
                p.setShader(null);
            }
            if (mode.equals("gaze")) c.drawCircle(cx, cy, r + dp(4), p);
            if (!paused && !resting && speed != 0) {
                // A bright arc running round: clockwise = down, anticlockwise = up.
                p.setColor(0xFFFFB27A);
                p.setStrokeWidth(dp(4));
                float a = (now / 3f * Math.signum(speed)) % 360;
                arc.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(arc, a, 70, false, p);
            }
            postInvalidateOnAnimation();
        }
    }

    private boolean error;
    private long rainbowUntil;
    private final long[] ringTaps = new long[3];
    private float downX, downY;

    /** Long hold on the ring: the launcher's nod calibration (live camera, dots, confirm / redo). */
    private final Runnable ringHold = () -> {
        if (pill != null) pill.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        android.content.Intent cal = new android.content.Intent(MainActivity.CALIBRATE_NOD)
            .setClass(this, MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(cal);
        } catch (RuntimeException e) {
            DiagLog.log("ring calibrate failed: " + e);
        }
    };

    private void showPill(String message) {
        if (windows == null) return;
        if (pill == null) {
            CameraRing ring = new CameraRing();
            ring.setClickable(true);
            ring.setContentDescription("Kopf-Scroll: dreimal tippen = Pause");
            ring.setOnTouchListener((v, e) -> {
                // Every touch elsewhere on the screen: your finger is in charge for a moment.
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_OUTSIDE) {
                    if (SystemClock.uptimeMillis() > ownGestureUntil) userTouched();
                    return true;
                }
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                    main.removeCallbacks(ringHold);
                }
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > dp(16)) {
                    main.removeCallbacks(ringHold);
                }
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    main.removeCallbacks(ringHold);
                    main.postDelayed(ringHold, 650);
                    long now = SystemClock.uptimeMillis();
                    System.arraycopy(ringTaps, 1, ringTaps, 0, 2);
                    ringTaps[2] = now;
                    if (ringTaps[0] > 0 && now - ringTaps[0] < 800) {
                        ringTaps[0] = ringTaps[1] = ringTaps[2] = 0;
                        paused = !paused;
                        setSpeed(0);
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                        android.widget.Toast.makeText(this, paused ? "Kopf-Scroll pausiert" : "Kopf-Scroll läuft",
                            android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
                return false;
            });
            pill = ring;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 30) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                lp.setFitInsetsTypes(0);
            }
            placeRing(lp);
            try {
                windows.addView(pill, lp);
            } catch (RuntimeException e) {
                pill = null;
                return;
            }
        }
        error = message != null && message.startsWith("○");
        if (error) android.widget.Toast.makeText(this, message.substring(1).trim(), android.widget.Toast.LENGTH_LONG).show();
        pill.invalidate();
    }

    /** Centre the ring window on the camera hole (from the display cutout; top centre as a fallback). */
    private void placeRing(WindowManager.LayoutParams lp) {
        android.graphics.Rect hole = null;
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.DisplayCutout cut = windows.getCurrentWindowMetrics().getWindowInsets().getDisplayCutout();
            // The bounding rect runs up to the screen edge; the cutout path is the hole itself.
            if (cut != null && Build.VERSION.SDK_INT >= 31 && cut.getCutoutPath() != null) {
                android.graphics.RectF b = new android.graphics.RectF();
                cut.getCutoutPath().computeBounds(b, true);
                if (!b.isEmpty()) hole = new android.graphics.Rect(Math.round(b.left), Math.round(b.top), Math.round(b.right), Math.round(b.bottom));
            }
            if (hole == null && cut != null && !cut.getBoundingRects().isEmpty()) hole = cut.getBoundingRects().get(0);
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float cx = hole != null ? hole.exactCenterX() : dm.widthPixels / 2f;
        float cy = hole != null ? hole.exactCenterY() : dp(20);
        float holeR = hole != null ? Math.max(hole.width(), hole.height()) / 2f : dp(8);
        int size = Math.round(holeR * 2 + dp(36));
        lp.width = size;
        lp.height = size;
        lp.x = Math.round(cx - size / 2f);
        lp.y = Math.round(cy - size / 2f);
        if (pill instanceof CameraRing) ((CameraRing) pill).holeR = holeR;
        DiagLog.log("camera ring at " + Math.round(cx) + "," + Math.round(cy) + " hole r=" + Math.round(holeR));
    }

    /** Full-screen, untouchable overlay for the corner LEDs; they light up only in the dark. */
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

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // Rotation moves the camera hole: follow it.
        if (pill != null && windows != null) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) pill.getLayoutParams();
            placeRing(lp);
            try {
                windows.updateViewLayout(pill, lp);
            } catch (RuntimeException ignored) {
            }
        }
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
        if (instance == this) instance = null;
        try {
            unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
        }
        stopTracking();
        hidePill();
        if (head != null) head.close();
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
