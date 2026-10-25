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
 * VOID FREQ inside ARC-DNA: VOID FREQ's own sound engine (from void-freq-ios www/index.html,
 * bundled in assets, works offline) with a new ARC-DNA interface around it, light or dark like
 * the launcher. Its own tile in the Mediathek and its own task.
 * The sound keeps playing when you go home; ✕ or back stops it.
 */
public class FreqActivity extends Activity {

    private int bg;
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Light or dark like the launcher.
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : bits, bits);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                  // session counter (vf_sessions)
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
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
        web.loadUrl("file:///android_asset/voidfreq/index.html" + (night ? "?night=1" : ""));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // ARC-DNA close button, top right, in the page's cyan.
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(night ? 0xFFEDEDE8 : 0xFF1A1A1A);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        GradientDrawable b = new GradientDrawable();
        b.setShape(GradientDrawable.OVAL);
        b.setColor(night ? 0xFF1C1C1A : 0xFFFFFFFF);
        b.setStroke(dp(1), night ? 0xFF2C2C2A : 0xFFE0E0D8);
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
