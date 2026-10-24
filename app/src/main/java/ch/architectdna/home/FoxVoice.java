package ch.architectdna.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Synthesizes the fox calls, the same recipe as fx/brand-fx.js on the website:
 * each syllable is a sawtooth sweeping from one pitch to another through a band-pass filter,
 * with a quick attack and an exponential fade. Rendered to PCM and played, no audio files.
 */
final class FoxVoice {

    private static final int RATE = 44100;

    /** Syllable → {start Hz, end Hz, seconds}. Rough imitations of real fox calls. */
    private static final Map<String, float[]> VOICE = new HashMap<>();

    static {
        VOICE.put("yip", new float[]{900, 1600, .07f});
        VOICE.put("wow", new float[]{700, 1150, .16f});
        VOICE.put("whoa", new float[]{650, 1050, .22f});
        VOICE.put("gekk", new float[]{620, 480, .05f});
        VOICE.put("ack", new float[]{520, 440, .06f});
        VOICE.put("ha", new float[]{800, 700, .05f});
        VOICE.put("eeek", new float[]{1900, 1300, .3f});
        VOICE.put("mrrp", new float[]{400, 700, .12f});
        VOICE.put("wa", new float[]{600, 900, .06f});
        VOICE.put("pa", new float[]{650, 850, .05f});
        VOICE.put("huff", new float[]{300, 200, .1f});
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** Plays e.g. "Gekk-gekk-gekk!" or "Wa-pa-pa-pa!" on a background thread. */
    void say(String call) {
        play(() -> render(call));
    }

    /** An F1 car passing: engine note plus air rush, rising then falling (a cheap Doppler). */
    void whoosh() {
        play(FoxVoice::renderWhoosh);
    }

    /** The fox twirling: a rising "wheee" with a fast warble as it spins, then a happy chirp. */
    void twirl() {
        play(FoxVoice::renderTwirl);
    }

    static short[] renderTwirl() {
        int n = (int) (0.62f * RATE);
        short[] out = new short[n];
        double phase = 0, x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double sec = (double) i / RATE, t = (double) i / n;
            double f;
            double env;
            if (t < 0.72) {
                // Wheee: 450 → 1900 Hz, warbling 16× a second like the spin.
                double k = t / 0.72;
                f = 450 * Math.pow(1900 / 450.0, k) * (1 + 0.06 * Math.sin(2 * Math.PI * 16 * sec));
                env = Math.min(1, sec / 0.02) * (0.55 + 0.45 * Math.abs(Math.sin(2 * Math.PI * 8 * sec)));
            } else {
                // "Yip" at the top: 1500 → 2300 Hz, short.
                double k = (t - 0.72) / 0.28;
                f = 1500 * Math.pow(2300 / 1500.0, Math.min(1, k * 2));
                env = Math.exp(-k * 5) * (k < 0.04 ? k / 0.04 : 1);
            }
            phase += f / RATE;
            double saw = 2 * (phase - Math.floor(phase + 0.5));
            // Band-pass that follows the pitch, so it stays voice-like instead of buzzy.
            double w0 = 2 * Math.PI * Math.min(5000, f * 1.3) / RATE, alpha = Math.sin(w0) / (2 * 1.4), a0 = 1 + alpha;
            double x = saw * env;
            double y = (alpha / a0) * x + (-alpha / a0) * x2 - (-2 * Math.cos(w0) / a0) * y1 - ((1 - alpha) / a0) * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            out[i] = (short) Math.max(-32767, Math.min(32767, y * 2.6 * 32767 * 0.35));
        }
        return out;
    }

    /** Volume down: a sad falling whimper, "awoo-hmm", slow and wobbly, fading out. */
    void whimper() {
        play(FoxVoice::renderWhimper);
    }

