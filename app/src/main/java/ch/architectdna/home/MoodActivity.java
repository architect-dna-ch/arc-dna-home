package ch.architectdna.home;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Stimmung: one tap a few times a day (😣 … 😄, optional word), kept on the phone. A heatmap of
 * weekday × hour shows when you're usually up or down, plus your best and hardest day and time,
 * the last two weeks as a line, and how music listening goes along with it.
 */
public class MoodActivity extends Activity {

    private static final String[] FACES = {"😣", "😕", "😐", "🙂", "😄"};
    private static final String[] WORDS = {"Mies", "Naja", "Okay", "Gut", "Super"};
    private static final int[] COLS = {0xFFD9472F, 0xFFF29A1F, 0xFFFFC400, 0xFF8BD14A, 0xFF2BBF6A};
    private static final String[] DAYS = {"MO", "DI", "MI", "DO", "FR", "SA", "SO"};

    private SharedPreferences prefs;
    private int bg, card, line, ink, ink2, ink3, rust;
    private Heat heat;
    private Trend trend;
    private TextView insight, recent;
    private EditText note;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        prefs = getSharedPreferences("mood", MODE_PRIVATE);
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
        getWindow().getDecorView();  // decor must exist or getInsetsController() NPEs here on Android 16 (SDK 36)
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
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                col.setPadding(dp(22) + i.left, dp(18) + i.top, dp(22) + i.right, dp(40) + i.bottom);
            }
            return in;
        });

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = mono(11, ink3);
        label.setText("— STIMMUNG");
        label.setLetterSpacing(0.24f);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(22, ink, true);
        close.setText("✕");
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);
        TextView ask = text(28, ink, true);
        ask.setText("Wie geht's dir gerade?");
        ask.setLetterSpacing(-0.03f);
        col.addView(ask, margins(-1, -2, dp(12)));

        LinearLayout faces = new LinearLayout(this);
        for (int i = 0; i < 5; i++) {
            int mood = i + 1;
            LinearLayout f = new LinearLayout(this);
            f.setOrientation(LinearLayout.VERTICAL);
            f.setGravity(Gravity.CENTER);
            f.setPadding(0, dp(10), 0, dp(8));
            f.setBackground(box(card, line, 18));
            TextView e = text(30, ink, false);
            e.setText(FACES[i]);
            e.setGravity(Gravity.CENTER);
            f.addView(e);
            TextView w = mono(10, ink2);
            w.setText(WORDS[i].toUpperCase(Locale.GERMAN));
            w.setGravity(Gravity.CENTER);
            f.addView(w);
            f.setOnClickListener(v -> {
                log(mood);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            faces.addView(f, lp);
        }
        col.addView(faces, margins(-1, -2, dp(4)));
        note = new EditText(this);
        note.setHint("Ein Wort dazu? (vorher oder gleich danach)");
        note.setTextColor(ink);
        note.setHintTextColor(ink3);
        note.setTextSize(15);
        note.setSingleLine(true);
        note.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        note.setOnEditorActionListener((v, id, ev) -> {
            attachNote();
            return false;
        });
        note.setBackground(box(card, line, 14));
        note.setPadding(dp(14), dp(10), dp(14), dp(10));
        col.addView(note, margins(-1, -2, dp(10)));

        col.addView(section("— WANN GEHT'S DIR WIE · WOCHENTAG × UHRZEIT"), margins(-1, -2, dp(26)));
        heat = new Heat();
        heat.setBackground(box(card, line, 18));
        col.addView(heat, margins(-1, dp(250), dp(8)));
        insight = text(15, ink, false);
        insight.setLineSpacing(0, 1.25f);
        insight.setPadding(dp(16), dp(14), dp(16), dp(14));
        insight.setBackground(box(card, rust, 18));
        col.addView(insight, margins(-1, -2, dp(10)));

        col.addView(section("— DIE LETZTEN 14 TAGE"), margins(-1, -2, dp(26)));
        trend = new Trend();
        trend.setBackground(box(card, line, 18));
        col.addView(trend, margins(-1, dp(160), dp(8)));

        col.addView(section("— ZULETZT"), margins(-1, -2, dp(26)));
        recent = text(14, ink2, false);
        recent.setLineSpacing(0, 1.35f);
        col.addView(recent, margins(-1, -2, dp(8)));
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(sv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        statusScrim(root, bg);
        setContentView(root);
        refresh();
    }

    private TextView section(String s) {
        TextView t = mono(11, ink3);
        t.setText(s);
        t.setLetterSpacing(0.2f);
        return t;
    }

    /* ---------- data ---------- */

    static JSONArray entries(android.content.Context c) {
        try {
            return new JSONArray(c.getSharedPreferences("mood", MODE_PRIVATE).getString("log", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void log(int mood) {
        JSONArray a = entries(this);
        try {
            a.put(new JSONObject().put("at", System.currentTimeMillis()).put("m", mood).put("n", note.getText().toString().trim()));
        } catch (Exception ignored) {
        }
        prefs.edit().putString("log", a.toString()).apply();
        note.setText("");
        refresh();
    }

    private final float[][] sum = new float[7][24];
    private final int[][] cnt = new int[7][24];
    private final float[] daySum = new float[14];
    private final int[] dayCnt = new int[14];

    private void refresh() {
        for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++) {
            sum[d][h] = 0;
            cnt[d][h] = 0;
        }
        java.util.Arrays.fill(daySum, 0);
        java.util.Arrays.fill(dayCnt, 0);
        JSONArray a = entries(this);
        Calendar c = Calendar.getInstance();
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        float[] wd = new float[7];
        int[] wn = new int[7];
        float[] slot = new float[4];
        int[] sn = new int[4];
        StringBuilder last = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("EEE d.M. HH:mm", Locale.GERMAN);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            long at = o.optLong("at");
            int m = o.optInt("m");
            c.setTimeInMillis(at);
            int d = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7, h = c.get(Calendar.HOUR_OF_DAY);
            sum[d][h] += m;
            cnt[d][h]++;
            wd[d] += m;
            wn[d]++;
            int sl = h < 6 ? 3 : h < 12 ? 0 : h < 18 ? 1 : 2;
            slot[sl] += m;
            sn[sl]++;
            int ago = (int) Math.floor((today.getTimeInMillis() - at) / 86_400_000.0) + 1;
            if (at >= today.getTimeInMillis()) ago = 0;
            if (ago >= 0 && ago < 14) {
                daySum[13 - ago] += m;
                dayCnt[13 - ago]++;
            }
            if (i >= a.length() - 8) {
                String n = o.optString("n");
                last.insert(0, FACES[m - 1] + "  " + f.format(new Date(at)) + (n.isEmpty() ? "" : " · " + n) + "\n");
            }
        }
        recent.setText(a.length() == 0 ? "Noch nichts – tipp oben auf ein Gesicht." : last.toString().trim());
        // Insight.
        if (a.length() < 5) {
            insight.setText("Ab 5 Einträgen zeige ich dir Muster. Tipp ein paar Mal am Tag – morgens, mittags, abends. (" + a.length() + "/5)");
        } else {
            int best = -1, worst = -1;
            for (int d = 0; d < 7; d++) {
                if (wn[d] == 0) continue;
                if (best < 0 || wd[d] / wn[d] > wd[best] / wn[best]) best = d;
                if (worst < 0 || wd[d] / wn[d] < wd[worst] / wn[worst]) worst = d;
            }
            String[] slots = {"morgens", "nachmittags", "abends", "nachts"};
            int bs = -1, ws = -1;
            for (int i = 0; i < 4; i++) {
                if (sn[i] == 0) continue;
                if (bs < 0 || slot[i] / sn[i] > slot[bs] / sn[bs]) bs = i;
                if (ws < 0 || slot[i] / sn[i] < slot[ws] / sn[ws]) ws = i;
            }
            String[] full = {"Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"};
            StringBuilder b = new StringBuilder();
            b.append("Am besten geht's dir ").append(full[best].equals(full[worst]) ? "" : "am " + full[best] + " ").append(bs >= 0 ? slots[bs] : "").append(" (")
                .append(String.format(Locale.GERMAN, "%.1f", wd[best] / wn[best])).append(" ").append(FACES[Math.round(wd[best] / wn[best]) - 1]).append(").\n");
            if (worst != best) b.append("Schwerer: ").append(full[worst]).append(ws >= 0 && ws != bs ? ", eher " + slots[ws] : "").append(" (")
                .append(String.format(Locale.GERMAN, "%.1f", wd[worst] / wn[worst])).append(").\n");
            long[] week = MusicStats.week(this);
            long musicToday = week[6];
            float todayMood = dayCnt[13] == 0 ? -1 : daySum[13] / dayCnt[13];
            if (todayMood > 0) b.append("Heute: ").append(String.format(Locale.GERMAN, "%.1f", todayMood)).append(" bei ").append(musicToday / 60).append(" h ")
                .append(musicToday % 60).append(" min Musik.");
            insight.setText(b.toString().trim());
        }
        heat.invalidate();
        trend.invalidate();
    }

    /* ---------- views ---------- */

    private int colourFor(float avg) {
        float k = Math.max(0, Math.min(4, avg - 1));
        int i = (int) Math.min(3, Math.floor(k));
        float f = k - i;
        int a = COLS[i], b = COLS[Math.min(4, i + 1)];
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f), g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f),
            bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private final class Heat extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Heat() {
            super(MoodActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), left = dp(34), top = dp(14), bottom = h - dp(28), right = w - dp(12);
            float cw = (right - left) / 24, ch = (bottom - top) / 7;
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(9));
            for (int d = 0; d < 7; d++) {
                p.setColor(ink3);
                c.drawText(DAYS[d], dp(10), top + d * ch + ch * 0.65f, p);
                for (int hr = 0; hr < 24; hr++) {
                    float x = left + hr * cw, y = top + d * ch;
                    p.setColor(cnt[d][hr] == 0 ? (line & 0x00FFFFFF) | 0x66000000 : colourFor(sum[d][hr] / cnt[d][hr]));
                    c.drawRoundRect(x + dp(1), y + dp(1), x + cw - dp(1), y + ch - dp(1), dp(3), dp(3), p);
                }
            }
            p.setColor(ink3);
            p.setTextAlign(Paint.Align.CENTER);
            for (int hr = 0; hr < 24; hr += 3) c.drawText(String.format(Locale.ROOT, "%02d", hr), left + hr * cw + cw / 2, h - dp(10), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    private final class Trend extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        Trend() {
            super(MoodActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), l = dp(16), r = w - dp(16), t = dp(18), b = h - dp(26);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(line);
            for (int i = 1; i <= 5; i++) {
                float y = b - (i - 1) / 4f * (b - t);
                c.drawLine(l, y, r, y, p);
            }
            path.reset();
            boolean started = false;
            for (int i = 0; i < 14; i++) {
                if (dayCnt[i] == 0) continue;
                float x = l + i / 13f * (r - l), y = b - (daySum[i] / dayCnt[i] - 1) / 4f * (b - t);
                if (!started) path.moveTo(x, y);
                else path.lineTo(x, y);
                started = true;
            }
            p.setStrokeWidth(dp(3));
            p.setColor(rust);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 14; i++) {
                if (dayCnt[i] == 0) continue;
                float avg = daySum[i] / dayCnt[i];
                float x = l + i / 13f * (r - l), y = b - (avg - 1) / 4f * (b - t);
                p.setColor(colourFor(avg));
                c.drawCircle(x, y, dp(5), p);
            }
            p.setColor(ink3);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(9));
            c.drawText("VOR 2 WOCHEN", l, h - dp(8), p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("HEUTE", r, h - dp(8), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    /* ---------- helpers ---------- */

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

    /** A solid strip behind the status bar: scrolled content no longer runs under the clock and icons. */
    static void statusScrim(android.widget.FrameLayout root, int colour) {
        View scrim = new View(root.getContext());
        scrim.setBackgroundColor(colour);
        root.addView(scrim, new android.widget.FrameLayout.LayoutParams(-1, 0, android.view.Gravity.TOP));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top = android.os.Build.VERSION.SDK_INT >= 30
                ? in.getInsets(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.displayCutout()).top
                : in.getSystemWindowInsetTop();
            scrim.getLayoutParams().height = top;
            scrim.requestLayout();
            // Hand the insets on to the children (the scroll view pads itself with them).
            for (int i = 0; i < root.getChildCount(); i++) root.getChildAt(i).dispatchApplyWindowInsets(in);
            return in;
        });
    }

    /** A word typed right after tapping a face (within 5 min) goes to that entry. */
    private void attachNote() {
        String n = note.getText().toString().trim();
        if (n.isEmpty()) return;
        JSONArray a = entries(this);
        JSONObject last = a.length() == 0 ? null : a.optJSONObject(a.length() - 1);
        if (last == null || System.currentTimeMillis() - last.optLong("at") > 5 * 60_000L) return;
        try {
            last.put("n", n);
        } catch (Exception ignored) {
        }
        prefs.edit().putString("log", a.toString()).apply();
        note.setText("");
        refresh();
    }

}
