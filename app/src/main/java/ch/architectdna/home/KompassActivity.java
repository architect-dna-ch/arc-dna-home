package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Kompass: a few fixed points for days without rules. The basics first (food, sleep, movement),
 * your own direct rules, a parking place for doubts (they wait for the Sunday check-in instead of
 * running all week), the "does this do me good?" test instead of "is this allowed?", two money
 * envelopes (eating out per week, clothes per month) and a log of contacts with how they felt –
 * you set the pace and the dose. Nothing leaves the phone.
 */
public class KompassActivity extends Activity {

    private static final String[] ANCHORS = {"🍳 Frühstück", "🍝 Mittag", "🍲 Abend", "😴 Schlaf ≥ 7 h", "🥊 Bewegung"};
    private static final String[] RULES = {
        "Ich esse dreimal am Tag. Nicht verhandelbar.",
        "Schlaf geht vor allem Zusätzlichen.",
        "„Vielleicht verboten“ heisst: erlaubt. Der Zweifel entscheidet nicht.",
        "Jede Woche hole ich eine gute Sache zurück, die ich weggeschnitten habe.",
        "Fragen nach richtig und falsch warten auf Sonntag.",
        "Ich bestimme Tempo und Dosis – bei Musik, Familie, Menschen."};
    private static final String[] KINDS = {"👨‍👩‍👦 Familie", "🤝 Freunde", "💬 Date / Frau", "🕌 Community", "🎶 Musik von früher", "🥊 Gym / Sport"};
    private static final String[][] HISTORY = {
        {"Abu Zayd al-Balkhi · 9. Jh.", "Beschrieb zwanghafte Zweifel, Angst und Traurigkeit als Krankheiten der Seele – nicht als Sünde – und behandelte sie mit Reden, Ablenkung und neuen Gedanken-Gewohnheiten."},
        {"Al-Ghazali · 11. Jh.", "Brach auf dem Höhepunkt seiner Karriere zusammen, konnte kaum sprechen oder essen, ging weg, reiste jahrelang – und kam zurück. Er schrieb offen darüber."},
        {"Ibn Qudama · 12./13. Jh.", "Schrieb ein ganzes Buch gegen übertriebene Skrupel: Strenge, die quält, ist keine Frömmigkeit, sondern eine Falle."},
        {"Ibn Hajar al-Haytami · 16. Jh.", "Gegen zwanghafte Zweifel hilft nichts besser, als sie komplett zu ignorieren – egal wie laut sie werden."},
        {"Heute", "Zwanghafte Zweifel und Skrupel sind bekannt und gut behandelbar (Therapie mit ERP). Viele haben das hinter sich gebracht."}};

    private SharedPreferences prefs;
    private int bg, card, line, ink, ink2, ink3, rust, green = 0xFF2BBF6A;
    private FrameLayout root;
    private LinearLayout anchors, doubts, money, contacts;
    private TextView testAnswer, anchorNote;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        prefs = getSharedPreferences("kompass", MODE_PRIVATE);
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
        label.setText("— KOMPASS");
        label.setLetterSpacing(0.24f);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = text(22, ink, true);
        close.setText("✕");
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        // 1. The basics, before any big question.
        TextView t1 = text(28, ink, true);
        t1.setText("Heute zuerst.");
        t1.setLetterSpacing(-0.03f);
        col.addView(t1, margins(dp(12)));
        TextView s1 = text(14, ink2, false);
        s1.setText("Essen, Schlaf, Bewegung kommen vor jeder Entscheidung über Charakter, Menschen oder Religion.");
        col.addView(s1, margins(dp(4)));
        anchors = new LinearLayout(this);
        anchors.setOrientation(LinearLayout.VERTICAL);
        col.addView(anchors, margins(dp(12)));
        anchorNote = mono(11, green);
        col.addView(anchorNote, margins(dp(6)));

