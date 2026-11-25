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
 * Every change carries a timestamp; the newer one wins, so either device can edit and nothing echoes.
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

    /** From the ARC-DNA app: the goal as the other device has it. Applied if it's newer than ours. */
    public static final class In extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            try {
                JSONObject o = new JSONObject(String.valueOf(i.getStringExtra("goal")));
                SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
                long at = o.optLong("at", 0);
                if (at <= p.getLong("one_updated", 0)) return;
                JSONArray done = o.optJSONArray("done");
                p.edit()
                    .putString("one_text", o.optString("t", ""))
                    .putLong("one_since", o.optLong("since", at))
                    .putLong("one_checked", o.optLong("checked", at))
                    .putString("one_done", done == null ? "[]" : done.toString())
                    .putLong("one_updated", at)
                    .apply();
            } catch (Exception ignored) { }
        }
    }
}
