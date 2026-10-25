package ch.architectdna.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * VOID FREQ's sound engine, ported 1:1 from its Web Audio graph (void-freq-ios www/index.html) to
 * plain Java: brown / white noise through biquad filters, sine drones, rain drops, the 72 BPM lo-fi
 * sequencer and the 200 / 210 Hz binaural pair, all generated live and played through one stereo
 * AudioTrack. Nothing is recorded or downloaded.
 */
final class FreqEngine {

    static final int RATE = 44100;
    static final String[] KEYS = {"focus", "rain", "void", "lofi", "neurons"};

    private final Random rnd = new Random();
    private final Object lock = new Object();
    private final List<Voice> voices = new ArrayList<>();
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;

    private String channel = "";
    private float volume = 0.7f, gain = 0f, gainTarget = 0f, gainRate;
    private long sample;                         // time in samples since start
    private String pending;                      // channel to start once the fade-out is done
    private long pendingAt = -1;

    /** The last ~512 mono samples, for the spectrum. */
    private final float[] scope = new float[512];
    private int scopeHead;

    String channel() {
        return channel;
    }

    void setVolume(float v) {
        synchronized (lock) {
            volume = v;
            if (!channel.isEmpty() && pending == null) fadeTo(v, 0.05f);
        }
    }

    /** Switch to a channel (fades the old one out first), or "" to stop. */
    void play(String key) {
        start();
        synchronized (lock) {
            fadeTo(0, 0.4f);
            pending = key;
            pendingAt = sample + (long) (0.5f * RATE);
            channel = key;
        }
    }

    /** The three-note timer chime, on top of whatever plays. */
    void chime() {
        start();
        synchronized (lock) {
            double[] f = {880, 1100, 1320};
            for (int i = 0; i < 3; i++) chimes.add(new Pluck(f[i], 0.2, 0.4, sample + (long) (i * 0.15 * RATE)));
        }
    }

    void copyScope(float[] out) {
        synchronized (scope) {
            for (int i = 0; i < out.length && i < scope.length; i++) out[i] = scope[(scopeHead + i) % scope.length];
        }
    }

    boolean idle() {
        synchronized (lock) {
            return channel.isEmpty() && pending == null && gain < 0.0005f && voices.isEmpty() && chimes.isEmpty();
        }
    }

    void release() {
        synchronized (lock) {
            gen++;
            running = false;
        }
    }

    private void fadeTo(float v, float seconds) {
        gainTarget = v;
        // setTargetAtTime(v, now, seconds / 4) in Web Audio: an exponential approach.
        gainRate = (float) (1 - Math.exp(-1.0 / (Math.max(0.01f, seconds / 4) * RATE)));
    }

    private int gen;

