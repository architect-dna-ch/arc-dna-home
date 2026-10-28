package ch.architectdna.home;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Flexplan: a plain day calendar made of preset blocks (learning, sport, rest …) that bends with
 * how you feel. "Nicht jetzt" doesn't just push a block back: it looks at your Stimmung log
 * (weekday × hour), finds a slot where you usually feel better and that still fits the week, and
 * moves it there – so the week's goals still get done, just at easier times.
 */
public class PlanActivity extends Activity {

    /** Block kinds: key, emoji, name, minutes, energy it takes (1 light … 3 heavy), colour. */
    static final Object[][] KINDS = {
        {"lernen", "📚", "Lernen", 50, 3, 0xFF3C8DFF},
        {"sport", "🏃", "Sport", 45, 3, 0xFF2BBF6A},
        {"arbeit", "💼", "Arbeit", 90, 2, 0xFFD9472F},
        {"kreativ", "🎨", "Kreativ", 60, 2, 0xFFA77BFF},
        {"haushalt", "🧹", "Haushalt", 30, 1, 0xFFE8B931},
        {"admin", "📨", "Admin / Mails", 30, 1, 0xFF9A9A98},
        {"erholung", "🌿", "Erholung", 30, 1, 0xFF6FC3F2},
    };
    /** Default goals per week (blocks), changeable under VORLAGEN. */
    private static final int[] DEFAULT_TARGET = {4, 3, 0, 1, 2, 1, 5};
    private static final int DAY_FROM = 7, DAY_TO = 22;
    private static final String[] FACES = {"😣", "😕", "😐", "🙂", "😄"};

    private SharedPreferences prefs;
    private int bg, card, line, ink, ink2, ink3, rust;
    private FrameLayout root;
    private LinearLayout days, goals, faces;
    private TextView banner, dayTitle;
    private DayView day;
    private ScrollView sv;
    private final List<Block> blocks = new ArrayList<>();
    private Calendar shown = Calendar.getInstance();
    private int feel = -1;
    private long feelAt;

    static final class Block {
        long id, at;
        int kind, min, state, moved;     // state: 0 planned, 1 done, 2 dropped
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        prefs = getSharedPreferences("plan", MODE_PRIVATE);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFEDEDE8 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!night && Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(bits, bits);
        }
        load();
        feel = prefs.getInt("feel", -1);
        feelAt = prefs.getLong("feelAt", 0);

        sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(40));
        sv.addView(col);
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                col.setPadding(dp(22) + i.left, dp(18) + i.top, dp(22) + i.right, dp(40) + i.bottom);
            }
            return in;
        });

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = mono(11, ink3);
        label.setText("— FLEXPLAN");
        label.setLetterSpacing(0.24f);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(22, ink, true);
        close.setText("✕");
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        TextView q = text(30, ink, true);
        q.setText("Wie geht's dir gerade?");
        q.setLetterSpacing(-0.03f);
        col.addView(q, margins(-1, -2, dp(14)));
        faces = new LinearLayout(this);
        for (int i = 0; i < 5; i++) {
            TextView f = text(26, ink, false);
            f.setGravity(Gravity.CENTER);
            f.setText(FACES[i]);
            int m = i + 1;
            f.setOnClickListener(v -> setFeel(m));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
            lp.rightMargin = i < 4 ? dp(8) : 0;
            faces.addView(f, lp);
        }
        col.addView(faces, margins(-1, -2, dp(12)));

        banner = text(15, ink, false);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setLineSpacing(0, 1.12f);
        banner.setVisibility(View.GONE);
        col.addView(banner, margins(-1, -2, dp(12)));

        // Goals of the week: done / target per kind – the plan moves, the goals stay.
        TextView gh = mono(11, ink3);
        gh.setText("— DIESE WOCHE");
        gh.setLetterSpacing(0.2f);
        col.addView(gh, margins(-1, -2, dp(22)));
        goals = new LinearLayout(this);
        goals.setOrientation(LinearLayout.VERTICAL);
        col.addView(goals, margins(-1, -2, dp(8)));

        // Buttons.
        LinearLayout acts = new LinearLayout(this);
        TextView auto = chip("✨ WOCHE PLANEN", rust, true);
        auto.setOnClickListener(v -> autoPlan());
        acts.addView(auto, weighted(dp(8)));
        TextView add = chip("＋ BLOCK", ink, false);
        add.setOnClickListener(v -> addSheet(-1));
        acts.addView(add, weighted(dp(8)));
        TextView tpl = chip("VORLAGEN", ink, false);
        tpl.setOnClickListener(v -> targetsSheet());
        acts.addView(tpl, weighted(0));
        col.addView(acts, margins(-1, -2, dp(14)));

        // Day strip.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        days = new LinearLayout(this);
        hs.addView(days);
        col.addView(hs, margins(-1, -2, dp(22)));
        dayTitle = text(20, ink, true);
        col.addView(dayTitle, margins(-1, -2, dp(14)));
        TextView hint = mono(10, ink3);
        hint.setText("FREIE ZEIT ANTIPPEN = BLOCK · BLOCK ANTIPPEN = ERLEDIGT / NICHT JETZT");
        col.addView(hint, margins(-1, -2, dp(4)));
        day = new DayView();
        col.addView(day, margins(-1, dp(60) * (DAY_TO - DAY_FROM) + dp(20), dp(10)));

        root = new FrameLayout(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        MoodActivity.statusScrim(root, bg);
        setContentView(root);
        if (blocks.isEmpty() && !prefs.getBoolean("seeded", false)) {
            prefs.edit().putBoolean("seeded", true).apply();
            autoPlan();
        }
        paint();
        // Open at the current hour.
        sv.post(() -> {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            sv.scrollTo(0, Math.max(0, day.getTop() + dp(60) * Math.max(0, h - DAY_FROM - 1)));
        });
    }

    /* ================= Data ================= */

    private void load() {
        blocks.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("blocks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Block b = new Block();
                b.id = o.optLong("id");
                b.at = o.optLong("at");
                b.kind = Math.max(0, Math.min(KINDS.length - 1, o.optInt("k")));
                b.min = o.optInt("min", 30);
                b.state = o.optInt("s");
                b.moved = o.optInt("mv");
                blocks.add(b);
            }
        } catch (Exception ignored) {
        }
        // Old weeks fall away after 3 weeks.
        long keep = System.currentTimeMillis() - 21L * 24 * 3600_000;
        blocks.removeIf(b -> b.at < keep);
    }

    private void save() {
        JSONArray a = new JSONArray();
        for (Block b : blocks) {
            try {
                a.put(new JSONObject().put("id", b.id).put("at", b.at).put("k", b.kind).put("min", b.min).put("s", b.state).put("mv", b.moved));
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString("blocks", a.toString()).apply();
    }

    private int target(int k) {
        return prefs.getInt("target" + k, DEFAULT_TARGET[k]);
    }

    private static String name(int k) {
        return (String) KINDS[k][2];
    }

    private static String emoji(int k) {
        return (String) KINDS[k][1];
    }

    private static int colour(int k) {
        return (Integer) KINDS[k][5];
    }

    private static int energy(int k) {
        return (Integer) KINDS[k][4];
    }

    /** Monday 00:00 of the week of t. */
    private static long weekStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        c.add(Calendar.DAY_OF_YEAR, -dow);
        return c.getTimeInMillis();
    }

    private int countWeek(int k, boolean doneOnly) {
        long ws = weekStart(System.currentTimeMillis()), we = ws + 7L * 24 * 3600_000;
        int n = 0;
        for (Block b : blocks) if (b.kind == k && b.at >= ws && b.at < we && (doneOnly ? b.state == 1 : b.state != 2)) n++;
        return n;
    }

    /* ================= Feeling → good times ================= */

    /**
     * How you usually feel at this weekday and hour, from the Stimmung log (1 … 5). Same weekday
     * counts fully, other days at the same hour a little; without data a gentle day curve.
     */
    private double[][] moodGrid;

    private double predicted(int dow, int hour) {
        if (moodGrid == null) {
            moodGrid = new double[7][24];
            for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++) moodGrid[d][h] = compute(d, h);
        }
        return moodGrid[dow][Math.max(0, Math.min(23, hour))];
    }

    private double compute(int dow, int hour) {
        JSONArray a = moodLog != null ? moodLog : (moodLog = MoodActivity.entries(this));
        double sum = 0, wsum = 0;
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            c.setTimeInMillis(o.optLong("at"));
            int d = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7, h = c.get(Calendar.HOUR_OF_DAY);
            int dh = Math.abs(h - hour);
            if (dh > 1) continue;
            double w = (d == dow ? 1 : 0.35) * (dh == 0 ? 1 : 0.5);
            sum += o.optInt("m", 3) * w;
            wsum += w;
        }
        double prior = hour < 9 ? 2.9 : hour < 12 ? 3.4 : hour < 14 ? 3.1 : hour < 16 ? 2.9 : hour < 19 ? 3.2 : hour < 21 ? 3.3 : 2.8;
        return (sum + prior * 1.5) / (wsum + 1.5);          // a little prior keeps thin data honest
    }

    private JSONArray moodLog;

    private int dow(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    private int hour(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    /** How good a start time is for a block of this kind. Heavy blocks want good-mood hours. */
    private double score(int kind, long at, long near) {
        double m = predicted(dow(at), hour(at));
        double s = m + (m - 3) * (energy(kind) - 1) * 0.6;
        s -= Math.abs(at - near) / 3600_000.0 * 0.04;            // rather soon than far away
        int same = 0, onDay = 0;
        long d0 = dayStart(at);
        for (Block b : blocks) {
            if (b.state == 2 || b.at < d0 || b.at >= d0 + 24 * 3600_000L) continue;
            onDay++;
            if (b.kind == kind) same++;
        }
        s -= same * 1.2 + Math.max(0, onDay - 3) * 0.5;        // spread it out, don't stack one day
        return s;
    }

    private static long dayStart(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private boolean free(long at, int min, Block except) {
        long end = at + min * 60_000L;
        for (Block b : blocks) {
            if (b == except || b.state == 2) continue;
            long be = b.at + b.min * 60_000L;
            if (at < be + 10 * 60_000L && end + 10 * 60_000L > b.at) return false;      // 10 min air between
        }
        return true;
    }

    /** The best free slot from 'from' to 'until' (half-hour steps, 7–22 h). */
    private long bestSlot(int kind, int min, long from, long until, long near, Block except) {
        long best = -1;
        double bestS = -1e9;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(from);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.MINUTE, c.get(Calendar.MINUTE) < 30 ? 30 : 0);
        if (c.get(Calendar.MINUTE) == 0) c.add(Calendar.HOUR_OF_DAY, 1);
        for (; c.getTimeInMillis() < until; c.add(Calendar.MINUTE, 30)) {
            int h = c.get(Calendar.HOUR_OF_DAY);
            long t = c.getTimeInMillis();
            if (h < DAY_FROM || t + min * 60_000L > dayStart(t) + DAY_TO * 3600_000L) continue;
            if (!free(t, min, except)) continue;
            double s = score(kind, t, near);
            if (s > bestS) {
                bestS = s;
                best = t;
            }
        }
        return best;
    }

    /* ================= Actions ================= */

    private void setFeel(int m) {
        feel = m;
        feelAt = System.currentTimeMillis();
        prefs.edit().putInt("feel", m).putLong("feelAt", feelAt).apply();
        // Into the shared Stimmung log too: every tap makes the good-time guess better.
        try {
            JSONArray a = MoodActivity.entries(this);
            a.put(new JSONObject().put("at", feelAt).put("m", m).put("n", "Flexplan"));
            getSharedPreferences("mood", MODE_PRIVATE).edit().putString("log", a.toString()).apply();
        } catch (Exception ignored) {
        }
        moodLog = null;
        moodGrid = null;
        paintFaces();
        if (m <= 2) offerLighter();
        else if (m >= 4) say("Schön! Gute Zeit für was Schweres – " + nextHeavyHint());
        else banner.setVisibility(View.GONE);
    }

    /** Low now: heavy blocks in the next 3 hours can move to easier times, in one tap. */
    private void offerLighter() {
        long now = System.currentTimeMillis();
        List<Block> heavy = new ArrayList<>();
        for (Block b : blocks) if (b.state == 0 && energy(b.kind) >= 2 && b.at + b.min * 60_000L > now && b.at < now + 3 * 3600_000L) heavy.add(b);
        if (heavy.isEmpty()) {
            say("Okay. Nichts Schweres in den nächsten Stunden – gönn dir 🌿 Erholung.");
            return;
        }
        StringBuilder sb = new StringBuilder("Heute schwer? Ich schieb dir ");
        for (int i = 0; i < heavy.size(); i++) sb.append(i > 0 ? ", " : "").append(emoji(heavy.get(i).kind)).append(' ').append(name(heavy.get(i).kind));
        sb.append(" auf Zeiten, wo's dir meist leichter fällt. Antippen = verschieben.");
        say(sb.toString());
        banner.setOnClickListener(v -> {
            StringBuilder done = new StringBuilder();
            for (Block b : heavy) {
                String r = notNow(b, false);
                if (r != null) done.append(r).append('\n');
            }
            save();
            paint();
            say(done.length() == 0 ? "Diese Woche ist kein besserer Platz frei – vielleicht kürzer machen?" : done.toString().trim());
        });
    }

    private String nextHeavyHint() {
        long now = System.currentTimeMillis();
        for (Block b : blocks) if (b.state == 0 && energy(b.kind) >= 3 && b.at > now - 30 * 60_000L && b.at < now + 4 * 3600_000L)
            return emoji(b.kind) + " " + name(b.kind) + " um " + hm(b.at) + " passt.";
        return "vielleicht jetzt " + emoji(0) + " Lernen?";
    }

    /**
     * "Nicht jetzt": find a slot where you're usually in better shape, at least 45 min from now,
     * still this week if the week's goal needs it. Returns the explanation, or null.
     */
    private String notNow(Block b, boolean single) {
        long now = System.currentTimeMillis();
        long from = Math.max(now + 45 * 60_000L, b.at + 30 * 60_000L);
        boolean needed = countWeek(b.kind, false) <= target(b.kind);
        long weekEnd = weekStart(now) + 7L * 24 * 3600_000;
        long until = needed && weekEnd - from > 6 * 3600_000L ? weekEnd : from + 4L * 24 * 3600_000;
        long t = bestSlot(b.kind, b.min, from, until, from, b);
        if (t < 0) return null;
        double m = predicted(dow(t), hour(t));
        b.at = t;
        b.moved++;
        String why = emoji(b.kind) + " " + name(b.kind) + " → " + new SimpleDateFormat("EE HH:mm", Locale.GERMAN).format(new Date(t))
            + String.format(Locale.GERMAN, " · da geht's dir meist %s (Ø %.1f).", m >= 3.6 ? "gut" : m >= 3 ? "okay" : "eher mau, aber besser als jetzt", m);
        if (single && b.moved >= 3) why += " Schon " + b.moved + "× verschoben – mach's kürzer? Kleinster Schritt zählt.";
        return why;
    }

    /** Fill the rest of the week with the missing blocks, each at its best free time. */
    private void autoPlan() {
        long now = System.currentTimeMillis(), we = weekStart(now) + 7L * 24 * 3600_000;
        int added = 0;
        for (int round = 0; round < 7; round++) {
            for (int k = 0; k < KINDS.length; k++) {
                if (countWeek(k, false) >= target(k)) continue;
                int min = (Integer) KINDS[k][3];
                long t = bestSlot(k, min, now + 15 * 60_000L, we, now, null);
                if (t < 0) continue;
                Block b = new Block();
                b.id = System.nanoTime();
                b.at = t;
                b.kind = k;
                b.min = min;
                blocks.add(b);
                added++;
            }
        }
        save();
        paint();
        say(added == 0 ? "Die Woche steht schon – alle Ziele sind eingeplant."
            : "✨ " + added + " Blöcke eingeplant – schwere dorthin, wo du dich meist gut fühlst. Passt was nicht? Antippen → Nicht jetzt.");
    }

    /* ================= Painting ================= */

    private void paint() {
        paintFaces();
        // Days of this week and the next.
        days.removeAllViews();
        long ws = weekStart(System.currentTimeMillis());
        SimpleDateFormat d1 = new SimpleDateFormat("EE", Locale.GERMAN), d2 = new SimpleDateFormat("d.", Locale.GERMAN);
        for (int i = 0; i < 14; i++) {
            long t = ws + i * 24L * 3600_000;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t + 12 * 3600_000L);
            boolean sel = dayStart(shown.getTimeInMillis()) == dayStart(c.getTimeInMillis());
            boolean today = dayStart(System.currentTimeMillis()) == dayStart(c.getTimeInMillis());
            int n = 0;
            for (Block b : blocks) if (b.state != 2 && dayStart(b.at) == dayStart(c.getTimeInMillis())) n++;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(12), dp(8), dp(12), dp(8));
            box.setBackground(box(sel ? rust : card, sel ? rust : today ? rust : line, 16));
            TextView a = mono(10, sel ? 0xFFFFFFFF : ink3);
            a.setText(d1.format(c.getTime()).replace(".", "").toUpperCase(Locale.GERMAN));
            box.addView(a);
            TextView b = text(18, sel ? 0xFFFFFFFF : ink, true);
            b.setText(d2.format(c.getTime()));
            box.addView(b);
            TextView dots = mono(9, sel ? 0xFFFFFFFF : rust);
            dots.setText(n == 0 ? " " : "•••••".substring(0, Math.min(5, n)));
            box.addView(dots);
            long pick = c.getTimeInMillis();
            box.setOnClickListener(v -> {
                shown.setTimeInMillis(pick);
                paint();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            days.addView(box, lp);
        }
        dayTitle.setText(new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(shown.getTime()));
        // Goals.
        goals.removeAllViews();
        for (int k = 0; k < KINDS.length; k++) {
            int t = target(k);
            if (t == 0) continue;
            int done = countWeek(k, true), planned = countWeek(k, false);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView l = text(14, ink, false);
            l.setText(emoji(k) + "  " + name(k));
            row.addView(l, new LinearLayout.LayoutParams(dp(150), -2));
            View bar = new Bar(done / (float) t, planned / (float) t, colour(k));
            row.addView(bar, new LinearLayout.LayoutParams(0, dp(10), 1f));
            TextView c = mono(11, done >= t ? 0xFF2BBF6A : ink2);
            c.setText("  " + done + "/" + t + (done >= t ? " ✓" : ""));
            row.addView(c, new LinearLayout.LayoutParams(dp(56), -2));
            goals.addView(row, margins(-1, -2, dp(6)));
        }
        day.invalidate();
    }

    private void paintFaces() {
        boolean fresh = System.currentTimeMillis() - feelAt < 3 * 3600_000L;
        for (int i = 0; i < 5; i++) {
            View f = faces.getChildAt(i);
            boolean on = fresh && feel == i + 1;
            f.setBackground(box(on ? rust : card, on ? rust : line, 18));
            f.setAlpha(fresh && !on ? 0.55f : 1f);
        }
    }

    private void say(String s) {
        banner.setText(s);
        banner.setTextColor(ink);
        banner.setBackground(box(card, rust, 18));
        banner.setVisibility(View.VISIBLE);
        banner.setOnClickListener(null);
        banner.setAlpha(0f);
        banner.animate().alpha(1f).setDuration(220).start();
    }

    private String hm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(t));
    }

    /* ================= Sheets ================= */

    private interface Pick {
        void on(int i);
    }

    /** A bottom sheet with big rows (no Android dialog). */
    private void sheet(String title, String[] rows, Pick pick) {
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x99000000);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable g = box(card, line, 26);
        box.setBackground(g);
        box.setClickable(true);
        TextView t = text(20, ink, true);
        t.setText(title);
        box.addView(t);
        for (int i = 0; i < rows.length; i++) {
            TextView r = text(16, ink, false);
            r.setText(rows[i]);
            r.setPadding(dp(16), dp(14), dp(16), dp(14));
            r.setBackground(box(bg, line, 16));
            int k = i;
            r.setOnClickListener(v -> {
                root.removeView(scrim);
                pick.on(k);
            });
            box.addView(r, margins(-1, -2, dp(8)));
        }
        int nav = 0;
        if (Build.VERSION.SDK_INT >= 30 && root.getRootWindowInsets() != null)
            nav = root.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()).bottom;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(dp(10), 0, dp(10), dp(10) + nav);
        ScrollView ss = new ScrollView(this);
        ss.addView(box);
        scrim.addView(ss, lp);
        scrim.setOnClickListener(v -> root.removeView(scrim));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        box.setTranslationY(dp(300));
        box.animate().translationY(0).setDuration(240).start();
    }

    /** Add a block: at a tapped time, or (at = -1) at the best time this week. */
    private void addSheet(long at) {
        String[] rows = new String[KINDS.length];
        for (int k = 0; k < KINDS.length; k++) rows[k] = emoji(k) + "  " + name(k) + " · " + KINDS[k][3] + " min";
        sheet(at < 0 ? "Was planen? (ich such die beste Zeit)" : "Um " + hm(at) + ":", rows, k -> {
            int min = (Integer) KINDS[k][3];
            long t = at;
            if (t < 0) {
                long now = System.currentTimeMillis();
                t = bestSlot(k, min, Math.max(now, dayStart(shown.getTimeInMillis())), weekStart(now) + 14L * 24 * 3600_000, Math.max(now, dayStart(shown.getTimeInMillis()) + 9 * 3600_000L), null);
                if (t < 0) {
                    say("Kein freier Platz gefunden – nimm einen Tag mit weniger Punkten.");
                    return;
                }
            }
            Block b = new Block();
            b.id = System.nanoTime();
            b.at = t;
            b.kind = k;
            b.min = min;
            blocks.add(b);
            save();
            shown.setTimeInMillis(t);
            paint();
            if (at < 0) say(emoji(k) + " " + name(k) + " → " + new SimpleDateFormat("EE HH:mm", Locale.GERMAN).format(new Date(t))
                + String.format(Locale.GERMAN, " (da meist Ø %.1f).", predicted(dow(t), hour(t))));
        });
    }

    private void blockSheet(Block b) {
        String when = new SimpleDateFormat("EE HH:mm", Locale.GERMAN).format(new Date(b.at)) + "–" + hm(b.at + b.min * 60_000L);
        String title = emoji(b.kind) + " " + name(b.kind) + " · " + when;
        if (b.state == 1) {
            sheet(title, new String[]{"↺ Doch nicht erledigt", "🗑 Entfernen"}, i -> {
                if (i == 0) b.state = 0;
                else blocks.remove(b);
                save();
                paint();
            });
            return;
        }
        sheet(title, new String[]{"✓ Erledigt", "😮‍💨 Nicht jetzt – leichtere Zeit finden", "⏱ Kürzer: nur " + Math.max(10, b.min / 2) + " min",
            "🗑 Entfernen"}, i -> {
            if (i == 0) {
                b.state = 1;
                save();
                paint();
                int k = b.kind;
                say("Stark! " + emoji(k) + " " + countWeek(k, true) + "/" + target(k) + " diese Woche.");
            } else if (i == 1) {
                String r = notNow(b, true);
                save();
                if (r != null) shown.setTimeInMillis(b.at);
                paint();
                say(r != null ? r : "Kein besserer Platz frei. Mach's kürzer – 10 Minuten zählen auch.");
            } else if (i == 2) {
                b.min = Math.max(10, b.min / 2);
                save();
                paint();
                say("Okay, nur " + b.min + " min. Kleiner Schritt, trotzdem geschafft.");
            } else {
                blocks.remove(b);
                save();
                paint();
            }
        });
    }

    /** Goals per week: tap a kind to cycle 0 … 7. */
    private void targetsSheet() {
        String[] rows = new String[KINDS.length + 1];
        for (int k = 0; k < KINDS.length; k++) rows[k] = emoji(k) + "  " + name(k) + " · " + target(k) + "× pro Woche  ＋";
        rows[KINDS.length] = "Fertig";
        sheet("Wochenziele (antippen = +1, nach 7 wieder 0)", rows, i -> {
            if (i == KINDS.length) {
                paint();
                return;
            }
            prefs.edit().putInt("target" + i, (target(i) + 1) % 8).apply();
            targetsSheet();
        });
    }

    /* ================= Views ================= */

    /** Done (solid) and planned (light) against the week's goal. */
    private final class Bar extends View {
        private final float done, planned;
        private final int col;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bar(float done, float planned, int col) {
            super(PlanActivity.this);
            this.done = Math.min(1, done);
            this.planned = Math.min(1, planned);
            this.col = col;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setColor(line);
            c.drawRoundRect(0, 0, w, h, r, r, p);
            p.setColor((col & 0x00FFFFFF) | 0x55000000);
            c.drawRoundRect(0, 0, w * planned, h, r, r, p);
            p.setColor(col);
            if (done > 0) c.drawRoundRect(0, 0, Math.max(h, w * done), h, r, r, p);
        }
    }

    /** The day: hours 7–22, blocks as cards, the "now" line. Tap empty = add, tap block = sheet. */
    private final class DayView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Object[]> hits = new ArrayList<>();      // RectF, Block

        DayView() {
            super(PlanActivity.this);
        }

        private float hourH() {
            return dp(60);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), left = dp(46), top = dp(8);
            long d0 = dayStart(shown.getTimeInMillis());
            hits.clear();
            // Mood band: how you usually feel at each hour of this weekday (green good … rust low).
            int dw = dow(d0 + 12 * 3600_000L);
            for (int h = DAY_FROM; h < DAY_TO; h++) {
                double m = predicted(dw, h);
                int col = m >= 3.6 ? 0xFF2BBF6A : m >= 3.0 ? 0xFFE8B931 : rust;
                p.setColor((col & 0x00FFFFFF) | 0x66000000);
                float y = top + (h - DAY_FROM) * hourH();
                c.drawRect(left - dp(8), y + dp(2), left - dp(4), y + hourH() - dp(2), p);
                p.setColor(line);
                c.drawRect(left, y, w, y + dp(1), p);
                p.setColor(ink3);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(10));
                c.drawText(String.format(Locale.ROOT, "%02d", h), 0, y + dp(12), p);
            }
            // Blocks.
            for (Block b : blocks) {
                if (b.state == 2 || dayStart(b.at) != d0) continue;
                Calendar c0 = Calendar.getInstance();
                c0.setTimeInMillis(b.at);
                float hh = c0.get(Calendar.HOUR_OF_DAY) + c0.get(Calendar.MINUTE) / 60f;
                float y = top + (hh - DAY_FROM) * hourH(), bh = Math.max(dp(34), b.min / 60f * hourH() - dp(3));
                RectF r = new RectF(left + dp(4), y + dp(2), w - dp(2), y + bh);
                hits.add(new Object[]{r, b});
                int col = colour(b.kind);
                p.setStyle(Paint.Style.FILL);
                p.setColor(b.state == 1 ? (col & 0x00FFFFFF) | 0x22000000 : (col & 0x00FFFFFF) | 0x38000000);
                c.drawRoundRect(r, dp(12), dp(12), p);
                p.setColor(col);
                c.drawRoundRect(r.left, r.top, r.left + dp(5), r.bottom, dp(3), dp(3), p);
                p.setColor(b.state == 1 ? ink3 : ink);
                p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                p.setTextSize(dp(15));
                String t = emoji(b.kind) + " " + name(b.kind) + (b.state == 1 ? "  ✓" : "");
                c.drawText(t, r.left + dp(14), r.top + dp(22), p);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(10));
                p.setColor(ink3);
                String sub = hm(b.at) + " · " + b.min + " MIN" + (b.moved > 0 ? " · ↻" + b.moved : "");
                if (bh > dp(44)) c.drawText(sub, r.left + dp(14), r.top + dp(38), p);
                else c.drawText(sub, r.right - p.measureText(sub) - dp(10), r.top + dp(22), p);
                if (b.state == 1) {
                    p.setColor(ink3);
                    c.drawRect(r.left + dp(14), r.top + dp(17), r.left + dp(14) + p.measureText(t) * 1.4f, r.top + dp(18.5f), p);
                }
            }
            // Now.
            if (d0 == dayStart(System.currentTimeMillis())) {
                Calendar n = Calendar.getInstance();
                float hh = n.get(Calendar.HOUR_OF_DAY) + n.get(Calendar.MINUTE) / 60f;
                if (hh >= DAY_FROM && hh <= DAY_TO) {
                    float y = top + (hh - DAY_FROM) * hourH();
                    p.setColor(rust);
                    c.drawRect(left, y - dp(1), w, y + dp(1), p);
                    c.drawCircle(left, y, dp(4), p);
                }
                postInvalidateDelayed(60_000);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            for (Object[] h : hits) {
                if (((RectF) h[0]).contains(e.getX(), e.getY())) {
                    blockSheet((Block) h[1]);
                    return true;
                }
            }
            float hh = DAY_FROM + (e.getY() - dp(8)) / hourH();
            if (hh < DAY_FROM || hh >= DAY_TO) return true;
            int half = (int) (hh * 2);
            long at = dayStart(shown.getTimeInMillis()) + half * 30 * 60_000L;
            addSheet(at);
            return true;
        }
    }

    /* ================= Helpers ================= */

    private TextView chip(String s, int col, boolean filled) {
        TextView t = mono(12, filled ? 0xFFFFFFFF : col);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        t.setBackground(box(filled ? rust : card, filled ? rust : line, 18));
        return t;
    }

    private LinearLayout.LayoutParams weighted(int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = right;
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(int sp, int col, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(col);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private TextView mono(int sp, int col) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(col);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1.5f), stroke);
        g.setCornerRadius(dp(r));
        return g;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        return p;
    }
}
