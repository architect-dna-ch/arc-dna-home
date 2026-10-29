package ch.architectdna.home;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.SweepGradient;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.DisplayCutout;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ARC-DNA Live: the blueprint grid and cube, light or dark with the system.
 * On the lock screen it adds a pulsing rainbow ring with glitter around the fingerprint sensor,
 * a rotating rainbow ring around the front camera, and rave circles when the music's beat drops.
 * At home it stays calm (only faint rust rings on the beat). Animates only while visible.
 */
public class ArcWallpaper extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new ArcEngine();
    }

    private final class ArcEngine extends Engine {
        // Pixel 9a: under-display fingerprint sensor centre, as a fraction of the screen.
        private static final float FP_X = 0.5f, FP_Y = 0.7245f;   // measured on the lock icon in a screenshot

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Matrix matrix = new Matrix();
        private final Random random = new Random();
        private final List<float[]> rings = new ArrayList<>();   // x, y, radius, alpha, hue, rave(1/0)
        private final float[][] glitter = new float[44][];       // angle, radius factor, hue, phase, speed, size
        private int width, height;
        private boolean visible;
        private Rect camera;
        private Visualizer visualizer;
        private float bassAvg, beat;
        private long lastBeat, lastFft, lastRestart;

        private final Runnable frame = this::drawFrame;

        ArcEngine() {
            for (int i = 0; i < glitter.length; i++) {
                glitter[i] = new float[]{random.nextFloat() * 6.283f, 1.15f + random.nextFloat() * 1.1f,
                    random.nextFloat() * 360f, random.nextFloat() * 6.283f, 1.5f + random.nextFloat() * 3f,
                    1.5f + random.nextFloat() * 3.5f};
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            width = w;
            height = h;
            locateCamera();
            drawFrame();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (camera != null) return;          // the display's own cutout (below) is more precise
            DisplayCutout cutout = insets.getDisplayCutout();
            Rect r = cutout != null && !cutout.getBoundingRects().isEmpty() ? cutout.getBoundingRects().get(0) : null;
            if (plausibleCamera(r)) camera = r;
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            watchTilt(v);
            DiagLog.init(ArcWallpaper.this);
            DiagLog.log("wallpaper visible=" + v + " locked=" + locked()
                + (Build.VERSION.SDK_INT >= 34 ? " flags=" + getWallpaperFlags() : "") + " preview=" + isPreview());
            if (v) {
                startVisualizer();
                drawFrame();
            } else {
                handler.removeCallbacks(frame);
                stopVisualizer();
            }
        }

        @Override
        public void onDestroy() {
            try {
                unregisterReceiver(unlock);
            } catch (RuntimeException ignored) {
            }
            watchTilt(false);
            watchService(false);
            handler.removeCallbacks(frame);
            stopVisualizer();
        }

        /** A front camera hole on this surface: near the top edge, roughly centred, small. */
        private boolean plausibleCamera(Rect r) {
            if (r == null || width <= 0 || height <= 0) return false;
            return r.exactCenterY() < height * 0.12f && r.exactCenterX() > width * 0.15f && r.exactCenterX() < width * 0.85f
                && r.width() < width * 0.3f;
        }

        /**
         * The camera hole straight from the display's cutout (the wallpaper window doesn't always get
         * insets on the lock screen), scaled in case the wallpaper surface isn't the display size.
         */
        private void locateCamera() {
            try {
                android.hardware.display.DisplayManager dm = getSystemService(android.hardware.display.DisplayManager.class);
                android.view.Display d = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                DisplayCutout cut = d == null || Build.VERSION.SDK_INT < 29 ? null : d.getCutout();
                android.graphics.Point real = new android.graphics.Point();
                if (d != null) d.getRealSize(real);
                Rect r = null;
                if (cut != null && Build.VERSION.SDK_INT >= 31 && cut.getCutoutPath() != null) {
                    android.graphics.RectF b = new android.graphics.RectF();
                    cut.getCutoutPath().computeBounds(b, true);
                    r = new Rect(Math.round(b.left), Math.round(b.top), Math.round(b.right), Math.round(b.bottom));
                } else if (cut != null && !cut.getBoundingRects().isEmpty()) {
                    r = cut.getBoundingRects().get(0);
                }
                if (r != null && real.x > 0 && width > 0) {
                    float sx = width / (float) real.x, sy = height / (float) real.y;
                    Rect scaled = new Rect(Math.round(r.left * sx), Math.round(r.top * sy), Math.round(r.right * sx), Math.round(r.bottom * sy));
                    // Read while the phone was in landscape, the hole sits on a side edge: keep the
                    // last good (portrait) position instead of drawing the ring mid-screen.
                    if (plausibleCamera(scaled)) camera = scaled;
                }
                DiagLog.init(ArcWallpaper.this);
                DiagLog.log("wallpaper surface " + width + "x" + height + ", display " + real.x + "x" + real.y
                    + ", camera " + (camera == null ? "?" : camera.toShortString()));
            } catch (RuntimeException e) {
                DiagLog.log("camera position: " + e);
            }
        }

        /**
         * Dark like the home. The wallpaper service's own configuration can lag behind (it stayed light
         * on the lock screen while the home was dark): also ask the system's configuration, the dark
         * theme setting and battery saver (which turns the dark theme on).
         */
        private boolean nightCache;
        private long nightAt;

        private boolean night() {
            long now = SystemClock.uptimeMillis();
            if (now - nightAt < 1000) return nightCache;       // asked many times per frame
            nightAt = now;
            nightCache = nightNow();
            return nightCache;
        }

        private boolean nightNow() {
            // The home says what it shows (written on every theme change): follow it, so a light home never
            // sits on a dark wallpaper (battery saver turning off flipped the home light, but an old "dark"
            // from the service's lagging configuration kept the wallpaper dark).
            android.content.SharedPreferences hp = getSharedPreferences("home", MODE_PRIVATE);
            if (hp.contains("uiNight") && System.currentTimeMillis() - hp.getLong("uiNightAt", 0) < 10 * 60_000L)
                return hp.getBoolean("uiNight", false);
            int mask = Configuration.UI_MODE_NIGHT_MASK, yes = Configuration.UI_MODE_NIGHT_YES;
            return (android.content.res.Resources.getSystem().getConfiguration().uiMode & mask) == yes;
        }

        /**
         * Lock effects only while the keyguard is really up: asked every frame, so they vanish the
         * moment you unlock instead of riding along the unlock animation (even on the lock-only engine).
         */
        private boolean locked() {
            if (unlockedAt > 0 && SystemClock.uptimeMillis() - unlockedAt < 1500) return false;
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        }

        /** USER_PRESENT arrives as soon as the phone is unlocked: drop the lock effects right away. */
        private long unlockedAt;
        private final android.content.BroadcastReceiver unlock = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, android.content.Intent i) {
                unlockedAt = SystemClock.uptimeMillis();
                rings.clear();
                if (visible) drawFrame();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            watchService(true);
            try {
                registerReceiver(unlock, new android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT));
            } catch (RuntimeException ignored) {
            }
        }

        private void drawFrame() {
            handler.removeCallbacks(frame);
            if (!visible || width == 0) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            boolean lock = locked();
            try {
                c = holder.lockHardwareCanvas();
                if (c != null) draw(c, lock);
            } catch (RuntimeException e) {
                c = null;
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }
            // The output-mix visualizer goes quiet after a pause; reconnect while music plays.
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            long t = SystemClock.uptimeMillis();
            if (am != null && am.isMusicActive() && t - lastFft > 2000 && t - lastRestart > 3000) {
                lastRestart = t;
                DiagLog.log("wallpaper visualizer reconnect, lock=" + lock);
                stopVisualizer();
                startVisualizer();
            }
            // Smooth while something moves; otherwise just watch for the lock state twice a second.
            boolean busy = lock || chargeLevel >= 0 || SystemClock.uptimeMillis() - unplugAt < UNPLUG || !rings.isEmpty() || cubeMoving() || SystemClock.uptimeMillis() - unlockedAt < 1200;
            handler.postDelayed(frame, busy ? 33 : 500);
        }

        private java.util.Map<Long, Integer> pixels = new java.util.HashMap<>();
        private long pixelsChecked, pixelsMod = -1;

        /*
         * No mobile network ("Notrufe only"): the lock screen panics – the whole scene trembles,
         * a red / blue siren sweeps the edges and a warning blinks. Calms down by itself once the
         * phone has service again; only the mobile network counts – that's what calls need.
         */
        private volatile boolean noService;
        private Object serviceCallback;

        private void watchService(boolean on) {
            android.telephony.TelephonyManager tm = getSystemService(android.telephony.TelephonyManager.class);
            if (tm == null || android.os.Build.VERSION.SDK_INT < 31) return;
            try {
                if (on && serviceCallback == null) {
                    final class Cb extends android.telephony.TelephonyCallback implements android.telephony.TelephonyCallback.ServiceStateListener {
                        @Override
                        public void onServiceStateChanged(android.telephony.ServiceState st) {
                            boolean none = st.getState() != android.telephony.ServiceState.STATE_IN_SERVICE
                                && st.getState() != android.telephony.ServiceState.STATE_POWER_OFF;      // airplane mode isn't a panic
                            if (none != noService) DiagLog.log("wallpaper: mobile service " + (none ? "lost – panic" : "back"));
                            noService = none && tm.getSimState() != android.telephony.TelephonyManager.SIM_STATE_ABSENT;   // a SIM, but no network
                        }
                    }
                    Cb cb = new Cb();
                    tm.registerTelephonyCallback(getMainExecutor(), cb);
                    serviceCallback = cb;
                } else if (!on && serviceCallback != null) {
                    tm.unregisterTelephonyCallback((android.telephony.TelephonyCallback) serviceCallback);
                    serviceCallback = null;
                }
            } catch (RuntimeException e) {
                DiagLog.log("wallpaper service watch: " + e.getMessage());
            }
        }

        private boolean lockedNow;

        private void draw(Canvas c, boolean lock) {
            lockedNow = lock;
            boolean panic = lock && noService;
            if (!panic) {
                drawScene(c, lock);
                if (lock) drawHeart(c);
                return;
            }
            float t = SystemClock.uptimeMillis() / 1000f, s = Math.min(width, height) / 1080f;
            c.save();
            c.translate((float) Math.sin(t * 47) * 6 * s + (random.nextFloat() - 0.5f) * 4 * s, (float) Math.cos(t * 39) * 5 * s);
            c.rotate((float) Math.sin(t * 23) * 0.6f, width / 2f, height / 2f);
            drawScene(c, lock);
            c.restore();
            // Siren: red and blue sweeping round the edges, alternating.
            boolean red = ((int) (t * 3)) % 2 == 0;
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * 12);
            int col = red ? 0xFFE8231A : 0xFF2A6BFF;
            paint.setShader(new android.graphics.RadialGradient(width / 2f, height / 2f, Math.max(width, height) * 0.75f,
                new int[]{0x00000000, 0x00000000, (col & 0x00FFFFFF) | ((int) (120 + 90 * pulse) << 24)}, new float[]{0, 0.55f, 1},
                android.graphics.Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            // The warning, blinking, between the unlock button and the buddy (higher up the lock screen's
            // own clock and media card covered it).
            if (((int) (t * 2.5f)) % 3 != 2) {          // the club banner stays (loved)
                float y = height * 0.81f;
                paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
                paint.setTextSize(44 * s);
                paint.setTextAlign(Paint.Align.CENTER);
                String a = "⚠ KEIN NETZ · NUR NOTRUFE ⚠";
                float tw = paint.measureText(a) + 60 * s;
                paint.setColor(0xE6120808);
                c.drawRoundRect(width / 2f - tw / 2, y - 64 * s, width / 2f + tw / 2, y + 26 * s, 30 * s, 30 * s, paint);
                paint.setColor(col);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5 * s);
                c.drawRoundRect(width / 2f - tw / 2, y - 64 * s, width / 2f + tw / 2, y + 26 * s, 30 * s, 30 * s, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                c.drawText(a, width / 2f + (random.nextFloat() - 0.5f) * 4 * s, y - 6 * s, paint);
                paint.setTextAlign(Paint.Align.LEFT);
            }
        }

        /** Your heart line (pinned in "Gelassen") on the lock screen: a calm card in the lower third. */
        private void drawHeart(Canvas c) {
            String q = getSharedPreferences("home", MODE_PRIVATE).getString("heartQuote", "");
            if (q.isEmpty()) return;
            boolean dark = night();
            float s = Math.min(width, height) / 1080f, maxW = Math.min(width * 0.8f, 900 * s);
            android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(dark ? 0xFFF4F2EC : 0xFF1A1A1A);
            tp.setTextSize(44 * s);
            tp.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            android.text.StaticLayout lay = android.text.StaticLayout.Builder.obtain(q, 0, q.length(), tp, (int) maxW)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.12f).setMaxLines(4)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build();
            float pad = 40 * s, head = 56 * s, h = lay.getHeight() + head + pad * 2, w = maxW + pad * 2;
            float left = (width - w) / 2, top = height * 0.68f - h / 2;
            // A soft breathing glow: the card pulses very slowly, like a heartbeat at rest.
            float beat = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 1000f * 1.1f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dark ? 0xC8121211 : 0xD9FAFAF8);
            c.drawRoundRect(left, top, left + w, top + h, 34 * s, 34 * s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * s);
            paint.setColor(((int) (90 + 120 * beat) << 24) | 0xC9402F);
            c.drawRoundRect(left, top, left + w, top + h, 34 * s, 34 * s, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
            paint.setTextSize(24 * s);
            paint.setLetterSpacing(0.22f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(dark ? 0xFFC9402F : 0xFFB5271E);
            c.drawText("♥ FÜR DICH", width / 2f, top + pad + 22 * s, paint);
            paint.setLetterSpacing(0);
            paint.setTextAlign(Paint.Align.LEFT);
            c.save();
            c.translate(left + pad, top + pad + head);
            lay.draw(c);
            c.restore();
        }

        private void drawScene(Canvas c, boolean lock) {
            boolean dark = night();
            int bg = dark ? 0xFF121211 : 0xFFFAFAF8;
            int ink = dark ? 0xECEBE6 : 0x1A1A1A;
            int rust = dark ? 0xFFC9402F : 0xFFB5271E;
            // Sized by the shorter side, so a landscape tablet doesn't get a giant cube.
            float s = Math.min(width, height) / 1080f;
            float t = SystemClock.uptimeMillis() / 1000f;
            c.drawColor(bg);

            // Blueprint grid.
            paint.setStyle(Paint.Style.STROKE);
            paint.setShader(null);
            paint.setStrokeWidth(2 * s);
            paint.setColor(0x0E000000 | ink);
            float cell = 84 * s;
            // A glass orb in the middle of the cube bends everything around it (grid, cube edges), with
            // colour fringes at the rim, like a lens. It breathes with the music.
            float cubeScale = 760 * s / 14f * 6f / (float) Math.sqrt(2);
            lensX = width / 2f;
            lensY = height * 0.52f;
            lensR = cubeScale * 0.85f * (1 + 0.04f * (float) Math.sin(t * 1.3f) + 0.12f * beat);
            orbBack(c, dark, t);
            paint.setStrokeWidth(2 * s);
            paint.setColor(0x0E000000 | ink);
            for (float x = -2 * s; x < width; x += cell) lensLine(c, x, 0, x, height, false);
            for (float y = 40 * s; y < height; y += cell) lensLine(c, 0, y, width, y, false);
            // Your pixel art in the grid's squares (PixelActivity), the grid still showing through.
            long nowMs = SystemClock.uptimeMillis();
            if (nowMs - pixelsChecked > 1000) {
                pixelsChecked = nowMs;
                long mod = PixelArt.file(ArcWallpaper.this).lastModified();
                if (mod != pixelsMod) {
                    pixelsMod = mod;
                    pixels = PixelArt.load(ArcWallpaper.this);
                }
            }
            PixelArt.draw(c, pixels, s, paint, dark ? 150 : 140);
            paint.setStyle(Paint.Style.STROKE);

            // The cube, as on the website wallpaper, but real 3D: two fingers on the home screen turn it.
            // The orb's rim and specks first, then the cube: its edges and letters stay on top (the blue
            // circles ran through the letters).
            orbFront(c, dark, t, s);
            charging(c, t, s);
            stepCube();
            drawCube(c, width / 2f, height * 0.52f, cubeScale, ink, rust);

            // Rave / beat rings (none in the professional look).
            beat *= 0.9f;
            if (getSharedPreferences("home", MODE_PRIVATE).getBoolean("pro", false)) rings.clear();
            for (int i = rings.size() - 1; i >= 0; i--) {
                float[] r = rings.get(i);
                r[2] += (r[5] > 0 ? 26 : 14) * s;
                r[3] *= r[5] > 0 ? 0.955f : 0.94f;
                if (r[3] < 0.02f) {
                    rings.remove(i);
                    continue;
                }
                paint.setShader(null);
                paint.setStrokeWidth((r[5] > 0 ? 7 : 3) * s);
                int col = r[5] > 0 ? Color.HSVToColor(new float[]{(r[4] + t * 90) % 360, 0.85f, 1f}) : rust;
                paint.setColor((col & 0x00FFFFFF) | ((int) (r[3] * 255) << 24));
                c.drawCircle(r[0], r[1], r[2], paint);
            }

            // Just unlocked: the lock under the camera springs open and fades, the rainbow ring with it.
            long sinceUnlock = SystemClock.uptimeMillis() - unlockedAt;
            if (!lock && unlockedAt > 0 && sinceUnlock < 1100 && camera != null) {
                float k = sinceUnlock / 1100f, fade = 1 - Math.max(0, (k - 0.45f) / 0.55f);
                float cx = camera.exactCenterX(), cy = camera.exactCenterY();
                float cr = Math.max(camera.width(), camera.height()) / 2f * 1.7f;
                rainbowRing(c, cx, cy, cr, 5 * s, -t * 160, 0.9f * fade);
                paint.setShader(null);
                lockIcon(c, cx, cy + cr + 44 * s, s, Math.min(1, k / 0.3f), fade, 0xFF000000 | ink, rust);
            }

            if (!lock) return;

            // Fingerprint: pulsing rainbow halo plus glitter (only where we know the sensor: Pixel, portrait).
            if (pixelPortrait()) fingerprint(c, s, t);

            // Front camera: rotating rainbow ring (only around a real camera hole).
            if (camera != null) {
                float cx = camera.exactCenterX(), cy = camera.exactCenterY();
                float cr = Math.max(camera.width(), camera.height()) / 2f * 1.7f
                    * (1 + 0.06f * (float) Math.sin(t * 3.1f) + 0.25f * beat);
                rainbowRing(c, cx, cy, cr, 5 * s, -t * 160, 0.9f);
                paint.setShader(null);
                // Face ID style: a closed lock waits under the camera until the phone is unlocked.
                lockIcon(c, cx, cy + cr + 44 * s, s, 0, 1, 0xFF000000 | ink, rust);
            }

            // While music plays: a rainbow flowing along the screen edge, and a little stage bottom-left where a
            // character takes turns every 15 s.
            if (audio == null) audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null && audio.isMusicActive()) {
                musicFrame(c, s, t);
                stage(c, s, dark, ink, rust);
            }
        }

        /** A small padlock; open 0 = shut, 1 = shackle swung open (then rust). */
        private void lockIcon(Canvas c, float x, float y, float s, float open, float alpha, int ink, int rust) {
            if (alpha <= 0.01f) return;
            float u = 20 * s;
            int col = open >= 1 ? rust : ink;
            int a = (int) (255 * alpha);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(col);
            paint.setAlpha(a);
            box.set(x - u, y - u * 0.2f, x + u, y + u * 1.3f);
            c.drawRoundRect(box, u * 0.3f, u * 0.3f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(u * 0.28f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            c.save();
            c.translate(0, -open * u * 0.35f);
            c.rotate(-open * 35, x + u * 0.62f, y - u * 0.2f);
            box.set(x - u * 0.62f, y - u * 1.15f, x + u * 0.62f, y + u * 0.1f);
            c.drawArc(box, 180, 180, false, paint);
            c.drawLine(x - u * 0.62f, y - u * 0.52f, x - u * 0.62f, y - u * 0.2f, paint);
            c.drawLine(x + u * 0.62f, y - u * 0.52f, x + u * 0.62f, y - u * 0.2f, paint);
            c.restore();
            paint.setAlpha(255);
        }

        /** The lock screen layout the fingerprint ring and media frame were measured on. */
        private boolean pixelPortrait() {
            return Build.MODEL != null && Build.MODEL.startsWith("Pixel") && height > width;
        }

        private void fingerprint(Canvas c, float s, float t) {
            float fx = width * FP_X, fy = height * FP_Y, r0 = width * 0.085f;
            float pulse = r0 * (1.35f + 0.08f * (float) Math.sin(t * 3.9f) + 0.35f * beat);
            rainbowRing(c, fx, fy, pulse, 9 * s, t * 120, 0.95f);
            rainbowRing(c, fx, fy, pulse * 1.12f, 22 * s, t * 120, 0.22f);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            for (float[] g : glitter) {
                float a = (float) (0.5 + 0.5 * Math.sin(t * g[4] + g[3]));
                float ang = g[0] + t * 0.25f, rr = r0 * g[1] * (1 + 0.15f * beat);
                int col = Color.HSVToColor(new float[]{(g[2] + t * 60) % 360, 0.7f, 1f});
                paint.setColor((col & 0x00FFFFFF) | ((int) (a * 230) << 24));
                c.drawCircle(fx + (float) Math.cos(ang) * rr, fy + (float) Math.sin(ang) * rr, g[5] * s * (0.6f + a), paint);
            }
            paint.setStyle(Paint.Style.STROKE);
        }

        /* ---- Lock screen while music plays ---- */

        private android.media.AudioManager audio;
        private final android.graphics.RectF box = new android.graphics.RectF();

        /**
         * While music plays: a rainbow flowing along the whole screen edge, following the glass's
         * rounded corners. (It used to frame the system's media card, but Android moves that card
         * around with the clock size and notifications, so the frame kept landing in the wrong place.)
         */
        private void musicFrame(Canvas c, float s, float t) {
            // A little in from the glass, with the corners rounder than the reported radius so the
            // whole curve stays visible on the physical corner.
            // Right on the glass edge (it used to sit 8 px in, leaving a pale gap to the phone's edge):
            // the line's outer side touches the screen edge, the corners follow the display's own radius.
            float lw = (5 + 3 * beat) * s, in = lw / 2;
            box.set(in, in, width - in, height - in);
            float r = Math.max(0, screenCorner() / 1.1f - in);
            int[] hues = {0xFFFF3B3B, 0xFFFF9A1F, 0xFFFFE31F, 0xFF2BD96B, 0xFF1FA8FF, 0xFF8A4BFF, 0xFFFF3BB0, 0xFFFF3B3B};
            android.graphics.LinearGradient flow = new android.graphics.LinearGradient(0, 0, width * 0.9f, height * 0.4f,
                hues, null, android.graphics.Shader.TileMode.REPEAT);
            matrix.setTranslate((t * width * 0.35f) % (width * 0.9f), 0);
            flow.setLocalMatrix(matrix);
            paint.setShader(flow);
            paint.setStyle(Paint.Style.STROKE);
            // A soft wide glow, then the crisp line; it breathes with the beat.
            paint.setStrokeWidth((18 + 10 * beat) * s);
            paint.setAlpha(55);
            c.drawRoundRect(box, r, r, paint);
            paint.setStrokeWidth(lw);
            paint.setAlpha(235);
            c.drawRoundRect(box, r, r, paint);
            paint.setAlpha(255);
            paint.setShader(null);
        }

        private float screenCorner() {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    android.hardware.display.DisplayManager dm = getSystemService(android.hardware.display.DisplayManager.class);
                    android.view.Display d = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                    android.view.RoundedCorner rc = d == null ? null : d.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT);
                    if (rc != null && rc.getRadius() > 0) return rc.getRadius() * 1.1f;
                } catch (RuntimeException ignored) {
                }
            }
            return width * 0.11f;
        }

        /** Every 15 s: bear → F1 car → rabbit → fox. Gibberish first, then the "translation". */
        private static final String[][][] LINES = {
            {{"Grrmmph brummel-wumm!", "Schau mich nicht an, sonst fress ich dich."},
                {"Hrrr… mmmh… Hoonig?", "Hast du Honig? Nein? Dann schlaf ich weiter."},
                {"Wuuoorrgh. Mpf.", "Wer hat mich geweckt? Ah, du. Natürlich."}},
            {{"Nyeeeooowww-ng-ng!", "Ich bin schneller als dein Entsperren."},
                {"Brrm brrm pfft.", "Box, box! Reifenwechsel im Hosensack."},
                {"Wrrraaap-tak-tak!", "Das war Sektor drei. Lila. Natürlich."}},
            {{"Mümmel-mümmel tick?", "Ich sehe nichts. Ich bin ein Busch."},
                {"Tipp-tipp-tapp-hüpf!", "Der Fuchs ist hinter mir, oder? ODER?"},
                {"Snff snff… mmh.", "Riecht nach Karotten. Oder nach Bass."}},
            {{"Gekk-gekk-ack!", "Ich hab dein Passwort nicht gesehen. Ehrlich."},
                {"Wa-pa-pa-pa-pow!", "Was sagt der Fuchs? Genau das."},
                {"Yip! Yip-yip!", "Dieser Beat ist besser als Hasenjagd."}}
        };

        private android.graphics.drawable.Drawable foxArt, rabbitArt;

        /*
         * Panic: the buddy (the bear) stays, twice the size, and points with THREE arms at the
         * "Emergency calls only" label top left, explaining why everything shakes.
         */
        private void panicStage(Canvas c, float s, boolean dark, int ink, int rust) {
            float t = SystemClock.uptimeMillis() / 1000f;
            float size = Math.min(width, height) * 0.34f;
            float x = width * 0.05f, y = height * 0.64f;            // above the unlock button, the banner below it
            float jump = Math.abs((float) Math.sin(t * 7)) * size * 0.05f;
            // Three short arms (cartoon, not spaghetti), all pointing up-left at the label, waving;
            // chevrons fly from the bear towards it.
            float tx = width * 0.2f, ty = 40 * s;
            float hx = x + size * 0.5f, hy = y - size * 0.1f - jump;
            float dirx = tx - hx, diry = ty - hy, dl = (float) Math.max(1, Math.hypot(dirx, diry));
            dirx /= dl;
            diry /= dl;
            paint.setShader(null);
            paint.setStrokeCap(Paint.Cap.ROUND);
            // Arms are drawn after the bear (see below), from its sides – never out of the top of
            // its head (that one looked like a rude finger).
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5 * s);
            for (int k = 0; k < 4; k++) {
                float f = ((t * 0.8f + k / 4f) % 1f);
                float cx2 = hx + (tx - hx) * (0.25f + f * 0.65f), cy2 = hy + (ty - hy) * (0.25f + f * 0.65f);
                paint.setColor(((int) (220 * Math.sin(f * Math.PI)) << 24) | 0xE8231A);
                float a = 22 * s, px = -diry, py = dirx;
                c.drawLine(cx2 - dirx * a + px * a, cy2 - diry * a + py * a, cx2, cy2, paint);
                c.drawLine(cx2 - dirx * a - px * a, cy2 - diry * a - py * a, cx2, cy2, paint);
            }
            // A blinking ring round the status bar label.
            if (((int) (t * 3)) % 2 == 0) {
                paint.setStrokeWidth(5 * s);
                paint.setColor(0xFFE8231A);
                c.drawRoundRect(14 * s, 8 * s, width * 0.5f, 74 * s, 34 * s, 34 * s, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            c.save();
            c.translate(x, y - jump);
            bear(c, size, dark);
            c.restore();
            // Two arms on the left point up-left at the label, one on the right waves for help.
            // Round mitten hands, no single fingers.
            float[][] arms = {{x + size * 0.08f, y + size * 0.48f, 0}, {x + size * 0.1f, y + size * 0.75f, 0}, {x + size * 0.92f, y + size * 0.55f, 1}};
            for (int i = 0; i < 3; i++) {
                float sx = arms[i][0], sy = arms[i][1] - jump;
                float ax, ay;
                if (arms[i][2] == 0) {
                    float wave = (float) Math.sin(t * 8 + i * 2) * 0.2f;
                    float bx = dirx - 0.35f, by = diry, bl = (float) Math.hypot(bx, by);
                    bx /= bl;
                    by /= bl;
                    ax = bx * (float) Math.cos(wave) - by * (float) Math.sin(wave);
                    ay = bx * (float) Math.sin(wave) + by * (float) Math.cos(wave);
                } else {
                    double a2 = Math.toRadians(-40 + Math.sin(t * 10) * 25);       // waving, up and out to the right
                    ax = (float) Math.cos(a2);
                    ay = (float) Math.sin(a2);
                }
                float len = size * 0.36f, ex = sx + ax * len, ey = sy + ay * len;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(size * 0.12f);
                paint.setColor(dark ? 0xFF6B4A33 : 0xFF7A5236);
                c.drawLine(sx, sy, ex, ey, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(ex, ey, size * 0.085f, paint);
                paint.setColor(dark ? 0xFF8A6448 : 0xFF9A6B48);
                c.drawCircle(ex + ax * size * 0.02f, ey + ay * size * 0.02f, size * 0.045f, paint);      // the paw pad
            }
            // Why, in a bubble to the right.
            String said = "KEIN NETZ! Da oben steht's: nur Notrufe. Die SIM hat gerade keinen Empfang – deshalb zittert hier alles.";
            float bx = x + size * 1.1f, bw = width * 0.95f - bx, by = y - size * 0.35f;
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            paint.setTextSize(32 * s);
            List<String> rows = wrap(said, bw - 44 * s);
            float lineH = paint.getTextSize() * 1.25f, bh = rows.size() * lineH + 36 * s;
            box.set(bx, by - bh / 2, bx + bw, by + bh / 2);
            paint.setColor(0xF2201010);
            c.drawRoundRect(box, 22 * s, 22 * s, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * s);
            paint.setColor(0xFFE8231A);
            c.drawRoundRect(box, 22 * s, 22 * s, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            float ty2 = box.top + 18 * s + paint.getTextSize();
            for (String r : rows) {
                c.drawText(r, bx + 22 * s, ty2, paint);
                ty2 += lineH;
            }
        }

        private void stage(Canvas c, float s, boolean dark, int ink, int rust) {
            if (noService) {
                panicStage(c, s, dark, ink, rust);
                return;
            }
            long ms = System.currentTimeMillis();
            int slot = (int) ((ms / 15000) % 4);
            long cycle = ms / 60000;
            float k = (ms % 15000) / 1000f;                 // seconds into this character's turn
            // Slide in during the first second, out during the last.
            float in = Math.min(1f, k) * Math.min(1f, 15f - k);
            float size = Math.min(width, height) * 0.17f;
            float x = width * 0.06f - (1 - in) * size * 1.4f, y = height * 0.885f;   // under "See what's playing", above the shortcuts
            float bob = (float) Math.sin(k * 6) * size * 0.03f * (1 + beat);
            c.save();
            c.translate(x, y + bob);
            switch (slot) {
                case 0: bear(c, size, dark); break;
                case 1: car(c, size, dark ? 0xFFFFFF : ink, dark ? 0xFFFF6A4F : rust); break;
                case 2: art(c, size, false); break;
                default: art(c, size, true); break;
            }
            c.restore();

            String[] line = LINES[slot][(int) (cycle % LINES[slot].length)];
            // Easter egg: an assistant opened over the lock screen in the last 12 s → they talk about it.
            String who = ScrollAssistService.assistant;
            if (System.currentTimeMillis() - ScrollAssistService.assistantAt < 12_000 && !who.isEmpty()) {
                String[][] egg = ASSISTANT.get(who);
                if (egg != null) line = egg[slot];
            }
            if (k < 1.2f || k > 14f) return;
            boolean translated = k > 5f;
            // The robot translator gets it wrong about every third time, then corrects itself.
            boolean slips = (cycle * 7 + slot * 3) % 3 == 0;
            boolean wrong = translated && slips && k < 9.5f, fixed = translated && slips && k >= 9.5f;
            String said = !translated ? line[0]
                : wrong ? WRONG[slot][(int) (cycle % WRONG[slot].length)]
                : fixed ? "Oh, den hab ich falsch erwischt! Eigentlich: " + line[1]
                : line[1];
            // Speech bubble to the right.
            float bx = width * 0.06f + size * 1.15f, bw = Math.min(width * 0.94f - bx, Math.min(width, height) * 0.8f), by = y - size * 0.15f;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(translated ? android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                : android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD_ITALIC));
            paint.setTextSize((translated ? 34 : 30) * s);
            List<String> rows = wrap(said, bw - 44 * s);
            float lineH = paint.getTextSize() * 1.25f, bh = rows.size() * lineH + 36 * s + (translated ? 30 * s : 0);
            box.set(bx, by - bh / 2, bx + bw, by + bh / 2);
            // Dark mode: the lock screen dims the wallpaper, so the bubble is lighter and outlined in rust.
            int glow = dark ? 0xFFFF6A4F : rust;
            paint.setColor(dark ? 0xF2393632 : 0xF2FFFFFF);
            c.drawRoundRect(box, 22 * s, 22 * s, paint);
            path.reset();
            path.moveTo(bx + 2 * s, by - 12 * s);
            path.lineTo(bx - 18 * s, by + 4 * s);
            path.lineTo(bx + 2 * s, by + 14 * s);
            path.close();
            c.drawPath(path, paint);
            if (dark) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f * s);
                paint.setColor(glow);
                c.drawRoundRect(box, 22 * s, 22 * s, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            float ty = box.top + 18 * s + paint.getTextSize();
            if (translated) {
                paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
                paint.setTextSize(20 * s);
                paint.setColor(glow);
                c.drawText(fixed ? "ROBO, KORRIGIERT:" : "ROBO ÜBERSETZT:", bx + 22 * s, box.top + 18 * s + 20 * s, paint);
                paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
                paint.setTextSize(34 * s);
                ty += 30 * s;
            }
            paint.setColor(translated ? (dark ? 0xFFFFFFFF : 0xFF000000 | ink) : glow);
            for (String row : rows) {
                c.drawText(row, bx + 22 * s, ty, paint);
                ty += lineH;
            }
            // The translator robot sits on the bubble's top edge while it speaks.
            if (translated) {
                float rs = size * 0.42f;
                robot(c, box.right - rs * 1.1f, box.top - rs * 0.62f, rs, dark, glow, fixed && k < 10.6f, k);
            }
        }

        /** What each character says when Gemini / Claude / ChatGPT shows up (gibberish, translation). */
        private static final java.util.Map<String, String[][]> ASSISTANT = new java.util.HashMap<>();

        static {
            ASSISTANT.put("gemini", new String[][]{
                {"Grrr… blinke-blink?", "Gemini? Ich dachte, ich bin hier der Bunte."},
                {"Nyeeoow-PIT!", "Gemini im Rückspiegel. Ich lass ihn nicht vorbei."},
                {"Mümmel… bling?", "Ein Stern mit Glitzer! Kann der Karotten finden?"},
                {"Yip-yip-hmm?", "Frag Gemini, was der Fuchs sagt. Ich warte."}});
            ASSISTANT.put("claude", new String[][]{
                {"Brumm-brumm, Chef!", "Claude ist da. Alle so tun, als wär der Code fertig."},
                {"Vroom… tak.", "Der Ingenieur! Bitte keinen neuen Boxenstopp."},
                {"Tipp-tapp-psst!", "Claude hat mich gezeichnet. Ohren zu gross, danke."},
                {"Gekk! Gekk!", "Hallo Erbauer. Der Dino war nicht meine Idee."}});
            ASSISTANT.put("chatgpt", new String[][]{
                {"Hrrmpf!", "Die Konkurrenz. Ich schlaf weiter."},
                {"Pfft-pfft.", "Andere Box, anderes Team."},
                {"Snff?", "Riecht nach fremder KI."},
                {"Yip…?", "Ich sag nichts ohne meinen Anwalt."}});
        }

        /** Things the robot thinks they said, before it notices. */
        private static final String[][] WRONG = {
            {"Ich hätte gern einen Smoothie mit Glitzer.", "Bitte weck mich um fünf, ich will joggen."},
            {"Ich fahre heute ganz gemütlich, Tempo 30.", "Kann mir jemand das Lenkrad halten?"},
            {"Ich liebe den Fuchs. Er ist mein bester Freund.", "Karotten? Nein danke, ich esse nur Pizza."},
            {"Ich bin seit heute vegetarisch.", "Ich will einfach nur kuscheln."}};

        /**
         * The translator: a small square robot with two LED eyes, a speaking mouth (bars that move
         * with the words) and a blinking antenna. "oops": eyes turn into × and an ! pops up.
         */
        private void robot(Canvas c, float cx, float cy, float r, boolean dark, int glow, boolean oops, float k) {
            int body = dark ? 0xFFD8D6CF : 0xFF2A2A28, face = dark ? 0xFF2A2A28 : 0xFFEDEDE8;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            // Antenna with a blinking tip.
            paint.setColor(body);
            paint.setStrokeWidth(r * 0.08f);
            c.drawLine(cx, cy - r * 0.55f, cx, cy - r * 0.85f, paint);
            paint.setColor((int) (k * 3) % 2 == 0 ? glow : body);
            c.drawCircle(cx, cy - r * 0.9f, r * 0.11f, paint);
            // Head and face plate.
            paint.setColor(body);
            box2.set(cx - r * 0.62f, cy - r * 0.55f, cx + r * 0.62f, cy + r * 0.5f);
            c.drawRoundRect(box2, r * 0.18f, r * 0.18f, paint);
            paint.setColor(face);
            box2.set(cx - r * 0.5f, cy - r * 0.42f, cx + r * 0.5f, cy + r * 0.38f);
            c.drawRoundRect(box2, r * 0.12f, r * 0.12f, paint);
            // Ears.
            paint.setColor(glow);
            c.drawRect(cx - r * 0.74f, cy - r * 0.12f, cx - r * 0.62f, cy + r * 0.12f, paint);
            c.drawRect(cx + r * 0.62f, cy - r * 0.12f, cx + r * 0.74f, cy + r * 0.12f, paint);
            // Eyes.
            float ey = cy - r * 0.14f, ex = r * 0.22f, er = r * 0.09f;
            if (oops) {
                paint.setStrokeWidth(r * 0.06f);
                for (int side = -1; side <= 1; side += 2) {
                    float x = cx + side * ex;
                    c.drawLine(x - er, ey - er, x + er, ey + er, paint);
                    c.drawLine(x - er, ey + er, x + er, ey - er, paint);
                }
                paint.setTextSize(r * 0.7f);
                paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
                c.drawText("!", cx + r * 0.8f, cy - r * 0.55f, paint);
            } else {
                boolean blink = (k % 3.1f) < 0.12f;
                if (blink) c.drawRect(cx - ex - er, ey - r * 0.02f, cx - ex + er, ey + r * 0.02f, paint);
                else c.drawCircle(cx - ex, ey, er, paint);
                if (blink) c.drawRect(cx + ex - er, ey - r * 0.02f, cx + ex + er, ey + r * 0.02f, paint);
                else c.drawCircle(cx + ex, ey, er, paint);
            }
            // Mouth: four bars that move while it talks.
            paint.setColor(body);
            float mw = r * 0.09f, my = cy + r * 0.18f;
            for (int i = 0; i < 4; i++) {
                float h = r * (0.04f + 0.1f * (float) Math.abs(Math.sin(k * 9 + i * 1.3)));
                float x = cx - r * 0.24f + i * r * 0.16f;
                c.drawRect(x, my - h, x + mw, my + h, paint);
            }
        }

        private final android.graphics.RectF box2 = new android.graphics.RectF();

        private List<String> wrap(String text, float max) {
            List<String> out = new ArrayList<>();
            StringBuilder row = new StringBuilder();
            for (String w : text.split(" ")) {
                String next = row.length() == 0 ? w : row + " " + w;
                if (paint.measureText(next) > max && row.length() > 0) {
                    out.add(row.toString());
                    row = new StringBuilder(w);
                } else {
                    row = new StringBuilder(next);
                }
            }
            if (row.length() > 0) out.add(row.toString());
            return out;
        }

        private void art(Canvas c, float size, boolean fox) {
            if (fox && foxArt == null) foxArt = getDrawable(R.drawable.fox);
            if (!fox && rabbitArt == null) rabbitArt = getDrawable(R.drawable.rabbit);
            android.graphics.drawable.Drawable d = fox ? foxArt : rabbitArt;
            if (d == null) return;
            float h = size * (fox ? 64 / 96f : 40 / 48f);
            d.setBounds(0, (int) (-h / 2), (int) size, (int) (h / 2));
            d.draw(c);
        }

        /** A round bear head peeking out: brown, ears, sleepy eyes, snout. */
        private void bear(Canvas c, float size, boolean dark) {
            float r = size * 0.42f, cx = size * 0.5f;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF6B4226);
            c.drawCircle(cx - r * 0.75f, -r * 0.75f, r * 0.38f, paint);
            c.drawCircle(cx + r * 0.75f, -r * 0.75f, r * 0.38f, paint);
            c.drawCircle(cx, 0, r, paint);
            paint.setColor(0xFFC89B6D);
            c.drawCircle(cx, r * 0.35f, r * 0.42f, paint);
            paint.setColor(0xFF1A1A1A);
            c.drawCircle(cx, r * 0.2f, r * 0.13f, paint);
            c.drawCircle(cx - r * 0.38f, -r * 0.2f, r * 0.09f, paint);
            c.drawCircle(cx + r * 0.38f, -r * 0.2f, r * 0.09f, paint);
            paint.setColor(0xFFFF5A3C);
            c.drawCircle(cx - r * 0.38f, -r * 0.22f, r * 0.03f, paint);   // the little glint that says "I'm hungry"
            c.drawCircle(cx + r * 0.38f, -r * 0.22f, r * 0.03f, paint);
        }

        /** The F1 car, side-on, same shape as on the timing board. */
        private void car(Canvas c, float size, int ink, int rust) {
            float w = size * 1.1f, h = w * 0.3f, x = 0, y = -h / 2;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF000000 | ink);
            path.reset();
            path.moveTo(x + w * 0.08f, y + h * 0.72f);
            path.lineTo(x + w * 0.16f, y + h * 0.42f);
            path.lineTo(x + w * 0.46f, y + h * 0.40f);
            path.lineTo(x + w * 0.56f, y + h * 0.22f);
            path.lineTo(x + w * 0.64f, y + h * 0.40f);
            path.lineTo(x + w * 0.98f, y + h * 0.60f);
            path.lineTo(x + w * 0.98f, y + h * 0.72f);
            path.close();
            c.drawPath(path, paint);
            c.drawRect(x, y + h * 0.10f, x + w * 0.12f, y + h * 0.30f, paint);
            c.drawRect(x + w * 0.86f, y + h * 0.72f, x + w * 1.02f, y + h * 0.80f, paint);
            paint.setColor(rust);
            c.drawRect(x + w * 0.20f, y + h * 0.50f, x + w * 0.80f, y + h * 0.58f, paint);
            paint.setColor(0xFF000000 | ink);
            c.drawCircle(x + w * 0.24f, y + h * 0.78f, h * 0.22f, paint);
            c.drawCircle(x + w * 0.80f, y + h * 0.78f, h * 0.20f, paint);
        }

        /* ---- The 3D cube: tilting the phone turns it; held normally (~45°) it is the logo ---- */

        private static final float BASE_YAW = (float) Math.toRadians(45), BASE_PITCH = (float) Math.toRadians(35.264);
        private final float[][] proj = new float[8][3];

        /* ---- The glass orb (lens) ---- */

        private float lensX, lensY, lensR;
        private final float[] warped = new float[2];
        private final android.graphics.Path lensPath = new android.graphics.Path();

        /**
         * Where a point shows up through the orb: magnified inside (pushed outwards), and outside the
         * rim bent away a little, fading out. spread > 1 bends more (for the blue fringe).
         */
        private void warp(float x, float y, float spread) {
            float dx = x - lensX, dy = y - lensY, d = (float) Math.hypot(dx, dy), r = lensR;
            if (d < 1e-3f || r <= 0) {
                warped[0] = x;
                warped[1] = y;
                return;
            }
            // Continuous at the rim (no gap, so lines bend instead of breaking).
            float nd, u = d / r;
            if (u < 1) {
                nd = r * (u + (0.45f + 0.08f * (spread - 1)) * u * (1 - u));       // magnified in the middle
            } else {
                float out = u - 1;
                nd = d + r * 0.9f * spread * out * (float) Math.exp(-out / 0.3f);   // bent round the rim
            }
            warped[0] = lensX + dx / d * nd;
            warped[1] = lensY + dy / d * nd;
        }

        /** A straight line as it looks through the orb; near the rim also a red and a blue fringe. */
        private void lensLine(Canvas c, float x1, float y1, float x2, float y2, boolean cube) {
            // Lines that don't come near the orb stay straight (cheap).
            float lx = x2 - x1, ly = y2 - y1, len2 = lx * lx + ly * ly;
            float tt = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((lensX - x1) * lx + (lensY - y1) * ly) / len2));
            float near = (float) Math.hypot(x1 + lx * tt - lensX, y1 + ly * tt - lensY);
            if (lensR <= 0 || near > lensR * 2.2f) {
                c.drawLine(x1, y1, x2, y2, paint);
                return;
            }
            int base = paint.getColor();
            int n = 64;
            if (near < lensR * 1.5f) {
                // Fringes: red bent less, blue bent more, both faint.
                int a = Math.min(255, (int) (((base >>> 24) & 0xFF) * (cube ? 1.4f : 2.2f)));
                float[] spreads = {0.8f, 1.3f};
                int[] cols = {0x00FF4A3A, 0x003C8CFF};
                for (int k = 0; k < 2; k++) {
                    linePath(x1, y1, x2, y2, n, spreads[k]);
                    paint.setColor(cols[k] | (Math.min(255, a * 2 / 3) << 24));
                    c.drawPath(lensPath, paint);
                }
            }
            linePath(x1, y1, x2, y2, n, 1);
            paint.setColor(base);
            c.drawPath(lensPath, paint);
        }

        private void linePath(float x1, float y1, float x2, float y2, int n, float spread) {
            lensPath.reset();
            for (int i = 0; i <= n; i++) {
                float f = i / (float) n;
                warp(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, spread);
                if (i == 0) lensPath.moveTo(warped[0], warped[1]);
                else lensPath.lineTo(warped[0], warped[1]);
            }
        }

        /** Behind the cube: a soft blue glow and the glass body. */
        private void orbBack(Canvas c, boolean dark, float t) {
            float r = lensR;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new android.graphics.RadialGradient(lensX, lensY, r * 1.9f,
                new int[]{dark ? 0x553A5A9A : 0x333C6EC8, dark ? 0x22304A80 : 0x143C6EC8, 0x00000000},
                new float[]{0.45f, 0.7f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(lensX, lensY, r * 1.9f, paint);
            paint.setShader(new android.graphics.RadialGradient(lensX - r * 0.25f, lensY - r * 0.3f, r * 1.25f,
                new int[]{dark ? 0xFF1E2840 : 0xFFF4F7FC, dark ? 0xFF121826 : 0xFFE3EAF5, dark ? 0xFF0A0D14 : 0xFFCCD7E8},
                new float[]{0f, 0.6f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            paint.setAlpha(dark ? 150 : 120);
            c.drawCircle(lensX, lensY, r, paint);
            paint.setAlpha(255);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
        }

        /** In front: the glass rim (a bright arc top left, blue below right) and slow specks inside. */
        /*
         * While charging: the orb fills with green energy up to the battery level, a wavy surface,
         * bubbles rising, and sparks running along the grid lines into the orb.
         */
        private int chargeLevel = -1;
        private long chargeChecked;
        private final float[][] sparksIn = new float[14][3];       // grid x/y origin index, progress

        private static final long UNPLUG = 3600;
        private long unplugAt;
        private int unplugLevel;

        /*
         * Unplugging: the bear stands bottom left holding the cable with its plug, which just came
         * out of the orb; the green liquid pours out of the hole at the bottom of the orb, drops
         * fall and make a puddle that sinks away; the orb empties.
         */
        private void unplug(Canvas c, float t, float s, long now) {
            float k = (now - unplugAt) / (float) UNPLUG;
            float r = lensR * 0.92f, cx = lensX, cy = lensY;
            float fade = k > 0.8f ? 1 - (k - 0.8f) / 0.2f : 1;
            // What's left in the orb, sinking.
            float left = unplugLevel / 100f * Math.max(0, 1 - k * 1.4f);
            if (left > 0.01f) {
                float level = cy + r - 2 * r * left;
                android.graphics.Path clip = new android.graphics.Path();
                clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x992BD96B);
                c.drawRect(cx - r, level + (float) Math.sin(t * 9) * 6 * s, cx + r, cy + r, paint);
                c.restore();
            }
            // The stream out of the bottom, and drops.
            float ground = height * 0.86f;
            if (k < 0.75f) {
                paint.setColor(0xCC2BD96B);
                float wob = (float) Math.sin(t * 20) * 3 * s;
                c.drawRect(cx - 7 * s + wob, cy + r * 0.95f, cx + 7 * s + wob, ground, paint);
            }
            for (int i = 0; i < 10; i++) {
                float f = ((t * 1.6f + i * 0.1f) % 1f);
                float dx = (float) Math.sin(i * 7.1) * 40 * s;
                float y = cy + r + f * (ground - cy - r);
                paint.setColor(((int) (200 * fade) << 24) | 0x2BD96B);
                c.drawCircle(cx + dx * f, y, (4 + i % 3 * 2) * s, paint);
            }
            // The puddle, spreading then sinking away.
            float pw = Math.min(1, k * 2.2f) * width * 0.32f;
            paint.setColor(((int) (150 * fade) << 24) | 0x2BD96B);
            c.drawOval(cx - pw, ground - 14 * s, cx + pw, ground + 14 * s, paint);
            // The bear holding the cable, plug in its paw.
            float size = Math.min(width, height) * 0.22f, bx = width * 0.08f, by = height * 0.80f;
            float pull = Math.min(1, k * 3) * size * 0.25f;
            float hx = bx + size * 0.95f - pull, hy = by + size * 0.35f;
            // During the no-network panic the big bear is already there: it holds the cable itself
            // (its waving paw), no second bear.
            boolean panicBear = noService && lockedNow;
            if (panicBear) {
                float ps = Math.min(width, height) * 0.34f;
                hx = width * 0.05f + ps * 1.15f - pull;
                hy = height * 0.64f + ps * 0.3f;
            }
            // Cable: a sagging line from the paw up to where it plugged into the orb.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(7 * s);
            paint.setColor(((int) (255 * fade) << 24) | 0x1A1A1A);
            path.reset();
            path.moveTo(hx, hy);
            path.quadTo((hx + cx) / 2, Math.max(hy, cy + r) + 120 * s, cx - r * 0.3f - (1 - Math.min(1, k * 3)) * 0, cy + r * 0.9f + Math.min(1, k * 3) * 60 * s);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            // Plug at the paw.
            paint.setColor(((int) (255 * fade) << 24) | 0xE8E6E0);
            c.drawRoundRect(hx - 14 * s, hy - 22 * s, hx + 14 * s, hy + 4 * s, 4 * s, 4 * s, paint);
            paint.setColor(((int) (255 * fade) << 24) | 0xB0B0A8);
            c.drawRect(hx - 8 * s, hy - 34 * s, hx - 4 * s, hy - 22 * s, paint);
            c.drawRect(hx + 4 * s, hy - 34 * s, hx + 8 * s, hy - 22 * s, paint);
            if (!panicBear) {
                c.save();
                c.translate(bx - pull * 0.3f, by);
                c.rotate(-6 * Math.min(1, k * 3), size / 2, size / 2);
                bear(c, size, true);
                c.restore();
            }
            // Paw over the plug.
            paint.setColor(((int) (255 * fade) << 24) | 0x6B4A33);
            c.drawCircle(hx, hy - 6 * s, size * 0.08f, paint);
            if (k > 0.15f && k < 0.85f && !panicBear) {
                paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
                paint.setTextSize(34 * s);
                paint.setColor(0xF2FFFFFF);
                String said = "Ups – Stecker raus! Alles ausgelaufen. 🔌";
                float tw = paint.measureText(said) + 40 * s, tx = Math.min(width - tw - 20 * s, bx + size * 1.1f), ty = by - 30 * s;
                c.drawRoundRect(tx, ty - 50 * s, tx + tw, ty + 14 * s, 22 * s, 22 * s, paint);
                paint.setColor(0xFF1A1A1A);
                c.drawText(said, tx + 20 * s, ty - 6 * s, paint);
            }
        }

        private void charging(Canvas c, float t, float s) {
            long now = SystemClock.uptimeMillis();
            if (now - chargeChecked > (chargeLevel >= 0 ? 800 : 3000)) {
                chargeChecked = now;
                android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                boolean plugged = b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
                int lvl = b == null ? -1 : b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
                // Unplugged right now: the buddy pulls the cable out and the orb spills.
                if (!plugged && chargeLevel >= 0) {
                    unplugAt = now;
                    unplugLevel = chargeLevel;
                }
                chargeLevel = plugged ? lvl : -1;
            }
            if (chargeLevel < 0 && unplugAt > 0 && now - unplugAt < UNPLUG) unplug(c, t, s, now);
            if (chargeLevel < 0 || lensR <= 0) return;
            float r = lensR * 0.92f, cx = lensX, cy = lensY;
            float level = cy + r - 2 * r * chargeLevel / 100f;
            // Liquid with a wave on top, clipped to the orb.
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            // Gravity: the liquid turns against the phone's tilt with a little spring, so it sloshes and
            // settles level, like water in a bottle.
            float target = (float) Math.toDegrees(gravRoll);
            float d = target - slosh;
            while (d > 180) d -= 360;
            while (d < -180) d += 360;
            sloshV = sloshV * 0.88f + d * 0.06f;
            slosh += sloshV;
            float swing = Math.min(1f, Math.abs(sloshV) / 6f);
            c.rotate(slosh, cx, cy);
            android.graphics.Path wave = new android.graphics.Path();
            float R = r * 1.5f;
            wave.moveTo(cx - R, cy + R);
            for (float x = cx - R; x <= cx + R; x += 6 * s) {
                wave.lineTo(x, level + (float) Math.sin((x - cx) / (40 * s) + t * 3) * (10 + 26 * swing) * s
                    + (x - cx) / R * sloshV * 4 * s);
            }
            wave.lineTo(cx + R, cy + R);
            wave.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new android.graphics.LinearGradient(0, level, 0, cy + r, 0xB32BD96B, 0x880A5A28, android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(wave, paint);
            paint.setShader(null);
            // 3D: the liquid's top seen slightly from above – an ellipse as wide as the ball there.
            float dy = level - cy, chord = (float) Math.sqrt(Math.max(0, r * r - dy * dy));
            if (chord > 4 * s) {
                float tilt = chord * 0.16f;
                paint.setColor(0x997CFFA8);
                c.drawOval(cx - chord, level - tilt, cx + chord, level + tilt, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * s);
                paint.setColor(0xCCC8FFD8);
                c.drawOval(cx - chord, level - tilt, cx + chord, level + tilt, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            // Bubbles.
            for (int i = 0; i < 12; i++) {
                float k = (t * (0.25f + (i % 4) * 0.08f) + i * 0.137f) % 1f;
                float bx = cx + (float) Math.sin(i * 2.3 + t) * r * 0.6f, by = cy + r - k * (cy + r - level);
                paint.setColor(0x88C8FFD8);
                c.drawCircle(bx, by, (3 + i % 3 * 2) * s, paint);
            }
            c.restore();
            // 3D glass: shade round the edge, light from the top left, a sharp highlight and a rim.
            c.save();
            c.clipPath(clip);
            paint.setShader(new android.graphics.RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.45f,
                new int[]{0x00000000, 0x00000000, 0x66000000}, new float[]{0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
            paint.setShader(new android.graphics.RadialGradient(cx - r * 0.38f, cy - r * 0.42f, r * 0.45f,
                new int[]{0x88FFFFFF, 0x00FFFFFF}, null, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx - r * 0.38f, cy - r * 0.42f, r * 0.45f, paint);
            paint.setShader(null);
            paint.setColor(0xCCFFFFFF);
            c.drawOval(cx - r * 0.52f, cy - r * 0.62f, cx - r * 0.3f, cy - r * 0.5f, paint);
            c.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * s);
            paint.setShader(new android.graphics.LinearGradient(cx - r, cy - r, cx + r, cy + r, 0x99FFFFFF, 0x2216A34A, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            // A soft shadow under the ball.
            paint.setShader(new android.graphics.RadialGradient(cx, cy + r * 1.08f, r * 0.8f, 0x55000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            c.drawOval(cx - r * 0.8f, cy + r * 1.0f, cx + r * 0.8f, cy + r * 1.16f, paint);
            paint.setShader(null);
            // Sparks along the grid lines, running into the orb.
            for (int i = 0; i < sparksIn.length; i++) {
                float[] sp = sparksIn[i];
                if (sp[2] <= 0 || sp[2] >= 1) {
                    double a = random.nextDouble() * Math.PI * 2;
                    sp[0] = cx + (float) Math.cos(a) * Math.max(width, height);
                    sp[1] = cy + (float) Math.sin(a) * Math.max(width, height);
                    sp[2] = 0.001f + random.nextFloat() * 0.3f;
                }
                sp[2] += 0.012f;
                float k = sp[2] * sp[2];
                float x = sp[0] + (cx - sp[0]) * k, y = sp[1] + (cy - sp[1]) * k;
                float px = sp[0] + (cx - sp[0]) * Math.max(0, k - 0.04f), py = sp[1] + (cy - sp[1]) * Math.max(0, k - 0.04f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3 * s);
                paint.setColor(((int) (200 * sp[2]) << 24) | 0x7CFFA8);
                c.drawLine(px, py, x, y, paint);
            }
            // The level in the middle.
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
            paint.setTextSize(30 * s);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFF7CFFA8);
            c.drawText("⚡ " + chargeLevel + " %", cx, cy + r + 50 * s, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void orbFront(Canvas c, boolean dark, float t, float s) {
            float r = lensR;
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * s);
            paint.setColor(dark ? 0x664A72C8 : 0x553C6EC8);
            c.drawCircle(lensX, lensY, r, paint);
            paint.setStrokeWidth(5 * s);
            paint.setStrokeCap(Paint.Cap.ROUND);
            android.graphics.RectF o = new android.graphics.RectF(lensX - r * 0.94f, lensY - r * 0.94f, lensX + r * 0.94f, lensY + r * 0.94f);
            paint.setColor(dark ? 0x40FFFFFF : 0x99FFFFFF);
            c.drawArc(o, 195, 70, false, paint);
            paint.setColor(dark ? 0x553C8CFF : 0x443C6EC8);
            c.drawArc(o, 20, 80, false, paint);
            // Specks drifting in the glass.
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 28; i++) {
                double a = i * 2.39996 + t * (0.05 + (i % 5) * 0.012);
                float d = r * 0.85f * (float) Math.sqrt(((i * 37) % 100) / 100f);
                float x = lensX + (float) Math.cos(a) * d, y = lensY + (float) Math.sin(a) * d;
                float tw = 0.5f + 0.5f * (float) Math.sin(t * 2 + i);
                paint.setColor(dark ? 0xFFDCE6FF : 0xFF3C5A8C);
                paint.setAlpha((int) ((dark ? 90 : 70) * tw));
                c.drawCircle(x, y, (1.4f + (i % 3)) * s, paint);
            }
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.STROKE);
        }
        private float yaw = BASE_YAW, pitch = BASE_PITCH, drawnYaw = Float.NaN, drawnPitch = Float.NaN;
        /** At a normal ~45° grip the cube shows the pose it used to have lying flat (your pick). */
        private static final float NEUTRAL_TILT = (float) Math.toRadians(90);
        private float tiltX, tiltY, baseX = Float.NaN;
        private volatile float gravRoll;
        private float slosh, sloshV;
        private android.hardware.SensorManager sensors;

        private final android.hardware.SensorEventListener gravity = new android.hardware.SensorEventListener() {
            @Override
            public void onSensorChanged(android.hardware.SensorEvent e) {
                float g = android.hardware.SensorManager.GRAVITY_EARTH;
                float x = (float) Math.asin(Math.max(-1f, Math.min(1f, e.values[0] / g)));   // left / right
                float y = (float) Math.asin(Math.max(-1f, Math.min(1f, e.values[1] / g)));   // towards / away
                if (Float.isNaN(baseX)) {          // first reading after the screen turns on: no lag
                    baseX = 0;
                    tiltX = x;
                    tiltY = y;
                }
                tiltX = tiltX * 0.8f + x * 0.2f;
                tiltY = tiltY * 0.8f + y * 0.2f;
                // Where "down" is on the screen: the charging liquid keeps its surface level to it.
                if (Math.abs(e.values[0]) + Math.abs(e.values[1]) > 1.5f) gravRoll = (float) Math.atan2(e.values[0], e.values[1]);
                // Fixed calibration (see NEUTRAL_TILT): held normally at ~45° the cube sits in its
                // home pose, rust edge from the centre to the top right; tilting turns it.
                yaw = BASE_YAW - tiltX * 1.4f;
                pitch = Math.max(-1.4f, Math.min(1.4f, BASE_PITCH + (tiltY - NEUTRAL_TILT) * 1.4f));
                if (visible && cubeMoving()) {
                    handler.removeCallbacks(frame);
                    handler.post(frame);
                }
            }

            @Override
            public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
            }
        };

        private void watchTilt(boolean on) {
            if (sensors == null) sensors = getSystemService(android.hardware.SensorManager.class);
            if (sensors == null) return;
            if (on) {
                android.hardware.Sensor g = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY);
                if (g == null) g = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
                if (g != null) sensors.registerListener(gravity, g, android.hardware.SensorManager.SENSOR_DELAY_GAME);
            } else {
                sensors.unregisterListener(gravity);
                baseX = Float.NaN;
            }
        }

        boolean cubeMoving() {
            return Float.isNaN(drawnYaw) || Math.abs(yaw - drawnYaw) > 0.003f || Math.abs(pitch - drawnPitch) > 0.003f;
        }

        private void stepCube() {
            drawnYaw = yaw;
            drawnPitch = pitch;
        }

        private void drawCube(Canvas c, float cx, float cy, float scale, int ink, int rust) {
            float cyw = (float) Math.cos(yaw), syw = (float) Math.sin(yaw);
            float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.22f * scale * (float) Math.sqrt(2) / 6f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                float x = (i & 1) == 0 ? -1 : 1, y = (i & 2) == 0 ? -1 : 1, z = (i & 4) == 0 ? -1 : 1;
                // Yaw around the vertical axis, then pitch towards the viewer.
                float x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;
                float y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
                float persp = 7f / (7f - z2);       // a touch of perspective
                proj[i][0] = cx + x1 * scale * persp;
                proj[i][1] = cy + y2 * scale * persp;
                proj[i][2] = z2;
            }
            // 12 edges: pairs of corners differing in one bit. Edges further back are fainter.
            for (int a = 0; a < 8; a++) {
                for (int bit = 1; bit < 8; bit <<= 1) {
                    int b = a | bit;
                    if (b == a) continue;
                    float depth = (proj[a][2] + proj[b][2]) / 2f;           // -1.7 (back) … 1.7 (front)
                    float alpha = (0.06f + 0.06f * (depth + 1.7f) / 3.4f) * (night() ? 1.8f : 1f);
                    boolean accent = a == 4 && b == 5;                        // the logo's rust edge
                    paint.setColor(accent ? (rust & 0x00FFFFFF) | ((int) (255 * (0.3f + 0.25f * (depth + 1.7f) / 3.4f)) << 24)
                        : (ink & 0x00FFFFFF) | ((int) (255 * alpha) << 24));
                    lensLine(c, proj[a][0], proj[a][1], proj[b][0], proj[b][1], true);
                }
            }
            faceLetters(c, rust);
        }

        /* ---- Letters on the cube: A · R · C, now and then D · N · A, turning with it ---- */

        /** Faces as {axis, sign}: in the normal grip these are left, top and right. */
        private final int[][] faces = {{0, -1}, {1, -1}, {2, 1}};
        private static final String ARC = "ARC", DNA = "DNA";
        private final android.graphics.Matrix faceMatrix = new android.graphics.Matrix();
        private final float[] src = {0, 0, 100, 0, 100, 100, 0, 100}, dst = new float[8];
        private final float[][] quad = new float[4][3];

        private void faceLetters(Canvas c, int rust) {
            // Every 30 s the letters blend into D · N · A for about 6 s.
            long ms = System.currentTimeMillis() % 30_000;
            float dna = ms < 22_000 ? 0 : ms < 22_600 ? (ms - 22_000) / 600f : ms < 27_400 ? 1 : ms < 28_000 ? (28_000 - ms) / 600f : 0;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
            paint.setTextSize(64);
            for (int f = 0; f < faces.length; f++) {
                int axis = faces[f][0], sign = faces[f][1];
                int u = axis == 0 ? 1 : 0, v = axis == 2 ? 1 : 2;
                // The face's corners in order round the face.
                int[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                float depth = 0;
                for (int k = 0; k < 4; k++) {
                    int idx = (sign > 0 ? 1 << axis : 0) | (uv[k][0] << u) | (uv[k][1] << v);
                    quad[k][0] = proj[idx][0];
                    quad[k][1] = proj[idx][1];
                    depth += proj[idx][2] / 4f;
                }
                if (depth < 0.08f) continue;                 // turned away: no letter
                // Clockwise on screen, starting with the top edge → the letter reads upright, never mirrored.
                float area = 0;
                for (int k = 0; k < 4; k++) {
                    float[] p0 = quad[k], p1 = quad[(k + 1) % 4];
                    area += p0[0] * p1[1] - p1[0] * p0[1];
                }
                if (area < 0) {
                    float[] t = quad[1]; quad[1] = quad[3]; quad[3] = t;
                }
                int top = 0;
                float best = Float.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    float m = (quad[k][1] + quad[(k + 1) % 4][1]) / 2f;
                    if (m < best) {
                        best = m;
                        top = k;
                    }
                }
                for (int k = 0; k < 4; k++) {
                    dst[2 * k] = quad[(top + k) % 4][0];
                    dst[2 * k + 1] = quad[(top + k) % 4][1];
                }
                if (!faceMatrix.setPolyToPoly(src, 0, dst, 0, 4)) continue;
                float face = Math.min(1f, (depth - 0.08f) / 0.3f);
                c.save();
                c.concat(faceMatrix);
                for (int w = 0; w < 2; w++) {
                    float mix = w == 0 ? 1 - dna : dna;
                    if (mix <= 0.01f) continue;
                    String letter = String.valueOf((w == 0 ? ARC : DNA).charAt(f));
                    int strength = night() ? 230 : 150;
                    paint.setColor(((night() ? 0xFF6A4F : rust) & 0x00FFFFFF) | ((int) (strength * face * mix) << 24));
                    c.drawText(letter, 50 - paint.measureText(letter) / 2f, 50 + 64 * 0.36f, paint);
                }
                c.restore();
            }
        }

        private void rainbowRing(Canvas c, float x, float y, float r, float stroke, float rotate, float alpha) {
            if (getSharedPreferences("home", MODE_PRIVATE).getBoolean("pro", false)) return;      // professional: no rainbows
            int a = (int) (alpha * 255) << 24;
            int[] hues = {0xFF0000, 0xFF9900, 0xFFEE00, 0x33DD55, 0x22AAFF, 0x8844FF, 0xFF33AA, 0xFF0000};
            for (int i = 0; i < hues.length; i++) hues[i] |= a;
            SweepGradient sweep = new SweepGradient(x, y, hues, null);
            matrix.setRotate(rotate % 360, x, y);
            sweep.setLocalMatrix(matrix);
            paint.setShader(sweep);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            c.drawCircle(x, y, r, paint);
        }

        /* ---- Beat detection on the phone's output mix (needs the microphone permission; nothing is recorded) ---- */

        private void startVisualizer() {
            if (visualizer != null) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
            try {
                visualizer = new Visualizer(0);
                visualizer.setEnabled(false);
                int[] range = Visualizer.getCaptureSizeRange();
                visualizer.setCaptureSize(Math.max(range[0], Math.min(512, range[1])));
                visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer v, byte[] w, int r) {
                    }

                    @Override
                    public void onFftDataCapture(Visualizer v, byte[] fft, int r) {
                        onFft(fft);
                    }
                }, Math.min(Visualizer.getMaxCaptureRate(), 20000), false, true);
                visualizer.setEnabled(true);
            } catch (RuntimeException e) {
                visualizer = null;
            }
        }

        private void stopVisualizer() {
            if (visualizer == null) return;
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (RuntimeException ignored) {
            }
            visualizer = null;
        }

        /** Bass energy well above its running average = a beat → spawn rings. */
        private void onFft(byte[] fft) {
            for (int k = 2; k < Math.min(16, fft.length); k++) {
                if (fft[k] != 0) {
                    lastFft = SystemClock.uptimeMillis();
                    break;
                }
            }
            float bass = 0;
            int bins = Math.min(7, fft.length / 2);
            for (int k = 1; k < bins; k++) bass += (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
            bass /= Math.max(1, bins - 1);
            bassAvg = bassAvg * 0.95f + bass * 0.05f;
            long now = SystemClock.uptimeMillis();
            if (bass > 8 && bass > bassAvg * 1.45f && now - lastBeat > 220) {
                lastBeat = now;
                beat = 1f;
                boolean lock = locked();
                if (rings.size() < 14) {
                    float hue = random.nextFloat() * 360;
                    if (lock) {
                        rings.add(new float[]{width / 2f, height * 0.52f, width * 0.05f, 0.9f, hue, 1});
                        if (pixelPortrait()) rings.add(new float[]{width * FP_X, height * FP_Y, width * 0.12f, 0.8f, hue + 120, 1});
                    } else {
                        rings.add(new float[]{width / 2f, height * 0.52f, width * 0.05f, 0.3f, 0, 0});
                    }
                }
                if (visible) {
                    handler.removeCallbacks(frame);
                    handler.post(frame);
                }
            }
        }
    }
}
