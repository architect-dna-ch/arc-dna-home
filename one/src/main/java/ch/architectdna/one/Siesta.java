package ch.architectdna.one;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Siesta 90 — one full sleep cycle (~90 min), so you wake from light sleep instead of out of deep
 * sleep. One tap: the phone goes quiet (alarms only, if ARC One may change Do Not Disturb), and a
 * real alarm clock is set (AlarmManager.setAlarmClock — survives Doze and shows in the status bar).
 * When it's time, SiestaWakeActivity wakes you gently and puts the sound settings back.
 */
final class Siesta {

    static final long LENGTH = 90 * 60_000L;

    private Siesta() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("home", Context.MODE_PRIVATE); }

    /** When the current siesta ends, or 0. */
    static long endsAt(Context c) {
        long t = prefs(c).getLong("siesta_end", 0);
        return t > System.currentTimeMillis() - 10 * 60_000L ? t : 0;
    }

    static boolean canSilence(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    /** Starts it. Returns false if no alarm could be set. */
    static boolean start(Context c) {
        long end = System.currentTimeMillis() + LENGTH;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return false;
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(end, show(c)), wake(c));
        } catch (SecurityException e) {
            return false;
        }
        SharedPreferences.Editor e = prefs(c).edit().putLong("siesta_end", end);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && nm.isNotificationPolicyAccessGranted()) {
            e.putInt("siesta_filter", nm.getCurrentInterruptionFilter());
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);   // calls and pings off, our alarm on
        } else e.remove("siesta_filter");
        e.apply();
        return true;
    }

    /** Cancels it (or ends it after waking) and gives the sound settings back. */
    static void stop(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(wake(c));
        SharedPreferences p = prefs(c);
        int f = p.getInt("siesta_filter", -1);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (f > 0 && nm != null && nm.isNotificationPolicyAccessGranted()) {
            try { nm.setInterruptionFilter(f); } catch (RuntimeException ignored) { }
        }
        p.edit().remove("siesta_end").remove("siesta_filter").apply();
    }

    private static PendingIntent wake(Context c) {
        return PendingIntent.getActivity(c, 90, new Intent(c, SiestaWakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent show(Context c) {
        return PendingIntent.getActivity(c, 91, new Intent(c, OneHomeActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
