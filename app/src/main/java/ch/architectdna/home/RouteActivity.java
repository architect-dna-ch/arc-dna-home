package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routen: your usual public-transport routes in Switzerland, learned on the phone, on a map ARC-DNA
 * draws itself from the timetable data (transport.opendata.ch): stations around you, the real course
 * of each of your routes stop by stop, and the trains on them right now, placed by the timetable.
 * Under the map: each route with its next connections (time, platform, delay, duration), and a search.
 */
public class RouteActivity extends Activity {

    private static final int REQ_LOC = 1;
    private static final int[] ROUTE_COL = {0xFFD9472F, 0xFFE8B931, 0xFF3C8CFF, 0xFF2BD96B, 0xFFA77BFF};
    private final Handler main = new Handler(Looper.getMainLooper());
    private int bg = 0xFF0E0E0D, card = 0xFF1A1A18, line = 0xFF2C2C2A, ink = 0xFFF4F2EC, ink2 = 0xFFA5A5A0,
        ink3 = 0xFF6F6E69, rust = 0xFFD9472F, green = 0xFF2BD96B;

    /** A stop on a journey (with its timetable times, seconds), or a station on the map. */
    static final class Stop {
        String name;
        double lat, lon;
        long arr, dep;
    }

    static final class Conn {
        String fromName = "", toName = "";
        long dep, arr;
        String platform = "", product = "", duration = "";
        int delay, transfers;
        final List<List<Stop>> legs = new ArrayList<>();
    }

