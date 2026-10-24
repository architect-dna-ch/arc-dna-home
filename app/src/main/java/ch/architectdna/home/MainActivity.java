package ch.architectdna.home;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.SearchManager;
import android.app.WallpaperManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;

/**
 * ARC-DNA Home — an Architect-DNA styled launcher for the Pixel 9a, laid out like architect-dna.ch/home.
 *
 * Main screen: clock, today's focus, web search, eight app tiles, project chips, the fox, a seismograph.
 * Long-press empty space: the Mediathek (all apps, with search) slides up.
 * Tap empty space: everything fades so only the wallpaper shows; tap again to bring it back.
 * Optional head-nod scrolling via the front camera (HeadTracker), off until switched on.
 */
public class MainActivity extends Activity implements SensorEventListener, HeadTracker.Listener {

    private static final String WEB_HOME = "https://architect-dna.ch/home";
    private static final int REQ_CAMERA = 7;
    private static final int REQ_AUDIO = 8;
    private static final String[] FOX_CALLS = {
        "Yip!", "Yip-yip!", "Gekk-gekk-gekk!", "Wow-wow!", "Ack-ack-ack!", "Mrrp?", "Wa-pa-pa-pa!", "Huff."
    };
    private static final String[][] PROJECTS = {
        {"VOID FREQ", " · Fokus", "https://freq.architect-dna.ch"},
        {"Einspruch", "", "https://einspruch.architect-dna.ch"},
        {"LIA", "", "https://architect-dna.ch/lia"},
        {"DORA", "", "https://architect-dna-ch.github.io/dora/"},
        {"CV", "", "https://architect-dna.ch/cv"},
        {"GitHub", "", "https://github.com/architect-dna-ch"},
        {"architect-dna.ch", "", "https://architect-dna.ch"},
    };

    static final class AppEntry {
        final String label;
        final ComponentName component;
        final Drawable icon;

        AppEntry(String label, ComponentName component, Drawable icon) {
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    private int ink, ink2, ink3, rust, card, panel, line;
    private final Typeface mono = Typeface.MONOSPACE;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private ScrollView scroll, libScroll;
    private LinearLayout content, libGrid;
    private FrameLayout library;
    private View shield;
    private TextView clock, date, greet, net, headLabel, headToggle, bubble;
    private EditText focus, webQuery, libSearch;
    private ImageView fox;
    private SeismoView seismo;
    private View bottomScrim;
    /** Height at the bottom covered by the fade, fox and seismograph; sections must clear it. */
    private int foldInset;
    private final Map<View, Float> foldState = new HashMap<>();
    private static final String LABEL_TAG = "section-label";
    private LinearLayout recentHome, recentLib;
    private boolean hidden, libraryOpen;

    private final List<AppEntry> apps = new ArrayList<>();
    private BroadcastReceiver packageReceiver;
    private ConnectivityManager.NetworkCallback netCallback;
    private SensorManager sensors;
    private long lastShake;

    private HeadTracker head;
    private final FoxVoice voice = new FoxVoice();
    private Updater updater;
    private MusicCard music;

    /** Circle gesture: each circle turns the screen 90°, four bring it back. */
    private static final int[] ORIENTATIONS = {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
    };
    private final List<float[]> stroke = new ArrayList<>();
    private boolean strokeMulti;
    /** Last finger contact; eye scrolling rests for EYE_PAUSE_MS after it. */
    private long lastTouch;
    private static final long EYE_PAUSE_MS = 4000;
    private TextView updateHome, updateLib;
    private int calibFrames;
    private float calibSum, basePitch, smoothTilt;
    private final Object calibToken = new Object();
    private View calibOverlay, calibDot;
    private TextView calibText;
    private int calibStage = -1, calibRetries;
    private boolean calibCollect;
    private float[] calibAcc = new float[3];
    private int[] calibN = new int[3];
    private float headSpeed;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateClock();
            int seconds = Calendar.getInstance().get(Calendar.SECOND);
            handler.postDelayed(this, (60 - seconds) * 1000L + 50);
        }
    };

    private final Runnable headScroller = new Runnable() {
        @Override
        public void run() {
            if (headSpeed == 0 || !libraryOpen) return;
            libScroll.scrollBy(0, Math.round(headSpeed));
            handler.postDelayed(this, 16);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        pickColours();
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        setContentView(buildUi());
        updater = new Updater(this, prefs, this::onUpdateState);
        setRequestedOrientation(ORIENTATIONS[prefs.getInt("orient", 0)]);
        if (prefs.getBoolean("hidden", false)) {
            hidden = true;
            shield.setVisibility(View.VISIBLE);
            content.setAlpha(0.12f);
            content.setScaleX(0.97f);
            content.setScaleY(0.97f);
        }
        applyWallpaperIfNeeded();
        loadApps();
        registerPackageReceiver();
        watchNetwork();
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    private void pickColours() {
        boolean night = isNight();
        ink = night ? 0xFFECEBE6 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA9A8A2 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFC9402F : 0xFFB5271E;
        card = night ? 0xB3121211 : 0xB3FAFAF8;
        panel = night ? 0xF2121211 : 0xF2FAFAF8;
        line = night ? 0xFF2C2C29 : 0xFFE0E0D8;
    }

    /* ================= Main screen ================= */

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Empty space: tap = only wallpaper, hold = Mediathek.
        content.setOnClickListener(v -> setHidden(true));
        content.setOnLongClickListener(v -> {
            openLibrary();
            return true;
        });
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateFolds());
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::updateFolds);

