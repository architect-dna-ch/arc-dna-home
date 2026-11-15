package ch.architectdna.orbit;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** ARC links open in ARC Browser when it's installed (private, no trackers); otherwise the default browser. */
final class Web {
    private Web() { }

    static final String BROWSER = "ch.architectdna.browser";

    static void open(Context c, String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (c.getPackageManager().getLaunchIntentForPackage(BROWSER) != null) i.setPackage(BROWSER);
        try {
            c.startActivity(i);
        } catch (RuntimeException e) {
            try { c.startActivity(i.setPackage(null)); } catch (RuntimeException ignored) { }
        }
    }
}
