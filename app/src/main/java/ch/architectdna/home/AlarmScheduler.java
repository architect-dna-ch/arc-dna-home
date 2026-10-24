package ch.architectdna.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

/** Schedules the daily bear alarm (and snoozes) as real alarm clocks, so they ring in Doze too. */
final class AlarmScheduler {

    static final String ACTION_ALARM = "ch.architectdna.home.ALARM";
    private static final int DAILY = 1, SNOOZE = 2;

    private AlarmScheduler() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Next time the daily alarm rings (today if still ahead, else tomorrow). */
    static long next(SharedPreferences p) {
        int m = p.getInt("alarmMinutes", 7 * 60);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, m / 60);
        c.set(Calendar.MINUTE, m % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    /** (Re)schedules the daily alarm, or cancels it when switched off. */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        if (!prefs(c).getBoolean("alarmOn", false)) {
            am.cancel(pending(c, DAILY));
            return;
        }
        set(c, am, next(prefs(c)), DAILY);
    }

    static void snooze(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) set(c, am, System.currentTimeMillis() + minutes * 60_000L, SNOOZE);
    }

    static void cancelSnooze(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(c, SNOOZE));
    }

    private static void set(Context c, AlarmManager am, long at, int code) {
        PendingIntent show = PendingIntent.getActivity(c, 10, new Intent(c, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pending(c, code));
        } catch (SecurityException e) {
            // Without exact-alarm access fall back to an inexact but Doze-proof alarm.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, code));
        }
    }

    private static PendingIntent pending(Context c, int code) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
