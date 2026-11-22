package ch.architectdna.home;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * ARC Lifeboat — "if the phone goes black, the tablet takes over."
 *
 * The honest split: ARC can't administer your Google account, and the thing that actually locks you out
 * is account access + 2FA, not local files. So this screen is mostly a guided checklist with deep-links
 * to the exact settings you must set on the phone NOW (and on the tablet), with your progress saved.
 * The part ARC owns — your encrypted Treasure vault — it backs up to the tablet with one tap.
 */
public class ContinuityActivity extends Activity {

    private LinearLayout col;
    private TextView tabletLine, vaultLine;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0A0F14);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        col.setPadding(pad, dp(26), pad, dp(36));
        sv.addView(col);
        setContentView(sv);
        getWindow().setStatusBarColor(0xFF0A0F14);

        TextView eye = text(12, 0xFF5BD6B0, true); eye.setText("— ARC · LIFEBOAT"); eye.setLetterSpacing(0.22f);
        col.addView(eye);
        TextView h = text(30, 0xFFEAF4F0, true); h.setText(L.t("Wenn das Handy ausfällt.")); h.setPadding(0, dp(6), 0, dp(4));
        col.addView(h);
        TextView sub = text(14, 0xFF93AEA8, false);
        sub.setText(L.t("Das Risiko ist nicht der Speicher – es ist der Zugang. Richte diese Dinge jetzt auf dem Handy (und auf dem Tablet) ein, solange es noch geht. ARC kann dein Google-Konto nicht verwalten, aber es führt dich durch und sichert deinen Tresor aufs Tablet."));
        col.addView(sub);

        // Tablet status card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(card());
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, -2); scl.topMargin = dp(20);
        col.addView(statusCard, scl);
        TextView st = text(11, 0xFF6E8A84, true); st.setText(L.t("DEIN RETTUNGSBOOT: DAS TABLET")); st.setLetterSpacing(0.14f);
        statusCard.addView(st);
        tabletLine = text(14, 0xFFEAF4F0, false); tabletLine.setPadding(0, dp(8), 0, 0); statusCard.addView(tabletLine);
        vaultLine = text(13, 0xFF93AEA8, false); vaultLine.setPadding(0, dp(4), 0, dp(4)); statusCard.addView(vaultLine);
        TextView backup = new TextView(this);
        backup.setText(L.t("🛟  Tresor jetzt aufs Tablet sichern"));
        backup.setTextColor(0xFF07140F); backup.setTextSize(15);
        backup.setTypeface(backup.getTypeface(), android.graphics.Typeface.BOLD);
        backup.setGravity(Gravity.CENTER); backup.setBackground(pill(0xFF5BD6B0));
        backup.setPadding(0, dp(13), 0, dp(13));
        backup.setOnClickListener(v -> backupVault());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = dp(10);
        statusCard.addView(backup, bl);

        // Checklist
        tier(L.t("JETZT — SONST AUSGESPERRT"), 0xFFE5534B);
        item("tablet_account", "📲", L.t("Tablet bei deinem Google-Konto anmelden"),
            L.t("Dann hält das Tablet das Konto selbst — unabhängig vom Handy."),
            L.t("Konten öffnen"), () -> openAddAccount());
        item("twostep", "🔐", L.t("2FA / Authenticator aufs Tablet"),
            L.t("Google-Authenticator-Cloud-Sync an, oder Tablet als zweiten Authenticator — für jede App."),
            L.t("2-Schritt öffnen"), () -> url("https://myaccount.google.com/signinoptions/two-step-verification"));
        item("passkey", "🗝", L.t("Passkey auf dem Tablet anlegen"),
            L.t("So kann das Tablet Anmeldungen bestätigen."),
            L.t("Passkeys öffnen"), () -> url("https://myaccount.google.com/signinoptions/passkeys"));
        item("codes", "🧩", L.t("Backup-Codes erzeugen & in den Tresor"),
            L.t("Letzte Rettung, wenn alles andere weg ist. In ARC Tresor sichern."),
            L.t("Codes öffnen"), () -> url("https://myaccount.google.com/signinoptions/two-step-verification"));
        item("recovery", "📮", L.t("Wiederherstellung: E-Mail + Nummer setzen"),
            L.t("Am besten nicht nur die Nummer des defekten Handys."),
            L.t("Sicherheit öffnen"), () -> url("https://myaccount.google.com/security"));
        item("sim", "📶", L.t("SIM / eSIM beweglich halten"),
            L.t("Eine Nummer, die im toten Handy steckt, ist der häufigste Aussperr-Grund. SIM umstecken können."),
            null, null);

        tier(L.t("LOKALE DATEN SICHERN"), 0xFFE08A2B);
        item("photos", "🖼", L.t("Google Fotos Backup an"),
            L.t("Fotos & Videos landen in der Cloud, auf dem Tablet sichtbar."),
            L.t("Fotos öffnen"), () -> openApp("com.google.android.apps.photos"));
        item("onebackup", "☁", L.t("Google One Geräte-Backup an"),
            L.t("Kontakte, SMS, App-Daten → wiederherstellbar auf dem Tablet."),
            L.t("Einstellungen"), () -> settings(Settings.ACTION_SETTINGS));
        item("whatsapp", "💬", L.t("WhatsApp → Backup auf Google Drive"),
            L.t("Nötig, damit Chats + deine Sticker auf einem neuen Gerät zurückkommen."),
            L.t("WhatsApp öffnen"), () -> openApp("com.whatsapp"));
        item("contacts", "👥", L.t("Kontakte mit Google synchronisieren"),
            L.t("Nicht nur auf SIM/Handy — sonst weg mit dem Gerät."),
            L.t("Konten öffnen"), () -> settings(Settings.ACTION_SYNC_SETTINGS));

        tier(L.t("BLINDE FLECKEN"), 0xFFD9B23A);
        item("email2fa", "✉", L.t("2FA fürs E-Mail-Konto selbst"),
            L.t("Fällt die E-Mail, fällt alles. Separat absichern."),
            null, null);
        item("banking", "🏦", L.t("Banking / TWINT / SwissID Reaktivierung prüfen"),
            L.t("Diese Apps sind oft gerätegebunden. Jetzt prüfen, wie du sie neu aktivierst — bevor das Handy weg ist."),
            null, null);
        item("breakglass", "🧯", L.t("Notfall-Notiz in den Tresor"),
            L.t("Liste der wichtigen Konten + wie du jedes wiederherstellst. In ARC Tresor."),
            L.t("Tresor öffnen"), () -> { try { startActivity(new Intent(this, TreasureActivity.class)); } catch (RuntimeException ignored) { } });

        TextView note = text(12, 0xFF6C847E, false);
        note.setText(L.t("ARC kann die Konten-Haken nicht prüfen — hak selbst ab, was erledigt ist. Deine Haken bleiben nur auf diesem Gerät."));
        note.setPadding(0, dp(24), 0, 0);
        col.addView(note);

        refreshStatus();
    }

    /* ---------- tablet + vault status ---------- */
    private void refreshStatus() {
        boolean paired = false;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) for (BluetoothDevice d : a.getBondedDevices()) {
                int major = d.getBluetoothClass() == null ? 0 : d.getBluetoothClass().getMajorDeviceClass();
                if (major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.COMPUTER) { paired = true; break; }
            }
        } catch (SecurityException ignored) { }
        tabletLine.setText(paired ? L.t("✓ Ein ARC-Gerät ist gekoppelt.") : L.t("⚠ Kein ARC-Gerät gekoppelt — erst in Bluetooth koppeln."));
        tabletLine.setTextColor(paired ? 0xFFEAF4F0 : 0xFFE5A24B);
        long last = TreasureSync.lastPush;
        vaultLine.setText(last == 0 ? L.t("Tresor-Backup noch nie angestossen.")
            : L.t("Tresor-Backup zuletzt angestossen: ") + android.text.format.DateUtils.getRelativeTimeSpanString(last));
    }

    private void backupVault() {
        try { TreasureSync.push(this); } catch (RuntimeException ignored) { }
        // push() is fire-and-forget over Bluetooth — it can't confirm here, so don't claim success.
        Toast.makeText(this, L.t("Tresor-Backup ans Tablet gestartet. ARC muss auf dem Tablet offen und in Reichweite sein."), Toast.LENGTH_LONG).show();
        col.postDelayed(this::refreshStatus, 1500);
    }

    /* ---------- checklist rows ---------- */
    private void tier(String label, int color) {
        TextView t = text(11, color, true); t.setText(label); t.setLetterSpacing(0.14f);
        t.setPadding(0, dp(24), 0, dp(4));
        col.addView(t);
    }

    private void item(final String key, String emoji, String title, String why, String action, final Runnable onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(card());
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(9);
        col.addView(row, rl);

        final boolean[] done = { getSharedPreferences("home", MODE_PRIVATE).getBoolean("lifeboat_" + key, false) };
        final TextView check = text(18, done[0] ? 0xFF5BD6B0 : 0xFF3A4A46, false);
        check.setText(done[0] ? "☑" : "☐"); check.setPadding(0, 0, dp(12), 0);
        row.addView(check);

        LinearLayout tc = new LinearLayout(this); tc.setOrientation(LinearLayout.VERTICAL);
        TextView tt = text(15, 0xFFEAF4F0, true); tt.setText(emoji + "  " + title); tc.addView(tt);
        TextView wy = text(12, 0xFF8AA49E, false); wy.setText(why); wy.setPadding(0, dp(2), 0, 0); tc.addView(wy);
        if (action != null && onAction != null) {
            TextView act = text(13, 0xFF5BD6B0, true); act.setText("→ " + action); act.setPadding(0, dp(7), 0, 0);
            act.setOnClickListener(v -> onAction.run());
            tc.addView(act);
        }
        row.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));

        row.setOnClickListener(v -> {
            done[0] = !done[0];
            getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("lifeboat_" + key, done[0]).apply();
            check.setText(done[0] ? "☑" : "☐");
            check.setTextColor(done[0] ? 0xFF5BD6B0 : 0xFF3A4A46);
        });
    }

    /* ---------- deep links ---------- */
    private void url(String u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { toast(L.t("Konnte die Seite nicht öffnen.")); }
    }
    private void settings(String action) {
        try { startActivity(new Intent(action)); } catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (RuntimeException ignored) { }
        }
    }
    private void openAddAccount() {
        try { startActivity(new Intent(Settings.ACTION_ADD_ACCOUNT)); }
        catch (RuntimeException e) { settings(Settings.ACTION_SYNC_SETTINGS); }
    }
    private void openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (RuntimeException ignored) { } }
        toast(L.t("App nicht gefunden."));
    }

    @Override protected void onResume() { super.onResume(); refreshStatus(); }

    /* ---------- helpers ---------- */
    private void toast(String s) { try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (RuntimeException ignored) { } }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        return v;
    }
    private android.graphics.drawable.GradientDrawable pill(int c) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(c); g.setCornerRadius(dp(14)); return g;
    }
    private android.graphics.drawable.GradientDrawable card() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF101A20); g.setCornerRadius(dp(14)); g.setStroke(Math.max(1, dp(1)), 0xFF1D2C30); return g;
    }
}
