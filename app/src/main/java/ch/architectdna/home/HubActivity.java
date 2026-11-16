package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static ch.architectdna.home.ArcUi.t;

/**
 * ARC-DNA, the hub. The old home screen (clock + duck) is retired: Statistik / Orbit are the home
 * now. Opening ARC-DNA shows every ARC tool in one calm list. Pull down from the top, or tap the
 * update line, to look for updates; ARC-DNA also keeps Statistik, Orbit and the Browser current.
 */
public class HubActivity extends Activity {

    private ArcUi ui;
    private SharedPreferences prefs;
    private Updater updater;
    private FrameLayout root;
    private ScrollView scroll;
    private TextView updateLine;
    private UpdateView updateView;
    private float downY = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DiagLog.init(this);
        ui = new ArcUi(this);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(ui.bg);
        root.setFitsSystemWindows(true);
        scroll = new ScrollView(this);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        updater = new Updater(this, prefs, this::onUpdateState);
        updater.progress = this::onProgress;
        int fresh = updater.justUpdatedTo();
        if (fresh > 0) root.postDelayed(() -> onProgress(4, 1, 1, fresh), 700);   // ✓ Updated to 1.0.N
        build();
        // Pull down from the top: look for updates now.
        scroll.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) downY = scroll.getScrollY() == 0 ? e.getY() : -1;
            else if (e.getActionMasked() == MotionEvent.ACTION_UP && downY >= 0 && e.getY() - downY > ui.dp(110)) {
                downY = -1;
                updateLine.setText(t("↻ Checking for updates …", "↻ Suche Updates …"));
                updater.check(true);
            }
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updater.check(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Updater.ACTION_STATUS.equals(intent.getAction())) updater.onStatus(intent);
    }

    private void build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(44));
        scroll.addView(col);

        col.addView(ui.label("ARC·DNA", true));
        col.addView(ui.title(t("Everything,\none tap away.", "Alles,\nein Tipp entfernt."), 40), ui.lp(-1, -2, ui.dp(18)));
        updateLine = ui.mono("", ui.ink3, 11);
        updateLine.setPadding(0, ui.dp(10), 0, ui.dp(4));
        updateLine.setOnClickListener(v -> { updater.check(true); });
        updateLine.setOnLongClickListener(v -> { askToken(); return true; });
        col.addView(updateLine, ui.lp(-1, -2, ui.dp(10)));
        TextView pull = ui.mono(t("PULL DOWN = CHECK FOR UPDATES", "RUNTERZIEHEN = UPDATES SUCHEN"), ui.ink3, 9.5f);
        col.addView(pull);

        section(col, t("Day", "Tag"), new Object[][]{
            {"🐻", t("Bear alarm", "Bär-Wecker"), t("Wake up, appointments", "Aufwachen, Termine"), WeckerActivity.class},
            {"🌊", "Takt", t("Your day around your body", "Dein Tag um deinen Körper"), TaktActivity.class},
            {"📅", "Flexplan", t("Small steps, asks \"now?\"", "Kleine Schritte, fragt „jetzt?“"), PlanActivity.class},
            {"🙂", t("Mood", "Stimmung"), t("How are you right now?", "Wie geht's dir gerade?"), MoodActivity.class},
        });
        section(col, t("Music", "Musik"), new Object[][]{
            {"💾", "ARC Player", t("Your face for whatever plays", "Dein Gesicht für alles, was läuft"), PlayerActivity.class},
            {"🔊", "ARC Remote", t("Steer the tablet's music", "Musik auf dem Tablet steuern"), RemoteActivity.class},
            {"📜", t("Music history", "Musik-Verlauf"), t("Everything you played", "Alles, was du gehört hast"), HistoryActivity.class},
            {"🎧", "VOID FREQ", t("Focus sounds", "Fokus-Klänge"), FreqActivity.class},
        });
        section(col, t("School", "Schule"), new Object[][]{
            {"∑", "ARC Math", t("Solve it in a second, with steps", "In einer Sekunde gelöst, mit Schritten"), MathActivity.class},
            {"📚", t("Books", "Bücher"), t("E-books and codes", "E-Books und Codes"), BuecherActivity.class},
        });
        section(col, t("Help", "Hilfe"), new Object[][]{
            {"⚖️", "Einspruch", t("Formal letter under Swiss law", "Formeller Brief nach Schweizer Recht"), EinspruchActivity.class},
            {"⚓", "Anchor", t("A companion who knows your situation", "Begleiter, der deine Lage kennt"), AnchorActivity.class},
            {"🧭", t("Compass", "Kompass"), t("For hard days", "Für schwere Tage"), KompassActivity.class},
            {"🌿", t("Calm", "Gelassen"), t("One minute of breathing", "Eine Minute atmen"), CalmActivity.class},
            {"🆘", t("Calm island", "Ruheinsel"), t("When it's too much", "Wenn's zu viel wird"), CrisisActivity.class},
            {"🎯", t("Focus", "Fokus"), t("Only 3 apps left", "Nur noch 3 Apps"), FokusActivity.class},
        });
        section(col, t("Tools", "Werkzeuge"), new Object[][]{
            {"🧰", t("AI tools", "KI-Werkzeuge"), t("Study buddy, Drive, Bear, Wellness, Health …", "Lernbegleiter, Antrieb, Bär, Wellness, Health …"), WerkzeugeActivity.class},
            {"🚆", t("Routes", "Routen"), t("Swiss public transport", "ÖV Schweiz"), RouteActivity.class},
            {"🔎", t("Fact check", "Faktencheck"), t("Spot the tricks", "Tricks erkennen"), FaktenActivity.class},
            {"📷", "ARC Camera", t("Composition coach", "Bild-Coach"), CameraActivity.class},
            {"🎙", t("Recorder", "Rekorder"), t("Voice recordings", "Sprachaufnahmen"), RecorderActivity.class},
            {"🔉", t("Decibels", "Dezibel"), t("How loud is it?", "Wie laut ist das?"), DecibelActivity.class},
            {"☀️", t("Sun & world", "Sonne & Welt"), t("Light, sleep, seasons", "Licht, Schlaf, Jahreszeiten"), SunActivity.class},
        });
        section(col, t("Phone", "Handy"), new Object[][]{
            {"🔋", t("Energy", "Energie"), t("Battery and what drains it", "Akku und was ihn zieht"), EnergyActivity.class},
            {"🛡", "VPN", t("How the internet sees you", "So sieht dich das Internet"), VpnActivity.class},
            {"🖼", t("Gallery", "Galerie"), t("Photos on a globe", "Fotos auf einem Globus"), GalleryActivity.class},
        });
        section(col, t("Play", "Spielen"), new Object[][]{
            {"🎯", t("Games", "Spielothek"), t("Darts, rocket, table tennis", "Darts, Rakete, Tischtennis"), SpielothekActivity.class},
            {"👾", "Nexus Breach", t("Waves, upgrades, bosses", "Wellen, Upgrades, Bosse"), NexusActivity.class},
            {"✏️", "Whiteboard", t("Draw, learn to draw", "Zeichnen, zeichnen lernen"), BoardActivity.class},
        });
    }

    /** Hold the update line: paste the read-only GitHub token (private repo). Stays encrypted on this device. */
    private void askToken() {
        android.widget.EditText in = ui.input("github_pat_…", false, 200);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout w = new LinearLayout(this);
        w.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        w.addView(in, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("Set up updates", "Updates einrichten"))
            .setMessage(t("GitHub → fine-grained token, only \"arc-dna-home\", Contents: read-only. Paste once, then ARC-DNA updates itself.",
                "GitHub → Fine-grained token, nur „arc-dna-home“, Contents: Read-only. Einmal einfügen, dann aktualisiert sich ARC-DNA selbst."))
            .setView(w)
            .setPositiveButton(t("Save & check", "Speichern & prüfen"), (d, i) -> { updater.setToken(in.getText().toString()); updater.check(true); })
            .setNegativeButton(t("Create token", "Token erstellen"), (d, i) -> Web.open(this, "https://github.com/settings/personal-access-tokens/new"))
            .show();
    }

    private void section(LinearLayout col, String name, Object[][] rows) {
        col.addView(ui.label(name, false), ui.lp(-1, -2, ui.dp(30)));
        for (Object[] r : rows) {
            Class<?> cls = (Class<?>) r[3];
            col.addView(ui.row((String) r[0], (String) r[1], (String) r[2], v -> {
                try { startActivity(new Intent(this, cls)); } catch (RuntimeException ignored) { }
            }), ui.lp(-1, -2, ui.dp(8)));
        }
    }

    private void onUpdateState(String text, boolean available) {
        updateLine.setText(text.toUpperCase(java.util.Locale.ROOT));
        updateLine.setTextColor(available ? ui.rust : ui.ink3);
        if (available && prefs.getBoolean("autoUpdate", true)) updater.install();
    }

    /** The download helix, same as before, at the top of the hub. */
    private void onProgress(int phase, long done, long total, int version) {
        if (updateView == null || updateView.getParent() != root) {
            updateView = new UpdateView(this);
            FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(-1, ui.dp((int) UpdateView.heightDp()), Gravity.TOP);
            l.leftMargin = ui.dp(12);
            l.rightMargin = ui.dp(12);
            l.topMargin = ui.dp(12);
            root.addView(updateView, l);
        }
        updateView.update(phase, done, total, version);
        if (updateView.isFinished()) {
            UpdateView v = updateView;
            updateView = null;
            v.postDelayed(() -> v.animate().alpha(0f).setDuration(280).withEndAction(() -> root.removeView(v)).start(), phase == -1 ? 2600 : phase == 4 ? 3200 : 1600);
        }
    }
}
