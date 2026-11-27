package ch.architectdna.one;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The «Wozu?» journal: every finished purpose as one entry — what, which app, from, to, how it ended
 * (erledigt · vergessen · abgelöst), and whether music was playing. Kept on the phone only, the last
 * 5000 entries (years of daily use).
 */
final class Journal {

    static final class Entry {
        String text, app, outcome;
        long start, end;
        boolean music;
        long minutes() { return Math.max(1, (end - start) / 60_000); }
        boolean done() { return "erledigt".equals(outcome); }
    }

    private static final int MAX = 5000;

    private Journal() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("journal", Context.MODE_PRIVATE); }

    static void add(Context c, String text, String app, long start, long end, String outcome, boolean music) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("entries", "[]"));
            a.put(new JSONObject().put("t", text).put("a", app).put("s", start).put("e", Math.max(start, end))
                .put("o", outcome).put("m", music));
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - MAX); i < a.length(); i++) keep.put(a.get(i));
            prefs(c).edit().putString("entries", keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** All entries that started at or after {@code from} (0 = all), oldest first. */
    static List<Entry> since(Context c, long from) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("entries", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optLong("s") < from) continue;
                Entry e = new Entry();
                e.text = o.optString("t"); e.app = o.optString("a"); e.outcome = o.optString("o");
                e.start = o.optLong("s"); e.end = o.optLong("e"); e.music = o.optBoolean("m");
                out.add(e);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Minutes per weekday (0 = Monday) × hour, split across hours an entry spans. */
    static float[][] heat(List<Entry> es) {
        float[][] h = new float[7][24];
        Calendar cal = Calendar.getInstance();
        for (Entry e : es) {
            long t = e.start, end = Math.max(e.end, e.start + 60_000);
            int guard = 0;
            while (t < end && guard++ < 200) {
                cal.setTimeInMillis(t);
                int dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7, hr = cal.get(Calendar.HOUR_OF_DAY);
                cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
                long next = cal.getTimeInMillis() + 3_600_000L;
                h[dow][hr] += (Math.min(end, next) - t) / 60_000f;
                t = next;
            }
        }
        return h;
    }
}
