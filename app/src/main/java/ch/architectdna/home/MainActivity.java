package ch.architectdna.home;

import android.animation.ValueAnimator;
import android.app.Activity;
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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
 * ARC-DNA Home — an Architect-DNA styled launcher for the Pixel 9a.
 * Clock, today's focus, a dock of default apps, all apps with search, and the fox.
 * Everything is built in code so the only resources are colours, the theme and two vectors.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String WEB_HOME = "https://architect-dna.ch/home";
    private static final String[] FOX_CALLS = {
        "Yip!", "Yip-yip!", "Gekk-gekk-gekk!", "Wow-wow!", "Ack-ack-ack!", "Mrrp?", "Wa-pa-pa-pa!", "Huff."
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

    private int ink, ink2, ink3, rust, card, line;
    private Typeface mono;
    private ScrollView scroll;
    private LinearLayout content, grid;
    private TextView clock, date, greet, bubble;
    private EditText focus, search;
    private ImageView fox;
    private View shield;
    private boolean hidden;
    private SharedPreferences prefs;
    private final List<AppEntry> apps = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private SensorManager sensors;
    private long lastShake;
    private BroadcastReceiver packageReceiver;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateClock();
            int seconds = Calendar.getInstance().get(Calendar.SECOND);
            handler.postDelayed(this, (60 - seconds) * 1000L + 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        pickColours();
        mono = Typeface.MONOSPACE;
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
        card = night ? 0xD91C1C1A : 0xD9FAFAF8;
        line = night ? 0xFF2C2C29 : 0xFFE0E0D8;
    }

    /* ---------------- UI ---------------- */

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // Tapping empty space (not a tile or field) fades everything away to show the wallpaper.
        content.setOnClickListener(v -> setHidden(true));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        content.addView(label("Architect · DNA"));

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

        content.addView(label("Apps"), margins(-1, -2, 0, dp(26), 0, 0));
        content.addView(dock(), margins(-1, -2, 0, dp(10), 0, 0));

        search = new EditText(this);
        search.setHint("Apps suchen…");
        search.setHintTextColor(ink3);
        search.setTextColor(ink);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setBackground(box(card, line));
        search.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                renderGrid();
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            List<AppEntry> hits = filtered();
            if (!hits.isEmpty()) launch(hits.get(0));
            return true;
        });
        content.addView(label("Alle Apps"), margins(-1, -2, 0, dp(26), 0, 0));
        content.addView(search, margins(-1, -2, 0, dp(10), 0, 0));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        content.addView(grid, margins(-1, -2, 0, dp(10), 0, 0));

        TextView hint = text(11, ink3, false);
        hint.setTypeface(mono);
        hint.setText("Leere Fläche tippen = nur Wallpaper · Fuchs: tippen = Yip · halten = Chat · schütteln = Erdbeben");
        content.addView(hint, margins(-1, -2, 0, dp(22), 0, 0));

        // While hidden, this transparent layer takes the tap that brings everything back,
        // so the faded tiles underneath can't be opened by accident.
        shield = new View(this);
        shield.setVisibility(View.GONE);
        shield.setContentDescription("Apps einblenden");
        shield.setOnClickListener(v -> setHidden(false));
        root.addView(shield, new FrameLayout.LayoutParams(-1, -1));

        // The fox sits bottom-left above the scrolling content.
        fox = new ImageView(this);
        fox.setImageResource(R.drawable.fox);
        fox.setContentDescription("Fuchs");
        fox.setOnClickListener(v -> foxCall());
        fox.setOnLongClickListener(v -> {
            open(new Intent(Intent.ACTION_VIEW, Uri.parse(WEB_HOME)));
            return true;
        });
        FrameLayout.LayoutParams foxLp = new FrameLayout.LayoutParams(dp(84), dp(56), Gravity.BOTTOM | Gravity.START);
        root.addView(fox, foxLp);

        bubble = text(13, Color.WHITE, true);
        bubble.setBackground(box(ink, ink));
        bubble.setTextColor(0xFF000000 | (~ink & 0xFFFFFF));
        bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
        bubble.setVisibility(View.GONE);
        root.addView(bubble, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(dp(20), top + dp(24), dp(20), bottom + dp(96));
            ((FrameLayout.LayoutParams) fox.getLayoutParams()).setMargins(dp(14), 0, 0, bottom + dp(6));
            ((FrameLayout.LayoutParams) bubble.getLayoutParams()).setMargins(dp(70), 0, 0, bottom + dp(58));
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
        box.setBackground(box(card, line));

        TextView k = text(10, ink3, true);
        k.setTypeface(mono, Typeface.BOLD);
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

    private View dock() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        Object[][] items = {
            {"Telefon", new Intent(Intent.ACTION_DIAL)},
            {"SMS", Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)},
            {"Kamera", new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)},
            {"Browser", Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)},
            {"Karten", Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)},
            {"Mail", Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)},
            {"Kalender", Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)},
            {"Web-Home", new Intent(Intent.ACTION_VIEW, Uri.parse(WEB_HOME))},
        };
        PackageManager pm = getPackageManager();
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                wrap.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            String name = (String) items[i][0];
            Intent intent = (Intent) items[i][1];
            Drawable icon = null;
            ResolveInfo ri = pm.resolveActivity(intent, 0);
            if (ri != null && !"android".equals(ri.activityInfo.packageName)) icon = ri.loadIcon(pm);
            if (name.equals("Web-Home")) icon = getDrawable(R.mipmap.ic_launcher);
            View tile = tile(name, icon);
            tile.setOnClickListener(v -> open(intent));
            row.addView(tile, weighted(i % 4 != 0));
        }
        return wrap;
    }

    private View tile(String name, Drawable icon) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(4), dp(12), dp(4), dp(10));
        t.setBackground(box(card, line));

        ImageView iv = new ImageView(this);
        if (icon != null) iv.setImageDrawable(icon);
        t.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView tv = text(11.5f, ink, true);
        tv.setText(name);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(tv, margins(-1, -2, 0, dp(6), 0, 0));
        return t;
    }

    private void renderGrid() {
        if (grid == null) return;
        grid.removeAllViews();
        List<AppEntry> list = filtered();
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                grid.addView(row, margins(-1, -2, 0, i == 0 ? 0 : dp(8), 0, 0));
            }
            AppEntry app = list.get(i);
            View tile = tile(app.label, app.icon);
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
            for (int i = list.size() % 4; i != 0 && i < 4; i++) {
                View spacer = new View(this);
                row.addView(spacer, weighted(true));
            }
        }
        if (list.isEmpty()) {
            TextView none = text(14, ink3, false);
            none.setText(apps.isEmpty() ? "Apps werden geladen…" : "Keine App gefunden.");
            grid.addView(none);
        }
    }

    private List<AppEntry> filtered() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return apps;
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry a : apps) if (a.label.toLowerCase(Locale.ROOT).contains(q)) out.add(a);
        return out;
    }

    /* ---------------- Apps ---------------- */

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
            List<AppEntry> list = new ArrayList<>();
            for (ResolveInfo ri : infos) {
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
                renderGrid();
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
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }
    }

    private void launch(AppEntry app) {
        Intent i = new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        open(i);
    }

    private void open(Intent intent) {
        try {
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Keine passende App gefunden.", Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------------- Wallpaper ---------------- */

    /**
     * Light mode gets the light ARC-DNA wallpaper, dark mode the dark one, on home and lock screen.
     * The activity is recreated when the system switches modes, so this runs on every switch;
     * it only writes the wallpaper when the mode differs from the last one applied.
     * Long-press the clock to turn this off (e.g. to keep a wallpaper of your own).
     */
    private void applyWallpaperIfNeeded() {
        if (!prefs.getBoolean("autoWallpaper", true)) return;
        boolean night = isNight();
        String mode = night ? "dark" : "light";
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

    /* ---------------- Clock & focus ---------------- */

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

    /* ---------------- Fox & quake ---------------- */

    private void foxCall() {
        hop();
        bubble.setText(FOX_CALLS[random.nextInt(FOX_CALLS.length)]);
        bubble.setVisibility(View.VISIBLE);
        bubble.setAlpha(1f);
        bubble.animate().cancel();
        bubble.animate().alpha(0f).setStartDelay(1400).setDuration(300)
            .withEndAction(() -> bubble.setVisibility(View.GONE)).start();
    }

    private void hop() {
        fox.animate().cancel();
        fox.animate().translationY(-dp(26)).setDuration(180)
            .withEndAction(() -> fox.animate().translationY(0).setDuration(220).start()).start();
    }

    private void quake() {
        hop();
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(900);
        a.addUpdateListener(v -> {
            float k = (float) v.getAnimatedValue() * dp(10);
            content.setTranslationX((random.nextFloat() - .5f) * 2 * k);
            content.setTranslationY((random.nextFloat() - .5f) * 2 * k);
            content.setRotation((random.nextFloat() - .5f) * k * .15f);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                content.setTranslationX(0);
                content.setTranslationY(0);
                content.setRotation(0);
            }
        });
        a.start();
        bubble.setText("Whoa.");
        bubble.setVisibility(View.VISIBLE);
        bubble.setAlpha(1f);
        bubble.animate().cancel();
        bubble.animate().alpha(0f).setStartDelay(1200).setDuration(300)
            .withEndAction(() -> bubble.setVisibility(View.GONE)).start();
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

    /* ---------------- Lifecycle ---------------- */

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        ticker.run();
        Sensor acc = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (sensors != null) sensors.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (packageReceiver != null) unregisterReceiver(packageReceiver);
    }

    /** Home pressed while already home: back to the top, search cleared. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetView();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (hidden) setHidden(false);
        else resetView();
    }

    /** Fade the home screen down to a faint ghost so the wallpaper shows, or bring it back. */
    private void setHidden(boolean hide) {
        if (hide == hidden) return;
        hidden = hide;
        prefs.edit().putBoolean("hidden", hide).apply();
        if (hide) {
            hideKeyboard();
            if (focus != null) focus.clearFocus();
            if (search != null) search.clearFocus();
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

    private void resetView() {
        if (search != null && search.getText().length() > 0) search.setText("");
        hideKeyboard();
        if (scroll != null) scroll.smoothScrollTo(0, 0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View v = getCurrentFocus();
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /* ---------------- Helpers ---------------- */

    /** Section label: short rust bar + monospace caps, like the website. */
    private View label(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackgroundColor(rust);
        row.addView(bar, new LinearLayout.LayoutParams(dp(22), dp(2)));
        TextView t = text(11, ink3, true);
        t.setTypeface(mono, Typeface.BOLD);
        t.setLetterSpacing(0.2f);
        t.setText(s.toUpperCase(Locale.GERMAN));
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(8));
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
}
