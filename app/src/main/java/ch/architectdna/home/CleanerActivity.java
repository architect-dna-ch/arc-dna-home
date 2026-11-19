package ch.architectdna.home;

import android.app.Activity;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ARC Cleaner — a smart cache cleaner that balances performance against storage.
 *
 * Clearing a cache you'll use tomorrow just makes the app slower today, so this never nukes blindly.
 * It fully clears ARC's own throwaway files (staged update copies, cache) automatically — those cost
 * nothing to lose. For every other app it reads cache size (StorageStatsManager) against how recently
 * you used it (UsageStats) and only flags the ones that are big AND cold: lots of space, no speed hit.
 * Android won't let a non-system app wipe another app's cache, so a flagged app is one tap from its
 * own Clear-cache screen. A Safe / Balanced / Aggressive dial sets how cold "cold" has to be.
 */
public class CleanerActivity extends Activity {

    private static final int PAPER = 0xFFFAF8F1, INK = 0xFF1B1613, INK2 = 0xFF6A5F54, INK3 = 0xFF9A9186,
        RUST = 0xFFB24A2E, LINE = 0xFFE2D9C8, CARD = 0xFFFFFFFF, GREEN = 0xFF2E7D55;
    private LinearLayout col, appsBox;
    private TextView bigNum, sub, btn, note;
    private final TextView[] balanceChips = new TextView[3];
    private int balance = 1;                 // 0 safe, 1 balanced, 2 aggressive
    private float d;
    private final List<AppRow> scanned = new ArrayList<>();
    private volatile boolean scanning;

    private static class AppRow { String pkg, label; long cache; long idleDays; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(PAPER);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(28), dp(20), dp(36));
        sv.addView(col);
        setContentView(sv);
        getWindow().setStatusBarColor(PAPER);

        TextView eye = t("🧹  ARC CLEANER", 12, RUST, true); eye.setLetterSpacing(0.18f);
        col.addView(eye);
        col.addView(sp(6));
        col.addView(t("Smart balance of performance and storage — clears the dead weight, keeps what makes apps fast.", 14, INK2, false));

