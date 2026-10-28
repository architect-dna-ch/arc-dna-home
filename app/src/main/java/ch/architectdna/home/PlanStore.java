package ch.architectdna.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Flexplan's brain, shared by the app and the live check-in notifications: blocks and tasks,
 * how you usually feel at each weekday/hour (Stimmung log), finding easier slots, planning the
 * week, and the alarm for the next check-in.
 */
final class PlanStore {

    /** Block kinds: key, emoji, name, minutes, energy it takes (1 light … 3 heavy), colour. */
    static final Object[][] KINDS = {
        {"lernen", "📚", "Lernen", 50, 3, 0xFF3C8DFF},
        {"sport", "🏃", "Sport", 45, 3, 0xFF2BBF6A},
        {"arbeit", "💼", "Arbeit", 90, 2, 0xFFD9472F},
        {"kreativ", "🎨", "Kreativ", 60, 2, 0xFFA77BFF},
        {"haushalt", "🧹", "Haushalt", 30, 1, 0xFFE8B931},
        {"admin", "📨", "Admin / Mails", 30, 1, 0xFF9A9A98},
        {"erholung", "🌿", "Erholung", 30, 1, 0xFF6FC3F2},
    };
    static final int[] DEFAULT_TARGET = {4, 3, 0, 1, 2, 1, 5};
    static final int DAY_FROM = 7, DAY_TO = 22, TASK_COLOUR = 0xFFFF7A2B;

    static final class Block {
        long id, at, task;
        int kind, min, state, moved;     // state: 0 planned, 1 done, 2 dropped
    }

    /** A thing with an end (taxes!): split into sessions that keep finding good slots until done. */
    static final class Task {
        long id, due;
        String name, emoji;
        int sessions, min, done;
    }

    final Context ctx;
    final SharedPreferences prefs;
    final List<Block> blocks = new ArrayList<>();
    final List<Task> tasks = new ArrayList<>();
    private double[][] moodGrid;

    PlanStore(Context c) {
        ctx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        prefs = ctx.getSharedPreferences("plan", Context.MODE_PRIVATE);
        load();
    }

    /* ================= Data ================= */

