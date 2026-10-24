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
        private static final float FP_X = 0.5f, FP_Y = 0.7225f;

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
            drawFrame();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            DisplayCutout cutout = insets.getDisplayCutout();
            camera = cutout != null && !cutout.getBoundingRects().isEmpty() ? cutout.getBoundingRects().get(0) : null;
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
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
            handler.removeCallbacks(frame);
            stopVisualizer();
        }

        private boolean night() {
            return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        }

        private boolean locked() {
            // A lock-screen-only engine (Android 14+) always shows the lock effects.
            if (Build.VERSION.SDK_INT >= 34 && getWallpaperFlags() == android.app.WallpaperManager.FLAG_LOCK) return true;
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
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
            boolean busy = lock || !rings.isEmpty() || cubeMoving();
            handler.postDelayed(frame, busy ? 33 : 500);
        }

        private void draw(Canvas c, boolean lock) {
            boolean dark = night();
            int bg = dark ? 0xFF121211 : 0xFFFAFAF8;
            int ink = dark ? 0xECEBE6 : 0x1A1A1A;
            int rust = dark ? 0xFFC9402F : 0xFFB5271E;
            float s = width / 1080f;
            float t = SystemClock.uptimeMillis() / 1000f;
            c.drawColor(bg);

            // Blueprint grid.
            paint.setStyle(Paint.Style.STROKE);
            paint.setShader(null);
            paint.setStrokeWidth(2 * s);
            paint.setColor(0x0E000000 | ink);
            float cell = 84 * s;
            for (float x = -2 * s; x < width; x += cell) c.drawLine(x, 0, x, height, paint);
            for (float y = 40 * s; y < height; y += cell) c.drawLine(0, y, width, y, paint);

            // The cube, as on the website wallpaper, but real 3D: two fingers on the home screen turn it.
            stepCube();
            drawCube(c, width / 2f, height * 0.52f, 760 * s / 14f * 6f / (float) Math.sqrt(2), ink, rust);

            // Rave / beat rings.
            beat *= 0.9f;
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

            if (!lock) return;

            // Fingerprint: pulsing rainbow halo plus glitter.
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

            // Front camera: rotating rainbow ring.
            float cx = camera != null ? camera.exactCenterX() : width / 2f;
            float cy = camera != null ? camera.exactCenterY() : height * 0.0475f;
            float cr = (camera != null ? Math.max(camera.width(), camera.height()) / 2f : 22 * s) * 1.7f
                * (1 + 0.06f * (float) Math.sin(t * 3.1f) + 0.25f * beat);
            rainbowRing(c, cx, cy, cr, 5 * s, -t * 160, 0.9f);
            paint.setShader(null);
        }

        /* ---- The 3D cube: yaw/pitch with inertia; the default pose is the isometric logo ---- */

        private static final float BASE_YAW = (float) Math.toRadians(45), BASE_PITCH = (float) Math.toRadians(35.264);
        private final float[][] verts = new float[8][3];
        private final float[][] proj = new float[8][3];
        private float yaw = BASE_YAW, pitch = BASE_PITCH, vYaw, vPitch;
        private boolean dragging;
        private long lastCubeStep;

        boolean cubeMoving() {
            return dragging || Math.abs(vYaw) > 0.0005f || Math.abs(vPitch) > 0.0005f;
        }

        @Override
        public android.os.Bundle onCommand(String action, int x, int y, int z, android.os.Bundle extras, boolean resultRequested) {
            if ("ch.architectdna.CUBE_DRAG".equals(action) && extras != null) {
                float k = 0.006f;   // radians per pixel
                float dYaw = extras.getFloat("dx") * k, dPitch = extras.getFloat("dy") * k;
                yaw += dYaw;
                pitch = Math.max(-1.45f, Math.min(1.45f, pitch + dPitch));
                vYaw = dYaw;
                vPitch = dPitch;
                dragging = true;
                lastCubeStep = SystemClock.uptimeMillis();
                if (visible) {
                    handler.removeCallbacks(frame);
                    handler.post(frame);
                }
            } else if ("ch.architectdna.CUBE_RELEASE".equals(action)) {
                dragging = false;
            } else if ("ch.architectdna.CUBE_RESET".equals(action)) {
                vYaw = vPitch = 0;
                yaw = BASE_YAW;
                pitch = BASE_PITCH;
                if (visible) drawFrame();
            }
            return null;
        }

        /** After letting go it keeps spinning and slows down (per 16 ms frame). */
        private void stepCube() {
            if (dragging) return;
            long now = SystemClock.uptimeMillis();
            float frames = Math.min(4f, (now - lastCubeStep) / 16f);
            lastCubeStep = now;
            if (!cubeMoving()) return;
            yaw += vYaw * frames;
            pitch = Math.max(-1.45f, Math.min(1.45f, pitch + vPitch * frames));
            float decay = (float) Math.pow(0.95, frames);
            vYaw *= decay;
            vPitch *= decay;
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
                    float alpha = 0.06f + 0.06f * (depth + 1.7f) / 3.4f;
                    boolean accent = a == 4 && b == 5;                        // the logo's rust edge
                    paint.setColor(accent ? (rust & 0x00FFFFFF) | ((int) (255 * (0.3f + 0.25f * (depth + 1.7f) / 3.4f)) << 24)
                        : (ink & 0x00FFFFFF) | ((int) (255 * alpha) << 24));
                    c.drawLine(proj[a][0], proj[a][1], proj[b][0], proj[b][1], paint);
                }
            }
        }

        private void rainbowRing(Canvas c, float x, float y, float r, float stroke, float rotate, float alpha) {
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
                        rings.add(new float[]{width * FP_X, height * FP_Y, width * 0.12f, 0.8f, hue + 120, 1});
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