    private void start() {
        int myGen;
        synchronized (lock) {
            if (running) return;
            running = true;
            myGen = ++gen;
        }
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(Math.max(min, 8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        track.play();
        AudioTrack mine = track;
        thread = new Thread(() -> loop(myGen, mine), "voidfreq");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private void loop(int myGen, AudioTrack out) {
        short[] pcm = new short[BLOCK * 2];
        while (true) {
            synchronized (lock) {
                if (!running || gen != myGen) break;
            }
            renderBlock(pcm);
            out.write(pcm, 0, pcm.length);
            synchronized (lock) {
                // Nothing left to play: stop this thread until the next play().
                if (gen == myGen && channel.isEmpty() && pending == null && gain < 0.0005f && voices.isEmpty() && chimes.isEmpty()) {
                    running = false;
                }
            }
        }
        try {
            out.stop();
        } catch (IllegalStateException ignored) {
        }
        out.release();
    }

    static final int BLOCK = 512;
    private final float[] blockL = new float[BLOCK], blockR = new float[BLOCK];

    /** One block of interleaved stereo PCM: channel switches, all voices, master gain, chime. */
    void renderBlock(short[] pcm) {
        final int n = BLOCK;
        float[] l = blockL, r = blockR;
        java.util.Arrays.fill(l, 0);
        java.util.Arrays.fill(r, 0);
        synchronized (lock) {
            if (pending != null && sample >= pendingAt) {
                voices.clear();
                if (!pending.isEmpty()) {
                    buildChannel(pending);
                    fadeTo(volume, 0.8f);
                }
                pending = null;
            }
            for (int i = voices.size() - 1; i >= 0; i--) {
                Voice v = voices.get(i);
                v.render(l, r, n, sample);
                if (v.done(sample)) voices.remove(i);
            }
            for (int i = chimes.size() - 1; i >= 0; i--) {
                Pluck p = chimes.get(i);
                p.renderInto(directL, directR, n, sample);
                if (p.done(sample)) chimes.remove(i);
            }
            for (int i = 0; i < n; i++) {
                gain += (gainTarget - gain) * gainRate;
                // The chime goes straight out (AC.destination in the original), the rest through the master gain.
                float lv = l[i] * gain + directL[i], rv = r[i] * gain + directR[i];
                directL[i] = 0;
                directR[i] = 0;
                pcm[2 * i] = (short) Math.max(-32767, Math.min(32767, lv * 32767 * 0.9f));
                pcm[2 * i + 1] = (short) Math.max(-32767, Math.min(32767, rv * 32767 * 0.9f));
            }
            sample += n;
        }
        synchronized (scope) {
            for (int i = 0; i < n; i++) {
                scope[scopeHead] = (pcm[2 * i] + pcm[2 * i + 1]) / 65534f;
                scopeHead = (scopeHead + 1) % scope.length;
            }
        }
    }

    private final float[] directL = new float[BLOCK], directR = new float[BLOCK];
    private final List<Pluck> chimes = new ArrayList<>();

    /* ---------------- The five channels (same numbers as the original) ---------------- */

    private void buildChannel(String key) {
        long t = sample;
        switch (key) {
            case "focus":
                voices.add(new Noise(true, Biquad.lowpass(350), 1));
                voices.add(new Sine(40, 0.035, 2, t));
                break;
            case "rain":
                voices.add(new Noise(false, Biquad.bandpass(900, 2.5), 0.9));
                voices.add(new Noise(false, Biquad.lowpass(120), 0.4));
                voices.add(new Drops());
                break;
            case "void":
                double[][] stack = {{30, 0.25}, {45, 0.12}, {60, 0.08}, {90, 0.04}, {120, 0.025}};
                for (double[] s : stack) voices.add(new Sine(s[0], s[1], 4, t));
                voices.add(new Sweep());
                voices.add(new Noise(true, Biquad.lowpass(60), 0.15));
                break;
            case "lofi":
                voices.add(new Noise(false, Biquad.bandpass(3000, 12), 0.04));
                voices.add(new Lofi(t));
                break;
            case "neurons":
                voices.add(new Binaural());
                voices.add(new Noise(false, Biquad.bandpass(600, 0.8), 0.08));
                break;
            default:
                break;
        }
    }

    private abstract static class Voice {
        abstract void render(float[] l, float[] r, int n, long t0);

        boolean done(long t) {
            return false;
        }
    }

    /** RBJ biquad, matching Web Audio's lowpass / bandpass / highpass. */
    static final class Biquad {
        private double b0, b1, b2, a1, a2, x1, x2, y1, y2;

        static Biquad lowpass(double f) {
            return make(f, Math.sqrt(0.5), 0);
        }

        static Biquad highpass(double f) {
            return make(f, Math.sqrt(0.5), 2);
        }

        static Biquad bandpass(double f, double q) {
            return make(f, q, 1);
        }

        private static Biquad make(double f, double q, int type) {
            Biquad b = new Biquad();
            double w = 2 * Math.PI * f / RATE, cs = Math.cos(w), al = Math.sin(w) / (2 * q), a0 = 1 + al;
            if (type == 0) {
                b.b0 = (1 - cs) / 2 / a0;
                b.b1 = (1 - cs) / a0;
                b.b2 = b.b0;
            } else if (type == 2) {
                b.b0 = (1 + cs) / 2 / a0;
                b.b1 = -(1 + cs) / a0;
                b.b2 = b.b0;
            } else {
                b.b0 = al / a0;
                b.b1 = 0;
                b.b2 = -al / a0;
            }
            b.a1 = -2 * cs / a0;
            b.a2 = (1 - al) / a0;
            return b;
        }

        double step(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    private final class Noise extends Voice {
        private final boolean brown;
        private final Biquad f;
        private final double g;
        private double last;

        Noise(boolean brown, Biquad f, double g) {
            this.brown = brown;
            this.f = f;
            this.g = g;
        }

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            for (int i = 0; i < n; i++) {
                double w = rnd.nextDouble() * 2 - 1, x;
                if (brown) {
                    last = (last + 0.02 * w) / 1.02;
                    x = last * 3.5;
                } else {
                    x = w;
                }
                float y = (float) (f.step(x) * g);
                l[i] += y;
                r[i] += y;
            }
        }
    }

    private static final class Sine extends Voice {
        private final double f, g, fadeIn;
        private final long start;
        private double ph;

        Sine(double f, double g, double fadeIn, long start) {
            this.f = f;
            this.g = g;
            this.fadeIn = fadeIn;
            this.start = start;
        }

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            for (int i = 0; i < n; i++) {
                double env = Math.min(1, (t0 + i - start) / (fadeIn * RATE));
                float y = (float) (Math.sin(ph) * g * env);
                ph += 2 * Math.PI * f / RATE;
                l[i] += y;
                r[i] += y;
            }
        }
    }

    /** The slow 75 → 110 Hz sweep under VOID (setTargetAtTime with a 12 s constant). */
    private static final class Sweep extends Voice {
        private double f = 75, ph;

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            double k = 1 - Math.exp(-1.0 / (12.0 * RATE));
            for (int i = 0; i < n; i++) {
                f += (110 - f) * k;
                float y = (float) (Math.sin(ph) * 0.05);
                ph += 2 * Math.PI * f / RATE;
                l[i] += y;
                r[i] += y;
            }
        }
    }

    /** 200 Hz left, 210 Hz right: a 10 Hz alpha beat between the ears. */
    private static final class Binaural extends Voice {
        private double pl, pr;

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            for (int i = 0; i < n; i++) {
                l[i] += (float) (Math.sin(pl) * 0.35);
                r[i] += (float) (Math.sin(pr) * 0.35);
                pl += 2 * Math.PI * 200 / RATE;
                pr += 2 * Math.PI * 210 / RATE;
            }
        }
    }

    /** A short decaying sine: rain drops and the timer chime. */
    private static final class Pluck extends Voice {
        private final double f, g, len;
        private final long start;
        private double ph;

        Pluck(double f, double g, double len, long start) {
            this.f = f;
            this.g = g;
            this.len = len;
            this.start = start;
        }

        @Override
        void render(float[] l, float[] r, int n, long t0) {
        }

        void renderInto(float[] l, float[] r, int n, long t0) {
            for (int i = 0; i < n; i++) {
                long t = t0 + i - start;
                if (t < 0) continue;
                double sec = t / (double) RATE;
                if (sec > len + 0.02) break;
                // exponentialRampToValueAtTime(0.001, len): g · 0.001^(sec / len)
                double env = g * Math.pow(0.001 / g, Math.min(1, sec / len));
                float y = (float) (Math.sin(ph) * env);
                ph += 2 * Math.PI * f / RATE;
                l[i] += y;
                r[i] += y;
            }
        }

        @Override
        boolean done(long t) {
            return t > start + (long) ((len + 0.05) * RATE);
        }
    }

    /** Rain drops: a sine blip every 40–260 ms, 600–1400 Hz. */
    private final class Drops extends Voice {
        private final List<Pluck> live = new ArrayList<>();
        private long next = -1;

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            if (next < 0) next = t0;
            while (next < t0 + n) {
                live.add(new Pluck(600 + rnd.nextDouble() * 800, 0.08 + rnd.nextDouble() * 0.12, 0.025, next));
                next += (long) ((40 + rnd.nextDouble() * 220) / 1000.0 * RATE);
            }
            for (int i = live.size() - 1; i >= 0; i--) {
                Pluck p = live.get(i);
                p.renderInto(l, r, n, t0);
                if (p.done(t0 + n)) live.remove(i);
            }
        }
    }

