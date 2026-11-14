package ch.architectdna.home;

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

    static final String ACTION_STATUS = "ch.architectdna.home.UPDATE_STATUS";
    private static final String REPO = "https://api.github.com/repos/architect-dna-ch/arc-dna-home";
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
        ui.onUpdateState("Suche Updates …", false);
        new Thread(() -> {
            try {
                HttpURLConnection c = open(REPO + "/releases/latest", "application/vnd.github+json", true);
                int code = c.getResponseCode();
                if (code == 401) throw new IOException("Token ungültig oder abgelaufen (401) – neu kopieren");
                if (code == 403 || code == 404) throw new IOException("Token ohne Zugriff (" + code + ") – Resource owner architect-dna-ch und Freigabe prüfen");
                if (code != 200) throw new IOException("GitHub antwortet " + code);
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
                    if (manual && n <= current()) toast("ARC-DNA ist aktuell (1.0." + current() + ").");
                });
            } catch (Exception e) {
                DiagLog.log("update check failed: " + e.getMessage());
                // Not again on every unlock: try once an hour until it works.
                prefs.edit().putLong("updateChecked", System.currentTimeMillis() - CHECK_EVERY_MS + 3_600_000L).apply();
                main.post(() -> {
                    ui.onUpdateState("Update-Prüfung fehlgeschlagen · tippen = im Browser laden", false);
                    if (manual) {
                        toast(e.getMessage() + " – lade im Browser");
                        openInBrowser();
                    }
                });
            } finally {
                busy = false;
            }
        }).start();
    }

    private void showState() {
        if (latest == 0) {
            latest = prefs.getInt("updateLatest", 0);
            assetId = prefs.getLong("updateAsset", 0);
            deltaId = prefs.getInt("updateDeltaFor", -1) == current() ? prefs.getLong("updateDelta", 0) : 0;
        }
        if (latest > current() && assetId != 0) ui.onUpdateState("Update 1.0." + latest + " → installieren", true);
        else ui.onUpdateState("ARC-DNA 1.0." + current() + " · aktuell", false);
    }

    /** Downloads the APK into a PackageInstaller session; Android then asks for confirmation. */
    void install() {
        if (busy || assetId == 0) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            toast("ARC-DNA erlauben, Apps zu installieren – dann nochmal tippen.");
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        busy = true;
        ui.onUpdateState("Lädt Update …", false);
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
                    c = download(assetId);
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
                        if (pct != lastPct) {
                            lastPct = pct;
                            main.post(() -> ui.onUpdateState((built != null ? "Installiert Update … " : "Lädt Update … ") + pct + " %", false));
                        }
                    }
                    session.fsync(out);
                }
                Intent status = new Intent(activity, MainActivity.class).setAction(ACTION_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getActivity(activity, 7, status, flags);
                session.commit(pending.getIntentSender());
                session.close();
                session = null;
                main.post(() -> ui.onUpdateState("Installation bestätigen …", false));
            } catch (Exception e) {
                if (session != null) session.abandon();
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
        if (code != 200) throw new IOException("Download fehlgeschlagen (" + code + ")");
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
            main.post(() -> ui.onUpdateState("Lädt kleines Update (" + Math.max(1, total / 1_000_000) + " MB) …", false));
            java.io.File patch = new java.io.File(activity.getCacheDir(), "update.delta");
            try (InputStream in = c.getInputStream(); OutputStream o = new java.io.FileOutputStream(patch)) {
                byte[] buf = new byte[64 * 1024];
                for (int r; (r = in.read(buf)) != -1; ) o.write(buf, 0, r);
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
                            if (r < 0) throw new IOException("Basis zu kurz");
                            o.write(buf, 0, r);
                            sha.update(buf, 0, r);
                            n -= r;
                        }
                    } else if (op == 1) {
                        int n = in.readInt();
                        while (n > 0) {
                            int r = in.read(buf, 0, Math.min(n, buf.length));
                            if (r < 0) throw new IOException("Patch zu kurz");
                            o.write(buf, 0, r);
                            sha.update(buf, 0, r);
                            n -= r;
                        }
                    } else {
                        throw new IOException("Patch kaputt");
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            patch.delete();
            if (out.length() != size || !java.security.MessageDigest.isEqual(sha.digest(), want)) throw new IOException("Prüfsumme falsch");
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
            toast("Update installiert.");
            showState();
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast("Update abgebrochen" + (msg != null ? ": " + msg : "."));
            showState();
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
