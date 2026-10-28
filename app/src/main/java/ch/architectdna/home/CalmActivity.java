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
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Gelassen: for the moment something annoys you. Say what (waiting for the bus, traffic, rain …)
 * and it puts it in perspective – gently, with people for whom the same thing is much harder –
 * then one minute of breathing and three things you're grateful for, kept as a small diary with
 * a streak. Everything stays on the phone.
 */
public class CalmActivity extends Activity {

    /** situation, then perspectives (several), then "what to do with it". */
    private static final String[][] SITUATIONS = {
        {"🚌 Warten",
            "Du wartest 10 Minuten in der Stadt. In vielen Dörfern fährt der Bus nur jede Stunde – am Sonntag manchmal gar nicht.",
            "Irgendwo geht gerade jemand zu Fuss zur Arbeit, weil es keinen Bus gibt. Eine Stunde hin, eine zurück.",
            "Wer im Rollstuhl sitzt, wartet manchmal auf den nächsten Bus, weil im ersten die Rampe nicht geht – und lächelt trotzdem dem Fahrer zu.",
            "Der Bus kommt. Mit Fahrplan, Heizung und jemandem am Steuer, der für dich früh aufgestanden ist.",
            "Zehn Minuten sind ein Geschenk, das du nicht bestellt hast: genug für eine Nachricht an jemanden, den du gern hast."},
        {"🚗 Verspätung",
            "Du kommst zu spät. Irgendwo hätte jemand gern überhaupt einen Ort, an den er zu spät kommen kann.",
            "Ein Notarzt steht gerade im selben Stau – und ruft trotzdem ruhig an, dass er gleich da ist.",
            "In vielen Ländern ist die Strasse zur Schule ein Fluss ohne Brücke. Dein Stau hat Asphalt.",
            "Zu spät ist eine Zeitangabe, kein Urteil über dich. Schreib kurz Bescheid – und dann atme."},
        {"🌧 Wetter",
            "Es regnet auf deine Jacke. Irgendwo regnet es durch ein Dach, das es eigentlich gar nicht gibt.",
            "Bauern, die seit Wochen auf Regen warten, würden jetzt mit dir tauschen.",
            "Kalte Hände heute heisst: du hast ein Zuhause, in dem sie wieder warm werden.",
            "Wetter ist das Einzige, das garantiert vorbeigeht."},
        {"💼 Arbeit / Schule",
            "Zu viel zu tun heisst: jemand traut dir etwas zu.",
            "Viele Menschen suchen gerade Arbeit – oder dürfen gar nicht zur Schule gehen.",
            "Eine schwierige Aufgabe ist nur eine Aufgabe, die du noch nicht in kleine Stücke geschnitten hast.",
            "Mach den kleinsten nächsten Schritt. Nur den."},
        {"📱 Handy / Akku / Netz",
            "Dein Akku ist leer. Irgendwo lädt jemand sein Handy einmal pro Woche im Dorfladen – für die ganze Familie.",
            "Langsames Internet ist immer noch ein Fenster in die ganze Welt.",
            "Ohne Handy siehst du vielleicht etwas, das du sonst verpasst hättest. Schau hoch."},
        {"🔊 Lärm & Leute",
            "Laute Menschen um dich heisst: du bist nicht allein. Manche würden viel geben, um nicht allein zu sein.",
            "Jede nervige Person hat heute auch etwas getragen, das du nicht siehst.",
            "Ohren können nicht zu – aber du kannst dir einen ruhigen Satz denken und ihn wiederholen."},
        {"😴 Müde",
            "Müde sein heisst: du hast heute etwas gegeben.",
            "Schichtarbeiter, junge Eltern, Pflegende – viele sind gerade müder als du und machen weiter.",
            "Du darfst langsamer. Heute ist kein Wettrennen."},
        {"💸 Geld",
            "Du ärgerst dich über eine Ausgabe – also hattest du etwas, das du ausgeben konntest.",
            "Viele Menschen rechnen gerade, ob es fürs Essen bis Monatsende reicht.",
            "Geld kommt und geht. Was du heute für jemanden tust, bleibt."},
        {"🔧 Kaputt",
            "Etwas ist kaputt. Es war also etwas da, das funktioniert hat – die meiste Zeit.",
            "Irgendwo repariert jemand mit Draht und Geduld, was du einfach ersetzen kannst.",
            "Kaputt ist ein Zustand, kein Ende. Und vielleicht eine Geschichte für später."}};