    private List<RouteLearner.Route> routes = new ArrayList<>();
    private final Map<String, List<Conn>> conns = new HashMap<>();
    private final List<Stop> stations = new ArrayList<>();
    private String selected;
    private double meLat = Double.NaN, meLon;
    private MapView map;
    private LinearLayout list, results;
    private EditText fromE, toE;
    private TextView hint;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        themeColours();
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(14), dp(18), dp(28));
        sv.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dash = new View(this);
        dash.setBackgroundColor(rust);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(24), dp(2));
        dl.rightMargin = dp(12);
        head.addView(dash, dl);
        TextView name = mono(11, ink3);
        name.setLetterSpacing(0.24f);
        name.setText("ROUTEN · ÖV SCHWEIZ");
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        map = new MapView();
        map.setBackground(box(bg == 0xFFFAFAF8 ? 0xFFF3F2EE : 0xFF121211, line, 18));
        map.setClipToOutline(true);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, dp(340));
        ml.topMargin = dp(14);
        col.addView(map, ml);

        // Search.
        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(14);
        col.addView(search, sl);
        fromE = field("Von");
        toE = field("Nach");
        search.addView(fromE, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView swap = text(18, ink2, true);
        swap.setText("⇄");
        swap.setGravity(Gravity.CENTER);
        swap.setOnClickListener(v -> {
            String f = fromE.getText().toString();
            fromE.setText(toE.getText());
            toE.setText(f);
        });
        search.addView(swap, new LinearLayout.LayoutParams(dp(36), dp(48)));
        search.addView(toE, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView go = text(18, 0xFFFFFFFF, true);
        go.setText("→");
        go.setGravity(Gravity.CENTER);
        go.setBackground(box(rust, rust, 14));
        go.setOnClickListener(v -> search(false));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(48), dp(48));
        gl.leftMargin = dp(8);
        search.addView(go, gl);
        toE.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        toE.setOnEditorActionListener((v, id, e) -> {
            search(false);
            return true;
        });
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results);

        TextView rh = mono(11, ink3);
        rh.setLetterSpacing(0.22f);
        rh.setText("— DEINE STRECKEN");
        rh.setPadding(0, dp(22), 0, dp(8));
        col.addView(rh);
        hint = text(14, ink3, false);
        col.addView(hint);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        setContentView(root);
        barIcons();
        if (!RouteLearner.allowed(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
        }
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOC) {
            RouteLearner.note(this);
            load();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            map.invalidate();          // live trains move with the clock
            main.postDelayed(this, 1000);
        }
    };

    /* ---------------- data ---------------- */

    private void load() {
        routes = RouteLearner.routes(this);
        Location l = RouteLearner.lastLocation(this);
        if (l != null) {
            meLat = l.getLatitude();
            meLon = l.getLongitude();
        }
        map.centre(Double.isNaN(meLat) ? 46.9488 : meLat, Double.isNaN(meLat) ? 7.4391 : meLon);       // else Bern
        paintRoutes();
        new Thread(() -> {
            // Stations around you (or Bern) for the map, and the nearest one as "Von".
            try {
                double la = Double.isNaN(meLat) ? 46.9488 : meLat, lo = Double.isNaN(meLat) ? 7.4391 : meLon;
                JSONArray st = RouteLearner.get("locations?type=station&x=" + la + "&y=" + lo).optJSONArray("stations");
                List<Stop> got = new ArrayList<>();
                for (int i = 0; st != null && i < st.length(); i++) {
                    Stop s = stop(st.getJSONObject(i));
                    if (s != null) got.add(s);
                }
                main.post(() -> {
                    stations.clear();
                    stations.addAll(got);
                    if (fromE.getText().length() == 0 && !got.isEmpty()) fromE.setText(got.get(0).name);
                    if (selected == null) map.fitStations();
                    map.invalidate();
                });
            } catch (Exception e) {
                main.post(() -> hint.setText("Fahrplan nicht erreichbar: " + e.getMessage()));
            }
            // Next connections for each of your routes.
            for (RouteLearner.Route r : new ArrayList<>(routes)) {
                List<Conn> c = connections(r.from, r.to, 4);
                main.post(() -> {
                    if (c != null) conns.put(r.key(), c);
                    paintRoutes();
                    map.invalidate();
                });
            }
        }, "arc-routes").start();
    }

    private static Stop stop(JSONObject s) {
        // Only real stops (they have a timetable id); the nearby search also returns addresses and places.
        String id = s.optString("id", "");
        if (id.isEmpty() || "null".equals(id)) return null;
        JSONObject co = s.optJSONObject("coordinate");
        if (co == null || co.isNull("x")) return null;
        Stop st = new Stop();
        st.name = s.optString("name");
        st.lat = co.optDouble("x");
        st.lon = co.optDouble("y");
        return st;
    }

    /** Connections with every stop of every leg (for the map and the live trains). Background thread. */
    private List<Conn> connections(String from, String to, int limit) {
        try {
            JSONArray a = RouteLearner.get("connections?limit=" + limit + "&from=" + RouteLearner.enc(from) + "&to=" + RouteLearner.enc(to))
                .optJSONArray("connections");
            List<Conn> out = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Conn c = new Conn();
                JSONObject f = o.optJSONObject("from"), t = o.optJSONObject("to");
                if (f == null || t == null) continue;
                c.dep = f.optLong("departureTimestamp");
                JSONObject fs = f.optJSONObject("station"), ts = t.optJSONObject("station");
                if (fs != null) c.fromName = fs.optString("name", "");
                if (ts != null) c.toName = ts.optString("name", "");
                c.arr = t.optLong("arrivalTimestamp");
                c.platform = f.optString("platform", "");
                if ("null".equals(c.platform)) c.platform = "";
                c.delay = f.isNull("delay") ? 0 : f.optInt("delay");
                c.transfers = o.optInt("transfers");
                String d = o.optString("duration", "");          // "00d00:19:00"
                if (d.length() >= 8) c.duration = d.substring(d.length() - 8, d.length() - 3).replaceFirst("^00:", "0:");
                JSONArray pr = o.optJSONArray("products");
                if (pr != null && pr.length() > 0) c.product = pr.optString(0);
                JSONArray secs = o.optJSONArray("sections");
                for (int s = 0; secs != null && s < secs.length(); s++) {
                    JSONObject j = secs.getJSONObject(s).optJSONObject("journey");
                    if (j == null) continue;      // a walk
                    if (c.legs.isEmpty()) {
                        // "B 19", "T 9", "IR 15": the line as it's signed, from the first ride.
                        String cat = j.optString("category", ""), num = j.optString("number", "");
                        if ("null".equals(cat)) cat = "";
                        if ("null".equals(num)) num = "";
                        String sign = (cat + " " + num).trim();
                        if (!sign.isEmpty()) c.product = sign;
                    }
                    JSONArray pass = j.optJSONArray("passList");
                    List<Stop> leg = new ArrayList<>();
                    for (int k = 0; pass != null && k < pass.length(); k++) {
                        JSONObject ps = pass.getJSONObject(k);
                        JSONObject stn = ps.optJSONObject("station");
                        Stop st = stn == null ? null : stop(stn);
                        if (st == null) continue;
                        st.arr = ps.isNull("arrivalTimestamp") ? 0 : ps.optLong("arrivalTimestamp");
                        st.dep = ps.isNull("departureTimestamp") ? 0 : ps.optLong("departureTimestamp");
                        leg.add(st);
                    }
                    if (leg.size() > 1) c.legs.add(leg);
                }
                out.add(c);
            }
            return out;
        } catch (Exception e) {
            DiagLog.log("routes: connections failed: " + e.getMessage());
            return null;
        }
    }

    private void search(boolean pin) {
        String f = fromE.getText().toString().trim(), t = toE.getText().toString().trim();
        if (f.isEmpty() || t.isEmpty()) return;
        android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(toE.getWindowToken(), 0);
        results.removeAllViews();
        TextView wait = mono(11, ink3);
        wait.setText("SUCHE …");
        wait.setPadding(0, dp(12), 0, 0);
        results.addView(wait);
        new Thread(() -> {
            List<Conn> c = connections(f, t, 5);
            main.post(() -> {
                results.removeAllViews();
                if (c == null || c.isEmpty()) {
                    TextView none = text(14, ink3, false);
                    none.setText("Keine Verbindung gefunden.");
                    none.setPadding(0, dp(12), 0, 0);
                    results.addView(none);
                    return;
                }
                // Under the timetable's own names (not what was typed, not a street address).
                String from = c.get(0).fromName.isEmpty() ? f : c.get(0).fromName, to = c.get(0).toName.isEmpty() ? t : c.get(0).toName;
                RouteLearner.searched(this, from, to, pin);
                String key = from + "→" + to;
                conns.put(key, c);
                selected = key;
                RouteLearner.Route r = new RouteLearner.Route();
                r.from = from;
                r.to = to;
                r.count = 1;
                results.addView(routeCard(r, true));
                map.fit(c.get(0));
            });
        }).start();
    }

    /* ---------------- list ---------------- */

    private void paintRoutes() {
        list.removeAllViews();
        hint.setText(routes.isEmpty()
            ? (RouteLearner.allowed(this)
            ? "ARC-DNA lernt ab jetzt deine Wege: jedes Mal, wenn du am Bahnhof das Home öffnest. Bis dahin: oben suchen – gesuchte Strecken zählen auch."
            : "Mit Standort lernt ARC-DNA deine Wege (nur auf dem Handy). Ohne: oben suchen – gesuchte Strecken zählen auch.")
            : "");
        int i = 0;
        for (RouteLearner.Route r : routes) {
            if (i++ >= 8) break;
            list.addView(routeCard(r, false));
        }
    }

    private View routeCard(RouteLearner.Route r, boolean fromSearch) {
        int idx = Math.max(0, routes.indexOf(r));
        int col = fromSearch ? rust : ROUTE_COL[idx % ROUTE_COL.length];
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        boolean sel = r.key().equals(selected);
        c.setBackground(box(card, sel ? col : line, 16));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(col);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.rightMargin = dp(10);
        top.addView(dot, dl);
        TextView t = text(16, ink, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(r.from + " → " + r.to);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (fromSearch) {
            TextView pin = mono(11, rust);
            pin.setText("📌 MERKEN");
            pin.setOnClickListener(v -> {
                RouteLearner.searched(this, r.from, r.to, true);
                pin.setText("✓ GEMERKT");
                routes = RouteLearner.routes(this);
                paintRoutes();
            });
            top.addView(pin);
        }
        c.addView(top);
        if (!fromSearch) {
            TextView meta = mono(10, ink3);
            int h = r.usualHour();
            meta.setText((r.pinned ? "📌 · " : "") + r.count + "× GEFAHREN" + (h >= 0 ? " · MEIST UM " + h + " UHR" : ""));
            meta.setPadding(dp(20), dp(4), 0, 0);
            c.addView(meta);
        }
        List<Conn> cs = conns.get(r.key());
        if (cs == null) {
            TextView w = mono(10, ink3);
            w.setText("LADE VERBINDUNGEN …");
            w.setPadding(dp(20), dp(8), 0, 0);
            c.addView(w);
        } else {
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.GERMAN);
            int n = 0;
            for (Conn x : cs) {
                if (x.dep * 1000 < System.currentTimeMillis() - 60_000) continue;
                if (n++ >= 3) break;
                LinearLayout row = new LinearLayout(this);
                row.setPadding(dp(20), dp(8), 0, 0);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView tm = mono(15, ink);
                tm.setText(hm.format(new Date(x.dep * 1000)));
                row.addView(tm);
                if (x.delay > 0) {
                    TextView dly = mono(11, rust);
                    dly.setText(" +" + x.delay + "'");
                    row.addView(dly);
                }
                TextView info = mono(10, ink2);
                long in = (x.dep * 1000 - System.currentTimeMillis()) / 60_000;
                info.setText("  " + (x.product.isEmpty() ? "" : x.product + " · ") + (x.platform.isEmpty() ? "" : "GL. " + x.platform + " · ")
                    + x.duration + (x.transfers > 0 ? " · " + x.transfers + "× UMST." : " · DIREKT") + (in <= 30 ? " · IN " + Math.max(0, in) + "'" : ""));
                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
                c.addView(row);
            }
        }
        c.setOnClickListener(v -> {
            selected = r.key();
            List<Conn> x = conns.get(r.key());
            if (x != null && !x.isEmpty()) map.fit(x.get(0));
            paintRoutes();
            map.invalidate();
        });
        if (!fromSearch) {
            c.setOnLongClickListener(v -> {
                RouteLearner.hide(this, r.key());
                routes = RouteLearner.routes(this);
                paintRoutes();
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    /* ---------------- the map ARC-DNA draws itself ---------------- */

    private final class MapView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final List<android.graphics.RectF> taken = new ArrayList<>();
        private double lat0 = 46.9488, lon0 = 7.4391;
        private float scale;        // px per degree of latitude
        private float lastX, lastY;
        private boolean pinching;
        private final ScaleGestureDetector scaler = new ScaleGestureDetector(RouteActivity.this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    scale = Math.max(dp(150), Math.min(dp(60_000), scale * d.getScaleFactor()));
                    invalidate();
                    return true;
                }
            });

        MapView() {
            super(RouteActivity.this);
            scale = dp(4000);
        }

        void centre(double la, double lo) {
            lat0 = la;
            lon0 = lo;
            invalidate();
        }

        /** Zoom so you and the stops around you fill the map (they sat in a clump). */
        void fitStations() {
            if (stations.isEmpty() || getWidth() == 0) return;
            double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
            for (Stop s : stations) {
                minLa = Math.min(minLa, s.lat);
                maxLa = Math.max(maxLa, s.lat);
                minLo = Math.min(minLo, s.lon);
                maxLo = Math.max(maxLo, s.lon);
            }
            if (!Double.isNaN(meLat)) {
                minLa = Math.min(minLa, meLat);
                maxLa = Math.max(maxLa, meLat);
                minLo = Math.min(minLo, meLon);
                maxLo = Math.max(maxLo, meLon);
            }
            lat0 = (minLa + maxLa) / 2;
            lon0 = (minLo + maxLo) / 2;
            double k = Math.cos(Math.toRadians(lat0));
            float sx = (float) ((getWidth() - dp(140)) / Math.max(0.001, (maxLo - minLo) * k));
            float sy = (float) ((getHeight() - dp(80)) / Math.max(0.001, maxLa - minLa));
            scale = Math.max(dp(150), Math.min(dp(60_000), Math.min(sx, sy)));
            invalidate();
        }

        /** Zoom so a connection's whole course fits. */
        void fit(Conn c) {
            double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
            for (List<Stop> leg : c.legs) {
                for (Stop s : leg) {
                    minLa = Math.min(minLa, s.lat);
                    maxLa = Math.max(maxLa, s.lat);
                    minLo = Math.min(minLo, s.lon);
                    maxLo = Math.max(maxLo, s.lon);
                }
            }
            if (minLa > maxLa) return;
            lat0 = (minLa + maxLa) / 2;
            lon0 = (minLo + maxLo) / 2;
            double k = Math.cos(Math.toRadians(lat0));
            float sx = (float) ((getWidth() - dp(60)) / Math.max(0.002, (maxLo - minLo) * k));
            float sy = (float) ((getHeight() - dp(60)) / Math.max(0.002, maxLa - minLa));
            scale = Math.max(dp(150), Math.min(dp(60_000), Math.min(sx, sy)));
            invalidate();
        }

        private float x(double lon) {
            return getWidth() / 2f + (float) ((lon - lon0) * Math.cos(Math.toRadians(lat0)) * scale);
        }

        private float y(double lat) {
            return getHeight() / 2f - (float) ((lat - lat0) * scale);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            // Blueprint grid every 0.01° / 0.1° depending on zoom.
            double step = scale > dp(20_000) ? 0.002 : scale > dp(3000) ? 0.01 : 0.1;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(bg == 0xFFFAFAF8 ? 0xFFE6E5DF : 0xFF1C1C1A);
            double la1 = lat0 + h / 2 / scale, la2 = lat0 - h / 2 / scale;
            double lo1 = lon0 - w / 2 / scale / Math.cos(Math.toRadians(lat0)), lo2 = lon0 + w / 2 / scale / Math.cos(Math.toRadians(lat0));
            for (double la = Math.floor(la2 / step) * step; la <= la1; la += step) c.drawLine(0, y(la), w, y(la), p);
            for (double lo = Math.floor(lo1 / step) * step; lo <= lo2; lo += step) c.drawLine(x(lo), 0, x(lo), h, p);

            // Your routes: the real course stop by stop, thick for the frequent ones.
            int idx = 0;
            for (RouteLearner.Route r : routes) drawRoute(c, r.key(), ROUTE_COL[idx++ % ROUTE_COL.length], r.key().equals(selected));
            if (selected != null && !containsRoute(selected)) drawRoute(c, selected, rust, true);

            // Stations around.
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(9));
            taken.clear();
            for (Stop s : stations) {
                float sx = x(s.lon), sy = y(s.lat);
                if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) continue;
                p.setStyle(Paint.Style.FILL);
                p.setColor(ink3);
                c.drawCircle(sx, sy, dp(3), p);
                // A name only where it fits (the nearest stations first); zoom in for the rest.
                String t = s.name.replaceFirst("^Bern, ", "").toUpperCase(Locale.GERMAN);
                android.graphics.RectF box = new android.graphics.RectF(sx + dp(5), sy - dp(8), sx + dp(8) + p.measureText(t), sy + dp(5));
                boolean free = true;
                for (android.graphics.RectF o : taken) if (android.graphics.RectF.intersects(o, box)) free = false;
                if (free) {
                    taken.add(box);
                    c.drawText(t, sx + dp(6), sy + dp(3), p);
                }
            }

            // Trains running now on your routes, placed by the timetable.
            long now = System.currentTimeMillis() / 1000;
            idx = 0;
            for (RouteLearner.Route r : routes) trains(c, r.key(), ROUTE_COL[idx++ % ROUTE_COL.length], now);
            if (selected != null && !containsRoute(selected)) trains(c, selected, rust, now);

            // You.
            if (!Double.isNaN(meLat)) {
                float mx = x(meLon), my = y(meLat);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x553C8CFF);
                c.drawCircle(mx, my, dp(14), p);
                p.setColor(0xFF3C8CFF);
                c.drawCircle(mx, my, dp(6), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(0xFFFFFFFF);
                c.drawCircle(mx, my, dp(6), p);
            }
            if (routes.isEmpty() && selected == null) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(ink3);
                p.setTextSize(dp(10));
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("ZIEHEN = VERSCHIEBEN · ZWEI FINGER = ZOOM", w / 2, h - dp(14), p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }

        private boolean containsRoute(String key) {
            for (RouteLearner.Route r : routes) if (r.key().equals(key)) return true;
            return false;
        }

        private void drawRoute(Canvas c, String key, int col, boolean sel) {
            List<Conn> cs = conns.get(key);
            if (cs == null || cs.isEmpty()) return;
            Conn x = cs.get(0);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            for (List<Stop> leg : x.legs) {
                path.reset();
                for (int i = 0; i < leg.size(); i++) {
                    float sx = x(leg.get(i).lon), sy = y(leg.get(i).lat);
                    if (i == 0) path.moveTo(sx, sy);
                    else path.lineTo(sx, sy);
                }
                p.setColor((col & 0x00FFFFFF) | (sel ? 0x55000000 : 0x22000000));
                p.setStrokeWidth(dp(sel ? 12 : 8));
                c.drawPath(path, p);
                p.setColor((col & 0x00FFFFFF) | (sel ? 0xFF000000 : 0xAA000000));
                p.setStrokeWidth(dp(sel ? 4 : 2.5f));
                c.drawPath(path, p);
            }
            // Stops: start and end big with names, the ones between small.
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            for (int l = 0; l < x.legs.size(); l++) {
                List<Stop> leg = x.legs.get(l);
                for (int i = 0; i < leg.size(); i++) {
                    Stop s = leg.get(i);
                    boolean end = (l == 0 && i == 0) || (l == x.legs.size() - 1 && i == leg.size() - 1) || i == 0 || i == leg.size() - 1;
                    float sx = x(s.lon), sy = y(s.lat);
                    p.setColor(end ? 0xFFF4F2EC : col);
                    c.drawCircle(sx, sy, dp(end ? 4.5f : 2.5f), p);
                    if ((end || scale > dp(9000)) && sel) {
                        p.setTextSize(dp(end ? 11 : 9));
                        p.setColor(end ? ink : ink2);
                        c.drawText(s.name, sx + dp(7), sy - dp(5), p);
                    }
                }
            }
        }

        /** Each connection that's running now: a train between the two stops it's between, by the clock. */
        private void trains(Canvas c, String key, int col, long now) {
            List<Conn> cs = conns.get(key);
            if (cs == null) return;
            for (Conn x : cs) {
                for (List<Stop> leg : x.legs) {
                    for (int i = 0; i + 1 < leg.size(); i++) {
                        Stop a = leg.get(i), b = leg.get(i + 1);
                        long t0 = a.dep > 0 ? a.dep : a.arr, t1 = b.arr > 0 ? b.arr : b.dep;
                        if (t0 == 0 || t1 <= t0 || now < t0 || now > t1) continue;
                        float f = (now - t0) / (float) (t1 - t0);
                        float tx = x(a.lon + (b.lon - a.lon) * f), ty = y(a.lat + (b.lat - a.lat) * f);
                        p.setStyle(Paint.Style.FILL);
                        p.setColor((col & 0x00FFFFFF) | 0x66000000);
                        c.drawCircle(tx, ty, dp(11), p);
                        p.setColor(0xFFF4F2EC);
                        c.drawRoundRect(tx - dp(6), ty - dp(4), tx + dp(6), ty + dp(4), dp(2), dp(2), p);
                        p.setColor(col);
                        c.drawRect(tx - dp(6), ty - dp(1), tx + dp(6), ty + dp(1), p);
                        if (!x.product.isEmpty()) {
                            p.setTextSize(dp(9));
                            p.setColor(ink);
                            c.drawText(x.product, tx + dp(10), ty + dp(14), p);
                        }
                    }
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            scaler.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                lastX = e.getX();
                lastY = e.getY();
                pinching = false;
            } else if (a == MotionEvent.ACTION_POINTER_DOWN) {
                pinching = true;
            } else if (a == MotionEvent.ACTION_MOVE && !pinching && e.getPointerCount() == 1) {
                lon0 -= (e.getX() - lastX) / scale / Math.cos(Math.toRadians(lat0));
                lat0 += (e.getY() - lastY) / scale;
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
            } else if (a == MotionEvent.ACTION_POINTER_UP) {
                int keep = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(keep);
                lastY = e.getY(keep);
            }
            return true;
        }
    }

    /* ---------------- helpers ---------------- */

    private EditText field(String hintText) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setHintTextColor(ink3);
        e.setTextColor(ink);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setBackground(box(card, line, 14));
        e.setPadding(dp(12), 0, dp(12), 0);
        return e;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private TextView mono(float sp, int colour) {
        TextView t = text(sp, colour, false);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.1f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
    /** Day or night like the system (they were always dark). */
    private void themeColours() {
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!night) {
            bg = 0xFFFAFAF8;
            card = 0xFFFFFFFF;
            line = 0xFFE0E0D8;
            ink = 0xFF1A1A1A;
            ink2 = 0xFF5A5A58;
            ink3 = 0xFF9A9A98;
            rust = 0xFFB5271E;
            green = 0xFF1A9A4C;
        }
    }

    /** Dark bar icons on the light day background (after setContentView). */
    private void barIcons() {
        if (Build.VERSION.SDK_INT < 30 || getWindow().getInsetsController() == null) return;
        boolean light = bg != 0xFF0E0E0D && bg != 0xFF141413;
        int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(light ? bits : 0, bits);
    }

}
