package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The newer buddies' own scenes (double-tap the buddy), one kind each:
 * PANDA – comic: four-panel strips, tap = next panel, the punchline last.
 * ICE   – fight: snowball fight on the ice; tap a penguin or seal before it throws, tap snowballs
 *         flying at you to smash them. Three hearts.
 * OWL   – exploration: a night forest, your finger is the owl's gaze; hold the light on hidden
 *         things to find them (with an owl fact each). Every night more to find, a little darker.
 * DRAGON – fight: fly up and down with your finger, hold to breathe fire at the knights, arrows and
 *         eagles. Fire runs out and refills. Three hearts.
 */
final class BuddySceneView extends View {

    static final int PANDA = 0, ICE = 1, OWL = 2, DRAGON = 3;

    private final int kind;
    private final Drawable art;
    private final SharedPreferences prefs;
    private final BuddyWorldView.Exit exit;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF(), closeR = new RectF(), againR = new RectF(), backR = new RectF();
    private final Random rnd = new Random();
    private final List<float[]> things = new ArrayList<>();
    private final List<float[]> shots = new ArrayList<>();
    private final List<float[]> fx = new ArrayList<>();          // x, y, born, kind, value
    private final List<String> fxText = new ArrayList<>();
    private long born = SystemClock.uptimeMillis();
    private float lastT, spawnIn = 1f, hurtAt = -9;
    private int score, hearts = 3, level = 1;
    private boolean over;
    private float fingerX = -1, fingerY = -1;
    private boolean down;

    BuddySceneView(Context c, int kind, Drawable art, SharedPreferences prefs, BuddyWorldView.Exit exit) {
        super(c);
        this.kind = kind;
        this.art = art.mutate();
        this.prefs = prefs;
        this.exit = exit;
        if (kind == PANDA) strip = Math.floorMod(prefs.getInt("pandaStrip", 0), STRIPS.length);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float t() {
        return (SystemClock.uptimeMillis() - born) / 1000f;
    }

    private String bestKey() {
        return new String[]{"scenePandaBest", "sceneIceBest", "sceneOwlBest", "sceneDragonBest"}[kind];
    }

    private void restart() {
        born = SystemClock.uptimeMillis();
        lastT = 0;
        things.clear();
        shots.clear();
        fx.clear();
        fxText.clear();
        score = 0;
        hearts = 3;
        level = 1;
        over = false;
        spawnIn = 1f;
        fuel = 1;
        dragonY = -1;
        if (kind == OWL) hide(getWidth(), getHeight());
    }

    private void end() {
        over = true;
        prefs.edit().putInt("buddyXp", prefs.getInt("buddyXp", 0) + Math.max(2, Math.min(30, score / 3))).apply();     // play together = friendship
        if (score > prefs.getInt(bestKey(), 0)) prefs.edit().putInt(bestKey(), score).apply();
    }

    private void pop(float x, float y, String text, int colour) {
        fx.add(new float[]{x, y, t(), colour, 0});
        fxText.add(text);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), t = t(), dt = Math.min(0.05f, Math.max(0, t - lastT));
        lastT = t;
        if (kind == PANDA) comic(c, w, h, t);
        else if (kind == ICE) ice(c, w, h, t, dt);
        else if (kind == OWL) owl(c, w, h, t, dt);
        else dragon(c, w, h, t, dt);
        drawFx(c, t);
        hud(c, w, h, t);
        if (over) drawOver(c, w, h);
        postInvalidateOnAnimation();
    }

    private void drawArt(Canvas c, float x, float yBottom, float width, boolean flip, float rot, int alpha) {
        float ah = width * art.getIntrinsicHeight() / (float) Math.max(1, art.getIntrinsicWidth());
        c.save();
        c.translate(x, yBottom);
        if (rot != 0) c.rotate(rot);
        if (flip) c.scale(-1, 1);
        art.setBounds((int) (-width / 2), (int) -ah, (int) (width / 2), 0);
        art.setAlpha(alpha);
        art.draw(c);
        c.restore();
    }

