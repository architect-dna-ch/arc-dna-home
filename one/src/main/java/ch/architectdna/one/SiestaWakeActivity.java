package ch.architectdna.one;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The end of Siesta 90: turns the screen on over the lock screen and wakes you gently — the alarm
 * sound starts almost silent and rises over a minute, with a soft vibration. "Wach" stops it;
 * "Music One" stops it and starts your track. Rings at most 10 minutes. Puts Do Not Disturb back.
 */
public class SiestaWakeActivity extends Activity {

    private static final int INK = 0xFFF4F1EC, INK2 = 0xFFA9A39A, BG = 0xFF0E0D0C, CARD = 0xFF1B1916, LINE = 0xFF2B2824;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private float vol = 0.03f;

    private final Runnable rise = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            vol = Math.min(1f, vol + 0.02f);                           // ~1 min from a whisper to full
            try { player.setVolume(vol, vol); } catch (RuntimeException ignored) { }
            if (vol < 1f) main.postDelayed(this, 1200);
        }
    };
    private final Runnable giveUp = this::finishUp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(BG);
        col.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView label = text(11, 0xFFE0573B, Typeface.create("monospace", Typeface.BOLD));
        label.setText("— SIESTA 90 · EIN SCHLAFZYKLUS");
        label.setLetterSpacing(0.2f);
        col.addView(label);
        TextView time = text(64, INK, Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setText(new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date()));
        time.setPadding(0, dp(12), 0, dp(4));
        col.addView(time);
        TextView line = text(17, INK2, Typeface.create("serif", Typeface.ITALIC));
        line.setText("Aufwachen. Langsam strecken, Wasser trinken, Licht.");
        line.setGravity(Gravity.CENTER);
        line.setPadding(0, 0, 0, dp(36));
        col.addView(line);

        TextView awake = pill("Wach ✓", INK, BG);
        awake.setOnClickListener(v -> finishUp());
        col.addView(awake, wide());
        TextView m1 = pill("♪  Music One", CARD, INK);
        m1.setOnClickListener(v -> {
            finishUp();
            startActivity(new Intent(this, OneHomeActivity.class).putExtra("play_m1", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        LinearLayout.LayoutParams ml = wide(); ml.topMargin = dp(12);
        col.addView(m1, ml);

        setContentView(col, new ViewGroup.LayoutParams(-1, -1));
        ring();
        main.postDelayed(giveUp, 10 * 60_000L);
    }

    private void ring() {
        try {
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, u);
            player.setLooping(true);
            player.prepare();
            player.setVolume(vol, vol);
            player.start();
            main.postDelayed(rise, 1200);
        } catch (Exception e) {
            player = null;
        }
        try {
            Vibrator vb = getSystemService(Vibrator.class);
            if (vb != null && vb.hasVibrator())
                vb.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 1800, 160, 1800, 220, 2400}, 1));
        } catch (RuntimeException ignored) { }
    }

    private void finishUp() {
        main.removeCallbacks(rise);
        main.removeCallbacks(giveUp);
        if (player != null) { try { player.stop(); player.release(); } catch (RuntimeException ignored) { } player = null; }
        try { Vibrator vb = getSystemService(Vibrator.class); if (vb != null) vb.cancel(); } catch (RuntimeException ignored) { }
        Siesta.stop(this);
        finish();
    }

    @Override protected void onDestroy() {
        if (player != null) finishUp();
        super.onDestroy();
    }

    @Override public void onBackPressed() { finishUp(); }

    private LinearLayout.LayoutParams wide() { return new LinearLayout.LayoutParams(dp(240), -2); }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setTypeface(tf); t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView pill(String s, int fill, int ink) {
        TextView t = text(17, ink, Typeface.DEFAULT_BOLD);
        t.setText(s);
        t.setPadding(dp(18), dp(15), dp(18), dp(15));
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(30));
        if (fill == CARD) g.setStroke(dp(1), LINE);
        t.setBackground(g);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
