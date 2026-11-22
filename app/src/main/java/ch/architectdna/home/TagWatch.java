package ch.architectdna.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * ARC Tag watcher: every ~15 min, asks architect-dna.ch/api/tag for each of your tags (with its owner
 * key) and notifies you about scans by SOMEONE ELSE since the last check — "your wallet was found".
 * Your own taps (made in ARC, which knows your key) are logged as "you" server-side and never alert.
 *
 * Runs on a background thread inside the app process; MediaListener (bound by the system while
 * notification access is on) starts it, and so does opening ARC Tag. A finder's report reaches you
 * within one check interval, not instantly — honest trade-off for no push server and low battery.
 */
final class TagWatch {

    static final String PREF = "arcTags";
    private static final String CHANNEL = "arc_tag";
    private static final long EVERY = 15 * 60_000L;
    private static final long MAX_AGE = 7L * 24 * 3600_000L;
    private static HandlerThread thread;
    private static Handler h;

    private TagWatch() { }

    static synchronized void start(Context ctx) {
        if (h != null) return;
        final Context c = ctx.getApplicationContext();
        thread = new HandlerThread("arc-tagwatch");
        thread.start();
        h = new Handler(thread.getLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try { check(c); } catch (Throwable ignored) { }
                h.postDelayed(this, EVERY);
            }
        }, 20_000);
    }

    /** One pass over all tags. Blocking network — only on the watcher thread. */
    static void check(Context c) throws Exception {
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        JSONArray tags = new JSONArray(p.getString(PREF, "[]"));
        long now = System.currentTimeMillis();
        for (int i = 0; i < tags.length(); i++) {
            JSONObject t = tags.optJSONObject(i);
            if (t == null) continue;
            String code = t.optString("code"), key = t.optString("key"), label = t.optString("label", "Tag");
            if (code.isEmpty() || key.isEmpty()) continue;
            JSONObject r = get("https://architect-dna.ch/api/tag?code=" + URLEncoder.encode(code, "UTF-8")
                + "&key=" + URLEncoder.encode(key, "UTF-8"));
            if (r == null || !r.optBoolean("owner")) continue;
            JSONArray scans = r.optJSONArray("scans");
            if (scans == null) continue;
            long seen = p.getLong("tagSeen_" + code, 0), newest = seen;
            JSONObject latest = null;
            int fresh = 0;
            for (int k = 0; k < scans.length(); k++) {
                JSONObject s = scans.optJSONObject(k);
                if (s == null) continue;
                long at = s.optLong("at");
                if (at > newest) newest = at;
                if (!"finder".equals(s.optString("by")) || at <= seen || now - at > MAX_AGE) continue;
                fresh++;
                if (latest == null || at > latest.optLong("at")) latest = s;
            }
            if (latest != null) notifyFound(c, code, label, latest, fresh);
            if (newest > seen) p.edit().putLong("tagSeen_" + code, newest).apply();
        }
    }

    private static JSONObject get(String u) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(u).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(10_000);
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0 && o.size() < 512 * 1024) o.write(b, 0, n);
                return new JSONObject(o.toString("UTF-8"));
            }
        } catch (Exception e) {
            return null;
        } finally { if (con != null) con.disconnect(); }
    }

    private static void notifyFound(Context c, String code, String label, JSONObject s, int count) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, L.t("ARC Tag – gefunden"), NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        StringBuilder text = new StringBuilder();
        text.append(android.text.format.DateUtils.getRelativeTimeSpanString(s.optLong("at")));
        boolean hasLoc = !s.isNull("lat") && !s.isNull("lng");
        if (hasLoc) {
            String d = distanceFromMe(c, s.optDouble("lat"), s.optDouble("lng"));
            text.append(d != null ? " · " + d : " · " + L.t("mit Fundort"));
        }
        String msg = s.optString("msg", "");
        if (!msg.isEmpty()) text.append(" · «").append(msg).append("»");
        if (count > 1) text.append(" (").append(count).append(L.t(" Meldungen)"));

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(c, code.hashCode(),
            new Intent(c, TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("🔔 " + label + L.t(" wurde gefunden"))
            .setContentText(text.toString())
            .setStyle(new Notification.BigTextStyle().bigText(text.toString()
                + (s.optString("contact", "").isEmpty() ? "" : "\n" + L.t("Kontakt: ") + s.optString("contact"))))
            .setAutoCancel(true)
            .setContentIntent(open);
        if (hasLoc) {
            PendingIntent map = PendingIntent.getActivity(c, code.hashCode() + 1,
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps?q=" + s.optDouble("lat") + "," + s.optDouble("lng")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
            b.addAction(new Notification.Action.Builder(null, L.t("Karte"), map).build());
        }
        try { nm.notify(0x7A60 + (code.hashCode() & 0xFFF), b.build()); } catch (RuntimeException ignored) { }
    }

    /** "~40 m von dir" / "2.3 km von dir" from the phone's last known position, or null if unknown. */
    private static String distanceFromMe(Context c, double lat, double lng) {
        try {
            LocationManager lm = c.getSystemService(LocationManager.class);
            if (lm == null) return null;
            Location best = null;
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return null;
            float[] out = new float[1];
            Location.distanceBetween(best.getLatitude(), best.getLongitude(), lat, lng, out);
            float m = out[0];
            return m < 1000 ? "~" + Math.round(m / 10f) * 10 + L.t(" m von dir") : String.format(java.util.Locale.GERMAN, "%.1f", m / 1000f) + L.t(" km von dir");
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }
}
