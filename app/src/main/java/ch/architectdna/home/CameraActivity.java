package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ARC-DNA Kamera: a clean camera with a thirds grid and a coach. The buddy that fits what you're
 * shooting (fox for animals, rabbit for flowers, bear for food, the F1 car for vehicles, the bunny
 * for people) tells you what to change: straighten the horizon, get closer, step back, put the
 * subject on a grid crossing (an arrow shows where), more light — and when it's right, says so.
 * Photos land in Pictures/ARC-DNA; the round preview bottom left opens them on the Galerie globe.
 */
public class CameraActivity extends Activity implements CameraEngine.Listener, SensorEventListener {

    private static final int REQ_CAM = 1;

    private CameraEngine engine;
    private Coach coach;
    private ImageView thumb;
    private View flash;
    private boolean front;
    private Uri lastShot;
    private SensorManager sensors;
    private float tilt, tiltSmooth;
    private boolean upright;
    private int rust = 0xFFD9472F;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(true);

        engine = new CameraEngine(this, this);
        root.addView(engine.preview(), new FrameLayout.LayoutParams(-1, -1));
        coach = new Coach();
        root.addView(coach, new FrameLayout.LayoutParams(-1, -1));
        flash = new View(this);
        flash.setBackgroundColor(0xFFFFFFFF);
        flash.setAlpha(0f);
        root.addView(flash, new FrameLayout.LayoutParams(-1, -1));

