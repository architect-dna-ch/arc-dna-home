package ch.architectdna.orbit;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ARC-DNA Orbit — the third home: the day as a dial (built on the Statistik home).
 * ARC-DNA Statistik — the second home. Built on what the big launchers already taught everyone:
 * the WHOLE screen is the control, never a small target.
 *   swipe up anywhere    → all apps (Pixel / One UI / HyperOS drawer)
 *   swipe down anywhere  → search with the keyboard up (iOS Spotlight)
 *   swipe left           → the statistics page (iOS Today view); swipe right or Back returns
 *   bottom dock          → your most-used apps, always in the same spot (iOS / macOS / ChromeOS shelf)
 *   suggestions grid     → what you most likely want now (Pixel app suggestions, iPadOS dock)
 * The ranking comes from real usage events (7 days): frequency, recency and hour-of-day match.
 */
public class OrbitHomeActivity extends Activity {

    private static final class App {
        String pkg, label;
        Drawable icon;
        Intent launch;
        long total, today, last;
        final long[] hours = new long[24];
        double score;
        String why = "";
    }

    private static final long HOUR = 3600_000L, DAY = 24 * HOUR;

    private int paper, card, ink, ink2, ink3, line, rust, teal;
    private SharedPreferences prefs;
    private InputMethodManager imm;
    private SwipeFrame root;
    private LinearLayout homePage, statsCol, suggestBox, dock, drawer, accessCard, topBox;
    private ScrollView statsPage;
    private TextView glance, accessLink, screenTv, hourHint, sugLabel;
    private View homeClock, homeGap;
    private OrbitView orbit;
    private EditText search;
    private GridView grid;
    private HourChart chart;
    private final List<App> apps = new ArrayList<>();     // ranked by score
    private final List<App> shown = new ArrayList<>();    // what the drawer grid shows
    private final GridAdapter adapter = new GridAdapter();
    private boolean loading, drawerOpen;
    private int page;                                      // 0 = home, 1 = statistics

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("orbit", MODE_PRIVATE);
        imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        paper = night ? 0xFF14110E : 0xFFF5F1E8;
        card = night ? 0xFF1C1915 : 0xFFFBF8F1;
        ink = night ? 0xFFEDE7DA : 0xFF1B1613;
        ink2 = night ? 0xFFADA293 : 0xFF6A5F54;
        ink3 = night ? 0xFF6E6456 : 0xFFA99E8E;
        line = night ? 0xFF2E2820 : 0xFFE2D9C8;
        rust = night ? 0xFFD4523B : 0xFFB5271E;
        teal = night ? 0xFF4FA594 : 0xFF2E6E63;
        getWindow().setStatusBarColor(paper);
        getWindow().setNavigationBarColor(paper);
        if (!night) getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        root = new SwipeFrame();
        root.setBackgroundColor(paper);
        buildHome();
        buildStats();
        buildDrawer();
        root.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(statsPage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, bo, ime = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; bo = i.bottom;
                ime = in.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            } else {
                l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight(); bo = in.getSystemWindowInsetBottom();
            }
            homePage.setPadding(l + dp(20), t + dp(20), r + dp(20), bo + dp(14));
            statsCol.setPadding(l + dp(20), t + dp(20), r + dp(20), bo + dp(28));
            drawer.setPadding(l + dp(14), t + dp(12), r + dp(14), Math.max(bo, ime));
            return in;
        });
        setContentView(root);
    }

    /** Page 0: glance on top, suggestions in the middle, dock and search at the bottom. */
    private void buildHome() {
        homePage = new LinearLayout(this);
        homePage.setOrientation(LinearLayout.VERTICAL);

        homePage.addView(label("ARC·DNA", rust));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(ink);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        clock.setLetterSpacing(-0.04f);
        homePage.addView(clock);
        homeClock = clock;
        clock.setOnLongClickListener(v -> { prefs.edit().putString("design", "orbit").apply(); applyDesign(); orbit.set(apps, Calendar.getInstance().get(Calendar.HOUR_OF_DAY)); return true; });
        glance = text(15, ink2, false);
        homePage.addView(glance);
        accessLink = text(14, rust, true);
        accessLink.setText("Nutzungszugriff erlauben →");
        accessLink.setPadding(0, dp(12), 0, dp(12));
        accessLink.setOnClickListener(v -> openUsageSettings());
        accessLink.setVisibility(View.GONE);
        homePage.addView(accessLink);

        homeGap = new View(this);
        homePage.addView(homeGap, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Design 3 — Orbit: the day as a dial. Long-press its centre to switch designs.
        orbit = new OrbitView();
        homePage.addView(orbit, new LinearLayout.LayoutParams(-1, 0, 1f));

        sugLabel = label("VORSCHLÄGE FÜR JETZT", ink3);
        homePage.addView(sugLabel, lp(-1, -2, 0, 0, 0, dp(10)));
        suggestBox = new LinearLayout(this);
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        homePage.addView(suggestBox);

        // Architect-DNA web tools, one tap away
        LinearLayout tools = new LinearLayout(this);
        tools.addView(toolChip("⚖ Einspruch", "https://einspruch.architect-dna.ch"), new LinearLayout.LayoutParams(0, -2, 1f));
        View tgap = new View(this);
        tools.addView(tgap, new LinearLayout.LayoutParams(dp(8), 1));
        tools.addView(toolChip("⚓ Anchor", "https://anchor.architect-dna.ch"), new LinearLayout.LayoutParams(0, -2, 1f));
        homePage.addView(tools, lp(-1, -2, 0, dp(14), 0, 0));

        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(10), dp(8), dp(10));
        dock.setBackground(box(card, line, 28));
        homePage.addView(dock, lp(-1, -2, 0, dp(18), 0, 0));

        TextView pill = text(14, ink3, false);
        pill.setText("Suchen   ·   ↑ alle Apps   ·   ← Statistik");
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(16), dp(13), dp(16), dp(13));
        pill.setBackground(box(card, line, 40));
        pill.setOnClickListener(v -> openDrawer(true));
        homePage.addView(pill, lp(-1, -2, 0, dp(12), 0, 0));
    }

    /** Page 1: the numbers behind the suggestions, and the three weights of the model. */
    private void buildStats() {
        statsPage = new ScrollView(this);
        statsPage.setBackgroundColor(paper);
        statsPage.setVisibility(View.GONE);
        statsCol = new LinearLayout(this);
        statsCol.setOrientation(LinearLayout.VERTICAL);
        statsPage.addView(statsCol, new ScrollView.LayoutParams(-1, -2));
        LinearLayout col = statsCol;

        col.addView(label("STATISTIK   ·   → zurück", rust));

        accessCard = cardBox();
        TextView at = text(15, ink, true);
        at.setText("Nutzungszugriff fehlt");
        accessCard.addView(at);
        TextView ab = text(13, ink2, false);
        ab.setText("Damit das Zuhause deine Muster modellieren kann, braucht es den Nutzungszugriff. Die Zahlen bleiben auf diesem Gerät.");
        accessCard.addView(ab, lp(-1, -2, 0, dp(4), 0, dp(10)));
        TextView btn = text(14, 0xFFFFFFFF, true);
        btn.setText("Zugriff erlauben");
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(18), dp(11), dp(18), dp(11));
        btn.setBackground(box(rust, rust, 40));
        btn.setOnClickListener(v -> openUsageSettings());
        accessCard.addView(btn, lp(-2, -2, 0, 0, 0, 0));
        accessCard.setVisibility(View.GONE);
        col.addView(accessCard, lp(-1, -2, 0, dp(16), 0, 0));

        LinearLayout model = cardBox();
        model.addView(label("MODELL — AUSTÜFTELN", ink3));
        model.addView(slider("Häufigkeit", "wF", 45));
        model.addView(slider("Kürzlich", "wR", 25));
        model.addView(slider("Tageszeit", "wH", 30));
        col.addView(model, lp(-1, -2, 0, dp(14), 0, 0));

        LinearLayout today = cardBox();
        today.addView(label("HEUTE AM BILDSCHIRM", ink3));
        screenTv = text(32, ink, true);
        screenTv.setText("—");
        today.addView(screenTv);
        topBox = new LinearLayout(this);
        topBox.setOrientation(LinearLayout.VERTICAL);
        today.addView(topBox, lp(-1, -2, 0, dp(8), 0, 0));
        col.addView(today, lp(-1, -2, 0, dp(14), 0, 0));

        LinearLayout hourCard = cardBox();
        hourCard.addView(label("NUTZUNG NACH STUNDE · 7 TAGE", ink3));
        chart = new HourChart();
        hourCard.addView(chart, lp(-1, dp(96), 0, dp(10), 0, 0));
        hourHint = text(11, ink3, false);
        hourHint.setTypeface(Typeface.MONOSPACE);
        hourCard.addView(hourHint, lp(-1, -2, 0, dp(6), 0, 0));
        col.addView(hourCard, lp(-1, -2, 0, dp(14), 0, 0));
    }

    /** The drawer: search on top, every app in a 4-column grid. Pull down at the top to close. */
    private void buildDrawer() {
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(paper);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        drawer.setFocusableInTouchMode(true);

        search = new EditText(this);
        search.setHint("Apps suchen");
        search.setHintTextColor(ink3);
        search.setTextColor(ink);
        search.setTextSize(17);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(dp(18), dp(13), dp(18), dp(13));
        search.setBackground(box(card, line, 40));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { filter(e.toString()); }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            if (!shown.isEmpty()) launch(shown.get(0));
            return true;
        });
        drawer.addView(search, lp(-1, -2, 0, 0, 0, dp(10)));

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(6));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setVerticalScrollBarEnabled(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(24));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(shown.get(pos)));
        grid.setOnTouchListener(new View.OnTouchListener() {
            float downY; boolean fromTop;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downY = e.getRawY();
                    fromTop = !grid.canScrollVertically(-1);
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && fromTop
                    && e.getRawY() - downY > dp(90)) {
                    fromTop = false;
                    closeDrawer();
                }
                return false;
            }
        });
        drawer.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    /** Leaving for another app: the next time home shows, it is the plain home page again. */
    @Override
    protected void onStop() {
        super.onStop();
        resetToHome();
    }

    /** Home button while already here. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer();
        showStats(false);
    }

    /** Back closes what is open; on the plain home there is nowhere to go back to. */
    @Override
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        else showStats(false);
    }

    // ================= whole-screen gestures, pages, drawer =================

    /** The root: any swipe that starts anywhere — on an icon or on empty space — is a gesture. */
    private final class SwipeFrame extends FrameLayout {
        private float sx, sy;
        private final int slop;

        SwipeFrame() {
            super(OrbitHomeActivity.this);
            slop = ViewConfiguration.get(OrbitHomeActivity.this).getScaledTouchSlop();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (drawerOpen) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                sx = e.getX(); sy = e.getY();
                return false;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(e.getX() - sx), dy = Math.abs(e.getY() - sy);
                if (dx < slop * 2 && dy < slop * 2) return false;
                return page == 0 || dx > dy;        // the statistics page keeps its own vertical scroll
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (drawerOpen) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - sx, dy = e.getY() - sy;
                if (Math.max(Math.abs(dx), Math.abs(dy)) > dp(44)) {
                    boolean horizontal = Math.abs(dx) > Math.abs(dy);
                    if (page == 1) { if (horizontal && dx > 0) showStats(false); }
                    else if (horizontal) { if (dx < 0) showStats(true); }
                    else if (dy < 0) openDrawer(false);
                    else openDrawer(true);
                }
            }
            return true;
        }
    }

    private void showStats(boolean on) {
        if ((page == 1) == on) return;
        page = on ? 1 : 0;
        float w = root.getWidth();
        if (on) {
            statsPage.setVisibility(View.VISIBLE);
            statsPage.setTranslationX(w);
            statsPage.animate().translationX(0).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).withEndAction(null).start();
            homePage.animate().translationX(-w * 0.25f).alpha(0f).setDuration(240).start();
        } else {
            statsPage.animate().translationX(w).setDuration(220).withEndAction(() -> statsPage.setVisibility(View.GONE)).start();
            homePage.animate().translationX(0).alpha(1f).setDuration(220).start();
        }
    }

    private void openDrawer(boolean focusSearch) {
        if (drawerOpen) return;
        drawerOpen = true;
        filter("");
        grid.setSelection(0);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationY(root.getHeight());
        drawer.animate().translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).withEndAction(null).start();
        if (focusSearch) {
            search.requestFocus();
            if (imm != null) imm.showSoftInput(search, 0);
        }
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        drawer.animate().translationY(root.getHeight()).setDuration(200).withEndAction(() -> {
            drawer.setVisibility(View.GONE);
            search.setText("");
            search.clearFocus();
        }).start();
    }

    private void resetToHome() {
        drawerOpen = false;
        page = 0;
        drawer.animate().cancel();
        statsPage.animate().cancel();
        homePage.animate().cancel();
        drawer.setVisibility(View.GONE);
        statsPage.setVisibility(View.GONE);
        homePage.setTranslationX(0);
        homePage.setAlpha(1f);
        search.setText("");
        search.clearFocus();
    }

    /** Drawer contents: everything A–Z, or the matches for what is typed (prefix matches first). */
    private void filter(String q) {
        final String s = q.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (App a : apps) if (s.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(s)) shown.add(a);
        Collections.sort(shown, (x, y) -> {
            if (!s.isEmpty()) {
                boolean px = x.label.toLowerCase(Locale.ROOT).startsWith(s), py = y.label.toLowerCase(Locale.ROOT).startsWith(s);
                if (px != py) return px ? -1 : 1;
            }
            return x.label.compareToIgnoreCase(y.label);
        });
        adapter.notifyDataSetChanged();
    }

    private final class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout cell = cv instanceof LinearLayout ? (LinearLayout) cv : appCell(52, true);
            if (cv == null) cell.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            App a = shown.get(i);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(a.icon);
            ((TextView) cell.getChildAt(1)).setText(a.label);
            return cell;
        }
    }

    /** An app cell: icon over (optionally) its name. The whole cell is the touch target. */
    private LinearLayout appCell(int iconDp, boolean withLabel) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(8), dp(4), dp(8));
        ImageView iv = new ImageView(this);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
        TextView nm = text(12, ink, false);
        nm.setGravity(Gravity.CENTER);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        nm.setVisibility(withLabel ? View.VISIBLE : View.GONE);
        cell.addView(nm, lp(-1, -2, 0, dp(5), 0, 0));
        return cell;
    }

    private TextView toolChip(String label, final String url) {
        TextView t = text(14, ink, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        t.setBackground(box(card, line, 40));
        t.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
        });
        return t;
    }

    static final String DEFAULT_DESIGN = "orbit";
    private boolean orbitDesign() { return prefs.getString("design", DEFAULT_DESIGN).equals("orbit"); }

    /** Orbit (design 3) or the grid (design 2): same data, two ways to show it. */
    private void applyDesign() {
        boolean o = orbitDesign();
        orbit.setVisibility(o ? View.VISIBLE : View.GONE);
        homeClock.setVisibility(o ? View.GONE : View.VISIBLE);
        homeGap.setVisibility(o ? View.GONE : View.VISIBLE);
        sugLabel.setVisibility(o ? View.GONE : View.VISIBLE);
        suggestBox.setVisibility(o ? View.GONE : View.VISIBLE);
    }

    /**
     * The day as a dial: 24 hours around, the hand at now. Each app sits at the hour you usually
     * use it (circular mean of its 7-day pattern); the closer it is to the hand and the more you use
     * it, the bigger and brighter it is. Tap an icon to open it; long-press the centre to switch design.
     */
    private final class OrbitView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<App> shownApps = new ArrayList<>();
        private float[] ax = new float[0], ay = new float[0], ar = new float[0];
        private float[] ang = new float[0], wgt = new float[0];
        private Drawable[] icons = new Drawable[0];
        private int now;
        private float downX, downY;
        private long downAt;

        OrbitView() { super(OrbitHomeActivity.this); }

        void set(List<App> ranked, int nowHour) {
            now = nowHour;
            shownApps.clear();
            List<App> used = new ArrayList<>();
            for (App a : ranked) if (a.total > 0) used.add(a);
            Collections.sort(used, (x, y) -> Long.compare(y.total, x.total));
            shownApps.addAll(used.subList(0, Math.min(12, used.size())));
            int n = shownApps.size();
            icons = new Drawable[n];
            for (int i = 0; i < n; i++) {
                Drawable d = shownApps.get(i).icon;
                Drawable.ConstantState cs = d == null ? null : d.getConstantState();
                icons[i] = cs != null ? cs.newDrawable(getResources()).mutate() : null;
            }
            ang = new float[n]; wgt = new float[n];
            double best = 1e-9;
            for (App a : shownApps) best = Math.max(best, a.score);
            for (int i = 0; i < n; i++) {
                App a = shownApps.get(i);
                double x = 0, y = 0;
                for (int h = 0; h < 24; h++) {
                    double t = (h + 0.5) / 24.0 * Math.PI * 2;
                    x += a.hours[h] * Math.cos(t); y += a.hours[h] * Math.sin(t);
                }
                double t = Math.atan2(y, x);
                if (t < 0) t += Math.PI * 2;
                ang[i] = (float) t;                                  // 0 = midnight, clockwise
                wgt[i] = (float) Math.max(0, Math.min(1, a.score / best));
            }
            requestLayout();
            invalidate();
        }

        private void layoutIcons(float cx, float cy, float R) {
            int n = shownApps.size();
            ax = new float[n]; ay = new float[n]; ar = new float[n];
            float[] rings = {R * 0.92f, R * 0.66f};
            for (int i = 0; i < n; i++) {
                float size = dp(18) + dp(10) * wgt[i];
                boolean placed = false;
                // try the app's own hour first, then step outwards: +7.5°, −7.5°, +15° … on both rings
                for (int k = 0; k < 48 && !placed; k++) {
                    float off = (float) Math.toRadians(7.5 * ((k / 2 + 1) / 2) * (k % 4 < 2 ? 1 : -1));
                    float rr = rings[k % 2];
                    float a = ang[i] - (float) (Math.PI / 2) + (k < 2 ? 0 : off);
                    float bx = cx + (float) Math.cos(a) * rr, by = cy + (float) Math.sin(a) * rr;
                    boolean free = Math.hypot(bx - cx, by - cy) > dp(52) + size + dp(4);
                    for (int j = 0; j < i && free; j++) if (Math.hypot(bx - ax[j], by - ay[j]) < size + ar[j] + dp(6)) free = false;
                    if (free) { ax[i] = bx; ay[i] = by; ar[i] = size; placed = true; }
                }
                if (!placed) { ax[i] = -9999; ay[i] = -9999; ar[i] = 0; }   // no room: leave it to the drawer
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float R = Math.min(w, h) / 2f - dp(34);
            if (R <= 0) return;
            // dial: 24 ticks, major every 6 h
            p.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 24; i++) {
                double t = i / 24.0 * Math.PI * 2 - Math.PI / 2;
                boolean major = i % 6 == 0;
                p.setColor(major ? ink3 : line);
                p.setStrokeWidth(dp(major ? 2 : 1));
                float r1 = R + dp(14), r2 = R + dp(major ? 24 : 19);
                c.drawLine(cx + (float) Math.cos(t) * r1, cy + (float) Math.sin(t) * r1,
                    cx + (float) Math.cos(t) * r2, cy + (float) Math.sin(t) * r2, p);
            }
            // hand at now (minutes included)
            Calendar cal = Calendar.getInstance();
            double tn = (cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0) / 24.0 * Math.PI * 2 - Math.PI / 2;
            p.setColor(rust);
            p.setStrokeWidth(dp(2.5f));
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx, cy, cx + (float) Math.cos(tn) * (R + dp(24)), cy + (float) Math.sin(tn) * (R + dp(24)), p);
            // icons
            layoutIcons(cx, cy, R);
            for (int i = 0; i < shownApps.size(); i++) {
                Drawable d = i < icons.length ? icons[i] : null;
                int s = Math.round(ar[i]);
                if (d != null) {
                    d.setBounds(Math.round(ax[i]) - s, Math.round(ay[i]) - s, Math.round(ax[i]) + s, Math.round(ay[i]) + s);
                    d.setAlpha(Math.round(110 + 145 * wgt[i]));
                    d.draw(c);
                    d.setAlpha(255);
                }
                if (i == 0) {                                        // the top pick gets a ring
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(rust);
                    p.setStrokeWidth(dp(2));
                    c.drawCircle(ax[i], ay[i], ar[i] + dp(5), p);
                }
            }
            // centre: the time, and today's screen time
            p.setStyle(Paint.Style.FILL);
            p.setColor(card);
            c.drawCircle(cx, cy, dp(52), p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(ink);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setTextSize(dp(30));
            c.drawText(String.format(Locale.ROOT, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), cx, cy + dp(6), p);
            long today = 0;
            for (App a : apps) today += a.today;
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(10.5f));
            p.setColor(ink3);
            c.drawText(today > 0 ? dur(today) + " heute" : "ARC·DNA", cx, cy + dp(24), p);
            if (shownApps.isEmpty()) {
                p.setColor(ink2);
                p.setTextSize(dp(12));
                c.drawText("Noch keine Nutzungsdaten", cx, cy + R * 0.6f, p);
            }
            postInvalidateDelayed(30_000);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); downAt = e.getEventTime();
                    return true;
                case MotionEvent.ACTION_UP: {
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > dp(12)) return true;
                    float cx = getWidth() / 2f, cy = getHeight() / 2f;
                    if (Math.hypot(e.getX() - cx, e.getY() - cy) < dp(52)) {
                        if (e.getEventTime() - downAt > 450) {           // long-press centre: switch design
                            prefs.edit().putString("design", "grid").apply();
                            applyDesign();
                        }
                        return true;
                    }
                    for (int i = 0; i < shownApps.size() && i < ax.length; i++)
                        if (Math.hypot(e.getX() - ax[i], e.getY() - ay[i]) < ar[i] + dp(8)) { launch(shownApps.get(i)); return true; }
                    return true;
                }
            }
            return true;
        }
    }

    private void openUsageSettings() {
        try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (RuntimeException ignored) { }
    }

    // ================= data =================

    private boolean hasAccess() {
        try {
            AppOpsManager ao = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            return ao != null && ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void load() {
        if (loading) return;
        loading = true;
        final boolean access = hasAccess();
        accessCard.setVisibility(access ? View.GONE : View.VISIBLE);
        accessLink.setVisibility(access ? View.GONE : View.VISIBLE);
        new Thread(() -> {
            final List<App> list = queryApps();
            if (access) {
                try { fillStats(list); } catch (RuntimeException ignored) { }
            }
            runOnUiThread(() -> {
                loading = false;
                apps.clear();
                apps.addAll(list);
                rank();
            });
        }).start();
    }

    private List<App> queryApps() {
        List<App> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.icon = ri.loadIcon(pm);
            a.launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, ri.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.add(a);
        }
        return out;
    }

    /** Walk the last 7 days of foreground/background events into per-app totals and hour patterns. */
    private void fillStats(List<App> list) {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        if (usm == null) return;
        Map<String, App> byPkg = new HashMap<>();
        for (App a : list) byPkg.put(a.pkg, a);
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long dayStart = c.getTimeInMillis();

        UsageEvents evs = usm.queryEvents(now - 7 * DAY, now);
        if (evs == null) return;
        UsageEvents.Event ev = new UsageEvents.Event();
        Map<String, Long> started = new HashMap<>();
        while (evs.hasNextEvent()) {
            evs.getNextEvent(ev);
            int type = ev.getEventType();
            if (type != 1 && type != 2) continue;          // 1 = to foreground, 2 = to background
            String pkg = ev.getPackageName();
            App a = byPkg.get(pkg);
            if (a == null) continue;
            long ts = ev.getTimeStamp();
            if (ts > a.last) a.last = ts;
            if (type == 1) {
                started.put(pkg, ts);
            } else {
                Long s = started.remove(pkg);
                if (s != null) addSpan(a, s, ts, dayStart, c);
            }
        }
    }

    private void addSpan(App a, long s, long e, long dayStart, Calendar c) {
        if (e <= s) return;
        if (e - s > 4 * HOUR) e = s + 4 * HOUR;             // a session left "open" overnight is not real use
        while (s < e) {
            c.setTimeInMillis(s);
            int h = c.get(Calendar.HOUR_OF_DAY);
            c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            long next = Math.min(e, c.getTimeInMillis() + HOUR);
            long d = next - s;
            a.hours[h] += d;
            a.total += d;
            if (s >= dayStart) a.today += d;
            s = next;
        }
    }

    // ================= model =================

    private void rank() {
        double f = prefs.getInt("wF", 45), r = prefs.getInt("wR", 25), h = prefs.getInt("wH", 30);
        double sum = f + r + h;
        if (sum <= 0) { f = r = h = 1; sum = 3; }
        f /= sum; r /= sum; h /= sum;
        long now = System.currentTimeMillis();
        int nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        long maxTotal = 1;
        for (App a : apps) maxTotal = Math.max(maxTotal, a.total);
        for (App a : apps) {
            double pf = f * (a.total / (double) maxTotal);
            double pr = a.last > 0 ? r * (1 - Math.min(1.0, (now - a.last) / (14.0 * HOUR))) : 0;
            long mx = 0;
            for (long v : a.hours) mx = Math.max(mx, v);
            double ph = mx > 0 ? h * (a.hours[nowHour] / (double) mx) : 0;
            a.score = pf + pr + ph;
            if (a.score <= 0) a.why = "noch keine Daten";
            else if (ph >= pf && ph >= pr) a.why = "um diese Zeit meistens";
            else if (pr >= pf) a.why = "gerade erst offen gehabt";
            else a.why = "dein Dauer-Begleiter";
        }
        Collections.sort(apps, (x, y) -> {
            int cmp = Double.compare(y.score, x.score);
            return cmp != 0 ? cmp : x.label.compareToIgnoreCase(y.label);
        });
        render(nowHour);
    }

    // ================= rendering =================

    private void render(int nowHour) {
        applyDesign();
        orbit.set(apps, nowHour);

        // dock: the four most-used apps of the week — stable, so the thumb learns the spot
        List<App> byTotal = new ArrayList<>(apps);
        Collections.sort(byTotal, (x, y) -> Long.compare(y.total, x.total));
        List<App> docked = new ArrayList<>(byTotal.subList(0, Math.min(4, byTotal.size())));
        dock.removeAllViews();
        for (App a : docked) {
            LinearLayout cell = appCell(54, false);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(a.icon);
            cell.setOnClickListener(v -> launch(a));
            dock.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        // suggestions: the next eight by score, two rows of four
        List<App> sug = new ArrayList<>();
        for (App a : apps) { if (!docked.contains(a)) sug.add(a); if (sug.size() == 8) break; }
        suggestBox.removeAllViews();
        for (int row = 0; row * 4 < sug.size(); row++) {
            LinearLayout line4 = new LinearLayout(this);
            for (int k = 0; k < 4; k++) {
                int idx = row * 4 + k;
                View cellView;
                if (idx < sug.size()) {
                    final App a = sug.get(idx);
                    LinearLayout cell = appCell(56, true);
                    ((ImageView) cell.getChildAt(0)).setImageDrawable(a.icon);
                    ((TextView) cell.getChildAt(1)).setText(a.label);
                    cell.setOnClickListener(v -> launch(a));
                    cellView = cell;
                } else cellView = new View(this);
                line4.addView(cellView, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            suggestBox.addView(line4, lp(-1, -2, 0, row == 0 ? 0 : dp(6), 0, 0));
        }

        // glance + statistics page: today's screen time and top five
        long todayAll = 0;
        List<App> byToday = new ArrayList<>();
        for (App a : apps) { todayAll += a.today; if (a.today > 0) byToday.add(a); }
        Collections.sort(byToday, (x, y) -> Long.compare(y.today, x.today));
        String date = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(new Date());
        glance.setText(todayAll > 0 ? date + "  ·  " + dur(todayAll) + " am Bildschirm" : date);
        screenTv.setText(todayAll > 0 ? dur(todayAll) : "—");
        topBox.removeAllViews();
        long topMax = byToday.isEmpty() ? 1 : byToday.get(0).today;
        for (int i = 0; i < Math.min(5, byToday.size()); i++) {
            App a = byToday.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView nm = text(13, ink, false);
            nm.setText(a.label);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(nm, new LinearLayout.LayoutParams(dp(104), -2));
            row.addView(bar(a.today / (double) topMax, i == 0 ? rust : teal, dp(12)), new LinearLayout.LayoutParams(0, dp(12), 1f));
            TextView mins = text(12, ink2, false);
            mins.setTypeface(Typeface.MONOSPACE);
            mins.setGravity(Gravity.END);
            mins.setText(dur(a.today));
            row.addView(mins, new LinearLayout.LayoutParams(dp(64), -2));
            topBox.addView(row, lp(-1, -2, 0, dp(7), 0, 0));
        }

        // usage by hour, all apps
        float[] hours = new float[24];
        int peak = 0;
        for (App a : apps) for (int i = 0; i < 24; i++) hours[i] += a.hours[i];
        for (int i = 1; i < 24; i++) if (hours[i] > hours[peak]) peak = i;
        chart.set(hours, nowHour);
        hourHint.setText(hours[peak] > 0
            ? String.format(Locale.ROOT, "Peak um %02d:00 · jetzt (%02d:00) rot", peak, nowHour)
            : "Noch keine Nutzungsdaten.");

        if (drawerOpen) filter(search.getText().toString());
    }

    private void launch(App a) {
        try { startActivity(a.launch); } catch (RuntimeException ignored) { }
    }

    /** A two-part horizontal bar: the filled share in colour, the rest in the line colour. */
    private View bar(double frac, int colour, int h) {
        float f = (float) Math.max(0.02, Math.min(1.0, frac));
        LinearLayout b = new LinearLayout(this);
        View on = new View(this);
        on.setBackground(box(colour, colour, 3));
        View off = new View(this);
        off.setBackground(box(line, line, 3));
        b.addView(on, new LinearLayout.LayoutParams(0, h, f));
        b.addView(off, new LinearLayout.LayoutParams(0, h, 1f - f));
        return b;
    }

    private View slider(String name, final String key, int def) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView nm = text(13, ink, true);
        nm.setText(name);
        row.addView(nm, new LinearLayout.LayoutParams(dp(92), -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(prefs.getInt(key, def));
        sb.setProgressTintList(ColorStateList.valueOf(rust));
        sb.setThumbTintList(ColorStateList.valueOf(rust));
        row.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = text(12, ink2, false);
        val.setTypeface(Typeface.MONOSPACE);
        val.setGravity(Gravity.END);
        val.setText(String.valueOf(sb.getProgress()));
        row.addView(val, new LinearLayout.LayoutParams(dp(34), -2));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(String.valueOf(p));
                if (!fromUser) return;
                prefs.edit().putInt(key, p).apply();
                rank();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        row.setLayoutParams(lp(-1, -2, 0, dp(10), 0, 0));
        return row;
    }

    /** 24 bars, one per hour of the day; the current hour is drawn in rust. */
    private final class HourChart extends View {
        private float[] vals = new float[24];
        private int now;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        HourChart() { super(OrbitHomeActivity.this); }

        void set(float[] v, int nowHour) { vals = v; now = nowHour; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), max = 0;
            for (float v : vals) max = Math.max(max, v);
            float axis = dp(14), top = h - axis, slot = w / 24f, gap = dp(2);
            for (int i = 0; i < 24; i++) {
                float bh = max > 0 ? Math.max(dp(2), top * vals[i] / max) : dp(2);
                p.setColor(i == now ? rust : teal);
                c.drawRect(i * slot + gap / 2, top - bh, (i + 1) * slot - gap / 2, top, p);
            }
            p.setColor(ink3);
            p.setTextSize(dp(9));
            p.setTypeface(Typeface.MONOSPACE);
            for (int i = 0; i < 24; i += 6) c.drawText(String.format(Locale.ROOT, "%02d", i), i * slot + gap, h - dp(2), p);
        }
    }

    // ================= small helpers =================

    private static String dur(long ms) {
        long m = Math.round(ms / 60000.0);
        return m < 60 ? m + "m" : (m / 60) + "h " + (m % 60) + "m";
    }

    private LinearLayout cardBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(box(card, line, 18));
        return l;
    }

    private TextView label(String s, int colour) {
        TextView t = text(11, colour, true);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setLetterSpacing(0.16f);
        t.setText(s);
        return t;
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