    private SharedPreferences prefs;
    private int bg, card, line, ink, ink2, ink3, rust, green = 0xFF2BBF6A;
    private final Random rnd = new Random();
    private int situation = 0, perspective = 1;
    private TextView quote, quoteTag, streak;
    private LinearLayout diary;
    private final TextView[] chips = new TextView[SITUATIONS.length];

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        prefs = getSharedPreferences("calm", MODE_PRIVATE);
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
        label.setText("— GELASSEN");
        label.setLetterSpacing(0.24f);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(22, ink, true);
        close.setText("✕");
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        TextView ask = text(28, ink, true);
        ask.setText("Was nervt gerade?");
        ask.setLetterSpacing(-0.03f);
        col.addView(ask, margins(-1, -2, dp(14)));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        for (int i = 0; i < SITUATIONS.length; i++) {
            int k = i;
            TextView c = mono(13, ink2);
            c.setText(SITUATIONS[i][0].toUpperCase(Locale.GERMAN));
            c.setPadding(dp(14), dp(9), dp(14), dp(9));
            c.setOnClickListener(v -> pick(k));
            chips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            row.addView(c, lp);
        }
        col.addView(hs, margins(-1, -2, dp(12)));

        // The perspective card: tap = another one.
        LinearLayout qc = new LinearLayout(this);
        qc.setOrientation(LinearLayout.VERTICAL);
        qc.setPadding(dp(20), dp(18), dp(20), dp(18));
        qc.setBackground(box(card, rust, 22));
        quoteTag = mono(10, rust);
        quoteTag.setLetterSpacing(0.2f);
        qc.addView(quoteTag);
        quote = text(20, ink, false);
        quote.setLineSpacing(0, 1.15f);
        quote.setPadding(0, dp(10), 0, 0);
        qc.addView(quote);
        TextView more = mono(10, ink3);
        more.setText("TIPPEN = NOCH EIN BLICK");
        more.setPadding(0, dp(12), 0, 0);
        qc.addView(more);
        qc.setOnClickListener(v -> nextPerspective());
        col.addView(qc, margins(-1, -2, dp(14)));

        // One minute of breathing.
        TextView bh = mono(11, ink3);
        bh.setText("— EINE MINUTE ATMEN");
        bh.setLetterSpacing(0.2f);
        col.addView(bh, margins(-1, -2, dp(26)));
        Breath breath = new Breath();
        breath.setBackground(box(card, line, 22));
        col.addView(breath, margins(-1, dp(260), dp(10)));

        // Gratitude.
        TextView gh = mono(11, ink3);
        gh.setText("— DREI DINGE, FÜR DIE DU DANKBAR BIST");
        gh.setLetterSpacing(0.2f);
        col.addView(gh, margins(-1, -2, dp(26)));
        EditText[] fields = new EditText[3];
        String[] hints = {"Etwas Kleines von heute …", "Ein Mensch …", "Etwas, das du sonst übersiehst …"};
        for (int i = 0; i < 3; i++) {
            EditText e = new EditText(this);
            e.setHint((i + 1) + ". " + hints[i]);
            e.setTextColor(ink);
            e.setHintTextColor(ink3);
            e.setTextSize(17);
            e.setBackground(box(card, line, 14));
            e.setPadding(dp(14), dp(12), dp(14), dp(12));
            e.setSingleLine(true);
            fields[i] = e;
            col.addView(e, margins(-1, -2, dp(8)));
        }
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        streak = mono(12, green);
        sr.addView(streak, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView save = mono(13, 0xFFFFFFFF);
        save.setText("SPEICHERN ✓");
        save.setPadding(dp(18), dp(11), dp(18), dp(11));
        save.setBackground(box(rust, rust, 22));
        save.setOnClickListener(v -> {
            StringBuilder b = new StringBuilder();
            for (EditText f : fields) {
                String t = f.getText().toString().trim();
                if (!t.isEmpty()) {
                    if (b.length() > 0) b.append(" · ");
                    b.append(t);
                }
            }
            if (b.length() == 0) return;
            remember(b.toString());
            for (EditText f : fields) f.setText("");
            paintDiary();
            quoteTag.setText("DANKE");
            quote.setText("Gespeichert. Lies es wieder, wenn der nächste Bus zu spät ist.");
        });
        sr.addView(save);
        col.addView(sr, margins(-1, -2, dp(12)));

        diary = new LinearLayout(this);
        diary.setOrientation(LinearLayout.VERTICAL);
        col.addView(diary, margins(-1, -2, dp(18)));

        FrameLayout root = new FrameLayout(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        pick(prefs.getInt("last", 0));
        paintDiary();
    }

    private void pick(int k) {
        situation = k;
        prefs.edit().putInt("last", k).apply();
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == k;
            chips[i].setTextColor(on ? 0xFFFFFFFF : ink2);
            chips[i].setBackground(box(on ? rust : card, on ? rust : line, 18));
        }
        perspective = 1 + rnd.nextInt(SITUATIONS[k].length - 1);
        showPerspective();
    }

