package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dino visit (every 5 minutes on the home screen, about 37 s): a dinosaur stomps in from the
 * right, stops in the middle, turns to face you and roars; the little robot flies in and
 * translates. Then it gobbles the buddy, the robot tazes it, it spits out a different buddy and
 * stomps off to the left with a last word. Drawn in the ARC-DNA line style; touches pass through.
 */
final class DinoView extends View {

    interface Host {
        /** Buddy's feet centre in this view's coordinates: {x, y}. */
        float[] buddy();

        void hideBuddy();

        /** The next buddy pops out and introduces itself (afterLine: what it says after the intro). */
        void swapBuddy(String afterLine);

        void buddySay(String text);

        void stomp();

        void chomp();

        void done();

        /** Black hole: pull the home towards (x, y), k = 0 … 1. */
        void vacuum(float k, float x, float y);

        /** The end of the black hole: lock the phone. */
        void lockNow();

        /** Fight: the dino's level (1 … 99) and one up after a win. */
        int level();

        void levelUp();

        /** Flight: one app tile flies into the dino's mouth at (x, y); false when nothing is left. */
        boolean eatOne(float x, float y);

        /** Everything the dino ate comes back. */
        void restoreEaten();

        /** The dino bit through the music: pause it. */
        void cutMusic();
    }

    /* ================= Fight: tap the dino ================= */

    private static final int FIGHT = 0, KO = 1, FLIGHT = 2, UPLOAD = 3, SAVED = 4, LOST = 5;
    private long fightAt = -1;
    private int fstate, level, eaten, cutAt, hurt;
    private float hp, maxHp, timeLimit, phaseAt, lastZap, nextEat, knock, knockY, lastFt;
    private boolean buddyEaten;
    private final RectF dinoRect = new RectF();
    private final List<float[]> pops = new ArrayList<>();       // x, y, time, hit?
    private final float[] fold = new float[4];                  // 0 … dp(120): TL, TR, BR, BL
    private final boolean[] folded = new boolean[4];
    private int dragCorner = -1;
    private float warnAt = -10;

    /** Where the dino stands on screen this frame (for taps). */
    private void markDino(float x, float g, float k, float sc) {
        dinoRect.set(x - 72 * k * sc, g - 104 * k * sc, x + 86 * k * sc, g);
    }

    private void startFight() {
        fightAt = SystemClock.uptimeMillis();
        level = Math.max(1, Math.min(99, host.level()));
        maxHp = hp = 12 + level * 1.6f;
        timeLimit = 20 + level * 0.2f;
        fstate = FIGHT;
        lastZap = 0;
        buddyEaten = !vacuum && fired[3] && !fired[4];
        host.vacuum(0, 0, 0);
        host.stomp();
        invalidate();
    }

    private void endFight() {
        host.vacuum(0, 0, 0);
        if (buddyEaten) host.swapBuddy("Danke fürs Retten!");
        post(host::done);
    }

    private boolean fightTouch(android.view.MotionEvent e) {
        int a = e.getActionMasked();
        float x = e.getX(), y = e.getY(), w = getWidth(), h = getHeight();
        if (fstate == FIGHT && a == android.view.MotionEvent.ACTION_DOWN) {
            float ft = (SystemClock.uptimeMillis() - fightAt) / 1000f;
            boolean hit = dinoRect.contains(x, y);
            if (hit) {
                hp = Math.max(0, hp - 1);
                knock = (x < dinoRect.centerX() ? 1 : -1) * dp(8);
                knockY = dp(46);             // every kick knocks it down a bit, it springs back up
                host.stomp();
            }
            pops.add(new float[]{x, y, ft, hit ? 1 : 0});
            return true;
        }
        if (fstate == FLIGHT || fstate == UPLOAD) {
            float[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                dragCorner = -1;
                for (int i = 0; i < 4; i++) {
                    if (!folded[i] && Math.hypot(x - corners[i][0], y - corners[i][1]) < dp(90)) dragCorner = i;
                }
            } else if (a == android.view.MotionEvent.ACTION_MOVE && dragCorner >= 0) {
                fold[dragCorner] = Math.min(dp(150), (float) Math.hypot(x - corners[dragCorner][0], y - corners[dragCorner][1]) * 0.8f);
            } else if ((a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) && dragCorner >= 0) {
                int i = dragCorner;
                dragCorner = -1;
                if (fold[i] > dp(85) && foldAllowed(i)) {
                    folded[i] = true;
                    fold[i] = dp(120);
                    host.chomp();
                } else {
                    if (fold[i] > dp(85)) warnAt = (SystemClock.uptimeMillis() - fightAt) / 1000f;
                    fold[i] = 0;
                }
            }
            return true;
        }
        return true;
    }

    /** Diagonals first: any corner, then its opposite; then the other two. */
    private boolean foldAllowed(int i) {
        int n = 0;
        for (boolean f : folded) if (f) n++;
        if (n == 1) return folded[(i + 2) % 4];
        return true;
    }

