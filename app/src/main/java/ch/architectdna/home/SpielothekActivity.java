package ch.architectdna.home;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * ARC-DNA Spielothek: three small games in the ARC look, each with its best score.
 * Darts – the aim wobbles like a real hand, tap to throw, 3 darts × 5 rounds.
 * Rakete – hold left / right to steer up through the asteroids, collect stars.
 * Tischtennis – top-down table, your bat follows the finger, first to 11 against the robot.
 */
public class SpielothekActivity extends Activity {

    private static final int MENU = 0, DARTS = 1, ROCKET = 2, PONG = 3;
    private static final int BG = 0xFF121211, CARD = 0xFF1C1C1A, LINE = 0xFF2C2C2A, INK = 0xFFEDEDE8, INK2 = 0xFFA5A5A0,
        INK3 = 0xFF6F6E69, RUST = 0xFFD9472F, GREEN = 0xFF2BBF6A, BLUE = 0xFF3C8DFF, YELLOW = 0xFFFFC400;

    private SharedPreferences prefs;
    private Stage stage;
    private int topInset, bottomInset;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        prefs = getSharedPreferences("spielothek", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        stage = new Stage();
        stage.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                topInset = i.top;
                bottomInset = i.bottom;
            }
            return in;
        });
        setContentView(stage);
    }

    @Override
    public void onBackPressed() {
        if (stage.mode != MENU) stage.go(MENU);
        else super.onBackPressed();
    }

    private final class Stage extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Random rnd = new Random();
        private final RectF r = new RectF();
        private final RectF[] cards = {new RectF(), new RectF(), new RectF()};
        private final RectF back = new RectF();
        int mode = MENU;
        private long last;

        Stage() {
            super(SpielothekActivity.this);
            setBackgroundColor(BG);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        void go(int m) {
            mode = m;
            if (m == DARTS) dartsReset();
            if (m == ROCKET) rocketReset();
            if (m == PONG) pongReset();
            last = 0;
            invalidate();
        }

        private int best(String k) {
            return prefs.getInt(k, 0);
        }

        private void record(String k, int v) {
            if (v > best(k)) prefs.edit().putInt(k, v).apply();
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.016f : Math.min(0.04f, (now - last) / 1000f);
            last = now;
            switch (mode) {
                case DARTS: darts(c, dt, now); break;
                case ROCKET: rocket(c, dt, now); break;
                case PONG: pong(c, dt, now); break;
                default: menu(c, now); break;
            }
            if (mode != MENU) {
                back.set(dp(12), topInset + dp(8), dp(64), topInset + dp(52));
                p.setStyle(Paint.Style.FILL);
                p.setColor(INK);
                p.setTextSize(dp(22));
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("←", back.centerX(), back.centerY() + dp(8), p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            postInvalidateOnAnimation();
        }

        private void label(Canvas c, String s, float x, float y, float size, int col, boolean bold, Paint.Align a) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            p.setTextSize(size);
            p.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.MONOSPACE);
            p.setTextAlign(a);
            c.drawText(s, x, y, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        /* ---------------- menu ---------------- */

        private void menu(Canvas c, long now) {
            float w = getWidth(), h = getHeight(), top = topInset + dp(24);
            label(c, "— ARC-DNA SPIELOTHEK", dp(22), top + dp(10), dp(11), INK3, false, Paint.Align.LEFT);
            label(c, "Eine Runde?", dp(22), top + dp(52), dp(30), INK, true, Paint.Align.LEFT);
            String[] names = {"Darts", "Rakete", "Tischtennis"};
            String[] subs = {"3 Pfeile × 5 Runden · ruhige Hand", "Halten links / rechts · Sterne sammeln", "Bis 11 gegen den Robo"};
            String[] keys = {"darts", "rocket", "pong"};
            float ch = Math.min(dp(170), (h - top - dp(90) - bottomInset - dp(24)) / 3 - dp(12));
            for (int i = 0; i < 3; i++) {
                float y = top + dp(80) + i * (ch + dp(12));
                cards[i].set(dp(20), y, w - dp(20), y + ch);
                p.setStyle(Paint.Style.FILL);
                p.setColor(CARD);
                c.drawRoundRect(cards[i], dp(22), dp(22), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.5f));
                p.setColor(LINE);
                c.drawRoundRect(cards[i], dp(22), dp(22), p);
                float ix = cards[i].left + ch * 0.5f, iy = cards[i].centerY(), ir = ch * 0.32f;
                if (i == 0) board(c, ix, iy, ir);
                else if (i == 1) rocketShip(c, ix, iy + (float) Math.sin(now / 300.0) * dp(4), ir * 0.9f, -90, true);
                else miniTable(c, ix, iy, ir, now);
                float tx = cards[i].left + ch + dp(4);
                label(c, names[i], tx, iy - dp(4), dp(24), INK, true, Paint.Align.LEFT);
                label(c, subs[i].toUpperCase(Locale.GERMAN), tx, iy + dp(20), dp(10), INK3, false, Paint.Align.LEFT);
                label(c, "REKORD " + best(keys[i]), tx, iy + dp(42), dp(11), RUST, false, Paint.Align.LEFT);
            }
        }

        private void miniTable(Canvas c, float x, float y, float r, long now) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1F5C3A);
            c.drawRoundRect(x - r * 0.7f, y - r, x + r * 0.7f, y + r, dp(4), dp(4), p);
            p.setColor(0xFFFFFFFF);
            c.drawRect(x - r * 0.7f, y - dp(1), x + r * 0.7f, y + dp(1), p);
            float by = y + (float) Math.sin(now / 250.0) * r * 0.8f;
            p.setColor(0xFFFFB627);
            c.drawCircle(x + (float) Math.cos(now / 400.0) * r * 0.4f, by, dp(4), p);
        }

        /* ---------------- darts ---------------- */

        private static final int[] ORDER = {20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5};
        private int dartRound, dartThrown, dartScore;
        private final List<float[]> darts = new ArrayList<>();      // x, y (relative to board, ×radius), points
        private String lastHit = "";
        private long hitAt;
        private float aimX, aimY;       // where the finger aims (screen), the wobble adds on top
        private boolean aiming;

        private void dartsReset() {
            dartRound = 1;
            dartThrown = 0;
            dartScore = 0;
            darts.clear();
            lastHit = "";
            aiming = false;
        }

        private void board(Canvas c, float cx, float cy, float R) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF111110);
            c.drawCircle(cx, cy, R * 1.12f, p);
            float[] rings = {1f, 0.95f, 0.62f, 0.57f, 0.1f, 0.04f};
            for (int s = 0; s < 20; s++) {
                float a0 = -90 - 9 + s * 18;
                boolean dark = s % 2 == 0;
                for (int k = 0; k < 4; k++) {
                    float ro = R * rings[k], ri = R * rings[k + 1];
                    boolean scoring = k == 0 || k == 2;
                    p.setColor(scoring ? (dark ? RUST : GREEN) : (dark ? 0xFF1A1A1A : 0xFFE8E2D0));
                    path.reset();
                    r.set(cx - ro, cy - ro, cx + ro, cy + ro);
                    path.arcTo(r, a0, 18);
                    r.set(cx - ri, cy - ri, cx + ri, cy + ri);
                    path.arcTo(r, a0 + 18, -18);
                    path.close();
                    c.drawPath(path, p);
                }
            }
            p.setColor(GREEN);
            c.drawCircle(cx, cy, R * 0.1f, p);
            p.setColor(RUST);
            c.drawCircle(cx, cy, R * 0.04f, p);
            if (R > dp(60)) {
                for (int s = 0; s < 20; s++) {
                    double a = Math.toRadians(-90 + s * 18);
                    label(c, String.valueOf(ORDER[s]), cx + (float) Math.cos(a) * R * 1.06f, cy + (float) Math.sin(a) * R * 1.06f + dp(4),
                        dp(10), INK, true, Paint.Align.CENTER);
                }
            }
        }

        private int[] scoreAt(float dx, float dy) {      // relative to radius
            float d = (float) Math.hypot(dx, dy);
            if (d <= 0.04f) return new int[]{50, 0};
            if (d <= 0.1f) return new int[]{25, 0};
            if (d > 1f) return new int[]{0, 0};
            double a = Math.toDegrees(Math.atan2(dy, dx)) + 90 + 9;
            a = (a % 360 + 360) % 360;
            int seg = ORDER[(int) (a / 18) % 20];
            int mult = d > 0.95f ? 2 : d > 0.57f && d <= 0.62f ? 3 : 1;
            return new int[]{seg * mult, mult};
        }

        private void darts(Canvas c, float dt, long now) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2, cy = h * 0.47f, R = Math.min(w, h) * 0.38f;
            label(c, "DARTS · RUNDE " + Math.min(5, dartRound) + "/5", w / 2, topInset + dp(36), dp(12), INK3, false, Paint.Align.CENTER);
            label(c, String.valueOf(dartScore), w / 2, topInset + dp(86), dp(44), INK, true, Paint.Align.CENTER);
            board(c, cx, cy, R);
            for (float[] d : darts) {
                float x = cx + d[0] * R, y = cy + d[1] * R;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(0xFFC4C4BE);
                c.drawLine(x, y, x + dp(10), y + dp(26), p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(BLUE);
                c.drawCircle(x, y, dp(4), p);
                path.reset();
                path.moveTo(x + dp(10), y + dp(26));
                path.lineTo(x + dp(2), y + dp(40));
                path.lineTo(x + dp(18), y + dp(36));
                path.close();
                c.drawPath(path, p);
            }
            // The aim: wobbles like a hand (more after each dart), drawn while the finger is down.
            if (aiming && dartRound <= 5) {
                float t = now / 1000f, wob = R * (0.05f + 0.02f * dartThrown);
                float ax = aimX + (float) (Math.sin(t * 2.3) + Math.sin(t * 3.7) * 0.5) * wob;
                float ay = aimY - dp(70) + (float) (Math.cos(t * 2.9) + Math.sin(t * 5.1) * 0.4) * wob;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(YELLOW);
                c.drawCircle(ax, ay, dp(10), p);
                c.drawLine(ax - dp(16), ay, ax + dp(16), ay, p);
                c.drawLine(ax, ay - dp(16), ax, ay + dp(16), p);
                curAim[0] = ax;
                curAim[1] = ay;
            }
            if (now - hitAt < 1200 && !lastHit.isEmpty()) {
                label(c, lastHit, w / 2, cy + R + dp(56), dp(26), YELLOW, true, Paint.Align.CENTER);
            }
            String hint = dartRound > 5 ? "FERTIG · " + dartScore + " PUNKTE · TIPPEN = NOCHMAL"
                : "FINGER HALTEN = ZIELEN · LOSLASSEN = WERFEN · PFEIL " + (dartThrown + 1) + "/3";
            label(c, hint, w / 2, h - bottomInset - dp(28), dp(11), INK2, false, Paint.Align.CENTER);
        }

        private final float[] curAim = new float[2];

        private void dartsTouch(MotionEvent e) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h * 0.47f, R = Math.min(w, h) * 0.38f;
            int a = e.getActionMasked();
            if (dartRound > 5) {
                if (a == MotionEvent.ACTION_UP) dartsReset();
                return;
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                aiming = true;
                aimX = e.getX();
                aimY = e.getY();
            } else if (a == MotionEvent.ACTION_UP && aiming) {
                aiming = false;
                float dx = (curAim[0] - cx) / R, dy = (curAim[1] - cy) / R;
                int[] sc = scoreAt(dx, dy);
                darts.add(new float[]{dx, dy});
                dartScore += sc[0];
                lastHit = sc[0] == 50 ? "BULLSEYE! 50" : sc[0] == 25 ? "BULL 25" : sc[0] == 0 ? "DANEBEN"
                    : (sc[1] == 3 ? "TRIPLE " : sc[1] == 2 ? "DOUBLE " : "") + sc[0];
                hitAt = SystemClock.uptimeMillis();
                performHapticFeedback(sc[0] >= 50 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
                dartThrown++;
                if (dartThrown == 3) {
                    dartThrown = 0;
                    dartRound++;
                    postDelayed(darts::clear, 700);
                    if (dartRound > 5) record("darts", dartScore);
                }
            }
        }

        /* ---------------- rocket ---------------- */

        private float shipX, speed, height;
        private int stars, steer;
        private boolean crashed;
        private final List<float[]> rocks = new ArrayList<>();     // x, y, r, isStar
        private final List<float[]> flames = new ArrayList<>();

        private void rocketReset() {
            shipX = 0.5f;
            speed = 1;
            height = 0;
            stars = 0;
            crashed = false;
            rocks.clear();
            flames.clear();
        }

        private void rocketShip(Canvas c, float x, float y, float s, float ang, boolean flame) {
            c.save();
            c.translate(x, y);
            c.rotate(ang + 90);
            if (flame) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFF9A2B);
                path.reset();
                path.moveTo(-s * 0.22f, s * 0.55f);
                path.lineTo(0, s * (0.9f + rnd.nextFloat() * 0.3f));
                path.lineTo(s * 0.22f, s * 0.55f);
                path.close();
                c.drawPath(path, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(RUST);
            path.reset();
            path.moveTo(-s * 0.45f, s * 0.6f);
            path.lineTo(-s * 0.25f, s * 0.15f);
            path.lineTo(s * 0.25f, s * 0.15f);
            path.lineTo(s * 0.45f, s * 0.6f);
            path.close();
            c.drawPath(path, p);
            p.setColor(0xFFF4F2EC);
            path.reset();
            path.moveTo(0, -s * 0.75f);
            path.quadTo(s * 0.32f, -s * 0.3f, s * 0.26f, s * 0.55f);
            path.lineTo(-s * 0.26f, s * 0.55f);
            path.quadTo(-s * 0.32f, -s * 0.3f, 0, -s * 0.75f);
            c.drawPath(path, p);
            p.setColor(BLUE);
            c.drawCircle(0, -s * 0.1f, s * 0.13f, p);
            c.restore();
        }

        private void rocket(Canvas c, float dt, long now) {
            float w = getWidth(), h = getHeight();
            // Stars in the background scroll by.
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 40; i++) {
                float sx = (i * 97 % 100) / 100f * w, sy = ((i * 53 % 100) / 100f * h + height * 0.6f * (1 + i % 3)) % h;
                p.setColor(0x55FFFFFF);
                c.drawCircle(sx, sy, dp(1 + i % 2), p);
            }
            float sy = h * 0.74f, size = dp(30);
            if (!crashed) {
                speed = Math.min(4f, 1 + height / 4000f);
                height += speed * dp(220) * dt;
                shipX = Math.max(0.06f, Math.min(0.94f, shipX + steer * dt * 0.9f));
                if (rnd.nextFloat() < dt * 1.4f * speed) {
                    boolean star = rnd.nextInt(4) == 0;
                    rocks.add(new float[]{rnd.nextFloat() * w, -dp(40), star ? dp(10) : dp(16 + rnd.nextInt(22)), star ? 1 : 0});
                }
            }
            float sx = shipX * w;
            for (int i = rocks.size() - 1; i >= 0; i--) {
                float[] rk = rocks.get(i);
                if (!crashed) rk[1] += speed * dp(220) * dt;
                if (rk[1] > h + dp(60)) {
                    rocks.remove(i);
                    continue;
                }
                if (rk[3] == 1) {
                    p.setColor(YELLOW);
                    drawStar(c, rk[0], rk[1], rk[2], now);
                } else {
                    p.setColor(0xFF6B5A4A);
                    c.drawCircle(rk[0], rk[1], rk[2], p);
                    p.setColor(0xFF4A3E33);
                    c.drawCircle(rk[0] - rk[2] * 0.3f, rk[1] - rk[2] * 0.2f, rk[2] * 0.25f, p);
                }
                if (!crashed && Math.hypot(rk[0] - sx, rk[1] - sy) < rk[2] + size * 0.4f) {
                    if (rk[3] == 1) {
                        stars++;
                        rocks.remove(i);
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    } else {
                        crashed = true;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        record("rocket", score());
                    }
                }
            }
            if (crashed) {
                for (int k = 0; k < 14; k++) {
                    p.setColor(k % 2 == 0 ? 0xFFFF9A2B : RUST);
                    double a = k * Math.PI / 7;
                    float rr = dp(18) + (float) Math.abs(Math.sin(now / 120.0 + k)) * dp(24);
                    c.drawCircle(sx + (float) Math.cos(a) * rr, sy + (float) Math.sin(a) * rr, dp(7), p);
                }
                label(c, "BUMM!", w / 2, h * 0.42f, dp(44), RUST, true, Paint.Align.CENTER);
                label(c, score() + " PUNKTE · TIPPEN = NOCHMAL", w / 2, h * 0.42f + dp(36), dp(12), INK2, false, Paint.Align.CENTER);
            } else {
                rocketShip(c, sx, sy, size, -90 + steer * 14, true);
            }
            label(c, (int) (height / dp(10)) + " M · ★ " + stars, w / 2, topInset + dp(40), dp(18), INK, true, Paint.Align.CENTER);
            if (!crashed) label(c, "LINKS / RECHTS HALTEN = LENKEN", w / 2, h - bottomInset - dp(28), dp(11), INK3, false, Paint.Align.CENTER);
        }

        private int score() {
            return (int) (height / dp(10)) + stars * 50;
        }

        private void drawStar(Canvas c, float x, float y, float r, long now) {
            path.reset();
            for (int k = 0; k < 10; k++) {
                double a = Math.PI / 5 * k + now / 600.0;
                float rr = k % 2 == 0 ? r : r * 0.45f;
                float px = x + (float) Math.cos(a) * rr, py = y + (float) Math.sin(a) * rr;
                if (k == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            path.close();
            c.drawPath(path, p);
        }

        private void rocketTouch(MotionEvent e) {
            int a = e.getActionMasked();
            if (crashed) {
                if (a == MotionEvent.ACTION_UP) rocketReset();
                return;
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) steer = e.getX() < getWidth() / 2f ? -1 : 1;
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) steer = 0;
        }

        /* ---------------- table tennis ---------------- */

        private float bx, by, bvx, bvy, spin, youX, botX, lastYouX;
        private int youPts, botPts;
        private long serveAt;
        private String pongMsg = "";

        private void pongReset() {
            youPts = 0;
            botPts = 0;
            youX = botX = 0.5f;
            serve(true);
        }

        private void serve(boolean toBot) {
            bx = 0.5f;
            by = 0.5f;
            bvx = (rnd.nextFloat() - 0.5f) * 0.4f;
            bvy = toBot ? -0.55f : 0.55f;
            spin = 0;
            serveAt = SystemClock.uptimeMillis() + 900;
        }

        private void pong(Canvas c, float dt, long now) {
            float w = getWidth(), h = getHeight();
            float tl = dp(24), tr = w - dp(24), tt = topInset + dp(70), tb = h - bottomInset - dp(60);
            float tw = tr - tl, th = tb - tt;
            // Table.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1F5C3A);
            r.set(tl, tt, tr, tb);
            c.drawRoundRect(r, dp(8), dp(8), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0xFFF4F2EC);
            c.drawRoundRect(r, dp(8), dp(8), p);
            p.setStrokeWidth(dp(1.5f));
            c.drawLine(w / 2, tt, w / 2, tb, p);
            p.setStrokeWidth(dp(4));
            p.setColor(0xCCFFFFFF);
            c.drawLine(tl - dp(8), tt + th / 2, tr + dp(8), tt + th / 2, p);       // the net
            boolean over = youPts >= 11 || botPts >= 11;
            if (!over && now > serveAt) {
                bx += (bvx + spin * 0.6f) * dt;
                by += bvy * dt;
                spin *= 0.985f;
                if (bx < 0.03f || bx > 0.97f) {
                    bvx = -bvx;
                    bx = Math.max(0.03f, Math.min(0.97f, bx));
                }
                // The robot follows, a bit slow, gets better with its points behind.
                float speed = 0.55f + 0.05f * Math.max(0, youPts - botPts);
                botX += Math.max(-speed * dt, Math.min(speed * dt, bx - botX));
                float padW = 0.22f;
                if (bvy > 0 && by > 0.92f && by < 0.98f && Math.abs(bx - youX) < padW / 2 + 0.02f) {
                    bvy = -Math.min(1.6f, Math.abs(bvy) * 1.06f);
                    bvx += (bx - youX) * 2.2f;
                    spin = (youX - lastYouX) / Math.max(0.001f, dt) * 0.05f;          // a swipe puts spin on it
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                if (bvy < 0 && by < 0.08f && by > 0.02f && Math.abs(bx - botX) < padW / 2 + 0.02f) {
                    bvy = Math.min(1.5f, Math.abs(bvy) * 1.04f);
                    bvx += (bx - botX) * 2f + (rnd.nextFloat() - 0.5f) * 0.3f;
                }
                if (by > 1.04f) {
                    botPts++;
                    pongMsg = "PUNKT ROBO";
                    serve(false);
                } else if (by < -0.04f) {
                    youPts++;
                    pongMsg = "PUNKT DU!";
                    serve(true);
                }
                if (youPts >= 11) record("pong", youPts - botPts);
            }
            lastYouX = youX;
            // Bats.
            float bw = tw * 0.22f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(RUST);
            c.drawRoundRect(tl + youX * tw - bw / 2, tb - dp(18), tl + youX * tw + bw / 2, tb - dp(6), dp(6), dp(6), p);
            p.setColor(BLUE);
            c.drawRoundRect(tl + botX * tw - bw / 2, tt + dp(6), tl + botX * tw + bw / 2, tt + dp(18), dp(6), dp(6), p);
            // Ball with a shadow; it looks bigger mid-table (the arc).
            float lift = (float) Math.sin(Math.PI * Math.max(0, Math.min(1, by))) * dp(5);
            p.setColor(0x55000000);
            c.drawCircle(tl + bx * tw + dp(4), tt + by * th + dp(4), dp(7), p);
            p.setColor(0xFFFFB627);
            c.drawCircle(tl + bx * tw, tt + by * th, dp(7) + lift * 0.4f, p);
            label(c, "ROBO " + botPts + "  :  " + youPts + " DU", w / 2, topInset + dp(44), dp(20), INK, true, Paint.Align.CENTER);
            if (over) {
                boolean won = youPts >= 11;
                label(c, won ? "GEWONNEN! 🏓" : "Der Robo gewinnt.", w / 2, h / 2 - dp(30), dp(34), won ? GREEN : RUST, true, Paint.Align.CENTER);
                label(c, "TIPPEN = NOCHMAL", w / 2, h / 2 + dp(4), dp(12), INK2, false, Paint.Align.CENTER);
            } else if (now < serveAt && !pongMsg.isEmpty()) {
                label(c, pongMsg, w / 2, h / 2 - dp(30), dp(22), YELLOW, true, Paint.Align.CENTER);
            }
            label(c, "FINGER UNTEN = SCHLÄGER · WISCHEN BEIM SCHLAG = SPIN", w / 2, h - bottomInset - dp(24), dp(10), INK3, false, Paint.Align.CENTER);
        }

        private void pongTouch(MotionEvent e) {
            if (youPts >= 11 || botPts >= 11) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) pongReset();
                return;
            }
            float tl = dp(24), tw = getWidth() - dp(48);
            youX = Math.max(0.08f, Math.min(0.92f, (e.getX() - tl) / tw));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            if (mode != MENU && e.getActionMasked() == MotionEvent.ACTION_UP && back.contains(x, y)) {
                go(MENU);
                return true;
            }
            switch (mode) {
                case DARTS: dartsTouch(e); break;
                case ROCKET: rocketTouch(e); break;
                case PONG: pongTouch(e); break;
                default:
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        for (int i = 0; i < 3; i++) if (cards[i].contains(x, y)) go(i + 1);
                    }
                    break;
            }
            return true;
        }
    }
}
