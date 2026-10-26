package ch.architectdna.home;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * NEXUS BREACH, native: the arena wave shooter from nexus-breach.architect-dna.ch, ported to Java
 * with the same rules and numbers (auto-attack, DASH / NOVA / STORM / PULL, six enemy types and a
 * boss every fifth wave with its special attacks, chain reactions, streaks, drops, power-ups, the
 * shop and the arena events), drawn in the ARC-DNA night style. Left thumb = joystick, right side
 * = the four abilities. Pets, achievements, themes and the meta shop are not ported (yet).
 */
public class NexusActivity extends Activity {

    private Game game;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        getWindow().setStatusBarColor(0xFF121211);
        getWindow().setNavigationBarColor(0xFF121211);
        game = new Game();
        setContentView(game);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
            getWindow().getInsetsController().setSystemBarsBehavior(
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        game.pause();
    }

    /* ======================================================================================== */

    private final class Game extends View {

        // ARC-DNA night palette.
        static final int BG = 0xFF121211, ARENA = 0xFF181816, LINE = 0xFF2C2C2A, INK = 0xFFEDEDE8, INK2 = 0xFFA5A5A0,
            INK3 = 0xFF6F6E69, RUST = 0xFFD9472F, GOLD = 0xFFF2C200, GREEN = 0xFF2BBF6A, BLUE = 0xFF3C8DFF,
            LILAC = 0xFFA77BFF, ORANGE = 0xFFF29A1F;

        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF rect = new RectF();
        final Random rnd = new Random();
        final SharedPreferences prefs = getSharedPreferences("nexus", MODE_PRIVATE);
        final float d = getResources().getDisplayMetrics().density;

        String state = "title";          // title, countdown, playing, paused, shop, dead
        float W, H, CX, CY, ARENA_R, S;  // S: game units → pixels (the web game was sized in CSS px)
        long last;
        float shakePow;

        Player player;
        final List<Enemy> enemies = new ArrayList<>();
        final List<Shot> shots = new ArrayList<>();
        final List<Particle> particles = new ArrayList<>();
        final List<FloatText> texts = new ArrayList<>();
        final List<Bolt> bolts = new ArrayList<>();
        final List<Orb> orbs = new ArrayList<>();         // health, xp, gold, power
        final List<float[]> meteors = new ArrayList<>();  // x, y, warnT, hit-player flag
        final List<Object[]> later = new ArrayList<>();   // {dueTime(Float), Runnable}

        int wave, score, coins, streak, mkCount, best;
        float streakTimer, streakMult = 1, mkTimer, waveTimer, countdownTimer, shopTimer, gameT;
        boolean waveActive;
        int countdownVal;
        String event;
        float eventTimer, eventSpawn;
        float[] pulseRings;
        List<Object> pulseHit = new ArrayList<>();
        List<Shop> shopItems = new ArrayList<>();

        // Touch: joystick on the left half, abilities on the right.
        int joyId = -1;
        float joySX, joySY, joyDX, joyDY;

        Game() {
            super(NexusActivity.this);
            best = prefs.getInt("best", 0);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        }

        float rand(float a, float b) {
            return a + rnd.nextFloat() * (b - a);
        }

        float dist(float ax, float ay, float bx, float by) {
            return (float) Math.hypot(bx - ax, by - ay);
        }

        void after(float sec, Runnable r) {
            later.add(new Object[]{gameT + sec, r});
        }

        void shake(float v) {
            shakePow = Math.max(shakePow, v * S);
        }

        void pause() {
            if (state.equals("playing") || state.equals("countdown")) state = "paused";
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            W = w;
            H = h;
            S = Math.min(w, h) / (W > H ? 420f : 400f);
            CX = W / 2;
            CY = W > H ? H * 0.5f : H * 0.44f;
            ARENA_R = Math.min(W, H) * (W > H ? 0.46f : 0.42f);
        }

        /* ---------------------------------- loop ---------------------------------- */

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            if (W == 0) {
                postInvalidateOnAnimation();
                return;
            }
            c.drawColor(BG);
            grid(c);
            shakePow *= 0.82f;
            c.save();
            c.translate((rnd.nextFloat() - 0.5f) * shakePow, (rnd.nextFloat() - 0.5f) * shakePow);
            switch (state) {
                case "title":
                    stepParticles(dt);
                    drawTitle(c);
                    break;
                case "playing":
                    step(dt);
                    drawWorld(c);
                    break;
                case "countdown":
                    drawWorld(c);
                    countdownTimer -= dt;
                    if (countdownTimer <= 0) {
                        countdownVal--;
                        countdownTimer = 1;
                        if (countdownVal <= 0) state = "playing";
                    }
                    overlay(c, 0x73000000);
                    big(c, String.valueOf(Math.max(1, countdownVal)), CY, 110, RUST);
                    break;
                case "paused":
                    drawWorld(c);
                    overlay(c, 0x94000000);
                    big(c, "PAUSE", CY - 10 * S, 40, INK);
                    small(c, "TIPPEN = WEITER", CY + 26 * S, 12, INK3);
                    break;
                case "shop":
                    shopTimer -= dt;
                    if (shopTimer <= 0) closeShop();
                    stepParticles(dt);
                    drawShop(c, dt);
                    break;
                case "dead":
                    stepParticles(dt);
                    drawWorld(c);
                    drawDead(c);
                    break;
                default:
                    break;
            }
            c.restore();
            postInvalidateOnAnimation();
        }

        void step(float dt) {
            gameT += dt;
            for (Iterator<Object[]> it = later.iterator(); it.hasNext(); ) {
                Object[] l = it.next();
                if ((Float) l[0] <= gameT) {
                    it.remove();
                    ((Runnable) l[1]).run();
                    break;          // one per frame keeps the list safe to modify inside runnables
                }
            }
            float mx = 0, my = 0;
            if (joyId >= 0) {
                float md = 40 * S;
                mx = Math.max(-1, Math.min(1, joyDX / md));
                my = Math.max(-1, Math.min(1, joyDY / md));
            }
            player.move(mx, my, dt);
            player.update(dt);
            separate(dt);
            for (int i = enemies.size() - 1; i >= 0; i--) {
                enemies.get(i).update(dt);
                if (enemies.get(i).dead) enemies.remove(i);
            }
            for (int i = shots.size() - 1; i >= 0; i--) {
                Shot s = shots.get(i);
                s.update(dt);
                if (!s.dead) {
                    if (s.mine) {
                        for (Enemy e : enemies) {
                            if (!e.dead && dist(s.x, s.y, e.x, e.y) < s.r + e.r) {
                                float dmg = s.dmg;
                                if (e.type.equals("shielded")) {
                                    double hit = Math.atan2(s.y - e.y, s.x - e.x);
                                    double diff = Math.abs(((hit - e.shieldAngle) + Math.PI * 3) % (Math.PI * 2) - Math.PI);
                                    if (diff < Math.PI / 3) dmg *= 0.2f;
                                }
                                e.hurt(dmg, 0);
                                s.dead = true;
                                burst(s.x, s.y, 9, 85, 4, s.col);
                                if (player.lifesteal > 0) player.hp = Math.min(player.maxHp, player.hp + s.dmg * player.lifesteal);
                                break;
                            }
                        }
                    } else if (dist(s.x, s.y, player.x, player.y) < s.r + player.r) {
                        player.hurt(s.dmg);
                        s.dead = true;
                    }
                }
                if (s.dead || dist(s.x, s.y, CX, CY) > ARENA_R * 1.8f) shots.remove(i);
            }
            stepParticles(dt);
            for (int i = bolts.size() - 1; i >= 0; i--) if ((bolts.get(i).life -= dt) <= 0) bolts.remove(i);
            for (int i = orbs.size() - 1; i >= 0; i--) {
                orbs.get(i).update(dt);
                if (orbs.get(i).dead) orbs.remove(i);
            }
            if (streakTimer > 0 && (streakTimer -= dt) <= 0) {
                streak = 0;
                streakMult = 1;
            }
            if (mkTimer > 0 && (mkTimer -= dt) <= 0) mkCount = 0;
            stepEvent(dt);
            if (waveActive && enemies.isEmpty()) {
                waveActive = false;
                waveTimer = 0;
                int bonus = 3 + wave * 2;
                coins += bonus;
                text(CX, CY - 50 * S, "WELLE GESCHAFFT!", GREEN, 2, 20);
                text(CX, CY - 24 * S, "+" + bonus + " MÜNZEN", GOLD, 2, 14);
                boolean shop = wave == 2 || (wave > 2 && wave % 3 == 0);
                if (shop) after(1.4f, () -> {
                    if (state.equals("playing")) openShop();
                });
                else waveTimer = 3;
            }
            if (!waveActive && waveTimer > 0 && (waveTimer -= dt) <= 0) nextWave();
            if (player.dead) {
                state = "dead";
                if (score > best) {
                    best = score;
                    prefs.edit().putInt("best", best).apply();
                }
            }
            if (particles.size() < 180 && rnd.nextFloat() < 0.03f) {
                double a = rnd.nextFloat() * Math.PI * 2;
                float r = rnd.nextFloat() * ARENA_R;
                particles.add(new Particle(CX + (float) Math.cos(a) * r, CY + (float) Math.sin(a) * r,
                    rand(-8, 8), rand(-25, -4), rand(1, 3), rand(1, 2), 0x40EDEDE8));
            }
        }

