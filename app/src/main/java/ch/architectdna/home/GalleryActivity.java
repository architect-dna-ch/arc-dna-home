package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Galerie, Globus-style: your newest photos as small pictures on a globe — one region per month,
 * newest first. Drag = turn, pinch = zoom, month chips = jump there, tap a photo = it turns to the
 * front with a big preview (open in Fotos / share).
 */
public class GalleryActivity extends Activity {

    private static final int REQ_PHOTOS = 1, MAX = 500;

    private boolean night;
    private int bg, card, line, ink, ink2, ink3, rust;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final List<Photo> photos = new ArrayList<>();
    private final Map<String, Month> months = new LinkedHashMap<>();
    private Globe globe;
    private LinearLayout chips, sheet;
    private ImageView preview;
    private TextView sheetTitle, sheetSub, count, allow;
    private Photo selected;
    private volatile boolean closed;

    private static final class Photo {
        Uri uri;
        long time;
        String bucket;
        double lat, lon;
        float sx, sy, depth;
        volatile Bitmap thumb;
        BitmapShader shader;
        Month month;
    }

    private static final class Month {
        String key, label;
        double lat, lon;
        int n;
        float sx, sy, depth;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
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

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(16));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Galerie · Globus"), new LinearLayout.LayoutParams(0, -2, 1f));
        count = mono(11, rust);
        head.addView(count);
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        TextView hint = mono(10, ink3);
        hint.setText("ZIEHEN = DREHEN · ZWEI FINGER = ZOOM · FOTO = ANSEHEN");
        hint.setPadding(0, dp(10), 0, dp(10));
        col.addView(hint);

        // Months to jump to.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        hs.addView(chips);
        col.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        allow = chip("MEHR FOTOS ERLAUBEN", rust);
        allow.setVisibility(View.GONE);
        allow.setOnClickListener(v -> ask());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.topMargin = dp(8);
        col.addView(allow, al);

        globe = new Globe();
        col.addView(globe, new LinearLayout.LayoutParams(-1, 0, 1f));

