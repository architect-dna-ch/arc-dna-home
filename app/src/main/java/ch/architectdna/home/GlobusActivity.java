package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Globus, the app navigator: every installed app as its icon on a globe you can turn (drag) and
 * zoom (pinch), one region per kind of app (games, music, photos, social, maps, work …), plus a WEB
 * region with your saved links. Type to search: the globe turns to the app. Tap: a card offers open /
 * app info (or open / remove for a link). Links still come from "Teilen → Globus".
 */
public class GlobusActivity extends Activity {

    private static final String[][] SEED = {
        {"VOID FREQ", "https://freq.architect-dna.ch"}, {"Einspruch", "https://einspruch.architect-dna.ch"},
        {"LIA", "https://architect-dna.ch/lia"}, {"DORA", "https://architect-dna-ch.github.io/dora/"},
        {"CV", "https://architect-dna.ch/cv"}, {"Architect-DNA", "https://architect-dna.ch"},
        {"GitHub", "https://github.com/architect-dna-ch"}};

    private SharedPreferences prefs;
    private final List<Mark> marks = new ArrayList<>();
    private boolean night;
    private int bg, card, line, ink, ink2, ink3, rust;
    private Globe globe;
    private LinearLayout sheet;
    private TextView sheetTitle, sheetUrl, count;
    private Mark selected;

    private static final class Mark {
        String title, url;
        double lat, lon;      // radians
        float sx, sy, depth;  // last projection
        /** Apps: the component to start, its region and icon. Links have none. */
        android.content.ComponentName app;
        String region = "WEB";
        android.graphics.Bitmap icon;
    }

    /** Regions: Android's app categories, in German; ARC-DNA's own apps and the links get their own. */
    private static final String[] REGIONS = {"ARC-DNA", "SPIELE", "MUSIK & AUDIO", "VIDEO", "FOTOS", "SOZIAL", "NEWS",
        "KARTEN", "ARBEIT", "HILFEN", "WEB", "WEITERE"};
    private final List<Mark> apps = new ArrayList<>();
    private final java.util.Map<String, double[]> regionCentre = new java.util.HashMap<>();

    private static String regionOf(int category) {
        switch (category) {
            case 0: return "SPIELE";
            case 1: return "MUSIK & AUDIO";
            case 2: return "VIDEO";
            case 3: return "FOTOS";
            case 4: return "SOZIAL";
            case 5: return "NEWS";
            case 6: return "KARTEN";
            case 7: return "ARBEIT";
            case 8: return "HILFEN";
            default: return "WEITERE";
        }
    }

