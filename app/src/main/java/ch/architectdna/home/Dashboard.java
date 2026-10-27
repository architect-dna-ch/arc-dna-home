package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * The tablet home from the sketch: weather and the live location on the left, an analog clock on
 * the right, below them a garden that grows by itself through the day, and a dock of app folders
 * (Apps, ARC-DNA, Zuletzt) instead of the long sections. Weather from Open-Meteo (no account),
 * place names from the system geocoder, the nearest stop from transport.opendata.ch.
 */
final class Dashboard {

    interface Host {
        /** A folder in the dock was tapped: 0 Apps, 1 ARC-DNA, 2 Zuletzt. */
        void folder(int which);

        /** Up to 4 icons to show on a folder. */
        Drawable[] folderIcons(int which);
    }

    private final Context ctx;
    private final int ink, ink2, ink3, rust, card, line;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Location here;
    private String place = "", stop = "";
    private float stopDist = -1, stopBearing;

    Dashboard(Context c, int[] colours, Host host) {
        ctx = c;
        ink = colours[0];
        ink2 = colours[1];
        ink3 = colours[2];
        rust = colours[3];
        card = colours[4];
        line = colours[5];
        this.host = host;
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable box() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setStroke(dp(1), line);
        g.setCornerRadius(dp(22));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(l, t, r, b);
        return p;
    }