        // The card for a tapped photo.
        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.HORIZONTAL);
        sheet.setGravity(Gravity.CENTER_VERTICAL);
        sheet.setBackground(box(card, line, 14));
        sheet.setPadding(dp(10), dp(10), dp(14), dp(10));
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setClipToOutline(true);
        preview.setBackground(box(line, line, 10));
        preview.setOnClickListener(v -> open());
        sheet.addView(preview, new LinearLayout.LayoutParams(dp(112), dp(112)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(14), 0, 0, 0);
        sheetTitle = text(17, ink, true);
        info.addView(sheetTitle);
        sheetSub = mono(10, ink3);
        sheetSub.setPadding(0, dp(4), 0, dp(10));
        info.addView(sheetSub);
        LinearLayout actions = new LinearLayout(this);
        TextView openC = chip("ÖFFNEN", rust);
        openC.setOnClickListener(v -> open());
        actions.addView(openC);
        TextView share = chip("TEILEN", ink3);
        share.setOnClickListener(v -> {
            if (selected == null) return;
            Intent s = new Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, selected.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "Foto teilen"));
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = dp(8);
        actions.addView(share, sl);
        info.addView(actions);
        sheet.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        sheet.setVisibility(View.GONE);
        col.addView(sheet, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : bits, bits);
        }
        if (hasAccess()) load();
        else ask();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closed = true;
        io.shutdownNow();
    }

    /* ---------------- photos ---------------- */

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) return true;
        if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean fullAccess() {
        if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        return hasAccess();
    }

    private void ask() {
        String[] p = Build.VERSION.SDK_INT >= 34
            ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
            : Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.READ_MEDIA_IMAGES}
            : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        requestPermissions(p, REQ_PHOTOS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PHOTOS) return;
        if (hasAccess()) load();
        else count.setText("KEIN ZUGRIFF");
    }

    private void load() {
        allow.setVisibility(fullAccess() ? View.GONE : View.VISIBLE);
        io.execute(() -> {
            List<Photo> found = new ArrayList<>();
            String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext() && found.size() < MAX) {
                    Photo p = new Photo();
                    p.uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                    long taken = c.isNull(1) ? 0 : c.getLong(1);
                    p.time = taken > 0 ? taken : c.getLong(2) * 1000;
                    p.bucket = c.getString(3);
                    found.add(p);
                }
            } catch (RuntimeException e) {
                DiagLog.log("gallery query failed: " + e.getMessage());
            }
            Collections.sort(found, (a, b) -> Long.compare(b.time, a.time));
            main.post(() -> {
                if (closed) return;
                photos.clear();
                photos.addAll(found);
                place();
                buildChips();
                count.setText(photos.size() + " FOTOS");
                globe.invalidate();
                loadThumbs();
            });
        });
    }

    /** One region per month along a spiral round the globe; the photos of a month around its centre. */
    private void place() {
        months.clear();
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM", Locale.ROOT), lbl = new SimpleDateFormat("MMM yy", Locale.GERMAN);
        for (Photo p : photos) {
            String k = key.format(new Date(p.time));
            Month m = months.get(k);
            if (m == null) {
                m = new Month();
                m.key = k;
                m.label = lbl.format(new Date(p.time)).replace(".", "").toUpperCase(Locale.GERMAN);
                months.put(k, m);
            }
            p.month = m;
            m.n++;
        }
        int mi = 0;
        Map<Month, Integer> seen = new java.util.HashMap<>();
        for (Month m : months.values()) {
            double lon0 = mi * 52.0, lat0 = 34 * Math.sin(mi * 1.15);
            m.lat = Math.toRadians(lat0);
            m.lon = Math.toRadians(lon0);
            mi++;
        }
        for (Photo p : photos) {
            Month m = p.month;
            int i = seen.getOrDefault(m, 0);
            seen.put(m, i + 1);
            double step = Math.min(7.5, 40 / Math.sqrt(Math.max(1, m.n)));
            double rad = step * Math.sqrt(i), ang = Math.toRadians(i * 137.5);
            double latD = Math.toDegrees(m.lat), lonD = Math.toDegrees(m.lon);
            p.lat = Math.toRadians(Math.max(-80, Math.min(80, latD + rad * Math.sin(ang))));
            p.lon = Math.toRadians(lonD + rad * Math.cos(ang) / Math.max(0.3, Math.cos(m.lat)));
        }
    }

    private void buildChips() {
        chips.removeAllViews();
        for (Month m : months.values()) {
            TextView c = chip(m.label + " · " + m.n, ink2);
            c.setOnClickListener(v -> {
                selected = null;
                showSheet();
                globe.turnTo(m.lat, m.lon, 1.9f);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chips.addView(c, lp);
        }
    }

    /** Small thumbnails, newest first; the globe redraws as they come in. */
    private void loadThumbs() {
        List<Photo> todo = new ArrayList<>(photos);
        int px = dp(56);
        io.execute(() -> {
            long last = 0;
            for (Photo p : todo) {
                if (closed) return;
                Bitmap b = thumb(p.uri, px);
                if (b != null) p.thumb = b;
                long now = System.currentTimeMillis();
                if (now - last > 120) {
                    last = now;
                    main.post(globe::invalidate);
                }
            }
            main.post(globe::invalidate);
        });
    }

    private Bitmap thumb(Uri uri, int px) {
        try {
            Bitmap b;
            if (Build.VERSION.SDK_INT >= 29) {
                b = getContentResolver().loadThumbnail(uri, new Size(px * 2, px * 2), null);
            } else {
                //noinspection deprecation
                b = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), ContentUris.parseId(uri),
                    MediaStore.Images.Thumbnails.MINI_KIND, null);
            }
            if (b == null) return null;
            // Square centre crop at the target size keeps memory small (500 × 56 dp).
            int s = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
            Bitmap out = Bitmap.createScaledBitmap(sq, px, px, true);
            if (sq != b) sq.recycle();
            if (out != b) b.recycle();
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void select(Photo p) {
        selected = p;
        globe.turnTo(p.lat, p.lon, Math.max(globe.zoom, 1.6f));
        showSheet();
    }

    private void showSheet() {
        if (selected == null) {
            sheet.setVisibility(View.GONE);
            return;
        }
        Photo p = selected;
        sheetTitle.setText(new SimpleDateFormat("EEEE, d. MMMM yyyy", Locale.GERMAN).format(new Date(p.time)));
        sheetSub.setText((new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(p.time))
            + (p.bucket != null ? " · " + p.bucket : "")).toUpperCase(Locale.GERMAN));
        preview.setImageBitmap(p.thumb);
        sheet.setVisibility(View.VISIBLE);
        io.execute(() -> {
            Bitmap big = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) big = getContentResolver().loadThumbnail(p.uri, new Size(dp(224), dp(224)), null);
            } catch (Exception | OutOfMemoryError ignored) {
            }
            Bitmap b = big;
            if (b != null) main.post(() -> {
                if (selected == p) preview.setImageBitmap(b);
            });
        });
    }

    private void open() {
        if (selected == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(selected.uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException ignored) {
        }
    }

    /* ---------------- the globe ---------------- */

    private final class Globe extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF(), src = new RectF();
        private final Matrix m = new Matrix();
        private double yaw = 0.0, pitch = -0.2, vYaw, vPitch, targetYaw = Double.NaN, targetPitch;
        float zoom = 1f;
        private float targetZoom = Float.NaN, lastX, lastY, downX, downY;
        private boolean pinched, touched;
        private android.graphics.RadialGradient shade;
        private float shadeR;
        private long lastFrame;
        private final ScaleGestureDetector scaler = new ScaleGestureDetector(GalleryActivity.this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    targetZoom = Float.NaN;
                    zoom = Math.max(0.7f, Math.min(5f, zoom * d.getScaleFactor()));
                    invalidate();
                    return true;
                }
            });

        Globe() {
            super(GalleryActivity.this);
        }

        void turnTo(double lat, double lon, float z) {
            targetYaw = -lon;
            targetPitch = lat;
            double d = targetYaw - yaw;
            targetYaw = yaw + Math.atan2(Math.sin(d), Math.cos(d));
            targetZoom = z;
            invalidate();
        }

        private void project(double lat, double lon, float cx, float cy, float r, float[] out) {
            double x = Math.cos(lat) * Math.sin(lon + yaw), y = Math.sin(lat), z = Math.cos(lat) * Math.cos(lon + yaw);
            double y2 = y * Math.cos(pitch) - z * Math.sin(pitch), z2 = y * Math.sin(pitch) + z * Math.cos(pitch);
            out[0] = (float) (cx + x * r);
            out[1] = (float) (cy - y2 * r);
            out[2] = (float) z2;
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = System.currentTimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving;
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
            } else if (!touched) {
                yaw += dt * 0.12;   // a slow spin until you take it in hand
                moving = true;
            } else {
                moving = false;     // after that it holds still: photos are easier to look at
            }
            if (!Float.isNaN(targetZoom)) {
                zoom += (targetZoom - zoom) * Math.min(1, dt * 5);
                if (Math.abs(targetZoom - zoom) < 0.01f) targetZoom = Float.NaN;
                moving = true;
            }

            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) * 0.42f * zoom;
            // Sphere: lit from the top left, darker towards the rim, so it reads as a ball.
            if (shade == null || shadeR != r) {
                shadeR = r;
                int hi = night ? 0xFF34332F : 0xFFFFFFFF, lo = night ? 0xFF0C0C0B : 0xFFD9D8D0;
                shade = new android.graphics.RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.45f,
                    new int[]{hi, card, lo}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            }
            p.setStyle(Paint.Style.FILL);
            p.setShader(shade);
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.2f));
            p.setColor(line);
            c.drawCircle(cx, cy, r, p);
            float[] s = new float[3];
            // Grid: meridians and parallels every 30°, the back half faint.
            p.setStrokeWidth(dp(1));
            for (int mm = 0; mm < 12; mm++) gridLine(c, true, Math.toRadians(mm * 30), cx, cy, r, s);
            for (int l = -60; l <= 60; l += 30) gridLine(c, false, Math.toRadians(l), cx, cy, r, s);
            for (Photo ph : photos) {
                project(ph.lat, ph.lon, cx, cy, r, s);
                ph.sx = s[0];
                ph.sy = s[1];
                ph.depth = s[2];
            }
            List<Photo> order = new ArrayList<>(photos);
            Collections.sort(order, (a, b) -> a == selected ? 1 : b == selected ? -1 : Float.compare(a.depth, b.depth));
            float base = dp(15) * (float) Math.sqrt(zoom);
            for (Photo ph : order) {
                if (ph.depth <= 0) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(ink3);
                    p.setAlpha(50);
                    c.drawCircle(ph.sx, ph.sy, dp(1.6f), p);
                    p.setAlpha(255);
                    continue;
                }
                float half = base * (0.45f + 0.55f * ph.depth) * (ph == selected ? 1.6f : 1f);
                dst.set(ph.sx - half, ph.sy - half, ph.sx + half, ph.sy + half);
                Bitmap b = ph.thumb;
                if (b != null && !b.isRecycled()) {
                    if (ph.shader == null) ph.shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    BitmapShader sh = ph.shader;
                    src.set(0, 0, b.getWidth(), b.getHeight());
                    m.setRectToRect(src, dst, Matrix.ScaleToFit.FILL);
                    sh.setLocalMatrix(m);
                    p.setStyle(Paint.Style.FILL);
                    p.setShader(sh);
                    p.setAlpha((int) (110 + 145 * Math.min(1, ph.depth * 1.4f)));
                    c.drawRoundRect(dst, half * 0.3f, half * 0.3f, p);
                    p.setShader(null);
                    p.setAlpha(255);
                } else {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(line);
                    c.drawRoundRect(dst, half * 0.3f, half * 0.3f, p);
                }
                if (ph == selected) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(2.5f));
                    p.setColor(rust);
                    c.drawRoundRect(dst, half * 0.3f, half * 0.3f, p);
                }
            }
            // Month names over their regions.
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(11));
            p.setTextAlign(Paint.Align.CENTER);
            for (Month mo : months.values()) {
                project(mo.lat, mo.lon, cx, cy, r, s);
                if (s[2] < 0.35f) continue;
                String t = mo.label;
                float tw = p.measureText(t);
                float ty = s[1] - base * 2.2f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(bg);
                p.setAlpha((int) (220 * Math.min(1, (s[2] - 0.35f) * 3)));
                c.drawRoundRect(s[0] - tw / 2 - dp(6), ty - dp(13), s[0] + tw / 2 + dp(6), ty + dp(5), dp(6), dp(6), p);
                p.setColor(rust);
                p.setAlpha((int) (255 * Math.min(1, (s[2] - 0.35f) * 3)));
                c.drawText(t, s[0], ty, p);
                p.setAlpha(255);
            }
            p.setTextAlign(Paint.Align.LEFT);
            if (photos.isEmpty()) {
                p.setColor(ink3);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(hasAccess() ? "LADE FOTOS …" : "ZUGRIFF AUF FOTOS NÖTIG", cx, cy, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            if (moving) postInvalidateOnAnimation();
        }

        private void gridLine(Canvas c, boolean meridian, double fixed, float cx, float cy, float r, float[] s) {
            float px = 0, py = 0, pz = 0;
            int steps = meridian ? 36 : 72;
            int gridCol = night ? 0xFF3A3935 : 0xFFCFCEC6;
            for (int i = 0; i <= steps; i++) {
                double v = Math.toRadians(i * 5);
                if (meridian) project(v - Math.PI / 2, fixed, cx, cy, r, s);
                else project(fixed, v, cx, cy, r, s);
                if (i > 0) {
                    boolean front = s[2] > 0 && pz > 0;
                    p.setColor(front ? gridCol : ink3);
                    p.setAlpha(front ? 255 : 28);
                    c.drawLine(px, py, s[0], s[1], p);
                }
                px = s[0];
                py = s[1];
                pz = s[2];
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
                touched = true;
                targetYaw = Double.NaN;
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                pinched = true;
                vYaw = vPitch = 0;
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                int keep = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(keep);
                lastY = e.getY(keep);
                vYaw = vPitch = 0;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (e.getPointerCount() > 1 || scaler.isInProgress()) return true;
                float r = Math.min(getWidth(), getHeight()) * 0.42f * zoom;
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
                    vYaw = vPitch = 0;
                    invalidate();
                    return true;
                }
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < dp(10)) {
                    // A tap: the front-most photo under the finger.
                    Photo best = null;
                    float bestD = dp(26) * (float) Math.sqrt(zoom);
                    for (Photo ph : photos) {
                        if (ph.depth <= 0.1f) continue;
                        float d = (float) Math.hypot(ph.sx - e.getX(), ph.sy - e.getY()) - ph.depth * dp(4);
                        if (d < bestD) {
                            bestD = d;
                            best = ph;
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

    /* ---------------- small view helpers ---------------- */

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
        TextView c = mono(11, colour);
        c.setText(s);
        c.setPadding(dp(12), dp(8), dp(12), dp(8));
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
