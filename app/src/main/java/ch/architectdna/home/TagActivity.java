package ch.architectdna.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * ARC Tag — "if found, tap me" for your wallet, keys, bag. The tag platform lives on the web
 * (architect-dna.ch/tag) so any finder's phone works without an app; this screen adds what only the
 * app can do:
 *  - it holds your tags' owner keys and mirrors them to TagWatch, which notifies you when someone ELSE
 *    scanned one;
 *  - it opens tag links / NFC taps itself, so YOUR tap is recognised as you (Chrome wouldn't know your
 *    key and would treat you as a stranger);
 *  - it writes a tag's link onto a blank NFC sticker.
 */
public class TagActivity extends Activity {

    static final String BASE = "https://architect-dna.ch/tag";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private NfcAdapter nfc;
    private volatile String pendingWrite;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0E12);
        final TextView note = new TextView(this);
        note.setText(L.t("ARC Tag lädt …"));
        note.setTextColor(0xFF9AA8B6);
        note.setTextSize(15);
        note.setGravity(android.view.Gravity.CENTER);
        note.setPadding(64, 0, 64, 0);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2);
        nlp.gravity = android.view.Gravity.CENTER;
        root.addView(note, nlp);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0E12);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // owner keys live in the page's localStorage
        s.setGeolocationEnabled(true);
        web.addJavascriptInterface(new Bridge(), "ArcTag");
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback cb) {
                cb.invoke(origin, origin != null && origin.startsWith("https://architect-dna.ch"), false);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("https://architect-dna.ch")) return false;   // the bridge only ever serves our own pages
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;                                                // maps etc. open outside
            }
            @Override public void onPageFinished(WebView v, String u) { note.setVisibility(View.GONE); }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, android.webkit.WebResourceResponse res) {
                if (!req.isForMainFrame()) return;
                v.setVisibility(View.INVISIBLE);
                note.setVisibility(View.VISIBLE);
                note.setText(res.getStatusCode() == 404
                    ? L.t("%s ist noch nicht online.\nDie Website architect-dna.ch muss zuerst veröffentlicht werden.").replace("%s", "ARC Tag")
                    : L.t("Der Server antwortet gerade nicht. Später nochmals versuchen."));
            }
            @Override public void onPageStarted(WebView v, String u, android.graphics.Bitmap fav) { v.setVisibility(View.VISIBLE); }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) { note.setVisibility(View.VISIBLE); note.setText(L.t("Keine Verbindung.\nOnline gehen und neu öffnen.")); }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        getWindow().setStatusBarColor(0xFF0B0E12);
        getWindow().setNavigationBarColor(0xFF0B0E12);

        nfc = NfcAdapter.getDefaultAdapter(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            try { requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 21); } catch (Exception ignored) { }
        TagWatch.start(this);

        String u = tagUrl(getIntent());
        web.loadUrl(u != null ? u : BASE);
    }

    /** A tag link from an NFC tap or a VIEW intent, or null. Only our own /tag pages are accepted. */
    private static String tagUrl(Intent i) {
        if (i == null) return null;
        Uri d = i.getData();
        if (d == null && NfcAdapter.ACTION_NDEF_DISCOVERED.equals(i.getAction())) {
            android.os.Parcelable[] raw = i.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            if (raw != null && raw.length > 0 && raw[0] instanceof NdefMessage)
                for (NdefRecord r : ((NdefMessage) raw[0]).getRecords()) { Uri x = r.toUri(); if (x != null) { d = x; break; } }
        }
        if (d == null) return null;
        String s = d.toString();
        return s.startsWith(BASE + "/") || s.equals(BASE) ? s : null;
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Tag t = i.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (pendingWrite != null && t != null) { write(t, pendingWrite); return; }
        String u = tagUrl(i);
        if (u != null && web != null) web.loadUrl(u);
    }

    /* ---------------- writing a sticker ---------------- */

    private void armWrite(String url) {
        if (nfc == null) { toast(L.t("Dieses Gerät hat kein NFC.")); return; }
        if (!nfc.isEnabled()) {
            toast(L.t("NFC ist aus — bitte einschalten."));
            try { startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); } catch (Exception ignored) { }
            return;
        }
        pendingWrite = url;
        enableDispatch();
        toast(L.t("Halte den leeren Sticker an die Rückseite des Handys …"));
    }

    private void enableDispatch() {
        if (nfc == null || pendingWrite == null) return;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, TagActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        try { nfc.enableForegroundDispatch(this, pi, null, null); } catch (RuntimeException ignored) { }
    }

    private void write(final Tag t, final String url) {
        new Thread(() -> {
            String result;
            NdefMessage msg = new NdefMessage(NdefRecord.createUri(url));
            try {
                Ndef ndef = Ndef.get(t);
                if (ndef != null) {
                    ndef.connect();
                    if (!ndef.isWritable()) result = L.t("Dieser Sticker ist schreibgeschützt.");
                    else if (ndef.getMaxSize() < msg.getByteArrayLength()) result = L.t("Sticker zu klein für den Link.");
                    else { ndef.writeNdefMessage(msg); result = null; }
                    ndef.close();
                } else {
                    NdefFormatable f = NdefFormatable.get(t);
                    if (f != null) { f.connect(); f.format(msg); f.close(); result = null; }
                    else result = L.t("Dieser Sticker unterstützt kein NDEF.");
                }
            } catch (Exception e) {
                result = L.t("Schreiben fehlgeschlagen — Sticker ruhig halten und nochmals versuchen.");
            }
            final String r = result;
            main.post(() -> {
                if (r == null) {
                    pendingWrite = null;
                    try { nfc.disableForegroundDispatch(this); } catch (RuntimeException ignored) { }
                    toast(L.t("✓ Sticker beschrieben. Tipp ihn an: ARC erkennt dich, ein Finder sieht deine Nachricht."));
                } else toast(r);
            });
        }, "arc-tag-write").start();
    }

    @Override protected void onResume() { super.onResume(); enableDispatch(); }
    @Override protected void onPause() {
        super.onPause();
        if (nfc != null) try { nfc.disableForegroundDispatch(this); } catch (RuntimeException ignored) { }
    }

    /* ---------------- bridge for architect-dna.ch/tag ---------------- */

    private final class Bridge {
        @JavascriptInterface public void save(String json) {
            if (json == null || json.length() > 20000) return;
            getSharedPreferences("home", MODE_PRIVATE).edit().putString(TagWatch.PREF, json).apply();
        }
        @JavascriptInterface public String load() {
            return getSharedPreferences("home", MODE_PRIVATE).getString(TagWatch.PREF, "[]");
        }
        @JavascriptInterface public void writeNfc(String url) {
            if (url == null || !url.startsWith(BASE + "/")) return;
            main.post(() -> armWrite(url));
        }
    }

    private void toast(String s) { try { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); } catch (RuntimeException ignored) { } }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