        // Label row: ARCHITECT · DNA ……… ● ONLINE
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label("Architect · DNA"), new LinearLayout.LayoutParams(0, -2, 1f));
        headLabel = monoText(11, rust);
        headLabel.setText("◉ KOPF  ");
        headLabel.setVisibility(View.GONE);
        top.addView(headLabel);
        net = monoText(11, ink3);
        top.addView(net);
        content.addView(top);

        updateHome = text(14, ink, true);
        updateHome.setPadding(dp(14), dp(12), dp(14), dp(12));
        updateHome.setBackground(box(card, rust, 8));
        updateHome.setVisibility(View.GONE);
        updateHome.setOnClickListener(v -> updater.install());
        content.addView(updateHome, margins(-1, -2, 0, dp(14), 0, 0));

        clock = new TextView(this);
        clock.setTextColor(ink);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 104);
        clock.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        clock.setLetterSpacing(-0.06f);
        clock.setIncludeFontPadding(false);
        clock.setPadding(0, dp(30), 0, 0);
        clock.setOnLongClickListener(v -> {
            showWallpaperChoice();
            return true;
        });
        content.addView(clock);

        date = text(17, ink2, false);
        date.setPadding(0, dp(10), 0, 0);
        content.addView(date);
        greet = text(17, ink, true);
        content.addView(greet);

        content.addView(focusCard(), margins(-1, -2, 0, dp(24), 0, 0));
        content.addView(searchRow(), margins(-1, -2, 0, dp(12), 0, 0));

        content.addView(label("Musik"), margins(-1, -2, 0, dp(26), 0, 0));
        music = new MusicCard(this, new int[]{ink, ink2, ink3, rust, card, line}, new MusicCard.Host() {
            @Override
            public void open(Intent intent) {
                MainActivity.this.open(intent);
            }

            @Override
            public void askAudioPermission() {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            }

            @Override
            public void onLevel(float level) {
                if (level > 0.3f) seismo.kick(Math.min(1f, level * 0.8f));
            }

            @Override
            public void say(String text) {
                hop();
                MainActivity.this.say(text, 1800);
            }
        });
        content.addView(music.view(), margins(-1, -2, 0, dp(10), 0, 0));

        content.addView(label("Apps"), margins(-1, -2, 0, dp(26), 0, 0));
        content.addView(appTiles(), margins(-1, -2, 0, dp(10), 0, 0));

        content.addView(label("Zuletzt"), margins(-1, -2, 0, dp(22), 0, 0));
        recentHome = new LinearLayout(this);
        recentHome.setOrientation(LinearLayout.VERTICAL);
        content.addView(recentHome, margins(-1, -2, 0, dp(10), 0, 0));

        content.addView(label("Projekte"), margins(-1, -2, 0, dp(26), 0, 0));
        content.addView(projectChips(), margins(-1, -2, 0, dp(10), 0, 0));

        TextView hint = monoText(11, ink3);
        hint.setText("Leere Fläche: tippen = nur Wallpaper · halten = Mediathek · Fuchs: tippen = Yip, halten = Chat · schütteln = Erdbeben");
        hint.setLineSpacing(0, 1.3f);
        content.addView(hint, margins(-1, -2, 0, dp(20), 0, 0));

        // While hidden, this transparent layer takes the tap that brings everything back,
        // so the faded tiles underneath can't be opened by accident.
        shield = new View(this);
        shield.setVisibility(View.GONE);
        shield.setContentDescription("Apps einblenden");
        shield.setOnClickListener(v -> setHidden(false));
        shield.setOnLongClickListener(v -> {
            setHidden(false);
            openLibrary();
            return true;
        });
        root.addView(shield, new FrameLayout.LayoutParams(-1, -1));

        // A soft fade at the bottom so the navigation buttons, seismograph and fox sit on a calm surface.
        bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{panel, (panel & 0x00FFFFFF) | 0xB3000000, panel & 0x00FFFFFF}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, dp(100), Gravity.BOTTOM));

        seismo = new SeismoView(this);
        root.addView(seismo, new FrameLayout.LayoutParams(-1, dp(30), Gravity.BOTTOM));

        library = buildLibrary();
        root.addView(library, new FrameLayout.LayoutParams(-1, -1));

        calibOverlay = buildCalibOverlay();
        root.addView(calibOverlay, new FrameLayout.LayoutParams(-1, -1));

        fox = new ImageView(this);
        fox.setImageResource(R.drawable.fox);
        fox.setContentDescription("Fuchs");
        fox.setOnClickListener(v -> foxCall());
        fox.setOnLongClickListener(v -> {
            open(new Intent(Intent.ACTION_VIEW, Uri.parse(WEB_HOME)));
            return true;
        });
        root.addView(fox, new FrameLayout.LayoutParams(dp(84), dp(56), Gravity.BOTTOM | Gravity.START));

        bubble = text(13, ink, true);
        bubble.setBackground(box(ink, ink, 6));
        bubble.setTextColor(0xFF000000 | (~ink & 0xFFFFFF));
        bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
        bubble.setVisibility(View.GONE);
        root.addView(bubble, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top0, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top0 = bars.top;
                bottom = bars.bottom;
            } else {
                top0 = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(dp(20), top0 + dp(24), dp(20), bottom + dp(132));
            library.setPadding(dp(20), top0 + dp(20), dp(20), bottom + dp(12));
            calibOverlay.setPadding(0, top0, 0, bottom);
            // Keep the seismograph and fox clear of the navigation buttons, with the fade behind them.
            bottomScrim.getLayoutParams().height = bottom + dp(84);
            foldInset = bottom + dp(84);
            ((FrameLayout.LayoutParams) seismo.getLayoutParams()).bottomMargin = bottom + dp(6);
            ((FrameLayout.LayoutParams) fox.getLayoutParams()).setMargins(dp(14), 0, 0, bottom + dp(20));
            ((FrameLayout.LayoutParams) bubble.getLayoutParams()).setMargins(dp(70), 0, 0, bottom + dp(72));
            bottomScrim.requestLayout();
            seismo.requestLayout();
            fox.requestLayout();
            bubble.requestLayout();
            return insets;
        });
        return root;
    }

    private View focusCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(10));
        box.setBackground(box(card, line, 8));

        TextView k = monoText(10, ink3);
        k.setLetterSpacing(0.16f);
        k.setText("HEUTE ZÄHLT");
        box.addView(k);

        focus = new EditText(this);
        focus.setBackground(null);
        focus.setPadding(0, dp(6), 0, 0);
        focus.setTextColor(ink);
        focus.setHintTextColor(ink3);
        focus.setHint("Eine Sache für heute…");
        focus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        focus.setTypeface(Typeface.DEFAULT_BOLD);
        focus.setSingleLine(true);
        focus.setImeOptions(EditorInfo.IME_ACTION_DONE);
        focus.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            focus.clearFocus();
            return true;
        });
        focus.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString("focus", s.toString()).putString("focusDay", today()).apply();
            }
        });
        box.addView(focus);
        return box;
    }

    private View searchRow() {
        LinearLayout row = new LinearLayout(this);
        webQuery = field("Suchen…");
        webQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        webQuery.setOnEditorActionListener((v, actionId, event) -> {
            webSearch();
            return true;
        });
        row.addView(webQuery, new LinearLayout.LayoutParams(0, dp(50), 1f));

        TextView go = text(18, 0xFF000000 | (~ink & 0xFFFFFF), true);
        go.setText("→");
        go.setGravity(Gravity.CENTER);
        go.setBackground(box(ink, ink, 8));
        go.setContentDescription("Suchen");
        go.setOnClickListener(v -> webSearch());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(50));
        lp.leftMargin = dp(8);
        row.addView(go, lp);
        return row;
    }

    private void webSearch() {
        String q = webQuery.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        webQuery.setText("");
        Intent search = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q);
        if (getPackageManager().resolveActivity(search, 0) != null) open(search);
        else open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))));
    }

    /** The eight tiles from architect-dna.ch/home, each opening the matching app on the phone. */
    private View appTiles() {
        Object[][] items = {
            {"Telefon", R.drawable.ic_phone, new Intent(Intent.ACTION_DIAL)},
            {"SMS", R.drawable.ic_sms, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)},
            {"Mail", R.drawable.ic_mail, appOr("ch.protonmail.android",
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL))},
            {"Kalender", R.drawable.ic_calendar, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)},
            {"Karten", R.drawable.ic_map, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)},
            {"Kamera", R.drawable.ic_camera, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)},
            {"SBB", R.drawable.ic_train, appOr("ch.sbb.mobile.android.b2c",
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.sbb.ch")))},
            {"Claude", R.drawable.ic_claude, appOr("com.anthropic.claude",
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai")))},
        };
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                wrap.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            Intent intent = (Intent) items[i][2];
            View tile = tile((String) items[i][0], getDrawable((Integer) items[i][1]), 28);
            tile.setOnClickListener(v -> open(intent));
            row.addView(tile, weighted(i % 4 != 0));
        }
        return wrap;
    }

    /** Launch an installed app by package, or fall back to another intent (usually its website). */
    private Intent appOr(String pkg, Intent fallback) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        return launch != null ? launch : fallback;
    }

    private View projectChips() {
        FlowLayout flow = new FlowLayout(this, dp(6));
        for (String[] p : PROJECTS) {
            TextView chip = text(13, ink2, true);
            SpannableString s = new SpannableString(p[0] + p[1]);
            s.setSpan(new ForegroundColorSpan(ink), 0, p[0].length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            chip.setText(s);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setBackground(box(card, line, 20));
            chip.setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(p[2]))));
            flow.addView(chip);
        }
        return flow;
    }

    /* ================= Mediathek ================= */

    private FrameLayout buildLibrary() {
        FrameLayout lib = new FrameLayout(this);
        lib.setBackgroundColor(panel);
        lib.setVisibility(View.GONE);
        lib.setClickable(true);

        // Only the search bar stays fixed; header, Zuletzt, all apps and the update line scroll together.
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        lib.addView(col, new FrameLayout.LayoutParams(-1, -1));

        libScroll = new ScrollView(this);
        libScroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(16), 0, dp(110));
        libScroll.addView(body);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Mediathek"), new LinearLayout.LayoutParams(0, -2, 1f));
        headToggle = monoText(11, ink2);
        headToggle.setPadding(dp(10), dp(8), dp(10), dp(8));
        headToggle.setBackground(box(card, line, 20));
        headToggle.setOnClickListener(v -> cycleHeadMode());
        headToggle.setOnLongClickListener(v -> {
            if (!headMode().equals("gaze")) return false;
            startGazeCalibration();
            return true;
        });
        head.addView(headToggle);
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(14), dp(4), dp(4), dp(4));
        close.setContentDescription("Mediathek schliessen");
        close.setOnClickListener(v -> closeLibrary());
        head.addView(close);
        body.addView(head);
        paintHeadToggle();

        libSearch = field("Apps suchen…");
        libSearch.setImeOptions(EditorInfo.IME_ACTION_GO);
        libSearch.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                renderLibrary();
            }
        });
        libSearch.setOnEditorActionListener((v, actionId, event) -> {
            List<AppEntry> hits = filtered();
            if (!hits.isEmpty()) launch(hits.get(0));
            return true;
        });
        col.addView(libSearch, new LinearLayout.LayoutParams(-1, dp(50)));
        col.addView(libScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        body.addView(label("Zuletzt"), margins(-1, -2, 0, dp(20), 0, 0));
        recentLib = new LinearLayout(this);
        recentLib.setOrientation(LinearLayout.VERTICAL);
        body.addView(recentLib, margins(-1, -2, 0, dp(10), 0, 0));
        body.addView(label("Alle Apps"), margins(-1, -2, 0, dp(20), 0, 0));

        libGrid = new LinearLayout(this);
        libGrid.setOrientation(LinearLayout.VERTICAL);
        libGrid.setPadding(0, dp(12), 0, 0);
        body.addView(libGrid);

        updateLib = monoText(11, ink2);
        updateLib.setText("UPDATES EINRICHTEN");
        updateLib.setPadding(dp(4), dp(12), dp(4), dp(8));
        updateLib.setOnClickListener(v -> {
            if (updater.token().isEmpty()) showTokenDialog();
            else if (updateHome.getVisibility() == View.VISIBLE) updater.install();
            else updater.check(true);
        });
        updateLib.setOnLongClickListener(v -> {
            showTokenDialog();
            return true;
        });
        body.addView(updateLib, margins(-1, -2, 0, dp(16), 0, 0));
        return lib;
    }

    private void openLibrary() {
        if (libraryOpen) return;
        libraryOpen = true;
        hideKeyboard();
        library.setVisibility(View.VISIBLE);
        library.setAlpha(0f);
        library.setTranslationY(dp(60));
        library.animate().alpha(1f).translationY(0).setDuration(260)
            .setInterpolator(new DecelerateInterpolator()).start();
        libScroll.scrollTo(0, 0);
        // Head / eye scrolling only works in the Mediathek: the camera starts here …
        if (!headMode().equals("off")) startHead();
    }

    private void closeLibrary() {
        if (!libraryOpen) return;
        libraryOpen = false;
        // … and stops when it closes.
        stopHead();
        hideKeyboard();
        libSearch.setText("");
        library.animate().alpha(0f).translationY(dp(60)).setDuration(200)
            .withEndAction(() -> library.setVisibility(View.GONE)).start();
    }

    private void renderLibrary() {
        if (libGrid == null) return;
        libGrid.removeAllViews();
        List<AppEntry> list = filtered();
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                libGrid.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            AppEntry app = list.get(i);
            View tile = tile(app.label, app.icon, 40);
            tile.setOnClickListener(v -> launch(app));
            tile.setOnLongClickListener(v -> {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.component.getPackageName(), null)));
                return true;
            });
            row.addView(tile, weighted(i % 4 != 0));
        }
        // Keep the last row's tiles the same width as the others.
        if (row != null) {
            for (int i = list.size() % 4; i != 0 && i < 4; i++) row.addView(new View(this), weighted(true));
        }
        if (list.isEmpty()) {
            TextView none = text(14, ink3, false);
            none.setText(apps.isEmpty() ? "Apps werden geladen…" : "Keine App gefunden.");
            libGrid.addView(none);
        }
    }

    private List<AppEntry> filtered() {
        String q = libSearch == null ? "" : libSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return apps;
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry a : apps) if (a.label.toLowerCase(Locale.ROOT).contains(q)) out.add(a);
        return out;
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<AppEntry> list = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                if (getPackageName().equals(ri.activityInfo.packageName)) continue;
                list.add(new AppEntry(
                    ri.loadLabel(pm).toString(),
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    ri.loadIcon(pm)));
            }
            Collator collator = Collator.getInstance(Locale.GERMAN);
            Collections.sort(list, (a, b) -> collator.compare(a.label, b.label));
            handler.post(() -> {
                apps.clear();
                apps.addAll(list);
                renderLibrary();
                refreshRecents();
            });
        }).start();
    }

    private void registerPackageReceiver() {
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                loadApps();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(packageReceiver, filter);
    }

    private void launch(AppEntry app) {
        open(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    private void open(Intent intent) {
        try {
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Keine passende App gefunden.", Toast.LENGTH_SHORT).show();
        }
    }

    /* ================= Zuletzt (recently used apps) ================= */

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = Build.VERSION.SDK_INT >= 29
            ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName())
            : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Apps brought to the foreground in the last three days, newest first, limited to apps
     * the Mediathek knows (launchable ones). Needs the special "Usage access" permission;
     * without it both rows show a button to the settings page instead.
     */
    private void refreshRecents() {
        if (recentHome == null || apps.isEmpty()) return;
        if (!hasUsageAccess()) {
            showRecentsPrompt(recentHome);
            showRecentsPrompt(recentLib);
            return;
        }
        Map<String, AppEntry> byPackage = new HashMap<>();
        for (AppEntry a : apps) {
            if (!byPackage.containsKey(a.component.getPackageName())) byPackage.put(a.component.getPackageName(), a);
        }
        new Thread(() -> {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            List<AppEntry> recent = new ArrayList<>();
            if (usm != null) {
                long now = System.currentTimeMillis();
                UsageEvents events = usm.queryEvents(now - 3L * 24 * 3600 * 1000, now);
                UsageEvents.Event e = new UsageEvents.Event();
                Map<String, Long> last = new HashMap<>();
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last.put(e.getPackageName(), e.getTimeStamp());
                }
                List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
                Collections.sort(sorted, (a, b) -> Long.compare(b.getValue(), a.getValue()));
                for (Map.Entry<String, Long> en : sorted) {
                    AppEntry app = byPackage.get(en.getKey());
                    if (app != null) recent.add(app);
                    if (recent.size() == 8) break;
                }
            }
            handler.post(() -> {
                renderRecents(recentHome, recent, 4);
                renderRecents(recentLib, recent, 8);
            });
        }).start();
    }

    private void renderRecents(LinearLayout into, List<AppEntry> recent, int max) {
        into.removeAllViews();
        if (recent.isEmpty()) {
            TextView none = text(13, ink3, false);
            none.setText("Noch keine Apps verwendet.");
            into.addView(none);
            return;
        }
        LinearLayout row = null;
        int n = Math.min(max, recent.size());
        for (int i = 0; i < n; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                into.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            AppEntry app = recent.get(i);
            View tile = tile(app.label, app.icon, 32);
            tile.setOnClickListener(v -> launch(app));
            row.addView(tile, weighted(i % 4 != 0));
        }
        for (int i = n % 4; i != 0 && i < 4; i++) row.addView(new View(this), weighted(true));
    }

    private void showRecentsPrompt(LinearLayout into) {
        into.removeAllViews();
        TextView ask = text(13, ink, true);
        ask.setText("Zuletzt verwendete Apps anzeigen →");
        ask.setPadding(dp(14), dp(12), dp(14), dp(12));
        ask.setBackground(box(card, line, 8));
        ask.setOnClickListener(v -> {
            Toast.makeText(this, "ARC-DNA antippen und Zugriff erlauben", Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        });
        into.addView(ask);

        // Sideloaded apps get "App was denied access" until restricted settings are allowed once.
        TextView blocked = text(12, ink2, false);
        blocked.setText("„Zugriff verweigert“? Android sperrt das bei Apps ausserhalb des Play Store: "
            + "hier tippen → App-Info → ⋮ oben rechts → „Eingeschränkte Einstellungen zulassen“, "
            + "dann oben nochmal.");
        blocked.setLineSpacing(0, 1.25f);
        blocked.setPadding(dp(2), dp(8), dp(2), 0);
        blocked.setOnClickListener(v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", getPackageName(), null))));
        into.addView(blocked);
    }

    /* ================= Circle gesture → rotate ================= */

    /** Watches every one-finger stroke on the home screen; the views still get the touches as usual. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        lastTouch = android.os.SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            stroke.clear();
            strokeMulti = false;
            stroke.add(new float[]{ev.getX(), ev.getY()});
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            strokeMulti = true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int h = 0; h < ev.getHistorySize(); h++) stroke.add(new float[]{ev.getHistoricalX(h), ev.getHistoricalY(h)});
            stroke.add(new float[]{ev.getX(), ev.getY()});
        } else if (action == MotionEvent.ACTION_UP) {
            stroke.add(new float[]{ev.getX(), ev.getY()});
            if (!strokeMulti && !libraryOpen && calibStage < 0) {
                int dir = circleDirection();
                if (dir != 0) rotateStep(dir);
            }
            stroke.clear();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            stroke.clear();
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * +1 for a clockwise circle, -1 for counter-clockwise, 0 for anything else. A circle has to
     * go most of the way round its centre (≥ 300°), be reasonably round, at least 80 dp across,
     * and end near where it started, so scrolls, swipes and taps never count.
     */
    private int circleDirection() {
        int n = stroke.size();
        if (n < 12) return 0;
        float cx = 0, cy = 0;
        for (float[] p : stroke) {
            cx += p[0];
            cy += p[1];
        }
        cx /= n;
        cy /= n;
        double meanR = 0;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            r[i] = Math.hypot(stroke.get(i)[0] - cx, stroke.get(i)[1] - cy);
            meanR += r[i];
        }
        meanR /= n;
        if (meanR < dp(40)) return 0;
        double var = 0;
        for (double v : r) var += (v - meanR) * (v - meanR);
        if (Math.sqrt(var / n) / meanR > 0.35) return 0;
        double sweep = 0, prev = Math.atan2(stroke.get(0)[1] - cy, stroke.get(0)[0] - cx);
        for (int i = 1; i < n; i++) {
            double a = Math.atan2(stroke.get(i)[1] - cy, stroke.get(i)[0] - cx);
            double d = a - prev;
            if (d > Math.PI) d -= 2 * Math.PI;
            if (d < -Math.PI) d += 2 * Math.PI;
            sweep += d;
            prev = a;
        }
        if (Math.abs(sweep) < Math.toRadians(300)) return 0;
        float[] first = stroke.get(0), last = stroke.get(n - 1);
        if (Math.hypot(last[0] - first[0], last[1] - first[1]) > meanR * 1.2) return 0;
        return sweep > 0 ? 1 : -1; // screen y points down, so a positive sweep looks clockwise
    }

    /** Clockwise: portrait → landscape → upside down → other landscape → portrait; counter-clockwise backwards. */
    private void rotateStep(int dir) {
        int i = (prefs.getInt("orient", 0) + dir + 4) % 4;
        prefs.edit().putInt("orient", i).commit();
        hop();
        voice.say(dir > 0 ? "Yip-yip!" : "Mrrp?");
        handler.postDelayed(() -> setRequestedOrientation(ORIENTATIONS[i]), 180);
    }

    /* ================= Updates ================= */

    private void onUpdateState(String text, boolean available) {
        updateLib.setText(text.toUpperCase(Locale.GERMAN));
        updateLib.setTextColor(available ? rust : ink2);
        updateHome.setText(text);
        updateHome.setVisibility(available ? View.VISIBLE : View.GONE);
    }

    /** One-time setup: a read-only GitHub token for this private repository. */
    private void showTokenDialog() {
        EditText input = new EditText(this);
        input.setHint("github_pat_…");
        input.setSingleLine(true);
        input.setText(updater.token());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
            .setTitle("Updates einrichten")
            .setMessage("GitHub → Fine-grained token: Repository access nur „arc-dna-home“, "
                + "Permissions → Contents: Read-only. Token hier einfügen – er bleibt nur auf diesem Handy.")
            .setView(wrap)
            .setPositiveButton("Speichern", (d, w) -> {
                updater.setToken(input.getText().toString());
                updater.check(true);
            })
            .setNeutralButton("Token erstellen", (d, w) ->
                open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new"))))
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    /* ================= Head / gaze scrolling ================= */

    /** "off", "nod" (head tilt) or "gaze" (eyes). Older versions stored a boolean. */
    private String headMode() {
        String m = prefs.getString("headMode", null);
        if (m == null) m = prefs.getBoolean("headScroll", false) ? "nod" : "off";
        return m;
    }

    /** Toggle in the Mediathek cycles AUS → NICKEN → BLICK → AUS. */
    private void cycleHeadMode() {
        String m = headMode();
        String next = m.equals("off") ? "nod" : m.equals("nod") ? "gaze" : "off";
        prefs.edit().putString("headMode", next).remove("headScroll").apply();
        paintHeadToggle();
        stopHead();
        if (!next.equals("off") && libraryOpen) startHead();
    }

    private void paintHeadToggle() {
        String m = headMode();
        headToggle.setText(m.equals("nod") ? "KOPF-SCROLL: NICKEN" : m.equals("gaze") ? "KOPF-SCROLL: BLICK" : "KOPF-SCROLL: AUS");
        headToggle.setTextColor(m.equals("off") ? ink2 : rust);
    }

    private void startHead() {
        String m = headMode();
        if (m.equals("off")) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (head == null) head = new HeadTracker(this, this);
        calibFrames = 0;
        calibSum = 0;
        smoothTilt = m.equals("gaze") ? prefs.getFloat("gazeMid", 0f) : 0;
        boolean gaze = m.equals("gaze");
        head.start(gaze ? HeadTracker.Mode.GAZE : HeadTracker.Mode.NOD);
        showHeadState(0);
        headLabel.setVisibility(View.VISIBLE);
        if (gaze && !prefs.contains("gazeMid")) startGazeCalibration();
        else if (!gaze) Toast.makeText(this, "Kopf gerade halten … dann nicken zum Scrollen", Toast.LENGTH_SHORT).show();
    }

    private void stopHead() {
        endGazeCalibration();
        if (head != null) head.stop();
        headSpeed = 0;
        handler.removeCallbacks(headScroller);
        headLabel.setVisibility(View.GONE);
    }

    /** On the Mediathek button while active: mode plus ↑ / ↓ / · (‖ = resting after a touch). */
    private void showHeadState(int dir) {
        String arrow = dir == 2 ? "‖" : dir < 0 ? "↑" : dir > 0 ? "↓" : "·";
        String mode = headMode().equals("gaze") ? "BLICK" : "NICKEN";
        headToggle.setText("KOPF-SCROLL: " + mode + " " + arrow);
        headLabel.setText("◉ " + mode + " " + arrow + "  ");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_AUDIO) {
            music.onAudioPermission(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED);
            return;
        }
        if (requestCode != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startHead();
        } else {
            prefs.edit().putString("headMode", "off").apply();
            paintHeadToggle();
            Toast.makeText(this, "Ohne Kamera kein Kopf-Scroll.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * NICKEN: the first frames set the neutral head position; tilting more than ~4° up or down
     * scrolls, reaching full speed at ~12°. The neutral point slowly follows your posture while
     * you hold still, so slouching doesn't start a scroll.
     */
    @Override
    public void onTilt(float pitch) {
        if (calibFrames < 6) {
            calibSum += pitch;
            if (++calibFrames == 6) basePitch = calibSum / 6f;
            return;
        }
        float delta = pitch - basePitch;
        smoothTilt = smoothTilt * 0.5f + delta * 0.5f;
        float dead = 4f;
        float over = Math.abs(smoothTilt) - dead;
        if (over <= 0) {
            basePitch = basePitch * 0.99f + pitch * 0.01f;
            setHeadSpeed(0);
            return;
        }
        float speed = (0.25f + 0.75f * Math.min(1f, over / 8f)) * dp(26);
        setHeadSpeed(smoothTilt > 0 ? -speed : speed); // looking up scrolls up
    }

    /**
     * BLICK: three zones from the user's own calibration (top / middle / bottom of the screen).
     * Starting needs the eyes 55 % of the way from the middle to the top or bottom point; once
     * scrolling it keeps going until they are back within 35 %, so the wide middle band is a
     * calm "stop" area and the edges don't flicker. Speed grows a little the further you look.
     */
    @Override
    public void onGaze(float vertical) {
        if (calibStage >= 0) {
            if (calibCollect) {
                calibAcc[calibStage] += vertical;
                calibN[calibStage]++;
            }
            return;
        }
        smoothTilt = smoothTilt * 0.6f + vertical * 0.4f;
        float mid = prefs.getFloat("gazeMid", 0f);
        float downRange = Math.max(0.03f, prefs.getFloat("gazeBottom", mid + 0.08f) - mid);
        float upRange = Math.max(0.03f, mid - prefs.getFloat("gazeTop", mid - 0.08f));
        float pos = smoothTilt - mid;
        float frac = pos > 0 ? pos / downRange : -pos / upRange;
        int dir = pos > 0 ? 1 : -1;
        boolean sameWay = headSpeed != 0 && Math.signum(headSpeed) == dir;
        float threshold = sameWay ? 0.35f : 0.55f;
        if (frac < threshold) {
            setHeadSpeed(0);
            return;
        }
        float speed = (0.4f + 0.6f * Math.min(1f, (frac - 0.35f) / 0.65f)) * dp(9);
        setHeadSpeed(dir * speed); // eyes down scrolls down, eyes up scrolls up
    }

    /* ---------- Gaze calibration: a dot at the top, middle and bottom ---------- */

    private void startGazeCalibration() {
        calibStage = 0;
        calibAcc = new float[3];
        calibN = new int[3];
        calibRetries = 0;
        setHeadSpeed(0);
        calibOverlay.setVisibility(View.VISIBLE);
        calibOverlay.setAlpha(0f);
        calibOverlay.animate().alpha(1f).setDuration(200).start();
        runCalibStage();
    }

    private void runCalibStage() {
        int stage = calibStage;
        String[] where = {"oben", "in der Mitte", "unten"};
        calibText.setText("Schau auf den Punkt " + where[stage]);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) calibDot.getLayoutParams();
        lp.gravity = Gravity.CENTER_HORIZONTAL | (stage == 0 ? Gravity.TOP : stage == 1 ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM);
        calibDot.setLayoutParams(lp);
        calibDot.setScaleX(0.4f);
        calibDot.setScaleY(0.4f);
        calibDot.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
        calibCollect = false;
        long now = android.os.SystemClock.uptimeMillis();
        // Give the eyes ~0.6 s to arrive, then sample for ~1.2 s.
        handler.postAtTime(() -> calibCollect = true, calibToken, now + 600);
        handler.postAtTime(this::finishCalibStage, calibToken, now + 1800);
    }

    private void finishCalibStage() {
        calibCollect = false;
        if (calibN[calibStage] < 5 && calibRetries < 2) {
            calibRetries++;
            calibText.setText("Gesicht nicht erkannt – nochmal");
            handler.postAtTime(this::runCalibStage, calibToken, android.os.SystemClock.uptimeMillis() + 700);
            return;
        }
        if (calibStage < 2) {
            calibStage++;
            runCalibStage();
            return;
        }
        float top = calibN[0] > 0 ? calibAcc[0] / calibN[0] : -0.1f;
        float mid = calibN[1] > 0 ? calibAcc[1] / calibN[1] : 0f;
        float bottom = calibN[2] > 0 ? calibAcc[2] / calibN[2] : 0.1f;
        prefs.edit().putFloat("gazeTop", top).putFloat("gazeMid", mid).putFloat("gazeBottom", bottom).apply();
        endGazeCalibration();
        smoothTilt = mid;
        Toast.makeText(this, "Blick kalibriert – Neu kalibrieren: Knopf lange drücken", Toast.LENGTH_SHORT).show();
    }

    private void endGazeCalibration() {
        handler.removeCallbacksAndMessages(calibToken);
        calibStage = -1;
        calibCollect = false;
        if (calibOverlay.getVisibility() == View.VISIBLE) {
            calibOverlay.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> calibOverlay.setVisibility(View.GONE)).start();
        }
    }

    private View buildCalibOverlay() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(panel);
        o.setClickable(true);
        o.setVisibility(View.GONE);
        o.setOnClickListener(v -> {
            endGazeCalibration();
            Toast.makeText(this, "Kalibrierung abgebrochen", Toast.LENGTH_SHORT).show();
        });

        calibDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(rust);
        calibDot.setBackground(dot);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
        dlp.setMargins(0, dp(70), 0, dp(70));
        o.addView(calibDot, dlp);

        calibText = text(17, ink, true);
        calibText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        tlp.topMargin = dp(70);
        o.addView(calibText, tlp);

        TextView skip = monoText(11, ink3);
        skip.setText("BLICK-KALIBRIERUNG · TIPPEN = ABBRECHEN");
        skip.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        slp.topMargin = dp(120);
        o.addView(skip, slp);
        return o;
    }

    @Override
    public void onLost() {
        setHeadSpeed(0);
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        prefs.edit().putString("headMode", "off").apply();
        paintHeadToggle();
        stopHead();
    }

    private void setHeadSpeed(float speed) {
        // Eyes rest for a moment after the screen was touched or scrolled by finger.
        boolean resting = headMode().equals("gaze")
            && android.os.SystemClock.uptimeMillis() - lastTouch < EYE_PAUSE_MS;
        if (resting) speed = 0;
        boolean wasIdle = headSpeed == 0;
        headSpeed = speed;
        showHeadState(resting ? 2 : speed < 0 ? -1 : speed > 0 ? 1 : 0);
        if (speed != 0 && wasIdle) handler.post(headScroller);
    }

    /* ================= Wallpaper ================= */

    /**
     * Light mode gets the light ARC-DNA wallpaper, dark mode the dark one, on home and lock screen.
     * The activity is recreated when the system switches modes, so this runs on every switch;
     * it only writes the wallpaper when the mode differs from the last one applied.
     * Long-press the clock to turn this off (e.g. to keep a wallpaper of your own).
     */
    private void applyWallpaperIfNeeded() {
        if (!prefs.getBoolean("autoWallpaper", true) || liveWallpaperActive()) return;
        boolean night = isNight();
        // The suffix is the wallpaper version: bumping it makes an update replace the old image.
        String mode = (night ? "dark" : "light") + "-v2";
        if (mode.equals(prefs.getString("wallpaperMode", ""))) return;
        int res = night ? R.drawable.wallpaper_dark : R.drawable.wallpaper_light;
        Context app = getApplicationContext();
        new Thread(() -> {
            try (java.io.InputStream in = app.getResources().openRawResource(res)) {
                WallpaperManager.getInstance(app).setStream(in, null, true,
                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                prefs.edit().putString("wallpaperMode", mode).apply();
            } catch (java.io.IOException | RuntimeException e) {
                handler.post(() -> Toast.makeText(app, "Wallpaper konnte nicht gesetzt werden.", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private boolean liveWallpaperActive() {
        android.app.WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
        return info != null && getPackageName().equals(info.getPackageName());
    }

    /** Long-press the clock: ARC-DNA Live, static light/dark, or leave the wallpaper alone. */
    private void showWallpaperChoice() {
        String[] items = {
            "ARC-DNA Live – Regenbogen, Glitzer & Rave (Sperrbildschirm)",
            "Statisch – hell/dunkel automatisch",
            "Aus – eigenes Wallpaper behalten",
        };
        new AlertDialog.Builder(this)
            .setTitle("Wallpaper")
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    Toast.makeText(this, "Wallpaper festlegen → „Startbildschirm und Sperrbildschirm“", Toast.LENGTH_LONG).show();
                    Intent live = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, ArcWallpaper.class));
                    try {
                        startActivity(live);
                    } catch (ActivityNotFoundException e) {
                        open(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                    }
                } else if (which == 1) {
                    prefs.edit().putBoolean("autoWallpaper", true).remove("wallpaperMode").apply();
                    if (liveWallpaperActive()) {
                        try {
                            WallpaperManager.getInstance(this).clear();
                        } catch (java.io.IOException ignored) {
                        }
                    }
                    applyWallpaperIfNeeded();
                    Toast.makeText(this, "Statisches Wallpaper: hell/dunkel automatisch", Toast.LENGTH_SHORT).show();
                } else {
                    prefs.edit().putBoolean("autoWallpaper", false).apply();
                    Toast.makeText(this, "Wallpaper bleibt, wie es ist", Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    /* ================= Clock, focus, network ================= */

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        String time = String.format(Locale.ROOT, "%02d:%02d", h, c.get(Calendar.MINUTE));
        SpannableString s = new SpannableString(time);
        s.setSpan(new ForegroundColorSpan(rust), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        clock.setText(s);
        date.setText(new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(c.getTime()));
        String hello = h < 5 ? "Gute Nacht" : h < 11 ? "Guten Morgen" : h < 17 ? "Hallo" : h < 22 ? "Guten Abend" : "Gute Nacht";
        greet.setText(hello + ", Muharrem.");

        // Today's focus resets at midnight.
        String saved = today().equals(prefs.getString("focusDay", "")) ? prefs.getString("focus", "") : "";
        if (!focus.hasFocus() && !saved.equals(focus.getText().toString())) focus.setText(saved);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().getTime());
    }

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        paintNet(cm != null && cm.getActiveNetwork() != null);
        if (cm == null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> paintNet(true));
            }

            @Override
            public void onLost(Network network) {
                handler.post(() -> paintNet(false));
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    private void paintNet(boolean online) {
        SpannableString s = new SpannableString(online ? "● ONLINE" : "● OFFLINE");
        s.setSpan(new ForegroundColorSpan(online ? 0xFF1A7A3C : ink3), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        net.setText(s);
    }

    /* ================= Frosted sections at the fold ================= */

    /**
     * A section that isn't fully visible above the bottom fade is blurred and faded like frosted
     * glass, more the less of it shows; it turns sharp once scrolled fully into view. A section
     * label shares the state of the block it introduces, so heading and content clear together.
     */
    private void updateFolds() {
        if (content == null || scroll.getHeight() == 0) return;
        int limit = scroll.getScrollY() + scroll.getHeight() - foldInset;
        int n = content.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = content.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            boolean isLabel = LABEL_TAG.equals(child.getTag()) && i + 1 < n;
            View block = isLabel ? content.getChildAt(i + 1) : child;
            float hidden = block.getHeight() == 0 ? 0
                : Math.max(0f, Math.min(1f, (block.getBottom() - limit) / (float) block.getHeight()));
            frost(child, hidden);
            if (isLabel) {
                frost(block, hidden);
                i++;
            }
        }
    }

    private void frost(View v, float hidden) {
        Float last = foldState.get(v);
        if (last != null && Math.abs(last - hidden) < 0.02f && (hidden > 0 || last == 0)) return;
        foldState.put(v, hidden);
        if (hidden <= 0.001f) {
            v.setAlpha(1f);
            if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null);
            return;
        }
        v.setAlpha(1f - 0.5f * Math.min(1f, hidden * 2f));
        if (Build.VERSION.SDK_INT >= 31) {
            float r = dp(4) + hidden * dp(14);
            v.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL));
        }
    }

    /* ================= Hide / fox / quake ================= */

    /** Fade the home screen down to a faint ghost so the wallpaper shows, or bring it back. */
    private void setHidden(boolean hide) {
        if (hide == hidden) return;
        hidden = hide;
        prefs.edit().putBoolean("hidden", hide).apply();
        if (hide) {
            hideKeyboard();
            focus.clearFocus();
            webQuery.clearFocus();
        }
        shield.setVisibility(hide ? View.VISIBLE : View.GONE);
        content.animate().cancel();
        content.animate()
            .alpha(hide ? 0.12f : 1f)
            .scaleX(hide ? 0.97f : 1f)
            .scaleY(hide ? 0.97f : 1f)
            .setDuration(hide ? 420 : 300)
            .setInterpolator(new DecelerateInterpolator())
            .start();
    }

    private void foxCall() {
        hop();
        String call = FOX_CALLS[random.nextInt(FOX_CALLS.length)];
        say(call, 1400);
        voice.say(call);
    }

    private void say(String s, long ms) {
        bubble.setText(s);
        bubble.setVisibility(View.VISIBLE);
        bubble.setAlpha(1f);
        bubble.animate().cancel();
        bubble.animate().alpha(0f).setStartDelay(ms).setDuration(300)
            .withEndAction(() -> bubble.setVisibility(View.GONE)).start();
    }

    private void hop() {
        fox.animate().cancel();
        fox.animate().translationY(-dp(26)).setDuration(180)
            .withEndAction(() -> fox.animate().translationY(0).setDuration(220).start()).start();
    }

    private void quake() {
        hop();
        seismo.kick(1f);
        View target = libraryOpen ? library : content;
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(900);
        a.addUpdateListener(v -> {
            float k = (float) v.getAnimatedValue() * dp(10);
            target.setTranslationX((random.nextFloat() - .5f) * 2 * k);
            target.setTranslationY((random.nextFloat() - .5f) * 2 * k);
            target.setRotation((random.nextFloat() - .5f) * k * .15f);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                target.setTranslationX(0);
                target.setTranslationY(0);
                target.setRotation(0);
            }
        });
        a.start();
        say("Whoa.", 1200);
        voice.say("Whoa.");
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double g = Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;
        long now = System.currentTimeMillis();
        if (g > 14 && now - lastShake > 2500) {
            lastShake = now;
            quake();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /* ================= Lifecycle ================= */

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        ticker.run();
        refreshRecents();
        music.start();
        updater.check(false);
        Sensor acc = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        if (libraryOpen && !headMode().equals("off")
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startHead();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        music.stop();
        if (sensors != null) sensors.unregisterListener(this);
        stopHead();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (packageReceiver != null) unregisterReceiver(packageReceiver);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null && netCallback != null) cm.unregisterNetworkCallback(netCallback);
        if (head != null) head.close();
        voice.close();
    }

    /** Home pressed while already home: close the Mediathek, back to the top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.ACTION_STATUS.equals(intent.getAction())) {
            updater.onStatus(intent);
            return;
        }
        closeLibrary();
        hideKeyboard();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (calibStage >= 0) endGazeCalibration();
        else if (libraryOpen) closeLibrary();
        else if (hidden) setHidden(false);
        else {
            hideKeyboard();
            scroll.smoothScrollTo(0, 0);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View v = getCurrentFocus();
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /* ================= Helpers ================= */

    /** Section label: short rust bar + monospace caps, like the website. */
    private View label(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setTag(LABEL_TAG);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackgroundColor(rust);
        row.addView(bar, new LinearLayout.LayoutParams(dp(22), dp(2)));
        TextView t = monoText(11, ink3);
        t.setLetterSpacing(0.2f);
        t.setText(s.toUpperCase(Locale.GERMAN));
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private View tile(String name, Drawable icon, int iconDp) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(4), dp(12), dp(4), dp(10));
        t.setBackground(box(card, line, 8));

        ImageView iv = new ImageView(this);
        if (icon != null) iv.setImageDrawable(icon);
        t.addView(iv, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

        TextView tv = text(12, ink, true);
        tv.setText(name);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(tv, margins(-1, -2, 0, dp(6), 0, 0));
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(ink3);
        e.setTextColor(ink);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setBackground(box(card, line, 8));
        return e;
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView monoText(float sp, int colour) {
        TextView t = text(sp, colour, false);
        t.setTypeface(mono, Typeface.BOLD);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private LinearLayout.LayoutParams weighted(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.leftMargin = dp(8);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }

    /** Lays children out left to right and wraps to the next line, like the web page's chips. */
    private static final class FlowLayout extends ViewGroup {
        private final int gap;

        FlowLayout(Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int max = MeasureSpec.getSize(widthSpec);
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + rowH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
        }
    }

    /** The seismograph line along the bottom edge, as on the website; kick() makes it spike. */
    private final class SeismoView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float[] trace = new float[0];
        private float amp;
        private boolean animating;

        SeismoView(Context c) {
            super(c);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(1.2f));
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            textPaint.setTextSize(dp(10));
            textPaint.setLetterSpacing(0.12f);
        }

        void kick(float a) {
            amp = Math.max(amp, a);
            if (!animating) {
                animating = true;
                postOnAnimation(step);
            }
        }

        private final Runnable step = new Runnable() {
            @Override
            public void run() {
                if (trace.length == 0) {
                    animating = false;
                    return;
                }
                System.arraycopy(trace, 1, trace, 0, trace.length - 1);
                trace[trace.length - 1] = amp * (random.nextFloat() * 2 - 1);
                amp *= 0.965f;
                invalidate();
                boolean flat = amp < 0.003f;
                for (float v : trace) if (Math.abs(v) > 0.003f) flat = false;
                if (flat) animating = false;
                else postOnAnimation(this);
            }
        };

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            trace = new float[Math.max(1, w / dp(3))];
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float mid = h * 0.62f, half = h * 0.36f;
            boolean hot = amp > 0.05f;
            path.reset();
            for (int i = 0; i < trace.length; i++) {
                float x = w - (trace.length - 1 - i) * (float) dp(3);
                float y = mid + trace[i] * half;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            linePaint.setColor(hot ? rust : (ink & 0x00FFFFFF) | 0x59000000);
            canvas.drawPath(path, linePaint);
            textPaint.setColor(hot ? rust : ink3);
            canvas.drawText(String.format(Locale.ROOT, "SEISMO  M %.1f", amp * 6), dp(12), dp(11), textPaint);
        }
    }
}
