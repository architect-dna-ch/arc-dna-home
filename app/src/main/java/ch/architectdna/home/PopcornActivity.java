package ch.architectdna.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Kino mode: full screen, a little kitchen at night, the microwave makes popcorn for 2:30 –
 * the plate turns, the bag puffs up, kernels start popping slowly, then like rain, then thin out,
 * DING. The door swings open and the movie can start.
 */
public class PopcornActivity extends Activity {

    private static final float TOTAL = 150f;            // 2:30
    private static final int RATE = 22050;

    private KitchenView view;
    private volatile boolean running = true;
    private volatile long startedAt;
    private volatile float audioT;                       // seconds of sound written (follows the picture)
    private Thread sound;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new KitchenView(this);
        setContentView(view);
        immersive();
        startedAt = SystemClock.uptimeMillis();
        sound = new Thread(this::soundLoop, "popcorn-sound");
        sound.start();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private float elapsed() {
        return (SystemClock.uptimeMillis() - startedAt) / 1000f;
    }

    /** Pops per second at time t: none for 40 s, rising to a storm around 1:40, thinning out to the end. */
    static float popRate(float t) {
        if (t < 40) return 0;
        if (t < 100) return 0.3f + (t - 40) / 60f * 7f;
        if (t < 125) return 7.3f;
        if (t < TOTAL) return Math.max(0.3f, 7.3f * (1 - (t - 125) / 25f));
        return 0;
    }

    /* ================= Sound: hum, fan, pops, ding ================= */