    private void drawFight(Canvas c, long now, float w, float h, float ground, float k, float mid) {
        float ft = (now - fightAt) / 1000f, dt = Math.max(0, Math.min(0.05f, ft - lastFt));
        lastFt = ft;
        knock *= 0.8f;
        knockY *= 0.86f;
        ground = h * 0.62f;             // the whole fight plays mid-screen
        if (fstate == FIGHT) {
            // The fight happens mid-screen: up where you can reach it; higher levels also hop up and down.
            float hop = h * 0.12f * Math.min(1, level / 50f) * (float) Math.sin(ft * (0.5f + level * 0.01f) * Math.PI * 2 + 1);
            ground += hop + knockY;
            // The dino dodges more and faster the higher its level; above 30 it heals a little.
            float amp = w * 0.32f * Math.min(1, level / 40f), freq = 0.35f + level * 0.012f;
            float fx = mid + amp * (float) Math.sin(ft * freq * Math.PI * 2) + knock;
            boolean right = Math.cos(ft * freq * Math.PI * 2) > 0 && amp > 0;
            if (level > 30) hp = Math.min(maxHp, hp + (level - 30) * 0.012f * dt);
            if (ft - lastZap > 1.3f && ft > 1) {
                lastZap = ft;
                hp = Math.max(0, hp - 0.35f);           // the robot helps a bit
            }
            hurt = hp / maxHp > 0.8f ? 0 : hp / maxHp > 0.6f ? 1 : hp / maxHp > 0.4f ? 2 : hp / maxHp > 0.2f ? 3 : 4;
            c.save();
            if (hurt >= 4) c.rotate(6 * (float) Math.sin(ft * 5), fx, ground);
            drawSide(c, fx, ground, k, Math.abs(knock) > dp(2) ? 0.7f : 0.15f, ft, amp > 0, !right);
            c.restore();
            if (hurt >= 4) smoke(c, fx, ground, k, ft);
            markDino(fx, ground, k, 1);
            float rx = Math.min(w - dp(40), fx + 100 * k), ry = ground - 150 * k + (float) Math.sin(ft * 3) * dp(4);
            drawRobot(c, rx, ry, dp(1), ft - lastZap < 0.2f);
            if (ft - lastZap < 0.2f) zap(c, rx - dp(22), ry + dp(10), fx, ground - 55 * k);
            drawPops(c, ft);
            hud(c, w, "DINO · LV " + level, hp / maxHp, Math.max(0, timeLimit - ft), rust);
            if (ft < 2.2f) bubble(c, "🤖 Hau ihn! Ich helfe!", rx, ry - dp(30), false, false);
            if (hp <= 0) {
                fstate = KO;
                phaseAt = ft;
                host.levelUp();
                host.chomp();
            } else if (ft >= timeLimit) {
                fstate = FLIGHT;
                phaseAt = ft;
                nextEat = ft + 0.8f;
                cutAt = 4 + rnd.nextInt(5);
            }
        } else if (fstate == KO) {
            float d = ft - phaseAt, fall = ease(Math.min(1, d / 0.5f));
            hurt = 5;
            c.save();
            c.rotate(180 * fall, mid, ground - 47 * k * fall);
            dead = fall > 0.5f;
            drawSide(c, mid, ground, k, 0.4f, ft, false, true);
            dead = false;
            c.restore();
            // Stars circling.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFC400);
            for (int i = 0; i < 5; i++) {
                double a = ft * 4 + i * Math.PI * 2 / 5;
                c.drawCircle(mid + (float) Math.cos(a) * 30 * k, ground - 70 * k + (float) Math.sin(a) * 8 * k, 4 * k, p);
            }
            bubble(c, "K.O.!", mid, ground - 90 * k, true, true);
            bubble(c, "🤖 Gewonnen! Dino jetzt Level " + Math.min(99, level + 1) + ".", w / 2, dp(150), false, false);
            if (d > 2.6f) {
                fight(false);
                endFight();
                return;
            }
        } else if (fstate == FLIGHT || fstate == UPLOAD) {
            // It gets away and eats the screen: fold the 4 corners (diagonals first), the robot saves it to the cloud.
            float mx = mid - 54 * k, my = ground - 76 * k;
            if (ft >= nextEat) {
                nextEat = ft + Math.max(0.45f, 1.1f - level * 0.006f);
                if (host.eatOne(mx, my)) {
                    eaten++;
                    host.chomp();
                    if (eaten == cutAt) host.cutMusic();
                }
                if (eaten >= 14) {
                    fstate = LOST;
                    phaseAt = ft;
                }
            }
            look = true;
            drawSide(c, mid, ground, k, (float) Math.abs(Math.sin(ft * 8)), ft, false, true);
            look = false;
            if (eaten >= cutAt && eaten < cutAt + 2) bubble(c, "♪ … *KNIRSCH*", mid, ground - 116 * k, true, false);
            drawFolds(c, w, h);
            int n = 0;
            for (boolean f : folded) if (f) n++;
            float rx = w - dp(60), ry = dp(170) + (float) Math.sin(ft * 3) * dp(4);
            drawRobot(c, rx, ry, dp(1), false);
            if (fstate == FLIGHT) {
                hud(c, w, "BILDSCHIRM FALTEN · " + n + " / 4", n / 4f, -1, 0xFF3C8DFF);
                bubble(c, ft - warnAt < 1.2f ? "🤖 Erst die Diagonale!" : n == 0
                    ? "🤖 Ecken einfalten! Erst zwei gegenüber, dann die anderen." : "🤖 Weiter falten!", rx, ry - dp(30), false, false);
                if (n == 4) {
                    fstate = UPLOAD;
                    phaseAt = ft;
                }
            } else {
                float up = Math.min(1, (ft - phaseAt) / 3f);
                hud(c, w, "UPLOAD IN DIE CLOUD ☁", up, -1, 0xFF2BBF6A);
                bubble(c, "🤖 Lade hoch … " + Math.round(up * 100) + " %", rx, ry - dp(30), false, false);
                if (up >= 1) {
                    host.restoreEaten();
                    fstate = SAVED;
                    phaseAt = ft;
                }
            }
        } else if (fstate == SAVED) {
            float d = ft - phaseAt;
            float x = mid - (mid + 120 * k) * ease(d / 1.6f);
            drawSide(c, x, ground, k, 0.8f, ft * 1.6f, true, true);
            bubble(c, "NEEEIN!", Math.max(dp(80), x), ground - 110 * k, true, true);
            bubble(c, "🤖 Gesichert in der Cloud. Alles wieder da.", w / 2, dp(150), false, false);
            if (d > 2.2f) {
                fight(false);
                endFight();
                return;
            }
        } else {
            // LOST: it ate everything.
            float d = ft - phaseAt;
            drawSide(c, mid, ground, k, 1, ft, false, true);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            p.setAlpha((int) (255 * clamp(d / 0.8f)));
            c.drawRect(0, 0, w, h, p);
            p.setAlpha(255);
            if (d > 0.8f && !locked) {
                locked = true;
                host.lockNow();
                postDelayed(host::done, 400);
                return;
            }
        }
        postInvalidateOnAnimation();
    }

