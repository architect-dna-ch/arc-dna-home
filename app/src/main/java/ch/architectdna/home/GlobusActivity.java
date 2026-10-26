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
 * Globus, native: your links as points on a globe you can turn (drag), zoom (pinch) and walk
 * around. Links of the same site sit close together. Tap a point: the globe turns it to the front
 * and a card offers open / remove. New links come from Chrome's "Teilen → Globus" or the field
 * below. (Android doesn't let apps read Chrome's bookmarks, so Globus keeps its own.)
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
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        load();

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(16));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Globus · Lesezeichen"), new LinearLayout.LayoutParams(0, -2, 1f));
        count = mono(11, rust);
        head.addView(count);
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        TextView hint = mono(10, ink3);
        hint.setText("ZIEHEN = DREHEN · ZWEI FINGER = ZOOM · PUNKT = ÖFFNEN");
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
        open.setOnClickListener(v -> {
            if (selected != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(selected.url)));
        });
        actions.addView(open);
        TextView remove = chip("ENTFERNEN", ink3);
        remove.setOnClickListener(v -> {
            if (selected == null) return;
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
        add.setHint("Link auf den Globus legen …");
        add.setHintTextColor(ink3);
        add.setTextColor(ink);
        add.setSingleLine(true);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        add.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        add.setImeOptions(EditorInfo.IME_ACTION_DONE);
        add.setBackground(box(card, line, 8));
        add.setPadding(dp(14), dp(12), dp(14), dp(12));
        add.setOnEditorActionListener((v, id, e) -> {
            String u = add.getText().toString().trim();
            if (!u.isEmpty()) {
                addLink(null, u);
                add.setText("");
            }
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
                return;
            }
        } catch (Exception ignored) {
        }
        for (String[] s : SEED) marks.add(mark(s[0], s[1]));
        save();
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Mark k : marks) a.put(new JSONObject().put("t", k.title).put("u", k.url));
        } catch (Exception ignored) {
        }
        prefs.edit().putString("marks", a.toString()).apply();
        // save() also runs while loading, before the header exists.
        if (count != null) count.setText(marks.size() + (marks.size() == 1 ? " PUNKT" : " PUNKTE"));
    }

    private void select(Mark k) {
        selected = k;
        globe.turnTo(k);
        showSheet();
    }

    private void showSheet() {
        if (count != null) count.setText(marks.size() + (marks.size() == 1 ? " PUNKT" : " PUNKTE"));
        if (selected == null) {
            sheet.setVisibility(View.GONE);
            return;
        }
        sheetTitle.setText(selected.title);
        sheetUrl.setText(selected.url);
        sheet.setVisibility(View.VISIBLE);
    }

    /* ---------------- The globe ---------------- */

    private final class Globe extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private double yaw = 0.6, pitch = -0.35, vYaw, vPitch, targetYaw = Double.NaN, targetPitch;
        private float zoom = 1f, lastX, lastY, downX, downY;
        private long lastFrame;
        private final ScaleGestureDetector scaler = new ScaleGestureDetector(GlobusActivity.this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoom = Math.max(0.7f, Math.min(3.2f, zoom * d.getScaleFactor()));
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
            for (Mark k : marks) {
                float[] s = project(k.lat, k.lon, cx, cy, r);
                k.sx = s[0];
                k.sy = s[1];
                k.depth = s[2];
                boolean front = s[2] > 0;
                p.setStyle(Paint.Style.FILL);
                p.setColor(k == selected ? rust : front ? rust : ink3);
                p.setAlpha(front ? 255 : 70);
                c.drawCircle(s[0], s[1], dp(k == selected ? 7 : 4.5f) * (0.7f + 0.3f * Math.max(0, s[2])), p);
                if (front && s[2] > 0.25f) {
                    p.setColor(k == selected ? ink : ink2);
                    p.setAlpha((int) (255 * Math.min(1, (s[2] - 0.25f) * 3)));
                    String t = k.title.length() > 22 ? k.title.substring(0, 21) + "…" : k.title;
                    c.drawText(t.toUpperCase(Locale.GERMAN), s[0] + dp(9), s[1] + dp(4), p);
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
                targetYaw = Double.NaN;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (e.getPointerCount() > 1) return true;
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
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < dp(10)) {
                    // A tap: the nearest front point within reach.
                    Mark best = null;
                    float bestD = dp(28);
                    for (Mark k : marks) {
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
