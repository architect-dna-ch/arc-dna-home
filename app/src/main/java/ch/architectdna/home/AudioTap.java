package ch.architectdna.home;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one output-mix visualizer the whole app shares. Android gives all visualizers on the mix
 * a single engine: when the music card, the wallpaper and the background phase reader each had
 * their own, one of them disabling or reconnecting silenced the others, and they kept knocking
 * each other out ("visualizer reconnect (no signal)" every few seconds). Now each just listens
 * here; reconnects are shared and spaced at least 3 s apart.
 */
final class AudioTap {

    interface Sink {
        void onFft(byte[] fft);
    }

    private static final CopyOnWriteArrayList<Sink> sinks = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Visualizer viz;
    private static long lastReconnect;
    static volatile long lastData;

    private AudioTap() {
    }

    /** Start listening (the caller checked the microphone permission). Main thread. */
    static void add(Sink s) {
        if (!sinks.contains(s)) sinks.add(s);
        if (viz == null) open();
    }

    static void remove(Sink s) {
        sinks.remove(s);
        if (sinks.isEmpty()) close();
    }

    static boolean listening(Sink s) {
        return sinks.contains(s);
    }

    /**
     * Ask for a fresh connection (no signal while music plays, or playback resumed). Only one
     * reconnect every 3 s for everyone; returns whether this call did it.
     */
    static boolean reconnect() {
        long t = SystemClock.uptimeMillis();
        if (sinks.isEmpty() || t - lastReconnect < 3000) return false;
        if (t - lastData < 1000) return false;           // someone else already got it back
        lastReconnect = t;
        close();
        open();
        return true;
    }

    private static void open() {
        try {
            Visualizer v = new Visualizer(0);       // 0 = the phone's whole output mix
            v.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.max(range[0], Math.min(512, range[1])));
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer x, byte[] w, int r) {
                }

                @Override
                public void onFftDataCapture(Visualizer x, byte[] fft, int r) {
                    for (int i = 2; i < fft.length; i++) {
                        if (fft[i] != 0) {
                            lastData = SystemClock.uptimeMillis();
                            break;
                        }
                    }
                    for (Sink s : sinks) s.onFft(fft);
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20000), false, true);
            v.setEnabled(true);
            viz = v;
        } catch (RuntimeException e) {
            viz = null;
            DiagLog.log("audio tap refused (" + e.getMessage() + ")");
        }
    }

    private static void close() {
        if (viz == null) return;
        try {
            viz.setEnabled(false);
            viz.release();
        } catch (RuntimeException ignored) {
        }
        viz = null;
    }
}
