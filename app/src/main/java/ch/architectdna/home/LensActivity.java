package ch.architectdna.home;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * ARC Lens — point the camera at something and ask about it (homework, a text, a sign, a thing).
 * The page lives at architect-dna.ch/lens (so it also works on the Mac and tablet); the vision model is
 * called server-side, the key never touches the phone. This screen hands the page the camera — only for
 * our own origin — and the photo picker.
 */
public class LensActivity extends Activity {

    private static final String URL = "https://architect-dna.ch/lens/";
    private static final String ORIGIN = "https://architect-dna.ch";
    private static final int REQ_CAM = 31, REQ_PICK = 32;

    private WebView web;
    private PermissionRequest pendingCam;
    private ValueCallback<Uri[]> pendingPick;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        final TextView note = new TextView(this);
        note.setText(L.t("ARC Lens lädt …"));
        note.setTextColor(0xFFB9B4AC);
        note.setTextSize(15);
        note.setGravity(android.view.Gravity.CENTER);
        note.setPadding(64, 0, 64, 0);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2);
        nlp.gravity = android.view.Gravity.CENTER;
        root.addView(note, nlp);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);      // the live camera preview autoplays
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (r.getUrl().toString().startsWith(ORIGIN)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView v, String u) { note.setVisibility(View.GONE); }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest req, android.webkit.WebResourceResponse res) {
                if (!req.isForMainFrame()) return;
                v.setVisibility(View.INVISIBLE);
                note.setVisibility(View.VISIBLE);
                note.setText(res.getStatusCode() == 404
                    ? L.t("%s ist noch nicht online.\nDie Website architect-dna.ch muss zuerst veröffentlicht werden.").replace("%s", "ARC Lens")
                    : L.t("Der Server antwortet gerade nicht. Später nochmals versuchen."));
            }
            @Override public void onPageStarted(WebView v, String u, android.graphics.Bitmap fav) { v.setVisibility(View.VISIBLE); }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) { note.setVisibility(View.VISIBLE); note.setText(L.t("Keine Verbindung.\nOnline gehen und neu öffnen.")); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean ours = req.getOrigin() != null && req.getOrigin().toString().startsWith(ORIGIN);
                    boolean wantsCam = false;
                    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCam = true;
                    if (!ours || !wantsCam) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingCam = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
                    }
                });
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(Intent.createChooser(i, L.t("Foto wählen")), REQ_PICK); return true; }
                catch (Exception e) { pendingPick = null; return false; }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        web.loadUrl(URL);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_CAM || pendingCam == null) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
            pendingCam.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        else pendingCam.deny();                 // the page then offers "Foto wählen" instead
        pendingCam = null;
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_PICK || pendingPick == null) return;
        Uri u = result == RESULT_OK && data != null ? data.getData() : null;
        pendingPick.onReceiveValue(u == null ? null : new Uri[]{u});
        pendingPick = null;
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
