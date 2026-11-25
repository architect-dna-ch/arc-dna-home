package ch.architectdna.one;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Mirrors "Mein Ziel gerade" between phone and tablet. ARC One has no Bluetooth of its own, so it hands
 * the goal to the ARC-DNA app (which holds the ARC link to the other device); that app sends a GOAL
 * frame over Bluetooth, and the ARC-DNA app on the other side hands it to its ARC One here.
 *
 * Only real changes are sent (a new sentence, a goal marked done) — never "Ja, weiter" or an unchanged
 * save, which once sent the tablet's old sentence with a fresh timestamp and overwrote a newer one on
 * the phone. The sentence's own time ("since") decides which is newer; done lists are merged; and any
 * sentence that gets replaced, here or from the other device, goes into the history first.
 */
final class GoalSync {

    static final String HUB = "ch.architectdna.home", HUB_RELAY = "ch.architectdna.home.GoalRelay$Out";

    private GoalSync() { }

    /** Call after any change to the goal (saved, marked done, "Ja, weiter"). */
    static void push(Context c) {
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        long at = System.currentTimeMillis();
        p.edit().putLong("one_updated", at).apply();
        try {
            JSONObject o = new JSONObject()
                .put("t", p.getString("one_text", ""))
                .put("since", p.getLong("one_since", at))
                .put("checked", p.getLong("one_checked", at))
                .put("done", new JSONArray(p.getString("one_done", "[]")))
                .put("at", at);
            c.sendBroadcast(new Intent().setClassName(HUB, HUB_RELAY).putExtra("goal", o.toString()));
        } catch (Exception ignored) { }
    }

    /** Keeps the last 50 replaced sentences ("one_history"), newest last, so a goal is never lost. */
    static void remember(SharedPreferences p, String text, long since) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            JSONArray h = new JSONArray(p.getString("one_history", "[]"));
            if (h.length() > 0 && text.equals(h.getJSONObject(h.length() - 1).optString("t"))) return;
            h.put(new JSONObject().put("t", text).put("since", since).put("at", System.currentTimeMillis()));
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, h.length() - 50); i < h.length(); i++) keep.put(h.get(i));
            p.edit().putString("one_history", keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** From the ARC-DNA app: the goal as the other device has it. Applied if its sentence is newer. */
    public static final class In extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            try {
                JSONObject o = new JSONObject(String.valueOf(i.getStringExtra("goal")));
                SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
                long at = o.optLong("at", 0);
                String theirs = o.optString("t", ""), mine = p.getString("one_text", "");
                long theirSince = o.optLong("since", 0), mySince = p.getLong("one_since", 0);
                SharedPreferences.Editor e = p.edit();
                boolean changed = false;
                // done lists: merge by time, so an "Erledigt" on either device is kept
                JSONArray merged = mergeDone(new JSONArray(p.getString("one_done", "[]")), o.optJSONArray("done"));
                if (!merged.toString().equals(p.getString("one_done", "[]"))) { e.putString("one_done", merged.toString()); changed = true; }
                // the sentence: only a newer one replaces ours — and ours is kept in the history
                boolean theirsIsNewer = theirSince > mySince || (mine.isEmpty() && !theirs.isEmpty() && theirSince >= mySince);
                boolean doneThere = theirs.isEmpty() && contains(merged, mine);       // they ticked off the same goal
                if (!theirs.equals(mine) && (theirsIsNewer || doneThere)) {
                    remember(p, mine, mySince);
                    e.putString("one_text", theirs).putLong("one_since", Math.max(theirSince, mySince));
                    changed = true;
                }
                if (changed) e.putLong("one_updated", Math.max(at, System.currentTimeMillis())).apply();
            } catch (Exception ignored) { }
        }
    }

    private static JSONArray mergeDone(JSONArray a, JSONArray b) throws Exception {
        java.util.TreeMap<Long, JSONObject> all = new java.util.TreeMap<>();
        for (JSONArray x : new JSONArray[]{a, b}) {
            if (x == null) continue;
            for (int i = 0; i < x.length(); i++) { JSONObject o = x.optJSONObject(i); if (o != null) all.put(o.optLong("at"), o); }
        }
        JSONArray out = new JSONArray();
        int skip = Math.max(0, all.size() - 100), i = 0;
        for (JSONObject o : all.values()) if (i++ >= skip) out.put(o);
        return out;
    }

    private static boolean contains(JSONArray done, String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < done.length(); i++) { JSONObject o = done.optJSONObject(i); if (o != null && text.equals(o.optString("t"))) return true; }
        return false;
    }
}
