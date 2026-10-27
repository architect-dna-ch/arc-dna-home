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
        DiagLog.log("alarm set code=" + code + " in " + (at - System.currentTimeMillis()) / 60000 + " min");
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pending(c, code));
        } catch (SecurityException e) {
            DiagLog.log("alarm: no exact-alarm access, inexact fallback");
            // Without exact-alarm access fall back to an inexact but Doze-proof alarm.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, code));
        }
    }

    private static PendingIntent pending(Context c, int code) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* ---------------- one-time deadlines ("Mi 13:10 · Vorbereitung erster Tag Homeoffice") ---------------- */

    static final class Deadline {
        long id, at;
        String label;
    }

    static java.util.List<Deadline> deadlines(Context c) {
        java.util.List<Deadline> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs(c).getString("deadlines", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Deadline d = new Deadline();
                d.id = o.getLong("id");
                d.at = o.getLong("at");
                d.label = o.optString("label");
                out.add(d);
            }
        } catch (Exception ignored) {
        }
        out.sort((x, y) -> Long.compare(x.at, y.at));
        return out;
    }

    private static void store(Context c, java.util.List<Deadline> list) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Deadline d : list) a.put(new org.json.JSONObject().put("id", d.id).put("at", d.at).put("label", d.label));
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("deadlines", a.toString()).apply();
    }

    static void addDeadline(Context c, long at, String label) {
        java.util.List<Deadline> list = deadlines(c);
        Deadline d = new Deadline();
        d.id = System.currentTimeMillis();
        d.at = at;
        d.label = label;
        list.add(d);
        store(c, list);
        armDeadline(c, d);
    }

    static void removeDeadline(Context c, long id) {
        java.util.List<Deadline> list = deadlines(c);
        list.removeIf(d -> d.id == id);
        store(c, list);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(deadlinePending(c, id, ""));
    }

    /** All future deadlines armed again (after a reboot or an update); past ones are dropped. */
    static void armDeadlines(Context c) {
        java.util.List<Deadline> list = deadlines(c), keep = new java.util.ArrayList<>();
        for (Deadline d : list) {
            if (d.at > System.currentTimeMillis() - 60_000) {
                keep.add(d);
                armDeadline(c, d);
            }
        }
        if (keep.size() != list.size()) store(c, keep);
    }

    private static void armDeadline(Context c, Deadline d) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null || d.at <= System.currentTimeMillis()) return;
        PendingIntent show = PendingIntent.getActivity(c, 11, new Intent(c, WeckerActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(d.at, show), deadlinePending(c, d.id, d.label));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, d.at, deadlinePending(c, d.id, d.label));
        }
        DiagLog.log("deadline set in " + (d.at - System.currentTimeMillis()) / 60000 + " min: " + d.label);
    }

    private static PendingIntent deadlinePending(Context c, long id, String label) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_ALARM).putExtra("deadline", id).putExtra("label", label);
        return PendingIntent.getBroadcast(c, 1000 + (int) (id % 1_000_000), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The next deadline still ahead, or null. */
    static Deadline nextDeadline(Context c) {
        for (Deadline d : deadlines(c)) if (d.at > System.currentTimeMillis()) return d;
        return null;
    }
}
