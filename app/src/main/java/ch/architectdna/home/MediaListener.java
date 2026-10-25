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
        MusicStats.update(this, playing != null, title, artist);
    }
}
