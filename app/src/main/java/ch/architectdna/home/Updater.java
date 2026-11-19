package ch.architectdna.home;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates from this repository's GitHub releases (private repo, so it needs a read-only
 * fine-grained token the user pastes once; it stays in the app's private storage).
 * Installs through PackageInstaller; Android and Play Protect still show their own confirmation.
 */
final class Updater {

    interface Ui {
        /** Short status for the launcher; available = an install button makes sense. */
        void onUpdateState(String text, boolean available);
    }

    /** Live progress for the download animation. phase: 0 patch, 1 rebuild, 2 full APK / writing, 3 confirm, -1 failed. */
    interface Progress {
        void onProgress(int phase, long done, long total, int version);
    }

    volatile Progress progress;
    private long lastProg;

    private void prog(int phase, long done, long total, boolean force) {
        Progress p = progress;
        if (p == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastProg < 60) return;
        lastProg = now;
        int v = latest;
        main.post(() -> p.onProgress(phase, done, total, v));
    }

    static final String ACTION_STATUS = "ch.architectdna.home.UPDATE_STATUS";
    private static final String REPO = "https://api.github.com/repos/architect-dna-ch/arc-dna-home";
    /** Public download mirror: full APK only, no token, robust CDN — so updates work on mobile data
     *  even when the private repo's signed asset download stalls. Built by build.yml's mirror step. */
    private static final String MIRROR = "https://api.github.com/repos/architect-dna-ch/arc-releases";
    private static final long CHECK_EVERY_MS = 6 * 3600 * 1000L;

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private int latest;
    private long assetId;

    Updater(Activity activity, SharedPreferences prefs, Ui ui) {
        this.activity = activity;
        this.prefs = prefs;
        this.ui = ui;
    }

    /** The token, decrypted from the Keystore-sealed copy (an older plain copy is sealed on first read). */
    String token() {
        String plain = prefs.getString("ghToken", null);
        if (plain != null) {
            prefs.edit().putString("ghTokenSealed", SecureStore.seal(plain)).remove("ghToken").apply();
            return plain;
        }
        return SecureStore.open(prefs.getString("ghTokenSealed", ""));
    }

    /**
     * Copying a long token on a phone can bring along line breaks (where GitHub wrapped it),
     * spaces or zero-width characters; a GitHub token only ever contains letters, digits and _.
     */
    void setToken(String token) {
        String clean = token.replaceAll("[^A-Za-z0-9_]", "");
        prefs.edit().putString("ghTokenSealed", SecureStore.seal(clean)).remove("ghToken").remove("updateChecked").apply();
    }

    int current() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : versionCodeLegacy(info);
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static int versionCodeLegacy(PackageInfo info) {
        return info.versionCode;
    }

    /** Called on resume (throttled) or from the button (manual = always, with feedback). */
    /** Messages only when you asked (tapped the update line); background auto-updates stay silent. */
    private boolean loud;

