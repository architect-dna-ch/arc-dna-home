package ch.architectdna.one;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ARC One — a home screen with exactly one thing on it: what you're going for right now.
 *
 * The launcher grew into a remote with 70 buttons. This one shows a single sentence, as big as the
 * screen allows, with how long you've held it. Come back after an hour or more and it asks
 * "Noch dran?" — keep going, write a new one, or mark it done (done ones are counted). Swipe up
 * anywhere for a search field over your apps; nothing else lives on the screen.
 *
 * Launcher Nr. 5 — its own app (ch.architectdna.one), updates itself from the public mirror.
 */
public class OneHomeActivity extends Activity {

    private static final long STALE = 60 * 60_000L;          // ask "Noch dran?" after an hour away
    private static final int INK = 0xFFF4F1EC, INK2 = 0xFFA9A39A, INK3 = 0xFF6F6A63,
        BG = 0xFF0E0D0C, CARD = 0xFF1B1916, LINE = 0xFF2B2824, RUST = 0xFFE0573B;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private FrameLayout root;
    private TextView clock, date, label, intent, since, doneCount;
    private EditText editor;
    private TextView editDone;
    private LinearLayout checkIn;
    private FrameLayout drawer;
    private EditText search;
    private LinearLayout appList;
    private final List<App> apps = new ArrayList<>();
    private SelfUpdater updater;
    private TextView upd;
    private String updText = "";
    private TextView m1Title;
    static final String YT_MUSIC = "com.google.android.apps.youtube.music";
    private long appsLoadedAt;

