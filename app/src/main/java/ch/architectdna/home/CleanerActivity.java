package ch.architectdna.home;

import android.app.Activity;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * ARC Cleaner — reclaims the storage ARC's own updates leave behind. Every interrupted self-update
 * stages a ~50 MB APK copy in a PackageInstaller session; on a flaky connection those pile up (the
 * "100 copies" problem). This abandons every stale staged session and clears ARC's cache and
 * leftover update files, with a before/after count of what it freed. Nothing of yours is touched —
 * only ARC's own throwaway files.
 */
public class CleanerActivity extends Activity {

    private static final int PAPER = 0xFFFAF8F1, INK = 0xFF1B1613, INK2 = 0xFF6A5F54, RUST = 0xFFB24A2E, LINE = 0xFFE2D9C8, CARD = 0xFFFFFFFF;
    private LinearLayout col;
    private TextView bigNum, sub, btn;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(PAPER);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, dp(28), pad, dp(28));
        sv.addView(col);
        setContentView(sv);
        getWindow().setStatusBarColor(PAPER);

        TextView eye = t("🧹  ARC CLEANER", 12, RUST, true);
        eye.setLetterSpacing(0.18f);
        col.addView(eye);
        col.addView(sp(6));
        col.addView(t("Reclaim the space ARC's updates leave behind.", 15, INK2, false));

        bigNum = t("…", 48, INK, true);
        bigNum.setPadding(0, dp(18), 0, 0);
        col.addView(bigNum);
        sub = t("scanning…", 13, INK2, false);
        col.addView(sub);

        col.addView(sp(18));
        btn = new TextView(this);
        btn.setText(L.t("Clean now"));
        btn.setTextColor(PAPER);
        btn.setTextSize(15);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(pill(INK));
        btn.setPadding(0, dp(14), 0, dp(14));
        btn.setOnClickListener(v -> clean());
        col.addView(btn, lp());

        rescan();
    }

    /* ---- scan ---- */
    private long staleFiles() {
        long t = 0;
        for (File dir : new File[]{getCacheDir(), getExternalCacheDir(), getFilesDir()}) {
            if (dir == null) continue;
            File[] k = dir.listFiles();
            if (k == null) continue;
            for (File f : k) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".apk") || n.endsWith(".delta") || n.endsWith(".bin") || n.startsWith("update")) t += size(f);
            }
        }
        return t;
    }
    private long cacheSize() { return size(getCacheDir()) + size(getExternalCacheDir()); }
    private int staleSessions() {
        try { return getPackageManager().getPackageInstaller().getMySessions().size(); }
        catch (Exception e) { return 0; }
    }

    private void rescan() {
        new Thread(() -> {
            final long files = staleFiles(), cache = cacheSize();
            final int sess = staleSessions();
            final long total = cache + files;            // sessions live in /data, size not countable here
            runOnUiThread(() -> {
                bigNum.setText(Formatter.formatShortFileSize(this, total));
                StringBuilder s = new StringBuilder();
                s.append(sess).append(sess == 1 ? " staged update copy" : " staged update copies");
                s.append(" · ").append(Formatter.formatShortFileSize(this, cache)).append(" cache");
                if (files > 0) s.append(" · ").append(Formatter.formatShortFileSize(this, files)).append(" leftover APKs");
                sub.setText(s.toString());
                btn.setEnabled(true);
                btn.setText(sess > 0 || total > 0 ? L.t("Clean now") : L.t("Already clean ✓"));
            });
        }, "arc-cleaner-scan").start();
    }

    /* ---- clean ---- */
    private void clean() {
        btn.setEnabled(false);
        btn.setText(L.t("Cleaning…"));
        new Thread(() -> {
            long before = cacheSize() + staleFiles();
            int killed = 0;
            // 1) abandon every staged install session — this is where the 50 MB copies hide
            try {
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                for (PackageInstaller.SessionInfo si : pi.getMySessions()) {
                    try { pi.abandonSession(si.getSessionId()); killed++; } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            // 2) delete leftover update files and clear the cache dirs
            wipeStale(getCacheDir()); wipeStale(getExternalCacheDir()); wipeStale(getFilesDir());
            wipeAll(getCacheDir()); wipeAll(getExternalCacheDir());
            final long freed = Math.max(0, before - (cacheSize() + staleFiles()));
            final int k = killed;
            runOnUiThread(() -> {
                bigNum.setText(Formatter.formatShortFileSize(this, freed));
                sub.setText(L.t("freed · ") + k + (k == 1 ? L.t(" staged copy cleared") : L.t(" staged copies cleared")));
                btn.setText(L.t("Clean again"));
                btn.setEnabled(true);
                btn.postDelayed(this::rescan, 1400);
            });
        }, "arc-cleaner-clean").start();
    }

    private void wipeStale(File dir) {
        if (dir == null) return;
        File[] k = dir.listFiles();
        if (k == null) return;
        for (File f : k) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".apk") || n.endsWith(".delta") || n.endsWith(".bin") || n.startsWith("update")) del(f);
        }
    }
    private void wipeAll(File dir) {          // clear cache contents (safe: regenerated on demand)
        if (dir == null) return;
        File[] k = dir.listFiles();
        if (k == null) return;
        for (File f : k) del(f);
    }

    /* ---- util ---- */
    private static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long t = 0; File[] k = f.listFiles();
        if (k != null) for (File c : k) t += size(c);
        return t;
    }
    private static void del(File f) {
        if (f == null) return;
        if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File c : k) del(c); }
        try { f.delete(); } catch (Exception ignored) { }
    }

    private int dp(int v) { return Math.round(v * d); }
    private View sp(int h) { View v = new View(this); v.setMinimumHeight(dp(h)); return v; }
    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); return p;
    }
    private TextView t(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }
    private android.graphics.drawable.GradientDrawable pill(int c) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(c); g.setCornerRadius(dp(30)); return g;
    }
}
