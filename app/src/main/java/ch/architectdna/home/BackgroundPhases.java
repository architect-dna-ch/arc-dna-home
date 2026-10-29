package ch.architectdna.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Track phases (and beats for the music mood) while ARC-DNA is in the background: the media
 * listener runs this whenever music plays and the music card isn't on screen. It taps the output
 * mix like the card does, reads GROOVE / BUILD / DROP / BREAK / VOCAL / INSTRUMENTAL the same way
 * (time constants scaled to the ~20 captures a second) and votes into the same per-song phase map.
 */
final class BackgroundPhases {

    /** The music card sets this while its own spectrum runs – then it does the reading itself. */
    static volatile long cardLiveAt;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PhaseBar bar;
    private MediaController controller;
    private String trackKey = "";
    private long lastSignal;
    private boolean loggedSignal;

    // Band averages (fast ≈0.3 s, mid ≈2 s, slow ≈8 s at ~20 frames/s).
    private float loS, midS, hiS, loL, midL, hiL, totM, hiM, prevBass, fluxAvg = 1;
    private final float[] trend = new float[8];
    private int trendHead, warm;
    private long trendAt, phaseSince, candidateSince, lastVocalAt = System.currentTimeMillis(), lastKick;
    private boolean built;
    private PhaseBar.Phase phase = PhaseBar.Phase.GROOVE, candidate = PhaseBar.Phase.GROOVE;

    BackgroundPhases(Context c) {
        ctx = c.getApplicationContext();
        bar = new PhaseBar(ctx, 0, 0, ms -> { });
    }

    /** Called on every playback / track change from the media listener. */
    void follow(MediaController playing) {
        controller = playing;
        boolean want = playing != null && SystemClock.uptimeMillis() - cardLiveAt > 3000
            && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        main.removeCallbacks(recheck);
        if (want) start();
        else stop();
        // The card is on screen: look again shortly so the background takes over once it closes.
        if (!want && playing != null && SystemClock.uptimeMillis() - cardLiveAt <= 3000) main.postDelayed(recheck, 5000);
        if (playing != null) {
            MediaMetadata m = playing.getMetadata();
            String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String key = t == null ? "" : t + "\u0001" + (a == null ? "" : a);
            if (!key.equals(trackKey)) {
                trackKey = key;
                warm = 0;
                lastVocalAt = System.currentTimeMillis();
                bar.newTrack(key);
            }
        }
    }

    private final AudioTap.Sink sink = this::feed;
    private int misses;

