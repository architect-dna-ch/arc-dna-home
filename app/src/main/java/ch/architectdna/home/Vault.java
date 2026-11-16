package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ARC Passwords: the logins, encrypted with a key in the Android Keystore (SecureStore) and kept
 * only on this device. No cloud, no sync. Unlocked by fingerprint / screen lock for a few minutes.
 */
final class Vault {
    private Vault() { }

    static final class Entry {
        String title = "", where = "", user = "", pass = "";

        JSONObject json() throws Exception {
            return new JSONObject().put("t", title).put("w", where).put("u", user).put("p", pass);
        }

        static Entry of(JSONObject o) {
            Entry e = new Entry();
            e.title = o.optString("t");
            e.where = o.optString("w");
            e.user = o.optString("u");
            e.pass = o.optString("p");
            return e;
        }
    }

    private static final long UNLOCK_MS = 3 * 60_000L;
    private static long unlockedAt;

    static boolean unlocked() { return unlockedAt != 0 && SystemClock.elapsedRealtime() - unlockedAt < UNLOCK_MS; }
    static void markUnlocked() { unlockedAt = SystemClock.elapsedRealtime(); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("vault", Context.MODE_PRIVATE); }

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        String raw = SecureStore.open(prefs(c).getString("data", ""));
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) out.add(Entry.of(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    static void save(Context c, List<Entry> list) {
        JSONArray a = new JSONArray();
        try { for (Entry e : list) a.put(e.json()); } catch (Exception ignored) { }
        prefs(c).edit().putString("data", SecureStore.seal(a.toString())).apply();
    }

    /** Logins that belong to this app (package) or website (host). */
    static List<Entry> match(List<Entry> all, String pkg, String host) {
        List<Entry> out = new ArrayList<>();
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        for (Entry e : all) {
            String w = e.where.toLowerCase(Locale.ROOT).trim().replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
            int slash = w.indexOf('/');
            if (slash > 0) w = w.substring(0, slash);
            if (w.isEmpty()) continue;
            if (w.equals(pkg) || (!h.isEmpty() && (h.equals(w) || h.endsWith("." + w) || w.endsWith("." + h)))) out.add(e);
        }
        return out;
    }

    /** "Off here" list: apps where the 🔑 key never shows. */
    static boolean mutedIn(Context c, String pkg) { return prefs(c).getStringSet("muted", new java.util.HashSet<>()).contains(pkg); }

    static void mute(Context c, String pkg) {
        java.util.Set<String> s = new java.util.HashSet<>(prefs(c).getStringSet("muted", new java.util.HashSet<>()));
        s.add(pkg);
        prefs(c).edit().putStringSet("muted", s).apply();
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean("on", true); }
    static void setEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("on", on).apply(); }

    /** Google Password Manager / Chrome export: name,url,username,password[,note]. */
    static int importCsv(Context c, String csv) {
        List<Entry> list = load(c);
        int n = 0;
        String[] lines = csv.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            List<String> f = csvRow(lines[i]);
            if (i == 0 && !f.isEmpty() && f.get(0).toLowerCase(Locale.ROOT).contains("name")) continue;   // header
            if (f.size() < 4 || f.get(3).isEmpty()) continue;
            Entry e = new Entry();
            e.title = f.get(0);
            e.where = f.get(1).isEmpty() ? f.get(0) : f.get(1);
            e.user = f.get(2);
            e.pass = f.get(3);
            if (e.where.startsWith("android://")) {                  // android://hash@package.name/
                int at = e.where.indexOf('@');
                e.where = at > 0 ? e.where.substring(at + 1).replace("/", "") : e.where;
            }
            list.add(e);
            n++;
        }
        save(c, list);
        return n;
    }

    private static List<String> csvRow(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { b.append('"'); i++; }
                else if (ch == '"') q = false;
                else b.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(b.toString()); b.setLength(0); }
            else b.append(ch);
        }
        out.add(b.toString());
        return out;
    }
}
