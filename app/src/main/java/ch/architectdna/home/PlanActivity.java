package ch.architectdna.home;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
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
import android.widget.EditText;
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

import static ch.architectdna.home.PlanStore.DAY_FROM;
import static ch.architectdna.home.PlanStore.DAY_TO;
import static ch.architectdna.home.PlanStore.KINDS;

/**
 * Flexplan: a plain day calendar made of preset blocks (learning, sport, rest …) and tasks with a
 * deadline (taxes!) that bends with how you feel. It asks live when a block starts ("jetzt?"), and
 * "Nicht jetzt" moves the block to a slot where your Stimmung log says you usually feel better –
 * still inside the week or before the deadline, so things get done, just at easier times.
 */
public class PlanActivity extends Activity {

    private static final String[] FACES = {"😣", "😕", "😐", "🙂", "😄"};
    /** Quick tasks: name, emoji, sessions, minutes each. */
    private static final Object[][] TASK_PRESETS = {
        {"Steuererklärung", "🧾", 4, 45},
        {"Rechnungen zahlen", "💸", 1, 30},
        {"Bewerbung schreiben", "📄", 3, 45},
        {"Wohnung putzen", "🧽", 2, 45},
        {"Papierkram / Post", "📬", 2, 30},
    };

    private PlanStore st;
    private int bg, card, line, ink, ink2, ink3, rust;
    private FrameLayout root;
    private LinearLayout days, goals, faces, nowBox, taskList;
    private TextView banner, dayTitle;
    private DayView day;
    private ScrollView sv;
    private final Calendar shown = Calendar.getInstance();
    private int feel = -1;
    private long feelAt;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        st = new PlanStore(this);
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
        feel = st.prefs.getInt("feel", -1);
        feelAt = st.prefs.getLong("feelAt", 0);
        if (!CalendarBusy.allowed(this)) requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 91);
        else loadBusy();

        sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(40));
        sv.addView(col);
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
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

        // What's on right now: answer it here like the notification.
        nowBox = new LinearLayout(this);
        nowBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(nowBox, margins(-1, -2, dp(10)));

        TextView q = text(28, ink, true);
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

        // Tasks with an end: the ones that otherwise get forgotten.
        LinearLayout th = new LinearLayout(this);
        th.setGravity(Gravity.CENTER_VERTICAL);
        TextView tl = mono(11, ink3);
        tl.setText("— AUFGABEN MIT FRIST");
        tl.setLetterSpacing(0.2f);
        th.addView(tl, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView addT = chip("＋ AUFGABE", rust, false);
        addT.setPadding(dp(12), dp(8), dp(12), dp(8));
        addT.setOnClickListener(v -> taskSheet());
        th.addView(addT);
        col.addView(th, margins(-1, -2, dp(22)));
        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        col.addView(taskList, margins(-1, -2, dp(8)));

        TextView gh = mono(11, ink3);
        gh.setText("— DIESE WOCHE");
        gh.setLetterSpacing(0.2f);
        col.addView(gh, margins(-1, -2, dp(22)));
        goals = new LinearLayout(this);
        goals.setOrientation(LinearLayout.VERTICAL);
        col.addView(goals, margins(-1, -2, dp(8)));

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

        TextView live = mono(11, ink3);
        live.setPadding(0, dp(18), 0, 0);
        col.addView(live);
        paintLiveToggle(live);
        live.setOnClickListener(v -> {
            st.prefs.edit().putBoolean("checkins", !st.prefs.getBoolean("checkins", true)).apply();
            st.arm();
            paintLiveToggle(live);
        });

        root = new FrameLayout(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        MoodActivity.statusScrim(root, bg);
        setContentView(root);
        if (st.blocks.isEmpty() && !st.prefs.getBoolean("seeded", false)) {
            st.prefs.edit().putBoolean("seeded", true).apply();
            autoPlan();
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        st.load();                        // a notification answer may have moved things
        st.forgetMood();
        st.arm();
        if (CalendarBusy.allowed(this)) loadBusy();
        paint();
    }

    private void paintLiveToggle(TextView t) {
        boolean on = st.prefs.getBoolean("checkins", true);
        t.setText(on ? "🔔 LIVE-NACHFRAGE AN · BEIM START: „JETZT?“  (ANTIPPEN = AUS)" : "🔕 LIVE-NACHFRAGE AUS  (ANTIPPEN = AN)");
        t.setTextColor(on ? rust : ink3);
    }

    /* ================= Actions ================= */

    private void setFeel(int m) {
        feel = m;
        feelAt = System.currentTimeMillis();
        st.prefs.edit().putInt("feel", m).putLong("feelAt", feelAt).apply();
        try {
            JSONArray a = MoodActivity.entries(this);
            a.put(new JSONObject().put("at", feelAt).put("m", m).put("n", "Flexplan"));
            getSharedPreferences("mood", MODE_PRIVATE).edit().putString("log", a.toString()).apply();
        } catch (Exception ignored) {
        }
        st.forgetMood();
        paintFaces();
        if (m <= 2) offerLighter();
        else if (m >= 4) say("Schön! Gute Zeit für was Schweres – " + nextHeavyHint());
        else banner.setVisibility(View.GONE);
    }

    private void offerLighter() {
        long now = System.currentTimeMillis();
        List<PlanStore.Block> heavy = new ArrayList<>();
        for (PlanStore.Block b : st.blocks)
            if (b.state == 0 && st.energy(b) >= 2 && b.at + b.min * 60_000L > now && b.at < now + 3 * 3600_000L) heavy.add(b);
        if (heavy.isEmpty()) {
            say("Okay. Nichts Schweres in den nächsten Stunden – gönn dir 🌿 Erholung.");
            return;
        }
        StringBuilder sb = new StringBuilder("Heute schwer? Ich schieb dir ");
        for (int i = 0; i < heavy.size(); i++) sb.append(i > 0 ? ", " : "").append(st.emoji(heavy.get(i))).append(' ').append(st.name(heavy.get(i)));
        sb.append(" auf Zeiten, wo's dir meist leichter fällt. Antippen = verschieben.");
        say(sb.toString());
        banner.setOnClickListener(v -> {
            StringBuilder done = new StringBuilder();
            for (PlanStore.Block b : heavy) {
                String r = st.notNow(b, false);
                if (r != null) done.append(r).append('\n');
            }
            st.save();
            paint();
            say(done.length() == 0 ? "Nichts Leichteres frei – vielleicht kürzer machen?" : done.toString().trim());
        });
    }

    private String nextHeavyHint() {
        long now = System.currentTimeMillis();
        for (PlanStore.Block b : st.blocks)
            if (b.state == 0 && st.energy(b) >= 2 && b.at > now - 30 * 60_000L && b.at < now + 4 * 3600_000L)
                return st.emoji(b) + " " + st.name(b) + " um " + hm(b.at) + " passt.";
        return "vielleicht jetzt was von den Aufgaben?";
    }

    /** Pull this week's busy times from the phone calendar so planning avoids them. */
    private java.util.List<CalendarBusy.Event> busyEvents = new java.util.ArrayList<>();

    private void loadBusy() {
        long from = PlanStore.weekStart(System.currentTimeMillis());
        long to = from + 8L * 24 * 3600_000L;
        busyEvents = CalendarBusy.between(this, from, to);
        st.busy.clear();
        for (CalendarBusy.Event e : busyEvents) st.busy.add(new long[]{e.start, e.end});
        if (day != null) day.invalidate();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 91 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            loadBusy();
            paint();
            say("Kalender verbunden – ich plane um deine Termine herum.");
        }
    }

    private void autoPlan() {
        loadBusy();
        int added = st.autoPlan();
        paint();
        say(added == 0 ? "Alles steht schon – Ziele und Aufgaben sind eingeplant."
            : "✨ " + added + " Blöcke eingeplant – schwere dorthin, wo du dich meist gut fühlst. Passt was nicht? Antippen → Nicht jetzt.");
    }

    /** Answer for the block that's on now (same as the notification buttons). */
    private void answer(PlanStore.Block b, int how) {
        String said;
        if (how == 0) {
            st.markDone(b);
            said = "Stark! " + st.emoji(b) + " eingetragen.";
        } else if (how == 2) {
            b.min = 10;
            st.markDone(b);
            said = "Nur 10 Minuten " + st.emoji(b) + " – kleinster Schritt, zählt trotzdem.";
        } else {
            String r = st.notNow(b, true);
            said = r != null ? r : "Nichts Leichteres frei – mach nur 10 Minuten?";
            if (r != null) shown.setTimeInMillis(b.at);
        }
        st.save();
        paint();
        say(said);
    }

    /* ================= Painting ================= */

    private void paint() {
        paintFaces();
        paintNow();
        paintTasks();
        days.removeAllViews();
        long ws = PlanStore.weekStart(System.currentTimeMillis());
        SimpleDateFormat d1 = new SimpleDateFormat("EE", Locale.GERMAN), d2 = new SimpleDateFormat("d.", Locale.GERMAN);
        for (int i = 0; i < 14; i++) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(ws + i * 24L * 3600_000 + 12 * 3600_000L);
            long d0 = PlanStore.dayStart(c.getTimeInMillis());
            boolean sel = PlanStore.dayStart(shown.getTimeInMillis()) == d0;
            boolean today = PlanStore.dayStart(System.currentTimeMillis()) == d0;
            int n = 0;
            for (PlanStore.Block b : st.blocks) if (b.state != 2 && PlanStore.dayStart(b.at) == d0) n++;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(12), dp(8), dp(12), dp(8));
            box.setBackground(box(sel ? rust : card, sel || today ? rust : line, 16));
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
        goals.removeAllViews();
        for (int k = 0; k < KINDS.length; k++) {
            int t = st.target(k);
            if (t == 0) continue;
            int done = st.countWeek(k, true), planned = st.countWeek(k, false);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView l = text(14, ink, false);
            l.setText(KINDS[k][1] + "  " + KINDS[k][2]);
            row.addView(l, new LinearLayout.LayoutParams(dp(150), -2));
            row.addView(new Bar(done / (float) t, planned / (float) t, (Integer) KINDS[k][5]), new LinearLayout.LayoutParams(0, dp(10), 1f));
            TextView c = mono(11, done >= t ? 0xFF2BBF6A : ink2);
            c.setText("  " + done + "/" + t + (done >= t ? " ✓" : ""));
            row.addView(c, new LinearLayout.LayoutParams(dp(56), -2));
            goals.addView(row, margins(-1, -2, dp(6)));
        }
        day.invalidate();
    }

    /** The block on right now, with the three answers. */
    private void paintNow() {
        nowBox.removeAllViews();
        PlanStore.Block b = st.current();
        if (b == null) return;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(box(card, st.colour(b), 22));
        TextView tag = mono(10, st.colour(b));
        tag.setText("JETZT · " + hm(b.at) + "–" + hm(b.at + b.min * 60_000L));
        tag.setLetterSpacing(0.2f);
        c.addView(tag);
        TextView t = text(22, ink, true);
        t.setText(st.emoji(b) + " " + st.name(b) + " – bist du dabei?");
        c.addView(t, margins(-1, -2, dp(6)));
        LinearLayout row = new LinearLayout(this);
        String[] opts = {"✓ MACH ICH", "😮‍💨 NOPE", "⏱ 10 MIN"};
        for (int i = 0; i < 3; i++) {
            TextView o = chip(opts[i], i == 0 ? rust : ink, i == 0);
            int how = i;
            o.setOnClickListener(v -> answer(b, how));
            row.addView(o, weighted(i < 2 ? dp(8) : 0));
        }
        c.addView(row, margins(-1, -2, dp(12)));
        nowBox.addView(c);
    }

    private void paintTasks() {
        taskList.removeAllViews();
        if (st.tasks.isEmpty()) {
            TextView none = text(14, ink3, false);
            none.setText("Steuern, Rechnungen, Bewerbung … alles mit Frist. Ich teil's in kleine Etappen und frag dich zur richtigen Zeit.");
            taskList.addView(none);
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("d. MMM", Locale.GERMAN);
        for (PlanStore.Task t : st.tasks) {
            boolean done = t.done >= t.sessions;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(box(card, done ? line : PlanStore.TASK_COLOUR, 18));
            row.setAlpha(done ? 0.6f : 1f);
            TextView n = text(17, ink, true);
            n.setText(t.emoji + " " + t.name + (done ? "  ✓" : ""));
            row.addView(n);
            PlanStore.Block next = st.nextFor(t);
            long left = t.due > 0 ? (t.due - System.currentTimeMillis()) / (24 * 3600_000L) : -1;
            TextView sub = mono(11, !done && t.due > 0 && left <= 3 ? rust : ink3);
            sub.setText(t.done + "/" + t.sessions + " ETAPPEN · " + t.min + " MIN" + (t.due > 0 ? " · FRIST " + f.format(new Date(t.due)).toUpperCase(Locale.GERMAN)
                + (t.due > System.currentTimeMillis() ? " (" + left + " T)" : " (ÜBERFÄLLIG)") : "")
                + (done ? " · GESCHAFFT" : next != null ? " · NÄCHSTE " + st.when(next.at).toUpperCase(Locale.GERMAN) : " · NOCH NICHT EINGEPLANT"));
            row.addView(sub, margins(-1, -2, dp(4)));
            row.setOnClickListener(v -> taskActions(t));
            taskList.addView(row, margins(-1, -2, dp(8)));
        }
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

    private FrameLayout openSheet;

    /** A bottom sheet with big rows (no Android dialog); 'extra' sits above the rows (e.g. a text field). */
    private void sheet(String title, View extra, String[] rows, Pick pick) {
        if (openSheet != null) root.removeView(openSheet);
        FrameLayout scrim = new FrameLayout(this);
        openSheet = scrim;
        scrim.setBackgroundColor(0x99000000);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setBackground(box(card, line, 26));
        box.setClickable(true);
        TextView t = text(20, ink, true);
        t.setText(title);
        box.addView(t);
        if (extra != null) box.addView(extra, margins(-1, -2, dp(10)));
        for (int i = 0; i < rows.length; i++) {
            TextView r = text(16, ink, false);
            r.setText(rows[i]);
            r.setPadding(dp(16), dp(14), dp(16), dp(14));
            r.setBackground(box(bg, line, 16));
            int k = i;
            r.setOnClickListener(v -> {
                root.removeView(scrim);
                if (openSheet == scrim) openSheet = null;
                pick.on(k);
            });
            box.addView(r, margins(-1, -2, dp(8)));
        }
        int nav = 0;
        if (Build.VERSION.SDK_INT >= 30 && root.getRootWindowInsets() != null)
            nav = root.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()).bottom;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(dp(10), dp(60), dp(10), dp(10) + nav);
        ScrollView ss = new ScrollView(this);
        ss.addView(box);
        scrim.addView(ss, lp);
        scrim.setOnClickListener(v -> {
            root.removeView(scrim);
            if (openSheet == scrim) openSheet = null;
        });
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        box.setTranslationY(dp(300));
        box.animate().translationY(0).setDuration(240).start();
    }

    private void addSheet(long at) {
        String[] rows = new String[KINDS.length];
        for (int k = 0; k < KINDS.length; k++) rows[k] = KINDS[k][1] + "  " + KINDS[k][2] + " · " + KINDS[k][3] + " min";
        sheet(at < 0 ? "Was planen? (ich such die beste Zeit)" : "Um " + hm(at) + ":", null, rows, k -> {
            int min = (Integer) KINDS[k][3];
            long t = at;
            if (t < 0) {
                long now = System.currentTimeMillis(), d0 = PlanStore.dayStart(shown.getTimeInMillis());
                t = st.bestSlot(k, 0, (Integer) KINDS[k][4], min, Math.max(now, d0), PlanStore.weekStart(now) + 14L * 24 * 3600_000, Math.max(now, d0 + 9 * 3600_000L), null);
                if (t < 0) {
                    say("Kein freier Platz gefunden – nimm einen Tag mit weniger Punkten.");
                    return;
                }
            }
            PlanStore.Block b = new PlanStore.Block();
            b.id = System.nanoTime();
            b.at = t;
            b.kind = k;
            b.min = min;
            st.blocks.add(b);
            st.save();
            shown.setTimeInMillis(t);
            paint();
            if (at < 0) say(KINDS[k][1] + " " + KINDS[k][2] + " → " + st.when(t)
                + String.format(Locale.GERMAN, " (da meist Ø %.1f).", st.predicted(PlanStore.dow(t), PlanStore.hour(t))));
        });
    }

    private void blockSheet(PlanStore.Block b) {
        String title = st.emoji(b) + " " + st.name(b) + " · " + st.when(b.at) + "–" + hm(b.at + b.min * 60_000L);
        if (b.state == 1) {
            sheet(title, null, new String[]{"↺ Doch nicht erledigt", "🗑 Entfernen"}, i -> {
                if (i == 0) st.unDone(b);
                else st.blocks.remove(b);
                st.save();
                paint();
            });
            return;
        }
        sheet(title, null, new String[]{"✓ Erledigt", "😮‍💨 Nicht jetzt – leichtere Zeit finden", "⏱ Kürzer: nur " + Math.max(10, b.min / 2) + " min", "🗑 Entfernen"}, i -> {
            if (i == 0) {
                st.markDone(b);
                st.save();
                paint();
                say(b.task != 0 ? "Stark! Eine Etappe weniger." : "Stark! " + st.emoji(b) + " " + st.countWeek(b.kind, true) + "/" + st.target(b.kind) + " diese Woche.");
            } else if (i == 1) {
                String r = st.notNow(b, true);
                st.save();
                if (r != null) shown.setTimeInMillis(b.at);
                paint();
                say(r != null ? r : "Kein besserer Platz frei. Mach's kürzer – 10 Minuten zählen auch.");
            } else if (i == 2) {
                b.min = Math.max(10, b.min / 2);
                st.save();
                paint();
                say("Okay, nur " + b.min + " min. Kleiner Schritt, trotzdem geschafft.");
            } else {
                st.blocks.remove(b);
                st.save();
                paint();
            }
        });
    }

    /** New task: pick a preset or type your own, then the deadline. */
    private void taskSheet() {
        EditText name = new EditText(this);
        name.setHint("Eigene Aufgabe … (z. B. Steuern Kanton Bern)");
        name.setTextColor(ink);
        name.setHintTextColor(ink3);
        name.setSingleLine(true);
        name.setBackground(box(bg, line, 14));
        name.setPadding(dp(14), dp(12), dp(14), dp(12));
        String[] rows = new String[TASK_PRESETS.length + 1];
        rows[0] = "→ Eigene Aufgabe übernehmen (3 Etappen à 30 min)";
        for (int i = 0; i < TASK_PRESETS.length; i++)
            rows[i + 1] = TASK_PRESETS[i][1] + "  " + TASK_PRESETS[i][0] + " · " + TASK_PRESETS[i][2] + "× " + TASK_PRESETS[i][3] + " min";
        sheet("Was steht an?", name, rows, i -> {
            String n, e;
            int ses, min;
            if (i == 0) {
                n = name.getText().toString().trim();
                if (n.isEmpty()) {
                    say("Gib der Aufgabe einen Namen – oder nimm eine Vorlage.");
                    return;
                }
                e = n.toLowerCase(Locale.GERMAN).contains("steuer") ? "🧾" : "📌";
                ses = 3;
                min = 30;
            } else {
                Object[] p = TASK_PRESETS[i - 1];
                n = (String) p[0];
                e = (String) p[1];
                ses = (Integer) p[2];
                min = (Integer) p[3];
            }
            dueSheet(n, e, ses, min);
        });
    }

    private void dueSheet(String n, String e, int ses, int min) {
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        long weekEnd = PlanStore.weekStart(now) + 7L * 24 * 3600_000 - 2 * 3600_000L;
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        c.set(Calendar.HOUR_OF_DAY, 20);
        long monthEnd = c.getTimeInMillis();
        long[] dues = {weekEnd, now + 14L * 24 * 3600_000, monthEnd, now + 60L * 24 * 3600_000, 0};
        SimpleDateFormat f = new SimpleDateFormat("EE d. MMM", Locale.GERMAN);
        String[] rows = {"Diese Woche (" + f.format(new Date(weekEnd)) + ")", "In 2 Wochen", "Ende Monat (" + f.format(new Date(monthEnd)) + ")",
            "In 2 Monaten", "Keine Frist – in den nächsten 10 Tagen verteilen"};
        sheet(e + " " + n + " – bis wann?", null, rows, i -> {
            PlanStore.Task t = new PlanStore.Task();
            t.id = System.nanoTime();
            t.name = n;
            t.emoji = e;
            t.sessions = ses;
            t.min = min;
            t.due = dues[i];
            st.tasks.add(t);
            int added = st.planTasks();
            st.save();
            paint();
            PlanStore.Block next = st.nextFor(t);
            say(e + " " + n + ": " + added + " Etappen eingeplant" + (next != null ? ", erste " + st.when(next.at) : "")
                + ". Ich frag dich dann live – „Nope“ schiebt sie in eine leichtere Zeit, die Frist bleibt im Blick.");
        });
    }

    private void taskActions(PlanStore.Task t) {
        sheet(t.emoji + " " + t.name, null, new String[]{"＋ Eine Etappe mehr", "✨ Fehlende Etappen neu einplanen", "✓ Ganz erledigt", "🗑 Aufgabe löschen"}, i -> {
            if (i == 0) {
                t.sessions++;
                st.planTasks();
            } else if (i == 1) {
                st.planTasks();
            } else if (i == 2) {
                t.done = t.sessions;
                for (PlanStore.Block b : st.blocks) if (b.task == t.id && b.state == 0) b.state = 2;
                say("🎉 " + t.name + " erledigt! Endlich vom Tisch.");
            } else {
                st.tasks.remove(t);
                st.blocks.removeIf(b -> b.task == t.id && b.state != 1);
            }
            st.save();
            paint();
        });
    }

    private void targetsSheet() {
        String[] rows = new String[KINDS.length + 1];
        for (int k = 0; k < KINDS.length; k++) rows[k] = KINDS[k][1] + "  " + KINDS[k][2] + " · " + st.target(k) + "× pro Woche  ＋";
        rows[KINDS.length] = "Fertig";
        sheet("Wochenziele (antippen = +1, nach 7 wieder 0)", null, rows, i -> {
            if (i == KINDS.length) {
                paint();
                return;
            }
            st.prefs.edit().putInt("target" + i, (st.target(i) + 1) % 8).apply();
            targetsSheet();
        });
    }

    /* ================= Views ================= */

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

    /** The day: hours 7–22 with a mood strip, blocks as cards, the "now" line. */
    private final class DayView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Object[]> hits = new ArrayList<>();
        private float downY;

        DayView() {
            super(PlanActivity.this);
        }

        private float hourH() {
            return dp(60);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), left = dp(46), top = dp(8);
            long d0 = PlanStore.dayStart(shown.getTimeInMillis());
            hits.clear();
            int dw = PlanStore.dow(d0 + 12 * 3600_000L);
            p.setStyle(Paint.Style.FILL);
            for (int h = DAY_FROM; h < DAY_TO; h++) {
                double m = st.predicted(dw, h);
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
            // Busy times from the phone calendar: grey bands behind the blocks, so you plan around them.
            for (CalendarBusy.Event ev : busyEvents) {
                long es = Math.max(ev.start, d0), ee = Math.min(ev.end, d0 + 24 * 3600_000L);
                if (ee <= d0 || es >= d0 + 24 * 3600_000L) continue;
                Calendar ce = Calendar.getInstance();
                ce.setTimeInMillis(es);
                float sh = ev.allDay ? DAY_FROM : ce.get(Calendar.HOUR_OF_DAY) + ce.get(Calendar.MINUTE) / 60f;
                float eh = ev.allDay ? DAY_TO : sh + (ee - es) / 3600_000f;
                sh = Math.max(DAY_FROM, sh);
                eh = Math.min(DAY_TO, eh);
                if (eh <= sh) continue;
                float y = top + (sh - DAY_FROM) * hourH(), bh = Math.max(dp(20), (eh - sh) * hourH());
                RectF r = new RectF(left + dp(4), y + dp(1), w - dp(2), y + bh - dp(1));
                p.setColor((ink3 & 0x00FFFFFF) | 0x24000000);
                c.drawRoundRect(r, dp(8), dp(8), p);
                p.setColor(ink3);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(10));
                String t = "📅 " + ev.title;
                if (p.measureText(t) > r.width() - dp(16)) {
                    while (t.length() > 4 && p.measureText(t + "…") > r.width() - dp(16)) t = t.substring(0, t.length() - 1);
                    t += "…";
                }
                c.drawText(t, r.left + dp(10), r.top + dp(14), p);
            }
            for (PlanStore.Block b : st.blocks) {
                if (b.state == 2 || PlanStore.dayStart(b.at) != d0) continue;
                Calendar c0 = Calendar.getInstance();
                c0.setTimeInMillis(b.at);
                float hh = c0.get(Calendar.HOUR_OF_DAY) + c0.get(Calendar.MINUTE) / 60f;
                float y = top + (hh - DAY_FROM) * hourH(), bh = Math.max(dp(34), b.min / 60f * hourH() - dp(3));
                RectF r = new RectF(left + dp(4), y + dp(2), w - dp(2), y + bh);
                hits.add(new Object[]{r, b});
                int col = st.colour(b);
                p.setColor(b.state == 1 ? (col & 0x00FFFFFF) | 0x22000000 : (col & 0x00FFFFFF) | 0x38000000);
                c.drawRoundRect(r, dp(12), dp(12), p);
                p.setColor(col);
                c.drawRoundRect(r.left, r.top, r.left + dp(5), r.bottom, dp(3), dp(3), p);
                p.setColor(b.state == 1 ? ink3 : ink);
                p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                p.setTextSize(dp(15));
                String t = st.emoji(b) + " " + st.name(b) + (b.state == 1 ? "  ✓" : "");
                c.drawText(t, r.left + dp(14), r.top + dp(22), p);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(10));
                p.setColor(ink3);
                String sub = hm(b.at) + " · " + b.min + " MIN" + (b.moved > 0 ? " · ↻" + b.moved : "");
                if (bh > dp(44)) c.drawText(sub, r.left + dp(14), r.top + dp(38), p);
                else c.drawText(sub, r.right - p.measureText(sub) - dp(10), r.top + dp(22), p);
            }
            if (d0 == PlanStore.dayStart(System.currentTimeMillis())) {
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
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downY = e.getY();
                return true;
            }
            if (e.getActionMasked() != MotionEvent.ACTION_UP || Math.abs(e.getY() - downY) > dp(10)) return true;
            for (Object[] h : hits) {
                if (((RectF) h[0]).contains(e.getX(), e.getY())) {
                    blockSheet((PlanStore.Block) h[1]);
                    return true;
                }
            }
            float hh = DAY_FROM + (e.getY() - dp(8)) / hourH();
            if (hh < DAY_FROM || hh >= DAY_TO) return true;
            int half = (int) (hh * 2);
            addSheet(PlanStore.dayStart(shown.getTimeInMillis()) + half * 30 * 60_000L);
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