        // 2. Direct rules.
        col.addView(section("DEINE REGELN"), margins(dp(28)));
        for (String r : RULES) {
            TextView rv = text(15, ink, false);
            rv.setText("•  " + r);
            rv.setPadding(dp(14), dp(10), dp(14), dp(10));
            rv.setBackground(box(card, line, 14));
            col.addView(rv, margins(dp(6)));
        }

        // 3. Park the doubt.
        col.addView(section("ZWEIFEL PARKEN"), margins(dp(28)));
        TextView s3 = text(14, ink2, false);
        s3.setText("Der Gedanke dreht? Aufschreiben, parken. Er wartet bis Sonntag – du musst ihn jetzt nicht lösen.");
        col.addView(s3, margins(dp(4)));
        EditText doubt = field("Was zweifelt gerade? …");
        col.addView(doubt, margins(dp(8)));
        LinearLayout dRow = new LinearLayout(this);
        TextView park = chip("🅿 PARKEN", true);
        park.setOnClickListener(v -> {
            String d = doubt.getText().toString().trim();
            if (d.isEmpty()) return;
            addTo("doubts", new String[]{"t", d});
            doubt.setText("");
            hideKeyboard(doubt);
            paintDoubts();
            toast("Geparkt bis Sonntag. Du darfst jetzt was anderes machen.");
        });
        dRow.addView(park, weighted(dp(8)));
        TextView review = chip("☀ CHECK-IN", false);
        review.setOnClickListener(v -> checkIn());
        dRow.addView(review, weighted(0));
        col.addView(dRow, margins(dp(8)));
        doubts = new LinearLayout(this);
        doubts.setOrientation(LinearLayout.VERTICAL);
        col.addView(doubts, margins(dp(8)));

        // 4. Does this do me good?
        col.addView(section("TUT MIR DAS GUT?"), margins(dp(28)));
        TextView s4 = text(14, ink2, false);
        s4.setText("Statt „ist das erlaubt?“ oder „gehört das zu mir?“ – nur diese Frage.");
        col.addView(s4, margins(dp(4)));
        EditText what = field("Was steht an? (z. B. Party, Familie anrufen, Musik)");
        col.addView(what, margins(dp(8)));
        LinearLayout tRow = new LinearLayout(this);
        String[] tests = {"👍 TUT GUT", "🤷 WEISS NICHT", "👎 ZIEHT RUNTER"};
        String[] answers = {
            "Mach's. Ohne Rechtfertigung, ohne Nachprüfen.",
            "Kleine Dosis ausprobieren – danach kurz unten bei „Tempo & Dosis“ eintragen, wie's war.",
            "Lass es oder mach's kürzer. Du hältst die Bremse – und das ist kein Versagen."};
        for (int i = 0; i < 3; i++) {
            TextView b = chip(tests[i], i == 0);
            int k = i;
            b.setOnClickListener(v -> {
                String w = what.getText().toString().trim();
                testAnswer.setText((w.isEmpty() ? "" : "„" + w + "“ – ") + answers[k]);
                testAnswer.setVisibility(View.VISIBLE);
                hideKeyboard(what);
            });
            tRow.addView(b, weighted(i < 2 ? dp(6) : 0));
        }
        col.addView(tRow, margins(dp(8)));
        testAnswer = text(15, ink, false);
        testAnswer.setPadding(dp(14), dp(12), dp(14), dp(12));
        testAnswer.setBackground(box(card, rust, 14));
        testAnswer.setVisibility(View.GONE);
        col.addView(testAnswer, margins(dp(8)));

        // 5. Money envelopes.
        col.addView(section("GELD · ZWEI KUVERTS"), margins(dp(28)));
        TextView s5 = text(14, ink2, false);
        s5.setText("Kleider-Geld zuerst zur Seite – dann frisst Auswärts-Essen es nicht auf. Ist die Woche leer: kochen, ohne Schuld. Halten = Betrag ändern.");
        col.addView(s5, margins(dp(4)));
        money = new LinearLayout(this);
        money.setOrientation(LinearLayout.VERTICAL);
        col.addView(money, margins(dp(8)));

