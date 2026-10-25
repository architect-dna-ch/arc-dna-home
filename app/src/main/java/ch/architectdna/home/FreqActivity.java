package ch.architectdna.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * VOID FREQ inside ARC-DNA: the same focus studio as freq.architect-dna.ch and the iOS app
 * (www/index.html from void-freq-ios, bundled in assets, works offline), not rebuilt, just framed:
 * its own tile in the Mediathek, ARC-DNA close button, the web footer (share / mail) hidden.
 * The sound keeps playing when you go home; ✕ or back stops it.
 */
public class FreqActivity extends Activity {

    private static final int BG = 0xFF04080F;     // the page's own --bg
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                  // session counter (vf_sessions)
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Fit into ARC-DNA: no web footer (share / mail) inside the launcher.
                view.evaluateJavascript("(function(){var s=document.createElement('style');"
                    + "s.textContent='.footer{display:none!important}';document.head.appendChild(s);})()", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                // mailto / web links open outside, the studio stays here.
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, req.getUrl()));
                } catch (RuntimeException ignored) {
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/voidfreq/index.html");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // ARC-DNA close button, top right, in the page's cyan.
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xCCFFFFFF);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        GradientDrawable b = new GradientDrawable();
        b.setShape(GradientDrawable.OVAL);
        b.setColor(0x66000000);
        b.setStroke(dp(1), 0x3300EEFF);
        close.setBackground(b);
        close.setContentDescription("VOID FREQ schliessen");
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(14), dp(14), 0);
        root.addView(close, lp);

        setContentView(root);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