        void stepParticles(float dt) {
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle q = particles.get(i);
                q.x += q.vx * S * dt;
                q.y += q.vy * S * dt;
                q.vx *= 0.97f;
                q.vy *= 0.97f;
                if ((q.life -= dt) <= 0) particles.remove(i);
            }
            for (int i = texts.size() - 1; i >= 0; i--) {
                FloatText t = texts.get(i);
                t.y -= 30 * S * dt;
                if ((t.life -= dt) <= 0) texts.remove(i);
            }
        }

        /* --------------------------------- game flow -------------------------------- */

        void startGame() {
            player = new Player();
            enemies.clear();
            shots.clear();
            particles.clear();
            texts.clear();
            bolts.clear();
            orbs.clear();
            meteors.clear();
            later.clear();
            wave = score = coins = streak = mkCount = 0;
            streakMult = 1;
            streakTimer = mkTimer = waveTimer = gameT = 0;
            event = null;
            state = "playing";
            nextWave();
        }

        void nextWave() {
            wave++;
            waveActive = true;
            event = null;
            meteors.clear();
            boolean boss = wave % 5 == 0;
            if (boss) {
                double a = rnd.nextFloat() * Math.PI * 2;
                enemies.add(new Enemy(CX + (float) Math.cos(a) * (ARENA_R - 32 * S), CY + (float) Math.sin(a) * (ARENA_R - 32 * S), "boss"));
                text(CX, CY - 90 * S, "★ BOSS-WELLE " + wave + " ★", LILAC, 3, 24);
            } else {
                int nc = Math.min(2 + wave, 14), na = Math.max(0, wave / 2 - 1), nb = Math.max(0, wave / 3 - 1);
                for (int i = 0; i < nc; i++) spawn("grunt");
                for (int i = 0; i < na; i++) spawn("archer");
                for (int i = 0; i < nb; i++) spawn("brute");
                if (wave >= 4) spawn("shielded");
                if (wave >= 6) spawn("healer");
                text(CX, CY - 90 * S, "WELLE " + wave, BLUE, 2.2f, 22);
                if (rnd.nextFloat() < 0.4f) after(1.5f, this::arenaEvent);
                after(8, this::spawnPower);
            }
            shake(9);
            ring(CX, CY, 20, 140, 4, BLUE);
        }

        /** Enemies push each other apart instead of stacking into one blob. */
        void separate(float dt) {
            for (int i = 0; i < enemies.size(); i++) {
                Enemy a = enemies.get(i);
                for (int j = i + 1; j < enemies.size(); j++) {
                    Enemy b = enemies.get(j);
                    float dx = b.x - a.x, dy = b.y - a.y, dd = (float) Math.hypot(dx, dy), min = a.r + b.r + 2 * S;
                    if (dd >= min || dd < 0.01f) continue;
                    float push = (min - dd) * 0.5f * Math.min(1, dt * 12), nx = dx / dd, ny = dy / dd;
                    float wa = b.r / (a.r + b.r), wb = 1 - wa;   // the bigger one moves less
                    a.x -= nx * push * 2 * wa;
                    a.y -= ny * push * 2 * wa;
                    b.x += nx * push * 2 * wb;
                    b.y += ny * push * 2 * wb;
                }
            }
        }

        void spawn(String type) {
            double a = rnd.nextFloat() * Math.PI * 2;
            enemies.add(new Enemy(CX + (float) Math.cos(a) * (ARENA_R - 14 * S), CY + (float) Math.sin(a) * (ARENA_R - 14 * S), type));
        }

        void onKill() {
            mkTimer = 1.2f;
            mkCount++;
            if (mkCount == 2) text(CX, CY - 40 * S, "DOUBLE KILL!", ORANGE, 1.5f, 22);
            else if (mkCount == 3) text(CX, CY - 40 * S, "TRIPLE KILL!", RUST, 1.5f, 22);
            else if (mkCount == 4) text(CX, CY - 40 * S, "QUAD KILL!", LILAC, 1.5f, 22);
            else if (mkCount >= 5) {
                text(CX, CY - 40 * S, "MASSACRE!", GOLD, 1.5f, 22);
                burst(CX, CY, 50, 250, 8, GOLD);
                shake(15);
            }
        }

        void chain(float x, float y, float dmg, int depth) {
            if (depth >= 3) return;
            float range = (115 - depth * 22) * S;
            Enemy target = null;
            float bestD = range;
            for (Enemy e : enemies) {
                float dd = dist(x, y, e.x, e.y);
                if (!e.dead && dd < bestD) {
                    bestD = dd;
                    target = e;
                }
            }
            if (target == null) return;
            Enemy t = target;
            bolts.add(new Bolt(x, y, t.x, t.y, depth == 0 ? RUST : ORANGE));
            burst(t.x, t.y, 7, 80, 4, RUST);
            after(depth * 0.035f, () -> {
                if (!t.dead) t.hurt(dmg * (0.65f - depth * 0.1f), depth + 1);
            });
        }

        /* ---- power-ups ---- */

        static final int MAGNET = 0, BLITZ = 1, GHOST = 2, OVERLOAD = 3;

        void spawnPower() {
            if (!state.equals("playing") || !waveActive) return;
            List<Integer> pool = new ArrayList<>();
            pool.add(MAGNET);
            if (wave >= 3) pool.add(BLITZ);
            if (wave >= 6) pool.add(GHOST);
            if (wave >= 10) pool.add(OVERLOAD);
            double a = rnd.nextFloat() * Math.PI * 2;
            float r = rand(ARENA_R * 0.25f, ARENA_R * 0.7f);
            Orb o = new Orb(CX + (float) Math.cos(a) * r, CY + (float) Math.sin(a) * r, "power", 0);
            o.power = pool.get(rnd.nextInt(pool.size()));
            o.life = 18;
            orbs.add(o);
            text(CX, CY - 70 * S, "⬟ POWER-UP!", GOLD, 1.5f, 14);
        }

        void applyPower(int pw, float x, float y) {
            String[] names = {"MAGNET", "BLITZ", "GHOST", "OVERLOAD"};
            int[] cols = {0xFF00E5FF, GOLD, INK, RUST};
            if (pw == BLITZ) {
                float o = player.atkCd;
                player.atkCd *= 0.5f;
                after(6, () -> player.atkCd = o);
            } else if (pw == GHOST) {
                player.inv += 5;
            } else if (pw == OVERLOAD) {
                float o = player.dmg;
                player.dmg *= 2;
                after(6, () -> player.dmg = o);
            } else {
                for (Orb g : orbs) {
                    if (g.kind.equals("xp") || g.kind.equals("gold")) {
                        double a = Math.atan2(player.y - g.y, player.x - g.x);
                        g.vx += Math.cos(a) * 500;
                        g.vy += Math.sin(a) * 500;
                    }
                }
            }
            text(x, y - 32 * S, names[pw] + "!", cols[pw], 2.2f, 20);
            burst(x, y, 24, 140, 7, cols[pw]);
            shake(9);
        }

        /* ---- arena events: meteors, void pulse, frenzy ---- */

        void arenaEvent() {
            if (!state.equals("playing") || !waveActive) return;
            int t = rnd.nextInt(3);
            if (t == 0) {
                event = "meteor";
                eventTimer = 12;
                eventSpawn = 1.5f;
                text(CX, CY - 80 * S, "☄ METEORITENSCHAUER!", ORANGE, 2.5f, 18);
            } else if (t == 1) {
                event = "pulse";
                eventTimer = 0.8f;
                pulseRings = null;
                pulseHit.clear();
                text(CX, CY - 80 * S, "⚡ VOID-PULS!", LILAC, 2.5f, 18);
            } else {
                event = "frenzy";
                eventTimer = 10;
                for (Enemy e : enemies) {
                    e.speed *= 2;
                    e.dmg *= 1.5f;
                    e.frenzied = true;
                }
                text(CX, CY - 80 * S, "⚡ RASEREI!", RUST, 2.5f, 18);
            }
            shake(10);
        }

        void stepEvent(float dt) {
            for (int i = meteors.size() - 1; i >= 0; i--) {
                float[] m = meteors.get(i);
                if (m[2] > 0) {
                    m[2] -= dt;
                    continue;
                }
                m[1] += 200 * S * dt;
                float r = 18 * S;
                if (m[3] == 0 && dist(m[0], m[1], player.x, player.y) < r + player.r) {
                    m[3] = 1;
                    player.hurt(15);
                }
                for (Enemy e : enemies) if (!e.dead && !e.meteorHit && dist(m[0], m[1], e.x, e.y) < r + e.r) {
                    e.meteorHit = true;
                    e.hurt(60, 3);
                }
                if (m[1] > CY + ARENA_R + 50 * S) meteors.remove(i);
            }
            if (event == null) return;
            if (event.equals("meteor")) {
                eventTimer -= dt;
                if ((eventSpawn -= dt) <= 0) {
                    eventSpawn = 1.5f;
                    for (Enemy e : enemies) e.meteorHit = false;
                    meteors.add(new float[]{CX + (rnd.nextFloat() - 0.5f) * ARENA_R * 1.7f, CY - ARENA_R, 0.5f, 0});
                }
                if (eventTimer <= 0) event = null;
            } else if (event.equals("pulse")) {
                if (pulseRings == null) {
                    if ((eventTimer -= dt) <= 0) pulseRings = new float[]{0, -0.3f * ARENA_R / 1.5f, -0.6f * ARENA_R / 1.5f};
                    return;
                }
                boolean done = true;
                for (int k = 0; k < 3; k++) {
                    pulseRings[k] += ARENA_R / 1.5f * dt;
                    float r = pulseRings[k];
                    if (r < ARENA_R) done = false;
                    if (r < 0) continue;
                    for (Enemy e : enemies) {
                        String key = k + ":" + System.identityHashCode(e);
                        if (!e.dead && !pulseHit.contains(key) && Math.abs(dist(CX, CY, e.x, e.y) - r) < 20 * S) {
                            pulseHit.add(key);
                            e.hurt(80, 3);
                        }
                    }
                    String pk = k + ":player";
                    if (!pulseHit.contains(pk) && Math.abs(dist(CX, CY, player.x, player.y) - r) < 20 * S) {
                        pulseHit.add(pk);
                        player.hurt(15);
                    }
                }
                if (done) event = null;
            } else if (event.equals("frenzy")) {
                if ((eventTimer -= dt) <= 0) {
                    for (Enemy e : enemies) if (e.frenzied) {
                        e.speed /= 2;
                        e.dmg /= 1.5f;
                        e.frenzied = false;
                    }
                    event = null;
                }
            }
        }

        /* ---- shop ---- */

        final class Shop {
            final String name, desc;
            final int cost, col;
            final Runnable apply;

            Shop(String name, String desc, int cost, int col, Runnable apply) {
                this.name = name;
                this.desc = desc;
                this.cost = cost;
                this.col = col;
                this.apply = apply;
            }
        }

        static final float SHOP_TIME = 20;

        void openShop() {
            List<Shop> pool = new ArrayList<>();
            pool.add(new Shop("REPARATUR", "50 HP zurück", 10, GREEN, () -> player.hp = Math.min(player.maxHp, player.hp + 50)));
            pool.add(new Shop("FESTUNG", "+60 Max-HP und heilen", 25, BLUE, () -> {
                player.maxHp += 60;
                player.hp = Math.min(player.maxHp, player.hp + 60);
            }));
            pool.add(new Shop("OVERDRIVE", "Schaden +30 %", 30, ORANGE, () -> player.dmg = Math.round(player.dmg * 1.3f)));
            pool.add(new Shop("NITRO", "Tempo +20 %", 20, 0xFF00E5FF, () -> player.speed *= 1.2f));
            pool.add(new Shop("SCHNELLFEUER", "Angriffstempo +25 %", 28, LILAC, () -> player.atkCd = Math.max(0.12f, player.atkCd * 0.75f)));
            pool.add(new Shop("ABKLINGEN", "Fähigkeiten −30 %", 40, INK2, () -> {
                for (int i = 0; i < 4; i++) player.cd[i] = Math.max(1, player.cd[i] * 0.7f);
            }));
            pool.add(new Shop("SCHILD", "8 s unverwundbar", 35, INK, () -> player.inv += 8));
            pool.add(new Shop("DOPPELSCHUSS", "+1 Geschoss pro Angriff", 45, RUST, () -> player.multi++));
            pool.add(new Shop("VAMPIR", "5 % Lebensraub", 50, 0xFFFF5AA5, () -> player.lifesteal += 0.05f));
            Collections.shuffle(pool, rnd);
            shopItems = new ArrayList<>(pool.subList(0, 3));
            shopTimer = SHOP_TIME;
            java.util.Arrays.fill(shopFlash, 0);
            java.util.Arrays.fill(shopShake, 0);
            java.util.Arrays.fill(shopBought, false);
            shopPressed = -1;
            shownCoins = coins;
            state = "shop";
        }

        void closeShop() {
            state = "playing";
            waveTimer = 2;
        }

        /* --------------------------------- entities --------------------------------- */

        final class Player {
            float x = CX, y = CY, r = 14 * S, hp = 100, maxHp = 100, xp, xpNext = 100, speed = 165, dmg = 12, inv, angle,
                glowT, hitFlash, atkTimer, atkCd = 0.48f, dashVx, dashVy, dashTime, vortex, lifesteal;
            int level = 1, multi = 1;
            boolean dead, dashing;
            final float[] cd = {3, 6, 10, 14}, timer = new float[4];

            void gainXP(float amount) {
                xp += amount;
                while (xp >= xpNext) {
                    xp -= xpNext;
                    level++;
                    xpNext = (float) Math.floor(xpNext * 1.45f);
                    maxHp += 25;
                    hp = maxHp;
                    dmg += 4;
                    atkCd = Math.max(0.18f, atkCd - 0.02f);
                    burst(x, y, 35, 130, 6, INK);
                    ring(x, y, 20, 110, 4, GOLD);
                    shake(10);
                    text(x, y - 30 * S, "LEVEL " + level + "!", GOLD, 2, 24);
                }
            }

            void move(float dx, float dy, float dt) {
                if (dashing) return;
                float len = (float) Math.hypot(dx, dy);
                if (len > 0.01f) {
                    angle = (float) Math.atan2(dy, dx);
                    x += dx / len * speed * S * dt;
                    y += dy / len * speed * S * dt;
                    keepIn();
                }
            }

            void keepIn() {
                float dd = dist(x, y, CX, CY);
                if (dd > ARENA_R - r) {
                    double a = Math.atan2(y - CY, x - CX);
                    x = CX + (float) Math.cos(a) * (ARENA_R - r);
                    y = CY + (float) Math.sin(a) * (ARENA_R - r);
                }
            }

            void update(float dt) {
                glowT += dt * 2.5f;
                if (hitFlash > 0) hitFlash -= dt * 6;
                if (inv > 0) inv -= dt;
                if (dashing) {
                    x += dashVx * dt;
                    y += dashVy * dt;
                    keepIn();
                    dashTime -= dt;
                    burst(x, y, 3, 70, 4, 0xFF00E5FF);
                    if (dashTime <= 0) {
                        dashing = false;
                        for (Enemy e : new ArrayList<>(enemies)) if (dist(x, y, e.x, e.y) < 55 * S) e.hurt(dmg * 2.5f, 0);
                        burst(x, y, 25, 160, 6, 0xFF00E5FF);
                        shake(6);
                    }
                }
                for (int i = 0; i < 4; i++) if (timer[i] > 0) timer[i] -= dt;
                if (vortex > 0) {
                    vortex -= dt;
                    for (Enemy e : new ArrayList<>(enemies)) {
                        double a = Math.atan2(y - e.y, x - e.x);
                        float dd = dist(x, y, e.x, e.y) / S, pull = Math.max(100, Math.min(600, 600 - dd));
                        e.vx += Math.cos(a) * pull * dt * 4;
                        e.vy += Math.sin(a) * pull * dt * 4;
                        e.hurt(dmg * 2 * dt, 0);
                    }
                    for (int i = 0; i < 3; i++) {
                        double a = rnd.nextFloat() * Math.PI * 2;
                        float rr = rand(20, 120) * S;
                        particles.add(new Particle(x + (float) Math.cos(a) * rr, y + (float) Math.sin(a) * rr,
                            (float) Math.cos(a + Math.PI) * 80, (float) Math.sin(a + Math.PI) * 80, 0.4f, rand(3, 7), 0xCCFF5AE0));
                    }
                }
                atkTimer -= dt;
                if (atkTimer <= 0 && !enemies.isEmpty()) {
                    Enemy near = null;
                    float b = Float.MAX_VALUE;
                    for (Enemy e : enemies) {
                        float dd = dist(x, y, e.x, e.y);
                        if (dd < b) {
                            b = dd;
                            near = e;
                        }
                    }
                    if (near != null && b < 260 * S) {
                        for (int s = 0; s < multi; s++) {
                            double a = Math.atan2(near.y - y, near.x - x) + (s - (multi - 1) / 2f) * 0.18f;
                            shots.add(new Shot(x + (float) Math.cos(a) * 18 * S, y + (float) Math.sin(a) * 18 * S,
                                (float) Math.cos(a) * 400, (float) Math.sin(a) * 400, (int) (dmg / multi * 1.4f), 5, RUST, true));
                        }
                        atkTimer = atkCd;
                    }
                }
            }

            void ability(int i) {
                if (timer[i] > 0 || dead) return;
                timer[i] = cd[i];
                if (i == 0) {
                    dashing = true;
                    dashVx = (float) Math.cos(angle) * 420 * S;
                    dashVy = (float) Math.sin(angle) * 420 * S;
                    dashTime = 0.22f;
                    inv = Math.max(inv, 0.28f);
                    shake(4);
                } else if (i == 1) {
                    ring(x, y, 28, 200, 7, LILAC);
                    burst(x, y, 35, 220, 9, LILAC);
                    shake(12);
                    for (Enemy e : new ArrayList<>(enemies)) {
                        if (dist(x, y, e.x, e.y) < 130 * S) {
                            e.hurt(dmg * 3.5f, 0);
                            double a = Math.atan2(e.y - y, e.x - x);
                            e.vx += Math.cos(a) * 350;
                            e.vy += Math.sin(a) * 350;
                        }
                    }
                } else if (i == 2) {
                    storm(0, 5 + level);
                } else {
                    vortex = 1.8f;
                    shake(14);
                    ring(x, y, 32, 250, 8, 0xFFFF5AE0);
                    text(x, y - 40 * S, "SINGULARITÄT", 0xFFFF5AE0, 1.2f, 18);
                }
            }

            void storm(int hits, int max) {
                if (hits >= max || enemies.isEmpty()) return;
                Enemy e = enemies.get(rnd.nextInt(enemies.size()));
                bolts.add(new Bolt(x, y, e.x, e.y, LILAC));
                e.hurt(dmg * 1.8f, 0);
                burst(e.x, e.y, 14, 110, 5, LILAC);
                shake(5);
                after(0.11f, () -> storm(hits + 1, max));
            }

            void hurt(float amount) {
                if (inv > 0 || dead) return;
                hp -= amount;
                hitFlash = 1;
                shake(7);
                burst(x, y, 10, 90, 5, RUST);
                if (hp <= 0) {
                    hp = 0;
                    dead = true;
                    burst(x, y, 60, 220, 9, RUST);
                    burst(x, y, 25, 110, 14, ORANGE);
                    shake(25);
                }
            }

            void draw(Canvas c) {
                float glow = (float) Math.sin(glowT);
                int body = hitFlash > 0.5f ? RUST : INK;
                if (inv > 0) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * d);
                    p.setColor(0x66EDEDE8);
                    c.drawCircle(x, y, r + (6 + 2 * glow) * S, p);
                }
                if (level >= 6) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.5f * d);
                    p.setColor(0x80D9472F);
                    path.reset();
                    for (int i = 0; i < 6; i++) {
                        double a = i / 6.0 * Math.PI * 2 + glowT * 0.5;
                        float px = x + (float) Math.cos(a) * (r + 8 * S), py = y + (float) Math.sin(a) * (r + 8 * S);
                        if (i == 0) path.moveTo(px, py);
                        else path.lineTo(px, py);
                    }
                    path.close();
                    c.drawPath(path, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(body);
                c.drawCircle(x, y, r, p);
                p.setColor(RUST);
                c.drawCircle(x, y, r * 0.45f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f * d);
                p.setColor(INK);
                c.drawLine(x, y, x + (float) Math.cos(angle) * (r + 7 * S), y + (float) Math.sin(angle) * (r + 7 * S), p);
            }
        }

        final class Enemy {
            final String type;
            float x, y, vx, vy, r, hp, maxHp, speed, dmg, atkRange, atkCd, atkTimer, hitFlash, angle, shieldAngle,
                healTimer = 1, orbit, specialCd, warnTime;
            int col, xp;
            boolean dead, phase2, frenzied, meteorHit;
            String pattern;

            Enemy(float x, float y, String type) {
                this.x = x;
                this.y = y;
                this.type = type;
                int w = wave;
                switch (type) {
                    case "grunt":
                        r = 10; hp = maxHp = 35 + w * 9; speed = 75 + w * 4; dmg = 6 + w; col = RUST; xp = 20;
                        atkRange = 18; atkCd = 1; atkTimer = rand(0, 1); break;
                    case "archer":
                        r = 9; hp = maxHp = 28 + w * 7; speed = 65; dmg = 10 + w * 2; col = ORANGE; xp = 30;
                        atkRange = 230; atkCd = 2; atkTimer = rand(0, 2); break;
                    case "brute":
                        r = 20; hp = maxHp = 110 + w * 22; speed = 45; dmg = 18 + w * 3; col = 0xFFA8323A; xp = 65;
                        atkRange = 32; atkCd = 1.6f; break;
                    case "boss":
                        r = 30; hp = maxHp = 380 + w * 85; speed = 52; dmg = 22 + w * 4; col = LILAC; xp = 220;
                        atkRange = 38; atkCd = 1.1f; specialCd = rand(6, 9); break;
                    case "shielded":
                        r = 12; hp = maxHp = 60 + w * 12; speed = 60; dmg = 8 + w; col = BLUE; xp = 40;
                        atkRange = 18; atkCd = 1; atkTimer = rand(0, 1); break;
                    default:      // healer
                        r = 8; hp = maxHp = 30 + w * 8; speed = 80; dmg = 0; col = GREEN; xp = 55;
                        atkRange = 0; atkCd = 9999; break;
                }
                r *= S;
                atkRange *= S;
            }

            void update(float dt) {
                if (hitFlash > 0) hitFlash -= dt * 4;
                vx *= 0.82f;
                vy *= 0.82f;
                double a = Math.atan2(player.y - y, player.x - x);
                float dd = dist(x, y, player.x, player.y);
                if (dd > atkRange) {
                    x += ((float) Math.cos(a) * speed * S + vx * S) * dt;
                    y += ((float) Math.sin(a) * speed * S + vy * S) * dt;
                } else {
                    x += vx * S * dt;
                    y += vy * S * dt;
                }
                angle = (float) a;
                if (type.equals("archer") && dd < 160 * S) {
                    x -= Math.cos(a) * speed * S * 0.6f * dt;
                    y -= Math.sin(a) * speed * S * 0.6f * dt;
                }
                if (type.equals("shielded")) shieldAngle += 1.5f * dt;
                if (type.equals("healer") && (healTimer -= dt) <= 0) {
                    healTimer = 1;
                    Enemy t = null;
                    float low = Float.MAX_VALUE;
                    for (Enemy e : enemies) {
                        if (e == this || e.dead || e.type.equals("healer")) continue;
                        if (dist(x, y, e.x, e.y) < 120 * S && e.hp < low) {
                            low = e.hp;
                            t = e;
                        }
                    }
                    if (t != null) {
                        t.hp = Math.min(t.maxHp, t.hp + 15);
                        text(t.x, t.y - 18 * S, "+15", GREEN, 0.7f, 12);
                    }
                }
                atkTimer -= dt;
                if (atkTimer <= 0 && dd <= atkRange + 12 * S) {
                    attack();
                    atkTimer = atkCd;
                }
                if (type.equals("boss")) {
                    orbit += dt * 1.5f;
                    if (!phase2 && hp < maxHp * 0.5f) {
                        phase2 = true;
                        speed *= 1.6f;
                        atkCd *= 0.55f;
                        burst(x, y, 45, 220, 11, LILAC);
                        shake(18);
                        text(x, y - 50 * S, "☠ RASEND!", LILAC, 2.5f, 24);
                    }
                    specialCd -= dt;
                    if (warnTime <= 0 && specialCd <= 0) {
                        warnTime = 0.85f;
                        pattern = new String[]{"spiral", "cross", "barrage"}[rnd.nextInt(3)];
                        text(x, y - 52 * S, "⚠ " + pattern.toUpperCase(Locale.ROOT), RUST, 0.8f, 15);
                    }
                    if (warnTime > 0 && (warnTime -= dt) <= 0) {
                        special();
                        specialCd = rand(7, 11);
                    }
                }
            }

            void attack() {
                if (type.equals("archer") || type.equals("boss")) {
                    int n = type.equals("boss") ? (phase2 ? 5 : 3) : 1;
                    float spread = type.equals("boss") ? 0.28f : 0;
                    for (int i = 0; i < n; i++) {
                        double a = Math.atan2(player.y - y, player.x - x) + (i - (n - 1) / 2f) * spread;
                        shots.add(new Shot(x + (float) Math.cos(a) * 18 * S, y + (float) Math.sin(a) * 18 * S,
                            (float) Math.cos(a) * 210, (float) Math.sin(a) * 210, dmg * (n > 1 ? 0.65f : 1),
                            type.equals("boss") ? 9 : 6, type.equals("boss") ? LILAC : ORANGE, false));
                    }
                } else {
                    player.hurt(dmg);
                    burst(x, y, 6, 65, 4, RUST);
                }
            }

            void special() {
                shake(14);
                burst(x, y, 18, 110, 7, RUST);
                if (pattern.equals("spiral")) {
                    for (int i = 0; i < 18; i++) {
                        int k = i;
                        after(i * 0.035f, () -> {
                            double a = k / 18.0 * Math.PI * 2 + orbit;
                            shots.add(new Shot(x, y, (float) Math.cos(a) * 195, (float) Math.sin(a) * 195, dmg * 0.55f, 8, RUST, false));
                        });
                    }
                } else if (pattern.equals("cross")) {
                    for (int dd = 0; dd < 4; dd++) {
                        for (int s = 0; s < 3; s++) {
                            double a = dd / 4.0 * Math.PI * 2 + (s - 1) * 0.2;
                            after(dd * 0.07f + s * 0.02f, () -> shots.add(new Shot(x, y, (float) Math.cos(a) * 215,
                                (float) Math.sin(a) * 215, dmg * 0.6f, 7, ORANGE, false)));
                        }
                    }
                } else {
                    for (int i = 0; i < 9; i++) {
                        after(i * 0.055f, () -> {
                            double a = Math.atan2(player.y - y, player.x - x) + rand(-0.35f, 0.35f);
                            shots.add(new Shot(x, y, (float) Math.cos(a) * 290, (float) Math.sin(a) * 290, dmg * 0.65f, 7, LILAC, false));
                        });
                    }
                }
            }

            void hurt(float amount, int chainDepth) {
                if (dead) return;
                hp -= amount;
                hitFlash = 1;
                burst(x, y, 4, 55, 3, INK);
                if (amount >= 1) text(x + rand(-8, 8) * S, y - 18 * S, "-" + Math.round(amount), 0xFFFF9999, 0.65f, 12);
                if (hp > 0) return;
                dead = true;
                player.gainXP(xp);
                burst(x, y, 22, 160, 7, col);
                burst(x, y, 12, 90, 11, INK);
                shake(type.equals("boss") ? 22 : 6);
                if (chainDepth < 3) chain(x, y, amount * 0.7f, chainDepth);
                streak++;
                streakTimer = 3.5f;
                streakMult = streak < 3 ? 1 : streak < 6 ? 1.5f : streak < 10 ? 2 : streak < 16 ? 3 : 4;
                int base = type.equals("boss") ? 500 : type.equals("brute") ? 120 : type.equals("shielded") ? 80
                    : type.equals("healer") ? 90 : 50;
                score += Math.round(base * streakMult);
                if (streak >= 3) text(x, y - 36 * S, "×" + fmt(streakMult) + " STREAK!", GOLD, 0.9f, 13);
                onKill();
                float hChance = type.equals("boss") ? 0.9f : type.equals("brute") ? 0.45f : 0.22f;
                if (rnd.nextFloat() < hChance) orbs.add(new Orb(x, y, "health", 0));
                int gc = type.equals("boss") ? 3 : type.equals("brute") ? 2 : rnd.nextFloat() < 0.7f ? 1 : 0;
                for (int i = 0; i < gc; i++) {
                    orbs.add(new Orb(x + rand(-8, 8) * S, y + rand(-8, 8) * S, "gold", type.equals("boss") ? 6 + rnd.nextInt(5) : 2 + rnd.nextInt(3)));
                }
                orbs.add(new Orb(x, y, "xp", (int) (xp * 0.4f)));
                if (type.equals("boss")) text(CX, CY - 70 * S, "BOSS BESIEGT!", GOLD, 3.5f, 30);
            }

            void draw(Canvas c) {
                int fill = hitFlash > 0.5f ? INK : col;
                p.setStyle(Paint.Style.FILL);
                p.setColor(fill);
                if (type.equals("boss")) {
                    path.reset();
                    for (int i = 0; i < 6; i++) {
                        double a = i / 6.0 * Math.PI * 2 + orbit;
                        float px = x + (float) Math.cos(a) * r, py = y + (float) Math.sin(a) * r;
                        if (i == 0) path.moveTo(px, py);
                        else path.lineTo(px, py);
                    }
                    path.close();
                    c.drawPath(path, p);
                    for (int i = 0; i < 3; i++) {
                        double a = orbit * 2 + i / 3.0 * Math.PI * 2;
                        c.drawCircle(x + (float) Math.cos(a) * (r + 14 * S), y + (float) Math.sin(a) * (r + 14 * S), 5 * S, p);
                    }
                    if (warnTime > 0) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(2 * d);
                        p.setColor(RUST);
                        c.drawCircle(x, y, r + (8 + 10 * (1 - warnTime)) * S, p);
                    }
                } else if (type.equals("brute")) {
                    c.save();
                    c.rotate((float) Math.toDegrees(angle) + 45, x, y);
                    c.drawRect(x - r, y - r, x + r, y + r, p);
                    c.restore();
                } else {
                    c.drawCircle(x, y, r, p);
                    if (type.equals("shielded")) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(4 * S);
                        p.setColor(0xFFBFD8FF);
                        rect.set(x - r - 5 * S, y - r - 5 * S, x + r + 5 * S, y + r + 5 * S);
                        c.drawArc(rect, (float) Math.toDegrees(shieldAngle) - 60, 120, false, p);
                    } else if (type.equals("healer")) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(2 * S);
                        p.setColor(INK);
                        c.drawLine(x - 5 * S, y, x + 5 * S, y, p);
                        c.drawLine(x, y - 5 * S, x, y + 5 * S, p);
                    }
                }
                float bw = r * 2.5f, bh = 4 * S, bx = x - bw / 2, by = y - r - 10 * S;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x8C000000);
                c.drawRect(bx, by, bx + bw, by + bh, p);
                float ratio = Math.max(0, Math.min(1, hp / maxHp));
                p.setColor(ratio > 0.5f ? GREEN : ratio > 0.25f ? ORANGE : RUST);
                c.drawRect(bx, by, bx + bw * ratio, by + bh, p);
            }
        }

        final class Shot {
            float x, y, vx, vy, dmg, r, life = 2.5f;
            final int col;
            final boolean mine;
            boolean dead;

            Shot(float x, float y, float vx, float vy, float dmg, float r, int col, boolean mine) {
                this.x = x;
                this.y = y;
                this.vx = vx;
                this.vy = vy;
                this.dmg = dmg;
                this.r = r * S;
                this.col = col;
                this.mine = mine;
            }

            void update(float dt) {
                x += vx * S * dt;
                y += vy * S * dt;
                if ((life -= dt) <= 0) dead = true;
            }
        }

        final class Orb {
            final String kind;    // health, xp, gold, power
            float x, y, vx, vy, life, pulse, angle;
            int val, power;
            boolean dead;

            Orb(float x, float y, String kind, int val) {
                this.x = x;
                this.y = y;
                this.kind = kind;
                this.val = val;
                life = kind.equals("health") ? 10 : 12;
                if (kind.equals("xp") || kind.equals("gold")) {
                    vx = rand(-60, 60);
                    vy = rand(-90, -20);
                }
            }

            void update(float dt) {
                life -= dt;
                pulse += dt * 4;
                angle += dt * 3;
                if (life <= 0) {
                    dead = true;
                    return;
                }
                if (kind.equals("xp") || kind.equals("gold")) {
                    vy += 120 * dt;
                    vx *= 0.95f;
                    vy *= 0.95f;
                    x += vx * S * dt;
                    y += vy * S * dt;
                    float dd = dist(x, y, player.x, player.y);
                    if (dd < 110 * S) {
                        double a = Math.atan2(player.y - y, player.x - x);
                        x += Math.cos(a) * 220 * S * dt;
                        y += Math.sin(a) * 220 * S * dt;
                    }
                } else if (kind.equals("health")) {
                    y -= 8 * S * dt;
                }
                float reach = player.r + (kind.equals("power") ? 17 : 13) * S;
                if (dist(x, y, player.x, player.y) < reach) {
                    dead = true;
                    if (kind.equals("health")) {
                        int heal = 18 + rnd.nextInt(15);
                        player.hp = Math.min(player.maxHp, player.hp + heal);
                        text(player.x, player.y - 44 * S, "+" + heal + " HP", GREEN, 1.1f, 15);
                        burst(x, y, 10, 80, 4, GREEN);
                    } else if (kind.equals("gold")) {
                        coins += val;
                        text(x, y - 18 * S, "+" + val + "c", GOLD, 0.8f, 13);
                        burst(x, y, 6, 55, 3, GOLD);
                    } else if (kind.equals("xp")) {
                        player.gainXP(val);
                        burst(x, y, 5, 50, 3, BLUE);
                    } else {
                        applyPower(power, x, y);
                    }
                }
            }

            void draw(Canvas c) {
                float a = Math.min(1, life * 3) * (kind.equals("power") ? 1 : Math.min(1, life / 2));
                float pp = 0.5f + 0.5f * (float) Math.sin(pulse);
                p.setStyle(Paint.Style.FILL);
                if (kind.equals("health")) {
                    p.setColor(GREEN);
                    p.setAlpha((int) (255 * a));
                    c.drawCircle(x, y, 6 * S, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * S);
                    c.drawCircle(x, y, (9 + pp * 3) * S, p);
                } else {
                    float rr = (kind.equals("power") ? 13 : kind.equals("gold") ? 7 : 5) * S;
                    p.setColor(kind.equals("gold") ? GOLD : kind.equals("xp") ? BLUE : GOLD);
                    p.setAlpha((int) (255 * a));
                    c.save();
                    c.rotate((float) Math.toDegrees(angle), x, y);
                    path.reset();
                    path.moveTo(x, y - rr);
                    path.lineTo(x + rr * 0.6f, y);
                    path.lineTo(x, y + rr);
                    path.lineTo(x - rr * 0.6f, y);
                    path.close();
                    c.drawPath(path, p);
                    c.restore();
                    if (kind.equals("power")) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(1.5f * d);
                        c.drawCircle(x, y, rr + (7 + pp * 5) * S, p);
                    }
                }
                p.setAlpha(255);
            }
        }

        final class Particle {
            float x, y, vx, vy, life, max, r;
            final int col;

            Particle(float x, float y, float vx, float vy, float life, float r, int col) {
                this.x = x;
                this.y = y;
                this.vx = vx;
                this.vy = vy;
                this.life = this.max = life;
                this.r = r;
                this.col = col;
            }
        }

        final class FloatText {
            float x, y, life, max, size;
            final String s;
            final int col;

            FloatText(float x, float y, String s, int col, float life, float size) {
                this.x = x;
                this.y = y;
                this.s = s;
                this.col = col;
                this.life = this.max = life;
                this.size = size;
            }
        }

        final class Bolt {
            final float[] pts = new float[18];
            final int col;
            float life = 0.18f;

            Bolt(float x1, float y1, float x2, float y2, int col) {
                this.col = col;
                for (int i = 0; i <= 8; i++) {
                    float t = i / 8f;
                    boolean end = i == 0 || i == 8;
                    pts[2 * i] = x1 + (x2 - x1) * t + (end ? 0 : rand(-18, 18) * S);
                    pts[2 * i + 1] = y1 + (y2 - y1) * t + (end ? 0 : rand(-18, 18) * S);
                }
            }
        }

        void burst(float x, float y, int n, float spd, float r, int col) {
            for (int i = 0; i < n && particles.size() < 220; i++) {
                double a = rnd.nextFloat() * Math.PI * 2;
                float s = rand(spd * 0.3f, spd);
                particles.add(new Particle(x, y, (float) Math.cos(a) * s, (float) Math.sin(a) * s, rand(0.3f, 0.9f), rand(r * 0.5f, r), col));
            }
        }

        void ring(float x, float y, int n, float spd, float r, int col) {
            for (int i = 0; i < n && particles.size() < 220; i++) {
                double a = i / (double) n * Math.PI * 2;
                particles.add(new Particle(x, y, (float) Math.cos(a) * spd, (float) Math.sin(a) * spd, 0.5f, r, col));
            }
        }

        void text(float x, float y, String s, int col, float life, float size) {
            texts.add(new FloatText(x, y, s, col, life, size));
        }

        String fmt(float v) {
            return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
        }

        /* ---------------------------------- drawing ---------------------------------- */

        void grid(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x09FFFFFF);
            float cell = 32 * d;
            for (float x = 0; x < W; x += cell) c.drawRect(x, 0, x + 1, H, p);
            for (float y = 0; y < H; y += cell) c.drawRect(0, y, W, y + 1, p);
        }

        void drawWorld(Canvas c) {
            // Arena: a darker disc, a fine cross, the rust rim.
            p.setStyle(Paint.Style.FILL);
            p.setColor(ARENA);
            c.drawCircle(CX, CY, ARENA_R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(LINE);
            c.drawCircle(CX, CY, ARENA_R * 0.15f, p);
            c.drawLine(CX, CY - ARENA_R, CX, CY + ARENA_R, p);
            c.drawLine(CX - ARENA_R, CY, CX + ARENA_R, CY, p);
            p.setStrokeWidth(2.5f * d);
            p.setColor(RUST);
            c.drawCircle(CX, CY, ARENA_R, p);
            // Event visuals.
            if ("pulse".equals(event)) {
                p.setStrokeWidth(3 * d);
                p.setColor(LILAC);
                if (pulseRings == null) {
                    p.setAlpha((int) (120 + 100 * Math.sin(gameT * 20)));
                    c.drawCircle(CX, CY, ARENA_R - 4 * d, p);
                } else for (float r : pulseRings) if (r > 0 && r < ARENA_R) c.drawCircle(CX, CY, r, p);
                p.setAlpha(255);
            }
            for (float[] m : meteors) {
                float r = 18 * S;
                if (m[2] > 0) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * d);
                    p.setColor(ORANGE);
                    c.drawLine(m[0], CY - ARENA_R, m[0], CY + ARENA_R, p);
                } else {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(ORANGE);
                    c.drawCircle(m[0], m[1], r, p);
                    p.setColor(0xFF6B4226);
                    c.drawCircle(m[0] - 5 * S, m[1] - 5 * S, 6 * S, p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            for (Particle q : particles) {
                p.setColor(q.col);
                p.setAlpha((int) (Color.alpha(q.col) * Math.max(0, q.life / q.max)));
                c.drawCircle(q.x, q.y, Math.max(0, q.r * S * q.life / q.max), p);
            }
            p.setAlpha(255);
            for (Shot s : shots) {
                p.setColor(s.col);
                c.drawCircle(s.x, s.y, s.r, p);
            }
            p.setStyle(Paint.Style.STROKE);
            for (Bolt b : bolts) {
                p.setColor(b.col);
                p.setStrokeWidth((1 + b.life / 0.18f * 3) * d);
                for (int i = 0; i < 8; i++) c.drawLine(b.pts[2 * i], b.pts[2 * i + 1], b.pts[2 * i + 2], b.pts[2 * i + 3], p);
            }
            for (Orb o : orbs) o.draw(c);
            for (Enemy e : enemies) e.draw(c);
            if (player != null) player.draw(c);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            for (FloatText t : texts) {
                p.setColor(t.col);
                p.setAlpha((int) (255 * Math.min(1, t.life / t.max * 4) * (t.life / t.max)));
                p.setTextSize(t.size * S);
                c.drawText(t.s, t.x, t.y, p);
            }
            p.setAlpha(255);
            if (player != null) drawHud(c);
        }

        float insL, insT, insR, insB;

        void readInsets() {
            WindowInsets wi = getRootWindowInsets();
            insL = insT = insR = insB = 0;
            if (wi == null || Build.VERSION.SDK_INT < 30) return;
            android.graphics.Insets in = wi.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
            insL = in.left;
            insT = in.top;
            insR = in.right;
            insB = in.bottom;
        }

        void drawHud(Canvas c) {
            readInsets();
            float pad = 16 * d + Math.max(insL, insR), top = 18 * d + insT;
            p.setTextAlign(Paint.Align.LEFT);
            p.setStyle(Paint.Style.FILL);
            // Label like the home screen: rust dash, mono caps.
            p.setColor(RUST);
            c.drawRect(pad, top + 4 * d, pad + 24 * d, top + 6 * d, p);
            p.setColor(INK3);
            p.setTextSize(11 * d);
            p.setLetterSpacing(0.24f);
            c.drawText("NEXUS BREACH", pad + 38 * d, top + 9 * d, p);
            p.setLetterSpacing(0.08f);
            // HP and XP bars.
            float bw = Math.min(220 * d, W * 0.42f), by = top + 22 * d;
            bar(c, pad, by, bw, 8 * d, player.hp / player.maxHp, GREEN);
            p.setColor(INK2);
            p.setTextSize(10 * d);
            c.drawText(String.format(Locale.ROOT, "HP %d / %d", (int) player.hp, (int) player.maxHp), pad, by + 22 * d, p);
            bar(c, pad, by + 30 * d, bw, 5 * d, player.xp / player.xpNext, GOLD);
            c.drawText(String.format(Locale.ROOT, "LV %d · XP %d / %d", player.level, (int) player.xp, (int) player.xpNext), pad, by + 50 * d, p);
            // Right: wave, score, coins, enemies.
            p.setTextAlign(Paint.Align.RIGHT);
            p.setColor(INK);
            p.setTextSize(13 * d);
            c.drawText("WELLE " + wave, W - pad, top + 10 * d, p);
            p.setTextSize(10 * d);
            p.setColor(INK2);
            c.drawText(score + " PUNKTE", W - pad, top + 28 * d, p);
            p.setColor(GOLD);
            c.drawText("⬡ " + coins, W - pad, top + 44 * d, p);
            p.setColor(RUST);
            c.drawText(enemies.size() + " GEGNER", W - pad, top + 60 * d, p);
            if (streak >= 3) {
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(GOLD);
                p.setTextSize(13 * d);
                c.drawText("🔥 " + streak + " STREAK ×" + fmt(streakMult), CX, top + 10 * d, p);
            }
            if (!waveActive && waveTimer > 0) {
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(INK3);
                p.setTextSize(11 * d);
                c.drawText("Nächste Welle in " + (int) Math.ceil(waveTimer) + " …", CX, CY + ARENA_R + 22 * d, p);
            }
            // Ability buttons, bottom right, ARC pills.
            String[] names = {"DASH", "NOVA", "STORM", "PULL"};
            float[][] b = buttons();
            for (int i = 0; i < 4; i++) {
                float bx = b[i][0], byy = b[i][1], br = b[i][2];
                boolean ready = player.timer[i] <= 0;
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF1C1C1A);
                c.drawCircle(bx, byy, br, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(ready ? RUST : LINE);
                c.drawCircle(bx, byy, br, p);
                if (!ready) {
                    p.setColor(RUST);
                    rect.set(bx - br, byy - br, bx + br, byy + br);
                    c.drawArc(rect, -90, 360 * (1 - player.timer[i] / player.cd[i]), false, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.CENTER);
                p.setColor(ready ? INK : INK3);
                p.setTextSize(10 * d);
                c.drawText(names[i], bx, byy + 4 * d, p);
            }
            // Joystick ring while held.
            if (joyId >= 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * d);
                p.setColor(LINE);
                c.drawCircle(joySX, joySY, 40 * S, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x66D9472F);
                float jx = Math.max(-40 * S, Math.min(40 * S, joyDX)), jy = Math.max(-40 * S, Math.min(40 * S, joyDY));
                c.drawCircle(joySX + jx, joySY + jy, 16 * S, p);
            }
            // Pause.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(LINE);
            rect.set(W - pad - 34 * d, top + 72 * d, W - pad, top + 106 * d);
            c.drawRoundRect(rect, 6 * d, 6 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(INK2);
            c.drawRect(W - pad - 22 * d, top + 81 * d, W - pad - 19 * d, top + 97 * d, p);
            c.drawRect(W - pad - 15 * d, top + 81 * d, W - pad - 12 * d, top + 97 * d, p);
        }

        float[][] buttons() {
            float R = 30 * d, pad = 18 * d + Math.max(insL, insR), gap = 66 * d, by = H - 18 * d - insB - R, bx = W - pad - R - gap * 3;
            return new float[][]{{bx, by, R}, {bx + gap, by - 34 * d, R}, {bx + gap * 2, by - 34 * d, R}, {bx + gap * 3, by, R}};
        }

        void bar(Canvas c, float x, float y, float w, float h, float frac, int col) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(LINE);
            rect.set(x, y, x + w, y + h);
            c.drawRoundRect(rect, h / 2, h / 2, p);
            p.setColor(col);
            rect.set(x, y, x + w * Math.max(0, Math.min(1, frac)), y + h);
            c.drawRoundRect(rect, h / 2, h / 2, p);
        }

        void overlay(Canvas c, int col) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawRect(-50, -50, W + 50, H + 50, p);
        }

        void big(Canvas c, String s, float y, float size, int col) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            p.setTextSize(size * S);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setLetterSpacing(-0.04f);
            c.drawText(s, CX, y, p);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setLetterSpacing(0.12f);
        }

        void small(Canvas c, String s, float y, float size, int col) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            p.setTextSize(size * d);
            c.drawText(s, CX, y, p);
        }

        void drawTitle(Canvas c) {
            float t = SystemClock.uptimeMillis() / 1000f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(RUST);
            c.drawCircle(CX, H * 0.36f, Math.min(W, H) * (0.22f + 0.01f * (float) Math.sin(t * 2)), p);
            p.setColor(LINE);
            c.drawCircle(CX, H * 0.36f, Math.min(W, H) * 0.3f, p);
            small(c, "— ARCHITECT · DNA", H * 0.12f, 11, INK3);
            big(c, "NEXUS", H * 0.34f, 60, INK);
            big(c, "BREACH", H * 0.34f + 56 * S, 60, RUST);
            small(c, "BESTER: " + best + " PUNKTE", H * 0.62f, 12, GOLD);
            small(c, "LINKER DAUMEN = BEWEGEN · RECHTS = DASH · NOVA · STORM · PULL", H * 0.68f, 10, INK3);
            float pp = 0.5f + 0.5f * (float) Math.sin(t * 3);
            small(c, "TIPPEN = START", H * 0.78f, 14, (pp > 0.5f) ? INK : INK2);
            small(c, "✕", 30 * d, 16, INK2);
        }

        // Shop feedback per card: flash after a buy, shake when too expensive, bought marker; coins roll down.
        final float[] shopFlash = new float[3], shopShake = new float[3];
        final boolean[] shopBought = new boolean[3];
        int shopPressed = -1;
        float shownCoins;

        /** Largest text size up to max (dp) whose width fits. */
        void fit(String t, float maxDp, float width) {
            p.setTextSize(maxDp * d);
            float w = p.measureText(t);
            if (w > width) p.setTextSize(Math.max(8 * d, maxDp * d * width / w));
        }

        void drawShop(Canvas c, float dt) {
            readInsets();
            overlay(c, 0xD1121211);
            shownCoins += (coins - shownCoins) * Math.min(1, dt * 10);
            if (Math.abs(coins - shownCoins) < 0.5f) shownCoins = coins;
            small(c, "— SHOP", H * 0.12f, 12, INK3);
            small(c, "⬡ " + Math.round(shownCoins) + " MÜNZEN", H * 0.12f + 26 * d, 15, GOLD);
            float bw = Math.min(W * 0.8f, 360 * d);
            boolean hurry = shopTimer < 5 && ((int) (shopTimer * 4) & 1) == 0;
            bar(c, CX - bw / 2, H * 0.12f + 40 * d, bw, 4 * d, shopTimer / SHOP_TIME, hurry ? GOLD : RUST);
            for (int i = 0; i < shopItems.size(); i++) {
                Shop s = shopItems.get(i);
                boolean bought = shopBought[i], can = !bought && coins >= s.cost;
                shopFlash[i] = Math.max(0, shopFlash[i] - dt * 2.5f);
                shopShake[i] = Math.max(0, shopShake[i] - dt * 3);
                shopCard(i, rect);
                float pad = Math.min(16 * d, rect.width() * 0.08f);
                c.save();
                c.translate((float) Math.sin(shopShake[i] * 40) * 10 * d * shopShake[i], 0);
                float sc = (i == shopPressed && !bought ? 0.97f : 1) + shopFlash[i] * 0.04f;
                c.scale(sc, sc, rect.centerX(), rect.centerY());
                float cx0 = rect.left, cw = rect.width(), y = rect.top, ch = rect.height();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF1C1C1A);
                c.drawRoundRect(rect, 8 * d, 8 * d, p);
                if (shopFlash[i] > 0 || bought) {
                    p.setColor(s.col);
                    p.setAlpha((int) (bought ? 28 + 150 * shopFlash[i] : 180 * shopFlash[i]));
                    c.drawRoundRect(rect, 8 * d, 8 * d, p);
                    p.setAlpha(255);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth((i == shopPressed ? 2.5f : 1.5f) * d);
                p.setColor(shopShake[i] > 0 ? RUST : can || bought ? s.col : LINE);
                c.drawRoundRect(rect, 8 * d, 8 * d, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.LEFT);
                p.setColor(can || bought ? INK : INK3);
                fit(s.name, 15, cw - 2 * pad);
                c.drawText(s.name, cx0 + pad, y + ch * 0.3f, p);
                p.setColor(INK3);
                fit(s.desc, 11, cw - 2 * pad);
                c.drawText(s.desc, cx0 + pad, y + ch * 0.3f + 22 * d, p);
                String price = bought ? "✓ GEKAUFT" : "⬡ " + s.cost + (can ? "" : " · FEHLEN " + (s.cost - coins));
                p.setColor(bought ? s.col : can ? GOLD : shopShake[i] > 0 ? RUST : INK3);
                fit(price, 14, can ? cw * 0.55f : cw - 2 * pad);
                c.drawText(price, cx0 + pad, y + ch - 16 * d, p);
                if (can) {
                    p.setTextAlign(Paint.Align.RIGHT);
                    p.setColor(s.col);
                    fit("KAUFEN", 14, cw * 0.35f);
                    c.drawText("KAUFEN", cx0 + cw - pad, y + ch - 16 * d, p);
                }
                c.restore();
            }
            // Purchase sparks and texts on top of the cards.
            p.setStyle(Paint.Style.FILL);
            for (Particle q : particles) {
                p.setColor(q.col);
                p.setAlpha((int) (Color.alpha(q.col) * Math.max(0, q.life / q.max)));
                c.drawCircle(q.x, q.y, Math.max(0, q.r * S * q.life / q.max), p);
            }
            p.setTextAlign(Paint.Align.CENTER);
            for (FloatText t : texts) {
                p.setColor(t.col);
                p.setAlpha((int) (255 * Math.min(1, t.life / t.max * 4) * (t.life / t.max)));
                p.setTextSize(t.size * S);
                c.drawText(t.s, t.x, t.y, p);
            }
            p.setAlpha(255);
            weiterRect(rect);
            p.setStyle(Paint.Style.FILL);
            p.setColor(shopPressed == 3 ? RUST : 0xFF1C1C1A);
            c.drawRoundRect(rect, 8 * d, 8 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(RUST);
            c.drawRoundRect(rect, 8 * d, 8 * d, p);
            small(c, "WEITER →  " + (int) Math.ceil(shopTimer) + " S", rect.centerY() + 5 * d, 14, shopPressed == 3 ? INK : RUST);
        }

        void weiterRect(RectF out) {
            float wy = H - 28 * d - insB;
            out.set(CX - 100 * d, wy - 34 * d, CX + 100 * d, wy + 12 * d);
        }

        void drawDead(Canvas c) {
            overlay(c, 0xB3121211);
            small(c, "— GAME OVER", CY - 90 * S, 12, INK3);
            big(c, score + "", CY - 20 * S, 54, INK);
            small(c, "PUNKTE · WELLE " + wave + " · LEVEL " + player.level, CY + 14 * S, 12, INK2);
            small(c, score >= best && score > 0 ? "NEUER REKORD!" : "BESTER: " + best, CY + 40 * S, 13, score >= best && score > 0 ? RUST : GOLD);
            small(c, "TIPPEN = NOCHMAL", CY + 80 * S, 14, INK);
        }

        /* ----------------------------------- touch ----------------------------------- */

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked(), i = e.getActionIndex();
            float x = e.getX(i), y = e.getY(i);
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                switch (state) {
                    case "title":
                        if (y < 60 * d && Math.abs(x - CX) < 40 * d) finish();
                        else startGame();
                        return true;
                    case "dead":
                        startGame();
                        return true;
                    case "paused":
                        state = "countdown";
                        countdownVal = 3;
                        countdownTimer = 1;
                        return true;
                    case "shop":
                        shopTap(x, y);
                        return true;
                    case "playing":
                        if (x > W - 80 * d - Math.max(insL, insR) && y < 130 * d + insT) {
                            state = "paused";
                            return true;
                        }
                        float[][] b = buttons();
                        for (int k = 0; k < 4; k++) {
                            if (dist(x, y, b[k][0], b[k][1]) < b[k][2] + 12 * d) {
                                player.ability(k);
                                return true;
                            }
                        }
                        if (x < W / 2 && joyId < 0) {
                            joyId = e.getPointerId(i);
                            joySX = x;
                            joySY = y;
                            joyDX = joyDY = 0;
                        }
                        return true;
                    default:
                        return true;
                }
            }
            if (a == MotionEvent.ACTION_MOVE && joyId >= 0) {
                int idx = e.findPointerIndex(joyId);
                if (idx >= 0) {
                    joyDX = e.getX(idx) - joySX;
                    joyDY = e.getY(idx) - joySY;
                }
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_CANCEL) {
                if (shopPressed >= 0 && shopPressed < 3) shopPressed = -1;
                if (a == MotionEvent.ACTION_CANCEL || e.getPointerId(i) == joyId) {
                    joyId = -1;
                    joyDX = joyDY = 0;
                }
            }
            return true;
        }

        /** Card i of the shop: side by side on a wide screen, stacked on a tall one; WEITER stays visible below. */
        void shopCard(int i, RectF out) {
            float top = H * 0.12f + 60 * d, bottom = H - 70 * d - insB;
            if (W > H) {
                float cw = Math.min((W - 64 * d) / 3 - 12 * d, 260 * d), ch = Math.min(bottom - top, 140 * d);
                float x0 = CX - (cw * 3 + 24 * d) / 2 + i * (cw + 12 * d), y0 = top + (bottom - top - ch) / 2;
                out.set(x0, y0, x0 + cw, y0 + ch);
            } else {
                float bw = Math.min(W * 0.86f, 380 * d), ch = Math.min((bottom - top) / 3 - 12 * d, 96 * d);
                float y0 = top + i * (ch + 12 * d);
                out.set(CX - bw / 2, y0, CX + bw / 2, y0 + ch);
            }
        }

        int shopHit(float x, float y) {
            for (int i = 0; i < shopItems.size(); i++) {
                shopCard(i, rect);
                rect.inset(-6 * d, -6 * d);
                if (rect.contains(x, y)) return i;
            }
            weiterRect(rect);
            rect.inset(-12 * d, -12 * d);
            return rect.contains(x, y) ? 3 : -1;
        }

        /** Instant on touch-down: buy (flash, sparks, haptic tick) or shake when coins are missing. */
        void shopTap(float x, float y) {
            int i = shopHit(x, y);
            shopPressed = i;
            if (i == 3) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                postDelayed(() -> {
                    shopPressed = -1;
                    if (state.equals("shop")) closeShop();
                }, 90);
                return;
            }
            if (i < 0 || shopBought[i]) return;
            Shop s = shopItems.get(i);
            shopCard(i, rect);
            if (coins < s.cost) {
                shopShake[i] = 1;
                performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? android.view.HapticFeedbackConstants.REJECT : android.view.HapticFeedbackConstants.LONG_PRESS);
                return;
            }
            coins -= s.cost;
            s.apply.run();
            shopBought[i] = true;
            shopFlash[i] = 1;
            performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? android.view.HapticFeedbackConstants.CONFIRM : android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            burst(rect.centerX(), rect.centerY(), 30, 180, 6, s.col);
            burst(rect.centerX(), rect.centerY(), 12, 90, 4, GOLD);
            text(rect.centerX(), rect.top - 10 * d, s.name + " AKTIV!", s.col, 1.4f, 16);
        }
    }
}