        // 6. Pace and dose.
        col.addView(section("TEMPO & DOSIS"), margins(dp(28)));
        TextView s6 = text(14, ink2, false);
        s6.setText("Nach einem Kontakt: wie war's? Mit der Zeit siehst du, was dir guttut – ohne grosse Entscheidung.");
        col.addView(s6, margins(dp(4)));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout kRow = new LinearLayout(this);
        hs.addView(kRow);
        for (int i = 0; i < KINDS.length; i++) {
            TextView b = chip(KINDS[i], false);
            int k = i;
            b.setOnClickListener(v -> feelSheet(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            kRow.addView(b, lp);
        }
        col.addView(hs, margins(dp(8)));
        contacts = new LinearLayout(this);
        contacts.setOrientation(LinearLayout.VERTICAL);
        col.addView(contacts, margins(dp(8)));

        // 7. Not alone.
        TextView hist = section("DU BIST NICHT ALLEIN  ▸");
        col.addView(hist, margins(dp(28)));
        LinearLayout histBox = new LinearLayout(this);
        histBox.setOrientation(LinearLayout.VERTICAL);
        histBox.setVisibility(View.GONE);
        for (String[] h : HISTORY) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(14), dp(12), dp(14), dp(12));
            c.setBackground(box(card, line, 14));
            TextView n = mono(11, rust);
            n.setText(h[0].toUpperCase(Locale.GERMAN));
            c.addView(n);
            TextView b = text(14, ink, false);
            b.setText(h[1]);
            b.setLineSpacing(0, 1.12f);
            c.addView(b, margins(dp(4)));
            histBox.addView(c, margins(dp(6)));
        }
        hist.setOnClickListener(v -> {
            boolean open = histBox.getVisibility() != View.VISIBLE;
            histBox.setVisibility(open ? View.VISIBLE : View.GONE);
            hist.setText(open ? "— DU BIST NICHT ALLEIN  ▾" : "— DU BIST NICHT ALLEIN  ▸");
        });
        col.addView(histBox, margins(dp(4)));

