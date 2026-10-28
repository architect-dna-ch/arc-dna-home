package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Wake-up log of the bear alarm. One entry per wake-up: from the first ring through every snooze
 * until "AUFWACHEN" is held. Keeps how long it rang, how many snoozes, how far the bear got and
 * whether the volume had to be boosted, so you can see how long you really need to wake up.
 */
final class AlarmLog {

    private static final String PREFS = "alarmlog";
    private static final int KEEP = 120;
    /** A ring more than this after the last one starts a new wake-up instead of continuing it. */
    private static final long SESSION_GAP = 30 * 60_000L;

    static final class Entry {
        long at;           // first ring
        String label;      // deadline label, or "" for the daily alarm
        int rings, snoozes;
        long ringMs;       // total time the bear was ringing
        long wakeMs;       // first ring to awake (snoozes included)
        long lastRingMs;   // the final ring alone: how long the last push took
        boolean roared, boosted;
        String outcome;    // "wach" or "abgebrochen"
    }

    private AlarmLog() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The bear starts ringing. */
    static void ringStarted(Context c, String label) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = p.edit();
        if (now - p.getLong("lastEvent", 0) > SESSION_GAP || p.getLong("start", 0) == 0) {
            e.putLong("start", now).putInt("rings", 0).putInt("snoozes", 0).putLong("ringMs", 0)
                .putBoolean("roared", false).putBoolean("boosted", false).putString("label", label == null ? "" : label);
        }
        e.putInt("rings", p.getInt("rings", 0) + 1).putLong("ringAt", now).putLong("lastEvent", now).apply();
        DiagLog.log("alarm log: ring " + (p.getInt("rings", 0)) + " started");
    }

    static void roared(Context c) {
        prefs(c).edit().putBoolean("roared", true).apply();
    }

    static void boosted(Context c) {
        prefs(c).edit().putBoolean("boosted", true).apply();
    }

    /** Snooze: the ring time adds up, the wake-up stays open. */
    static void snoozed(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        p.edit().putInt("snoozes", p.getInt("snoozes", 0) + 1)
            .putLong("ringMs", p.getLong("ringMs", 0) + Math.max(0, now - p.getLong("ringAt", now)))
            .putLong("lastEvent", now).apply();
        DiagLog.log("alarm log: snoozed after " + (now - p.getLong("ringAt", now)) / 1000 + " s");
    }

    /** Awake (held the button), or the screen went away without an answer ("abgebrochen"). */
    static void finished(Context c, boolean awake) {
        SharedPreferences p = prefs(c);
        long start = p.getLong("start", 0);
        if (start == 0) return;
        long now = System.currentTimeMillis(), ringAt = p.getLong("ringAt", now);
        Entry en = new Entry();
        en.at = start;
        en.label = p.getString("label", "");
        en.rings = p.getInt("rings", 1);
        en.snoozes = p.getInt("snoozes", 0);
        en.lastRingMs = Math.max(0, now - ringAt);
        en.ringMs = p.getLong("ringMs", 0) + en.lastRingMs;
        en.wakeMs = Math.max(0, now - start);
        en.roared = p.getBoolean("roared", false);
        en.boosted = p.getBoolean("boosted", false);
        en.outcome = awake ? "wach" : "abgebrochen";
        List<Entry> all = entries(c);
        all.add(en);
        while (all.size() > KEEP) all.remove(0);
        JSONArray a = new JSONArray();
        for (Entry x : all) a.put(toJson(x));
        p.edit().putString("log", a.toString()).putLong("start", 0).apply();
        DiagLog.log("alarm log: " + en.outcome + " after " + en.wakeMs / 1000 + " s, rang " + en.ringMs / 1000
            + " s, snoozes " + en.snoozes + (en.boosted ? ", volume boosted" : ""));
    }

    static List<Entry> entries(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("log", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.at = o.optLong("at");
                e.label = o.optString("label");
                e.rings = o.optInt("rings", 1);
                e.snoozes = o.optInt("snoozes");
                e.ringMs = o.optLong("ringMs");
                e.wakeMs = o.optLong("wakeMs");
                e.lastRingMs = o.optLong("lastRingMs");
                e.roared = o.optBoolean("roared");
                e.boosted = o.optBoolean("boosted");
                e.outcome = o.optString("outcome", "wach");
                out.add(e);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    static String json(Context c) {
        return prefs(c).getString("log", "[]");
    }

    private static JSONObject toJson(Entry e) {
        JSONObject o = new JSONObject();
        try {
            o.put("at", e.at).put("label", e.label).put("rings", e.rings).put("snoozes", e.snoozes)
                .put("ringMs", e.ringMs).put("wakeMs", e.wakeMs).put("lastRingMs", e.lastRingMs)
                .put("roared", e.roared).put("boosted", e.boosted).put("outcome", e.outcome);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** "1:05" or "12:30" (m:ss), "1 h 05" from an hour. */
    static String dur(long ms) {
        long s = ms / 1000;
        if (s >= 3600) return String.format(java.util.Locale.ROOT, "%d h %02d", s / 3600, s / 60 % 60);
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
