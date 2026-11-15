package ch.architectdna.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ARC Browser: a private browser in the ARC look.
 * - Nothing is kept: no history file; cookies, cache and site storage are wiped when you leave.
 * - Ads and trackers are blocked before they load; the shield shows how many on this page.
 * - Third-party cookies off, location off, DuckDuckGo as search.
 * - The bar sits at the bottom, where the thumb is. A dot in it shows whether a VPN is up.
 * No settings: these are the defaults.
 */
public class BrowserActivity extends Activity {

    private static final String SEARCH = "https://duckduckgo.com/?q=";
    private static final String START = "https://start.arc-dna.invalid/";

    /** Ad and tracking hosts (and their subdomains). Blocked unless they are the page itself. */
    private static final Set<String> BLOCK = new HashSet<>(Arrays.asList(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "app-measurement.com",
        "connect.facebook.net", "an.facebook.com", "scorecardresearch.com", "quantserve.com", "adnxs.com",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com", "ads-twitter.com",
        "analytics.twitter.com", "hotjar.com", "mixpanel.com", "segment.io", "cdn.segment.com", "adsrvr.org",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "moatads.com", "chartbeat.com",
        "teads.tv", "bidswitch.net", "smartadserver.com", "adform.net", "px.ads.linkedin.com", "bat.bing.com",
        "clarity.ms", "analytics.tiktok.com", "tr.snapchat.com", "yieldmo.com", "3lift.com", "sharethrough.com",
        "media.net", "zedo.com", "adcolony.com", "unityads.unity3d.com", "applovin.com", "inmobi.com",
        "mopub.com", "branch.io", "appsflyer.com", "adjust.com", "kochava.com", "omtrdc.net", "demdex.net",
        "everesttech.net", "krxd.net", "bluekai.com", "exelator.com", "agkn.com", "rlcdn.com", "tapad.com",
        "crwdcntrl.net", "addthis.com", "sharethis.com", "newrelic.com", "nr-data.net", "fullstory.com",
        "mouseflow.com", "luckyorange.com", "crazyegg.com", "optimizely.com", "adroll.com", "serving-sys.com",
        "advertising.com", "yahoo-adsystem.com", "ads.yahoo.com", "id5-sync.com", "33across.com", "gumgum.com"));

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean dark;
    private int bg, card, line, ink, ink2, ink3, rust;
    private static final int GREEN = 0xFF2BA35A;

    private FrameLayout webBox;
    private Progress progress;
    private EditText address;
    private TextView shield, tabsBtn, backBtn, vpnDot;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> fileCallback;

    private final List<Tab> tabs = new ArrayList<>();
    private Tab cur;

    private static final class Tab {
        WebView web;
        int blocked;
        String title = "";
        boolean start = true;
    }

    private static boolean de() { return "de".equals(Locale.getDefault().getLanguage()); }
    private static String t(String en, String de) { return de() ? de : en; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF0E0E0D : 0xFFFAFAF8;
        card = dark ? 0xFF1A1A18 : 0xFFFFFFFF;
        line = dark ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = dark ? 0xFFF4F2EC : 0xFF1A1A1A;
        ink2 = dark ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = dark ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = dark ? 0xFFD9472F : 0xFFB5271E;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);

