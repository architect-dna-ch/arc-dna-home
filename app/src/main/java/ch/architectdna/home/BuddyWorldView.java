package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Each buddy's own little world (double-tap the buddy):
 * DUCK – the pond: swim left/right (drag) and catch the bread kids throw; dodge the stones.
 * BEAR – the river under the waterfall: tap the jumping salmon, leave the bees alone, honey is a bonus.
 * Three hearts, the speed creeps up, the best score is kept.
 */
final class BuddyWorldView extends View {

    static final int DUCK = 0, BEAR = 1;

    interface Exit {
        void exit();
    }

    private final int kind;
    private final Drawable art;
    private final SharedPreferences prefs;
    private final Exit exit;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF(), closeR = new RectF(), againR = new RectF(), backR = new RectF();
    private final Random rnd = new Random();
    private final List<float[]> things = new ArrayList<>();     // x, y, vx, vy, type, born
    private final List<float[]> fx = new ArrayList<>();         // x, y, born, kind(0 splash,1 +text,2 -text), value
    private long born = SystemClock.uptimeMillis();
    private float lastT, spawnIn = 0.6f, px = -1, targetX = -1;
    private int score, hearts = 3;
    private boolean over;
    private float hurtAt = -9, gulpAt = -9;

    BuddyWorldView(Context c, int kind, Drawable art, SharedPreferences prefs, Exit exit) {
        super(c);
        this.kind = kind;
        this.art = art.mutate();
        this.prefs = prefs;
        this.exit = exit;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float t() {
        return (SystemClock.uptimeMillis() - born) / 1000f;
    }

    private String bestKey() {
        return kind == DUCK ? "worldDuckBest" : "worldBearBest";
    }

    private void restart() {
        born = SystemClock.uptimeMillis();
        lastT = 0;
        things.clear();
        fx.clear();
        score = 0;
        hearts = 3;
        over = false;
        spawnIn = 0.6f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), t = t(), dt = Math.min(0.05f, t - lastT);
        lastT = t;
        if (kind == DUCK) drawPond(c, w, h, t);
        else drawRiver(c, w, h, t);
        if (!over) step(w, h, t, dt);
        drawThings(c, w, h, t);
        drawPlayer(c, w, h, t);
        drawFx(c, t);
        hud(c, w, h);
        if (over) drawOver(c, w, h);
        postInvalidateOnAnimation();
    }

    /* ================= Worlds ================= */

    private float water(float h) {
        return kind == DUCK ? h * 0.62f : h * 0.74f;
    }