    private void start() {
        if (AudioTap.listening(sink)) return;
        AudioTap.add(sink);
        if (!trackKey.isEmpty()) bar.newTrack(trackKey);     // reload: the card may have voted meanwhile
        loggedSignal = false;
        misses = 0;
        lastSignal = SystemClock.uptimeMillis();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 5000);
    }

    void stop() {
        main.removeCallbacks(watchdog);
        bar.save();
        AudioTap.remove(sink);
    }

    /** No sound arriving while music plays: reconnect the shared tap; after a few misses, rest a minute. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!AudioTap.listening(sink)) return;
            if (SystemClock.uptimeMillis() - cardLiveAt < 3000) {      // the card took over
                stop();
                main.postDelayed(recheck, 5000);
                return;
            }
            if (SystemClock.uptimeMillis() - lastSignal > 4000) {
                if (++misses > 4) {
                    DiagLog.log("bg phases: no signal in the background – retry in 60 s");
                    stop();
                    main.postDelayed(recheck, 60_000);
                    return;
                }
                AudioTap.reconnect();
            } else {
                misses = 0;
            }
            main.postDelayed(this, 4000);
        }
    };

    private final Runnable recheck = () -> follow(controller);

    private void feed(byte[] fft) {
        if (SystemClock.uptimeMillis() - cardLiveAt < 1500) return;      // the card reads it itself
        int bins = fft.length / 2;
        float lo = 0, mid = 0, hi = 0, bass = 0;
        int nl = 0, nm = 0, nh = 0;
        for (int k = 1; k < bins; k++) {
            float mag = (float) Math.min(1, Math.log1p(Math.hypot(fft[2 * k], fft[2 * k + 1])) / Math.log1p(90));
            double pos = Math.log(k) / Math.log(bins);            // log scale like the card's bars
            if (pos < 1 / 3.0) {
                lo += mag;
                nl++;
            } else if (pos < 2 / 3.0) {
                mid += mag;
                nm++;
            } else {
                hi += mag;
                nh++;
            }
            if (k < 7) bass += (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
        }
        lo /= Math.max(1, nl);
        mid /= Math.max(1, nm);
        hi /= Math.max(1, nh);
        long now = System.currentTimeMillis();
        if (lo + mid + hi > 0.03f) {
            lastSignal = SystemClock.uptimeMillis();
            if (!loggedSignal) {
                loggedSignal = true;
                DiagLog.log("bg phases: listening in the background");
            }
        } else {
            return;
        }
        // Beats for the music mood (spectral flux of the low end).
        float flux = Math.max(0, bass - prevBass);
        prevBass = bass;
        if (flux > fluxAvg * 1.8f + 4 && now - lastKick > 260) {
            lastKick = now;
            MusicMood.beat();
        }
        fluxAvg = fluxAvg * 0.93f + flux * 0.07f;
        MusicMood.level((lo + mid + hi) / 3f);
        read(lo, mid, hi, now);
        if (controller != null) {
            PlaybackState st = controller.getPlaybackState();
            MediaMetadata m = controller.getMetadata();
            if (st != null && m != null) {
                long pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0)
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION), at = pos;
                PhaseBar.Phase heard = phase;
                main.post(() -> bar.update(at, dur, heard));
            }
        }
    }

    /** The music card's phase reading, with its time constants scaled from ~60 to ~20 frames a second. */
    private void read(float lo, float mid, float hi, long t) {
        float tot = lo + mid + hi;
        loS += (lo - loS) * 0.18f;
        midS += (mid - midS) * 0.18f;
        hiS += (hi - hiS) * 0.18f;
        totM += (tot - totM) * 0.024f;
        hiM += (hi - hiM) * 0.024f;
        float slow = warm < 80 ? 0.15f : 0.006f;
        warm++;
        loL += (lo - loL) * slow;
        midL += (mid - midL) * slow;
        hiL += (hi - hiL) * slow;
        float totS = loS + midS + hiS, totL = loL + midL + hiL;
        if (t - trendAt > 500) {
            trendAt = t;
            trend[trendHead] = totM;
            trendHead = (trendHead + 1) % trend.length;
        }
        float fourAgo = trend[trendHead];
        boolean rising = fourAgo > 0.02f && totM > fourAgo * 1.12f && hiM > hiL * 1.06f;
        boolean bassUp = loS > loL * 1.15f;
        PhaseBar.Phase p;
        if (totL < 0.04f || warm < 80) p = PhaseBar.Phase.GROOVE;
        else if ((totS > totL * 1.18f && bassUp) || (built && totS > totL * 1.08f && bassUp) || totS > totL * 1.4f) p = PhaseBar.Phase.DROP;
        else if (totM < totL * 0.7f) p = PhaseBar.Phase.BREAK;
        else if (rising) p = PhaseBar.Phase.BUILD;
        else if (midS > totS * 0.42f) p = PhaseBar.Phase.VOCAL;
        else p = PhaseBar.Phase.GROOVE;
        if (midS > totS * 0.42f) lastVocalAt = t;
        if (p == PhaseBar.Phase.GROOVE && warm >= 80 && t - lastVocalAt > 20_000) p = PhaseBar.Phase.INSTRUMENTAL;
        if (phase == PhaseBar.Phase.DROP && p != PhaseBar.Phase.DROP && t - phaseSince < 2000) p = PhaseBar.Phase.DROP;
        if (p != candidate) {
            candidate = p;
            candidateSince = t;
        } else if (p != phase && t - candidateSince > 600) {
            phase = p;
            phaseSince = t;
            if (p == PhaseBar.Phase.BUILD) built = true;
            if (p == PhaseBar.Phase.DROP || p == PhaseBar.Phase.BREAK) built = false;
        }
    }
}
