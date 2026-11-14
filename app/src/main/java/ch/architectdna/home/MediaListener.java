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

    /** The notice's progress bar moves on every 5 s while something plays. */
    private final Runnable progress = new Runnable() {
        @Override
        public void run() {
            for (MediaController c : watched) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    ArcMediaNotice.show(MediaListener.this, c);      // only the notice, not the stats
                    main.postDelayed(this, 5_000);
                    return;
                }
            }
        }
    };

    /** While something plays, book the time every minute. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            report();
            BtSync.syncSoon(MediaListener.this, 15 * 60_000L, null);
            main.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onListenerConnected() {
        try {
            android.content.IntentFilter vf = new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(volume, vf, RECEIVER_EXPORTED);
            else registerReceiver(volume, vf);
        } catch (RuntimeException ignored) {
        }
        // Battery diary for "Energie": every % change and every screen on / off.
        try {
            android.content.IntentFilter bf = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            bf.addAction(android.content.Intent.ACTION_SCREEN_ON);
            bf.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            registerReceiver(battery, bf);
        } catch (RuntimeException ignored) {
        }
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
        BtSync.startServer(this);
        BtRemote.startServer(this);      // the phone can steer this device's music (ARC Remote)
    }

    private final android.content.BroadcastReceiver battery = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            android.content.Intent b = android.content.Intent.ACTION_BATTERY_CHANGED.equals(i.getAction()) ? i : null;
            android.content.Context app = c.getApplicationContext();
            new Thread(() -> BatteryLog.sample(app, b), "arc-battery-log").start();
            LowPower.stale();
        }
    };

    @Override
    public void onListenerDisconnected() {
        try {
            unregisterReceiver(volume);
        } catch (RuntimeException ignored) {
        }
        try {
            unregisterReceiver(battery);
        } catch (RuntimeException ignored) {
        }
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        main.removeCallbacks(tick);
        main.removeCallbacks(progress);
        if (bgPhases != null) bgPhases.stop();
        ArcMediaNotice.cancel(this);
        BtSync.stopServer();
        BtRemote.stopServer();
        report();
    }

    private BackgroundPhases bgPhases;

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
        MusicMood.onUpdate(this, playing != null, title, artist);
        if (bgPhases == null) bgPhases = new BackgroundPhases(this);
        bgPhases.follow(LowPower.active(this) ? null : playing);     // paused under 15 %                  // phases + beats also while ARC-DNA is in the background
        // The ARC Player notice: the playing session, else a paused one; none = gone.
        MediaController shown = playing;
        if (shown == null) {
            for (MediaController c : watched) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PAUSED && c.getMetadata() != null) {
                    shown = c;
                    break;
                }
            }
        }
        ArcMediaNotice.show(this, shown);
        main.removeCallbacks(progress);
        if (playing != null) main.postDelayed(progress, 5_000);
    }

    /* ---- ★ from the earbud volume: up, down, up, down within ~2 s ---- */

    private final int[] volDir = new int[4];
    private final long[] volAt = new long[4];

    private final android.content.BroadcastReceiver volume = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return;
            int now = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", 0);
            int before = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", now);
            if (now == before) return;
            System.arraycopy(volDir, 1, volDir, 0, 3);
            System.arraycopy(volAt, 1, volAt, 0, 3);
            volDir[3] = now > before ? 1 : -1;
            volAt[3] = System.currentTimeMillis();
            // Up, down, up, down quickly: too deliberate to happen while just setting the volume.
            boolean zigzag = volDir[0] == 1 && volDir[1] == -1 && volDir[2] == 1 && volDir[3] == -1 && volAt[3] - volAt[0] < 2000;
            if (!zigzag) return;
            java.util.Arrays.fill(volDir, 0);
            for (MediaController mc : watched) {
                PlaybackState st = mc.getPlaybackState();
                MediaMetadata m = mc.getMetadata();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING && m != null
                    && m.getString(MediaMetadata.METADATA_KEY_TITLE) != null) {
                    favourite(mc, m.getString(MediaMetadata.METADATA_KEY_TITLE), m.getString(MediaMetadata.METADATA_KEY_ARTIST), "volume");
                    return;
                }
            }
        }
    };

    // (The "next, then straight back" ★ gesture is gone: it liked songs you only skipped back to.)

    private FoxVoice voice;

    private void favourite(MediaController c, String title, String artist, String how) {
        boolean liked = MusicStats.favourite(this, title, artist);
        // Press the app's own Like if it offers one (YouTube Music's thumb, Spotify's heart …).
        PlaybackState st = c.getPlaybackState();
        if (st != null && liked) {
            for (PlaybackState.CustomAction a : st.getCustomActions()) {
                String n = (a.getAction() + " " + a.getName()).toLowerCase(java.util.Locale.ROOT);
                // Never "Undo like" (already liked): that took the like away again.
                if (n.contains("undo") || n.contains("remove") || n.contains("unlike") || n.contains("entfernen")) continue;
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
        DiagLog.log("earbud gesture (" + how + "): favourite " + (liked ? "on" : "off"));
    }
}
