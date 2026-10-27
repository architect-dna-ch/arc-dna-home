package ch.architectdna.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Rings the bear alarm: a full-screen alarm notification opens BearAlarmActivity over the lock
 * screen (or as a heads-up while the phone is in use). Also re-arms the alarm after a reboot or
 * an app update, and schedules tomorrow's alarm each time it rings.
 */
public class AlarmReceiver extends BroadcastReceiver {

    static final String CHANNEL = "alarm";
    static final int NOTIFICATION_ID = 42;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        DiagLog.init(ctx);
        DiagLog.log("alarm receiver: " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AlarmScheduler.schedule(ctx);
            AlarmScheduler.armDeadlines(ctx);
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) remindFullScreen(ctx);
            return;
        }
        if (!AlarmScheduler.ACTION_ALARM.equals(action)) return;

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Bären-Wecker", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);       // the activity plays the growl itself
            nm.createNotificationChannel(ch);
        }
        long deadline = intent.getLongExtra("deadline", -1);
        String label = intent.getStringExtra("label");
        Intent full = new Intent(ctx, BearAlarmActivity.class)
            .putExtra("label", label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent pi = PendingIntent.getActivity(ctx, 3, full,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle(deadline >= 0 ? "Der Bär erinnert dich" : "Der Bär kommt …")
            .setContentText(deadline >= 0 && label != null ? label : "Aufwachen! Antippen, um den Wecker zu öffnen.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
        try {
            ctx.startActivity(full);       // works when allowed; the full-screen intent covers the rest
        } catch (RuntimeException ignored) {
        }
        if (deadline >= 0) AlarmScheduler.removeDeadline(ctx, deadline);     // one-time: done
        else AlarmScheduler.schedule(ctx);      // tomorrow, same time
    }

    /**
     * Android takes the full-screen access away again on each sideloaded update. If the bear alarm
     * is on, a quiet notification leads straight to the switch: one tap instead of digging in settings.
     */
    private static void remindFullScreen(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT < 34) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.canUseFullScreenIntent()) return;
        if (!ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("alarmOn", false)) return;
        DiagLog.log("full-screen access lost after update: reminder posted");
        if (nm.getNotificationChannel("setup") == null) {
            nm.createNotificationChannel(new NotificationChannel("setup", "Einrichtung", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            android.net.Uri.parse("package:" + ctx.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(43, new Notification.Builder(ctx, "setup")
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle("Bär-Wecker: Vollbild wieder erlauben")
            .setContentText("Android hat es beim Update zurückgesetzt. Antippen → Schalter an.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build());
    }
}
