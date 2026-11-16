package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static ch.architectdna.home.ArcUi.t;

/** Turns the floating ARC Play bar on/off and asks for the overlay permission it needs. */
public class PlayBarActivity extends Activity {

    private ArcUi ui;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        build();
    }

    @Override protected void onResume() { super.onResume(); build(); }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.bg);
        sv.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(44));
        sv.addView(col);
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(ui.label("ARC " + t("Play bar", "Play-Leiste"), true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = ui.mono("\u2715", ui.ink2, 13);
        x.setOnClickListener(v -> finish());
        h.addView(x);
        col.addView(h);
        col.addView(ui.title(t("Music, everywhere.", "Musik, \u00fcberall."), 38), ui.lp(-1, -2, ui.dp(20)));
        col.addView(ui.body(t("A slim control floats at the bottom of the screen whenever something plays \u2014 cover, title, \u221210s, play, +10s, skip. \u2715 hides it until the next track. Tap the cover to open ARC Player.",
            "Eine schmale Steuerung schwebt unten am Bildschirm, sobald etwas l\u00e4uft \u2014 Cover, Titel, \u221210s, Play, +10s, weiter. \u2715 blendet sie bis zum n\u00e4chsten Titel aus. Tipp aufs Cover \u00f6ffnet ARC Player."), ui.ink2, 15), ui.lp(-1, -2, ui.dp(12)));

        boolean on = prefs.getBoolean("playBar", true);
        TextView toggle = ui.body((on ? "\u25cf  " : "\u25cb  ") + t("Play bar on", "Play-Leiste an"), on ? ui.ink : ui.ink2, 16);
        toggle.setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(16));
        toggle.setBackground(ui.box(ui.card, on ? ui.rust : ui.line, 8));
        toggle.setOnClickListener(v -> { prefs.edit().putBoolean("playBar", !on).apply(); if (on) PlayBar.hide(); build(); });
        col.addView(toggle, ui.lp(-1, -2, ui.dp(16)));

        boolean overlay = android.os.Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        if (!overlay) {
            col.addView(ui.button(t("Allow floating over apps", "\u00dcber Apps schweben erlauben"), 1, v -> {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); } catch (RuntimeException ignored) { }
            }), ui.lp(-1, ui.dp(52), ui.dp(10)));
        }
        if (!isNotifAccess())
            col.addView(ui.body(t("Needs notification access (Settings \u2192 Notification access \u2192 ARC-DNA) so it can read what's playing.",
                "Braucht Benachrichtigungszugriff (Einstellungen \u2192 Benachrichtigungszugriff \u2192 ARC-DNA), um zu sehen was l\u00e4uft."), ui.rust, 13.5f), ui.lp(-1, -2, ui.dp(12)));
        setContentView(sv);
    }

    private boolean isNotifAccess() {
        String f = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return f != null && f.contains(getPackageName());
    }
}
