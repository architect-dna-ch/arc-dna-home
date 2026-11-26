package ch.architectdna.one;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ARC Guidebook — Quran and Sunnah explained, a few pages a day, like an e-book.
 *
 * Opens where you left off. Swipe or tap the edges to turn pages; a page counts as read after a few
 * seconds on it. A daily goal (3 pages), a streak of days, bookmarks (♡), and a map: every chapter a
 * node, every page a dot that lights up once read — tap a chapter to jump to its first unread page.
 * Content lives in assets/guide.json; progress in prefs ("guide").
 */
public class GuideActivity extends Activity {

    private static final int INK = 0xFFF4F1EC, INK2 = 0xFFA9A39A, INK3 = 0xFF6F6A63,
        BG = 0xFF0E0D0C, CARD = 0xFF1B1916, LINE = 0xFF2B2824, RUST = 0xFFE0573B;
    static final int DAILY = 3;

    static final class Page { String title, quote, src, text, act; int chapter; }
    static final class Chapter { String id, title; int color; int first, count; }

    final List<Chapter> chapters = new ArrayList<>();
    final List<Page> pages = new ArrayList<>();
    String note = "";

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int at;
    final Set<Integer> read = new HashSet<>();
    final Set<Integer> marked = new HashSet<>();
    final Set<Integer> skipped = new HashSet<>();      // "Zu viel gerade": passed over gently, not failed

    private FrameLayout root;
    private ScrollView pageScroll;
    private TextView chap, title, quote, src, body, act, progress, today, heart;
    private View bar;
    private LinearLayout pageBox;
    private FrameLayout mapLayer;

    private final Runnable markRead = () -> { markRead(at); renderHeader(); };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("guide", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        load();
        restore();

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins.consumeSystemWindowInsets();
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // header: name, progress, today
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(24), dp(18), dp(24), dp(6));
        TextView name = text(11, RUST, Typeface.create("monospace", Typeface.BOLD));
        name.setText("— ARC GUIDEBOOK");
        name.setLetterSpacing(0.2f);
        head.addView(name);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        progress = text(12, INK2, Typeface.create("monospace", Typeface.NORMAL));
        row.addView(progress, new LinearLayout.LayoutParams(0, -2, 1f));
        today = text(12, INK2, Typeface.create("monospace", Typeface.NORMAL));
        row.addView(today);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(8);
        head.addView(row, rl);
        FrameLayout track = new FrameLayout(this);
        track.setBackgroundColor(LINE);
        bar = new View(this);
        bar.setBackgroundColor(RUST);
        track.addView(bar, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, dp(2)); tl.topMargin = dp(8);
        head.addView(track, tl);
        col.addView(head);

