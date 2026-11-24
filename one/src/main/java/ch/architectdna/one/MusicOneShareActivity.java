package ch.architectdna.one;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Teilen → ARC One" from YouTube Music (or YouTube): the shared link becomes your Music One track.
 * Accepts only YouTube links; takes the title from the share subject or the text before the link.
 * No screen of its own — it saves, says so, and closes.
 */
public class MusicOneShareActivity extends Activity {

    private static final Pattern YT = Pattern.compile("https?://(?:music\\.youtube\\.com|(?:www\\.|m\\.)?youtube\\.com|youtu\\.be)/\\S+");

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        String text = i == null ? null : i.getStringExtra(Intent.EXTRA_TEXT);
        String subject = i == null ? null : i.getStringExtra(Intent.EXTRA_SUBJECT);
        Matcher m = text == null ? null : YT.matcher(text);
        if (m == null || !m.find()) {
            Toast.makeText(this, L.t("Kein YouTube-Music-Link — in YouTube Music auf «Teilen» → ARC One tippen."), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String link = m.group();
        String before = text.substring(0, m.start()).replaceAll("[\\s\\-–:]+$", "").trim();
        String title = subject != null && !subject.trim().isEmpty() ? subject.trim() : before;
        String artist = "";
        int dash = title.lastIndexOf(" - ");
        if (dash > 0) { artist = title.substring(dash + 3).trim(); title = title.substring(0, dash).trim(); }
        if (title.isEmpty()) title = L.t("Dein Song");
        getSharedPreferences("home", MODE_PRIVATE).edit()
            .putString("m1_link", link).putString("m1_title", title).putString("m1_artist", artist).apply();
        Toast.makeText(this, L.t("Music One: ") + title, Toast.LENGTH_SHORT).show();
        finish();
    }
}