    void check(boolean manual) {
        loud = manual;
        // Public repo needs no token; a private one uses the pasted token if there is one.
        if (busy) return;
        if (!manual && System.currentTimeMillis() - prefs.getLong("updateChecked", 0) < CHECK_EVERY_MS) {
            showState();
            return;
        }
        busy = true;
        ui.onUpdateState(L.t("Suche Updates …"), false);
        new Thread(() -> {
            try {
                HttpURLConnection c = open(REPO + "/releases/latest", "application/vnd.github+json", true);
                int code = c.getResponseCode();
                if (code == 401) throw new IOException(L.t("Token ungültig oder abgelaufen (401) – neu kopieren"));
                if (code == 403 || code == 404) throw new IOException(L.t("Token ohne Zugriff (") + code + L.t(") – Resource owner architect-dna-ch und Freigabe prüfen"));
                if (code != 200) throw new IOException(L.t("GitHub antwortet ") + code);
                JSONObject release = new JSONObject(readAll(c.getInputStream()));
                String tag = release.getString("tag_name");                 // e.g. v1.0.12
                int n = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
                long id = 0, delta = 0;
                String mine = "delta-v1.0." + current() + ".bin";      // a small patch from exactly this version
                JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) id = a.getLong("id");
                    if (a.getString("name").equals(mine)) delta = a.getLong("id");
                }
                latest = n;
                assetId = id;
                deltaId = delta;
                prefs.edit().putLong("updateChecked", System.currentTimeMillis()).putInt("updateLatest", n)
                    .putLong("updateAsset", id).putLong("updateDelta", delta).putInt("updateDeltaFor", current()).apply();
                main.post(() -> {
                    showState();
                    if (manual && n <= current()) toast(L.t("ARC-DNA ist aktuell (1.0.") + current() + ").");
                });
                if (n <= current()) updateSiblings(manual);
            } catch (Throwable e) {          // Throwable, so an OutOfMemoryError is handled, not crashing
                DiagLog.log("update check failed: " + e.getMessage());
                // Not again on every unlock: try once an hour until it works.
                prefs.edit().putLong("updateChecked", System.currentTimeMillis() - CHECK_EVERY_MS + 3_600_000L).apply();
                main.post(() -> {
                    ui.onUpdateState(L.t("Update-Prüfung fehlgeschlagen · tippen = im Browser laden"), false);
                    if (manual) {
                        toast(e.getMessage() + L.t(" – lade im Browser"));
                        openInBrowser();
                    }
                });
            } finally {
                busy = false;
            }
        }).start();
    }

    /** The other ARC apps (separate APKs) have no updater of their own: ARC-DNA keeps them current. */
    private static final String[][] SIBLINGS = {
        {"ch.architectdna.stats", "stats-v"}, {"ch.architectdna.orbit", "orbit-v"}, {"ch.architectdna.browser", "browser-v"}};

    private void updateSiblings(boolean manual) {
        try {
            HttpURLConnection c = open(REPO + "/releases?per_page=40", "application/vnd.github+json", true);
            if (c.getResponseCode() != 200) return;
            JSONArray all = new JSONArray(readAll(c.getInputStream()));
            for (String[] sib : SIBLINGS) {
                long installed;
                try {
                    PackageInfo pi = activity.getPackageManager().getPackageInfo(sib[0], 0);
                    installed = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : versionCodeLegacy(pi);
                } catch (Exception e) {
                    continue;                               // not installed: never installs anything new by itself
                }
                int best = 0;
                long asset = 0;
                for (int i = 0; i < all.length(); i++) {
                    JSONObject r = all.getJSONObject(i);
                    String tag = r.getString("tag_name");
                    if (!tag.startsWith(sib[1])) continue;
                    int n = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));   // stats-v0.14 → 14 = versionCode
                    if (n <= best) continue;
                    JSONArray as = r.getJSONArray("assets");
                    for (int k = 0; k < as.length(); k++)
                        if (as.getJSONObject(k).getString("name").endsWith(".apk")) { best = n; asset = as.getJSONObject(k).getLong("id"); }
                }
                if (best > installed && asset != 0) {
                    DiagLog.log("update sibling " + sib[0] + ": " + installed + " → " + best);
                    if (manual) main.post(() -> toast(L.t("Aktualisiert ") + sib[0].substring(sib[0].lastIndexOf('.') + 1) + " …"));
                    installSibling(asset, sib[0]);
                }
            }
        } catch (Exception e) {
            DiagLog.log("sibling update failed: " + e.getMessage());
        }
    }

    /** Same as install(), for another ARC app; Android asks once, later updates go through quietly. */
    private void installSibling(long id, String pkg) throws Exception {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) return;
        HttpURLConnection c = download(id);
        long length = c.getContentLengthLong();
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int sid = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sid);
        try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite(pkg + ".apk", 0, length)) {
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) != -1; ) out.write(buf, 0, r);
            session.fsync(out);
        } catch (Exception e) {
            session.abandon();
            throw e;
        }
        Intent status = new Intent(activity, activity.getClass()).setAction(ACTION_STATUS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        session.commit(PendingIntent.getActivity(activity, 8 + Math.abs(pkg.hashCode() % 50), status, flags).getIntentSender());
        session.close();
    }

    /** Back after our own update: the version we just reached (once), else 0. */
    int justUpdatedTo() {
        int p = prefs.getInt("updatePending", 0);
        if (p > 0 && current() >= p) { prefs.edit().remove("updatePending").apply(); return p; }
        if (p > 0 && System.currentTimeMillis() - prefs.getLong("updatePendingAt", 0) > 86_400_000L) prefs.edit().remove("updatePending").apply();
        return 0;
    }

    private void showState() {
        if (latest == 0) {
            latest = prefs.getInt("updateLatest", 0);
            assetId = prefs.getLong("updateAsset", 0);
            deltaId = prefs.getInt("updateDeltaFor", -1) == current() ? prefs.getLong("updateDelta", 0) : 0;
        }
        if (latest > current() && assetId != 0) ui.onUpdateState("Update 1.0." + latest + L.t(" → installieren"), true);
        else ui.onUpdateState("ARC-DNA 1.0." + current() + L.t(" · aktuell"), false);
    }

    /** Downloads the APK into a PackageInstaller session; Android then asks for confirmation. */
    void install() {
        if (busy || assetId == 0) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            toast(L.t("ARC-DNA erlauben, Apps zu installieren – dann nochmal tippen."));
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        busy = true;
        ui.onUpdateState(L.t("Lädt Update …"), false);
        prog(deltaId != 0 ? 0 : 2, 0, 0, true);
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                // First choice: the small patch from the installed version (a few MB instead of the whole APK).
                java.io.File built = deltaId != 0 ? fromDelta() : null;
                InputStream source;
                long length;
                HttpURLConnection c = null;
                if (built != null) {
                    source = new java.io.FileInputStream(built);
                    length = built.length();
                } else {
                    c = openFullApk();
                    source = c.getInputStream();
                    length = c.getContentLengthLong();
                }

                PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(activity.getPackageName());
                // Once ARC-DNA installed itself (it's the installer of record), Android 12+ updates
                // without asking again – the first in-app update still shows Android's confirmation.
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = installer.createSession(params);
                session = installer.openSession(id);
                try (InputStream in = source;
                     OutputStream out = session.openWrite("ARC-DNA-Home.apk", 0, length)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    for (int r; (r = in.read(buf)) != -1; ) {
                        out.write(buf, 0, r);
                        done += r;
                        int pct = length > 0 ? (int) (done * 100 / length) : 0;
                        prog(built != null ? 1 : 2, done, length, false);
                        if (pct != lastPct) {
                            lastPct = pct;
                            main.post(() -> ui.onUpdateState((built != null ? L.t("Installiert Update … ") : L.t("Lädt Update … ")) + pct + " %", false));
                        }
                    }
                    session.fsync(out);
                }
                Intent status = new Intent(activity, activity.getClass()).setAction(ACTION_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getActivity(activity, 7, status, flags);
                session.commit(pending.getIntentSender());
                session.close();
                session = null;
                prog(3, 1, 1, true);
                prefs.edit().putInt("updatePending", latest).putLong("updatePendingAt", System.currentTimeMillis()).apply();
                main.post(() -> ui.onUpdateState(L.t("Installation bestätigen …"), false));
            } catch (Exception e) {
                if (session != null) session.abandon();
                prog(-1, 0, 0, true);
                main.post(() -> {
                    toast(e.getMessage());
                    showState();
                });
            } finally {
                busy = false;
            }
        }).start();
    }

    private long deltaId;

    /**
     * The full APK, from the public mirror first (no token, plain CDN — survives mobile data and
     * VPNs where the private repo's signed asset redirect stalls), the private release as fallback.
     */
    private HttpURLConnection openFullApk() throws IOException {
        try {
            HttpURLConnection m = open(MIRROR + "/releases?per_page=40", "application/vnd.github+json", false);
            if (m.getResponseCode() == 200) {
                JSONArray all = new JSONArray(readAll(m.getInputStream()));
                int best = -1; String url = null;
                for (int i = 0; i < all.length(); i++) {
                    JSONObject r = all.getJSONObject(i);
                    String tag = r.getString("tag_name");
                    if (!tag.startsWith("v1.0.")) continue;           // the hub's own tags, not a sibling's
                    int n = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
                    if (n <= best || n <= current()) continue;
                    JSONArray as = r.getJSONArray("assets");
                    for (int k = 0; k < as.length(); k++) {
                        JSONObject a = as.getJSONObject(k);
                        if (a.getString("name").endsWith(".apk")) { best = n; url = a.getString("browser_download_url"); }
                    }
                }
                if (url != null) {
                    HttpURLConnection d = open(url, "application/octet-stream", false);
                    if (d.getResponseCode() == 200) return d;
                    d.disconnect();
                }
            }
        } catch (Exception mirrorMiss) {
            DiagLog.log("update mirror unavailable, using private release: " + mirrorMiss.getMessage());
        }
        return download(assetId);
    }

    /** A release asset; GitHub redirects to a signed URL that must be fetched without the token. */
    private HttpURLConnection download(long id) throws IOException {
        HttpURLConnection c = open(REPO + "/releases/assets/" + id, "application/octet-stream", true);
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        if (code / 100 == 3) {
            String location = c.getHeaderField("Location");
            c.disconnect();
            c = open(location, "application/octet-stream", false);
            code = c.getResponseCode();
        }
        if (code != 200) throw new IOException(L.t("Download fehlgeschlagen (") + code + ")");
        return c;
    }

    /**
     * Downloads the patch from the installed version and rebuilds the new APK from the installed
     * one (see tools/apkdelta.py): unchanged parts are copied, the rest comes with the patch. Only
     * used if the result's SHA-256 matches; any problem → null, and the full APK is loaded instead.
     */
    private java.io.File fromDelta() {
        java.io.File out = new java.io.File(activity.getCacheDir(), "update.apk");
        try {
            HttpURLConnection c = download(deltaId);
            long total = c.getContentLengthLong();
            main.post(() -> ui.onUpdateState(L.t("Lädt kleines Update (") + Math.max(1, total / 1_000_000) + " MB) …", false));
            java.io.File patch = new java.io.File(activity.getCacheDir(), "update.delta");
            try (InputStream in = c.getInputStream(); OutputStream o = new java.io.FileOutputStream(patch)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                prog(0, 0, total, true);
                for (int r; (r = in.read(buf)) != -1; ) {
                    o.write(buf, 0, r);
                    got += r;
                    prog(0, got, total, false);
                }
                prog(0, got, total, true);
            }
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            byte[] want = new byte[32];
            long size;
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                     new java.util.zip.GZIPInputStream(new java.io.FileInputStream(patch)), 1 << 16));
                 java.io.RandomAccessFile old = new java.io.RandomAccessFile(activity.getApplicationInfo().sourceDir, "r");
                 OutputStream o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out), 1 << 16)) {
                byte[] magic = new byte[5];
                in.readFully(magic);
                if (!"ARCD1".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) throw new IOException("kein Patch");
                size = in.readLong();
                in.readFully(want);
                byte[] buf = new byte[1 << 16];
                for (int op; (op = in.readUnsignedByte()) != 2; ) {
                    if (op == 0) {
                        long off = in.readLong();
                        int n = in.readInt();
                        old.seek(off);
                        while (n > 0) {
                            int r = old.read(buf, 0, Math.min(n, buf.length));
                            if (r < 0) throw new IOException(L.t("Basis zu kurz"));
                            o.write(buf, 0, r);
                            sha.update(buf, 0, r);
                            n -= r;
                        }
                    } else if (op == 1) {
                        int n = in.readInt();
                        while (n > 0) {
                            int r = in.read(buf, 0, Math.min(n, buf.length));
                            if (r < 0) throw new IOException(L.t("Patch zu kurz"));
                            o.write(buf, 0, r);
                            sha.update(buf, 0, r);
                            n -= r;
                        }
                    } else {
                        throw new IOException(L.t("Patch kaputt"));
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            patch.delete();
            if (out.length() != size || !java.security.MessageDigest.isEqual(sha.digest(), want)) throw new IOException(L.t("Prüfsumme falsch"));
            DiagLog.log("update: patch " + total / 1000 + " kB instead of the full APK");
            return out;
        } catch (Exception e) {
            DiagLog.log("update: patch failed (" + e.getMessage() + "), loading the full APK");
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }
    }

    /** Result of a session commit, delivered to MainActivity.onNewIntent. */
    @SuppressWarnings("deprecation")
    void onStatus(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            toast(L.t("Update installiert."));
            relaunch();
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast(L.t("Update abgebrochen") + (msg != null ? ": " + msg : "."));
            showState();
        }
    }

    /**
     * After we install our own new APK, this live process still runs the OLD code — the new version
     * only takes effect once a fresh process starts, which is why you used to have to reopen ARC-DNA
     * by hand. So we book the launcher to reopen in a moment (a system-fired alarm, which is allowed
     * to come to the front even as we go away) and then exit, letting the new process take over. The
     * fresh HubActivity then plays the ✓ "updated" animation on its own via justUpdatedTo().
     */
    private void relaunch() {
        Context app = activity.getApplicationContext();
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launch == null) { showState(); return; }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            int f = PendingIntent.FLAG_CANCEL_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(app, 0x1DEA, launch, f);
            AlarmManager am = app.getSystemService(AlarmManager.class);
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 450, pi);   // set(), not setExact() → no exact-alarm permission needed
            DiagLog.log("update: installed → relaunching a fresh process");
            new Handler(Looper.getMainLooper()).postDelayed(() -> Runtime.getRuntime().exit(0), 700);
        } catch (Exception e) {
            DiagLog.log("relaunch failed, staying put: " + e);
            showState();   // worst case = the old behaviour: it's installed, reopen ARC-DNA yourself
        }
    }

    private HttpURLConnection open(String url, String accept, boolean withToken) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (withToken && !token().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token());
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = s.read(buf)) != -1; ) out.write(buf, 0, r);
            return out.toString("UTF-8");
        }
    }

    /** Fallback when the in-app check fails (e.g. an expired token): the newest APK straight from GitHub in the browser. */
    private void openInBrowser() {
        try {
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/architect-dna-ch/arc-dna-home/releases/latest/download/ARC-DNA-Home.apk"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }

    private void toast(String s) {
        DiagLog.log("update: " + s);
        if (!loud) return;
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }
}
