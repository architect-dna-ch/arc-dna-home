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
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AlarmScheduler.schedule(ctx);
            return;
        }
        if (!AlarmScheduler.ACTION_ALARM.equals(action)) return;

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Bären-Wecker", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);       // the activity plays the growl itself
            nm.createNotificationChannel(ch);
        }
        Intent full = new Intent(ctx, BearAlarmActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent pi = PendingIntent.getActivity(ctx, 3, full,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle("Der Bär kommt …")
            .setContentText("Aufwachen! Antippen, um den Wecker zu öffnen.")
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
        AlarmScheduler.schedule(ctx);      // tomorrow, same time
    }
}
