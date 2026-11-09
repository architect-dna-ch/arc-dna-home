package ch.architectdna.home;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;

/**
 * Fuchs-Rennen über Bluetooth: one device hosts, the other joins. Both run the same seeded course
 * live (BtRace), stream their distance, and see the partner as a ghost fox. Furthest wins.
 */
public class RaceActivity extends Activity {

    private RaceView race;
    private boolean started, hosting;
    private long seed;
    private TextView status;

    private final BtRace.Listener listener = new BtRace.Listener() {
        @Override public void onConnected(String name) {
            toast("Verbunden mit " + name);
            if (hosting) { seed = new Random().nextLong(); BtRace.send("START " + seed); begin(seed); }
            else if (status != null) status.setText("Verbunden – warte auf Start …");
        }
        @Override public void onMessage(String line) { handle(line); }
        @Override public void onClosed(String why) { toast("Rennen getrennt: " + why); if (!started) chooser(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView();
        getWindow().setStatusBarColor(0xFFF5F1E8);
        chooser();
    }

    private void chooser() {
        started = false;
        int paper = 0xFFF5F1E8, ink = 0xFF1B1613, ink2 = 0xFF6A5F54, rust = 0xFFB5271E;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(paper);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Fuchs-Rennen");
        title.setTextColor(ink); title.setTextSize(28); title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Beide Geräte: ARC-DNA offen, Bluetooth an, gekoppelt. Einer hostet, einer tritt bei.");
        sub.setTextColor(ink2); sub.setTextSize(14); sub.setPadding(0, pad / 3, 0, pad);
        col.addView(sub);

        col.addView(button("🏁  Rennen hosten (warten)", rust, 0xFFFFFFFF, v -> {
            hosting = true; status.setText("Warte auf Partner …"); BtRace.host(this, listener);
        }));

        status = new TextView(this);
        status.setTextColor(ink2); status.setTextSize(13); status.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(status);

        TextView join = new TextView(this);
        join.setText("… oder einem Partner beitreten:");
        join.setTextColor(ink); join.setTextSize(15); join.setPadding(0, pad / 2, 0, pad / 3);
        col.addView(join);

        java.util.List<BluetoothDevice> parts = BtRace.partners(this);
        if (parts.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Keine gekoppelten Geräte gefunden.");
            none.setTextColor(ink2); none.setTextSize(13);
            col.addView(none);
        }
        for (BluetoothDevice d : parts) {
            col.addView(button("↗  " + BtRace.safeName(d), 0x00000000, ink, v -> {
                hosting = false; status.setText("Verbinde …"); BtRace.join(this, d, listener);
            }));
        }
        sv.addView(col);
        setContentView(sv);
    }

    private Button button(String text, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextColor(fg); b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        float dp = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(bg == 0 ? 0x00000000 : bg); g.setCornerRadius(22 * dp);
        if (bg == 0) { g.setStroke((int) (1.5f * dp), 0xFFE2D9C8); }
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (10 * dp);
        b.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        return b;
    }

    private void handle(String line) {
        try {
            if (line.startsWith("START ")) {
                if (!started) begin(Long.parseLong(line.substring(6).trim()));
            } else if (line.startsWith("D ")) {
                if (race != null) race.setOpponent(Integer.parseInt(line.substring(2).trim()), false, -1);
            } else if (line.startsWith("X ")) {
                int d = Integer.parseInt(line.substring(2).trim());
                if (race != null) race.setOpponent(d, true, d);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void begin(long seed) {
        if (started) return;
        started = true;
        race = new RaceView(this);
        race.start(seed, new RaceView.Host() {
            @Override public void onTick(int distance) { BtRace.send("D " + distance); }
            @Override public void onCrash(int distance) { BtRace.send("X " + distance); }
        });
        setContentView(race);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        BtRace.close();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
