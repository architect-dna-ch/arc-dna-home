package ch.architectdna.one;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * "Wozu?" — the one sentence you came for, kept in front of you.
 *
 * Opening Chrome, Settings, YouTube & co. from ARC One first asks what for (or "nur kurz"). That
 * sentence then sits in a silent notification with a running clock, and after 4 minutes (then every
 * 4 more) the phone taps you: "Noch bei: …?". "Erledigt ✓" in the notification, or coming back to
 * ARC One, ends it. Nothing blocks you — it just keeps the thread from getting lost in a detour.
 */
final class Purpose {

    static final String CH_ON = "purpose", CH_NUDGE = "purpose_nudge";
    private static final int NOTE = 61;
    static final long NUDGE_MS = 4 * 60_000L;
    /** The apps where a quick look turns into a detour. */
    static final String[] RABBIT_HOLES = {
        "com.android.chrome", "com.android.settings", "com.google.android.youtube", "com.instagram.android",
        "com.zhiliaoapp.musically", "com.reddit.frontpage", "com.twitter.android", "com.facebook.katana",
        "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
        "com.google.android.googlequicksearchbox", "com.android.vending", "com.snapchat.android", "com.linkedin.android",
    };

    private Purpose() { }

    static boolean guarded(Context c, String pkg) {
        if (!prefs(c).getBoolean("purpose_on", true)) return false;
        for (String p : RABBIT_HOLES) if (p.equals(pkg)) return true;
        return false;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("home", Context.MODE_PRIVATE); }

    static String current(Context c) { return prefs(c).getString("purpose_text", ""); }
    static long since(Context c) { return prefs(c).getLong("purpose_at", 0); }

    /** Starts it: remembers the sentence, shows it, and sets the first nudge. Empty = "nur kurz". */
    static void begin(Context c, String text, String appLabel) {
        String t = text == null || text.trim().isEmpty() ? "nur kurz in " + appLabel : text.trim();
        prefs(c).edit().putString("purpose_text", t).putLong("purpose_at", System.currentTimeMillis())
            .putString("purpose_app", appLabel).apply();
        remember(c, t);
        show(c, false);
        schedule(c);
    }