        // ---- ARC's own junk ----
        LinearLayout card = cardBox();
        card.addView(t("ARC's own leftovers", 12, INK3, true));
        bigNum = t("…", 40, INK, true); bigNum.setPadding(0, dp(6), 0, 0); card.addView(bigNum);
        sub = t("scanning…", 13, INK2, false); card.addView(sub);
        btn = new TextView(this);
        btn.setText(L.t("Clean ARC now")); btn.setTextColor(PAPER); btn.setTextSize(15);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        btn.setGravity(Gravity.CENTER); btn.setBackground(pill(INK)); btn.setPadding(0, dp(13), 0, dp(13));
        btn.setOnClickListener(v -> cleanArc());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2); blp.topMargin = dp(12);
        card.addView(btn, blp);
        col.addView(card);

        // ---- balance dial ----
        col.addView(sp(20));
        col.addView(t("How aggressive?", 12, INK3, true));
        LinearLayout dial = new LinearLayout(this); dial.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2); dlp.topMargin = dp(8);
        String[] names = {L.t("Safe"), L.t("Balanced"), L.t("Aggressive")};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView c = new TextView(this);
            c.setText(names[i]); c.setTextSize(13.5f); c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(10), 0, dp(10));
            c.setOnClickListener(v -> { balance = idx; paintBalance(); renderApps(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.leftMargin = i == 0 ? 0 : dp(6);
            dial.addView(c, p);
            balanceChips[i] = c;
        }
        col.addView(dial, dlp);
        hintView = hint();
        col.addView(hintView);

        // ---- other apps ----
        col.addView(sp(20));
        col.addView(t("Biggest caches worth clearing", 12, INK3, true));
        appsBox = new LinearLayout(this); appsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(appsBox);
        note = t("", 12, INK3, false); note.setPadding(0, dp(12), 0, 0); col.addView(note);

        paintBalance();
        rescanArc();
        scanApps();
    }

    private TextView hint() {
        String[] h = {
            L.t("Only apps you haven't opened in ~2 weeks. Nothing you use regularly."),
            L.t("Big caches from apps you haven't opened in a few days. A light speed cost."),
            L.t("Every cold app with a sizable cache. Most space back, apps re-cache on next open.")};
        TextView v = t(h[balance], 12, INK3, false); v.setPadding(0, dp(8), 0, 0); return v;
    }
    private void paintBalance() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == balance;
            balanceChips[i].setBackground(pill(on ? INK : CARD));
            balanceChips[i].setTextColor(on ? PAPER : INK2);
            if (!on) balanceChips[i].setBackground(pillStroke());
        }
        // refresh the hint line to match the chosen mode
        int idx = col.indexOfChild(hintView);
        if (idx >= 0) { col.removeViewAt(idx); hintView = hint(); col.addView(hintView, idx); }
    }
    private View hintView;

    /* ---- ARC self-clean (safe, automatic) ---- */
    private long staleFiles() {
        long tot = 0;
        for (File dir : new File[]{getCacheDir(), getExternalCacheDir(), getFilesDir()}) {
            if (dir == null) continue; File[] k = dir.listFiles(); if (k == null) continue;
            for (File f : k) { String n = f.getName().toLowerCase();
                if (n.endsWith(".apk") || n.endsWith(".delta") || n.endsWith(".bin") || n.startsWith("update")) tot += size(f); }
        }
        return tot;
    }
    private long cacheSize() { return size(getCacheDir()) + size(getExternalCacheDir()); }
    private int staleSessions() {
        try { return getPackageManager().getPackageInstaller().getMySessions().size(); } catch (Exception e) { return 0; }
    }
    private void rescanArc() {
        new Thread(() -> {
            final long files = staleFiles(), cache = cacheSize(); final int sess = staleSessions();
            final long total = cache + files;
            runOnUiThread(() -> {
                bigNum.setText(Formatter.formatShortFileSize(this, total));
                StringBuilder s = new StringBuilder();
                s.append(sess).append(sess == 1 ? " staged update copy" : " staged update copies");
                s.append(" · ").append(Formatter.formatShortFileSize(this, cache)).append(" cache");
                if (files > 0) s.append(" · ").append(Formatter.formatShortFileSize(this, files)).append(" leftover APKs");
                sub.setText(s.toString());
                btn.setEnabled(true);
                btn.setText(sess > 0 || total > 0 ? L.t("Clean ARC now") : L.t("ARC already clean ✓"));
            });
        }, "arc-clean-scan").start();
    }
    private void cleanArc() {
        btn.setEnabled(false); btn.setText(L.t("Cleaning…"));
        new Thread(() -> {
            long before = cacheSize() + staleFiles(); int killed = 0;
            try { PackageInstaller pi = getPackageManager().getPackageInstaller();
                for (PackageInstaller.SessionInfo si : pi.getMySessions()) { try { pi.abandonSession(si.getSessionId()); killed++; } catch (Exception ig) {} } } catch (Exception ig) {}
            wipeStale(getCacheDir()); wipeStale(getExternalCacheDir()); wipeStale(getFilesDir());
            wipeAll(getCacheDir()); wipeAll(getExternalCacheDir());
            final long freed = Math.max(0, before - (cacheSize() + staleFiles())); final int k = killed;
            runOnUiThread(() -> {
                bigNum.setText(Formatter.formatShortFileSize(this, freed));
                sub.setText(L.t("freed · ") + k + (k == 1 ? L.t(" staged copy cleared") : L.t(" staged copies cleared")));
                btn.setText(L.t("Clean again")); btn.setEnabled(true); btn.postDelayed(this::rescanArc, 1400);
            });
        }, "arc-clean-run").start();
    }

    /* ---- smart per-app analysis ---- */
    private void scanApps() {
        if (scanning) return; scanning = true;
        appsBox.removeAllViews(); appsBox.addView(t(L.t("Reading caches…"), 13, INK3, false));
        new Thread(() -> {
            List<AppRow> rows = new ArrayList<>();
            boolean haveAccess = false;
            try {
                StorageStatsManager ssm = (StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
                UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                Map<String, UsageStats> use = usm.queryAndAggregateUsageStats(now - 60L * 86400_000L, now);
                PackageManager pm = getPackageManager();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (ai.packageName.equals(getPackageName())) continue;
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                    long cache;
                    try { StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle());
                        cache = st.getCacheBytes(); haveAccess = true;
                    } catch (Exception e) { continue; }
                    if (cache < 2L * 1024 * 1024) continue;        // ignore anything under 2 MB
                    AppRow r = new AppRow(); r.pkg = ai.packageName; r.label = String.valueOf(pm.getApplicationLabel(ai));
                    r.cache = cache;
                    UsageStats u = use.get(ai.packageName);
                    long last = u == null ? 0 : u.getLastTimeUsed();
                    r.idleDays = last <= 0 ? 999 : Math.max(0, (now - last) / 86400_000L);
                    rows.add(r);
                }
            } catch (Exception e) { /* no access */ }
            Collections.sort(rows, (a, b) -> Long.compare(b.cache, a.cache));
            final boolean access = haveAccess;
            scanned.clear(); scanned.addAll(rows);
            runOnUiThread(() -> { scanning = false; if (!access) renderNoAccess(); else renderApps(); });
        }, "arc-clean-apps").start();
    }

    private int idleThreshold() { return balance == 0 ? 14 : balance == 1 ? 3 : 1; }
    private long cacheThreshold() { return balance == 0 ? 20L*1024*1024 : balance == 1 ? 20L*1024*1024 : 8L*1024*1024; }

    private void renderApps() {
        appsBox.removeAllViews();
        long total = 0; int shown = 0;
        for (AppRow r : scanned) {
            boolean flag = r.idleDays >= idleThreshold() && r.cache >= cacheThreshold();
            if (!flag) continue;
            total += r.cache; shown++;
            appsBox.addView(appRow(r, true));
        }
        // also show a few "keep" ones for context (used recently, big cache)
        int keepShown = 0;
        for (AppRow r : scanned) {
            boolean flag = r.idleDays >= idleThreshold() && r.cache >= cacheThreshold();
            if (flag || r.cache < cacheThreshold() || keepShown >= 3) continue;
            appsBox.addView(appRow(r, false)); keepShown++;
        }
        if (shown == 0 && keepShown == 0) appsBox.addView(t(L.t("Nothing worth clearing — your caches are pulling their weight."), 13, INK3, false));
        note.setText(shown == 0 ? L.t("Android only lets ARC wipe its own cache. Tap an app to clear its cache yourself.")
            : (L.t("≈ ") + Formatter.formatShortFileSize(this, total) + L.t(" across ") + shown
               + L.t(" cold apps. Android won't let ARC wipe them — tap one to clear it.")));
    }

    private View appRow(AppRow r, boolean clear) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(pillStroke());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2); rlp.topMargin = dp(9);
        row.setLayoutParams(rlp);

        LinearLayout txt = new LinearLayout(this); txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(t(r.label, 15, INK, true));
        String age = r.idleDays >= 999 ? L.t("never opened") : r.idleDays == 0 ? L.t("used today") : (L.t("idle ") + r.idleDays + L.t("d"));
        txt.addView(t(Formatter.formatShortFileSize(this, r.cache) + " · " + age, 12, INK2, false));
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView verdict = t(clear ? L.t("CLEAR →") : L.t("KEEP"), 12, clear ? RUST : GREEN, true);
        row.addView(verdict);
        if (clear) row.setOnClickListener(v -> openAppStorage(r.pkg));
        return row;
    }

    private void renderNoAccess() {
        appsBox.removeAllViews();
        appsBox.addView(t(L.t("To analyse other apps' caches, ARC needs Usage access."), 14, INK, false));
        TextView grant = t(L.t("Grant usage access →"), 14, RUST, true); grant.setPadding(0, dp(10), 0, 0);
        grant.setOnClickListener(v -> { try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } catch (Exception e) {} });
        appsBox.addView(grant);
        note.setText("");
    }

    private void openAppStorage(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); } catch (Exception ig) {}
        }
    }

    @Override protected void onResume() { super.onResume(); if (!scanning && !scanned.isEmpty()) { rescanArc(); scanApps(); } }

    /* ---- util ---- */
    private void wipeStale(File dir) { if (dir == null) return; File[] k = dir.listFiles(); if (k == null) return;
        for (File f : k) { String n = f.getName().toLowerCase();
            if (n.endsWith(".apk") || n.endsWith(".delta") || n.endsWith(".bin") || n.startsWith("update")) del(f); } }
    private void wipeAll(File dir) { if (dir == null) return; File[] k = dir.listFiles(); if (k == null) return; for (File f : k) del(f); }
    private static long size(File f) { if (f == null || !f.exists()) return 0; if (f.isFile()) return f.length();
        long tot = 0; File[] k = f.listFiles(); if (k != null) for (File c : k) tot += size(c); return tot; }
    private static void del(File f) { if (f == null) return;
        if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File c : k) del(c); }
        try { f.delete(); } catch (Exception ig) {} }

    private int dp(int v) { return Math.round(v * d); }
    private View sp(int h) { View v = new View(this); v.setMinimumHeight(dp(h)); return v; }
    private LinearLayout cardBox() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16)); c.setBackground(pillStroke());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(16); c.setLayoutParams(p);
        return c;
    }
    private TextView t(String s, float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        if (color == INK3 && bold) v.setLetterSpacing(0.08f);
        return v;
    }
    private android.graphics.drawable.GradientDrawable pill(int c) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(c); g.setCornerRadius(dp(14)); return g;
    }
    private android.graphics.drawable.GradientDrawable pillStroke() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(CARD); g.setCornerRadius(dp(14)); g.setStroke(Math.max(1, dp(1)), LINE); return g;
    }
}
