package ch.architectdna.home;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * "Play this track N times" — for ARC One's Music One button (another app, so it asks us: this app
 * holds the notification access that lets it see and steer the music session).
 *
 * The first track that starts playing after the request becomes the anchor. If it loops on its own
 * (repeat-one), every wrap back to the start counts; if the player moves on to another track at the
 * end, we skip back to the anchor until it has played N times.
 *
 * Mode "each" (Music One's default): after N plays, on to the next track — which then also plays N
 * times, and so on, through the queue or radio. If you pick another song yourself (it changes before
 * the anchor has ended), that one becomes the new anchor. Stops when you pause for 3 minutes, after
 * 3 hours, or if nothing starts within 20 s. Without "each": after the N-th play, it pauses.
 */
final class RepeatWatch {

    /** Explicit broadcast target: ch.architectdna.home/.RepeatWatch$Receiver, extras "times" (1–5), "each". */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int n = Math.max(1, Math.min(5, i.getIntExtra("times", 3)));
            start(c, n, i.getBooleanExtra("each", false));
        }
    }

    private static Handler h;
    private static Runnable loop;

    private RepeatWatch() { }

    static synchronized void start(Context ctx, final int times) { start(ctx, times, false); }

    static synchronized void start(Context ctx, final int times, final boolean each) {
        final Context c = ctx.getApplicationContext();
        stop();
        h = new Handler(Looper.getMainLooper());
        final long t0 = SystemClock.elapsedRealtime();
        final String[] anchor = {null};
        final int[] plays = {0};
        final long[] lastPos = {0}, actedAt = {0}, anchorDur = {0}, quietSince = {0}, userSkipAt = {0};
        loop = new Runnable() {
            @Override public void run() {
                long now = SystemClock.elapsedRealtime();
                if (now - t0 > (each ? 3 * 3600_000L : 30 * 60_000L)) { stop(); return; }
                MediaController m = pick(c);
                if (m != null) {
                    MediaMetadata md = m.getMetadata();
                    PlaybackState s = m.getPlaybackState();
                    String title = md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
                    long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
                    long pos = s == null ? 0 : s.getPosition();
                    // you paused it yourself for a while: done
                    if (!playing && anchor[0] != null) {
                        if (quietSince[0] == 0) quietSince[0] = now;
                        else if (now - quietSince[0] > 3 * 60_000L) { stop(); return; }
                    } else quietSince[0] = 0;

                    if (anchor[0] == null) {
                        if (playing && !title.isEmpty() && now - t0 > 1500 && now - actedAt[0] > 1500) {
                            anchor[0] = title; plays[0] = 1; lastPos[0] = pos; anchorDur[0] = dur;
                        }
                    } else if (title.equals(anchor[0])) {
                        boolean nearEnd = anchorDur[0] > 0 ? lastPos[0] > anchorDur[0] - 6_000 : lastPos[0] > 20_000;
                        if (nearEnd && pos < 5_000) {                         // looped back to the start by itself
                            plays[0]++;
                            if (plays[0] > times) {
                                if (each) { skipNext(m); anchor[0] = null; actedAt[0] = now; lastPos[0] = 0; }
                                else { pause(m); stop(); return; }
                            }
                        }
                        if (anchor[0] != null) { lastPos[0] = pos; if (dur > 0) anchorDur[0] = dur; }
                    } else if (!title.isEmpty() && now - actedAt[0] > 4000) { // the track changed
                        // only an automatic move at the very end counts; unknown length or any earlier change = you skipped
                        boolean reachedEnd = anchorDur[0] > 0 && lastPos[0] > anchorDur[0] - 6_000;
                        if (!reachedEnd) {                                   // you chose another song: follow it
                            if (now - userSkipAt[0] < 60_000) { stop(); return; }   // skipping around: you're in charge
                            userSkipAt[0] = now;
                            anchor[0] = title; plays[0] = 1; lastPos[0] = pos; anchorDur[0] = dur;
                        } else if (plays[0] < times) {                       // the player moved on too early
                            actedAt[0] = now;
                            plays[0]++;
                            lastPos[0] = 0;
                            try { m.getTransportControls().skipToPrevious(); } catch (RuntimeException ignored) { }
                        } else if (each) {                                   // N plays done: this one is next
                            anchor[0] = title; plays[0] = 1; lastPos[0] = pos; anchorDur[0] = dur;
                        } else { if (!each) pause(m); stop(); return; }
                    }
                } else if (anchor[0] == null && now - t0 > 20_000) { stop(); return; }
                if (h != null) h.postDelayed(this, 1000);
            }
        };
        h.postDelayed(loop, 1000);
    }

    private static void skipNext(MediaController m) {
        try { m.getTransportControls().skipToNext(); } catch (RuntimeException ignored) { }
    }

    static synchronized void stop() {
        if (h != null && loop != null) h.removeCallbacks(loop);
        h = null;
        loop = null;
    }

    private static void pause(MediaController m) {
        try { m.getTransportControls().pause(); } catch (RuntimeException ignored) { }
    }

    /** The music session to watch: YouTube Music first, else whatever is playing. */
    private static MediaController pick(Context c) {
        MediaSessionManager sm = c.getSystemService(MediaSessionManager.class);
        if (sm == null) return null;
        MediaController any = null;
        try {
            for (MediaController m : sm.getActiveSessions(new ComponentName(c, MediaListener.class))) {
                if (BtRemote.YT_MUSIC.equals(m.getPackageName())) return m;
                PlaybackState s = m.getPlaybackState();
                if (any == null || s != null && s.getState() == PlaybackState.STATE_PLAYING) any = m;
            }
        } catch (SecurityException ignored) { }
        return any;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