    void load() {
        blocks.clear();
        tasks.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("blocks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Block b = new Block();
                b.id = o.optLong("id");
                b.at = o.optLong("at");
                b.kind = Math.max(0, Math.min(KINDS.length - 1, o.optInt("k")));
                b.min = o.optInt("min", 30);
                b.state = o.optInt("s");
                b.moved = o.optInt("mv");
                b.task = o.optLong("t");
                blocks.add(b);
            }
            JSONArray t = new JSONArray(prefs.getString("tasks", "[]"));
            for (int i = 0; i < t.length(); i++) {
                JSONObject o = t.getJSONObject(i);
                Task k = new Task();
                k.id = o.optLong("id");
                k.due = o.optLong("due");
                k.name = o.optString("name", "Aufgabe");
                k.emoji = o.optString("emoji", "📌");
                k.sessions = o.optInt("n", 1);
                k.min = o.optInt("min", 30);
                k.done = o.optInt("done");
                tasks.add(k);
            }
        } catch (Exception ignored) {
        }
        long keep = System.currentTimeMillis() - 21L * 24 * 3600_000;
        blocks.removeIf(b -> b.at < keep);
        tasks.removeIf(k -> k.done >= k.sessions && k.due > 0 && k.due < keep);
    }

    void save() {
        JSONArray a = new JSONArray(), t = new JSONArray();
        try {
            for (Block b : blocks)
                a.put(new JSONObject().put("id", b.id).put("at", b.at).put("k", b.kind).put("min", b.min).put("s", b.state).put("mv", b.moved).put("t", b.task));
            for (Task k : tasks)
                t.put(new JSONObject().put("id", k.id).put("due", k.due).put("name", k.name).put("emoji", k.emoji).put("n", k.sessions).put("min", k.min).put("done", k.done));
        } catch (Exception ignored) {
        }
        prefs.edit().putString("blocks", a.toString()).putString("tasks", t.toString()).apply();
        arm();
    }

    Task task(long id) {
        for (Task k : tasks) if (k.id == id) return k;
        return null;
    }

    Block block(long id) {
        for (Block b : blocks) if (b.id == id) return b;
        return null;
    }

    int target(int k) {
        return prefs.getInt("target" + k, DEFAULT_TARGET[k]);
    }

    String name(Block b) {
        Task t = b.task != 0 ? task(b.task) : null;
        return t != null ? t.name : (String) KINDS[b.kind][2];
    }

    String emoji(Block b) {
        Task t = b.task != 0 ? task(b.task) : null;
        return t != null ? t.emoji : (String) KINDS[b.kind][1];
    }

    int colour(Block b) {
        return b.task != 0 ? TASK_COLOUR : (Integer) KINDS[b.kind][5];
    }

    int energy(Block b) {
        return b.task != 0 ? 2 : (Integer) KINDS[b.kind][4];
    }

    static long weekStart(long t) {
        long d = dayStart(t);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(d);
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        c.add(Calendar.DAY_OF_YEAR, -dow);
        return c.getTimeInMillis();
    }

    static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    int countWeek(int k, boolean doneOnly) {
        long ws = weekStart(System.currentTimeMillis()), we = ws + 7L * 24 * 3600_000;
        int n = 0;
        for (Block b : blocks) if (b.task == 0 && b.kind == k && b.at >= ws && b.at < we && (doneOnly ? b.state == 1 : b.state != 2)) n++;
        return n;
    }

    /** Sessions of a task still planned in the future. */
    int plannedAhead(Task t) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Block b : blocks) if (b.task == t.id && b.state == 0 && b.at + b.min * 60_000L > now) n++;
        return n;
    }

    Block nextFor(Task t) {
        Block best = null;
        for (Block b : blocks) if (b.task == t.id && b.state == 0 && (best == null || b.at < best.at)) best = b;
        return best;
    }

    /* ================= Feeling → good times ================= */

    void forgetMood() {
        moodGrid = null;
    }

    double predicted(int dow, int hour) {
        if (moodGrid == null) {
            JSONArray a = MoodActivity.entries(ctx);
            moodGrid = new double[7][24];
            double[][] sum = new double[7][24], w = new double[7][24];
            Calendar c = Calendar.getInstance();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                c.setTimeInMillis(o.optLong("at"));
                int d = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7, h = c.get(Calendar.HOUR_OF_DAY), m = o.optInt("m", 3);
                for (int dd = 0; dd < 7; dd++) {
                    for (int hh = Math.max(0, h - 1); hh <= Math.min(23, h + 1); hh++) {
                        double ww = (dd == d ? 1 : 0.35) * (hh == h ? 1 : 0.5);
                        sum[dd][hh] += m * ww;
                        w[dd][hh] += ww;
                    }
                }
            }
            for (int d = 0; d < 7; d++) {
                for (int h = 0; h < 24; h++) {
                    double prior = h < 9 ? 2.9 : h < 12 ? 3.4 : h < 14 ? 3.1 : h < 16 ? 2.9 : h < 19 ? 3.2 : h < 21 ? 3.3 : 2.8;
                    moodGrid[d][h] = (sum[d][h] + prior * 1.5) / (w[d][h] + 1.5);     // a little prior keeps thin data honest
                }
            }
        }
        return moodGrid[dow][Math.max(0, Math.min(23, hour))];
    }

    static int dow(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    static int hour(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    /** How good a start time is for this block. Heavy blocks want good-mood hours; spread, not stacked. */
    double score(int kind, long task, int energy, long at, long near) {
        double m = predicted(dow(at), hour(at));
        double s = m + (m - 3) * (energy - 1) * 0.6;
        s -= Math.abs(at - near) / 3600_000.0 * 0.04;
        int same = 0, onDay = 0;
        long d0 = dayStart(at);
        for (Block b : blocks) {
            if (b.state == 2 || b.at < d0 || b.at >= d0 + 24 * 3600_000L) continue;
            onDay++;
            if (task != 0 ? b.task == task : b.task == 0 && b.kind == kind) same++;
        }
        s -= same * 1.2 + Math.max(0, onDay - 3) * 0.5;
        return s;
    }

    boolean free(long at, int min, Block except) {
        long end = at + min * 60_000L;
        for (Block b : blocks) {
            if (b == except || b.state == 2) continue;
            long be = b.at + b.min * 60_000L;
            if (at < be + 10 * 60_000L && end + 10 * 60_000L > b.at) return false;
        }
        return true;
    }

    long bestSlot(int kind, long task, int energy, int min, long from, long until, long near, Block except) {
        long best = -1;
        double bestS = -1e9;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(from);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.get(Calendar.MINUTE) == 0) {
            // on the hour already
        } else if (c.get(Calendar.MINUTE) <= 30) {
            c.set(Calendar.MINUTE, 30);
        } else {
            c.set(Calendar.MINUTE, 0);
            c.add(Calendar.HOUR_OF_DAY, 1);
        }
        for (; c.getTimeInMillis() < until; c.add(Calendar.MINUTE, 30)) {
            int h = c.get(Calendar.HOUR_OF_DAY);
            long t = c.getTimeInMillis();
            if (h < DAY_FROM || t + min * 60_000L > dayStart(t) + DAY_TO * 3600_000L) continue;
            if (!free(t, min, except)) continue;
            double s = score(kind, task, energy, t, near);
            if (s > bestS) {
                bestS = s;
                best = t;
            }
        }
        return best;
    }

    String when(long t) {
        return new SimpleDateFormat("EE HH:mm", Locale.GERMAN).format(new Date(t));
    }

    /**
     * "Nicht jetzt": a slot at least 45 min away where you're usually in better shape; still
     * before the task's deadline or within the week if its goal needs it. Returns why, or null.
     */
    String notNow(Block b, boolean single) {
        long now = System.currentTimeMillis();
        long from = Math.max(now + 45 * 60_000L, b.at + 30 * 60_000L);
        long until;
        Task t = b.task != 0 ? task(b.task) : null;
        if (t != null && t.due > from + 3 * 3600_000L) until = t.due;
        else if (t != null) until = from + 3L * 24 * 3600_000;
        else {
            boolean needed = countWeek(b.kind, false) <= target(b.kind);
            long weekEnd = weekStart(now) + 7L * 24 * 3600_000;
            until = needed && weekEnd - from > 6 * 3600_000L ? weekEnd : from + 4L * 24 * 3600_000;
        }
        long at = bestSlot(b.kind, b.task, energy(b), b.min, from, until, from, b);
        if (at < 0) return null;
        double m = predicted(dow(at), hour(at));
        b.at = at;
        b.moved++;
        String why = emoji(b) + " " + name(b) + " → " + when(at)
            + String.format(Locale.GERMAN, " · da geht's dir meist %s (Ø %.1f).", m >= 3.6 ? "gut" : m >= 3 ? "okay" : "eher mau, aber besser als jetzt", m);
        if (single && b.moved >= 3) why += " Schon " + b.moved + "× verschoben – mach's kürzer? Kleinster Schritt zählt.";
        return why;
    }

    void markDone(Block b) {
        if (b.state == 1) return;
        b.state = 1;
        Task t = b.task != 0 ? task(b.task) : null;
        if (t != null) {
            t.done++;
            if (t.done >= t.sessions) {
                // Finished early: drop the sessions still waiting.
                for (Block o : blocks) if (o.task == t.id && o.state == 0) o.state = 2;
            }
        }
    }

    void unDone(Block b) {
        if (b.state != 1) return;
        b.state = 0;
        Task t = b.task != 0 ? task(b.task) : null;
        if (t != null) t.done = Math.max(0, t.done - 1);
    }

    /** Fill the rest of the week with the missing blocks and every open task's sessions. */
    int autoPlan() {
        long now = System.currentTimeMillis(), we = weekStart(now) + 7L * 24 * 3600_000;
        int added = planTasks();
        for (int round = 0; round < 7; round++) {
            for (int k = 0; k < KINDS.length; k++) {
                if (countWeek(k, false) >= target(k)) continue;
                int min = (Integer) KINDS[k][3];
                long t = bestSlot(k, 0, (Integer) KINDS[k][4], min, now + 15 * 60_000L, we, now, null);
                if (t < 0) continue;
                Block b = new Block();
                b.id = System.nanoTime();
                b.at = t;
                b.kind = k;
                b.min = min;
                blocks.add(b);
                added++;
            }
        }
        save();
        return added;
    }

    /** Every open task gets its missing sessions placed before the deadline, at most one a day while there's room. */
    int planTasks() {
        long now = System.currentTimeMillis();
        int added = 0;
        for (Task t : tasks) {
            int need = t.sessions - t.done - plannedAhead(t);
            long until = t.due > now + 3600_000L ? t.due : now + 10L * 24 * 3600_000;
            for (int i = 0; i < need; i++) {
                long at = bestSlot(0, t.id, 2, t.min, now + 30 * 60_000L, until, now, null);
                if (at < 0) at = bestSlot(0, t.id, 2, t.min, now + 30 * 60_000L, until + 3L * 24 * 3600_000, now, null);
                if (at < 0) break;
                Block b = new Block();
                b.id = System.nanoTime();
                b.at = at;
                b.task = t.id;
                b.min = t.min;
                blocks.add(b);
                added++;
            }
        }
        return added;
    }

    /* ================= Live check-ins ================= */

    /** The next planned block's start gets an alarm: the check-in notification asks "jetzt?". */
    void arm() {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7300, new Intent(ctx, PlanReceiver.class).setAction(PlanReceiver.CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (!prefs.getBoolean("checkins", true)) return;
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        for (Block b : blocks) if (b.state == 0 && b.at > now + 30_000 && b.at < next) next = b.at;
        if (next == Long.MAX_VALUE) return;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    /** The block starting now (within the last 20 min), if any is still open. */
    Block current() {
        long now = System.currentTimeMillis();
        Block best = null;
        for (Block b : blocks) {
            if (b.state != 0) continue;
            if (b.at <= now + 60_000 && now - b.at < Math.max(20, b.min) * 60_000L && (best == null || b.at > best.at)) best = b;
        }
        return best;
    }
}
