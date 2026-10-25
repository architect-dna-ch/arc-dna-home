package ch.architectdna.home;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Notification listener entry. Android only shares the active media sessions (title, cover,
 * play/pause of Spotify & co.) with apps that hold notification access; this is that entry.
 * It doesn't read or keep any notifications. While connected it also feeds the on-device
 * listening stats (MusicStats) from the media sessions.
 */
public class MediaListener extends NotificationListenerService {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private MediaSessionManager sessions;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            report();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            report();
        }
    };

    /** While something plays, book the time every minute. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            report();
            main.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        try {
            ComponentName me = new ComponentName(this, MediaListener.class);
            sessions.addOnActiveSessionsChangedListener(changed, me, main);
            watch(sessions.getActiveSessions(me));
        } catch (SecurityException ignored) {
        }
        main.removeCallbacks(tick);
        main.postDelayed(tick, 60_000);
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        main.removeCallbacks(tick);
        report();
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(callback, main);
                watched.add(c);
            }
        }
        report();
    }

    private void report() {
        MediaController playing = null;
        for (MediaController c : watched) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                playing = c;
                break;
            }
        }
        String title = null, artist = null;
        if (playing != null && playing.getMetadata() != null) {
            MediaMetadata m = playing.getMetadata();
            title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        }
        MusicStats.update(this, playing != null, title, artist, playing != null ? playing.getPackageName() : null);
        if (playing != null && title != null) watchFavGesture(playing, title, artist);
    }

    /* ---- ★ from the earbuds: "next" and straight back to the same song within ~4 s ---- */

    private String trackNow = "", trackBefore = "";
    private long changedAt, changedBeforeAt;
    private FoxVoice voice;

    private void watchFavGesture(MediaController c, String title, String artist) {
        String key = title + "\u0001" + (artist == null ? "" : artist);
        if (key.equals(trackNow)) return;
        long now = System.currentTimeMillis();
        // A → B → A, with B lasting under 4 s: that's the double tap "next, previous" on the earbuds.
        if (key.equals(trackBefore) && now - changedAt < 4000 && now - changedBeforeAt < 9000) {
            favourite(c, title, artist);
            trackBefore = "";
        } else {
            trackBefore = trackNow;
        }
        trackNow = key;
        changedBeforeAt = changedAt;
        changedAt = now;
    }

    private void favourite(MediaController c, String title, String artist) {
        boolean liked = MusicStats.favourite(this, title, artist);
        // Press the app's own Like if it offers one (YouTube Music's thumb, Spotify's heart …).
        PlaybackState st = c.getPlaybackState();
        if (st != null && liked) {
            for (PlaybackState.CustomAction a : st.getCustomActions()) {
                String n = (a.getAction() + " " + a.getName()).toLowerCase(java.util.Locale.ROOT);
                if (n.contains("like") && !n.contains("dislike") || n.contains("thumb_up") || n.contains("thumbs up")
                    || n.contains("heart") || n.contains("favorit") || n.contains("mag ich")) {
                    try {
                        c.getTransportControls().sendCustomAction(a, null);
                    } catch (RuntimeException ignored) {
                    }
                    break;
                }
            }
        }
        // A little yip in the earbuds, so you know it worked without looking.
        if (voice == null) voice = new FoxVoice();
        voice.say(liked ? "Yip-yip!" : "Huff.");
        DiagLog.log("earbud gesture: favourite " + (liked ? "on" : "off"));
    }
}