    static short[] renderWhimper() {
        int n = (int) (1.25f * RATE);
        short[] out = new short[n];
        double phase = 0, x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double sec = (double) i / RATE, t = (double) i / n;
            double f, env;
            if (t < 0.62) {
                // Awoo: a short lift, then a long slide down 820 → 380 Hz with a slow, unsteady vibrato.
                double k = t / 0.62;
                double lift = k < 0.12 ? 1 + 0.08 * (k / 0.12) : 1.08 * Math.pow(380 / 885.6, (k - 0.12) / 0.88);
                f = 820 * lift * (1 + 0.025 * Math.sin(2 * Math.PI * 5.5 * sec));
                env = Math.min(1, sec / 0.06) * (1 - 0.35 * k);
            } else {
                // Hmm: a low, quiet sigh 330 → 260 Hz, dying away.
                double k = (t - 0.62) / 0.38;
                f = 330 * Math.pow(260 / 330.0, k) * (1 + 0.03 * Math.sin(2 * Math.PI * 4 * sec));
                env = 0.45 * Math.min(1, k / 0.08) * Math.exp(-k * 3.2);
            }
            phase += f / RATE;
            // Softer than the other calls: mostly sine with a little saw for the voice.
            double saw = 2 * (phase - Math.floor(phase + 0.5));
            double x = (0.75 * Math.sin(2 * Math.PI * phase) + 0.25 * saw) * env;
            double w0 = 2 * Math.PI * Math.min(4000, f * 1.6) / RATE, alpha = Math.sin(w0) / (2 * 0.9), a0 = 1 + alpha;
            double y = (alpha / a0) * x + (-alpha / a0) * x2 - (-2 * Math.cos(w0) / a0) * y1 - ((1 - alpha) / a0) * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            out[i] = (short) Math.max(-32767, Math.min(32767, y * 2.6 * 32767 * 0.35));
        }
        return out;
    }

    private void play(java.util.function.Supplier<short[]> source) {
        worker.execute(() -> {
            short[] pcm = source.get();
            if (pcm.length == 0) return;
            AudioTrack track = null;
            try {
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
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
                track.play();
                Thread.sleep(pcm.length * 1000L / RATE + 60);
            } catch (Exception ignored) {
                // No sound is better than a crash on the home screen.
            } finally {
                if (track != null) track.release();
            }
        });
    }

    static short[] renderWhoosh() {
        int n = (int) (0.9f * RATE);
        short[] out = new short[n];
        java.util.Random rnd = new java.util.Random(7);
        double phase = 0, x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            // Passing point at 45 %: pitch and filter rise towards it, then drop away.
            double pass = t < 0.45 ? t / 0.45 : 1 - (t - 0.45) / 0.55;
            double env = Math.pow(Math.max(0, pass), 1.6) * (t < 0.45 ? 1 : 0.85);
            double engineHz = t < 0.45 ? 260 + 220 * (t / 0.45) : 480 - 300 * ((t - 0.45) / 0.55);
            phase += engineHz / RATE;
            double engine = 2 * (phase - Math.floor(phase + 0.5));
            // Air rush: white noise through a band-pass that sweeps with the pass.
            double fc = 500 + 2100 * Math.max(0, pass);
            double w0 = 2 * Math.PI * fc / RATE, alpha = Math.sin(w0) / (2 * 0.9), a0 = 1 + alpha;
            double xn = rnd.nextDouble() * 2 - 1;
            double yn = (alpha / a0) * xn + (-alpha / a0) * x2 - (-2 * Math.cos(w0) / a0) * y1 - ((1 - alpha) / a0) * y2;
            x2 = x1;
            x1 = xn;
            y2 = y1;
            y1 = yn;
            double v = env * (0.32 * engine + 1.4 * yn);
            out[i] = (short) Math.max(-32767, Math.min(32767, v * 32767 * 0.5));
        }
        return out;
    }

    void close() {
        worker.shutdownNow();
    }

    static short[] render(String call) {
        String[] syllables = call.toLowerCase(Locale.ROOT).replaceAll("[^a-z-]", "").split("-");
        int total = 0;
        for (String s : syllables) total += (int) ((voice(s)[2] + 0.035f) * RATE);
        if (total == 0) return new short[0];
        short[] out = new short[total];

        // Band-pass at 1.4 kHz, Q 1.2 (RBJ cookbook), shared across syllables like the web version.
        double w0 = 2 * Math.PI * 1400 / RATE, alpha = Math.sin(w0) / (2 * 1.2);
        double a0 = 1 + alpha;
        double b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * Math.cos(w0) / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0, phase = 0;

        int pos = 0;
        for (String s : syllables) {
            float[] v = voice(s);
            int n = (int) (v[2] * RATE), gap = (int) (0.035f * RATE);
            for (int i = 0; i < n + gap && pos < total; i++, pos++) {
                double x = 0;
                if (i < n) {
                    double t = (double) i / n;
                    double f = v[0] * Math.pow(v[1] / v[0], t);       // exponential sweep
                    phase += f / RATE;
                    double saw = 2 * (phase - Math.floor(phase + 0.5));
                    double sec = (double) i / RATE;
                    double env = sec < 0.012 ? sec / 0.012 : Math.exp(-4.6 * (sec - 0.012) / (v[2] - 0.012));
                    x = saw * env;
                }
                double y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1;
                x1 = x;
                y2 = y1;
                y1 = y;
                out[pos] = (short) Math.max(-32767, Math.min(32767, y * 2.6 * 32767 * 0.35));
            }
        }
        return out;
    }

    private static float[] voice(String syllable) {
        float[] v = VOICE.get(syllable);
        return v != null ? v : VOICE.get("yip");
    }
}
