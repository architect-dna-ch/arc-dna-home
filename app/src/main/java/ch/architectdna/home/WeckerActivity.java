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

        rootView = new FrameLayout(this);
        rootView.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootView);
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

    private FrameLayout rootView;

    private void pickDaily() {
        int m = prefs.getInt("alarmMinutes", 7 * 60);
        sheet(null, true, m / 60, m % 60);
    }

    private void addDeadline(String preset) {
        sheet(preset == null ? "" : preset, false, 13, 10);
    }

    /*
     * The sheet instead of Android's grey pickers: the text, a row of days (Heute, Morgen, the
     * weekdays ahead), and a drawn clock dial – tap the hours or the minutes above it, drag round
     * the dial. All in ARC colours, light or dark.
     */
    private void sheet(String text, boolean daily, int hour, int minute) {
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x88000000);
        scrim.setClickable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // Above the navigation bar (the buttons were hidden behind it).
        int navBottom = 0;
        if (Build.VERSION.SDK_INT >= 30 && rootView.getRootWindowInsets() != null) {
            navBottom = rootView.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom;
        }
        panel.setPadding(dp(22), dp(18), dp(22), dp(20) + navBottom);
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        panel.setBackground(g);
        panel.setClickable(true);
        TextView title = mono(11, ink3);
        title.setText(daily ? "— TÄGLICHER WECKER" : "— NEUER TERMIN");
        title.setLetterSpacing(0.24f);
        panel.addView(title);
        EditText e = null;
        if (!daily) {
            e = new EditText(this);
            e.setText(text);
            e.setHint("Was? z. B. Vorbereitung erster Tag Homeoffice");
            e.setTextColor(ink);
            e.setHintTextColor(ink3);
            e.setTextSize(20);
            e.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            e.setBackground(box(bg, line, 14));
            e.setPadding(dp(14), dp(12), dp(14), dp(12));
            e.setSingleLine(true);
            panel.addView(e, margins(-1, -2, dp(12)));
        }
        // Days.
        final Calendar day = Calendar.getInstance();
        final TextView[] dayChips = new TextView[14];
        if (!daily) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            hs.addView(row);
            SimpleDateFormat df = new SimpleDateFormat("EEE d.", Locale.GERMAN);
            for (int k = 0; k < dayChips.length; k++) {
                Calendar c = Calendar.getInstance();
                c.add(Calendar.DAY_OF_YEAR, k);
                String lbl = k == 0 ? "HEUTE" : k == 1 ? "MORGEN" : df.format(c.getTime()).toUpperCase(Locale.GERMAN);
                TextView chip = mono(12, ink2);
                chip.setText(lbl);
                chip.setPadding(dp(14), dp(9), dp(14), dp(9));
                int idx = k;
                chip.setOnClickListener(v -> {
                    day.setTimeInMillis(System.currentTimeMillis());
                    day.add(Calendar.DAY_OF_YEAR, idx);
                    for (int q = 0; q < dayChips.length; q++) paintChip(dayChips[q], q == idx);
                });
                dayChips[k] = chip;
                paintChip(chip, k == 0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(8);
                row.addView(chip, lp);
            }
            panel.addView(hs, margins(-1, -2, dp(14)));
        }
        // The dial.
        Dial dial = new Dial(hour, minute);
        // Smaller dial on short screens, so the buttons always fit.
        int dialH = Math.min(dp(330), (int) (getResources().getDisplayMetrics().heightPixels * (daily ? 0.42f : 0.34f)));
        panel.addView(dial, margins(-1, dialH, dp(10)));
        // Buttons.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        TextView cancel = chip("ABBRECHEN", ink2);
        TextView ok = chip(daily ? "WECKER STELLEN 🐻" : "TERMIN SETZEN 🐻", rust);
        buttons.addView(cancel);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
        ol.leftMargin = dp(10);
        buttons.addView(ok, ol);
        panel.addView(buttons, margins(-1, -2, dp(14)));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        scrim.addView(panel, pl);
        rootView.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        panel.setTranslationY(dp(500));
        panel.animate().translationY(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        Runnable close = () -> panel.animate().translationY(panel.getHeight()).setDuration(200)
            .withEndAction(() -> rootView.removeView(scrim)).start();
        cancel.setOnClickListener(v -> close.run());
        scrim.setOnClickListener(v -> close.run());
        EditText field = e;
        ok.setOnClickListener(v -> {
            if (daily) {
                prefs.edit().putInt("alarmMinutes", dial.hour * 60 + dial.minute).putBoolean("alarmOn", true).apply();
                AlarmScheduler.schedule(this);
            } else {
                String what = field.getText().toString().trim();
                if (what.isEmpty()) what = "Termin";
                day.set(Calendar.HOUR_OF_DAY, dial.hour);
                day.set(Calendar.MINUTE, dial.minute);
                day.set(Calendar.SECOND, 0);
                day.set(Calendar.MILLISECOND, 0);
                if (day.getTimeInMillis() <= System.currentTimeMillis()) {
                    title.setText("— DAS IST SCHON VORBEI · ANDERE ZEIT");
                    title.setTextColor(rust);
                    return;
                }
                AlarmScheduler.addDeadline(this, day.getTimeInMillis(), what);
            }
            paint();
            close.run();
        });
    }

    private void paintChip(TextView t, boolean on) {
        t.setTextColor(on ? 0xFFFFFFFF : ink2);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(on ? rust : card);
        g.setStroke(dp(1.5f), on ? rust : line);
        t.setBackground(g);
    }

    /** HH:MM big on top (tap one to set it), a clock dial below to drag round: 24 hours, or 60 minutes. */
    private final class Dial extends android.view.View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        int hour, minute;
        private boolean minutes;
        private final android.graphics.RectF hRect = new android.graphics.RectF(), mRect = new android.graphics.RectF();

        Dial(int h, int m) {
            super(WeckerActivity.this);
            hour = h;
            minute = m;
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            // Time on top.
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(dp(56));
            p.setLetterSpacing(-0.04f);
            String hh = String.format(Locale.ROOT, "%02d", hour), mm = String.format(Locale.ROOT, "%02d", minute);
            float hw = p.measureText(hh), cw = p.measureText(":"), mw = p.measureText(mm);
            float x = (w - hw - cw - mw) / 2, base = dp(60);
            p.setColor(minutes ? ink3 : rust);
            c.drawText(hh, x, base, p);
            hRect.set(x - dp(8), 0, x + hw + dp(8), base + dp(10));
            p.setColor(ink3);
            c.drawText(":", x + hw, base, p);
            p.setColor(minutes ? rust : ink3);
            c.drawText(mm, x + hw + cw, base, p);
            mRect.set(x + hw + cw - dp(8), 0, x + hw + cw + mw + dp(8), base + dp(10));
            p.setLetterSpacing(0);
            // Dial.
            float cx = w / 2, cy = base + dp(24) + (h - base - dp(24)) / 2, r = Math.min(w / 2, (h - base - dp(24)) / 2) - dp(6);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(bg);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(line);
            c.drawCircle(cx, cy, r, p);
            int steps = minutes ? 60 : 24;
            float value = minutes ? minute : hour;
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            for (int k = 0; k < steps; k++) {
                double a = Math.PI * 2 * k / steps - Math.PI / 2;
                boolean major = minutes ? k % 5 == 0 : true;
                if (minutes && !major) {
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(dp(1));
                    p.setColor(ink3);
                    c.drawLine(cx + (float) Math.cos(a) * r * 0.86f, cy + (float) Math.sin(a) * r * 0.86f,
                        cx + (float) Math.cos(a) * r * 0.92f, cy + (float) Math.sin(a) * r * 0.92f, p);
                    continue;
                }
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(dp(minutes ? 13 : 12));
                boolean sel = k == (int) value;
                float rr = r * 0.8f;
                if (sel) {
                    p.setColor(rust);
                    c.drawCircle(cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr, dp(16), p);
                }
                p.setColor(sel ? 0xFFFFFFFF : k % 6 == 0 || minutes ? ink : ink2);
                c.drawText(String.format(Locale.ROOT, "%02d", k), cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr + dp(4.5f), p);
            }
            // Hand.
            double a = Math.PI * 2 * value / steps - Math.PI / 2;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setColor(rust);
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * r * 0.62f, cy + (float) Math.sin(a) * r * 0.62f, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            c.drawCircle(cx, cy, dp(5), p);
            p.setTextAlign(android.graphics.Paint.Align.LEFT);
            p.setTextSize(dp(10));
            p.setTypeface(Typeface.MONOSPACE);
            p.setColor(ink3);
            String hint = minutes ? "MINUTEN · RUNDHERUM ZIEHEN" : "STUNDEN · RUNDHERUM ZIEHEN · DANN MINUTEN";
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            c.drawText(hint, cx, h - dp(2), p);
            p.setTextAlign(android.graphics.Paint.Align.LEFT);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            float x = e.getX(), y = e.getY();
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN && (hRect.contains(x, y) || mRect.contains(x, y))) {
                minutes = mRect.contains(x, y);
                invalidate();
                return true;
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            float base = dp(60), w = getWidth(), h = getHeight();
            float cx = w / 2, cy = base + dp(24) + (h - base - dp(24)) / 2;
            double ang = Math.atan2(y - cy, x - cx) + Math.PI / 2;
            if (ang < 0) ang += Math.PI * 2;
            int steps = minutes ? 60 : 24;
            int v = (int) Math.round(ang / (Math.PI * 2) * steps) % steps;
            if (minutes) {
                if (v != minute) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                minute = v;
            } else {
                if (v != hour) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                hour = v;
                if (a == android.view.MotionEvent.ACTION_UP) minutes = true;       // hours set: on to the minutes
            }
            invalidate();
            return true;
        }
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