    private static final class App { String label, pkg, cls; Drawable icon; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins.consumeSystemWindowInsets();
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(26), dp(22), dp(26), dp(18));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // top: time + date, a quiet ⋯ for the rest
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tc = new LinearLayout(this); tc.setOrientation(LinearLayout.VERTICAL);
        clock = text(30, INK, Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = text(13, INK3, Typeface.DEFAULT);
        tc.addView(clock); tc.addView(date);
        top.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView more = text(24, INK3, Typeface.DEFAULT_BOLD); more.setText("⋯");
        more.setPadding(dp(14), dp(4), 0, dp(4));
        more.setOnClickListener(v -> showMenu());
        top.addView(more);
        col.addView(top);

        // centre: the one sentence
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1f));

        label = text(11, RUST, Typeface.create("monospace", Typeface.BOLD));
        label.setText(L.t("— MEIN ZIEL GERADE"));
        label.setLetterSpacing(0.2f);
        mid.addView(label);

        intent = text(40, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        intent.setLetterSpacing(-0.03f);
        intent.setLineSpacing(0, 0.98f);
        intent.setAutoSizeTextTypeUniformWithConfiguration(26, 84, 2, TypedValue.COMPLEX_UNIT_SP);
        intent.setOnClickListener(v -> openEditor());
        mid.addView(intent, new LinearLayout.LayoutParams(-1, dp(300)));

        editor = new EditText(this);
        editor.setTextColor(INK);
        editor.setHintTextColor(INK3);
        editor.setTextSize(34);
        editor.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        editor.setBackground(null);
        editor.setHint(L.t("Was willst du gerade erreichen?"));
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setImeOptions(EditorInfo.IME_ACTION_DONE);   // one sentence: Enter saves …
        editor.setHorizontallyScrolling(false);               // … but long text still wraps
        editor.setMaxLines(6);
        editor.setOnEditorActionListener((v, id, e) -> { saveEditor(); return true; });
        editor.setVisibility(View.GONE);
        mid.addView(editor, new LinearLayout.LayoutParams(-1, -2));

        editDone = pill(L.t("Fertig"), RUST, 0xFFFFFFFF);
        editDone.setOnClickListener(v -> saveEditor());
        editDone.setVisibility(View.GONE);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2); el.topMargin = dp(8);
        mid.addView(editDone, el);

        since = text(13, INK2, Typeface.DEFAULT);
        since.setPadding(0, dp(10), 0, 0);
        mid.addView(since);

        // "Noch dran?" — shown when you come back after an hour or more
        checkIn = new LinearLayout(this);
        checkIn.setOrientation(LinearLayout.VERTICAL);
        checkIn.setVisibility(View.GONE);
        TextView ask = text(15, INK, Typeface.DEFAULT_BOLD); ask.setText(L.t("Noch dran?"));
        checkIn.addView(ask);
        LinearLayout btns = new LinearLayout(this);
        TextView yes = pill(L.t("Ja, weiter"), INK, BG);
        TextView neu = pill(L.t("Neu schreiben"), CARD, INK);
        TextView done = pill(L.t("Erledigt ✓"), CARD, INK);
        yes.setOnClickListener(v -> { prefs.edit().putLong("one_checked", now()).apply(); render(); });
        neu.setOnClickListener(v -> openEditor());
        done.setOnClickListener(v -> markDone());
        for (TextView t : new TextView[]{yes, neu, done}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(8);
            btns.addView(t, p);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(10);
        checkIn.addView(btns, bl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(22);
        mid.addView(checkIn, cl);

        // Music One: your one track, three times
        LinearLayout m1 = new LinearLayout(this);
        m1.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable mb = new GradientDrawable(); mb.setColor(CARD); mb.setCornerRadius(dp(16)); mb.setStroke(dp(1), LINE);
        m1.setBackground(mb);
        m1.setPadding(dp(16), dp(12), dp(12), dp(12));
        TextView note = text(18, RUST, Typeface.DEFAULT_BOLD); note.setText("♪");
        m1.addView(note);
        LinearLayout mt = new LinearLayout(this); mt.setOrientation(LinearLayout.VERTICAL); mt.setPadding(dp(12), 0, 0, 0);
        TextView mLabel = text(10, INK3, Typeface.create("monospace", Typeface.BOLD)); mLabel.setText("MUSIC ONE"); mLabel.setLetterSpacing(0.18f);
        m1Title = text(15, INK, Typeface.DEFAULT_BOLD);
        m1Title.setSingleLine(true);
        m1Title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mt.addView(mLabel); mt.addView(m1Title);
        m1.addView(mt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView play = pill("▶  3×", INK, BG);
        play.setOnClickListener(v -> playMusicOne());
        m1.addView(play);
        m1.setOnClickListener(v -> playMusicOne());
        m1.setOnLongClickListener(v -> {
            Toast.makeText(this, L.t("Song ändern: in YouTube Music auf «Teilen» → ARC One."), Toast.LENGTH_LONG).show();
            return true;
        });
        LinearLayout.LayoutParams m1l = new LinearLayout.LayoutParams(-1, -2); m1l.bottomMargin = dp(10);
        col.addView(m1, m1l);

        // bottom: achieved count + the one way to everything else
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        doneCount = text(12, INK3, Typeface.create("monospace", Typeface.NORMAL));
        bottom.addView(doneCount, new LinearLayout.LayoutParams(0, -2, 1f));
        upd = text(12, RUST, Typeface.DEFAULT_BOLD);
        upd.setText(L.t("Update ↻"));
        upd.setPadding(dp(10), dp(10), dp(4), dp(10));
        upd.setVisibility(View.GONE);
        upd.setOnClickListener(v -> updater.fix(() -> Toast.makeText(this, L.t("Update gerade nicht erreichbar."), Toast.LENGTH_SHORT).show()));
        bottom.addView(upd);
        TextView appsBtn = text(13, INK2, Typeface.DEFAULT_BOLD);
        appsBtn.setText(L.t("⌃  Apps"));
        appsBtn.setPadding(dp(14), dp(10), 0, dp(10));
        appsBtn.setOnClickListener(v -> openDrawer());
        bottom.addView(appsBtn);
        col.addView(bottom);

        buildDrawer();

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (vy < -900 && Math.abs(vy) > Math.abs(vx)) { openDrawer(); return true; }
                return false;
            }
        });
        col.setOnTouchListener((v, e) -> gd.onTouchEvent(e));

        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        updater = new SelfUpdater(this, "one-v", (txt, need) -> {
            updText = txt;
            if (upd != null) upd.setVisibility(need ? View.VISIBLE : View.GONE);
        });
        new Thread(this::loadApps, "arc-one-apps").start();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        tick.run();
        if (now() - appsLoadedAt > 5 * 60_000L) new Thread(this::loadApps, "arc-one-apps").start();
        if (updater != null) updater.check(false);
    }

    @Override protected void onPause() { super.onPause(); main.removeCallbacks(tick); }

    /** Pressing Home while already here: close whatever is open, back to the sentence. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (SelfUpdater.ACTION.equals(i.getAction())) { updater.onStatus(i); return; }
        closeDrawer();
        if (editor.getVisibility() == View.VISIBLE) saveEditor();
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else if (editor.getVisibility() == View.VISIBLE) saveEditor();
        // a home screen has nowhere to go back to
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date d = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.GERMAN).format(d));
            date.setText(new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMAN).format(d));
            renderSince();
            main.postDelayed(this, 30_000);
        }
    };

    /* ---------------- the sentence ---------------- */

    private void render() {
        String t = prefs.getString("one_text", "");
        boolean has = !t.isEmpty();
        intent.setText(has ? t : L.t("Was willst du gerade erreichen?"));
        intent.setTextColor(has ? INK : INK3);
        label.setText(has ? L.t("— MEIN ZIEL GERADE") : L.t("— TIPPEN ZUM SCHREIBEN"));
        boolean stale = has && now() - prefs.getLong("one_checked", now()) >= STALE;
        checkIn.setVisibility(stale && editor.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        int n = doneList().length();
        doneCount.setText(n == 0 ? "" : n + L.t(" erreicht"));
        renderSince();
        renderMusicOne();
    }

    private void renderMusicOne() {
        if (m1Title == null) return;
        String t = prefs.getString("m1_title", "Eyes (Slowed)"), a = prefs.getString("m1_artist", "HAIX");
        m1Title.setText(a.isEmpty() ? t : t + "  ·  " + a);
    }

    /** Opens your track in YouTube Music (the exact link if you shared one, else by search), and asks the
     *  ARC-DNA hub — it holds the media access — to play it 3× and pause after. */
    private void playMusicOne() {
        String link = prefs.getString("m1_link", "");
        String q = (prefs.getString("m1_title", "Eyes (Slowed)") + " " + prefs.getString("m1_artist", "HAIX")).trim();
        Intent i = !link.isEmpty()
            ? new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))
            : new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(android.app.SearchManager.QUERY, q)
                .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean started = false;
        try { startActivity(new Intent(i).setPackage(YT_MUSIC)); started = true; } catch (Exception ignored) { }
        if (!started) try { startActivity(i); started = true; } catch (Exception ignored) { }
        if (!started) { Toast.makeText(this, L.t("YouTube Music ist nicht installiert."), Toast.LENGTH_SHORT).show(); return; }
        boolean counted = false;
        try {
            sendBroadcast(new Intent().setClassName("ch.architectdna.home", "ch.architectdna.home.RepeatWatch$Receiver").putExtra("times", 3));
            counted = isInstalled("ch.architectdna.home");
        } catch (Exception ignored) { }
        Toast.makeText(this, counted ? L.t("Music One · 3×, dann Pause.") : L.t("Music One läuft (3× braucht die ARC-DNA-App)."), Toast.LENGTH_SHORT).show();
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    private void renderSince() {
        String t = prefs.getString("one_text", "");
        if (t.isEmpty()) { since.setText(""); return; }
        since.setText(L.t("seit ") + ago(now() - prefs.getLong("one_since", now())) + L.t(" · tippen zum Ändern"));
    }

    private void openEditor() {
        closeDrawer();
        editor.setText(prefs.getString("one_text", ""));
        editor.setSelection(editor.getText().length());
        intent.setVisibility(View.GONE);
        since.setVisibility(View.GONE);
        checkIn.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        editDone.setVisibility(View.VISIBLE);
        editor.requestFocus();
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) editor.post(() -> im.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT));
    }

    private void saveEditor() {
        String t = editor.getText().toString().trim().replace('\n', ' ');
        String old = prefs.getString("one_text", "");
        SharedPreferences.Editor e = prefs.edit();
        if (!t.equals(old)) e.putString("one_text", t).putLong("one_since", now());
        e.putLong("one_checked", now()).apply();
        hideKeyboard(editor);
        editor.setVisibility(View.GONE);
        editDone.setVisibility(View.GONE);
        intent.setVisibility(View.VISIBLE);
        since.setVisibility(View.VISIBLE);
        render();
    }

    private void markDone() {
        String t = prefs.getString("one_text", "");
        if (t.isEmpty()) return;
        JSONArray a = doneList();
        try { a.put(new JSONObject().put("t", t).put("at", now())); } catch (Exception ignored) { }
        JSONArray keep = new JSONArray();
        for (int i = Math.max(0, a.length() - 100); i < a.length(); i++) keep.put(a.opt(i));
        prefs.edit().putString("one_done", keep.toString()).putString("one_text", "").apply();
        Toast.makeText(this, L.t("Erreicht ✓ — was als Nächstes?"), Toast.LENGTH_SHORT).show();
        render();
        openEditor();
    }

    private JSONArray doneList() {
        try { return new JSONArray(prefs.getString("one_done", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /* ---------------- ⋯ : what you achieved, and how to make this the home ---------------- */

    private void showMenu() {
        closeDrawer();
        final FrameLayout sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0xEE0E0D0C);
        sheet.setClickable(true);
        ScrollView sv = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(26), dp(40), dp(26), dp(40));
        sv.addView(c);
        sheet.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView h = text(26, INK, Typeface.create("sans-serif-black", Typeface.NORMAL)); h.setText(L.t("Erreicht"));
        c.addView(h);
        JSONArray a = doneList();
        if (a.length() == 0) { TextView e = text(14, INK2, Typeface.DEFAULT); e.setText(L.t("Noch nichts abgehakt. Das kommt.")); e.setPadding(0, dp(8), 0, 0); c.addView(e); }
        for (int i = a.length() - 1; i >= 0 && i >= a.length() - 30; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            TextView r = text(15, INK, Typeface.DEFAULT);
            r.setText("✓  " + o.optString("t"));
            r.setPadding(0, dp(12), 0, 0);
            c.addView(r);
            TextView w = text(11, INK3, Typeface.DEFAULT);
            w.setText(new SimpleDateFormat("EEE d. MMM, HH:mm", Locale.GERMAN).format(new Date(o.optLong("at"))));
            c.addView(w);
        }
        TextView setHome = pill(L.t("Als Startbildschirm festlegen"), CARD, INK);
        setHome.setOnClickListener(v -> { try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); } catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } } });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = dp(30);
        c.addView(setHome, sl);
        TextView all = pill(L.t("Alle ARC-Werkzeuge öffnen"), CARD, INK);
        all.setOnClickListener(v -> {
            try { startActivity(new Intent().setClassName("ch.architectdna.home", "ch.architectdna.home.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { Toast.makeText(this, L.t("ARC-DNA ist nicht installiert."), Toast.LENGTH_SHORT).show(); }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2); al.topMargin = dp(10);
        c.addView(all, al);
        TextView ver = text(11, INK3, Typeface.create("monospace", Typeface.NORMAL));
        ver.setText("ARC One 0." + (updater == null ? "" : updater.current()) + (updText.isEmpty() ? "" : "  ·  " + updText));
        ver.setPadding(0, dp(18), 0, 0);
        c.addView(ver);
        TextView close = pill(L.t("Schliessen"), INK, BG);
        close.setOnClickListener(v -> root.removeView(sheet));
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(-2, -2); xl.topMargin = dp(10);
        c.addView(close, xl);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
    }

    /* ---------------- apps: one search field, nothing else ---------------- */

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xF20E0D0C);
        drawer.setClickable(true);
        drawer.setVisibility(View.GONE);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(24), dp(20), dp(10));
        drawer.addView(c, new FrameLayout.LayoutParams(-1, -1));

        search = new EditText(this);
        search.setHint(L.t("App suchen …"));
        search.setTextColor(INK);
        search.setHintTextColor(INK3);
        search.setTextSize(18);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        GradientDrawable sb = new GradientDrawable(); sb.setColor(CARD); sb.setCornerRadius(dp(14)); sb.setStroke(dp(1), LINE);
        search.setBackground(sb);
        search.setPadding(dp(16), dp(14), dp(16), dp(14));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { fillApps(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        search.setOnEditorActionListener((v, id, e) -> {            // Enter opens the first match
            if (appList.getChildCount() > 0) appList.getChildAt(0).performClick();
            return true;
        });
        c.addView(search, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(appList);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, 0, 1f); svl.topMargin = dp(10);
        c.addView(sv, svl);

        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openDrawer() {
        if (editor.getVisibility() == View.VISIBLE) saveEditor();
        search.setText("");
        fillApps();
        drawer.setVisibility(View.VISIBLE);
        search.requestFocus();
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) search.post(() -> im.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
    }

    private void closeDrawer() {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) return;
        hideKeyboard(search);
        drawer.setVisibility(View.GONE);
    }

    private void loadApps() {
        List<App> out = new ArrayList<>();
        try {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : getPackageManager().queryIntentActivities(q, 0)) {
                if (r.activityInfo == null || getPackageName().equals(r.activityInfo.packageName) && OneHomeActivity.class.getName().equals(r.activityInfo.name)) continue;
                App a = new App();
                a.label = String.valueOf(r.loadLabel(getPackageManager()));
                a.pkg = r.activityInfo.packageName;
                a.cls = r.activityInfo.name;
                try { a.icon = r.loadIcon(getPackageManager()); } catch (Exception ignored) { }
                out.add(a);
            }
        } catch (Exception ignored) { }
        Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
        main.post(() -> { apps.clear(); apps.addAll(out); appsLoadedAt = now(); if (drawer.getVisibility() == View.VISIBLE) fillApps(); });
    }

    private void fillApps() {
        appList.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (final App a : apps) {
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(10), dp(6), dp(10));
            ImageView ic = new ImageView(this);
            if (a.icon != null) ic.setImageDrawable(a.icon);
            row.addView(ic, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView t = text(16, INK, Typeface.DEFAULT);
            t.setText(a.label);
            t.setPadding(dp(14), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> launch(a));
            appList.addView(row);
            if (++shown >= 60) break;
        }
        if (shown == 0) {
            TextView e = text(14, INK3, Typeface.DEFAULT);
            e.setText(apps.isEmpty() ? L.t("Apps laden …") : L.t("Keine App gefunden."));
            e.setPadding(dp(6), dp(16), 0, 0);
            appList.addView(e);
        }
    }

    private void launch(App a) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(a.pkg, a.cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            closeDrawer();
        } catch (Exception e) {
            Toast.makeText(this, L.t("Konnte die App nicht öffnen."), Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------------- helpers ---------------- */

    private static long now() { return System.currentTimeMillis(); }

    private static String ago(long ms) {
        long m = Math.max(0, ms / 60_000);
        if (m < 1) return L.t("gerade eben");
        if (m < 60) return m + L.t(" Min.");
        long h = m / 60;
        if (h < 24) return h + L.t(" Std. ") + (m % 60) + L.t(" Min.");
        long d = h / 24;
        return d == 1 ? L.t("gestern") : d + L.t(" Tagen");
    }

    private void hideKeyboard(View v) {
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setTypeface(tf);
        return t;
    }

    private TextView pill(String s, int fill, int ink) {
        TextView t = text(14, ink, Typeface.DEFAULT_BOLD);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(22));
        if (fill == CARD) g.setStroke(dp(1), LINE);
        t.setBackground(g);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
