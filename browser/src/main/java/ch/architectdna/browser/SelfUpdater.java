package ch.architectdna.browser;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * This app updates itself from the GitHub releases of its own module (tag prefix below), with no
 * other ARC app involved. The repository is private, so it needs the read-only token once; it is
 * kept encrypted with a Keystore key. Android asks to confirm the first self-update, then it is quiet.
 */
final class SelfUpdater {

    interface Ui { void state(String text, boolean actionNeeded); }

    static final String ACTION = "ch.architectdna.browser.SELF_UPDATE";
    /** Public mirror first (no token needed), the private repo only as a fallback with the token. */
    private static final String MIRROR = "https://api.github.com/repos/architect-dna-ch/arc-releases";
    private static final String REPO = "https://api.github.com/repos/architect-dna-ch/arc-dna-home";
    private volatile String from = MIRROR;
    private static final long EVERY_MS = 6 * 3600 * 1000L;

    private final Activity a;
    private final String prefix;
    private final Ui ui;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private UpdateView helix;
    private long lastProg;

    /** The same 🧬 helix card as ARC-DNA, over this app's window, while the update downloads. */
    private void progress(int phase, long done, long total, int version, boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastProg < 60) return;
        lastProg = now;
        main.post(() -> {
            if (a.isFinishing()) return;
            android.view.ViewGroup decor = (android.view.ViewGroup) a.getWindow().getDecorView();
            if (helix == null || helix.getParent() != decor) {
                helix = new UpdateView(a);
                float dn = a.getResources().getDisplayMetrics().density;
                android.widget.FrameLayout.LayoutParams l = new android.widget.FrameLayout.LayoutParams(-1, Math.round(UpdateView.heightDp() * dn), android.view.Gravity.TOP);
                l.leftMargin = l.rightMargin = Math.round(12 * dn);
                l.topMargin = Math.round(48 * dn);
                decor.addView(helix, l);
                UpdateView mine = helix;
                mine.setOnClickListener(v -> decor.removeView(mine));
            }
            helix.update(phase, done, total, version);
            if (helix.isFinished()) {
                UpdateView v = helix;
                helix = null;
                v.postDelayed(() -> v.animate().alpha(0f).setDuration(280).withEndAction(() -> decor.removeView(v)).start(), phase == -1 ? 2600 : 1600);
            }
        });
    }

    SelfUpdater(Activity a, String tagPrefix, Ui ui) {
        this.a = a;
        this.prefix = tagPrefix;
        this.ui = ui;
        prefs = a.getSharedPreferences("selfupdate", Activity.MODE_PRIVATE);
    }

    String token() { return SecureStore.open(prefs.getString("tok", "")); }

    boolean hasToken() { return !token().isEmpty(); }

    void setToken(String raw) {
        String t = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "");
        prefs.edit().putString("tok", SecureStore.seal(t)).putLong("checked", 0).apply();
    }

    long current() {
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : legacy(pi);
        } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("deprecation")
    private static long legacy(PackageInfo pi) { return pi.versionCode; }

    /** On every resume (at most every 6 h) and when asked. Installs by itself when there is something new. */
    void check(boolean manual) {
        if (busy) return;
        if (!manual && System.currentTimeMillis() - prefs.getLong("checked", 0) < EVERY_MS) { ui.state(L.t("Aktuell · 0.") + current(), false); return; }
        busy = true;
        ui.state(L.t("Suche Updates …"), false);
        new Thread(() -> {
            try {
                int best = 0;
                long asset = 0;
                for (String repo : new String[]{MIRROR, REPO}) {
                boolean priv = repo.equals(REPO);
                if (priv && (best > 0 || !hasToken())) break;    // the mirror had it, or no token for the fallback
                HttpURLConnection c = open(repo + "/releases?per_page=40", "application/vnd.github+json", priv);
                int code = c.getResponseCode();
                if (code != 200) {
                    if (priv) throw new IOException(L.t("Token ungültig oder ohne Zugriff (") + code + ")");
                    continue;
                }
                JSONArray all = new JSONArray(read(c.getInputStream()));
                for (int i = 0; i < all.length(); i++) {
                    JSONObject r = all.getJSONObject(i);
                    String tag = r.getString("tag_name");
                    if (!tag.startsWith(prefix)) continue;
                    int n = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
                    if (n <= best) continue;
                    JSONArray as = r.getJSONArray("assets");
                    for (int k = 0; k < as.length(); k++)
                        if (as.getJSONObject(k).getString("name").endsWith(".apk")) { best = n; asset = as.getJSONObject(k).getLong("id"); from = repo; }
                }
                }
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
                if (best > current() && asset != 0) {
                    if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                        main.post(() -> ui.state(L.t("Update bereit · Installieren erlauben →"), true));
                        return;
                    }
                    int v = best;
                    main.post(() -> ui.state(L.t("Lädt Update 0.") + v + " …", false));
                    install(asset, best);
                    main.post(() -> ui.state(L.t("Installiert 0.") + v + " …", false));
                } else {
                    main.post(() -> ui.state(L.t("Aktuell · 0.") + current(), false));
                }
            } catch (Exception e) {
                prefs.edit().putLong("checked", System.currentTimeMillis() - EVERY_MS + 3_600_000L).apply();
                String m = e.getMessage();
                main.post(() -> ui.state(L.t("Update-Prüfung fehlgeschlagen") + (m == null ? "" : ": " + m), true));
            } finally {
                busy = false;
            }
        }, "arc-self-update").start();
    }

    /** What a tap on the update line does when something is needed. */
    void fix(Runnable askToken) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (RuntimeException ignored) { }
            return;
        }
        check(true);
    }

    private void install(long id, int version) throws Exception {
        progress(2, 0, 0, version, true);
        try {
            installInner(id, version);
        } catch (Exception e) {
            progress(-1, 0, 0, version, true);
            throw e;
        }
    }

    private void installInner(long id, int version) throws Exception {
        HttpURLConnection c = open(from + "/releases/assets/" + id, "application/octet-stream", from.equals(REPO));
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        if (code / 100 == 3) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            c = open(loc, "application/octet-stream", false);   // the signed URL must be fetched without the token
            code = c.getResponseCode();
        }
        if (code != 200) throw new IOException("Download " + code);
        long length = c.getContentLengthLong();
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        PackageInstaller.Session s = pi.openSession(pi.createSession(params));
        try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("update.apk", 0, length)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            for (int r; (r = in.read(buf)) != -1; ) {
                out.write(buf, 0, r);
                done += r;
                progress(2, done, length, version, false);
            }
            progress(2, done, length, version, true);
            s.fsync(out);
        } catch (Exception e) {
            s.abandon();
            throw e;
        }
        progress(3, 1, 1, version, true);
        Intent status = new Intent(a, a.getClass()).setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        s.commit(PendingIntent.getActivity(a, 31, status, flags).getIntentSender());
        s.close();
    }

    /** The install result comes back to the activity (onNewIntent). */
    @SuppressWarnings("deprecation")
    void onStatus(Intent intent) {
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) a.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            ui.state(L.t("Update abgebrochen"), true);
        }
    }

    private HttpURLConnection open(String url, String accept, boolean withToken) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (withToken && hasToken()) c.setRequestProperty("Authorization", "Bearer " + token());
        return c;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = s.read(buf)) != -1; ) out.write(buf, 0, r);
            return out.toString("UTF-8");
        }
    }
}