        // 8. Help, quietly at the bottom.
        TextView help = text(14, ink2, false);
        help.setText("Wenn's zu schwer wird: 📞 143 · Die Dargebotene Hand – rund um die Uhr, anonym. Antippen = anrufen.");
        help.setPadding(dp(14), dp(12), dp(14), dp(12));
        help.setBackground(box(card, line, 14));
        help.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:143")));
            } catch (RuntimeException ignored) {
            }
        });
        col.addView(help, margins(dp(28)));

        root = new FrameLayout(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        MoodActivity.statusScrim(root, bg);
        setContentView(root);
        paint();
        // Sunday: the check-in offers itself.
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && list("doubts").length() > 0
            && !today().equals(prefs.getString("checkedIn", ""))) {
            root.postDelayed(this::checkIn, 600);
        }
    }

    /* ================= Painting ================= */

    private void paint() {
        paintAnchors();
        paintDoubts();
        paintMoney();
        paintContacts();
    }

    private String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
    }

    private void paintAnchors() {
        anchors.removeAllViews();
        String d = today();
        int done = 0;
        for (int i = 0; i < ANCHORS.length; i++) {
            boolean on = prefs.getBoolean("a:" + d + ":" + i, false);
            if (on) done++;
            TextView b = text(16, on ? 0xFFFFFFFF : ink, false);
            b.setText((on ? "✓  " : "○  ") + ANCHORS[i]);
            b.setPadding(dp(16), dp(12), dp(16), dp(12));
            b.setBackground(box(on ? green : card, on ? green : line, 16));
            int k = i;
            b.setOnClickListener(v -> {
                prefs.edit().putBoolean("a:" + d + ":" + k, !prefs.getBoolean("a:" + d + ":" + k, false)).apply();
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                paintAnchors();
            });
            anchors.addView(b, margins(dp(6)));
        }
        // Days in a row with all three meals.
        int streak = 0;
        Calendar c = Calendar.getInstance();
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        for (int i = 0; i < 60; i++) {
            String key = f.format(c.getTime());
            boolean meals = prefs.getBoolean("a:" + key + ":0", false) && prefs.getBoolean("a:" + key + ":1", false) && prefs.getBoolean("a:" + key + ":2", false);
            if (!meals && i > 0) break;
            if (meals) streak++;
            c.add(Calendar.DAY_OF_YEAR, -1);
        }
        anchorNote.setText(done == ANCHORS.length ? "ALLES DA · STARK. DER REST DES TAGES GEHÖRT DIR."
            : done + "/" + ANCHORS.length + " HEUTE" + (streak > 1 ? " · " + streak + " TAGE IN FOLGE DREI MAHLZEITEN" : ""));
    }

    private void paintDoubts() {
        doubts.removeAllViews();
        JSONArray a = list("doubts");
        int open = 0;
        for (int i = 0; i < a.length(); i++) if (!a.optJSONObject(i).optBoolean("done")) open++;
        TextView n = mono(11, open > 0 ? rust : ink3);
        n.setText(open == 0 ? "NICHTS GEPARKT" : open + " GEPARKT · WARTEN AUF SONNTAG");
        doubts.addView(n);
        JSONArray th = list("therapy");
        if (th.length() > 0) {
            TextView t = mono(11, ink2);
            t.setText("🩺 " + th.length() + " FÜR DIE THERAPIE NOTIERT · HALTEN = ANZEIGEN");
            t.setPadding(0, dp(6), 0, 0);
            t.setOnLongClickListener(v -> {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < th.length(); i++) b.append("• ").append(th.optJSONObject(i).optString("t")).append('\n');
                sheet("Für die Therapie", b.toString().trim(), new String[]{"Teilen", "Liste leeren", "Schliessen"}, k -> {
                    if (k == 0) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Für die Therapie:\n" + b), "Teilen"));
                    else if (k == 1) {
                        prefs.edit().putString("therapy", "[]").apply();
                        paintDoubts();
                    }
                });
                return true;
            });
            doubts.addView(t);
        }
    }

    /** Sunday check-in: every parked doubt once – done, still there, or for the therapist. */
    private void checkIn() {
        checkIn(0);
    }

    private void checkIn(int from) {
        JSONArray a = list("doubts");
        int idx = -1;
        for (int i = from; i < a.length(); i++) if (!a.optJSONObject(i).optBoolean("done")) {
            idx = i;
            break;
        }
        if (idx < 0) {
            prefs.edit().putString("checkedIn", today()).apply();
            sheet("Check-in", from == 0 ? "Nichts mehr geparkt. Die Woche gehört dir." : "Durch. Was noch geparkt ist, wartet bis nächsten Sonntag.",
                new String[]{"Gut"}, k -> { });
            return;
        }
        JSONObject o = a.optJSONObject(idx);
        int at = idx;
        sheet("Check-in · geparkter Zweifel", "„" + o.optString("t") + "“\n\nIst das heute noch wichtig?",
            new String[]{"✓ Erledigt – war nur der Zweifel", "↻ Bleibt geparkt", "🩺 Mit der Therapie besprechen"}, k -> {
                if (k != 1) {
                    try {
                        a.getJSONObject(at).put("done", true);
                    } catch (Exception ignored) {
                    }
                    prefs.edit().putString("doubts", a.toString()).apply();
                    if (k == 2) addTo("therapy", new String[]{"t", o.optString("t")});
                }
                paintDoubts();
                root.postDelayed(() -> checkIn(at + 1), 250);
            });
    }

    private int weekFood() {
        return prefs.getInt("foodWeek", 60);
    }

    private int monthClothes() {
        return prefs.getInt("clothesMonth", 90);
    }

    private void paintMoney() {
        money.removeAllViews();
        Calendar c = Calendar.getInstance();
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -dow);
        long weekStart = c.getTimeInMillis();
        Calendar m = Calendar.getInstance();
        m.set(Calendar.DAY_OF_MONTH, 1);
        m.set(Calendar.HOUR_OF_DAY, 0);
        m.set(Calendar.MINUTE, 0);
        long monthStart = m.getTimeInMillis();
        float food = 0, clothes = 0;
        JSONArray spend = list("spend");
        for (int i = 0; i < spend.length(); i++) {
            JSONObject o = spend.optJSONObject(i);
            if (o.optInt("k") == 0 && o.optLong("at") >= weekStart) food += o.optDouble("chf");
            if (o.optInt("k") == 1 && o.optLong("at") >= monthStart) clothes += o.optDouble("chf");
        }
        money.addView(envelope("🍔 Auswärts essen · diese Woche", food, weekFood(), 0, new int[]{40, 60, 80, 100, 120}, "foodWeek"));
        money.addView(envelope("👕 Kleider · dieser Monat", clothes, monthClothes(), 1, new int[]{50, 90, 120, 150, 200}, "clothesMonth"), margins(dp(8)));
    }

    private View envelope(String title, float spent, int budget, int kind, int[] steps, String key) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(box(card, line, 18));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(15, ink, true);
        t.setText(title);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        float left = budget - spent;
        TextView l = mono(13, left < 0 ? rust : left < budget * 0.25f ? 0xFFE8B931 : green);
        l.setText(String.format(Locale.ROOT, "CHF %.0f %s", Math.abs(left), left < 0 ? "DRÜBER" : "ÜBRIG"));
        top.addView(l);
        c.addView(top);
        View bar = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), f = Math.min(1, spent / Math.max(1, budget));
                p.setColor(line);
                cv.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
                p.setColor(spent > budget ? rust : green);
                if (f > 0) cv.drawRoundRect(0, 0, Math.max(h, w * f), h, h / 2, h / 2, p);
            }
        };
        c.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
        ((LinearLayout.LayoutParams) bar.getLayoutParams()).topMargin = dp(10);
        TextView sub = mono(10, ink3);
        sub.setText(String.format(Locale.ROOT, "CHF %.0f VON %d · ANTIPPEN = AUSGABE · HALTEN = BETRAG", spent, budget));
        c.addView(sub, margins(dp(6)));
        c.setOnClickListener(v -> spendSheet(kind));
        c.setOnLongClickListener(v -> {
            int now = prefs.getInt(key, steps[1]), next = steps[0];
            for (int i = 0; i < steps.length; i++) if (steps[i] == now) next = steps[(i + 1) % steps.length];
            prefs.edit().putInt(key, next).apply();
            paintMoney();
            return true;
        });
        return c;
    }

    private void spendSheet(int kind) {
        int[] amounts = kind == 0 ? new int[]{8, 12, 15, 20, 25, 35} : new int[]{20, 30, 50, 80, 120};
        String[] rows = new String[amounts.length];
        for (int i = 0; i < amounts.length; i++) rows[i] = "CHF " + amounts[i];
        sheet(kind == 0 ? "🍔 Auswärts gegessen" : "👕 Kleider gekauft", "Wie viel?", rows, k -> {
            addTo("spend", new String[]{"k", String.valueOf(kind), "chf", String.valueOf(amounts[k])});
            paintMoney();
        });
    }

    private void feelSheet(int kind) {
        sheet(KINDS[kind], "Wie war's?", new String[]{"😊 Gut", "😐 Okay", "😣 Hat runtergezogen"}, k -> {
            addTo("contacts", new String[]{"k", String.valueOf(kind), "f", String.valueOf(k)});
            paintContacts();
            if (kind == 2 && k == 2) toast("Enttäuschung ist kein Beweis gegen dich. Langsam: Gefühle wachsen lassen, nicht springen.");
            else if (k == 2) toast("Notiert. Du darfst Abstand nehmen – das ist kein Versagen.");
        });
    }

    private void paintContacts() {
        contacts.removeAllViews();
        JSONArray a = list("contacts");
        long since = System.currentTimeMillis() - 14L * 24 * 3600_000;
        int[][] n = new int[KINDS.length][3];
        boolean any = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o.optLong("at") < since) continue;
            n[Math.min(KINDS.length - 1, o.optInt("k"))][Math.min(2, o.optInt("f"))]++;
            any = true;
        }
        if (!any) {
            TextView none = text(13, ink3, false);
            none.setText("Noch nichts eingetragen. Oben antippen nach einem Kontakt, einem Song, einem Training.");
            contacts.addView(none);
            return;
        }
        for (int k = 0; k < KINDS.length; k++) {
            int total = n[k][0] + n[k][1] + n[k][2];
            if (total == 0) continue;
            TextView r = mono(12, ink);
            String hint = n[k][2] > n[k][0] ? "  → WENIGER / KÜRZER" : n[k][0] > n[k][2] ? "  → TUT DIR GUT" : "";
            r.setText(KINDS[k] + "  ·  😊" + n[k][0] + "  😐" + n[k][1] + "  😣" + n[k][2] + hint);
            r.setTextColor(n[k][2] > n[k][0] ? rust : n[k][0] > n[k][2] ? green : ink);
            r.setPadding(0, dp(4), 0, dp(4));
            contacts.addView(r);
        }
        TextView foot = mono(10, ink3);
        foot.setText("LETZTE 14 TAGE");
        contacts.addView(foot, margins(dp(4)));
    }

    /* ================= Data ================= */

    private JSONArray list(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void addTo(String key, String[] kv) {
        JSONArray a = list(key);
        try {
            JSONObject o = new JSONObject().put("at", System.currentTimeMillis());
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String v = kv[i + 1];
                if (v.matches("-?\\d+")) o.put(kv[i], Integer.parseInt(v));
                else o.put(kv[i], v);
            }
            a.put(o);
            while (a.length() > 500) a.remove(0);
        } catch (Exception ignored) {
        }
        prefs.edit().putString(key, a.toString()).apply();
    }

    /* ================= Sheets & helpers ================= */

    private interface Pick {
        void on(int i);
    }

    private void sheet(String title, String msg, String[] rows, Pick pick) {
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x99000000);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setBackground(box(card, line, 26));
        box.setClickable(true);
        TextView t = mono(10, rust);
        t.setText("— " + title.toUpperCase(Locale.GERMAN));
        t.setLetterSpacing(0.2f);
        box.addView(t);
        if (msg != null) {
            TextView m = text(16, ink, false);
            m.setText(msg);
            m.setLineSpacing(0, 1.15f);
            box.addView(m, margins(dp(8)));
        }
        for (int i = 0; i < rows.length; i++) {
            TextView r = text(16, i == 0 && rows.length > 1 ? 0xFFFFFFFF : ink, false);
            r.setText(rows[i]);
            r.setPadding(dp(16), dp(14), dp(16), dp(14));
            r.setBackground(i == 0 && rows.length > 1 ? box(rust, rust, 16) : box(bg, line, 16));
            int k = i;
            r.setOnClickListener(v -> {
                root.removeView(scrim);
                pick.on(k);
            });
            box.addView(r, margins(dp(8)));
        }
        int nav = 0;
        if (Build.VERSION.SDK_INT >= 30 && root.getRootWindowInsets() != null)
            nav = root.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()).bottom;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(dp(10), dp(60), dp(10), dp(10) + nav);
        ScrollView ss = new ScrollView(this);
        ss.addView(box);
        scrim.addView(ss, lp);
        scrim.setOnClickListener(v -> root.removeView(scrim));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        box.setTranslationY(dp(300));
        box.animate().translationY(0).setDuration(240).start();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private void hideKeyboard(View v) {
        android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private TextView section(String s) {
        TextView t = mono(11, ink3);
        t.setText("— " + s);
        t.setLetterSpacing(0.2f);
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(ink);
        e.setHintTextColor(ink3);
        e.setTextSize(16);
        e.setBackground(box(card, line, 14));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    private TextView chip(String s, boolean filled) {
        TextView t = mono(12, filled ? 0xFFFFFFFF : ink);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackground(box(filled ? rust : card, filled ? rust : line, 18));
        return t;
    }

    private LinearLayout.LayoutParams weighted(int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = right;
        return lp;
    }

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

    private LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top;
        return p;
    }
}