    private void drawPond(Canvas c, float w, float h, float t) {
        float wl = water(h);
        p.setShader(new LinearGradient(0, 0, 0, wl, 0xFFBFE6FF, 0xFFF3F8EC, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, wl, p);
        p.setShader(new LinearGradient(0, wl, 0, h, 0xFF4BA3C7, 0xFF1E5D7A, Shader.TileMode.CLAMP));
        c.drawRect(0, wl, w, h, p);
        p.setShader(null);
        // Far trees and the sun.
        p.setColor(0xFFFFE08A);
        c.drawCircle(w * 0.82f, h * 0.12f, dp(28), p);
        p.setColor(0xFF6FAF6A);
        for (int i = 0; i < 7; i++) c.drawCircle(w * (i / 6f), wl - dp(8), dp(34) + (i % 3) * dp(10), p);
        p.setColor(0xFF4E8F4B);
        for (int i = 0; i < 6; i++) c.drawCircle(w * (i / 5f) + dp(20), wl - dp(2), dp(22) + (i % 2) * dp(8), p);
        // Waves.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0x55FFFFFF);
        for (int row = 0; row < 6; row++) {
            float y = wl + dp(14) + row * dp(28);
            path.reset();
            for (float x = -dp(20); x <= w + dp(20); x += dp(10)) {
                float yy = y + (float) Math.sin(x / dp(22) + t * 2 + row) * dp(3);
                if (x < 0) path.moveTo(x, yy);
                else path.lineTo(x, yy);
            }
            c.drawPath(path, p);
        }
        p.setStyle(Paint.Style.FILL);
        // Lily pads and reeds.
        p.setColor(0xFF3E8E41);
        c.drawOval(w * 0.08f, wl + dp(60), w * 0.08f + dp(46), wl + dp(76), p);
        c.drawOval(w * 0.74f, wl + dp(110), w * 0.74f + dp(40), wl + dp(124), p);
        p.setColor(0xFFFF8FB1);
        c.drawCircle(w * 0.08f + dp(26), wl + dp(64), dp(6), p);
        p.setStrokeWidth(dp(3));
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFF5B7F3A);
        for (int i = 0; i < 5; i++) {
            float x = dp(8) + i * dp(9), sway = (float) Math.sin(t * 1.5 + i) * dp(4);
            c.drawLine(x, wl + dp(20), x + sway, wl - dp(60) - i * dp(6), p);
            c.drawLine(w - x, wl + dp(20), w - x - sway, wl - dp(56) - i * dp(5), p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawRiver(Canvas c, float w, float h, float t) {
        float wl = water(h);
        p.setShader(new LinearGradient(0, 0, 0, wl, 0xFF2E4A2C, 0xFF5D7A43, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, wl, p);
        p.setShader(null);
        // Rocks left and right, the waterfall in the middle.
        p.setColor(0xFF6E6A62);
        c.drawRoundRect(0, h * 0.18f, w * 0.3f, wl, dp(30), dp(30), p);
        c.drawRoundRect(w * 0.7f, h * 0.14f, w, wl, dp(30), dp(30), p);
        p.setColor(0xFF8F8A80);
        c.drawRoundRect(w * 0.04f, h * 0.24f, w * 0.22f, h * 0.34f, dp(20), dp(20), p);
        float fl = w * 0.3f, fr = w * 0.7f;
        p.setColor(0xFF9FD8F0);
        c.drawRect(fl, 0, fr, wl, p);
        p.setColor(0xCCFFFFFF);
        for (int i = 0; i < 9; i++) {
            float x = fl + (fr - fl) * (i + 0.5f) / 9f, off = ((t * 400 + i * 97) % (wl + dp(60))) - dp(60);
            c.drawRoundRect(x - dp(3), off, x + dp(3), off + dp(50), dp(3), dp(3), p);
        }
        // River.
        p.setShader(new LinearGradient(0, wl, 0, h, 0xFF3C8DB8, 0xFF1C4E6E, Shader.TileMode.CLAMP));
        c.drawRect(0, wl, w, h, p);
        p.setShader(null);
        p.setColor(0x66FFFFFF);
        for (int i = 0; i < 14; i++) {
            float x = ((i * 83 + t * 60) % (w + dp(40))) - dp(20), y = wl + dp(10) + (i % 4) * dp(24);
            c.drawRoundRect(x, y, x + dp(26), y + dp(3), dp(2), dp(2), p);
        }
        // Foam where the fall hits.
        p.setColor(0xDDFFFFFF);
        for (int i = 0; i < 8; i++) c.drawCircle(fl + (fr - fl) * (i / 7f), wl + (float) Math.sin(t * 8 + i) * dp(3), dp(10), p);
    }

    /* ================= Play ================= */

    private float playerX(float w) {
        if (px < 0) px = targetX = w / 2;
        return px;
    }

    private void step(float w, float h, float t, float dt) {
        float speed = 1 + Math.min(1.6f, t / 45f);
        if (kind == BEAR && t >= 60) {                // the bear fishes one minute
            end();
            return;
        }
        float wl = water(h);
        if (kind == DUCK) px += (targetX - px) * Math.min(1, dt * 10);
        spawnIn -= dt * speed;
        if (spawnIn <= 0) {
            spawnIn = kind == DUCK ? 0.75f + rnd.nextFloat() * 0.5f : 0.9f + rnd.nextFloat() * 0.6f;
            if (kind == DUCK) {
                float roll = rnd.nextFloat();
                int type = roll < 0.66f ? 0 : roll < 0.74f ? 1 : 2;            // bread, croissant, stone
                things.add(new float[]{dp(30) + rnd.nextFloat() * (w - dp(60)), -dp(20), (rnd.nextFloat() - 0.5f) * dp(40),
                    dp(120) + rnd.nextFloat() * dp(60), type, t});
            } else {
                float roll = rnd.nextFloat();
                int type = roll < 0.62f ? 0 : roll < 0.9f ? 1 : 2;              // salmon, bee, honey
                boolean left = rnd.nextBoolean();
                if (type == 0) {
                    float sx = left ? w * 0.25f : w * 0.75f;
                    things.add(new float[]{sx, wl + dp(10), (left ? 1 : -1) * (dp(70) + rnd.nextFloat() * dp(80)), -(dp(520) + rnd.nextFloat() * dp(160)), 0, t});
                } else {
                    float y = h * (0.2f + rnd.nextFloat() * 0.45f);
                    things.add(new float[]{left ? -dp(20) : w + dp(20), y, (left ? 1 : -1) * (dp(70) + rnd.nextFloat() * dp(50)), 0, type, t});
                }
            }
        }
        float duckY = wl - dp(14);
        for (int i = things.size() - 1; i >= 0; i--) {
            float[] o = things.get(i);
            o[0] += o[2] * dt * speed;
            o[1] += o[3] * dt * speed;
            if (kind == BEAR && o[4] == 0) o[3] += dp(620) * dt * speed;         // salmon arc
            if (kind == BEAR && o[4] == 1) o[1] += (float) Math.sin(t * 9 + i) * dp(1.2f);
            if (kind == DUCK) {
                if (o[1] > duckY - dp(26) && o[1] < duckY + dp(6) && Math.abs(o[0] - px) < dp(46)) {
                    things.remove(i);
                    if (o[4] == 2) {
                        hearts--;
                        hurtAt = t;
                        fx.add(new float[]{o[0], o[1], t, 2, 1});
                        if (hearts <= 0) end();
                    } else {
                        int v = o[4] == 1 ? 5 : 1;
                        score += v;
                        gulpAt = t;
                        fx.add(new float[]{o[0], o[1], t, 1, v});
                    }
                    continue;
                }
                if (o[1] > wl + dp(10)) {
                    fx.add(new float[]{o[0], wl + dp(6), t, 0, 0});
                    things.remove(i);
                }
            } else {
                if (o[1] > h + dp(30) || o[0] < -dp(60) || o[0] > w + dp(60)) things.remove(i);
            }
        }
    }

    private void end() {
        over = true;
        if (score > prefs.getInt(bestKey(), 0)) prefs.edit().putInt(bestKey(), score).apply();
    }

    private static final String[][] ICONS = {{"🍞", "🥐", "🪨"}, {"🐟", "🐝", "🍯"}};

    private void drawThings(Canvas c, float w, float h, float t) {
        p.setTextAlign(Paint.Align.CENTER);
        for (float[] o : things) {
            p.setTextSize(dp(kind == BEAR && o[4] == 0 ? 34 : 28));
            c.save();
            c.translate(o[0], o[1]);
            if (kind == BEAR && o[4] == 0) c.rotate(Math.max(-40, Math.min(40, o[3] / dp(14))) * (o[2] < 0 ? -1 : 1));
            if (kind == BEAR && o[4] != 2 && o[2] > 0) c.scale(-1, 1);       // emoji face left: turn them the way they fly
            else if (kind == DUCK) c.rotate((t - o[5]) * 90);
            c.drawText(ICONS[kind][(int) o[4]], 0, dp(10), p);
            c.restore();
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawPlayer(Canvas c, float w, float h, float t) {
        float wl = water(h);
        float size = kind == DUCK ? dp(110) : Math.min(w * 0.5f, dp(200));
        float x = kind == DUCK ? playerX(w) : w / 2, y = kind == DUCK ? wl + dp(18) + (float) Math.sin(t * 3) * dp(3) : h - dp(20);
        float ah = size * art.getIntrinsicHeight() / (float) Math.max(1, art.getIntrinsicWidth());
        boolean hurt = t - hurtAt < 0.4f, gulp = t - gulpAt < 0.2f;
        c.save();
        c.translate(x, y);
        if (hurt) c.rotate((float) Math.sin(t * 60) * 8);
        if (gulp) c.scale(1.08f, 0.94f);
        if (kind == DUCK && targetX < px - dp(2)) c.scale(-1, 1);          // faces where it swims
        art.setBounds((int) (-size / 2), (int) -ah, (int) (size / 2), 0);
        art.setAlpha(hurt ? 160 : 255);
        art.draw(c);
        c.restore();
        if (kind == DUCK) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0x88FFFFFF);
            c.drawOval(x - size * 0.55f, y - dp(6), x + size * 0.55f, y + dp(8), p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    private void drawFx(Canvas c, float t) {
        for (int i = fx.size() - 1; i >= 0; i--) {
            float[] e = fx.get(i);
            float age = t - e[2];
            if (age > 0.8f) {
                fx.remove(i);
                continue;
            }
            if (e[3] == 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(0xFFFFFFFF);
                p.setAlpha((int) (200 * (1 - age / 0.8f)));
                c.drawOval(e[0] - dp(10) - age * dp(40), e[1] - dp(4) - age * dp(6), e[0] + dp(10) + age * dp(40), e[1] + dp(4) + age * dp(6), p);
                p.setStyle(Paint.Style.FILL);
                p.setAlpha(255);
            } else {
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                p.setTextSize(dp(18));
                p.setColor(e[3] == 1 ? 0xFF2BBF6A : 0xFFE5482C);
                p.setAlpha((int) (255 * (1 - age / 0.8f)));
                c.drawText((e[3] == 1 ? "+" : "−") + (int) e[4] + (e[3] == 2 ? " ♥" : ""), e[0], e[1] - age * dp(50), p);
                p.setAlpha(255);
                p.setTextAlign(Paint.Align.LEFT);
                p.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private void hud(Canvas c, float w, float h) {
        float top = dp(54);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC121211);
        r.set(dp(14), top - dp(30), w - dp(14), top + dp(14));
        c.drawRoundRect(r, dp(18), dp(18), p);
        p.setColor(0xFFF4F2EC);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(dp(13));
        String name = kind == DUCK ? "🦆 TEICH" : "🐻 FLUSS";
        String clock = kind == BEAR ? "  ·  0:" + String.format(Locale.ROOT, "%02d", Math.max(0, 60 - (int) t())) : "";
        c.drawText(name + "  ·  " + score + clock + "  ·  BEST " + Math.max(score, prefs.getInt(bestKey(), 0)), dp(28), top, p);
        String hs = "♥♥♥".substring(0, Math.max(0, hearts)) + "♡♡♡".substring(0, 3 - Math.max(0, hearts));
        p.setColor(0xFFE5482C);
        c.drawText(hs, w - dp(110), top, p);
        p.setColor(0xFFF4F2EC);
        p.setTextSize(dp(18));
        closeR.set(w - dp(56), top - dp(30), w - dp(14), top + dp(14));
        c.drawText("✕", w - dp(42), top + dp(1), p);
        p.setTypeface(Typeface.DEFAULT);
        if (t() < 3.5f) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(15));
            p.setColor(0xEEFFFFFF);
            String tip = kind == DUCK ? "Wischen = schwimmen · Brot fangen 🍞 · Steinen ausweichen 🪨" : "Lachse antippen 🐟 · Bienen in Ruhe lassen 🐝 · Honig = +3 🍯";
            r.set(dp(20), h * 0.4f - dp(26), w - dp(20), h * 0.4f + dp(12));
            p.setColor(0xCC121211);
            c.drawRoundRect(r, dp(16), dp(16), p);
            p.setColor(0xFFF4F2EC);
            c.drawText(tip, w / 2, h * 0.4f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawOver(Canvas c, float w, float h) {
        p.setColor(0xB3000000);
        c.drawRect(0, 0, w, h, p);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xFFF4F2EC);
        p.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        p.setTextSize(dp(34));
        c.drawText(kind == DUCK ? "Satt! 🦆" : "Genug gefischt! 🐻", w / 2, h * 0.36f, p);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(dp(15));
        c.drawText(score + (kind == DUCK ? " Brotkrumen" : " Lachse") + " · Bestwert " + prefs.getInt(bestKey(), 0), w / 2, h * 0.36f + dp(36), p);
        againR.set(dp(40), h * 0.5f, w - dp(40), h * 0.5f + dp(56));
        backR.set(dp(40), h * 0.5f + dp(68), w - dp(40), h * 0.5f + dp(118));
        p.setColor(0xFFB5271E);
        c.drawRoundRect(againR, dp(16), dp(16), p);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(backR, dp(16), dp(16), p);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        c.drawText("NOCHMAL", againR.centerX(), againR.centerY() + dp(5), p);
        c.drawText("ZURÜCK ZUM HOME", backR.centerX(), backR.centerY() + dp(5), p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && closeR.contains(x, y)) {
            exit.exit();
            return true;
        }
        if (over) {
            if (a == MotionEvent.ACTION_UP) {
                if (againR.contains(x, y)) restart();
                else if (backR.contains(x, y)) exit.exit();
            }
            return true;
        }
        if (kind == DUCK) {
            targetX = Math.max(dp(40), Math.min(getWidth() - dp(40), x));
            return true;
        }
        if (a != MotionEvent.ACTION_DOWN) return true;
        float t = t();
        for (int i = things.size() - 1; i >= 0; i--) {
            float[] o = things.get(i);
            if (Math.hypot(o[0] - x, o[1] - y) > dp(36)) continue;
            things.remove(i);
            if (o[4] == 1) {
                hearts--;
                hurtAt = t;
                fx.add(new float[]{o[0], o[1], t, 2, 1});
                if (hearts <= 0) end();
            } else {
                int v = o[4] == 2 ? 3 : 1;
                score += v;
                gulpAt = t;
                fx.add(new float[]{o[0], o[1], t, 1, v});
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            return true;
        }
        return true;
    }
}
