package ch.architectdna.home;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The battery diary behind "Energie": at every 1 % change (and when the screen goes on or off) one
 * line with what was going on — screen, the app in front, music and which app plays it, Wi-Fi or
 * mobile and the signal. Afterwards each stretch of discharge is put down to those, so you see what
 * really drains: an app, music, a weak signal, the screen being off. Only on the phone, 7 days.
 */
final class BatteryLog {

    private BatteryLog() {
    }

    static final class Sample {
        long time;
        int level, signal;
        boolean plugged, screen;
        String app = "", music = "", net = "";
        float temp;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "battery.log");
    }

    private static long lastAt;
    private static int lastLevel = -1;
    private static boolean lastScreen;

    /** Called from the always-running media listener with the battery broadcast (or null on screen changes). */
    static synchronized void sample(Context c, Intent battery) {
        if (battery == null) battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        int lvl = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (lvl < 0) return;
        int level = Math.round(lvl * 100f / Math.max(1, scale));
        PowerManager pm = c.getSystemService(PowerManager.class);
        boolean screen = pm != null && pm.isInteractive();
        long now = System.currentTimeMillis();
        // Only real changes: a new %, the screen switching, or every 30 min as a heartbeat.
        if (level == lastLevel && screen == lastScreen && now - lastAt < 30 * 60_000L) return;
        lastLevel = level;
        lastScreen = screen;
        lastAt = now;
        Sample s = new Sample();
        s.time = now;
        s.level = level;
        s.plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        s.screen = screen;
        s.temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        if (screen) s.app = frontApp(c);
        s.music = playing(c);
        net(c, s);
        try (FileWriter w = new FileWriter(file(c), true)) {
            w.write(s.time + "\t" + s.level + "\t" + (s.plugged ? 1 : 0) + "\t" + (s.screen ? 1 : 0) + "\t" + clean(s.app) + "\t"
                + clean(s.music) + "\t" + s.net + "\t" + s.signal + "\t" + s.temp + "\n");
        } catch (IOException ignored) {
        }
        if (file(c).length() > 400_000) prune(c);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    /** The app in front (from usage access), or "". */
    private static String frontApp(Context c) {
        try {
            UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            UsageEvents ev = um.queryEvents(now - 3 * 3600_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = "";
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
            }
            return last;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The app that's playing music right now, or "". */
    private static String playing(Context c) {
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            for (MediaController mc : m.getActiveSessions(new ComponentName(c, MediaListener.class))) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return mc.getPackageName();
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static void net(Context c, Sample s) {
        s.net = "none";
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s.net = "wifi";
                else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s.net = "cell";
            }
        } catch (RuntimeException ignored) {
        }
        s.signal = -1;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                TelephonyManager tm = c.getSystemService(TelephonyManager.class);
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) s.signal = ss.getLevel();       // 0 … 4
            } catch (RuntimeException ignored) {
            }
        }
    }

    static List<Sample> read(Context c) {
        List<Sample> out = new ArrayList<>();
        long since = System.currentTimeMillis() - 7L * 86_400_000L;
        try (BufferedReader r = new BufferedReader(new FileReader(file(c)))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\t", -1);
                if (f.length < 9) continue;
                try {
                    Sample s = new Sample();
                    s.time = Long.parseLong(f[0]);
                    if (s.time < since) continue;
                    s.level = Integer.parseInt(f[1]);
                    s.plugged = f[2].equals("1");
                    s.screen = f[3].equals("1");
                    s.app = f[4];
                    s.music = f[5];
                    s.net = f[6];
                    s.signal = Integer.parseInt(f[7]);
                    s.temp = Float.parseFloat(f[8]);
                    out.add(s);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void prune(Context c) {
        List<Sample> keep = read(c);
        try (FileWriter w = new FileWriter(file(c), false)) {
            for (Sample s : keep) {
                w.write(s.time + "\t" + s.level + "\t" + (s.plugged ? 1 : 0) + "\t" + (s.screen ? 1 : 0) + "\t" + clean(s.app) + "\t"
                    + clean(s.music) + "\t" + s.net + "\t" + s.signal + "\t" + s.temp + "\n");
            }
        } catch (IOException ignored) {
        }
    }

    /** One drain bucket: how long it was so, and how many % went meanwhile. */
    static final class Drain {
        final String key;
        double hours, drop;

        Drain(String key) {
            this.key = key;
        }

        double rate() {
            return hours > 0 ? drop / hours : 0;
        }
    }

    /**
     * Each stretch between two samples while unplugged is put down to what was going on at its start:
     * the app in front (or "screen off"), music, and the network. Three lists, worst first.
     */
    static List<List<Drain>> analyse(List<Sample> list, java.util.function.Function<String, String> label) {
        Map<String, Drain> apps = new HashMap<>(), music = new HashMap<>(), net = new HashMap<>();
        for (int i = 0; i + 1 < list.size(); i++) {
            Sample a = list.get(i), b = list.get(i + 1);
            if (a.plugged || b.plugged) continue;
            double h = (b.time - a.time) / 3_600_000.0;
            if (h <= 0 || h > 3) continue;
            double drop = Math.max(0, a.level - b.level);
            String ak = a.screen ? (a.app.isEmpty() ? "Bildschirm an" : label.apply(a.app)) : "Bildschirm aus";
            add(apps, ak, h, drop);
            add(music, a.music.isEmpty() ? "Ohne Musik" : "Musik · " + label.apply(a.music), h, drop);
            String nk = a.net.equals("wifi") ? "WLAN"
                : a.net.equals("cell") ? "Mobil · Signal " + (a.signal < 0 ? "?" : a.signal + "/4") + (a.signal >= 0 && a.signal <= 1 ? " (schwach)" : "")
                : "Kein Netz";
            add(net, nk, h, drop);
        }
        List<List<Drain>> out = new ArrayList<>();
        for (Map<String, Drain> m : List.of(apps, music, net)) {
            List<Drain> l = new ArrayList<>();
            for (Drain d : m.values()) if (d.hours >= 0.1) l.add(d);
            l.sort((x, y) -> Double.compare(y.rate(), x.rate()));
            out.add(l);
        }
        return out;
    }

    private static void add(Map<String, Drain> m, String k, double h, double drop) {
        Drain d = m.get(k);
        if (d == null) m.put(k, d = new Drain(k));
        d.hours += h;
        d.drop += drop;
    }
}