    private void soundLoop() {
        int n = 1024;
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), n * 4))
                .build();
            track.play();
        } catch (Exception e) {
            return;
        }
        short[] buf = new short[n];
        Random rnd = new Random();
        double hum = 0, fan = 0, popEnv = 0, popLp = 0, dingT = -1;
        long sample = 0;
        boolean dinged = false;
        while (running) {
            for (int i = 0; i < n; i++, sample++) {
                double t = (double) sample / RATE;
                double v = 0;
                if (t < TOTAL) {
                    // Hum: 120 Hz with a little 240 Hz, plus the fan's soft noise.
                    hum += 2 * Math.PI * 120 / RATE;
                    v += (Math.sin(hum) * 0.08 + Math.sin(hum * 2) * 0.04);
                    fan += 0.02 * ((rnd.nextDouble() * 2 - 1) - fan);
                    v += fan * 0.5;
                    // Pops: random by the rate; a sharp noise burst with a quick decay.
                    if (rnd.nextDouble() < popRate((float) t) / RATE) popEnv = 0.7 + rnd.nextDouble() * 0.5;
                } else if (!dinged) {
                    dinged = true;
                    dingT = t;
                }
                if (popEnv > 0.002) {
                    popLp += 0.55 * ((rnd.nextDouble() * 2 - 1) - popLp);
                    v += popLp * popEnv;
                    popEnv *= 0.9965;
                } else popEnv = 0;
                if (dingT >= 0 && t - dingT < 2.2) {
                    double d = t - dingT;
                    v += (Math.sin(2 * Math.PI * 1568 * d) * 0.35 + Math.sin(2 * Math.PI * 3136 * d) * 0.1) * Math.exp(-d * 2.4);
                }
                buf[i] = (short) Math.max(-32767, Math.min(32767, v * 32767 * 0.7));
            }
            track.write(buf, 0, n);
            audioT = (float) sample / RATE;
            if (dinged && audioT - dingT > 2.4) break;
        }
        try {
            track.stop();
        } catch (IllegalStateException ignored) {
        }
        track.release();
    }

    /* ================= The kitchen ================= */

    private final class KitchenView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final Random rnd = new Random(4);
        private final List<float[]> puffs = new ArrayList<>();     // x, y, vx, vy, born, size (inside the window, 0..1)
        private final List<float[]> steam = new ArrayList<>();
        private float lastT, nextPop;
        private boolean buzzed;
        private final RectF startBtn = new RectF(), backBtn = new RectF(), close = new RectF();

        KitchenView(Context c) {
            super(c);
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), t = elapsed(), dt = Math.min(0.05f, t - lastT);
            lastT = t;
            boolean done = t >= TOTAL;
            float s = Math.min(w, h * 0.62f) / 400f;          // scene scale

            // Wall: warm dark tiles, a soft lamp glow.
            c.drawColor(0xFF1B1613);
            p.setShader(new RadialGradient(w * 0.5f, h * 0.18f, Math.max(w, h) * 0.7f, 0x44FFC27A, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            float counter = h * 0.68f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0x22FFFFFF);
            float tile = dp(34);
            for (float y = tile; y < counter; y += tile) {
                c.drawLine(0, y, w, y, p);
                float off = ((int) (y / tile)) % 2 == 0 ? 0 : tile / 2;
                for (float x = off; x < w; x += tile) c.drawLine(x, y - tile, x, y, p);
            }
            // Upper cabinets.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2E2621);
            c.drawRect(0, 0, w, h * 0.12f, p);
            p.setColor(0xFF3A302A);
            for (int i = 0; i < 3; i++) {
                float cw = w / 3f;
                c.drawRoundRect(i * cw + dp(6), dp(4), (i + 1) * cw - dp(6), h * 0.12f - dp(6), dp(6), dp(6), p);
                p.setColor(0xFFB8A48A);
                c.drawRoundRect(i * cw + cw / 2 - dp(14), h * 0.12f - dp(18), i * cw + cw / 2 + dp(14), h * 0.12f - dp(14), dp(2), dp(2), p);
                p.setColor(0xFF3A302A);
            }
            // Counter: wood top, cabinets below.
            p.setColor(0xFF8A5A34);
            c.drawRect(0, counter, w, counter + dp(18), p);
            p.setColor(0xFF6E4627);
            c.drawRect(0, counter + dp(14), w, counter + dp(18), p);
            p.setColor(0xFF241E1A);
            c.drawRect(0, counter + dp(18), w, h, p);
            p.setColor(0xFF2E2621);
            for (int i = 0; i < 2; i++) {
                float cw = w / 2f;
                c.drawRoundRect(i * cw + dp(8), counter + dp(30), (i + 1) * cw - dp(8), h - dp(20), dp(8), dp(8), p);
            }

            // Microwave.
            float mw = 330 * s, mh = 190 * s, mx = (w - mw) / 2, my = counter - mh;
            p.setColor(0x55000000);
            c.drawOval(mx - 10 * s, counter - 8 * s, mx + mw + 10 * s, counter + 10 * s, p);
            p.setColor(0xFFE9E6DF);
            r.set(mx, my, mx + mw, my + mh);
            c.drawRoundRect(r, 16 * s, 16 * s, p);
            p.setColor(0xFFCFCAC0);
            c.drawRect(mx + 8 * s, my + mh - 8 * s, mx + mw - 8 * s, my + mh, p);
            // Window (behind the door).
            float wx = mx + 16 * s, wy = my + 18 * s, ww = mw * 0.64f, wh = mh - 36 * s;
            float glow = done ? 0.25f : 1f;
            p.setShader(new RadialGradient(wx + ww / 2, wy + wh * 0.4f, ww * 0.7f,
                new int[]{blend(0xFFFFE08A, 0xFF2A2420, 1 - glow), blend(0xFFB8862E, 0xFF15110E, 1 - glow * 0.8f)}, null, Shader.TileMode.CLAMP));
            r.set(wx, wy, wx + ww, wy + wh);
            c.drawRoundRect(r, 10 * s, 10 * s, p);
            p.setShader(null);
            // Turntable and the bag on it.
            float spin = done ? 0 : t * 40;
            float px = wx + ww / 2, py = wy + wh * 0.82f;
            p.setColor(0x88FFFFFF);
            c.drawOval(px - ww * 0.36f, py - 10 * s, px + ww * 0.36f, py + 10 * s, p);
            float puff = Math.min(1, Math.max(0, (t - 30) / 100f));
            float bw = (70 + 40 * puff) * s, bh = (34 + 62 * puff) * s;
            float face = (float) Math.cos(Math.toRadians(spin));
            float bx = px + (float) Math.sin(Math.toRadians(spin)) * 14 * s;
            c.save();
            c.translate(bx, py - 4 * s);
            c.scale(0.55f + 0.45f * Math.abs(face), 1);
            p.setColor(0xFFF4ECDA);
            path.reset();
            path.moveTo(-bw / 2, 0);
            path.cubicTo(-bw / 2 - 6 * s * puff, -bh * 0.5f, -bw / 2 + 4 * s, -bh, -bw * 0.1f, -bh);
            path.lineTo(bw * 0.1f, -bh);
            path.cubicTo(bw / 2 - 4 * s, -bh, bw / 2 + 6 * s * puff, -bh * 0.5f, bw / 2, 0);
            path.close();
            c.drawPath(path, p);
            if (face > 0) {
                p.setColor(0xFFC8372D);
                c.drawRect(-bw * 0.3f, -bh * 0.62f, bw * 0.3f, -bh * 0.38f, p);
                p.setColor(0xFFFFFFFF);
                p.setTextSize(9 * s);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                c.drawText("POPCORN", 0, -bh * 0.46f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            c.restore();
            // Pops inside the window: little white puffs jumping off the bag.
            if (!done && popRate(t) > 0) {
                nextPop -= dt * popRate(t);
                while (nextPop < 0) {
                    nextPop += 1;
                    puffs.add(new float[]{bx + (rnd.nextFloat() - 0.5f) * bw * 0.8f, py - bh * (0.4f + 0.5f * rnd.nextFloat()),
                        (rnd.nextFloat() - 0.5f) * 120 * s, -(60 + rnd.nextFloat() * 90) * s, t, (3 + rnd.nextFloat() * 3) * s});
                    if (rnd.nextFloat() < 0.25f) shake = 1;
                }
            }
            for (int i = puffs.size() - 1; i >= 0; i--) {
                float[] q = puffs.get(i);
                float age = t - q[4];
                if (age > 0.6f) {
                    puffs.remove(i);
                    continue;
                }
                q[0] += q[2] * dt;
                q[1] += q[3] * dt;
                q[3] += 400 * s * dt;
                q[0] = Math.max(wx + 4 * s, Math.min(wx + ww - 4 * s, q[0]));
                q[1] = Math.max(wy + 4 * s, Math.min(py, q[1]));
                p.setColor(0xFFFFF8E8);
                c.drawCircle(q[0], q[1], q[5], p);
                c.drawCircle(q[0] + q[5] * 0.7f, q[1] - q[5] * 0.4f, q[5] * 0.7f, p);
            }
            // Glass reflection.
            p.setColor(0x18FFFFFF);
            path.reset();
            path.moveTo(wx + ww * 0.1f, wy);
            path.lineTo(wx + ww * 0.35f, wy);
            path.lineTo(wx + ww * 0.15f, wy + wh);
            path.lineTo(wx - ww * 0.1f + ww * 0.1f, wy + wh);
            path.close();
            c.drawPath(path, p);
            // The door: frame around the window; at the end it swings open (hinge on the left).
            float open = done ? Math.min(1, (t - TOTAL - 1.2f) / 0.6f) : 0;
            if (open > 0) {
                float dw = (ww + 22 * s) * (1 - open * 0.85f);
                p.setColor(0xFFD9D4CA);
                c.drawRoundRect(mx - dw * open * 0.3f, my + 6 * s, mx - dw * open * 0.3f + dw, my + mh - 6 * s, 10 * s, 10 * s, p);
                // Steam out of the open door.
                if (rnd.nextFloat() < 0.4f) steam.add(new float[]{wx + ww * (0.3f + 0.4f * rnd.nextFloat()), wy + wh * 0.4f, t});
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(8 * s);
                p.setColor(0xFFD9D4CA);
                r.set(wx - 4 * s, wy - 4 * s, wx + ww + 4 * s, wy + wh + 4 * s);
                c.drawRoundRect(r, 12 * s, 12 * s, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFB9B3A8);
                c.drawRoundRect(wx + ww + 10 * s, wy + wh * 0.25f, wx + ww + 16 * s, wy + wh * 0.75f, 3 * s, 3 * s, p);
            }
            p.setStyle(Paint.Style.FILL);
            for (int i = steam.size() - 1; i >= 0; i--) {
                float[] q = steam.get(i);
                float age = t - q[2];
                if (age > 2) {
                    steam.remove(i);
                    continue;
                }
                p.setColor(0xFFFFFFFF);
                p.setAlpha((int) (90 * (1 - age / 2)));
                c.drawCircle(q[0] + (float) Math.sin(age * 4 + i) * 10 * s, q[1] - age * 80 * s, (6 + age * 14) * s, p);
            }
            p.setAlpha(255);
            // Control panel: green display counting down, keypad, start.
            float cx0 = wx + ww + 26 * s, cw = mx + mw - 14 * s - cx0;
            p.setColor(0xFF101410);
            r.set(cx0, wy, cx0 + cw, wy + 34 * s);
            c.drawRoundRect(r, 6 * s, 6 * s, p);
            float left = Math.max(0, TOTAL - t);
            String disp = done ? (((int) (t * 2)) % 2 == 0 ? "END" : "") : String.format(Locale.ROOT, "%d:%02d", (int) left / 60, (int) left % 60);
            p.setColor(0xFF4CFF7A);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(22 * s);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(disp, cx0 + cw / 2, wy + 25 * s, p);
            p.setColor(0xFFCFCAC0);
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 3; col++) {
                    float kx = cx0 + col * cw / 3f + 4 * s, ky = wy + 44 * s + row * 22 * s;
                    c.drawRoundRect(kx, ky, kx + cw / 3f - 8 * s, ky + 16 * s, 4 * s, 4 * s, p);
                }
            }
            p.setColor(done ? 0xFF6E6A62 : 0xFF2BBF6A);
            c.drawRoundRect(cx0 + 4 * s, wy + 134 * s, cx0 + cw - 4 * s, wy + wh, 6 * s, 6 * s, p);
            p.setTextAlign(Paint.Align.LEFT);

            // Header and the bowl at the end.
            p.setColor(0xFFF4F2EC);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(12));
            p.setLetterSpacing(0.24f);
            c.drawText("🍿 KINO-MODUS", dp(22), dp(48), p);
            p.setLetterSpacing(0);
            close.set(w - dp(64), dp(18), w - dp(12), dp(66));
            p.setTextSize(dp(24));
            c.drawText("✕", w - dp(48), dp(52), p);
            p.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            p.setTextSize(dp(30));
            String headline = done ? "Popcorn fertig! 🍿" : t < 40 ? "Mikrowelle läuft …" : t < 100 ? "Es fängt an zu poppen!" : t < 125 ? "Popcorn-Sturm!" : "Gleich fertig …";
            c.drawText(headline, dp(22), dp(96), p);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(12));
            p.setColor(0xFFA5A5A0);
            c.drawText(done ? "FILM AB – VIEL SPASS" : "MACH'S DIR GEMÜTLICH · LICHT DIMMEN · HANDY LEISE", dp(22), dp(120), p);

            if (done) {
                if (!buzzed) {
                    buzzed = true;
                    Vibrator vib = getSystemService(Vibrator.class);
                    if (vib != null) vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 90, 120}, -1));
                }
                float k = Math.min(1, (t - TOTAL - 1.6f) / 0.7f);
                if (k > 0) {
                    float bwl = 150 * s, bxl = w / 2f, byl = counter + dp(18) + (h - counter) * 0.35f;
                    for (int i = 0; i < 26; i++) {
                        float a = i / 26f;
                        p.setColor(i % 3 == 0 ? 0xFFFFE9A8 : 0xFFFFF8E8);
                        p.setAlpha((int) (255 * k));
                        c.drawCircle(bxl + (float) Math.cos(a * 17) * bwl * 0.4f, byl - 20 * s - (float) Math.abs(Math.sin(a * 11)) * 26 * s, 9 * s, p);
                    }
                    p.setAlpha((int) (255 * k));
                    p.setColor(0xFFC8372D);
                    path.reset();
                    path.moveTo(bxl - bwl / 2, byl - 20 * s);
                    path.lineTo(bxl + bwl / 2, byl - 20 * s);
                    path.lineTo(bxl + bwl * 0.36f, byl + 50 * s);
                    path.lineTo(bxl - bwl * 0.36f, byl + 50 * s);
                    path.close();
                    c.drawPath(path, p);
                    p.setColor(0xFFFFFFFF);
                    for (int i = -2; i <= 2; i++) c.drawRect(bxl + i * bwl * 0.16f - 5 * s, byl - 20 * s, bxl + i * bwl * 0.16f + 5 * s, byl + 50 * s, p);
                    p.setAlpha(255);
                    // Buttons.
                    float by2 = h - dp(150);
                    startBtn.set(dp(22), by2, w - dp(22), by2 + dp(58));
                    backBtn.set(dp(22), by2 + dp(68), w - dp(22), by2 + dp(116));
                    p.setColor(0xFFB5271E);
                    c.drawRoundRect(startBtn, dp(16), dp(16), p);
                    p.setColor(0x33FFFFFF);
                    c.drawRoundRect(backBtn, dp(16), dp(16), p);
                    p.setColor(0xFFFFFFFF);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                    p.setTextSize(dp(15));
                    c.drawText("▶ FILM STARTEN", startBtn.centerX(), startBtn.centerY() + dp(5), p);
                    c.drawText("ZURÜCK ZUM HOME", backBtn.centerX(), backBtn.centerY() + dp(5), p);
                    p.setTextAlign(Paint.Align.LEFT);
                }
            } else {
                // Progress line along the bottom.
                p.setColor(0x33FFFFFF);
                c.drawRect(0, h - dp(4), w, h, p);
                p.setColor(0xFFFFC94A);
                c.drawRect(0, h - dp(4), w * t / TOTAL, h, p);
            }
            shake *= 0.8f;
            if (shake > 0.05f) setTranslationX((rnd.nextFloat() - 0.5f) * dp(2) * shake);
            else setTranslationX(0);
            postInvalidateOnAnimation();
        }

        private float shake;

        private int blend(int a, int b, float k) {
            k = Math.max(0, Math.min(1, k));
            int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return 0xFF000000 | ((int) (ar + (br - ar) * k) << 16) | ((int) (ag + (bg - ag) * k) << 8) | (int) (ab + (bb - ab) * k);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float x = e.getX(), y = e.getY();
            if (close.contains(x, y) || backBtn.contains(x, y)) {
                finish();
            } else if (startBtn.contains(x, y)) {
                startMovie();
            }
            return true;
        }
    }

    /** The first streaming app that's installed. */
    private void startMovie() {
        String[] pkgs = {"com.netflix.mediaclient", "com.disney.disneyplus", "ch.srgssr.playsuisse", "com.amazon.avod.thirdpartyclient",
            "com.plexapp.android", "com.google.android.youtube"};
        for (String pk : pkgs) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pk);
            if (i != null) {
                startActivity(i);
                finish();
                return;
            }
        }
        finish();
    }
}
