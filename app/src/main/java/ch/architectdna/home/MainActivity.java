package ch.architectdna.home;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
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

    private CrackView cracks;
    private static final int REQ_CAMERA = 7;
    private static final int REQ_AUDIO = 8;
    private static final int REQ_NOTIFY = 9;
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
    private SpicyBackdrop backdrop;
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
    private FoxView fox;
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
    /** The stroke started on the brightness lever (its own drag, not the corner swipe). */
    private boolean strokeOnLever;
    /** System brightness (0..255) for LO / MID / HI. */
    // Raw 0–255 values; Android's slider is log-like, so these sit at ≈ 20 % / 58 % / 100 % on it.
    private static final int[] BRIGHTNESS = {3, 30, 255};
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
    private LinearLayout recentHome, recentLib, recentRail;
    private boolean edgeFired, edgeFiredL;
    private ThumbFanView thumbFan;
    private AmbientView ambient;
    private boolean hidden, libraryOpen;

    private final List<AppEntry> apps = new ArrayList<>();
    private BroadcastReceiver packageReceiver;
    private BroadcastReceiver kbdBattReceiver;
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
    private boolean strokeMulti, strokeOnSlider, circling, strokeOnCard, cardFlipped, strokeOnCarousel;
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
    private View calibOverlay, calibDot, calibCursor;
    private TextView calibText, calibLive;
    private LinearLayout calibResult;
    private HeadDebugView calibDebug;
    private float calibRef = Float.NaN;
    private boolean headDbgOn;
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
        setupNfc();
        DiagLog.init(this);
        DiagLog.log("launcher start, " + wallpaperStatusSafe());
        pickColours();
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            // A see-through navigation bar: the home screen runs underneath it.
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        setContentView(buildUi());
        torch = new DarkTorch(this, (on, byUs) -> {
            if (!byUs) torchOurs = false;          // switched by hand (quick settings): leave it alone
            paintTorchChip();
        });
        AlarmScheduler.schedule(this);
        if (!prefs.getBoolean("lockIntroShown", false) && !liveOnLock()) handler.postDelayed(this::showLockIntro, 1200);
        // Keep the scroll position across a recreate (dark/light swipe, process restart).
        int savedY = prefs.getInt("homeScrollY", 0);
        if (savedY > 0) scroll.post(() -> scroll.scrollTo(0, savedY));
        updater = new Updater(this, prefs, this::onUpdateState);
        applyOrientation();
        prefs.edit().remove("hidden").apply();   // the old wallpaper-only mode became the Fuchsjagd game
        applyWallpaperIfNeeded();
        loadApps();
        registerPackageReceiver();
        registerKbdBatt();
        watchNetwork();
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The picked look (Themes.ALL): "Spicy" is the default, "Klassisch" the original ink/rust. */
    private Themes.Theme theme() {
        return Themes.current(prefs);
    }

    /** Clean home: content on a solid themed surface, sleek like the ARC apps. Default on. */
    private boolean cleanHome() {
        return prefs.getBoolean("cleanHome", true);
    }

    /** Mock home: strip the page to clock · date · greeting + the thumb-fans, like the ARC mockup. */
    private boolean mockHome() {
        return prefs.getBoolean("mockHome", true);
    }

    /** Strip the home to clock · date · greeting + the thumb-fans (mockup look), or restore it. */
    private void applyMockHome() {
        if (content == null || flexGap == null) return;
        boolean on = mockHome();
        int gi = content.indexOfChild(flexGap);
        for (int i = gi + 1; i < content.getChildCount(); i++) content.getChildAt(i).setVisibility(on ? View.GONE : View.VISIBLE);
        if (peek != null) peek.setVisibility(View.GONE);   // peek dial retired — it collided with the header
        if (lever != null) lever.setVisibility(on ? View.GONE : View.VISIBLE);
        if (thumbFan != null) thumbFan.setMinimal(on);
        // opaque background so the wallpaper's cube/grid stops bleeding through — clean like the mockup
        if (scroll != null) scroll.setBackgroundColor(on ? (paper() | 0xFF000000) : ((paper() & 0x00FFFFFF) | 0xF4000000));
        refreshRecentRail();   // the bottom dock stays in both modes
        if (on) {
            for (View v : new View[]{fox, bubble, seismo, proDock}) if (v != null) v.setVisibility(View.GONE);
        } else {
            floaters(true);
            applyPro();
        }
    }

    /** Glow themes get the drawn backdrop, the flip-in scroll and the bolder outlines. */
    private boolean spicy() {
        return theme().glow;
    }

    private int[] pal() {
        return theme().pal(isNight());
    }

    /** The page colour behind everything. */
    private int paper() {
        return pal()[Themes.BG] | 0xFF000000;
    }

    /** Pick a look: the home rebuilds, the live wallpaper follows on its next frame. */
    private void showThemePicker() {
        Themes.Theme now = theme();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Themes.Theme t : Themes.ALL) {
            // A row with a live colour preview: background, text, accent and (glow themes) the two glows.
            LinearLayout rowv = new LinearLayout(this);
            rowv.setGravity(Gravity.CENTER_VERTICAL);
            rowv.setPadding(dp(12), dp(10), dp(14), dp(10));
            rowv.setBackground(box(t == now ? ((rust & 0x00FFFFFF) | 0x22000000) : paper(), t == now ? rust : line, 16));
            int[] dp2 = t.day;
            FrameLayout sw = new FrameLayout(this);
            View chip = new View(this);
            chip.setBackground(box(dp2[Themes.BG] | 0xFF000000, dp2[Themes.LINE], 10));
            sw.addView(chip, new FrameLayout.LayoutParams(dp(46), dp(46)));
            View dot = new View(this);          // the accent, a filled circle
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(dp2[Themes.ACCENT]);
            dot.setBackground(g);
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(18), dp(18));
            dl.leftMargin = dp(7);
            dl.topMargin = dp(7);
            sw.addView(dot, dl);
            if (t.glow) {                        // two glow dabs, bottom-right
                for (int gi = 0; gi < 2; gi++) {
                    View gv = new View(this);
                    android.graphics.drawable.GradientDrawable gg = new android.graphics.drawable.GradientDrawable();
                    gg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    gg.setColor(dp2[gi == 0 ? Themes.GLOW_A : Themes.GLOW_B]);
                    gv.setBackground(gg);
                    FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(13), dp(13));
                    gl.leftMargin = dp(10 + gi * 11);
                    gl.topMargin = dp(27);
                    sw.addView(gv, gl);
                }
            }
            rowv.addView(sw, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(15, ink, true);
            name.setText((t == now ? "● " : "○ ") + t.name);
            txt.addView(name);
            TextView desc = monoText(10, ink3);
            desc.setText(t.desc.toUpperCase(Locale.GERMAN));
            desc.setLetterSpacing(0.04f);
            txt.addView(desc, margins(-1, -2, 0, dp(2), 0, 0));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = dp(14);
            rowv.addView(txt, tl);
            rowv.setOnClickListener(v -> {
                prefs.edit().putString("theme", t.id).apply();
                closeArcSheet();
                recreate();
            });
            list.addView(rowv, margins(-1, -2, 0, dp(8), 0, 0));
        }
        arcSheet("Theme", "Home + Sperrbildschirm · mehr kommen dazu", null, list, new String[]{"Schliessen"}, k -> { });
    }

    private void pickColours() {
        boolean night = isNight();
        // The ARC-DNA Live wallpaper follows exactly this (it lagged behind the home before).
        prefs.edit().putBoolean("uiNight", night).putLong("uiNightAt", System.currentTimeMillis()).apply();
        if (!"classic".equals(theme().id)) {
            int[] pl = theme().pal(night);
            ink = pl[Themes.INK];
            ink2 = pl[Themes.INK2];
            ink3 = pl[Themes.INK3];
            rust = pl[Themes.ACCENT];
            card = pl[Themes.CARD];
            panel = pl[Themes.PANEL];
            line = pl[Themes.LINE];
            return;
        }
        ink = night ? 0xFFECEBE6 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA9A8A2 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFC9402F : 0xFFB5271E;
        // Nearly solid: pixel art and the grid behind must not shine through text (contrast).
        card = night ? 0xD9121211 : 0xD9FAFAF8;      // a little see-through again, still readable
        panel = night ? 0xF2121211 : 0xF2FAFAF8;
        line = night ? 0xFF2C2C29 : 0xFFE0E0D8;
    }

    /* ================= Main screen ================= */

    /** Schlicht: a stock-simple home – clock, app search, favourites, nothing moving. Magic one tap away. */
    private boolean minimal() {
        return prefs.getBoolean("minimal", false);
    }

    /** The minimal home: a clean surface with the clock, a search bar, favourite apps and two buttons. */
    private View buildMinimalUi() {
        FrameLayout root = new FrameLayout(this);
        rootFrame = root;
        root.setBackgroundColor(paper());
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        scroll = sv;
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        content = col;
        col.setPadding(dp(22), dp(28), dp(22), dp(40));
        col.setOnApplyWindowInsetsListener((v, insets) -> {
            int top0, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top0 = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top0 = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(dp(22) + left, dp(24) + top0, dp(22) + right, dp(40) + bottom);
            return insets;
        });
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label("Architect · DNA"), new LinearLayout.LayoutParams(0, -2, 1f));
        net = monoText(11, ink3);
        top.addView(net);
        TextView full = monoText(12, rust);
        full.setText("✨ ARC  ");
        full.setContentDescription("Volle ARC-DNA einschalten");
        full.setOnClickListener(v -> {
            prefs.edit().putBoolean("minimal", false).apply();
            recreate();
        });
        top.addView(full);
        top.addView(gearButton());
        col.addView(top);

        clock = new TextView(this);
        clock.setTextColor(ink);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92);
        clock.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        clock.setLetterSpacing(-0.06f);
        clock.setIncludeFontPadding(false);
        clock.setPadding(0, dp(24), 0, 0);
        col.addView(clock);
        date = text(15, ink2, false);
        col.addView(date, margins(-1, -2, 0, dp(10), 0, 0));
        greet = text(18, ink, true);
        col.addView(greet, margins(-1, -2, 0, dp(2), 0, 0));

        col.addView(searchRow(), margins(-1, -2, 0, dp(24), 0, dp(6)));
        col.addView(appTiles(), margins(-1, -2, 0, dp(14), 0, 0));

        TextView all = text(15, ink, true);
        all.setText("▦   Alle Apps");
        all.setGravity(Gravity.CENTER);
        all.setPadding(dp(16), dp(15), dp(16), dp(15));
        all.setBackground(box(card, line, 16));
        all.setOnClickListener(v -> openLibrary());
        col.addView(all, margins(-1, -2, 0, dp(12), 0, 0));

        watchNetwork();
        updateClock();
        return root;
    }

    private FrameLayout minimalOverlay;
    private TextView minimalClock, minimalDate, minimalGreet;

    /** Schlicht: a clean stock-simple screen laid over the (fully built, intact) home. */
    private void addMinimalOverlay(FrameLayout root) {
        FrameLayout ov = new FrameLayout(this);
        minimalOverlay = ov;
        ov.setBackgroundColor(paper());
        ov.setClickable(true);
        ov.setElevation(dp(24));
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        ov.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(22), dp(28), dp(22), dp(40));
        c.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(dp(22) + i.left, dp(24) + i.top, dp(22) + i.right, dp(40) + i.bottom);
            }
            return insets;
        });
        sv.addView(c, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label("Architect · DNA"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sos = monoText(12, rust);
        sos.setText("🆘 ");
        sos.setOnClickListener(v -> openCrisis());
        top.addView(sos);
        TextView full = monoText(12, rust);
        full.setText("✨ ARC  ");
        full.setContentDescription("Volle ARC-DNA");
        full.setOnClickListener(v -> {
            prefs.edit().putBoolean("minimal", false).apply();
            recreate();
        });
        top.addView(full);
        top.addView(gearButton());
        c.addView(top);

        minimalClock = new TextView(this);
        minimalClock.setTextColor(ink);
        minimalClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92);
        minimalClock.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        minimalClock.setLetterSpacing(-0.06f);
        minimalClock.setIncludeFontPadding(false);
        minimalClock.setPadding(0, dp(24), 0, 0);
        c.addView(minimalClock);
        minimalDate = text(15, ink2, false);
        c.addView(minimalDate, margins(-1, -2, 0, dp(10), 0, 0));
        minimalGreet = text(18, ink, true);
        c.addView(minimalGreet, margins(-1, -2, 0, dp(2), 0, 0));

        c.addView(searchRow(), margins(-1, -2, 0, dp(24), 0, dp(6)));
        c.addView(appTiles(), margins(-1, -2, 0, dp(14), 0, 0));

        TextView all = text(15, ink, true);
        all.setText("▦   Alle Apps");
        all.setGravity(Gravity.CENTER);
        all.setPadding(dp(16), dp(15), dp(16), dp(15));
        all.setBackground(box(card, line, 16));
        all.setOnClickListener(v -> openLibrary());
        c.addView(all, margins(-1, -2, 0, dp(12), 0, 0));

        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        updateClock();
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootFrame = root;
        // Light mode while the phone's own theme is dark: Android dims the wallpaper then (grey
        // page). So in light mode the launcher paints the light ARC wallpaper itself, undimmed.
        if (spicy()) {
            backdrop = new SpicyBackdrop(this, isNight(), pal(), pro());
            root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        } else if (!isNight() && prefs.getBoolean("autoWallpaper", true)) {
            ImageView paper = new ImageView(this);
            paper.setImageResource(R.drawable.wallpaper_light);
            paper.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(paper, new FrameLayout.LayoutParams(-1, -1));
        }

        // Pixel art (Pixel-Art app): ARC-DNA Live paints it itself; over a still wallpaper the home does.
        pixelLayer = new PixelLayer();
        root.addView(pixelLayer, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        // Clean home: the content sits on a near-solid themed surface (like the ARC Player sheet),
        // so the wallpaper, cube and pixel art stop bleeding through the text. Just a whisper shows.
        if (cleanHome()) scroll.setBackgroundColor((paper() & 0x00FFFFFF) | 0xF4000000);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Empty space: tap = only wallpaper, hold = Mediathek.
        content.setOnLongClickListener(v -> {
            openLibrary();
            return true;
        });
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        peek = new PeekDial();
        root.addView(peek, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (backdrop != null) backdrop.scrolled(y, y - oy);
            updateFolds();
            peek.follow(y);
        });
        // Sections change size (cover loads, a fold opens): judge the blur again after every layout.
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            scroll.post(this::updateFolds);
            scroll.post(this::fitClock);
            scroll.post(() -> peek.findClocks(content));
        });
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::updateFolds);

        // Label row: ARCHITECT · DNA ……… ● ONLINE
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(label("Architect · DNA"), new LinearLayout.LayoutParams(0, -2, 1f));
        headLabel = monoText(11, rust);
        headLabel.setText("◉ KOPF  ");
        headLabel.setVisibility(View.GONE);
        // Long hold on the camera ◉: calibrate the nod by hand (live camera, guided dots, confirm / redo).
        headLabel.setOnLongClickListener(v -> {
            startGazeCalibration();
            return true;
        });
        top.addView(headLabel);
        torchChip = monoText(11, rust);
        torchChip.setText("🔦 AUS  ");
        torchChip.setContentDescription("Taschenlampe ausschalten");
        torchChip.setVisibility(View.GONE);
        torchChip.setOnClickListener(v -> {
            torchDeclined = true;       // not again until you come back home
            if (torch != null) torch.set(false);
        });
        top.addView(torchChip);
        // Under 15 %: what's paused, and that it comes back by itself.
        lowChip = monoText(11, rust);
        lowChip.setText("🔋 SPART  ");
        lowChip.setVisibility(View.GONE);
        lowChip.setOnClickListener(v -> arcSheet("Akku-Schutz", "Unter " + LowPower.LIMIT + " % ohne Ladekabel",
            "Pausiert: Kopf-Scroll und Gesichts-Wache (Kamera), Diebstahl-Wache (Kamera, Mikro, GPS), "
                + "Musik-Analyse im Hintergrund und das Glühen. Alles kommt von selbst zurück, sobald du lädst.",
            null, new String[]{"Okay"}, k -> { }));
        top.addView(lowChip);
        net = monoText(11, ink3);
        top.addView(net);
        TextView calmBtn = monoText(16, rust);
        calmBtn.setText("🆘 ");
        calmBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
        calmBtn.setContentDescription("Ruheinsel – wenn alles zu viel ist");
        calmBtn.setOnClickListener(v -> openCrisis());
        top.addView(calmBtn);
        top.addView(gearButton());
        top.setOnLongClickListener(v -> {       // long-press the header toggles the mockup-clean home
            prefs.edit().putBoolean("mockHome", !mockHome()).apply();
            applyMockHome();
            pop(mockHome() ? "Mock-Home · lang drücken = voller Home" : "Voller Home · lang drücken = Mock");
            return true;
        });
        content.addView(top);

        updateHome = text(14, ink, true);
        updateHome.setPadding(dp(14), dp(12), dp(14), dp(12));
        updateHome.setBackground(box(card, rust, 16));
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
                // Below LO: the Geister-Level. Any other position leaves it.
                if (level == 0) {
                    if (ghosts == null) enterGhosts();
                    else ghostBrightness();
                } else {
                    if (ghosts != null) exitGhosts();
                    setBrightness(level - 1);
                }
            }

            @Override
            public void onAuto() {
                if (ghosts != null) exitGhosts();
                setAutoBrightness();
            }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(64), dp(150));
        llp.bottomMargin = dp(10);
        clockRow.addView(lever, llp);
        content.addView(clockRow);

        f1Panel = new F1Panel(this, new int[]{ink, ink2, ink3, rust, card, line}, voice);
        // The buddy's eye chases the car; a pass under 0.65 s is too fast and leaves it dizzy.
        f1Panel.setWatcher(new F1Panel.Watcher() {
            @Override
            public void onCar(float x, float y, long passMs) {
                if (fox != null && fox.isShown() && buddy() != 3) fox.watch(x, y);
            }

            @Override
            public void onCarGone(long passMs) {
                if (fox == null || !fox.isShown()) return;
                // The racer has no eyes to spin: it revs on its rope, and some lap it breaks free.
                if (buddy() == 3) {
                    if (!raceFree && !burning && (passMs < 650 || random.nextInt(2) == 0)) burnout();
                    return;
                }
                if (passMs >= 650) return;
                fox.dizzy(1500);
                if (random.nextInt(3) == 0) {
                    String[] l = {"Wo ist er hin?!", "Zu schnell …", "Mir ist schwindlig."};
                    say(l[random.nextInt(l.length)], 1300);
                }
            }
        });
        f1Panel.setVisibility(View.GONE);
        f1Panel.setOnClickListener(v -> setF1(false));
        content.addView(f1Panel, margins(-1, f1Height(), 0, dp(14), 0, 0));

        // For a week after a clock change: the old time next to the new one.
        oldTime = monoText(12, rust);
        oldTime.setLetterSpacing(0.12f);
        oldTime.setVisibility(View.GONE);
        content.addView(oldTime, margins(-1, -2, 0, dp(4), 0, 0));

        date = text(17, ink2, false);
        date.setPadding(0, dp(10), 0, 0);
        content.addView(date);
        greet = text(17, ink, true);
        content.addView(greet);
        // Flexible gap: with sections folded the page fills the screen exactly (clock on top, section
        // titles down by the thumb, no scrolling); opened sections eat the gap before the page scrolls.
        flexGap = new View(this);
        content.addView(flexGap, new LinearLayout.LayoutParams(-1, 0, 1f));
        alarmRow = monoText(10, ink3);
        alarmRow.setLetterSpacing(0.14f);
        alarmCard = alarmCard();
        paintAlarm();

        // Priorities: search, apps and music always open; everything else folds away.
        content.addView(modeBar(), margins(-1, -2, 0, dp(16), 0, 0));
        widgetStrip = new LinearLayout(this);
        widgetStrip.setOrientation(LinearLayout.VERTICAL);
        content.addView(widgetStrip, margins(-1, -2, 0, dp(4), 0, 0));
        foldable("Suche", "search", true, searchRow(), dp(20));
        // Tablet: the dashboard from the sketch; Apps, ARC-DNA and Zuletzt move into its folder dock.
        if (Dashboard.wanted(this)) {
            dashboard = new Dashboard(this, new int[]{ink, ink2, ink3, rust, card, line}, new Dashboard.Host() {
                @Override
                public void folder(int which) {
                    openFolder(which);
                }

                @Override
                public Drawable[] folderIcons(int which) {
                    if (which == 0) {
                        Object[][] m = modeApps();
                        if (m != null) {
                            Drawable[] d = new Drawable[Math.min(4, m.length)];
                            for (int i = 0; i < d.length; i++) d[i] = m[i][1] instanceof Integer ? getDrawable((Integer) m[i][1]) : (Drawable) m[i][1];
                            return d;
                        }
                        return new Drawable[]{getDrawable(R.drawable.ic_phone), getDrawable(R.drawable.ic_sms),
                            getDrawable(R.drawable.ic_mail), getDrawable(R.drawable.ic_calendar)};
                    }
                    if (which == 1) return new Drawable[]{getDrawable(R.drawable.ic_line_player), getDrawable(R.drawable.ic_line_gallery),
                        getDrawable(R.drawable.ic_line_energy), getDrawable(R.drawable.ic_line_routes)};
                    Drawable[] d = new Drawable[Math.min(4, lastRecent.size())];
                    for (int i = 0; i < d.length; i++) d[i] = lastRecent.get(i).icon;
                    return d;
                }
            });
            dashboardView = dashboard.build();
            content.addView(dashboardView, margins(-1, -2, 0, dp(18), 0, 0));
        }

        appTilesBox = new FrameLayout(this);
        appTilesBox.addView(appTiles());
        foldable("Apps", "apps", true, appTilesBox, dp(22));
        intoFolder(0);
        renderWidgets();

        // ARC-DNA's own native apps, folded away until opened.
        arcBox = new FrameLayout(this);
        arcBox.addView(arcApps());
        foldable("ARC-DNA Apps", "arcApps", false, arcBox, dp(22));
        intoFolder(1);

        // Zuletzt: always on, one row.
        recentHome = new LinearLayout(this);
        recentHome.setOrientation(LinearLayout.VERTICAL);
        // Keep the row's last height while the apps load (theme switch, restart), so nothing jumps.
        recentHome.setMinimumHeight(prefs.getInt("recentH", 0));
        foldable("Zuletzt", "recentRow", true, recentHome, dp(22));   // new key: the old "recent" fold was closed by default
        intoFolder(2);

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

            @Override
            public void pickDj() {
                showDjPicker();
            }
        });
        musicView = music.view();
        foldable("Musik", "music", true, musicView, dp(26));

        // Mediathek inline: all apps, folded by default.
        homeLib = new LinearLayout(this);
        homeLib.setOrientation(LinearLayout.VERTICAL);
        foldable("Mediathek", "lib", false, homeLib, dp(26));

        LinearLayout today = new LinearLayout(this);
        today.setOrientation(LinearLayout.VERTICAL);
        today.addView(alarmCard, margins(-1, -2, 0, dp(6), 0, 0));
        today.addView(focusCard(), margins(-1, -2, 0, dp(12), 0, 0));
        foldable("Heute & Wecker", "today", false, today, dp(22));


        foldable("Projekte", "projects", false, projectChips(), dp(22));

        TextView hint = monoText(11, ink3);
        hint.setText("Abschnitt-Titel tippen = auf/zu · leere Fläche halten = volle Mediathek · Hasen-Knopf unten links = Fuchsjagd · wischen ← dunkel, → hell · Kreis = drehen · Buddy (Fuchs, Bär, Hase oder F1 – in den Einstellungen): tippen = Wecker, Akku, Musik, Heute zählt · halten = Tritt ins Glas · schütteln = Erdbeben");
        hint.setLineSpacing(0, 1.3f);
        hint.setTextColor(ink2);
        hint.setPadding(dp(14), dp(12), dp(14), dp(12));
        hint.setBackground(box(card, line, 16));          // its own card, like the music player
        foldable("Tipps", "hints", false, hint, dp(20));

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
        // The half dial hangs from the top edge itself: above the status-bar fade, not greyed under it.
        root.removeView(peek);
        root.addView(peek, root.indexOfChild(topScrim) + 1, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

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
        gameButton.setVisibility(View.GONE);

        // Claude, always one tap away: floating bottom right.
        claudeFab = new ImageView(this);
        claudeFab.setImageResource(R.drawable.ic_spark);         // the ARC-DNA spark: the menu (Claude is one of its bubbles)
        claudeFab.setScaleType(ImageView.ScaleType.FIT_CENTER);
        claudeFab.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable cr = new GradientDrawable();
        cr.setShape(GradientDrawable.OVAL);
        cr.setColor(card);
        cr.setStroke(dp(1.5f), rust);
        claudeFab.setBackground(cr);
        claudeFab.setElevation(dp(4));
        claudeFab.setContentDescription("Menü: Claude, Mediathek, Aufräumen");
        // Tap: bubbles float up (Claude, Mediathek, buddy, broom). Hold: straight to Claude.
        claudeFab.setOnClickListener(v -> toggleFabMenu());
        claudeFab.setOnLongClickListener(v -> {
            openClaude();
            return true;
        });
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        fabLp.setMargins(0, 0, dp(16), dp(70));   // lift it out of the corner clutter / system nav
        root.addView(claudeFab, fabLp);

        // Right-edge swipe → Mediathek (you use 3-button nav, so the edge is free of the back gesture).
        View edgeSwipe = new View(this);
        final android.view.GestureDetector edgeGd = new android.view.GestureDetector(this,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(android.view.MotionEvent e) { edgeFired = false; return true; }
                @Override public boolean onScroll(android.view.MotionEvent e1, android.view.MotionEvent e2, float dx, float dy) {
                    if (!edgeFired && e1 != null && e2 != null && e1.getX() - e2.getX() > dp(40)
                        && Math.abs(e1.getX() - e2.getX()) > Math.abs(e1.getY() - e2.getY())) { edgeFired = true; openArcWorld(); return true; }
                    return false;
                }
                @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                    if (!edgeFired && e1 != null && e2 != null && e1.getX() - e2.getX() > dp(28) && Math.abs(vx) > Math.abs(vy)) { edgeFired = true; openArcWorld(); return true; }
                    return false;
                }
            });
        edgeSwipe.setOnTouchListener((v, ev) -> edgeGd.onTouchEvent(ev));
        root.addView(edgeSwipe, new FrameLayout.LayoutParams(dp(26), -1, Gravity.END));

        // Left-edge drag → the Mediathek, following your thumb the whole way (not a snap).
        View edgeSwipeL = new View(this);
        edgeSwipeL.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; boolean dragging;
            @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        sx = ev.getRawX(); sy = ev.getRawY(); dragging = false; return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - sx, dy = ev.getRawY() - sy;
                        if (!dragging && dx > dp(14) && Math.abs(dx) > Math.abs(dy)) { dragging = true; libDragStart(); }
                        if (dragging) libDrag(dx);
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragging) libDragEnd(ev.getRawX() - sx);
                        return true;
                }
                return false;
            }
        });
        root.addView(edgeSwipeL, new FrameLayout.LayoutParams(dp(26), -1, Gravity.START));

        // The dock: your recent apps in a floating bar along the bottom, centred — MacBook-style,
        // always the same spot. Tap an icon to launch; swipe up on the dock to open the Mediathek.
        // Horizontal so it never collides with the right-edge FOKUS fan label.
        recentRail = new LinearLayout(this);
        recentRail.setOrientation(LinearLayout.HORIZONTAL);
        recentRail.setGravity(Gravity.CENTER_VERTICAL);
        recentRail.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable railBg = new GradientDrawable();
        railBg.setColor((card & 0x00FFFFFF) | 0xFF000000);   // opaque card — no content bleeds through
        railBg.setStroke(dp(1), line);
        railBg.setCornerRadius(dp(26));
        recentRail.setBackground(railBg);
        recentRail.setElevation(dp(7));
        recentRail.setVisibility(View.GONE);
        final android.view.GestureDetector railGd = new android.view.GestureDetector(this,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                @Override public boolean onSingleTapUp(android.view.MotionEvent e) { launchRailAt(e.getX()); return true; }
                @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                    if (e1 != null && e2 != null && e1.getY() - e2.getY() > dp(36) && Math.abs(vy) > Math.abs(vx)) { openLibrary(); return true; }
                    return false;
                }
            });
        recentRail.setOnTouchListener((v, ev) -> railGd.onTouchEvent(ev));
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        railLp.bottomMargin = dp(76);
        root.addView(recentRail, railLp);

        // Ambient background data — faint, non-clickable diagrams behind the home.
        ambient = new AmbientView(this);
        ambient.setColors(ink2, ink3, 0xFFE07A3C);
        root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
        refreshAmbient();

        setupThumbFan(root);
        applyMockHome();

        // Compact (Professionell): the three main apps stay at the bottom, always reachable.
        proDock = new LinearLayout(this);
        proDock.setGravity(Gravity.CENTER_VERTICAL);
        proDock.setPadding(dp(8), dp(6), dp(8), dp(6));
        proDock.setBackground(box(card, line, 30));
        proDock.setElevation(dp(4));
        proDock.setVisibility(View.GONE);
        root.addView(proDock, new FrameLayout.LayoutParams(-2, dp(60), Gravity.BOTTOM | Gravity.START));

        seismo = new SeismoView(this);
        root.addView(seismo, new FrameLayout.LayoutParams(-1, dp(30), Gravity.BOTTOM));

        library = buildLibrary();
        root.addView(library, new FrameLayout.LayoutParams(-1, -1));

        calibOverlay = buildCalibOverlay();
        root.addView(calibOverlay, new FrameLayout.LayoutParams(-1, -1));

        fox = new FoxView(this, ink, paper());
        applyBuddy();
        // Tap the buddy: it says something. Double-tap: its own world (game). Hold: the Gemini-style chat.
        android.view.GestureDetector buddyTaps = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) {
                return true;
            }

            @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                if (pro()) return true;
                if (buddy() == 4) duckRipples();
                hop();
                if (chat == null) buddyComment();
                return true;
            }

            @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                if (pro() || chat != null) return true;
                hop();
                handler.postDelayed(MainActivity.this::startBuddyWorld, 220);
                return true;
            }

            @Override public void onLongPress(android.view.MotionEvent e) {
                if (pro() || chat != null) return;
                fox.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                openChat();
            }
        });
        fox.setOnTouchListener((v, e) -> buddyTaps.onTouchEvent(e));
        root.addView(fox, new FrameLayout.LayoutParams(dp(84), dp(56), Gravity.BOTTOM | Gravity.START));

        bubble = text(13, ink, true);
        bubble.setBackground(box(ink, ink, 12));
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
            content.setPadding(left + dp(20), top0 + dp(24), right + dp(20), bottom + dp(170));   // clears rabbit button + fox
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
            ((FrameLayout.LayoutParams) claudeFab.getLayoutParams()).setMargins(0, 0, right + dp(18), bottom + dp(40));
            ((FrameLayout.LayoutParams) proDock.getLayoutParams()).setMargins(left + dp(18), 0, 0, bottom + dp(38));
            claudeFab.requestLayout();
            proDock.requestLayout();
            gameButton.requestLayout();
            ((FrameLayout.LayoutParams) bubble.getLayoutParams()).setMargins(left + dp(70), 0, 0, bottom + dp(72));
            bottomScrim.requestLayout();
            seismo.requestLayout();
            fox.requestLayout();
            bubble.requestLayout();
            return insets;
        });

        // Mirror LEDs in the display corners when it's too dark for head / eye scrolling.
        edgeLight = new EdgeLight(this, null);
        android.content.IntentFilter uf = new android.content.IntentFilter(Intent.ACTION_USER_PRESENT);
        uf.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, uf, RECEIVER_NOT_EXPORTED);
        else registerReceiver(unlockReceiver, uf);
        if (CALIBRATE_NOD.equals(getIntent().getAction())) handler.postDelayed(() -> calibrate("nod"), 600);
        root.addView(edgeLight, new FrameLayout.LayoutParams(-1, -1));
        if (minimal()) addMinimalOverlay(root);
        return root;
    }

    private LinearLayout alarmCard;
    private TextView alarmTime;

    /** The alarm as its own card: the bear, the time big, and what happens; tap = set, hold = on/off. */
    private LinearLayout alarmCard() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView bear = text(30, ink, false);
        bear.setText("🐻");
        box.addView(bear, margins(-2, -2, 0, 0, dp(14), 0));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        alarmTime = text(30, ink, true);
        alarmTime.setLetterSpacing(-0.03f);
        col.addView(alarmTime);
        col.addView(alarmRow);
        box.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView hint = monoText(10, ink3);
        hint.setText("HALTEN = AN/AUS");
        box.addView(hint);
        box.setOnClickListener(v -> openApp(new Intent(this, WeckerActivity.class), getDrawable(R.drawable.ic_line_alarm)));
        box.setOnLongClickListener(v -> {
            setAlarmOn(!prefs.getBoolean("alarmOn", false));
            return true;
        });
        return box;
    }

    private View focusCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(10));
        box.setBackground(box(card, line, 16));

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
        // Wraps onto up to 4 lines instead of scrolling sideways; Enter still means done.
        focus.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        focus.setHorizontallyScrolling(false);
        focus.setMaxLines(4);
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
        row.setPadding(0, 0, 0, dp(4));       // room for the field's lower edge (it was clipped)
        row.setClipToPadding(false);
        webQuery = field("Apps suchen…");
        webQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        webQuery.setOnEditorActionListener((v, actionId, event) -> {
            webSearch();
            return true;
        });
        tint(webQuery, LIGHT_RED);     // Suche in light red, like the other sections' colours
        row.addView(webQuery, new LinearLayout.LayoutParams(0, dp(50), 1f));

        TextView go = text(18, 0xFFFFFFFF, true);
        go.setText("→");
        go.setGravity(Gravity.CENTER);
        go.setBackground(box(LIGHT_RED, LIGHT_RED, 16));
        go.setContentDescription("Apps suchen");
        go.setOnClickListener(v -> webSearch());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(50));
        lp.leftMargin = dp(8);
        row.addView(go, lp);
        return row;
    }

    /** The home search bar now searches apps: it opens the Mediathek and carries the query into its app search. */
    private void webSearch() {
        String q = webQuery.getText().toString().trim();
        webQuery.setText("");
        openLibrary();
        if (libSearch != null) {
            libSearch.setText(q);
            libSearch.setSelection(q.length());
            libSearch.post(() -> {
                libSearch.requestFocus();
                android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (im != null) im.showSoftInput(libSearch, 0);
            });
        }
    }

    /** The eight tiles from architect-dna.ch/home, each opening the matching app on the phone. */
    private View appTiles() {
        return appTilesFrom(mainItems());
    }

    /** The APPS row's entries {label, icon, intent}: the mode's, else the standard eight. */
    private Object[][] mainItems() {
        Object[][] items = {
            {"Telefon", R.drawable.ic_phone, new Intent(this, DialerActivity.class)},
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
        Object[][] byMode = modeApps();
        return byMode != null ? byMode : items;
    }

    private View appTilesFrom(Object[][] items) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        // 8 favourites: one row of 8 on wide screens, else two rows of 4 (never a stretched half row).
        int cols = cols() >= 8 ? 8 : 4;
        for (int i = 0; i < items.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                wrap.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            Intent intent = (Intent) items[i][2];
            tileTint = YELLOW;
            Object ic = items[i][1];
            View tile = tile((String) items[i][0], ic instanceof Integer ? getDrawable((Integer) ic) : (Drawable) ic, 28);
            tileTint = 0;
            tile.setOnClickListener(v -> open(intent));
            // Telefon: long-press = the system phone app (favourites, voicemail, settings).
            if (i == 0) tile.setOnLongClickListener(v -> {
                Intent system = getPackageManager().getLaunchIntentForPackage("com.google.android.dialer");
                open(system != null ? system : Intent.createChooser(new Intent(Intent.ACTION_DIAL), "Telefon-App"));
                return true;
            });
            row.addView(tile, weighted(i % cols != 0));
        }
        for (int i = items.length % cols; i != 0 && i < cols; i++) row.addView(new View(this), weighted(true));
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
            // VOID FREQ lives inside ARC-DNA now; the other projects open on the web.
            chip.setOnClickListener(v -> open(p[0].equals("VOID FREQ") ? new Intent(this, FreqActivity.class)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(p[2]))));
            flow.addView(chip);
        }
        return flow;
    }

    /* ================= Mediathek ================= */

    private FrameLayout buildLibrary() {
        FrameLayout lib = new FrameLayout(this);
        lib.setBackgroundColor(isNight() ? 0xE6140D07 : 0xECF6E6C6);   // warm orange frosted glass (translucent)
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
            if (headMode().equals("off")) return false;
            startGazeCalibration();
            return true;
        });
        head.addView(headToggle);
        head.addView(gearButton());
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
        TextView settingsLink = monoText(11, ink2);
        settingsLink.setText("⚙ EINSTELLUNGEN →");
        settingsLink.setPadding(dp(4), dp(16), dp(4), dp(8));
        settingsLink.setOnClickListener(v -> showSettings());
        body.addView(settingsLink, margins(-1, -2, 0, dp(16), 0, 0));
        return lib;
    }

    /* ================= Settings: one screen, grouped, reachable from ⚙ on home and in the Mediathek ================= */

    private FrameLayout settingsSheet;

    private TextView gearButton() {
        TextView g = text(18, ink2, false);
        g.setText("⚙");
        g.setPadding(dp(14), dp(2), dp(4), dp(2));
        g.setContentDescription("Einstellungen");
        g.setOnClickListener(v -> showSettings());
        return g;
    }

    private void showSettings() {
        if (settingsSheet != null) return;
        hideKeyboard();
        FrameLayout sheet = new FrameLayout(this);
        sheet.setBackgroundColor(panel);
        sheet.setClickable(true);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        android.graphics.Insets in = android.graphics.Insets.NONE;
        if (Build.VERSION.SDK_INT >= 30 && rootFrame.getRootWindowInsets() != null) {
            in = rootFrame.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        }
        col.setPadding(dp(22) + in.left, dp(18) + in.top, dp(22) + in.right, dp(40) + in.bottom);
        sv.addView(col);
        sheet.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Einstellungen"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(14), dp(4), dp(4), dp(4));
        close.setContentDescription("Einstellungen schliessen");
        close.setOnClickListener(v -> closeSettings());
        head.addView(close);
        col.addView(head);

        LinearLayout home = settingsGroup(col, "Startseite");
        settingsRow(home, "ARC·Tap · Theme/Buddy teilen", () -> "An dein Partner-Gerät senden (Bluetooth)", v -> showArcTap(), null);
        if (getResources().getConfiguration().smallestScreenWidthDp >= 600) {
            settingsRow(home, "Tablet-Dashboard", () -> prefs.getBoolean("dashboard", true) ? "AN · Wetter, Uhr, Garten, Ordner" : "AUS · Listen", v -> {
                prefs.edit().putBoolean("dashboard", !prefs.getBoolean("dashboard", true)).apply();
                recreate();
            }, null);
        }
        settingsRow(home, "Zuletzt-Karussell", () -> prefs.getInt("recentCount", 8) + " Apps", v -> {
            int[] steps = {4, 8, 12, 16, 24};
            int now = prefs.getInt("recentCount", 8), next = steps[0];
            for (int i = 0; i < steps.length; i++) if (steps[i] == now) next = steps[(i + 1) % steps.length];
            prefs.edit().putInt("recentCount", next).apply();
            refreshRecents();
        }, null);
        settingsRow(home, "Gesichts-Sperre nach dem Entsperren", () -> prefs.getBoolean("faceLock", false) ? "An" : "Aus", v -> {
            boolean on = !prefs.getBoolean("faceLock", false);
            if (on && !biometricReady()) {
                pop("Erst Gesichtsentsperrung oder Fingerabdruck in Android einrichten");
                return;
            }
            prefs.edit().putBoolean("faceLock", on).apply();
        }, null);
        settingsRow(home, "Taschenlampe im Dunkeln", () -> prefs.getBoolean("autoTorch", true) ? "Automatisch" : "Aus",
            v -> prefs.edit().putBoolean("autoTorch", !prefs.getBoolean("autoTorch", true)).apply(), null);

        LinearLayout type = settingsGroup(col, "Typografie");
        settingsRow(type, "Schrift", () -> FONTS[Math.max(0, Math.min(FONTS.length - 1, prefs.getInt("font", 0)))], v -> {
            prefs.edit().putInt("font", (prefs.getInt("font", 0) + 1) % FONTS.length).apply();
            applyTypography();
        }, null);
        settingsRow(type, "Textgrösse", () -> SIZES[Math.max(0, Math.min(SIZES.length - 1, prefs.getInt("fontSize", 1)))], v -> {
            prefs.edit().putInt("fontSize", (prefs.getInt("fontSize", 1) + 1) % SIZES.length).apply();
            applyTypography();
        }, null);

        LinearLayout sync = settingsGroup(col, "Musik-Sync · Bluetooth");
        settingsRow(sync, "Mit Tablet / Handy abgleichen", () -> {
            String n = BtSync.peerName(this);
            long at = BtSync.peer(this).getLong("at", 0);
            return n == null ? "Jetzt ›" : n + " · " + new java.text.SimpleDateFormat("HH:mm", Locale.GERMAN).format(new java.util.Date(at));
        }, v -> {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 31);
                return;
            }
            pop("Suche gekoppelte Geräte mit ARC-DNA …");
            BtSync.syncSoon(this, 0, name -> {
                pop(name != null ? "Abgeglichen mit " + name + "." : "Kein Partner gefunden.\nBeide Geräte koppeln, Bluetooth an und ARC-DNA auf beiden öffnen.");
                if (music != null) music.refreshStats();
            });
        }, null);

        LinearLayout head2 = settingsGroup(col, "Kopf-Scroll");
        settingsRow(head2, "In der Mediathek", () -> {
            String m = headMode();
            return m.equals("nod") ? "Nicken" : "Aus";
        }, v -> cycleHeadMode(), null);
        settingsRow(head2, "Nicken kalibrieren", () -> prefs.contains("nodMid") ? "kalibriert" : "noch nie", v -> calibrate("nod"), null);
        settingsRow(head2, "Bei jedem Start neu zentrieren", () -> prefs.getBoolean("nodAlwaysCalib", true) ? "Still, automatisch" : "Aus",
            v -> prefs.edit().putBoolean("nodAlwaysCalib", !prefs.getBoolean("nodAlwaysCalib", true)).apply(), null);
        settingsRow(head2, "In anderen Apps · pro App", () -> prefs.getBoolean("scrollOutside", false) ? "An" : "Aus",
            v -> showAppScrollSettings(), null);

        LinearLayout sys = settingsGroup(col, "System");
        settingsRow(sys, "Updates", () -> updateLib.getText().toString(), v -> updateLib.performClick(), v -> {
            showTokenDialog();
            return true;
        });
        settingsRow(sys, "Kamera-Debug", () -> prefs.getBoolean("camDebug", false) ? "an · ● nimmt auf · aus = teilen" : "aus · an = aufnehmen", v -> {
            boolean off = prefs.getBoolean("camDebug", false);
            prefs.edit().putBoolean("camDebug", !off).apply();
            if (off && HeadDebugView.hasRecording()) handler.postDelayed(() -> HeadDebugView.saveAndShare(this), 300);
            if (libraryOpen && !headMode().equals("off")) {
                stopHead();
                startHead();
            }
        }, null);
        settingsRow(sys, "Tipps", () -> Coach.seenCount(this) + "/" + Coach.TIPS.length + (Coach.isOff(this) ? " · pausiert" : " · wächst mit der Zeit"), v -> showTipsList(), null);
        settingsRow(sys, "Diagnose an Claude senden", () -> "anonym · halten = teilen", v -> uploadDiagnostics(), v -> {
            shareDiagnostics();
            return true;
        });

        permissionsGroup(col);

        settingsSheet = sheet;
        rootFrame.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
        sheet.setAlpha(0f);
        sheet.setTranslationY(dp(40));
        sheet.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** Calibrate NICKEN or BLICK: switches the Mediathek to that mode, opens it and starts the dots. */
    private void calibrate(String mode) {
        prefs.edit().putString("headMode", mode).remove("headScroll").apply();
        paintHeadToggle();
        closeSettings();
        stopHead();
        prefs.edit().remove(mode.equals("nod") ? "nodMid" : "gazeMid").apply();   // forces the calibration on start
        if (!libraryOpen) openLibrary();
        else startHead();
        if (calibStage < 0) handler.postDelayed(() -> {
            if (calibStage < 0) startGazeCalibration();
        }, 500);
    }

    /**
     * Every permission ARC-DNA holds: what it's for, whether it's granted right now, and a tap to
     * Android's own page where it can be changed or taken away. Nothing here leaves the phone.
     */
    private void permissionsGroup(LinearLayout col) {
        LinearLayout g = settingsGroup(col, "Berechtigungen");
        TextView note = monoText(10, ink3);
        note.setSingleLine(false);
        note.setText("ALLES BLEIBT AUF DEM HANDY · ANTIPPEN = IN ANDROID ÄNDERN ODER ENTZIEHEN");
        note.setPadding(dp(16), dp(12), dp(16), dp(4));
        g.addView(note);
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        Object[][] runtime = {
            {"Kamera", "Kopf-Scroll, Taschenlampe im Dunkeln", Manifest.permission.CAMERA},
            {"Mikrofon", "Musik-Visualizer (nur Pegel, keine Aufnahme)", Manifest.permission.RECORD_AUDIO},
            {"Kontakte", "Namen im Telefon", Manifest.permission.READ_CONTACTS},
            {"Anrufliste", "Letzte Anrufe im Telefon", Manifest.permission.READ_CALL_LOG},
            {"Telefonieren", "Anrufen per Tipp im Telefon", Manifest.permission.CALL_PHONE},
            {"Bluetooth", "Kopfhörer-Name, Akku, Musik-Sync mit eigenen Geräten", Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : null},
            {"Benachrichtigungen", "Wecker, VOID FREQ, Kopf-Scroll", Build.VERSION.SDK_INT >= 33 ? Manifest.permission.POST_NOTIFICATIONS : null}};
        for (Object[] p : runtime) {
            String perm = (String) p[2];
            if (perm == null) continue;
            permissionRow(g, (String) p[0], (String) p[1],
                () -> checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED, details);
        }
        android.app.AlarmManager am = getSystemService(android.app.AlarmManager.class);
        permissionRow(g, "Nutzungszugriff", "Zuletzt benutzte Apps", this::hasUsageAccess,
            new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        permissionRow(g, "Systemeinstellungen", "Helligkeits-Hebel", () -> Settings.System.canWrite(this),
            new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
        permissionRow(g, "Bedienungshilfe", "Kopf-Scroll in Apps (liest keine Inhalte)", this::scrollAssistOn,
            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        permissionRow(g, "Benachrichtigungszugriff", "Musik-Karte, Statistik (nur Mediensitzungen)", () -> {
            String on = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return on != null && on.contains(getPackageName());
        }, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        permissionRow(g, "Apps installieren", "Nur ARC-DNA-Updates (Android prüft die Signatur)",
            () -> getPackageManager().canRequestPackageInstalls(),
            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        if (Build.VERSION.SDK_INT >= 31) {
            permissionRow(g, "Exakte Wecker", "Bären-Wecker auf die Minute", () -> am == null || am.canScheduleExactAlarms(),
                new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        }
    }

    private void permissionRow(LinearLayout card, String name, String why, java.util.function.BooleanSupplier granted, Intent where) {
        View div = new View(this);
        div.setBackgroundColor(line);
        card.addView(div, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(14), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = text(15, ink, true);
        n.setText(name);
        texts.addView(n);
        TextView w = text(12, ink3, false);
        w.setText(why);
        texts.addView(w);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = monoText(11, rust);
        Runnable paint = () -> {
            boolean on = granted.getAsBoolean();
            val.setText(on ? "ERLAUBT ›" : "AUS ›");
            val.setTextColor(on ? rust : ink3);
        };
        paint.run();
        row.addView(val);
        row.setOnClickListener(v -> {
            open(where);
            v.postDelayed(paint, 1500);
        });
        card.addView(row);
    }

    private void closeSettings() {
        FrameLayout sheet = settingsSheet;
        settingsSheet = null;
        if (sheet == null) return;
        sheet.animate().alpha(0f).translationY(dp(40)).setDuration(200)
            .withEndAction(() -> rootFrame.removeView(sheet)).start();
    }

    private LinearLayout settingsGroup(LinearLayout col, String title) {
        col.addView(label(title), margins(-1, -2, 0, dp(26), 0, dp(10)));
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(box(card, line, 16));
        col.addView(group);
        return group;
    }

    /** One setting: name left, current value right in rust; tap changes it, the value repaints. */
    private void settingsRow(LinearLayout card, String name, java.util.function.Supplier<String> value,
                             View.OnClickListener tap, View.OnLongClickListener hold) {
        if (card.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(line);
            card.addView(div, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));
        }
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(14), dp(15));
        TextView n = text(15, ink, true);
        n.setText(name);
        row.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = monoText(11, rust);
        val.setLetterSpacing(0.08f);
        val.setGravity(Gravity.END);
        val.setMaxWidth(dp(170));
        Runnable paint = () -> val.setText(value.get().toUpperCase(Locale.GERMAN));
        paint.run();
        row.addView(val);
        TextView chev = text(15, ink3, false);
        chev.setText("  ›");
        row.addView(chev);
        row.setOnClickListener(v -> {
            tap.onClick(v);
            paint.run();
            v.postDelayed(paint, 600);      // async ones (update check) repaint a moment later
        });
        if (hold != null) row.setOnLongClickListener(hold);
        card.addView(row);
    }

    private void openLibrary() {
        if (libraryOpen) return;
        libraryOpen = true;
        hideKeyboard();
        // Orange frosted glass: the Mediathek sweeps in from the right (matching the edge-swipe) over
        // the blurred home, which stays faintly visible through the translucent panel.
        blurHome(true);
        library.setElevation(dp(28));      // above the Schlicht overlay (elevation 24) when it's on
        library.bringToFront();
        library.setVisibility(View.VISIBLE);
        library.setAlpha(0f);
        library.setTranslationX(-(library.getWidth() > 0 ? library.getWidth() * 0.16f : dp(80)));   // from the LEFT
        library.animate().cancel();
        library.animate().alpha(1f).translationX(0).setDuration(340)
            .setInterpolator(new DecelerateInterpolator(1.7f)).start();
        scroll.animate().cancel();
        scroll.setPivotX(scroll.getWidth());   // home recedes to the right as the Mediathek enters from the left
        scroll.animate().alpha(0.4f).scaleX(0.97f).scaleY(0.97f).setDuration(340)
            .setInterpolator(new DecelerateInterpolator(1.7f)).start();
        libScroll.scrollTo(0, 0);
        floaters(false);
        dockOverLib(true);                 // keep the bottom dock on top of the Mediathek
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
        blurHome(false);
        // The same move backwards: the Mediathek sweeps back out to the left, the home comes forward.
        library.animate().cancel();
        library.animate().alpha(0f).translationX(-(library.getWidth() > 0 ? library.getWidth() * 0.16f : dp(80))).setDuration(260)
            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction(() -> library.setVisibility(View.GONE)).start();
        scroll.animate().cancel();
        scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        floaters(true);
        dockOverLib(false);
    }

    // ---- interactive Mediathek drag: the panel tracks your thumb from the left edge ----
    private float libW() { float w = library.getWidth(); return w > 0 ? w : getResources().getDisplayMetrics().widthPixels; }
    /** Keep the bottom dock drawn and tappable above the open Mediathek; restore the normal stack on close. */
    private void dockOverLib(boolean over) {
        if (recentRail != null) { recentRail.setElevation(dp(over ? 30 : 7)); if (over) recentRail.bringToFront(); }
        if (!over) {   // put the interactive overlays back on top of the dock
            if (ambient != null) ambient.bringToFront();
            if (thumbFan != null) thumbFan.bringToFront();
            if (claudeFab != null) claudeFab.bringToFront();
        }
    }
    private void libDragStart() {
        if (libraryOpen || library == null) return;
        library.setElevation(dp(28)); library.bringToFront();
        library.setVisibility(View.VISIBLE);
        library.setTranslationX(-libW()); library.setAlpha(0f);   // hidden off the LEFT
        library.animate().cancel(); scroll.animate().cancel();
        scroll.setPivotX(scroll.getWidth());
        floaters(false); blurHome(true); dockOverLib(true);
    }
    private void libDrag(float dx) {
        if (library == null) return;
        float w = libW(), p = Math.max(0f, Math.min(1f, dx / w));
        library.setTranslationX(-w * (1f - p)); library.setAlpha(Math.min(1f, p * 1.3f));   // slides in from the left
        scroll.setAlpha(1f - 0.6f * p); scroll.setScaleX(1f - 0.03f * p); scroll.setScaleY(1f - 0.03f * p);
    }
    private void libDragEnd(float dx) {
        if (library == null) return;
        float w = libW(), p = dx / w;
        if (p > 0.30f) {                       // committed → settle the Mediathek open
            libraryOpen = true;
            library.animate().alpha(1f).translationX(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.7f)).start();
            scroll.animate().alpha(0.4f).scaleX(0.97f).scaleY(0.97f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.7f)).start();
            libScroll.scrollTo(0, 0);
            if (!headMode().equals("off")) startHead();
        } else {                                // let go early → slide it back out to the left
            library.animate().alpha(0f).translationX(-w).setDuration(200)
                .withEndAction(() -> library.setVisibility(View.GONE)).start();
            scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
            blurHome(false); floaters(true); dockOverLib(false);
        }
    }

    /** Fox, rabbit button, speech bubble and seismograph float over the page: out of the way in the Mediathek. */
    private void floaters(boolean show) {
        if (show && mockHome()) show = false;   // mock home keeps the fox / seismo / bubble hidden
        for (View v : new View[]{fox, bubble, seismo}) {
            if (v == null) continue;
            v.animate().cancel();
            if (show) {
                if (v == bubble) continue;            // the bubble only comes back when the fox speaks
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(200).start();
            } else {
                v.animate().alpha(0f).setDuration(150).withEndAction(() -> v.setVisibility(View.INVISIBLE)).start();
            }
        }
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
            if (java.util.Arrays.asList(TINT_FOLDS).contains(key)) recolour();
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
        // At most 34 apps here: the ones you use first, then A–Z (the full list is the volle Mediathek).
        // Main screen stays ARC-only: the colourful third-party apps live in the Mediathek,
        // reached by the cube button bottom-right. Here we show just our own apps.
        List<AppEntry> pick = new ArrayList<>();
        for (AppEntry a : lastRecent) if (isOwnApp(a) && !pick.contains(a)) pick.add(a);
        for (AppEntry a : apps) {
            if (pick.size() >= 34) break;
            if (isOwnApp(a) && !pick.contains(a)) pick.add(a);
        }
        fillGrid(homeLib, pick.subList(0, Math.min(34, pick.size())), cols());
        // Bottom bar, so a long open list can be closed without scrolling back up to its title.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(box(card, line, 16));
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

    /** True for our own ARC-DNA apps (same package); false for installed third-party apps. */
    private boolean isOwnApp(AppEntry a) {
        return a != null && a.component != null && getPackageName().equals(a.component.getPackageName());
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<AppEntry> list = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                // Our own entries: only the built-in apps (VOID FREQ), not the launcher itself.
                if (getPackageName().equals(ri.activityInfo.packageName) && !FreqActivity.class.getName().equals(ri.activityInfo.name)
                    && !GlobusActivity.class.getName().equals(ri.activityInfo.name)
                    && !NexusActivity.class.getName().equals(ri.activityInfo.name)
                    && !BoardActivity.class.getName().equals(ri.activityInfo.name)
                    && !RecorderActivity.class.getName().equals(ri.activityInfo.name)
                    && !GalleryActivity.class.getName().equals(ri.activityInfo.name)
                    && !CameraActivity.class.getName().equals(ri.activityInfo.name)
                    && !DecibelActivity.class.getName().equals(ri.activityInfo.name)
                    && !PlayerActivity.class.getName().equals(ri.activityInfo.name)
                    && !EnergyActivity.class.getName().equals(ri.activityInfo.name)
                    && !VpnActivity.class.getName().equals(ri.activityInfo.name)
                    && !RouteActivity.class.getName().equals(ri.activityInfo.name)
                    && !WeckerActivity.class.getName().equals(ri.activityInfo.name)) continue;
                list.add(new AppEntry(
                    ri.loadLabel(pm).toString(),
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    ownIcon(ri.activityInfo.name, ri.loadIcon(pm))));
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

    /** ARC Link (Mac) broadcasts the laptop keyboard's battery over USB; show it as a floating chip. */
    private void registerKbdBatt() {
        kbdBattReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra("level", -1);
                boolean charging = intent.getBooleanExtra("charging", false);
                if (level < 0) return;
                if (!KbdBatt.canShow(MainActivity.this)) { askOverlayOnce(); return; }
                KbdBatt.show(MainActivity.this, level, charging);
            }
        };
        IntentFilter f = new IntentFilter("ch.architectdna.home.KBD_BATT");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(kbdBattReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(kbdBattReceiver, f);
    }

    private void askOverlayOnce() {
        if (prefs.getBoolean("askedKbdOverlay", false)) return;
        prefs.edit().putBoolean("askedKbdOverlay", true).apply();
        pop("ARC Link: Overlay-Recht für Tastatur-Akku aktivieren");
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException ignored) { }
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
        openApp(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), app.icon);
    }

    /* ---- Opening and coming back: slide up; back home the app is crushed and binned ---- */

    private float tapX = -1, tapY = -1;
    private long downAt;
    private Drawable leftIcon;
    private Intent leftIntent;
    private long leftAt, pausedAt;
    private boolean crumplePending;

    /** Start an app sliding up from the bottom; remember it for the paper ball on the way back. */
    private void openApp(Intent intent, Drawable icon) {
        try {
            // The app grows out of the tile you tapped (the pop-up from the bottom edge was disliked);
            // without a fresh tap (voice, Claude, a shortcut) it grows from the middle.
            int w = rootFrame.getWidth(), h = rootFrame.getHeight();
            android.app.ActivityOptions o;
            if (w > 0 && h > 0) {
                int[] at = new int[2];
                rootFrame.getLocationOnScreen(at);
                boolean fresh = android.os.SystemClock.uptimeMillis() - downAt < 1500 && tapX >= 0;
                int size = dp(56);
                int cx = fresh ? Math.round(tapX) - at[0] : w / 2, cy = fresh ? Math.round(tapY) - at[1] : h / 2;
                o = android.app.ActivityOptions.makeScaleUpAnimation(rootFrame, cx - size / 2, cy - size / 2, size, size);
            } else {
                o = android.app.ActivityOptions.makeCustomAnimation(this, R.anim.app_slide_up, R.anim.home_hold);
            }
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o.toBundle());
            leftIcon = icon == null ? null : icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
            leftIntent = intent;
            if (intent.getComponent() != null && getPackageName().equals(intent.getComponent().getPackageName())) {
                prefs.edit().putLong("arcUsed:" + intent.getComponent().getClassName(), System.currentTimeMillis()).apply();
            }
            leftAt = System.currentTimeMillis();
            crumplePending = true;
        } catch (ActivityNotFoundException | SecurityException e) {
            pop("Keine passende App gefunden.");
        }
    }

    /** Back home after using an app: whoosh, crush, throw it in the bin next to the buddy. */
    /** "Ruhig": no dino visits, bin throws or races; now and then (1 in 8) a surprise anyway. */
    private boolean calm() {
        return prefs.getBoolean("calm", false) || pro() || swept();
    }

    /** "Professionell": neat and quiet – no buddy, no rainbows or glows, plain tiles, no surprises. */
    private ImageView claudeFab;
    private FrameLayout fabMenu;

    private void openClaude() {
        openApp(appOr("com.anthropic.claude", new Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai"))), getDrawable(R.drawable.ic_claude));
    }

    /** The floating button's bubbles: they rise one after another above it; tap outside = close. */
    /** Ruheinsel: the calm crisis screen (header 🆘, the menu, or when things get heavy). */
    private void openCrisis() {
        try {
            startActivity(new Intent(this, CrisisActivity.class));
        } catch (RuntimeException ignored) {
        }
    }

    private void toggleFabMenu() {
        if (fabMenu != null) {
            closeFabMenu();
            return;
        }
        FrameLayout m = new FrameLayout(this);
        fabMenu = m;
        m.setOnClickListener(v -> closeFabMenu());
        m.setElevation(dp(9));
        // Full-screen blackout + blur behind the bubbles, so the menu reads as its own layer.
        m.setBackgroundColor(isNight() ? 0xC2000000 : 0x990F0B08);
        m.setAlpha(0f);
        m.animate().alpha(1f).setDuration(200).start();
        blurHome(true);
        String name = BUDDIES[buddy()][1];
        Object[][] items = {
            {"✳  Claude", (Runnable) this::openClaude},
            {"▦  Mediathek", (Runnable) this::openLibrary},
            {swept() ? "↺  Alles zurück" : "🧹  Aufräumen", (Runnable) () -> {
                if (swept()) unsweep();
                else sweep();
            }},
            {"💬  " + name + " fragen", (Runnable) this::openChat},
            {"🆘  Ruheinsel", (Runnable) this::openCrisis},
        };
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) claudeFab.getLayoutParams();
        for (int i = 0; i < items.length; i++) {
            TextView b = text(15, ink, true);
            b.setText((String) items[i][0]);
            b.setPadding(dp(18), dp(12), dp(18), dp(12));
            b.setBackground(box(isNight() ? 0xF21C1C1A : 0xF2FFFFFF, rust, 24));
            b.setElevation(dp(6));
            Runnable act = (Runnable) items[i][1];
            b.setOnClickListener(v -> {
                closeFabMenu();
                act.run();
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, fl.rightMargin, fl.bottomMargin + dp(68) + i * dp(58));
            m.addView(b, lp);
            b.setAlpha(0f);
            b.setTranslationY(dp(40));
            b.setScaleX(0.8f);
            b.setScaleY(0.8f);
            b.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setStartDelay(i * 45L).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f)).start();
        }
        rootFrame.addView(m, new FrameLayout.LayoutParams(-1, -1));
        claudeFab.bringToFront();
        claudeFab.setElevation(dp(10));
        claudeFab.animate().rotation(135).setDuration(260).start();
    }

    private void closeFabMenu() {
        FrameLayout m = fabMenu;
        fabMenu = null;
        if (m == null) return;
        claudeFab.animate().rotation(0).setDuration(220).start();
        claudeFab.setElevation(dp(4));
        blurHome(false);
        m.animate().alpha(0f).setDuration(150).start();
        m.postDelayed(() -> rootFrame.removeView(m), 170);
    }

    private LinearLayout proDock;

    private boolean pro() {
        return prefs.getBoolean("pro", true);   // artistic ARC-DNA is quiet by default: no buddy, no games, no glows on home
    }

    private void applyPro() {
        boolean on = pro();
        int vis = on ? View.GONE : View.VISIBLE;
        if (fox != null) fox.setVisibility(vis);
        if (gameButton != null) gameButton.setVisibility(View.GONE);      // the bunny button is gone: tap the buddy for its game
        if (seismo != null) seismo.setVisibility(vis);
        if (on && bubble != null) bubble.setVisibility(View.GONE);
        if (on && edgeLight != null) edgeLight.watch(false);
        if (proDock != null) {
            proDock.removeAllViews();
            proDock.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) {
                Object[][] items = mainItems();
                for (int i = 0; i < Math.min(3, items.length); i++) {
                    Object[] it = items[i];
                    LinearLayout b = new LinearLayout(this);
                    b.setGravity(Gravity.CENTER_VERTICAL);
                    b.setPadding(dp(12), 0, dp(14), 0);
                    ImageView ic = new ImageView(this);
                    ic.setImageDrawable(it[1] instanceof Integer ? getDrawable((Integer) it[1]) : (Drawable) it[1]);
                    b.addView(ic, new LinearLayout.LayoutParams(dp(26), dp(26)));
                    TextView l = text(13, ink, true);
                    l.setText((String) it[0]);
                    l.setPadding(dp(8), 0, 0, 0);
                    b.addView(l);
                    Intent in = (Intent) it[2];
                    b.setOnClickListener(v -> open(in));
                    proDock.addView(b, new LinearLayout.LayoutParams(-2, -1));
                }
            }
        }
    }

    private boolean calmSkip() {
        return calm() && random.nextInt(8) != 0;
    }

    private void playCrumple() {
        if (calmSkip()) return;
        if (rootFrame == null || rootFrame.getWidth() == 0) return;
        float binX, binY;
        if (fox != null && fox.isShown() && fox.getAlpha() > 0.5f) {
            binX = fox.getRight() + dp(34);
            binY = fox.getTop() + fox.getHeight() * 0.45f;
        } else {
            binX = dp(48);
            binY = dp(130);
        }
        int paper = isNight() ? 0xFF2A2A28 : 0xFFFFFFFF;
        CrumpleView[] holder = new CrumpleView[1];
        holder[0] = new CrumpleView(this, leftIcon, binX, binY, paper, line, ink, rust,
            () -> rootFrame.removeView(holder[0]));
        String[] lines = {"Weg damit!", "Tschüss!", "Treffer.", "Aufgeräumt.", "Und tschüss."};
        holder[0].hooks(() -> voice.crumple(), () -> {
            voice.thud();
            hop();
            say(lines[random.nextInt(lines.length)], 1200);
        });
        // About one automatic throw in four misses, and the buddy takes it out on you.
        // A miss by hand gets the same treatment.
        {
            String[] angry = {"Echt jetzt?!", "Echt jetzt?!", "Im Ernst?!", "Alter?!", "Daneben?! Echt jetzt?!", "Triff doch mal!", "Na warte …"};
            holder[0].miss(random.nextInt(4) == 0, () -> {
                voice.thud();
                hop();
                say(angry[random.nextInt(angry.length)], 900);
            }, () -> {
                voice.thud();
                if (cracks == null) {
                    cracks = new CrackView(this, ink);
                    rootFrame.addView(cracks, new FrameLayout.LayoutParams(-1, -1));
                }
                cracks.bringToFront();
                cracks.crack(rootFrame.getWidth() / 2f, rootFrame.getHeight() * 0.5f);
            });
        }
        Intent again = leftIntent;
        Drawable againIcon = leftIcon;
        if (again != null) holder[0].reopen(() -> {
            say("Doch nicht? Bitte schön.", 1200);
            openApp(again, againIcon);
        });
        rootFrame.addView(holder[0], new FrameLayout.LayoutParams(-1, -1));
        leftIcon = null;
    }

    private void open(Intent intent) {
        try {
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            pop("Keine passende App gefunden.");
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
    /** Fill the right-edge thumb rail with recent apps (orange strip, fixed position = muscle memory). */
    private void refreshRecentRail() {
        if (recentRail == null) return;
        recentRail.removeAllViews();
        int n = Math.min(6, lastRecent.size());
        if (n == 0) { recentRail.setVisibility(View.GONE); return; }
        recentRail.setVisibility(View.VISIBLE);
        recentRail.setAlpha(1f); recentRail.setTranslationY(0f);
        for (int i = 0; i < n; i++) {
            AppEntry a = lastRecent.get(i);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setTag(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.leftMargin = i == 0 ? 0 : dp(12);
            recentRail.addView(iv, lp);
        }
    }

    /** Fill the ambient background cells: battery and network now, storage now, weather off-thread. */
    private void refreshAmbient() {
        if (ambient == null) return;
        try {
            android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lvl = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (lvl >= 0 && sc > 0) ambient.setBattery(Math.round(lvl * 100f / sc));
            }
        } catch (Exception ignored) { }
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            String net = "Offline";
            if (cm != null) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (nc != null) net = nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "WLAN"
                    : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobil" : "Netz";
            }
            ambient.setNet(net);
        } catch (Exception ignored) { }
        try {
            android.os.StatFs fs = new android.os.StatFs(getFilesDir().getAbsolutePath());
            float gb = 1024f * 1024f * 1024f;
            ambient.setStorage(fs.getAvailableBytes() / gb, fs.getTotalBytes() / gb);
        } catch (Exception ignored) { }
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=46.95&longitude=7.44&current=temperature_2m,weather_code").openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(6000);
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String ln;
                while ((ln = r.readLine()) != null) sb.append(ln);
                r.close();
                org.json.JSONObject cur = new org.json.JSONObject(sb.toString()).getJSONObject("current");
                final float temp = (float) cur.getDouble("temperature_2m");
                final String sky = skyWord(cur.optInt("weather_code", -1));
                runOnUiThread(() -> { if (ambient != null) ambient.setWeather(temp, sky); });
            } catch (Exception ignored) { }
        }).start();
    }
    private String skyWord(int code) {
        if (code == 0) return "klar";
        if (code <= 3) return "wolkig";
        if (code <= 48) return "Nebel";
        if (code <= 67) return "Regen";
        if (code <= 77) return "Schnee";
        if (code <= 82) return "Schauer";
        if (code <= 99) return "Gewitter";
        return "";
    }

    /** Left-edge swipe target: open the ARC·DNA world (the Fokus funnel). */
    private void openArcWorld() {
        try { startActivity(new Intent(this, FokusActivity.class)); } catch (RuntimeException ignored) { }
    }

    /** The thumb-fans: press-hold a bottom corner, slide along your thumb's arc, release to launch. */
    private void setupThumbFan(FrameLayout root) {
        final String LERN = "https://claude.ai/artifact/KjKSrDurQdhPhGEzZQtUhY";
        ThumbFanView.Item[] left = {
            new ThumbFanView.Item("Claude", dr(R.drawable.ic_claude), this::openClaude),
            new ThumbFanView.Item("Telefon", null, () -> safe(new Intent(Intent.ACTION_DIAL))),
            new ThumbFanView.Item("SMS", null, () -> safe(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))),
            new ThumbFanView.Item("Mail", null, () -> safe(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL))),
            new ThumbFanView.Item("Kamera", dr(R.drawable.ic_line_arccam), () -> startAct(CameraActivity.class)),
        };
        ThumbFanView.Cat[] right = {
            new ThumbFanView.Cat("Ruhe", new ThumbFanView.Item[]{
                new ThumbFanView.Item("Musik", dr(R.drawable.ic_line_player), () -> startAct(PlayerActivity.class)),
                new ThumbFanView.Item("Globus", dr(R.drawable.ic_line_globus), () -> startAct(GlobusActivity.class)),
                new ThumbFanView.Item("VOID FREQ", dr(R.drawable.ic_line_freq), () -> startAct(FreqActivity.class)),
            }, this::openArcWorld),
            new ThumbFanView.Cat("Spiel", new ThumbFanView.Item[]{
                new ThumbFanView.Item("Fuchsjagd", null, () -> startAct(RaceActivity.class)),
                new ThumbFanView.Item("Nexus", dr(R.drawable.ic_line_nexus), () -> startAct(NexusActivity.class)),
                new ThumbFanView.Item("Board", dr(R.drawable.ic_line_board), () -> startAct(BoardActivity.class)),
            }, this::openArcWorld),
            new ThumbFanView.Cat("Schule", new ThumbFanView.Item[]{
                new ThumbFanView.Item("Lernen", null, () -> safe(new Intent(Intent.ACTION_VIEW, Uri.parse(LERN)))),
                new ThumbFanView.Item("Bücher", dr(R.drawable.ic_line_buecher), () -> startAct(BuecherActivity.class)),
                new ThumbFanView.Item("Wecker", null, () -> startAct(WeckerActivity.class)),
            }, this::openArcWorld),
            new ThumbFanView.Cat("Arbeit", new ThumbFanView.Item[]{
                new ThumbFanView.Item("Board", dr(R.drawable.ic_line_board), () -> startAct(BoardActivity.class)),
                new ThumbFanView.Item("Aufnahme", dr(R.drawable.ic_line_recorder), () -> startAct(RecorderActivity.class)),
                new ThumbFanView.Item("Werkzeuge", dr(R.drawable.ic_line_werkzeuge), () -> startAct(WerkzeugeActivity.class)),
            }, this::openArcWorld),
        };
        thumbFan = new ThumbFanView(this);
        thumbFan.setColors(card, line, ink, ink2, ink3, 0xFFE07A3C);
        thumbFan.setData(left, right);
        thumbFan.setMinimal(mockHome());
        // While a fan is open, slide the dock out of the way (it lives low-centre, where the right
        // fan blooms) — MacBook-style auto-hide.
        thumbFan.setActiveListener(active -> {
            if (recentRail == null) return;
            recentRail.animate().cancel();
            recentRail.animate().alpha(active ? 0f : 1f).translationY(active ? dp(28) : 0)
                .setDuration(active ? 140 : 200).start();
        });
        root.addView(thumbFan, new FrameLayout.LayoutParams(-1, -1));
    }
    private Drawable dr(int res) { try { return getDrawable(res); } catch (RuntimeException e) { return null; } }
    private void startAct(Class<?> cls) { try { startActivity(new Intent(this, cls)); } catch (RuntimeException ignored) { } }
    private void safe(Intent i) { try { startActivity(i); } catch (RuntimeException ignored) { } }

    /** A tap on the dock at horizontal position x launches whichever recent icon sits there. */
    private void launchRailAt(float x) {
        if (recentRail == null) return;
        for (int i = 0; i < recentRail.getChildCount(); i++) {
            View c = recentRail.getChildAt(i);
            if (x >= c.getLeft() && x <= c.getRight() && c.getTag() instanceof AppEntry) {
                launch((AppEntry) c.getTag());
                return;
            }
        }
    }

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
                    if (recent.size() >= Math.max(8, prefs.getInt("recentCount", 8))) break;
                }
            }
            handler.post(() -> {
                // After a restart (theme change …) first the row as it was, without a fade; then only
                // what really changed moves (see updateRecentCarousel).
                if (recentHome.getChildCount() == 0 || recentCarousel == null) {
                    List<AppEntry> cached = new ArrayList<>();
                    for (String pkg : prefs.getString("recentOrder", "").split(",")) {
                        AppEntry a = byPackage.get(pkg);
                        if (a != null) cached.add(a);
                    }
                    if (!cached.isEmpty()) {
                        renderRecentCarousel(cached);
                        recentHome.animate().cancel();
                        recentHome.setAlpha(1f);
                    }
                }
                boolean same = keys(recent).equals(keys(lastRecent)) && recentCarousel != null;
                lastRecent = recent;
                refreshRecentRail();
                prefs.edit().putString("recentOrder", android.text.TextUtils.join(",", keys(recent))).apply();
                if (dashboard != null) dashboard.refreshFolders();
                if (same) return;               // nothing changed: leave the row alone
                if (!updateRecentCarousel(recent)) renderRecentCarousel(recent);
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

    /* ---- Zuletzt carousel: N recent apps (setting), a page per row, dots show where you are ---- */

    private android.widget.HorizontalScrollView recentCarousel;
    private int carouselWidth, carouselCols;
    private LinearLayout recentDots;

    private static List<String> keys(List<AppEntry> l) {
        List<String> k = new ArrayList<>();
        for (AppEntry a : l) k.add(a.component.getPackageName());
        return k;
    }

    /** Which app each Zuletzt tile shows (for the move animation). */
    private final Map<View, String> recentTilePkg = new java.util.WeakHashMap<>();

    /**
     * Only what changed: tiles that stay slide to their new place with a card flip (like an UNO
     * reverse), a new app pops in with a yellow flash. False = the row has to be built anew.
     */
    private boolean updateRecentCarousel(List<AppEntry> recent) {
        if (recentCarousel == null || recentCarousel.getChildCount() == 0) return false;
        LinearLayout strip = (LinearLayout) recentCarousel.getChildAt(0);
        int n = Math.min(prefs.getInt("recentCount", 8), recent.size());
        if (strip.getChildCount() != n || n == 0) return false;            // other page count: dots change too
        Map<String, View> old = new HashMap<>();
        Map<String, Integer> oldLeft = new HashMap<>();
        for (int i = 0; i < strip.getChildCount(); i++) {
            View v = strip.getChildAt(i);
            String pkg = recentTilePkg.get(v);
            if (pkg == null) return false;
            old.put(pkg, v);
            oldLeft.put(pkg, v.getLeft());
        }
        int width = content.getWidth() > 0 ? content.getWidth() - content.getPaddingLeft() - content.getPaddingRight()
            : getResources().getDisplayMetrics().widthPixels - dp(44);
        int gap = dp(8), tileW = (width - (cols() - 1) * gap) / cols();
        strip.removeAllViews();
        List<View> fresh = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AppEntry app = recent.get(i);
            String pkg = app.component.getPackageName();
            View tile = old.get(pkg);
            if (tile == null) {
                tileTint = GREEN;
                tile = tile(app.label, app.icon, 32);
                tileTint = 0;
                tile.setOnClickListener(v -> launch(app));
                recentTilePkg.put(tile, pkg);
                fresh.add(tile);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, -2);
            lp.leftMargin = i == 0 ? 0 : gap;
            strip.addView(tile, lp);
        }
        recentCarousel.scrollTo(0, 0);
        strip.post(() -> {
            int order = 0;
            for (int i = 0; i < strip.getChildCount(); i++) {
                View v = strip.getChildAt(i);
                String pkg = recentTilePkg.get(v);
                if (fresh.contains(v)) {
                    // The new one: pops up with a yellow flash.
                    v.setScaleX(0.4f);
                    v.setScaleY(0.4f);
                    v.setAlpha(0f);
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(160).setDuration(420)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
                    GradientDrawable glow = new GradientDrawable();
                    glow.setColor(0x00000000);
                    glow.setStroke(dp(3), 0xFFE8B931);
                    glow.setCornerRadius(dp(22));
                    v.setForeground(glow);
                    android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(255, 0);
                    fade.setStartDelay(500);
                    fade.setDuration(1100);
                    fade.addUpdateListener(an -> glow.setAlpha((int) an.getAnimatedValue()));
                    fade.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator a) {
                            v.setForeground(null);
                        }
                    });
                    fade.start();
                    continue;
                }
                Integer was = oldLeft.get(pkg);
                if (was == null || was == v.getLeft()) continue;
                // Moved: slides over from its old place and flips once on the way.
                v.setTranslationX(was - v.getLeft());
                v.setCameraDistance(dp(1200));
                v.setRotationY(0);
                v.animate().translationX(0).rotationY(360).setStartDelay(order++ * 60L).setDuration(560)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .withEndAction(() -> v.setRotationY(0)).start();
            }
        });
        return true;
    }

    private void renderRecentCarousel(List<AppEntry> recent) {
        boolean first = recentHome.getChildCount() == 0;
        if (first && !recent.isEmpty()) {
            recentHome.setAlpha(0f);
            recentHome.animate().alpha(1f).setDuration(220).start();
        }
        recentHome.post(() -> {
            int h = recentHome.getHeight();
            if (h > 0 && !recent.isEmpty()) prefs.edit().putInt("recentH", h).apply();
        });
        recentHome.removeAllViews();
        recentCarousel = null;
        if (recent.isEmpty()) {
            renderRecents(recentHome, recent, cols());
            return;
        }
        int cols = cols(), n = Math.min(prefs.getInt("recentCount", 8), recent.size());
        int pages = (n + cols - 1) / cols;
        int width = content.getWidth() > 0 ? content.getWidth() - content.getPaddingLeft() - content.getPaddingRight()
            : getResources().getDisplayMetrics().widthPixels - dp(44);
        int gap = dp(8), tileW = (width - (cols - 1) * gap) / cols;
        carouselWidth = width;
        carouselCols = cols;

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout strip = new LinearLayout(this);
        // Only real apps: the carousel ends when the last one reaches the right edge (no empty page).
        for (int i = 0; i < n; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, -2);
            lp.leftMargin = i == 0 ? 0 : gap;
            AppEntry app = recent.get(i);
            tileTint = GREEN;
            View tile = tile(app.label, app.icon, 32);
            tileTint = 0;
            tile.setOnClickListener(v -> launch(app));
            recentTilePkg.put(tile, app.component.getPackageName());
            strip.addView(tile, lp);
        }
        // Focus landing on a tile (e.g. when coming back home) made the carousel jump to it: never let
        // tiles take focus, and always start at the newest app.
        strip.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        hs.setFocusable(false);
        hs.addView(strip);
        recentHome.addView(hs);
        recentCarousel = hs;
        hs.post(() -> hs.scrollTo(0, 0));

        // Dots: one per page, the current one in rust.
        recentDots = new LinearLayout(this);
        recentDots.setGravity(Gravity.CENTER);
        for (int i = 0; i < pages; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(6), dp(6));
            dl.setMargins(dp(4), 0, dp(4), 0);
            recentDots.addView(d, dl);
        }
        recentHome.addView(recentDots, margins(-1, -2, 0, dp(10), 0, 0));
        recentDots.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
        int step = tileW + gap, pageW = cols * step;
        Runnable paint = () -> {
            int page = Math.min(pages - 1, Math.round(hs.getScrollX() / (float) pageW));
            int max = hs.getChildAt(0).getWidth() - hs.getWidth();
            if (max > 0 && hs.getScrollX() >= max - step / 2) page = pages - 1;   // the end shows the last dot
            for (int i = 0; i < recentDots.getChildCount(); i++) {
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(i == page ? rust : ink3);
                recentDots.getChildAt(i).setBackground(dot);
                recentDots.getChildAt(i).setAlpha(i == page ? 1f : 0.45f);
            }
        };
        paint.run();
        // Let a swipe glide out, then settle softly on the nearest app (one app at a time, not a whole row).
        final boolean[] touching = {false};
        Runnable settle = () -> {
            if (touching[0]) return;
            int max = Math.max(0, hs.getChildAt(0).getWidth() - hs.getWidth());
            int target = Math.min(max, Math.round(hs.getScrollX() / (float) step) * step);
            if (target != hs.getScrollX()) hs.smoothScrollTo(target, 0);
        };
        hs.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            paint.run();
            hs.removeCallbacks(settle);
            hs.postDelayed(settle, 90);
        });
        hs.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            touching[0] = a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE;
            if (!touching[0]) {
                hs.removeCallbacks(settle);
                hs.postDelayed(settle, 90);
            }
            return false;
        });
    }

    private boolean onView(View v, float rawX, float rawY) {
        if (!v.isShown()) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return rawX >= at[0] && rawX <= at[0] + v.getWidth() && rawY >= at[1] && rawY <= at[1] + v.getHeight();
    }

    private void showRecentsPrompt(LinearLayout into) {
        into.removeAllViews();
        TextView ask = text(13, ink, true);
        ask.setText("Zuletzt verwendete Apps anzeigen →");
        ask.setPadding(dp(14), dp(12), dp(14), dp(12));
        ask.setBackground(box(card, line, 16));
        ask.setOnClickListener(v -> {
            pop("ARC-DNA antippen und Zugriff erlauben");
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
        if (action == MotionEvent.ACTION_DOWN) {
            tapX = ev.getRawX();
            tapY = ev.getRawY();
            downAt = lastTouch;
        }
        if (ghosts != null) ghosts.touch(ev);
        if (fox != null) {
            boolean down = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
            fox.finger(ev.getRawX(), ev.getRawY(), down);
        }
        if (libraryOpen && headSpeed != 0) {
            headSpeed = 0;          // the finger takes over at once; head scrolling rests for 4 s
            handler.removeCallbacks(headScroller);
        }
        // No home gestures while the dino is on screen: flame-thrower and sabre strokes ran in circles and turned the screen.
        boolean gestures = !libraryOpen && settingsSheet == null && calibStage < 0 && game == null && dino == null;
        if (action == MotionEvent.ACTION_DOWN) {
            strokeMulti = false;
            circling = false;
            pathTurn = 0;
            pathLen = 0;
            lastHeading = Float.NaN;
            downX = lastPX = ev.getX();
            downY = lastPY = ev.getY();
            boxL = boxR = downX;
            boxT = boxB = downY;
            downTime = ev.getEventTime();
            strokeOnSlider = music != null && music.isOnSlider(ev.getRawX(), ev.getRawY());
            strokeOnCard = !strokeOnSlider && music != null && music.isOnCard(ev.getRawX(), ev.getRawY());
            cardFlipped = false;
            // The Zuletzt carousel scrolls sideways itself: no dark / light swipe from there.
            // The mode chips scroll sideways themselves too: no dark / light swipe (and no ghosts) from there.
            strokeOnCarousel = recentCarousel != null && onView(recentCarousel, ev.getRawX(), ev.getRawY())
                || modeBarView != null && onView(modeBarView, ev.getRawX(), ev.getRawY());
            strokeOnLever = lever != null && lever.isShown() && onView(lever, ev.getRawX(), ev.getRawY());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            strokeMulti = true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int h = 0; h < ev.getHistorySize(); h++) addPoint(ev.getHistoricalX(h), ev.getHistoricalY(h));
            addPoint(ev.getX(), ev.getY());
            // Sideways on the music card: player ↔ stats page.
            if (strokeOnCard && !cardFlipped && !circling) {
                float sx = ev.getX() - downX, sy = ev.getY() - downY;
                if (Math.abs(sx) > dp(48) && Math.abs(sx) > 2 * Math.abs(sy)) {
                    cardFlipped = true;
                    music.flip(sx < 0 ? -1 : 1);
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
            }
            if (cardFlipped) return true;
            // No circle-to-rotate in the Geister-Level: there the finger roams around as a torch.
            if (gestures && ghosts == null && !strokeMulti && !circling && !strokeOnSlider
                && Math.abs(pathTurn) > Math.toRadians(150) && pathLen > dp(120)
                && boxR - boxL > dp(60) && boxB - boxT > dp(60)) {          // a real loop is wide and tall
                circling = true;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
            if (circling) return true;
        } else if (action == MotionEvent.ACTION_UP) {
            addPoint(ev.getX(), ev.getY());
            if (cardFlipped) return true;
            if (circling) {
                circling = false;
                if (Math.abs(pathTurn) >= Math.toRadians(280)) rotateStep(pathTurn > 0 ? 1 : -1);
                return true;
            }
            float dx = ev.getX() - downX, dy = ev.getY() - downY;
            if (gestures && !strokeMulti && !strokeOnSlider && !strokeOnCard && !strokeOnCarousel
                && Math.abs(dx) > rootFrame.getWidth() * 0.4f && Math.abs(dy) < Math.abs(dx) * 0.4f
                && ev.getEventTime() - downTime < 800) {
                // Horizontal swipe opens an app (the old dark/light toggle is gone):
                // right → left = Mediathek, left → right = Globus.
                if (dx < 0) openLibrary();
                else startActivity(new Intent(this, GlobusActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            // Down from the top-right corner: Energie drops in like a control centre.
            if (gestures && !strokeMulti && !strokeOnLever && downX > rootFrame.getWidth() * 0.6f && downY < rootFrame.getHeight() * 0.2f
                && dy > dp(110) && Math.abs(dx) < dy && ev.getEventTime() - downTime < 700) {
                DiagLog.log("corner swipe: energy");
                try {
                    startActivity(new Intent(this, EnergyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
                return super.dispatchTouchEvent(ev);
            }
            // A strong, straight vertical swipe over at least half the screen: skip the home, straight
            // to the app globe.
            if (gestures && !strokeMulti && !strokeOnSlider && Math.abs(dy) > rootFrame.getHeight() * 0.5f
                && Math.abs(dx) < Math.abs(dy) * 0.35f && ev.getEventTime() - downTime < 450) {
                DiagLog.log("strong swipe: app globe");
                try {
                    startActivity(new Intent(this, GlobusActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            circling = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private float boxL, boxR, boxT, boxB;
    private TextView lowChip;
    private final android.content.BroadcastReceiver lowWatch = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            LowPower.stale();
            boolean low = LowPower.active(MainActivity.this);
            if (lowChip != null) lowChip.setVisibility(low ? View.VISIBLE : View.GONE);
            if (low && head != null && libraryOpen) stopHead();
        }
    };

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
            // A sudden about-turn is a scroll changing direction, not a circle: start counting again
            // (it read as a circle, cancelled the scroll and held the finger until it let go).
            if (Math.abs(t) > Math.toRadians(100)) pathTurn = 0;
            else pathTurn += t;  // screen y points down, so a positive turn looks clockwise
        }
        boxL = Math.min(boxL, x);
        boxR = Math.max(boxR, x);
        boxT = Math.min(boxT, y);
        boxB = Math.max(boxB, y);
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
    /* ================= Geister-Level ================= */

    private GhostView ghosts;

    /** Almost black, ghosts drifting; the finger is a torch over the apps. Above the page, below the fox. */
    private void enterGhosts() {
        ghosts = new GhostView(this);
        // Above the page and the light fades behind the status / nav bar (so it's black edge to edge),
        // below the fox, rabbit button and seismograph.
        int above = Math.max(rootFrame.indexOfChild(topScrim), rootFrame.indexOfChild(bottomScrim));
        rootFrame.addView(ghosts, Math.max(rootFrame.indexOfChild(scroll), above) + 1, new FrameLayout.LayoutParams(-1, -1));
        systemBarIcons(false);
        ghostBrightness();
        ghosts.start();
        voice.say("Whoa-whoa!");
        hop();
        say("Geister-Level 👻 – Finger = Taschenlampe. Hebel hoch oder → wischen = raus.", 2600);
        if (lever != null) lever.setLevel(0, true);
        DiagLog.log("ghost level on");
    }

    /** The navigation bar stays hidden on the home screen until you swipe it up from the edge. */
    private void hideNavBar() {
        if (Build.VERSION.SDK_INT < 30 || getWindow().getInsetsController() == null) return;
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(android.view.WindowInsets.Type.navigationBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) maybePlayUnlock();
        if (hasFocus) hideNavBar();
        if (hasFocus && ghosts == null) systemBarIcons(paperIsLight());     // Neon is dark even by day
    }

    private boolean paperIsLight() {
        return android.graphics.Color.luminance(paper()) > 0.5f;
    }

    /** Dark icons on a light screen, light icons on the dark one (and in the Geister-Level). */
    private void systemBarIcons(boolean dark) {
        if (Build.VERSION.SDK_INT < 30 || getWindow().getInsetsController() == null) return;
        int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(dark ? bits : 0, bits);
    }

    private void exitGhosts() {
        GhostView g = ghosts;
        ghosts = null;
        if (g == null) return;
        g.stop(() -> rootFrame.removeView(g));
        // Leaving the darkest level: back to LO, unless the lever is about to set another level.
        if (Settings.System.canWrite(this)
            && Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 3) <= 1) {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, BRIGHTNESS[0]);
        }
        systemBarIcons(paperIsLight());
        say("Licht an. Die Geister sind weg.", 1600);
        syncLever();
    }

    private void switchNight(boolean dark, float x, float y) {
        if (Build.VERSION.SDK_INT < 31) {
            pop("Braucht Android 12 oder neuer.");
            return;
        }
        // Already dark: one more ← opens the Geister-Level; → leaves it first.
        if (!dark && ghosts != null) {
            exitGhosts();
            return;
        }
        if (dark && isNight()) {
            if (ghosts == null) enterGhosts();
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
                    applyNight(dark);
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

    /** Drive the REAL system dark mode (one switch: the launcher toggle and the phone's native toggle
     *  become the same). Needs WRITE_SECURE_SETTINGS — the manifest declares it; grant via adb if the
     *  phone hasn't. Falls back to the app-only override (which does NOT follow the native toggle). */
    private void applyNight(boolean dark) {
        try {
            // Write the real system night setting; UiModeManagerService observes it and re-themes.
            android.provider.Settings.Secure.putInt(getContentResolver(), "ui_night_mode", dark ? 2 : 1);  // 2=yes 1=no
        } catch (Exception e) {
            setAppNight(dark ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_NO);
            pop("Für echten System-Dark: WRITE_SECURE_SETTINGS per ARC Link freigeben.");
        }
    }

    /** Clockwise: portrait → landscape → upside down → other landscape → portrait; counter-clockwise backwards. */
    private void rotateStep(int dir) {
        int i = (prefs.getInt("orient", 0) + dir + 4) % 4;
        prefs.edit().putInt("orient", i).commit();
        hop();
        voice.say(dir > 0 ? "Yip-yip!" : "Mrrp?");
        handler.postDelayed(this::applyOrientation, 180);
    }

    /*
     * Screen turning: phones honour the requested orientation, but many refuse upside down (180°),
     * and tablets ignore it and letterbox the home into a narrow strip. So: ask the system where it
     * helps, then check where the screen really is and turn the home's own content for the rest.
     */
    private int softDeg;
    private final Runnable softCheck = this::checkSoftRotation;

    private boolean largeScreen() {
        return getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private void applyOrientation() {
        if (game != null) return;
        int i = prefs.getInt("orient", 0);
        setRequestedOrientation(largeScreen() ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : ORIENTATIONS[i]);
        handler.removeCallbacks(softCheck);
        handler.postDelayed(softCheck, 700);
    }

    /** Where the screen really is, as 0 / 90 / 180 / 270 in the same steps as ORIENTATIONS. */
    private int actualAngle() {
        int rot = getDisplay() != null ? getDisplay().getRotation() : 0;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getDisplay().getRealMetrics(dm);
        boolean wide = dm.widthPixels > dm.heightPixels;
        boolean natPortrait = (rot == 0 || rot == 2) != wide;
        return natPortrait ? rot * 90 : (rot * 90 + 90) % 360;
    }

    private void checkSoftRotation() {
        if (game != null || rootFrame == null) {
            setSoftRotation(0);
            return;
        }
        int want = prefs.getInt("orient", 0) * 90;
        setSoftRotation(((want - actualAngle()) % 360 + 360) % 360);
    }

    private void setSoftRotation(int deg) {
        View parent = (View) rootFrame.getParent();
        if (parent == null) return;
        int w = parent.getWidth(), h = parent.getHeight();
        if (w == 0 || h == 0) {
            parent.post(() -> setSoftRotation(deg));
            return;
        }
        boolean changed = deg != softDeg;
        softDeg = deg;
        android.view.ViewGroup.LayoutParams lp = rootFrame.getLayoutParams();
        if (deg == 90 || deg == 270) {
            lp.width = h;
            lp.height = w;
            rootFrame.setPivotX(h / 2f);
            rootFrame.setPivotY(w / 2f);
            rootFrame.setTranslationX((w - h) / 2f);
            rootFrame.setTranslationY((h - w) / 2f);
        } else {
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            rootFrame.setPivotX(w / 2f);
            rootFrame.setPivotY(h / 2f);
            rootFrame.setTranslationX(0);
            rootFrame.setTranslationY(0);
        }
        rootFrame.setRotation(deg);
        rootFrame.setLayoutParams(lp);
        if (changed) rootFrame.post(this::reflow);
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
        hint.setText("Pro App antippen: AUS ↔ NICKEN. In der App zeigt ein oranger Ring um die Frontkamera den Zustand – 3× tippen = Pause. "
            + "Kalibrieren: ⚙ Einstellungen → Kopf-Scroll → Nicken kalibrieren.");
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(0, 0, 0, dp(10));
        list.addView(hint);

        // Main switch: off by default. While off, the service never starts the camera in another app.
        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        mainRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        mainRow.setBackground(box(card, rust, 16));
        LinearLayout mainTexts = new LinearLayout(this);
        mainTexts.setOrientation(LinearLayout.VERTICAL);
        TextView mainName = text(14, ink, true);
        mainName.setText("Kopf-Scroll in anderen Apps");
        mainTexts.addView(mainName);
        TextView mainHint = text(11, ink3, false);
        mainHint.setText("Aus = die Kamera startet nie ausserhalb von ARC-DNA, egal was unten steht. "
            + "An = nur in den Apps, die du unten wählst; Android zeigt dann den grünen Kamera-Punkt.");
        mainHint.setLineSpacing(0, 1.15f);
        mainTexts.addView(mainHint);
        mainRow.addView(mainTexts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView mainChip = monoText(12, ink2);
        mainChip.setPadding(dp(12), dp(7), dp(12), dp(7));
        Runnable paintMain = () -> {
            boolean on = prefs.getBoolean("scrollOutside", false);
            mainChip.setText(on ? "AN" : "AUS");
            mainChip.setTextColor(on ? 0xFFFFFFFF : ink2);
            mainChip.setBackground(on ? box(rust, rust, 20) : box(card, line, 20));
        };
        paintMain.run();
        mainChip.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("scrollOutside", false);
            prefs.edit().putBoolean("scrollOutside", on).apply();
            DiagLog.log("scroll outside the launcher: " + (on ? "allowed" : "off"));
            paintMain.run();
        });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.bottomMargin = dp(12);
        mainRow.addView(mainChip);
        list.addView(mainRow, ml);

        // One switch for every app (also ones installed later); single apps can still differ.
        LinearLayout allRow = new LinearLayout(this);
        allRow.setGravity(Gravity.CENTER_VERTICAL);
        allRow.setPadding(0, dp(4), 0, dp(10));
        TextView allName = text(14, ink, true);
        allName.setText("Alle Apps");
        allRow.addView(allName, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView allChip = monoText(11, ink2);
        allChip.setPadding(dp(12), dp(7), dp(12), dp(7));
        allChip.setBackground(box(card, rust, 20));
        paintAppScroll(allChip, prefs.getString("appScrollAll", "off"));
        allRow.addView(allChip);
        list.addView(allRow);
        allChip.setOnClickListener(v -> {
            String now = prefs.getString("appScrollAll", "off");
            String next = now.equals("off") ? "nod" : "off";
            SharedPreferences.Editor e = prefs.edit().putString("appScrollAll", next);
            for (String k : prefs.getAll().keySet()) if (k.startsWith("appScroll:")) e.remove(k);   // everyone follows
            e.apply();
            if (!next.equals("off") && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            showAppScrollSettings();          // the sheet opens anew (the old one closes)
        });

        java.util.Set<String> seen = new java.util.HashSet<>();
        List<AppEntry> sorted = new ArrayList<>(apps);
        // Apps already switched on first, then alphabetical.
        Collections.sort(sorted, (a, b) -> {
            boolean ao = !appScrollMode(a.component.getPackageName()).equals("off");
            boolean bo = !appScrollMode(b.component.getPackageName()).equals("off");
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
            paintAppScroll(chip, appScrollMode(pkg));
            chip.setOnClickListener(v -> {
                String now = appScrollMode(pkg);
                String next = now.equals("off") ? "nod" : "off";
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
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(dp(420), (int) (rootFrame.getHeight() * 0.55f))));
        arcSheet("Kopf-Scroll pro App", "Wo soll Nicken scrollen?", null, sv, new String[]{"Fertig"}, i -> { });
    }

    /** The app's own setting, else the "Alle Apps" switch. */
    private String appScrollMode(String pkg) {
        String m = prefs.getString("appScroll:" + pkg, prefs.getString("appScrollAll", "off"));
        return m.equals("gaze") ? "nod" : m;
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
        // Auto-update: when a newer GitHub version is ready, pull & install it in the background —
        // a small delta patch, silent on Android 12+ once ARC-DNA is the installer of record.
        if (available && prefs.getBoolean("autoUpdate", true)) updater.install();
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
        input.setTextColor(ink);
        input.setHintTextColor(ink3);
        arcSheet("Updates einrichten", "Token einfügen",
            "GitHub → Fine-grained token: Resource owner architect-dna-ch, nur „arc-dna-home“, Contents: Read-only. "
                + "Er bleibt nur auf diesem Handy.", wrap,
            new String[]{"Speichern & prüfen", "Token auf GitHub erstellen", "Abbrechen"}, i -> {
                if (i == 0) {
                    updater.setToken(input.getText().toString());
                    updater.check(true);
                } else if (i == 1) {
                    open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")));
                }
            });
    }

    /* ================= Head / gaze scrolling ================= */

    /**
     * "off" or "nod" (head tilt). BLICK (eyes) is gone: a phone camera sees the eyes too coarsely
     * to steer reliably; an old "gaze" setting now means NICKEN. Older versions stored a boolean.
     */
    private String headMode() {
        String m = prefs.getString("headMode", null);
        if (m == null) m = prefs.getBoolean("headScroll", false) ? "nod" : "off";
        return m.equals("gaze") ? "nod" : m;
    }

    /** Toggle in the Mediathek: AUS ↔ NICKEN. */
    private void cycleHeadMode() {
        String m = headMode();
        String next = m.equals("off") ? "nod" : "off";
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
        if (LowPower.active(this)) {
            pop("🔋 Unter " + LowPower.LIMIT + " % – Kopf-Scroll pausiert, bis du lädst");
            return;
        }
        faceWatch = false;          // head scroll takes the camera over
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (head == null) head = new HeadTracker(this, this);
        calibFrames = 0;
        calibSum = 0;
        smoothTilt = m.equals("gaze") ? prefs.getFloat("gazeMid", 0f) : 0;
        boolean gaze = m.equals("gaze");
        // NICKEN calibrates on every start (can be switched off in ⚙); the calibration shows the live camera.
        boolean willCalib = gaze ? !prefs.contains("gazeMid")
            : !prefs.contains("nodMid");      // later starts just re-centre quietly on your current posture
        boolean dbg = prefs.getBoolean("camDebug", false);
        head.stop();
        head.setDebug(dbg || willCalib);
        headDbgOn = dbg || willCalib;
        head.start(gaze ? HeadTracker.Mode.GAZE : HeadTracker.Mode.NOD);
        if (dbg && headDebug == null) {
            headDebug = new HeadDebugView(this, rust);
            int top = Build.VERSION.SDK_INT >= 30 && rootFrame.getRootWindowInsets() != null
                ? rootFrame.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars()).top : dp(24);
            // A wide strip along the top (over search and header), so the apps stay free.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(140), Gravity.TOP);
            lp.setMargins(dp(12), top + dp(4), dp(12), 0);
            rootFrame.addView(headDebug, lp);
        }
        if (!pro()) edgeLight.watch(true);
        showHeadState(0);
        headLabel.setVisibility(View.VISIBLE);
        if (willCalib) startGazeCalibration();
        else if (!gaze && !prefs.getBoolean("nodAlwaysCalib", true)) {
            // Re-centring switched off: start from the saved neutral position.
            basePitch = prefs.getFloat("nodMid", 0f);
            calibFrames = 6;
        }
    }

    private void stopHead() {
        faceWatch = false;
        endGazeCalibration();
        if (head != null) head.stop();
        if (headDebug != null) {
            rootFrame.removeView(headDebug);
            headDebug = null;
        }
        if (edgeLight != null) edgeLight.watch(false);
        headSpeed = 0;
        handler.removeCallbacks(headScroller);
        headLabel.setVisibility(View.GONE);
        handler.postDelayed(this::startFaceWatch, 800);     // back on the home: keep watching for the dino
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
        AlarmScheduler.Deadline next = AlarmScheduler.nextDeadline(this);
        String nd = next == null ? "" : "\n🐻 " + new java.text.SimpleDateFormat("EEE HH:mm", Locale.GERMAN).format(new java.util.Date(next.at)).toUpperCase(Locale.GERMAN)
            + " · " + next.label.toUpperCase(Locale.GERMAN);
        alarmRow.setText((on ? "WECKER · BÄR AUS DER HÖHLE" : "WECKER AUS · TIPPEN = BÄR-WECKER") + nd);
        alarmRow.setTextColor(on ? rust : ink3);
        if (alarmTime != null) {
            alarmTime.setText(t);
            alarmTime.setTextColor(on ? ink : ink3);
            alarmCard.setBackground(box(card, on ? rust : line, 16));
            alarmCard.setAlpha(on ? 1f : 0.75f);
        }
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
            pop("Wecker aus. Der Bär schläft.");
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
        arcSheet("Bär-Wecker", "🐻 Über dem Sperrbildschirm",
            "Damit der Wecker bei gesperrtem Handy im Vollbild klingelt, erlaube „Vollbild-Benachrichtigungen“ für ARC-DNA. "
                + "Android nimmt das nach jedem Update wieder weg.", null,
            new String[]{"Jetzt erlauben", "Später"}, i -> {
                if (i != 0) return;
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    pop("Einstellungen → Apps → ARC-DNA → Vollbild-Benachrichtigungen");
                }
            });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFY) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) checkFullScreenAccess();
            else pop("Ohne Benachrichtigungen klingelt der Bär nur, wenn ARC-DNA offen ist.");
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
            pop("Ohne Kamera kein Kopf-Scroll.");
        }
    }

    /**
     * NICKEN: the first frames set the neutral head position. How far "up" and "down" go comes
     * from your own calibration (long-press the toggle): scrolling starts at half of your nod and
     * keeps going until you're back within 30 %, full speed at your full nod. The neutral point
     * follows your posture only slowly (~15 s) while you hold still, so a slow nod isn't swallowed.
     */
    @Override
    public void onTilt(float pitch) {
        lastFaceEvent = android.os.SystemClock.uptimeMillis();
        if (faceWatch) {
            if (edgeLight != null) edgeLight.face(true);
            return;                 // only watching whether you face the screen; no scrolling
        }
        if (edgeLight != null) edgeLight.face(true);
        if (calibStage >= 0) {
            if (calibCollect && calibStage < 3) {
                calibAcc[calibStage] += pitch;
                calibN[calibStage]++;
            }
            calibFollow(pitch);
            return;
        }
        if (fox != null && calibFrames >= 6) fox.userTilt(pitch - basePitch);
        if (game != null) {
            gameNod(pitch);
            return;
        }
        if (calibFrames < 6) {
            calibSum += pitch;
            if (++calibFrames == 6) basePitch = calibSum / 6f;
            return;
        }
        // Head turned aside: its pitch reading goes wild (the recording showed a sideways turn as a 95 % "nod").
        if (Math.abs(lastYaw) > 18) {
            setHeadSpeed(0);
            return;
        }
        float delta = pitch - basePitch;
        smoothTilt = smoothTilt * 0.5f + delta * 0.5f;
        float frac = nodFraction(prefs, smoothTilt);
        boolean sameWay = headSpeed != 0 && Math.signum(headSpeed) == (smoothTilt > 0 ? -1 : 1);
        if (frac < (sameWay ? 0.4f : 0.6f)) {
            if (frac < 0.4f) basePitch = basePitch * 0.997f + pitch * 0.003f;
            setHeadSpeed(0);
            return;
        }
        float speed = nodSpeed(frac) * dp(40);
        setHeadSpeed(smoothTilt > 0 ? -speed : speed); // looking up scrolls up
    }

    /**
     * Speed from how far you tilt (0.08 … 1): just past the calm zone it creeps, at your full
     * calibrated nod it's about half speed, tilting further (1.3×) gives full speed. Quadratic, so
     * small changes near the middle stay gentle.
     */
    static float nodSpeed(float frac) {
        float k = Math.max(0f, Math.min(1f, (frac - 0.4f) / 0.9f));
        return 0.08f + 0.92f * k * k;
    }

    /** How far along your own calibrated nod the tilt is (0 = neutral, 1 = your full nod). */
    static float nodFraction(SharedPreferences p, float tilt) {
        float mid = p.getFloat("nodMid", 0f);
        // At least 5° each way, so a tiny calibrated nod doesn't make scrolling twitchy.
        // Capped at 10° each way: a wide calibration made a normal ~8° nod read only 30 % (debug recording).
        float up = Math.min(10f, Math.max(5f, p.getFloat("nodUp", mid + 8f) - mid));
        float down = Math.min(10f, Math.max(5f, mid - p.getFloat("nodDown", mid - 8f)));
        return tilt > 0 ? tilt / up : -tilt / down;
    }

    /**
     * BLICK: three zones from the user's own calibration (top / middle / bottom of the screen).
     * Starting needs the eyes 55 % of the way from the middle to the top or bottom point; once
     * scrolling it keeps going until they are back within 35 %, so the wide middle band is a
     * calm "stop" area and the edges don't flicker. Speed grows a little the further you look.
     */
    @Override
    public void onGaze(float vertical) {
        if (edgeLight != null) edgeLight.face(true);
        if (calibStage >= 0) {
            if (calibCollect && calibStage < 3) {
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
        float threshold = sameWay ? 0.45f : 0.7f;
        if (frac < threshold) {
            setHeadSpeed(0);
            return;
        }
        float speed = (0.4f + 0.6f * Math.min(1f, (frac - 0.45f) / 0.55f)) * dp(9);
        setHeadSpeed(dir * speed); // eyes down scrolls down, eyes up scrolls up
    }

    /* ---------- Gaze calibration: a dot at the top, middle and bottom ---------- */

    private void startGazeCalibration() {
        handler.removeCallbacksAndMessages(calibToken);
        calibStage = 0;
        calibAcc = new float[3];
        calibN = new int[3];
        calibRetries = 0;
        calibRef = Float.NaN;
        setHeadSpeed(0);
        calibResult.setVisibility(View.GONE);
        calibDot.setVisibility(View.VISIBLE);
        calibCursor.setVisibility(View.VISIBLE);
        calibLive.setText("");
        // The live camera with the tracked lines, in the corner of the calibration screen.
        if (head != null && !headDbgOn) {
            head.stop();
            head.setDebug(true);
            headDbgOn = true;
            head.start(headMode().equals("gaze") ? HeadTracker.Mode.GAZE : HeadTracker.Mode.NOD);
        }
        calibOverlay.setVisibility(View.VISIBLE);
        calibOverlay.setAlpha(0f);
        calibOverlay.animate().alpha(1f).setDuration(200).start();
        runCalibStage();
    }

    private void runCalibStage() {
        int stage = calibStage;
        String[] where = {"oben", "in der Mitte", "unten"};
        boolean nod = headMode().equals("nod");
        calibText.setText(nod
            ? new String[]{"Nick mit dem Kopf zum Punkt oben – so weit wie du später scrollen willst", "Kopf ganz normal halten", "Nick mit dem Kopf zum Punkt unten"}[stage]
            : "Schau auf den Punkt " + where[stage]);
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
        if (headMode().equals("nod")) {
            float up = calibN[0] > 0 ? calibAcc[0] / calibN[0] : 8f;
            float mid = calibN[1] > 0 ? calibAcc[1] / calibN[1] : 0f;
            float down = calibN[2] > 0 ? calibAcc[2] / calibN[2] : -8f;
            // The neutral reading often drifts (you settle a bit up or down); what matters is that up and
            // down are clearly apart. Then the neutral point goes back between them if it slipped outside.
            if (up - down >= 4f && (mid > up - 2f || mid < down + 2f)) mid = (up + down) / 2f;
            if (calibN[0] < 5 || calibN[2] < 5 || up - down < 4f) {
                // Hardly any movement (or no face) between the dots: don't save a useless calibration.
                DiagLog.log(String.format(Locale.ROOT, "nod calibration rejected: up %+.1f°, down %+.1f°", up - mid, down - mid));
                calibText.setText("Kein Nicken erkannt – deutlicher nicken");
                calibLive.setText(String.format(Locale.ROOT, "OBEN %+.1f°  ·  UNTEN %+.1f°", up - mid, down - mid));
                showCalibButtons(false, null);
                return;
            }
            showCalibResult(up, mid, down);
            return;
        }
        float top = calibN[0] > 0 ? calibAcc[0] / calibN[0] : -0.1f;
        float mid = calibN[1] > 0 ? calibAcc[1] / calibN[1] : 0f;
        float bottom = calibN[2] > 0 ? calibAcc[2] / calibN[2] : 0.1f;
        prefs.edit().putFloat("gazeTop", top).putFloat("gazeMid", mid).putFloat("gazeBottom", bottom).apply();
        endGazeCalibration();
        smoothTilt = mid;
        pop("Blick kalibriert – Neu kalibrieren: Knopf lange drücken");
    }

    /** Manual check before saving: your three positions, then ✓ Übernehmen or ↻ Nochmal. */
    private void showCalibResult(float up, float mid, float down) {
        calibStage = 3;              // results on screen: the cursor keeps following, nothing is sampled
        calibCollect = false;
        calibText.setText("Passt das?");
        calibLive.setText(String.format(Locale.ROOT, "OBEN %+.1f°  ·  MITTE  ·  UNTEN %+.1f°", up - mid, down - mid));
        showCalibButtons(true, () -> {
            prefs.edit().putFloat("nodUp", up).putFloat("nodMid", mid).putFloat("nodDown", down).apply();
            DiagLog.log(String.format(java.util.Locale.ROOT, "nod calibrated: up %+.1f°, down %+.1f° from neutral", up - mid, down - mid));
            endGazeCalibration();
            basePitch = mid;
            calibFrames = 6;
            smoothTilt = 0;
            pop("Nicken übernommen");
        });
    }

    private void showCalibButtons(boolean canSave, Runnable save) {
        handler.removeCallbacksAndMessages(calibToken);
        calibStage = 3;
        calibDot.setVisibility(View.GONE);
        calibResult.removeAllViews();
        if (canSave) {
            TextView ok = text(15, card, true);
            ok.setText("✓ ÜBERNEHMEN");
            ok.setGravity(Gravity.CENTER);
            ok.setPadding(dp(20), dp(12), dp(20), dp(12));
            ok.setBackground(box(rust, rust, 24));
            ok.setOnClickListener(v -> save.run());
            calibResult.addView(ok);
        }
        TextView again = text(15, ink, true);
        again.setText("↻ NOCHMAL");
        again.setGravity(Gravity.CENTER);
        again.setPadding(dp(20), dp(12), dp(20), dp(12));
        again.setBackground(box(card, line, 24));
        again.setOnClickListener(v -> startGazeCalibration());
        calibResult.addView(again, margins(-2, -2, canSave ? dp(12) : 0, 0, 0, 0));
        calibResult.setVisibility(View.VISIBLE);
    }

    /** The ring follows your head live (relative to where you started), so you can see it meet the dots. */
    private void calibFollow(float pitch) {
        if (calibCursor == null) return;
        if (calibStage >= 1 && calibN[1] > 5) calibRef = calibAcc[1] / calibN[1];     // once known: your neutral
        else if (Float.isNaN(calibRef)) calibRef = pitch;
        float range = calibOverlay.getHeight() / 2f - dp(80);
        float y = Math.max(-range, Math.min(range, -(pitch - calibRef) / 10f * range));
        calibCursor.setTranslationY(y);
        if (calibStage < 3) calibLive.setText(String.format(Locale.ROOT, "KOPF %+.1f°", pitch - calibRef));
    }

    private void endGazeCalibration() {
        handler.removeCallbacksAndMessages(calibToken);
        boolean was = calibStage >= 0;
        calibStage = -1;
        calibCollect = false;
        if (was && head != null && headDbgOn && !prefs.getBoolean("camDebug", false) && libraryOpen && !headMode().equals("off")) {
            head.stop();
            head.setDebug(false);
            headDbgOn = false;
            head.start(headMode().equals("gaze") ? HeadTracker.Mode.GAZE : HeadTracker.Mode.NOD);
        }
        if (calibOverlay.getVisibility() == View.VISIBLE) {
            calibOverlay.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> calibOverlay.setVisibility(View.GONE)).start();
        }
    }

    private View buildCalibOverlay() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor((panel & 0x00FFFFFF) | 0xD9000000);    // see-through, the home stays visible behind
        o.setClickable(true);
        o.setVisibility(View.GONE);
        o.setOnClickListener(v -> {
            endGazeCalibration();
            pop("Kalibrierung abgebrochen");
        });

        calibDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(rust);
        calibDot.setBackground(dot);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
        dlp.setMargins(0, dp(70), 0, dp(70));
        o.addView(calibDot, dlp);

        calibText = text(15, ink, true);
        calibText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        tlp.topMargin = dp(70);
        o.addView(calibText, tlp);

        // The live head ring (follows your nod) and the readout under the instruction.
        calibCursor = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(2), ink);
        calibCursor.setBackground(ring);
        o.addView(calibCursor, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        calibLive = monoText(12, rust);
        calibLive.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        llp.topMargin = dp(150);
        o.addView(calibLive, llp);
        calibResult = new LinearLayout(this);
        calibResult.setGravity(Gravity.CENTER);
        calibResult.setVisibility(View.GONE);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        rlp.topMargin = dp(210);
        o.addView(calibResult, rlp);
        // Live camera with the tracked lines (top right).
        calibDebug = new HeadDebugView(this, rust);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(130), dp(130 * 4 / 3 + 64), Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(40), dp(12), 0);
        o.addView(calibDebug, clp);

        TextView skip = monoText(11, ink3);
        skip.setText("KALIBRIERUNG · DANEBEN TIPPEN = ABBRECHEN");
        skip.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        slp.topMargin = dp(120);
        o.addView(skip, slp);
        return o;
    }

    @Override
    public void onDebug(HeadTracker.Debug d) {
        if (calibStage >= 0 && calibDebug != null) calibDebug.update(d, d.face ? "GESICHT ✓" : "KEIN GESICHT");
        if (headDebug == null) return;
        String dec;
        if (calibStage >= 0) dec = "KALIBRIERT …";
        else if (!d.face) dec = "→ STOPP (KEIN GESICHT)";
        else {
            float frac = nodFraction(prefs, smoothTilt);
            dec = String.format(Locale.ROOT, "NICK %3.0f%%  %s", frac * 100,
                headSpeed < 0 ? "→ SCROLL ↑" : headSpeed > 0 ? "→ SCROLL ↓" : "→ STEHT");
        }
        headDebug.update(d, dec);
    }

    private HeadDebugView headDebug;

    @Override
    public void onLost() {
        if (edgeLight != null) edgeLight.face(false);
        setHeadSpeed(0);
        noteAway();
    }

    /** Facing the screen? Head turned more than ~28° aside or tipped far up / down (25°) counts as looking away. */
    private float facingPitch = Float.NaN, lastYaw;
    private long facingSince;
    private TextView awayChip;

    /** The look-away countdown: its own chip at the top centre (it used to squeeze the header). */
    private void showAwayChip(String text) {
        if (awayChip == null) {
            awayChip = monoText(12, 0xFFFFFFFF);
            awayChip.setPadding(dp(16), dp(10), dp(16), dp(10));
            awayChip.setBackground(box(rust, rust, 20));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(120);
            rootFrame.addView(awayChip, lp);
        }
        awayChip.setText(text);
        if (awayChip.getVisibility() != View.VISIBLE || awayChip.getAlpha() < 1f) {
            awayChip.animate().cancel();
            if (awayChip.getVisibility() != View.VISIBLE) awayChip.setAlpha(0f);
            awayChip.setVisibility(View.VISIBLE);
            awayChip.animate().alpha(1f).setDuration(220).start();
        }
        awayChip.bringToFront();
    }

    private TextView note;
    private final Runnable hideNote = () -> {
        if (note != null) note.animate().alpha(0f).setDuration(200).withEndAction(() -> note.setVisibility(View.GONE)).start();
    };

    /**
     * Our own toast: the system one cuts text after two lines. Wraps the whole message, stays
     * longer for longer text, tap = away. Falls back to a toast while the home isn't on screen.
     */
    /** The growing tutorial: at most one new tip, a few seconds after the home settles. */
    private void maybeCoach() {
        if (!homeInFront || coachCard != null || settingsSheet != null || arcSheetOpen != null
            || libraryOpen || game != null || world != null || scene != null || dino != null || sweepView != null) {
            return;
        }
        if (prefs.getInt("whatsNewRev", 0) < WHATS_NEW_REV) { showWhatsNew(); return; }
        Coach.Tip tip = Coach.next(this);
        if (tip != null) showCoachCard(tip);
    }

    private static final int WHATS_NEW_REV = 1;  // bump to show the "Was ist neu" overlay again
    private FrameLayout coachCard;

    private void showCoachCard(Coach.Tip tip) {
        Coach.markSeen(this, tip.id);
        FrameLayout wrap = new FrameLayout(this);
        coachCard = wrap;
        wrap.setElevation(dp(10));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(box(isNight() ? 0xF21C1C1A : 0xF2FFFFFF, rust, 20));
        box.setPadding(dp(18), dp(16), dp(18), dp(14));
        box.setClickable(true);
        TextView tag = monoText(10, rust);
        tag.setLetterSpacing(0.18f);
        tag.setText("— TIPP " + Coach.seenCount(this) + "/" + Coach.TIPS.length);
        box.addView(tag);
        TextView title = text(18, ink, true);
        title.setText(tip.title);
        box.addView(title, margins(-1, -2, 0, dp(4), 0, 0));
        TextView body = text(14, ink2, false);
        body.setText(tip.body);
        body.setLineSpacing(0, 1.2f);
        box.addView(body, margins(-1, -2, 0, dp(6), 0, dp(10)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        TextView stop = text(13, ink3, false);
        stop.setText("Tipps aus");
        stop.setPadding(dp(14), dp(8), dp(14), dp(8));
        stop.setOnClickListener(v -> {
            Coach.setOff(this, true);
            closeCoachCard();
            pop("Tipps aus · wieder an in Einstellungen → Tipps");
        });
        row.addView(stop);
        TextView ok = text(14, 0xFFFFFFFF, true);
        ok.setText("Verstanden");
        ok.setPadding(dp(18), dp(8), dp(18), dp(8));
        ok.setBackground(box(rust, rust, 14));
        ok.setOnClickListener(v -> closeCoachCard());
        row.addView(ok, margins(-2, -2, dp(8), 0, 0, 0));
        box.addView(row);
        wrap.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(dp(16), 0, dp(16), dp(96));
        rootFrame.addView(wrap, lp);
        box.setTranslationY(dp(30));
        box.setAlpha(0f);
        box.animate().translationY(0).alpha(1f).setDuration(300).setInterpolator(new android.view.animation.OvershootInterpolator(0.9f)).start();
    }

    private void closeCoachCard() {
        FrameLayout c = coachCard;
        coachCard = null;
        if (c == null) return;
        c.animate().alpha(0f).translationY(dp(20)).setDuration(180).withEndAction(() -> rootFrame.removeView(c)).start();
    }

    /** A one-time "Was ist neu" overlay after a big update: everything that now exists, one tap in. */
    private void showWhatsNew() {
        prefs.edit().putInt("whatsNewRev", WHATS_NEW_REV).apply();
        FrameLayout wrap = new FrameLayout(this);
        coachCard = wrap;
        wrap.setElevation(dp(12));
        wrap.setBackgroundColor(isNight() ? 0xF2141110 : 0xF7F5F1E8);
        wrap.setClickable(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(26), dp(46), dp(26), dp(34));

        TextView eye = monoText(11, rust); eye.setLetterSpacing(0.2f); eye.setText("— WAS IST NEU"); box.addView(eye);
        TextView h = text(30, ink, true); h.setText("Dein ARC·DNA wächst"); box.addView(h, margins(-1, -2, 0, dp(6), 0, dp(4)));
        TextView sub = text(14, ink2, false); sub.setText("Das steckt jetzt drin. Tippen öffnet es."); box.addView(sub, margins(-1, -2, 0, 0, 0, dp(16)));

        addNew(box, "Fokus", "Zwei Entscheidungen, bis nur noch 3 Apps bleiben — kein App-Wald mehr.", FokusActivity.class, null);
        addNew(box, "Bücher", "Alle E-Books + Codes an einem Ort. Tippen kopiert den Code.", BuecherActivity.class, null);
        addNew(box, "Globus", "Neig das Handy — peile Regionen, springt zurück wie ein Controller-Stick.", GlobusActivity.class, null);
        addNew(box, "Lernbegleiter", "Übt mit dir. Theorie erst NACH dem Fehler — kein Volldump.", null, "https://claude.ai/artifact/KjKSrDurQdhPhGEzZQtUhY");
        addNew(box, "Antrieb", "Checkt aktiv bei dir ein und wirft dir Funken. Du pickst, was zieht.", null, "https://claude.ai/artifact/4r5tzLALZwBnjcZrjMcrmQ");
        addNew(box, "Startbildschirm", "Nur noch ARC auf dem Home. Bunte Apps liegen in der Mediathek — Würfel-Knopf unten rechts.", null, null);

        TextView go = text(15, 0xFFFFFFFF, true); go.setText("Los geht's"); go.setGravity(Gravity.CENTER);
        go.setPadding(dp(18), dp(14), dp(18), dp(14)); go.setBackground(box(rust, rust, 40));
        go.setOnClickListener(v -> closeCoachCard());
        box.addView(go, margins(-1, -2, 0, dp(12), 0, 0));

        sv.addView(box);
        wrap.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        rootFrame.addView(wrap, new FrameLayout.LayoutParams(-1, -1));
        wrap.setAlpha(0f);
        wrap.animate().alpha(1f).setDuration(260).start();
    }

    private void addNew(LinearLayout box, String name, String desc, Class<?> cls, String url) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(isNight() ? 0xFF1C1915 : 0xFFFBF8F1, isNight() ? 0xFF2E2820 : 0xFFE2D9C8, 14));
        c.setPadding(dp(16), dp(13), dp(16), dp(13));
        boolean opens = cls != null || url != null;
        c.setClickable(opens);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(16, ink, true); t.setText(name);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (opens) { TextView a = monoText(11, rust); a.setText("ÖFFNEN ↗"); head.addView(a); }
        c.addView(head);
        TextView d = text(13, ink2, false); d.setText(desc); d.setLineSpacing(0, 1.15f);
        c.addView(d, margins(-1, -2, 0, dp(3), 0, 0));
        if (opens) c.setOnClickListener(v -> {
            closeCoachCard();
            try {
                if (cls != null) startActivity(new Intent(this, cls));
                else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (RuntimeException ignored) { }
        });
        box.addView(c, margins(-1, -2, 0, 0, 0, dp(10)));
    }

    /** Einstellungen → Tipps: read any unlocked tip again, or start the tutorial over. */
    private void showTipsList() {
        java.util.List<Coach.Tip> ul = Coach.unlocked(this);
        String[] rows = new String[ul.size() + 2];
        for (int i = 0; i < ul.size(); i++) rows[i] = ul.get(i).title;
        rows[ul.size()] = Coach.isOff(this) ? "▶︎ Tipps wieder einschalten" : "⏸ Tipps pausieren";
        rows[ul.size() + 1] = "↺ Tutorial von vorn";
        arcSheet("Tipps", Coach.seenCount(this) + " von " + Coach.TIPS.length + " entdeckt · der Rest kommt mit der Zeit", rows, pick -> {
            if (pick < ul.size()) {
                Coach.Tip t = ul.get(pick);
                arcSheet(t.title, null, t.body, null, new String[]{"Okay"}, k -> { });
            } else if (pick == ul.size()) {
                Coach.setOff(this, !Coach.isOff(this));
                pop(Coach.isOff(this) ? "Tipps pausiert" : "Tipps wieder an");
            } else {
                Coach.reset(this);
                pop("Tutorial startet von vorn");
            }
        });
    }

    /* ───────── ARC·Tap: share a theme or buddy to the paired device over Bluetooth ───────── */
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener arcTapListener;

    private void showArcTap() {
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> acts = new java.util.ArrayList<>();
        labels.add("🏁 Fuchs-Rennen (Bluetooth)");
        acts.add(() -> startActivity(new Intent(this, RaceActivity.class)));
        labels.add("📲 Auf NFC-Tag schreiben …");
        acts.add(this::showArcTapNfc);
        for (Themes.Theme t : Themes.ALL) {
            final String id = t.id, nm = t.name;
            labels.add("Theme · " + nm);
            acts.add(() -> arcTapSend(id, -1, "Theme " + nm));
        }
        for (int i = 0; i < BUDDIES.length; i++) {
            final int idx = i;
            labels.add("Buddy · " + BUDDIES[i][0]);
            acts.add(() -> arcTapSend(null, idx, "Buddy " + BUDDIES[idx][0]));
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("ARC·Tap · teilen")
            .setItems(labels.toArray(new CharSequence[0]), (d, w) -> acts.get(w).run())
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    private void arcTapSend(String themeId, int buddyIdx, String what) {
        BtSync.queueShare(themeId, buddyIdx);
        pop("ARC·Tap: " + what + " senden …");
        BtSync.syncSoon(this, 0, name -> pop(name != null ? "Geteilt mit " + name
            : "Sende beim nächsten Abgleich – ist ARC-DNA auf dem Partner-Gerät offen?"));
    }

    /** A theme/buddy the partner shared arrived via BtSync — offer to apply it. */
    private void checkArcTapIncoming() {
        String in = prefs.getString("arctapIn", null);
        long at = prefs.getLong("arctapInAt", 0);
        if (in == null || at <= prefs.getLong("arctapShown", 0)) return;
        prefs.edit().putLong("arctapShown", at).apply();
        String[] f = in.split("\\|", -1);
        final String themeId = f.length > 0 ? f[0] : "";
        int bi = -1;
        try { if (f.length > 1) bi = Integer.parseInt(f[1].trim()); } catch (NumberFormatException ignored) {}
        final int buddyIdx = (bi >= 0 && bi < BUDDIES.length) ? bi : -1;
        StringBuilder what = new StringBuilder();
        if (!themeId.isEmpty()) {
            String tn = themeId;
            for (Themes.Theme t : Themes.ALL) if (t.id.equals(themeId)) tn = t.name;
            what.append("Theme ").append(tn);
        }
        if (buddyIdx >= 0) {
            if (what.length() > 0) what.append(" + ");
            what.append("Buddy ").append(BUDDIES[buddyIdx][0]);
        }
        if (what.length() == 0) return;
        new android.app.AlertDialog.Builder(this)
            .setTitle("ARC·Tap")
            .setMessage("Dein Partner teilt:\n" + what + "\n\nÜbernehmen?")
            .setPositiveButton("Übernehmen", (d, w) -> {
                SharedPreferences.Editor e = prefs.edit();
                if (!themeId.isEmpty()) e.putString("theme", themeId);
                if (buddyIdx >= 0) { e.putInt("buddy", buddyIdx); e.putBoolean("pro", false); }
                e.apply();
                recreate();
            })
            .setNegativeButton("Nein", null)
            .show();
    }

    /* ───────── ARC·Tap NFC: write a theme/buddy to a tag, tap a tag to apply ───────── */
    private android.nfc.NfcAdapter arcNfc;
    private android.app.PendingIntent arcNfcPI;
    private String nfcWrite;
    private boolean nfcChecked;

    private void setupNfc() {
        arcNfc = android.nfc.NfcAdapter.getDefaultAdapter(this);
        if (arcNfc == null) return;
        Intent i = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0;
        arcNfcPI = android.app.PendingIntent.getActivity(this, 0, i, flags);
    }

    private void nfcResume() {
        if (arcNfc != null && nfcWrite != null) {
            try { arcNfc.enableForegroundDispatch(this, arcNfcPI, null, null); } catch (RuntimeException ignored) {}
        }
    }

    private void nfcPause() {
        if (arcNfc != null) { try { arcNfc.disableForegroundDispatch(this); } catch (RuntimeException ignored) {} }
    }

    private void armNfcWrite(String themeId, int buddyIdx, String what) {
        if (arcNfc == null) { pop("Dieses Gerät hat kein NFC"); return; }
        if (!arcNfc.isEnabled()) { pop("NFC ist aus – oben in den Schnelleinstellungen einschalten"); return; }
        nfcWrite = (themeId == null ? "" : themeId) + "|" + buddyIdx;
        nfcResume();
        pop("NFC: " + what + " – Tag jetzt an die Rückseite des Geräts halten");
    }

    private void showArcTapNfc() {
        final java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> acts = new java.util.ArrayList<>();
        for (Themes.Theme t : Themes.ALL) {
            final String id = t.id, nm = t.name;
            labels.add("Theme · " + nm);
            acts.add(() -> armNfcWrite(id, -1, "Theme " + nm));
        }
        for (int i = 0; i < BUDDIES.length; i++) {
            final int idx = i;
            labels.add("Buddy · " + BUDDIES[i][0]);
            acts.add(() -> armNfcWrite(null, idx, "Buddy " + BUDDIES[idx][0]));
        }
        new android.app.AlertDialog.Builder(this).setTitle("Auf NFC-Tag schreiben")
            .setItems(labels.toArray(new CharSequence[0]), (d, w) -> acts.get(w).run())
            .setNegativeButton("Abbrechen", null).show();
    }

    private void handleNfc(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        android.nfc.Tag tag = intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG);
        if (nfcWrite != null && tag != null) {                   // writing a tag we armed
            boolean ok = writeTag(tag, nfcWrite);
            pop(ok ? "Auf NFC-Tag geschrieben ✓" : "Schreiben fehlgeschlagen – Tag nochmal halten");
            if (ok) { nfcWrite = null; nfcPause(); }
            return;
        }
        if (android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {   // tapped our tag
            String payload = readArcTap(intent);
            if (payload != null) {
                prefs.edit().putString("arctapIn", payload).putLong("arctapInAt", System.currentTimeMillis()).apply();
                checkArcTapIncoming();
            }
        }
    }

    private String readArcTap(Intent intent) {
        android.os.Parcelable[] raw = intent.getParcelableArrayExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (raw == null) return null;
        for (android.os.Parcelable pr : raw) {
            if (!(pr instanceof android.nfc.NdefMessage)) continue;
            for (android.nfc.NdefRecord r : ((android.nfc.NdefMessage) pr).getRecords()) {
                if ("application/vnd.architectdna.arctap".equals(r.toMimeType())) {
                    return new String(r.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private boolean writeTag(android.nfc.Tag tag, String payload) {
        android.nfc.NdefMessage msg = new android.nfc.NdefMessage(android.nfc.NdefRecord.createMime(
            "application/vnd.architectdna.arctap", payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try {
            android.nfc.tech.Ndef ndef = android.nfc.tech.Ndef.get(tag);
            if (ndef != null) {
                ndef.connect();
                boolean ok = ndef.isWritable() && ndef.getMaxSize() >= msg.toByteArray().length;
                if (ok) ndef.writeNdefMessage(msg);
                ndef.close();
                return ok;
            }
            android.nfc.tech.NdefFormatable f = android.nfc.tech.NdefFormatable.get(tag);
            if (f != null) { f.connect(); f.format(msg); f.close(); return true; }
        } catch (Exception e) {
            DiagLog.log("nfc write: " + e.getMessage());
        }
        return false;
    }

    private void pop(CharSequence text) {
        if (rootFrame == null || isFinishing()) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            return;
        }
        if (note == null) {
            note = new TextView(this);
            note.setTextColor(0xFF1A1A1A);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            note.setLineSpacing(0, 1.15f);
            note.setPadding(dp(18), dp(14), dp(18), dp(14));
            note.setBackground(box(0xFFFAFAF8, rust, 18));
            note.setElevation(dp(8));
            note.setOnClickListener(v -> {
                handler.removeCallbacks(hideNote);
                hideNote.run();
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.leftMargin = lp.rightMargin = dp(20);
            lp.bottomMargin = dp(150);
            rootFrame.addView(note, lp);
        }
        note.setText(text);
        note.setVisibility(View.VISIBLE);
        note.bringToFront();
        note.animate().cancel();
        note.setAlpha(0f);
        note.animate().alpha(1f).setDuration(160).start();
        handler.removeCallbacks(hideNote);
        handler.postDelayed(hideNote, Math.min(8000, 1800 + text.length() * 55L));
    }

    private void hideAwayChip() {
        if (awayChip == null || awayChip.getVisibility() != View.VISIBLE) return;
        TextView c = awayChip;
        c.animate().cancel();
        c.animate().alpha(0f).setDuration(220).withEndAction(() -> c.setVisibility(View.GONE)).start();
    }

    @Override
    public void onPose(float pitch, float yaw) {
        lastYaw = yaw;
        lastFaceEvent = android.os.SystemClock.uptimeMillis();
        headRetries = 0;
        if (Float.isNaN(facingPitch)) facingPitch = pitch;
        boolean away = Math.abs(yaw) > 28 || Math.abs(pitch - facingPitch) > 25;     // was 20/18: reading at an angle counted as away
        long now = android.os.SystemClock.uptimeMillis();
        if (away) {
            facingSince = 0;
            noteAway();
        } else {
            // Only a steady look back (0.5 s) counts; single frames that see the face don't reset the countdown.
            if (facingSince == 0) facingSince = now;
            if (now - facingSince < 500) return;
            if (faceLostAt != 0) hideAwayChip();
            faceLostAt = 0;
            if (dino != null) dino.lookedBack();      // caught it in time: it runs and hides
            facingPitch += (pitch - facingPitch) * 0.05f;      // follows how you hold the phone
        }
    }

    /** After a lock that failed (no accessibility service): no new black hole for 3 min (it looped every 13 s). */
    private long blackHoleRestUntil;

    private boolean blackHoleAllowed(long now) {
        return dino == null && homeInFront && game == null && settingsSheet == null && calibStage < 0
            && now >= blackHoleRestUntil && prefs.getBoolean("dinoLock", true);
    }

    private void noteAway() {
        long now = android.os.SystemClock.uptimeMillis();
        lastFaceEvent = now;
        if (faceLostAt == 0) {
            faceLostAt = now;
            DiagLog.log("looking away");
        }
        // The chip only once it's clear (away for over 1 s), so a glance aside doesn't flash it.
        if (now - faceLostAt > 1000 && blackHoleAllowed(now)) {
            long left = Math.max(1, 5 - (now - faceLostAt) / 1000);
            showAwayChip("◉ WEGGESCHAUT · DINO IN " + left);
        }
        // Looking away for 5 s: the black-hole dino swallows the home and locks the phone.
        if (now - faceLostAt > 5000 && blackHoleAllowed(now)) {
            hideAwayChip();
            startDino(true);
            if (dino != null) lastDinoAt = now;
        }
    }

    @Override
    public void onError(String message) {
        // Often only a busy camera (the face watch or the scroll service still had it): try again
        // a few times before giving up – and never switch the user's setting off for that.
        DiagLog.log("head error (" + headRetries + "): " + message);
        stopHead();
        if (libraryOpen && !headMode().equals("off") && headRetries < 3) {
            headRetries++;
            handler.postDelayed(() -> {
                if (libraryOpen && !headMode().equals("off") && head != null) startHead();
            }, 1500L * headRetries);
            return;
        }
        headRetries = 0;
        pop(message + " – Kopf-Scroll pausiert. Mediathek schliessen und wieder öffnen startet neu.");
    }

    private int headRetries;

    private void setHeadSpeed(float speed) {
        // Eyes rest for a moment after the screen was touched or scrolled by finger.
        // Any touch pauses head and eye scrolling for 4 s, so the finger wins.
        boolean resting = android.os.SystemClock.uptimeMillis() - lastTouch < EYE_PAUSE_MS
            || ScrollAssistService.paused;      // paused by a tap on the camera ring
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

    /** Only this app knows it; architect-dna.ch/api/diag keeps just its hash. */
    private static final String DIAG_TOKEN = "845948abb2e6d9f707f10d75a30f7e2114bf47179618474a";

    /** Sends the same anonymous report to the private drop on architect-dna.ch, where Claude reads it. */
    private void uploadDiagnostics() {
        String report = diagnosticsText();
        pop("Diagnose wird gesendet …");
        new Thread(() -> {
            int code = -1;
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL("https://architect-dna.ch/api/diag").openConnection();
                con.setRequestMethod("POST");
                con.setConnectTimeout(10_000);
                con.setReadTimeout(15_000);
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                con.setRequestProperty("x-diag-token", DIAG_TOKEN);
                try (java.io.OutputStream out = con.getOutputStream()) {
                    out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                code = con.getResponseCode();
                con.disconnect();
            } catch (Exception e) {
                DiagLog.log("diag upload failed: " + e.getClass().getSimpleName());
            }
            int result = code;
            runOnUiThread(() -> pop(result == 200 ? "Gesendet ✓ — sag Claude: „check the log“" : "Senden fehlgeschlagen (" + result + ") — lang drücken zum Teilen"));
        }).start();
    }

    /** Technical state + the log, as plain text to share (e.g. into the Claude chat). Nothing personal. */
    private void shareDiagnostics() {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "ARC-DNA Diagnose")
            .putExtra(Intent.EXTRA_TEXT, diagnosticsText());
        open(Intent.createChooser(send, "Diagnose teilen"));
    }

    private String diagnosticsText() {
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
        b.append("Bluetooth-Sync: ").append(BtSync.status).append(" · Partner: ").append(BtSync.peerName(this)).append('\n');
        b.append("Helligkeit schreiben: ").append(Settings.System.canWrite(this)).append('\n');
        b.append("Wecker: ").append(prefs.getBoolean("alarmOn", false) ? "an" : "aus").append('\n');
        b.append("Kopf-Scroll: ").append(headMode()).append(", Update-Token gesetzt: ").append(!updater.token().isEmpty()).append('\n');
        b.append("Nachtmodus: ").append(isNight()).append(", Bildschirm ").append(getResources().getConfiguration().screenWidthDp)
            .append("×").append(getResources().getConfiguration().screenHeightDp).append(" dp\n\n--- Log · neueste zuerst ---\n");
        // Newest first and only the last ~300 lines, so a shortened paste still has what just happened.
        String[] lines = DiagLog.read().split("\n");
        for (int i = lines.length - 1, n = 0; i >= 0 && n < 300; i--, n++) b.append(lines[i]).append('\n');
        return b.toString();
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
        Runnable setup = () -> {
            Intent in = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, ArcWallpaper.class));
            try {
                startActivity(in);
            } catch (ActivityNotFoundException e) {
                open(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            }
            if (!mic) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        };
        String[] rows = !lockLive ? new String[]{"Einrichten", "Später"} : !mic ? new String[]{"Mikrofon erlauben", "Später"} : new String[]{"Fertig"};
        arcSheet("Sperrbildschirm", "Dynamischer Sperrbildschirm", msg, null, rows, k -> {
            if (k != 0 || rows.length == 1) return;
            if (!lockLive) setup.run();
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        });
    }

    /** Long-press the clock: ARC-DNA Live, static light/dark, or leave the wallpaper alone. */
    private void showWallpaperChoice() {
        String[] items = {
            "Dynamischer Sperrbildschirm – Einführung",
            "ARC-DNA Live – Regenbogen, Glitzer & Rave (Sperrbildschirm)",
            "Statisch – hell/dunkel automatisch",
            "Aus – eigenes Wallpaper behalten",
            "Hell/Dunkel wieder dem System folgen",
            prefs.getBoolean("stealMe", true) ? "Sperrbildschirm-Spruch „STEAL ME.“ ausblenden" : "Sperrbildschirm-Spruch „STEAL ME.“ zeigen",
            prefs.getBoolean("theftCam", true) ? "Diebstahl-Wache (Selfie-Kamera, GPS, Mikro) aus" : "Diebstahl-Wache (Selfie-Kamera, GPS, Mikro) an",
            prefs.getBoolean("wallpaperCalm", true) ? "Wallpaper-Energie: RUHIG · auf Rave umstellen" : "Wallpaper-Energie: RAVE · auf ruhig umstellen",
        };
        arcSheet("Wallpaper & Farben", wallpaperStatus(), items, pick -> {
                if (pick == 0) {
                    showLockIntro();
                    return;
                }
                int which = pick - 1;
                if (which == 0) {
                    pop("Wallpaper festlegen → „Startbildschirm und Sperrbildschirm“");
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
                    pop("Statisches Wallpaper: hell/dunkel automatisch");
                } else if (which == 2) {
                    prefs.edit().putBoolean("autoWallpaper", false).apply();
                    pop("Wallpaper bleibt, wie es ist");
                } else if (which == 3) {
                    setAppNight(UiModeManager.MODE_NIGHT_AUTO);
                    pop("Hell/Dunkel folgt wieder dem System");
                } else if (which == 6) {
                    boolean calm = !prefs.getBoolean("wallpaperCalm", true);
                    prefs.edit().putBoolean("wallpaperCalm", calm).apply();
                    pop(calm ? "Ruhig: atmet mit dem Beat, kein Ring-Gewitter" : "Rave: volle Ringe, Glitzer & Konfetti");
                } else if (which == 5) {
                    boolean on = !prefs.getBoolean("theftCam", true);
                    prefs.edit().putBoolean("theftCam", on).apply();
                    if (on && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 77);
                    }
                    pop(on ? "📷 Wache an: Sperrbildschirm zeigt Selfie-Kamera, GPS & Mikro – nur lokal, nichts gespeichert"
                        : "Wache aus");
                } else {
                    boolean on = !prefs.getBoolean("stealMe", true);
                    prefs.edit().putBoolean("stealMe", on).apply();
                    pop(on ? "😈 STEAL ME. YES, FINALLY. – auf dem Sperrbildschirm" : "Spruch weg vom Sperrbildschirm");
                }
        });
    }

    /** A choice sheet in the ARC look (instead of the grey Android dialog): title, small status line, big rows. */
    private void arcSheet(String title, String sub, String[] rows, java.util.function.IntConsumer pick) {
        arcSheet(title, sub, null, null, rows, pick);
    }

    private FrameLayout arcSheetOpen;

    private void closeArcSheet() {
        if (arcSheetOpen != null) rootFrame.removeView(arcSheetOpen);
        arcSheetOpen = null;
    }

    /** The ARC sheet with an optional message and an extra view (a text field, a list) above the rows. */
    private void arcSheet(String title, String sub, String message, View extra, String[] rows, java.util.function.IntConsumer pick) {
        closeArcSheet();
        FrameLayout scrim = new FrameLayout(this);
        arcSheetOpen = scrim;
        scrim.setBackgroundColor(0x99000000);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setBackground(box(isNight() ? 0xFF1C1C1A : 0xFFFFFFFF, line, 26));
        box.setClickable(true);
        TextView tag = monoText(10, rust);
        tag.setLetterSpacing(0.22f);
        tag.setText("— " + title.toUpperCase(Locale.GERMAN));
        box.addView(tag);
        if (sub != null && !sub.isEmpty()) {
            TextView st = text(17, ink, true);
            st.setText(sub);
            box.addView(st, margins(-1, -2, 0, dp(6), 0, 0));
        }
        if (message != null) {
            TextView m = text(14, ink2, false);
            m.setText(message);
            m.setLineSpacing(0, 1.18f);
            box.addView(m, margins(-1, -2, 0, dp(10), 0, 0));
        }
        if (extra != null) box.addView(extra, margins(-1, -2, 0, dp(12), 0, 0));
        for (int i = 0; i < rows.length; i++) {
            TextView r = text(15, ink, false);
            r.setText(rows[i]);
            r.setPadding(dp(16), dp(14), dp(16), dp(14));
            r.setBackground(box(paper(), line, 16));
            int k = i;
            r.setOnClickListener(v -> {
                rootFrame.removeView(scrim);
                if (arcSheetOpen == scrim) arcSheetOpen = null;
                pick.accept(k);
            });
            if (i == 0 && rows.length > 1) {          // the first row is the main action: in rust
                r.setTextColor(0xFFFFFFFF);
                r.setBackground(box(rust, rust, 16));
            }
            box.addView(r, margins(-1, -2, 0, dp(8), 0, 0));
        }
        int nav = 0;
        if (Build.VERSION.SDK_INT >= 30 && rootFrame.getRootWindowInsets() != null)
            nav = rootFrame.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()).bottom;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(dp(10), dp(60), dp(10), dp(10) + nav);
        ScrollView bs = new ScrollView(this);
        bs.addView(box);
        scrim.addView(bs, lp);
        scrim.setOnClickListener(v -> closeArcSheet());
        scrim.setElevation(dp(20));
        rootFrame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        box.setTranslationY(dp(300));
        box.animate().translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /* ================= Clock, focus, network ================= */

    private TextView oldTime;

    /**
     * Within 7 days after a daylight-saving change, shows what the clock would say on the old
     * time ("SOMMERZEIT 21:12 · UMGESTELLT SO 25.10. 03:00 → 02:00"), so you can adjust.
     */
    private void paintOldTime(Calendar c) {
        if (oldTime == null) { dstOldOffset = Integer.MIN_VALUE; return; }
        java.util.TimeZone tz = c.getTimeZone();
        long now = c.getTimeInMillis(), weekAgo = now - 3L * 24 * 3600 * 1000;      // 3 days is plenty to get used to it
        int offNow = tz.getOffset(now), offBefore = tz.getOffset(weekAgo);
        if (offNow == offBefore) {
            oldTime.setVisibility(View.GONE);
            dstOldOffset = Integer.MIN_VALUE;
            return;
        }
        dstOldOffset = offBefore;
        oldTime.setTextColor(oldColour());
        // Find the change to the minute (offsets change once in a week).
        long lo = weekAgo, hi = now;
        while (hi - lo > 60_000) {
            long mid = (lo + hi) / 2;
            if (tz.getOffset(mid) == offBefore) lo = mid;
            else hi = mid;
        }
        boolean wasSummer = offBefore > offNow;
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.GERMAN);
        SimpleDateFormat day = new SimpleDateFormat("d.M.", Locale.GERMAN);
        java.util.TimeZone before = new java.util.SimpleTimeZone(offBefore, "before");
        hm.setTimeZone(before);
        String old = hm.format(new java.util.Date(now));
        String from = hm.format(new java.util.Date(hi));
        hm.setTimeZone(tz);
        String to = hm.format(new java.util.Date(hi));
        String wd = new SimpleDateFormat("EE", Locale.GERMAN).format(new java.util.Date(hi)).replace(".", "");
        oldTime.setText((wasSummer ? "UHR ZURÜCKGESTELLT " : "UHR VORGESTELLT ") + wd.toUpperCase(Locale.GERMAN) + " "
            + day.format(new java.util.Date(hi)) + " (" + from + " → " + to + ")  ·  NACH " + (wasSummer ? "SOMMERZEIT" : "WINTERZEIT") + " WÄRE ES " + old);
        oldTime.setVisibility(prefs.getLong("dstHidden", 0) == hi / 60_000 ? View.GONE : View.VISIBLE);
        final long change = hi / 60_000;
        oldTime.setOnClickListener(v -> {                 // tap = got it, gone for this change
            prefs.edit().putLong("dstHidden", change).apply();
            v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                v.setVisibility(View.GONE);
                v.setAlpha(1f);
            }).start();
        });
    }

    /** The old (pre-change) time's colour: a warm amber, neutral but clearly not "now". */
    private int oldColour() {
        return isNight() ? 0xFFE8B931 : 0xFFB8860B;
    }

    private int dstOldOffset = Integer.MIN_VALUE;
    private boolean clockShowsOld, flipArmed;

    /** In the week after a time change the big clock swipes between today's and the old time every 12.5 s. */
    private final Runnable clockFlip = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            flipArmed = false;
            if (dstOldOffset == Integer.MIN_VALUE || clock == null) {
                if (clockShowsOld) {
                    clockShowsOld = false;
                    updateClock();
                }
                return;
            }
            clockShowsOld = !clockShowsOld;
            float d = dp(26);
            clock.animate().cancel();
            clock.animate().translationY(-d).alpha(0f).setDuration(200).withEndAction(() -> {
                updateClock();
                clock.setTranslationY(d);
                clock.animate().translationY(0).alpha(1f).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }).start();
            if (homeInFront) {
                flipArmed = true;
                handler.postDelayed(this, 12_500);
            }
        }
    };

    private void updateClock() {
        if (clock == null) return;
        Calendar c = Calendar.getInstance();
        paintOldTime(c);
        boolean old = clockShowsOld && dstOldOffset != Integer.MIN_VALUE;
        Calendar shown = c;
        if (old) {
            shown = Calendar.getInstance(new java.util.SimpleTimeZone(dstOldOffset, "old"));
            shown.setTimeInMillis(c.getTimeInMillis());
        }
        int h = c.get(Calendar.HOUR_OF_DAY);
        String time = String.format(Locale.ROOT, "%02d:%02d", shown.get(Calendar.HOUR_OF_DAY), shown.get(Calendar.MINUTE));
        SpannableString s = new SpannableString(time);
        if (old) s.setSpan(new ForegroundColorSpan(oldColour()), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else s.setSpan(new ForegroundColorSpan(rust), 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        clock.setText(s);
        date.setText(new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(c.getTime()));
        // (The big clock no longer flips to the old summer time – it read like a wrong clock.)
        String hello = h < 5 ? "Gute Nacht" : h < 11 ? "Guten Morgen" : h < 17 ? "Hallo" : h < 22 ? "Guten Abend" : "Gute Nacht";
        greet.setText(hello + ", Muharrem.");
        if (minimalClock != null) { minimalClock.setText(s); minimalDate.setText(date.getText()); minimalGreet.setText(greet.getText()); }

        // Today's focus resets at midnight.
        if (focus != null) {
            String saved = today().equals(prefs.getString("focusDay", "")) ? prefs.getString("focus", "") : "";
            if (!focus.hasFocus() && !saved.equals(focus.getText().toString())) focus.setText(saved);
        }
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
        if (!on) {
            raceFree = false;           // next time it has to break free again
            burnouts = 0;
        }
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
        race(on);
    }

    private RaceOverlay raceOverlay;

    /* ---------- The racer's burnout: gas on, held back as if on a rope, tyre smoke; now and then it breaks free ---------- */

    private int burnouts;
    /** The racer broke free: the race over the whole home is on until the board closes. */
    private boolean burning, raceFree;
    private Smoke smoke;

    private void burnout() {
        if (burning || rootFrame == null) return;
        burning = true;
        burnouts++;
        boolean escape = burnouts >= 5 || (burnouts >= 3 && random.nextFloat() < 0.45f);
        if (smoke == null) {
            smoke = new Smoke();
            rootFrame.addView(smoke, new FrameLayout.LayoutParams(-1, -1));
        }
        smoke.bringToFront();
        fox.bringToFront();
        voice.say("Brrm!");
        fox.animate().cancel();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0, 1);
        a.setDuration(1400);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue(), ph = (t * 5) % 1f;
            // Surges forward, the rope snaps it back.
            float x = ph < 0.7f ? dp(14) * (float) Math.sin(ph / 0.7f * Math.PI / 2) : dp(14) * (1 - (ph - 0.7f) / 0.3f);
            fox.setTranslationX(x);
            fox.setTranslationY((random.nextFloat() - 0.5f) * dp(2));
            fox.setRotation(-2.5f * (float) Math.sin(ph * Math.PI));
            smoke.puffAt(rearWheel(), 2);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                fox.setRotation(0);
                fox.setTranslationY(0);
                if (escape) breakFree();
                else {
                    fox.animate().translationX(0).setDuration(150).start();
                    burning = false;
                }
            }
        });
        a.start();
    }

    /** The rope snaps: flame from the exhaust, off it goes, and the race begins. */
    private void breakFree() {
        burnouts = 0;
        raceFree = true;
        say("Box, box … LOS!", 1400);
        voice.say("Nyeeoow!");
        smoke.flame(fox);
        float out = rootFrame.getWidth() + fox.getWidth();
        fox.animate().translationX(out).setDuration(520).setInterpolator(new android.view.animation.AccelerateInterpolator(2.2f))
            .withEndAction(() -> {
                if (!f1) setF1(true);
                else race(true);
                // The timing car smashes out of the board's glass with it: crack, a shout, and off they go.
                f1Panel.post(this::glassBreak);
                // Back in from the left after its lap.
                fox.setTranslationX(-rootFrame.getWidth());
                fox.animate().translationX(0).setStartDelay(1600).setDuration(700)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(() -> burning = false).start();
            }).start();
    }

    private void glassBreak() {
        if (rootFrame == null || f1Panel == null || !f1Panel.isShown()) return;
        int[] a = new int[2], r = new int[2];
        f1Panel.getLocationInWindow(a);
        rootFrame.getLocationInWindow(r);
        float x = a[0] - r[0] + f1Panel.getWidth() * 0.7f, y = a[1] - r[1] + f1Panel.getHeight() * 0.86f;
        voice.thud();
        if (cracks == null) {
            cracks = new CrackView(this, ink);
            rootFrame.addView(cracks, new FrameLayout.LayoutParams(-1, -1));
        }
        cracks.bringToFront();
        cracks.crack(x, y);
        handler.postDelayed(() -> {
            say("Hey, wir leben, Mann – das Leben ist gut! Tschiiuuu!", 2600);
            voice.say("Nyeeoow!");
        }, 450);
    }

    /** Where the rear wheel touches the ground, in rootFrame coordinates (the car faces right). */
    private float[] rearWheel() {
        int[] a = new int[2], r = new int[2];
        fox.getLocationInWindow(a);
        rootFrame.getLocationInWindow(r);
        return new float[]{a[0] - r[0] + fox.getWidth() * 0.24f, a[1] - r[1] + fox.getHeight() * 0.88f};
    }

    /** Tyre smoke and the exhaust flame, drawn over the home (touches pass through). */
    private final class Smoke extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final java.util.List<float[]> puffs = new java.util.ArrayList<>();     // x, y, vx, vy, age, size
        private View flameOn;
        private long flameAt;

        Smoke() {
            super(MainActivity.this);
            setClickable(false);
        }

        void puffAt(float[] at, int n) {
            for (int i = 0; i < n; i++) {
                puffs.add(new float[]{at[0], at[1], -dp(30 + random.nextFloat() * 50), -dp(10 + random.nextFloat() * 25), 0,
                    dp(6 + random.nextFloat() * 8)});
            }
            invalidate();
        }

        void flame(View car) {
            flameOn = car;
            flameAt = android.os.SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setStyle(android.graphics.Paint.Style.FILL);
            float dt = 0.016f;
            for (int i = puffs.size() - 1; i >= 0; i--) {
                float[] q = puffs.get(i);
                q[4] += dt;
                if (q[4] > 1.1f) {
                    puffs.remove(i);
                    continue;
                }
                q[0] += q[2] * dt;
                q[1] += q[3] * dt;
                q[2] *= 0.97f;
                p.setColor(isNight() ? 0xFFB8B8B2 : 0xFF9A9A98);
                p.setAlpha((int) (150 * (1 - q[4] / 1.1f)));
                c.drawCircle(q[0], q[1], q[5] * (1 + q[4] * 2.2f), p);
            }
            p.setAlpha(255);
            long since = android.os.SystemClock.uptimeMillis() - flameAt;
            if (flameOn != null && since < 700) {
                // Exhaust at the car's tail: a flickering cone pointing backwards.
                int[] a = new int[2], r = new int[2];
                flameOn.getLocationInWindow(a);
                getLocationInWindow(r);
                float x = a[0] - r[0] + flameOn.getWidth() * 0.06f, y = a[1] - r[1] + flameOn.getHeight() * 0.6f;
                for (int i = 0; i < 14; i++) {
                    float f = i / 14f, len = dp(26 + random.nextFloat() * 22);
                    p.setColor(f < 0.4f ? 0xFFFFF27A : f < 0.75f ? 0xFFFFA62B : 0xFF3C8CFF);
                    p.setAlpha((int) (230 * (1 - since / 700f)));
                    c.drawCircle(x - len * f, y + (random.nextFloat() - 0.5f) * dp(6) * f, dp(7) * (1 - f * 0.6f), p);
                }
                p.setAlpha(255);
            }
            if (!puffs.isEmpty() || since < 700) postInvalidateOnAnimation();
        }
    }

    /** With the F1 board open and the racer as buddy: the race track over the whole home. */
    private void race(boolean on) {
        boolean want = on && buddy() == 3 && raceFree && rootFrame != null && (!calm() || askedRace);
        if (want) {
            if (raceOverlay == null) {
                raceOverlay = new RaceOverlay(this);
                raceOverlay.onFinished = () -> {
                    askedRace = false;
                    if (f1) setF1(false);          // one race, then the hyper-seconds board closes too
                };
                rootFrame.addView(raceOverlay, new FrameLayout.LayoutParams(-1, -1));
            }
            raceOverlay.bringToFront();
            if (fox != null) fox.bringToFront();
            raceOverlay.start();
        } else if (raceOverlay != null && raceOverlay.getVisibility() == View.VISIBLE) {
            raceOverlay.stop();
        }
    }

    /** The timing board takes a bit under half the screen height. */
    private int f1Height() {
        // As tall as its content (time, lap, sectors, track) – it used to take 42 % of the screen,
        // mostly empty.
        return Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.42f), dp(232));
    }

    /** Into or out of split screen: rows and clock for the new width. */
    @Override
    public void onMultiWindowModeChanged(boolean multi, Configuration config) {
        super.onMultiWindowModeChanged(multi, config);
        reflow();
    }

    /** Rotation no longer recreates the screen (circle gesture, game); only sizes follow. */
    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        f1Panel.getLayoutParams().height = f1Height();
        f1Panel.requestLayout();
        reflow();
        // A turn by the system (or by hand on the tablet): check whether the home still needs turning.
        handler.removeCallbacks(softCheck);
        handler.postDelayed(softCheck, 300);
    }

    /** Re-flow the grids for the new width (4 columns in portrait, up to 8 in landscape). */
    private void reflow() {
        appTilesBox.removeAllViews();
        appTilesBox.addView(appTiles());
        // The ARC-DNA apps too: built for another width (full screen ↔ split screen) rows went missing.
        if (arcBox != null) {
            arcBox.removeAllViews();
            arcBox.addView(arcApps());
        }
        renderLibrary();
        if (!lastRecent.isEmpty()) {
            renderRecentCarousel(lastRecent);
            renderRecents(recentLib, lastRecent, 2 * cols());
        }
        rootFrame.requestApplyInsets();
    }

    /** Tiles per row: about one per 100 dp of width, at least 4, at most 8. */
    private int cols() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        // Turned by hand (90° / 270°): the home is as wide as the screen is tall.
        if (softDeg == 90 || softDeg == 270) widthDp = getResources().getConfiguration().screenHeightDp;
        return Math.max(4, Math.min(8, widthDp / 100));
    }

    /* ================= Brightness lever ================= */

    /** Changing system brightness needs "Modify system settings"; ask once, then it just works. */
    private boolean canWriteSettings() {
        if (Settings.System.canWrite(this)) return true;
        pop("ARC-DNA erlauben, Systemeinstellungen zu ändern – dann nochmal schieben");
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

    /**
     * Geister-Level: the system's very lowest brightness, set as the real system value (manual mode),
     * so it stays that dark in every app you open from here, not just on the home screen.
     */
    private void ghostBrightness() {
        if (!canWriteSettings()) return;
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 1);
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
            if (ghosts != null) {
                lever.setLevel(0, false);
                return;
            }
            int b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            int best = 0;
            for (int i = 1; i < 3; i++) {
                if (Math.abs(BRIGHTNESS[i] - b) < Math.abs(BRIGHTNESS[best] - b)) best = i;
            }
            lever.setLevel(best + 1, false);   // lever 0 is the Geister-Level
        } catch (Settings.SettingNotFoundException e) {
            lever.setLevel(2, false);
        }
    }

    /* ================= Fuchsjagd ================= */

    /** Tap on empty space: the screen turns to landscape and the fox chases the rabbit. */
    private BuddyWorldView world;
    private boolean askedRace;

    /** Double-tap the buddy: its own world – fox hunt, bunny run, bear river, duck pond, F1 race. */
    private void startBuddyWorld() {
        int b = buddy();
        befriend(2);
        if (b == 0 || b == 2) {
            startGame();
            return;
        }
        if (b == 3) {                                      // the racer: straight onto the track
            raceFree = true;
            askedRace = true;                              // asked for: also in the quiet / tidied home
            if (f1) race(true);
            else setF1(true);
            return;
        }
        if (world != null || scene != null || game != null) return;
        exitGhosts();
        hideKeyboard();
        if (b >= 5) {                                      // panda, ice bear, owl, dragon: their own scenes
            scene = new BuddySceneView(this, b - 5, getDrawable(BUDDY_ART[b]), prefs, this::exitScene);
            scene.setElevation(dp(30));
            rootFrame.addView(scene, new FrameLayout.LayoutParams(-1, -1));
            scene.setAlpha(0f);
            scene.animate().alpha(1f).setDuration(300).start();
            voice.say(BUDDY_CALLS[b][0]);
            return;
        }
        world = new BuddyWorldView(this, b == 4 ? BuddyWorldView.DUCK : BuddyWorldView.BEAR, getDrawable(BUDDY_ART[b]), prefs, this::exitWorld);
        world.setElevation(dp(30));
        rootFrame.addView(world, new FrameLayout.LayoutParams(-1, -1));
        world.setAlpha(0f);
        world.animate().alpha(1f).setDuration(300).start();
        voice.say(BUDDY_CALLS[b][0]);
    }

    private BuddySceneView scene;

    private void exitScene() {
        BuddySceneView v = scene;
        scene = null;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(200).withEndAction(() -> rootFrame.removeView(v)).start();
    }

    private void exitWorld() {
        BuddyWorldView w = world;
        world = null;
        if (w == null) return;
        w.animate().alpha(0f).setDuration(200).withEndAction(() -> rootFrame.removeView(w)).start();
    }

    private void startGame() {
        if (game != null) return;
        exitGhosts();
        hideKeyboard();
        game = new FoxRunView(this, new int[]{ink, ink2, ink3, rust, card, line, paper()},
            voice, prefs, this::exitGame, buddy() == 2);
        rootFrame.addView(game, new FrameLayout.LayoutParams(-1, -1));
        game.setAlpha(0f);
        game.animate().alpha(1f).setDuration(300).start();
        setSoftRotation(0);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        voice.say(buddy() == 2 ? "Hüpf!" : "Yip-yip!");
        game.resume();
        startGameNod();
        pop("Tippen oder Kopf hoch = springen · 3× tippen oder 2× Home = zurück");
    }

    /* A head bump up = jump: a fast reading vs. a slow baseline; a lift of ~2.5° jumps, re-armed once the head is back. */
    private float nodFast = Float.NaN, nodSlow;
    private boolean nodArmed = true;

    private void gameNod(float pitch) {
        if (Float.isNaN(nodFast)) {
            nodFast = nodSlow = pitch;
            return;
        }
        nodFast += (pitch - nodFast) * 0.6f;
        nodSlow += (pitch - nodSlow) * 0.06f;
        float lift = nodFast - nodSlow;           // positive = head moving up
        if (nodArmed && lift > 2.5f) {
            nodArmed = false;
            if (game != null) game.jump();
        } else if (!nodArmed && lift < 1f) {
            nodArmed = true;
        }
    }

    /** Nod to jump: the front camera runs while the game is open (only if the camera is allowed). */
    private void startGameNod() {
        faceWatch = false;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (head == null) head = new HeadTracker(this, this);
        nodFast = Float.NaN;
        nodArmed = true;
        hideAwayChip();
        // Restart the camera once the screen has turned to landscape: the face watcher's camera was set up
        // for portrait, its frames came in sideways and no face (no nod) was found.
        head.stop();
        handler.postDelayed(() -> {
            if (game != null && head != null) {
                head.setDebug(false);
                head.start(HeadTracker.Mode.NOD);
            }
        }, 900);
    }

    private void exitGame() {
        if (game == null) return;
        FoxRunView g = game;
        game = null;
        if (head != null && !libraryOpen) head.stop();
        handler.postDelayed(this::startFaceWatch, 800);
        g.pause();
        g.animate().alpha(0f).setDuration(200).withEndAction(() -> rootFrame.removeView(g)).start();
        applyOrientation();
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
        int cw = content.getWidth() - content.getPaddingLeft() - content.getPaddingRight();
        if (recentCarousel != null && cw > 0 && (Math.abs(cw - carouselWidth) > dp(4) || cols() != carouselCols)
            && !lastRecent.isEmpty()) {
            carouselWidth = cw;
            carouselCols = cols();
            content.post(() -> renderRecentCarousel(lastRecent));    // tiles were sized for another width
        }
        int limit = scroll.getScrollY() + scroll.getHeight() - foldInset;
        // At the very end of the page nothing can scroll up any more: show everything sharp.
        if (!scroll.canScrollVertically(1)) limit = Integer.MAX_VALUE / 2;
        int n = content.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = content.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            boolean isLabel = LABEL_TAG.equals(child.getTag()) && i + 1 < n;
            View block = isLabel ? content.getChildAt(i + 1) : child;
            // A folded section keeps its old height while GONE: judge its title on its own.
            if (isLabel && block.getVisibility() != View.VISIBLE) {
                frost(child, child.getHeight() == 0 ? 0
                    : Math.max(0f, Math.min(1f, (child.getBottom() - limit) / (float) child.getHeight())));
                i++;
                continue;
            }
            // A tall block (the open Mediathek) folds row by row, not as one piece.
            // The music card is never frosted, not even row by row (clear anything left from before).
            if (block == musicView && block instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) block;
                for (int k = 0; k < g.getChildCount(); k++) frost(g.getChildAt(k), 0);
            }
            // Every section with rows (apps grids, Zuletzt, the Mediathek) frosts row by row, so only
            // the row under the fold blurs, not the whole section.
            LinearLayout rows = rowsOf(block);
            if (isLabel && rows != null && block.getVisibility() == View.VISIBLE && block != musicView) {
                frost(child, 0);
                frost(block, 0);
                int off = rows == block ? 0 : rows.getTop();
                for (int k = 0; k < rows.getChildCount(); k++) {
                    View r = rows.getChildAt(k);
                    int bottom = block.getTop() + off + r.getBottom();
                    frost(r, r.getHeight() == 0 ? 0
                        : Math.max(0f, Math.min(1f, (bottom - limit) / (float) r.getHeight())));
                }
                i++;
                continue;
            }
            // Rows frosted one by one while the block was tall (screen turned, lock screen) stay
            // blurred otherwise once it folds as one piece again: clear them.
            if (isLabel && block instanceof LinearLayout && block != musicView) {
                ViewGroup g = (ViewGroup) block;
                for (int k = 0; k < g.getChildCount(); k++) {
                    Float was = foldState.get(g.getChildAt(k));
                    if (was != null && was > 0) frost(g.getChildAt(k), 0);
                }
            }
            float hidden = block.getHeight() == 0 ? 0
                : Math.max(0f, Math.min(1f, (block.getBottom() - limit) / (float) block.getHeight()));
            if (block == musicView) hidden = 0;       // its title stays sharp with it
            frost(child, hidden);
            if (isLabel) {
                frost(block, hidden);
                i++;
            }
        }
    }

    /* ---- Fill the free space: the clock grows into the gap left by folded sections ---- */

    private View flexGap;
    private static final float CLOCK_SP = 104, CLOCK_MAX_SP = 220;

    /**
     * With sections folded there's room left: the clock takes it (up to 220 sp and the width next to
     * the lever) instead of leaving an empty gap; opening sections shrinks it back to 104 sp.
     */
    private void fitClock() {
        if (flexGap == null || clock == null || clock.getHeight() == 0 || f1) return;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        float curPx = clock.getTextSize(), basePx = CLOCK_SP * sp;
        float perPx = (clock.getHeight() - clock.getPaddingTop()) / curPx;      // text height per px of size
        float roomAtBase = flexGap.getHeight() + (curPx - basePx) * perPx;
        float targetPx = basePx + Math.max(0, roomAtBase - dp(12)) / perPx;
        // Never wider than the space beside the brightness lever.
        float widthRoom = content.getWidth() - content.getPaddingLeft() - content.getPaddingRight() - dp(80);
        android.graphics.Paint probe = new android.graphics.Paint(clock.getPaint());
        probe.setTextSize(targetPx);
        float textW = probe.measureText(clock.getText().toString());
        if (textW > widthRoom && textW > 0) targetPx *= widthRoom / textW;
        targetPx = Math.max(basePx, Math.min(CLOCK_MAX_SP * sp, targetPx));
        // Split screen: half the room, so a smaller clock leaves space for the apps.
        if (Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode()) targetPx = Math.min(targetPx, Math.max(basePx, 72 * sp));
        if (Math.abs(targetPx - curPx) > dp(2)) clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx);
    }

    /** The music card is never frosted: it's interactive and animates its own alpha (page swipe). */
    private View musicView;

    /** The vertical list of rows inside a section (directly, or inside its frame), or null. */
    private LinearLayout rowsOf(View block) {
        View v = block;
        if (v instanceof FrameLayout && ((FrameLayout) v).getChildCount() == 1) v = ((FrameLayout) v).getChildAt(0);
        if (v instanceof LinearLayout && ((LinearLayout) v).getOrientation() == LinearLayout.VERTICAL
            && ((LinearLayout) v).getChildCount() > 1) return (LinearLayout) v;
        return null;
    }

    /** Clears the frosted-fold blur on a view and its rows (a few levels down). */
    private void unfrost(View v, int depth) {
        if (foldState.remove(v) != null) {          // only what the fold effect touched (faded tiles keep their look)
            v.setAlpha(1f);
            if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null);
            if (spicy()) {
                v.setRotationX(0);
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
        }
        if (depth > 0 && v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) unfrost(g.getChildAt(i), depth - 1);
        }
    }

    private void frost(View v, float hidden) {
        if (v == musicView || v == dashboardView || v == widgetStrip) hidden = 0;     // widgets stay sharp
        Float last = foldState.get(v);
        if (last != null && Math.abs(last - hidden) < 0.02f && (hidden > 0 || last == 0)) return;
        foldState.put(v, hidden);
        if (spicy()) {
            // Spicy: rows flip up out of the bottom edge like cards off a deck, instead of the blur.
            if (pro()) hidden = Math.min(hidden, 0.35f);
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(0);
            if (v.getCameraDistance() < 1000 * getResources().getDisplayMetrics().density)
                v.setCameraDistance(getResources().getDisplayMetrics().density * 9000);
            v.setRotationX(hidden * 62);
            float sc = 1 - 0.14f * hidden;
            v.setScaleX(sc);
            v.setScaleY(sc);
            v.setAlpha(1 - 0.6f * hidden);
            return;
        }
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

    /* ================= Auto torch ================= */

    private DarkTorch torch;
    private TextView torchChip;
    private boolean torchOurs, torchDeclined, covered;
    private long darkSince = -1, lastProbe;

    /**
     * At home in the dark: the front light sensor reads under ~8 lux for 1.2 s → the back camera
     * takes a one-second look (so a phone lying face down on a lit table doesn't count) → torch on.
     * Off again when it gets bright, when you leave home, or by tapping the 🔦 chip.
     */
    private void onLux(float lux) {
        if (torch == null || !torch.available() || !prefs.getBoolean("autoTorch", true)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (torch.isOn()) {
            if (torchOurs && lux > 40) {
                torch.set(false);
                DiagLog.log("auto torch off, bright again (lux " + Math.round(lux) + ")");
            }
            return;
        }
        if (lux >= 8 || covered || libraryOpen || game != null || torchDeclined || !homeInFront) {
            darkSince = -1;
            return;
        }
        if (darkSince < 0) darkSince = now;
        if (now - darkSince < 1200 || torch.isProbing() || now - lastProbe < 10000) return;
        lastProbe = now;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            DiagLog.log("auto torch: no camera permission, using the light sensor only");
            torchOn();
            return;
        }
        torch.probe(luma -> {
            if (luma >= 0 && luma < 45 && homeInFront && !torch.isOn()) torchOn();
        });
    }

    private void torchOn() {
        torchOurs = true;
        torch.set(true);
        hop();
        say("Dunkel hier – Licht an. 🔦", 1800);
    }

    private void paintTorchChip() {
        if (torchChip == null) return;
        torchChip.setVisibility(torch != null && torch.isOn() ? View.VISIBLE : View.GONE);
    }

    /* ================= Hide / fox / quake ================= */

    /** One full twist per volume step; quick presses stack up into a longer spin. */
    private void spinFox() {
        if (fox == null || game != null) return;
        if (buddy() == 3) {
            burnout();          // the racer doesn't spin: it revs on the spot
            return;
        }
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

    /* ---- The buddy: fox, bear, rabbit or F1 racer, chosen in the settings ---- */

    /** {name, "der/die …" for the greeting, its calls}. Drawables and eyes in BUDDY_ART. */
    private static final String[][] BUDDIES = {
        {"Fuchs", "der Fuchs"}, {"Bär", "der Bär"}, {"Hase", "der Hase"}, {"F1-Racer", "der F1-Racer"}, {"Ente", "die Ente Erwin"},
        {"Panda", "der Panda"}, {"Eisbär", "der Eisbär"}, {"Eule", "die Eule"}, {"Drache", "der Drache"}};
    private static final String[][] BUDDY_CALLS = {
        {"Yip!", "Yip-yip!", "Gekk-gekk-gekk!", "Wow-wow!", "Ack-ack-ack!", "Mrrp?", "Wa-pa-pa-pa!", "Huff."},
        {"Brumm.", "Hrrmm?", "Grrmpf!", "Mmmh, Honig."},
        {"Mümmel!", "Tipp-tapp!", "Snff?", "Hüpf!"},
        {"Brrm!", "Nyeeoow!", "Box, box!", "Vroom."},
        {"Quak!", "Quak-quak!", "Wak?", "Quaaak."},
        {"Mampf.", "Bambus?", "Hmpf-hmpf!", "Kuschelzeit."},
        {"Eisbär ist da.", "Eisbär sagt Hallo.", "Brrr.", "Eisbär macht Tee."},
        {"Huhu!", "Uhu-uhu!", "Schuhu?", "Ich seh alles."},
        {"Rawrr!", "Fffshhh!", "Grrooar.", "Kleines Feuer?"}};
    private static final float[][] BUDDY_EYES = {{75f, 31f, 3.6f, 1.8f}, {75f, 26f, 3.4f, 1.8f}, {70.3f, 33.8f, 2.8f, 1.4f}, {0f, 0f, 0f, 0f}, {74.5f, 20.5f, 2.3f, 1.2f},
        {76f, 27f, 3.2f, 1.7f}, {76f, 28f, 3f, 1.6f}, {58f, 25f, 4.6f, 2.4f}, {75f, 18f, 3f, 1.6f}};      // the racer has no eye: its helmet is the face
    private static final int[] BUDDY_ART = {R.drawable.fox, R.drawable.bear, R.drawable.bunnybuddy, R.drawable.f1buddy, R.drawable.duckbuddy,
        R.drawable.pandabuddy, R.drawable.icebearbuddy, R.drawable.owlbuddy, R.drawable.dragonbuddy};
    private int tipIndex;

    private int buddy() {
        return Math.max(0, Math.min(BUDDIES.length - 1, prefs.getInt("buddy", 0)));
    }

    private void applyBuddy() {
        int b = buddy();
        float[] e = BUDDY_EYES[b];
        fox.buddy(BUDDY_ART[b], e[0], e[1], e[2], e[3]);
        fox.setContentDescription(BUDDIES[b][0]);
    }

    /** Tap the buddy: its call, plus something useful, a different thing each tap. */
    /*
     * Erwin the duck: unflappable, loves rain, philosophises a little, and every tap spreads ripples
     * over the home like a pond (a feather floats down now and then).
     */
    private static final String[] DUCK_TRAITS = {"Regen? Herrlich. Für mich ist jeder Tag Badetag.",
        "Alles perlt an mir ab. Probier's mal.", "Oben ruhig, unten paddeln wie verrückt. Wie du.",
        "Brot? Nein danke, ich bin auf Wasserlinsen.", "Ich watschel nur kurz durch, mach weiter.",
        "Gleichmut ist mein Superkraft-Gefieder.", "Wenn's stürmt: Kopf unter den Flügel. Fünf Minuten."};

    private void duckRipples() {
        if (rootFrame == null || fox == null || pro()) return;
        int[] at = new int[2], root = new int[2];
        fox.getLocationOnScreen(at);
        rootFrame.getLocationOnScreen(root);
        float cx = at[0] - root[0] + fox.getWidth() * 0.5f, cy = at[1] - root[1] + fox.getHeight() * 0.85f;
        boolean feather = random.nextInt(3) == 0;
        View v = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final long start = android.os.SystemClock.uptimeMillis();

            @Override
            protected void onDraw(Canvas c) {
                float t = (android.os.SystemClock.uptimeMillis() - start) / 1000f;
                if (t > 2.6f) {
                    post(() -> rootFrame.removeView(this));
                    return;
                }
                p.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 4; i++) {
                    float k = t - i * 0.3f;
                    if (k <= 0 || k > 1.8f) continue;
                    float r = dp(20) + k * dp(160);
                    p.setStrokeWidth(dp(2.5f) * (1 - k / 1.8f) + dp(0.5f));
                    p.setColor(((int) (180 * (1 - k / 1.8f)) << 24) | 0x3C8DFF);
                    c.drawOval(cx - r, cy - r * 0.35f, cx + r, cy + r * 0.35f, p);
                }
                if (feather) {
                    float fx = cx + dp(40) + (float) Math.sin(t * 3) * dp(26), fy = cy - dp(220) + t * dp(90);
                    c.save();
                    c.rotate((float) Math.sin(t * 3) * 30, fx, fy);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xDDF4F2EC);
                    c.drawOval(fx - dp(4), fy - dp(14), fx + dp(4), fy + dp(14), p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(1));
                    p.setColor(0xFF6F6E69);
                    c.drawLine(fx, fy - dp(16), fx, fy + dp(16), p);
                    c.restore();
                }
                postInvalidateOnAnimation();
            }
        };
        rootFrame.addView(v, new FrameLayout.LayoutParams(-1, -1));
        fox.bringToFront();
    }

    private void foxCall() {
        if (buddy() == 4) {
            hop();
            duckRipples();
            voice.say(BUDDY_CALLS[4][random.nextInt(BUDDY_CALLS[4].length)]);
            say("Quak! " + DUCK_TRAITS[tipIndex++ % DUCK_TRAITS.length], 3200);
            return;
        }
        hop();
        String[] calls = BUDDY_CALLS[buddy()];
        String call = calls[random.nextInt(calls.length)];
        voice.say(call);
        java.util.List<String> tips = buddyTips();
        String tip = tips.isEmpty() ? null : tips.get(tipIndex++ % tips.size());
        say(tip == null ? call : call + " " + tip, tip == null ? 1400 : 3200);
    }

    private java.util.List<String> buddyTips() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (prefs.getBoolean("alarmOn", false)) {
            long in = Math.max(0, AlarmScheduler.next(prefs) - System.currentTimeMillis()) / 60_000;
            out.add("Wecker in " + (in / 60) + " h " + (in % 60) + " min.");
        }
        android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
        if (bm != null) {
            int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (pct > 0) out.add("Akku " + pct + " %" + (bm.isCharging() ? ", lädt." : pct <= 20 ? " – bald laden!" : "."));
        }
        long[] week = MusicStats.week(this);
        if (week[6] > 0) out.add("Heute " + (week[6] / 60 > 0 ? week[6] / 60 + " h " : "") + week[6] % 60 + " min Musik.");
        String f = prefs.getString("focus", "");
        if (!f.isEmpty() && today().equals(prefs.getString("focusDay", ""))) out.add("Heute zählt: " + f);
        return out;
    }

    /** Easter egg: Gemini (or Claude, ChatGPT) opens over the home screen and the buddy notices. */
    private void assistantArrived(String who) {
        if (!homeInFront || isFinishing() || bubble == null || fox == null || game != null) return;
        String[] lines = who.equals("claude")
            ? new String[]{"Claude! Der hat mich gebaut. Benimm dich.", "Psst – Claude ist da. Tu so, als wär alles fertig.", "Oh, der Chef. Ich war nur kurz Pause."}
            : who.equals("chatgpt")
            ? new String[]{"Die Konkurrenz? Hier drin?!", "Ich sag nichts. Ich bin nur Deko."}
            : new String[]{"Psst, Gemini hört mit. Ich sag nix.", "Gemini! Frag mal, ob der Dino echt ist.", "Ein Kollege! Aber ich bin der mit dem Fell.",
                "Hey Gemini – nicht meine Kacheln essen, das macht schon der Dino."};
        handler.postDelayed(() -> {
            if (!homeInFront || bubble == null) return;
            say(lines[random.nextInt(lines.length)], 3600);
            if (voice != null) voice.say("Yip?");
            fox.animate().cancel();
            fox.animate().translationY(-dp(18)).setDuration(160).withEndAction(() ->
                fox.animate().translationY(0).setDuration(260).setInterpolator(new android.view.animation.BounceInterpolator()).start()).start();
        }, 700);
    }

    /*
     * Now and then (at most every 2 h, then 1 in 3 home visits) the buddy points out one of
     * ARC-DNA's own apps you haven't opened for the longest time; tap the bubble to open it.
     */
    private static final String[] SUGGEST = {"Schon vergessen? ", "Lange nicht gesehen: ", "Probier mal wieder ", "Kennst du noch "};

    private void suggestArc() {
        long now = System.currentTimeMillis();
        if (pro() || dino != null || game != null || now - prefs.getLong("arcSuggestAt", 0) < 2 * 3600_000L || random.nextInt(3) != 0) return;
        Object[] pick = null;
        long oldest = Long.MAX_VALUE;
        for (Object[] a : arcList()) {
            if (a[1] == null) continue;
            long used = prefs.getLong("arcUsed:" + ((Class<?>) a[1]).getName(), 0);
            if (used < oldest) {
                oldest = used;
                pick = a;
            }
        }
        if (pick == null || now - oldest < 24 * 3600_000L) return;       // everything used lately
        prefs.edit().putLong("arcSuggestAt", now).apply();
        Object[] app = pick;
        hop();
        say(SUGGEST[random.nextInt(SUGGEST.length)] + app[0] + "? → antippen", 6000);
        bubble.setOnClickListener(v -> {
            bubble.setOnClickListener(null);
            bubble.setVisibility(View.GONE);
            openApp(new Intent(this, (Class<?>) app[1]), getDrawable((Integer) app[2]));
        });
        handler.postDelayed(() -> bubble.setOnClickListener(null), 6500);
        DiagLog.log("suggested " + app[0]);
    }

    private void say(String s, long ms) {
        if (pro()) return;            // professional: the buddy keeps quiet
        bubble.setText(s);
        bubble.setVisibility(View.VISIBLE);
        bubble.setAlpha(1f);
        bubble.animate().cancel();
        bubble.animate().alpha(0f).setStartDelay(ms).setDuration(300)
            .withEndAction(() -> bubble.setVisibility(View.GONE)).start();
    }

    /* ================= Buddy DJs: pick who puts on the music ================= */

    private DjPicker djPicker;

    private void showDjPicker() {
        if (djPicker != null || rootFrame == null) return;
        djPicker = new DjPicker(this, MusicStats.favoriteArtist(this), new DjPicker.Listener() {
            @Override
            public void onPick(DjPicker.Dj dj) {
                DiagLog.log("dj: " + dj.name + " (" + dj.query + ")");
                playFromSearch(dj.query);
                handler.postDelayed(() -> {
                    removeDjPicker();
                    say(dj.name + " legt auf: " + dj.kind.toLowerCase(Locale.GERMAN) + " 🎧", 2600);
                }, 700);
            }

            @Override
            public void onClose() {
                removeDjPicker();
            }
        });
        rootFrame.addView(djPicker, new FrameLayout.LayoutParams(-1, -1));
    }

    private void removeDjPicker() {
        if (djPicker == null) return;
        rootFrame.removeView(djPicker);
        djPicker = null;
    }

    /**
     * Asks the music app to search and play right away (the same way voice assistants do); YouTube
     * Music first, then Spotify, else any app that can. ARC-DNA comes back to the front after a moment.
     */
    private void playFromSearch(String query) {
        Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (String pkg : new String[]{"com.google.android.apps.youtube.music", "com.spotify.music"}) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                i.setPackage(pkg);
                break;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                pop("Keine Musik-App gefunden.");
                return;
            }
        }
        // Back home once the music runs (the home button via the accessibility service, else ourselves).
        handler.postDelayed(() -> {
            if (homeInFront) return;
            if (ScrollAssistService.instance == null || !ScrollAssistService.instance.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) {
                try {
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
            }
        }, 3200);
    }

    /** Now and then, with nothing playing: the buddy offers a DJ (at most every 3 h, daytime only). */
    private void suggestDj() {
        if (!homeInFront || dino != null || game != null || djPicker != null) return;
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null || am.isMusicActive()) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        long now = System.currentTimeMillis();
        if (h < 7 || h >= 23 || now - prefs.getLong("djSuggestAt", 0) < 3 * 3600_000L) return;
        prefs.edit().putLong("djSuggestAt", now).apply();
        String[] lines = {"🎧 Stille? Soll ich auflegen? Musik-Titel antippen.", "🎧 Ich hätt da was. Titel im Musik-Kärtchen antippen.",
            "🎧 DJ gesucht – tipp den Musik-Titel an."};
        say(lines[random.nextInt(lines.length)], 3600);
    }

    /* ================= Dino visit: every 5 minutes on the home screen ================= */

    private static final long DINO_EVERY = 2 * 60_000L;
    private DinoView dino;
    private boolean swapped;
    /** Every app tile (for the dino to eat in a fight it won), and what it has eaten. */
    private final java.util.WeakHashMap<View, Boolean> allTiles = new java.util.WeakHashMap<>();
    private final List<View> eatenTiles = new ArrayList<>();

    private void restoreEaten() {
        for (View t : eatenTiles) {
            t.animate().cancel();
            t.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f).rotation(0).alpha(1f)
                .setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        }
        eatenTiles.clear();
    }
    private long faceLostAt, lastFaceEvent, lastDinoAt = -DINO_EVERY;
    private final Runnable dinoTick = new Runnable() {
        @Override
        public void run() {
            // The comic visit (every 5th return to the home) while you're looking;
            // looking away belongs to the black-hole dino.
            if (homeInFront && prefs.getBoolean("dino", true) && faceLostAt == 0) {
                lastDinoAt = android.os.SystemClock.uptimeMillis();
                startDino();
            }
        }
    };

    private void startDino() {
        startDino(false);
    }

    /** blackHole: the dino swallows the home and the phone locks (looking away); else the comic visit. */
    private void startDino(boolean blackHole) {
        if (!blackHole && calmSkip()) return;
        // During the race the full visit is too much: he comes small and gets run over on the track.
        if (!blackHole && raceOverlay != null && raceOverlay.getVisibility() == View.VISIBLE) {
            raceOverlay.miniDino();
            DiagLog.log("dino on the race track");
            return;
        }
        if (dino != null || rootFrame == null || rootFrame.getWidth() == 0 || game != null || settingsSheet != null
            || calibStage >= 0 || fox == null || (!blackHole && !fox.isShown())) {
            DiagLog.log("dino skipped: dino=" + (dino != null) + " game=" + (game != null) + " settings=" + (settingsSheet != null)
                + " calib=" + calibStage + " buddy=" + (fox != null && fox.isShown()));
            return;
        }
        int paper = isNight() ? 0xFF2A2A28 : 0xFFFFFFFF;
        dino = new DinoView(this, ink, paper, rust, new DinoView.Host() {
            @Override
            public float[] buddy() {
                // From the parent + layout position: the buddy's own spin/hop (volume keys) must not move the dino's ground.
                int[] a = new int[2], r = new int[2];
                ((View) fox.getParent()).getLocationInWindow(a);
                rootFrame.getLocationInWindow(r);
                return new float[]{a[0] - r[0] + fox.getLeft() + fox.getWidth() / 2f, a[1] - r[1] + fox.getTop() + fox.getHeight()};
            }

            @Override
            public void hideBuddy() {
                fox.animate().cancel();
                fox.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(180).start();
            }

            @Override
            public void showBuddy() {
                fox.animate().cancel();
                fox.setTranslationX(dp(40));
                fox.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0).setDuration(420)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
            }

            @Override
            public void buddyDodge() {
                fox.animate().cancel();
                fox.animate().translationX(dp(72)).translationY(-dp(46)).rotation(20).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> fox.animate().translationY(0).rotation(0).setDuration(320)
                        .setInterpolator(new android.view.animation.BounceInterpolator())
                        .withEndAction(() -> fox.animate().translationX(0).setStartDelay(4500).setDuration(600).start()).start())
                    .start();
            }

            @Override
            public void swapBuddy(String afterLine) {
                int next = (MainActivity.this.buddy() + 1) % BUDDIES.length;
                prefs.edit().putInt("buddy", next).apply();
                applyBuddy();
                swapped = true;
                // Pops out of the ground with a spin, lands with a hop, then introduces itself.
                fox.animate().cancel();
                fox.setAlpha(0f);
                fox.setScaleX(0.1f);
                fox.setScaleY(0.1f);
                fox.setRotation(-180f);
                fox.animate().alpha(1f).scaleX(1.25f).scaleY(1.25f).rotation(0f).setDuration(420)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> fox.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(3f))
                        .withEndAction(MainActivity.this::hop).start()).start();
                voice.say(BUDDY_CALLS[next][0]);
                handler.postDelayed(() -> say("Tadaa! Ich bin " + BUDDIES[next][1] + ". " + afterLine, 2800), 600);
            }

            @Override
            public void buddySay(String text) {
                say(text, 2000);
            }

            @Override
            public void stomp() {
                voice.thud();
            }

            @Override
            public void chomp() {
                voice.crumple();
            }

            @Override
            public void done() {
                endDino();
            }

            @Override
            public void vacuum(float k, float x, float y) {
                for (View v : new View[]{scroll, fox}) {
                    // Pivot from the plain layout position (not the on-screen one, which already
                    // includes this spin and made the centre wobble every frame).
                    float lx = 0, ly = 0;
                    for (View q = v; q != null && q != rootFrame; q = q.getParent() instanceof View ? (View) q.getParent() : null) {
                        lx += q.getLeft();
                        ly += q.getTop();
                    }
                    v.setPivotX(x - lx);
                    v.setPivotY(y - ly);
                    v.setScaleX(Math.max(0.01f, 1 - k));
                    v.setScaleY(Math.max(0.01f, 1 - k));
                    v.setRotation(540 * k);
                    v.setAlpha(1 - 0.7f * k);
                }
            }

            @Override
            public int level() {
                return prefs.getInt("dinoLevel", 1);
            }

            @Override
            public void levelUp() {
                int l = Math.min(99, prefs.getInt("dinoLevel", 1) + 1);
                prefs.edit().putInt("dinoLevel", l).apply();
                DiagLog.log("dino beaten, now level " + l);
            }

            @Override
            public boolean eatOne(float x, float y) {
                // A random app tile that's on screen right now flies into the mouth.
                List<View> fresh = new ArrayList<>();
                android.graphics.Rect vis = new android.graphics.Rect();
                for (View t : allTiles.keySet()) {
                    if (t != null && t.isShown() && t.getAlpha() > 0.5f && !eatenTiles.contains(t) && t.getGlobalVisibleRect(vis)) fresh.add(t);
                }
                if (fresh.isEmpty()) return false;
                View t = fresh.get(random.nextInt(fresh.size()));
                int[] a = new int[2], r = new int[2];
                t.getLocationInWindow(a);
                rootFrame.getLocationInWindow(r);
                float tx = x - (a[0] - r[0] + t.getWidth() / 2f), ty = y - (a[1] - r[1] + t.getHeight() / 2f);
                eatenTiles.add(t);
                t.animate().cancel();
                t.animate().translationXBy(tx).translationYBy(ty).scaleX(0.05f).scaleY(0.05f).rotation(400).alpha(0f)
                    .setDuration(450).setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
                return true;
            }

            @Override
            public void restoreEaten() {
                MainActivity.this.restoreEaten();
            }

            @Override
            public void cutMusic() {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null && am.isMusicActive()) {
                    am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE));
                    am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE));
                    DiagLog.log("dino cut the music");
                }
            }

            @Override
            public void lockNow() {
                boolean ok = ScrollAssistService.lockScreen();
                DiagLog.log("dino lock: " + (ok ? "locked" : "accessibility service not running"));
                if (!ok) {
                    blackHoleRestUntil = android.os.SystemClock.uptimeMillis() + 180_000;
                    pop("Zum Sperren braucht der Dino die Bedienungshilfe (⚙ → Berechtigungen). Pause für 3 Minuten.");
                }
                DiagLog.log("dino black hole → lock");
            }
        });
        if (blackHole) dino.blackHole();
        DiagLog.log(blackHole ? "dino black hole" : "dino comic visit");
        rootFrame.addView(dino, new FrameLayout.LayoutParams(-1, -1));
        DiagLog.log("dino visit");
    }

    /**
     * Watchdog: every 3 s while the home is in front and nothing is going on, a buddy that ended up
     * hidden, faded, shrunk or off screen fades back in – and the log says how it was found.
     */
    private final Runnable buddyWatch = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (!homeInFront) return;
            handler.postDelayed(this, 3000);
            if (fox == null || dino != null || chat != null || sweeping || world != null || game != null || libraryOpen || pro()) return;
            boolean off = Math.abs(fox.getTranslationX()) > dp(40) || Math.abs(fox.getTranslationY()) > dp(60);
            if (fox.getVisibility() == View.VISIBLE && fox.getAlpha() > 0.9f && fox.getScaleX() > 0.9f && Math.abs(fox.getScaleX()) <= 1.3f && !off) return;
            DiagLog.log("buddy watchdog: vis=" + fox.getVisibility() + " a=" + fox.getAlpha() + " s=" + fox.getScaleX()
                + " tx=" + (int) fox.getTranslationX() + " ty=" + (int) fox.getTranslationY() + " f1=" + f1 + " swept=" + swept());
            fox.animate().cancel();
            fox.setVisibility(View.VISIBLE);
            fox.setScaleX(1f);
            fox.setScaleY(1f);
            fox.setRotation(0f);
            fox.setTranslationX(0f);
            fox.setTranslationY(0f);
            fox.animate().alpha(1f).setDuration(300).start();
            fox.bringToFront();
        }
    };

    /** Nothing going on (no dino, chat or sweep): the buddy is visible, full size, in its place. */
    private void reviveBuddy() {
        if (fox == null || dino != null || chat != null || sweeping || pro()) return;
        fox.animate().cancel();
        fox.setVisibility(View.VISIBLE);
        fox.setAlpha(1f);
        fox.setScaleX(1f);
        fox.setScaleY(1f);
        fox.setRotation(0f);
        fox.setTranslationX(0f);
        fox.setTranslationY(0f);
        fox.setElevation(0);
    }

    /** One tap on the buddy: a line of its own – a call, a duck trait, a tip about your phone. */
    /* ---- Buddy gallery: the first five are yours, the others are earned (friendship) or bought (coins) ---- */

    private static final int STARTERS = 5;
    /** Per buddy: friendship ♥ that unlocks it, or coins (Nexus Breach bank) to buy it now. */
    private static final int[][] UNLOCK = {{0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {60, 600}, {150, 1000}, {260, 1500}, {400, 2200}};

    private boolean buddyOwned(int b) {
        return b < STARTERS || prefs.getBoolean("buddyOwned" + b, false);
    }

    private int friendship() {
        return prefs.getInt("buddyXp", 0);
    }

    private android.content.SharedPreferences bankPrefs() {
        return getSharedPreferences("nexus", MODE_PRIVATE);
    }

    private int coins() {
        return bankPrefs().getInt("bank", 0);
    }

    /** Time together, a tap, a game, a chat: friendship grows; enough of it unlocks the next buddy. */
    private void befriend(int hearts) {
        prefs.edit().putInt("buddyXp", friendship() + hearts).apply();
        checkUnlocks();
    }

    private void checkUnlocks() {
        int xp = friendship();
        for (int b = STARTERS; b < BUDDIES.length; b++) {
            if (buddyOwned(b) || xp < UNLOCK[b][0]) continue;
            prefs.edit().putBoolean("buddyOwned" + b, true).apply();
            pop("🎉 Neuer Buddy: " + BUDDIES[b][0] + " – Einstellungen → Buddy-Galerie");
            DiagLog.log("buddy unlocked: " + BUDDIES[b][0] + " at " + xp + " friendship");
            if (fox != null) hop();
        }
    }

    /** Every 5 minutes with the home open counts as time together. */
    private final Runnable friendTick = new Runnable() {
        @Override
        public void run() {
            befriend(1);
            handler.postDelayed(this, 5 * 60_000);
        }
    };

    /** All buddies as cards: yours in colour, the others as silhouettes with what it takes. */
    private void showBuddyGallery() {
        checkUnlocks();
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(3);
        int cellW = (Math.min(rootFrame.getWidth(), dp(460)) - dp(40) - dp(16)) / 3 - dp(8);
        for (int b = 0; b < BUDDIES.length; b++) {
            boolean owned = buddyOwned(b), active = b == buddy();
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(6), dp(8), dp(6), dp(8));
            cell.setBackground(box(active ? (rust & 0x00FFFFFF) | 0x22000000 : paper(), active ? rust : line, 14));
            ImageView art = new ImageView(this);
            art.setImageResource(BUDDY_ART[b]);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (!owned) {
                art.setColorFilter(isNight() ? 0xFF3A3A38 : 0xFF2A2A28, android.graphics.PorterDuff.Mode.SRC_IN);
                art.setAlpha(0.55f);
            }
            cell.addView(art, new LinearLayout.LayoutParams(-1, dp(46)));
            TextView name = text(13, owned ? ink : ink3, true);
            name.setText(owned ? BUDDIES[b][0] : "🔒 " + BUDDIES[b][0]);
            name.setGravity(Gravity.CENTER);
            cell.addView(name, margins(-1, -2, 0, dp(4), 0, 0));
            TextView st = monoText(9, active ? rust : ink3);
            st.setGravity(Gravity.CENTER);
            st.setLetterSpacing(0.04f);
            if (active) st.setText("● DEIN BUDDY");
            else if (owned) st.setText("TIPPEN = WÄHLEN");
            else st.setText("♥ " + friendship() + "/" + UNLOCK[b][0] + "\noder 🪙 " + UNLOCK[b][1]);
            cell.addView(st, margins(-1, -2, 0, dp(2), 0, 0));
            if (!owned) {
                // How close: a little friendship bar.
                FrameLayout bar = new FrameLayout(this);
                bar.setBackground(box(line, line, 3));
                View fill = new View(this);
                fill.setBackground(box(rust, rust, 3));
                float f = Math.min(1f, friendship() / (float) UNLOCK[b][0]);
                bar.addView(fill, new FrameLayout.LayoutParams(Math.max(dp(4), Math.round((cellW - dp(12)) * f)), -1));
                cell.addView(bar, margins(-1, dp(5), 0, dp(5), 0, 0));
            }
            int which = b;
            cell.setOnClickListener(v -> pickBuddy(which));
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = cellW;
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(cell, lp);
        }
        arcSheet("Buddy-Galerie", "♥ " + friendship() + " Freundschaft · 🪙 " + coins() + " Münzen",
            "Freundschaft wächst mit Antippen, Spielen, Chatten und Zeit zusammen. Oder kauf einen frei – mit Münzen aus Nexus Breach.",
            grid, new String[]{"Fertig"}, pick -> { });
    }

    private void pickBuddy(int b) {
        if (buddyOwned(b)) {
            closeArcSheet();
            prefs.edit().putInt("buddy", b).apply();
            applyBuddy();
            hop();
            say("Hi, ich bin " + BUDDIES[b][1] + ".", 1600);
            return;
        }
        int price = UNLOCK[b][1];
        if (coins() >= price) {
            bankPrefs().edit().putInt("bank", coins() - price).apply();
            prefs.edit().putBoolean("buddyOwned" + b, true).apply();
            DiagLog.log("buddy bought: " + BUDDIES[b][0] + " for " + price);
            pop("🪙 " + price + " → " + BUDDIES[b][0] + " ist frei!");
            showBuddyGallery();
            return;
        }
        pop("Noch " + Math.max(0, UNLOCK[b][0] - friendship()) + " ♥ – oder " + (price - coins()) + " 🪙 mehr");
    }

    private void buddyComment() {
        befriend(1);
        String[] calls = BUDDY_CALLS[buddy()];
        java.util.List<String> tips = buddyTips();
        String line;
        int pick = random.nextInt(3);
        if (buddy() == 4 && pick == 0) line = DUCK_TRAITS[random.nextInt(DUCK_TRAITS.length)];
        else if (pick == 1 && !tips.isEmpty()) line = tips.get(tipIndex++ % tips.size());
        else line = calls[random.nextInt(calls.length)] + " Doppeltipp = Spiel · halten = Chat.";
        voice.say(calls[random.nextInt(calls.length)]);
        say(line, 3800);
    }

    private void endDino() {
        if (dino == null) return;
        rootFrame.removeView(dino);
        dino = null;
        restoreEaten();
        boolean keepPop = swapped;       // the new buddy's entrance keeps running
        swapped = false;
        scroll.setScaleX(1f);
        scroll.setScaleY(1f);
        scroll.setRotation(0f);
        scroll.setAlpha(1f);
        faceLostAt = 0;
        if (!keepPop) {
            fox.setRotation(0f);
            fox.animate().cancel();
            fox.setAlpha(1f);
            fox.setScaleX(1f);
            fox.setScaleY(1f);
            fox.setTranslationX(0f);
            fox.setTranslationY(0f);
        }
    }

    /*
     * Face watch: while the home is in front and the Dino-Sperre is on, the front camera only checks
     * whether you're facing the screen (nothing is recorded or stored). No face for 5 s → black hole.
     */
    private boolean faceWatch;

    private void startFaceWatch() {
        handler.removeCallbacks(faceWatchdog);
        if (minimal()) return;                         // schlicht: no camera behind the overlay
        if (LowPower.active(this)) return;             // under 15 %: no camera just for the dino
        if (!homeInFront || libraryOpen || game != null || !prefs.getBoolean("dinoLock", true)) return;
        handler.postDelayed(faceWatchdog, 3000);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            DiagLog.log("face watch: no camera permission");
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        // Already watching and frames are coming in: nothing to do.
        if (faceWatch && now - lastFaceEvent < 2500) return;
        if (head == null) head = new HeadTracker(this, this);
        head.stop();
        head.setDebug(false);
        head.start(HeadTracker.Mode.NOD);
        faceWatch = true;
        faceLostAt = 0;
        lastFaceEvent = now;
        DiagLog.log("face watch started");
    }

    /** Every 3 s on the home: if the watcher's camera went quiet (stopped by something else), start it again. */
    private final Runnable faceWatchdog = this::startFaceWatch;

    private void stopFaceWatch() {
        handler.removeCallbacks(faceWatchdog);
        if (!faceWatch) return;
        faceWatch = false;
        if (head != null && !libraryOpen && game == null) head.stop();
    }

    private void hop() {
        if (chat != null) {                 // floating in the chat: hop in place (buddyFloat draws it)
            chatHopAt = android.os.SystemClock.uptimeMillis();
            return;
        }
        fox.animate().cancel();
        fox.animate().translationY(-dp(26)).setDuration(180)
            .withEndAction(() -> fox.animate().translationY(0).setDuration(220).start()).start();
    }

    /** The fox winds up, kicks, and the glass cracks at a random spot as its foot lands. */
    private void foxKick() {
        fox.animate().cancel();
        fox.setPivotX(fox.getWidth() * 0.2f);
        fox.setPivotY(fox.getHeight());
        fox.animate().rotation(14).translationX(-dp(6)).setDuration(160).withEndAction(() ->
            fox.animate().rotation(-22).translationX(dp(16)).translationY(-dp(10)).setDuration(110).withEndAction(() -> {
                float w = rootFrame.getWidth(), h = rootFrame.getHeight();
                quake(w * (0.12f + random.nextFloat() * 0.76f), h * (0.15f + random.nextFloat() * 0.7f));
                fox.animate().rotation(0).translationX(0).translationY(0).setDuration(320)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
            }).start()).start();
    }

    private void quake() {
        quake(rootFrame.getWidth() / 2f, rootFrame.getHeight() * 0.4f);
    }

    private void quake(float x, float y) {
        if (cracks == null) {
            cracks = new CrackView(this, ink);
            rootFrame.addView(cracks, new FrameLayout.LayoutParams(-1, -1));
        }
        cracks.bringToFront();
        cracks.crack(x, y);
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
        if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            onLux(e.values[0]);
            return;
        }
        if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            covered = e.values[0] < e.sensor.getMaximumRange();     // pocket, bag, face down
            return;
        }
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
        checkUnlocks();                      // games and scenes add friendship too
        registerReceiver(lowWatch, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        handler.removeCallbacks(friendTick);
        handler.postDelayed(friendTick, 5 * 60_000);
        homeInFront = true;
        refreshAmbient();                    // refresh the ambient background data cells
        if (mockHome()) applyMockHome();     // re-assert the clean look after returning to home
        prefs.edit().putBoolean("uiNight", isNight()).putLong("uiNightAt", System.currentTimeMillis()).apply();
        checkArcTapIncoming();
        if (arcTapListener == null) {
            arcTapListener = (sp, key) -> {
                if ("arctapIn".equals(key)) handler.post(this::checkArcTapIncoming);   // instant, no next-sync wait
            };
            prefs.registerOnSharedPreferenceChangeListener(arcTapListener);
        }
        nfcResume();
        if (!nfcChecked) { nfcChecked = true; handleNfc(getIntent()); }
        applyPro();
        applySwept();
        reviveBuddy();
        handler.postDelayed(buddyWatch, 3000);
        if (pixelLayer != null) pixelLayer.refresh();
        if (dashboard != null) dashboard.resume();
        if (phoneWidgets != null) phoneWidgets.resume();
        xiaomiGestures();
        ScrollAssistService.onAssistant = this::assistantArrived;
        handler.postDelayed(this::suggestArc, 2500);
        handler.postDelayed(this::maybeCoach, 3200);
        handler.postDelayed(this::maybeSweep, 4200);
        if (crumplePending) {
            crumplePending = false;
            // Only after real use (not a launch that bounced straight back).
            if (System.currentTimeMillis() - leftAt > 900 && game == null && ghosts == null) rootFrame.post(this::playCrumple);
        }
        hideNavBar();
        handler.removeCallbacks(dinoTick);
        // Every 5th real return to the home (not a dialog or a launch that bounced back): the dino comic.
        if (System.currentTimeMillis() - pausedAt > 900 && prefs.getBoolean("dino", true)) {
            int n = prefs.getInt("homeReturns", 0) + 1;
            prefs.edit().putInt("homeReturns", n).apply();
            if (n % 5 == 0) handler.postDelayed(dinoTick, 1800);
            else handler.postDelayed(this::suggestDj, 2600);
        }
        handler.postDelayed(this::startFaceWatch, 1500);
        handler.removeCallbacks(clockFlip);
        flipArmed = dstOldOffset != Integer.MIN_VALUE;
        clockShowsOld = false;
        ScrollAssistService.homeInFront(getPackageName());
        BtSync.startServer(this);          // also without notification access: the partner must find us
        RouteLearner.note(this);           // at a station? (Routen learns your usual routes)
        BtSync.syncSoon(this, 3 * 60_000L, name -> {
            if (name != null && music != null) music.refreshStats();
        });
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
        race(f1);
        if (game != null) {
            game.resume();
            startGameNod();
        }
        refreshRecents();
        if (music != null) music.start();
        updater.check(false);
        Sensor acc = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        Sensor lightSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor != null) sensors.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        Sensor near = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (near != null) sensors.registerListener(this, near, SensorManager.SENSOR_DELAY_NORMAL);
        torchDeclined = false;
        darkSince = -1;
        if (libraryOpen && !headMode().equals("off")
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startHead();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfcPause();
        handler.removeCallbacks(friendTick);
        try {
            unregisterReceiver(lowWatch);
        } catch (RuntimeException ignored) {
        }
        if (dashboard != null) dashboard.pause();
        if (phoneWidgets != null) phoneWidgets.pause();
        if (folderClose != null && folderPanel != null) folderClose.run();      // an app was started from a folder
        if (sweepView != null) sweepDone(sweepView);      // left mid-sweep: finish it, don't leave the buddy invisible
        closeFabMenu();
        pausedAt = System.currentTimeMillis();
        handler.removeCallbacks(clockFlip);
        flipArmed = false;
        homeInFront = false;
        handler.removeCallbacks(dinoTick);
        endDino();
        stopFaceWatch();
        hideAwayChip();
        if (volumeReceiver != null) {
            unregisterReceiver(volumeReceiver);
            volumeReceiver = null;
        }
        prefs.edit().putInt("homeScrollY", scroll.getScrollY()).apply();
        handler.removeCallbacks(ticker);
        f1Running = false;
        f1Panel.pause();
        if (raceOverlay != null) raceOverlay.stop();
        if (game != null) game.pause();
        if (music != null) music.stop();
        if (sensors != null) sensors.unregisterListener(this);
        // The automatic torch belongs to the home screen: leaving home switches it off.
        if (torch != null && torch.isOn() && torchOurs) torch.set(false);
        stopHead();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (packageReceiver != null) unregisterReceiver(packageReceiver);
        if (kbdBattReceiver != null) { try { unregisterReceiver(kbdBattReceiver); } catch (RuntimeException ignored) { } KbdBatt.hide(); }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null && netCallback != null) cm.unregisterNetworkCallback(netCallback);
        try {
            unregisterReceiver(unlockReceiver);
        } catch (RuntimeException ignored) {
        }
        if (head != null) head.close();
        voice.close();
    }

    /* ================= Unlock: Face-ID style lock under the camera, home comes into focus ================= */

    static final String CALIBRATE_NOD = "ch.architectdna.home.CALIBRATE_NOD";
    private UnlockView unlockView;
    private long unlockAt;

    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                lockedSinceOff = true;
                unlockAt = 0;
                return;
            }
            unlockAt = android.os.SystemClock.uptimeMillis();
            maybePlayUnlock();
        }
    };

    private boolean lockedSinceOff;

    /**
     * The home's first moment in front after the screen was off and the phone is unlocked. Face
     * unlock is fast: the home can get focus before USER_PRESENT arrives, or after; either order works.
     */
    private void maybePlayUnlock() {
        // USER_PRESENT itself means a lock screen was passed; face unlock with "skip lock screen"
        // can already be done when the screen turns on, so nothing else is required.
        if (rootFrame == null || !hasWindowFocus()) return;
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) return;
        long now = android.os.SystemClock.uptimeMillis();
        // Only a real unlock: Android says USER_PRESENT once the lock screen was actually passed
        // (face, fingerprint, PIN). Screen just back on without a lock: no animation.
        if (unlockAt == 0) return;
        if (now - unlockAt > 3000) {
            lockedSinceOff = false;          // unlocked into another app long ago: no show later
            return;
        }
        lockedSinceOff = false;
        unlockAt = 0;
        if (prefs.getBoolean("faceLock", false) && biometricReady()) {
            holdLocked();
            return;
        }
        DiagLog.log("unlock animation after a real unlock");
        rootFrame.postDelayed(this::playUnlock, 60);
    }

    /* ---- Gesichts-Sperre: the home stays blurred behind a lock until Android's face / biometric check passes ---- */

    private View lockVeil;

    private boolean biometricReady() {
        if (Build.VERSION.SDK_INT < 30) return false;
        android.hardware.biometrics.BiometricManager bm = getSystemService(android.hardware.biometrics.BiometricManager.class);
        return bm != null && bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void holdLocked() {
        if (lockVeil != null) {
            askFace();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31) scroll.setRenderEffect(RenderEffect.createBlurEffect(dp(28), dp(28), Shader.TileMode.CLAMP));
        scroll.setScaleX(0.96f);
        scroll.setScaleY(0.96f);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor((panel & 0x00FFFFFF) | 0x66000000);
        v.setClickable(true);
        TextView lock = text(44, ink, true);
        lock.setText("🔒");
        lock.setGravity(Gravity.CENTER);
        v.addView(lock);
        TextView hint = monoText(12, ink2);
        hint.setText("GESICHT ZEIGEN · TIPPEN = NOCHMAL");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, 0);
        v.addView(hint);
        v.setOnClickListener(x -> askFace());
        lockVeil = v;
        rootFrame.addView(v, new FrameLayout.LayoutParams(-1, -1));
        askFace();
    }

    private void askFace() {
        if (Build.VERSION.SDK_INT < 30) return;
        android.hardware.biometrics.BiometricPrompt prompt = new android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("ARC-DNA")
            .setSubtitle("Startseite entsperren")
            .setConfirmationRequired(false)
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButton("Später", getMainExecutor(), (d, w) -> { })
            .build();
        prompt.authenticate(new android.os.CancellationSignal(), getMainExecutor(),
            new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                    View v = lockVeil;
                    lockVeil = null;
                    if (v != null) rootFrame.removeView(v);
                    DiagLog.log("face lock passed");
                    playUnlock();         // the lock springs open, the blur clears
                }
            });
    }

    private void playUnlock() {
        if (unlockView != null || rootFrame == null || rootFrame.getWidth() == 0) return;
        float cx = rootFrame.getWidth() / 2f, cy = dp(20), holeR = dp(8);
        if (Build.VERSION.SDK_INT >= 31 && rootFrame.getRootWindowInsets() != null) {
            android.view.DisplayCutout cut = rootFrame.getRootWindowInsets().getDisplayCutout();
            if (cut != null && cut.getCutoutPath() != null) {
                android.graphics.RectF b = new android.graphics.RectF();
                cut.getCutoutPath().computeBounds(b, true);
                int[] at = new int[2];
                rootFrame.getLocationOnScreen(at);
                if (!b.isEmpty() && b.width() < rootFrame.getWidth() / 3f) {
                    cx = b.centerX() - at[0];
                    cy = b.centerY() - at[1];
                    holeR = Math.max(b.width(), b.height()) / 2f;
                }
            }
        }
        View target = scroll;
        unlockView = new UnlockView(this, cx, cy, holeR, ink, rust, k -> {
            // Blurred and a touch small at first, sharp by the time the lock has opened.
            float blur = 1 - Math.min(1f, k / 0.6f);
            if (Build.VERSION.SDK_INT >= 31) {
                target.setRenderEffect(blur > 0.01f
                    ? RenderEffect.createBlurEffect(dp(28) * blur, dp(28) * blur, Shader.TileMode.CLAMP) : null);
            }
            float sc = 1 - 0.04f * blur;
            target.setScaleX(sc);
            target.setScaleY(sc);
        }, () -> {
            if (Build.VERSION.SDK_INT >= 31) target.setRenderEffect(null);
            target.setScaleX(1f);
            target.setScaleY(1f);
            rootFrame.removeView(unlockView);
            unlockView = null;
        });
        rootFrame.addView(unlockView, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Home pressed while already home: close the Mediathek, back to the top. Coming back from an app keeps the position. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getAction() != null && intent.getAction().startsWith("android.nfc.action.")) {
            handleNfc(intent);
            return;
        }
        if (CALIBRATE_NOD.equals(intent.getAction())) {
            calibrate("nod");
            return;
        }
        if (Updater.ACTION_STATUS.equals(intent.getAction())) {
            updater.onStatus(intent);
            return;
        }
        if (game != null) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastHomePress < 1500) exitGame();
            else pop("Nochmal Home = Fuchsjagd beenden");
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
        if (coachCard != null) {
            closeCoachCard();
            return;
        }
        if (raceOverlay != null && raceOverlay.driving()) {
            raceOverlay.quitDriving();
            return;
        }
        if (arcSheetOpen != null) {
            closeArcSheet();
            return;
        }
        if (world != null) {
            exitWorld();
            return;
        }
        if (scene != null) {
            exitScene();
            return;
        }
        if (fabMenu != null) {
            closeFabMenu();
            return;
        }
        if (djPicker != null) {
            djPicker.close();
            return;
        }
        if (game != null) {
            pop("3× tippen oder 2× Home = zurück");
            return;
        }
        if (settingsSheet != null) closeSettings();
        else if (calibStage >= 0) endGazeCalibration();
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

    /** Our own apps get the bold line icon of the ARC-DNA Apps tiles everywhere (Zuletzt, Mediathek). */
    private Drawable ownIcon(String activity, Drawable fallback) {
        int id = FreqActivity.class.getName().equals(activity) ? R.drawable.ic_line_freq
            : GlobusActivity.class.getName().equals(activity) ? R.drawable.ic_line_globus
            : NexusActivity.class.getName().equals(activity) ? R.drawable.ic_line_nexus
            : BoardActivity.class.getName().equals(activity) ? R.drawable.ic_line_board
            : RecorderActivity.class.getName().equals(activity) ? R.drawable.ic_line_recorder
            : GalleryActivity.class.getName().equals(activity) ? R.drawable.ic_line_gallery
            : CameraActivity.class.getName().equals(activity) ? R.drawable.ic_line_arccam
            : DecibelActivity.class.getName().equals(activity) ? R.drawable.ic_line_decibel
            : PlayerActivity.class.getName().equals(activity) ? R.drawable.ic_line_player
            : EnergyActivity.class.getName().equals(activity) ? R.drawable.ic_line_energy
            : VpnActivity.class.getName().equals(activity) ? R.drawable.ic_line_vpn
            : RouteActivity.class.getName().equals(activity) ? R.drawable.ic_line_routes
            : WeckerActivity.class.getName().equals(activity) ? R.drawable.ic_line_alarm
            : CalmActivity.class.getName().equals(activity) ? R.drawable.ic_line_calm
            : PixelActivity.class.getName().equals(activity) ? R.drawable.ic_line_pixel : 0;
        return id == 0 ? fallback : getDrawable(id);
    }

    /** {name, activity, line icon, launcher icon}: the native apps built into ARC-DNA, tiles like the APPS grid. */
    private FrameLayout arcBox;

    private View arcApps() {
        Object[][] own = arcList();
        return arcGrid(own);
    }

    /** ARC-DNA's own apps {name, activity, line icon, launcher icon}. */
    private static Object[][] arcList() {
        return new Object[][]{{"Fokus", FokusActivity.class, R.drawable.ic_line_fokus, R.drawable.ic_line_fokus},
            {"Werkzeuge", WerkzeugeActivity.class, R.drawable.ic_line_werkzeuge, R.drawable.ic_line_werkzeuge},
            {"VOID FREQ", FreqActivity.class, R.drawable.ic_line_freq, R.drawable.ic_voidfreq},
            {"Bücher", BuecherActivity.class, R.drawable.ic_line_buecher, R.drawable.ic_line_buecher},
            {"Globus", GlobusActivity.class, R.drawable.ic_line_globus, R.drawable.ic_globus},
            {"Sonne & Welt", SunActivity.class, R.drawable.ic_line_sonne, R.drawable.ic_line_sonne},
            {"Faktencheck", FaktenActivity.class, R.drawable.ic_line_fakten, R.drawable.ic_line_fakten},
            {"Nexus Breach", NexusActivity.class, R.drawable.ic_line_nexus, R.drawable.ic_nexus},
            {"Dr. Overloaded", BoardActivity.class, R.drawable.ic_line_board, R.drawable.ic_board},
            {"Rekorder", RecorderActivity.class, R.drawable.ic_line_recorder, R.drawable.ic_recorder},
            {"Galerie", GalleryActivity.class, R.drawable.ic_line_gallery, R.drawable.ic_gallery},
            {"ARC Kamera", CameraActivity.class, R.drawable.ic_line_arccam, R.drawable.ic_arccam},
            {"Dezibel", DecibelActivity.class, R.drawable.ic_line_decibel, R.drawable.ic_decibel},
            {"ARC Player", PlayerActivity.class, R.drawable.ic_line_player, R.drawable.ic_player},
            {"Energie", EnergyActivity.class, R.drawable.ic_line_energy, R.drawable.ic_energy},
            {"VPN", VpnActivity.class, R.drawable.ic_line_vpn, R.drawable.ic_vpn},
            {"Routen", RouteActivity.class, R.drawable.ic_line_routes, R.drawable.ic_routes},
            {"Bär-Wecker", WeckerActivity.class, R.drawable.ic_line_alarm, R.drawable.ic_alarm},
            {"Pfeifen", TuneActivity.class, R.drawable.ic_music, R.drawable.ic_music},
            {"Pixel-Art", PixelActivity.class, R.drawable.ic_line_pixel, R.drawable.ic_line_pixel},
            {"Gelassen", CalmActivity.class, R.drawable.ic_line_calm, R.drawable.ic_line_calm},
            {"Spielothek", SpielothekActivity.class, R.drawable.ic_line_games, R.drawable.ic_line_games},
            {"Stimmung", MoodActivity.class, R.drawable.ic_line_mood, R.drawable.ic_line_mood},
            {"Kompass", KompassActivity.class, R.drawable.ic_line_kompass, R.drawable.ic_line_kompass},
            {"Flexplan", PlanActivity.class, R.drawable.ic_line_plan, R.drawable.ic_line_plan},
            // Not built yet: greyed out, a tap says what's coming.
            {"Eigenes VPN", null, R.drawable.ic_line_vpn, R.drawable.ic_vpn}};
    }

    private View arcGrid(Object[][] own) {

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        int cols = cols() >= 8 ? 8 : 4;
        for (int i = 0; i < own.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                wrap.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            Object[] a = own[i];
            tileTint = PURPLE;
            View t = tile((String) a[0], getDrawable((Integer) a[2]), 28);
            tileTint = 0;
            if (a[1] == null) {
                t.setAlpha(0.35f);
                t.setOnClickListener(v -> pop("Kommt bald: dein eigenes ARC-DNA-VPN mit deinen Proton-Servern (WireGuard) – "
                    + "verbinden direkt hier, ohne Proton-App."));
            } else {
                t.setOnClickListener(v -> openApp(new Intent(this, (Class<?>) a[1]), getDrawable((Integer) a[2])));
            }
            row.addView(t, weighted(i % cols != 0));
        }
        for (int i = own.length % cols; i != 0 && i < cols; i++) row.addView(new View(this), weighted(true));
        return wrap;
    }

    /* Vibrant section colours (like the DROP red): strong yellow, green, light purple, see-through. */
    static final int YELLOW = 0xFFFFD60A, GREEN = 0xFF2BD96B, PURPLE = 0xFFA77BFF, LIGHT_RED = 0xFFF2564A;
    private int tileTint;

    /** A tile tinted in the section's colour: a light see-through fill and a clear edge. */
    /** The colours only make sense together: tinted while Suche, Apps, ARC-DNA Apps and Zuletzt are all open, else plain. */
    private static final String[] TINT_FOLDS = {"search", "apps", "arcApps", "recentRow"};
    private static final boolean[] TINT_DEFAULTS = {true, true, false, true};
    private final java.util.WeakHashMap<View, Integer> tintViews = new java.util.WeakHashMap<>();

    private boolean vibrant() {
        for (int i = 0; i < TINT_FOLDS.length; i++) if (!prefs.getBoolean("fold:" + TINT_FOLDS[i], TINT_DEFAULTS[i])) return false;
        return true;
    }

    private void tint(View v, int colour) {
        tintViews.put(v, colour);
        v.setBackground(tinted(colour));
    }

    private void recolour() {
        for (java.util.Map.Entry<View, Integer> e : new ArrayList<>(tintViews.entrySet())) {
            if (e.getKey() != null) e.getKey().setBackground(tinted(e.getValue()));
        }
    }

    private android.graphics.drawable.Drawable tinted(int colour) {
        // Plain compact rounded rectangle everywhere – no angled hexagon corner.
        if (!vibrant()) return box(card, line, 14);
        boolean night = isNight();
        int fill = (colour & 0x00FFFFFF) | ((night ? 0x2E : 0x3D) << 24);
        int edge = (colour & 0x00FFFFFF) | ((night ? 0x99 : 0xB3) << 24);
        return box(fill, edge, 14);
    }

    private View tile(String name, Drawable icon, int iconDp) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(6), dp(7), dp(6), dp(6));          // compact: the card hugs the icon + label
        allTiles.put(t, Boolean.TRUE);
        // Plain, compact rounded rectangle – no coloured tint, no hexagon edges.
        t.setBackground(box(card, line, 14));

        ImageView iv = new ImageView(this);
        if (icon != null) iv.setImageDrawable(icon);
        t.addView(iv, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

        TextView tv = text(12, ink, true);
        tv.setText(name);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(tv, margins(-1, -2, 0, dp(3), 0, 0));
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
        e.setBackground(box(card, line, 16));
        return e;
    }

    /* ---- Typografie (⚙): the home's font family and text size ---- */

    static final String[] FONTS = {"Grotesk", "Kondensiert", "Serif", "Mono"};
    static final String[] FONT_FAMILIES = {"sans-serif", "sans-serif-condensed", "serif", "monospace"};
    static final String[] SIZES = {"Kompakt", "Normal", "Gross"};
    static final float[] SIZE_SCALE = {0.9f, 1f, 1.12f};

    private Typeface face(boolean bold) {
        int f = Math.max(0, Math.min(FONTS.length - 1, prefs.getInt("font", 0)));
        return Typeface.create(FONT_FAMILIES[f], bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    private float fontScale() {
        return SIZE_SCALE[Math.max(0, Math.min(SIZES.length - 1, prefs.getInt("fontSize", 1)))];
    }

    /** Every text made by text()/monoText(): {sp, bold, mono}, so a font change restyles them in place. */
    private final java.util.WeakHashMap<TextView, float[]> styled = new java.util.WeakHashMap<>();

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * fontScale());
        t.setTextColor(colour);
        t.setTypeface(face(bold));
        styled.put(t, new float[]{sp, bold ? 1 : 0, 0});
        return t;
    }

    /** Font or size changed in ⚙: restyle every text live (no restart, settings stay open), with a soft fade. */
    private void applyTypography() {
        float scale = fontScale();
        for (java.util.Map.Entry<TextView, float[]> e : new ArrayList<>(styled.entrySet())) {
            TextView t = e.getKey();
            float[] st = e.getValue();
            if (t == null) continue;
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, st[0] * scale);
            if (st[2] == 0) t.setTypeface(face(st[1] > 0));
        }
        View fade = settingsSheet != null ? settingsSheet : rootFrame;
        fade.setAlpha(0.55f);
        fade.animate().alpha(1f).setDuration(260).start();
        rootFrame.post(this::reflow);
    }

    private TextView monoText(float sp, int colour) {
        TextView t = text(sp, colour, false);
        t.setTypeface(mono, Typeface.BOLD);
        styled.get(t)[2] = 1;
        t.setLetterSpacing(0.12f);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        // Spicy: a 1.5 dp outline and rounder corners – friendly, a bit bold.
        d.setStroke(spicy() ? Math.round(getResources().getDisplayMetrics().density * 1.5f) : dp(1), stroke);
        d.setCornerRadius(dp(spicy() ? radiusDp + 4 : radiusDp));
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

    private PeekDial peek;

    /**
     * Scrolled down, a half dial hangs from the top edge like the clock card peeking out – but
     * it reads something else: battery, the day, the week or the time to the alarm. Tap = next.
     */
    private final class PeekDial extends View {
        private final String[] modes = {"AKKU", "TAG", "WOCHE", "WECKER"};
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean shown;
        private float shownV = -1;
        /** Where the last clock on the page ends (content y): the dial only comes once it's gone. */
        private int clocksEnd;

        void findClocks(View root) {
            int[] end = {0};
            dials.clear();
            walk(root, end);
            clocksEnd = end[0];
            follow(scroll.getScrollY());
        }

        /** The analog clock cards (content y from … to): while one is up top, the dial steps aside. */
        private final java.util.List<int[]> dials = new java.util.ArrayList<>();

        private void walk(View v, int[] end) {
            if (v.getVisibility() != View.VISIBLE) return;
            if (v == clock || "Clock".equals(v.getClass().getSimpleName())) {
                int[] a = new int[2], b = new int[2];
                v.getLocationInWindow(a);
                content.getLocationInWindow(b);
                end[0] = Math.max(end[0], a[1] - b[1] + v.getHeight());
                if (v != clock) dials.add(new int[]{a[1] - b[1], a[1] - b[1] + v.getHeight()});
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), end);
            }
        }
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                if (shown) postDelayed(this, 1000);
            }
        };

        PeekDial() {
            super(MainActivity.this);
            setTranslationY(-dp(400));
            setVisibility(View.INVISIBLE);
            setOnClickListener(v -> {
                prefs.edit().putInt("peekMode", (mode() + 1) % modes.length).apply();
                shownV = -1;
                lastRead = null;
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
            });
            // Hold, then turn: the numbers ring rotates under your finger like a watch bezel, clicking
            // at every mark, and stays where you leave it. Hold and let go without turning = back to the top.
            setOnLongClickListener(v -> {
                turning = true;
                turned = false;
                startAngle = angleAt(touchX, touchY);
                startBezel = bezel;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            });
            bezel = prefs.getFloat("peekBezel", 0);
        }

        private boolean turning, turned, passing;
        private float downRawY;

        private void forward(android.view.MotionEvent e, int action) {
            int[] at = new int[2];
            scroll.getLocationOnScreen(at);
            android.view.MotionEvent ev = android.view.MotionEvent.obtain(e.getDownTime(), e.getEventTime(), action,
                e.getRawX() - at[0], e.getRawY() - at[1], 0);
            scroll.dispatchTouchEvent(ev);
            ev.recycle();
        }
        private float touchX, touchY, startAngle, startBezel, bezel;

        private float angleAt(float x, float y) {
            return (float) Math.toDegrees(Math.atan2(y - peekCy(), x - getWidth() / 2f));
        }

        private int mode() {
            return Math.floorMod(prefs.getInt("peekMode", 0), modes.length);
        }

        /** The gauge hangs from just below the status bar / camera cut-out, so it never covers the lens. */
        private float peekCy() {
            int top = dp(30);
            if (Build.VERSION.SDK_INT >= 30 && getRootWindowInsets() != null) {
                top = getRootWindowInsets().getInsets(WindowInsets.Type.statusBars()).top;
                if (getRootWindowInsets().getDisplayCutout() != null)
                    top = Math.max(top, getRootWindowInsets().getDisplayCutout().getSafeInsetTop());
            }
            return top + dp(18);   // real clearance below the status-bar icons so the dial isn't jammed under them
        }

        private int statusTop() {
            if (Build.VERSION.SDK_INT >= 30 && getRootWindowInsets() != null)
                return getRootWindowInsets().getInsets(WindowInsets.Type.statusBars()).top;
            return dp(24);
        }

        private float radius() {
            return Math.min(dp(108), getResources().getDisplayMetrics().widthPixels * 0.28f);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Math.round(peekCy() + radius() + dp(6)));
        }

        /** Appears once the top cards have scrolled away, slides back up at the top. */
        void follow(int y) {
            // Disabled: it hung from the top centre and collided with the ARCHITECT·DNA header. The
            // clean home keeps the big digital clock; the alarm countdown belongs in Bär, not floating.
            if (!prefs.getBoolean("peekDial", false)) { shown = false; removeCallbacks(tick); setVisibility(View.GONE); return; }
            // Always there (asked for); at the top it hangs small above the big clock, full size once scrolled.
            boolean big = y > Math.max(dp(300), clocksEnd - dp(8));
            float sc = big ? 1f : 0.55f;            // small at the top: clears the ARCHITECT · DNA line
            setPivotX(getWidth() / 2f);
            setPivotY(peekCy());                     // scales from the top edge it hangs from
            if (Math.abs(getScaleX() - sc) > 0.01f) animate().scaleX(sc).scaleY(sc).setDuration(260).start();
            // An analog clock card under it: two dials on top of each other looked messy – step aside.
            int reach = (int) (peekCy() + radius() * sc);
            boolean clash = false;
            for (int[] d : dials) if (d[0] - y < reach && d[1] - y > 0) clash = true;
            boolean want = chat == null && !clash;
            if (want == shown) return;
            shown = want;
            animate().cancel();
            if (want) {
                setVisibility(View.VISIBLE);
                shownV = -1;
                animate().translationY(0).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(0.8f)).start();
                removeCallbacks(tick);
                post(tick);
            } else {
                removeCallbacks(tick);
                animate().translationY(-getHeight() - dp(10)).setDuration(260).withEndAction(() -> {
                    if (!shown) setVisibility(View.INVISIBLE);
                }).start();
            }
        }

        /** Only the disc takes touches; beside it the page stays usable. */
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            int act = e.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN && (dino != null
                || Math.hypot(e.getX() - getWidth() / 2f, e.getY() - peekCy()) > radius())) return false;
            touchX = e.getX();
            touchY = e.getY();
            // A drag that starts on the dial is a scroll of the page, not a press on the dial: hand it
            // over to the page (it got stuck under the dial before).
            if (act == android.view.MotionEvent.ACTION_DOWN) {
                downRawY = e.getRawY();
                passing = false;
            }
            if (!turning && !passing && act == android.view.MotionEvent.ACTION_MOVE
                && Math.abs(e.getRawY() - downRawY) > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                passing = true;
                super.onTouchEvent(android.view.MotionEvent.obtain(e.getDownTime(), e.getEventTime(),
                    android.view.MotionEvent.ACTION_CANCEL, touchX, touchY, 0));
                forward(e, android.view.MotionEvent.ACTION_DOWN);
            }
            if (passing) {
                forward(e, act);
                if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) passing = false;
                return true;
            }
            if (!turning) return super.onTouchEvent(e);
            if (act == android.view.MotionEvent.ACTION_MOVE) {
                float d = angleAt(touchX, touchY) - startAngle;
                if (d > 180) d -= 360;
                if (d < -180) d += 360;
                float next = Math.round((startBezel + d) / 3.6f) * 3.6f;      // clicks into each mark
                if (next != bezel) {
                    bezel = next;
                    turned = true;
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                }
            } else if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) {
                turning = false;
                bezel = ((bezel % 360) + 360) % 360;
                prefs.edit().putFloat("peekBezel", bezel).apply();
                if (!turned && act == android.view.MotionEvent.ACTION_UP) scroll.smoothScrollTo(0, 0);
                super.onTouchEvent(android.view.MotionEvent.obtain(e.getDownTime(), e.getEventTime(),
                    android.view.MotionEvent.ACTION_CANCEL, touchX, touchY, 0));      // no click after the turn
                invalidate();
            }
            return true;
        }

        /** 0..1 on the dial plus the big readout. */
        /** Plugged in (BatteryManager.isCharging() said no on a slow charger, so ask the battery broadcast). */
        private boolean charging() {
            Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return false;
            int st = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            return b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                || st == android.os.BatteryManager.BATTERY_STATUS_CHARGING;
        }

        private boolean overdrive;
        private long checkedAt;
        private Object[] lastRead;

        private Object[] read() {
            // The overdrive redraws every frame: the battery is asked once a second, not 60 times.
            long now = android.os.SystemClock.uptimeMillis();
            if (lastRead != null && now - checkedAt < 1000) return lastRead;
            checkedAt = now;
            return lastRead = readNow();
        }

        private Object[] readNow() {
            Calendar c = Calendar.getInstance();
            int m = mode();
            overdrive = charging();
            if (m == 0 || overdrive) {                 // charging: the battery takes over, in overdrive
                android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
                int pct = bm == null ? 0 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                String sub = "AKKU";
                if (overdrive) {
                    long full = bm != null && Build.VERSION.SDK_INT >= 28 ? bm.computeChargeTimeRemaining() : -1;
                    sub = full > 0 ? String.format(Locale.ROOT, "⚡ VOLL IN %d:%02d", full / 3600_000, full / 60_000 % 60) : pct >= 100 ? "⚡ VOLL" : "⚡ LÄDT";
                }
                return new Object[]{pct / 100f, pct + "%", sub};
            }
            if (m == 1) {
                float f = (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)) / 1440f;
                return new Object[]{f, Math.round(f * 100) + "%", "TAG VORBEI"};
            }
            if (m == 2) {
                int d = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;           // Monday 0
                float f = (d * 1440 + c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)) / (7f * 1440);
                return new Object[]{f, Math.round(f * 100) + "%", "WOCHE · " + new SimpleDateFormat("EE", Locale.GERMAN).format(c.getTime()).replace(".", "").toUpperCase(Locale.GERMAN)};
            }
            if (!prefs.getBoolean("alarmOn", false)) return new Object[]{0f, "AUS", "WECKER"};
            long in = Math.max(0, AlarmScheduler.next(prefs) - System.currentTimeMillis()) / 60_000;
            return new Object[]{1 - Math.min(1f, in / 1440f), in / 60 + "h " + String.format(Locale.ROOT, "%02d", in % 60), "BIS WECKER"};
        }

        @Override protected void onDraw(Canvas cv) {
            if (dino != null) {                 // a dino visit / fight owns the top of the screen
                postInvalidateDelayed(500);
                return;
            }
            Object[] r = read();
            float v = (Float) r[0];
            shownV = shownV < 0 ? 0 : shownV + (v - shownV) * 0.16f;
            if (Math.abs(shownV - v) > 0.002f) postInvalidateOnAnimation();
            boolean night = isNight();
            int face = night ? 0xFF1A1A18 : 0xFFFAFAF8, ink = night ? 0xFFF4F2EC : 0xFF1A1A1A, ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98,
                rust = night ? 0xFFD9472F : 0xFFB5271E;
            if (spicy()) {
                face = pal()[Themes.PANEL] | 0xFF000000;
                ink = MainActivity.this.ink;
                ink3 = MainActivity.this.ink3;
                rust = MainActivity.this.rust;
            }
            float R = radius(), cx = getWidth() / 2f, cy = peekCy();
            cv.clipRect(0, cy, getWidth(), getHeight());        // a true half-dial: nothing draws above the flat edge (clears the camera)
            p.setStyle(Paint.Style.FILL);
            p.setColor(face);
            cv.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(night ? 0x33FFFFFF : 0x22000000);
            cv.drawCircle(cx, cy, R, p);
            // Filled arc from 0 (left) round the bottom to the value.
            p.setStrokeWidth(dp(4));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(rust);
            float ar = R - dp(9);
            android.graphics.RectF ring = new android.graphics.RectF(cx - ar, cy - ar, cx + ar, cy + ar);
            cv.drawArc(ring, 180, -180 * shownV, false, p);
            float rev = 0, tt = android.os.SystemClock.uptimeMillis() / 1000f;
            if (overdrive) {
                // Overdrive: the charge level glows green, a spark runs along it, the rim pulses, and every
                // 3 s the needle revs up into the red zone and drops back – like an engine on the dyno.
                float pulse = 0.5f + 0.5f * (float) Math.sin(tt * 5);
                p.setColor(0xFF2BBF6A);
                p.setStrokeWidth(dp(6));
                cv.drawArc(ring, 180, -180 * shownV, false, p);
                p.setStrokeWidth(dp(2));
                p.setColor(((int) (60 + 120 * pulse) << 24) | 0x2BBF6A);
                cv.drawCircle(cx, cy, R - dp(1.5f), p);
                // Red zone 8–10, glowing.
                p.setStrokeWidth(dp(5));
                p.setColor(((int) (90 + 140 * pulse) << 24) | 0xE5482C);
                cv.drawArc(ring, 180 - 180 * 0.8f, -180 * 0.2f, false, p);
                // The spark.
                float sp = (tt % 1.2f) / 1.2f * shownV;
                double sa = Math.toRadians(180 - 180 * sp);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                cv.drawCircle(cx + (float) Math.cos(sa) * ar, cy + (float) Math.sin(sa) * ar, dp(3.5f), p);
                p.setColor(0x66B6FFCB);
                cv.drawCircle(cx + (float) Math.cos(sa) * ar, cy + (float) Math.sin(sa) * ar, dp(8), p);
                p.setStyle(Paint.Style.STROKE);
                // The rev: up fast, hold with a shake, fall back.
                float ph = (tt % 3.2f) / 3.2f;
                float up = ph < 0.18f ? (float) Math.sin(ph / 0.18f * Math.PI / 2) : ph < 0.4f ? 1 : Math.max(0, 1 - (ph - 0.4f) / 0.35f);
                rev = up * (0.97f - shownV) + (float) Math.sin(tt * 47) * 0.006f * (0.3f + up);
                if (up > 0.95f) {
                    cv.translate((float) Math.sin(tt * 61) * dp(0.8f), 0);
                    p.setColor(0x88FFA62B);
                    p.setStrokeWidth(dp(3));
                    cv.drawArc(ring, 180 - 180 * shownV, -180 * (0.97f - shownV), false, p);
                }
                postInvalidateOnAnimation();
            }
            // Ticks and the numbers 0–10, like the clock face.
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.MONOSPACE);
            if (turning) {          // turning: the bezel lights up
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(spicy() ? pal()[Themes.GLOW_B] : rust);
                cv.drawCircle(cx, cy, R - dp(2), p);
            }
            // The bezel turns the full ring of marks; 0–10 sit on the half you see when it's not turned.
            for (int i = 0; i < 100; i++) {
                double a = Math.toRadians(180 - i * 3.6 + bezel);
                boolean big = i % 5 == 0;
                if (bezel == 0 && i > 50) break;
                int label = i <= 50 ? i / 5 : (100 - i) / 5;
                float r1 = R - dp(big ? 26 : 20), r2 = R - dp(15);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(big ? ink : ink3);
                p.setStrokeWidth(dp(big ? 2.4f : 1));
                cv.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
                if (big) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(ink3);
                    p.setTextSize(dp(11));
                    float rn = R - dp(34);
                    cv.drawText(String.valueOf(label), cx + (float) Math.cos(a) * rn, cy + (float) Math.sin(a) * rn + dp(4), p);
                }
            }
            // Readout.
            p.setStyle(Paint.Style.FILL);
            p.setColor(ink);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(22));
            cv.drawText((String) r[1], cx, cy + R * 0.36f, p);
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(ink3);
            p.setTextSize(dp(9.5f));
            p.setLetterSpacing(0.18f);
            cv.drawText((String) r[2], cx, cy + R * 0.50f, p);
            p.setLetterSpacing(0);
            // Pointer on the rim (no centre needle: it ran through the readout).
            double a = Math.toRadians(180 - 180 * Math.min(1f, shownV + rev));
            float n1 = R - dp(46), n2 = R - dp(14);          // stays inside the face
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(3));
            p.setColor(ink);
            cv.drawLine(cx + (float) Math.cos(a) * n1, cy + (float) Math.sin(a) * n1, cx + (float) Math.cos(a) * n2, cy + (float) Math.sin(a) * n2, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(rust);
            cv.drawCircle(cx + (float) Math.cos(a) * n1, cy + (float) Math.sin(a) * n1, dp(4), p);
            // Little dots: which reading is on.
            for (int i = 0; i < modes.length; i++) {
                p.setColor(i == (overdrive ? 0 : mode()) ? (overdrive ? 0xFF2BBF6A : rust) : ink3);
                cv.drawCircle(cx + (i - 1.5f) * dp(9), cy + R * 0.58f, dp(i == mode() ? 2.6f : 1.8f), p);
            }
        }
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

    /**
     * Xiaomi switches to the three buttons when another app is home ("can't use full screen gestures
     * with third party launchers"). Its own switch force_fsg_nav_bar lets the gestures work anyway;
     * writing it needs WRITE_SECURE_SETTINGS, which only adb can grant (once, it survives updates).
     */
    private void xiaomiGestures() {
        String maker = Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        if (!maker.contains("xiaomi") && !maker.contains("redmi") && !maker.contains("poco")) return;
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (Settings.Global.getInt(getContentResolver(), "force_fsg_nav_bar", 0) != 1) {
                Settings.Global.putInt(getContentResolver(), "force_fsg_nav_bar", 1);
                DiagLog.log("xiaomi: full-screen gestures forced on");
                pop("Gesten an – die Wischleiste ist zurück.");
            }
        } catch (RuntimeException e) {
            DiagLog.log("xiaomi gestures: " + e.getMessage());
        }
    }


    /* ================= Tablet dashboard: folders ================= */

    private Dashboard dashboard;
    private View dashboardView;
    private final View[] folderHead = new View[3], folderBlock = new View[3];
    private FrameLayout folderPanel;

    /** With the dashboard: the section just added hides; its content opens from the dock. */
    private void intoFolder(int which) {
        int n = content.getChildCount();
        folderHead[which] = content.getChildAt(n - 2);
        folderBlock[which] = content.getChildAt(n - 1);
        if (dashboard == null) return;              // phone: just remembered (the swept home opens them as folders)
        folderHead[which].setVisibility(View.GONE);
        folderBlock[which].setVisibility(View.GONE);
    }

    /** The folder full of apps, as a card over the home; the section itself is borrowed and given back. */
    private void openFolder(int which) {
        View block = folderBlock[which];
        if (block == null || folderPanel != null) return;
        int index = content.indexOfChild(block);
        int wasVis = block.getVisibility();
        content.removeView(block);
        unfrost(block, 4);          // rows blurred by the home's fold effect would open blurred in the folder
        block.setVisibility(View.VISIBLE);
        block.setAlpha(1f);
        block.setTranslationY(0);
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        LinearLayout cardV = new LinearLayout(this);
        cardV.setOrientation(LinearLayout.VERTICAL);
        cardV.setPadding(dp(24), dp(20), dp(24), dp(24));
        GradientDrawable g = new GradientDrawable();
        g.setColor(panel);
        g.setStroke(dp(1), line);
        g.setCornerRadius(dp(28));
        cardV.setBackground(g);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(26, ink, true);
        t.setText(new String[]{"Apps", "ARC-DNA", "Zuletzt"}[which]);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = text(20, ink, true);
        x.setText("✕");
        x.setPadding(dp(12), dp(4), dp(4), dp(4));
        head.addView(x);
        cardV.addView(head);
        ScrollView sv = new ScrollView(this);
        sv.addView(block, new FrameLayout.LayoutParams(-1, -2));
        cardV.addView(sv, margins(-1, -2, 0, dp(16), 0, 0));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Math.min(rootFrame.getWidth() - dp(48), dp(900)), -2, Gravity.CENTER);
        scrim.addView(cardV, cl);
        Runnable close = () -> {
            FrameLayout p = folderPanel;
            folderPanel = null;
            if (p == null) return;
            p.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                sv.removeView(block);
                block.setVisibility(wasVis);
                content.addView(block, Math.min(index, content.getChildCount()));
                rootFrame.removeView(p);
            }).start();
        };
        x.setOnClickListener(v -> close.run());
        scrim.setOnClickListener(v -> close.run());
        cardV.setClickable(true);
        // Starting an app from the folder closes it too.
        block.post(() -> {
            if (which == 2 && !lastRecent.isEmpty()) renderRecentCarousel(lastRecent);
        });
        folderPanel = scrim;
        rootFrame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setAlpha(0f);
        cardV.setScaleX(0.9f);
        cardV.setScaleY(0.9f);
        scrim.animate().alpha(1f).setDuration(180).start();
        cardV.animate().scaleX(1f).scaleY(1f).setDuration(240)
            .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
        folderClose = close;
    }

    private Runnable folderClose;


    /* ================= Modes: presets of apps and widgets ================= */

    /*
     * One tap at the top switches what the home is for: the APPS row shows that mode's apps (the
     * ones installed), widgets appear above it, and surprises follow the mode (calm for school and
     * work). "Standard" is the home as before.
     */
    private static final String[][] MODES = {
        // name, emoji, widgets, calm, apps (package or own activity, first installed wins per slot)
        {"Standard", "◎", "", "0", ""},
        {"Schule", "📚", "clock,weather", "1",
            "calendar|com.google.android.calendar|com.android.calendar;BoardActivity;RecorderActivity;com.anthropic.claude;"
                + "com.google.android.apps.bard|com.google.android.apps.gemini;com.google.android.keep|com.miui.notes;"
                + "com.google.android.apps.docs;WeckerActivity"},
        {"Arbeit", "💼", "clock,live", "1",
            "ch.protonmail.android|com.google.android.gm;calendar|com.google.android.calendar;com.anthropic.claude;"
                + "com.microsoft.teams;com.google.android.apps.docs;com.github.android;BoardActivity;phone"},
        {"Draussen", "🧭", "weather,live", "0",
            "maps|com.google.android.apps.maps;ch.sbb.mobile.android.b2c;RouteActivity;CameraActivity;GlobusActivity;"
                + "phone;GalleryActivity;EnergyActivity"},
        {"Entspannen", "🌿", "garden,clock", "0",
            "com.google.android.apps.youtube.music;PlayerActivity;GalleryActivity;FreqActivity;com.google.android.youtube;"
                + "com.instagram.android;com.spotify.music;CalmActivity"},
        {"Zuhause", "🏠", "clock,garden", "0",
            "phone;sms;calendar|com.google.android.calendar;EnergyActivity;WeckerActivity;GalleryActivity;"
                + "com.google.android.apps.youtube.music;com.android.chrome"},
        {"Social", "💬", "clock,weather", "0",
            "com.whatsapp;com.instagram.android;org.telegram.messenger;sms;phone;com.zhiliaoapp.musically;"
                + "com.linkedin.android;CameraActivity"},
        {"Kino", "🍿", "", "1",
            "com.netflix.mediaclient;com.disney.disneyplus;ch.srgssr.playsuisse;com.amazon.avod.thirdpartyclient;"
                + "com.google.android.youtube;com.plexapp.android;PlayerActivity;com.google.android.apps.youtube.music"}};

    private LinearLayout widgetStrip;
    private Dashboard phoneWidgets;

    private int mode() {
        return Math.max(0, Math.min(MODES.length - 1, prefs.getInt("mode", 0)));
    }

    private View modeBar() {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        for (int i = 0; i < MODES.length; i++) {
            int m = i;
            TextView c = monoText(12, i == mode() ? (isNight() ? 0xFF121211 : 0xFFFFFFFF) : ink2);
            c.setText(MODES[i][1] + "  " + MODES[i][0].toUpperCase(Locale.GERMAN));
            c.setLetterSpacing(0.08f);
            c.setPadding(dp(14), dp(9), dp(14), dp(9));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            g.setColor(i == mode() ? rust : card);
            g.setStroke(dp(1), i == mode() ? rust : line);
            c.setBackground(g);
            c.setOnClickListener(v -> setMode(m));
            row.addView(c, margins(-2, -2, i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        modeBarView = hs;
        return hs;
    }

    private View modeBarView;

    private void setMode(int m) {
        prefs.edit().putInt("mode", m).putBoolean("calm", "1".equals(MODES[m][3])).apply();
        // Repaint the bar, the apps and the widgets.
        int at = content.indexOfChild(modeBarView);
        if (at >= 0) {
            content.removeView(modeBarView);
            content.addView(modeBar(), at, margins(-1, -2, 0, dp(16), 0, 0));
        }
        appTilesBox.removeAllViews();
        appTilesBox.addView(appTiles());
        renderWidgets();
        if (m > 0) say(MODES[m][1] + " " + MODES[m][0] + "-Modus.", 1200);
        // Kino: popcorn first – the microwave runs 2:30, full screen.
        if ("Kino".equals(MODES[m][0])) startActivity(new Intent(this, PopcornActivity.class));
        if (calm()) {
            if (f1) setF1(false);
            if (dino != null) endDino();
        }
    }

    /** The mode's widgets, full width, stacked (the tablet dashboard has its own). */
    private void renderWidgets() {
        if (widgetStrip == null) return;
        widgetStrip.removeAllViews();
        String w = MODES[mode()][2];
        if (dashboard != null) {
            // Tablet: the mode picks the dashboard's cards, and its apps sit right on the page.
            dashboard.showOnly(w);
            boolean show = mode() > 0;
            if (folderHead[0] != null && content.indexOfChild(folderBlock[0]) >= 0) {
                folderHead[0].setVisibility(show ? View.VISIBLE : View.GONE);
                folderBlock[0].setVisibility(show ? View.VISIBLE : View.GONE);
                if (show && content.indexOfChild(folderHead[0]) != content.indexOfChild(dashboardView) - 2) {
                    // The mode's apps go right above the dashboard's cards.
                    content.removeView(folderHead[0]);
                    content.removeView(folderBlock[0]);
                    int at = content.indexOfChild(dashboardView);
                    content.addView(folderHead[0], at);
                    content.addView(folderBlock[0], at + 1);
                }
            }
            return;
        }
        if (w.isEmpty()) return;
        if (phoneWidgets == null) phoneWidgets = new Dashboard(this, new int[]{ink, ink2, ink3, rust, card, line}, null);
        for (String kind : w.split(",")) {
            int h = kind.equals("clock") ? 210 : kind.equals("garden") ? 150 : 190;
            widgetStrip.addView(phoneWidgets.widget(kind), margins(-1, dp(h), 0, dp(10), 0, 0));
        }
    }

    /** The APPS row for the current mode: {label, icon, intent} of what's installed, or null for the standard row. */
    private Object[][] modeApps() {
        int m = mode();
        if (m == 0) return null;
        PackageManager pm = getPackageManager();
        List<Object[]> out = new ArrayList<>();
        for (String slot : MODES[m][4].split(";")) {
            for (String c : slot.split("\\|")) {
                Object[] it = resolveModeApp(pm, c);
                if (it != null) {
                    out.add(it);
                    break;
                }
            }
            if (out.size() >= 8) break;
        }
        return out.isEmpty() ? null : out.toArray(new Object[0][]);
    }

    private Object[] resolveModeApp(PackageManager pm, String c) {
        switch (c) {
            case "phone":
                return new Object[]{"Telefon", R.drawable.ic_phone, new Intent(this, DialerActivity.class)};
            case "sms":
                return new Object[]{"SMS", R.drawable.ic_sms, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)};
            case "calendar":
                return new Object[]{"Kalender", R.drawable.ic_calendar, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)};
            case "maps":
                return new Object[]{"Karten", R.drawable.ic_map, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)};
            default:
                break;
        }
        if (!c.contains(".")) {
            // One of ARC-DNA's own apps.
            try {
                Class<?> k = Class.forName(getPackageName() + "." + c);
                String name = c.equals("BoardActivity") ? "Dr. Overloaded" : c.equals("RecorderActivity") ? "Rekorder"
                    : c.equals("WeckerActivity") ? "Bär-Wecker" : c.equals("RouteActivity") ? "Routen" : c.equals("CameraActivity") ? "ARC Kamera"
                    : c.equals("GlobusActivity") ? "Globus" : c.equals("GalleryActivity") ? "Galerie" : c.equals("EnergyActivity") ? "Energie"
                    : c.equals("PlayerActivity") ? "ARC Player" : c.equals("FreqActivity") ? "VOID FREQ"
                    : c.equals("CalmActivity") ? "Gelassen" : c;
                return new Object[]{name, ownIcon(k.getName(), getDrawable(R.drawable.ic_cube)), new Intent(this, k)};
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        Intent launch = pm.getLaunchIntentForPackage(c);
        if (launch == null) return null;
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(c, 0);
            return new Object[]{pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), launch};
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }


    /* ================= Pixel art on the home (still wallpaper) ================= */

    private PixelLayer pixelLayer;

    /** The pixel squares in the wallpaper grid's geometry (screen coordinates), half see-through. */
    private final class PixelLayer extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] at = new int[2];
        private java.util.Map<Long, Integer> cells = new HashMap<>();
        private long mod = -1;

        PixelLayer() {
            super(MainActivity.this);
        }

        /** Re-read when the file changed (back from the editor). */
        void refresh() {
            long m = PixelArt.file(MainActivity.this).lastModified();
            if (m != mod) {
                mod = m;
                cells = PixelArt.load(MainActivity.this);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (cells.isEmpty() || liveWallpaperActive()) return;
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            getLocationOnScreen(at);
            c.save();
            c.translate(-at[0], -at[1]);
            // Softer on the home than on the lock screen, so text stays readable.
            PixelArt.draw(c, cells, PixelArt.scale(dm.widthPixels, dm.heightPixels), p, 80);
            c.restore();
        }
    }


    /* ================= Chat with the buddy (Gemini-style overlay) ================= */

    private FrameLayout chat;
    private LinearLayout chatList;
    private EditText chatInput;
    private ScrollView chatScroll;
    private static final int REQ_SPEAK = 77;

    /** Tap the buddy: a glowing overlay from the bottom with a chat and an "Ask …" pill (text or voice). */
    private void openChat() {
        if (chat != null || rootFrame == null) return;
        String name = BUDDIES[buddy()][0];
        chat = new FrameLayout(this);
        chat.setClickable(true);
        // The glow: a soft rainbow rising from the bottom edge, like Gemini's.
        View glow = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), t = android.os.SystemClock.uptimeMillis() / 1000f;
                p.setShader(new android.graphics.LinearGradient(0, 0, 0, h * 0.7f, 0xA6000000, 0xE8000000, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                int[] hues = {0xFF3C8DFF, 0xFFA77BFF, 0xFFFF4FA0, 0xFFFFB627, 0xFF2BBF6A, 0xFF3C8DFF};
                for (int i = 0; i < 5; i++) {
                    float x = w * (i / 4f) + (float) Math.sin(t * 0.8 + i) * w * 0.12f;
                    p.setShader(new android.graphics.RadialGradient(x, h, w * 0.55f, (hues[i] & 0x00FFFFFF) | 0x66000000, 0x00000000,
                        android.graphics.Shader.TileMode.CLAMP));
                    c.drawCircle(x, h, w * 0.55f, p);
                }
                p.setShader(null);
                postInvalidateOnAnimation();
            }
        };
        chat.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        chat.setOnClickListener(v -> closeChat());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClickable(true);
        int navBottom = 0;
        if (Build.VERSION.SDK_INT >= 30 && rootFrame.getRootWindowInsets() != null) {
            navBottom = rootFrame.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()).bottom;
        }
        col.setPadding(dp(16), 0, dp(16), dp(16) + navBottom);
        // The keyboard pushes the whole chat up (it used to cover pill, chips and bubbles).
        int navB = navBottom;
        col.setOnApplyWindowInsetsListener((v, in) -> {
            int ime = Build.VERSION.SDK_INT >= 30 ? in.getInsets(WindowInsets.Type.ime()).bottom : 0;
            v.setPadding(dp(16), 0, dp(16), dp(16) + Math.max(navB, ime));
            return in;
        });
        chatScroll = new ScrollView(this);
        chatScroll.setVerticalFadingEdgeEnabled(true);          // older bubbles fade out instead of a hard cut
        chatScroll.setFadingEdgeLength(dp(48));
        chatScroll.setVerticalScrollBarEnabled(false);
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(chatList);
        col.addView(chatScroll, new LinearLayout.LayoutParams(-1, -2, 0f));
        // Quick chips.
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        hs.addView(chips);
        for (String q : new String[]{"Wecker?", "Akku?", "Aufräumen 🧹", "Musik heute?", "Witz", "Öffne Gelassen", "Termin morgen 13:10 Arzt"}) {
            TextView c = text(13, 0xFF1A1A1A, true);
            c.setText(q);
            c.setPadding(dp(14), dp(9), dp(14), dp(9));
            c.setBackground(box(0xF2FFFFFF, 0x00000000, 20));
            c.setOnClickListener(v -> ask(q));
            chips.addView(c, margins(-2, -2, 0, 0, dp(8), 0));
        }
        col.addView(hs, margins(-1, -2, 0, dp(10), 0, dp(10)));
        // The pill: + (Claude), text, mic.
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(6), dp(8), dp(6));
        pill.setBackground(box(0xFAFFFFFF, 0x00000000, 34));
        pill.setElevation(dp(6));
        TextView plus = text(26, 0xFF1A1A1A, false);
        plus.setText("+");
        plus.setPadding(dp(6), 0, dp(10), 0);
        plus.setOnClickListener(v -> askClaude(chatInput.getText().toString()));
        pill.addView(plus);
        chatInput = new EditText(this);
        chatInput.setHint("Frag " + BUDDIES[buddy()][1].replaceFirst("^(der|die|das) ", "") + " …");
        chatInput.setHintTextColor(0xFF6F6E69);
        chatInput.setTextColor(0xFF1A1A1A);
        chatInput.setTextSize(19);
        chatInput.setBackground(null);
        chatInput.setSingleLine(true);
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInput.setOnEditorActionListener((v, id, ev) -> {
            befriend(2);
            ask(chatInput.getText().toString());
            return true;
        });
        pill.addView(chatInput, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView mic = text(20, 0xFF1A1A1A, false);
        mic.setText("🎙");
        mic.setGravity(Gravity.CENTER);
        GradientDrawable mg = new GradientDrawable();
        mg.setShape(GradientDrawable.OVAL);
        mg.setColor(0xFFC9D8FF);
        mic.setBackground(mg);
        mic.setOnClickListener(v -> listen());
        pill.addView(mic, new LinearLayout.LayoutParams(dp(52), dp(52)));
        col.addView(pill, new LinearLayout.LayoutParams(-1, dp(66)));
        chat.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        rootFrame.addView(chat, new FrameLayout.LayoutParams(-1, -1));
        // Above the floating buttons (they have elevation), which step aside; the buddy hops up
        // and sits on top of the chat, talking.
        chat.setElevation(dp(10));
        if (gameButton != null) gameButton.animate().alpha(0f).setDuration(150).start();
        if (claudeFab != null) claudeFab.animate().alpha(0f).setDuration(150).start();
        fox.setElevation(dp(12));
        fox.bringToFront();
        chatCol = col;
        blurHome(true);
        fox.animate().cancel();
        fox.setAlpha(1f);                  // the broom visit fades it out; an interrupted one left it invisible
        floatStart = android.os.SystemClock.uptimeMillis();
        handler.removeCallbacks(buddyFloat);
        handler.postDelayed(buddyFloat, 60);
        chat.setAlpha(0f);
        col.setTranslationY(dp(120));
        chat.animate().alpha(1f).setDuration(200).start();
        col.animate().translationY(0).setDuration(280).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        // The buddy opens with its call and something useful.
        String[] calls = BUDDY_CALLS[buddy()];
        java.util.List<String> tips = buddyTips();
        String tip = buddy() == 4 ? DUCK_TRAITS[random.nextInt(DUCK_TRAITS.length)] : tips.isEmpty() ? "Was kann ich für dich tun?" : tips.get(tipIndex++ % tips.size());
        voice.say(calls[random.nextInt(calls.length)]);
        bubbleMsg(calls[random.nextInt(calls.length)] + " " + tip, false);
    }

    /* ================= Broom: now and then the buddy sweeps the clutter away ================= */

    private static final long SWEEP_FOR = 4 * 3600_000L;
    private TextView sweptChip;
    private boolean sweeping;

    private boolean swept() {
        return prefs.getLong("sweptUntil", 0) > System.currentTimeMillis();
    }

    /** The extras the broom takes away: game button, seismo line, ghosts; the pixel art goes faint. */
    private View[] clutter() {
        return new View[]{gameButton, seismo, ghosts};
    }

    /** Every 6 hours at most, 1 in 3 returns home, when nothing else is going on (the home needs it). */
    private void maybeSweep() {
        long now = System.currentTimeMillis();
        if (pro() || prefs.getBoolean("calm", false) || swept() || sweeping || dino != null || game != null || chat != null || f1
            || !homeInFront || now - prefs.getLong("sweepAt", 0) < 6 * 3600_000L || random.nextInt(3) != 0) return;
        sweep();
    }

    /** The buddy walks in from the right with a broom and sweeps across the bottom; what it passes is gone. */
    private void sweep() {
        if (rootFrame == null || rootFrame.getWidth() == 0 || sweeping || pro()) return;
        sweeping = true;
        prefs.edit().putLong("sweepAt", System.currentTimeMillis()).apply();
        DiagLog.log("broom sweep");
        if (fox != null) fox.animate().alpha(0f).setDuration(200).start();
        if (bubble != null) bubble.setVisibility(View.GONE);
        SweepView sv = new SweepView();
        sweepView = sv;
        rootFrame.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        sv.setElevation(dp(14));
    }

    private SweepView sweepView;

    private void sweepDone(SweepView sv) {
        if (sweepView != sv) return;          // already finished (left the home mid-sweep)
        sweepView = null;
        rootFrame.removeView(sv);
        sweeping = false;
        prefs.edit().putLong("sweptUntil", System.currentTimeMillis() + SWEEP_FOR).apply();
        content.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            applySwept();
            content.animate().alpha(1f).setDuration(320).start();
        }).start();
        if (fox != null) {
            fox.animate().alpha(1f).setDuration(300).start();
            hop();
        }
        scroll.smoothScrollTo(0, 0);
        say("☕ Aufgeräumt! ↺ unten holt alles zurück.", 5000);
    }

    /** Hide the clutter while swept (again after every resume: applyPro shows it otherwise). */
    private void applySwept() {
        boolean on = swept() && !pro();
        strip(on);
        if (on) {
            for (View v : clutter()) if (v != null) v.setVisibility(View.GONE);
            if (pixelLayer != null) pixelLayer.setAlpha(0.25f);
        } else if (pixelLayer != null) pixelLayer.setAlpha(1f);
        if (on && sweptChip == null && rootFrame != null) {
            sweptChip = text(10, ink2, true);
            sweptChip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            sweptChip.setText("☕ ↺ ALLES ZURÜCK");
            sweptChip.setPadding(dp(12), dp(5), dp(12), dp(5));
            sweptChip.setBackground(box(card, line, 18));
            sweptChip.setElevation(dp(6));
            sweptChip.setOnClickListener(v -> unsweep());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            int nav = 0;
            if (Build.VERSION.SDK_INT >= 30 && rootFrame.getRootWindowInsets() != null)
                nav = rootFrame.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()).bottom;
            lp.bottomMargin = dp(40) + nav;
            rootFrame.addView(sweptChip, lp);
        } else if (!on && sweptChip != null) {
            rootFrame.removeView(sweptChip);
            sweptChip = null;
        }
    }

    private LinearLayout minimalBlock;
    private final java.util.Map<View, Integer> stripped = new java.util.HashMap<>();

    /**
     * The real clean-up: below the clock only three apps, the folders, the weather and the music
     * card stay; every other section is hidden until ↺ (or 2 hours).
     */
    private void strip(boolean on) {
        if (content == null || widgetStrip == null) return;
        if (!on) {
            for (java.util.Map.Entry<View, Integer> e : stripped.entrySet()) e.getKey().setVisibility(e.getValue());
            stripped.clear();
            if (minimalBlock != null) content.removeView(minimalBlock);
            minimalBlock = null;
            return;
        }
        int from = content.indexOfChild(widgetStrip) - 1;           // the mode bar: everything above (clock) stays
        if (from < 0) return;
        if (minimalBlock == null || content.indexOfChild(minimalBlock) < 0) {
            minimalBlock = buildMinimal();
            content.addView(minimalBlock, from, margins(-1, -2, 0, dp(18), 0, 0));
            from++;
        } else from = content.indexOfChild(minimalBlock) + 1;
        View musicBlock = musicView;
        while (musicBlock != null && musicBlock.getParent() != content) {
            musicBlock = musicBlock.getParent() instanceof View ? (View) musicBlock.getParent() : null;
        }
        int mi = musicBlock == null ? -1 : content.indexOfChild(musicBlock);
        for (int i = from; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v == minimalBlock || i == mi || i == mi - 1) continue;      // the music card and its title stay
            if (!stripped.containsKey(v)) stripped.put(v, v.getVisibility());
            v.setVisibility(View.GONE);
        }
        if (musicBlock != null) musicBlock.setVisibility(View.VISIBLE);
    }

    /** Three apps, three folders, the weather. */
    private LinearLayout buildMinimal() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout apps = new LinearLayout(this);
        Object[][] items = mainItems();
        for (int i = 0; i < Math.min(3, items.length); i++) {
            Object[] it = items[i];
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(8), dp(14), dp(8), dp(12));
            b.setBackground(box(card, line, 20));
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(it[1] instanceof Integer ? getDrawable((Integer) it[1]) : (Drawable) it[1]);
            b.addView(ic, new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView l = text(13, ink, true);
            l.setText((String) it[0]);
            l.setPadding(0, dp(8), 0, 0);
            b.addView(l);
            Intent in = (Intent) it[2];
            b.setOnClickListener(v -> open(in));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = i < 2 ? dp(10) : 0;
            apps.addView(b, lp);
        }
        box.addView(apps);
        LinearLayout folders = new LinearLayout(this);
        String[] names = {"▦  Apps", "◆  ARC-DNA", "◷  Zuletzt"};
        for (int i = 0; i < 3; i++) {
            TextView f = monoText(12, ink);
            f.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            f.setText(names[i]);
            f.setGravity(Gravity.CENTER);
            f.setPadding(dp(6), dp(14), dp(6), dp(14));
            f.setBackground(box(card, line, 18));
            int which = i;
            f.setOnClickListener(v -> openFolder(which));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = i < 2 ? dp(10) : 0;
            folders.addView(f, lp);
        }
        box.addView(folders, margins(-1, -2, 0, dp(10), 0, 0));
        if (phoneWidgets == null) phoneWidgets = new Dashboard(this, new int[]{ink, ink2, ink3, rust, card, line}, null);
        box.addView(phoneWidgets.widget("weather"), margins(-1, dp(190), 0, dp(12), 0, 0));
        return box;
    }

    private void unsweep() {
        prefs.edit().putLong("sweptUntil", 0).apply();
        if (ghosts != null) ghosts.setVisibility(View.VISIBLE);
        applyPro();
        applySwept();
        if (pixelLayer != null) pixelLayer.setAlpha(1f);
        hop();
        say("Alles wieder da. Volles Programm! 🎉", 2500);
    }

    private static final float COFFEE = 7f;        // walk in, pour slowly, a sip – then the broom

    /** The sweep itself: first a coffee, then buddy + broom along the bottom, dust behind, clutter fading as the broom passes. */
    private final class SweepView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long born = android.os.SystemClock.uptimeMillis();
        private final Drawable buddyArt = getDrawable(BUDDY_ART[buddy()]).mutate();
        private final List<float[]> dust = new ArrayList<>();       // x, y, vx, vy, born, size
        private final java.util.Set<View> gone = new java.util.HashSet<>();
        private float lastT;

        SweepView() {
            super(MainActivity.this);
            setClickable(false);
        }

        /** Buddy pours a coffee into a mug: stream, rising level, steam. */
        private void coffee(Canvas c, float w, float h, float t) {
            float size = Math.min(dp(120), w * 0.26f), ground = h * 0.86f;
            float in = Math.min(1, t / 0.4f), mx = w * 0.56f, my = ground;
            // Buddy.
            c.save();
            c.translate(w * 0.32f, ground + (1 - in) * dp(80));
            float aw = size, ah = size * buddyArt.getIntrinsicHeight() / (float) Math.max(1, buddyArt.getIntrinsicWidth());
            buddyArt.setBounds((int) (-aw / 2), (int) -ah, (int) (aw / 2), 0);
            buddyArt.draw(c);
            c.restore();
            // Mug.
            float mw = dp(54), mh = dp(60);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFF4F2EC);
            c.drawRoundRect(mx - mw / 2, my - mh, mx + mw / 2, my, dp(8), dp(8), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(5));
            p.setColor(0xFFF4F2EC);
            c.drawArc(mx + mw / 2 - dp(10), my - mh * 0.8f, mx + mw / 2 + dp(18), my - mh * 0.25f, -90, 180, false, p);
            // Coffee level rises while pouring (1.3 … 4.8 s).
            float lvl = Math.max(0, Math.min(1, (t - 1.3f) / 3.5f));
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF5A3B24);
            c.drawRect(mx - mw / 2 + dp(4), my - dp(4) - (mh - dp(12)) * lvl, mx + mw / 2 - dp(4), my - dp(4), p);
            p.setColor(rust);
            c.drawRect(mx - mw / 2, my - mh * 0.55f, mx + mw / 2, my - mh * 0.45f, p);
            // Pot, tilted, with the stream.
            // Tilts towards the mug (clockwise: the spout on its right dips down over the mug).
            float tilt = t < 0.6f ? 0 : t < 1.2f ? (t - 0.6f) / 0.6f : t < 4.8f ? 1 : Math.max(0, 1 - (t - 4.8f) / 0.6f);
            tilt = tilt * tilt * (3 - 2 * tilt);
            float pvx = mx - dp(46), pvy = my - mh - dp(58), ang = 60 * tilt;
            c.save();
            c.translate(pvx, pvy);
            c.rotate(ang);
            p.setColor(0xFF1A1A1A);
            c.drawRoundRect(-dp(30), -dp(28), dp(20), dp(26), dp(10), dp(10), p);
            p.setColor(0xFF3A3A38);
            c.drawRect(-dp(30), -dp(30), dp(20), -dp(20), p);
            android.graphics.Path sp = new android.graphics.Path();
            sp.moveTo(dp(18), -dp(8));
            sp.lineTo(dp(34), -dp(18));
            sp.lineTo(dp(22), dp(4));
            sp.close();
            c.drawPath(sp, p);
            c.restore();
            if (t > 1.15f && t < 4.9f) {
                // The stream leaves the spout's tip and falls into the mug.
                double ar = Math.toRadians(ang);
                float lx = dp(34), ly = -dp(18);
                float tipX = pvx + (float) (lx * Math.cos(ar) - ly * Math.sin(ar));
                float tipY = pvy + (float) (lx * Math.sin(ar) + ly * Math.cos(ar));
                float endX = mx - dp(6), endY = my - dp(6) - (mh - dp(12)) * lvl, wob = (float) Math.sin(t * 30) * dp(1.2f);
                android.graphics.Path st = new android.graphics.Path();
                st.moveTo(tipX, tipY);
                st.quadTo(tipX + dp(6) + wob, (tipY + endY) / 2, endX, endY);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(dp(5));
                p.setColor(0xFF6B4528);
                c.drawPath(st, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x88C08A5A);
                c.drawCircle(endX + wob, endY, dp(4), p);       // little splash
            }
            // Steam.
            if (lvl > 0.3f) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2.5f));
                p.setColor(0x99FFFFFF);
                for (int i = -1; i <= 1; i++) {
                    android.graphics.Path st = new android.graphics.Path();
                    float bx = mx + i * dp(12), base = my - mh - dp(6);
                    st.moveTo(bx, base);
                    for (int k = 1; k <= 6; k++) st.lineTo(bx + (float) Math.sin(t * 4 + k + i) * dp(5), base - k * dp(7));
                    c.drawPath(st, p);
                }
            }
            // Its line.
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(15));
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextAlign(Paint.Align.CENTER);
            String line = t < 5.2f ? "Erst mal Kaffee ☕" : "Ahh. So – jetzt wird aufgeräumt! 🧹";
            float tw = p.measureText(line) + dp(24), tx = w * 0.32f, ty = ground - size * 1.2f - dp(14);
            tx = Math.max(tw / 2 + dp(8), Math.min(w - tw / 2 - dp(8), tx));
            p.setColor(0xF2FFFFFF);
            c.drawRoundRect(tx - tw / 2, ty - dp(26), tx + tw / 2, ty + dp(10), dp(16), dp(16), p);
            p.setColor(0xFF1A1A1A);
            c.drawText(line, tx, ty - dp(3), p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), t = (android.os.SystemClock.uptimeMillis() - born) / 1000f, dt = Math.min(0.05f, t - lastT);
            lastT = t;
            if (t < COFFEE) {
                coffee(c, w, h, t);
                postInvalidateOnAnimation();
                return;
            }
            t -= COFFEE;
            float dur = 4.6f, f = Math.min(1, t / dur);
            float size = Math.min(dp(120), w * 0.26f);
            float bx = w + size - (w + size * 2.4f) * f;                // buddy x (walks right to left)
            float ground = h * 0.86f, bob = (float) Math.abs(Math.sin(t * 9)) * dp(6);
            float sw = (float) Math.sin(t * 11);                         // the sweep stroke
            float broomX = bx - size * 0.55f + sw * dp(18);
            // What the broom passes fades out.
            for (View v : clutter()) {
                if (v == null || v.getVisibility() != View.VISIBLE || gone.contains(v)) continue;
                int[] a = new int[2], me = new int[2];
                v.getLocationOnScreen(a);
                getLocationOnScreen(me);
                if (broomX < a[0] - me[0] + v.getWidth() / 2f) {
                    gone.add(v);
                    v.animate().alpha(0f).translationX(-dp(60)).setDuration(350).withEndAction(() -> {
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                        v.setTranslationX(0);
                    }).start();
                    for (int i = 0; i < 10; i++) dust.add(new float[]{a[0] - me[0] + v.getWidth() / 2f, a[1] - me[1] + v.getHeight() / 2f,
                        -dp(140) - random.nextFloat() * dp(160), -dp(60) * random.nextFloat(), t, dp(6 + 10 * random.nextFloat())});
                }
            }
            if (pixelLayer != null) pixelLayer.setAlpha(Math.max(0.25f, 1 - f * 1.2f));
            // Dust kicked up by the bristles.
            if (f < 1 && random.nextFloat() < 0.8f)
                dust.add(new float[]{broomX - dp(10), ground, -dp(80) - random.nextFloat() * dp(120), -dp(30) - random.nextFloat() * dp(70), t, dp(5 + 9 * random.nextFloat())});
            p.setStyle(Paint.Style.FILL);
            for (int i = dust.size() - 1; i >= 0; i--) {
                float[] d = dust.get(i);
                float age = t - d[4];
                if (age > 1.1f) {
                    dust.remove(i);
                    continue;
                }
                d[0] += d[2] * dt;
                d[1] += d[3] * dt;
                d[3] += dp(40) * dt;
                p.setColor(isNight() ? 0xFF8A8A86 : 0xFFB8B6AE);
                p.setAlpha((int) (170 * (1 - age / 1.1f)));
                c.drawCircle(d[0], d[1], d[5] * (1 + age), p);
            }
            p.setAlpha(255);
            // Broom: stick from the buddy's paws down to the bristles in front.
            float hx = bx - size * 0.1f, hy = ground - size * 0.55f - bob;
            float fx = broomX, fy = ground - dp(6);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(5));
            p.setColor(0xFF8A5A2B);
            c.drawLine(hx, hy, fx + dp(10), fy - dp(16), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFE0B54A);
            android.graphics.Path br = new android.graphics.Path();
            br.moveTo(fx + dp(16), fy - dp(22));
            br.lineTo(fx + dp(4), fy - dp(24));
            br.lineTo(fx - dp(22), fy + dp(4));
            br.lineTo(fx + dp(22), fy + dp(4));
            br.close();
            c.drawPath(br, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.4f));
            p.setColor(0xFF9C7A22);
            for (int i = -3; i <= 3; i++) c.drawLine(fx + dp(6) + i * dp(1.5f), fy - dp(20), fx + i * dp(6), fy + dp(3), p);
            // The buddy, walking (facing left), with a little sway.
            c.save();
            c.translate(bx, ground - bob);
            c.rotate(-6 + sw * 4);
            c.scale(-1, 1);
            float aw = size, ah = size * buddyArt.getIntrinsicHeight() / (float) Math.max(1, buddyArt.getIntrinsicWidth());
            buddyArt.setBounds((int) (-aw / 2), (int) -ah, (int) (aw / 2), 0);
            buddyArt.draw(c);
            c.restore();
            if (t < 1.6f) {
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(dp(15));
                p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                p.setTextAlign(Paint.Align.CENTER);
                String line = "Hier ist's mir zu voll! 🧹";
                float tw = p.measureText(line) + dp(24), ty = ground - size * 1.25f - dp(10), tx = Math.max(tw / 2 + dp(8), Math.min(w - tw / 2 - dp(8), bx));
                p.setColor(0xF2FFFFFF);
                c.drawRoundRect(tx - tw / 2, ty - dp(26), tx + tw / 2, ty + dp(10), dp(16), dp(16), p);
                p.setColor(0xFF1A1A1A);
                c.drawText(line, tx, ty - dp(3), p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            if (t > dur + 1.1f) {
                post(() -> sweepDone(this));
                return;
            }
            postInvalidateOnAnimation();
        }
    }

    private View chatCol;
    private long floatStart, chatHopAt;

    /** Where a view sits inside rootFrame by layout alone (its own and its parents' translations left out). */
    private float[] placeIn(View v) {
        float x = v.getLeft(), y = v.getTop();
        android.view.ViewParent p = v.getParent();
        while (p instanceof View && p != rootFrame) {
            View pv = (View) p;
            x += pv.getLeft() - pv.getScrollX();
            y += pv.getTop() - pv.getScrollY();
            p = pv.getParent();
        }
        return new float[]{x, y};
    }

    /** The home behind the chat goes soft, so the bubbles read clearly. */
    private void blurHome(boolean on) {
        if (Build.VERSION.SDK_INT < 31) return;
        android.graphics.RenderEffect fx = on ? android.graphics.RenderEffect.createBlurEffect(dp(14), dp(14), android.graphics.Shader.TileMode.CLAMP) : null;
        if (scroll != null) scroll.setRenderEffect(fx);
        if (pixelLayer != null) pixelLayer.setRenderEffect(fx);
    }

    /*
     * While chatting the buddy floats in the free space above the chat: a slow figure-eight with a
     * gentle bob and tilt, facing the way it drifts – never over the chat or off screen.
     */
    private final Runnable buddyFloat = new Runnable() {
        @Override
        public void run() {
            if (chat == null || chatCol == null || fox == null) return;
            // Layout places inside the root (no transforms: flipping and tilting the duck skewed
            // getLocationOnScreen, so it ended up between the bubbles).
            float[] f = placeIn(fox), cc = placeIn(chatCol);
            int[] r = {0, 0};
            float baseX = f[0], baseY = f[1];
            float chatTop = cc[1] + chatCol.getTranslationY() + chatScroll.getTop();
            float w = rootFrame.getWidth(), topY = dp(70), bottomY = chatTop - fox.getHeight() - dp(16);
            if (bottomY < topY) bottomY = topY;
            float t = (android.os.SystemClock.uptimeMillis() - floatStart) / 1000f;
            float cxp = r[0] + (w - fox.getWidth()) / 2f, ampX = (w - fox.getWidth()) / 2f - dp(16);
            float cyp = (topY + bottomY) / 2f, ampY = (bottomY - topY) / 2f;
            float x = cxp + (float) Math.sin(t * 0.45) * ampX;
            float y = cyp + (float) Math.sin(t * 0.9) * ampY * 0.8f + (float) Math.sin(t * 3.2) * dp(5);
            // Ease in from where it sat for the first moment.
            float in = Math.min(1f, t / 0.8f);
            in = in * in * (3 - 2 * in);
            // A hop while chatting: a quick bounce up from wherever it floats.
            float hk = (android.os.SystemClock.uptimeMillis() - chatHopAt) / 400f;
            if (hk >= 0 && hk < 1) y -= (float) Math.sin(Math.PI * hk) * dp(26);
            float tx = (x - baseX) * in, ty = (y - baseY) * in;
            if (in >= 1) {
                // Glide (the free space changes when a bubble arrives) instead of jumping.
                tx = fox.getTranslationX() + (tx - fox.getTranslationX()) * 0.15f;
                ty = fox.getTranslationY() + (ty - fox.getTranslationY()) * (hk >= 0 && hk < 1 ? 0.5f : 0.15f);
            }
            fox.setTranslationX(tx);
            fox.setTranslationY(ty);
            float vx = (float) Math.cos(t * 0.45);
            fox.setScaleX(vx < 0 ? -1f : 1f);
            fox.setRotation((float) Math.sin(t * 1.3) * 6);
            handler.postDelayed(this, 16);
        }
    };

    private void closeChat() {
        FrameLayout c = chat;
        chat = null;
        if (c == null) return;
        hideKeyboard();
        blurHome(false);
        c.animate().alpha(0f).setDuration(180).withEndAction(() -> rootFrame.removeView(c)).start();
        handler.removeCallbacks(buddyFloat);
        fox.setScaleX(1f);
        fox.animate().translationX(0).translationY(0).rotation(0).setDuration(360).withEndAction(() -> fox.setElevation(0)).start();
        if (gameButton != null) gameButton.animate().alpha(1f).setDuration(200).start();
        if (claudeFab != null) claudeFab.animate().alpha(1f).setDuration(200).start();
    }

    private void bubbleMsg(String text, boolean mine) {
        if (chatList == null) return;
        TextView t = text(15, mine ? 0xFFFFFFFF : 0xFF1A1A1A, false);
        t.setText(text);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(box(mine ? rust : 0xF2FFFFFF, 0x00000000, 18));
        t.setMaxWidth((int) (rootFrame.getWidth() * 0.78f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = dp(8);
        chatList.addView(t, lp);
        // Keep the list from covering the whole screen.
        chatScroll.getLayoutParams().height = Math.min(chatList.getChildCount() * dp(70), (int) (rootFrame.getHeight() * 0.45f));
        chatScroll.requestLayout();
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        t.setAlpha(0f);
        t.animate().alpha(1f).setDuration(200).start();
    }

    private void ask(String q) {
        q = q.trim();
        if (q.isEmpty()) return;
        chatInput.setText("");
        bubbleMsg(q, true);
        String answer = answer(q);
        handler.postDelayed(() -> bubbleMsg(answer, false), 350);
        hop();
    }

    /** The buddy's joke book: the joke, and what's funny about it if you ask. */
    private static final String[][] JOKES = {
        {"Warum können Geister so schlecht lügen? Man sieht durch sie hindurch.", "\"Durchschauen\" heisst auch: eine Lüge erkennen. Geister sind durchsichtig – doppelt ertappt."},
        {"Treffen sich zwei Magnete. Sagt der eine: Was soll ich heute bloss anziehen?", "Magnete ziehen an – und \"anziehen\" heisst auch Kleider wählen."},
        {"Was macht ein Pirat am Computer? Er drückt die Enter-Taste.", "Enter klingt wie \"Entern\" – das, was Piraten mit Schiffen machen."},
        {"Wie nennt man einen Bären ohne Ohren? B.", "\"Bär\" klingt wie B + \"är\". Nimm die \"Ohren\" (das \"är\") weg – bleibt nur B. Reiner Klang-Kalauer."},
        {"Was sagt die Ente zum Regen? Endlich Wetter!", "Für Menschen ist Regen schlechtes Wetter – für eine Ente ist's genau ihr Ding."},
        {"Was ist orange und läuft durch den Wald? Eine Wanderine.", "Mandarine + wandern = Wanderine."},
        {"Was ist grün und klopft an die Tür? Ein Klopfsalat.", "Kopfsalat mit einem L mehr – und schon klopft er."},
        {"Wie heisst ein Spanier ohne Auto? Carlos.", "\"Car los\" – das Auto ist los, also weg."},
        {"Was liegt am Strand und spricht undeutlich? Eine Nuschel.", "Muschel + nuscheln (undeutlich reden)."},
        {"Was ist ein Keks unter einem Baum? Ein schattiges Plätzchen.", "Plätzchen = Keks und auch ein gemütlicher Ort im Schatten."},
        {"Was macht ein Clown im Büro? Faxen.", "Faxen = Blödsinn machen, aber auch das alte Büro-Fax."},
        {"Was ist rot und schlecht für die Zähne? Ein Ziegelstein.", "Man erwartet Süssigkeiten – aber es ist ein Ziegelstein. Der Witz ist, dass er wörtlich stimmt."},
        {"Sitzen zwei Kühe auf der Weide. Sagt die eine: Muh. Sagt die andere: Ich wollt's gerade sagen.", "Kühe sagen eh nur Muh – darum ist das so trocken."},
    };
    private final java.util.ArrayList<Integer> jokeBag = new java.util.ArrayList<>();
    private int lastJoke = -1;

    /** "Woher hast du den Witz?", "Versteh ich nicht" … about the joke just told. */
    private String jokeFollowUp(String l) {
        if (lastJoke < 0) return null;
        if (l.contains("woher") || l.contains("quelle") || l.contains("wer hat") || l.contains("selbst ausgedacht") || l.contains("erfunden")) {
            String[] who = {"Aus meinem kleinen Witzebuch – fest in ARC-DNA eingebaut, ganz offline. Klassiker vom Pausenhof, ich hab sie nur gesammelt.",
                "Ehrlich? Uralte Kalauer. Die erzählt man sich in der Schweiz und in Deutschland seit Jahrzehnten – ich hab sie in mein Notizbuch geschrieben.",
                "Nicht selbst erfunden – " + JOKES.length + " Stück stehen in meinem eingebauten Witzebuch. Willst du noch einen?"};
            return who[random.nextInt(who.length)];
        }
        if (l.contains("versteh") || l.contains("warum") || l.contains("wieso") || l.contains("erklär") || l.contains("was heisst") || l.contains("check")) {
            return JOKES[lastJoke][1];
        }
        if (l.contains("schlecht") || l.contains("flach") || l.contains("lahm") || l.contains("nicht lustig") || l.contains("cringe")) {
            return "Autsch. 😅 Okay, der war flach. Sag \"noch einen\" – der nächste wird besser. Vielleicht.";
        }
        if (l.contains("haha") || l.contains("lol") || l.contains("😂") || l.contains("gut") || l.contains("nice")) {
            return "Hehe, freut mich! Noch einen?";
        }
        return null;
    }

    /** On the phone, from what ARC-DNA knows; anything else goes to Claude. */
    private String answer(String q) {
        String l = q.toLowerCase(Locale.GERMAN);
        String follow = jokeFollowUp(l);
        if (follow != null) return follow;
        if (!l.contains("witz") && !l.matches("^(ja|gern|noch|nochmal|mehr|weiter)\\b.*")) lastJoke = -1;
        // Open an app: "öffne X", "starte X", or just its name.
        java.util.regex.Matcher om = java.util.regex.Pattern.compile("^(öffne|starte|open|zeig)\\s+(.+)$").matcher(l);
        String want = om.find() ? om.group(2).trim() : null;
        if (want != null) {
            for (Object[] a : arcList()) {
                if (a[1] != null && ((String) a[0]).toLowerCase(Locale.GERMAN).contains(want)) {
                    Object[] app = a;
                    handler.postDelayed(() -> {
                        closeChat();
                        openApp(new Intent(this, (Class<?>) app[1]), getDrawable((Integer) app[2]));
                    }, 700);
                    return "Mach ich – " + a[0] + " geht auf.";
                }
            }
            for (AppEntry a : apps) {
                if (a.label.toLowerCase(Locale.GERMAN).contains(want)) {
                    handler.postDelayed(() -> {
                        closeChat();
                        launch(a);
                    }, 700);
                    return "Los geht's: " + a.label + ".";
                }
            }
            return "\"" + want + "\" finde ich nicht. Wie heisst die App genau?";
        }
        // One-time reminder: "termin [heute|morgen] HH:MM text".
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("(termin|erinner)\\D*?(heute|morgen|übermorgen)?\\s*(\\d{1,2})[:.](\\d{2})\\s*(.*)").matcher(l);
        if (tm.find()) {
            Calendar cal = Calendar.getInstance();
            if ("morgen".equals(tm.group(2))) cal.add(Calendar.DAY_OF_YEAR, 1);
            if ("übermorgen".equals(tm.group(2))) cal.add(Calendar.DAY_OF_YEAR, 2);
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(tm.group(3)));
            cal.set(Calendar.MINUTE, Integer.parseInt(tm.group(4)));
            cal.set(Calendar.SECOND, 0);
            if (tm.group(2) == null && cal.getTimeInMillis() < System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
            String what = tm.group(5).trim().isEmpty() ? "Termin" : Character.toUpperCase(tm.group(5).trim().charAt(0)) + tm.group(5).trim().substring(1);
            AlarmScheduler.addDeadline(this, cal.getTimeInMillis(), what);
            paintAlarm();
            return "Notiert 🐻 " + new java.text.SimpleDateFormat("EEE HH:mm", Locale.GERMAN).format(cal.getTime()) + " – " + what + ". Der Bär weckt dich.";
        }
        if (l.contains("wecker") || l.contains("alarm") || l.contains("termin")) {
            StringBuilder b = new StringBuilder();
            if (prefs.getBoolean("alarmOn", false)) {
                long in = Math.max(0, AlarmScheduler.next(prefs) - System.currentTimeMillis()) / 60_000;
                b.append("Wecker in ").append(in / 60).append(" h ").append(in % 60).append(" min.");
            } else b.append("Der tägliche Wecker ist aus.");
            AlarmScheduler.Deadline d = AlarmScheduler.nextDeadline(this);
            if (d != null) b.append(" Nächster Termin: ").append(new java.text.SimpleDateFormat("EEE HH:mm", Locale.GERMAN).format(new java.util.Date(d.at)))
                .append(" – ").append(d.label).append('.');
            return b.toString();
        }
        if (l.contains("akku") || l.contains("batter")) {
            android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
            int pct = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return "Akku " + pct + " %" + (bm != null && bm.isCharging() ? ", lädt gerade." : pct <= 20 ? " – bitte bald laden!" : ".");
        }
        if (l.contains("musik") || l.contains("gehört") || l.contains("song")) {
            long[] week = MusicStats.week(this);
            return "Heute " + week[6] / 60 + " h " + week[6] % 60 + " min Musik. Meist gehört: " + MusicStats.favoriteArtist(this) + ".";
        }
        if (l.contains("leiser") || l.contains("lauter")) {
            android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, l.contains("leiser") ? android.media.AudioManager.ADJUST_LOWER
                : android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
            return l.contains("leiser") ? "Etwas leiser." : "Etwas lauter!";
        }
        if (l.contains("zeit") || l.contains("uhr") || l.contains("spät")) {
            return "Es ist " + new java.text.SimpleDateFormat("HH:mm", Locale.GERMAN).format(new java.util.Date()) + ".";
        }
        if (l.contains("witz") || l.contains("lustig") || (lastJoke >= 0 && l.matches("^(ja|gern|noch (einen|einer|eins|mal)|nochmal|mehr|weiter)\\b.*"))) {
            // Every joke once before any repeats.
            if (jokeBag.isEmpty()) {
                for (int i = 0; i < JOKES.length; i++) jokeBag.add(i);
                java.util.Collections.shuffle(jokeBag, random);
                if (jokeBag.size() > 1 && jokeBag.get(0) == lastJoke) jokeBag.add(jokeBag.remove(0));
            }
            lastJoke = jokeBag.remove(0);
            return JOKES[lastJoke][0];
        }
        if (l.matches("^(hallo|hi|hey|servus|grüezi|moin)\\b.*")) return BUDDY_CALLS[buddy()][0] + " Schön, dass du da bist.";
        if (l.contains("aufräum") || l.contains("besen") || l.contains("zu viel") || l.contains("chaos")) {
            handler.postDelayed(() -> {
                closeChat();
                handler.postDelayed(this::sweep, 400);
            }, 900);
            return "Ich hol den Besen! 🧹";
        }
        if (l.contains("danke")) return "Gern! " + BUDDY_CALLS[buddy()][1];
        // Anything else: Claude knows more.
        String quest = q;
        handler.postDelayed(() -> askClaude(quest), 1200);
        return "Das weiss Claude besser – ich frag ihn für dich …";
    }

    /** Hand the question to the Claude app (or claude.ai). */
    private void askClaude(String q) {
        closeChat();
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, q).setPackage("com.anthropic.claude");
        try {
            if (q.trim().isEmpty()) throw new ActivityNotFoundException();
            startActivity(send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            open(appOr("com.anthropic.claude", new Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new?q=" + Uri.encode(q)))));
        }
    }

    private void listen() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "de-CH")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Sag's " + BUDDIES[buddy()][1] + " …");
        try {
            startActivityForResult(i, REQ_SPEAK);
        } catch (RuntimeException e) {
            bubbleMsg("Keine Spracherkennung auf diesem Gerät – tipp's lieber.", false);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SPEAK && res == RESULT_OK && data != null) {
            java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) handler.postDelayed(() -> ask(r.get(0)), 200);
        }
    }

}
