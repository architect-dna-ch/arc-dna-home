package ch.architectdna.home;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns your usual public-transport routes, only on the phone: when the home opens (at most every
 * 10 min) the last known location is snapped to the nearest station (transport.opendata.ch, within
 * 400 m) and noted. A change of station within 4 h is a trip; trips that repeat are your routes,
 * with the hour you usually leave. Routes you search in "Routen" count too.
 */
final class RouteLearner {

    static final String API = "https://transport.opendata.ch/v1/";

    private RouteLearner() {
    }

    static final class Visit {
        long time;
        String id, name;
        double lat, lon;
    }

    static final class Route {
        String from, to;
        double fromLat, fromLon, toLat, toLon;
        int count;
        int[] hours = new int[24];
        boolean pinned, searched;

        String key() {
            return from + "→" + to;
        }

        /** The hour you usually leave, or -1. */
        int usualHour() {
            int best = -1, n = 0;
            for (int h = 0; h < 24; h++) {
                if (hours[h] > n) {
                    n = hours[h];
                    best = h;
                }
            }
            return best;
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "visits.log");
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Best last known location of any provider, or null. */
    @SuppressWarnings("MissingPermission")
    static Location lastLocation(Context c) {
        if (!allowed(c)) return null;
        LocationManager lm = c.getSystemService(LocationManager.class);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (RuntimeException ignored) {
        }
        return best;
    }

    /** From the home's onResume: note where you are (background thread, throttled). */
    static void note(Context ctx) {
        Context c = ctx.getApplicationContext();
        SharedPreferences p = c.getSharedPreferences("routes", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!allowed(c) || now - p.getLong("lastNote", 0) < 10 * 60_000L) return;
        p.edit().putLong("lastNote", now).apply();
        new Thread(() -> {
            Location l = lastLocation(c);
            if (l == null || now - l.getTime() > 30 * 60_000L) return;      // too old to say where you are now
            try {
                JSONArray st = get("locations?type=station&x=" + l.getLatitude() + "&y=" + l.getLongitude()).optJSONArray("stations");
                if (st == null || st.length() == 0) return;
                JSONObject s = st.getJSONObject(0);
                JSONObject co = s.optJSONObject("coordinate");
                if (co == null) return;
                float[] d = new float[1];
                Location.distanceBetween(l.getLatitude(), l.getLongitude(), co.optDouble("x"), co.optDouble("y"), d);
                if (d[0] > 400) return;          // not at a station
                String line = now + "\t" + s.optString("id") + "\t" + s.optString("name").replace('\t', ' ') + "\t"
                    + co.optDouble("x") + "\t" + co.optDouble("y") + "\n";
                synchronized (RouteLearner.class) {
                    try (FileWriter w = new FileWriter(file(c), true)) {
                        w.write(line);
                    }
                }
                DiagLog.log("routes: at " + s.optString("name"));
            } catch (Exception e) {
                DiagLog.log("routes: note failed: " + e.getMessage());
            }
        }, "arc-routes-note").start();
    }

    static synchronized List<Visit> visits(Context c) {
        List<Visit> out = new ArrayList<>();
        long since = System.currentTimeMillis() - 90L * 86_400_000L;
        try (BufferedReader r = new BufferedReader(new FileReader(file(c)))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\t", -1);
                if (f.length < 5) continue;
                try {
                    Visit v = new Visit();
                    v.time = Long.parseLong(f[0]);
                    if (v.time < since) continue;
                    v.id = f[1];
                    v.name = f[2];
                    v.lat = Double.parseDouble(f[3]);
                    v.lon = Double.parseDouble(f[4]);
                    out.add(v);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Learned trips plus searched / pinned routes, most frequent first. */
    static List<Route> routes(Context c) {
        Map<String, Route> m = new HashMap<>();
        List<Visit> vs = visits(c);
        Visit last = null;
        for (Visit v : vs) {
            if (last != null && !v.name.equals(last.name) && v.time - last.time < 4 * 3600_000L) {
                Route r = route(m, last.name, v.name);
                r.fromLat = last.lat;
                r.fromLon = last.lon;
                r.toLat = v.lat;
                r.toLon = v.lon;
                r.count++;
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(last.time);
                r.hours[cal.get(Calendar.HOUR_OF_DAY)]++;
            }
            last = v;
        }
        // Searches and pins: "from|to|count|pinned" lines.
        SharedPreferences p = c.getSharedPreferences("routes", Context.MODE_PRIVATE);
        for (String s : p.getString("searched", "").split("\n")) {
            String[] f = s.split("\\|");
            if (f.length < 4) continue;
            Route r = route(m, f[0], f[1]);
            try {
                r.count += Integer.parseInt(f[2]);
            } catch (NumberFormatException ignored) {
            }
            r.pinned |= f[3].equals("1");
            r.searched = true;
        }
        for (String s : p.getString("hidden", "").split("\n")) m.remove(s);
        List<Route> out = new ArrayList<>(m.values());
        out.removeIf(r -> r.count < 2 && !r.pinned && !r.searched);      // a learned trip counts from the 2nd time
        out.sort((a, b) -> a.pinned != b.pinned ? (a.pinned ? -1 : 1) : b.count - a.count);
        return out;
    }

    private static Route route(Map<String, Route> m, String from, String to) {
        Route r = m.get(from + "→" + to);
        if (r == null) {
            r = new Route();
            r.from = from;
            r.to = to;
            m.put(r.key(), r);
        }
        return r;
    }

    /** A search from the Routen screen counts as a trip; pin = keep it on top. */
    static void searched(Context c, String from, String to, boolean pin) {
        SharedPreferences p = c.getSharedPreferences("routes", Context.MODE_PRIVATE);
        StringBuilder b = new StringBuilder();
        boolean found = false;
        for (String s : p.getString("searched", "").split("\n")) {
            String[] f = s.split("\\|");
            if (f.length < 4) continue;
            if (f[0].equals(from) && f[1].equals(to)) {
                found = true;
                int n = 0;
                try {
                    n = Integer.parseInt(f[2]);
                } catch (NumberFormatException ignored) {
                }
                b.append(from).append('|').append(to).append('|').append(n + 1).append('|').append(pin || f[3].equals("1") ? 1 : 0).append('\n');
            } else {
                b.append(s).append('\n');
            }
        }
        if (!found) b.append(from).append('|').append(to).append("|1|").append(pin ? 1 : 0).append('\n');
        p.edit().putString("searched", b.toString()).apply();
    }

    static void hide(Context c, String key) {
        SharedPreferences p = c.getSharedPreferences("routes", Context.MODE_PRIVATE);
        p.edit().putString("hidden", p.getString("hidden", "") + key + "\n").apply();
    }

    /** GET a transport.opendata.ch endpoint (background thread only). */
    static JSONObject get(String path) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("Fahrplan antwortet " + code);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) bo.write(buf, 0, n);
        }
        return new JSONObject(bo.toString("UTF-8"));
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
