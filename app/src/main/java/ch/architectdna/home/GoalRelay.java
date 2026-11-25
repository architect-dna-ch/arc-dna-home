package ch.architectdna.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * ARC One's goal mirror rides on this app's Bluetooth link: ARC One hands us its goal (Out), we send it
 * to the paired ARC device as a GOAL frame, and that device's ARC-DNA hands it to its ARC One (deliver).
 */
final class GoalRelay {

    private GoalRelay() { }

    /** Explicit broadcast from ARC One: extra "goal" = the goal as JSON. */
    public static final class Out extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent i) {
            final String goal = i.getStringExtra("goal");
            if (goal == null || goal.length() > 60_000) return;
            final Context c = ctx.getApplicationContext();
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try { BtRemote.sendToPeer(c, "GOAL\t" + goal.replace('\t', ' ').replace('\n', ' ')); }
                catch (RuntimeException ignored) { }
                finally { pr.finish(); }
            }, "arc-goal").start();
        }
    }

    /** A GOAL frame arrived from the other device: pass it to ARC One here. */
    static void deliver(Context c, String goal) {
        try {
            c.sendBroadcast(new Intent().setClassName("ch.architectdna.one", "ch.architectdna.one.GoalSync$In")
                .putExtra("goal", goal));
        } catch (RuntimeException ignored) { }
    }
}
