package ch.architectdna.home;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import static ch.architectdna.home.ArcUi.t;

/**
 * Einspruch, native: a formal Swiss objection letter in five steps, in the ARC Player look.
 * Talks to the same service as einspruch.architect-dna.ch (same rules, same credits): the free
 * letter by default, Premium (Claude) from a paid pack or the small daily trial. Letters stay
 * on the phone.
 */
public class EinspruchActivity extends Activity {

    static final String ORIGIN = "https://einspruch.architect-dna.ch";
    private static final String[][] RECIPIENTS = {
        {"kk", "🏥", t("Health insurer", "Krankenkasse")}, {"gemeinde", "🏛️", t("Municipality / authority", "Gemeinde / Behörde")}, {"vermieter", "🏠", t("Landlord", "Vermieter")},
        {"arbeitgeber", "💼", t("Employer", "Arbeitgeber")}, {"ahv", "📋", t("AHV / IV / social services", "AHV / IV / Sozialdienst")}, {"bank", "🏦", t("Bank / insurance", "Bank / Versicherung")},
        {"telekom", "📱", t("Telecom / internet", "Telekom / Internet")}, {"busse", "🚗", t("Fine (parking, traffic)", "Ordnungsbusse")}, {"kuendigung", "✂️", t("Cancel a contract", "Vertrag / Abo kündigen")},
        {"versicherung_sach", "🛡️", t("Property insurance", "Sachversicherung")}, {"andere", "📝", t("Other", "Andere")},
    };
    private static final String[][] LANGS = {{"de", "Deutsch"}, {"fr", "Français"}, {"it", "Italiano"}};

    private ArcUi ui;
    private SharedPreferences prefs;
    private ScrollView scroll;
    private String screen = "home";

