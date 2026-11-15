package ch.architectdna.home;

import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Netflix-style extras for whatever plays: jump ±10 s, playback speed, sleep timer.
 * Used by ARC Player (this device) and by ARC Remote's receiver (the tablet).
 */
final class MediaExtras {
    private MediaExtras() { }

    static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};
    static final int[] SLEEP_MIN = {0, 15, 30, 60};
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static MediaController sleepTarget;
    private static long sleepAt;
    private static final Runnable sleep = () -> {
        if (sleepTarget != null) try { sleepTarget.getTransportControls().pause(); } catch (RuntimeException ignored) { }
        sleepAt = 0;
    };

    static long position(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0)
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * (s.getPlaybackSpeed() == 0 ? 1f : s.getPlaybackSpeed()));
        return pos;
    }

    static void seekBy(MediaController c, long deltaMs) {
        if (c == null) return;
        long to = Math.max(0, position(c) + deltaMs);
        long dur = c.getMetadata() == null ? 0 : c.getMetadata().getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        if (dur > 0) to = Math.min(to, dur - 500);
        try { c.getTransportControls().seekTo(to); } catch (RuntimeException ignored) { }
    }

    /** Next speed in the cycle; returns it (1× if the app or Android can't change speed). */
    static float nextSpeed(MediaController c) {
        if (c == null || Build.VERSION.SDK_INT < 29) return 1f;
        PlaybackState s = c.getPlaybackState();
        float now = s == null || s.getPlaybackSpeed() == 0 ? 1f : s.getPlaybackSpeed();
        int i = 0;
        for (int k = 0; k < SPEEDS.length; k++) if (Math.abs(SPEEDS[k] - now) < 0.01f) i = k;
        float next = SPEEDS[(i + 1) % SPEEDS.length];
        try { c.getTransportControls().setPlaybackSpeed(next); } catch (RuntimeException ignored) { }
        return next;
    }

    static void setSpeed(MediaController c, float speed) {
        if (c == null || Build.VERSION.SDK_INT < 29) return;
        try { c.getTransportControls().setPlaybackSpeed(speed); } catch (RuntimeException ignored) { }
    }

    /** Pause in N minutes (0 = timer off). */
    static void sleepIn(MediaController c, int minutes) {
        main.removeCallbacks(sleep);
        sleepTarget = c;
        sleepAt = minutes > 0 ? SystemClock.elapsedRealtime() + minutes * 60_000L : 0;
        if (minutes > 0) main.postDelayed(sleep, minutes * 60_000L);
    }

    /** Minutes left on the sleep timer, 0 if off. */
    static int sleepLeft() {
        return sleepAt == 0 ? 0 : (int) Math.max(1, (sleepAt - SystemClock.elapsedRealtime() + 59_999) / 60_000);
    }

    static String speedLabel(float s) {
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "×";
    }
}
