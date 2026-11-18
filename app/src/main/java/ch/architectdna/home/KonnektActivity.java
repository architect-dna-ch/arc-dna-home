package ch.architectdna.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Konnekt inside ARC·DNA — the anti-loneliness bubble platform as a first-class home screen.
 * A thin WebView over the hosted Konnekt app (architect-dna.ch/konnekt), which runs standalone
 * with on-device storage and, signed in, as the shared multiplayer version. Kept deliberately
 * simple: the platform itself lives on the web so one codebase serves phone, tablet and browser.
 */
public class KonnektActivity extends Activity {

    private static final String URL = "https://architect-dna.ch/konnekt/";
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0F172A);                 // Konnekt slate, so there is no white flash

        final TextView note = new TextView(this);
        note.setText(L.t("Konnekt lädt …"));
        note.setTextColor(0xFF94A3B8);
        note.setTextSize(15);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2);
        nlp.gravity = android.view.Gravity.CENTER;
        root.addView(note, nlp);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0F172A);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                        // the standalone app persists bubbles in localStorage
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("https://architect-dna.ch") || u.startsWith("https://claude.ai")) return false;
                try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;                                 // other links (maps, share) open outside
            }
            @Override public void onPageFinished(WebView v, String u) { note.setVisibility(View.GONE); }
            @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    note.setVisibility(View.VISIBLE);
                    note.setText(L.t("Keine Verbindung zu Konnekt.\nOnline gehen und neu öffnen."));
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        getWindow().setStatusBarColor(0xFF0F172A);
        getWindow().setNavigationBarColor(0xFF0F172A);
        web.loadUrl(URL);
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
