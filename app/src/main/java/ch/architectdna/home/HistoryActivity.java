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
    private List<String[]> plays, all;
    private final Map<String, Integer> counts = new HashMap<>();

    /* ---- Search and filter layers: up to 10 stacked filters, each narrowing the selection ---- */

    private static final String[] CATEGORIES = {"KÜNSTLER", "APP", "TAGESZEIT", "WOCHENTAG", "TAG", "TIER", "★"};
    private final List<String[]> layers = new ArrayList<>();    // {category, value, label}
    private String query = "";
    private int mode;                                             // 0 Verlauf, 1 Top, 2 Tiere
    private LinearLayout crumbs;
    private View globe;
    private final Map<String, Integer> animalOf = new HashMap<>();

    private String valueOf(String cat, String[] p) {
        long t = parse(p[0]);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        switch (cat) {
            case "KÜNSTLER":
                return p[2].isEmpty() ? "(ohne Künstler)" : p[2];
            case "APP":
                return appName(p[3]);
            case "TAGESZEIT": {
                int h = c.get(Calendar.HOUR_OF_DAY);
                return h >= 5 && h < 11 ? "Morgen" : h < 14 && h >= 11 ? "Mittag" : h >= 14 && h < 18 ? "Nachmittag" : h >= 18 && h < 22 ? "Abend" : "Nacht";
            }
            case "WOCHENTAG":
                return new SimpleDateFormat("EEEE", Locale.GERMAN).format(new Date(t));
            case "TAG":
                return dayLabel(t);
            case "TIER":
                return ANIMALS[animalIndex(p)][1];
            default:
                return MusicStats.isFavourite(this, p[1], p[2]) ? "Favoriten" : "Ohne Stern";
        }
    }

    private int animalIndex(String[] p) {
        Integer a = animalOf.get(key(p));
        if (a == null) {
            a = animal(shares(p));
            animalOf.put(key(p), a);
        }
        return a;
    }

    private boolean matches(String[] p) {
        if (!query.isEmpty()) {
            String q = query.toLowerCase(Locale.GERMAN), hay = (p[1] + " " + p[2] + " " + appName(p[3])).toLowerCase(Locale.GERMAN);
            if (!hay.contains(q)) return false;
        }
        for (String[] l : layers) if (!valueOf(l[0], p).equals(l[1])) return false;
        return true;
    }

    /** Re-filter and redraw the current tab, the breadcrumb of layers and the globe. */
    private void refresh() {
        plays = new ArrayList<>();
        for (String[] p : all) if (matches(p)) plays.add(p);
        paintCrumbs();
        if (globe != null) globe.invalidate();
        if (mode == 1) showTop();
        else if (mode == 2) showAnimals();
        else showRecent();
    }

    private void paintCrumbs() {
        crumbs.removeAllViews();
        TextView total = mono(11, ink2);
        total.setText(String.valueOf(all.size()));
        crumbs.addView(total);
        List<String[]> step = new ArrayList<>(all);
        for (int i = 0; i < layers.size(); i++) {
            String[] l = layers.get(i);
            List<String[]> next = new ArrayList<>();
            for (String[] p : step) if (valueOf(l[0], p).equals(l[1])) next.add(p);
            step = next;
            TextView arrow = mono(11, ink3);
            arrow.setText(" → ");
            crumbs.addView(arrow);
            TextView chip = mono(10, 0xFFFFFFFF);
            chip.setText("✕ " + l[2] + " · " + step.size());
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setBackground(box(rust, 0));
            int idx = i;
            chip.setOnClickListener(v -> {
                layers.remove(idx);
                refresh();
            });
            crumbs.addView(chip);
        }
        if (!query.isEmpty() || !layers.isEmpty()) {
            TextView now = mono(11, rust);
            now.setText("  = " + plays.size());
            crumbs.addView(now);
        }
    }

    /** Pick a value of a category among what's still selected (most frequent first). */
    private void pickLayer(String cat) {
        if (layers.size() >= 10) {
            Toast.makeText(this, "Maximal 10 Filter-Ebenen", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, Integer> n = new HashMap<>();
        for (String[] p : plays) n.merge(valueOf(cat, p), 1, Integer::sum);
        List<Map.Entry<String, Integer>> e = new ArrayList<>(n.entrySet());
        Collections.sort(e, (a, b) -> b.getValue() - a.getValue());
        int size = Math.min(40, e.size());
        String[] labels = new String[size];
        for (int i = 0; i < size; i++) labels[i] = e.get(i).getKey() + "  ·  " + e.get(i).getValue();
        new android.app.AlertDialog.Builder(this)
            .setTitle(cat + " · Ebene " + (layers.size() + 1) + " / 10")
            .setItems(labels, (d, w) -> {
                String v = e.get(w).getKey();
                layers.add(new String[]{cat, v, cat.equals("★") ? v : v});
                refresh();
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

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
        all = dedupe(MusicStats.history(this));
        plays = all;
        for (String[] p : all) counts.merge(key(p), 1, Integer::sum);
        setContentView(build());
        refresh();
    }

    /** Older logs have a song twice: once without the artist, a moment later with it. Keep the one with the artist. */
    private static List<String[]> dedupe(List<String[]> in) {
        List<String[]> out = new ArrayList<>();
        for (String[] p : in) {
            if (!out.isEmpty()) {
                String[] prev = out.get(out.size() - 1);
                boolean close = Math.abs(parse(prev[0]) - parse(p[0])) < 90_000 && prev[1].equals(p[1]);
                if (close && (prev[2].isEmpty() || p[2].isEmpty())) {
                    if (prev[2].isEmpty()) out.set(out.size() - 1, p);
                    continue;
                }
            }
            out.add(p);
        }
        return out;
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
        big.setText(all.size() + " Titel");
        big.setPadding(0, dp(16), 0, 0);
        root.addView(big);
        TextView sub = mono(10, ink3);
        int favs = getSharedPreferences("favourites", MODE_PRIVATE).getAll().size();
        sub.setText(counts.size() + " VERSCHIEDENE · ★ " + favs + " FAVORITEN" + (BtSync.peerName(this) != null ? " · HIER + " + BtSync.peerName(this).toUpperCase(java.util.Locale.GERMAN) : ""));
        root.addView(sub);

        LinearLayout tabs = new LinearLayout(this);
        tabRecent = tab("VERLAUF");
        tabTop = tab("TOP");
        tabRecent.setOnClickListener(v -> {
            mode = 0;
            showRecent();
        });
        tabTop.setOnClickListener(v -> {
            mode = 1;
            showTop();
        });
        tabs.addView(tabRecent);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(8);
        tabs.addView(tabTop, tl);
        tabAnimals = tab("TIERE");
        tabAnimals.setOnClickListener(v -> {
            mode = 2;
            showAnimals();
        });
        LinearLayout.LayoutParams tl2 = new LinearLayout.LayoutParams(-2, -2);
        tl2.leftMargin = dp(8);
        tabs.addView(tabAnimals, tl2);
        LinearLayout.LayoutParams tsl = new LinearLayout.LayoutParams(-1, -2);
        tsl.topMargin = dp(16);
        root.addView(tabs, tsl);

        // Search, the globe of all plays (the selection lights up, the rest fades), categories, layers.
        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("Titel, Künstler, App suchen…");
        search.setHintTextColor(ink3);
        search.setTextColor(ink);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setBackground(box(card, line));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim();
                refresh();
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(12);
        root.addView(search, sl);
        root.setFocusableInTouchMode(true);     // no keyboard popping up on open
        root.requestFocus();
        globe = new PlayGlobe();
        root.addView(globe, new LinearLayout.LayoutParams(-1, dp(150)));
        android.widget.HorizontalScrollView cats = new android.widget.HorizontalScrollView(this);
        cats.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow = new LinearLayout(this);
        for (String cat : CATEGORIES) {
            TextView ch = mono(10, ink2);
            ch.setText("+ " + cat);
            ch.setPadding(dp(12), dp(7), dp(12), dp(7));
            ch.setBackground(box(card, line));
            ch.setOnClickListener(v -> pickLayer(cat));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.rightMargin = dp(6);
            catRow.addView(ch, cl);
        }
        cats.addView(catRow);
        root.addView(cats);
        android.widget.HorizontalScrollView crumbScroll = new android.widget.HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbs = new LinearLayout(this);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbScroll.addView(crumbs);
        LinearLayout.LayoutParams crl = new LinearLayout.LayoutParams(-1, -2);
        crl.topMargin = dp(8);
        root.addView(crumbScroll, crl);

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
        {"🐻", "Bär in der Höhle", "ruhig, tief, Breaks und Instrumentals"},
        {"🦊", "Fuchs", "Stimme vorne, Flow"},
        {"🐰", "Hase", "leicht und verspielt, Groove"},
        {"?", "Noch nicht gehört", "Phasen werden beim nächsten Hören gelernt"}};

    /** Phase shares {groove, build, drop, break, vocal, instrumental} from the per-song map, or null if too little was heard. */
    private float[] shares(String[] p) {
        String saved = getSharedPreferences("phases2", MODE_PRIVATE).getString(key(p), null);
        if (saved == null) return null;
        // "plays;6;cells" (with INSTRUMENTAL) or the older "plays;cells" with 5 phases.
        String[] parts = saved.split(";", 3);
        if (parts.length < 2) return null;
        int stride;
        try {
            stride = parts.length > 2 ? Integer.parseInt(parts[1]) : 5;
        } catch (NumberFormatException e) {
            return null;
        }
        String[] cells = parts[parts.length - 1].split(",", -1);
        float[] sum = new float[6];
        int heard = 0;
        for (int sl = 0; sl * stride + stride - 1 < cells.length; sl++) {
            int best = -1, bestVotes = 0;
            for (int ph = 0; ph < Math.min(stride, 6); ph++) {
                String c = cells[sl * stride + ph];
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
        for (int i = 0; i < 6; i++) sum[i] /= heard;
        return sum;
    }

    private static int animal(float[] f) {
        if (f == null) return 4;
        float energy = f[1] + f[2], calm = f[3] + 0.6f * f[5], vocal = f[4];   // instrumental leans to the cave
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
        if (latest.isEmpty()) {
            TextView none = text(14, ink2, false);
            none.setText("Nichts in dieser Auswahl.");
            list.addView(none);
            return;
        }
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
        int[] colours = {0xFF8A8A86, 0xFFF29A1F, 0xFFE0281E, 0xFF3C8DFF, 0xFF2BBF6A, 0xFFA77BFF};
        return new View(this) {
            private final Paint p = new Paint();

            @Override
            protected void onDraw(Canvas c) {
                float x = dp(64), w = getWidth() - x - dp(12);
                for (int i = 0; i < 6; i++) {
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
            none.setText(all.isEmpty() ? "Noch nichts gehört. Spiel einen Titel ab – er erscheint hier (Benachrichtigungszugriff nötig)."
                : "Nichts in dieser Auswahl – Filter oben antippen zum Entfernen.");
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
        t1.setText(p[1].isEmpty() ? "–" : p[1]);
        if (fav) t1.setTextColor(rust);
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
        // ★ badge: filled rust for favourites, a faint outline otherwise. Tap it to switch.
        TextView star = text(18, 0xFFFFFFFF, true);
        star.setGravity(Gravity.CENTER);
        paintStar(star, t1, fav);
        star.setOnClickListener(v -> {
            boolean now = MusicStats.favourite(this, p[1], p[2]);
            paintStar(star, t1, now);
            DiagLog.log("favourite " + (now ? "on" : "off") + " from the history");
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(28), dp(28));
        sl.leftMargin = dp(8);
        r.addView(star, sl);
        r.setOnClickListener(v -> playAgain(p));
        return r;
    }

    private void paintStar(TextView star, TextView title, boolean fav) {
        GradientDrawable b = new GradientDrawable();
        b.setShape(GradientDrawable.OVAL);
        if (fav) {
            b.setColor(rust);
            star.setText("★");
            star.setTextColor(0xFFFFFFFF);
        } else {
            b.setColor(0x00000000);
            b.setStroke(dp(1), ink3);
            star.setText("☆");
            star.setTextColor(ink3);
        }
        star.setBackground(b);
        star.setAlpha(fav ? 1f : 0.55f);
        title.setTextColor(fav ? rust : ink);
    }

    /**
     * Every play as a dot on a slowly turning globe: around = hour of the day, up/down = the artist.
     * The current selection glows rust and big, everything filtered out shrinks to faint specks.
     */
    private final class PlayGlobe extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<String[], Double> hours = new java.util.IdentityHashMap<>();
        private final java.util.Set<String[]> picked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        private double yaw;
        private boolean dragging, expanded, moved;
        private float lastX, downX, downY;

        PlayGlobe() {
            super(HistoryActivity.this);
        }

        double hourOf(String[] q) {
            Double hour = hours.get(q);
            if (hour == null) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(parse(q[0]));
                hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0;
                hours.put(q, hour);
            }
            return hour;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                dragging = true;
                moved = false;
                lastX = downX = e.getX();
                downY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (a == android.view.MotionEvent.ACTION_MOVE) {
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > dp(8)) moved = true;
                yaw += (e.getX() - lastX) / (getWidth() * 0.3);
                lastX = e.getX();
            } else {
                dragging = false;
                if (a == android.view.MotionEvent.ACTION_UP && !moved) toggleSize();
            }
            return true;
        }

        /** Tap: the globe grows to almost the screen width (with the hours around it), tap again: back. */
        private void toggleSize() {
            expanded = !expanded;
            int from = getHeight(), to = expanded ? (int) Math.min(getWidth() * 0.95f, getResources().getDisplayMetrics().heightPixels * 0.55f) : dp(150);
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(from, to);
            va.setDuration(320);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            va.addUpdateListener(an -> {
                getLayoutParams().height = (int) an.getAnimatedValue();
                requestLayout();
            });
            va.start();
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        }

        @Override
        protected void onDraw(Canvas c) {
            picked.clear();
            picked.addAll(plays);
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) * 0.44f;
            // A narrowed selection turns to the front (its average hour); otherwise a slow spin. Drag to turn by hand.
            if (dragging) {
                // the finger decides
            } else if (plays.size() < all.size() && !plays.isEmpty()) {
                double sx = 0, sy = 0;
                for (String[] q : plays) {
                    double a = hourOf(q) / 24 * Math.PI * 2;
                    sx += Math.sin(a);
                    sy += Math.cos(a);
                }
                double target = -Math.atan2(sx, sy), d = Math.atan2(Math.sin(target - yaw), Math.cos(target - yaw));
                yaw += d * 0.08;
            } else {
                yaw += 0.003;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(line);
            c.drawCircle(cx, cy, r, p);
            c.drawOval(cx - r, cy - r * 0.3f, cx + r, cy + r * 0.3f, p);
            boolean narrowed = plays.size() < all.size();
            p.setStyle(Paint.Style.FILL);
            for (int pass = 0; pass < 2; pass++) {
                for (String[] q : all) {
                    boolean in = picked.contains(q);
                    if ((pass == 1) != in) continue;            // selection drawn on top
                    double lon = hourOf(q) / 24 * Math.PI * 2 + yaw;
                    double lat = ((q[2].hashCode() & 0xFFFF) / 65535.0 - 0.5) * 2.4;
                    double x = Math.cos(lat) * Math.sin(lon), y = Math.sin(lat), z = Math.cos(lat) * Math.cos(lon);
                    if (z < -0.2 && !in) continue;
                    float sx = cx + (float) x * r, sy = cy - (float) y * r * 0.9f;
                    float depth = (float) (0.55 + 0.45 * z);
                    if (in) {
                        p.setColor(rust);
                        p.setAlpha((int) (255 * Math.max(0.35f, depth)));
                        c.drawCircle(sx, sy, dp(narrowed ? 4 : 2.6f) * depth, p);
                    } else {
                        p.setColor(ink3);
                        p.setAlpha((int) (70 * depth));
                        c.drawCircle(sx, sy, dp(1.2f), p);
                    }
                }
            }
            p.setAlpha(255);
            // Big: the hours on the equator, so an empty side reads as the hours you don't listen (night).
            if (getHeight() > dp(220)) {
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(android.graphics.Typeface.MONOSPACE);
                p.setTextSize(dp(10));
                for (int hr = 0; hr < 24; hr += 3) {
                    double lon = hr / 24.0 * Math.PI * 2 + yaw;
                    double z = Math.cos(lon);
                    if (z < 0.05) continue;
                    p.setColor(ink3);
                    p.setAlpha((int) (255 * z));
                    c.drawText(hr + "h", cx + (float) Math.sin(lon) * r * 1.02f, cy + r * 0.3f * (float) z + dp(14), p);
                }
                p.setAlpha(255);
            }
            postInvalidateOnAnimation();
        }
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

    private final Map<String, String> appNames = new HashMap<>();

    private String appName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = appNames.get(pkg);
        if (cached != null) return cached;
        String name = lookupName(pkg);
        appNames.put(pkg, name);
        return name;
    }

    private String lookupName(String pkg) {
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
