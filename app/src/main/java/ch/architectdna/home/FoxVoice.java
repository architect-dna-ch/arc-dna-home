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
        worker.execute(() -> {
            short[] pcm = render(call);
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