    private void fight(boolean on) {
        if (!on) hurt = 0;
    }

    /** Top bar: label, a bar (fraction), and a countdown when seconds ≥ 0. */
    private void hud(Canvas c, float w, String label, float frac, float seconds, int colour) {
        float top = dp(92), left = dp(24), right = w - dp(24);
        r.set(left - dp(10), top - dp(26), right + dp(10), top + dp(24));
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF2FAFAF8);
        c.drawRoundRect(r, dp(14), dp(14), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.5f));
        p.setColor(0xFF1A1A1A);
        c.drawRoundRect(r, dp(14), dp(14), p);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(dp(12));
        p.setLetterSpacing(0.12f);
        p.setColor(0xFF1A1A1A);
        c.drawText(label, left, top - dp(6), p);
        if (seconds >= 0) {
            String s = String.format(java.util.Locale.ROOT, "%d:%02d", (int) seconds / 60, (int) Math.ceil(seconds) % 60);
            p.setColor(seconds < 5 ? rust : 0xFF1A1A1A);
            c.drawText(s, right - p.measureText(s), top - dp(6), p);
        }
        r.set(left, top + dp(2), right, top + dp(12));
        p.setColor(0xFFE0E0D8);
        c.drawRoundRect(r, dp(5), dp(5), p);
        r.set(left, top + dp(2), left + (right - left) * clamp(frac), top + dp(12));
        p.setColor(colour);
        c.drawRoundRect(r, dp(5), dp(5), p);
    }

    private void drawPops(Canvas c, float ft) {
        for (int i = pops.size() - 1; i >= 0; i--) {
            float[] q = pops.get(i);
            float age = ft - q[2];
            if (age > 0.45f) {
                pops.remove(i);
                continue;
            }
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(q[3] > 0 ? 20 : 12));
            p.setColor(q[3] > 0 ? rust : 0xFF9A9A98);
            p.setAlpha((int) (255 * (1 - age / 0.45f)));
            String s = q[3] > 0 ? (i % 3 == 0 ? "KICK!" : i % 3 == 1 ? "POW!" : "BAM!") : "daneben";
            c.drawText(s, q[0] - p.measureText(s) / 2, q[1] - age * dp(60), p);
            p.setAlpha(255);
        }
    }

    /** The folded-in corners: a turned-over paper triangle with an edge line. */
    private void drawFolds(Canvas c, float w, float h) {
        float[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        float[][] dir = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
        for (int i = 0; i < 4; i++) {
            float f = fold[i];
            if (f <= 0) continue;
            float cx = corners[i][0], cy = corners[i][1], dx = dir[i][0], dy = dir[i][1];
            path.reset();
            path.moveTo(cx + dx * f, cy);
            path.lineTo(cx, cy + dy * f);
            path.lineTo(cx + dx * f, cy + dy * f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(folded[i] ? 0xFFE6E4DC : 0xFFF2F0EA);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0xFF1A1A1A);
            c.drawPath(path, p);
            // The hidden corner itself: dark.
            path.reset();
            path.moveTo(cx, cy);
            path.lineTo(cx + dx * f, cy);
            path.lineTo(cx, cy + dy * f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF121211);
            c.drawPath(path, p);
        }
    }

    private boolean vacuum, locked, look, dead;
    /** Started by hand from the settings: plays the whole black hole even though you're looking. */
    boolean test;

    /* Looked back in time: the dino runs off and hides behind a bush; tap the bush and it plays dead. */
    private float scaredAt = -1, deadAt = -1, scaredFromX;
    private final RectF bush = new RectF();

    /** You looked at the screen again. In the first 2 s of the black hole that scares the dino off. */
    void lookedBack() {
        if (!vacuum || test || scaredAt >= 0 || locked || start < 0) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        if (t > 2.0f) return;               // too late: the sucking has started
        scaredAt = t;
        float w = getWidth(), mid = w / 2, k = Math.min(dp(170), Math.min(w, getHeight()) * 0.42f) / 100f;
        scaredFromX = t < 1.4f ? w + 80 * k - (w + 80 * k - mid) * ease(t / 1.4f) : mid;
        host.vacuum(0, 0, 0);
        host.stomp();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (fightAt >= 0) return fightTouch(e);
        // Tap the dino (while it's out and about, not after the lock started): the fight begins.
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && scaredAt < 0 && !locked && !dinoRect.isEmpty()
            && dinoRect.contains(e.getX(), e.getY())) {
            startFight();
            return true;
        }
        // Only the bush takes touches (and only while the dino hides there); the rest goes to the home.
        if (scaredAt < 0 || deadAt >= 0 || bush.isEmpty()) return false;
        if (e.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return true;
        if (!bush.contains(e.getX(), e.getY())) return false;
        deadAt = (SystemClock.uptimeMillis() - start) / 1000f;
        host.chomp();
        invalidate();
        return true;
    }

    /** The other scene: the dino swallows the whole home like a black hole (7.5 s) and the phone locks. */
    DinoView blackHole() {
        vacuum = true;
        return this;
    }

    /* {roar, translation, buddy, roar2, translation2, robot after chomp, roar3, translation3, buddy after, leave roar, translation4} */
    private static final String[][] SCRIPTS = {
        {"ROOOAAAR!", "»Hallo. Ich habe Hunger.«", "Ähm … Hunger auf was?", "RAWR. RAWR.", "»Auf dich.«",
            "Hey! Sofort ausspucken!", "RRRAWWR?!", "»Aua! Schmeckt eh nach Fell.«", "… wo bin ich?", "Grmpf.", "»Ich komme wieder.«"},
        {"GRAAAOOOR!", "»Schönes Display hast du.«", "Danke …?", "RAWWR!", "»Was ist das Kleine da unten?«",
            "Nein! Das ist der Buddy!", "RRRAAAW?!", "»Okay, okay! Nicht mehr tazen!«", "Echt jetzt?!", "Hmpf.", "»Nächstes Mal den Bären.«"},
        {"ROAAAAR!", "»Ich bin vom letzten Update übrig.«", "Welches Update?", "RAWR!", "»1.0.∞ – der Snack-Patch.«",
            "Nicht auf meiner Startseite!", "RAAWRR?!", "»Akku leer. Ich meine: Aua.«", "Alter?!", "Grrr.", "»Fünf Sterne. Würde wieder essen.«"},
    };

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Random rnd = new Random();
    private final Host host;
    private final int ink, paper, rust;
    private final String[] lines;
    private long start = -1;
    private final boolean[] fired = new boolean[16];
    private int lastStep = -1;

    DinoView(Context c, int ink, int paper, int rust, Host host) {
        super(c);
        this.ink = ink;
        this.paper = paper;
        this.rust = rust;
        this.host = host;
        lines = SCRIPTS[rnd.nextInt(SCRIPTS.length)];
        setClickable(false);
        setFocusable(false);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static float ease(float k) {
        k = clamp(k);
        return k < 0.5f ? 2 * k * k : 1 - (float) Math.pow(-2 * k + 2, 2) / 2;
    }

    private boolean once(int i, float t, float at) {
        if (t < at || fired[i]) return false;
        fired[i] = true;
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (start < 0) start = now;
        float t = (now - start) / 1000f;
        float w = getWidth(), h = getHeight();
        float[] b = host.buddy();
        float ground = b[1], size = Math.min(dp(170), Math.min(w, h) * 0.42f), k = size / 100f;
        float mid = w / 2;
        if (fightAt >= 0) {
            drawFight(c, now, w, h, ground, k, mid);
            return;
        }
        if (vacuum) {
            drawVacuum(c, t, w, h, ground, size, k, mid);
            return;
        }

        // ---- dino position and pose ----
        float x;
        boolean front = false, walking = false, facingLeft = true;
        float jaw = 0, shake = 0;
        float eatX = b[0] + 48 * k;          // head over the buddy
        if (t < 4) {
            x = w + 80 * k - (w + 80 * k - mid) * ease(t / 4f);
            walking = true;
        } else if (t < 15) {
            x = mid;
            front = t >= 4.6f;
            if (front && ((t > 5 && t < 8) || (t > 12 && t < 14))) jaw = 0.6f + 0.4f * (float) Math.abs(Math.sin(t * 9));
        } else if (t < 17) {
            x = mid - (mid - eatX) * ease((t - 15) / 2f);
            walking = true;
            jaw = clamp((t - 16) / 0.6f);
        } else if (t < 21) {
            x = eatX;
            jaw = t < 17.4f ? 1 - clamp((t - 17) / 0.4f) : (float) Math.abs(Math.sin((t - 17.4) * 7)) * 0.35f;   // chomp, then chew
        } else if (t < 27) {
            x = eatX;
            front = true;
            if (t > 22 && t < 25.5f) shake = dp(5);
            if (t > 25 && t < 27) jaw = 0.6f + 0.4f * (float) Math.abs(Math.sin(t * 9));
        } else if (t < 29) {
            x = eatX;
            jaw = t < 28 ? clamp((t - 27) / 0.3f) : 1 - clamp((t - 28) / 0.4f);   // a big burp
        } else {
            x = eatX - (eatX + 120 * k) * ease((t - 29) / 4.5f);
            walking = true;
        }

        // ---- events ----
        if (walking && (int) (t * 2.4f) != lastStep) {
            lastStep = (int) (t * 2.4f);
            host.stomp();
        }
        if (once(2, t, 10)) host.buddySay(lines[2]);
        if (once(3, t, 17.2f)) {
            host.chomp();
            host.hideBuddy();
        }
        // The new buddy only shows up once dino and robot are both gone.
        if (once(4, t, 36.4f)) host.swapBuddy(lines[8]);

        // ---- robot: flies in at 5 s, hovers, tazes 22–25.5 s, leaves at 35 s ----
        float rx = w - dp(70), ry = ground - size * 1.75f;
        if (t < 5) rx = w + dp(60);
        else if (t < 6.5f) rx = w + dp(60) - (dp(130)) * ease((t - 5) / 1.5f);
        else if (t > 35) rx = w - dp(70) + dp(140) * ease((t - 35) / 1.2f);
        float rob = (float) Math.sin(t * 3) * dp(4);
        if (t > 21 && t < 26) {
            // Closer for the taser.
            float g = ease(clamp((t - 21) / 0.8f)) * (1 - ease(clamp((t - 25.2f) / 0.8f)));
            rx += (x + 40 * k - rx) * g * 0.6f;
            ry += (ground - size * 1.25f - ry) * g;
        }

        markDino(x, ground, k, 1);
        c.save();
        c.translate(shake * (float) Math.sin(t * 60), 0);
        if (front) {
            look = true;            // same dino, head turned to you (not a front-on smiley)
            drawSide(c, x, ground, k, jaw, t, false, true);
            look = false;
        }
        else drawSide(c, x, ground, k, jaw, t, walking, facingLeft);
        c.restore();

        boolean tazing = t > 22 && t < 25.5f;
        drawRobot(c, rx, ry + rob, dp(1), tazing);
        if (tazing) zap(c, rx - dp(22), ry + rob + dp(10), x, ground - 55 * k);

        // ---- speech ----
        if (t > 5 && t < 8) bubble(c, lines[0], x - 40 * k, ground - 116 * k, true, true);
        if (t > 7.4f && t < 10.5f) bubble(c, "🤖 " + lines[1], rx, ry + rob - dp(34), false, false);
        if (t > 12 && t < 14) bubble(c, lines[3], x - 40 * k, ground - 116 * k, true, true);
        if (t > 13.4f && t < 16) bubble(c, "🤖 " + lines[4], rx, ry + rob - dp(34), false, false);
        if (t > 18.2f && t < 21) bubble(c, "*mampf* *mampf*", x - 30 * k, ground - 95 * k, true, false);
        if (t > 20.6f && t < 23) bubble(c, "🤖 " + lines[5], rx, ry + rob - dp(34), false, false);
        if (t > 22.4f && t < 25.5f) bubble(c, "BZZZZT!", rx - dp(40), ry + rob + dp(48), true, false);
        if (t > 25 && t < 27.2f) bubble(c, lines[6], x - 40 * k, ground - 116 * k, true, true);
        if (t > 27.4f && t < 29.2f) bubble(c, "*RÜLPS*", x - 40 * k, ground - 110 * k, true, false);
        if (t > 26.4f && t < 29.6f) bubble(c, "🤖 " + lines[7], rx, ry + rob - dp(34), false, false);
        if (t > 29.6f && t < 31.6f) bubble(c, lines[9], Math.max(dp(90), x), ground - 100 * k, true, false);
        if (t > 31.6f && t < 35) bubble(c, "🤖 " + lines[10], rx, ry + rob - dp(34), false, false);

        if (t < 37.2f) postInvalidateOnAnimation();
        else post(host::done);
    }

    /* ---------- black hole: walk in (1.5 s), mouth opens into a vortex, everything spirals in, lock at 7.5 s ---------- */

    private void drawVacuum(Canvas c, float t, float w, float h, float ground, float size, float k, float mid) {
        if (scaredAt >= 0) {
            drawScared(c, t, w, ground, k);
            return;
        }
        // 0–1.4 s: stomps in from the right.
        if (t < 1.4f) {
            float x = w + 80 * k - (w + 80 * k - mid) * ease(t / 1.4f);
            if ((int) (t * 2.4f) != lastStep) {
                lastStep = (int) (t * 2.4f);
                host.stomp();
            }
            markDino(x, ground, k, 1);
            drawSide(c, x, ground, k, 0, t, true, true);
            postInvalidateOnAnimation();      // keep walking in (this frame request was missing)
            return;
        }
        // 1.4–2.6 s: turns to you and comes up to the glass (like the bin), mouth opening wide.
        float come = ease((t - 1.4f) / 1.2f);
        float sc = 1 + 0.5f * come, groundC = ground + (h * 0.8f - ground) * come;
        // It walks up so its mouth ends in the middle of the screen (it was off to the left).
        float dx = mid + 54 * k * sc * come;
        float mx = dx - 54 * k * sc, my = groundC - 76 * k * sc;      // its mouth on screen
        markDino(dx, groundC, k, sc);
        float pull = clamp((t - 2.4f) / 4.5f);
        host.vacuum(pull * pull, mx, my);
        if (once(6, t, 2.4f)) host.chomp();
        c.save();
        c.translate(dx - mid, groundC - ground);
        c.scale(sc, sc, mid, ground);
        look = true;
        drawSide(c, mid, ground, k, Math.min(1, come * 1.4f), t, false, true);
        look = false;
        c.restore();
        // The vortex in the mouth: a dark disc with spiral arms, growing.
        float rr = (8 + 60 * pull) * k * sc * 0.6f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF000000);
        c.drawCircle(mx, my, rr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int arm = 0; arm < 4; arm++) {
            path.reset();
            for (int i = 0; i <= 24; i++) {
                float f = i / 24f;
                double a = arm * Math.PI / 2 + f * 5 - t * 6;
                float d = rr * (0.2f + 2.6f * f);
                float px = mx + (float) Math.cos(a) * d, py = my + (float) Math.sin(a) * d;
                if (i == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            p.setColor(arm % 2 == 0 ? rust : 0xFF8A4BFF);
            p.setAlpha((int) (200 * (0.3f + 0.7f * pull)));
            p.setStrokeWidth(dp(2.5f));
            c.drawPath(path, p);
        }
        p.setAlpha(255);
        // 6.9–7.5 s: black, then the lock.
        if (t > 6.9f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            p.setAlpha((int) (255 * clamp((t - 6.9f) / 0.6f)));
            c.drawRect(0, 0, w, h, p);
            p.setAlpha(255);
        }
        if (t >= 7.5f && !locked) {
            locked = true;
            host.lockNow();
            postDelayed(host::done, 400);
            return;
        }
        if (!locked) postInvalidateOnAnimation();
    }

    /** Runs to the bush (0.9 s), hides and peeks; tapped → plays dead; untouched for 12 s → sneaks away. */
    private void drawScared(Canvas c, float t, float w, float ground, float k) {
        float s = t - scaredAt, bx = w * 0.78f;
        bush.set(bx - 72 * k, ground - 86 * k, bx + 72 * k, ground);
        if (deadAt >= 0) {
            float d = t - deadAt;
            // Falls over onto its back in front of the bush, legs up, X eyes.
            float fall = ease(Math.min(1, d / 0.5f));
            drawBush(c, bx, ground, k, 1);
            c.save();
            c.rotate(180 * fall, bx - 20 * k, ground - 47 * k * fall);
            dead = fall > 0.5f;
            drawSide(c, bx - 20 * k, ground, k, fall * 0.5f, t, false, true);
            dead = false;
            c.restore();
            if (d > 0.8f && d < 3.6f) bubble(c, "*totgestellt*", bx - 20 * k, ground - 70 * k, false, false);
            float fade = Math.max(0, (d - 3.6f) / 0.6f);
            if (fade >= 1) {
                post(host::done);
                return;
            }
            setAlpha(1 - fade);
            postInvalidateOnAnimation();
            return;
        }
        if (s < 0.9f) {
            // Runs off to the right, fast, looking back at you.
            float x = scaredFromX + (bx + 10 * k - scaredFromX) * ease(s / 0.9f);
            if ((int) (s * 6) != lastStep) {
                lastStep = (int) (s * 6);
                host.stomp();
            }
            drawSide(c, x, ground, k, 0.3f, t * 1.8f, true, false);
            drawBush(c, bx, ground, k, clamp(s / 0.4f));
            if (s < 0.8f) bubble(c, "EEK!", x, ground - 110 * k, true, false);
        } else if (s < 13) {
            // Hidden behind the bush, peeking out every couple of seconds.
            float peek = (float) Math.max(0, Math.sin((s - 0.9f) * 1.6f)) * 54 * k;
            c.save();
            c.translate(0, 46 * k - peek);
            look = true;
            drawSide(c, bx + 6 * k, ground, k, 0, t, false, true);
            look = false;
            c.restore();
            drawBush(c, bx, ground, k, 1);
            if (s > 1.4f && s < 4) bubble(c, "Bin nicht da.", bx, ground - 96 * k, false, false);
        } else {
            post(host::done);
            return;
        }
        postInvalidateOnAnimation();
    }

    /** A round ARC-DNA bush: green puffs with an ink outline and a few rust berries. */
    private void drawBush(Canvas c, float bx, float g, float k, float grow) {
        if (grow <= 0) return;
        float[][] puffs = {{-44, -30, 30}, {0, -50, 38}, {44, -30, 30}, {-22, -16, 26}, {24, -14, 26}};
        for (int pass = 0; pass < 2; pass++) {
            for (float[] f : puffs) {
                float r0 = f[2] * k * grow;
                p.setStyle(pass == 0 ? Paint.Style.STROKE : Paint.Style.FILL);
                p.setStrokeWidth(3 * k);
                p.setColor(pass == 0 ? ink : 0xFF5CC97B);
                c.drawCircle(bx + f[0] * k, g + f[1] * k * grow, r0, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(rust);
        float[][] berries = {{-30, -40}, {12, -62}, {36, -26}, {-6, -24}};
        for (float[] b : berries) c.drawCircle(bx + b[0] * k, g + b[1] * k * grow, 3.4f * k * grow, p);
    }

    /* ---------- dino, side view (facing left), feet centre at (x, g) ---------- */

    private void drawSide(Canvas c, float x, float g, float k, float jaw, float t, boolean walking, boolean left) {
        c.save();
        c.translate(x, g);
        if (!left) c.scale(-1, 1);
        float step = walking ? (float) Math.sin(t * 9) : 0;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        // Back spikes (rust).
        p.setStyle(Paint.Style.FILL);
        p.setColor(rust);
        for (int i = 0; i < 7; i++) {
            float sx = -22 + i * 11, sy = -56 + (i * i) * 0.45f;
            path.reset();
            path.moveTo((sx - 4) * k, sy * k + 3 * k);
            path.lineTo(sx * k, (sy - 9) * k);
            path.lineTo((sx + 4) * k, sy * k + 3 * k);
            path.close();
            c.drawPath(path, p);
        }
        // Tail.
        path.reset();
        path.moveTo(26 * k, -46 * k);
        path.quadTo(60 * k, -40 * k, 84 * k, (-24 + step * 2) * k);
        path.quadTo(58 * k, -26 * k, 26 * k, -28 * k);
        path.close();
        fillStroke(c);
        // Legs (back one first).
        leg(c, 14 * k, -24 * k, -step, k);
        // Body.
        r.set(-30 * k, -60 * k, 36 * k, -20 * k);
        p.setStyle(Paint.Style.FILL);
        p.setColor(paper);
        c.drawOval(r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * k);
        p.setColor(ink);
        c.drawOval(r, p);
        leg(c, -6 * k, -24 * k, step, k);
        // Neck.
        path.reset();
        path.moveTo(-14 * k, -56 * k);
        path.quadTo(-26 * k, -70 * k, -32 * k, -82 * k);
        path.lineTo(-44 * k, -76 * k);
        path.quadTo(-34 * k, -60 * k, -26 * k, -42 * k);
        path.close();
        fillStroke(c);
        // Tiny arm.
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        p.setStrokeWidth(3 * k);
        c.drawLine(-24 * k, -42 * k, -32 * k, (-34 + step) * k, p);
        // Head: skull, then the lower jaw hinged at the back. Looking at you, it throws the head up to roar.
        c.save();
        if (look) c.rotate(jaw * 14, -36 * k, -80 * k);
        if (jaw > 0.05f) {
            // Inside of the mouth.
            p.setStyle(Paint.Style.FILL);
            p.setColor(rust);
            r.set(-62 * k, -80 * k, -36 * k, (-74 + 16 * jaw) * k);
            c.drawOval(r, p);
        }
        r.set(-66 * k, -94 * k, -30 * k, -74 * k);
        p.setStyle(Paint.Style.FILL);
        p.setColor(paper);
        c.drawRoundRect(r, 9 * k, 9 * k, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        c.drawRoundRect(r, 9 * k, 9 * k, p);
        c.save();
        c.rotate(-jaw * 32, -36 * k, -76 * k);
        r.set(-64 * k, -80 * k, -32 * k, -70 * k);
        p.setStyle(Paint.Style.FILL);
        p.setColor(paper);
        c.drawRoundRect(r, 5 * k, 5 * k, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        c.drawRoundRect(r, 5 * k, 5 * k, p);
        // Teeth.
        p.setStrokeWidth(2 * k);
        for (int i = 0; i < 4; i++) c.drawLine((-60 + i * 6) * k, -80 * k, (-58 + i * 6) * k, -84 * k, p);
        c.restore();
        // Eyes and nostril: side-on one eye; turned to you, both eyes with pupils looking out at you.
        p.setStyle(Paint.Style.FILL);
        if (look) {
            for (int e = 0; e < 2; e++) {
                float ex = (e == 0 ? -42 : -53) * k, ey = (e == 0 ? -88 : -90) * k, er = (e == 0 ? 4.6f : 3.8f) * k;
                p.setStyle(Paint.Style.FILL);
                p.setColor(paper);
                c.drawCircle(ex, ey, er, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * k);
                p.setColor(ink);
                c.drawCircle(ex, ey, er, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(ex - 0.6f * k, ey + 0.8f * k, er * 0.48f, p);
            }
            // Angry brow.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f * k);
            c.drawLine(-58 * k, -96 * k, -38 * k, -94 * k, p);
        } else if (dead || hurt >= 3) {
            // X eyes.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f * k);
            p.setColor(ink);
            c.drawLine(-46 * k, -91 * k, -38 * k, -83 * k, p);
            c.drawLine(-46 * k, -83 * k, -38 * k, -91 * k, p);
        } else {
            p.setColor(ink);
            c.drawCircle(-42 * k, -87 * k, 2.6f * k, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(ink);
        c.drawCircle(-62 * k, -89 * k, 1.2f * k, p);
        if (hurt >= 2) {
            // Plaster across the head.
            c.save();
            c.rotate(-20, -48 * k, -96 * k);
            r.set(-58 * k, -99 * k, -38 * k, -93 * k);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFF2D7B6);
            c.drawRect(r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * k);
            p.setColor(ink);
            c.drawRect(r, p);
            c.restore();
        }
        c.restore();
        // Hurt in five steps: bruises, plaster, X eye, smoke, K.O. stars (drawn by the fight).
        if (hurt >= 1) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xAA8A4BFF);
            c.drawCircle(8 * k, -44 * k, 5 * k, p);
            c.drawCircle(-12 * k, -32 * k, 3.5f * k, p);
        }
        c.restore();
    }

    /** Smoke from its back, in screen space: always rises, grows and fades (it used to fall when the dino tilted). */
    private void smoke(Canvas c, float x, float g, float k, float t) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float rise = (t * 0.7f + i / 4f) % 1f;
            p.setColor(0xFF9A9A98);
            p.setAlpha((int) (150 * (1 - rise)));
            c.drawCircle(x + (8 + i * 6 + rise * 10 * (i % 2 == 0 ? 1 : -1)) * k, g - (62 + rise * 70) * k, (4 + rise * 9) * k, p);
        }
        p.setAlpha(255);
    }

    private void leg(Canvas c, float hx, float hy, float swing, float k) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        p.setStrokeWidth(8 * k);
        float fx = hx + swing * 7 * k;
        c.drawLine(hx, hy, fx, 0, p);
        p.setStrokeWidth(4 * k);
        c.drawLine(fx, 0, fx - 7 * k, 0, p);
    }

    private void fillStroke(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(paper);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        p.setStrokeWidth(3 * getResources().getDisplayMetrics().density * 0.9f);
        c.drawPath(path, p);
    }

    /* ---------- the robot translator ---------- */

    private void drawRobot(Canvas c, float x, float y, float u, boolean tazing) {
        p.setStrokeCap(Paint.Cap.ROUND);
        // Rotor.
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        p.setStrokeWidth(2.5f * u);
        float spin = (float) Math.abs(Math.sin(SystemClock.uptimeMillis() / 40.0));
        c.drawLine(x, y - 26 * u, x, y - 18 * u, p);
        c.drawLine(x - 16 * u * spin, y - 26 * u, x + 16 * u * spin, y - 26 * u, p);
        // Head box.
        r.set(x - 20 * u, y - 18 * u, x + 20 * u, y + 14 * u);
        p.setStyle(Paint.Style.FILL);
        p.setColor(paper);
        c.drawRoundRect(r, 8 * u, 8 * u, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        c.drawRoundRect(r, 8 * u, 8 * u, p);
        // Screen face.
        r.set(x - 14 * u, y - 11 * u, x + 14 * u, y + 7 * u);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1A1A1A);
        c.drawRoundRect(r, 4 * u, 4 * u, p);
        p.setColor(tazing ? 0xFFFFE680 : 0xFF33E0FF);
        c.drawCircle(x - 6 * u, y - 3 * u, 2.6f * u, p);
        c.drawCircle(x + 6 * u, y - 3 * u, 2.6f * u, p);
        // Taser arm.
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        p.setStrokeWidth(3 * u);
        c.drawLine(x - 20 * u, y + 6 * u, x - 30 * u, y + 12 * u, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(rust);
        c.drawCircle(x - 30 * u, y + 12 * u, 3.4f * u, p);
    }

    /** Electric bolts from the taser to the dino, flickering. */
    private void zap(Canvas c, float x1, float y1, float x2, float y2) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            p.setColor(pass == 0 ? 0x6633AAFF : 0xFFFFF27A);
            p.setStrokeWidth(dp(pass == 0 ? 7 : 2.5f));
            for (int b = 0; b < 2; b++) {
                path.reset();
                path.moveTo(x1, y1);
                for (int i = 1; i < 8; i++) {
                    float f = i / 8f;
                    path.lineTo(x1 + (x2 - x1) * f + (rnd.nextFloat() - 0.5f) * dp(26), y1 + (y2 - y1) * f + (rnd.nextFloat() - 0.5f) * dp(26));
                }
                path.lineTo(x2, y2);
                c.drawPath(path, p);
            }
        }
        // Sparks on the dino.
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFF27A);
        for (int i = 0; i < 6; i++) {
            double a = rnd.nextFloat() * Math.PI * 2;
            float d = dp(10 + rnd.nextFloat() * 22);
            c.drawCircle(x2 + (float) Math.cos(a) * d, y2 + (float) Math.sin(a) * d, dp(2), p);
        }
    }

    /* ---------- comic speech bubbles ---------- */

    private void bubble(Canvas c, String text, float ax, float ay, boolean loud, boolean big) {
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(dp(big ? 17 : 13));
        p.setLetterSpacing(0.04f);
        float maxW = Math.min(getWidth() - dp(32), dp(240));
        List<String> rows = wrap(text, maxW - dp(24));
        float tw = 0;
        for (String s : rows) tw = Math.max(tw, p.measureText(s));
        float lh = p.getTextSize() * 1.25f, bw = tw + dp(24), bh = rows.size() * lh + dp(16);
        float left = Math.max(dp(12), Math.min(getWidth() - dp(12) - bw, ax - bw / 2)), top = Math.max(dp(40), ay - bh - dp(10));
        r.set(left, top, left + bw, top + bh);
        p.setStyle(Paint.Style.FILL);
        p.setColor(loud ? 0xFFFFE680 : 0xFFFAFAF8);      // always light with dark text, also at night
        path.reset();
        float tx = Math.max(left + dp(14), Math.min(left + bw - dp(14), ax));
        path.moveTo(tx - dp(7), top + bh - 1);
        path.lineTo(tx, top + bh + dp(10));
        path.lineTo(tx + dp(7), top + bh - 1);
        path.close();
        c.drawRoundRect(r, dp(12), dp(12), p);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0xFF1A1A1A);
        c.drawRoundRect(r, dp(12), dp(12), p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1A1A1A);
        float y = top + dp(8) + p.getTextSize();
        for (String s : rows) {
            c.drawText(s, left + dp(12), y, p);
            y += lh;
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
