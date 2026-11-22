package ch.architectdna.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * ARC Flow / ARC Sync — the music follows the device in your hand.
 *
 * Two modes (pref "flowMode"):
 *  - "handoff": pick up a device and the track jumps here; the other device pauses.
 *  - "sync": both devices play the same track at the same second. The one in your hand plays out loud,
 *    the other is ducked to a whisper but stays aligned — so walking to it is seamless, with no echo.
 *
 * Leadership (who is loud / who drives the position) goes to whichever device was picked up most recently.
 * Each device compares its own last pickup against the last time it heard the peer assert itself; the
 * newer wins. Clocks are never compared across devices, only each device's own event order, so it needs
 * no shared clock. Runs as a foreground service so the sensor and the Bluetooth link survive screen-off.
 */
public class FlowService extends Service {

    static final String PREF = "flowAuto";
    static final String MODE = "flowMode";          // "handoff" | "sync"
    private static final String CHANNEL = "arc_flow";
    private static final int NOTIF = 0x5F10;
    private static final long TICK = 20_000;   // each nudge opens a short link; the tablet keeps one at a time, so keep it rare

    // Cross-component leadership timestamps (elapsedRealtime). Updated from the UI, the sensor, and the
    // Bluetooth receiver (BtRemote), so they are static.
    private static volatile long sLocalPickupAt;
    private static volatile long sPeerAssertAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PickupDetector det;
    private volatile boolean busy;

    /** The peer asserted itself (sent us a SYNC / SYNCPOS / DUCK). Called from BtRemote's receiver. */
    static void notePeerAssert() { sPeerAssertAt = SystemClock.elapsedRealtime(); }

    private static void noteLocalPickup() { sLocalPickupAt = SystemClock.elapsedRealtime(); }

    /** This device is the leader (loud, position source) if it was picked up at least as recently as the
     *  last time the peer claimed leadership. */
    private static boolean leaderHere() { return sLocalPickupAt > 0 && sLocalPickupAt >= sPeerAssertAt; }

    /* ---------------- on / off, mode ---------------- */

    public static boolean isOn(Context c) { return c.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean(PREF, false); }
    public static boolean isSync(Context c) { return "sync".equals(c.getSharedPreferences("home", Context.MODE_PRIVATE).getString(MODE, "handoff")); }

    public static void setMode(Context c, boolean sync) {
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString(MODE, sync ? "sync" : "handoff").apply();
    }

