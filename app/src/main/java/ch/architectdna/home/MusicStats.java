package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Listening stats for the music card's second page, kept on the phone only: minutes listened per
 * day (last 7 days), titles started today and today's top artists. Fed by MediaListener from the
 * active media sessions, so it counts in the background too.
 */
final class MusicStats {

    private MusicStats() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static String day(long t) {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(t));
    }

    /** A playback state or track change from any media app. */
    static synchronized void update(Context c, boolean playing, String title, String artist) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        long now = System.currentTimeMillis();
        long since = p.getLong("msPlayStart", 0);
        if (since > 0 && (!playing || now - since > 60_000)) {
            // Book the time listened so far (also every minute while it keeps playing).
            String d = day(now);
            e.putLong("musicMs:" + d, p.getLong("musicMs:" + d, 0) + Math.min(now - since, 6 * 3_600_000L));
            since = 0;
        }
        e.putLong("msPlayStart", playing ? (since > 0 ? since : now) : 0);
        String key = (title == null ? "" : title) + "\u0001" + (artist == null ? "" : artist);
        if (playing && title != null && !key.equals(p.getString("lastTrack", ""))) {
            String d = day(now);
            e.putString("lastTrack", key);
            e.putInt("musicTracks:" + d, p.getInt("musicTracks:" + d, 0) + 1);
            if (artist != null && !artist.isEmpty()) {
                Map<String, Integer> top = artists(p, d);
                top.put(artist, top.getOrDefault(artist, 0) + 1);
                StringBuilder b = new StringBuilder();
                for (Map.Entry<String, Integer> en : top.entrySet()) {
                    b.append(en.getKey().replace('\n', ' ').replace('\t', ' ')).append('\t').append(en.getValue()).append('\n');
                }
                e.putString("musicArtists:" + d, b.toString());
            }
        }
        e.apply();
    }

    private static Map<String, Integer> artists(SharedPreferences p, String d) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String line : p.getString("musicArtists:" + d, "").split("\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) continue;
            try {
                m.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    /** Minutes per day, oldest first, today last (includes a session still running). */
    static long[] week(Context c) {
        SharedPreferences p = prefs(c);
        long[] out = new long[7];
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -6);
        for (int i = 0; i < 7; i++) {
            out[i] = p.getLong("musicMs:" + day(cal.getTimeInMillis()), 0) / 60_000;
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        long since = p.getLong("msPlayStart", 0);
        if (since > 0) out[6] += Math.max(0, System.currentTimeMillis() - since) / 60_000;
        return out;
    }

    static int tracksToday(Context c) {
        return prefs(c).getInt("musicTracks:" + day(System.currentTimeMillis()), 0);
    }

    /** Up to three of today's artists, most played first. */
    static List<String> topArtists(Context c) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(artists(prefs(c), day(System.currentTimeMillis())).entrySet());
        Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, list.size()); i++) out.add(list.get(i).getKey() + " · " + list.get(i).getValue() + "×");
        return out;
    }
}