    View build() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(ctx);
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        weather = new Weather();
        weather.setBackground(box());
        left.addView(weather, lp(-1, dp(210), 0, 0, 0, 0, dp(12)));
        live = new Live();
        live.setBackground(box());
        live.setOnClickListener(v -> {
            // No location yet: ask (the same permission Routen uses); then start the live updates.
            if (!RouteLearner.allowed(ctx) && ctx instanceof android.app.Activity) {
                ((android.app.Activity) ctx).requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, 61);
            } else {
                pause();
                locate();
            }
        });
        left.addView(live, lp(-1, dp(210), 0, 0, 0, 0, 0));
        top.addView(left, lp(0, -2, 2f, 0, 0, dp(12), 0));
        Clock clock = new Clock();
        clock.setBackground(box());
        top.addView(clock, lp(0, dp(432), 1f, 0, 0, 0, 0));
        col.addView(top);
        Garden garden = new Garden();
        garden.setBackground(box());
        col.addView(garden, lp(-1, dp(170), 0, 0, dp(12), 0, 0));
        // The dock: main app folders.
        LinearLayout dock = new LinearLayout(ctx);
        String[] names = {"Apps", "ARC-DNA", "Zuletzt"};
        for (int i = 0; i < names.length; i++) {
            int which = i;
            View f = folder(names[i], which);
            f.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                host.folder(which);
            });
            dock.addView(f, lp(0, dp(120), 1f, i == 0 ? 0 : dp(6), 0, i == names.length - 1 ? 0 : dp(6), 0));
        }
        col.addView(dock, lp(-1, -2, 0, 0, dp(12), 0, 0));
        locate();
        return col;
    }

    /* ---------------- folders ---------------- */

    private final java.util.List<FolderView> folders = new java.util.ArrayList<>();

    private View folder(String name, int which) {
        LinearLayout f = new LinearLayout(ctx);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(dp(16), dp(12), dp(16), dp(12));
        f.setBackground(box());
        FolderView icons = new FolderView(which);
        folders.add(icons);
        f.addView(icons, new LinearLayout.LayoutParams(dp(84), dp(84)));
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), 0, 0, 0);
        TextView small = new TextView(ctx);
        small.setText("— ORDNER");
        small.setTextColor(ink3);
        small.setTextSize(10);
        small.setTypeface(Typeface.MONOSPACE);
        small.setLetterSpacing(0.2f);
        t.addView(small);
        TextView big = new TextView(ctx);
        big.setText(name);
        big.setTextColor(ink);
        big.setTextSize(22);
        big.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        big.setLetterSpacing(-0.03f);
        t.addView(big);
        f.addView(t);
        return f;
    }

    /** The folders' little icons changed (new recent apps). */
    void refreshFolders() {
        for (FolderView f : folders) f.invalidate();
    }

    private final class FolderView extends View {
        private final int which;

        FolderView(int which) {
            super(ctx);
            this.which = which;
        }

        @Override
        protected void onDraw(Canvas c) {
            Drawable[] icons = host.folderIcons(which);
            float w = getWidth(), cell = w / 2f, pad = dp(8);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor((rust & 0x00FFFFFF) | 0x14000000);
            c.drawRoundRect(0, 0, w, getHeight(), dp(18), dp(18), p);
            for (int i = 0; i < Math.min(4, icons.length); i++) {
                Drawable d = icons[i];
                if (d == null) continue;
                int x = (int) ((i % 2) * cell + pad), y = (int) ((i / 2) * cell + pad), s = (int) (cell - 2 * pad);
                d.setBounds(x, y, x + s, y + s);
                d.draw(c);
            }
        }
    }

    /* ---------------- location ---------------- */

    private Weather weather;
    private Live live;
    private LocationListener updates;

    private static final double BERN_LAT = 46.948, BERN_LON = 7.447;

    @SuppressWarnings("MissingPermission")
    private void locate() {
        here = RouteLearner.lastLocation(ctx);
        lookup();
        if (!RouteLearner.allowed(ctx)) return;
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        if (lm == null) return;
        updates = l -> {
            boolean moved = here == null || l.distanceTo(here) > 30;
            here = l;
            if (live != null) live.invalidate();
            if (moved) lookup();
        };
        try {
            for (String pr : lm.getProviders(true)) {
                if (LocationManager.PASSIVE_PROVIDER.equals(pr)) continue;
                lm.requestLocationUpdates(pr, 15_000, 10, updates, Looper.getMainLooper());
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Stops the live updates (home paused). */
    void pause() {
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        if (lm != null && updates != null) {
            try {
                lm.removeUpdates(updates);
            } catch (RuntimeException ignored) {
            }
        }
        updates = null;
    }

    void resume() {
        if (updates == null) locate();
        if (weather != null && System.currentTimeMillis() - weather.at > 30 * 60_000L) weather.fetch();
    }

    /** Place name and nearest stop for the current position (background thread). */
    private void lookup() {
        double lat = here != null ? here.getLatitude() : BERN_LAT, lon = here != null ? here.getLongitude() : BERN_LON;
        new Thread(() -> {
            String name = "";
            try {
                @SuppressWarnings("deprecation")
                java.util.List<android.location.Address> a = new android.location.Geocoder(ctx, Locale.GERMAN).getFromLocation(lat, lon, 1);
                if (a != null && !a.isEmpty()) {
                    android.location.Address ad = a.get(0);
                    String street = ad.getThoroughfare() != null ? ad.getThoroughfare() + (ad.getSubThoroughfare() != null ? " " + ad.getSubThoroughfare() : "") : "";
                    String town = ad.getLocality() != null ? ad.getLocality() : "";
                    name = street.isEmpty() ? town : street + (town.isEmpty() ? "" : ", " + town);
                }
            } catch (Exception ignored) {
            }
            String st = "";
            float dist = -1, bearing = 0;
            try {
                JSONArray s = RouteLearner.get("locations?type=station&x=" + lat + "&y=" + lon).optJSONArray("stations");
                for (int i = 0; s != null && i < s.length(); i++) {
                    JSONObject o = s.getJSONObject(i);
                    JSONObject co = o.optJSONObject("coordinate");
                    if (o.optString("id").isEmpty() || co == null) continue;
                    float[] r = new float[2];
                    Location.distanceBetween(lat, lon, co.optDouble("x"), co.optDouble("y"), r);
                    st = o.optString("name");
                    dist = r[0];
                    bearing = r[1];
                    break;
                }
            } catch (Exception ignored) {
            }
            String fn = name, fs = st;
            float fd = dist, fb = bearing;
            main.post(() -> {
                if (!fn.isEmpty()) place = fn;
                stop = fs;
                stopDist = fd;
                stopBearing = fb;
                if (live != null) live.invalidate();
                if (weather != null) weather.invalidate();
            });
        }, "arc-dash-place").start();
        if (weather != null) weather.fetch();
    }

    /* ---------------- weather ---------------- */

    private final class Weather extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        long at;
        private float temp = Float.NaN, wind;
        private int code = -1;
        private float[] hours = new float[0], rain = new float[0];
        private int firstHour;

        Weather() {
            super(ctx);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            fetch();
        }

        void fetch() {
            double lat = here != null ? here.getLatitude() : BERN_LAT, lon = here != null ? here.getLongitude() : BERN_LON;
            new Thread(() -> {
                try {
                    URL u = new URL(String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,wind_speed_10m&hourly=temperature_2m,precipitation_probability"
                        + "&forecast_hours=13&timezone=auto", lat, lon));
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[8192];
                        for (int n; (n = in.read(buf)) != -1; ) bo.write(buf, 0, n);
                    }
                    JSONObject o = new JSONObject(bo.toString("UTF-8"));
                    JSONObject cur = o.getJSONObject("current");
                    JSONObject h = o.getJSONObject("hourly");
                    JSONArray tt = h.getJSONArray("temperature_2m"), pp = h.optJSONArray("precipitation_probability");
                    float[] hs = new float[tt.length()], rs = new float[tt.length()];
                    for (int i = 0; i < tt.length(); i++) {
                        hs[i] = (float) tt.optDouble(i);
                        rs[i] = pp == null ? 0 : (float) pp.optDouble(i, 0);
                    }
                    String first = h.getJSONArray("time").optString(0);
                    int fh = first.length() >= 13 ? Integer.parseInt(first.substring(11, 13)) : 0;
                    main.post(() -> {
                        temp = (float) cur.optDouble("temperature_2m");
                        wind = (float) cur.optDouble("wind_speed_10m");
                        code = cur.optInt("weather_code", -1);
                        hours = hs;
                        rain = rs;
                        firstHour = fh;
                        at = System.currentTimeMillis();
                        invalidate();
                    });
                } catch (Exception e) {
                    DiagLog.log("weather: " + e.getMessage());
                }
            }, "arc-weather").start();
        }

        private String words(int c) {
            if (c == 0) return "Klar";
            if (c <= 2) return "Leicht bewölkt";
            if (c == 3) return "Bedeckt";
            if (c == 45 || c == 48) return "Nebel";
            if (c >= 51 && c <= 57) return "Niesel";
            if (c >= 61 && c <= 67 || c >= 80 && c <= 82) return "Regen";
            if (c >= 71 && c <= 77 || c == 85 || c == 86) return "Schnee";
            if (c >= 95) return "Gewitter";
            return "–";
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), t = SystemClock.uptimeMillis() / 1000f;
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(10));
            p.setColor(ink3);
            p.setLetterSpacing(0.2f);
            c.drawText("— WETTER" + (place.isEmpty() ? "" : " · " + place.toUpperCase(Locale.GERMAN)), dp(20), dp(30), p);
            p.setLetterSpacing(0);
            if (Float.isNaN(temp)) {
                p.setTextSize(dp(16));
                p.setColor(ink3);
                c.drawText("lädt …", dp(20), dp(80), p);
                postInvalidateDelayed(1000);
                return;
            }
            // Icon, big temperature, words.
            icon(c, dp(58), dp(98), dp(34), t);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(dp(56));
            p.setColor(ink);
            p.setLetterSpacing(-0.05f);
            String tx = Math.round(temp) + "°";
            c.drawText(tx, dp(108), dp(118), p);
            float tw = p.measureText(tx);
            p.setLetterSpacing(0);
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextSize(dp(16));
            p.setColor(ink2);
            c.drawText(words(code), dp(116) + tw, dp(94), p);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(11));
            p.setColor(ink3);
            c.drawText("WIND " + Math.round(wind) + " KM/H", dp(116) + tw, dp(116), p);
            // The next 12 hours: temperature line, rain chance as bars.
            if (hours.length > 1) {
                float l = dp(20), r = w - dp(20), top = h - dp(70), bot = h - dp(26);
                float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
                for (float v : hours) {
                    mn = Math.min(mn, v);
                    mx = Math.max(mx, v);
                }
                if (mx - mn < 2) mx = mn + 2;
                int n = hours.length;
                for (int i = 0; i < n; i++) {
                    float x = l + (r - l) * i / (n - 1);
                    if (rain[i] > 5) {
                        p.setColor(0x553C8DFF);
                        float bh = (bot - top) * rain[i] / 100f;
                        c.drawRoundRect(x - dp(5), bot - bh, x + dp(5), bot, dp(3), dp(3), p);
                    }
                    if (i % 3 == 0) {
                        p.setColor(ink3);
                        p.setTextSize(dp(9));
                        p.setTextAlign(Paint.Align.CENTER);
                        c.drawText(String.format(Locale.ROOT, "%02d", (firstHour + i) % 24), x, h - dp(10), p);
                        p.setTextAlign(Paint.Align.LEFT);
                    }
                }
                path.reset();
                for (int i = 0; i < n; i++) {
                    float x = l + (r - l) * i / (n - 1), y = bot - (hours[i] - mn) / (mx - mn) * (bot - top);
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2.5f));
                p.setColor(rust);
                c.drawPath(path, p);
            }
            postInvalidateDelayed(50);
        }

        /** Hand-drawn weather: sun with turning rays, cloud, rain drops, snow, lightning, fog. */
        private void icon(Canvas c, float x, float y, float r, float t) {
            boolean sun = code <= 2, cloud = code >= 1, rainy = code >= 51 && code <= 67 || code >= 80 && code <= 82,
                snow = code >= 71 && code <= 77 || code == 85 || code == 86, storm = code >= 95, fog = code == 45 || code == 48;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            if (sun) {
                float sx = cloud ? x - r * 0.3f : x, sy = cloud ? y - r * 0.35f : y;
                p.setColor(0xFFFFC400);
                c.drawCircle(sx, sy, r * 0.42f, p);
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4 + t * 0.4;
                    c.drawLine(sx + (float) Math.cos(a) * r * 0.58f, sy + (float) Math.sin(a) * r * 0.58f,
                        sx + (float) Math.cos(a) * r * 0.8f, sy + (float) Math.sin(a) * r * 0.8f, p);
                }
            }
            if (cloud || fog) {
                float dx = (float) Math.sin(t * 0.6) * dp(3);
                p.setStyle(Paint.Style.FILL);
                p.setColor(card);
                c.drawCircle(x - r * 0.35f + dx, y + r * 0.1f, r * 0.38f, p);
                c.drawCircle(x + r * 0.1f + dx, y - r * 0.12f, r * 0.5f, p);
                c.drawCircle(x + r * 0.55f + dx, y + r * 0.12f, r * 0.35f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(ink2);
                path.reset();
                path.moveTo(x - r * 0.7f + dx, y + r * 0.45f);
                path.lineTo(x + r * 0.85f + dx, y + r * 0.45f);
                c.drawPath(path, p);
                c.drawArc(x - r * 0.73f + dx, y - r * 0.28f, x + r * 0.03f + dx, y + r * 0.48f, 90, 180, false, p);
                c.drawArc(x - r * 0.4f + dx, y - r * 0.62f, x + r * 0.6f + dx, y + r * 0.38f, 180, 180, false, p);
                c.drawArc(x + r * 0.2f + dx, y - r * 0.23f, x + r * 0.9f + dx, y + r * 0.47f, 270, 180, false, p);
            }
            if (rainy || snow) {
                for (int k = 0; k < 3; k++) {
                    float px = x - r * 0.35f + k * r * 0.35f, fall = (t * 0.9f + k * 0.33f) % 1f, py = y + r * 0.6f + fall * r * 0.6f;
                    if (snow) {
                        p.setColor(0xFF9AD0FF);
                        c.drawCircle(px, py, dp(2.5f), p);
                    } else {
                        p.setColor(0xFF3C8DFF);
                        c.drawLine(px, py, px - dp(3), py + dp(8), p);
                    }
                }
            }
            if (storm) {
                p.setColor(0xFFFFC400);
                path.reset();
                path.moveTo(x + r * 0.1f, y + r * 0.5f);
                path.lineTo(x - r * 0.15f, y + r * 0.95f);
                path.lineTo(x + r * 0.12f, y + r * 0.95f);
                path.lineTo(x - r * 0.1f, y + r * 1.35f);
                c.drawPath(path, p);
            }
            if (fog) {
                p.setColor(ink3);
                for (int k = 0; k < 3; k++) c.drawLine(x - r * 0.8f, y + r * (0.7f + k * 0.25f), x + r * 0.8f, y + r * (0.7f + k * 0.25f), p);
            }
        }
    }

    /* ---------------- live location ---------------- */

    private final class Live extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Live() {
            super(ctx);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            long now = SystemClock.uptimeMillis();
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(10));
            p.setColor(ink3);
            p.setLetterSpacing(0.2f);
            c.drawText("— LIVE-STANDORT", dp(20), dp(30), p);
            p.setLetterSpacing(0);
            // Radar on the right: you in the middle (pulsing), the nearest stop where it is.
            float rr = Math.min(h * 0.38f, w * 0.2f), cx = w - rr - dp(24), cy = h / 2f + dp(6);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(line);
            for (int k = 1; k <= 3; k++) c.drawCircle(cx, cy, rr * k / 3, p);
            c.drawLine(cx - rr, cy, cx + rr, cy, p);
            c.drawLine(cx, cy - rr, cx, cy + rr, p);
            float pulse = (now % 1800) / 1800f;
            p.setColor((rust & 0x00FFFFFF) | ((int) (200 * (1 - pulse)) << 24));
            c.drawCircle(cx, cy, dp(6) + rr * 0.6f * pulse, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(rust);
            c.drawCircle(cx, cy, dp(6), p);
            if (stopDist >= 0) {
                float d = Math.min(1f, stopDist / 600f) * rr;
                double a = Math.toRadians(stopBearing - 90);
                float sx = cx + (float) Math.cos(a) * d, sy = cy + (float) Math.sin(a) * d;
                p.setColor(0xFF3C8DFF);
                c.drawRoundRect(sx - dp(6), sy - dp(6), sx + dp(6), sy + dp(6), dp(3), dp(3), p);
            }
            // Text on the left.
            float tx = dp(20), maxW = cx - rr - tx - dp(16);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(dp(22));
            p.setColor(ink);
            String pl = place.isEmpty() ? (here == null ? "Standort unbekannt" : "…") : place;
            c.drawText(ellipsize(pl, maxW), tx, dp(70), p);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(11));
            p.setColor(ink2);
            if (here != null) {
                c.drawText(String.format(Locale.ROOT, "%.5f, %.5f", here.getLatitude(), here.getLongitude()), tx, dp(96), p);
                c.drawText("± " + Math.round(here.getAccuracy()) + " M · " + ago(here.getTime()), tx, dp(116), p);
            } else {
                c.drawText(RouteLearner.allowed(ctx) ? "WARTE AUF GPS …" : "TIPPEN = STANDORT ERLAUBEN", tx, dp(96), p);
            }
            if (!stop.isEmpty()) {
                p.setColor(0xFF3C8DFF);
                c.drawRoundRect(tx, dp(140), tx + dp(12), dp(152), dp(3), dp(3), p);
                p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                p.setTextSize(dp(15));
                p.setColor(ink);
                c.drawText(ellipsize(stop, maxW - dp(20)), tx + dp(20), dp(152), p);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(11));
                p.setColor(ink3);
                c.drawText("NÄCHSTE HALTESTELLE · " + Math.round(stopDist) + " M", tx + dp(20), dp(172), p);
            }
            postInvalidateDelayed(40);
        }

        private String ago(long t) {
            long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
            return s < 60 ? "GERADE" : s < 3600 ? "VOR " + s / 60 + " MIN" : "VOR " + s / 3600 + " H";
        }

        private String ellipsize(String s, float max) {
            if (p.measureText(s) <= max) return s;
            while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
            return s + "…";
        }
    }

    /* ---------------- clock ---------------- */

    private final class Clock extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Clock() {
            super(ctx);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2 - dp(10), r = Math.min(w, h) * 0.4f;
            Calendar cal = Calendar.getInstance();
            float ms = cal.get(Calendar.MILLISECOND) / 1000f, sec = cal.get(Calendar.SECOND) + ms,
                min = cal.get(Calendar.MINUTE) + sec / 60f, hr = cal.get(Calendar.HOUR) + min / 60f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(line);
            c.drawCircle(cx, cy, r, p);
            for (int k = 0; k < 60; k++) {
                double a = Math.PI * 2 * k / 60 - Math.PI / 2;
                float in = k % 5 == 0 ? r * 0.86f : r * 0.93f;
                p.setStrokeWidth(k % 5 == 0 ? dp(3) : dp(1));
                p.setColor(k % 5 == 0 ? ink : ink3);
                c.drawLine(cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in,
                    cx + (float) Math.cos(a) * r * 0.98f, cy + (float) Math.sin(a) * r * 0.98f, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(r * 0.16f);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(ink);
            for (int k = 1; k <= 12; k++) {
                double a = Math.PI * 2 * k / 12 - Math.PI / 2;
                c.drawText(String.valueOf(k), cx + (float) Math.cos(a) * r * 0.7f, cy + (float) Math.sin(a) * r * 0.7f + r * 0.055f, p);
            }
            hand(c, cx, cy, hr / 12f, r * 0.5f, dp(7), ink);
            hand(c, cx, cy, min / 60f, r * 0.78f, dp(4.5f), ink);
            hand(c, cx, cy, sec / 60f, r * 0.86f, dp(2), rust);
            p.setStyle(Paint.Style.FILL);
            p.setColor(rust);
            c.drawCircle(cx, cy, dp(6), p);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(11));
            p.setColor(ink3);
            c.drawText(String.format(Locale.GERMAN, "%1$tA · %1$te. %1$tB", cal).toUpperCase(Locale.GERMAN), cx, h - dp(22), p);
            p.setTextAlign(Paint.Align.LEFT);
            postInvalidateOnAnimation();
        }

        private void hand(Canvas c, float cx, float cy, float f, float len, float width, int col) {
            double a = Math.PI * 2 * f - Math.PI / 2;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(width);
            p.setColor(col);
            c.drawLine(cx - (float) Math.cos(a) * len * 0.15f, cy - (float) Math.sin(a) * len * 0.15f,
                cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, p);
        }
    }

    /* ---------------- the garden ---------------- */

    /**
     * Grows by itself: from 6:00 a new plant every hour, each growing through its hour; a different
     * garden every day. Butterflies by day; at night the flowers close, a moon and fireflies.
     */
    private final class Garden extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Garden() {
            super(ctx);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), t = SystemClock.uptimeMillis() / 1000f;
            Calendar cal = Calendar.getInstance();
            float hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f;
            boolean night = hour < 6.5f || hour > 20f;
            Random day = new Random(cal.get(Calendar.YEAR) * 400L + cal.get(Calendar.DAY_OF_YEAR));
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(10));
            p.setLetterSpacing(0.2f);
            p.setColor(ink3);
            int grown = Math.max(0, Math.min(14, (int) Math.floor(hour - 6) + 1));
            c.drawText("— GARTEN · " + (night ? "SCHLÄFT" : grown + " PFLANZEN HEUTE"), dp(20), dp(26), p);
            p.setLetterSpacing(0);
            float ground = h - dp(20);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0x804A7A3A);
            path.reset();
            path.moveTo(dp(16), ground);
            for (float x = dp(16); x < w - dp(16); x += dp(20)) path.quadTo(x + dp(10), ground - dp(3), x + dp(20), ground);
            c.drawPath(path, p);
            if (night) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFF4E9B0);
                c.drawCircle(w - dp(60), dp(50), dp(16), p);
                p.setColor(card);
                c.drawCircle(w - dp(52), dp(44), dp(14), p);
                for (int k = 0; k < 8; k++) {
                    float fx = (float) (w * ((k * 0.137 + Math.sin(t * 0.3 + k) * 0.05 + 1) % 1)), fy = h * 0.4f + (float) Math.sin(t * 0.8 + k * 2) * h * 0.2f;
                    p.setColor(((int) (120 + 120 * Math.sin(t * 3 + k)) << 24) | 0xE8F27A);
                    c.drawCircle(fx, fy, dp(2.5f), p);
                }
            }
            int slots = 14;
            for (int i = 0; i < slots; i++) {
                int kind = day.nextInt(4);
                int col = new int[]{rust, 0xFFFFC400, 0xFFA77BFF, 0xFF3C8DFF, 0xFFF29A1F}[day.nextInt(5)];
                float tall = h * (0.35f + day.nextFloat() * 0.35f);
                if (i >= grown) continue;
                float growth = i == grown - 1 ? Math.min(1f, Math.max(0.15f, hour - 6 - i)) : 1f;
                float x = dp(36) + (w - dp(72)) * i / (slots - 1);
                float sway = (float) Math.sin(t * 1.2 + i * 0.8) * dp(5) * growth;
                float topY = ground - tall * growth;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2.2f));
                p.setColor(0xFF2BBF6A);
                path.reset();
                path.moveTo(x, ground);
                path.quadTo(x + sway * 0.4f, (ground + topY) / 2, x + sway, topY);
                c.drawPath(path, p);
                if (growth > 0.4f) {
                    path.reset();
                    float ly = ground - tall * growth * 0.4f;
                    path.moveTo(x + sway * 0.15f, ly);
                    path.quadTo(x + dp(14), ly - dp(4), x + dp(12) + sway * 0.2f, ly - dp(12));
                    c.drawPath(path, p);
                }
                if (growth < 0.6f) continue;           // still a sprout
                float hx = x + sway, hy = topY, bloom = night ? 0.45f : Math.min(1f, (growth - 0.6f) / 0.4f);
                p.setColor(col);
                switch (kind) {
                    case 0:                            // daisy
                        for (int k = 0; k < 7; k++) {
                            double a = k * Math.PI * 2 / 7 + t * 0.2;
                            p.setStyle(Paint.Style.STROKE);
                            c.drawOval(hx + (float) Math.cos(a) * dp(8) * bloom - dp(4), hy + (float) Math.sin(a) * dp(8) * bloom - dp(4),
                                hx + (float) Math.cos(a) * dp(8) * bloom + dp(4), hy + (float) Math.sin(a) * dp(8) * bloom + dp(4), p);
                        }
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0xFFFFC400);
                        c.drawCircle(hx, hy, dp(3.5f), p);
                        break;
                    case 1:                            // tulip
                        p.setStyle(Paint.Style.FILL);
                        path.reset();
                        path.moveTo(hx - dp(8) * bloom, hy - dp(10));
                        path.lineTo(hx - dp(3), hy - dp(4) - dp(4) * bloom);
                        path.lineTo(hx, hy - dp(12));
                        path.lineTo(hx + dp(3), hy - dp(4) - dp(4) * bloom);
                        path.lineTo(hx + dp(8) * bloom, hy - dp(10));
                        path.quadTo(hx + dp(8), hy + dp(4), hx, hy + dp(4));
                        path.quadTo(hx - dp(8), hy + dp(4), hx - dp(8) * bloom, hy - dp(10));
                        c.drawPath(path, p);
                        break;
                    case 2:                            // sunflower
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0xFFFFC400);
                        for (int k = 0; k < 12; k++) {
                            double a = k * Math.PI / 6;
                            c.drawCircle(hx + (float) Math.cos(a) * dp(9) * bloom, hy + (float) Math.sin(a) * dp(9) * bloom, dp(4) * bloom, p);
                        }
                        p.setColor(0xFF6B3E1E);
                        c.drawCircle(hx, hy, dp(6), p);
                        break;
                    default:                           // a tuft of grass with seeds
                        p.setStyle(Paint.Style.STROKE);
                        p.setColor(0xFF4A9A3A);
                        for (int k = -2; k <= 2; k++) c.drawLine(hx, hy + dp(14), hx + k * dp(5) + sway * 0.3f, hy - dp(4) - Math.abs(k) * dp(2), p);
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(col);
                        c.drawCircle(hx, hy - dp(6), dp(2.5f), p);
                        break;
                }
            }
            if (!night && grown > 2) {
                // Two butterflies drifting over it.
                for (int k = 0; k < 2; k++) {
                    float bx = (float) ((t * (18 + k * 9) + k * 300) % (w + 60)) - 30, by = h * 0.35f + (float) Math.sin(t * 1.7 + k) * h * 0.15f;
                    float flap = (float) Math.abs(Math.sin(t * 9 + k)) * dp(6) + dp(2);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(k == 0 ? 0xCCF29A1F : 0xCCA77BFF);
                    c.drawOval(bx - flap - dp(4), by - dp(5), bx, by + dp(3), p);
                    c.drawOval(bx, by - dp(5), bx + flap + dp(4), by + dp(3), p);
                    p.setColor(ink);
                    c.drawRect(bx - dp(1), by - dp(4), bx + dp(1), by + dp(4), p);
                }
            }
            postInvalidateDelayed(40);
        }
    }

    static boolean wanted(Context c) {
        return c.getResources().getConfiguration().smallestScreenWidthDp >= 600
            && c.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("dashboard", true);
    }
}
