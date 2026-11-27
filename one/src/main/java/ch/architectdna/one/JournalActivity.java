package ch.architectdna.one;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
 * The «Wozu?» journal: what you went into which app for, when, how long, and whether you got it done.
 *
 * Range: Woche · Monat · Jahr · Alles. Shows the totals, a weekday × hour heatmap (when do detours
 * happen), the days of the week side by side, what you most often go for and in which apps, whether
 * music helped, and the entries themselves day by day like a diary.
 */
public class JournalActivity extends Activity {

    private static final int INK = 0xFFF4F1EC, INK2 = 0xFFA9A39A, INK3 = 0xFF6F6A63,
        BG = 0xFF0E0D0C, CARD = 0xFF1B1916, LINE = 0xFF2B2824, RUST = 0xFFE0573B, GREEN = 0xFF7FD08A;
    private static final String[] DAYS = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};

    private LinearLayout body;
    private int range = 0;                   // 0 Woche · 1 Monat · 2 Jahr · 3 Alles

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins.consumeSystemWindowInsets();
        });
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(40));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView name = text(11, RUST, Typeface.create("monospace", Typeface.BOLD));
        name.setText("— WOZU? · JOURNAL");
        name.setLetterSpacing(0.2f);
        col.addView(name);
        TextView h = text(26, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        h.setText("Wofür du wann wohin gehst.");
        h.setPadding(0, dp(8), 0, dp(14));
        col.addView(h);

        LinearLayout chips = new LinearLayout(this);
        String[] names = {"Woche", "Monat", "Jahr", "Alles"};
        final TextView[] cs = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int r = i;
            cs[i] = chip(names[i], i == range);
            cs[i].setOnClickListener(v -> { range = r; for (int k = 0; k < 4; k++) tint(cs[k], k == r); render(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f); if (i > 0) p.leftMargin = dp(6);
            chips.addView(cs[i], p);
        }
        col.addView(chips);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        col.addView(body);
        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        render();
    }

    private long from() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        switch (range) {
            case 0: c.add(Calendar.DAY_OF_YEAR, -6); return c.getTimeInMillis();
            case 1: c.add(Calendar.DAY_OF_YEAR, -29); return c.getTimeInMillis();
            case 2: c.add(Calendar.DAY_OF_YEAR, -364); return c.getTimeInMillis();
            default: return 0;
        }
    }

    private void render() {
        body.removeAllViews();
        List<Journal.Entry> es = Journal.since(this, from());
        if (es.isEmpty()) {
            TextView e = text(15, INK2, Typeface.DEFAULT);
            e.setText("Noch keine Einträge in diesem Zeitraum.\n\nJedes «Wozu?», das du mit «Erledigt ✓» oder «Vergessen» abschliesst, landet hier – mit Zeit, App und Dauer.");
            e.setLineSpacing(0, 1.25f);
            e.setPadding(0, dp(24), 0, 0);
            body.addView(e);
            return;
        }

        // 1) totals
        long mins = 0; int done = 0, forgot = 0;
        for (Journal.Entry e : es) { mins += e.minutes(); if (e.done()) done++; else if ("vergessen".equals(e.outcome)) forgot++; }
        LinearLayout tiles = new LinearLayout(this);
        tiles.addView(stat(String.valueOf(es.size()), "Mal"), new LinearLayout.LayoutParams(0, -2, 1f));
        tiles.addView(stat(hm(mins), "Zeit"), lp(1));
        tiles.addView(stat(Math.round(100f * done / es.size()) + " %", "erledigt"), lp(1));
        tiles.addView(stat(hm(mins / Math.max(1, es.size())), "pro Mal"), lp(1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.topMargin = dp(16);
        body.addView(tiles, tl);

        // 2) when: weekday × hour heatmap
        body.addView(section("Wann – Wochentag × Stunde"));
        body.addView(new Heat(this, Journal.heat(es)), new LinearLayout.LayoutParams(-1, dp(7 * 22 + 26)));
        body.addView(note(peak(Journal.heat(es))));

        // 3) by weekday
        body.addView(section("Pro Wochentag"));
        float[] perDay = new float[7]; int[] doneDay = new int[7], cntDay = new int[7];
        Calendar cal = Calendar.getInstance();
        for (Journal.Entry e : es) {
            cal.setTimeInMillis(e.start); int d = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            perDay[d] += e.minutes(); cntDay[d]++; if (e.done()) doneDay[d]++;
        }
        float maxDay = 1; for (float v : perDay) maxDay = Math.max(maxDay, v);
        for (int d = 0; d < 7; d++)
            body.addView(bar(DAYS[d], perDay[d] / maxDay, hm((long) perDay[d]) + (cntDay[d] > 0 ? " · " + Math.round(100f * doneDay[d] / cntDay[d]) + " % erledigt" : "")));

        // 4) what for, which apps
        body.addView(section("Wofür am häufigsten"));
        for (Map.Entry<String, long[]> t : top(es, true)) body.addView(bar(t.getKey(), t.getValue()[2] / 100f, t.getValue()[0] + "× · " + hm(t.getValue()[1])));
        body.addView(section("In welchen Apps"));
        for (Map.Entry<String, long[]> t : top(es, false)) body.addView(bar(t.getKey(), t.getValue()[2] / 100f, t.getValue()[0] + "× · " + hm(t.getValue()[1])));

        // 5) music
        int mN = 0, mD = 0, nN = 0, nD = 0; long mM = 0, nM = 0;
        for (Journal.Entry e : es) { if (e.music) { mN++; mM += e.minutes(); if (e.done()) mD++; } else { nN++; nM += e.minutes(); if (e.done()) nD++; } }
        if (mN > 0 && nN > 0) {
            body.addView(section("Mit Musik vs. ohne"));
            body.addView(note("Mit Musik: " + Math.round(100f * mD / mN) + " % erledigt, Ø " + hm(mM / mN)
                + "\nOhne Musik: " + Math.round(100f * nD / nN) + " % erledigt, Ø " + hm(nM / nN)));
        }
        if (forgot > 0) body.addView(note(forgot + "× «vergessen» – da hat der Umweg gewonnen."));

        // 6) the diary, newest day first
        body.addView(section("Tagebuch"));
        List<Journal.Entry> rev = new ArrayList<>(es); Collections.reverse(rev);
        SimpleDateFormat day = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN), hhmm = new SimpleDateFormat("HH:mm", Locale.GERMAN);
        String lastDay = ""; int shown = 0;
        for (Journal.Entry e : rev) {
            if (shown++ >= 200) break;
            String d = day.format(new Date(e.start));
            if (!d.equals(lastDay)) {
                lastDay = d;
                TextView dh = text(12, INK3, Typeface.create("monospace", Typeface.BOLD));
                dh.setText(d.toUpperCase(Locale.GERMAN)); dh.setLetterSpacing(0.1f); dh.setPadding(0, dp(14), 0, dp(4));
                body.addView(dh);
            }
            LinearLayout r = new LinearLayout(this);
            r.setPadding(0, dp(4), 0, dp(4));
            TextView tm = text(12, INK2, Typeface.create("monospace", Typeface.NORMAL));
            tm.setText(hhmm.format(new Date(e.start)) + "–" + hhmm.format(new Date(e.end)));
            r.addView(tm, new LinearLayout.LayoutParams(dp(92), -2));
            TextView tx = text(14, e.done() ? INK : INK2, Typeface.DEFAULT);
            tx.setText((e.done() ? "✓ " : "vergessen".equals(e.outcome) ? "✗ " : "↷ ") + e.text + "  ·  " + e.app + (e.music ? "  ♪" : ""));
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            body.addView(r);
        }
    }

    /** Most frequent purposes (or apps): count, minutes, share of the top one (×100). */
    private List<Map.Entry<String, long[]>> top(List<Journal.Entry> es, boolean byText) {
        Map<String, long[]> m = new HashMap<>();
        for (Journal.Entry e : es) {
            String k = byText ? e.text.toLowerCase(Locale.GERMAN).trim() : e.app;
            if (k.isEmpty()) k = "—";
            long[] v = m.get(k); if (v == null) { v = new long[3]; m.put(k, v); }
            v[0]++; v[1] += e.minutes();
        }
        List<Map.Entry<String, long[]>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, (a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        if (l.size() > 6) l = new ArrayList<>(l.subList(0, 6));
        long max = l.isEmpty() ? 1 : l.get(0).getValue()[0];
        for (Map.Entry<String, long[]> e : l) e.getValue()[2] = 100 * e.getValue()[0] / Math.max(1, max);
        return l;
    }

    private String peak(float[][] h) {
        int bd = 0, bh = 0; float best = -1;
        for (int d = 0; d < 7; d++) for (int hr = 0; hr < 24; hr++) if (h[d][hr] > best) { best = h[d][hr]; bd = d; bh = hr; }
        float[] hours = new float[24]; for (float[] row : h) for (int i = 0; i < 24; i++) hours[i] += row[i];
        int ph = 0; for (int i = 1; i < 24; i++) if (hours[i] > hours[ph]) ph = i;
        return "Am meisten unterwegs: " + DAYS[bd] + " um " + bh + " Uhr. Über alle Tage: " + ph + "–" + (ph + 1) + " Uhr.";
    }

    /** Weekday × hour grid; brighter = more minutes. */
    private static final class Heat extends View {
        private final float[][] h; private float max = 1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        Heat(Context c, float[][] h) {
            super(c); this.h = h;
            for (float[] r : h) for (float v : r) max = Math.max(max, v);
            t.setColor(INK3); t.setTextSize(c.getResources().getDisplayMetrics().density * 10); t.setTypeface(Typeface.MONOSPACE);
        }
        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density, left = 26 * d, top = 4 * d;
            float cw = (getWidth() - left) / 24f, ch = 22 * d;
            RectF r = new RectF();
            for (int y = 0; y < 7; y++) {
                cv.drawText(DAYS[y], 0, top + y * ch + ch * 0.7f, t);
                for (int x = 0; x < 24; x++) {
                    float v = h[y][x] / max;
                    p.setColor(v <= 0 ? 0xFF1B1916 : (((int) (40 + 215 * Math.sqrt(v))) << 24) | (RUST & 0xFFFFFF));
                    r.set(left + x * cw + 1, top + y * ch + 1, left + (x + 1) * cw - 1, top + (y + 1) * ch - 1);
                    cv.drawRoundRect(r, 3 * d, 3 * d, p);
                }
            }
            for (int x = 0; x <= 24; x += 6) cv.drawText(String.valueOf(x), left + x * cw - (x == 24 ? 12 * d : 0), top + 7 * ch + 14 * d, t);
        }
    }

    /* ---------------- bits ---------------- */

    private View bar(String label, float share, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        LinearLayout top = new LinearLayout(this);
        TextView l = text(14, INK, Typeface.DEFAULT); l.setText(label); l.setSingleLine(true); l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text(12, INK3, Typeface.create("monospace", Typeface.NORMAL)); v.setText(value);
        top.addView(v);
        r.addView(top);
        FrameLayout track = new FrameLayout(this);
        track.setBackgroundColor(LINE);
        View fill = new View(this); fill.setBackgroundColor(RUST);
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        track.post(() -> { ViewGroup.LayoutParams p = fill.getLayoutParams(); p.width = Math.round(track.getWidth() * Math.max(0, Math.min(1, share))); fill.setLayoutParams(p); });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(5)); tp.topMargin = dp(4);
        r.addView(track, tp);
        return r;
    }

    private View stat(String big, String small) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER);
        s.setPadding(dp(4), dp(10), dp(4), dp(10));
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(12)); g.setStroke(dp(1), LINE);
        s.setBackground(g);
        TextView b = text(18, INK, Typeface.create("sans-serif-black", Typeface.NORMAL)); b.setText(big); b.setGravity(Gravity.CENTER);
        TextView l = text(11, INK3, Typeface.DEFAULT); l.setText(small); l.setGravity(Gravity.CENTER);
        s.addView(b); s.addView(l);
        return s;
    }

    private LinearLayout.LayoutParams lp(float w) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, w); p.leftMargin = dp(6); return p; }

    private TextView section(String s) {
        TextView t = text(11, RUST, Typeface.create("monospace", Typeface.BOLD));
        t.setText("— " + s.toUpperCase(Locale.GERMAN)); t.setLetterSpacing(0.16f); t.setPadding(0, dp(24), 0, dp(8));
        return t;
    }

    private TextView note(String s) {
        TextView t = text(13, INK2, Typeface.DEFAULT); t.setText(s); t.setLineSpacing(0, 1.2f); t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private static String hm(long m) { return m < 60 ? m + " Min." : (m / 60) + " h " + (m % 60) + " Min."; }

    private TextView chip(String s, boolean on) {
        TextView t = text(13, on ? BG : INK, Typeface.DEFAULT_BOLD);
        t.setText(s); t.setGravity(Gravity.CENTER); t.setPadding(0, dp(9), 0, dp(9));
        tint(t, on);
        return t;
    }

    private void tint(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable(); g.setColor(on ? INK : CARD); g.setCornerRadius(dp(20));
        if (!on) g.setStroke(dp(1), LINE);
        t.setBackground(g); t.setTextColor(on ? BG : INK);
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setTypeface(tf);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
