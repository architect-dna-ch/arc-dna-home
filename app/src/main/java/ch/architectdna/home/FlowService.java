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
import android.widget.Toast;

/**
 * ARC Flow — while on, the music follows the device in your hand. A pickup on this device (PickupDetector)
 * asks the paired ARC device what it's playing; if it's playing and nothing is playing here, we pause it
 * there and continue the same track here (best-effort position). Runs as a foreground service so the
 * sensor and the Bluetooth link stay alive when the screen is off.
 */
public class FlowService extends Service {

    static final String PREF = "flowAuto";
    private static final String CHANNEL = "arc_flow";
    private static final int NOTIF = 0x5F10;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PickupDetector det;
    private volatile boolean busy;

    public static boolean isOn(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    /** Turn ARC Flow on/off and start/stop the service. */
    public static void setOn(Context c, boolean on) {
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply();
        Intent i = new Intent(c, FlowService.class);
        try {
            if (on) { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); }
            else c.stopService(i);
        } catch (RuntimeException ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (det == null) {
            det = new PickupDetector(this, this::onPickup);
            det.start();
        }
        return START_STICKY;
    }

    /** A pickup on this device: bring the music here, unless it is already playing here. */
    private void onPickup() {
        if (busy || LocalMedia.isPlayingHere(this)) return;
        busy = true;
        new Thread(() -> {
            String[] t = null;
            try { t = BtRemote.pullFromPeer(this); } catch (RuntimeException ignored) { }
            final String[] track = t;
            main.post(() -> {
                if (track != null) {
                    LocalMedia.playHere(this, track[0], track[1], parse(track[2]));
                    toast(L.t("Musik folgt dir: ") + track[0]);
                }
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
        if (det != null) { det.stop(); det = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