        // the page
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageBox = new LinearLayout(this);
        pageBox.setOrientation(LinearLayout.VERTICAL);
        pageBox.setPadding(dp(24), dp(18), dp(24), dp(30));
        pageScroll.addView(pageBox);
        col.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        chap = text(11, INK3, Typeface.create("monospace", Typeface.BOLD));
        chap.setLetterSpacing(0.16f);
        pageBox.addView(chap);
        title = text(30, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setLetterSpacing(-0.02f);
        title.setPadding(0, dp(8), 0, dp(16));
        pageBox.addView(title);

        LinearLayout qbox = new LinearLayout(this);
        qbox.setOrientation(LinearLayout.VERTICAL);
        qbox.setPadding(dp(18), dp(16), dp(18), dp(14));
        GradientDrawable qb = new GradientDrawable(); qb.setColor(CARD); qb.setCornerRadius(dp(14)); qb.setStroke(dp(1), LINE);
        qbox.setBackground(qb);
        quote = text(19, INK, Typeface.create("serif", Typeface.ITALIC));
        quote.setLineSpacing(0, 1.18f);
        qbox.addView(quote);
        src = text(11, INK2, Typeface.create("monospace", Typeface.NORMAL));
        src.setPadding(0, dp(10), 0, 0);
        qbox.addView(src);
        pageBox.addView(qbox);

        body = text(16, INK, Typeface.DEFAULT);
        body.setLineSpacing(0, 1.32f);
        body.setPadding(0, dp(18), 0, dp(18));
        pageBox.addView(body);

        act = text(15, INK, Typeface.DEFAULT_BOLD);
        act.setLineSpacing(0, 1.2f);
        act.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable ab = new GradientDrawable(); ab.setColor(0x22E0573B); ab.setCornerRadius(dp(14)); ab.setStroke(dp(1), 0x66E0573B);
        act.setBackground(ab);
        pageBox.addView(act);

        // the way out of any page that triggers: no reading streak lost, no judgement
        TextView tooMuch = text(13, INK3, Typeface.DEFAULT);
        tooMuch.setText("Zu viel gerade →  weiterblättern");
        tooMuch.setPadding(0, dp(22), 0, dp(6));
        tooMuch.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            skipped.add(at);
            save();
            go(Math.min(pages.size() - 1, at + 1));
        });
        pageBox.addView(tooMuch);

        // bottom: ‹  ♡  Karte  ›
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(16), dp(8), dp(16), dp(14));
        TextView prev = pill("‹", CARD, INK), next = pill("›", INK, BG);
        heart = pill("♡", CARD, INK);
        TextView map = pill("Karte", CARD, INK);
        prev.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); go(at - 1); });
        next.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); markRead(at); go(at + 1); });
        heart.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); toggleMark(); });
        map.setOnClickListener(v -> openMap());
        nav.addView(prev);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
        nav.addView(new View(this), sp);
        nav.addView(heart);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2); ml.leftMargin = dp(8);
        nav.addView(map, ml);
        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        nav.addView(next);
        col.addView(nav);

        // swipe to turn pages
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (Math.abs(vx) > 900 && Math.abs(vx) > Math.abs(vy) * 1.5f) {
                    if (vx < 0) { markRead(at); go(at + 1); } else go(at - 1);
                    return true;
                }
                return false;
            }
        });
        pageScroll.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return false; });

        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        show(false);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(markRead);
        save();
    }

    @Override public void onBackPressed() {
        if (mapLayer != null) { root.removeView(mapLayer); mapLayer = null; return; }
        super.onBackPressed();
    }

    /* ---------------- content & progress ---------------- */

    private void load() {
        try (InputStream in = getAssets().open("guide.json")) {
            byte[] buf = new byte[in.available()];
            int n = 0;
            while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; }
            JSONObject o = new JSONObject(new String(buf, 0, n, "UTF-8"));
            note = o.optString("note");
            JSONArray cs = o.getJSONArray("chapters");
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.getJSONObject(i);
                Chapter ch = new Chapter();
                ch.id = c.optString("id"); ch.title = c.optString("title");
                ch.color = Color.parseColor(c.optString("color", "#E0573B"));
                ch.first = pages.size();
                JSONArray ps = c.getJSONArray("pages");
                for (int k = 0; k < ps.length(); k++) {
                    JSONObject p = ps.getJSONObject(k);
                    Page pg = new Page();
                    pg.title = p.optString("t"); pg.quote = p.optString("q"); pg.src = p.optString("s");
                    pg.text = p.optString("x"); pg.act = p.optString("a"); pg.chapter = i;
                    pages.add(pg);
                }
                ch.count = pages.size() - ch.first;
                chapters.add(ch);
            }
        } catch (Exception e) {
            Page pg = new Page();
            pg.title = "Guidebook nicht lesbar"; pg.quote = ""; pg.src = ""; pg.text = String.valueOf(e.getMessage()); pg.act = "";
            pages.add(pg);
            Chapter ch = new Chapter(); ch.title = "—"; ch.color = RUST; ch.first = 0; ch.count = 1;
            chapters.add(ch);
        }
    }

    private void restore() {
        at = Math.max(0, Math.min(pages.size() - 1, prefs.getInt("at", 0)));
        for (String s : prefs.getString("read", "").split(",")) try { read.add(Integer.parseInt(s.trim())); } catch (RuntimeException ignored) { }
        for (String s : prefs.getString("marked", "").split(",")) try { marked.add(Integer.parseInt(s.trim())); } catch (RuntimeException ignored) { }
        for (String s : prefs.getString("skipped", "").split(",")) try { skipped.add(Integer.parseInt(s.trim())); } catch (RuntimeException ignored) { }
        if (prefs.getInt("v", 1) < 2) {                  // v2 put "Die Mitte" (6 pages) in as chapter 2, after page 5
            read.clear(); read.addAll(shift(prefs.getString("read", "")));
            marked.clear(); marked.addAll(shift(prefs.getString("marked", "")));
            if (at >= 5) at += 6;
            prefs.edit().putInt("v", 2).apply();
            save();
        }
    }

    private void save() {
        prefs.edit().putInt("at", at).putString("read", join(read)).putString("marked", join(marked))
            .putString("skipped", join(skipped)).apply();
    }

    private static Set<Integer> shift(String csv) {
        Set<Integer> out = new HashSet<>();
        for (String s : csv.split(",")) try { int i = Integer.parseInt(s.trim()); out.add(i >= 5 ? i + 6 : i); } catch (RuntimeException ignored) { }
        return out;
    }

    private static String join(Set<Integer> s) {
        StringBuilder b = new StringBuilder();
        for (int i : s) { if (b.length() > 0) b.append(','); b.append(i); }
        return b.toString();
    }

    private static String day(long t) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(t)); }

    /** Counts a page as read (once), and today's pages + the streak of reading days. */
    private void markRead(int i) {
        if (i < 0 || i >= pages.size() || read.contains(i)) return;
        read.add(i);
        skipped.remove(i);
        String d = day(System.currentTimeMillis());
        String last = prefs.getString("day", "");
        int todayN = d.equals(last) ? prefs.getInt("todayN", 0) : 0;
        int streak = prefs.getInt("streak", 0);
        if (!d.equals(last)) {
            String yesterday = day(System.currentTimeMillis() - 86_400_000L);
            streak = yesterday.equals(last) ? streak + 1 : 1;
        }
        prefs.edit().putString("day", d).putInt("todayN", todayN + 1).putInt("streak", streak).apply();
        save();
    }

    int todayCount() { return day(System.currentTimeMillis()).equals(prefs.getString("day", "")) ? prefs.getInt("todayN", 0) : 0; }

    int streak() {
        String last = prefs.getString("day", "");
        long now = System.currentTimeMillis();
        return day(now).equals(last) || day(now - 86_400_000L).equals(last) ? prefs.getInt("streak", 0) : 0;
    }

    /* ---------------- reading ---------------- */

    void go(int i) {
        if (i < 0 || i >= pages.size()) return;
        at = i;
        save();
        show(true);
    }

    private void show(boolean animate) {
        Page p = pages.get(at);
        Chapter c = chapters.get(Math.min(p.chapter, chapters.size() - 1));
        chap.setText((p.chapter + 1) + " · " + c.title.toUpperCase(Locale.GERMAN));
        chap.setTextColor(c.color);
        title.setText(p.title);
        quote.setText(p.quote.isEmpty() ? "" : "« " + p.quote + " »");
        quote.setVisibility(p.quote.isEmpty() ? View.GONE : View.VISIBLE);
        src.setText(p.src.isEmpty() ? "" : "— " + p.src);
        body.setText(at == 0 && !note.isEmpty() ? p.text + "\n\n" + note : p.text);
        act.setText(p.act.isEmpty() ? "" : "Heute:  " + p.act);
        act.setVisibility(p.act.isEmpty() ? View.GONE : View.VISIBLE);
        heart.setText(marked.contains(at) ? "♥" : "♡");
        heart.setTextColor(marked.contains(at) ? RUST : INK);
        pageScroll.scrollTo(0, 0);
        if (animate) { pageBox.setAlpha(0f); pageBox.setTranslationY(dp(10)); pageBox.animate().alpha(1f).translationY(0).setDuration(260).start(); }
        main.removeCallbacks(markRead);
        main.postDelayed(markRead, 6000);                            // a page counts as read after a few seconds
        renderHeader();
    }

    private void renderHeader() {
        int n = pages.size(), r = read.size(), t = todayCount(), s = streak();
        progress.setText("Seite " + (at + 1) + " / " + n + " · " + (n == 0 ? 0 : r * 100 / n) + " % gelesen");
        today.setText((t >= DAILY ? "✓ " : "") + "heute " + Math.min(t, 99) + " / " + DAILY + (s > 1 ? " · " + s + " Tage" : ""));
        today.setTextColor(t >= DAILY ? 0xFF7FD08A : INK2);
        bar.post(() -> {
            View track = (View) bar.getParent();
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            lp.width = n == 0 ? 0 : track.getWidth() * r / n;
            bar.setLayoutParams(lp);
        });
    }

    private void toggleMark() {
        if (!marked.remove(at)) marked.add(at);
        save();
        heart.setText(marked.contains(at) ? "♥" : "♡");
        heart.setTextColor(marked.contains(at) ? RUST : INK);
    }

    /* ---------------- map ---------------- */

    private void openMap() {
        if (mapLayer != null) return;
        main.removeCallbacks(markRead);
        mapLayer = new FrameLayout(this);
        mapLayer.setBackgroundColor(BG);
        mapLayer.setClickable(true);
        ScrollView sv = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(18), dp(20), dp(30));
        sv.addView(c);
        mapLayer.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView h = text(11, RUST, Typeface.create("monospace", Typeface.BOLD));
        h.setText("— KARTE · TIPPE EIN KAPITEL");
        h.setLetterSpacing(0.2f);
        c.addView(h);

        GuideMapView map = new GuideMapView(this, this);
        c.addView(map, new LinearLayout.LayoutParams(-1, dp(420)));

        TextView stats = text(13, INK2, Typeface.create("monospace", Typeface.NORMAL));
        int s = streak();
        stats.setText(read.size() + " von " + pages.size() + " Seiten gelesen" + (s > 0 ? " · " + s + (s == 1 ? " Tag" : " Tage") + " in Folge" : "")
            + (skipped.isEmpty() ? "" : " · " + skipped.size() + " für später"));
        stats.setPadding(0, dp(6), 0, dp(18));
        c.addView(stats);

        for (int i = 0; i < chapters.size(); i++) {
            final Chapter ch = chapters.get(i);
            int got = 0;
            for (int k = ch.first; k < ch.first + ch.count; k++) if (read.contains(k)) got++;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(10), 0, dp(10));
            View dot = new View(this);
            GradientDrawable dd = new GradientDrawable(); dd.setShape(GradientDrawable.OVAL); dd.setColor(ch.color);
            dot.setBackground(dd);
            r.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            TextView t = text(16, INK, Typeface.DEFAULT_BOLD);
            t.setText("  " + (i + 1) + "  " + ch.title);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView g = text(12, got == ch.count ? 0xFF7FD08A : INK3, Typeface.create("monospace", Typeface.NORMAL));
            g.setText(got == ch.count ? "✓ " + got + "/" + ch.count : got + "/" + ch.count);
            r.addView(g);
            final int idx = i;
            r.setOnClickListener(v -> jumpToChapter(idx));
            c.addView(r);
        }

        if (!marked.isEmpty()) {
            TextView mh = text(11, INK3, Typeface.create("monospace", Typeface.BOLD));
            mh.setText("♥ GEMERKT");
            mh.setLetterSpacing(0.2f);
            mh.setPadding(0, dp(24), 0, dp(4));
            c.addView(mh);
            for (int i = 0; i < pages.size(); i++) {
                if (!marked.contains(i)) continue;
                final int idx = i;
                TextView m = text(15, INK, Typeface.DEFAULT);
                m.setText("♥  " + pages.get(i).title + "   ·   " + pages.get(i).src);
                m.setPadding(0, dp(10), 0, dp(10));
                m.setOnClickListener(v -> { closeMap(); go(idx); });
                c.addView(m);
            }
        }

        TextView close = pill("Zurück zum Lesen", INK, BG);
        close.setOnClickListener(v -> { closeMap(); show(false); });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.topMargin = dp(24);
        c.addView(close, cl);

        root.addView(mapLayer, new FrameLayout.LayoutParams(-1, -1));
        mapLayer.setAlpha(0f);
        mapLayer.animate().alpha(1f).setDuration(220).start();
    }

    private void closeMap() {
        if (mapLayer == null) return;
        root.removeView(mapLayer);
        mapLayer = null;
    }

    /** From the map: the first unread page of that chapter (or its first page if all are read). */
    void jumpToChapter(int i) {
        Chapter ch = chapters.get(i);
        int to = ch.first;
        for (int k = ch.first; k < ch.first + ch.count; k++) if (!read.contains(k)) { to = k; break; }
        closeMap();
        go(to);
    }

    /* ---------------- bits ---------------- */

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setTypeface(tf);
        return t;
    }

    private TextView pill(String s, int fill, int ink) {
        TextView t = text(15, ink, Typeface.DEFAULT_BOLD);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(52));
        t.setPadding(dp(18), dp(11), dp(18), dp(11));
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(24));
        if (fill == CARD) g.setStroke(dp(1), LINE);
        t.setBackground(g);
        return t;
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
