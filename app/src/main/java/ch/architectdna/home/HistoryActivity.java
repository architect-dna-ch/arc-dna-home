package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Musik-Verlauf: every song you started, newest first and grouped by day, or the TOP list of the
 * most played. Tap a song to play it again in the app it came from. Kept on the phone only
 * (MusicStats writes it from the media sessions).
 */
public class HistoryActivity extends Activity {

    private int bg, ink, ink2, ink3, rust, card, line;
    private LinearLayout list;
    private TextView tabRecent, tabTop, tabAnimals;
    private List<String[]> plays;
    private final Map<String, Integer> counts = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        ink = night ? 0xFFECEBE6 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA9A8A2 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFC9402F : 0xFFB5271E;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C29 : 0xFFE0E0D8;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        plays = MusicStats.history(this);
        for (String[] p : plays) counts.merge(key(p), 1, Integer::sum);
        setContentView(build());
        showRecent();
    }

    private static String key(String[] p) {
        return p[1] + "\u0001" + p[2];
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView mono(float sp, int colour) {
        TextView t = text(sp, colour, true);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.16f);
        return t;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(8));
        return d;
    }

    private View sectionLabel(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackgroundColor(rust);
        row.addView(bar, new LinearLayout.LayoutParams(dp(22), dp(2)));
        TextView t = mono(11, ink3);
        t.setLetterSpacing(0.2f);
        t.setText(s.toUpperCase(Locale.GERMAN));
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private View build() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(bg);
        frame.addView(new View(this) {
            private final Paint p = new Paint();

            @Override
            protected void onDraw(Canvas c) {
                p.setColor((ink & 0x00FFFFFF) | 0x0F000000);
                p.setStrokeWidth(dp(1));
                float cell = dp(32);
                for (float x = 0; x < getWidth(); x += cell) c.drawLine(x, 0, x, getHeight(), p);
                for (float y = 0; y < getHeight(); y += cell) c.drawLine(0, y, getWidth(), y, p);
            }
        }, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout safe = new FrameLayout(this);
        safe.setFitsSystemWindows(true);
        frame.addView(safe, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(10));
        safe.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(sectionLabel("Architect · DNA · Musik-Verlauf"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> finish());
        top.addView(close);
        root.addView(top);

        TextView big = text(34, ink, true);
        big.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        big.setLetterSpacing(-0.04f);
        big.setText(plays.size() + " Titel");
        big.setPadding(0, dp(16), 0, 0);
        root.addView(big);
        TextView sub = mono(10, ink3);
        int favs = getSharedPreferences("favourites", MODE_PRIVATE).getAll().size();
        sub.setText(counts.size() + " VERSCHIEDENE · ★ " + favs + " FAVORITEN · NUR AUF DEM HANDY");
        root.addView(sub);

        LinearLayout tabs = new LinearLayout(this);
        tabRecent = tab("VERLAUF");
        tabTop = tab("TOP");
        tabRecent.setOnClickListener(v -> showRecent());
        tabTop.setOnClickListener(v -> showTop());
        tabs.addView(tabRecent);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(8);
        tabs.addView(tabTop, tl);
        tabAnimals = tab("TIERE");
        tabAnimals.setOnClickListener(v -> showAnimals());
        LinearLayout.LayoutParams tl2 = new LinearLayout.LayoutParams(-2, -2);
        tl2.leftMargin = dp(8);
        tabs.addView(tabAnimals, tl2);
        LinearLayout.LayoutParams tsl = new LinearLayout.LayoutParams(-1, -2);
        tsl.topMargin = dp(16);
        root.addView(tabs, tsl);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(24));
        sv.addView(list);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, 0, 1f);
        svl.topMargin = dp(8);
        root.addView(sv, svl);
        return frame;
    }

    private TextView tab(String s) {
        TextView t = mono(11, ink2);
        t.setText(s);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        return t;
    }

    private void paintTabs(boolean recent) {
        paintTab(recent ? tabRecent : tabTop);
    }

    private void paintTab(TextView on) {
        for (TextView t : new TextView[]{tabRecent, tabTop, tabAnimals}) {
            boolean active = t == on;
            t.setTextColor(active ? 0xFFFFFFFF : ink2);
            t.setBackground(active ? box(rust, 0) : box(card, line));
        }
    }

    /* ---- TIERE: every song sorted to a lock screen character by its remembered phase map ---- */

    private static final String[][] ANIMALS = {
        {"🏎", "F1-Racer", "Energie, Builds und Drops"},
        {"🐻", "Bär in der Höhle", "ruhig, tief, viele Breaks"},
        {"🦊", "Fuchs", "Stimme vorne, Flow"},
        {"🐰", "Hase", "leicht und verspielt, Groove"},
        {"?", "Noch nicht gehört", "Phasen werden beim nächsten Hören gelernt"}};

    /** Phase shares {groove, build, drop, break, vocal} from the per-song map, or null if too little was heard. */
    private float[] shares(String[] p) {
        String saved = getSharedPreferences("phases2", MODE_PRIVATE).getString(key(p), null);
        if (saved == null) return null;
        String[] parts = saved.split(";", 2);
        if (parts.length < 2) return null;
        String[] cells = parts[1].split(",", -1);
        float[] sum = new float[5];
        int heard = 0;
        for (int sl = 0; sl * 5 + 4 < cells.length; sl++) {
            int best = -1, bestVotes = 0;
            for (int ph = 0; ph < 5; ph++) {
                String c = cells[sl * 5 + ph];
                int v;
                try {
                    v = c.isEmpty() ? 0 : Integer.parseInt(c, 36);
                } catch (NumberFormatException e) {
                    v = 0;
                }
                if (v > bestVotes) {
                    bestVotes = v;
                    best = ph;
                }
            }
            if (best >= 0) {
                sum[best]++;
                heard++;
            }
        }
        if (heard < 12) return null;                // under ~10 % of the song: not enough to judge
        for (int i = 0; i < 5; i++) sum[i] /= heard;
        return sum;
    }

    private static int animal(float[] f) {
        if (f == null) return 4;
        float energy = f[1] + f[2], calm = f[3], vocal = f[4];
        if (energy >= 0.28f && energy >= calm) return 0;   // F1
        if (calm >= 0.22f) return 1;                       // bear
        if (vocal >= 0.32f) return 2;                      // fox
        return 3;                                          // bunny
    }

    private void showAnimals() {
        paintTab(tabAnimals);
        list.removeAllViews();
        Map<String, String[]> latest = new HashMap<>();
        for (String[] p : plays) latest.putIfAbsent(key(p), p);
        List<List<String[]>> groups = new ArrayList<>();
        List<Map<String, float[]>> why = new ArrayList<>();
        for (int i = 0; i < ANIMALS.length; i++) {
            groups.add(new ArrayList<>());
            why.add(new HashMap<>());
        }
        for (String[] p : latest.values()) {
            float[] f = shares(p);
            int a = animal(f);
            groups.get(a).add(p);
            if (f != null) why.get(a).put(key(p), f);
        }
        for (int a = 0; a < ANIMALS.length; a++) {
            List<String[]> g = groups.get(a);
            if (g.isEmpty()) continue;
            Collections.sort(g, (x, y) -> counts.get(key(y)) - counts.get(key(x)));
            // Header: big animal, name, what fits it, how many songs.
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon = text(30, ink, false);
            icon.setText(ANIMALS[a][0]);
            head.addView(icon);
            LinearLayout ht = new LinearLayout(this);
            ht.setOrientation(LinearLayout.VERTICAL);
            ht.setPadding(dp(12), 0, 0, 0);
            TextView name = text(17, ink, true);
            name.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            name.setText(ANIMALS[a][1] + "  ·  " + g.size());
            ht.addView(name);
            TextView what = mono(10, ink3);
            what.setText(ANIMALS[a][2].toUpperCase(Locale.GERMAN));
            ht.addView(what);
            head.addView(ht);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
            hl.topMargin = dp(18);
            list.addView(head, hl);
            LinearLayout group = new LinearLayout(this);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setBackground(box(card, a == 4 ? line : rust));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
            gl.topMargin = dp(8);
            list.addView(group, gl);
            for (int i = 0; i < g.size(); i++) {
                if (i > 0) {
                    View div = new View(this);
                    div.setBackgroundColor(line);
                    group.addView(div, new LinearLayout.LayoutParams(-1, dp(1)));
                }
                LinearLayout wrap = new LinearLayout(this);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.addView(row(ANIMALS[a][0], g.get(i)));
                float[] f = why.get(a).get(key(g.get(i)));
                if (f != null) wrap.addView(mix(f), new LinearLayout.LayoutParams(-1, dp(4)));
                group.addView(wrap);
            }
        }
    }

    /** A thin bar of the song's phase shares (grey groove, orange build, red drop, blue break, green vocal). */
    private View mix(float[] f) {
        int[] colours = {0xFF8A8A86, 0xFFF29A1F, 0xFFE0281E, 0xFF3C8DFF, 0xFF2BBF6A};
        return new View(this) {
            private final Paint p = new Paint();

            @Override
            protected void onDraw(Canvas c) {
                float x = dp(64), w = getWidth() - x - dp(12);
                for (int i = 0; i < 5; i++) {
                    p.setColor(colours[i]);
                    c.drawRect(x, 0, x + w * f[i], getHeight(), p);
                    x += w * f[i];
                }
            }
        };
    }

    /** Newest first, a card per day. */
    private void showRecent() {
        paintTabs(true);
        list.removeAllViews();
        if (plays.isEmpty()) {
            TextView none = text(14, ink2, false);
            none.setText("Noch nichts gehört. Spiel einen Titel ab – er erscheint hier (Benachrichtigungszugriff nötig).");
            list.addView(none);
            return;
        }
        String day = null;
        LinearLayout group = null;
        for (String[] p : plays) {
            long t = parse(p[0]);
            String d = dayLabel(t);
            if (!d.equals(day)) {
                day = d;
                View label = sectionLabel(d);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
                ll.topMargin = dp(16);
                list.addView(label, ll);
                group = new LinearLayout(this);
                group.setOrientation(LinearLayout.VERTICAL);
                group.setBackground(box(card, line));
                LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
                gl.topMargin = dp(8);
                list.addView(group, gl);
            } else {
                View div = new View(this);
                div.setBackgroundColor(line);
                group.addView(div, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            group.addView(row(new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(t)), p));
        }
    }

    /** Most played, with counts. */
    private void showTop() {
        paintTabs(false);
        list.removeAllViews();
        Map<String, String[]> latest = new HashMap<>();
        for (String[] p : plays) latest.putIfAbsent(key(p), p);
        List<String[]> top = new ArrayList<>(latest.values());
        Collections.sort(top, (a, b) -> counts.get(key(b)) - counts.get(key(a)));
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(box(card, line));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = dp(12);
        list.addView(group, gl);
        for (int i = 0; i < Math.min(50, top.size()); i++) {
            if (i > 0) {
                View div = new View(this);
                div.setBackgroundColor(line);
                group.addView(div, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            group.addView(row(String.format(Locale.ROOT, "%02d", i + 1), top.get(i)));
        }
    }

    private View row(String lead, String[] p) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView time = mono(11, rust);
        time.setText(lead);
        r.addView(time, new LinearLayout.LayoutParams(dp(52), -2));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = text(15, ink, true);
        boolean fav = MusicStats.isFavourite(this, p[1], p[2]);
        t1.setText((fav ? "★ " : "") + (p[1].isEmpty() ? "–" : p[1]));
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t1);
        TextView t2 = mono(10, ink3);
        t2.setLetterSpacing(0.08f);
        t2.setText((p[2].isEmpty() ? "" : p[2] + " · ") + appName(p[3]).toUpperCase(Locale.GERMAN));
        t2.setSingleLine(true);
        t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t2);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView n = mono(12, ink2);
        n.setText(counts.get(key(p)) + "×");
        n.setPadding(dp(8), 0, 0, 0);
        r.addView(n);
        r.setOnClickListener(v -> playAgain(p));
        return r;
    }

    /** Ask the app the song came from to find and play it (works with YouTube Music, Spotify & co.). */
    private void playAgain(String[] p) {
        String query = (p[1] + " " + p[2]).trim();
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_TITLE, p[1])
            .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, p[2])
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!p[3].isEmpty()) i.setPackage(p[3]);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            try {
                startActivity(i.setPackage(null));
            } catch (RuntimeException e2) {
                Toast.makeText(this, "Keine Musik-App kann danach suchen.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String appName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String dayLabel(long t) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int days = (now.get(Calendar.YEAR) - c.get(Calendar.YEAR)) * 366 + now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR);
        if (days == 0) return "Heute";
        if (days == 1) return "Gestern";
        return new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(new Date(t));
    }
}
