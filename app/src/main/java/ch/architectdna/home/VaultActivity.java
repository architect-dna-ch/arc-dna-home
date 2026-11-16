package ch.architectdna.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

import static ch.architectdna.home.ArcUi.t;

/**
 * ARC Passwords. Two faces:
 *  - PICK (from the 🔑 key above the keyboard): a card with the logins for this app / site; tap one
 *    and it's filled in. Fingerprint or screen lock first (then 3 minutes without asking again).
 *  - Manage (from the hub): add, edit, delete, import Google's CSV export, the 🔑 key on/off.
 */
public class VaultActivity extends Activity {

    static final String PICK = "ch.architectdna.home.VAULT_PICK";

    private ArcUi ui;
    private boolean pick;
    private String pkg = "", host = "";
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        pick = PICK.equals(getIntent().getAction());
        pkg = getIntent().getStringExtra("pkg") == null ? "" : getIntent().getStringExtra("pkg");
        host = getIntent().getStringExtra("host") == null ? "" : getIntent().getStringExtra("host");
        if (Vault.unlocked()) show();
        else unlock();
    }

    /* ================= unlock ================= */

    private void unlock() {
        if (Build.VERSION.SDK_INT >= 28) {
            BiometricPrompt.Builder bb = new BiometricPrompt.Builder(this)
                .setTitle(t("ARC Passwords", "ARC Passwörter"))
                .setSubtitle(pick ? (host.isEmpty() ? pkg : host) : t("Unlock", "Entsperren"));
            if (Build.VERSION.SDK_INT >= 30) bb.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            else bb.setDeviceCredentialAllowed(true);
            try {
                bb.build().authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { Vault.markUnlocked(); show(); }
                    @Override public void onAuthenticationError(int code, CharSequence msg) { finish(); }
                });
                return;
            } catch (RuntimeException ignored) { }
        }
        KeyguardManager km = getSystemService(KeyguardManager.class);
        Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent(t("ARC Passwords", "ARC Passwörter"), null);
        if (i == null) { Vault.markUnlocked(); show(); return; }       // no screen lock set up at all
        startActivityForResult(i, 90);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 90) {
            if (res == RESULT_OK) { Vault.markUnlocked(); show(); } else finish();
        } else if (req == 91 && res == RESULT_OK && data != null && data.getData() != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(data.getData())))) {
                StringBuilder sb = new StringBuilder();
                for (String l; (l = r.readLine()) != null; ) sb.append(l).append('\n');
                int n = Vault.importCsv(this, sb.toString());
                Toast.makeText(this, n + t(" logins imported. Delete the CSV file now.", " Logins importiert. Lösch die CSV-Datei jetzt."), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, t("Couldn't read the file.", "Datei liess sich nicht lesen."), Toast.LENGTH_LONG).show();
            }
            show();
        }
    }

    private void show() {
        if (pick) showPick();
        else showManage();
    }

    /* ================= pick ================= */

    private void showPick() {
        List<Vault.Entry> all = Vault.load(this), hits = Vault.match(all, pkg, host);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);
        root.setOnClickListener(v -> finish());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        card.setBackground(ui.box(ui.card, ui.line, 14));
        int w = Math.min(ui.dp(520), (int) (getResources().getDisplayMetrics().widthPixels * 0.92f));
        root.addView(card, new FrameLayout.LayoutParams(w, -2, Gravity.CENTER));
        card.addView(ui.label(t("Fill login", "Login einfügen") + (host.isEmpty() ? "" : " · " + host), true));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(rows);
        card.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        List<Vault.Entry> list = hits.isEmpty() ? all : hits;
        if (hits.isEmpty() && !all.isEmpty())
            rows.addView(ui.body(t("Nothing saved for this one yet, here are all:", "Für hier noch nichts gespeichert, hier alle:"), ui.ink3, 13), ui.lp(-1, -2, ui.dp(10)));
        for (Vault.Entry e : list) {
            rows.addView(ui.row("🔑", e.title.isEmpty() ? e.where : e.title, e.user, v -> fillAndClose(e)), ui.lp(-1, -2, ui.dp(8)));
        }
        if (all.isEmpty()) rows.addView(ui.body(t("No logins saved yet.", "Noch keine Logins gespeichert."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(12)));
        LinearLayout acts = new LinearLayout(this);
        acts.addView(ui.button(t("＋ New for here", "＋ Neu für hier"), 2, v -> edit(null, host.isEmpty() ? pkg : host)), new LinearLayout.LayoutParams(0, ui.dp(48), 1f));
        View g = new View(this);
        acts.addView(g, new LinearLayout.LayoutParams(ui.dp(8), 1));
        acts.addView(ui.button(t("Close", "Schliessen"), 2, v -> finish()), new LinearLayout.LayoutParams(0, ui.dp(48), 1f));
        card.addView(acts, ui.lp(-1, -2, ui.dp(14)));
        setContentView(root);
    }

    private void fillAndClose(Vault.Entry e) {
        finish();
        overridePendingTransition(0, 0);
        // Once the app is back in front, put username and password into its fields.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ScrollAssistService s = ScrollAssistService.instance;
            boolean ok = s != null && PassKey.fill(s, e);
            if (!ok) {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("password", e.pass));
                Toast.makeText(getApplicationContext(), t("Couldn't fill here; password copied (paste it).", "Hier nicht einfügbar; Passwort kopiert (einfügen)."), Toast.LENGTH_LONG).show();
            }
        }, 450);
    }

    /* ================= manage ================= */

    private void showManage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.bg);
        sv.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(44));
        sv.addView(col);
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(ui.label(t("ARC Passwords", "ARC Passwörter"), true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = ui.mono("✕", ui.ink2, 13);
        x.setOnClickListener(v -> finish());
        h.addView(x);
        col.addView(h);
        col.addView(ui.title(t("Your logins.\nOnly here.", "Deine Logins.\nNur hier."), 38), ui.lp(-1, -2, ui.dp(20)));
        col.addView(ui.body(t("Encrypted on this device, unlocked with your fingerprint. When a login field opens, a 🔑 key appears above the keyboard. ✕ hides it, hold ✕ = never in that app.",
            "Verschlüsselt auf diesem Gerät, entsperrt mit deinem Fingerabdruck. Öffnet sich ein Login-Feld, erscheint über der Tastatur ein 🔑. ✕ blendet es aus, ✕ halten = in dieser App nie mehr."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(10)));

        boolean on = Vault.enabled(this);
        TextView toggle = ui.body((on ? "●  " : "○  ") + t("🔑 key above the keyboard", "🔑 über der Tastatur"), on ? ui.ink : ui.ink2, 15);
        toggle.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        toggle.setBackground(ui.box(ui.card, on ? ui.rust : ui.line, 8));
        toggle.setOnClickListener(v -> { Vault.setEnabled(this, !Vault.enabled(this)); showManage(); });
        col.addView(toggle, ui.lp(-1, -2, ui.dp(18)));
        if (ScrollAssistService.instance == null)
            col.addView(ui.body(t("Needs Settings → Accessibility → ARC-DNA Scroll → on.", "Braucht Einstellungen → Bedienungshilfen → ARC-DNA Scroll → an."), ui.rust, 13.5f), ui.lp(-1, -2, ui.dp(8)));

        LinearLayout acts = new LinearLayout(this);
        acts.addView(ui.button(t("＋ Add", "＋ Hinzufügen"), 1, v -> edit(null, "")), new LinearLayout.LayoutParams(0, ui.dp(52), 1f));
        View g = new View(this);
        acts.addView(g, new LinearLayout.LayoutParams(ui.dp(8), 1));
        acts.addView(ui.button(t("Import CSV", "CSV importieren"), 2, v -> {
            try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*"), 91); } catch (RuntimeException ignored) { }
        }), new LinearLayout.LayoutParams(0, ui.dp(52), 1f));
        col.addView(acts, ui.lp(-1, -2, ui.dp(18)));
        col.addView(ui.body(t("Import: passwords.google.com → ⚙ → Export, then pick the file here.", "Import: passwords.google.com → ⚙ → Exportieren, dann die Datei hier wählen."), ui.ink3, 12.5f), ui.lp(-1, -2, ui.dp(8)));

        List<Vault.Entry> all = Vault.load(this);
        col.addView(ui.label(all.size() + t(" logins", " Logins"), false), ui.lp(-1, -2, ui.dp(26)));
        for (Vault.Entry e : all)
            col.addView(ui.row("🔑", e.title.isEmpty() ? e.where : e.title, e.user + "  ·  " + e.where, v -> edit(e, "")), ui.lp(-1, -2, ui.dp(8)));
        setContentView(sv);
    }

    private void edit(Vault.Entry existing, String whereHint) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        EditText title = ui.input(t("Name, e.g. Instagram", "Name, z.B. Instagram"), false, 80);
        EditText where = ui.input(t("Website or app, e.g. instagram.com", "Website oder App, z.B. instagram.com"), false, 200);
        EditText user = ui.input(t("Username / email", "Benutzername / E-Mail"), false, 200);
        EditText pass = ui.input(t("Password", "Passwort"), false, 200);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pass.setTypeface(Typeface.MONOSPACE);
        if (existing != null) { title.setText(existing.title); where.setText(existing.where); user.setText(existing.user); pass.setText(existing.pass); }
        else where.setText(whereHint);
        for (EditText e : new EditText[]{title, where, user, pass}) f.addView(e, ui.lp(-1, -2, ui.dp(8)));
        AlertDialog.Builder d = new AlertDialog.Builder(this)
            .setTitle(existing == null ? t("New login", "Neues Login") : t("Edit login", "Login bearbeiten"))
            .setView(f)
            .setPositiveButton(t("Save", "Speichern"), (dd, i) -> {
                List<Vault.Entry> all = Vault.load(this);
                Vault.Entry e = new Vault.Entry();
                e.title = title.getText().toString().trim();
                e.where = where.getText().toString().trim();
                e.user = user.getText().toString().trim();
                e.pass = pass.getText().toString();
                if (existing != null) remove(all, existing);
                all.add(0, e);
                Vault.save(this, all);
                show();
            })
            .setNegativeButton(t("Cancel", "Abbrechen"), null);
        if (existing != null) d.setNeutralButton(t("Delete", "Löschen"), (dd, i) -> {
            List<Vault.Entry> all = Vault.load(this);
            remove(all, existing);
            Vault.save(this, all);
            show();
        });
        d.show();
    }

    private static void remove(List<Vault.Entry> all, Vault.Entry x) {
        for (int i = 0; i < all.size(); i++) {
            Vault.Entry e = all.get(i);
            if (e.title.equals(x.title) && e.where.equals(x.where) && e.user.equals(x.user)) { all.remove(i); return; }
        }
    }
}
