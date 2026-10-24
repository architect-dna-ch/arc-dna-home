package ch.architectdna.home;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.UiModeManager;
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
import android.media.AudioManager;
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
import android.view.ViewAnimationUtils;
import android.view.View;
import android.view.WindowManager;
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
    private static final int REQ_NOTIFY = 9;
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
    /** True while the home screen is in front; Home pressed then (not on return from an app) scrolls up. */
    private boolean homeInFront;
    private LinearLayout content, libGrid, homeLib;
    private FrameLayout library;
    private View shield;
    private TextView alarmRow;
    private TextView clock, date, greet, net, headLabel, headToggle, bubble;
    private EditText focus, webQuery, libSearch;
    private ImageView fox;
    private EdgeLight edgeLight;
    private View libFoldHead;
    /** Volume raised (keys, music slider, Bluetooth speaker) while home is in front: the fox spins. */
    private BroadcastReceiver volumeReceiver;
    private android.animation.ObjectAnimator foxSpin;
    private float spinTarget;
    private android.animation.AnimatorSet foxSad;
    private long lastTwirl;
    private SeismoView seismo;
    private View bottomScrim, topScrim;
    private LinearLayout f1Box;
    private TextView f1Time;
    private boolean f1, f1Running;
    private F1Panel f1Panel;
    private FoxRunView game;
    private ImageView gameButton;
    private BrightnessLever lever;
    /** System brightness (0..255) for LO / MID / HI. */
    private static final int[] BRIGHTNESS = {12, 70, 255};
    private FrameLayout appTilesBox;
    private List<AppEntry> lastRecent = new ArrayList<>();
    private long lastHomePress;

    /** Redraws seconds.milliseconds every frame while F1 mode is on and the screen is in front. */
    private final Runnable f1Tick = new Runnable() {
        @Override
        public void run() {
            if (!f1 || !f1Running) return;
            long now = System.currentTimeMillis();
            Calendar c = Calendar.getInstance();
            f1Time.setText(String.format(Locale.ROOT, "%02d.%03d", c.get(Calendar.SECOND), now % 1000));
            f1Time.postOnAnimation(this);
        }
    };
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
    private boolean strokeMulti, strokeOnSlider, circling, cubeDrag;
    private float cubeX, cubeY;
    private float downX, downY, lastPX, lastPY, pathTurn, pathLen, lastHeading = Float.NaN;
    private long downTime;
    private FrameLayout rootFrame;
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
        DiagLog.init(this);
        DiagLog.log("launcher start, " + wallpaperStatusSafe());
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
        AlarmScheduler.schedule(this);
        if (!prefs.getBoolean("lockIntroShown", false) && !liveOnLock()) handler.postDelayed(this::showLockIntro, 1200);
        // Keep the scroll position across a recreate (dark/light swipe, process restart).
        int savedY = prefs.getInt("homeScrollY", 0);
        if (savedY > 0) scroll.post(() -> scroll.scrollTo(0, savedY));
        updater = new Updater(this, prefs, this::onUpdateState);
        setRequestedOrientation(ORIENTATIONS[prefs.getInt("orient", 0)]);
        prefs.edit().remove("hidden").apply();   // the old wallpaper-only mode became the Fuchsjagd game
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
        rootFrame = root;

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Empty space: tap = only wallpaper, hold = Mediathek.
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
        clock.setOnClickListener(v -> setF1(!f1));

        // F1 mode: seconds and milliseconds ticking beside the clock, like a race timer.
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clockRow.addView(clock);
        f1Box = new LinearLayout(this);
        f1Box.setOrientation(LinearLayout.VERTICAL);
        f1Box.setPadding(dp(8), 0, 0, dp(14));
        TextView f1Label = monoText(11, rust);
        f1Label.setText("F1 · LAP");
        f1Label.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC));
        f1Box.addView(f1Label);
        f1Time = text(22, ink, true);
        f1Time.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        f1Time.setIncludeFontPadding(false);
        f1Box.addView(f1Time);
        f1Box.setOnClickListener(v -> setF1(false));
        f1Box.setVisibility(View.GONE);
        clockRow.addView(f1Box);

        // Brightness lever to the right of the clock: HI / MID / LO, long-press = automatic.
        clockRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        lever = new BrightnessLever(this, new int[]{ink, ink2, ink3, rust, card, line}, new BrightnessLever.Listener() {
            @Override
            public void onLevel(int level) {
                setBrightness(level);
            }

            @Override
            public void onAuto() {
                setAutoBrightness();
            }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(64), dp(118));
        llp.bottomMargin = dp(10);
        clockRow.addView(lever, llp);
        content.addView(clockRow);

        f1Panel = new F1Panel(this, new int[]{ink, ink2, ink3, rust, card, line}, voice);
        f1Panel.setVisibility(View.GONE);
        f1Panel.setOnClickListener(v -> setF1(false));
        content.addView(f1Panel, margins(-1, f1Height(), 0, dp(14), 0, 0));

        date = text(17, ink2, false);
        date.setPadding(0, dp(10), 0, 0);
        content.addView(date);
        greet = text(17, ink, true);
        content.addView(greet);
        alarmRow = monoText(12, ink3);
        alarmRow.setPadding(0, dp(10), 0, dp(4));
        alarmRow.setOnClickListener(v -> pickAlarm());
        alarmRow.setOnLongClickListener(v -> {
            setAlarmOn(!prefs.getBoolean("alarmOn", false));
            return true;
        });
        paintAlarm();

        // Priorities: search, apps and music always open; everything else folds away.
        content.addView(searchRow(), margins(-1, -2, 0, dp(20), 0, 0));

        content.addView(label("Apps"), margins(-1, -2, 0, dp(22), 0, 0));
        appTilesBox = new FrameLayout(this);
        appTilesBox.addView(appTiles());
        content.addView(appTilesBox, margins(-1, -2, 0, dp(10), 0, 0));

        // Zuletzt: always on, one row.
        content.addView(label("Zuletzt"), margins(-1, -2, 0, dp(22), 0, 0));
        recentHome = new LinearLayout(this);
        recentHome.setOrientation(LinearLayout.VERTICAL);
        content.addView(recentHome, margins(-1, -2, 0, dp(10), 0, 0));

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

        // Mediathek inline: all apps, folded by default.
        homeLib = new LinearLayout(this);
        homeLib.setOrientation(LinearLayout.VERTICAL);
        foldable("Mediathek", "lib", false, homeLib, dp(26));

        LinearLayout today = new LinearLayout(this);
        today.setOrientation(LinearLayout.VERTICAL);
        today.addView(alarmRow);
        today.addView(focusCard(), margins(-1, -2, 0, dp(6), 0, 0));
        foldable("Heute & Wecker", "today", false, today, dp(22));


        foldable("Projekte", "projects", false, projectChips(), dp(22));

        TextView hint = monoText(11, ink3);
        hint.setText("Abschnitt-Titel tippen = auf/zu · leere Fläche halten = volle Mediathek · Hasen-Knopf unten links = Fuchsjagd · wischen ← dunkel, → hell · Kreis = drehen · Fuchs: tippen = Yip, halten = Chat · schütteln = Erdbeben");
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

        // A matching fade at the top keeps the status bar readable over scrolled content.
        topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{panel, (panel & 0x00FFFFFF) | 0xB3000000, panel & 0x00FFFFFF}));
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP));

        // A soft fade at the bottom so the navigation buttons, seismograph and fox sit on a calm surface.
        bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{panel, (panel & 0x00FFFFFF) | 0xB3000000, panel & 0x00FFFFFF}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, dp(100), Gravity.BOTTOM));

        // Floating rabbit button bottom-left, just above the fox: starts the Fuchsjagd game.
        gameButton = new ImageView(this);
        gameButton.setImageResource(R.drawable.rabbit);
        gameButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(9);
        gameButton.setPadding(pad, pad, pad, pad);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(card);
        round.setStroke(dp(1.5f), rust);
        gameButton.setBackground(round);
        gameButton.setElevation(dp(4));
        gameButton.setContentDescription("Fuchsjagd starten");
        gameButton.setOnClickListener(v -> startGame());
        root.addView(gameButton, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM | Gravity.START));

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
            int top0, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top0 = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top0 = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            content.setPadding(left + dp(20), top0 + dp(24), right + dp(20), bottom + dp(132));
            library.setPadding(left + dp(20), top0 + dp(20), right + dp(20), bottom + dp(12));
            calibOverlay.setPadding(left, top0, right, bottom);
            // Keep the seismograph and fox clear of the navigation buttons, with the fade behind them.
            bottomScrim.getLayoutParams().height = bottom + dp(84);
            topScrim.getLayoutParams().height = top0 + dp(28);
            topScrim.requestLayout();
            foldInset = bottom + dp(84);
            ((FrameLayout.LayoutParams) seismo.getLayoutParams()).setMargins(left, 0, right, bottom + dp(6));
            ((FrameLayout.LayoutParams) fox.getLayoutParams()).setMargins(left + dp(14), 0, 0, bottom + dp(20));
            ((FrameLayout.LayoutParams) gameButton.getLayoutParams()).setMargins(left + dp(18), 0, 0, bottom + dp(84));
            gameButton.requestLayout();
            ((FrameLayout.LayoutParams) bubble.getLayoutParams()).setMargins(left + dp(70), 0, 0, bottom + dp(72));
            bottomScrim.requestLayout();
            seismo.requestLayout();
            fox.requestLayout();
            bubble.requestLayout();
            return insets;
        });

        // Ring light on the display edge when it's too dark for head / eye scrolling.
        edgeLight = new EdgeLight(this, on -> {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = on ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        });
        root.addView(edgeLight, new FrameLayout.LayoutParams(-1, -1));
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
        int cols = cols();
        for (int i = 0; i < items.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                wrap.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            Intent intent = (Intent) items[i][2];
            View tile = tile((String) items[i][0], getDrawable((Integer) items[i][1]), 28);
            tile.setOnClickListener(v -> open(intent));
            row.addView(tile, weighted(i % cols != 0));
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

        TextView diag = monoText(11, ink2);
        diag.setText("DIAGNOSE-LOG TEILEN (ANONYM) →");
        diag.setPadding(dp(4), dp(12), dp(4), dp(8));
        diag.setOnClickListener(v -> shareDiagnostics());
        body.addView(diag);

        TextView appScroll = monoText(11, ink2);
        appScroll.setText("EINSTELLUNGEN · KOPF-SCROLL PRO APP →");
        appScroll.setPadding(dp(4), dp(12), dp(4), dp(8));
        appScroll.setOnClickListener(v -> showAppScrollSettings());
        body.addView(appScroll);
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

    /**
     * A section that folds: tap its label to open or close it (▸ / ▾), remembered across restarts.
     * The label stays paired with its block for the frosted-fold effect.
     */
    private void foldable(String title, String key, boolean openByDefault, View block, int top) {
        LinearLayout head = (LinearLayout) label(title);
        TextView arrow = monoText(11, rust);
        arrow.setPadding(dp(10), 0, 0, 0);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(arrow);
        head.setPadding(0, dp(8), 0, dp(8));
        boolean open = prefs.getBoolean("fold:" + key, openByDefault);
        block.setVisibility(open ? View.VISIBLE : View.GONE);
        arrow.setText(open ? "▾ ZU" : "▸ AUF");
        head.setOnClickListener(v -> {
            boolean now = block.getVisibility() != View.VISIBLE;
            prefs.edit().putBoolean("fold:" + key, now).apply();
            arrow.setText(now ? "▾ ZU" : "▸ AUF");
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (now) {
                block.setVisibility(View.VISIBLE);
                if (block == homeLib) renderHomeLibrary();
                block.setAlpha(0f);
                block.setTranslationY(-dp(12));
                block.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
                // Bring the opened section into view.
                block.post(() -> scroll.smoothScrollTo(0, Math.max(scroll.getScrollY(), head.getTop() - dp(80))));
            } else {
                block.animate().cancel();
                block.setVisibility(View.GONE);
            }
            scroll.post(this::updateFolds);
        });
        if (block == homeLib) libFoldHead = head;
        content.addView(head, margins(-1, -2, 0, top - dp(8), 0, 0));
        content.addView(block, margins(-1, -2, 0, dp(6), 0, 0));
    }

    /** The inline Mediathek on the home screen: built only while it is open. */
    private void renderHomeLibrary() {
        if (homeLib == null || homeLib.getVisibility() != View.VISIBLE) return;
        fillGrid(homeLib, apps, cols());
        // Bottom bar, so a long open list can be closed without scrolling back up to its title.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(box(card, line, 8));
        TextView close = monoText(11, rust);
        close.setText("▴ ZUKLAPPEN");
        close.setPadding(dp(14), dp(12), dp(14), dp(12));
        close.setOnClickListener(v -> {
            if (libFoldHead == null) return;
            libFoldHead.performClick();
            libFoldHead.post(() -> scroll.smoothScrollTo(0, Math.max(0, libFoldHead.getTop() - dp(120))));
        });
        bar.addView(close);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView more = monoText(11, ink2);
        more.setText("VOLLE MEDIATHEK →");
        more.setPadding(dp(14), dp(12), dp(14), dp(12));
        more.setOnClickListener(v -> openLibrary());
        bar.addView(more);
        homeLib.addView(bar, margins(-1, -2, 0, dp(12), 0, 0));
    }

    private void renderLibrary() {
        renderHomeLibrary();
        if (libGrid == null) return;
        fillGrid(libGrid, filtered(), cols());
    }

    private void fillGrid(LinearLayout grid, List<AppEntry> list, int cols) {
        grid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                grid.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            AppEntry app = list.get(i);
            View tile = tile(app.label, app.icon, 40);
            tile.setOnClickListener(v -> launch(app));
            tile.setOnLongClickListener(v -> {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.component.getPackageName(), null)));
                return true;
            });
            row.addView(tile, weighted(i % cols != 0));
        }
        // Keep the last row's tiles the same width as the others.
        if (row != null) {
            for (int i = list.size() % cols; i != 0 && i < cols; i++) row.addView(new View(this), weighted(true));
        }
        if (list.isEmpty()) {
            TextView none = text(14, ink3, false);
            none.setText(apps.isEmpty() ? "Apps werden geladen…" : "Keine App gefunden.");
            grid.addView(none);
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
                lastRecent = recent;
                renderRecents(recentHome, recent, cols());
                renderRecents(recentLib, recent, 2 * cols());
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
        int cols = cols();
        int n = Math.min(max, recent.size());
        for (int i = 0; i < n; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                into.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            AppEntry app = recent.get(i);
            View tile = tile(app.label, app.icon, 32);
            tile.setOnClickListener(v -> launch(app));
            row.addView(tile, weighted(i % cols != 0));
        }
        for (int i = n % cols; i != 0 && i < cols; i++) row.addView(new View(this), weighted(true));
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

    /**
     * Watches every one-finger stroke on the home screen. Once a stroke has clearly curved
     * (its direction turned ~150° over 120 dp) it counts as a circle: the views get a cancel, so
     * the page stops scrolling, and the rest of the stroke is ours. On release a full turn
     * (≥ 280°) rotates the screen. A fast, flat horizontal swipe switches dark/light instead.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        lastTouch = android.os.SystemClock.uptimeMillis();
        if (libraryOpen && headSpeed != 0) {
            headSpeed = 0;          // the finger takes over at once; head scrolling rests for 4 s
            handler.removeCallbacks(headScroller);
        }
        boolean gestures = !libraryOpen && calibStage < 0 && game == null;
        if (action == MotionEvent.ACTION_DOWN) {
            strokeMulti = false;
            circling = false;
            cubeDrag = false;
            pathTurn = 0;
            pathLen = 0;
            lastHeading = Float.NaN;
            downX = lastPX = ev.getX();
            downY = lastPY = ev.getY();
            downTime = ev.getEventTime();
            strokeOnSlider = music != null && music.isOnSlider(ev.getRawX(), ev.getRawY());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            strokeMulti = true;
            // Two fingers on the home screen turn the wallpaper cube in 3D.
            if (gestures && ev.getPointerCount() == 2 && !circling) {
                cubeDrag = true;
                cubeX = (ev.getX(0) + ev.getX(1)) / 2f;
                cubeY = (ev.getY(0) + ev.getY(1)) / 2f;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                if (!liveWallpaperActive() && !prefs.getBoolean("cubeHint", false)) {
                    prefs.edit().putBoolean("cubeHint", true).apply();
                    say("Würfel drehen geht mit ARC-DNA Live – Uhr lange drücken.", 2600);
                }
            }
            if (cubeDrag) return true;
        } else if (cubeDrag && action == MotionEvent.ACTION_MOVE) {
            if (ev.getPointerCount() >= 2) {
                float x = (ev.getX(0) + ev.getX(1)) / 2f, y = (ev.getY(0) + ev.getY(1)) / 2f;
                android.os.Bundle b = new android.os.Bundle();
                b.putFloat("dx", x - cubeX);
                b.putFloat("dy", y - cubeY);
                cubeX = x;
                cubeY = y;
                wallpaperCommand("ch.architectdna.CUBE_DRAG", b);
            }
            return true;
        } else if (cubeDrag && (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP
            || action == MotionEvent.ACTION_CANCEL)) {
            if (action == MotionEvent.ACTION_POINTER_UP) {
                wallpaperCommand("ch.architectdna.CUBE_RELEASE", null);
                return true;   // keep swallowing until the last finger lifts
            }
            cubeDrag = false;
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int h = 0; h < ev.getHistorySize(); h++) addPoint(ev.getHistoricalX(h), ev.getHistoricalY(h));
            addPoint(ev.getX(), ev.getY());
            if (gestures && !strokeMulti && !circling && !strokeOnSlider
                && Math.abs(pathTurn) > Math.toRadians(150) && pathLen > dp(120)) {
                circling = true;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
            if (circling) return true;
        } else if (action == MotionEvent.ACTION_UP) {
            addPoint(ev.getX(), ev.getY());
            if (circling) {
                circling = false;
                if (Math.abs(pathTurn) >= Math.toRadians(280)) rotateStep(pathTurn > 0 ? 1 : -1);
                return true;
            }
            float dx = ev.getX() - downX, dy = ev.getY() - downY;
            if (gestures && !strokeMulti && !strokeOnSlider
                && Math.abs(dx) > rootFrame.getWidth() * 0.4f && Math.abs(dy) < Math.abs(dx) * 0.4f
                && ev.getEventTime() - downTime < 800) {
                switchNight(dx < 0, ev.getX(), ev.getY());
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            circling = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void wallpaperCommand(String action, android.os.Bundle extras) {
        try {
            WallpaperManager.getInstance(this).sendWallpaperCommand(
                getWindow().getDecorView().getWindowToken(), action, 0, 0, 0, extras);
        } catch (RuntimeException ignored) {
        }
    }

    /** Adds a point (≥ 8 dp from the last, which smooths finger jitter) and adds up how far the path's direction has turned. */
    private void addPoint(float x, float y) {
        float dx = x - lastPX, dy = y - lastPY;
        float d = (float) Math.hypot(dx, dy);
        if (d < dp(8)) return;
        float heading = (float) Math.atan2(dy, dx);
        if (!Float.isNaN(lastHeading)) {
            float t = heading - lastHeading;
            if (t > Math.PI) t -= 2 * Math.PI;
            if (t < -Math.PI) t += 2 * Math.PI;
            pathTurn += t;  // screen y points down, so a positive turn looks clockwise
        }
        lastHeading = heading;
        lastPX = x;
        lastPY = y;
        pathLen += d;
    }

    /* ================= Swipe → dark / light ================= */

    /**
     * Right-to-left = dark, left-to-right = light, for ARC-DNA itself (home, Mediathek and its
     * wallpaper; Android doesn't let apps switch the system-wide setting). The new colour floods
     * out in a circle from the finger, then the screen redraws in the new mode.
     */
    private void switchNight(boolean dark, float x, float y) {
        if (Build.VERSION.SDK_INT < 31) {
            Toast.makeText(this, "Braucht Android 12 oder neuer.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (dark == isNight()) {
            hop();
            say(dark ? "Schon dunkel." : "Schon hell.", 1000);
            return;
        }
        View veil = new View(this);
        veil.setBackgroundColor(dark ? 0xFF121211 : 0xFFFAFAF8);
        rootFrame.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        voice.say(dark ? "Huff." : "Yip-yip!");
        veil.post(() -> {
            float radius = (float) Math.hypot(rootFrame.getWidth(), rootFrame.getHeight());
            android.animation.Animator a = ViewAnimationUtils.createCircularReveal(veil, (int) x, (int) y, 0, radius);
            a.setDuration(520);
            a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    setAppNight(dark ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO);
                }
            });
            a.start();
        });
    }

    private void setAppNight(int mode) {
        if (Build.VERSION.SDK_INT < 31) return;
        UiModeManager ui = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (ui != null) ui.setApplicationNightMode(mode);   // the activity is recreated in the new mode
    }

    /** Clockwise: portrait → landscape → upside down → other landscape → portrait; counter-clockwise backwards. */
    private void rotateStep(int dir) {
        int i = (prefs.getInt("orient", 0) + dir + 4) % 4;
        prefs.edit().putInt("orient", i).commit();
        hop();
        voice.say(dir > 0 ? "Yip-yip!" : "Mrrp?");
        handler.postDelayed(() -> setRequestedOrientation(ORIENTATIONS[i]), 180);
    }

    /* ================= Kopf-Scroll pro App ================= */

    private boolean scrollAssistOn() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(getPackageName() + "/");
    }

    /**
     * Per app: AUS → NICKEN → BLICK. ScrollAssistService (an accessibility service the user
     * switches on once) reads these as "appScroll:<package>" when that app comes to the front.
     */
    private void showAppScrollSettings() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView status = text(13, scrollAssistOn() ? ink2 : rust, true);
        status.setText(scrollAssistOn()
            ? "Bedienungshilfe „ARC-DNA Scroll“ ist an."
            : "Zuerst einmalig einschalten: Bedienungshilfen → Installierte Apps → ARC-DNA Scroll → An →");
        status.setPadding(0, 0, 0, dp(8));
        status.setOnClickListener(v -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        list.addView(status);

        TextView hint = text(12, ink3, false);
        hint.setText("Pro App antippen: AUS → NICKEN → BLICK. In der App zeigt eine kleine Pille den Zustand – antippen = Pause. "
            + "BLICK nutzt deine Kalibrierung aus der Mediathek.");
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(0, 0, 0, dp(10));
        list.addView(hint);

        java.util.Set<String> seen = new java.util.HashSet<>();
        List<AppEntry> sorted = new ArrayList<>(apps);
        // Apps already switched on first, then alphabetical.
        Collections.sort(sorted, (a, b) -> {
            boolean ao = !prefs.getString("appScroll:" + a.component.getPackageName(), "off").equals("off");
            boolean bo = !prefs.getString("appScroll:" + b.component.getPackageName(), "off").equals("off");
            return ao == bo ? 0 : ao ? -1 : 1;
        });
        for (AppEntry app : sorted) {
            String pkg = app.component.getPackageName();
            if (!seen.add(pkg)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView name = text(14, ink, false);
            name.setText(app.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(12), 0, dp(8), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView chip = monoText(11, ink2);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setBackground(box(card, line, 20));
            paintAppScroll(chip, prefs.getString("appScroll:" + pkg, "off"));
            chip.setOnClickListener(v -> {
                String now = prefs.getString("appScroll:" + pkg, "off");
                String next = now.equals("off") ? "nod" : now.equals("nod") ? "gaze" : "off";
                prefs.edit().putString("appScroll:" + pkg, next).apply();
                paintAppScroll(chip, next);
                if (!next.equals("off") && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                }
            });
            row.addView(chip);
            list.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        new AlertDialog.Builder(this)
            .setTitle("Kopf-Scroll pro App")
            .setView(sv)
            .setPositiveButton("Fertig", null)
            .show();
    }

    private void paintAppScroll(TextView chip, String mode) {
        chip.setText(mode.equals("nod") ? "NICKEN" : mode.equals("gaze") ? "BLICK" : "AUS");
        chip.setTextColor(mode.equals("off") ? ink3 : rust);
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
        edgeLight.watch(true);
        showHeadState(0);
        headLabel.setVisibility(View.VISIBLE);
        if (gaze && !prefs.contains("gazeMid")) startGazeCalibration();
        else if (!gaze) Toast.makeText(this, "Kopf gerade halten … dann nicken zum Scrollen", Toast.LENGTH_SHORT).show();
    }

    private void stopHead() {
        endGazeCalibration();
        if (head != null) head.stop();
        if (edgeLight != null) edgeLight.watch(false);
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

    /* ================= Bear alarm ================= */

    private void paintAlarm() {
        boolean on = prefs.getBoolean("alarmOn", false);
        int m = prefs.getInt("alarmMinutes", 7 * 60);
        String t = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
        alarmRow.setText(on ? "⏰ WECKER " + t + " · BÄR AUS DER HÖHLE" : "⏰ WECKER AUS · TIPPEN ZUM STELLEN");
        alarmRow.setTextColor(on ? rust : ink3);
    }

    private void pickAlarm() {
        int m = prefs.getInt("alarmMinutes", 7 * 60);
        android.app.TimePickerDialog d = new android.app.TimePickerDialog(this, (tp, h, min) -> {
            prefs.edit().putInt("alarmMinutes", h * 60 + min).apply();
            setAlarmOn(true);
        }, m / 60, m % 60, true);
        d.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Test", (di, w) ->
            startActivity(new Intent(this, BearAlarmActivity.class)));
        d.show();
    }

    private void setAlarmOn(boolean on) {
        prefs.edit().putBoolean("alarmOn", on).apply();
        AlarmScheduler.schedule(this);
        if (!on) AlarmScheduler.cancelSnooze(this);
        paintAlarm();
        if (!on) {
            Toast.makeText(this, "Wecker aus. Der Bär schläft.", Toast.LENGTH_SHORT).show();
            return;
        }
        long in = AlarmScheduler.next(prefs) - System.currentTimeMillis();
        Toast.makeText(this, String.format(Locale.ROOT, "Der Bär kommt in %d h %02d min.",
            in / 3_600_000, in / 60_000 % 60), Toast.LENGTH_LONG).show();
        // Ringing over the lock screen needs notifications and, on Android 14+, full-screen access.
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        } else {
            checkFullScreenAccess();
        }
    }

    private void checkFullScreenAccess() {
        if (Build.VERSION.SDK_INT < 34) return;
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm == null || nm.canUseFullScreenIntent()) return;
        new AlertDialog.Builder(this)
            .setTitle("Bär über dem Sperrbildschirm")
            .setMessage("Damit der Wecker bei gesperrtem Handy im Vollbild klingelt, erlaube \"Vollbild-Benachrichtigungen\" für ARC-DNA.")
            .setPositiveButton("Einstellungen", (d, w) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    Toast.makeText(this, "Einstellungen → Apps → ARC-DNA → Vollbild-Benachrichtigungen", Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Später", null)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFY) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) checkFullScreenAccess();
            else Toast.makeText(this, "Ohne Benachrichtigungen klingelt der Bär nur, wenn ARC-DNA offen ist.", Toast.LENGTH_LONG).show();
            return;
        }
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
        // Any touch pauses head and eye scrolling for 4 s, so the finger wins.
        boolean resting = android.os.SystemClock.uptimeMillis() - lastTouch < EYE_PAUSE_MS;
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
        if (!prefs.getBoolean("autoWallpaper", true)) return;
        // Never paint the static image over a screen that runs ARC-DNA Live (home or lock).
        int flags = 0;
        if (!liveWallpaperActive()) flags |= WallpaperManager.FLAG_SYSTEM;
        if (!liveOnLock()) flags |= WallpaperManager.FLAG_LOCK;
        if (flags == 0) return;
        final int which = flags;
        boolean night = isNight();
        // The suffix is the wallpaper version: bumping it makes an update replace the old image.
        String mode = (night ? "dark" : "light") + "-v2";
        if (mode.equals(prefs.getString("wallpaperMode", ""))) return;
        int res = night ? R.drawable.wallpaper_dark : R.drawable.wallpaper_light;
        Context app = getApplicationContext();
        new Thread(() -> {
            try (java.io.InputStream in = app.getResources().openRawResource(res)) {
                WallpaperManager.getInstance(app).setStream(in, null, true, which);
                DiagLog.log("static wallpaper " + mode + " written, flags=" + which);
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

    private String wallpaperStatusSafe() {
        try {
            return wallpaperStatus();
        } catch (RuntimeException e) {
            return "wallpaper status n/a";
        }
    }

    /** Technical state + the log, as plain text to share (e.g. into the Claude chat). Nothing personal. */
    private void shareDiagnostics() {
        StringBuilder b = new StringBuilder("ARC-DNA Home Diagnose\n");
        try {
            b.append("Version: ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append('\n');
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        b.append("Gerät: ").append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append(wallpaperStatusSafe()).append('\n');
        b.append("Mikrofon/Visualizer: ").append(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED).append('\n');
        b.append("Kamera: ").append(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED).append('\n');
        b.append("Nutzungszugriff: ").append(hasUsageAccess()).append('\n');
        if (Build.VERSION.SDK_INT >= 33) {
            b.append("Benachrichtigungen: ").append(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED).append('\n');
        }
        if (Build.VERSION.SDK_INT >= 34) {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            b.append("Vollbild-Benachrichtigungen: ").append(nm != null && nm.canUseFullScreenIntent()).append('\n');
        }
        b.append("Helligkeit schreiben: ").append(Settings.System.canWrite(this)).append('\n');
        b.append("Wecker: ").append(prefs.getBoolean("alarmOn", false) ? "an" : "aus").append('\n');
        b.append("Kopf-Scroll: ").append(headMode()).append(", Update-Token gesetzt: ").append(!updater.token().isEmpty()).append('\n');
        b.append("Nachtmodus: ").append(isNight()).append(", Bildschirm ").append(getResources().getConfiguration().screenWidthDp)
            .append("×").append(getResources().getConfiguration().screenHeightDp).append(" dp\n\n--- Log ---\n");
        b.append(DiagLog.read());
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "ARC-DNA Diagnose")
            .putExtra(Intent.EXTRA_TEXT, b.toString());
        open(Intent.createChooser(send, "Diagnose teilen"));
    }

    /** Lock screen runs ARC-DNA Live (own lock engine, or home live shared with the lock screen). */
    private boolean liveOnLock() {
        WallpaperManager wm = WallpaperManager.getInstance(this);
        if (Build.VERSION.SDK_INT >= 34) {
            android.app.WallpaperInfo info = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
            if (info != null) return getPackageName().equals(info.getPackageName());
        }
        // No separate lock wallpaper (id < 0) = the lock screen shows the home one.
        return liveWallpaperActive() && wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0;
    }

    /** What Android actually shows on home and lock screen (the lock screen part needs Android 14+). */
    private String wallpaperStatus() {
        WallpaperManager wm = WallpaperManager.getInstance(this);
        String home = liveWallpaperActive() ? "ARC-DNA Live" : wm.getWallpaperInfo() != null ? "andere Live" : "statisch";
        String lock = "?";
        if (Build.VERSION.SDK_INT >= 34) {
            android.app.WallpaperInfo info = wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK);
            lock = liveOnLock() ? "ARC-DNA Live" : info != null ? "andere Live" : "statisch ✗";
        }
        return "Start: " + home + " · Sperre: " + lock;
    }

    /**
     * Introduces the dynamic lock screen: what it shows, what is set up already (✓ / ✗), and a
     * button for the next missing step. Opens once by itself while the lock screen is still static.
     */
    private void showLockIntro() {
        prefs.edit().putBoolean("lockIntroShown", true).apply();
        boolean live = liveOnLock() || liveWallpaperActive();
        boolean lockLive = liveOnLock();
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        String msg = "Wenn das Handy gesperrt ist, lebt der Hintergrund:\n\n"
            + "🌈  Regenbogen-Ring mit Glitzer um den Fingerabdruck-Sensor – pulsiert sanft\n"
            + "📷  Drehender Regenbogen-Ring um die Frontkamera\n"
            + "🔊  Rave-Kreise bei jedem Bass-Beat deiner Musik\n"
            + "🌗  Hell/Dunkel folgt dem System\n\n"
            + (live ? "✓" : "✗") + "  ARC-DNA Live gewählt\n"
            + (lockLive ? "✓" : "✗") + "  Auch für den Sperrbildschirm\n"
            + (mic ? "✓" : "✗") + "  Mikrofon für den Beat (nichts wird aufgenommen)\n\n"
            + (lockLive && mic ? "Alles bereit – sperr das Handy und spiel Musik."
            : "Tippe auf den Knopf. Bei der Frage wo: \"Startbildschirm und Sperrbildschirm\".");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Dynamischer Sperrbildschirm")
            .setMessage(msg)
            .setNegativeButton("Später", null);
        if (!lockLive) {
            b.setPositiveButton("Einrichten", (d, w) -> {
                Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, ArcWallpaper.class));
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    open(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
                }
                if (!mic) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            });
        } else if (!mic) {
            b.setPositiveButton("Mikrofon erlauben", (d, w) ->
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO));
        }
        b.show();
    }

    /** Long-press the clock: ARC-DNA Live, static light/dark, or leave the wallpaper alone. */
    private void showWallpaperChoice() {
        String[] items = {
            "Dynamischer Sperrbildschirm – Einführung",
            "ARC-DNA Live – Regenbogen, Glitzer & Rave (Sperrbildschirm)",
            "Statisch – hell/dunkel automatisch",
            "Aus – eigenes Wallpaper behalten",
            "Hell/Dunkel wieder dem System folgen",
        };
        new AlertDialog.Builder(this)
            .setTitle("Wallpaper & Farben\n" + wallpaperStatus())
            .setItems(items, (d, pick) -> {
                if (pick == 0) {
                    showLockIntro();
                    return;
                }
                int which = pick - 1;
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
                } else if (which == 2) {
                    prefs.edit().putBoolean("autoWallpaper", false).apply();
                    Toast.makeText(this, "Wallpaper bleibt, wie es ist", Toast.LENGTH_SHORT).show();
                } else {
                    setAppNight(UiModeManager.MODE_NIGHT_AUTO);
                    Toast.makeText(this, "Hell/Dunkel folgt wieder dem System", Toast.LENGTH_SHORT).show();
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

    /* ================= F1 mode ================= */

    private void setF1(boolean on) {
        boolean changed = on != f1;
        f1 = on;
        prefs.edit().putBoolean("f1", on).apply();
        f1Box.setVisibility(View.GONE);
        f1Panel.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && changed) f1Panel.begin();
        if (on && changed) {
            hop();
            say("Box, box!", 1200);
            voice.say("Wow-wow!");
        }
        startF1Tick();
    }

    /** The timing board takes a bit under half the screen height. */
    private int f1Height() {
        return (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
    }

    /** Rotation no longer recreates the screen (circle gesture, game); only sizes follow. */
    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        f1Panel.getLayoutParams().height = f1Height();
        f1Panel.requestLayout();
        // Re-flow the grids for the new width (4 columns in portrait, up to 8 in landscape).
        appTilesBox.removeAllViews();
        appTilesBox.addView(appTiles());
        renderLibrary();
        if (!lastRecent.isEmpty()) {
            renderRecents(recentHome, lastRecent, cols());
            renderRecents(recentLib, lastRecent, 2 * cols());
        }
        rootFrame.requestApplyInsets();
    }

    /** Tiles per row: about one per 100 dp of width, at least 4, at most 8. */
    private int cols() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        return Math.max(4, Math.min(8, widthDp / 100));
    }

    /* ================= Brightness lever ================= */

    /** Changing system brightness needs "Modify system settings"; ask once, then it just works. */
    private boolean canWriteSettings() {
        if (Settings.System.canWrite(this)) return true;
        Toast.makeText(this, "ARC-DNA erlauben, Systemeinstellungen zu ändern – dann nochmal schieben", Toast.LENGTH_LONG).show();
        open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
        return false;
    }

    private void setBrightness(int level) {
        if (!canWriteSettings()) {
            syncLever();
            return;
        }
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, BRIGHTNESS[level]);
    }

    private void setAutoBrightness() {
        if (!canWriteSettings()) {
            syncLever();
            return;
        }
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        say("Auto-Helligkeit", 1200);
    }

    /** Puts the knob on the level nearest to the current brightness, or "A" when automatic. */
    private void syncLever() {
        try {
            int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                lever.setAuto(true);
                return;
            }
            int b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            int best = 0;
            for (int i = 1; i < 3; i++) {
                if (Math.abs(BRIGHTNESS[i] - b) < Math.abs(BRIGHTNESS[best] - b)) best = i;
            }
            lever.setLevel(best, false);
        } catch (Settings.SettingNotFoundException e) {
            lever.setLevel(1, false);
        }
    }

    /* ================= Fuchsjagd ================= */

    /** Tap on empty space: the screen turns to landscape and the fox chases the rabbit. */
    private void startGame() {
        if (game != null) return;
        hideKeyboard();
        game = new FoxRunView(this, new int[]{ink, ink2, ink3, rust, card, line, isNight() ? 0xFF121211 : 0xFFFAFAF8},
            voice, prefs, this::exitGame);
        rootFrame.addView(game, new FrameLayout.LayoutParams(-1, -1));
        game.setAlpha(0f);
        game.animate().alpha(1f).setDuration(300).start();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        voice.say("Yip-yip!");
        game.resume();
        Toast.makeText(this, "Tippen = springen · 3× tippen oder 2× Home = zurück", Toast.LENGTH_SHORT).show();
    }

    private void exitGame() {
        if (game == null) return;
        FoxRunView g = game;
        game = null;
        g.pause();
        g.animate().alpha(0f).setDuration(200).withEndAction(() -> rootFrame.removeView(g)).start();
        setRequestedOrientation(ORIENTATIONS[prefs.getInt("orient", 0)]);
    }

    private void startF1Tick() {
        // The small seconds readout is replaced by the F1 timing board; nothing to tick here.
        f1Running = false;
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
            // A tall block (the open Mediathek) folds row by row, not as one piece.
            if (isLabel && block instanceof LinearLayout && block.getVisibility() == View.VISIBLE
                && block.getHeight() > scroll.getHeight() / 2) {
                frost(child, 0);
                frost(block, 0);
                LinearLayout rows = (LinearLayout) block;
                for (int k = 0; k < rows.getChildCount(); k++) {
                    View r = rows.getChildAt(k);
                    int bottom = block.getTop() + r.getBottom();
                    frost(r, r.getHeight() == 0 ? 0
                        : Math.max(0f, Math.min(1f, (bottom - limit) / (float) r.getHeight())));
                }
                i++;
                continue;
            }
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

    /** One full twist per volume step; quick presses stack up into a longer spin. */
    private void spinFox() {
        if (fox == null || game != null) return;
        float from = fox.getRotation();
        boolean spinning = foxSpin != null && foxSpin.isRunning();
        spinTarget = (spinning ? spinTarget : (float) Math.ceil(from / 360f) * 360) + 360;
        if (foxSpin != null) foxSpin.cancel();
        if (foxSad != null) foxSad.cancel();
        fox.setPivotX(fox.getWidth() / 2f);
        fox.setPivotY(fox.getHeight() / 2f);
        fox.setScaleY(1f);
        fox.setTranslationY(0);
        foxSpin = android.animation.ObjectAnimator.ofFloat(fox, View.ROTATION, from, spinTarget);
        foxSpin.setDuration(520);
        foxSpin.setInterpolator(new android.view.animation.OvershootInterpolator(1.4f));
        foxSpin.start();
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTwirl > 450) {       // fast presses: one twirl sound, not a queue of them
            lastTwirl = now;
            voice.twirl();
        }
    }

    /** Volume down: the fox droops, head hanging and sinking low, then slowly picks itself up. */
    private void sadFox() {
        if (fox == null || game != null) return;
        if (foxSpin != null) foxSpin.cancel();
        if (foxSad != null) foxSad.cancel();
        fox.setRotation(fox.getRotation() % 360);
        fox.setPivotX(fox.getWidth() * 0.75f);       // the head end
        fox.setPivotY(fox.getHeight());
        android.animation.ObjectAnimator droop = android.animation.ObjectAnimator.ofPropertyValuesHolder(fox,
            android.animation.PropertyValuesHolder.ofFloat(View.ROTATION, fox.getRotation(), 9),
            android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, fox.getTranslationY(), dp(8)),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, fox.getScaleY(), 0.86f));
        droop.setDuration(650);
        droop.setInterpolator(new android.view.animation.DecelerateInterpolator());
        android.animation.ObjectAnimator sigh = android.animation.ObjectAnimator.ofFloat(fox, View.SCALE_Y, 0.86f, 0.9f, 0.86f);
        sigh.setDuration(700);
        android.animation.ObjectAnimator recover = android.animation.ObjectAnimator.ofPropertyValuesHolder(fox,
            android.animation.PropertyValuesHolder.ofFloat(View.ROTATION, 9, 0),
            android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dp(8), 0),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.86f, 1f));
        recover.setDuration(900);
        recover.setStartDelay(400);
        recover.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        foxSad = new android.animation.AnimatorSet();
        foxSad.playSequentially(droop, sigh, recover);
        foxSad.start();
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTwirl > 900) {
            lastTwirl = now;
            voice.whimper();
        }
    }

    /** At full volume the system sends no change, so the key itself still gets a spin. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null && keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
            && am.getStreamVolume(AudioManager.STREAM_MUSIC) >= am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) spinFox();
        if (am != null && keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            && am.getStreamVolume(AudioManager.STREAM_MUSIC) <= am.getStreamMinVolume(AudioManager.STREAM_MUSIC)) sadFox();
        return super.onKeyDown(keyCode, event);
    }

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
        homeInFront = true;
        volumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return;
                int now = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", 0);
                int before = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", now);
                if (now > before) spinFox();
                else if (now < before) sadFox();
            }
        };
        IntentFilter vf = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(volumeReceiver, vf, Context.RECEIVER_EXPORTED);
        else registerReceiver(volumeReceiver, vf);
        handler.removeCallbacks(ticker);
        ticker.run();
        syncLever();
        f1 = prefs.getBoolean("f1", false);
        f1Box.setVisibility(View.GONE);
        f1Panel.setVisibility(f1 ? View.VISIBLE : View.GONE);
        if (f1) f1Panel.resume();
        if (game != null) game.resume();
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
        homeInFront = false;
        if (volumeReceiver != null) {
            unregisterReceiver(volumeReceiver);
            volumeReceiver = null;
        }
        prefs.edit().putInt("homeScrollY", scroll.getScrollY()).apply();
        handler.removeCallbacks(ticker);
        f1Running = false;
        f1Panel.pause();
        if (game != null) game.pause();
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

    /** Home pressed while already home: close the Mediathek, back to the top. Coming back from an app keeps the position. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.ACTION_STATUS.equals(intent.getAction())) {
            updater.onStatus(intent);
            return;
        }
        if (game != null) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastHomePress < 1500) exitGame();
            else Toast.makeText(this, "Nochmal Home = Fuchsjagd beenden", Toast.LENGTH_SHORT).show();
            lastHomePress = now;
            return;
        }
        boolean wasInFront = homeInFront;
        closeLibrary();
        hideKeyboard();
        if (wasInFront) scroll.smoothScrollTo(0, 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (game != null) {
            Toast.makeText(this, "3× tippen oder 2× Home = zurück", Toast.LENGTH_SHORT).show();
            return;
        }
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
        /** Samples still to be written: the calm heartbeat blip. */
        private final java.util.ArrayDeque<Float> queued = new java.util.ArrayDeque<>();
        private static final float[] HEARTBEAT = {0.05f, -0.12f, 0.55f, -0.42f, 0.12f, 0f, 0f, 0.08f, 0.16f, 0.08f, 0f};

        /** While home is calm, the line keeps a slow heartbeat so it feels alive. */
        private final Runnable heartbeat = new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) return;
                if (homeInFront && isShown() && amp < 0.02f) {
                    for (float v : HEARTBEAT) queued.add(v * 0.35f);
                    if (!animating) {
                        animating = true;
                        postOnAnimation(step);
                    }
                }
                postDelayed(this, 4200);
            }
        };

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            removeCallbacks(heartbeat);
            postDelayed(heartbeat, 1500);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(heartbeat);
        }

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
                Float beat = queued.poll();
                trace[trace.length - 1] = (beat != null ? beat : 0f) + amp * (random.nextFloat() * 2 - 1);
                amp *= 0.965f;
                invalidate();
                boolean flat = amp < 0.003f && queued.isEmpty();
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
            // The pen: a small rust dot writing at the right end, glowing while it moves.
            float penY = trace.length > 0 ? mid + trace[trace.length - 1] * half : mid;
            float glow = Math.min(1f, Math.abs(penY - mid) / (half * 0.4f) + (hot ? 1 : 0));
            textPaint.setColor((rust & 0x00FFFFFF) | ((int) (40 + 60 * glow) << 24));
            canvas.drawCircle(w - dp(4), penY, dp(4.5f), textPaint);
            textPaint.setColor(rust);
            canvas.drawCircle(w - dp(4), penY, dp(2), textPaint);

            // Label on the right, clear of the fox: magnitude plus a word for the mood.
            float m = amp * 6;
            String mood = m < 0.1f ? "RUHIG ♥" : m < 1.5f ? "LEICHT" : m < 3.5f ? "SPÜRBAR" : "BEBEN!";
            String label = String.format(Locale.ROOT, "SEISMO · M %.1f · %s", m, mood);
            textPaint.setColor(hot ? rust : ink3);
            canvas.drawText(label, w - dp(16) - textPaint.measureText(label), dp(11), textPaint);
        }
    }
}
