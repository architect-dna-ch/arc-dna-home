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

        /** The buddy comes back (spat out). */
        default void showBuddy() {
        }

        /** The buddy hops out of the way. */
        default void buddyDodge() {
        }

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

    /* Five kinds of dino, each with its own look and trick in the fight. */
    static final int REX = 0, STEGO = 1, RAPTOR = 2, TRIKE = 3, PTERO = 4;
    private static final String[] TYPE_NAME = {"T-REX", "STEGOSAURUS", "RAPTOR", "TRICERATOPS", "PTERO-REX"};
    private static final String[] TYPE_TIP = {
        "🤖 T-Rex! Unter 40 % wird er wütend und schneller.",
        "🤖 Stegosaurus! Panzerplatten: jeder Tritt zählt nur halb.",
        "🤖 Raptor! Klein und blitzschnell – zielen!",
        "🤖 Triceratops! Wenn er anrennt, blocken die Hörner.",
        "🤖 Ptero-Rex! Er fliegt hoch und runter."};
    private static final int[] TYPE_TINT = {0xFFFFFFFF, 0xFF8FD18A, 0xFFB79BFF, 0xFFFFD60A, 0xFF7FC4FF};
    private final int type, skin;
    private boolean charging;
    private final RectF dinoRect = new RectF();
    private final List<float[]> pops = new ArrayList<>();       // x, y, time, hit?
    private final float[] fold = new float[4];                  // 0 … dp(120): TL, TR, BR, BL
    private final boolean[] folded = new boolean[4];
    private int dragCorner = -1;
    private float dragFrom;
    private float warnAt = -10;

    /** Where the dino stands on screen this frame (for taps). */
    private void markDino(float x, float g, float k, float sc) {
        dinoRect.set(x - 72 * k * sc, g - 104 * k * sc, x + 86 * k * sc, g);
    }

    /* ================= Weapons from the bone shop ================= */

    private static final int FIST = 0, STONE = 1, FLAME = 2, SABER = 3, SPELL = 4, HAMMER = 5, BANANA = 6, SNOW = 7,
        BOOMERANG = 8, WATER = 9, BOW = 10, ICE = 11, KATANA = 12, GUITAR = 13, BOLT = 14, ROCKET = 15, CONFETTI = 16,
        LASER2 = 17, HOURGLASS = 18, SHIELD = 19, METEOR = 20, TORNADO = 21, TURBO = 22, MAGNET = 23, CLOVER = 24;
    /** How an item works: tap the dino, throw at a spot, hold a stream, swipe, tap anywhere, use once, always on. */
    private static final int K_TAP = 0, K_THROW = 1, K_STREAM = 2, K_SWIPE = 3, K_WAVE = 4, K_BOLT = 5, K_CAST = 6, K_PASSIVE = 7;
    private static final String[] W_NAME = {"FAUST", "STEINE", "FLAMMEN", "LASER", "SPRUCH", "HAMMER", "BANANE", "SCHNEEBALL",
        "BUMERANG", "WASSERBOMBE", "BOGEN", "EISSTRAHL", "KATANA", "GITARRE", "BLITZ", "RAKETE", "KONFETTI", "LASER MK II",
        "SANDUHR", "SCHILD", "METEOR", "TORNADO", "ROBO-TURBO", "MAGNET", "GLÜCKSKLEE"};
    private static final String[] W_ICON = {"👊", "🪨", "🔥", "⚔️", "📜", "🔨", "🍌", "❄️", "🪃", "💧", "🏹", "🧊", "🗡️", "🎸", "⚡",
        "🚀", "🎉", "💜", "⏳", "🛡️", "☄️", "🌪️", "🤖", "🧲", "🍀"};
    /** Bones: once for a weapon or a perk, every time for the one-shot items (scrolls, meteor …). */
    private static final int[] W_COST = {0, 15, 40, 70, 60, 25, 25, 30, 35, 45, 50, 55, 60, 70, 80, 90, 20, 150, 35, 25, 70, 45,
        100, 120, 80};
    private static final int[] W_KIND = {K_TAP, K_THROW, K_STREAM, K_SWIPE, K_CAST, K_TAP, K_THROW, K_THROW, K_THROW, K_THROW,
        K_THROW, K_STREAM, K_SWIPE, K_WAVE, K_BOLT, K_THROW, K_STREAM, K_SWIPE, K_CAST, K_CAST, K_CAST, K_CAST, K_PASSIVE, K_PASSIVE,
        K_PASSIVE};
    private static final String[] W_DESC = {
        "Antippen = Tritt. Immer dabei.",
        "Werfen: tipp, wo er landen soll.",
        "Halten: Feuerstrahl, 4,5 s Treibstoff pro Kampf.",
        "Durchwischen: Lichtschwert-Schnitt.",
        "Einmal pro Kauf: der Dino zerbröselt sofort.",
        "Antippen: doppelter Schaden, wirft ihn um.",
        "Werfen: er rutscht aus – 1,5 s k.o.",
        "Werfen: friert ihn 2,5 s langsam.",
        "Werfen: trifft hin und zurück – zweimal.",
        "Werfen: platscht breit, trifft auch knapp daneben.",
        "Pfeil: schnell und gerade, kein Bogen.",
        "Halten: Eisstrahl, bremst ihn ab.",
        "Wischen: schneller als der Laser.",
        "Tippen irgendwo: Schallwelle trifft immer.",
        "Tippen: Blitz von oben, starker Treffer.",
        "Werfen: langsam, aber BUMM – riesige Explosion.",
        "Halten: Regenbogen-Konfetti. Wenig Schaden, viel Party.",
        "Wischen: der Traum-Laser. Breit, lila, brutal.",
        "Einmal: die Zeit steht 4 s – er bewegt sich nicht.",
        "Einmal: +6 Sekunden Kampfzeit.",
        "Einmal: ein Meteor – 40 % seiner Energie weg.",
        "Einmal: Tornado wirbelt ihn 3 s herum.",
        "Immer aktiv: der Roboter zappt 3× so stark.",
        "Immer aktiv: +50 % Knochen pro Sieg.",
        "Immer aktiv: 15 % Chance auf kritische Treffer (×2)."};
    private static final int SHOP_SLOT = 4;
    private int weapon = FIST, bonesWon, editSlot = 3;
    private final RectF[] slots = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private final float[] fuels = new float[W_NAME.length];
    private float fingerX, fingerY, lastSlash = -9, crumbleAt = -1, shopMsgAt = -9, lastWave = -9, lastBolt = -9;
    private float dclock, stunUntil = -1, slowUntil = -1, freezeUntil = -1, meteorAt = -1, tornadoUntil = -1, critAt = -9, critX, critY;
    private String shopMsg = "";
    private boolean flaming, shopOpen;
    private long shopAt;
    private final RectF[] shopCells = new RectF[W_NAME.length];
    private final RectF[] beltCells = {new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF shopClose = new RectF();
    private final List<float[]> stones = new ArrayList<>();     // from x, y, to x, y, thrown at, item
    private final List<float[]> waves = new ArrayList<>();      // x, y, time
    private final List<float[]> bolts = new ArrayList<>();      // x, y, time
    private final List<float[]> trail = new ArrayList<>();      // x, y, time
    private final RectF crumbleRect = new RectF();

    {
        for (int i = 0; i < shopCells.length; i++) shopCells[i] = new RectF();
    }

    private android.content.SharedPreferences store() {
        return getContext().getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    private int bones() {
        return store().getInt("dinoBones", 15);       // a few to start with: enough for the stones
    }

    private boolean owns(int w) {
        return w == FIST || store().getBoolean("dinoWeapon" + w, false);
    }

    /** The four items on the belt (the bar in the fight); the fifth slot opens the shop. */
    private int[] belt() {
        int[] b = {FIST, STONE, FLAME, SABER};
        String[] parts = store().getString("dinoBelt", "").split(",");
        for (int i = 0; i < 4 && i < parts.length; i++) {
            try {
                int v = Integer.parseInt(parts[i].trim());
                if (v >= 0 && v < W_NAME.length && W_KIND[v] != K_PASSIVE) b[i] = v;
            } catch (NumberFormatException ignored) {
            }
        }
        return b;
    }

    private void setBelt(int slot, int item) {
        int[] b = belt();
        for (int i = 0; i < 4; i++) if (b[i] == item) b[i] = b[slot];      // swap if it's already on
        b[slot] = item;
        store().edit().putString("dinoBelt", b[0] + "," + b[1] + "," + b[2] + "," + b[3]).apply();
    }

    private void shopSay(String m, float ft) {
        shopMsg = m;
        shopMsgAt = ft;
    }

    /** A belt slot was tapped: pick, buy, or use a one-shot item. */
    private void pickWeapon(int i, float ft) {
        int have = bones();
        if (W_KIND[i] == K_CAST) {
            if (crumbleAt >= 0) return;
            if (have < W_COST[i]) {
                shopSay(W_NAME[i] + " kostet " + W_COST[i] + " 🦴 – du hast " + have + ".", ft);
                return;
            }
            store().edit().putInt("dinoBones", have - W_COST[i]).apply();
            cast(i, ft);
            return;
        }
        if (owns(i)) {
            weapon = i;
            store().edit().putInt("dinoWeaponSel", i).apply();
            return;
        }
        if (!buy(i, ft)) return;
        weapon = i;
        store().edit().putInt("dinoWeaponSel", i).apply();
    }

    /** Pay for a weapon or perk; false (with a word why) when the bones don't reach. */
    private boolean buy(int i, float ft) {
        int have = bones();
        if (have < W_COST[i]) {
            shopSay(W_NAME[i] + ": noch " + (W_COST[i] - have) + " 🦴 sammeln.", ft);
            return false;
        }
        store().edit().putInt("dinoBones", have - W_COST[i]).putBoolean("dinoWeapon" + i, true).apply();
        shopSay("Gekauft: " + W_NAME[i] + "!", ft);
        host.chomp();
        return true;
    }

    /** One-shot items. */
    private void cast(int i, float ft) {
        if (i == SPELL) {
            crumbleAt = ft;
            crumbleRect.set(dinoRect);
            host.chomp();
        } else if (i == HOURGLASS) {
            freezeUntil = ft + 4;
            shopSay("⏳ Die Zeit steht still!", ft);
        } else if (i == SHIELD) {
            timeLimit += 6;
            shopSay("🛡️ +6 Sekunden!", ft);
        } else if (i == METEOR) {
            meteorAt = ft;
        } else if (i == TORNADO) {
            tornadoUntil = ft + 3;
            host.stomp();
        }
    }

    /** A hit with dmg (tap = 1); false when it bounced off the triceratops' horns. */
    private boolean damage(float dmg, float x, float y, float ft, boolean pop) {
        if (charging) {
            if (pop) pops.add(new float[]{x, y, ft, 0});
            return false;
        }
        if (owns(CLOVER) && rnd.nextFloat() < 0.15f && dmg >= 1) {
            dmg *= 2;
            critAt = lastFt;
            critX = x;
            critY = y;
        }
        hp = Math.max(0, hp - dmg * (type == STEGO ? 0.5f : 1));     // stego plates: half damage
        knock = (x < dinoRect.centerX() ? 1 : -1) * dp(8);
        knockY = Math.max(knockY, dp(46) * Math.min(1, dmg));
        if (pop) {
            pops.add(new float[]{x, y, ft, 1});
            host.stomp();
        }
        return true;
    }

    private void startFight() {
        fightAt = SystemClock.uptimeMillis();
        bringToFront();          // the weapon bar over everything (the bunny button covered FAUST)
        setElevation(getResources().getDisplayMetrics().density * 40);    // above the floating buttons too (they have elevation)
        int sel = store().getInt("dinoWeaponSel", FIST);
        weapon = owns(sel) && W_KIND[sel] != K_CAST && W_KIND[sel] != K_PASSIVE ? sel : FIST;
        java.util.Arrays.fill(fuels, 4.5f);
        flaming = false;
        shopOpen = false;
        dclock = 0;
        stunUntil = slowUntil = freezeUntil = meteorAt = tornadoUntil = -1;
        waves.clear();
        bolts.clear();
        stones.clear();
        trail.clear();
        crumbleAt = -1;
        bonesWon = 0;
        level = Math.max(1, Math.min(99, host.level()));
        maxHp = hp = (12 + level * 1.6f) * (type == RAPTOR ? 0.75f : 1);
        timeLimit = 20 + level * 0.2f;
        fstate = FIGHT;
        lastZap = 0;
        buddyEaten = !vacuum && fired[3] && !fired[4] && (plot == EAT || plot == SPIT && !fired[7]);   // eaten and not yet back
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
        if (fstate == FIGHT && shopOpen) return shopTouch(e);
        if (fstate == FIGHT) {
            float ft = (SystemClock.uptimeMillis() - fightAt) / 1000f;
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i].contains(x, y)) {
                        if (i == SHOP_SLOT) openShop();
                        else pickWeapon(belt()[i], ft);
                        return true;
                    }
                }
            }
            if (crumbleAt >= 0) return true;
            boolean down = a == android.view.MotionEvent.ACTION_DOWN, up = a == android.view.MotionEvent.ACTION_UP
                || a == android.view.MotionEvent.ACTION_CANCEL;
            int kind = W_KIND[weapon];
            if (kind == K_TAP && down) {
                // Every kick knocks it down a bit, it springs back up; the hammer hits twice as hard.
                if (dinoRect.contains(x, y)) {
                    damage(weapon == HAMMER ? 2 : 1, x, y, ft, true);
                    if (weapon == HAMMER) knockY = Math.max(knockY, dp(70));
                } else pops.add(new float[]{x, y, ft, 0});
            } else if (kind == K_THROW && down) {
                stones.add(new float[]{w / 2f, h - dp(120), x, y, ft, weapon});
            } else if (kind == K_STREAM) {
                flaming = !up;
                fingerX = x;
                fingerY = y;
            } else if (kind == K_SWIPE) {
                if (down) trail.clear();
                if (!up) {
                    trail.add(new float[]{x, y, ft});
                    float cool = weapon == KATANA ? 0.1f : weapon == LASER2 ? 0.18f : 0.22f;
                    float dmg = weapon == KATANA ? 2.2f : weapon == LASER2 ? 5 : 3;
                    RectF hit = new RectF(dinoRect);
                    if (weapon == LASER2) hit.inset(-dp(18), -dp(18));
                    if (!down && hit.contains(x, y) && ft - lastSlash > cool && trail.size() > 2) {
                        lastSlash = ft;
                        damage(dmg, x, y, ft, true);
                    }
                }
            } else if (kind == K_WAVE && down && ft - lastWave > 0.35f) {
                lastWave = ft;
                waves.add(new float[]{x, y, ft, 0});
            } else if (kind == K_BOLT && down && ft - lastBolt > 0.7f) {
                lastBolt = ft;
                bolts.add(new float[]{x, y, ft});
                RectF hit = new RectF(dinoRect);
                hit.inset(-dp(30), -dp(30));
                if (hit.contains(x, y)) damage(4, x, y, ft, true);
                host.stomp();
            }
            return true;
        }
        if (fstate == FLIGHT || fstate == UPLOAD) {
            float[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                dragCorner = -1;
                // Start anywhere near a corner (not right on the edge, where Android's home/back swipes live).
                for (int i = 0; i < 4; i++) {
                    float d = (float) Math.hypot(x - corners[i][0], y - corners[i][1]);
                    if (!folded[i] && d < dp(170)) {
                        dragCorner = i;
                        dragFrom = d;
                    }
                }
            } else if (a == android.view.MotionEvent.ACTION_MOVE && dragCorner >= 0) {
                // Only the drag towards the middle counts.
                float d = (float) Math.hypot(x - corners[dragCorner][0], y - corners[dragCorner][1]);
                fold[dragCorner] = Math.max(0, Math.min(dp(150), (d - dragFrom) * 1.3f));
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
        if (shopOpen) now = shopAt;                       // the fight waits while you shop
        float ft = (now - fightAt) / 1000f, dt = Math.max(0, Math.min(0.05f, ft - lastFt));
        lastFt = ft;
        knock *= 0.8f;
        knockY *= 0.86f;
        ground = h * 0.62f;             // the whole fight plays mid-screen
        if (fstate == FIGHT) {
            // The fight happens mid-screen: up where you can reach it; higher levels also hop up and down.
            // The dino's own clock: stops when stunned, frozen or caught in the tornado, crawls when iced.
            boolean held = ft < stunUntil || ft < freezeUntil || ft < tornadoUntil;
            dclock += dt * (held ? 0 : ft < slowUntil ? 0.35f : 1);
            float hop = h * 0.12f * Math.min(1, level / 50f) * (float) Math.sin(dclock * (0.5f + level * 0.01f) * Math.PI * 2 + 1);
            if (type == PTERO) hop += h * 0.16f * (float) Math.sin(dclock * 1.4f) - h * 0.08f;   // flies up and down
            ground += hop + knockY;
            // The dino dodges more and faster the higher its level; above 30 it heals a little.
            float amp = w * 0.32f * Math.min(1, level / 40f), freq = 0.35f + level * 0.012f;
            boolean rage = type == REX && hp / maxHp < 0.4f;
            if (type == RAPTOR) {
                amp = Math.max(amp, w * 0.2f);
                freq *= 1.7f;
            } else if (type == PTERO) {
                amp = Math.max(amp, w * 0.18f);
            } else if (type == STEGO) {
                freq *= 0.8f;
            }
            if (rage) freq *= 1.5f;
            float fx = mid + amp * (float) Math.sin(dclock * freq * Math.PI * 2) + knock;
            // Triceratops: every 3.5 s it charges across the screen, horns first; taps bounce off.
            float cp = dclock % 3.5f;
            charging = type == TRIKE && dclock > 1.5f && cp < 0.7f && !held;
            if (charging) fx += (float) Math.sin(cp / 0.7f * Math.PI) * w * 0.3f * (((int) (dclock / 3.5f)) % 2 == 0 ? 1 : -1);
            boolean right = Math.cos(dclock * freq * Math.PI * 2) > 0 && amp > 0;
            if (ft < tornadoUntil) {
                damage(maxHp * 0.25f / 3f * dt, fx, ground - 50 * k, ft, false);
                fx += (float) Math.sin(ft * 14) * dp(14);
            }
            if (level > 30 && ft >= freezeUntil) hp = Math.min(maxHp, hp + (level - 30) * 0.012f * dt);
            if (ft - lastZap > 1.3f && ft > 1) {
                lastZap = ft;
                hp = Math.max(0, hp - (owns(TURBO) ? 1.05f : 0.35f));     // the robot helps a bit (turbo: 3×)
            }
            hurt = hp / maxHp > 0.8f ? 0 : hp / maxHp > 0.6f ? 1 : hp / maxHp > 0.4f ? 2 : hp / maxHp > 0.2f ? 3 : 4;
            float cd = crumbleAt < 0 ? -1 : ft - crumbleAt;
            if (cd < 0.9f) {
                c.save();
                if (hurt >= 4) c.rotate(6 * (float) Math.sin(ft * 5), fx, ground);
                if (cd >= 0) c.translate((rnd.nextFloat() - 0.5f) * dp(10) * cd, (rnd.nextFloat() - 0.5f) * dp(6) * cd);
                if (ft < tornadoUntil) c.rotate(25 * (float) Math.sin(ft * 9), fx, ground - 50 * k);
                drawSide(c, fx, ground, k, Math.abs(knock) > dp(2) ? 0.7f : 0.15f, held ? dclock : ft, amp > 0 && !held, !right);
                c.restore();
                if (cd >= 0) cracks(c, crumbleRect, cd / 0.9f);
                else if (hurt >= 4) smoke(c, fx, ground, k, ft);
            }
            if (cd >= 0) {
                crumble(c, crumbleRect, cd, k);
                if (cd < 2.2f) bubble(c, "KRRRCHHHHHH!", crumbleRect.centerX(), crumbleRect.top - dp(10), true, true);
                if (cd > 2.2f) hp = 0;
            }
            if (crumbleAt < 0) markDino(fx, ground, k, 1);
            float rx = Math.min(w - dp(40), fx + 100 * k), ry = ground - 150 * k + (float) Math.sin(ft * 3) * dp(4);
            drawRobot(c, rx, ry, dp(1), ft - lastZap < 0.2f);
            if (ft - lastZap < 0.2f) zap(c, rx - dp(22), ry + dp(10), fx, ground - 55 * k);
            weaponsFx(c, w, h, ft, dt);
            drawPops(c, ft);
            weaponBar(c, w, h, ft);
            hud(c, w, TYPE_NAME[type] + " · LV " + level + " · " + bones() + " 🦴", hp / maxHp, Math.max(0, timeLimit - ft), rust);
            if (ft - shopMsgAt < 2f && !shopOpen) bubble(c, shopMsg, w / 2, slots[0].top - dp(6), false, false);
            if (ft < freezeUntil) bubble(c, "⏳ " + (int) Math.ceil(freezeUntil - ft), fx, ground - 110 * k, true, false);
            else if (ft < stunUntil) bubble(c, "🍌 RUTSCH!", fx, ground - 110 * k, true, false);
            else if (ft < slowUntil) bubble(c, "❄️ BRRR", fx, ground - 110 * k, false, false);
            if (ft - critAt < 0.6f) bubble(c, "KRIT! ×2", critX, critY - dp(30), true, false);
            if (shopOpen) drawShop(c, w, h);
            if (ft < 3f) bubble(c, TYPE_TIP[type], rx, ry - dp(30), false, false);
            else if (charging) bubble(c, "BLOCK!", fx, ground - 110 * k, true, false);
            else if (rage && ft % 2 < 0.8f) bubble(c, "WUT!", fx, ground - 110 * k, true, false);
            if (hp <= 0) {
                fstate = KO;
                phaseAt = ft;
                flaming = false;
                bonesWon = (6 + level / 2) * (owns(MAGNET) ? 3 : 2) / 2;
                store().edit().putInt("dinoBones", bones() + bonesWon).apply();
                host.levelUp();
                host.chomp();
            } else if (ft >= timeLimit && crumbleAt < 0) {     // a spell that's already crumbling still wins
                fstate = FLIGHT;
                flaming = false;
                phaseAt = ft;
                nextEat = ft + 0.8f;
                cutAt = 4 + rnd.nextInt(5);
            }
        } else if (fstate == KO) {
            float d = ft - phaseAt, fall = ease(Math.min(1, d / 0.5f));
            hurt = 5;
            if (crumbleAt >= 0) {
                crumble(c, crumbleRect, 2.2f + d, k);        // just the rubble left
            } else {
                c.save();
                c.rotate(180 * fall, mid, ground - 47 * k * fall);
                dead = fall > 0.5f;
                drawSide(c, mid, ground, k, 0.4f, ft, false, true);
                dead = false;
                c.restore();
            }
            // Stars circling.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFC400);
            for (int i = 0; i < 5; i++) {
                double a = ft * 4 + i * Math.PI * 2 / 5;
                c.drawCircle(mid + (float) Math.cos(a) * 30 * k, ground - 70 * k + (float) Math.sin(a) * 8 * k, 4 * k, p);
            }
            bubble(c, "K.O.!", mid, ground - 90 * k, true, true);
            bubble(c, "🤖 Gewonnen! +" + bonesWon + " 🦴 · Dino jetzt Level " + Math.min(99, level + 1) + ".", w / 2, dp(150), false, false);
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
            if (fstate == FLIGHT && dragCorner < 0) foldHints(c, w, h, ft);
            int n = 0;
            for (boolean f : folded) if (f) n++;
            float rx = w - dp(60), ry = dp(170) + (float) Math.sin(ft * 3) * dp(4);
            drawRobot(c, rx, ry, dp(1), false);
            if (fstate == FLIGHT) {
                hud(c, w, "BILDSCHIRM FALTEN · " + n + " / 4", n / 4f, -1, 0xFF3C8DFF);
                bubble(c, ft - warnAt < 1.2f ? "🤖 Erst die Diagonale!" : n == 0
                    ? "🤖 Ecke zur Mitte ziehen – wie ein Eselsohr!" : n == 1 ? "🤖 Jetzt die gegenüberliegende Ecke!" : "🤖 Noch die letzten Ecken!", rx, ry - dp(30), false, false);
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

    /** Throws in flight, the streams, swipe trails, sound waves, bolts, meteor and tornado. */
    private void weaponsFx(Canvas c, float w, float h, float ft, float dt) {
        float ox = w / 2f, oy = h - dp(120);
        // Throws: an arc (or a straight shot) from the bottom to where you tapped; a hit if the dino is still there.
        for (int i = stones.size() - 1; i >= 0; i--) {
            float[] st = stones.get(i);
            int item = (int) st[5];
            float dur = item == BOW ? 0.18f : item == ROCKET ? 0.7f : item == BOOMERANG ? 0.45f : item == WATER ? 0.45f : 0.38f;
            float f = (ft - st[4]) / dur;
            if (item == BOOMERANG && f >= 1 && f < 2.6f) {
                // Circles round the target, hits again on the turn, flies home.
                if (st.length < 7) {
                    st = new float[]{st[0], st[1], st[2], st[3], st[4], st[5], 0};
                    stones.set(i, st);
                }
                if (st[6] == 0) {
                    st[6] = 1;
                    hitAt(item, st[2], st[3], ft);
                } else if (st[6] == 1 && f > 1.7f) {
                    st[6] = 2;
                    hitAt(item, st[2], st[3], ft);
                }
                float back = Math.max(0, (f - 1.8f) / 0.8f), ang = (f - 1) * 9;
                float bx = st[2] + (float) Math.cos(ang) * dp(26) * (1 - back), by = st[3] + (float) Math.sin(ang) * dp(18) * (1 - back);
                bx += (st[0] - bx) * back;
                by += (st[1] - by) * back;
                drawThrown(c, item, bx, by, ft, 0);
                continue;
            }
            if (f >= 1) {
                stones.remove(i);
                if (item != BOOMERANG) hitAt(item, st[2], st[3], ft);
                continue;
            }
            float lift = item == BOW || item == ROCKET ? 0 : (float) Math.sin(f * Math.PI) * dp(90);
            float sx = st[0] + (st[2] - st[0]) * f, sy = st[1] + (st[3] - st[1]) * f - lift;
            if (item == ROCKET) sx += (float) Math.sin(f * 20) * dp(4);
            drawThrown(c, item, sx, sy, ft, f);
        }
        // Streams: while the finger is down, from the bottom to it; each weapon its own fuel.
        int kind = W_KIND[weapon];
        if (kind == K_STREAM && flaming && crumbleAt < 0) {
            if (fuels[weapon] > 0) {
                fuels[weapon] = Math.max(0, fuels[weapon] - dt);
                p.setStyle(Paint.Style.FILL);
                int[] confetti = {0xFFFF4FA0, 0xFFFFD60A, 0xFF3C8DFF, 0xFF2BBF6A, 0xFFA77BFF, 0xFFFF7A2B};
                for (int i = 0; i < 26; i++) {
                    float f = (i + rnd.nextFloat()) / 26f;
                    float x = ox + (fingerX - ox) * f + (rnd.nextFloat() - 0.5f) * dp(18) * f;
                    float y = oy + (fingerY - oy) * f + (rnd.nextFloat() - 0.5f) * dp(18) * f;
                    if (weapon == CONFETTI) {
                        p.setColor(confetti[rnd.nextInt(confetti.length)]);
                        c.drawRect(x - dp(4), y - dp(3), x + dp(4), y + dp(3), p);
                        continue;
                    }
                    if (weapon == ICE) p.setColor(f < 0.35f ? 0xFFFFFFFF : f < 0.7f ? 0xFFBFE9FF : 0xFF6FC3F2);
                    else p.setColor(f < 0.35f ? 0xFFFFF27A : f < 0.7f ? 0xFFFFA62B : 0xFFE5482C);
                    p.setAlpha((int) (230 * (1 - f * 0.4f)));
                    c.drawCircle(x, y, dp(5 + 13 * f), p);
                }
                p.setAlpha(255);
                if (dinoRect.contains(fingerX, fingerY)) {
                    float dps = weapon == ICE ? 3.5f : weapon == CONFETTI ? 2f : 5.5f;
                    if (damage(dps * dt, fingerX, fingerY, ft, false)) {
                        knockY = Math.max(knockY, dp(8));
                        if (weapon == ICE) slowUntil = Math.max(slowUntil, ft + 0.6f);
                        if (rnd.nextFloat() < dt * 6) pops.add(new float[]{fingerX, fingerY - dp(20), ft, 1});
                    }
                }
            } else if (ft - shopMsgAt > 2f) {
                shopSay(W_NAME[weapon] + " leer – nächster Kampf wieder voll.", ft);
            }
        }
        // Swipes: a glowing trail of the last 0.2 s.
        for (int i = trail.size() - 1; i >= 0; i--) if (ft - trail.get(i)[2] > 0.2f) trail.remove(i);
        if (trail.size() > 1) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            int glow = weapon == KATANA ? 0x55FFFFFF : weapon == LASER2 ? 0x77B04BFF : 0x5533E0FF;
            int core = weapon == KATANA ? 0xFFD8D8D8 : weapon == LASER2 ? 0xFFF3E6FF : 0xFFE9FDFF;
            float wide = weapon == LASER2 ? 2f : weapon == KATANA ? 0.6f : 1f;
            for (int pass = 0; pass < 2; pass++) {
                p.setColor(pass == 0 ? glow : core);
                p.setStrokeWidth(dp(pass == 0 ? 14 * wide : 4 * Math.max(1, wide * 0.8f)));
                path.reset();
                for (int i = 0; i < trail.size(); i++) {
                    float[] q = trail.get(i);
                    if (i == 0) path.moveTo(q[0], q[1]);
                    else path.lineTo(q[0], q[1]);
                }
                c.drawPath(path, p);
            }
        }
        // Guitar: rings from the tap; the first one reaching the dino hits, wherever you tapped.
        p.setStyle(Paint.Style.STROKE);
        for (int i = waves.size() - 1; i >= 0; i--) {
            float[] q = waves.get(i);
            float age = ft - q[2];
            if (age > 0.7f) {
                waves.remove(i);
                continue;
            }
            if (q[3] == 0 && age > 0.15f) {
                q[3] = 1;
                damage(1.2f, dinoRect.centerX(), dinoRect.centerY(), ft, true);
            }
            for (int ring = 0; ring < 3; ring++) {
                float r = dp(30) + (age - ring * 0.08f) * Math.max(w, h) * 1.1f;
                if (r < 0) continue;
                p.setStrokeWidth(dp(3));
                p.setColor(ring == 0 ? 0xFFFFD60A : ring == 1 ? 0xFFFF7A2B : rust);
                p.setAlpha((int) (220 * (1 - age / 0.7f)));
                c.drawCircle(q[0], q[1], r, p);
            }
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(20));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("🎵", q[0] + age * dp(40), q[1] - age * dp(90), p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setStyle(Paint.Style.STROKE);
        }
        // Lightning: a zigzag from the top of the screen down to the tap.
        for (int i = bolts.size() - 1; i >= 0; i--) {
            float[] q = bolts.get(i);
            float age = ft - q[2];
            if (age > 0.3f) {
                bolts.remove(i);
                continue;
            }
            java.util.Random br = new java.util.Random((long) (q[2] * 1000));
            for (int pass = 0; pass < 2; pass++) {
                p.setColor(pass == 0 ? 0x66FFF27A : 0xFFFFFFFF);
                p.setStrokeWidth(dp(pass == 0 ? 12 : 3));
                path.reset();
                path.moveTo(q[0] + (br.nextFloat() - 0.5f) * dp(60), 0);
                br.setSeed((long) (q[2] * 1000));
                for (int sgm = 1; sgm <= 9; sgm++) {
                    float f = sgm / 9f;
                    path.lineTo(q[0] + (sgm == 9 ? 0 : (br.nextFloat() - 0.5f) * dp(46)), q[1] * f);
                }
                c.drawPath(path, p);
            }
            if (age < 0.08f) c.drawColor(0x33FFFFFF);
        }
        // Meteor: falls from the top right onto the dino, then a big hit.
        if (meteorAt >= 0) {
            float f = (ft - meteorAt) / 0.7f;
            float tx = dinoRect.centerX(), ty = dinoRect.centerY();
            if (f >= 1) {
                meteorAt = -1;
                damage(maxHp * 0.4f, tx, ty, ft, true);
                knockY = Math.max(knockY, dp(80));
                host.stomp();
                for (int k = 0; k < 6; k++) pops.add(new float[]{tx + (rnd.nextFloat() - 0.5f) * dp(90), ty + (rnd.nextFloat() - 0.5f) * dp(60), ft, 1});
            } else {
                float mx = w + dp(40) + (tx - w - dp(40)) * f, my = -dp(40) + (ty + dp(40)) * f;
                p.setStyle(Paint.Style.FILL);
                for (int t = 0; t < 8; t++) {
                    float b = t / 8f;
                    p.setColor(t < 3 ? 0xFFFFF27A : t < 6 ? 0xFFFFA62B : 0xFFE5482C);
                    p.setAlpha((int) (200 * (1 - b)));
                    c.drawCircle(mx + b * dp(70), my - b * dp(60), dp(16) * (1 - b * 0.7f), p);
                }
                p.setAlpha(255);
                p.setColor(0xFF5A4A40);
                c.drawCircle(mx, my, dp(15), p);
            }
        }
        // Tornado: grey funnel around the dino.
        if (ft < tornadoUntil) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            float tx = dinoRect.centerX(), base = dinoRect.bottom;
            for (int ring = 0; ring < 9; ring++) {
                float f = ring / 8f, rw = dp(30) + f * dinoRect.width() * 0.9f;
                p.setColor(0xFF9A9A98);
                p.setAlpha((int) (200 * (1 - f * 0.5f)));
                float y = base - f * dinoRect.height() * 1.3f, sh = (float) Math.sin(ft * 12 + ring) * dp(10);
                c.drawOval(tx - rw / 2 + sh, y - dp(7), tx + rw / 2 + sh, y + dp(7), p);
            }
            p.setAlpha(255);
        }
    }

    /** A throw lands at (x, y): what it does depends on the item. */
    private void hitAt(int item, float x, float y, float ft) {
        RectF hit = new RectF(dinoRect);
        float splash = item == ROCKET ? dp(60) : item == WATER ? dp(40) : 0;
        hit.inset(-splash, -splash);
        if (item == ROCKET) {
            host.stomp();
            for (int k = 0; k < 5; k++) pops.add(new float[]{x + (rnd.nextFloat() - 0.5f) * dp(80), y + (rnd.nextFloat() - 0.5f) * dp(60), ft, 1});
        }
        if (crumbleAt >= 0 || !hit.contains(x, y)) {
            pops.add(new float[]{x, y, ft, 0});
            return;
        }
        float dmg = item == STONE ? 2.5f : item == BANANA ? 1 : item == SNOW ? 1.5f : item == BOOMERANG ? 2
            : item == WATER ? 3 : item == BOW ? 3 : item == ROCKET ? 6 : 2;
        if (damage(dmg, x, y, ft, true)) {
            if (item == BANANA) stunUntil = ft + 1.5f;
            if (item == SNOW) slowUntil = ft + 2.5f;
            if (item == ROCKET) knockY = Math.max(knockY, dp(70));
        }
    }

    /** One thrown thing in flight. */
    private void drawThrown(Canvas c, int item, float x, float y, float ft, float f) {
        p.setStyle(Paint.Style.FILL);
        if (item == STONE) {
            p.setColor(0xFF7A7570);
            c.drawCircle(x, y, dp(8 - 3 * f), p);
            p.setColor(0xFFA8A29C);
            c.drawCircle(x - dp(2), y - dp(2), dp(3 - f), p);
            return;
        }
        if (item == SNOW) {
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, dp(9 - 3 * f), p);
            p.setColor(0xFFBFE9FF);
            c.drawCircle(x + dp(2), y + dp(2), dp(4 - f), p);
            return;
        }
        if (item == WATER) {
            p.setColor(0xFF3C8DFF);
            c.drawCircle(x, y, dp(11 - 3 * f), p);
            p.setColor(0xFFBFE0FF);
            c.drawCircle(x - dp(3), y - dp(3), dp(3), p);
            return;
        }
        if (item == BOW) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0xFF8A5A2B);
            c.drawLine(x, y + dp(22), x, y - dp(4), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFB0B0B0);
            path.reset();
            path.moveTo(x, y - dp(12));
            path.lineTo(x - dp(5), y - dp(2));
            path.lineTo(x + dp(5), y - dp(2));
            path.close();
            c.drawPath(path, p);
            return;
        }
        // Emoji items: banana, boomerang (spinning), rocket.
        c.save();
        c.rotate(item == BOOMERANG ? ft * 900 : item == BANANA ? ft * 400 : -45, x, y);
        p.setTextSize(dp(item == ROCKET ? 30 : 24));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(W_ICON[item], x, y + dp(8), p);
        p.setTextAlign(Paint.Align.LEFT);
        c.restore();
        if (item == ROCKET) {
            p.setColor(0xFFFFA62B);
            for (int t = 1; t < 5; t++) {
                p.setAlpha(200 - t * 40);
                c.drawCircle(x, y + dp(10) + t * dp(8), dp(6 - t), p);
            }
            p.setAlpha(255);
        }
    }

    /** The belt along the bottom: four items to pick (price if not yet bought) and the shop. */
    private void weaponBar(Canvas c, float w, float h, float ft) {
        float sw = Math.min((w - dp(32)) / 5f - dp(6), dp(82)), sh = dp(72);
        float total = sw * 5 + dp(6) * 4, left = (w - total) / 2, top = h - dp(36) - sh;
        int have = bones();
        int[] b = belt();
        for (int i = 0; i < 5; i++) {
            RectF s = slots[i];
            s.set(left + i * (sw + dp(6)), top, left + i * (sw + dp(6)) + sw, top + sh);
            boolean shop = i == SHOP_SLOT;
            int it = shop ? -1 : b[i];
            boolean cast = !shop && W_KIND[it] == K_CAST;
            boolean own = !shop && owns(it) && !cast, sel = !shop && weapon == it && !cast;
            boolean afford = shop || have >= W_COST[it];
            p.setStyle(Paint.Style.FILL);
            p.setColor(shop ? 0xFF1A1A1A : sel ? 0xFFFFF4E8 : 0xF2FAFAF8);
            c.drawRoundRect(s, dp(14), dp(14), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(sel || shop ? 3 : 1.5f));
            p.setColor(sel || shop ? rust : 0xFF1A1A1A);
            c.drawRoundRect(s, dp(14), dp(14), p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(24));
            p.setAlpha(own || afford ? 255 : 110);
            c.drawText(shop ? "🛒" : W_ICON[it], s.centerX(), s.top + dp(32), p);
            p.setAlpha(255);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(9.5f));
            p.setLetterSpacing(0.04f);
            String sub = shop ? "SHOP" : own ? W_NAME[it] : W_COST[it] + " 🦴";
            if (p.measureText(sub) > s.width() - dp(6)) sub = sub.substring(0, Math.min(sub.length(), 7)) + "…";
            p.setColor(shop ? 0xFFFAFAF8 : own ? 0xFF1A1A1A : afford ? rust : 0xFF9A9A98);
            c.drawText(sub, s.centerX(), s.bottom - dp(14), p);
            if (own && W_KIND[it] == K_STREAM) {
                // Fuel gauge.
                float fw = s.width() - dp(20);
                p.setColor(0xFFE0E0D8);
                c.drawRect(s.left + dp(10), s.bottom - dp(8), s.left + dp(10) + fw, s.bottom - dp(5), p);
                p.setColor(it == ICE ? 0xFF6FC3F2 : it == CONFETTI ? 0xFFFF4FA0 : 0xFFFFA62B);
                c.drawRect(s.left + dp(10), s.bottom - dp(8), s.left + dp(10) + fw * fuels[it] / 4.5f, s.bottom - dp(5), p);
            }
            p.setTextAlign(Paint.Align.LEFT);
            p.setLetterSpacing(0);
            p.setTypeface(Typeface.DEFAULT);
        }
    }

    /* ================= The bone shop ================= */

    private void openShop() {
        shopOpen = true;
        shopAt = SystemClock.uptimeMillis();
        flaming = false;
        trail.clear();
        shopMsg = "Tipp ein Teil: kaufen. Gekauft: kommt in den gewählten Gürtel-Platz.";
        shopMsgAt = Float.MAX_VALUE / 4;
        invalidate();
    }

    private void closeShop() {
        shopOpen = false;
        fightAt += SystemClock.uptimeMillis() - shopAt;      // the fight carries on where it stopped
        lastFt = (SystemClock.uptimeMillis() - fightAt) / 1000f;
        shopMsgAt = -9;
        invalidate();
    }

    private boolean shopTouch(android.view.MotionEvent e) {
        if (e.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return true;
        float x = e.getX(), y = e.getY(), ft = Float.MAX_VALUE / 4;
        if (shopClose.contains(x, y)) {
            closeShop();
            return true;
        }
        for (int i = 0; i < 4; i++) {
            if (beltCells[i].contains(x, y)) {
                editSlot = i;
                invalidate();
                return true;
            }
        }
        for (int i = 0; i < shopCells.length; i++) {
            if (!shopCells[i].contains(x, y)) continue;
            boolean cast = W_KIND[i] == K_CAST, passive = W_KIND[i] == K_PASSIVE;
            if (!cast && !owns(i)) {
                if (!buy(i, ft)) {
                    shopMsgAt = ft;
                    break;
                }
            }
            if (passive) {
                shopSay(W_ICON[i] + " " + W_NAME[i] + ": " + W_DESC[i], ft);
            } else {
                setBelt(editSlot, i);
                if (!cast) {
                    weapon = i;
                    store().edit().putInt("dinoWeaponSel", i).apply();
                }
                shopSay(W_ICON[i] + " " + W_NAME[i] + " → Platz " + (editSlot + 1) + ". " + W_DESC[i], ft);
            }
            break;
        }
        invalidate();
        return true;
    }

    /** Full-screen shop: bones on top, all items in a grid, the belt and "back to the fight" below. */
    private void drawShop(Canvas c, float w, float h) {
        c.drawColor(0xF20E0E0D);
        float pad = dp(16), top = dp(56);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextAlign(Paint.Align.LEFT);
        p.setLetterSpacing(0.16f);
        p.setTextSize(dp(11));
        p.setColor(rust);
        c.drawText("— KNOCHEN-SHOP", pad, top, p);
        p.setLetterSpacing(-0.03f);
        p.setTextSize(dp(30));
        p.setColor(0xFFF4F2EC);
        p.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        c.drawText(bones() + " 🦴", pad, top + dp(38), p);
        p.setLetterSpacing(0);

        int cols = w > dp(600) ? 7 : 5;
        float gap = dp(6), cw = (w - pad * 2 - gap * (cols - 1)) / cols, ch = Math.min(dp(86), cw * 1.15f);
        float gy = top + dp(58);
        int have = bones();
        int[] b = belt();
        for (int i = 0; i < shopCells.length; i++) {
            int col = i % cols, row = i / cols;
            RectF r = shopCells[i];
            r.set(pad + col * (cw + gap), gy + row * (ch + gap), pad + col * (cw + gap) + cw, gy + row * (ch + gap) + ch);
            boolean cast = W_KIND[i] == K_CAST, passive = W_KIND[i] == K_PASSIVE;
            boolean own = owns(i) && !cast, onBelt = false;
            for (int v : b) if (v == i) onBelt = true;
            boolean afford = have >= W_COST[i];
            p.setStyle(Paint.Style.FILL);
            p.setColor(own ? 0xFF262624 : 0xFF1A1A18);
            c.drawRoundRect(r, dp(12), dp(12), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(onBelt ? 2.5f : 1));
            p.setColor(onBelt ? rust : passive && own ? 0xFF2BBF6A : own ? 0xFF5A5A58 : afford ? 0xFFE8B931 : 0xFF2C2C2A);
            c.drawRoundRect(r, dp(12), dp(12), p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(Math.min(dp(26), ch * 0.3f));
            p.setAlpha(own || afford || cast ? 255 : 90);
            c.drawText(W_ICON[i], r.centerX(), r.top + ch * 0.42f, p);
            p.setAlpha(255);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(Math.min(dp(8.5f), cw / 9f));
            String name = W_NAME[i];
            while (p.measureText(name) > cw - dp(6) && name.length() > 3) name = name.substring(0, name.length() - 1);
            p.setColor(0xFFF4F2EC);
            c.drawText(name, r.centerX(), r.top + ch * 0.66f, p);
            String sub = passive && own ? "AKTIV" : own ? (onBelt ? "IM GÜRTEL" : "BESITZT") : cast ? W_COST[i] + "🦴/MAL" : W_COST[i] + " 🦴";
            p.setColor(passive && own ? 0xFF2BBF6A : own ? 0xFF9A9A98 : afford ? 0xFFE8B931 : 0xFF6F6E69);
            while (p.measureText(sub) > cw - dp(4) && sub.length() > 3) sub = sub.substring(0, sub.length() - 1);
            c.drawText(sub, r.centerX(), r.top + ch * 0.86f, p);
        }
        int rows = (shopCells.length + cols - 1) / cols;
        float by = gy + rows * (ch + gap) + dp(14);

        // The belt: pick the place, then an item.
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(10));
        p.setLetterSpacing(0.16f);
        p.setColor(0xFF9A9A98);
        c.drawText("GÜRTEL · PLATZ WÄHLEN, DANN TEIL ANTIPPEN", pad, by, p);
        p.setLetterSpacing(0);
        float bw = (w - pad * 2 - gap * 3) / 4, bh = dp(54);
        for (int i = 0; i < 4; i++) {
            RectF r = beltCells[i];
            r.set(pad + i * (bw + gap), by + dp(8), pad + i * (bw + gap) + bw, by + dp(8) + bh);
            p.setStyle(Paint.Style.FILL);
            p.setColor(i == editSlot ? 0xFF3A1814 : 0xFF1A1A18);
            c.drawRoundRect(r, dp(12), dp(12), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(i == editSlot ? 2.5f : 1));
            p.setColor(i == editSlot ? rust : 0xFF3A3A38);
            c.drawRoundRect(r, dp(12), dp(12), p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(dp(22));
            c.drawText(W_ICON[b[i]], r.centerX() - dp(16), r.centerY() + dp(8), p);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(10));
            p.setColor(i == editSlot ? rust : 0xFF9A9A98);
            c.drawText(String.valueOf(i + 1), r.centerX() + dp(18), r.centerY() + dp(4), p);
        }
        // The last word (what you bought / what it does) and back to the fight.
        float my = by + dp(8) + bh + dp(26);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(dp(13));
        p.setColor(0xFFF4F2EC);
        String msg = shopMsg;
        while (p.measureText(msg) > w - pad * 2 && msg.length() > 4) msg = msg.substring(0, msg.length() - 2);
        if (!msg.equals(shopMsg)) msg = msg + "…";
        c.drawText(msg, w / 2, my, p);
        shopClose.set(pad, Math.min(h - dp(36) - dp(56), my + dp(16)), w - pad, Math.min(h - dp(36), my + dp(16) + dp(56)));
        p.setStyle(Paint.Style.FILL);
        p.setColor(rust);
        c.drawRoundRect(shopClose, dp(14), dp(14), p);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(dp(14));
        p.setLetterSpacing(0.1f);
        p.setColor(0xFFFAFAF8);
        c.drawText("WEITER KÄMPFEN ⚔", shopClose.centerX(), shopClose.centerY() + dp(5), p);
        p.setLetterSpacing(0);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    /** Cracks spreading over the dino while the spell takes hold. */
    private void cracks(Canvas c, RectF b, float f) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.2f));
        p.setColor(0xFF1A1A1A);
        java.util.Random cr = new java.util.Random(7);
        for (int i = 0; i < 9; i++) {
            float x = b.left + b.width() * cr.nextFloat(), y = b.top + b.height() * cr.nextFloat();
            path.reset();
            path.moveTo(x, y);
            for (int j = 0; j < 4; j++) {
                x += (cr.nextFloat() - 0.5f) * dp(34) * f;
                y += (cr.nextFloat() - 0.2f) * dp(26) * f;
                path.lineTo(x, y);
            }
            c.drawPath(path, p);
        }
    }

    /** The dino falls apart into chunks that tumble down and settle as a heap of rubble. */
    private void crumble(Canvas c, RectF b, float d, float k) {
        if (d < 0.9f) return;
        float t = d - 0.9f;
        java.util.Random cr = new java.util.Random(11);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 46; i++) {
            float x0 = b.left + b.width() * cr.nextFloat(), y0 = b.top + b.height() * (0.15f + 0.85f * cr.nextFloat());
            float vx = (cr.nextFloat() - 0.5f) * dp(120), vy = -dp(60) * cr.nextFloat();
            float size = dp(4 + 9 * cr.nextFloat()), spin = (cr.nextFloat() - 0.5f) * 720;
            float x = x0 + vx * Math.min(t, 0.8f);
            float y = Math.min(b.bottom - size * 0.5f, y0 + vy * t + dp(900) * t * t);     // gravity, lands on the ground
            c.save();
            c.rotate(spin * Math.min(t, 0.8f), x, y);
            p.setColor(i % 4 == 0 ? rust : i % 3 == 0 ? ink : skin);
            c.drawRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, p);
            c.restore();
        }
        // Dust.
        if (t < 1.2f) {
            p.setColor(0xFF9A9A98);
            for (int i = 0; i < 8; i++) {
                p.setAlpha((int) (120 * (1 - t / 1.2f)));
                c.drawCircle(b.left + b.width() * (i + 0.5f) / 8, b.bottom - dp(10) - t * dp(30), dp(10) + t * dp(24), p);
            }
            p.setAlpha(255);
        }
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

    /** Shows which corners to fold next: a pulsing dog-ear and a finger that swipes in towards the middle. */
    private void foldHints(Canvas c, float w, float h, float ft) {
        float[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        float[][] dir = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
        int n = 0, any = -1;
        for (int i = 0; i < 4; i++) if (folded[i]) n++;
        for (int i = 0; i < 4; i++) {
            if (folded[i] || !foldAllowed(i)) continue;
            if (n == 0) {
                // First fold: hint only one corner (top left, or top right) so it's clear where to begin.
                if (any >= 0) continue;
                any = i;
            }
            float cx = corners[i][0], cy = corners[i][1], dx = dir[i][0], dy = dir[i][1];
            float e = dp(34) + dp(8) * (float) Math.sin(ft * 6);
            path.reset();
            path.moveTo(cx, cy);
            path.lineTo(cx + dx * e, cy);
            path.lineTo(cx, cy + dy * e);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(rust);
            c.drawPath(path, p);
            // Finger: from near the corner diagonally in, then again.
            float k = (ft * 0.9f) % 1f, s = dp(60) + dp(110) * ease(k);
            float fx = cx + dx * s * 0.7071f, fy = cy + dy * s * 0.7071f, sx = cx + dx * dp(42), sy = cy + dy * dp(42);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(4));
            p.setColor(0x99B5271E);
            c.drawLine(sx, sy, fx, fy, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFAFAF8);
            p.setAlpha((int) (255 * (1 - k * 0.6f)));
            c.drawCircle(fx, fy, dp(15), p);
            p.setAlpha(255);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setColor(0xFF1A1A1A);
            c.drawCircle(fx, fy, dp(15), p);
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

    /* How the attack ends: EAT (robot tazes, burp, new buddy), SPIT (tastes bad, buddy back),
       DODGE (buddy hops away, dino bites air and falls over), SNACK (robot throws it a pizza instead). */
    private static final int EAT = 0, SPIT = 1, DODGE = 2, SNACK = 3;
    private static int lastPlot = -1;
    private final int plot;
    private int lastStep = -1;

    DinoView(Context c, int ink, int paper, int rust, Host host) {
        super(c);
        this.ink = ink;
        this.paper = paper;
        this.rust = rust;
        this.host = host;
        lines = SCRIPTS[rnd.nextInt(SCRIPTS.length)];
        // Four endings, never the same one twice in a row.
        int pl;
        do pl = rnd.nextInt(4); while (pl == lastPlot);
        plot = lastPlot = pl;
        type = rnd.nextInt(5);
        skin = mix(paper, TYPE_TINT[type], type == REX ? 0 : 0.45f);
        setClickable(false);
        setFocusable(false);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static int mix(int a, int b, float f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
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
        tilt = 0;
        float eatX = b[0] + 48 * k;          // head over the buddy
        if (t < 4) {
            x = w + 80 * k - (w + 80 * k - mid) * ease(t / 4f);
            walking = true;
        } else if (t < 15) {
            x = mid;
            front = t >= 4.6f;
            if (front && ((t > 5 && t < 8) || (t > 12 && t < 14))) jaw = 0.6f + 0.4f * (float) Math.abs(Math.sin(t * 9));
        } else if (plot != EAT) {
            float[] pose = plotPose(t, mid, eatX, w, k);
            x = pose[0];
            jaw = pose[1];
            front = pose[2] > 0;
            walking = pose[3] > 0;
            facingLeft = pose[4] > 0;
            tilt = pose[5];
            shake = pose[6];
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
        if (plot == EAT || plot == SPIT) {
            if (once(3, t, 17.2f)) {
                host.chomp();
                host.hideBuddy();
            }
        }
        // The new buddy only shows up once dino and robot are both gone.
        if (plot == EAT && once(4, t, 36.4f)) host.swapBuddy(lines[8]);
        if (plot == SPIT) {
            if (once(7, t, 21.7f)) {
                host.chomp();
                host.showBuddy();
            }
            if (once(8, t, 22.6f)) host.buddySay("Igitt. Dino-Spucke.");
        } else if (plot == DODGE) {
            if (once(7, t, 16.8f)) host.buddyDodge();
            if (once(8, t, 17.3f)) host.chomp();
            if (once(9, t, 18.4f)) host.buddySay("Zu langsam, Echse!");
            if (once(10, t, 18.9f)) host.stomp();
        } else if (plot == SNACK) {
            if (once(7, t, 18.5f)) host.chomp();
            if (once(8, t, 21.2f)) host.buddySay("Puh. Danke, Robo.");
        }

        // ---- robot: flies in at 5 s, hovers, tazes 22–25.5 s, leaves at 35 s ----
        float rx = w - dp(70), ry = ground - size * 1.75f;
        if (t < 5) rx = w + dp(60);
        else if (t < 6.5f) rx = w + dp(60) - (dp(130)) * ease((t - 5) / 1.5f);
        else if (t > robotLeave()) rx = w - dp(70) + dp(140) * ease((t - robotLeave()) / 1.2f);
        float rob = (float) Math.sin(t * 3) * dp(4);
        if (plot == SNACK && t > 14.6f && t < 19) {
            // Flies in between dino and buddy with the pizza.
            float g = ease(clamp((t - 14.6f) / 0.8f)) * (1 - ease(clamp((t - 18.2f) / 0.8f)));
            rx += ((mid + eatX) / 2 + dp(30) - rx) * g;
            ry += (ground - size * 1.4f - ry) * g;
        }
        if (plot == EAT && t > 21 && t < 26) {
            // Closer for the taser.
            float g = ease(clamp((t - 21) / 0.8f)) * (1 - ease(clamp((t - 25.2f) / 0.8f)));
            rx += (x + 40 * k - rx) * g * 0.6f;
            ry += (ground - size * 1.25f - ry) * g;
        }

        markDino(x, ground, k, 1);
        c.save();
        c.translate(shake * (float) Math.sin(t * 60), 0);
        if (tilt != 0) c.rotate(tilt, x, ground);
        if (front) {
            look = true;            // same dino, head turned to you (not a front-on smiley)
            drawSide(c, x, ground, k, jaw, t, false, true);
            look = false;
        }
        else drawSide(c, x, ground, k, jaw, t, walking, facingLeft);
        c.restore();
        if (plot == DODGE && t > 18.9f && t < 23) stars(c, x - 40 * k, ground - 70 * k, k, t);
        if (plot == SNACK && t > 16.4f && t < 18.6f) {
            // The pizza slice arcs from the robot into the open mouth.
            float f = ease((t - 16.4f) / 2.1f);
            float px = rx + (x - 54 * k - rx) * f, py = ry + (ground - 80 * k - ry) * f - (float) Math.sin(f * Math.PI) * dp(70);
            pizza(c, px, py, k, t * 300);
        }

        boolean tazing = plot == EAT && t > 22 && t < 25.5f;
        drawRobot(c, rx, ry + rob, dp(1), tazing);
        if (tazing) zap(c, rx - dp(22), ry + rob + dp(10), x, ground - 55 * k);

        // ---- speech ----
        if (t > 5 && t < 8) bubble(c, lines[0], x - 40 * k, ground - 116 * k, true, true);
        if (t > 7.4f && t < 10.5f) bubble(c, "🤖 " + lines[1], rx, ry + rob - dp(34), false, false);
        if (t > 12 && t < 14) bubble(c, lines[3], x - 40 * k, ground - 116 * k, true, true);
        if (t > 13.4f && t < (plot == SNACK ? 14.8f : 16)) bubble(c, "🤖 " + lines[4], rx, ry + rob - dp(34), false, false);
        if (plot != EAT) {
            plotSpeech(c, t, x, ground, k, rx, ry + rob);
            float end = plotEnd();
            if (t < end) postInvalidateOnAnimation();
            else post(host::done);
            return;
        }
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

    private float tilt;

    private float robotLeave() {
        return plot == EAT ? 35 : plot == SPIT ? 28.5f : plot == DODGE ? 26.5f : 27.5f;
    }

    private float plotEnd() {
        return plot == SPIT ? 30.5f : plot == DODGE ? 28.5f : 30;
    }

    /** {x, jaw, front, walking, facingLeft, tilt°, shake} from 15 s on for the other endings. */
    private float[] plotPose(float t, float mid, float eatX, float w, float k) {
        float x = mid, jaw = 0, front = 0, walking = 0, left = 1, tilt = 0, shake = 0;
        float off = -(eatX + 120 * k);       // walks off to the left
        if (plot == SPIT) {
            if (t < 17) {
                x = mid - (mid - eatX) * ease((t - 15) / 2f);
                walking = 1;
                jaw = clamp((t - 16) / 0.6f);
            } else if (t < 20) {
                x = eatX;
                jaw = t < 17.4f ? 1 - clamp((t - 17) / 0.4f) : (float) Math.abs(Math.sin((t - 17.4) * 7)) * 0.35f;
            } else if (t < 21.4f) {
                x = eatX;
                front = 1;                   // it doesn't like the taste
                shake = dp(3);
                jaw = 0.15f;
            } else if (t < 22.2f) {
                x = eatX;
                jaw = clamp((t - 21.4f) / 0.25f);          // ptui
            } else if (t < 24) {
                x = eatX;
                front = 1;
                jaw = 0.2f;
            } else {
                x = eatX + (off - eatX) * ease((t - 24) / 4.5f);
                walking = 1;
            }
        } else if (plot == DODGE) {
            if (t < 16.8f) {
                x = mid - (mid - eatX) * ease((t - 15) / 1.8f);
                walking = 1;
                jaw = clamp((t - 15.8f) / 0.6f);
            } else if (t < 17.4f) {
                x = eatX - 14 * k * ease((t - 16.8f) / 0.4f);      // lunges … into nothing
                jaw = 1 - clamp((t - 17.1f) / 0.2f);
            } else if (t < 18.9f) {
                x = eatX - 14 * k;
                tilt = -8 * (float) Math.sin((t - 17.4f) * 9) * (1 - clamp((t - 17.4f) / 1.5f));   // wobbles
                tilt -= 70 * ease(clamp((t - 18.4f) / 0.5f));                                       // and falls on its face
            } else if (t < 22.5f) {
                x = eatX - 14 * k;
                tilt = -70;
            } else if (t < 23.4f) {
                x = eatX - 14 * k;
                tilt = -70 * (1 - ease((t - 22.5f) / 0.9f));        // gets up
            } else {
                x = eatX - 14 * k + (off - eatX) * ease((t - 23.4f) / 4.2f);
                walking = 1;
            }
        } else {            // SNACK: stays in the middle, the robot throws the pizza
            if (t < 16.4f) {
                front = 1;
            } else if (t < 18.5f) {
                front = 1;
                jaw = clamp((t - 16.6f) / 0.6f);
            } else if (t < 21) {
                jaw = t < 18.8f ? 1 - clamp((t - 18.5f) / 0.3f) : (float) Math.abs(Math.sin((t - 18.8) * 7)) * 0.35f;
            } else if (t < 24.6f) {
                front = 1;
                jaw = t > 23.4f && t < 24.4f ? 0.9f : 0.2f;     // happy, then a burp
            } else {
                x = mid + (off - mid) * ease((t - 24.6f) / 4.8f);
                walking = 1;
            }
        }
        return new float[]{x, jaw, front, walking, left, tilt, shake};
    }

    private void plotSpeech(Canvas c, float t, float x, float ground, float k, float rx, float ry) {
        float hy = ground - 116 * k, by = ry - dp(34);
        if (plot == SPIT) {
            if (t > 18.2f && t < 20) bubble(c, "*mampf* … hm?", x - 30 * k, ground - 95 * k, true, false);
            if (t > 20 && t < 21.6f) bubble(c, "BÄÄÄH!", x - 40 * k, hy, true, true);
            if (t > 21.6f && t < 22.6f) bubble(c, "*PTUI*", x - 40 * k, ground - 100 * k, true, false);
            if (t > 22.4f && t < 25.2f) bubble(c, "🤖 Übersetzt: »Schmeckt nach Akku und Fell.«", rx, by, false, false);
            if (t > 25 && t < 28) bubble(c, lines[9], Math.max(dp(90), x), ground - 100 * k, true, false);
        } else if (plot == DODGE) {
            if (t > 17.2f && t < 18.6f) bubble(c, "*SCHNAPP*", x - 40 * k, ground - 100 * k, true, false);
            if (t > 19.2f && t < 21) bubble(c, "Aua.", x - 30 * k, ground - 60 * k, true, false);
            if (t > 20.4f && t < 23.2f) bubble(c, "🤖 Übersetzt: »Das war Absicht. Ich wollte liegen.«", rx, by, false, false);
            if (t > 23.4f && t < 26) bubble(c, lines[10], Math.max(dp(90), x), ground - 100 * k, true, false);
        } else {
            if (t > 14.8f && t < 17) bubble(c, "🤖 Halt! Nimm lieber das!", rx, by, false, false);
            if (t > 18.8f && t < 21) bubble(c, "*mampf* *mampf*", x - 30 * k, ground - 95 * k, true, false);
            if (t > 21 && t < 23.2f) bubble(c, "RAWR ♥", x - 40 * k, hy, true, true);
            if (t > 21.8f && t < 24.8f) bubble(c, "🤖 Übersetzt: »Mit Ananas wär's perfekt.«", rx, by, false, false);
            if (t > 23.4f && t < 24.6f) bubble(c, "*RÜLPS*", x - 40 * k, ground - 110 * k, true, false);
        }
    }

    /** Dizzy stars over its head. */
    private void stars(Canvas c, float x, float y, float k, float t) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFC400);
        for (int i = 0; i < 4; i++) {
            double a = t * 4 + i * Math.PI / 2;
            c.drawCircle(x + (float) Math.cos(a) * 24 * k, y + (float) Math.sin(a) * 7 * k, 3.6f * k, p);
        }
    }

    /** A spinning pizza slice. */
    private void pizza(Canvas c, float x, float y, float k, float deg) {
        c.save();
        c.rotate(deg, x, y);
        path.reset();
        path.moveTo(x, y - 14 * k);
        path.lineTo(x + 11 * k, y + 9 * k);
        path.lineTo(x - 11 * k, y + 9 * k);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFD27A);
        c.drawPath(path, p);
        p.setColor(0xFFC8862E);
        c.drawRect(x - 12 * k, y + 7 * k, x + 12 * k, y + 11 * k, p);
        p.setColor(rust);
        c.drawCircle(x - 3 * k, y + 2 * k, 2.2f * k, p);
        c.drawCircle(x + 4 * k, y + 4 * k, 2 * k, p);
        c.drawCircle(x, y - 5 * k, 1.8f * k, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * k);
        p.setColor(ink);
        c.drawPath(path, p);
        c.restore();
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
        if (type == RAPTOR) k *= 0.82f;     // the raptor is small
        if (type == PTERO) wings(c, k, t);
        backSpines(c, k);
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
        p.setColor(skin);
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
        p.setColor(skin);
        c.drawRoundRect(r, 9 * k, 9 * k, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        c.drawRoundRect(r, 9 * k, 9 * k, p);
        if (type == TRIKE) horns(c, k);
        if (type == PTERO || type == RAPTOR) crest(c, k);
        c.save();
        c.rotate(-jaw * 32, -36 * k, -76 * k);
        r.set(-64 * k, -80 * k, -32 * k, -70 * k);
        p.setStyle(Paint.Style.FILL);
        p.setColor(skin);
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

    /** Big, bold back armour, outlined in ink so it reads: rex spikes, stego plates, small rows for the rest. */
    private void backSpines(Canvas c, float k) {
        p.setStrokeWidth(2.6f * k);
        int n = type == STEGO ? 6 : type == REX ? 8 : 7;
        for (int i = 0; i < n; i++) {
            float f = i / (float) (n - 1);
            float sx = -24 + f * 62, sy = -57 + (float) Math.pow(f, 2) * 24;    // along the back down to the tail
            float bulge = 1 - Math.abs(f - 0.35f) * 1.1f;                   // tallest over the shoulders
            path.reset();
            if (type == STEGO) {
                float hgt = 22 + 24 * bulge, wd = 9 + 5 * bulge;
                path.moveTo((sx - wd) * k, (sy + 4) * k);
                path.quadTo((sx - wd - 2) * k, (sy - hgt * 0.6f) * k, sx * k, (sy - hgt) * k);
                path.quadTo((sx + wd + 2) * k, (sy - hgt * 0.6f) * k, (sx + wd) * k, (sy + 4) * k);
            } else {
                float hgt = (type == REX ? 22 + 18 * bulge : 14 + 10 * bulge), wd = type == REX ? 8 : 6;
                path.moveTo((sx - wd) * k, (sy + 4) * k);
                path.lineTo((sx - 2) * k, (sy - hgt) * k);      // leans back: sharper, meaner
                path.lineTo((sx + wd) * k, (sy + 4) * k);
            }
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(i % 2 == 0 || type != STEGO ? rust : 0xFFD9472F);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(ink);
            c.drawPath(path, p);
        }
    }

    /** Triceratops: a frill behind the skull and three horns. */
    private void horns(Canvas c, float k) {
        p.setStrokeWidth(2.2f * k);
        r.set(-40 * k, -108 * k, -18 * k, -76 * k);
        p.setStyle(Paint.Style.FILL);
        p.setColor(rust);
        c.drawOval(r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        c.drawOval(r, p);
        float[][] hs = {{-58, -94, -66, -112}, {-46, -94, -52, -114}, {-66, -82, -78, -90}};
        for (float[] hh : hs) {
            path.reset();
            path.moveTo((hh[0] - 3) * k, hh[1] * k);
            path.lineTo(hh[2] * k, hh[3] * k);
            path.lineTo((hh[0] + 3) * k, hh[1] * k);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFF4D6);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(ink);
            c.drawPath(path, p);
        }
    }

    /** Raptor feathers / ptero crest on the back of the head. */
    private void crest(Canvas c, float k) {
        p.setStrokeWidth(2.2f * k);
        path.reset();
        path.moveTo(-40 * k, -92 * k);
        path.lineTo(type == PTERO ? -12 * k : -24 * k, (type == PTERO ? -104 : -100) * k);
        path.lineTo(-32 * k, -84 * k);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(rust);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ink);
        c.drawPath(path, p);
    }

    /** Ptero-Rex: two big flapping wings behind the body. */
    private void wings(Canvas c, float k, float t) {
        float flap = (float) Math.sin(t * 7) * 22;
        p.setStrokeWidth(2.4f * k);
        for (int s = 0; s < 2; s++) {
            float tip = -110 - flap + s * 14;
            path.reset();
            path.moveTo(-10 * k, -54 * k);
            path.lineTo((24 + s * 12) * k, tip * k);
            path.quadTo((40 + s * 10) * k, (-80 - flap * 0.4f) * k, (34 + s * 6) * k, -52 * k);
            path.quadTo(14 * k, -60 * k, -10 * k, -54 * k);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(s == 0 ? mix(skin, ink, 0.25f) : skin);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(ink);
            c.drawPath(path, p);
        }
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
        p.setColor(skin);
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