    /** The lo-fi loop: 72 BPM eighths, kick on 1 and 5, hats on 3, 5, 7, an Am7-ish chord per bar. */
    private final class Lofi extends Voice {
        private final double step = 60.0 / 72 / 2;
        private final double[][] chords = {{220, 277.2, 329.6, 415}, {174.6, 220, 261.6, 311.1}, {261.6, 329.6, 392, 493.9}, {196, 246.9, 329.6, 415}};
        private final int[][] beat = {{1, 0, 1}, {0, 0, 0}, {0, 1, 0}, {0, 0, 0}, {1, 1, 0}, {0, 0, 0}, {0, 1, 0}, {0, 0, 0}};
        private final List<Voice> hits = new ArrayList<>();
        private long nextStep;
        private int stepI, chordI;

        Lofi(long start) {
            nextStep = start + (long) (0.05 * RATE);
        }

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            while (nextStep < t0 + n) {
                int[] b = beat[stepI % 8];
                if (b[0] == 1) hits.add(new Kick(nextStep));
                if (b[1] == 1) hits.add(new Hat(nextStep));
                if (b[2] == 1) {
                    for (double f : chords[chordI % chords.length]) hits.add(new Keys(f, nextStep, step));
                    chordI++;
                }
                nextStep += (long) (step * RATE);
                stepI++;
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Voice v = hits.get(i);
                v.render(l, r, n, t0);
                if (v.done(t0 + n)) hits.remove(i);
            }
        }
    }

    private static final class Kick extends Voice {
        private final long start;
        private double ph;

        Kick(long start) {
            this.start = start;
        }

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            for (int i = 0; i < n; i++) {
                long t = t0 + i - start;
                if (t < 0) continue;
                double sec = t / (double) RATE;
                if (sec > 0.15) break;
                double f = 80 * Math.pow(28 / 80.0, Math.min(1, sec / 0.07));
                double env = 0.45 * Math.pow(0.001 / 0.45, Math.min(1, sec / 0.11));
                ph += 2 * Math.PI * f / RATE;
                float y = (float) (Math.sin(ph) * env);
                l[i] += y;
                r[i] += y;
            }
        }

        @Override
        boolean done(long t) {
            return t > start + (long) (0.16 * RATE);
        }
    }

    private final class Hat extends Voice {
        private final long start;
        private final Biquad hp = Biquad.highpass(7000);

        Hat(long start) {
            this.start = start;
        }

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            for (int i = 0; i < n; i++) {
                long t = t0 + i - start;
                if (t < 0) continue;
                double sec = t / (double) RATE;
                if (sec > 0.04) break;
                double env = 0.18 * Math.pow(0.001 / 0.18, Math.min(1, sec / 0.035));
                float y = (float) (hp.step(rnd.nextDouble() * 2 - 1) * env);
                l[i] += y;
                r[i] += y;
            }
        }

        @Override
        boolean done(long t) {
            return t > start + (long) (0.05 * RATE);
        }
    }

    /** One chord note: triangle with tape warble, 80 ms attack, released after six steps. */
    private final class Keys extends Voice {
        private final double f, warpF, step;
        private final long start;
        private double ph, wph;

        Keys(double f, long start, double step) {
            this.f = f;
            this.start = start;
            this.step = step;
            warpF = 0.2 + rnd.nextDouble() * 0.2;
        }

        @Override
        void render(float[] l, float[] r, int n, long t0) {
            double end = step * 7 + 0.5, rel = step * 6;
            for (int i = 0; i < n; i++) {
                long t = t0 + i - start;
                if (t < 0) continue;
                double sec = t / (double) RATE;
                if (sec > end) break;
                double env = sec < 0.08 ? 0.07 * sec / 0.08 : 0.07;
                if (sec > rel) env *= Math.exp(-(sec - rel) / 0.3);
                wph += 2 * Math.PI * warpF / RATE;
                ph += (f + Math.sin(wph) * 1.5) / RATE;
                double tri = 4 * Math.abs(ph - Math.floor(ph + 0.5)) - 1;
                float y = (float) (tri * env);
                l[i] += y;
                r[i] += y;
            }
        }

        @Override
        boolean done(long t) {
            return t > start + (long) ((step * 7 + 0.55) * RATE);
        }
    }
}
