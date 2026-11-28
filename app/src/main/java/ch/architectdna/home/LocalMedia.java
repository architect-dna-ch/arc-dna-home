package ch.architectdna.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * The music session on THIS device: read what's playing, start a track here, or pause here.
 *
 * Both the phone and the tablet run ARC with notification access, so each can drive its own media
 * app (YouTube Music first). ARC Flow uses this on both ends: the device you pick up reads the other
 * device's track over the Bluetooth link, then plays it here and seeks to roughly the same spot.
 *
 * Honest limit: "roughly". We can read the position on the source and seek here after the track loads,
 * but a music app resolves a search in its own time and not every app honours a seek — so the handoff
 * lands within a few seconds of where you were, not frame-perfect.
 */
final class LocalMedia {

    private LocalMedia() { }

    private static ComponentName me(Context c) { return new ComponentName(c, MediaListener.class); }

    /** {title, artist, positionMs, playing(1/0), videoId or ""} for the best local session, or null if none / no access. */
    static String[] nowPlaying(Context c) {
        MediaController ctl = pick(c);
        if (ctl == null) return null;
        MediaMetadata md = ctl.getMetadata();
        PlaybackState s = ctl.getPlaybackState();
        String title = md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
        long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        long pos = 0;
        if (s != null) {
            pos = s.getPosition();
            float speed = s.getPlaybackSpeed() == 0 ? 1f : s.getPlaybackSpeed();
            if (playing && s.getLastPositionUpdateTime() > 0)
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * speed);
            if (dur > 0) pos = Math.min(pos, dur);
        }
        if (title.isEmpty()) return null;
        return new String[]{title, artist, Long.toString(Math.max(0, pos)), playing ? "1" : "0", videoId(md)};
    }

    static boolean isPlayingHere(Context c) {
        String[] n = nowPlaying(c);
        return n != null && "1".equals(n[3]);
    }

    /** Pause whatever is playing on this device. */
    static void pauseHere(Context c) {
        MediaController ctl = pick(c);
        if (ctl == null) return;
        try { ctl.getTransportControls().pause(); } catch (RuntimeException ignored) { }
    }

    /** ARC Sync: nudge local playback to {@code targetPos} only if it has drifted more than ~1.2 s.
     *  Keeps the quiet (follower) device on the same second as the one in your hand, without constant seeks. */
    static void alignTo(Context c, long targetPos) {
        MediaController ctl = pick(c);
        if (ctl == null) return;
        PlaybackState s = ctl.getPlaybackState();
        if (s == null || s.getState() != PlaybackState.STATE_PLAYING) return;
        if ((s.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) return;
        long cur = s.getPosition();
        float speed = s.getPlaybackSpeed() == 0 ? 1f : s.getPlaybackSpeed();
        if (s.getLastPositionUpdateTime() > 0)
            cur += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * speed);
        if (Math.abs(cur - targetPos) > 1200)
            try { ctl.getTransportControls().seekTo(targetPos); } catch (RuntimeException ignored) { }
    }

    /** ARC Sync auto-duck: lower this device's music to a background level, or restore it. We remember the
     *  loud level the first time we duck so we can bring it back when this device comes into your hand. */
    static void duck(Context c, boolean on) {
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        int stream = android.media.AudioManager.STREAM_MUSIC;
        int max = am.getStreamMaxVolume(stream);
        int quiet = Math.max(1, Math.round(max * 0.12f));
        android.content.SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        try {
            if (on) {
                int cur = am.getStreamVolume(stream);
                if (cur > quiet) p.edit().putInt("preDuckVol", cur).apply();   // remember the loud level
                if (cur > quiet) am.setStreamVolume(stream, quiet, 0);
            } else {
                int restore = p.getInt("preDuckVol", Math.max(quiet, Math.round(max * 0.6f)));
                am.setStreamVolume(stream, Math.min(max, restore), 0);
            }
        } catch (RuntimeException ignored) { }
    }

    /** Play a track here by search, then ease toward {@code posMs} once it has loaded (best effort).
     *  Uses the music app's session only if it advertises play-from-search (otherwise Android drops the
     *  request silently), checks the title actually changed, and falls back to the play-from-search
     *  intent. The seek adds the time the track took to load, so it lands closer to "the same second". */
    /** The exact YouTube video id of the track, when the app exposes it (YouTube Music does, as its media id).
     *  With it the other device plays THIS song, not whatever its search ranks first for the same words. */
    static String videoId(MediaMetadata md) {
        if (md == null) return "";
        String[] cands = {
            md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            md.getDescription() == null ? null : md.getDescription().getMediaId(),
            md.getDescription() == null || md.getDescription().getMediaUri() == null ? null : md.getDescription().getMediaUri().toString(),
        };
        for (String s : cands) {
            if (s == null) continue;
            s = s.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})").matcher(s);
            if (m.find()) return m.group(1);
            if (s.matches("[A-Za-z0-9_-]{11}")) return s;
        }
        return "";
    }

    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{11}"); }

    /** Open exactly this video in YouTube Music. */
    static boolean playId(Context c, String id) {
        if (!validId(id)) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/watch?v=" + id))
            .setPackage(BtRemote.YT_MUSIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); return true; } catch (RuntimeException e) { return false; }
    }

    static void playHere(final Context c, final String title, String artist, final long posMs) {
        playHere(c, title, artist, posMs, "");
    }

    /** Play the track here. With a video id: exactly that track (then seek). Without one, or if the id route
     *  didn't land on the right title: the old search. */
    static void playHere(final Context c, final String title, final String artist, final long posMs, String id) {
        if (validId(id)) {
            final long t0 = SystemClock.elapsedRealtime();
            if (playId(c, id)) {
                final Handler h = new Handler(Looper.getMainLooper());
                h.postDelayed(new Runnable() {
                    int tries = 0;
                    @Override public void run() {
                        if (sameTitle(currentTitle(c), title)) { if (posMs > 4000) seekSoon(c, posMs, t0); return; }
                        if (++tries < 6) { h.postDelayed(this, 1000); return; }
                        playSearch(c, title, artist, posMs);           // id didn't take: fall back
                    }
                }, 1500);
                return;
            }
        }
        playSearch(c, title, artist, posMs);
    }

    static boolean sameTitle(String now, String want) {
        if (now == null || want == null || now.isEmpty() || want.isEmpty()) return false;
        String a = now.toLowerCase(java.util.Locale.ROOT).trim(), b = want.toLowerCase(java.util.Locale.ROOT).trim();
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static void playSearch(final Context c, final String title, String artist, final long posMs) {
        final String q = (title + " " + artist).trim();
        if (q.isEmpty()) return;
        final long t0 = SystemClock.elapsedRealtime();
        final String before = currentTitle(c);
        MediaController ctl = pickFor(c, BtRemote.YT_MUSIC);
        boolean viaSession = false;
        if (ctl != null && ctl.getPlaybackState() != null
                && (ctl.getPlaybackState().getActions() & PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0) {
            try { ctl.getTransportControls().playFromSearch(q, new Bundle()); viaSession = true; }
            catch (RuntimeException ignored) { }
        }
        if (!viaSession && !searchIntent(c, q)) return;
        final boolean triedSession = viaSession;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (landed(c, before, q)) { if (posMs > 4000) seekSoon(c, posMs, t0); return; }
            if (triedSession && searchIntent(c, q) && posMs > 4000) seekSoon(c, posMs, t0);
        }, 3500);
    }

    private static boolean searchIntent(Context c, String q) {
        Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, q)
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .setPackage(BtRemote.YT_MUSIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); return true; } catch (RuntimeException e) { return false; }
    }

    private static String currentTitle(Context c) {
        MediaController m = pick(c);
        MediaMetadata md = m == null ? null : m.getMetadata();
        return md == null ? "" : nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
    }

    private static boolean landed(Context c, String before, String q) {
        String now = currentTitle(c);
        return !now.isEmpty() && (!now.equals(before)
            || q.toLowerCase(java.util.Locale.ROOT).contains(now.toLowerCase(java.util.Locale.ROOT)));
    }

    /** The track loads asynchronously; seek a couple of times as it comes up, to where the source is NOW
     *  (its position when we asked + the time since). */
    private static void seekSoon(final Context c, final long posMs, final long t0) {
        final Handler h = new Handler(Looper.getMainLooper());
        for (long delay : new long[]{300, 2000}) {
            h.postDelayed(() -> {
                MediaController ctl = pick(c);
                if (ctl == null) return;
                PlaybackState s = ctl.getPlaybackState();
                boolean actionable = s != null && (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
                long target = posMs + (SystemClock.elapsedRealtime() - t0);
                if (actionable) try { ctl.getTransportControls().seekTo(target); } catch (RuntimeException ignored) { }
            }, delay);
        }
    }

    /** Best local session: a playing one wins, else YouTube Music, else the first. */
    private static MediaController pick(Context c) {
        MediaSessionManager sm = c.getSystemService(MediaSessionManager.class);
        if (sm == null) return null;
        MediaController best = null;
        try {
            List<MediaController> list = sm.getActiveSessions(me(c));
            for (MediaController m : list) {
                if (best == null) { best = m; continue; }
                boolean mp = playing(m), bp = playing(best);
                if (mp && !bp) best = m;
                else if (mp == bp && BtRemote.YT_MUSIC.equals(m.getPackageName()) && !BtRemote.YT_MUSIC.equals(best.getPackageName())) best = m;
            }
        } catch (SecurityException ignored) { }
        return best;
    }

    /** Prefer a controller for {@code pkg} (so a handoff plays in YouTube Music), else the best one. */
    private static MediaController pickFor(Context c, String pkg) {
        MediaSessionManager sm = c.getSystemService(MediaSessionManager.class);
        if (sm != null) try {
            for (MediaController m : sm.getActiveSessions(me(c)))
                if (pkg.equals(m.getPackageName())) return m;
        } catch (SecurityException ignored) { }
        return pick(c);
    }

    private static boolean playing(MediaController m) {
        PlaybackState s = m.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
