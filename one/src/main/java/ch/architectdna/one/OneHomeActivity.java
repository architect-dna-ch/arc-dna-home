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
    private LinearLayout m1, bottom, sinceRow;
    private TextView finishBtn;
    private boolean finishArmed;
    private final Runnable disarm = this::disarmFinish;
    private BrainView brain, calmBrain;
    private FrameLayout calm;
    private TextView calmBreath, calmLine;
    private static final String[] CALM = {
        "Gedanken sind Signale, keine Befehle.",
        "Schmerz ist eine Information. Wut auch.",
        "Jemand hat sich zurückgezogen. Das sagt etwas über die Situation, nicht über deinen Wert.",
        "Was du fühlst, darf da sein. Es muss nicht bestimmen.",
        "Du musst heute Nacht nichts über alle Menschen entscheiden.",
        "Atme mit. Dann zurück an die Arbeit.",
    };
    static final String YT_MUSIC = "com.google.android.apps.youtube.music";
    private long appsLoadedAt;
    private boolean peeking;
    private static boolean awayTracked;
    private TextView siestaBtn;
    private final Runnable endPeek = () -> { peeking = false; render(); };
    // the goal arriving from the other device (GoalSync.In writes prefs on the main thread) re-renders live
    private final SharedPreferences.OnSharedPreferenceChangeListener goalIn = (sp, k) -> {
        if (("one_updated".equals(k) || "one_private".equals(k)) && editor != null && editor.getVisibility() != View.VISIBLE) render();
    };

    private static final class App { String label, pkg, cls; Drawable icon; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        if (!awayTracked) { awayTracked = true; Purpose.track(getApplication()); }   // check in when you leave ARC One
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

        // the brain: a calm, turning net of lines in the free space; tap it to breathe with it
        brain = new BrainView(this);
        brain.setIntensity(0.6f);
        brain.setContentDescription(L.t("Gehirn – tippen zum Durchatmen"));
        // tap = breathe with it; swipe up over it still opens the apps (the brain used to swallow that)
        final GestureDetector bg = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { tick(brain); openCalm(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (vy < -900 && Math.abs(vy) > Math.abs(vx)) { openDrawer(); return true; }
                return false;
            }
        });
        brain.setOnTouchListener((v, e) -> bg.onTouchEvent(e));
        brain.setOnClickListener(v -> openCalm());     // TalkBack / switch access
        brain.setMinimumHeight(dp(56));
        col.addView(brain, new LinearLayout.LayoutParams(-1, 0, 1f));

        // centre: the one sentence
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams midl = new LinearLayout.LayoutParams(-1, -2); midl.topMargin = dp(14);
        col.addView(mid, midl);

        label = text(11, RUST, Typeface.create("monospace", Typeface.BOLD));
        label.setText(L.t("— MEIN ZIEL GERADE"));
        label.setLetterSpacing(0.2f);
        mid.addView(label);

        intent = text(40, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        intent.setLetterSpacing(-0.03f);
        intent.setLineSpacing(0, 0.98f);
        intent.setAutoSizeTextTypeUniformWithConfiguration(26, 84, 2, TypedValue.COMPLEX_UNIT_SP);
        // private mode (tram, bus, queue): the sentence is blurred. Hold it to switch; tap peeks for 4 s.
        intent.setOnClickListener(v -> {
            if (isPrivate() && !peeking) { tick(v); peeking = true; render(); main.postDelayed(endPeek, 4000); return; }
            openEditor();
        });
        intent.setOnLongClickListener(v -> { confirm(v); setPrivate(!isPrivate()); return true; });
        intent.addOnLayoutChangeListener((v, nl, nt, nr, nb, ol, ot, orr, ob) -> {
            redaction.setBounds(0, 0, Math.max(1, nr - nl), Math.max(1, nb - nt));
            v.invalidate();
        });
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
        editDone.setOnClickListener(v -> { tick(v); saveEditor(); });
        editDone.setVisibility(View.GONE);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2); el.topMargin = dp(8);
        mid.addView(editDone, el);

        // "seit … · tippen zum Ändern" with the one way to close it out: ✓ Erledigt (tap twice — no accidents)
        sinceRow = new LinearLayout(this);
        sinceRow.setGravity(Gravity.CENTER_VERTICAL);
        sinceRow.setPadding(0, dp(10), 0, 0);
        since = text(13, INK2, Typeface.DEFAULT);
        since.setOnClickListener(v -> { tick(v); openEditor(); });
        sinceRow.addView(since, new LinearLayout.LayoutParams(0, -2, 1f));
        finishBtn = pill(L.t("✓ Erledigt"), CARD, INK);
        finishBtn.setTextSize(13);
        finishBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        finishBtn.setContentDescription(L.t("Ziel als erledigt markieren"));
        finishBtn.setOnClickListener(v -> {
            if (!finishArmed) {                       // first tap arms it, the second within 3 s confirms
                tick(v);
                finishArmed = true;
                finishBtn.setText(L.t("Sicher? Nochmals tippen"));
                tint(finishBtn, RUST, 0xFFFFFFFF);
                main.postDelayed(disarm, 3000);
                return;
            }
            disarmFinish();
            confirm(v);
            markDone();
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2); fl.leftMargin = dp(10);
        sinceRow.addView(finishBtn, fl);
        mid.addView(sinceRow, new LinearLayout.LayoutParams(-1, -2));

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
        yes.setOnClickListener(v -> { tick(v); prefs.edit().putLong("one_checked", now()).apply(); render(); });   // a check-in isn't a change: not mirrored
        neu.setOnClickListener(v -> { tick(v); openEditor(); });
        done.setOnClickListener(v -> { confirm(v); markDone(); });
        for (TextView t : new TextView[]{yes, neu, done}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(8);
            btns.addView(t, p);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(10);
        checkIn.addView(btns, bl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(22);
        mid.addView(checkIn, cl);

        // Music One: your one track, three times
        m1 = new LinearLayout(this);
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
        play.setOnClickListener(v -> { confirm(v); playMusicOne(); });
        m1.addView(play);
        m1.setOnClickListener(v -> { confirm(v); playMusicOne(); });
        m1.setOnLongClickListener(v -> {
            Toast.makeText(this, L.t("Song ändern: in YouTube Music auf «Teilen» → ARC One."), Toast.LENGTH_LONG).show();
            return true;
        });
        LinearLayout.LayoutParams m1l = new LinearLayout.LayoutParams(-1, -2); m1l.bottomMargin = dp(10);
        col.addView(m1, m1l);

        // bottom: achieved count + the one way to everything else
        bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        doneCount = text(12, INK3, Typeface.create("monospace", Typeface.NORMAL));
        bottom.addView(doneCount, new LinearLayout.LayoutParams(0, -2, 1f));
        upd = text(12, RUST, Typeface.DEFAULT_BOLD);
        upd.setText(L.t("Update ↻"));
        upd.setPadding(dp(10), dp(10), dp(4), dp(10));
        upd.setVisibility(View.GONE);
        upd.setOnClickListener(v -> updater.fix(() -> Toast.makeText(this, L.t("Update gerade nicht erreichbar."), Toast.LENGTH_SHORT).show()));
        bottom.addView(upd);
        siestaBtn = text(13, INK2, Typeface.DEFAULT_BOLD);          // Siesta 90: one sleep cycle, then a gentle wake
        siestaBtn.setPadding(dp(14), dp(10), dp(4), dp(10));
        siestaBtn.setOnClickListener(v -> { tick(v); toggleSiesta(); });
        bottom.addView(siestaBtn);
        TextView guideBtn = text(13, INK2, Typeface.DEFAULT_BOLD);   // ARC Guidebook: Quran & Sunnah, a few pages a day
        guideBtn.setText(L.t("Guide"));
        guideBtn.setPadding(dp(14), dp(10), dp(4), dp(10));
        guideBtn.setOnClickListener(v -> { tick(v); startActivity(new Intent(this, GuideActivity.class)); });
        bottom.addView(guideBtn);
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
        if (getIntent() != null && getIntent().getBooleanExtra("play_m1", false)) main.postDelayed(this::playMusicOne, 600);
        if (getIntent() != null && getIntent().hasExtra("hey")) { final String h = getIntent().getStringExtra("hey"); main.postDelayed(() -> heyCommand(h), 600); }
        new Thread(this::loadApps, "arc-one-apps").start();
    }

    @Override protected void onStart() { super.onStart(); prefs.registerOnSharedPreferenceChangeListener(goalIn); }
    @Override protected void onStop() { prefs.unregisterOnSharedPreferenceChangeListener(goalIn); super.onStop(); }

    @Override protected void onResume() {
        super.onResume();
        render();
        tick.run();
        if (now() - appsLoadedAt > 5 * 60_000L) new Thread(this::loadApps, "arc-one-apps").start();
        if (updater != null) updater.check(false);
        if (brain != null) brain.start();
        checkPurpose();
        // keep "Hey Claude" alive: Android only lets the microphone service start while ARC One is on screen
        if (HeyService.enabled(this) && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { startForegroundService(new Intent(this, HeyService.class)); } catch (RuntimeException ignored) { }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        if (brain != null) brain.stop();
        closeCalm();
    }

    /** Pressing Home while already here: close whatever is open, back to the sentence. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (SelfUpdater.ACTION.equals(i.getAction())) { updater.onStatus(i); return; }
        if (i.getBooleanExtra("play_m1", false)) { i.removeExtra("play_m1"); main.postDelayed(this::playMusicOne, 400); return; }
        if (i.hasExtra("hey")) { String h = i.getStringExtra("hey"); i.removeExtra("hey"); heyCommand(h); return; }
        closeDrawer();
        if (editor.getVisibility() == View.VISIBLE) saveEditor();
    }

    @Override public void onBackPressed() {
        if (calm != null) { closeCalm(); return; }
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
            renderSiesta();
            main.postDelayed(this, 30_000);
        }
    };

    /* ---------------- calm: the brain full-screen, breathe with it ---------------- */

    private void openCalm() {
        if (calm != null) return;
        closeDrawer();
        calm = new FrameLayout(this);
        calm.setBackgroundColor(BG);
        calm.setClickable(true);
        calm.setOnClickListener(v -> closeCalm());
        calmBrain = new BrainView(this);
        calmBrain.setIntensity(1f);
        calmBrain.setLegend(true);
        calmBrain.setZoomable(this::closeCalm);          // hold = zoom 300 %, tap = back
        calm.addView(calmBrain, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        c.setPadding(dp(28), 0, dp(28), dp(48));
        calmBreath = text(30, INK, Typeface.create("sans-serif-light", Typeface.NORMAL));
        calmBreath.setGravity(Gravity.CENTER);
        c.addView(calmBreath);
        calmLine = text(17, INK2, Typeface.create("serif", Typeface.ITALIC));
        calmLine.setGravity(Gravity.CENTER);
        calmLine.setPadding(0, dp(14), 0, dp(22));
        c.addView(calmLine);
        TextView back = text(12, INK3, Typeface.create("monospace", Typeface.NORMAL));
        back.setText(L.t("TIPPEN ZUM ZURÜCKKEHREN · HALTEN ZUM ZOOMEN"));
        back.setLetterSpacing(0.16f);
        c.addView(back);
        calm.addView(c, new FrameLayout.LayoutParams(-1, -1));
        root.addView(calm, new FrameLayout.LayoutParams(-1, -1));
        calm.setAlpha(0f);
        calm.animate().alpha(1f).setDuration(500).start();
        calmBrain.start();
        calmTick.run();
    }

    private void closeCalm() {
        if (calm == null) return;
        main.removeCallbacks(calmTick);
        if (calmBrain != null) calmBrain.stop();
        final FrameLayout c = calm;
        calm = null;
        c.animate().alpha(0f).setDuration(300).withEndAction(() -> root.removeView(c)).start();
    }

    /** "ein" for 4 s, "aus" for 6 s (6 breaths a minute), and a new quiet line every two breaths. */
    private final Runnable calmTick = new Runnable() {
        private int lastLine = -1;
        @Override public void run() {
            if (calm == null) return;
            long t = android.os.SystemClock.uptimeMillis();
            calmBreath.setText((t % 10_000) < 4000 ? L.t("ein") : L.t("aus"));
            int idx = (int) ((t / 20_000) % CALM.length);
            if (idx != lastLine) { lastLine = idx; calmLine.setText(L.t(CALM[idx])); }
            main.postDelayed(this, 200);
        }
    };

    /* ---------------- the sentence ---------------- */

    private void render() {
        String t = prefs.getString("one_text", "");
        boolean has = !t.isEmpty();
        boolean hide = has && isPrivate() && !peeking;
        intent.setText(has ? t : L.t("Was willst du gerade erreichen?"));
        intent.setTextColor(has ? INK : INK3);
        redact(hide);
        label.setText(!has ? L.t("— TIPPEN ZUM SCHREIBEN")
            : hide ? L.t("— PRIVAT · TIPPEN ZEIGT")
            : isPrivate() ? L.t("— PRIVAT · GLEICH VERDECKT")
            : L.t("— MEIN ZIEL GERADE"));
        boolean stale = has && now() - prefs.getLong("one_checked", now()) >= STALE;
        checkIn.setVisibility(stale && editor.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        int n = doneList().length();
        doneCount.setText(n == 0 ? "" : n + L.t(" erreicht"));
        renderSince();
        finishBtn.setVisibility(has && checkIn.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);   // check-in has its own
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
            sendBroadcast(new Intent().setClassName("ch.architectdna.home", "ch.architectdna.home.RepeatWatch$Receiver")
                .putExtra("times", 3).putExtra("each", true));                // every song 3×, then the next, and so on
            counted = isInstalled("ch.architectdna.home");
        } catch (Exception ignored) { }
        Toast.makeText(this, counted ? L.t("Music One · jeder Song 3×, dann der nächste.") : L.t("Music One läuft (3× braucht die ARC-DNA-App)."), Toast.LENGTH_SHORT).show();
    }

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    private void renderSince() {
        String t = prefs.getString("one_text", "");
        if (t.isEmpty()) { since.setText(""); return; }
        boolean hidden = isPrivate() && !peeking;
        since.setText(L.t("seit ") + ago(now() - prefs.getLong("one_since", now())) + (hidden ? L.t(" · verdeckt") : L.t(" · tippen zum Ändern")));
    }

    private void openEditor() {
        closeDrawer();
        editor.setText(prefs.getString("one_text", ""));
        editor.setSelection(editor.getText().length());
        intent.setVisibility(View.GONE);
        sinceRow.setVisibility(View.GONE);
        disarmFinish();
        checkIn.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        editDone.setVisibility(View.VISIBLE);
        m1.setVisibility(View.GONE);                  // editing is focus: just the sentence and "Fertig"
        bottom.setVisibility(View.GONE);
        editor.requestFocus();
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) editor.post(() -> im.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT));
    }

    private void saveEditor() {
        String t = editor.getText().toString().trim().replace('\n', ' ');
        String old = prefs.getString("one_text", "");
        SharedPreferences.Editor e = prefs.edit();
        boolean changed = !t.equals(old);
        if (changed) { GoalSync.remember(prefs, old, prefs.getLong("one_since", 0)); e.putString("one_text", t).putLong("one_since", now()); }
        e.putLong("one_checked", now()).apply();
        if (changed) GoalSync.push(this);             // mirror to the paired phone/tablet — only real changes
        hideKeyboard(editor);
        editor.setVisibility(View.GONE);
        editDone.setVisibility(View.GONE);
        intent.setVisibility(View.VISIBLE);
        sinceRow.setVisibility(View.VISIBLE);
        m1.setVisibility(View.VISIBLE);
        bottom.setVisibility(View.VISIBLE);
        render();
    }

    private static void tick(View v) { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); }
    private static void confirm(View v) {
        v.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
            ? android.view.HapticFeedbackConstants.CONFIRM : android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    private void disarmFinish() {
        main.removeCallbacks(disarm);
        if (!finishArmed) return;
        finishArmed = false;
        finishBtn.setText(L.t("✓ Erledigt"));
        tint(finishBtn, CARD, INK);
    }

    private void tint(TextView t, int fill, int ink) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(40));
        if (fill == CARD) g.setStroke(dp(1), LINE);
        t.setBackground(g); t.setTextColor(ink);
    }

    /** Private mode: each line of the sentence becomes a dark rounded bar of the same length — like a
     *  blacked-out document, calm instead of a grey blur. The text stays laid out, just not drawn. */
    private final android.graphics.drawable.Drawable redaction = new android.graphics.drawable.Drawable() {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rf = new android.graphics.RectF();
        @Override public void draw(android.graphics.Canvas c) {
            android.text.Layout l = intent.getLayout();
            if (l == null) return;
            float ox = intent.getTotalPaddingLeft(), oy = intent.getTotalPaddingTop();
            for (int i = 0; i < l.getLineCount(); i++) {
                float left = l.getLineLeft(i), right = l.getLineRight(i);
                if (right - left < 1) continue;
                float top = l.getLineTop(i), bottom = l.getLineBottom(i), h = bottom - top;
                rf.set(ox + left, oy + top + h * 0.2f, ox + right, oy + bottom - h * 0.16f);
                fill.setColor(0xFF221F1C);
                c.drawRoundRect(rf, rf.height() * 0.22f, rf.height() * 0.22f, fill);
                fill.setColor(0xFF2E2A26);                                   // a faint top edge, like paper over ink
                c.drawRect(rf.left + rf.height() * 0.22f, rf.top, rf.right - rf.height() * 0.22f, rf.top + dp(1), fill);
            }
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    };
    private boolean redacted;

    private void redact(boolean on) {
        if (on) intent.setTextColor(0x00000000);
        if (on == redacted) { if (on) redaction.invalidateSelf(); return; }
        redacted = on;
        if (on) {
            redaction.setBounds(0, 0, Math.max(1, intent.getWidth()), Math.max(1, intent.getHeight()));
            intent.getOverlay().add(redaction);
        } else intent.getOverlay().remove(redaction);
        intent.invalidate();
    }

    private boolean isPrivate() { return prefs.getBoolean("one_private", false); }

    private void setPrivate(boolean on) {
        prefs.edit().putBoolean("one_private", on).apply();
        peeking = false;
        main.removeCallbacks(endPeek);
        Toast.makeText(this, on ? L.t("Privat: dein Ziel ist verdeckt. Tippen zeigt es kurz.") : L.t("Privat-Modus aus."), Toast.LENGTH_SHORT).show();
        render();
    }

    /* ---------------- Hey Claude: offline voice quick commands ---------------- */

    private void toggleHey() {
        if (HeyService.enabled(this)) { HeyService.stop(this); Toast.makeText(this, L.t("«Hey Claude» aus."), Toast.LENGTH_SHORT).show(); return; }
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.RECORD_AUDIO);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), 47); return; }
        startHey();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 47) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startHey();
        else Toast.makeText(this, L.t("Ohne Mikrofon kann «Hey Claude» nicht zuhören."), Toast.LENGTH_LONG).show();
    }

    private void startHey() {
        HeyService.start(this);
        Toast.makeText(this, L.t("«Hey Claude» an. Beim ersten Mal lädt es das Sprachmodell (~40 MB). Sag: Hey Claude … siesta, music, goal, time, private, calm, guide."), Toast.LENGTH_LONG).show();
        // music / calm / guide open ARC One from the background: Android only allows that with this permission
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("hey_overlay_asked", false)) {
            prefs.edit().putBoolean("hey_overlay_asked", true).apply();
            main.postDelayed(() -> {
                Toast.makeText(this, L.t("Damit «music», «calm» und «guide» auch aus anderen Apps gehen: «Über anderen Apps einblenden» erlauben."), Toast.LENGTH_LONG).show();
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
            }, 2500);
        }
    }

    private void heyCommand(String h) {
        closeDrawer();
        if ("music".equals(h)) main.postDelayed(this::playMusicOne, 300);
        else if ("calm".equals(h)) openCalm();
        else if ("guide".equals(h)) startActivity(new Intent(this, GuideActivity.class));
        else if ("boxing".equals(h)) startActivity(new Intent(this, RoundTimerActivity.class));
    }

    private void renderSiesta() {
        if (siestaBtn == null) return;
        long end = Siesta.endsAt(this);
        if (end > System.currentTimeMillis()) {
            siestaBtn.setText(L.t("Siesta bis ") + new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(end)));
            siestaBtn.setTextColor(RUST);
        } else {
            siestaBtn.setText(L.t("Siesta 90"));
            siestaBtn.setTextColor(INK2);
        }
    }

    /** Tap: start (asking once for Do-Not-Disturb access, so the phone can go quiet), tap again: cancel. */
    private void toggleSiesta() {
        if (Siesta.endsAt(this) > System.currentTimeMillis()) {
            Siesta.stop(this);
            Toast.makeText(this, L.t("Siesta abgebrochen."), Toast.LENGTH_SHORT).show();
            renderSiesta();
            return;
        }
        if (!Siesta.canSilence(this) && !prefs.getBoolean("siesta_asked", false)) {
            prefs.edit().putBoolean("siesta_asked", true).apply();
            Toast.makeText(this, L.t("Einmal erlauben: ARC One darf «Nicht stören» setzen. Dann nochmals Siesta tippen."), Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return; } catch (Exception ignored) { }
        }
        if (!Siesta.start(this)) {
            Toast.makeText(this, L.t("Wecker ging nicht – bitte «Wecker & Erinnerungen» für ARC One erlauben."), Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
            return;
        }
        long end = Siesta.endsAt(this);
        Toast.makeText(this, L.t("Siesta 90 · Wecken um ") + new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(end))
            + (Siesta.canSilence(this) ? L.t(" · Handy ist still.") : ""), Toast.LENGTH_LONG).show();
        renderSiesta();
    }

    private void markDone() {
        String t = prefs.getString("one_text", "");
        if (t.isEmpty()) return;
        JSONArray a = doneList();
        try { a.put(new JSONObject().put("t", t).put("at", now())); } catch (Exception ignored) { }
        JSONArray keep = new JSONArray();
        for (int i = Math.max(0, a.length() - 100); i < a.length(); i++) keep.put(a.opt(i));
        prefs.edit().putString("one_done", keep.toString()).putString("one_text", "").apply();
        GoalSync.push(this);
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
            r.setText("✓  " + (isPrivate() ? "• • • • •" : o.optString("t")));
            r.setPadding(0, dp(12), 0, 0);
            c.addView(r);
            TextView w = text(11, INK3, Typeface.DEFAULT);
            w.setText(new SimpleDateFormat("EEE d. MMM, HH:mm", Locale.GERMAN).format(new Date(o.optLong("at"))));
            c.addView(w);
        }
        // earlier sentences — replaced ones are kept, so a goal can't get lost
        try {
            JSONArray hist = new JSONArray(prefs.getString("one_history", "[]"));
            if (hist.length() > 0) {
                TextView hh = text(26, INK, Typeface.create("sans-serif-black", Typeface.NORMAL)); hh.setText(L.t("Frühere Ziele"));
                hh.setPadding(0, dp(30), 0, 0);
                c.addView(hh);
                TextView hint = text(12, INK3, Typeface.DEFAULT); hint.setText(L.t("Tippen = wieder als Ziel setzen"));
                c.addView(hint);
                for (int i = hist.length() - 1; i >= 0 && i >= hist.length() - 15; i--) {
                    JSONObject o = hist.optJSONObject(i);
                    if (o == null) continue;
                    final String txt = o.optString("t");
                    TextView r = text(15, INK, Typeface.DEFAULT);
                    r.setText("↺  " + (isPrivate() ? "• • • • •" : txt));
                    r.setPadding(0, dp(12), 0, 0);
                    r.setOnClickListener(v -> {
                        root.removeView(sheet);
                        GoalSync.remember(prefs, prefs.getString("one_text", ""), prefs.getLong("one_since", 0));
                        prefs.edit().putString("one_text", txt).putLong("one_since", now()).putLong("one_checked", now()).apply();
                        GoalSync.push(this);
                        render();
                    });
                    c.addView(r);
                    TextView w = text(11, INK3, Typeface.DEFAULT);
                    w.setText(L.t("ersetzt ") + new SimpleDateFormat("EEE d. MMM, HH:mm", Locale.GERMAN).format(new Date(o.optLong("at"))));
                    c.addView(w);
                }
            }
        } catch (Exception ignored) { }
        TextView guide = pill(L.t("ARC Guidebook lesen"), CARD, INK);
        guide.setOnClickListener(v -> { root.removeView(sheet); startActivity(new Intent(this, GuideActivity.class)); });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2); gl.topMargin = dp(30);
        c.addView(guide, gl);
        TextView hey = pill(HeyService.enabled(this) ? L.t("«Hey Claude» ausschalten") : L.t("«Hey Claude» einschalten (offline)"), CARD, INK);
        hey.setOnClickListener(v -> { root.removeView(sheet); toggleHey(); });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2); hl.topMargin = dp(10);
        c.addView(hey, hl);
        TextView purp = pill(Purpose.prefs(this).getBoolean("purpose_on", true) ? L.t("«Wozu?» vor Chrome & Co. ausschalten") : L.t("«Wozu?» vor Chrome & Co. einschalten"), CARD, INK);
        purp.setOnClickListener(v -> { root.removeView(sheet); boolean on = !Purpose.prefs(this).getBoolean("purpose_on", true);
            Purpose.prefs(this).edit().putBoolean("purpose_on", on).apply();
            Toast.makeText(this, on ? L.t("«Wozu?» an.") : L.t("«Wozu?» aus."), Toast.LENGTH_SHORT).show(); });
        LinearLayout.LayoutParams pul = new LinearLayout.LayoutParams(-2, -2); pul.topMargin = dp(10);
        c.addView(purp, pul);
        TextView awayB = pill(Purpose.awayOn(this) ? L.t("Check-in alle 10 Min. ausschalten") : L.t("Check-in alle 10 Min. einschalten"), CARD, INK);
        awayB.setOnClickListener(v -> { root.removeView(sheet); boolean on = !Purpose.awayOn(this);
            Purpose.prefs(this).edit().putBoolean("away_on", on).apply();
            Toast.makeText(this, on ? L.t("ARC One fragt nach, wenn du 10 Min. weg bist.") : L.t("Check-in aus."), Toast.LENGTH_SHORT).show(); });
        LinearLayout.LayoutParams awl = new LinearLayout.LayoutParams(-2, -2); awl.topMargin = dp(10);
        c.addView(awayB, awl);
        TextView eff = pill(L.t("Aufwand & Wirkung"), CARD, INK);
        eff.setOnClickListener(v -> { root.removeView(sheet); startActivity(new Intent(this, EffortActivity.class)); });
        LinearLayout.LayoutParams efl = new LinearLayout.LayoutParams(-2, -2); efl.topMargin = dp(10);
        c.addView(eff, efl);
        TextView ring = pill(L.t("Rundentimer (Boxen)"), CARD, INK);
        ring.setOnClickListener(v -> { root.removeView(sheet); startActivity(new Intent(this, RoundTimerActivity.class)); });
        LinearLayout.LayoutParams rgl = new LinearLayout.LayoutParams(-2, -2); rgl.topMargin = dp(10);
        c.addView(ring, rgl);
        TextView norm = pill(L.t("Wie die meisten (Checkliste)"), CARD, INK);
        norm.setOnClickListener(v -> { root.removeView(sheet); startActivity(new Intent(this, NormActivity.class)); });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2); nl.topMargin = dp(10);
        c.addView(norm, nl);
        TextView priv = pill(isPrivate() ? L.t("Privat-Modus ausschalten") : L.t("Privat-Modus (ÖV, unterwegs)"), CARD, INK);
        priv.setOnClickListener(v -> { root.removeView(sheet); setPrivate(!isPrivate()); });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2); pl.topMargin = dp(10);
        c.addView(priv, pl);
        TextView browserDl = pill(L.t("Update im Browser laden"), CARD, INK);
        browserDl.setOnClickListener(v -> {
            root.removeView(sheet);
            // plain download from the public mirror; Chrome installs it after a tap — works when the in-app update won't
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/architect-dna-ch/arc-releases/releases?q=one-v&expanded=true"))); } catch (Exception ignored) { }
        });
        if (!DiagNote.last.isEmpty()) {
            TextView why = text(12, INK3, Typeface.create("monospace", Typeface.NORMAL));
            why.setText(L.t("Letzter Update-Fehler: ") + DiagNote.last);
            why.setPadding(0, dp(24), 0, 0);
            c.addView(why);
        }
        LinearLayout.LayoutParams bdl = new LinearLayout.LayoutParams(-2, -2); bdl.topMargin = dp(10);
        TextView upNow = pill(L.t("Jetzt nach Updates suchen"), CARD, INK);
        upNow.setOnClickListener(v -> {
            root.removeView(sheet);
            if (updater != null) {
                updater.check(true);
                Toast.makeText(this, L.t("Suche Updates …"), Toast.LENGTH_SHORT).show();
                if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls())
                    updater.fix(() -> { });                       // first time: let ARC One install its own updates
            }
        });
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-2, -2); ul.topMargin = dp(10);
        c.addView(upNow, ul);
        c.addView(browserDl, bdl);
        TextView setHome = pill(L.t("Als Startbildschirm festlegen"), CARD, INK);
        setHome.setOnClickListener(v -> { try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); } catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } } });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = dp(10);
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
        if (Purpose.guarded(this, a.pkg)) { askPurpose(a); return; }
        open(a);
    }

    /** Before a rabbit-hole app: what for? One line, a recent one, or "nur kurz". Never blocks. */
    private void askPurpose(final App a) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(22), dp(8), dp(22), 0);
        final EditText in = new EditText(this);
        in.setHint(L.t("z. B. Zugverbindung nach Thun nachschauen"));
        in.setSingleLine(true);
        f.addView(in);
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        for (final String r : Purpose.recent(this)) {
            TextView t = text(14, INK2, Typeface.DEFAULT);
            t.setText("↺  " + r);
            t.setPadding(0, dp(10), 0, dp(4));
            t.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); Purpose.begin(this, r, a.label); open(a); });
            f.addView(t);
        }
        dlg[0] = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Wozu öffnest du ") + a.label + "?")
            .setView(f)
            .setPositiveButton(L.t("Los"), (d, w) -> { Purpose.begin(this, in.getText().toString(), a.label); open(a); })
            .setNeutralButton(L.t("Nur kurz"), (d, w) -> { Purpose.begin(this, "", a.label); open(a); })
            .setNegativeButton(L.t("Doch nicht"), null)
            .create();
        dlg[0].setOnShowListener(d -> { in.requestFocus(); dlg[0].getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE); });
        in.setOnEditorActionListener((v, id, e) -> { dlg[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); return true; });
        dlg[0].show();
    }

    /** Back on the home screen with a purpose still open: did you get it? */
    private void checkPurpose() {
        String p = Purpose.current(this);
        if (p.isEmpty() || now() - Purpose.since(this) < 20_000) return;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Erledigt?"))
            .setMessage(L.t("Du wolltest: ") + p + "\n" + L.t("seit ") + ago(now() - Purpose.since(this)))
            .setPositiveButton(L.t("Ja ✓"), (d, w) -> Purpose.end(this))
            .setNegativeButton(L.t("Noch nicht"), null)
            .setNeutralButton(L.t("Vergessen, egal"), (d, w) -> Purpose.end(this))
            .show();
    }

    private void open(App a) {
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
