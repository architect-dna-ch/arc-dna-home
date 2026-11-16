package ch.architectdna.home;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ARC Play dock: a slim handle tucked against the RIGHT edge (like a Samsung edge panel) whenever
 * music plays. Swipe it left — or tap it — to pull out a vertical control strip (cover · −10 s · ▶ ·
 * +10 s · ⏭ · ✕); swipe it right or tap the grip to tuck it back to the handle. Collapsed it is a
 * few pixels wide, so it never covers the page. Driven by MediaListener, over every app via the
 * overlay permission.
 */
final class PlayBar {
    private PlayBar() { }

    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static FrameLayout root;
    private static View handle;
    private static LinearLayout panel;
    private static TextView play;
    private static ImageView cover;
    private static boolean open;
    private static float density = 2f;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable AUTOCLOSE = PlayBar::collapse;
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
        density = c.getResources().getDisplayMetrics().density;
        boolean dark = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? 0xF21A1A18 : 0xF2FFFFFF, ink = dark ? 0xFFF4F2EC : 0xFF1A1A1A, ink2 = dark ? 0xFFA5A5A0 : 0xFF5A5A58,
            line = dark ? 0xFF2C2C2A : 0xFFE0E0D8, rust = dark ? 0xFFD9472F : 0xFFB5271E;
        if (root == null) build(c, bg, ink, ink2, line, rust);
        else {
            ((GradientDrawable) panel.getBackground()).setColor(bg);
            ((GradientDrawable) handle.getBackground()).setColor(rust);
        }
        if (root.getParent() != null) return;
        lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;   // right edge, vertically centred — beside the Samsung edge panel
        open = false;
        panel.setVisibility(View.GONE);
        handle.setVisibility(View.VISIBLE);
        try { wm.addView(root, lp); } catch (RuntimeException ignored) { }
        excludeGesture();
    }

    /** Claim our strip back from the phone's edge back-gesture / edge-panel zone, so taps land on us. */
    private static void excludeGesture() {
        if (Build.VERSION.SDK_INT < 29 || root == null) return;
        root.post(() -> {
            try {
                java.util.List<android.graphics.Rect> r = new java.util.ArrayList<>();
                r.add(new android.graphics.Rect(0, 0, root.getWidth(), root.getHeight()));
                root.setSystemGestureExclusionRects(r);
            } catch (Exception ignored) { }
        });
    }

    private static void build(Context c, int bg, int ink, int ink2, int line, int rust) {
        root = new FrameLayout(c);

        // The always-there handle: a clear vertical tab hugging the edge, with a ‹ to say "pull me".
        TextView tab = glyph(c, "‹", 0xFFFFFFFF, 20);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(rust);
        hg.setCornerRadii(new float[]{px(11), px(11), 0, 0, 0, 0, px(11), px(11)});   // round the LEFT side only
        tab.setBackground(hg);
        tab.setElevation(px(8));
        handle = tab;
        root.addView(handle, new FrameLayout.LayoutParams((int) px(24), (int) px(104), Gravity.END | Gravity.CENTER_VERTICAL));
        // a big invisible touch target over the tab, so it's easy to grab even near the edge gesture zone
        View grab = new View(c);
        root.addView(grab, new FrameLayout.LayoutParams((int) px(50), (int) px(160), Gravity.END | Gravity.CENTER_VERTICAL));
        grab.setOnTouchListener(edgeSwipe(true));

        // The pulled-out control strip, stacked top → bottom.
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding((int) px(6), (int) px(8), (int) px(6), (int) px(8));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(bg);
        pg.setCornerRadii(new float[]{px(18), px(18), 0, 0, 0, 0, px(18), px(18)});
        pg.setStroke((int) px(1), line);
        panel.setBackground(pg);
        panel.setElevation(px(12));

        TextView grip = glyph(c, "⋮", ink2, 16);     // tap / swipe-right here to close
        grip.setOnTouchListener(edgeSwipe(false));
        panel.addView(grip, new LinearLayout.LayoutParams((int) px(34), (int) px(22)));

        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(line);
        cover.setClipToOutline(true);
        final float cr = px(8);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cr); }
        });
        cover.setOnClickListener(v -> { openPlayer(c); collapse(); });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams((int) px(44), (int) px(44));
        cl.bottomMargin = (int) px(6);
        panel.addView(cover, cl);

        panel.addView(btn(c, "⟲", ink2, 20, v -> { MediaExtras.seekBy(current, -10_000); kick(); }));
        play = btn(c, "▶", ink, 24, v -> {
            if (current == null) return;
            PlaybackState s = current.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) current.getTransportControls().pause();
            else current.getTransportControls().play();
            kick();
        });
        panel.addView(play);
        panel.addView(btn(c, "⟳", ink2, 20, v -> { MediaExtras.seekBy(current, 10_000); kick(); }));
        panel.addView(btn(c, "⏭", ink, 20, v -> { if (current != null) current.getTransportControls().skipToNext(); kick(); }));
        panel.addView(btn(c, "✕", ink2, 15, v -> { hiddenKey = lastArt; hide(); }));

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams((int) px(58), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        root.addView(panel, pl);
        panel.setVisibility(View.GONE);
    }

    /** One finger on the edge: tap or swipe toward the screen opens, away from it closes. */
    private static View.OnTouchListener edgeSwipe(final boolean toOpen) {
        return new View.OnTouchListener() {
            float x0, y0; long t0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); t0 = System.currentTimeMillis(); return true;
                    case MotionEvent.ACTION_UP:
                        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                        boolean tap = Math.abs(dx) < px(16) && Math.abs(dy) < px(16) && System.currentTimeMillis() - t0 < 300;
                        if (toOpen && (tap || dx < -px(20))) expand();
                        else if (!toOpen && (tap || dx > px(20))) collapse();
                        return true;
                    default: return true;
                }
            }
        };
    }

    private static void expand() {
        if (root == null || open) { kick(); return; }
        open = true;
        handle.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        relayout();
        panel.setTranslationX(px(66));
        panel.animate().translationX(0).setDuration(240).start();
        kick();
    }

    private static void collapse() {
        if (root == null || !open) return;
        open = false;
        H.removeCallbacks(AUTOCLOSE);
        panel.animate().translationX(px(66)).setDuration(200).withEndAction(() -> {
            panel.setVisibility(View.GONE);
            panel.setTranslationX(0);
            if (handle != null) handle.setVisibility(View.VISIBLE);
            relayout();
        }).start();
    }

    /** Keep the strip open a while after each tap, then tuck it away on its own. */
    private static void kick() {
        if (!open) return;
        H.removeCallbacks(AUTOCLOSE);
        H.postDelayed(AUTOCLOSE, 6000);
    }

    private static void relayout() {
        try { if (root != null && root.getParent() != null) wm.updateViewLayout(root, lp); } catch (RuntimeException ignored) { }
        excludeGesture();
    }

    private static TextView btn(Context c, String s, int colour, float sp, View.OnClickListener l) {
        TextView t = glyph(c, s, colour, sp);
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams((int) px(46), (int) px(44)));
        return t;
    }

    private static TextView glyph(Context c, String s, int colour, float sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static void openPlayer(Context c) {
        try {
            c.startActivity(new android.content.Intent(c, PlayerActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) { }
    }

    static void hide() {
        open = false;
        H.removeCallbacks(AUTOCLOSE);
        try { if (root != null && root.getParent() != null && wm != null) wm.removeView(root); } catch (RuntimeException ignored) { }
    }

    private static float px(float v) { return density * v; }

    private static String nz(String s) { return s == null ? "" : s; }
}
