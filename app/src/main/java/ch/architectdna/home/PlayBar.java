package ch.architectdna.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ARC Play bar: a slim music control that floats at the bottom of the screen (above the keyboard
 * when one is up) whenever something plays. Cover, title, −10 s · ▶ · +10 s, and ✕ to hide until
 * the next track. Driven by MediaListener, shown over every app via the overlay permission.
 */
final class PlayBar {
    private PlayBar() { }

    private static WindowManager wm;
    private static View bar;
    private static TextView title, play;
    private static ImageView cover;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static String lastArt = "", hiddenKey = "";
    private static MediaController current;

    static boolean on(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("playBar", true)
            && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c));
    }

    /** Called from MediaListener whenever the playing session changes. null = nothing plays. */
    static void update(Context c, MediaController mc) {
        if (!on(c) || mc == null || mc.getMetadata() == null) { hide(); return; }
        MediaMetadata m = mc.getMetadata();
        String t = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE)), a = nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        String key = t + "|" + a;
        if (key.equals(hiddenKey)) return;          // you hid this track with ✕
        hiddenKey = "";
        current = mc;
        show(c);
        title.setText(t + (a.isEmpty() ? "" : "  ·  " + a));
        PlaybackState s = mc.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        play.setText(playing ? "❚❚" : "▶");
        if (!key.equals(lastArt)) {
            lastArt = key;
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art != null) cover.setImageBitmap(art); else cover.setImageDrawable(null);
        }
    }

    private static void show(Context c) {
        if (wm == null) wm = c.getSystemService(WindowManager.class);
        boolean dark = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        float d = c.getResources().getDisplayMetrics().density;
        int bg = dark ? 0xF21A1A18 : 0xF2FFFFFF, ink = dark ? 0xFFF4F2EC : 0xFF1A1A1A, ink2 = dark ? 0xFFA5A5A0 : 0xFF5A5A58,
            line = dark ? 0xFF2C2C2A : 0xFFE0E0D8, rust = dark ? 0xFFD9472F : 0xFFB5271E;
        if (bar == null) {
            LinearLayout b = new LinearLayout(c);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding((int) (8 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
            GradientDrawable g = new GradientDrawable();
            g.setColor(bg);
            g.setCornerRadius(14 * d);
            g.setStroke((int) d, line);
            b.setBackground(g);
            b.setElevation(10 * d);

            cover = new ImageView(c);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(line);
            cover.setClipToOutline(true);
            final float cr = 8 * d;
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cr); }
            });
            cover.setOnClickListener(v -> openPlayer(c));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams((int) (38 * d), (int) (38 * d));
            b.addView(cover, cl);

            title = new TextView(c);
            title.setTextColor(ink);
            title.setTextSize(13);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            title.setPadding((int) (10 * d), 0, (int) (8 * d), 0);
            title.setOnClickListener(v -> openPlayer(c));
            b.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

            b.addView(btn(c, "⟲", ink2, 20, d, v -> MediaExtras.seekBy(current, -10_000)));
            play = btn(c, "▶", ink, 22, d, v -> {
                if (current == null) return;
                PlaybackState s = current.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) current.getTransportControls().pause();
                else current.getTransportControls().play();
            });
            b.addView(play);
            b.addView(btn(c, "⟳", ink2, 20, d, v -> MediaExtras.seekBy(current, 10_000)));
            b.addView(btn(c, "⏭", ink, 18, d, v -> { if (current != null) current.getTransportControls().skipToNext(); }));
            b.addView(btn(c, "✕", ink2, 15, d, v -> { hiddenKey = lastArt; hide(); }));
            bar = b;
        } else {
            ((GradientDrawable) bar.getBackground()).setColor(bg);
        }
        if (bar.getParent() != null) return;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM;
        p.horizontalMargin = 0.02f;
        p.y = (int) (8 * d);
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;   // ride up with the keyboard
        try { wm.addView(bar, p); } catch (RuntimeException ignored) { }
    }

    private static TextView btn(Context c, String s, int colour, float sp, float d, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth((int) (40 * d));
        t.setPadding((int) (4 * d), (int) (6 * d), (int) (4 * d), (int) (6 * d));
        t.setOnClickListener(l);
        return t;
    }

    private static void openPlayer(Context c) {
        try {
            c.startActivity(new android.content.Intent(c, PlayerActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) { }
    }

    static void hide() {
        if (bar != null && bar.getParent() != null) try { wm.removeView(bar); } catch (RuntimeException ignored) { }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
