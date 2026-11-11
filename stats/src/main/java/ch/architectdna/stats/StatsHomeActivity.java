package ch.architectdna.stats;

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
import android.view.View;
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
 * ARC-DNA Statistik — the second home. No icon wall: it ranks your apps by a score built from
 * how much you use them (last 7 days), how recently, and whether this hour of the day matches
 * their pattern. The three weights are yours to tune. All numbers come from Android's usage
 * events and never leave the phone.
 */
public class StatsHomeActivity extends Activity {

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
    private LinearLayout col, accessCard, predBox, topBox, allBox;
    private TextView dateTv, screenTv, hourHint;
    private HourChart chart;
    private final List<App> apps = new ArrayList<>();
    private boolean loading;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("stats", MODE_PRIVATE);
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

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(paper);
        sv.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(28));
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, bo;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; bo = i.bottom;
            } else {
                l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight(); bo = in.getSystemWindowInsetBottom();
            }
            col.setPadding(l + dp(20), t + dp(20), r + dp(20), bo + dp(28));
            return in;
        });

        // ---- header: eyebrow, clock, date ----
        col.addView(label("ARC·DNA · STATISTIK-HOME", rust));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(ink);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        clock.setLetterSpacing(-0.04f);
        col.addView(clock);
        dateTv = text(15, ink2, false);
        dateTv.setTypeface(Typeface.MONOSPACE);
        col.addView(dateTv);

        // ---- usage access prompt (hidden once granted) ----
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
        btn.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (RuntimeException ignored) { }
        });
        accessCard.addView(btn, lp(-2, -2, 0, 0, 0, 0));
        accessCard.setVisibility(View.GONE);
        col.addView(accessCard, lp(-1, -2, 0, dp(16), 0, 0));

        // ---- now: the predicted apps ----
        col.addView(label("JETZT WAHRSCHEINLICH", ink3), lp(-1, -2, 0, dp(22), 0, dp(8)));
        predBox = new LinearLayout(this);
        predBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(predBox);

        // ---- the model: three weights ----
        LinearLayout model = cardBox();
        model.addView(label("MODELL — AUSTÜFTELN", ink3));
        model.addView(slider("Häufigkeit", "wF", 45));
        model.addView(slider("Kürzlich", "wR", 25));
        model.addView(slider("Tageszeit", "wH", 30));
        col.addView(model, lp(-1, -2, 0, dp(14), 0, 0));

        // ---- today's screen time + top apps ----
        LinearLayout today = cardBox();
        today.addView(label("HEUTE AM BILDSCHIRM", ink3));
        screenTv = text(32, ink, true);
        screenTv.setText("—");
        today.addView(screenTv);
        topBox = new LinearLayout(this);
        topBox.setOrientation(LinearLayout.VERTICAL);
        today.addView(topBox, lp(-1, -2, 0, dp(8), 0, 0));
        col.addView(today, lp(-1, -2, 0, dp(14), 0, 0));

        // ---- usage by hour ----
        LinearLayout hourCard = cardBox();
        hourCard.addView(label("NUTZUNG NACH STUNDE · 7 TAGE", ink3));
        chart = new HourChart();
        hourCard.addView(chart, lp(-1, dp(96), 0, dp(10), 0, 0));
        hourHint = text(11, ink3, false);
        hourHint.setTypeface(Typeface.MONOSPACE);
        hourCard.addView(hourHint, lp(-1, -2, 0, dp(6), 0, 0));
        col.addView(hourCard, lp(-1, -2, 0, dp(14), 0, 0));

        // ---- every app, ranked ----
        col.addView(label("ALLE APPS · NACH SCORE", ink3), lp(-1, -2, 0, dp(22), 0, dp(8)));
        allBox = new LinearLayout(this);
        allBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(allBox);

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        dateTv.setText(new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(new Date()));
        load();
    }

    /** A home screen has nowhere to go back to. */
    @Override
    public void onBackPressed() {
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
        double best = apps.isEmpty() ? 1 : Math.max(apps.get(0).score, 1e-9);

        // predicted: top 6 in two rows of three
        predBox.removeAllViews();
        int n = Math.min(6, apps.size());
        for (int row = 0; row * 3 < n; row++) {
            LinearLayout line3 = new LinearLayout(this);
            for (int k = 0; k < 3; k++) {
                int idx = row * 3 + k;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (k > 0) clp.leftMargin = dp(8);
                line3.addView(idx < n ? predCell(apps.get(idx), idx == 0, apps.get(idx).score / best) : new View(this), clp);
            }
            predBox.addView(line3, lp(-1, -2, 0, row == 0 ? 0 : dp(8), 0, 0));
        }

        // today's screen time + top five
        long todayAll = 0;
        List<App> byToday = new ArrayList<>();
        for (App a : apps) { todayAll += a.today; if (a.today > 0) byToday.add(a); }
        Collections.sort(byToday, (x, y) -> Long.compare(y.today, x.today));
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

        // every app, ranked
        allBox.removeAllViews();
        for (App a : apps) allBox.addView(appRow(a), lp(-1, -2, 0, 0, 0, dp(6)));
    }

    private View predCell(App a, boolean top, double frac) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(12), dp(12), dp(12), dp(12));
        cell.setBackground(box(card, top ? rust : line, 14));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView nm = text(14, ink, true);
        nm.setText(a.label);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(nm, lp(-1, -2, 0, dp(6), 0, 0));
        TextView why = text(11, ink2, false);
        why.setText(a.why);
        why.setLines(2);
        cell.addView(why);
        cell.addView(bar(frac, rust, dp(4)), lp(-1, dp(4), 0, dp(6), 0, 0));
        cell.setOnClickListener(v -> launch(a));
        return cell;
    }

    private View appRow(App a) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(box(card, line, 12));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        row.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView nm = text(14, ink, false);
        nm.setText(a.label);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.leftMargin = dp(12);
        row.addView(nm, nlp);
        TextView t = text(12, ink3, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setText(a.total > 0 ? dur(a.total / 7) + "/Tag" : "");
        row.addView(t);
        row.setOnClickListener(v -> launch(a));
        return row;
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

        HourChart() { super(StatsHomeActivity.this); }

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
