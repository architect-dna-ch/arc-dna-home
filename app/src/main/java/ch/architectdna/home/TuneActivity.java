package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;
import java.util.Random;

/**
 * Pfeif- & Klavier-Lehrer: pick a melody, ▶ hear it, then whistle (or play / sing) it note by
 * note. The microphone finds your pitch (autocorrelation, on the phone), a keyboard shows the
 * note you should hit in rust and the one you hit in blue, a needle shows sharp / flat. Octaves
 * don't matter, so a whistle two octaves up counts. "Echo" plays a short random pattern for you
 * to whistle back. Tap a key to hear it.
 */
public class TuneActivity extends Activity {

    private static final int RATE = 44100;
    private static final String[] NAMES = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "H"};
    /** Melodies as MIDI notes (C4 = 60). */
    private static final String[] SONG_NAMES = {"Tonleiter", "Alle meine Entchen", "Ode an die Freude", "Happy Birthday", "Echo"};
    private static final int[][] SONGS = {
        {60, 62, 64, 65, 67, 69, 71, 72},
        {60, 62, 64, 65, 67, 67, 69, 69, 69, 69, 67, 69, 69, 69, 69, 67, 65, 65, 65, 65, 64, 64, 62, 62, 62, 62, 60},
        {64, 64, 65, 67, 67, 65, 64, 62, 60, 60, 62, 64, 64, 62, 62},
        {60, 60, 62, 60, 65, 64, 60, 60, 62, 60, 67, 65},
        {}};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private boolean night;
    private int bg, card, line, ink, ink2, ink3, rust, blue = 0xFF3C8DFF, green = 0xFF2BBF6A;
    private Stage stage;
    private volatile boolean listening;
    private Thread reader;

    private int song = 0, step = 0;
    private int[] notes = SONGS[0];
    private volatile float heardHz = -1;
    private float heardMidi = -1;
    private long holdSince = -1, doneAt = -1, startAt;
    private int playingIndex = -1;
    private boolean playing;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFEDEDE8 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!night && Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(bits, bits);
        }
        stage = new Stage();
        stage.setBackgroundColor(bg);
        setContentView(stage);
        pick(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) listen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        listening = false;
    }

    private void pick(int i) {
        song = i;
        if (i == SONGS.length - 1) {
            // Echo: a short random pattern from the C major scale.
            int[] scale = {60, 62, 64, 65, 67, 69, 71, 72};
            notes = new int[3 + rnd.nextInt(3)];
            for (int k = 0; k < notes.length; k++) notes[k] = scale[rnd.nextInt(scale.length)];
            main.postDelayed(this::play, 500);
        } else {
            notes = SONGS[i];
        }
        step = 0;
        doneAt = -1;
        holdSince = -1;
        startAt = SystemClock.uptimeMillis();
        stage.invalidate();
    }

    /* ---------------- listening: pitch by autocorrelation ---------------- */

    private void listen() {
        if (listening) return;
        listening = true;
        reader = new Thread(() -> {
            int size = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192);
            AudioRecord rec;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    rec.release();
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                }
                rec.startRecording();
            } catch (RuntimeException e) {
                listening = false;
                return;
            }
            short[] buf = new short[4096];
            float[] x = new float[buf.length];
            while (listening) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 1024) continue;
                double rms = 0;
                for (int i = 0; i < n; i++) {
                    x[i] = buf[i] / 32768f;
                    rms += x[i] * x[i];
                }
                rms = Math.sqrt(rms / n);
                heardHz = rms < 0.01 || playing ? -1 : pitch(x, n);
            }
            rec.stop();
            rec.release();
        }, "arc-tune");
        reader.start();
        stage.postInvalidateOnAnimation();
    }

    /** YIN-style: cumulative mean normalized difference, first dip under the threshold, parabolic refine. */
    private static float pitch(float[] x, int n) {
        int minLag = RATE / 4200, maxLag = Math.min(n / 2, RATE / 70);
        float[] d = new float[maxLag + 1];
        int w = n - maxLag;
        for (int lag = 1; lag <= maxLag; lag++) {
            float s = 0;
            for (int i = 0; i < w; i++) {
                float diff = x[i] - x[i + lag];
                s += diff * diff;
            }
            d[lag] = s;
        }
        float run = 0;
        d[0] = 1;
        for (int lag = 1; lag <= maxLag; lag++) {
            run += d[lag];
            d[lag] = run == 0 ? 1 : d[lag] * lag / run;
        }
        for (int lag = minLag; lag < maxLag; lag++) {
            if (d[lag] < 0.15f) {
                while (lag + 1 < maxLag && d[lag + 1] < d[lag]) lag++;
                float a = d[lag - 1], b = d[lag], c = d[lag + 1], den = a - 2 * b + c;
                float shift = den == 0 ? 0 : 0.5f * (a - c) / den;
                return RATE / (lag + shift);
            }
        }
        return -1;
    }

    private static float midiOf(float hz) {
        return (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2));
    }

    /* ---------------- playing the melody (soft whistle-like tones) ---------------- */

    private void play() {
        if (playing) return;
        playing = true;
        int[] seq = notes.clone();
        new Thread(() -> {
            int noteLen = (int) (RATE * 0.42), gap = (int) (RATE * 0.08);
            short[] pcm = new short[(noteLen + gap) * seq.length];
            for (int k = 0; k < seq.length; k++) {
                double f = 440 * Math.pow(2, (seq[k] + 12 - 69) / 12.0);       // an octave up: more like a whistle
                for (int i = 0; i < noteLen; i++) {
                    double env = Math.min(1, i / (RATE * 0.03)) * Math.min(1, (noteLen - i) / (RATE * 0.08));
                    double vib = 1 + 0.004 * Math.sin(2 * Math.PI * 5.5 * i / RATE);
                    pcm[k * (noteLen + gap) + i] = (short) (Math.sin(2 * Math.PI * f * vib * i / RATE) * env * 9000);
                }
            }
            AudioTrack t = null;
            try {
                t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
                t.write(pcm, 0, pcm.length);
                t.play();
                for (int k = 0; k < seq.length; k++) {
                    int idx = k;
                    main.post(() -> {
                        playingIndex = idx;
                        stage.invalidate();
                    });
                    SystemClock.sleep((noteLen + gap) * 1000L / RATE);
                }
            } catch (RuntimeException ignored) {
            } finally {
                if (t != null) t.release();
                main.post(() -> {
                    playing = false;
                    playingIndex = -1;
                    stage.invalidate();
                });
            }
        }, "arc-tune-play").start();
    }

    private void tone(int midi) {
        int[] save = notes;
        notes = new int[]{midi};
        play();
        notes = save;
    }

    /* ---------------- the screen ---------------- */

    private final class Stage extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final RectF[] chips = new RectF[SONG_NAMES.length];
        private final RectF playBtn = new RectF(), closeBtn = new RectF();
        private float kbTop, kbLeft, whiteW;

        Stage() {
            super(TuneActivity.this);
            for (int i = 0; i < chips.length; i++) chips[i] = new RectF();
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            long now = SystemClock.uptimeMillis();
            float top = dp(52);
            // Header.
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(11));
            p.setLetterSpacing(0.24f);
            p.setColor(ink3);
            c.drawText("— PFEIF- & KLAVIER-LEHRER", dp(20), top, p);
            p.setLetterSpacing(0);
            p.setTextSize(dp(22));
            p.setColor(ink);
            closeBtn.set(w - dp(56), top - dp(30), w - dp(12), top + dp(10));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("✕", closeBtn.centerX(), top + dp(2), p);
            p.setTextAlign(Paint.Align.LEFT);

            // Melody chips.
            float x = dp(20), y = top + dp(22);
            p.setTextSize(dp(12));
            for (int i = 0; i < SONG_NAMES.length; i++) {
                String s = SONG_NAMES[i].toUpperCase(Locale.GERMAN);
                float tw = p.measureText(s) + dp(26);
                if (x + tw > w - dp(20)) {
                    x = dp(20);
                    y += dp(42);
                }
                chips[i].set(x, y, x + tw, y + dp(34));
                p.setStyle(Paint.Style.FILL);
                p.setColor(i == song ? rust : card);
                c.drawRoundRect(chips[i], dp(17), dp(17), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(i == song ? rust : line);
                c.drawRoundRect(chips[i], dp(17), dp(17), p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(i == song ? 0xFFFFFFFF : ink2);
                c.drawText(s, x + dp(13), y + dp(22), p);
                x += tw + dp(8);
            }
            y += dp(58);

            // The melody as a row of notes: done green, current rust (pulsing), coming grey.
            float noteW = Math.min(dp(44), (w - dp(40)) / Math.max(1, notes.length));
            float nx = (w - noteW * notes.length) / 2;
            for (int k = 0; k < notes.length; k++) {
                float cx = nx + noteW * (k + 0.5f);
                boolean cur = k == step && doneAt < 0, done = k < step || doneAt >= 0;
                float rad = noteW * 0.36f * (cur ? 1 + 0.08f * (float) Math.sin(now / 150.0) : 1);
                p.setColor(k == playingIndex ? blue : done ? green : cur ? rust : line);
                c.drawCircle(cx, y, rad, p);
                p.setColor(done || cur || k == playingIndex ? 0xFFFFFFFF : ink3);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(Math.min(dp(13), noteW * 0.36f));
                c.drawText(NAMES[notes[k] % 12], cx, y + dp(4), p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            y += dp(40);

            // Big: what to hit, what you hit, a needle for the cents.
            int target = step < notes.length ? notes[step] : -1;
            float hz = heardHz;
            heardMidi = hz > 0 ? midiOf(hz) : -1;
            int heardNote = heardMidi > 0 ? Math.round(heardMidi) : -1;
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(72));
            float bigY = y + dp(70);
            if (doneAt >= 0) {
                p.setColor(green);
                c.drawText("Bravo! 🎉", w / 2, bigY, p);
                p.setTextSize(dp(14));
                p.setTypeface(Typeface.MONOSPACE);
                p.setColor(ink2);
                c.drawText(notes.length + " TÖNE IN " + (doneAt - startAt) / 1000 + " S · TIPPEN = NOCHMAL", w / 2, bigY + dp(30), p);
            } else {
                p.setColor(rust);
                c.drawText(target >= 0 ? NAMES[target % 12] : "–", w * 0.3f, bigY, p);
                p.setColor(heardNote >= 0 ? blue : line);
                c.drawText(heardNote >= 0 ? NAMES[heardNote % 12] : "…", w * 0.7f, bigY, p);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(11));
                p.setColor(ink3);
                c.drawText("ZIEL", w * 0.3f, bigY + dp(22), p);
                c.drawText(hz > 0 ? String.format(Locale.ROOT, "DU · %.0f HZ", hz) : "PFEIF MAL …", w * 0.7f, bigY + dp(22), p);
                // Needle: how far off the target (any octave), in cents.
                if (heardMidi > 0 && target >= 0) {
                    float off = ((heardMidi - target) % 12 + 18) % 12 - 6;      // -6 … +6 semitones
                    float cents = Math.max(-1, Math.min(1, off / 1.5f));
                    float ny = bigY + dp(52), nl = w * 0.35f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(2));
                    p.setColor(line);
                    c.drawLine(w / 2 - nl, ny, w / 2 + nl, ny, p);
                    p.setColor(Math.abs(off) < 0.5f ? green : rust);
                    p.setStrokeWidth(dp(4));
                    c.drawLine(w / 2 + cents * nl, ny - dp(12), w / 2 + cents * nl, ny + dp(12), p);
                    p.setStyle(Paint.Style.FILL);
                    p.setTextSize(dp(10));
                    p.setColor(ink3);
                    c.drawText(Math.abs(off) < 0.5f ? "TRIFFT" : off < 0 ? "HÖHER ↑" : "TIEFER ↓", w / 2, ny + dp(28), p);
                }
                // Hit and held for 0.25 s: next note.
                boolean hit = heardNote >= 0 && target >= 0 && ((heardNote - target) % 12 + 12) % 12 == 0;
                if (hit) {
                    if (holdSince < 0) holdSince = now;
                    else if (now - holdSince > 250) {
                        step++;
                        holdSince = -1;
                        if (step >= notes.length) doneAt = now;
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                    }
                } else {
                    holdSince = -1;
                }
            }
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Typeface.MONOSPACE);

            // Play button.
            float pbY = h - dp(250);
            playBtn.set(w / 2 - dp(90), pbY, w / 2 + dp(90), pbY + dp(46));
            p.setStyle(Paint.Style.FILL);
            p.setColor(playing ? blue : rust);
            c.drawRoundRect(playBtn, dp(23), dp(23), p);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(dp(13));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(playing ? "♪ HÖR ZU …" : "▶ VORSPIELEN", playBtn.centerX(), playBtn.centerY() + dp(5), p);
            p.setTextAlign(Paint.Align.LEFT);

            // Keyboard: two octaves from C4; target rust, heard blue (shown in this octave range).
            kbTop = h - dp(180);
            float kbH = dp(150);
            int whites = 15;
            whiteW = (w - dp(24)) / whites;
            kbLeft = dp(12);
            int[] whiteSemis = {0, 2, 4, 5, 7, 9, 11};
            for (int k = 0; k < whites; k++) {
                int midi = 60 + (k / 7) * 12 + whiteSemis[k % 7];
                r.set(kbLeft + k * whiteW + dp(1), kbTop, kbLeft + (k + 1) * whiteW - dp(1), kbTop + kbH);
                p.setStyle(Paint.Style.FILL);
                p.setColor(keyColour(midi, target, heardNote, 0xFFF4F2EC));
                c.drawRoundRect(r, dp(6), dp(6), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(line);
                c.drawRoundRect(r, dp(6), dp(6), p);
                if (k % 7 == 0) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(ink3);
                    p.setTextSize(dp(10));
                    c.drawText("C" + (4 + k / 7), r.left + dp(5), r.bottom - dp(8), p);
                }
            }
            int[] blackAfter = {0, 1, 3, 4, 5};      // black keys after these whites in an octave
            int[] blackSemis = {1, 3, 6, 8, 10};
            for (int o = 0; o < 2; o++) {
                for (int b = 0; b < 5; b++) {
                    int k = o * 7 + blackAfter[b];
                    int midi = 60 + o * 12 + blackSemis[b];
                    float bx = kbLeft + (k + 1) * whiteW;
                    r.set(bx - whiteW * 0.32f, kbTop, bx + whiteW * 0.32f, kbTop + kbH * 0.6f);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(keyColour(midi, target, heardNote, 0xFF1A1A1A));
                    c.drawRoundRect(r, dp(4), dp(4), p);
                }
            }
            if (listening) postInvalidateOnAnimation();
        }

        /** Pitch classes count (any octave): rust = to hit, blue = heard, green = both. */
        private int keyColour(int midi, int target, int heard, int base) {
            // Lit in the lower octave only, so one key glows, not two.
            boolean isT = target >= 0 && midi % 12 == target % 12 && midi < 72;
            boolean isH = heard >= 0 && midi % 12 == heard % 12 && midi < 72;
            if (isT && isH) return green;
            if (isT) return rust;
            if (isH) return blue;
            return base;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float x = e.getX(), y = e.getY();
            if (closeBtn.contains(x, y)) {
                finish();
                return true;
            }
            for (int i = 0; i < chips.length; i++) {
                if (chips[i].contains(x, y)) {
                    pick(i);
                    return true;
                }
            }
            if (playBtn.contains(x, y)) {
                play();
                return true;
            }
            if (doneAt >= 0 && y < playBtn.top) {
                pick(song);
                return true;
            }
            if (y > kbTop && whiteW > 0) {
                int k = (int) ((x - kbLeft) / whiteW);
                int[] whiteSemis = {0, 2, 4, 5, 7, 9, 11};
                if (k >= 0 && k < 15) tone(60 + (k / 7) * 12 + whiteSemis[k % 7] - 12);
            }
            invalidate();
            return true;
        }
    }
}
