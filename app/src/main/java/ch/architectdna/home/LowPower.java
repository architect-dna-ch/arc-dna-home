package ch.architectdna.home;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

/**
 * Low battery: under 15 % and not plugged in, ARC-DNA pauses what eats the most – head scroll and
 * the face watch (camera), the theft watch on the lock screen (camera, microphone, GPS), the
 * background music analysis and the glow animation. Everything comes back by itself once the
 * phone is charging or above 15 % again. Asked often, so the battery is read at most every 20 s.
 */
final class LowPower {

    static final int LIMIT = 15;
    private static long checkedAt;
    private static boolean low;
    private static int level = 100;

    private LowPower() {
    }

    static boolean active(Context c) {
        long now = SystemClock.uptimeMillis();
        if (checkedAt != 0 && now - checkedAt < 20_000) return low;
        checkedAt = now;
        Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return low = false;
        int lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        level = lv < 0 ? 100 : Math.round(lv * 100f / Math.max(1, scale));
        boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        boolean was = low;
        low = !plugged && level < LIMIT;
        if (low != was) DiagLog.log(low ? "low power: " + level + " % – camera, mic, music analysis and glow paused" : "low power over: " + level + " %");
        return low;
    }

    static int level() {
        return level;
    }

    /** A battery broadcast just came in: read fresh next time. */
    static void stale() {
        checkedAt = 0;
    }
}