    static void end(Context c) {
        prefs(c).edit().remove("purpose_text").remove("purpose_at").remove("purpose_app").apply();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTE);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(nudge(c));
    }

    /** The last few purposes, newest first, as one-tap suggestions. */
    static String[] recent(Context c) {
        String s = prefs(c).getString("purpose_recent", "");
        return s.isEmpty() ? new String[0] : s.split("\n");
    }

    private static void remember(Context c, String t) {
        if (t.startsWith("nur kurz")) return;
        StringBuilder b = new StringBuilder(t);
        int n = 1;
        for (String r : recent(c)) if (!r.equals(t) && n++ < 6) b.append('\n').append(r);
        prefs(c).edit().putString("purpose_recent", b.toString()).apply();
    }

    private static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_ON) == null)
            nm.createNotificationChannel(new NotificationChannel(CH_ON, "Wozu? (läuft)", NotificationManager.IMPORTANCE_LOW));
        if (nm.getNotificationChannel(CH_NUDGE) == null)
            nm.createNotificationChannel(new NotificationChannel(CH_NUDGE, "Wozu? (Erinnerung)", NotificationManager.IMPORTANCE_HIGH));
    }

    private static void show(Context c, boolean nudge) {
        String t = current(c);
        if (t.isEmpty()) return;
        channels(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent home = PendingIntent.getActivity(c, 60, new Intent(c, OneHomeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent done = PendingIntent.getBroadcast(c, 62, new Intent(c, Receiver.class).setAction("done"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, nudge ? CH_NUDGE : CH_ON)
            .setSmallIcon(R.drawable.ic_one)
            .setContentTitle(nudge ? "Noch bei deinem Ziel?" : "Du wolltest:")
            .setContentText(t)
            .setStyle(new Notification.BigTextStyle().bigText(t))
            .setWhen(since(c)).setUsesChronometer(true).setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(!nudge)
            .setContentIntent(home)
            .addAction(new Notification.Action.Builder(null, "Erledigt ✓", done).build())
            .build();
        try { nm.notify(NOTE, n); } catch (SecurityException ignored) { }
    }

    private static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + NUDGE_MS, nudge(c));
    }

    private static PendingIntent nudge(Context c) {
        return PendingIntent.getBroadcast(c, 63, new Intent(c, Receiver.class).setAction("nudge"), PendingIntent.FLAG_IMMUTABLE);
    }

    /* ---------------- away check: ARC One looks after you while you're in other apps ---------------- */

    static final long AWAY_MS = 10 * 60_000L;
    private static final int AWAY_NOTE = 64;
    private static int started;                          // ARC One screens currently visible

    static boolean awayOn(Context c) { return prefs(c).getBoolean("away_on", true); }

    /** Counts ARC One's own visible screens; when the last one goes, the check-in clock starts. */
    static void track(android.app.Application app) {
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(android.app.Activity a) { if (started++ == 0) back(a); }
            @Override public void onActivityStopped(android.app.Activity a) { if (--started <= 0) { started = 0; left(a); } }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { }
            @Override public void onActivityResumed(android.app.Activity a) { }
            @Override public void onActivityPaused(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(android.app.Activity a) { }
        });
    }

    private static void left(Context c) {
        if (!awayOn(c)) return;
        prefs(c).edit().putLong("away_since", System.currentTimeMillis()).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + AWAY_MS, away(c));
    }

    private static void back(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(away(c));
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(AWAY_NOTE);
        prefs(c).edit().remove("away_since").apply();
    }

    private static PendingIntent away(Context c) {
        return PendingIntent.getBroadcast(c, 65, new Intent(c, Receiver.class).setAction("away"), PendingIntent.FLAG_IMMUTABLE);
    }

    private static void awayNudge(Context c) {
        if (!awayOn(c) || prefs(c).getLong("away_since", 0) == 0) return;
        if (!current(c).isEmpty()) return;                      // a «Wozu?» is running: that one nudges already
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        if (pm != null && !pm.isInteractive()) {                // screen off: no nudge, look again later
            prefs(c).edit().putLong("away_since", System.currentTimeMillis()).apply();
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + AWAY_MS, away(c));
            return;
        }
        channels(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        long mins = Math.max(1, (System.currentTimeMillis() - prefs(c).getLong("away_since", 0)) / 60_000);
        String goal = prefs(c).getString("one_text", "");
        boolean priv = prefs(c).getBoolean("one_private", false);
        String body = goal.isEmpty() || priv ? "Seit " + mins + " Min. weg vom Startbildschirm. Bist du noch bei dem, was du wolltest?"
            : "Seit " + mins + " Min. unterwegs. Dein Ziel: " + goal;
        PendingIntent home = PendingIntent.getActivity(c, 66, new Intent(c, OneHomeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent fine = PendingIntent.getBroadcast(c, 67, new Intent(c, Receiver.class).setAction("fine"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH_NUDGE)
            .setSmallIcon(R.drawable.ic_one)
            .setContentTitle("Abgelenkt?")
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(home)
            .addAction(new Notification.Action.Builder(null, "Zurück zu ARC One", home).build())
            .addAction(new Notification.Action.Builder(null, "Alles gut", fine).build())
            .build();
        try { nm.notify(AWAY_NOTE, n); } catch (SecurityException ignored) { }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + AWAY_MS, away(c));
    }

    /** "Erledigt ✓" from the notification, the 4-minute taps, and the away check-ins. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if ("away".equals(i.getAction())) { awayNudge(c); return; }
            if ("fine".equals(i.getAction())) {                 // "Alles gut": quiet for 30 min, then ask again
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                if (nm != null) nm.cancel(AWAY_NOTE);
                AlarmManager am = c.getSystemService(AlarmManager.class);
                if (am != null) { am.cancel(away(c)); am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3 * AWAY_MS, away(c)); }
                return;
            }
            if ("done".equals(i.getAction())) { end(c); return; }
            if (current(c).isEmpty()) return;
            show(c, true);
            schedule(c);
        }
    }
}
