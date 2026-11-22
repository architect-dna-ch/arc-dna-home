package ch.architectdna.home;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Crowdsourced public-transport density. Volunteers tag "how full" a line is (0 empty … 3 packed) and
 * everyone sees the recency-weighted level on ARC Route's connections. Backed by architect-dna.ch/api/crowd.
 * Keyed by the line label (e.g. "S3", "IC8"), normalised so "S 3" and "s3" match.
 */
final class Crowd {
    private static final String BASE = "https://architect-dna.ch/api/crowd";
    private static final Map<String, int[]> cache = new HashMap<>();   // line -> {level, n, ageMin}
    private Crowd() { }

    static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
    static int level(String line) { int[] v = cache.get(norm(line)); return v == null ? -1 : v[0]; }
    static int count(String line) { int[] v = cache.get(norm(line)); return v == null ? 0 : v[1]; }

    /** Blocking GET for a set of lines; fills the cache. Call OFF the main thread. */
    static void fetchSync(Collection<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        Set<String> uniq = new LinkedHashSet<>();
        for (String l : lines) { String n = norm(l); if (!n.isEmpty()) uniq.add(n); }
        if (uniq.isEmpty()) return;
        StringBuilder q = new StringBuilder();
        for (String n : uniq) { if (q.length() > 0) q.append(','); q.append(n); }
        try {
            HttpURLConnection c = open(BASE + "?lines=" + URLEncoder.encode(q.toString(), "UTF-8"), "GET");
            if (c.getResponseCode() != 200) return;
            JSONObject o = new JSONObject(read(c.getInputStream()));
            JSONObject L = o.optJSONObject("lines");
            if (L == null) return;
            for (Iterator<String> it = L.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject e = L.optJSONObject(k);
                if (e != null) cache.put(k, new int[]{e.optInt("level", -1), e.optInt("n", 0), e.optInt("ageMin", 0)});
            }
        } catch (Exception ignored) { }
    }

    /** Fire a report; updates the cache optimistically, posts on its own thread. */
    static void report(final String line, final int lvl) {
        final String n = norm(line);
        if (n.isEmpty()) return;
        final int level = Math.max(0, Math.min(3, lvl));
        cache.put(n, new int[]{level, Math.max(1, count(line)), 0});   // show it immediately
        new Thread(() -> {
            try {
                HttpURLConnection c = open(BASE, "POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = ("{\"line\":\"" + n + "\",\"level\":" + level + "}").getBytes("UTF-8");
                try (OutputStream os = c.getOutputStream()) { os.write(b); }
                c.getResponseCode();
            } catch (Exception ignored) { }
        }, "arc-crowd-report").start();
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }
    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int r; (r = s.read(buf)) != -1; ) o.write(buf, 0, r);
            return o.toString("UTF-8");
        }
    }
}