        // Top: name and close.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(12), dp(8), dp(12));
        View dash = new View(this);
        dash.setBackgroundColor(rust);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(24), dp(2));
        dl.rightMargin = dp(12);
        top.addView(dash, dl);
        TextView name = mono(11, 0xFFB8B8B2);
        name.setLetterSpacing(0.24f);
        name.setText("ARC-DNA KAMERA");
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = mono(18, 0xFFFFFFFF);
        close.setText("✕");
        close.setPadding(dp(14), dp(6), dp(14), dp(6));
        close.setOnClickListener(v -> finish());
        top.addView(close);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Bottom: last photo (→ Galerie globe), shutter, switch camera.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(28), dp(16), dp(28), dp(26));
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setClipToOutline(true);
        GradientDrawable tb = new GradientDrawable();
        tb.setShape(GradientDrawable.OVAL);
        tb.setColor(0xFF1C1C1A);
        tb.setStroke(dp(2), rust);
        thumb.setBackground(tb);
        thumb.setPadding(dp(3), dp(3), dp(3), dp(3));
        thumb.setContentDescription("Letztes Foto in der Galerie");
        thumb.setOnClickListener(v -> {
            Intent g = new Intent(this, GalleryActivity.class);
            if (lastShot != null) g.putExtra("focus", lastShot.toString());
            startActivity(g);
        });
        bottom.addView(thumb, new LinearLayout.LayoutParams(dp(56), dp(56)));
        View sp1 = new View(this);
        bottom.addView(sp1, new LinearLayout.LayoutParams(0, 1, 1f));
        View shutter = new Shutter();
        shutter.setContentDescription("Foto aufnehmen");
        shutter.setOnClickListener(v -> shoot());
        bottom.addView(shutter, new LinearLayout.LayoutParams(dp(78), dp(78)));
        View sp2 = new View(this);
        bottom.addView(sp2, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView flip = mono(22, 0xFFFFFFFF);
        flip.setText("⟲");
        flip.setGravity(Gravity.CENTER);
        GradientDrawable fb = new GradientDrawable();
        fb.setShape(GradientDrawable.OVAL);
        fb.setColor(0x33FFFFFF);
        flip.setBackground(fb);
        flip.setContentDescription("Kamera wechseln");
        flip.setOnClickListener(v -> {
            front = !front;
            engine.stop();
            engine.start(front, rotation());
            coach.reset();
        });
        bottom.addView(flip, new LinearLayout.LayoutParams(dp(56), dp(56)));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        sensors = getSystemService(SensorManager.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) engine.start(front, rotation());
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        Sensor acc = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        engine.stop();
        if (sensors != null) sensors.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.close();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAM && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) engine.start(front, rotation());
        else if (requestCode == REQ_CAM) Toast.makeText(this, "Ohne Kamera-Zugriff geht's nicht.", Toast.LENGTH_LONG).show();
    }

    /** Volume keys take the photo, like the system camera. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) shoot();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    private int rotation() {
        return Build.VERSION.SDK_INT >= 30 && getDisplay() != null ? getDisplay().getRotation()
            : getWindowManager().getDefaultDisplay().getRotation();
    }

    private void shoot() {
        flash.animate().cancel();
        flash.setAlpha(0.85f);
        flash.animate().alpha(0f).setDuration(260).start();
        try {
            new android.media.MediaActionSound().play(android.media.MediaActionSound.SHUTTER_CLICK);
        } catch (RuntimeException ignored) {
        }
        engine.take(rotation(), uri -> {
            if (uri == null) {
                Toast.makeText(this, "Foto konnte nicht gesichert werden.", Toast.LENGTH_SHORT).show();
                return;
            }
            lastShot = uri;
            DiagLog.log("camera photo saved");
            new Thread(() -> {
                Bitmap b = null;
                try {
                    if (Build.VERSION.SDK_INT >= 29) b = getContentResolver().loadThumbnail(uri, new Size(dp(112), dp(112)), null);
                } catch (Exception ignored) {
                }
                Bitmap bb = b;
                runOnUiThread(() -> {
                    if (bb != null) thumb.setImageBitmap(bb);
                    thumb.setScaleX(0.6f);
                    thumb.setScaleY(0.6f);
                    thumb.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
                    coach.shot();
                });
            }).start();
        });
    }

    /* ---------------- scene + sensors ---------------- */

    @Override
    public void onScene(CameraEngine.Scene s) {
        coach.scene(s);
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        // Gravity in screen coordinates (the display may be turned on a tablet).
        int r = rotation();
        float sx = x, sy = y;
        if (r == Surface.ROTATION_90) {
            sx = -y;
            sy = x;
        } else if (r == Surface.ROTATION_180) {
            sx = -x;
            sy = -y;
        } else if (r == Surface.ROTATION_270) {
            sx = y;
            sy = -x;
        }
        upright = Math.abs(z) < 7.5f;              // held up, not pointing at the floor
        tilt = (float) Math.toDegrees(Math.atan2(sx, sy));
        tiltSmooth += (tilt - tiltSmooth) * 0.15f;
        coach.invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /* ---------------- the coach: grid, hints and the character ---------------- */

    /** {English labels (lower case) …} → {accusative, nominative}. */
    private static final String[][] WORDS = {
        {"dog", "den Hund", "der Hund"}, {"cat", "die Katze", "die Katze"}, {"bird", "den Vogel", "der Vogel"},
        {"horse", "das Pferd", "das Pferd"}, {"pet", "das Tier", "das Tier"}, {"flower", "die Blume", "die Blume"},
        {"plant", "die Pflanze", "die Pflanze"}, {"tree", "den Baum", "der Baum"}, {"petal", "die Blüte", "die Blüte"},
        {"food", "das Essen", "das Essen"}, {"cake", "den Kuchen", "der Kuchen"}, {"coffee", "den Kaffee", "der Kaffee"},
        {"cup", "die Tasse", "die Tasse"}, {"fruit", "das Obst", "das Obst"}, {"bread", "das Brot", "das Brot"},
        {"pizza", "die Pizza", "die Pizza"}, {"dessert", "das Dessert", "das Dessert"}, {"drink", "das Getränk", "das Getränk"},
        {"car", "das Auto", "das Auto"}, {"vehicle", "das Fahrzeug", "das Fahrzeug"}, {"bicycle", "das Velo", "das Velo"},
        {"motorcycle", "das Motorrad", "das Motorrad"}, {"wheel", "das Rad", "das Rad"}, {"bus", "den Bus", "der Bus"},
        {"person", "die Person", "die Person"}, {"selfie", "dich", "du"}, {"smile", "das Lächeln", "das Lächeln"},
        {"building", "das Gebäude", "das Gebäude"}, {"sky", "den Himmel", "der Himmel"}, {"lake", "den See", "der See"},
        {"mountain", "den Berg", "der Berg"}, {"book", "das Buch", "das Buch"}, {"guitar", "die Gitarre", "die Gitarre"},
        {"shoe", "den Schuh", "der Schuh"}, {"toy", "das Spielzeug", "das Spielzeug"}};

    /** Character per kind of subject: {drawable, voice}. */
    private static final int FOX = 0, RABBIT = 1, BEAR = 2, CAR = 3, BUNNY = 4;
    private static final String[] VOICE = {"Yip! ", "Mümmel … ", "Brumm. ", "Box, box! ", "Hoppla – "};

    private static int characterFor(List<String> labels) {
        for (String l : labels) {
            String s = l.toLowerCase(Locale.ROOT);
            if (s.matches("dog|cat|bird|pet|horse|animal|bear|rabbit|duck|fish|insect|butterfly")) return FOX;
            if (s.matches("flower|plant|tree|petal|garden|grass|leaf|forest|moss|bloom")) return RABBIT;
            if (s.matches("food|cake|coffee|cup|dessert|fruit|bread|pizza|drink|wine|beer|cuisine|meal|dish|cookie")) return BEAR;
            if (s.matches("car|vehicle|bicycle|motorcycle|wheel|bus|train|road|tire|racing")) return CAR;
            if (s.matches("person|selfie|smile|hair|face|skin|beard|eyelash|fun|people")) return BUNNY;
        }
        return FOX;
    }

    private static String[] wordFor(List<String> labels) {
        for (String l : labels) {
            String s = l.toLowerCase(Locale.ROOT);
            for (String[] w : WORDS) if (w[0].equals(s)) return new String[]{w[1], w[2]};
        }
        return new String[]{"das Motiv", "das Motiv"};
    }

    private final class Coach extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF content = new RectF(), box = new RectF(), target = new RectF();
        private final Path path = new Path();
        private CameraEngine.Scene scene;
        private boolean hasBox, good;
        private float bl, bt, br, bb;           // smoothed box (normalized)
        private float tx = -1, ty = -1;         // target crossing (normalized)
        private String hint = "", pending = "";
        private int pendingCount, character = FOX;
        private long hintAt, shotAt;
        private final Drawable[] art = new Drawable[5];

        Coach() {
            super(CameraActivity.this);
            int[] ids = {R.drawable.fox, R.drawable.rabbit, R.drawable.bear, R.drawable.f1buddy, R.drawable.bunnybuddy};
            for (int i = 0; i < ids.length; i++) {
                try {
                    art[i] = getDrawable(ids[i]);
                } catch (RuntimeException ignored) {
                }
            }
        }

        void reset() {
            scene = null;
            hasBox = false;
            hint = "";
            pending = "";
            invalidate();
        }

        void shot() {
            shotAt = SystemClock.uptimeMillis();
            invalidate();
        }

        void scene(CameraEngine.Scene s) {
            scene = s;
            if (s.box) {
                float l = front ? 1 - s.r : s.l, r = front ? 1 - s.l : s.r;
                if (!hasBox) {
                    lockAt = SystemClock.uptimeMillis();      // a new subject: the lock-on frames fly in
                    bl = l;
                    bt = s.t;
                    br = r;
                    bb = s.b;
                }
                // Light smoothing so the box glides instead of jumping.
                bl += (l - bl) * 0.5f;
                br += (r - br) * 0.5f;
                bt += (s.t - bt) * 0.5f;
                bb += (s.b - bb) * 0.5f;
                hasBox = true;
            } else {
                hasBox = false;
            }
            int ch = characterFor(s.labels);
            String next = decide(s);
            // A new hint only once it's seen twice in a row and the old one stood for 1.2 s: no flicker.
            if (next.equals(pending)) pendingCount++;
            else {
                pending = next;
                pendingCount = 1;
            }
            long now = SystemClock.uptimeMillis();
            if (!next.equals(hint) && pendingCount >= 2 && now - hintAt > 1200) {
                hint = next;
                hintAt = now;
                character = ch;
            }
            invalidate();
        }

        /** The one most useful thing to change right now. */
        private String decide(CameraEngine.Scene s) {
            String[] w = wordFor(s.labels);
            good = false;
            tx = ty = -1;
            if (s.brightness < 0.16f) return "Zu dunkel. Geh ans Licht oder halt ganz still.";
            if (upright && Math.abs(tiltSmooth) > 2.5f && Math.abs(tiltSmooth) < 40)
                return String.format(Locale.GERMAN, "Horizont schief – %.0f° nach %s drehen.", Math.abs(tiltSmooth), tiltSmooth > 0 ? "links" : "rechts");
            if (!hasBox) return s.labels.isEmpty() ? "Such dir ein Motiv – ich warte." : "Ich sehe " + w[0] + ". Halt drauf!";
            float area = (br - bl) * (bb - bt), cx = (bl + br) / 2, cy = (bt + bb) / 2;
            if (area < 0.05f) return "Näher an " + w[0] + " ran – füll das Bild!";
            boolean cut = bl < 0.015f || br > 0.985f || bt < 0.015f || bb > 0.985f;
            if (cut && area > 0.45f) return "Ein Schritt zurück – sonst ist " + w[1] + " angeschnitten.";
            // Nearest grid crossing (rule of thirds); big subjects may also sit in the middle.
            float bestD = Float.MAX_VALUE;
            for (int i = 1; i <= 2; i++) {
                for (int j = 1; j <= 2; j++) {
                    float px = i / 3f, py = j / 3f, d = (float) Math.hypot(cx - px, cy - py);
                    if (d < bestD) {
                        bestD = d;
                        tx = px;
                        ty = py;
                    }
                }
            }
            if (area > 0.35f && Math.hypot(cx - 0.5f, cy - 0.5f) < 0.08f) {
                tx = ty = -1;
                good = true;
                return "Schön mittig und gross. Perfekt – jetzt!";
            }
            if (bestD > 0.09f) {
                String where = (ty < 0.5f ? "oben " : "unten ") + (tx < 0.5f ? "links" : "rechts");
                return "Bring " + w[0] + " auf den Kreuzpunkt " + where + " – dem Pfeil nach.";
            }
            good = true;
            return "Genau so! " + capital(w[1]) + " sitzt auf dem Gitter. Jetzt!";
        }

        private String capital(String s) {
            return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float aspect = scene == null ? 0.75f : scene.aspect;
            // Where the preview sits (fit centre).
            float cw = w, ch = w / aspect;
            if (ch > h) {
                ch = h;
                cw = h * aspect;
            }
            content.set((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
            int lineCol = good && hint.startsWith("Genau") || good && hint.startsWith("Schön") ? 0xCC2BD96B : 0x66FFFFFF;
            // Thirds grid.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(lineCol);
            for (int i = 1; i <= 2; i++) {
                float x = content.left + content.width() * i / 3f, y = content.top + content.height() * i / 3f;
                c.drawLine(x, content.top, x, content.bottom, p);
                c.drawLine(content.left, y, content.right, y, p);
            }
            // Crossings, the target one pulsing in rust.
            long now = SystemClock.uptimeMillis();
            for (int i = 1; i <= 2; i++) {
                for (int j = 1; j <= 2; j++) {
                    float x = content.left + content.width() * i / 3f, y = content.top + content.height() * j / 3f;
                    boolean isTarget = Math.abs(tx - i / 3f) < 0.01f && Math.abs(ty - j / 3f) < 0.01f && hint.startsWith("Bring");
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(isTarget ? rust : lineCol);
                    float rr = isTarget ? dp(6) + dp(3) * (float) Math.abs(Math.sin(now / 260.0)) : dp(2.5f);
                    c.drawCircle(x, y, rr, p);
                }
            }
            boolean animate = hint.startsWith("Bring");
            // Subject box: corner brackets and its name.
            if (hasBox) {
                box.set(content.left + bl * content.width(), content.top + bt * content.height(),
                    content.left + br * content.width(), content.top + bb * content.height());
                lockOn(c, now);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2.5f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(good ? 0xFF2BD96B : 0xFFFFFFFF);
                float k = Math.min(dp(22), Math.min(box.width(), box.height()) / 3);
                path.reset();
                path.moveTo(box.left, box.top + k);
                path.lineTo(box.left, box.top);
                path.lineTo(box.left + k, box.top);
                path.moveTo(box.right - k, box.top);
                path.lineTo(box.right, box.top);
                path.lineTo(box.right, box.top + k);
                path.moveTo(box.right, box.bottom - k);
                path.lineTo(box.right, box.bottom);
                path.lineTo(box.right - k, box.bottom);
                path.moveTo(box.left + k, box.bottom);
                path.lineTo(box.left, box.bottom);
                path.lineTo(box.left, box.bottom - k);
                c.drawPath(path, p);
                if (scene != null && !scene.labels.isEmpty()) {
                    String name = wordFor(scene.labels)[1];
                    name = name.substring(name.indexOf(' ') + 1).toUpperCase(Locale.GERMAN);
                    p.setStyle(Paint.Style.FILL);
                    p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                    p.setTextSize(dp(11));
                    float tw = p.measureText(name);
                    float ly = Math.max(content.top + dp(18), box.top - dp(8));
                    p.setColor(0xAA000000);
                    c.drawRoundRect(box.left, ly - dp(14), box.left + tw + dp(14), ly + dp(5), dp(8), dp(8), p);
                    p.setColor(0xFFFFFFFF);
                    c.drawText(name, box.left + dp(7), ly, p);
                }
                // Arrow from the subject to its crossing.
                if (animate && tx >= 0) {
                    float sx = box.centerX(), sy = box.centerY();
                    float ex = content.left + tx * content.width(), ey = content.top + ty * content.height();
                    float d = (float) Math.hypot(ex - sx, ey - sy);
                    if (d > dp(30)) {
                        float ux = (ex - sx) / d, uy = (ey - sy) / d;
                        float ax = ex - ux * dp(12), ay = ey - uy * dp(12);
                        p.setStyle(Paint.Style.STROKE);
                        p.setColor(rust);
                        p.setStrokeWidth(dp(3));
                        p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(10), dp(8)}, -(now / 30f) % dp(18)));
                        c.drawLine(sx, sy, ax, ay, p);
                        p.setPathEffect(null);
                        path.reset();
                        path.moveTo(ax + ux * dp(10), ay + uy * dp(10));
                        path.lineTo(ax - uy * dp(8) - ux * dp(6), ay + ux * dp(8) - uy * dp(6));
                        path.lineTo(ax + uy * dp(8) - ux * dp(6), ay - ux * dp(8) - uy * dp(6));
                        path.close();
                        p.setStyle(Paint.Style.FILL);
                        c.drawPath(path, p);
                    }
                }
            }
            // Level: a line that turns with the phone, green when straight.
            if (upright && Math.abs(tiltSmooth) < 40) {
                boolean level = Math.abs(tiltSmooth) < 1.2f;
                float cx = content.centerX(), cy = content.centerY(), half = dp(46);
                c.save();
                c.rotate(level ? 0 : -tiltSmooth, cx, cy);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(level ? 0xFF2BD96B : 0xCCFFFFFF);
                c.drawLine(cx - half, cy, cx - dp(10), cy, p);
                c.drawLine(cx + dp(10), cy, cx + half, cy, p);
                c.restore();
                p.setColor(level ? 0xFF2BD96B : 0x66FFFFFF);
                c.drawLine(cx - half - dp(14), cy, cx - half - dp(4), cy, p);
                c.drawLine(cx + half + dp(4), cy, cx + half + dp(14), cy, p);
            }
            tunnel(c, now);
            drawZoomLook(c);
            drawMeta(c);
            // The character with its tip, above the controls.
            String say = now - shotAt < 1600 ? "Hab's! Liegt schon auf dem Globus." : hint;
            if (!say.isEmpty()) speak(c, w, h, VOICE[character] + say);
            if (animate || now - shotAt < 1600 || !upright || Math.abs(tiltSmooth) > 0.2f) postInvalidateOnAnimation();
            else postInvalidateDelayed(200);       // the 2× look and the numbers stay live
        }

        private Bitmap look;
        private long lookAt;

        /** Top right: the middle of the picture at 2×, so you see how the zoom would frame it. */
        private void drawZoomLook(Canvas c) {
            long now = SystemClock.uptimeMillis();
            if (now - lookAt > 180) {
                lookAt = now;
                Bitmap b = engine.snapshot();
                if (b != null) look = b;
            }
            if (look == null || look.isRecycled() || content.width() <= 0) return;
            float iw = content.width() * 0.34f, ih = iw * content.height() / content.width();
            float r = content.right - dp(12), t = content.top + dp(12);
            RectF dst = new RectF(r - iw, t, r, t + ih);
            int bw = look.getWidth(), bh = look.getHeight();
            android.graphics.Rect src = new android.graphics.Rect(bw / 4, bh / 4, bw * 3 / 4, bh * 3 / 4);
            c.save();
            path.reset();
            path.addRoundRect(dst, dp(12), dp(12), Path.Direction.CW);
            c.clipPath(path);
            c.drawBitmap(look, src, dst, null);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0xE6FFFFFF);
            c.drawRoundRect(dst, dp(12), dp(12), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC111110);
            c.drawRoundRect(dst.left + dp(8), dst.top + dp(8), dst.left + dp(40), dst.top + dp(28), dp(10), dp(10), p);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(11));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("2×", dst.left + dp(24), dst.top + dp(22), p);
            p.setTextAlign(Paint.Align.LEFT);
            // Where the 2× frame sits in the big picture.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0x80FFFFFF);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0));
            c.drawRect(content.left + content.width() / 4, content.top + content.height() / 4,
                content.right - content.width() / 4, content.bottom - content.height() / 4, p);
            p.setPathEffect(null);
        }

        /** Left: the camera's own numbers, one bubble each, stacked. */
        private void drawMeta(Canvas c) {
            List<String> lines = engine.meta();
            float x = content.left + dp(12), y = content.top + dp(14);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(11));
            for (String s : lines) {
                float tw = p.measureText(s) + dp(20);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xB3111110);
                c.drawRoundRect(x, y, x + tw, y + dp(26), dp(13), dp(13), p);
                p.setColor(0xFFFFFFFF);
                c.drawText(s, x + dp(10), y + dp(17.5f), p);
                y += dp(32);
            }
        }

        private long searchSince = -1;

        /*
         * While it's still searching for a subject: a time tunnel like the DeLorean charging up –
         * frames in orange, yellow and blue rush from the edges into the middle, faster and faster
         * the longer it searches, the edges darken. Once a subject is found the lock-on takes over.
         */
        private void tunnel(Canvas c, long now) {
            if (hasBox) {
                searchSince = -1;
                return;
            }
            if (searchSince < 0) searchSince = now;
            float t = (now - searchSince) / 1000f;
            float speed = Math.min(2.2f, 0.35f + t * 0.25f);            // charging up
            float ramp = Math.min(1f, t / 0.6f);
            float cx = content.centerX(), cy = content.centerY();
            // Vignette.
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.RadialGradient(cx, cy, Math.max(content.width(), content.height()) * 0.7f,
                new int[]{0x00000000, 0x00000000, (int) (150 * ramp) << 24}, new float[]{0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(content, p);
            p.setShader(null);
            int[] cols = {0xFFFF6A1F, 0xFFFFC21F, 0xFF3C8DFF};
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            int n = 9;
            for (int i = 0; i < n; i++) {
                float k = ((i / (float) n) + t * speed * 0.35f) % 1f;          // 0 = edge, 1 = centre
                float e = k * k;
                float hw = content.width() / 2f * (1 - e * 0.92f), hh = content.height() / 2f * (1 - e * 0.92f);
                int col = cols[i % 3];
                float a = (float) Math.sin(Math.PI * k) * ramp * 0.85f;
                p.setStrokeWidth(dp(5) * (1 - k * 0.7f));
                p.setColor((col & 0x00FFFFFF) | ((int) (255 * a) << 24));
                c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, dp(26) * (1 - e) + dp(6), dp(26) * (1 - e) + dp(6), p);
            }
            // Streaks flying out of the centre, like the time-travel flash.
            p.setStrokeWidth(dp(2));
            for (int i = 0; i < 16; i++) {
                double ang = i * Math.PI / 8 + (i % 2) * 0.2;
                float k = ((i * 0.37f) + t * speed * 0.9f) % 1f;
                float r0 = k * Math.max(content.width(), content.height()) * 0.6f, r1 = r0 + dp(30) + k * dp(50);
                p.setColor((cols[i % 3] & 0x00FFFFFF) | ((int) (180 * ramp * (1 - k)) << 24));
                c.drawLine(cx + (float) Math.cos(ang) * r0, cy + (float) Math.sin(ang) * r0,
                    cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1, p);
            }
            if (speed > 1.6f) {           // full charge: a quick white flash now and then
                float fl = (float) Math.max(0, Math.sin(t * 9) - 0.85) / 0.15f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(((int) (70 * fl) << 24) | 0xFFFFFF);
                c.drawRect(content, p);
            }
            postInvalidateOnAnimation();
        }

        private long lockAt = -1;
        private final RectF frame = new RectF();

        /*
         * Lock-on like the "Back to the Future" logo: six frames in its colours – orange, orange,
         * yellow, yellow, blue, blue – rush in from the edges one after the other, close in on the
         * subject and fade, a little hand-drawn wobble each. Again with every new subject.
         */
        private void lockOn(Canvas c, long now) {
            if (lockAt < 0) return;
            float t = (now - lockAt) / 1000f;
            if (t > 1.6f) return;
            int[] cols = {0xFFFF6A1F, 0xFFFF8A2A, 0xFFFFC21F, 0xFFFFD84A, 0xFF3C8DFF, 0xFF6FB0FF};
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            for (int i = 0; i < cols.length; i++) {
                float k = (t - i * 0.08f) / 0.7f;
                if (k <= 0) continue;
                float e = 1 - (1 - Math.min(1, k)) * (1 - Math.min(1, k)) * (1 - Math.min(1, k));
                // From around the whole picture to just round the subject (inner ones end tighter).
                float end = dp(6) + (cols.length - 1 - i) * dp(9);
                frame.set(content.left + (box.left - end - content.left) * e, content.top + (box.top - end - content.top) * e,
                    content.right - (content.right - box.right - end) * e, content.bottom - (content.bottom - box.bottom - end) * e);
                float fade = k < 1 ? 1 : Math.max(0, 1 - (k - 1) * 1.6f);
                if (fade <= 0) continue;
                p.setStrokeWidth(dp(4) - i * dp(0.3f));
                p.setColor((cols[i] & 0x00FFFFFF) | ((int) (230 * fade) << 24));
                float wob = dp(3) * (1 - Math.min(1, k));
                c.save();
                c.rotate((i % 2 == 0 ? 1 : -1) * 1.5f * (1 - Math.min(1, k)), frame.centerX(), frame.centerY());
                c.drawRoundRect(frame.left + wob, frame.top - wob, frame.right - wob, frame.bottom + wob, dp(18), dp(18), p);
                c.restore();
            }
            if (t < 1.6f) postInvalidateOnAnimation();
        }

        private void speak(Canvas c, float w, float h, String text) {
            float size = dp(54), bottom = h - dp(128);
            float x = dp(16), y = bottom - size;
            Drawable d = art[character];
            float bob = (float) Math.sin(SystemClock.uptimeMillis() / 300.0) * dp(2);
            float artW = size;
            if (d != null) {
                // Keep the drawing's own shape (96 × 64): it was squeezed into a square.
                float ar = d.getIntrinsicHeight() > 0 ? d.getIntrinsicWidth() / (float) d.getIntrinsicHeight() : 1f;
                artW = size * ar;
                d.setBounds((int) x, (int) (y + bob), (int) (x + artW), (int) (bottom + bob));
                d.draw(c);
            }
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(dp(14));
            float bx = x + artW + dp(10), maxW = Math.min(w - bx - dp(16), dp(420));
            List<String> rows = wrap(text, maxW - dp(24));
            float lh = p.getTextSize() * 1.3f, bh = rows.size() * lh + dp(16), bw = 0;
            for (String r : rows) bw = Math.max(bw, p.measureText(r));
            bw += dp(24);
            float top = bottom - bh;
            p.setStyle(Paint.Style.FILL);
            p.setColor(good ? 0xF2E9FBEF : 0xF2FAFAF8);
            c.drawRoundRect(bx, top, bx + bw, bottom, dp(14), dp(14), p);
            path.reset();
            path.moveTo(bx + 1, bottom - dp(22));
            path.lineTo(bx - dp(9), bottom - dp(12));
            path.lineTo(bx + 1, bottom - dp(8));
            path.close();
            c.drawPath(path, p);
            p.setColor(0xFF1A1A1A);
            float ty = top + dp(8) + p.getTextSize();
            for (String r : rows) {
                c.drawText(r, bx + dp(12), ty, p);
                ty += lh;
            }
        }

        private List<String> wrap(String text, float max) {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (p.measureText(next) > max && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (line.length() > 0) out.add(line.toString());
            return out;
        }
    }

    /** White ring shutter; green ring when the coach says it's right. */
    private final class Shutter extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Shutter() {
            super(CameraActivity.this);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f, cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(4));
            p.setColor(coach != null && coach.good ? 0xFF2BD96B : 0xFFFFFFFF);
            c.drawCircle(cx, cy, r - dp(3), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(isPressed() ? 0xFFDDDDDD : 0xFFFFFFFF);
            c.drawCircle(cx, cy, r - dp(10), p);
            postInvalidateDelayed(300);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView mono(float sp, int colour) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return t;
    }
}
