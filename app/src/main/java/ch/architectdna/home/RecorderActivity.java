package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Rekorder, native: one big button records (keeps going with the screen off), pause / resume,
 * a live waveform, and your recordings below — tap = play / pause, hold = rename, share, delete.
 */
public class RecorderActivity extends Activity {

    private static final int REQ_MIC = 1;

    private boolean night;
    private int bg, card, line, ink, ink2, ink3, rust;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView time, status;
    private View recBtn, pauseBtn;
    private TextView pauseIcon;
    private Wave wave;
    private LinearLayout list;
    private TextView count;
    private MediaPlayer player;
    private Item playing;
    private final List<Item> items = new ArrayList<>();

    private static final class Item {
        Uri uri;
        File file;
        String name;
        long date, dur;
        TextView meta;
        View bar;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFEDEDE8 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(0));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("Rekorder"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(18, ink, true);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        // The recorder card: time, state, waveform, buttons.
        LinearLayout rc = new LinearLayout(this);
        rc.setOrientation(LinearLayout.VERTICAL);
        rc.setGravity(Gravity.CENTER_HORIZONTAL);
        rc.setBackground(box(card, line, 20));
        rc.setPadding(dp(18), dp(22), dp(18), dp(22));
        LinearLayout.LayoutParams rcl = new LinearLayout.LayoutParams(-1, -2);
        rcl.topMargin = dp(16);
        col.addView(rc, rcl);

        time = text(56, ink, false);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setFontFeatureSettings("tnum");
        time.setText("0:00");
        rc.addView(time);
        status = mono(11, ink3);
        status.setText("BEREIT");
        rc.addView(status);

        wave = new Wave();
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, dp(96));
        wl.topMargin = dp(16);
        wl.bottomMargin = dp(18);
        rc.addView(wave, wl);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        rc.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        View spacer = new View(this);
        buttons.addView(spacer, new LinearLayout.LayoutParams(dp(60), dp(60)));
        recBtn = new RecButton();
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(84), dp(84));
        bl.leftMargin = bl.rightMargin = dp(26);
        buttons.addView(recBtn, bl);
        recBtn.setOnClickListener(v -> toggleRecord());
        recBtn.setContentDescription("Aufnahme starten oder stoppen");
        FrameLayout pb = new FrameLayout(this);
        pb.setBackground(circle(0, line));
        pauseIcon = text(20, ink, true);
        pauseIcon.setGravity(Gravity.CENTER);
        pb.addView(pauseIcon, new FrameLayout.LayoutParams(-1, -1));
        pauseBtn = pb;
        pauseBtn.setOnClickListener(v -> {
            if (RecorderService.paused()) RecorderService.resume();
            else RecorderService.pause();
            paint();
        });
        buttons.addView(pauseBtn, new LinearLayout.LayoutParams(dp(60), dp(60)));

        LinearLayout lh = new LinearLayout(this);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.setPadding(0, dp(24), 0, dp(10));
        lh.addView(label("Aufnahmen"), new LinearLayout.LayoutParams(0, -2, 1f));
        count = mono(11, rust);
        lh.addView(count);
        col.addView(lh);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, dp(24));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(night ? 0 : bits, bits);
        }
        RecorderService.onChange = () -> main.post(() -> {
            paint();
            load();
        });
        load();
        paint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paint();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RecorderService.onChange = null;
        stopPlayer();
    }

    /* ---------------- recording ---------------- */

    private void toggleRecord() {
        if (RecorderService.recording()) {
            boolean kept = RecorderService.stop(this);
            Toast.makeText(this, kept ? "Gesichert." : "Zu kurz – nichts gesichert.", Toast.LENGTH_SHORT).show();
            wave.clear();
            paint();
            load();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            List<String> ask = new ArrayList<>();
            ask.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33) ask.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(ask.toArray(new String[0]), REQ_MIC);
            return;
        }
        stopPlayer();
        try {
            RecorderService.start(this);
            recBtn.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        } catch (Exception e) {
            DiagLog.log("recorder start failed: " + e.getMessage());
            Toast.makeText(this, "Aufnahme geht nicht: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        wave.clear();
        paint();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) toggleRecord();
        else if (requestCode == REQ_MIC) Toast.makeText(this, "Ohne Mikrofon keine Aufnahme.", Toast.LENGTH_LONG).show();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (RecorderService.recording()) {
                time.setText(clock(RecorderService.elapsed()));
                if (!RecorderService.paused()) wave.push(RecorderService.amplitude());
            }
            if (player != null && playing != null && playing.bar != null) {
                try {
                    float f = player.getDuration() > 0 ? player.getCurrentPosition() / (float) player.getDuration() : 0;
                    playing.bar.setScaleX(Math.max(0.001f, f));
                    playing.meta.setText(clock(player.getCurrentPosition()) + " / " + clock(player.getDuration()));
                } catch (IllegalStateException ignored) {
                }
            }
            main.postDelayed(this, 50);
        }
    };

    private void paint() {
        boolean on = RecorderService.recording(), p = RecorderService.paused();
        status.setText(!on ? "BEREIT · TIPPEN = AUFNEHMEN" : p ? "PAUSE" : "● AUFNAHME · LÄUFT AUCH GESPERRT");
        status.setTextColor(on && !p ? rust : ink3);
        if (!on) time.setText("0:00");
        pauseBtn.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        pauseIcon.setText(p ? "●" : "❚❚");
        pauseIcon.setTextColor(p ? rust : ink);
        recBtn.invalidate();
    }

    /* ---------------- list ---------------- */

    private void load() {
        items.clear();
        android.content.SharedPreferences prefs = getSharedPreferences("recorder", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 29) {
            String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION};
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?", new String[]{"%" + RecorderService.FOLDER + "%"},
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext()) {
                    Item it = new Item();
                    long id = c.getLong(0);
                    it.uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    it.name = stripExt(c.getString(1));
                    it.date = c.getLong(2) * 1000;
                    it.dur = c.getLong(3);
                    if (it.dur <= 0) it.dur = prefs.getLong("dur:" + id, 0);
                    items.add(it);
                }
            } catch (RuntimeException e) {
                DiagLog.log("recorder list failed: " + e.getMessage());
            }
        } else {
            File[] fs = RecorderService.legacyDir(this).listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : fs) {
                    Item it = new Item();
                    it.file = f;
                    it.uri = Uri.fromFile(f);
                    it.name = stripExt(f.getName());
                    it.date = f.lastModified();
                    it.dur = prefs.getLong("dur:" + f.getName(), 0);
                    items.add(it);
                }
            }
        }
        list.removeAllViews();
        count.setText(items.size() + (items.size() == 1 ? " AUFNAHME" : " AUFNAHMEN"));
        if (items.isEmpty()) {
            TextView empty = text(15, ink3, false);
            empty.setText("Noch nichts aufgenommen. Der rote Knopf oben startet.");
            empty.setPadding(0, dp(12), 0, 0);
            list.addView(empty);
        }
        SimpleDateFormat df = new SimpleDateFormat("EEE d. MMM · HH:mm", Locale.GERMAN);
        for (Item it : items) list.addView(row(it, df));
    }

    private View row(Item it, SimpleDateFormat df) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackground(box(card, line, 14));
        r.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(16, ink, true);
        name.setText(it.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        it.meta = mono(11, ink3);
        it.meta.setText(df.format(new Date(it.date)) + (it.dur > 0 ? " · " + clock(it.dur) : ""));
        it.meta.setPadding(0, dp(4), 0, 0);
        texts.addView(it.meta);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView play = text(16, rust, true);
        play.setText(it == playing && player != null && player.isPlaying() ? "❚❚" : "▶");
        play.setGravity(Gravity.CENTER);
        play.setBackground(circle(0, rust));
        top.addView(play, new LinearLayout.LayoutParams(dp(42), dp(42)));
        r.addView(top);
        View bar = new View(this);
        bar.setBackgroundColor(rust);
        bar.setPivotX(0);
        bar.setScaleX(0.001f);
        it.bar = bar;
        LinearLayout.LayoutParams barL = new LinearLayout.LayoutParams(-1, dp(2));
        barL.topMargin = dp(10);
        r.addView(bar, barL);
        r.setOnClickListener(v -> togglePlay(it, play));
        r.setOnLongClickListener(v -> {
            menu(it);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView playIcon;

    private void togglePlay(Item it, TextView icon) {
        if (RecorderService.recording()) return;
        if (playing == it && player != null) {
            if (player.isPlaying()) player.pause();
            else player.start();
            icon.setText(player.isPlaying() ? "❚❚" : "▶");
            return;
        }
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, it.uri);
            player.prepare();
            player.start();
            playing = it;
            playIcon = icon;
            icon.setText("❚❚");
            player.setOnCompletionListener(mp -> {
                icon.setText("▶");
                if (it.bar != null) it.bar.setScaleX(0.001f);
            });
        } catch (Exception e) {
            stopPlayer();
            Toast.makeText(this, "Kann nicht abspielen.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
        }
        player = null;
        if (playIcon != null) playIcon.setText("▶");
        if (playing != null && playing.bar != null) playing.bar.setScaleX(0.001f);
        playing = null;
        playIcon = null;
    }

    private void menu(Item it) {
        String[] actions = it.file == null ? new String[]{"Umbenennen", "Teilen", "Löschen"} : new String[]{"Umbenennen", "Löschen"};
        new AlertDialog.Builder(this)
            .setTitle(it.name)
            .setItems(actions, (d, which) -> {
                String a = actions[which];
                if (a.equals("Umbenennen")) rename(it);
                else if (a.equals("Teilen")) share(it);
                else delete(it);
            })
            .show();
    }

    private void rename(Item it) {
        EditText e = new EditText(this);
        e.setText(it.name);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e);
        new AlertDialog.Builder(this)
            .setTitle("Umbenennen")
            .setView(wrap)
            .setPositiveButton("OK", (d, w) -> {
                String n = e.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "-");
                if (n.isEmpty()) return;
                try {
                    if (it.file != null) {
                        //noinspection ResultOfMethodCallIgnored
                        it.file.renameTo(new File(it.file.getParentFile(), n + ".m4a"));
                    } else {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Audio.Media.DISPLAY_NAME, n + ".m4a");
                        getContentResolver().update(it.uri, v, null, null);
                    }
                } catch (RuntimeException ex) {
                    Toast.makeText(this, "Umbenennen ging nicht.", Toast.LENGTH_SHORT).show();
                }
                load();
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    private void share(Item it) {
        Intent s = new Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, it.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, it.name));
    }

    private void delete(Item it) {
        new AlertDialog.Builder(this)
            .setTitle("Löschen?")
            .setMessage(it.name)
            .setPositiveButton("Löschen", (d, w) -> {
                if (playing == it) stopPlayer();
                try {
                    if (it.file != null) {
                        //noinspection ResultOfMethodCallIgnored
                        it.file.delete();
                    } else {
                        getContentResolver().delete(it.uri, null, null);
                    }
                } catch (RuntimeException e) {
                    Toast.makeText(this, "Löschen ging nicht.", Toast.LENGTH_SHORT).show();
                }
                load();
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    private static String stripExt(String n) {
        if (n == null) return "Aufnahme";
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /* ---------------- views ---------------- */

    /** Rolling bars of the input level, newest on the right. */
    private final class Wave extends View {
        private final float[] levels = new float[96];
        private int n;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Wave() {
            super(RecorderActivity.this);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void push(int amp) {
            float v = amp <= 0 ? 0 : (float) Math.min(1, Math.log10(1 + amp) / Math.log10(32768));
            v = Math.max(0, (v - 0.25f) / 0.75f);
            System.arraycopy(levels, 1, levels, 0, levels.length - 1);
            levels[levels.length - 1] = v;
            n++;
            invalidate();
        }

        void clear() {
            java.util.Arrays.fill(levels, 0);
            n = 0;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), step = w / levels.length;
            p.setStrokeWidth(Math.max(2, step * 0.55f));
            boolean on = RecorderService.recording();
            for (int i = 0; i < levels.length; i++) {
                float x = i * step + step / 2, v = Math.max(0.03f, levels[i]) * h * 0.46f;
                p.setColor(on ? rust : line);
                p.setAlpha(on ? (int) (90 + 165 * i / (float) levels.length) : 255);
                c.drawLine(x, h / 2 - v, x, h / 2 + v, p);
            }
            p.setAlpha(255);
        }
    }

    /** Big rust circle: a white dot to start, a white square to stop. */
    private final class RecButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        RecButton() {
            super(RecorderActivity.this);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2, cy = getHeight() / 2f, r = Math.min(w, getHeight()) / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(rust);
            c.drawCircle(cx, cy, r - dp(2), p);
            p.setStyle(Paint.Style.FILL);
            if (RecorderService.recording()) {
                float s = r * 0.42f;
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, dp(6), dp(6), p);
            } else {
                c.drawCircle(cx, cy, r * 0.72f, p);
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private TextView mono(float sp, int colour) {
        TextView t = text(sp, colour, false);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.12f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View label(String s) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        View dash = new View(this);
        dash.setBackgroundColor(rust);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(24), dp(2));
        dl.rightMargin = dp(14);
        l.addView(dash, dl);
        TextView t = mono(11, ink3);
        t.setLetterSpacing(0.24f);
        t.setText(s.toUpperCase(Locale.GERMAN));
        l.addView(t);
        return l;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable circle(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1.5f)), stroke);
        return g;
    }
}
