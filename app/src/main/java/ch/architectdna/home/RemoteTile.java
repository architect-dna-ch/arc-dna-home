package ch.architectdna.home;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * ARC Remote, as a Quick-Settings tile. Pull down the shade, tap it, and the remote for the tablet's
 * music opens straight away — no hunting for an app icon. Add it once from the shade's edit (pencil)
 * screen, or let ARC Remote offer to add it the first time you open it.
 */
public class RemoteTile extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("ARC Remote");
        t.setState(Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, RemoteActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(i);   // deprecated < 34, still the only way there
            }
        } catch (RuntimeException ignored) { }
    }
}
