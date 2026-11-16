package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;
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
 * ARC Play dock: a tab parked on a screen edge whenever music plays. Tap / swipe it toward the
 * middle to pull out a vertical control strip (cover · −10 s · ▶ · +10 s · ⏭ · ✕); swipe it back or
 * tap the grip to tuck away. You can DRAG the tab up and down to any height, and LONG-PRESS it to
 * flip to the other edge — so it dodges the Statistik edge-swipe, a cracked spot, or the Samsung
 * edge panel. Its place is remembered. Collapsed it is a few px wide, so it never covers the page.
 */
final class PlayBar {
    private PlayBar() { }

    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static SharedPreferences prefs;
    private static FrameLayout root;
    private static View handle, grab;
    private static LinearLayout panel;
    private static TextView play, tab;
    private static GradientDrawable handleBg, panelBg;
    private static boolean open, left;
    private static int posY = -1, screenH = 0;
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

    private static ImageView cover;

    private static void show(Context c) {
        if (wm == null) wm = c.getSystemService(WindowManager.class);
        density = c.getResources().getDisplayMetrics().density;
        screenH = c.getResources().getDisplayMetrics().heightPixels;
        prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        left = "L".equals(prefs.getString("playBarSide", "R"));
        posY = prefs.getInt("playBarY", -1);
        boolean dark = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? 0xF21A1A18 : 0xF2FFFFFF, ink = dark ? 0xFFF4F2EC : 0xFF1A1A1A, ink2 = dark ? 0xFFA5A5A0 : 0xFF5A5A58,
            line = dark ? 0xFF2C2C2A : 0xFFE0E0D8, rust = dark ? 0xFFD9472F : 0xFFB5271E;
        if (root == null) build(c, bg, ink, ink2, line, rust);
        else { panelBg.setColor(bg); handleBg.setColor(rust); }
        applySide();
        if (root.getParent() != null) return;
        lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.END);
        if (posY < 0) posY = (screenH - (int) px(160)) / 2;    // default: centred vertically
        lp.y = clampY(posY);
        open = false;
        panel.setVisibility(View.GONE);
        handle.setVisibility(View.VISIBLE);
        try { wm.addView(root, lp); } catch (RuntimeException ignored) { }
        excludeGesture();
    }

    private static int clampY(int y) {
        int max = Math.max((int) px(8), screenH - (int) px(168));
        return Math.max((int) px(8), Math.min(y, max));
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

        tab = glyph(c, "‹", 0xFFFFFFFF, 20);
        handleBg = new GradientDrawable();
        handleBg.setColor(rust);
        tab.setBackground(handleBg);
        tab.setElevation(px(8));
        handle = tab;
        root.addView(handle, new FrameLayout.LayoutParams((int) px(24), (int) px(104)));
        grab = new View(c);                                   // big invisible target; carries the gestures
        root.addView(grab, new FrameLayout.LayoutParams((int) px(52), (int) px(168)));
        grab.setOnTouchListener(dockTouch());

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding((int) px(6), (int) px(8), (int) px(6), (int) px(8));
        panelBg = new GradientDrawable();
        panelBg.setColor(bg);
        panelBg.setStroke((int) px(1), line);
        panel.setBackground(panelBg);
        panel.setElevation(px(12));

        TextView grip = glyph(c, "⋮", ink2, 16);
        grip.setOnTouchListener(edgeClose());
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

        root.addView(panel, new FrameLayout.LayoutParams((int) px(58), FrameLayout.LayoutParams.WRAP_CONTENT));
        panel.setVisibility(View.GONE);
    }

    /** Put everything on the chosen edge: horizontal gravity + which corners are rounded. */
    private static void applySide() {
        int g = (left ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
        for (View v : new View[]{handle, grab, panel}) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
            p.gravity = g;
            v.setLayoutParams(p);
        }
        tab.setText(left ? "›" : "‹");
        // round the corners that face INTO the screen (away from the edge the dock hugs)
        handleBg.setCornerRadii(sideRadii(px(11)));
        panelBg.setCornerRadii(sideRadii(px(18)));
        if (root != null && root.getParent() != null && lp != null) {
            lp.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.END);
            try { wm.updateViewLayout(root, lp); } catch (RuntimeException ignored) { }
            excludeGesture();
        }
    }

    private static float[] sideRadii(float r) {
        // {TL,TL, TR,TR, BR,BR, BL,BL}
        return left ? new float[]{0, 0, r, r, r, r, 0, 0}    // left edge → round the right corners
                    : new float[]{r, r, 0, 0, 0, 0, r, r};   // right edge → round the left corners
    }

    /** The grab tab: tap/swipe-in opens · drag up-down moves · long-press flips to the other edge. */
    private static View.OnTouchListener dockTouch() {
        return new View.OnTouchListener() {
            float x0, y0; int startY; long t0; boolean moved, dragging, flipped;
            final Runnable flip = () -> { if (!moved) { left = !left; prefs.edit().putString("playBarSide", left ? "L" : "R").apply(); applySide(); flipped = true; if (handle != null) handle.performHapticFeedback(0); } };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); startY = lp.y; t0 = System.currentTimeMillis();
                        moved = dragging = flipped = false;
                        H.postDelayed(flip, 500);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                        if (!moved && (Math.abs(dx) > px(14) || Math.abs(dy) > px(14))) { moved = true; H.removeCallbacks(flip); }
                        if (moved && Math.abs(dy) > Math.abs(dx)) {     // vertical → move the dock
                            dragging = true;
                            lp.y = clampY(startY + (int) dy);
                            try { wm.updateViewLayout(root, lp); } catch (RuntimeException ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        H.removeCallbacks(flip);
                        if (dragging) { posY = lp.y; prefs.edit().putInt("playBarY", posY).apply(); excludeGesture(); return true; }
                        if (flipped) return true;
                        float ux = e.getRawX() - x0, uy = e.getRawY() - y0;
                        boolean tap = Math.abs(ux) < px(16) && Math.abs(uy) < px(16) && System.currentTimeMillis() - t0 < 320;
                        boolean swipeIn = left ? ux > px(18) : ux < -px(18);   // swipe toward the middle
                        if (tap || swipeIn) expand();
                        return true;
                    default: return true;
                }
            }
        };
    }

    /** The ⋮ grip inside the open panel: tap or swipe back toward the edge to close. */
    private static View.OnTouchListener edgeClose() {
        return new View.OnTouchListener() {
            float x0, y0; long t0;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); y0 = e.getRawY(); t0 = System.currentTimeMillis(); return true;
                    case MotionEvent.ACTION_UP:
                        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                        boolean tap = Math.abs(dx) < px(16) && Math.abs(dy) < px(16) && System.currentTimeMillis() - t0 < 320;
                        boolean swipeOut = left ? dx < -px(18) : dx > px(18);
                        if (tap || swipeOut) collapse();
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
        float off = left ? -px(66) : px(66);
        panel.setTranslationX(off);
        panel.animate().translationX(0).setDuration(240).start();
        kick();
    }

    private static void collapse() {
        if (root == null || !open) return;
        open = false;
        H.removeCallbacks(AUTOCLOSE);
        float off = left ? -px(66) : px(66);
        panel.animate().translationX(off).setDuration(200).withEndAction(() -> {
            panel.setVisibility(View.GONE);
            panel.setTranslationX(0);
            if (handle != null) handle.setVisibility(View.VISIBLE);
            relayout();
        }).start();
    }

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
