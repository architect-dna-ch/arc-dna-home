package ch.architectdna.home;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.SearchManager;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
import java.util.List;
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
    private boolean hidden, libraryOpen;

    private final List<AppEntry> apps = new ArrayList<>();
    private BroadcastReceiver packageReceiver;
    private ConnectivityManager.NetworkCallback netCallback;
    private SensorManager sensors;
    private long lastShake;

    private HeadTracker head;
    private int calibFrames;
    private float calibSum, basePitch, smoothTilt;
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
            if (headSpeed == 0) return;
            ScrollView target = libraryOpen ? libScroll : scroll;
            target.scrollBy(0, Math.round(headSpeed));
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

        clock = new TextView(this);
        clock.setTextColor(ink);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 104);
        clock.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        clock.setLetterSpacing(-0.06f);
        clock.setIncludeFontPadding(false);
        clock.setPadding(0, dp(30), 0, 0);
        clock.setOnLongClickListener(v -> {
            boolean on = !prefs.getBoolean("autoWallpaper", true);
            prefs.edit().putBoolean("autoWallpaper", on).remove("wallpaperMode").apply();
            Toast.makeText(this, on ? "Auto-Wallpaper an" : "Auto-Wallpaper aus", Toast.LENGTH_SHORT).show();
            applyWallpaperIfNeeded();
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

        content.addView(label("Apps"), margins(-1, -2, 0, dp(26), 0, 0));
        content.addView(appTiles(), margins(-1, -2, 0, dp(10), 0, 0));

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

        seismo = new SeismoView(this);
        root.addView(seismo, new FrameLayout.LayoutParams(-1, dp(30), Gravity.BOTTOM));

        library = buildLibrary();
        root.addView(library, new FrameLayout.LayoutParams(-1, -1));

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
            content.setPadding(dp(20), top0 + dp(24), dp(20), bottom + dp(96));
            library.setPadding(dp(20), top0 + dp(20), dp(20), bottom + dp(12));
            ((FrameLayout.LayoutParams) seismo.getLayoutParams()).bottomMargin = bottom;
            ((FrameLayout.LayoutParams) fox.getLayoutParams()).setMargins(dp(14), 0, 0, bottom + dp(14));
            ((FrameLayout.LayoutParams) bubble.getLayoutParams()).setMargins(dp(70), 0, 0, bottom + dp(66));
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

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        lib.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Mediathek"), new LinearLayout.LayoutParams(0, -2, 1f));
        headToggle = monoText(11, ink2);
        headToggle.setPadding(dp(10), dp(8), dp(10), dp(8));
        headToggle.setBackground(box(card, line, 20));
        headToggle.setOnClickListener(v -> cycleHeadMode());
        head.addView(headToggle);
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(14), dp(4), dp(4), dp(4));
        close.setContentDescription("Mediathek schliessen");
        close.setOnClickListener(v -> closeLibrary());
        head.addView(close);
        col.addView(head);
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
        col.addView(libSearch, margins(-1, dp(50), 0, dp(14), 0, 0));

        libScroll = new ScrollView(this);
        libScroll.setVerticalScrollBarEnabled(false);
        libGrid = new LinearLayout(this);
        libGrid.setOrientation(LinearLayout.VERTICAL);
        libGrid.setPadding(0, dp(12), 0, dp(80));
        libScroll.addView(libGrid);
        col.addView(libScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
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
    }

    private void closeLibrary() {
        if (!libraryOpen) return;
        libraryOpen = false;
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
        if (!next.equals("off")) startHead();
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
        smoothTilt = 0;
        boolean gaze = m.equals("gaze");
        head.start(gaze ? HeadTracker.Mode.GAZE : HeadTracker.Mode.NOD);
        showHeadState(0);
        headLabel.setVisibility(View.VISIBLE);
        Toast.makeText(this, gaze
            ? "Kurz in die Bildschirmmitte schauen … dann unten = runter, oben = rauf"
            : "Kopf gerade halten … dann nicken zum Scrollen", Toast.LENGTH_LONG).show();
    }

    private void stopHead() {
        if (head != null) head.stop();
        headSpeed = 0;
        handler.removeCallbacks(headScroller);
        headLabel.setVisibility(View.GONE);
    }

    /** Top bar while active: mode plus ↑ / ↓ / · so you can see what the camera reads. */
    private void showHeadState(int dir) {
        String arrow = dir < 0 ? "↑" : dir > 0 ? "↓" : "·";
        headLabel.setText("◉ " + (headMode().equals("gaze") ? "BLICK " : "NICKEN ") + arrow + "  ");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
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
     * BLICK: the first frames (looking at the middle of the screen) set the rest point.
     * Eye movements within the phone's height are small, so the threshold is low (0.04 of the
     * blendshape range) and full speed is reached 0.12 beyond it.
     * Looking toward the bottom edge scrolls down slowly and steadily, back to the middle stops,
     * looking at the top edge / into the camera scrolls up. No drift here, so a long look holds.
     */
    @Override
    public void onGaze(float vertical) {
        if (calibFrames < 12) {
            calibSum += vertical;
            if (++calibFrames == 12) basePitch = calibSum / 12f;
            return;
        }
        float delta = vertical - basePitch;
        smoothTilt = smoothTilt * 0.6f + delta * 0.4f;
        // Starts at 0.04, keeps going down to 0.025 (hysteresis), so it doesn't flicker at the edge.
        float dead = headSpeed == 0 ? 0.04f : 0.025f;
        float over = Math.abs(smoothTilt) - dead;
        if (over <= 0) {
            setHeadSpeed(0);
            return;
        }
        float speed = (0.35f + 0.65f * Math.min(1f, over / 0.12f)) * dp(9);
        setHeadSpeed(smoothTilt > 0 ? speed : -speed); // eyes down scrolls down
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
        boolean wasIdle = headSpeed == 0;
        headSpeed = speed;
        showHeadState(speed < 0 ? -1 : speed > 0 ? 1 : 0);
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
        say(FOX_CALLS[random.nextInt(FOX_CALLS.length)], 1400);
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
        Sensor acc = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        if (!headMode().equals("off")
            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startHead();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
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
    }

    /** Home pressed while already home: close the Mediathek, back to the top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeLibrary();
        hideKeyboard();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (libraryOpen) closeLibrary();
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
