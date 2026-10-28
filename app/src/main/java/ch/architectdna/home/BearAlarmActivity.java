package ch.architectdna.home;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Canvas;
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

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * The bear alarm: you wake up in a dark cave and a bear walks out of the darkness towards you,
 * bigger with every heavy step. Each step shakes the screen and the phone, the growl gets louder,
 * and it roars every four seconds – after ~50 s it gets angry: longer, higher roars and, if your
 * alarm volume is low, the volume is raised to the maximum after 90 s. Every wake-up is logged
 * (AlarmLog): how long it rang, snoozes, how long you needed.
 * Hold "AUFWACHEN" for a second to stop it; "SNOOZE" sends the bear
 * back into the cave for five minutes.
 */
public class BearAlarmActivity extends Activity {

    private static final int RATE = 22050;
    /** Seconds until the bear is right in front of you. */
    private static final float APPROACH_S = 45f;
    private static final long HOLD_MS = 1000;
    /** From here on (a multiple of the 8 s loop) the angry loop plays. */
    private static final float ANGRY_S = 48f;
    /** Still not up: the alarm stream goes to full volume. */
    private static final float BOOST_S = 90f;

    private AudioTrack track;
    private Vibrator vibrator;
    private BearView view;
    private boolean done, angry, boosted;
    private int alarmVolBefore = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DiagLog.init(this);
        DiagLog.log("bear alarm shown");
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new BearView(this);
        setContentView(view);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        vibrator = getSystemService(Vibrator.class);
        AlarmLog.ringStarted(this, getIntent().getStringExtra("label"));
        startSound(renderLoop(false));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!done) AlarmLog.finished(this, false);     // swiped away / killed without an answer
        stopAll();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // The bear doesn't go away that easily: back does nothing, use the buttons.
    }

    private void stopAll() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
            track = null;
        }
        if (vibrator != null) vibrator.cancel();
        restoreVolume();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(AlarmReceiver.NOTIFICATION_ID);
    }

    /** 90 s and still asleep: the alarm stream to its maximum (put back afterwards). */
    private void boostVolume() {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        int cur = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM), max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
        if (cur >= max) return;
        try {
            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, max, 0);
            alarmVolBefore = cur;
            AlarmLog.boosted(this);
            DiagLog.log("bear alarm: alarm volume " + cur + " -> " + max);
        } catch (SecurityException ignored) {          // do-not-disturb may refuse
        }
    }

    private void restoreVolume() {
        if (alarmVolBefore < 0) return;
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        try {
            if (am != null) am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, alarmVolBefore, 0);
        } catch (SecurityException ignored) {
        }
        alarmVolBefore = -1;
    }

    private void wakeUp() {
        if (done) return;
        done = true;
        AlarmScheduler.cancelSnooze(this);
        AlarmLog.finished(this, true);
        stopAll();
        finish();
    }

    private void snooze() {
        if (done) return;
        done = true;
        AlarmScheduler.snooze(this, 5);
        AlarmLog.snoozed(this);
        stopAll();
        finish();
    }

    /* ================= Sound: an 8-second loop of steps, growl and a roar ================= */

    private void startSound(short[] pcm) {
        float vol = 0.25f;
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
            track = null;
            vol = 1f;
        }
        try {
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
            track.write(pcm, 0, pcm.length);
            track.setLoopPoints(0, pcm.length, -1);
            track.setVolume(vol);
            track.play();
        } catch (Exception e) {
            track = null;
        }
    }

    /**
     * How far the mouth is open (0..1) at this point of the 8 s loop: calm roars 1.8 s every 4 s,
     * angry ones 2.6 s every 4 s. The sound uses the same windows, so mouth and roar match.
     */
    static float roarAt(float loop, boolean angry) {
        float in = loop % 4f, from = angry ? 0.4f : 1.6f, len = angry ? 2.6f : 1.8f;
        if (in < from || in > from + len) return 0;
        return (in - from) / len;
    }

    /**
     * Steps every second (on the beat of the animation), a low growl under them and a roar every
     * 4 s. Phone speakers barely play below ~200 Hz, so steps and roar carry upper harmonics and a
     * rasp up to ~2 kHz – that is what you actually hear from a phone on the night table.
     */
    static short[] renderLoop(boolean angry) {
        int n = 8 * RATE;
        short[] out = new short[n];
        Random rnd = new Random(angry ? 5 : 3);
        double growlPhase = 0, roarPhase = 0, lp = 0, lp2 = 0, hp = 0;
        for (int i = 0; i < n; i++) {
            double sec = (double) i / RATE, inStep = sec % 1.0;
            // Footstep: a 55 Hz thump plus a 170 Hz knock the small speaker can play.
            double dec = Math.exp(-inStep * 18);
            double step = (Math.sin(2 * Math.PI * 55 * inStep) * 0.8 + Math.sin(2 * Math.PI * 170 * inStep) * 0.55) * dec * 1.1;
            // Growl: rough saw around 75 Hz and its octave, breathing.
            double f = (angry ? 95 : 72) + 10 * Math.sin(2 * Math.PI * 0.5 * sec) + rnd.nextGaussian() * 6;
            growlPhase += f / RATE;
            double saw = 2 * (growlPhase - Math.floor(growlPhase + 0.5));
            double saw2 = 2 * (growlPhase * 2 - Math.floor(growlPhase * 2 + 0.5));
            double breath = 0.5 + 0.5 * Math.sin(2 * Math.PI * 0.25 * sec - 1.2);
            double growl = (saw * 0.18 + saw2 * 0.12) * breath * (angry ? 1.3 : 1);
            // Roar: a falling saw with rasp (noise), bright enough for a phone speaker.
            double roar = 0;
            float open = roarAt((float) sec, angry);
            if (open > 0) {
                double t = open;
                double env = Math.sin(Math.PI * Math.min(1, t * 1.5)) * (1 - t * 0.35);
                double rf = (angry ? 320 - 150 * t : 240 - 100 * t) * (1 + 0.04 * Math.sin(2 * Math.PI * 23 * sec)) + rnd.nextGaussian() * 20;
                roarPhase += rf / RATE;
                double rs = 2 * (roarPhase - Math.floor(roarPhase + 0.5));
                double noise = rnd.nextDouble() * 2 - 1;
                lp += 0.45 * ((rs * 0.85 + noise * 0.55) - lp);
                hp += 0.08 * (lp - hp);
                roar = (lp - hp * 0.6) * env * (angry ? 2.0 : 1.7);
            }
            lp2 += 0.6 * ((step + growl + roar) - lp2);
            double v = Math.tanh(lp2 * 1.4);                    // soft clip: louder without crackle
            out[i] = (short) Math.max(-32767, Math.min(32767, v * 32767 * 0.8));
        }
        return out;
    }

    /* ================= The cave ================= */

    private final class BearView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final long born = SystemClock.uptimeMillis();
        private final RectF wakeBtn = new RectF(), snoozeBtn = new RectF();
        private long holdStart = -1, lastStep = -1;
        private final Random rnd = new Random();

        BearView(Context c) {
            super(c);
            setKeepScreenOn(true);
            postOnAnimation(frame);
        }

        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (done || !isAttachedToWindow()) return;
                if (holdStart >= 0 && SystemClock.uptimeMillis() - holdStart >= HOLD_MS) {
                    wakeUp();
                    return;
                }
                invalidate();
                postOnAnimation(this);
            }
        };

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                if (wakeBtn.contains(e.getX(), e.getY())) {
                    holdStart = SystemClock.uptimeMillis();
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                } else if (snoozeBtn.contains(e.getX(), e.getY())) {
                    snooze();
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                holdStart = -1;
            } else if (a == MotionEvent.ACTION_MOVE && holdStart >= 0 && !wakeBtn.contains(e.getX(), e.getY())) {
                holdStart = -1;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            long now = SystemClock.uptimeMillis();
            float sec = (now - born) / 1000f;
            float near = Math.min(1f, sec / APPROACH_S);          // 0 = deep in the cave, 1 = in your face
            float ease = 1 - (1 - near) * (1 - near);

            // One heavy step per second: screen shake, vibration and sound volume grow as it nears.
            float inStep = sec % 1f;
            long stepNo = (long) sec;
            if (stepNo != lastStep) {
                lastStep = stepNo;
                if (vibrator != null) {
                    int amp = (int) (60 + 195 * ease);
                    vibrator.vibrate(VibrationEffect.createOneShot(140 + (long) (160 * ease), Math.min(255, amp)));
                }
                if (track != null) track.setVolume(0.25f + 0.75f * ease);
                if (!angry && sec >= ANGRY_S) {
                    angry = true;
                    startSound(renderLoop(true));
                    AlarmLog.roared(BearAlarmActivity.this);
                    DiagLog.log("bear alarm: angry after " + (int) sec + " s");
                }
                if (!boosted && sec >= BOOST_S) {
                    boosted = true;
                    boostVolume();
                }
            }
            float shake = (float) Math.exp(-inStep * 9) * dp(2 + 16 * ease);
            c.save();
            c.translate((rnd.nextFloat() - 0.5f) * shake, (rnd.nextFloat() - 0.5f) * shake);

            // Cave: black, with a dim warm opening that the bear blocks more and more.
            c.drawColor(0xFF050403);
            float cx = w / 2, cy = h * 0.46f;
            paint.setShader(new RadialGradient(cx, cy, w * 0.9f,
                new int[]{0xFF3A2A1C, 0xFF140E09, 0xFF050403}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            // Rock arch.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF000000);
            path.reset();
            path.moveTo(0, 0);
            path.lineTo(w, 0);
            path.lineTo(w, h);
            path.lineTo(w * 0.94f, h);
            path.cubicTo(w * 0.98f, h * 0.55f, w * 0.80f, h * 0.12f, cx, h * 0.10f);
            path.cubicTo(w * 0.20f, h * 0.12f, w * 0.02f, h * 0.55f, w * 0.06f, h);
            path.lineTo(0, h);
            path.close();
            c.drawPath(path, paint);
            // Stalactites.
            for (int i = 0; i < 9; i++) {
                float sx = w * (0.12f + i * 0.095f), len = h * (0.04f + 0.05f * ((i * 37) % 5) / 4f);
                path.reset();
                path.moveTo(sx - dp(10), h * 0.10f);
                path.lineTo(sx + dp(10), h * 0.10f);
                path.lineTo(sx + dp(1), h * 0.10f + len);
                path.close();
                c.drawPath(path, paint);
            }

            // The bear: grows from a speck to bigger than the screen, bobbing with each step.
            float size = w * (0.08f + 1.6f * ease * ease);
            float bob = (float) Math.abs(Math.sin(Math.PI * inStep)) * size * 0.04f;
            float by = cy + size * 0.15f - bob + ease * h * 0.12f;
            drawBear(c, cx, by, size, ease, sec);
            c.restore();

            // Red heartbeat vignette at the end.
            if (ease > 0.6f) {
                float pulse = (float) (0.5 + 0.5 * Math.sin(sec * 7));
                int alpha = (int) (110 * (ease - 0.6f) / 0.4f * pulse);
                paint.setShader(new RadialGradient(cx, h / 2, h * 0.75f,
                    new int[]{0x00B5271E, (alpha << 24) | 0xB5271E}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, paint);
                paint.setShader(null);
            }
            drawHud(c, w, h, now);
        }

        /** A front-on bear in the ARC-DNA line style: dark fur, ink outlines, glowing rust eyes. */
        private void drawBear(Canvas c, float cx, float cy, float s, float ease, float sec) {
            int fur = 0xFF1E140D, edge = 0xFF5A3B24;
            paint.setStyle(Paint.Style.FILL);
            // Shoulders / body.
            paint.setColor(fur);
            rect.set(cx - s * 0.75f, cy + s * 0.15f, cx + s * 0.75f, cy + s * 1.6f);
            c.drawOval(rect, paint);
            // Ears.
            c.drawCircle(cx - s * 0.36f, cy - s * 0.36f, s * 0.15f, paint);
            c.drawCircle(cx + s * 0.36f, cy - s * 0.36f, s * 0.15f, paint);
            // Head.
            rect.set(cx - s * 0.48f, cy - s * 0.42f, cx + s * 0.48f, cy + s * 0.42f);
            c.drawOval(rect, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(dp(1.5f), s * 0.012f));
            paint.setColor(edge);
            c.drawOval(rect, paint);
            c.drawCircle(cx - s * 0.36f, cy - s * 0.36f, s * 0.15f, paint);
            c.drawCircle(cx + s * 0.36f, cy - s * 0.36f, s * 0.15f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF3A2616);
            c.drawCircle(cx - s * 0.36f, cy - s * 0.36f, s * 0.07f, paint);
            c.drawCircle(cx + s * 0.36f, cy - s * 0.36f, s * 0.07f, paint);
            // Muzzle and nose.
            paint.setColor(0xFF4A3220);
            rect.set(cx - s * 0.2f, cy + s * 0.02f, cx + s * 0.2f, cy + s * 0.32f);
            c.drawOval(rect, paint);
            paint.setColor(0xFF080605);
            rect.set(cx - s * 0.08f, cy + s * 0.04f, cx + s * 0.08f, cy + s * 0.13f);
            c.drawOval(rect, paint);
            // Mouth: opens on the roar (the sound loop roars around 5.6-7.4 s of every 8).
            float loop = angry ? (sec - ANGRY_S) % 8f : sec % 8f, ro = roarAt(loop, angry);
            float open = ro > 0 ? (float) Math.sin(Math.PI * ro) : 0.08f;
            open *= 0.3f + 0.7f * ease;
            paint.setColor(0xFF2A0806);
            rect.set(cx - s * 0.13f, cy + s * 0.18f, cx + s * 0.13f, cy + s * (0.2f + 0.2f * open));
            c.drawOval(rect, paint);
            if (open > 0.3f) {
                paint.setColor(0xFFE8E2D4);
                for (int i = -1; i <= 1; i += 2) {
                    path.reset();
                    float tx = cx + i * s * 0.08f, ty = cy + s * 0.19f;
                    path.moveTo(tx - s * 0.025f, ty);
                    path.lineTo(tx + s * 0.025f, ty);
                    path.lineTo(tx, ty + s * 0.06f * open);
                    path.close();
                    c.drawPath(path, paint);
                }
            }
            // Eyes: rust glow, brighter the closer it gets.
            float er = s * 0.045f;
            int glow = (int) (120 + 135 * ease);
            for (int i = -1; i <= 1; i += 2) {
                float ex = cx + i * s * 0.2f, ey = cy - s * 0.1f;
                paint.setShader(new RadialGradient(ex, ey, er * 4,
                    new int[]{(glow << 24) | 0xFF4A2E, 0x00B5271E}, null, Shader.TileMode.CLAMP));
                c.drawCircle(ex, ey, er * 4, paint);
                paint.setShader(null);
                paint.setColor(0xFFFFB27A);
                c.drawCircle(ex, ey, er, paint);
            }
            // Claws on the front paws when close.
            if (ease > 0.4f) {
                paint.setColor(0xFFD9D2C3);
                for (int side = -1; side <= 1; side += 2) {
                    float px = cx + side * s * 0.55f, py = cy + s * 1.05f;
                    for (int k = 0; k < 4; k++) {
                        float kx = px + (k - 1.5f) * s * 0.05f;
                        path.reset();
                        path.moveTo(kx - s * 0.012f, py);
                        path.lineTo(kx + s * 0.012f, py);
                        path.lineTo(kx, py + s * 0.07f);
                        path.close();
                        c.drawPath(path, paint);
                    }
                }
            }
        }

        private void drawHud(Canvas c, float w, float h, long now) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setLetterSpacing(0.14f);
            paint.setTextSize(dp(12));
            paint.setColor(0xFFB5271E);
            float top = dp(118);        // below the heads-up notification
            long ring = (long) ((now - born) / 1000f);
            String run = "KLINGELT SEIT " + ring / 60 + ":" + String.format(Locale.ROOT, "%02d", ring % 60)
                + (angry ? " · WÜTEND" : "") + (boosted ? " · LAUT" : "");
            c.drawText("ARC-DNA · WECKER", dp(24), top, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(angry ? 0xFFE8B931 : 0xFF9A9A98);
            paint.setTextSize(dp(10));
            c.drawText(run, w - dp(24), top, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(dp(12));
            Calendar cal = Calendar.getInstance();
            String time = String.format(Locale.ROOT, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            paint.setLetterSpacing(-0.05f);
            paint.setTextSize(dp(88));
            paint.setColor(0xFFF2EFE8);
            c.drawText(time, dp(20), top + dp(84), paint);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setLetterSpacing(0.12f);
            paint.setTextSize(dp(12));
            paint.setColor(0xFF9A9A98);
            String label = getIntent().getStringExtra("label");
            c.drawText(label != null && !label.isEmpty() ? "🐻 " + label.toUpperCase(Locale.GERMAN) : "DER BÄR KOMMT AUS DER HÖHLE …", dp(24), top + dp(110), paint);

            // Snooze (tap) and wake up (hold 1 s, fills up).
            float pad = dp(24), bh = dp(60), bottom = h - dp(48);
            snoozeBtn.set(pad, bottom - bh * 2 - dp(14), w - pad, bottom - bh - dp(14));
            wakeBtn.set(pad, bottom - bh, w - pad, bottom);
            paint.setColor(0x33F2EFE8);
            c.drawRoundRect(snoozeBtn, dp(8), dp(8), paint);
            paint.setColor(0xFFB5271E);
            c.drawRoundRect(wakeBtn, dp(8), dp(8), paint);
            if (holdStart >= 0) {
                float k = Math.min(1f, (now - holdStart) / (float) HOLD_MS);
                paint.setColor(0xFF7A140E);
                c.save();
                c.clipRect(wakeBtn.left, wakeBtn.top, wakeBtn.left + wakeBtn.width() * k, wakeBtn.bottom);
                c.drawRoundRect(wakeBtn, dp(8), dp(8), paint);
                c.restore();
            }
            paint.setTextSize(dp(14));
            paint.setColor(0xFFF2EFE8);
            String s1 = "SNOOZE 5 MIN · BÄR ZURÜCK IN DIE HÖHLE";
            String s2 = holdStart >= 0 ? "HALTEN …" : "AUFWACHEN · 1 S HALTEN";
            if (paint.measureText(s1) > snoozeBtn.width() - dp(20)) s1 = "SNOOZE 5 MIN";
            c.drawText(s1, snoozeBtn.centerX() - paint.measureText(s1) / 2, snoozeBtn.centerY() + dp(5), paint);
            c.drawText(s2, wakeBtn.centerX() - paint.measureText(s2) / 2, wakeBtn.centerY() + dp(5), paint);
        }
    }
}