    private void nextPerspective() {
        int n = SITUATIONS[situation].length - 1;
        perspective = perspective % n + 1;
        showPerspective();
    }

    private void showPerspective() {
        quoteTag.setText("PERSPEKTIVE " + perspective + " / " + (SITUATIONS[situation].length - 1));
        quote.setAlpha(0f);
        quote.setText(SITUATIONS[situation][perspective]);
        quote.animate().alpha(1f).setDuration(300).start();
    }

    /* ---------- the diary ---------- */

    private JSONArray entries() {
        try {
            return new JSONArray(prefs.getString("diary", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void remember(String text) {
        JSONArray a = entries();
        try {
            a.put(new JSONObject().put("at", System.currentTimeMillis()).put("text", text));
        } catch (Exception ignored) {
        }
        prefs.edit().putString("diary", a.toString()).apply();
    }

    /** Days in a row with at least one entry, up to today (or yesterday). */
    private int streakDays(JSONArray a) {
        SimpleDateFormat d = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        java.util.Set<String> days = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) days.add(d.format(new Date(a.optJSONObject(i).optLong("at"))));
        java.util.Calendar c = java.util.Calendar.getInstance();
        if (!days.contains(d.format(c.getTime()))) c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        int n = 0;
        while (days.contains(d.format(c.getTime()))) {
            n++;
            c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        }
        return n;
    }

    private void paintDiary() {
        JSONArray a = entries();
        int st = streakDays(a);
        streak.setText(st == 0 ? "NOCH KEINE SERIE" : "🌱 " + st + (st == 1 ? " TAG" : " TAGE") + " IN FOLGE");
        diary.removeAllViews();
        SimpleDateFormat f = new SimpleDateFormat("EEE d. MMM", Locale.GERMAN);
        for (int i = a.length() - 1; i >= Math.max(0, a.length() - 7); i--) {
            JSONObject o = a.optJSONObject(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(16), dp(12), dp(16), dp(12));
            r.setBackground(box(card, line, 16));
            TextView w = mono(10, ink3);
            w.setText(f.format(new Date(o.optLong("at"))).toUpperCase(Locale.GERMAN));
            r.addView(w);
            TextView t = text(15, ink, false);
            t.setText(o.optString("text"));
            t.setPadding(0, dp(4), 0, 0);
            r.addView(t);
            diary.addView(r, margins(-1, -2, dp(8)));
        }
    }

    /* ---------- one minute of breathing: 4 in, 4 hold, 6 out ---------- */

    private final class Breath extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long start = -1;

        Breath() {
            super(CalmActivity.this);
            setOnClickListener(v -> {
                start = start < 0 ? SystemClock.uptimeMillis() : -1;
                invalidate();
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2 - dp(22), rMax = Math.min(w, h) * 0.3f;
            String say;
            float k;
            if (start < 0) {
                k = 0.45f;
                say = "TIPPEN = START";
            } else {
                long t = SystemClock.uptimeMillis() - start;
                if (t > 60_000) {
                    start = -1;
                    k = 0.45f;
                    say = "GUT GEMACHT 🌿";
                } else {
                    float cyc = (t % 14_000) / 1000f;
                    if (cyc < 4) {
                        k = 0.35f + 0.65f * ease(cyc / 4);
                        say = "EINATMEN · " + (4 - (int) cyc);
                    } else if (cyc < 8) {
                        k = 1f;
                        say = "HALTEN · " + (8 - (int) cyc);
                    } else {
                        k = 1f - 0.65f * ease((cyc - 8) / 6);
                        say = "AUSATMEN · " + (14 - (int) cyc);
                    }
                    p.setTypeface(Typeface.MONOSPACE);
                    p.setTextSize(dp(10));
                    p.setColor(ink3);
                    p.setTextAlign(Paint.Align.CENTER);
                    c.drawText(((60_000 - t) / 1000) + " S", cx, h - dp(14), p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor((green & 0x00FFFFFF) | 0x26000000);
            c.drawCircle(cx, cy, rMax * k * 1.15f, p);
            p.setColor((green & 0x00FFFFFF) | 0x55000000);
            c.drawCircle(cx, cy, rMax * k, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(green);
            c.drawCircle(cx, cy, rMax * k, p);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(14));
            p.setColor(ink);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(say, cx, cy + rMax * 1.15f + dp(22), p);          // under the circle, never across it
            p.setTextAlign(Paint.Align.LEFT);
            if (start >= 0) postInvalidateOnAnimation();
        }

        private float ease(float t) {
            t = Math.max(0, Math.min(1, t));
            return t * t * (3 - 2 * t);
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
}