        progress = new Progress();
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        webBox = new FrameLayout(this);
        root.addView(webBox, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(bar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        CookieManager.getInstance().setAcceptCookie(true);
        newTab(null);
        handle(getIntent());
        // First start: offer once to become the default browser, so every link opens here.
        android.content.SharedPreferences p = getSharedPreferences("browser", MODE_PRIVATE);
        if (!isDefault() && !p.getBoolean("askedDefault", false)) {
            p.edit().putBoolean("askedDefault", true).apply();
            main.postDelayed(this::askDefault, 900);
        }
    }

    private boolean isDefault() {
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
            return rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER);
        }
        android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(
            new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Android's own "make default browser" sheet; older Android: the default-apps screen. */
    private void askDefault() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 78);
                    return;
                }
            }
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (RuntimeException e) {
            toast(t("Settings → Apps → Default apps → Browser", "Einstellungen → Apps → Standard-Apps → Browser"));
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        paintVpn();
        if (cur != null) cur.web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cur != null) cur.web.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Tab t : tabs) t.web.destroy();
        tabs.clear();
        if (isFinishing()) wipe();
    }

    /** Nothing kept: cookies, site storage, cache and form data go. */
    private void wipe() {
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            WebView w = new WebView(getApplicationContext());
            w.clearCache(true);
            w.clearHistory();
            w.clearFormData();
            w.destroy();
        } catch (RuntimeException ignored) { }
    }

    private void handle(Intent i) {
        if (i == null) return;
        String go = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) go = i.getData().toString();
        else if (Intent.ACTION_WEB_SEARCH.equals(i.getAction())) go = i.getStringExtra(android.app.SearchManager.QUERY);
        else if (Intent.ACTION_SEND.equals(i.getAction())) go = i.getStringExtra(Intent.EXTRA_TEXT);
        if (go == null || go.trim().isEmpty()) return;
        if (cur != null && !cur.start) newTab(null);
        open(go.trim());
    }

    /* ================= the bar ================= */

    private View bar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(10));
        bar.setBackgroundColor(bg);

        backBtn = icon("‹", 28);
        backBtn.setOnClickListener(v -> { if (cur != null && cur.web.canGoBack()) cur.web.goBack(); });
        backBtn.setOnLongClickListener(v -> { if (cur != null && cur.web.canGoForward()) cur.web.goForward(); return true; });
        bar.addView(backBtn, new LinearLayout.LayoutParams(dp(42), dp(46)));

        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), 0, dp(6), 0);
        pill.setBackground(round(card, line, 23));
        vpnDot = new TextView(this);
        vpnDot.setText("●");
        vpnDot.setTextSize(10);
        pill.addView(vpnDot);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setBackground(null);
        address.setTextColor(ink);
        address.setHintTextColor(ink3);
        address.setTextSize(15);
        address.setHint(t("Search or address", "Suchen oder Adresse"));
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setEllipsize(TextUtils.TruncateAt.END);
        address.setPadding(dp(8), 0, dp(4), 0);
        address.setOnEditorActionListener((v, id, e) -> {
            String q = address.getText().toString().trim();
            if (!q.isEmpty()) open(q);
            hideKeyboard();
            return true;
        });
        address.setOnFocusChangeListener((v, has) -> {
            if (has && cur != null && !cur.start) address.setText(cur.web.getUrl());
            if (has) address.selectAll();
            else paintAddress();
        });
        pill.addView(address, new LinearLayout.LayoutParams(0, dp(46), 1f));
        shield = new TextView(this);
        shield.setTextColor(rust);
        shield.setTextSize(12);
        shield.setTypeface(Typeface.MONOSPACE);
        shield.setPadding(dp(4), 0, dp(6), 0);
        shield.setOnClickListener(v -> toast(cur == null ? "" : t(cur.blocked + " ads and trackers blocked on this page.", cur.blocked + " Werbe- und Tracker-Anfragen auf dieser Seite blockiert.")));
        pill.addView(shield);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        pl.leftMargin = dp(4);
        pl.rightMargin = dp(6);
        bar.addView(pill, pl);

        tabsBtn = new TextView(this);
        tabsBtn.setGravity(Gravity.CENTER);
        tabsBtn.setTextColor(ink);
        tabsBtn.setTextSize(13);
        tabsBtn.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        tabsBtn.setBackground(round(0, ink2, 7));
        tabsBtn.setOnClickListener(v -> showTabs());
        tabsBtn.setOnLongClickListener(v -> { newTab(null); return true; });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(30), dp(30));
        tl.leftMargin = dp(4);
        tl.rightMargin = dp(8);
        bar.addView(tabsBtn, tl);

        TextView menu = icon("⋯", 22);
        menu.setOnClickListener(v -> showMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(46)));
        return bar;
    }

    private void paintAddress() {
        if (cur == null || address.hasFocus()) return;
        if (cur.start) { address.setText(""); shield.setText(""); }
        else {
            String u = cur.web.getUrl();
            Uri uri = u == null ? null : Uri.parse(u);
            String host = uri == null || uri.getHost() == null ? (u == null ? "" : u) : uri.getHost().replaceFirst("^www\\.", "");
            boolean secure = u != null && u.startsWith("https://");
            address.setText((secure ? "🔒 " : "⚠ ") + host);
            shield.setText(cur.blocked > 0 ? "🛡" + cur.blocked : "");
        }
        backBtn.setTextColor(cur.web.canGoBack() ? ink : ink3);
        tabsBtn.setText(String.valueOf(tabs.size()));
    }

    private void paintVpn() {
        boolean on = false;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) on = true;
            }
        } catch (RuntimeException ignored) { }
        vpnDot.setTextColor(on ? GREEN : ink3);
        boolean vpn = on;
        vpnDot.setOnClickListener(v -> toast(vpn ? t("VPN is on.", "VPN ist an.") : t("No VPN. Your network can see which sites you visit.", "Kein VPN. Dein Netz sieht, welche Seiten du besuchst.")));
    }

    /* ================= tabs ================= */

    private void newTab(String url) {
        Tab tab = new Tab();
        tab.web = makeWeb(tab);
        tabs.add(tab);
        show(tab);
        if (url == null) startPage(tab);
        else open(url);
    }

    private void show(Tab tab) {
        if (cur != null) cur.web.onPause();
        cur = tab;
        webBox.removeAllViews();
        webBox.addView(tab.web, new FrameLayout.LayoutParams(-1, -1));
        tab.web.onResume();
        paintAddress();
    }

    private void closeTab(Tab tab) {
        int i = tabs.indexOf(tab);
        tabs.remove(tab);
        tab.web.destroy();
        if (tabs.isEmpty()) { newTab(null); return; }
        show(tabs.get(Math.max(0, i - 1)));
    }

    private void showTabs() {
        List<String> names = new ArrayList<>();
        for (Tab tb : tabs) names.add((tb == cur ? "● " : "   ") + (tb.start ? t("New tab", "Neuer Tab") : tb.title.isEmpty() ? tb.web.getUrl() : tb.title));
        names.add(t("＋  New tab", "＋  Neuer Tab"));
        names.add(t("✕  Close this tab", "✕  Diesen Tab schliessen"));
        new AlertDialog.Builder(this, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(t("Tabs", "Tabs"))
            .setItems(names.toArray(new String[0]), (d, i) -> {
                if (i < tabs.size()) show(tabs.get(i));
                else if (i == tabs.size()) newTab(null);
                else closeTab(cur);
            })
            .show();
    }

    /* ================= menu ================= */

    private void showMenu() {
        if (cur == null) return;
        String u = cur.start ? null : cur.web.getUrl();
        String[] items = {
            t("→  Forward", "→  Vorwärts"),
            t("↻  Reload", "↻  Neu laden"),
            t("↗  Share", "↗  Teilen"),
            t("⧉  Copy link", "⧉  Link kopieren"),
            t("⎋  Open in another app", "⎋  In anderer App öffnen"),
            t("🖥  Desktop site on/off", "🖥  Desktop-Seite an/aus"),
            t("🧹  Wipe everything now", "🧹  Jetzt alles löschen"),
        };
        new AlertDialog.Builder(this, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setItems(items, (d, i) -> {
                switch (i) {
                    case 0: if (cur.web.canGoForward()) cur.web.goForward(); break;
                    case 1: cur.web.reload(); break;
                    case 2: if (u != null) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null)); break;
                    case 3: if (u != null) copy(u); break;
                    case 4: if (u != null) try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(u)), null)); } catch (RuntimeException ignored) { } break;
                    case 5: toggleDesktop(); break;
                    default:
                        for (Tab tb : new ArrayList<>(tabs)) { tabs.remove(tb); tb.web.destroy(); }
                        cur = null;
                        wipe();
                        newTab(null);
                        toast(t("Everything wiped.", "Alles gelöscht."));
                        break;
                }
            })
            .show();
    }

    private void toggleDesktop() {
        WebSettings s = cur.web.getSettings();
        boolean desktop = s.getUserAgentString().contains("X11");
        s.setUserAgentString(desktop ? null
            : "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(!desktop);
        cur.web.reload();
    }

    /* ================= web views ================= */

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView makeWeb(Tab tab) {
        WebView w = new WebView(this);
        w.setBackgroundColor(bg);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);

        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (r.isForMainFrame()) return null;
                String host = r.getUrl().getHost();
                if (host != null && blocked(host)) {
                    main.post(() -> { tab.blocked++; if (tab == cur) paintAddress(); });
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if ("arc".equals(scheme)) { askDefault(); return true; }   // the start page's "make default" button
                if ("http".equals(scheme) || "https".equals(scheme) || "about".equals(scheme) || "data".equals(scheme)) return false;
                // intent:, mailto:, tel:, market: … hand over to the right app
                try {
                    Intent i = "intent".equals(scheme) ? Intent.parseUri(r.getUrl().toString(), Intent.URI_INTENT_SCHEME)
                        : new Intent(Intent.ACTION_VIEW, r.getUrl());
                    i.addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null).setSelector(null);
                    startActivity(i);
                } catch (Exception ignored) { }
                return true;
            }

            @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                if (url != null && url.startsWith("http")) { tab.start = url.startsWith(START); tab.blocked = 0; }
                if (tab == cur) { progress.set(0.08f); paintAddress(); }
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (tab == cur) { progress.set(1f); paintAddress(); }
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { if (tab == cur) progress.set(p / 100f); }

            @Override public void onReceivedTitle(WebView v, String title) { tab.title = title == null ? "" : title; }

            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                customView = view;
                customCallback = cb;
                ((ViewGroup) getWindow().getDecorView()).addView(view, new FrameLayout.LayoutParams(-1, -1));
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override public void onHideCustomView() { exitFullscreen(); }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(params.createIntent(), 77); }
                catch (RuntimeException e) { fileCallback = null; return false; }
                return true;
            }
        });
        w.setDownloadListener((url, ua, disposition, mime, length) -> download(url, ua, disposition, mime));
        w.setOnLongClickListener(v -> {
            WebView.HitTestResult h = w.getHitTestResult();
            String link = h.getExtra();
            if (link == null || (h.getType() != WebView.HitTestResult.SRC_ANCHOR_TYPE && h.getType() != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) return false;
            new AlertDialog.Builder(this, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle(link)
                .setItems(new String[]{t("Open in new tab", "In neuem Tab öffnen"), t("Copy link", "Link kopieren"), t("Share", "Teilen")}, (d, i) -> {
                    if (i == 0) newTab(link);
                    else if (i == 1) copy(link);
                    else startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null));
                })
                .show();
            return true;
        });
        return w;
    }

    private static boolean blocked(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        while (true) {
            if (BLOCK.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        ((ViewGroup) getWindow().getDecorView()).removeView(customView);
        customView = null;
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 78) {
            toast(isDefault() ? t("ARC Browser is now your default. Links open here.", "ARC Browser ist jetzt Standard. Links öffnen hier.") : "");
            for (Tab tb : tabs) if (tb.start) startPage(tb);
            return;
        }
        if (req == 77 && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        }
    }

    private void download(String url, String ua, String disposition, String mime) {
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            toast(t("Downloading ", "Lädt ") + name);
        } catch (RuntimeException e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException ignored) { }
        }
    }

    /* ================= navigation ================= */

    private void open(String q) {
        if (cur == null) return;
        String url;
        if (q.matches("(?i)^(https?|about|data):.*")) url = q;
        else if (!q.contains(" ") && q.contains(".") && !q.endsWith(".")) url = "https://" + q;   // typed domain: secure first
        else url = SEARCH + Uri.encode(q);
        cur.start = false;
        cur.web.loadUrl(url);
        address.clearFocus();
        paintAddress();
    }

    private void startPage(Tab tab) {
        tab.start = true;
        String paper = dark ? "#0E0E0D" : "#FAFAF8", cardC = dark ? "#1A1A18" : "#FFFFFF", lineC = dark ? "#2C2C2A" : "#E0E0D8",
            inkC = dark ? "#F4F2EC" : "#1A1A1A", ink2C = dark ? "#A5A5A0" : "#5A5A58", rustC = dark ? "#D9472F" : "#B5271E";
        String[][] tiles = {
            {"🧬", "Werkschau", "https://architect-dna.ch/werkschau"},
            {"⚖️", "Einspruch", "https://einspruch.architect-dna.ch"},
            {"⚓", "Anchor", "https://anchor.architect-dna.ch"},
            {"🛁", "Wellness", "https://architect-dna.ch/wellness/"},
            {"🧅", "Health", "https://architect-dna.ch/health/"},
            {"🚆", "SBB", "https://www.sbb.ch"},
            {"🔎", "DuckDuckGo", "https://duckduckgo.com"},
            {"🌍", "Wikipedia", "https://wikipedia.org"},
        };
        StringBuilder tb = new StringBuilder();
        for (String[] x : tiles)
            tb.append("<a class=t href='").append(x[2]).append("'><span>").append(x[0]).append("</span>").append(x[1]).append("</a>");
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>"
            + "body{margin:0;background:" + paper + ";color:" + inkC + ";font-family:-apple-system,Roboto,sans-serif;padding:56px 22px}"
            + ".l{font:11px monospace;letter-spacing:.24em;color:" + rustC + "}"
            + "h1{font-size:46px;letter-spacing:-.05em;line-height:.95;margin:16px 0 8px;font-weight:900}"
            + "p{color:" + ink2C + ";font-size:15px;line-height:1.5;margin:0 0 26px}"
            + "form{display:flex;gap:8px}input{flex:1;font-size:16px;padding:14px;border-radius:8px;border:1px solid " + lineC + ";background:" + cardC + ";color:" + inkC + "}"
            + "button{border:0;border-radius:8px;background:" + inkC + ";color:" + paper + ";font-weight:800;padding:0 18px;font-size:15px}"
            + ".g{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:30px}"
            + ".t{display:flex;flex-direction:column;align-items:center;gap:6px;text-decoration:none;color:" + ink2C + ";font-size:11.5px;padding:12px 4px;border:1px solid " + lineC + ";border-radius:8px;background:" + cardC + "}"
            + ".d{display:block;margin-top:18px;padding:14px;border-radius:8px;background:" + rustC + ";color:#fff;text-decoration:none;font-weight:700;font-size:14.5px}"
            + ".t span{font-size:24px}.f{margin-top:36px;font:10.5px monospace;letter-spacing:.14em;color:" + ink2C + ";line-height:1.8}"
            + "</style></head><body><div class=l>— ARC·DNA BROWSER</div>"
            + "<h1>" + t("Browse.<br>Leave no trace.", "Surfen.<br>Keine Spuren.") + "</h1>"
            + "<p>" + t("Trackers blocked. Nothing kept when you leave.", "Tracker blockiert. Nichts bleibt, wenn du gehst.") + "</p>"
            + "<form action='https://duckduckgo.com/' method=get><input name=q placeholder='" + t("Search privately", "Privat suchen") + "'><button>→</button></form>"
            + (isDefault() ? "" : "<a class=d href='arc://default'>" + t("Make ARC Browser your default → every link opens here, private.", "ARC Browser als Standard → jeder Link öffnet hier, privat.") + "</a>")
            + "<div class=g>" + tb + "</div>"
            + "<div class=f>" + t("🛡 ADS & TRACKERS BLOCKED · 🍪 NO THIRD-PARTY COOKIES<br>🧹 WIPED ON EXIT · ● GREEN = VPN ON",
                "🛡 WERBUNG & TRACKER BLOCKIERT · 🍪 KEINE DRITT-COOKIES<br>🧹 BEIM SCHLIESSEN GELÖSCHT · ● GRÜN = VPN AN") + "</div>"
            + "</body></html>";
        tab.web.loadDataWithBaseURL(START, html, "text/html", "utf-8", null);
        if (tab == cur) paintAddress();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (address.hasFocus()) { address.clearFocus(); hideKeyboard(); return; }
        if (cur != null && cur.web.canGoBack()) { cur.web.goBack(); return; }
        if (tabs.size() > 1) { closeTab(cur); return; }
        super.onBackPressed();
    }

    /* ================= small things ================= */

    /** A thin rust line at the top while a page loads. */
    private final class Progress extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float f = 1f;

        Progress() { super(BrowserActivity.this); }

        void set(float v) {
            f = v;
            invalidate();
            if (v >= 1f) main.postDelayed(this::invalidate, 300);
        }

        @Override protected void onDraw(Canvas c) {
            if (f >= 1f) return;
            p.setColor(rust);
            c.drawRect(0, 0, getWidth() * Math.max(0.05f, f), getHeight(), p);
        }
    }

    private TextView icon(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ink);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private GradientDrawable round(int fill, int stroke, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1.2f), stroke);
        return g;
    }

    private void copy(String s) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("link", s));
        toast(t("Link copied", "Link kopiert"));
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void toast(String s) {
        if (!s.isEmpty()) Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
