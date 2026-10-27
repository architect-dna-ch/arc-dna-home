package ch.architectdna.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bär-Wecker: the daily bear alarm and one-time deadlines ("Mi 13:10 · Vorbereitung erster Tag
 * Homeoffice"). Each deadline rings once with the bear and its own text, then it's gone.
 * The home's alarm card shows the next one and opens this.
 */
public class WeckerActivity extends Activity {

    private SharedPreferences prefs;
    private int bg, card, line, ink, ink2, ink3, rust;
    private LinearLayout list;
    private TextView daily, dailyState;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = AlarmScheduler.prefs(this);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFEDEDE8 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!night && Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(bits, bits);
        }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(40));
        sv.addView(col);
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                col.setPadding(dp(22) + i.left, dp(18) + i.top, dp(22) + i.right, dp(40) + i.bottom);
            }
            return in;
        });

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = mono(11, ink3);
        label.setText("— BÄR-WECKER");
        label.setLetterSpacing(0.24f);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(22, ink, true);
        close.setText("✕");
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        // The bear, big, with the daily alarm.
        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(16), dp(18), dp(16));
        hero.setBackground(box(card, line, 22));
        ImageView bear = new ImageView(this);
        bear.setImageResource(R.drawable.bear);
        hero.addView(bear, new LinearLayout.LayoutParams(dp(120), dp(80)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(16), 0, 0, 0);
        daily = text(44, ink, true);
        daily.setLetterSpacing(-0.04f);
        t.addView(daily);
        dailyState = mono(11, ink3);
        t.addView(dailyState);
        hero.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sw = chip("AN/AUS", rust);
        sw.setOnClickListener(v -> {
            prefs.edit().putBoolean("alarmOn", !prefs.getBoolean("alarmOn", false)).apply();
            AlarmScheduler.schedule(this);
            paint();
        });
        hero.addView(sw);
        hero.setOnClickListener(v -> pickDaily());
        col.addView(hero, margins(-1, -2, dp(14)));

        // Deadlines.
        LinearLayout dh = new LinearLayout(this);
        dh.setGravity(Gravity.CENTER_VERTICAL);
        TextView dl = mono(11, ink3);
        dl.setText("— TERMINE · EINMALIG");
        dl.setLetterSpacing(0.2f);
        dh.addView(dl, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView add = chip("+ TERMIN", rust);
        add.setOnClickListener(v -> addDeadline(null));
        dh.addView(add);
        col.addView(dh, margins(-1, -2, dp(26)));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, margins(-1, -2, dp(10)));

        // Quick ideas: one tap fills the form.
        TextView ideas = mono(10, ink3);
        ideas.setText("SCHNELL");
        ideas.setLetterSpacing(0.2f);
        col.addView(ideas, margins(-2, -2, dp(22)));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        String[] quick = {"Vorbereitung erster Tag Homeoffice", "Abgabe / Deadline", "Termin – losgehen", "Medikament"};
        for (String q : quick) {
            TextView c = chip("＋ " + q, ink2);
            c.setOnClickListener(v -> addDeadline(q));
            chips.addView(c, margins(-2, -2, dp(8)));
        }
        col.addView(chips);

        setContentView(sv);
        paint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paint();
    }

    private void paint() {
        boolean on = prefs.getBoolean("alarmOn", false);
        int m = prefs.getInt("alarmMinutes", 7 * 60);
        daily.setText(String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60));
        daily.setTextColor(on ? ink : ink3);
        dailyState.setText(on ? "TÄGLICH · DER BÄR KOMMT AUS DER HÖHLE" : "TÄGLICH · AUS · TIPPEN ZUM STELLEN");
        dailyState.setTextColor(on ? rust : ink3);
        list.removeAllViews();
        List<AlarmScheduler.Deadline> ds = AlarmScheduler.deadlines(this);
        SimpleDateFormat f = new SimpleDateFormat("EEE d. MMM · HH:mm", Locale.GERMAN);
        if (ds.isEmpty()) {
            TextView none = text(15, ink3, false);
            none.setText("Noch nichts. „+ TERMIN“ – z. B. Mittwoch 13:10, Vorbereitung erster Tag Homeoffice.");
            list.addView(none);
        }
        for (AlarmScheduler.Deadline d : ds) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(10), dp(12));
            boolean past = d.at < System.currentTimeMillis();
            row.setBackground(box(card, past ? line : rust, 16));
            row.setAlpha(past ? 0.5f : 1f);
            TextView e = text(22, ink, false);
            e.setText("🐻");
            row.addView(e);
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(dp(12), 0, 0, 0);
            TextView name = text(17, ink, true);
            name.setText(d.label);
            tx.addView(name);
            TextView when = mono(11, rust);
            when.setText(f.format(new Date(d.at)).toUpperCase(Locale.GERMAN) + " · " + until(d.at));
            tx.addView(when);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView del = text(18, ink3, true);
            del.setText("✕");
            del.setPadding(dp(12), dp(6), dp(6), dp(6));
            long id = d.id;
            del.setOnClickListener(v -> {
                AlarmScheduler.removeDeadline(this, id);
                paint();
            });
            row.addView(del);
            list.addView(row, margins(-1, -2, dp(8)));
        }
    }

    private String until(long at) {
        long min = (at - System.currentTimeMillis()) / 60000;
        if (min < 0) return "VORBEI";
        if (min < 60) return "IN " + min + " MIN";
        if (min < 48 * 60) return "IN " + min / 60 + " H " + min % 60 + " MIN";
        return "IN " + min / 1440 + " TAGEN";
    }

    private void pickDaily() {
        int m = prefs.getInt("alarmMinutes", 7 * 60);
        android.app.TimePickerDialog d = new android.app.TimePickerDialog(this, (tp, h, min) -> {
            prefs.edit().putInt("alarmMinutes", h * 60 + min).putBoolean("alarmOn", true).apply();
            AlarmScheduler.schedule(this);
            paint();
        }, m / 60, m % 60, true);
        d.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Test", (di, w) -> startActivity(new Intent(this, BearAlarmActivity.class)));
        d.show();
    }

    /** Text, then day, then time. */
    private void addDeadline(String preset) {
        EditText e = new EditText(this);
        e.setHint("Was? z. B. Vorbereitung erster Tag Homeoffice");
        if (preset != null) e.setText(preset);
        e.setSelectAllOnFocus(preset != null);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e);
        new AlertDialog.Builder(this)
            .setTitle("Neuer Termin")
            .setView(wrap)
            .setPositiveButton("Tag wählen", (di, w) -> {
                String what = e.getText().toString().trim();
                if (what.isEmpty()) what = "Termin";
                String label = what;
                Calendar c = Calendar.getInstance();
                new android.app.DatePickerDialog(this, (dp, y, mo, da) -> {
                    c.set(y, mo, da);
                    new android.app.TimePickerDialog(this, (tp, h, mi) -> {
                        c.set(Calendar.HOUR_OF_DAY, h);
                        c.set(Calendar.MINUTE, mi);
                        c.set(Calendar.SECOND, 0);
                        c.set(Calendar.MILLISECOND, 0);
                        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
                            android.widget.Toast.makeText(this, "Das ist schon vorbei.", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        AlarmScheduler.addDeadline(this, c.getTimeInMillis(), label);
                        paint();
                    }, 13, 10, true).show();
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    /* ---------- small helpers ---------- */

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(int sp, int col, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(col);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private TextView mono(int sp, int col) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(col);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private TextView chip(String s, int col) {
        TextView t = mono(12, col);
        t.setText(s);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(box(card, col, 20));
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1.5f), stroke);
        g.setCornerRadius(dp(r));
        return g;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        return p;
    }
}