    /** All launchable apps with icon and region (background thread). */
    private void loadApps() {
        new Thread(() -> {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<Mark> found = new ArrayList<>();
            int px = dp(40);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) && ri.activityInfo.name.equals(GlobusActivity.class.getName())) continue;
                Mark k = new Mark();
                k.title = ri.loadLabel(pm).toString();
                k.url = pkg;
                k.app = new android.content.ComponentName(pkg, ri.activityInfo.name);
                k.region = pkg.equals(getPackageName()) ? "ARC-DNA"
                    : Build.VERSION.SDK_INT >= 26 ? regionOf(ri.activityInfo.applicationInfo.category) : "WEITERE";
                try {
                    android.graphics.drawable.Drawable d = ri.loadIcon(pm);
                    android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(b);
                    d.setBounds(0, 0, px, px);
                    d.draw(cv);
                    k.icon = b;
                } catch (RuntimeException | OutOfMemoryError ignored) {
                }
                found.add(k);
            }
            java.util.Collections.sort(found, (a, b) -> a.title.compareToIgnoreCase(b.title));
            runOnUiThread(() -> {
                apps.clear();
                apps.addAll(found);
                spread();
                showSheet();
                globe.invalidate();
            });
        }).start();
    }

    /** Everything on the globe: apps and links. */
    private List<Mark> all() {
        List<Mark> out = new ArrayList<>(apps);
        out.addAll(marks);
        return out;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Our own screens slide up from the bottom for real.
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        prefs = getSharedPreferences("globus", MODE_PRIVATE);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFEDEDE8 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        // Full screen: draw behind the status / nav bars (the globe fills the whole screen),
        // the bars transparent over the light background instead of solid black.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        load();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(16));
        col.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                v.setPadding(dp(22) + i.left, dp(18) + i.top, dp(22) + i.right, dp(16) + i.bottom);
            }
            return in;
        });
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Globus · Apps"), new LinearLayout.LayoutParams(0, -2, 1f));
        count = mono(11, rust);
        head.addView(count);
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        TextView hint = mono(10, ink3);
        hint.setText("ZIEHEN = DREHEN · ZWEI FINGER = ZOOM · TIPPEN = APP");
        hint.setPadding(0, dp(10), 0, 0);
        col.addView(hint);

        globe = new Globe();
        col.addView(globe, new LinearLayout.LayoutParams(-1, 0, 1f));

        // The card for a tapped point.
        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(box(card, line, 8));
        sheet.setPadding(dp(16), dp(14), dp(16), dp(14));
        sheetTitle = text(19, ink, true);
        sheet.addView(sheetTitle);
        sheetUrl = mono(11, ink3);
        sheetUrl.setPadding(0, dp(4), 0, dp(12));
        sheet.addView(sheetUrl);
        LinearLayout actions = new LinearLayout(this);
        TextView open = chip("ÖFFNEN →", rust);
        open.setOnClickListener(v -> openSelected());
        actions.addView(open);
        remove = chip("ENTFERNEN", ink3);
        remove.setOnClickListener(v -> {
            if (selected == null) return;
            if (selected.app != null) {
                // Apps: their system info page.
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + selected.app.getPackageName())));
                } catch (RuntimeException ignored) {
                }
                return;
            }
            marks.remove(selected);
            selected = null;
            save();
            showSheet();
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.leftMargin = dp(8);
        actions.addView(remove, rl);
        sheet.addView(actions);
        sheet.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.bottomMargin = dp(10);
        col.addView(sheet, sl);

        // Add a link by hand.
        EditText add = new EditText(this);
        add.setHint("App suchen … (oder Link einfügen)");
        add.setHintTextColor(ink3);
        add.setTextColor(ink);
        add.setSingleLine(true);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        add.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        add.setImeOptions(EditorInfo.IME_ACTION_DONE);
        add.setBackground(box(card, line, 8));
        add.setPadding(dp(14), dp(12), dp(14), dp(12));
        add.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        add.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                Mark m = search(e.toString().trim());
                if (m != null && m != selected) select(m);
            }
        });
        add.setOnEditorActionListener((v, id, e) -> {
            String u = add.getText().toString().trim();
            if (u.isEmpty()) return true;
            if (u.contains(".") && !u.contains(" ") && search(u) == null) {
                addLink(null, u);          // looks like a web address: put it on the globe
            } else if (selected != null) {
                openSelected();
            }
            add.setText("");
            return true;
        });
        col.addView(add);

        setContentView(root);
        // Bar icon colours only once the window exists (Android 16 crashes otherwise).
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : bits, bits);
        }
        handleShare(getIntent());
        showSheet();
        loadApps();
    }

    private TextView remove;

    /** Best match: name starts with the text, else contains it. */
    private Mark search(String q) {
        if (q.isEmpty()) return null;
        String l = q.toLowerCase(Locale.GERMAN);
        Mark contains = null;
        for (Mark k : all()) {
            String t = k.title.toLowerCase(Locale.GERMAN);
            if (t.startsWith(l)) return k;
            if (contains == null && t.contains(l)) contains = k;
        }
        return contains;
    }

    private void openSelected() {
        if (selected == null) return;
        try {
            if (selected.app != null) {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(selected.app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(selected.url)));
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "Lässt sich nicht öffnen.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    /** "Teilen → Globus" from Chrome or any app: the shared link lands on the globe. */
    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
        if (!m.find()) {
            Toast.makeText(this, "Kein Link gefunden.", Toast.LENGTH_SHORT).show();
            return;
        }
        String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
        addLink(subject, m.group());
    }

    private void addLink(String title, String url) {
        if (!url.startsWith("http")) url = "https://" + url;
        // Shared text comes from any app: keep it sane (web links only, bounded size and count).
        if (!url.matches("https?://[^\\s]{3,2000}") || marks.size() >= 500) {
            Toast.makeText(this, marks.size() >= 500 ? "Der Globus ist voll (500 Punkte)." : "Kein gültiger Link.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (title != null && title.length() > 120) title = title.substring(0, 120);
        for (Mark k : marks) {
            if (k.url.equals(url)) {
                select(k);
                return;
            }
        }
        Mark k = mark(title == null || title.isEmpty() ? host(url) : title, url);
        marks.add(k);
        save();
        select(k);
        Toast.makeText(this, "Auf den Globus gelegt.", Toast.LENGTH_SHORT).show();
    }

    /** Same site → same region; each link a little apart, always the same place. */
    private Mark mark(String title, String url) {
        Mark k = new Mark();
        k.title = title;
        k.url = url;
        String h = host(url);
        int hh = h.hashCode(), uh = url.hashCode();
        k.lon = Math.toRadians((hh & 0xFFFF) / 65535.0 * 360 - 180 + ((uh & 0xFF) / 255.0 - 0.5) * 18);
        k.lat = Math.toRadians(((hh >>> 16) & 0xFFFF) / 65535.0 * 120 - 60 + (((uh >>> 8) & 0xFF) / 255.0 - 0.5) * 14);
        return k;
    }

    private static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? url : h.replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return url;
        }
    }

    private void load() {
        marks.clear();
        String saved = prefs.getString("marks", null);
        try {
            if (saved != null) {
                JSONArray a = new JSONArray(saved);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    marks.add(mark(o.optString("t"), o.optString("u")));
                }
                spread();
                return;
            }
        } catch (Exception ignored) {
        }
        for (String[] s : SEED) marks.add(mark(s[0], s[1]));
        save();
    }

    /**
     * Links of the same site share a region but no longer sit on top of each other: the first at the
     * site's spot, the others around it on a sunflower spiral (about 16° apart).
     */
    private void spread() {
        // Region centres spread evenly over the globe (a Fibonacci sphere), filled regions only.
        java.util.Map<String, Integer> size = new java.util.HashMap<>();
        for (Mark k : all()) size.merge(k.region, 1, Integer::sum);
        List<String> used = new ArrayList<>();
        for (String r : REGIONS) if (size.containsKey(r)) used.add(r);
        regionCentre.clear();
        int n = used.size();
        for (int i = 0; i < n; i++) {
            double y = n == 1 ? 0 : 1 - 2.0 * (i + 0.5) / n;                 // -1 … 1
            double lat = Math.asin(y * 0.8), lon = i * Math.toRadians(137.5);
            regionCentre.put(used.get(i), new double[]{lat, lon});
        }
        // Each region's points round its centre on a sunflower spiral, closer when there are many.
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (Mark k : all()) {
            double[] c = regionCentre.get(k.region);
            if (c == null) continue;
            int i = count.getOrDefault(k.region, 0);
            count.put(k.region, i + 1);
            double step = Math.min(9, 30 / Math.sqrt(Math.max(1, size.get(k.region))));
            double rad = step * Math.sqrt(i), ang = Math.toRadians(i * 137.5), lat0 = Math.toDegrees(c[0]);
            k.lat = Math.toRadians(Math.max(-80, Math.min(80, lat0 + rad * Math.sin(ang))));
            k.lon = c[1] + Math.toRadians(rad * Math.cos(ang) / Math.max(0.3, Math.cos(c[0])));
        }
    }

    private void save() {
        spread();
        JSONArray a = new JSONArray();
        try {
            for (Mark k : marks) a.put(new JSONObject().put("t", k.title).put("u", k.url));
        } catch (Exception ignored) {
        }
        prefs.edit().putString("marks", a.toString()).apply();
        // save() also runs while loading, before the header exists.
        if (count != null) count.setText(apps.size() + " APPS · " + marks.size() + " LINKS");
        if (globe != null) globe.invalidate();
    }

    private void select(Mark k) {
        selected = k;
        globe.turnTo(k);
        showSheet();
    }

    private void showSheet() {
        if (count != null) count.setText(apps.size() + " APPS · " + marks.size() + " LINKS");
        if (selected == null) {
            sheet.setVisibility(View.GONE);
            return;
        }
        sheetTitle.setText(selected.title);
        sheetUrl.setText(selected.app != null ? selected.region + " · " + selected.url : selected.url);
        remove.setText(selected.app != null ? "APP-INFO" : "ENTFERNEN");
        sheet.setVisibility(View.VISIBLE);
    }

    /* ---------------- The globe ---------------- */

    private final class Globe extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.RectF iconDst = new android.graphics.RectF();
        private double yaw = 0.6, pitch = -0.35, vYaw, vPitch, targetYaw = Double.NaN, targetPitch;
        private float zoom = 1f, lastX, lastY, downX, downY;
        private boolean pinched;
        private long lastFrame;
        private final ScaleGestureDetector scaler = new ScaleGestureDetector(GlobusActivity.this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoom = Math.max(0.7f, Math.min(4f, zoom * d.getScaleFactor()));
                    invalidate();
                    return true;
                }
            });

        Globe() {
            super(GlobusActivity.this);
        }

        void turnTo(Mark k) {
            // Bring the point to the front centre: yaw opposite its longitude, pitch to its latitude.
            targetYaw = -k.lon;
            targetPitch = k.lat;
            double d = targetYaw - yaw;
            targetYaw = yaw + Math.atan2(Math.sin(d), Math.cos(d));
            invalidate();
        }

        private float[] project(double lat, double lon, float cx, float cy, float r) {
            double x = Math.cos(lat) * Math.sin(lon + yaw), y = Math.sin(lat), z = Math.cos(lat) * Math.cos(lon + yaw);
            double y2 = y * Math.cos(pitch) - z * Math.sin(pitch), z2 = y * Math.sin(pitch) + z * Math.cos(pitch);
            return new float[]{(float) (cx + x * r), (float) (cy - y2 * r), (float) z2};
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = System.currentTimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving = false;
            if (!Double.isNaN(targetYaw)) {
                yaw += (targetYaw - yaw) * Math.min(1, dt * 6);
                pitch += (targetPitch - pitch) * Math.min(1, dt * 6);
                if (Math.abs(targetYaw - yaw) < 0.002 && Math.abs(targetPitch - pitch) < 0.002) targetYaw = Double.NaN;
                moving = true;
            } else if (Math.abs(vYaw) > 0.0005 || Math.abs(vPitch) > 0.0005) {
                yaw += vYaw;
                pitch = Math.max(-1.3, Math.min(1.3, pitch + vPitch));
                vYaw *= 0.94;
                vPitch *= 0.94;
                moving = true;
            } else {
                yaw += dt * 0.08;      // a slow idle spin
                moving = true;
            }

            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) * 0.4f * zoom;
            // Sphere: soft fill and outline.
            p.setStyle(Paint.Style.FILL);
            p.setColor(card);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.2f));
            p.setColor(line);
            c.drawCircle(cx, cy, r, p);
            // Grid: meridians every 30°, parallels every 30°, only the front half drawn solid.
            p.setStrokeWidth(dp(1));
            for (int m = 0; m < 12; m++) gridLine(c, true, Math.toRadians(m * 30), cx, cy, r);
            for (int l = -60; l <= 60; l += 30) gridLine(c, false, Math.toRadians(l), cx, cy, r);
            // Points, back ones faint, front ones with a label.
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(10));
            List<Mark> everything = all();
            for (Mark k : everything) {
                float[] s = project(k.lat, k.lon, cx, cy, r);
                k.sx = s[0];
                k.sy = s[1];
                k.depth = s[2];
            }
            // Back to front, so near icons cover far ones; labels nearest first (they claim space).
            List<Mark> back = new ArrayList<>(everything);
            java.util.Collections.sort(back, (a, b) -> a == selected ? 1 : b == selected ? -1 : Float.compare(a.depth, b.depth));
            float isz = dp(15) * (float) Math.sqrt(zoom);
            for (Mark k : back) {
                if (k.icon == null || k.depth <= 0) continue;
                float half = isz * (0.5f + 0.5f * k.depth) * (k == selected ? 1.5f : 1f);
                iconDst.set(k.sx - half, k.sy - half, k.sx + half, k.sy + half);
                p.setAlpha((int) (120 + 135 * Math.min(1, k.depth * 1.5f)));
                c.drawBitmap(k.icon, null, iconDst, p);
                p.setAlpha(255);
                if (k == selected) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(2.5f));
                    p.setColor(rust);
                    c.drawCircle(k.sx, k.sy, half + dp(4), p);
                    p.setStyle(Paint.Style.FILL);
                }
            }
            // Region names over their centres.
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            for (java.util.Map.Entry<String, double[]> en : regionCentre.entrySet()) {
                float[] s = project(en.getValue()[0], en.getValue()[1], cx, cy, r);
                if (s[2] < 0.3f) continue;
                p.setTextSize(dp(11));
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(rust);
                p.setAlpha((int) (255 * Math.min(1, (s[2] - 0.3f) * 3)));
                c.drawText(en.getKey(), s[0], s[1] - isz * 2.6f, p);
                p.setAlpha(255);
                p.setTextAlign(Paint.Align.LEFT);
            }
            p.setTextSize(dp(10));
            List<Mark> order = new ArrayList<>(everything);
            java.util.Collections.sort(order, (a, b) -> a == selected ? -1 : b == selected ? 1 : Float.compare(b.depth, a.depth));
            List<android.graphics.RectF> taken = new ArrayList<>();
            for (Mark k : order) {
                float[] s = {k.sx, k.sy, k.depth};
                boolean front = s[2] > 0;
                p.setStyle(Paint.Style.FILL);
                if (k.icon == null || !front) {
                    p.setColor(k == selected ? rust : front ? rust : ink3);
                    p.setAlpha(front ? 255 : 70);
                    c.drawCircle(s[0], s[1], dp(k == selected ? 7 : front ? 4.5f : 2.5f) * (0.7f + 0.3f * Math.max(0, s[2])), p);
                }
                // Names only for the nearest (zoom in for more).
                if (front && s[2] > (k == selected ? 0 : 0.55f)) {
                    p.setColor(k == selected ? ink : ink2);
                    p.setAlpha((int) (255 * Math.min(1, (s[2] - 0.25f) * 3)));
                    String t = (k.title.length() > 22 ? k.title.substring(0, 21) + "…" : k.title).toUpperCase(Locale.GERMAN);
                    // A label only where it doesn't collide with a nearer one (zoom in to see the rest).
                    float off = k.icon != null ? isz * (0.5f + 0.5f * s[2]) + dp(3) : dp(7);
                    android.graphics.RectF box = new android.graphics.RectF(s[0] + off, s[1] - dp(8), s[0] + off + dp(4) + p.measureText(t), s[1] + dp(7));
                    boolean free = true;
                    for (android.graphics.RectF o : taken) if (android.graphics.RectF.intersects(o, box)) free = false;
                    if (free) {
                        taken.add(box);
                        c.drawText(t, s[0] + off + dp(2), s[1] + dp(4), p);
                    }
                }
                p.setAlpha(255);
            }
            if (moving) postInvalidateOnAnimation();
        }

        private void gridLine(Canvas c, boolean meridian, double fixed, float cx, float cy, float r) {
            float[] prev = null;
            int steps = meridian ? 36 : 72;          // meridian: pole to pole; parallel: all the way round
            for (int i = 0; i <= steps; i++) {
                double v = Math.toRadians(i * 5);
                float[] s = meridian ? project(v - Math.PI / 2, fixed, cx, cy, r) : project(fixed, v, cx, cy, r);
                if (prev != null) {
                    boolean front = s[2] > 0 && prev[2] > 0;
                    p.setColor(front ? line : ink3);
                    p.setAlpha(front ? 255 : 25);
                    c.drawLine(prev[0], prev[1], s[0], s[1], p);
                }
                prev = s;
            }
            p.setAlpha(255);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = downX = e.getX();
                lastY = downY = e.getY();
                vYaw = vPitch = 0;
                pinched = false;
                targetYaw = Double.NaN;
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                pinched = true;
                vYaw = vPitch = 0;
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                // Continue from the finger that stays, without a jump.
                int keep = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(keep);
                lastY = e.getY(keep);
                vYaw = vPitch = 0;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (e.getPointerCount() > 1 || scaler.isInProgress()) return true;
                float r = Math.min(getWidth(), getHeight()) * 0.4f * zoom;
                vYaw = (e.getX() - lastX) / r;
                vPitch = (e.getY() - lastY) / r;
                yaw += vYaw;
                pitch = Math.max(-1.3, Math.min(1.3, pitch + vPitch));
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (pinched) {
                    vYaw = vPitch = 0;      // a zoom ends where it is: no spin
                    invalidate();
                    return true;
                }
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < dp(10)) {
                    // A tap: the nearest front point within reach.
                    Mark best = null;
                    float bestD = dp(28);
                    for (Mark k : all()) {
                        if (k.depth <= 0) continue;
                        float d = (float) Math.hypot(k.sx - e.getX(), k.sy - e.getY());
                        if (d < bestD) {
                            bestD = d;
                            best = k;
                        }
                    }
                    if (best != null) select(best);
                    else {
                        selected = null;
                        showSheet();
                    }
                }
                invalidate();
                return true;
            }
            return true;
        }
    }

    /* ---------------- Small ARC-DNA view helpers ---------------- */

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
        t.setLetterSpacing(0.12f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView chip(String s, int colour) {
        TextView c = mono(12, colour);
        c.setText(s);
        c.setPadding(dp(14), dp(9), dp(14), dp(9));
        c.setBackground(box(card, colour, 20));
        return c;
    }

    private View label(String s) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        View dash = new View(this);
        dash.setBackgroundColor(rust);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(24), dp(2));
        dl.rightMargin = dp(14);
        l.addView(dash, dl);
        TextView t = mono(11, ink3);
        t.setLetterSpacing(0.24f);
        t.setText(s.toUpperCase(Locale.GERMAN));
        l.addView(t);
        return l;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
}