    private void emoji(Canvas c, String e, float x, float y, float size) {
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size);
        p.setColor(0xFF000000);
        c.drawText(e, x, y + size * 0.35f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /* ================= PANDA: the comic ================= */

    /** Each strip: 4 panels of {caption, speech, prop emoji, pose}; pose 0 stand, 1 flip, 2 lie, 3 jump. */
    private static final String[][][] STRIPS = {
        {{"Montag.", "Heute nur gesund.", "🎋", "0"}, {"Dann:", "…ist das Kuchen?", "🍰", "1"},
            {"Analyse:", "Bambus ist auch Kuchen. Irgendwie.", "🤔", "0"}, {"Fazit.", "Beides. Balance.", "🎋🍰", "3"}},
        {{"Ruhe finden.", "Ommm.", "🧘", "0"}, {"BZZZ.", "…", "📱", "1"},
            {"Nur kurz.", "Ommm-ail checken.", "📧", "0"}, {"Später.", "Morgen meditiere ich. Versprochen.", "🌙", "2"}},
        {{"Boxgym.", "Heute zeig ich's dem Sack!", "🥊", "0"}, {"BAM!", "Hah!", "💥", "3"},
            {"Der Sack schwingt zurück.", "Aua.", "😵", "2"}, {"Trotzdem.", "Runde 2. Immer Runde 2.", "💪", "0"}},
        {{"Spaziergang.", "Mist, Regen.", "🌧️", "0"}, {"Schirm auf …", "…kaputt.", "☂️", "1"},
            {"Stille.", "…", "💧", "0"}, {"Plan B.", "Dann halt tanzen.", "💃", "3"}},
        {{"Abends.", "Was, wenn …", "💭", "0"}, {"Und was, wenn …", "…und dann …und dann …", "💭💭💭", "1"},
            {"Der Eisbär kommt.", "Eisbär sagt: Tee.", "🍵", "0"}, {"Hm.", "Okay. Tee.", "🍵", "2"}},
        {{"Wichtig.", "Heute: Steuern.", "📄", "0"}, {"5 Minuten später.", "Zzz …", "😴", "2"},
            {"Ruck!", "Nur fünf Minuten am Stück!", "⏱️", "3"}, {"Erledigt.", "Panda ist ein Held.", "✅", "0"}},
        {{"Party.", "Ich kenn hier niemand.", "🎉", "0"}, {"Jemand winkt.", "Meint die mich?", "👋", "1"},
            {"Mut.", "Hi. Ich bin Panda.", "🙂", "0"}, {"Später.", "Ein Gespräch. Reicht für heute.", "⭐", "3"}},
        {{"Spiegel.", "Bin ich gut genug?", "🪞", "0"}, {"Liste:", "Fehler 1, Fehler 2 …", "📝", "1"},
            {"Die Eule fragt:", "Und was hast du gut gemacht?", "🦉", "0"}, {"Oh.", "…ziemlich viel, eigentlich.", "💚", "3"}},
    };
    private int strip, panel = 1;
    private float panelAt;

    private void comic(Canvas c, float w, float h, float t) {
        c.drawColor(0xFFFFF4D6);
        // Halftone dots.
        p.setColor(0x14B5271E);
        for (float y = dp(10); y < h; y += dp(14)) for (float x = (y / dp(14)) % 2 * dp(7); x < w; x += dp(14)) c.drawCircle(x, y, dp(2.2f), p);
        float top = dp(96), gap = dp(10), pw = (w - dp(16) * 2 - gap) / 2, ph = Math.min(pw * 1.25f, (h - top - dp(110) - gap) / 2);
        String[][] s = STRIPS[strip];
        for (int i = 0; i < 4; i++) {
            float x = dp(16) + (i % 2) * (pw + gap), y = top + (i / 2) * (ph + gap);
            r.set(x, y, x + pw, y + ph);
            if (i >= panel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(0x331A1A1A);
                c.drawRoundRect(r, dp(6), dp(6), p);
                p.setStyle(Paint.Style.FILL);
                if (i == panel) {
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setColor(0x661A1A1A);
                    p.setTextSize(dp(28));
                    c.drawText("?", r.centerX(), r.centerY() + dp(10), p);
                    p.setTextAlign(Paint.Align.LEFT);
                }
                continue;
            }
            float in = i == panel - 1 ? Math.min(1, (t - panelAt) / 0.25f) : 1;
            c.save();
            float sc = 0.85f + 0.15f * in;
            c.scale(sc, sc, r.centerX(), r.centerY());
            p.setStyle(Paint.Style.FILL);
            int[] bg = {0xFFFFE08A, 0xFF9FD8F2, 0xFFF7B2C4, 0xFFB8E6A8};
            p.setColor(bg[(i + strip) % 4]);
            c.drawRoundRect(r, dp(6), dp(6), p);
            // Action lines on the punchline.
            if (i == 3) {
                p.setColor(0x33FFFFFF);
                p.setStrokeWidth(dp(3));
                for (int k = 0; k < 12; k++) {
                    double a = k / 12.0 * Math.PI * 2;
                    c.drawLine(r.centerX(), r.centerY() + ph * 0.1f, r.centerX() + (float) Math.cos(a) * pw, r.centerY() + (float) Math.sin(a) * pw, p);
                }
            }
            c.save();
            c.clipRect(r);
            int pose = Integer.parseInt(s[i][3]);
            float bob = pose == 3 ? -Math.abs((float) Math.sin(t * 6)) * dp(10) : 0;
            drawArt(c, r.centerX() - pw * 0.08f, r.bottom - dp(8) + bob, pw * 0.68f, pose == 1, pose == 2 ? -80 : 0, 255);
            emoji(c, s[i][2], r.right - pw * 0.2f, r.bottom - ph * 0.42f, dp(26));
            c.restore();
            // Caption box.
            p.setColor(0xFFFFF4D6);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(10));
            float cw = p.measureText(s[i][0]) + dp(12);
            c.drawRect(r.left, r.top, r.left + cw, r.top + dp(18), p);
            p.setColor(0xFF1A1A1A);
            c.drawText(s[i][0], r.left + dp(6), r.top + dp(13), p);
            // Speech bubble.
            bubble(c, s[i][1], r.left + dp(8), r.top + dp(24), pw - dp(16));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setColor(0xFF1A1A1A);
            c.drawRoundRect(r, dp(6), dp(6), p);
            p.setStyle(Paint.Style.FILL);
            c.restore();
        }
        p.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(15));
        p.setColor(0xFF1A1A1A);
        float y = top + 2 * ph + gap + dp(34);
        c.drawText(panel < 4 ? "Tippen = weiter" : "Tippen = nächster Comic  ·  " + (strip + 1) + "/" + STRIPS.length, w / 2, y, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void bubble(Canvas c, String text, float x, float y, float maxW) {
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        p.setTextSize(dp(12));
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(tryLine) > maxW - dp(14) && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tryLine);
            }
        }
        lines.add(line.toString());
        float bw = 0;
        for (String l : lines) bw = Math.max(bw, p.measureText(l));
        float bh = lines.size() * dp(15) + dp(8);
        r.set(x, y, x + bw + dp(14), y + bh);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawRoundRect(r, dp(10), dp(10), p);
        path.reset();
        path.moveTo(x + dp(14), y + bh - 1);
        path.lineTo(x + dp(10), y + bh + dp(9));
        path.lineTo(x + dp(26), y + bh - 1);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.5f));
        p.setColor(0xFF1A1A1A);
        c.drawRoundRect(r, dp(10), dp(10), p);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < lines.size(); i++) c.drawText(lines.get(i), x + dp(7), y + dp(16) + i * dp(15), p);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void comicTap() {
        if (panel < 4) {
            panel++;
            panelAt = t();
            if (panel == 4) {
                score++;
                prefs.edit().putInt("buddyXp", prefs.getInt("buddyXp", 0) + 1).apply();
                if (score > prefs.getInt(bestKey(), 0)) prefs.edit().putInt(bestKey(), score).apply();
            }
        } else {
            strip = (strip + 1) % STRIPS.length;
            prefs.edit().putInt("pandaStrip", strip).apply();
            panel = 1;
            panelAt = t();
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /* ================= ICE BEAR: snowball fight ================= */

    // things: x, y, born, type (0 penguin, 1 seal), threwAt (-1 not yet)
    // shots: x, y, tx, ty, born, mine (1 = his throw, 0 = at him)

    private void ice(Canvas c, float w, float h, float t, float dt) {
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF9ED8F5, 0xFFEAF7FF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // Icebergs and the ice floor.
        p.setColor(0xFFFFFFFF);
        path.reset();
        path.moveTo(0, h * 0.62f);
        for (int i = 0; i <= 6; i++) path.lineTo(w * i / 6f, h * (0.58f + (i % 2) * 0.05f));
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        c.drawPath(path, p);
        p.setColor(0x339ED8F5);
        for (int i = 0; i < 5; i++) c.drawOval(w * (0.1f + i * 0.2f) - dp(30), h * 0.48f + (i % 2) * dp(40), w * (0.1f + i * 0.2f) + dp(30), h * 0.48f + (i % 2) * dp(40) + dp(12), p);
        float bx = w / 2, by = h - dp(40);
        if (!over) {
            spawnIn -= dt;
            float pace = 1 + t / 40f;
            if (spawnIn <= 0 && things.size() < 2 + (int) (t / 20)) {
                int hole = rnd.nextInt(5);
                things.add(new float[]{w * (0.1f + hole * 0.2f), h * 0.48f + (hole % 2) * dp(40), t, rnd.nextInt(3) == 0 ? 1 : 0, -1});
                spawnIn = Math.max(0.45f, 1.5f / pace);
            }
            for (int i = things.size() - 1; i >= 0; i--) {
                float[] o = things.get(i);
                float stay = Math.max(0.9f, 1.8f / pace);
                if (o[4] < 0 && t - o[2] > stay) {
                    o[4] = t;
                    shots.add(new float[]{o[0], o[1] - dp(20), bx + (rnd.nextFloat() - 0.5f) * dp(40), by - dp(50), t, 0});
                }
                if (o[4] >= 0 && t - o[4] > 0.5f) things.remove(i);
            }
            for (int i = shots.size() - 1; i >= 0; i--) {
                float[] s = shots.get(i);
                float dur = s[5] == 1 ? 0.28f : Math.max(0.8f, 1.4f / (1 + t / 60f));
                if (t - s[4] >= dur) {
                    shots.remove(i);
                    if (s[5] == 1) {
                        for (int k = things.size() - 1; k >= 0; k--) {
                            float[] o = things.get(k);
                            if (Math.hypot(o[0] - s[2], o[1] - dp(16) - s[3]) < dp(44)) {
                                things.remove(k);
                                score += 2;
                                pop(o[0], o[1] - dp(40), o[3] == 1 ? "+2 PLATSCH" : "+2 TREFFER", 0xFF2B8BD9);
                                break;
                            }
                        }
                    } else {
                        hearts--;
                        hurtAt = t;
                        pop(bx, by - dp(110), "−♥", 0xFFE5482C);
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        if (hearts <= 0) end();
                    }
                }
            }
        }
        for (float[] o : things) {
            float up = Math.min(1, (t - o[2]) / 0.2f);
            p.setColor(0xFF2B5F7A);
            c.drawOval(o[0] - dp(30), o[1] - dp(4), o[0] + dp(30), o[1] + dp(10), p);
            c.save();
            c.clipRect(o[0] - dp(40), o[1] - dp(80), o[0] + dp(40), o[1] + dp(3));
            emoji(c, o[3] == 1 ? "🦭" : "🐧", o[0], o[1] - dp(22) * up + dp(18) * (1 - up), dp(40));
            c.restore();
            if (o[4] >= 0) emoji(c, "💨", o[0] + dp(22), o[1] - dp(40), dp(18));
        }
        for (float[] s : shots) {
            float dur = s[5] == 1 ? 0.28f : Math.max(0.8f, 1.4f / (1 + t / 60f));
            float k = Math.min(1, (t - s[4]) / dur);
            float x = s[0] + (s[2] - s[0]) * k, y = s[1] + (s[3] - s[1]) * k - (float) Math.sin(k * Math.PI) * dp(60);
            float size = s[5] == 1 ? dp(10) * (1 - k * 0.5f) : dp(8) + dp(16) * k;
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, size, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(0xFF7FB6D6);
            c.drawCircle(x, y, size, p);
            p.setStyle(Paint.Style.FILL);
        }
        boolean hurt = t - hurtAt < 0.4f;
        drawArt(c, bx, h - dp(8), Math.min(w * 0.55f, dp(210)), false, hurt ? (float) Math.sin(t * 60) * 6 : 0, hurt ? 150 : 255);
        if (t < 3.5f) tip(c, w, h, "Pinguine & Robben antippen ❄ · Schneebälle auf dich wegtippen");
    }

    /* ================= OWL: night exploration ================= */

    private static final String[] OWL_FINDS = {"🐭", "⭐", "🍄", "🗝️", "💎", "📜", "🌙", "🐌", "🦔", "🌰", "🪶", "🕯️"};
    private static final String[] OWL_FACTS = {
        "Eulen drehen den Kopf bis 270°.", "Eulen fliegen fast lautlos.", "Ihre Augen sind Röhren – sie drehen sich nicht.",
        "Ein Ohr sitzt höher als das andere: so hören sie, wo etwas ist.", "Schleiereulen hören eine Maus unter Schnee.",
        "Eine Eulengruppe heißt Parlament.", "Eulen sehen Farben, aber nachts vor allem Hell und Dunkel.",
        "Manche Eulen jagen auch tagsüber.", "Eulen haben drei Augenlider.", "Der Uhu ist die grösste Eule Europas.",
        "Eulen schlucken Mäuse ganz – und würgen Gewölle aus.", "Federn an den Füssen halten warm."};
    // things: x, y, emoji index, found(0/1), seen (seconds under the light)
    private int found;
    private float dark = 0.86f;

    private void hide(float w, float h) {
        things.clear();
        found = 0;
        int n = Math.min(OWL_FINDS.length, 4 + level * 2);
        for (int i = 0; i < n; i++) {
            things.add(new float[]{dp(30) + rnd.nextFloat() * (w - dp(60)), h * 0.2f + rnd.nextFloat() * h * 0.52f, rnd.nextInt(OWL_FINDS.length), 0, 0});
        }
        dark = Math.min(0.95f, 0.82f + level * 0.03f);
    }

    private void owl(Canvas c, float w, float h, float t, float dt) {
        if (things.isEmpty() && !over) hide(w, h);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF1B2A4A, 0xFF2E4A33, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // Moon, stars, trees.
        p.setColor(0xFFFFF4C2);
        c.drawCircle(w * 0.8f, h * 0.14f, dp(26), p);
        p.setColor(0xFF1B2A4A);
        c.drawCircle(w * 0.8f + dp(10), h * 0.14f - dp(6), dp(22), p);
        Random sr = new Random(level * 31L);
        p.setColor(0xCCFFFFFF);
        for (int i = 0; i < 40; i++) c.drawCircle(sr.nextFloat() * w, sr.nextFloat() * h * 0.35f, dp(1 + sr.nextFloat()), p);
        for (int i = 0; i < 7; i++) {
            float x = sr.nextFloat() * w, base = h * (0.7f + sr.nextFloat() * 0.15f), th = h * (0.35f + sr.nextFloat() * 0.2f);
            p.setColor(0xFF14301E);
            path.reset();
            path.moveTo(x, base - th);
            path.lineTo(x - dp(50), base);
            path.lineTo(x + dp(50), base);
            path.close();
            c.drawPath(path, p);
            p.setColor(0xFF3B2A1A);
            c.drawRect(x - dp(6), base, x + dp(6), base + dp(24), p);
        }
        // The hidden things (found ones glow).
        for (float[] o : things) {
            if (o[3] == 1) {
                p.setColor(0x55FFF4C2);
                c.drawCircle(o[0], o[1], dp(26), p);
            }
            emoji(c, OWL_FINDS[(int) o[2]], o[0], o[1], dp(28));
        }
        // Fireflies.
        for (int i = 0; i < 9; i++) {
            float fxp = (float) (w * (0.5 + 0.45 * Math.sin(t * 0.3 + i * 1.7))), fyp = (float) (h * (0.45 + 0.3 * Math.sin(t * 0.23 + i * 2.3)));
            p.setColor(0xFFE6FF7A);
            p.setAlpha((int) (120 + 120 * Math.sin(t * 3 + i)));
            c.drawCircle(fxp, fyp, dp(2.5f), p);
        }
        p.setAlpha(255);
        // Night: dark everywhere except the owl's gaze.
        float gx = down ? fingerX : w / 2, gy = down ? fingerY : h * 0.45f, gr = dp(down ? 95 : 60);
        p.setShader(new RadialGradient(gx, gy, gr * 1.6f, new int[]{0x00000000, 0x00000000, ((int) (255 * dark) << 24)},
            new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // Looking at something long enough finds it.
        if (!over && down) {
            for (float[] o : things) {
                if (o[3] == 1) continue;
                if (Math.hypot(o[0] - gx, o[1] - gy) < gr * 0.7f) {
                    o[4] += dt;
                    if (o[4] > 0.6f) {
                        o[3] = 1;
                        found++;
                        score++;
                        pop(o[0], o[1] - dp(30), OWL_FACTS[(int) o[2] % OWL_FACTS.length], 0xFFFFF4C2);
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    }
                } else {
                    o[4] = Math.max(0, o[4] - dt);
                }
            }
            if (found == things.size()) {
                level++;
                pop(w / 2, h * 0.3f, "Nacht " + level + " · noch dunkler", 0xFFE6FF7A);
                hide(w, h);
            }
        }
        // The owl on its branch, head towards your gaze.
        p.setColor(0xFF3B2A1A);
        c.drawRoundRect(w * 0.1f, h - dp(64), w * 0.9f, h - dp(52), dp(6), dp(6), p);
        drawArt(c, w / 2, h - dp(58), dp(150), gx < w / 2, 0, 255);
        if (!over && t > 90) end();
        if (t < 4f) tip(c, w, h, "Finger = Blick der Eule · Licht auf etwas halten = gefunden");
    }

    /* ================= DRAGON: fly and fire ================= */

    // things: x, y, vx, vy, type (0 knight, 1 eagle, 2 arrow), hp
    private float fuel = 1, dragonY = -1;
    private boolean fireOut;

    private void dragon(Canvas c, float w, float h, float t, float dt) {
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFFFF9A5A, 0xFF5A2A6A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // Hills and a castle far away, scrolling.
        float ground = h * 0.84f;
        p.setColor(0xFF3A1F4A);
        for (int i = -1; i < 4; i++) {
            float x = ((i * w / 2.2f - t * dp(30)) % (w * 1.4f) + w * 1.4f) % (w * 1.4f) - w * 0.2f;
            c.drawCircle(x, ground + dp(60), w * 0.35f, p);
        }
        p.setColor(0xFF2A1636);
        float cx = ((w * 0.8f - t * dp(14)) % (w * 1.6f) + w * 1.6f) % (w * 1.6f) - w * 0.3f;
        c.drawRect(cx, ground - dp(70), cx + dp(70), ground, p);
        for (int i = 0; i < 4; i++) c.drawRect(cx + i * dp(20), ground - dp(84), cx + i * dp(20) + dp(10), ground - dp(70), p);
        p.setColor(0xFF2A1A20);
        c.drawRect(0, ground, w, h, p);
        if (dragonY < 0) dragonY = h * 0.4f;
        float target = down ? Math.max(h * 0.15f, Math.min(ground - dp(40), fingerY)) : dragonY;
        dragonY += (target - dragonY) * Math.min(1, dt * 6);
        float dx = w * 0.22f, mouthX = dx + dp(62), mouthY = dragonY - dp(52);
        boolean firing = down && !fireOut && !over;
        if (firing) {
            fuel -= dt * 0.45f;
            if (fuel <= 0) {
                fuel = 0;
                fireOut = true;
            }
        } else {
            fuel = Math.min(1, fuel + dt * 0.3f);
            if (fuel > 0.3f) fireOut = false;
        }
        if (!over) {
            spawnIn -= dt;
            float pace = 1 + t / 35f;
            if (spawnIn <= 0) {
                int type = rnd.nextInt(3);
                if (type == 0) things.add(new float[]{w + dp(20), ground - dp(14), -dp(60) * pace, 0, 0, 2});
                else if (type == 1) things.add(new float[]{w + dp(20), h * (0.15f + rnd.nextFloat() * 0.5f), -dp(140) * pace, 0, 1, 1});
                else {
                    float ax = w * (0.55f + rnd.nextFloat() * 0.4f), ay = ground;
                    float vx = (dx - ax), vy = (dragonY - dp(30) - ay);
                    float len = (float) Math.hypot(vx, vy);
                    things.add(new float[]{ax, ay, vx / len * dp(300), vy / len * dp(300), 2, 1});
                }
                spawnIn = Math.max(0.35f, 1.3f / pace);
            }
            for (int i = things.size() - 1; i >= 0; i--) {
                float[] o = things.get(i);
                o[0] += o[2] * dt;
                o[1] += o[3] * dt;
                if (o[4] == 1) o[1] += (float) Math.sin(t * 4 + i) * dp(40) * dt;
                // Fire: a cone from the mouth to the right.
                if (firing) {
                    float fx2 = o[0] - mouthX, fy2 = o[1] - mouthY;
                    if (fx2 > 0 && fx2 < dp(240) && Math.abs(fy2) < dp(20) + fx2 * 0.35f) {
                        o[5] -= dt * 3;
                        if (o[5] <= 0) {
                            things.remove(i);
                            int v = o[4] == 0 ? 3 : o[4] == 1 ? 2 : 1;
                            score += v;
                            pop(o[0], o[1] - dp(20), "+" + v + (o[4] == 2 ? " VERBRANNT" : " 🔥"), 0xFFFFD34D);
                            continue;
                        }
                    }
                }
                // Hits the dragon.
                if (Math.hypot(o[0] - (dx + dp(10)), o[1] - (dragonY - dp(36))) < dp(46)) {
                    things.remove(i);
                    hearts--;
                    hurtAt = t;
                    pop(dx, dragonY - dp(90), "−♥", 0xFFE5482C);
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    if (hearts <= 0) end();
                    continue;
                }
                if (o[0] < -dp(40) || o[1] < -dp(40) || o[0] > w + dp(60)) things.remove(i);
            }
        }
        for (float[] o : things) {
            if (o[4] == 2) {
                p.setColor(0xFF1A1A1A);
                p.setStrokeWidth(dp(2.5f));
                float len = (float) Math.hypot(o[2], o[3]);
                c.drawLine(o[0], o[1], o[0] - o[2] / len * dp(22), o[1] - o[3] / len * dp(22), p);
                p.setColor(0xFFF4F2EC);
                c.drawCircle(o[0] - o[2] / len * dp(22), o[1] - o[3] / len * dp(22), dp(3), p);
            } else {
                emoji(c, o[4] == 0 ? "🛡️" : "🦅", o[0], o[1], dp(32));
                if (o[4] == 0) emoji(c, "⚔️", o[0] - dp(18), o[1] - dp(10), dp(18));
            }
        }
        // Fire.
        if (firing) {
            for (int i = 0; i < 26; i++) {
                float k = rnd.nextFloat();
                float x = mouthX + k * dp(230), y = mouthY + (rnd.nextFloat() - 0.5f) * (dp(14) + k * dp(150));
                p.setColor(k < 0.3f ? 0xFFFFF27A : k < 0.65f ? 0xFFFF9A2B : 0xFFE5482C);
                p.setAlpha((int) (230 * (1 - k * 0.7f)));
                c.drawCircle(x, y, dp(5) + k * dp(14), p);
            }
            p.setAlpha(255);
        }
        boolean hurt = t - hurtAt < 0.4f;
        float flap = (float) Math.sin(t * (firing ? 14 : 8)) * 5;
        drawArt(c, dx, dragonY + (float) Math.sin(t * 3) * dp(4), dp(170), false, flap + (hurt ? (float) Math.sin(t * 60) * 8 : 0), hurt ? 150 : 255);
        // Fire gauge.
        p.setColor(0x66000000);
        c.drawRoundRect(dp(16), h - dp(40), w - dp(16), h - dp(28), dp(6), dp(6), p);
        p.setColor(fireOut ? 0xFF8A8A86 : 0xFFFF9A2B);
        c.drawRoundRect(dp(16), h - dp(40), dp(16) + (w - dp(32)) * fuel, h - dp(28), dp(6), dp(6), p);
        if (t < 4f) tip(c, w, h, "Finger hoch/runter = fliegen · halten = Feuer 🔥 · Pfeilen ausweichen");
    }

    /* ================= Shared ================= */

    private void tip(Canvas c, float w, float h, String text) {
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(dp(12));
        p.setTextAlign(Paint.Align.CENTER);
        r.set(dp(14), h * 0.32f - dp(24), w - dp(14), h * 0.32f + dp(12));
        p.setColor(0xCC121211);
        c.drawRoundRect(r, dp(16), dp(16), p);
        p.setColor(0xFFF4F2EC);
        c.drawText(text, w / 2, h * 0.32f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawFx(Canvas c, float t) {
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = fx.size() - 1; i >= 0; i--) {
            float[] e = fx.get(i);
            String text = fxText.get(i);
            float life = text.length() > 14 ? 3f : 0.9f;          // owl facts stay to be read
            float age = t - e[2];
            if (age > life) {
                fx.remove(i);
                fxText.remove(i);
                continue;
            }
            float a = Math.min(1, (life - age) / 0.4f);
            p.setTextSize(dp(text.length() > 14 ? 13 : 18));
            float tw = p.measureText(text);
            float x = Math.max(tw / 2 + dp(12), Math.min(getWidth() - tw / 2 - dp(12), e[0]));
            float y = e[1] - Math.min(age, 0.9f) * dp(40);
            if (text.length() > 14) {
                p.setColor(0xCC121211);
                p.setAlpha((int) (200 * a));
                c.drawRoundRect(x - tw / 2 - dp(10), y - dp(18), x + tw / 2 + dp(10), y + dp(8), dp(10), dp(10), p);
            }
            p.setColor((int) e[3]);
            p.setAlpha((int) (255 * a));
            c.drawText(text, x, y, p);
        }
        p.setAlpha(255);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void hud(Canvas c, float w, float h, float t) {
        float top = dp(54);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC121211);
        r.set(dp(14), top - dp(30), w - dp(14), top + dp(14));
        c.drawRoundRect(r, dp(18), dp(18), p);
        p.setColor(0xFFF4F2EC);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setTextSize(dp(13));
        String name = new String[]{"🐼 COMIC", "🐻‍❄️ SCHNEEBALL", "🦉 NACHT " + level, "🐉 FEUER"}[kind];
        String extra = kind == OWL ? "  ·  " + found + "/" + things.size() + "  ·  0:" + String.format(Locale.ROOT, "%02d", Math.max(0, 90 - (int) t)) : "";
        c.drawText(name + "  ·  " + score + extra + "  ·  BEST " + Math.max(score, prefs.getInt(bestKey(), 0)), dp(28), top, p);
        if (kind == ICE || kind == DRAGON) {
            String hs = "♥♥♥".substring(0, Math.max(0, hearts)) + "♡♡♡".substring(0, 3 - Math.max(0, hearts));
            p.setColor(0xFFE5482C);
            c.drawText(hs, w - dp(110), top, p);
        }
        p.setColor(0xFFF4F2EC);
        p.setTextSize(dp(18));
        closeR.set(w - dp(56), top - dp(30), w - dp(14), top + dp(14));
        c.drawText("✕", w - dp(42), top + dp(1), p);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawOver(Canvas c, float w, float h) {
        p.setColor(0xB3000000);
        c.drawRect(0, 0, w, h, p);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xFFF4F2EC);
        p.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        p.setTextSize(dp(32));
        c.drawText(new String[]{"", "Eisbär ist müde. ❄", "Morgen wird's hell. 🦉", "Der Drache landet. 🐉"}[kind], w / 2, h * 0.36f, p);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(dp(15));
        String unit = new String[]{"", " Treffer", " gefunden", " Punkte"}[kind];
        c.drawText(score + unit + " · Bestwert " + prefs.getInt(bestKey(), 0), w / 2, h * 0.36f + dp(36), p);
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
        fingerX = x;
        fingerY = y;
        if (a == MotionEvent.ACTION_DOWN) down = true;
        else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) down = false;
        if (a != MotionEvent.ACTION_DOWN) return true;
        if (kind == PANDA) {
            comicTap();
        } else if (kind == ICE) {
            float t = t();
            // A snowball flying at him: smash it.
            for (int i = shots.size() - 1; i >= 0; i--) {
                float[] s = shots.get(i);
                if (s[5] == 1) continue;
                float dur = Math.max(0.8f, 1.4f / (1 + t / 60f));
                float k = Math.min(1, (t - s[4]) / dur);
                float sx = s[0] + (s[2] - s[0]) * k, sy = s[1] + (s[3] - s[1]) * k - (float) Math.sin(k * Math.PI) * dp(60);
                if (Math.hypot(sx - x, sy - y) < dp(40)) {
                    shots.remove(i);
                    score++;
                    pop(sx, sy, "+1 ✶", 0xFF2B8BD9);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                }
            }
            // Otherwise: throw at where you tapped.
            shots.add(new float[]{getWidth() / 2f, getHeight() - dp(120), x, y, t, 1});
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        return true;
    }
}