    // the draft
    private String recipient = "", lang = "fr".equals(Locale.getDefault().getLanguage()) ? "fr" : "it".equals(Locale.getDefault().getLanguage()) ? "it" : "de";
    private String situation = "", goal = "", name = "", address = "";
    private boolean premium, verfuegung;
    private int step;
    private String letter = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        prefs = getSharedPreferences("einspruch", MODE_PRIVATE);
        name = prefs.getString("name", "");
        address = prefs.getString("address", "");
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFitsSystemWindows(true);
        setContentView(scroll);
        home();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCredits();
    }

    @Override
    public void onBackPressed() {
        if ("wizard".equals(screen) && step > 0) { step--; wizard(); }
        else if (!"home".equals(screen)) home();
        else super.onBackPressed();
    }

    private LinearLayout page() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(44));
        scroll.removeAllViews();
        scroll.addView(col);
        scroll.scrollTo(0, 0);
        return col;
    }

    private LinearLayout header(LinearLayout col, String right, View.OnClickListener back) {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = ui.label("Einspruch", true);
        h.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView r = ui.mono(right, ui.ink2, 13);
        r.setPadding(ui.dp(12), ui.dp(6), 0, ui.dp(6));
        r.setOnClickListener(back);
        h.addView(r);
        col.addView(h);
        return h;
    }

    /* ================= home ================= */

    private void home() {
        screen = "home";
        LinearLayout col = page();
        LinearLayout h = header(col, "✕", v -> finish());
        h.getChildAt(0).setOnLongClickListener(v -> { ownerCode(); return true; });

        col.addView(ui.title(t("Push back.\nIn writing.", "Wehr dich.\nSchriftlich."), 44), ui.lp(-1, -2, ui.dp(34)));
        col.addView(ui.body(t("A formal letter under Swiss law to your health insurer, landlord or a public office. With the right legal articles, in 60 seconds.", "Ein formeller Brief nach Schweizer Recht an Krankenkasse, Vermieter oder Behörde. Mit den passenden Gesetzesartikeln, in 60 Sekunden."), ui.ink2, 16), ui.lp(-1, -2, ui.dp(14)));

        col.addView(ui.button(t("✉  Write a letter", "✉  Brief schreiben"), 1, v -> { step = 0; wizard(); }), ui.lp(-1, ui.dp(56), ui.dp(26)));

        TextView credits = ui.mono(creditLine(), ui.ink3, 11);
        credits.setGravity(Gravity.CENTER);
        col.addView(credits, ui.lp(-1, -2, ui.dp(12)));

        col.addView(ui.label(t("How it works", "So geht's"), false), ui.lp(-1, -2, ui.dp(34)));
        String[][] how = {{"1", t("Pick who it goes to", "Empfänger wählen"), t("Insurer, landlord, authority …", "Krankenkasse, Vermieter, Behörde …")}, {"2", t("Say what happened", "Erzählen, was passiert ist"), t("In your own words, short is fine.", "In deinen Worten, kurz reicht.")}, {"3", t("Check it and send it", "Brief prüfen und senden"), t("Copy, share or save as PDF.", "Kopieren, teilen oder als PDF.")}};
        for (String[] s : how) {
            LinearLayout r = new LinearLayout(this);
            r.setPadding(0, ui.dp(12), 0, ui.dp(12));
            TextView n = ui.mono(s[0], ui.rust, 14);
            r.addView(n, new LinearLayout.LayoutParams(ui.dp(30), -2));
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            TextView t = ui.body(s[1], ui.ink, 16);
            t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            c.addView(t);
            c.addView(ui.body(s[2], ui.ink2, 14));
            r.addView(c);
            col.addView(r);
        }

        col.addView(ui.label(t("Tools", "Werkzeuge"), false), ui.lp(-1, -2, ui.dp(28)));
        addRow(col, "⏱️", t("Deadline calculator", "Frist berechnen"), t("How long do I have?", "Wie lange habe ich noch?"), v -> web("/fristenrechner"));
        addRow(col, "🧭", t("Who's responsible?", "Wegweiser"), t("Find the right office", "Wer ist zuständig?"), v -> web("/wegweiser"));
        addRow(col, "📁", t("Evidence folder", "Belegmappe"), t("Collect your proof", "Beweise sammeln"), v -> web("/belegmappe"));
        addRow(col, "⚖️", t("Case review", "Fallprüfung"), t("A person looks at your case", "Ein Mensch schaut deinen Fall an"), v -> web("/fallpruefung"));

        JSONArray saved = saved();
        if (saved.length() > 0) {
            col.addView(ui.label(t("Your letters", "Deine Briefe"), false), ui.lp(-1, -2, ui.dp(28)));
            for (int i = 0; i < Math.min(saved.length(), 20); i++) {
                JSONObject o = saved.optJSONObject(i);
                if (o == null) continue;
                String sub = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(o.optLong("at"))) + " · " + o.optString("to");
                String text = o.optString("letter");
                addRow(col, null, subject(text), sub, v -> { letter = text; result(false); });
            }
        }

        if (premiumLeft() <= 0) {
            col.addView(ui.label("Premium", false), ui.lp(-1, -2, ui.dp(28)));
            col.addView(ui.body(t("Premium writes with Claude: more precise, stronger reasoning. 3 letters a day free, then 10 letters for CHF 4.90, one time, no subscription.", "Premium schreibt mit Claude: genauer, mit stärkerer Begründung. 3 Briefe pro Tag gratis, danach 10 Briefe für CHF 4.90, einmalig, kein Abo."), ui.ink2, 14.5f), ui.lp(-1, -2, ui.dp(8)));
            col.addView(ui.button(t("10 Premium letters · CHF 4.90", "10 Premium-Briefe · CHF 4.90"), 2, v -> buy()), ui.lp(-1, ui.dp(52), ui.dp(12)));
        }

        TextView priv = ui.body(t("Your case goes to the server only to write the letter and is not stored there. Your letters stay on this phone. Not legal advice: for large amounts or deadlines about to run out, also ask an advice centre.", "Dein Fall geht nur zum Schreiben an den Server und wird dort nicht gespeichert. Deine Briefe bleiben auf diesem Handy. Kein Rechtsrat: bei grossen Beträgen oder Fristen kurz vor Ablauf zusätzlich eine Beratungsstelle fragen."), ui.ink3, 12.5f);
        col.addView(priv, ui.lp(-1, -2, ui.dp(30)));
    }

    private void addRow(LinearLayout col, String em, String t, String s, View.OnClickListener l) {
        col.addView(ui.row(em, t, s, l), ui.lp(-1, -2, ui.dp(8)));
    }

    private void web(String path) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ORIGIN + path))); }
        catch (RuntimeException e) { Toast.makeText(this, t("No browser found", "Kein Browser gefunden"), Toast.LENGTH_SHORT).show(); }
    }

    /* ================= wizard ================= */

    private static final String[] STEPS = {"An wen?", "Was ist passiert?", t("What do you want to achieve?", "Was willst du erreichen?"), "Wer bist du?"};

    private void wizard() {
        screen = "wizard";
        LinearLayout col = page();
        header(col, t("← Back", "← Zurück"), v -> onBackPressed());
        col.addView(ui.segments(STEPS.length, step), ui.lp(-1, -2, ui.dp(22)));
        col.addView(ui.mono(t("STEP ", "SCHRITT ") + (step + 1) + " / " + STEPS.length, ui.ink3, 10.5f), ui.lp(-1, -2, ui.dp(14)));
        col.addView(ui.title(STEPS[step], 32), ui.lp(-1, -2, ui.dp(6)));

        switch (step) {
            case 0: {
                ArcUi.Wrap w = new ArcUi.Wrap(this, ui.dp(8));
                for (String[] r : RECIPIENTS) {
                    w.addView(ui.chip(r[1] + "  " + r[2], r[0].equals(recipient), v -> { recipient = r[0]; step = 1; wizard(); }));
                }
                col.addView(w, ui.lp(-1, -2, ui.dp(22)));
                col.addView(ui.label(t("Language of the letter", "Sprache des Briefs"), false), ui.lp(-1, -2, ui.dp(28)));
                ArcUi.Wrap lw = new ArcUi.Wrap(this, ui.dp(8));
                for (String[] l : LANGS) lw.addView(ui.chip(l[1], l[0].equals(lang), v -> { lang = l[0]; wizard(); }));
                col.addView(lw, ui.lp(-1, -2, ui.dp(10)));
                break;
            }
            case 1: {
                col.addView(ui.body(t("What, when, who. Amounts and dates help. Your own words are enough.", "Was, wann, wer. Beträge und Daten helfen. Deine Worte reichen."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(8)));
                EditText e = ui.input(t("e.g. On 3 October my insurer refused to pay for my physiotherapy, even though …", "z.B. Die Krankenkasse hat am 3. Oktober die Rechnung für meine Physiotherapie abgelehnt, obwohl …"), true, 4000);
                e.setText(situation);
                e.setMinLines(8);
                col.addView(e, ui.lp(-1, -2, ui.dp(18)));
                col.addView(next(() -> {
                    situation = e.getText().toString().trim();
                    if (situation.length() < 15) { e.setError(t("One or two more sentences, please.", "Ein, zwei Sätze mehr, bitte.")); return false; }
                    return true;
                }), ui.lp(-1, ui.dp(56), ui.dp(16)));
                e.requestFocus();
                break;
            }
            case 2: {
                col.addView(ui.body(t("What should they do? The more concrete, the stronger.", "Was soll der Empfänger tun? Je konkreter, desto stärker."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(8)));
                EditText e = ui.input(t("e.g. Cover the CHF 420", "z.B. Die Kosten von CHF 420 übernehmen"), true, 1000);
                e.setMinLines(4);
                e.setText(goal);
                col.addView(e, ui.lp(-1, -2, ui.dp(18)));
                boolean canForce = "gemeinde".equals(recipient) || "ahv".equals(recipient) || "andere".equals(recipient);
                if (canForce) {
                    TextView t = toggle(t("Only refused verbally? Demand a formal, appealable decision", "Nur mündlich abgelehnt? Anfechtbare Verfügung verlangen"), verfuegung, v -> { verfuegung = !verfuegung; goal = e.getText().toString(); wizard(); });
                    col.addView(t, ui.lp(-1, -2, ui.dp(12)));
                }
                col.addView(next(() -> {
                    goal = e.getText().toString().trim();
                    if (goal.length() < 4) { e.setError(t("What do you want to achieve?", "Was willst du erreichen?")); return false; }
                    return true;
                }), ui.lp(-1, ui.dp(56), ui.dp(16)));
                break;
            }
            default: {
                col.addView(ui.body(t("Goes at the bottom of the letter. Stays on this phone, the server never sees it.", "Kommt unten in den Brief. Bleibt nur auf diesem Handy, der Server sieht es nie."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(8)));
                EditText n = ui.input(t("First and last name", "Vor- und Nachname"), false, 80);
                n.setText(name);
                col.addView(n, ui.lp(-1, -2, ui.dp(18)));
                EditText ad = ui.input(t("Street no., postcode town", "Strasse Nr., PLZ Ort"), true, 200);
                ad.setMinLines(2);
                ad.setText(address);
                col.addView(ad, ui.lp(-1, -2, ui.dp(10)));
                int left = premiumLeft();
                String pl = left > 0 ? t("Premium (Claude) · ", "Premium (Claude) · noch ") + left + t(" left", " Briefe") : t("Premium (Claude) · 3 a day free", "Premium (Claude) · 3 pro Tag gratis");
                col.addView(toggle(pl, premium, v -> {
                    premium = !premium;
                    name = n.getText().toString().trim();
                    address = ad.getText().toString().trim();
                    wizard();
                }), ui.lp(-1, -2, ui.dp(16)));
                col.addView(ui.button(t("Write the letter", "Brief schreiben"), 1, v -> {
                    name = n.getText().toString().trim();
                    address = ad.getText().toString().trim();
                    prefs.edit().putString("name", name).putString("address", address).apply();
                    generate();
                }), ui.lp(-1, ui.dp(56), ui.dp(18)));
                break;
            }
        }
    }

    private interface Check { boolean ok(); }

    private TextView next(Check c) {
        return ui.button(t("Continue →", "Weiter →"), 0, v -> { if (c.ok()) { step++; wizard(); } });
    }

    private TextView toggle(String s, boolean on, View.OnClickListener l) {
        TextView t = ui.body((on ? "●  " : "○  ") + s, on ? ui.ink : ui.ink2, 15);
        t.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        t.setBackground(ui.box(ui.card, on ? ui.rust : ui.line, 8));
        t.setOnClickListener(l);
        return t;
    }

    /* ================= writing ================= */

    private void generate() {
        screen = "writing";
        LinearLayout col = page();
        header(col, "", null);
        col.addView(ui.title(t("Writing your letter …", "Schreibt deinen Brief …"), 32), ui.lp(-1, -2, ui.dp(60)));
        col.addView(ui.body(premium ? t("Premium with Claude. This takes 20 to 40 seconds.", "Premium mit Claude. Das dauert 20 bis 40 Sekunden.") : t("This takes a few seconds.", "Das dauert ein paar Sekunden."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(10)));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.rust));
        col.addView(pb, ui.lp(-1, ui.dp(6), ui.dp(26)));

        JSONObject body = new JSONObject();
        try {
            body.put("recipient", recipient.isEmpty() ? "andere" : recipient);
            body.put("situation", situation);
            body.put("goal", goal);
            body.put("lang", lang);
            body.put("premium", premium);
            body.put("forceVerfuegung", verfuegung);
            String token = prefs.getString("token", "");
            if (!token.isEmpty()) body.put("token", token);
            String admin = prefs.getString("admin", "");
            if (!admin.isEmpty()) body.put("admin", admin);
        } catch (Exception ignored) { }
        ui.post(ORIGIN, "/api/brief", body, r -> {
            if (r.code == 200 && !r.json.optString("letter").isEmpty()) {
                letter = personalise(r.json.optString("letter"));
                if (r.json.has("remaining")) prefs.edit().putInt("left", r.json.optInt("remaining")).apply();
                save(letter);
                result(true);
            } else if (r.code == 402) {
                paywall();
            } else {
                String msg = r.code == 429 ? t("Too many letters in a short time. Try again in an hour.", "Zu viele Briefe in kurzer Zeit. In einer Stunde wieder.") : r.code == 0 ? t("No connection. Check your internet.", "Keine Verbindung. Internet prüfen.") : t("That didn't work (" + r.code + "). Try again.", "Das hat nicht geklappt (" + r.code + "). Nochmal versuchen.");
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                step = 3;
                wizard();
            }
        });
    }

    private String personalise(String s) {
        if (!name.isEmpty()) s = s.replace("[NAME]", name);
        if (!address.isEmpty()) s = s.replace("[ADRESSE]", address);
        return s.trim();
    }

    private void paywall() {
        screen = "paywall";
        LinearLayout col = page();
        header(col, t("← Back", "← Zurück"), v -> { step = 3; wizard(); });
        col.addView(ui.title(t("Today's free Premium letters are used.", "Die Gratis-Briefe von heute sind weg."), 32), ui.lp(-1, -2, ui.dp(40)));
        col.addView(ui.body(t("10 Premium letters for CHF 4.90, one time, no subscription. Or continue without Premium: the standard letter stays free.", "10 Premium-Briefe für CHF 4.90, einmalig, kein Abo. Oder ohne Premium weiterschreiben: der normale Brief bleibt gratis."), ui.ink2, 16), ui.lp(-1, -2, ui.dp(12)));
        col.addView(ui.button(t("10 letters · CHF 4.90", "10 Briefe · CHF 4.90"), 1, v -> buy()), ui.lp(-1, ui.dp(56), ui.dp(26)));
        col.addView(ui.button(t("Write without Premium", "Ohne Premium schreiben"), 2, v -> { premium = false; generate(); }), ui.lp(-1, ui.dp(52), ui.dp(10)));
    }

    /* ================= result ================= */

    private void result(boolean fresh) {
        screen = "result";
        LinearLayout col = page();
        header(col, t("Done", "Fertig"), v -> home());
        col.addView(ui.title(fresh ? t("Your letter.", "Dein Brief.") : subject(letter), 34), ui.lp(-1, -2, ui.dp(26)));
        if (fresh) col.addView(ui.body(t("Read it, fill in anything missing, then sign it and send it by registered mail.", "Lies ihn durch, ergänze was fehlt, dann unterschreiben und eingeschrieben senden."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(8)));

        TextView paper = new TextView(this);
        paper.setText(letter);
        paper.setTextIsSelectable(true);
        paper.setTextColor(0xFF1A1A1A);
        paper.setTextSize(14.5f);
        paper.setLineSpacing(0, 1.3f);
        paper.setTypeface(Typeface.SERIF);
        paper.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(22));
        paper.setBackground(ui.box(0xFFFFFFFF, ui.line, 8));
        col.addView(paper, ui.lp(-1, -2, ui.dp(20)));

        LinearLayout acts = new LinearLayout(this);
        acts.addView(ui.button(t("Copy", "Kopieren"), 0, v -> copy()), new LinearLayout.LayoutParams(0, ui.dp(52), 1f));
        View g = new View(this);
        acts.addView(g, new LinearLayout.LayoutParams(ui.dp(8), 1));
        acts.addView(ui.button(t("Share", "Teilen"), 2, v -> share()), new LinearLayout.LayoutParams(0, ui.dp(52), 1f));
        col.addView(acts, ui.lp(-1, -2, ui.dp(16)));
        col.addView(ui.button(t("Save as PDF / print", "Als PDF speichern / drucken"), 2, v -> print()), ui.lp(-1, ui.dp(52), ui.dp(8)));
        if (fresh) col.addView(ui.button(t("New letter", "Neuen Brief"), 2, v -> { step = 0; situation = ""; goal = ""; verfuegung = false; wizard(); }), ui.lp(-1, ui.dp(52), ui.dp(8)));
        TextView tip = ui.body(t("Tip: send it registered and keep a copy. Deadlines run from when the decision was delivered.", "Tipp: Eingeschrieben senden und eine Kopie behalten. Fristen laufen ab Zustellung des Entscheids."), ui.ink3, 12.5f);
        col.addView(tip, ui.lp(-1, -2, ui.dp(22)));
    }

    private static String subject(String l) {
        for (String line : l.split("\n")) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("betreff") || t.toLowerCase(Locale.ROOT).startsWith("objet") || t.toLowerCase(Locale.ROOT).startsWith("oggetto")) {
                int c = t.indexOf(':');
                return c > 0 ? t.substring(c + 1).trim() : t;
            }
        }
        return t("Letter", "Brief");
    }

    private void copy() {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Brief", letter));
        Toast.makeText(this, t("Copied", "Kopiert"), Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, letter)
            .putExtra(Intent.EXTRA_SUBJECT, subject(letter));
        startActivity(Intent.createChooser(i, t("Share letter", "Brief teilen")));
    }

    private WebView printView;

    private void print() {
        printView = new WebView(this);
        String html = "<html><head><meta charset='utf-8'><style>@page{margin:22mm 20mm}body{font-family:serif;font-size:11.5pt;line-height:1.5;color:#111;white-space:pre-wrap}</style></head><body>"
            + Html.escapeHtml(letter) + "</body></html>";
        printView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                pm.print("Einspruch – " + subject(letter), v.createPrintDocumentAdapter("Einspruch"),
                    new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
            }
        });
        printView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    /* ================= storage, credits, payment ================= */

    private JSONArray saved() {
        try { return new JSONArray(prefs.getString("letters", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void save(String l) {
        JSONArray old = saved(), out = new JSONArray();
        try {
            String to = t("Letter", "Brief");
            for (String[] r : RECIPIENTS) if (r[0].equals(recipient)) to = r[2];
            out.put(new JSONObject().put("at", System.currentTimeMillis()).put("to", to).put("letter", l));
            for (int i = 0; i < Math.min(old.length(), 29); i++) out.put(old.get(i));
        } catch (Exception ignored) { }
        prefs.edit().putString("letters", out.toString()).apply();
    }

    private int premiumLeft() {
        return prefs.getString("token", "").isEmpty() ? 0 : prefs.getInt("left", 0);
    }

    private String creditLine() {
        int left = premiumLeft();
        return left > 0 ? t("PREMIUM · " + left + " LETTERS LEFT", "PREMIUM · NOCH " + left + " BRIEFE") : t("FREE · PREMIUM 3× A DAY", "GRATIS · PREMIUM 3× PRO TAG");
    }

    private void buy() {
        ui.post(ORIGIN, "/api/checkout", new JSONObject(), r -> {
            String url = r.json.optString("url");
            String sid = ArcUi.sessionIdFrom(url);
            if (url.isEmpty()) { Toast.makeText(this, t("Payment isn't available right now.", "Zahlung gerade nicht möglich."), Toast.LENGTH_LONG).show(); return; }
            if (sid != null) prefs.edit().putString("pending", sid).putLong("pendingAt", System.currentTimeMillis()).apply();
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException ignored) { }
        });
    }

    /** After paying in the browser: the purchase becomes the token. Also refreshes what's left. */
    private void refreshCredits() {
        String pending = prefs.getString("pending", "");
        if (!pending.isEmpty() && System.currentTimeMillis() - prefs.getLong("pendingAt", 0) > 86_400_000L) {
            prefs.edit().remove("pending").apply();
            pending = "";
        }
        String sid = !pending.isEmpty() ? pending : prefs.getString("token", "");
        if (sid.isEmpty()) return;
        boolean wasPending = !pending.isEmpty();
        JSONObject b = new JSONObject();
        try { b.put("session_id", sid); } catch (Exception ignored) { }
        ui.post(ORIGIN, "/api/webhook", b, r -> {
            if (r.code == 200 && r.json.optInt("granted") > 0) {
                prefs.edit().putString("token", sid).putInt("left", r.json.optInt("granted")).remove("pending").apply();
                if (wasPending) Toast.makeText(this, t("Thanks! " + r.json.optInt("granted") + " Premium letters ready.", "Danke! " + r.json.optInt("granted") + " Premium-Briefe bereit."), Toast.LENGTH_LONG).show();
                if ("home".equals(screen)) home();
            } else if (r.code == 402 && !wasPending) {
                prefs.edit().putInt("left", 0).apply();
                if ("home".equals(screen)) home();
            }
        });
    }

    /** Long-press the label: the owner's premium code. */
    private void ownerCode() {
        EditText e = ui.input("Code", false, 80);
        e.setText(prefs.getString("admin", ""));
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout w = new LinearLayout(this);
        w.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        w.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("Owner code", "Inhaber-Code"))
            .setView(w)
            .setPositiveButton(t("Save", "Speichern"), (d, i) -> prefs.edit().putString("admin", e.getText().toString().trim()).apply())
            .setNegativeButton(t("Cancel", "Abbrechen"), null)
            .show();
    }
}