    public static void setOn(Context c, boolean on) {
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply();
        Intent i = new Intent(c, FlowService.class);
        try {
            if (on) { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); }
            else c.stopService(i);
        } catch (RuntimeException ignored) { }
    }

    /* ---------------- UI entry points (ARC Remote player / Flow screen) ---------------- */

    /** One-shot: pull the paired device's music onto THIS device now (handoff — pauses the other). */
    public static void pullHere(final Context c) {
        noteLocalPickup();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String[] t = null;
            try { t = BtRemote.pullFromPeer(app, true); } catch (RuntimeException ignored) { }
            final String[] track = t;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (track != null) LocalMedia.playHere(app, track[0], track[1], parse(track[2]));
            });
        }, "arc-flow-pullhere").start();
    }

    private static void runService(Context c, boolean on) {
        Intent i = new Intent(c, FlowService.class);
        try {
            if (on) { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); }
            else c.stopService(i);
        } catch (RuntimeException ignored) { }
    }

    /** Stop ARC Sync: back to handoff mode, and un-duck BOTH devices so neither is left whispering.
     *  The service keeps running only if Auto-Flow is on. */
    public static void stopSync(final Context c) {
        setMode(c, false);
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            LocalMedia.duck(app, false);
            BtRemote.sendToPeer(app, "DUCK\t0", "SYNCOFF\t");
        }, "arc-flow-stopsync").start();
        if (!isOn(c)) runService(c, false);
    }

    /** Start ARC Sync when the music is playing on the OTHER device (e.g. from the ARC Remote player):
     *  take its track without pausing it, play it here at the same second (this device is in your hand,
     *  so it's loud), and ask the other device to join quietly. */
    public static void startSyncFromPeer(final Context c) {
        setMode(c, true);
        runService(c, true);
        noteLocalPickup();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String[] t = null;
            try { t = BtRemote.pullFromPeer(app, false); } catch (RuntimeException ignored) { }
            if (t == null) return;
            final String[] track = t;
            new Handler(Looper.getMainLooper()).post(() -> LocalMedia.playHere(app, track[0], track[1], parse(track[2])));
            LocalMedia.duck(app, false);
            BtRemote.sendToPeer(app, "SYNCJOIN\t", "DUCK\t1");
        }, "arc-flow-syncfrompeer").start();
    }

    /** The peer started ARC Sync and sent us its track: join in sync mode too, so picking THIS device up
     *  later takes the lead back. (Starting a foreground service from the background can be refused on
     *  newer Android; then this device still plays along ducked, it just won't take the lead on pickup.) */
    static void followSync(Context c) {
        if (isSync(c)) return;
        setMode(c, true);
        runService(c, true);
    }

    /** The peer switched ARC Sync off: leave sync mode here too (no message back, so no ping-pong). */
    static void stopSyncLocal(Context c) {
        setMode(c, false);
        LocalMedia.duck(c, false);
        if (!isOn(c)) runService(c, false);
    }

    /** Start ARC Sync from the device you're listening on: sync mode on, service running, and tell the
     *  peer to play the same track at the same second, ducked, while this device stays loud. */
    public static void startSync(final Context c) {
        setMode(c, true);
        runService(c, true);
        noteLocalPickup();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            LocalMedia.duck(app, false);
            String[] n = LocalMedia.nowPlaying(app);
            if (n != null && "1".equals(n[3]))
                BtRemote.sendToPeer(app, "SYNC\t" + n[0] + "\t" + n[1] + "\t" + n[2], "DUCK\t1");
        }, "arc-flow-startsync").start();
    }

    /* ---------------- service ---------------- */

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (det == null) { det = new PickupDetector(this, this::onPickup); det.start(); }
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK);
        return START_STICKY;
    }

    /** Keep the ducked follower on the same second while this device is the leader. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (isSync(FlowService.this) && leaderHere() && LocalMedia.isPlayingHere(FlowService.this)) {
                String[] n = LocalMedia.nowPlaying(FlowService.this);
                final long pos = n == null ? 0 : parse(n[2]);
                LocalMedia.duck(FlowService.this, false);
                new Thread(() -> BtRemote.sendToPeer(FlowService.this, "SYNCPOS\t" + pos, "DUCK\t1"), "arc-flow-tick").start();
            }
            main.postDelayed(this, TICK);
        }
    };

    /** A pickup on this device: bring the music here (or, in sync mode, take the lead + quiet the peer). */
    private void onPickup() {
        if (busy) return;
        noteLocalPickup();
        final boolean sync = isSync(this);
        if (!sync && LocalMedia.isPlayingHere(this)) return;   // handoff: already here, nothing to do
        busy = true;
        LocalMedia.duck(this, false);                          // this device is now in hand → loud
        new Thread(() -> {
            try {
                if (!LocalMedia.isPlayingHere(this)) {
                    String[] t = BtRemote.pullFromPeer(this, !sync);   // sync keeps the peer playing; handoff pauses it
                    if (t != null) {
                        final String[] track = t;
                        main.post(() -> LocalMedia.playHere(this, track[0], track[1], parse(track[2])));
                    }
                }
                if (sync) {
                    String[] n = LocalMedia.nowPlaying(this);
                    long pos = n == null ? 0 : parse(n[2]);
                    BtRemote.sendToPeer(this, "SYNCPOS\t" + pos, "DUCK\t1");   // take the lead, quiet the other room
                }
            } catch (RuntimeException ignored) { }
            main.post(() -> {
                toast(sync ? L.t("Musik folgt dir – auf dem anderen Gerät leiser.") : L.t("Musik folgt dir."));
                busy = false;
            });
        }, "arc-flow-pull").start();
    }

    private void toast(String s) { try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (RuntimeException ignored) { } }
    private static long parse(String s) { try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; } }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, L.t("ARC Flow"), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, FlowActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(L.t("ARC Flow aktiv"))
            .setContentText(L.t("Die Musik folgt dem Gerät in deiner Hand."))
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF, n);
        } catch (RuntimeException e) {
            try { startForeground(NOTIF, n); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void onDestroy() {
        main.removeCallbacks(tick);
        if (det != null) { det.stop(); det = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
