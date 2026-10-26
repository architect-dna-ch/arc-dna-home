package ch.architectdna.home;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * ARC Player: our own face for whatever plays — YouTube Music (or Spotify …) keeps doing the
 * playing in the background, this screen remaps it. The track sits on a retro floppy disk, a
 * shimmering bar to swipe through it, big controls, the app's own extra buttons (like, shuffle …),
 * the queue to jump in, and the buddy DJs to start something new.
 */
public class PlayerActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private MediaController controller;
    private ComponentName listenerName;
    private int bg = 0xFF0E0E0D, ink = 0xFFF4F2EC, ink2 = 0xFFA5A5A0, ink3 = 0xFF6F6E69, line = 0xFF2C2C2A, rust = 0xFFD9472F;
    private FrameLayout root;
    private Disk disk;
    private Seek seek;
    private TextView title, artist, source, playBtn, status;
    private LinearLayout actions, queue;
    private DjPicker dj;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        sessions = getSystemService(MediaSessionManager.class);
        listenerName = new ComponentName(this, MediaListener.class);

        root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);
        ScrollView sv = new ScrollView(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(16), dp(22), dp(28));
        sv.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dash = new View(this);
        dash.setBackgroundColor(rust);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(24), dp(2));
        dl.rightMargin = dp(12);
        head.addView(dash, dl);
        TextView name = mono(11, ink3);
        name.setLetterSpacing(0.24f);
        name.setText("ARC PLAYER");
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView djB = chip("🎧 DJ", rust);
        djB.setOnClickListener(v -> openDj());
        head.addView(djB);
        TextView close = mono(18, ink);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        disk = new Disk();
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, dp(300));
        kl.topMargin = dp(18);
        col.addView(disk, kl);

        title = text(22, ink, true);
        title.setPadding(0, dp(18), 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        col.addView(title);
        artist = text(16, ink2, false);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(artist);
        source = mono(10, ink3);
        source.setPadding(0, dp(6), 0, 0);
        source.setOnClickListener(v -> openApp());
        col.addView(source);

        seek = new Seek();
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(52));
        sl.topMargin = dp(14);
        col.addView(seek, sl);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(8), 0, 0);
        TextView prev = round("⏮", 58, false);
        prev.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        playBtn = round("▶", 78, true);
        playBtn.setOnClickListener(v -> togglePlay());
        TextView next = round("⏭", 58, false);
        next.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
        controls.addView(prev, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(78), dp(78));
        pl.leftMargin = pl.rightMargin = dp(28);
        controls.addView(playBtn, pl);
        controls.addView(next, new LinearLayout.LayoutParams(dp(58), dp(58)));
        col.addView(controls);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        actions = new LinearLayout(this);
        hs.addView(actions);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(18);
        col.addView(hs, al);

        status = text(14, ink3, false);
        status.setPadding(0, dp(16), 0, 0);
        col.addView(status);

        TextView qh = mono(11, ink3);
        qh.setLetterSpacing(0.24f);
        qh.setText("— ALS NÄCHSTES");
        qh.setPadding(0, dp(24), 0, dp(8));
        col.addView(qh);
        queue = new LinearLayout(this);
        queue.setOrientation(LinearLayout.VERTICAL);
        col.addView(queue);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            sessions.addOnActiveSessionsChangedListener(changed, listenerName);
            pick(sessions.getActiveSessions(listenerName));
        } catch (SecurityException e) {
            pick(null);
            status.setText("ARC Player braucht den Benachrichtigungszugriff für ARC-DNA. Hier antippen.");
            status.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        }
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            sessions.removeOnActiveSessionsChangedListener(changed);
        } catch (RuntimeException ignored) {
        }
        if (controller != null) controller.unregisterCallback(callback);
        main.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        if (dj != null) dj.close();
        else super.onBackPressed();
    }

    /* ---------------- the session ---------------- */

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::pick;

    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null) best = c;
            }
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = best;
        if (controller != null) controller.registerCallback(callback, main);
        render();
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            render();
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> q) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    private void render() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        if (m == null) {
            title.setText("Nichts läuft");
            artist.setText("🎧 DJ antippen – ein Buddy legt auf.");
            source.setText("");
            disk.set(null, "LEER", 0);
        } else {
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE), a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
            title.setText(t != null ? t : "–");
            artist.setText(a != null ? a : "");
            source.setText(appName(controller.getPackageName()).toUpperCase(Locale.GERMAN) + " ↗ · DORT ÖFFNEN");
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            disk.set(art, t != null ? t : "", colourOf(art));
        }
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        playBtn.setText(playing ? "❚❚" : "▶");
        // The app's own extra buttons: like, shuffle, repeat … whatever it offers.
        actions.removeAllViews();
        if (s != null) {
            for (PlaybackState.CustomAction ca : s.getCustomActions()) {
                TextView c = chip(String.valueOf(ca.getName()).toUpperCase(Locale.GERMAN), ink2);
                c.setOnClickListener(v -> {
                    if (controller != null) controller.getTransportControls().sendCustomAction(ca, null);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(8);
                actions.addView(c, lp);
            }
        }
        // The queue: tap = play from there.
        queue.removeAllViews();
        List<MediaSession.QueueItem> q = controller == null ? null : controller.getQueue();
        long active = s == null ? -1 : s.getActiveQueueItemId();
        if (q == null || q.isEmpty()) {
            TextView none = text(14, ink3, false);
            none.setText(controller == null ? "–" : "Diese App zeigt ihre Warteschlange nicht.");
            queue.addView(none);
        } else {
            int shown = 0;
            boolean after = active < 0;
            for (MediaSession.QueueItem it : q) {
                if (!after) {
                    if (it.getQueueId() == active) after = true;
                    continue;
                }
                if (shown++ >= 25) break;
                queue.addView(queueRow(it));
            }
            if (shown == 0) {
                TextView none = text(14, ink3, false);
                none.setText("Ende der Warteschlange.");
                queue.addView(none);
            }
        }
        disk.invalidate();
    }

    private View queueRow(MediaSession.QueueItem it) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(10), dp(14), dp(10));
        r.setBackground(box(0xFF1A1A18, line, 12));
        TextView t = text(15, ink, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(it.getDescription().getTitle());
        r.addView(t);
        CharSequence sub = it.getDescription().getSubtitle();
        if (sub != null) {
            TextView a = text(13, ink2, false);
            a.setSingleLine(true);
            a.setEllipsize(TextUtils.TruncateAt.END);
            a.setText(sub);
            r.addView(a);
        }
        r.setOnClickListener(v -> {
            if (controller != null) controller.getTransportControls().skipToQueueItem(it.getQueueId());
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    private void togglePlay() {
        if (controller == null) {
            openDj();
            return;
        }
        PlaybackState s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    private void openApp() {
        if (controller == null) return;
        PendingIntent pi = controller.getSessionActivity();
        try {
            if (pi != null) {
                pi.send();
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
        if (launch != null) startActivity(launch);
    }

    private String appName(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** A colour for the disk shell from the cover (its average), so each track has its own disk. */
    private int colourOf(Bitmap b) {
        if (b == null) return 0xFF3A3A37;
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 1, 1, true);
            int c = s.getPixel(0, 0);
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.3f + 0.15f);
            hsv[2] = Math.max(0.35f, Math.min(0.7f, hsv[2]));
            return android.graphics.Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFF3A3A37;
        }
    }

    /* ---------------- DJ ---------------- */

    private void openDj() {
        if (dj != null) return;
        dj = new DjPicker(this, MusicStats.favoriteArtist(this), new DjPicker.Listener() {
            @Override
            public void onPick(DjPicker.Dj d) {
                playFromSearch(d.query);
                main.postDelayed(() -> onClose(), 700);
            }

            @Override
            public void onClose() {
                if (dj != null) root.removeView(dj);
                dj = null;
            }
        });
        root.addView(dj, new FrameLayout.LayoutParams(-1, -1));
    }

    private void playFromSearch(String query) {
        Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (String pkg : new String[]{"com.google.android.apps.youtube.music", "com.spotify.music"}) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                i.setPackage(pkg);
                break;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query))));
            } catch (RuntimeException ignored) {
            }
            return;
        }
        // Come back to the player once the music runs.
        main.postDelayed(() -> {
            try {
                startActivity(new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }, 3200);
    }

    /* ---------------- ticking: position ---------------- */

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            seek.invalidate();
            main.postDelayed(this, 33);
        }
    };

    private long position() {
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        if (s == null) return 0;
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, p);
    }

    private long duration() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        return m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    /* ---------------- views ---------------- */

    /** The track as a 3½" floppy disk: shell in the cover's colour, metal shutter, the cover as label. */
    private final class Disk extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path path = new Path();
        private Bitmap art;
        private String label = "";
        private int shell = 0xFF3A3A37;
        private long changedAt;

        Disk() {
            super(PlayerActivity.this);
        }

        void set(Bitmap a, String l, int colour) {
            // Only a new title slides in (the cover comes as a fresh copy on every play / pause).
            if (!l.equals(label)) changedAt = SystemClock.uptimeMillis();
            art = a;
            label = l;
            shell = colour == 0 ? 0xFF3A3A37 : colour;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), size = Math.min(getWidth(), h) * 0.94f, x = (getWidth() - size) / 2, y = (h - size) / 2;
            // New track: the disk slides in from below.
            float in = Math.min(1, (SystemClock.uptimeMillis() - changedAt) / 380f);
            in = 1 - (1 - in) * (1 - in);
            c.save();
            c.translate(0, (1 - in) * dp(60));
            p.setAlpha(255);
            float u = size / 100f;
            RectF r = new RectF(x, y, x + size, y + size);
            path.reset();
            path.moveTo(r.left + 3 * u, r.top);
            path.lineTo(r.right - 8 * u, r.top);
            path.lineTo(r.right, r.top + 8 * u);
            path.lineTo(r.right, r.bottom - 3 * u);
            path.quadTo(r.right, r.bottom, r.right - 3 * u, r.bottom);
            path.lineTo(r.left + 3 * u, r.bottom);
            path.quadTo(r.left, r.bottom, r.left, r.bottom - 3 * u);
            path.lineTo(r.left, r.top + 3 * u);
            path.quadTo(r.left, r.top, r.left + 3 * u, r.top);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(shell);
            c.drawPath(path, p);
            // Shutter with its window.
            p.setColor(0xFFC4C4BE);
            c.drawRect(r.left + 24 * u, r.top, r.right - 20 * u, r.top + 28 * u, p);
            p.setColor(0xFF2A2A28);
            c.drawRect(r.left + 56 * u, r.top + 4 * u, r.left + 68 * u, r.top + 24 * u, p);
            // Write-protect hole, bottom corners.
            c.drawRect(r.left + 4 * u, r.bottom - 9 * u, r.left + 9 * u, r.bottom - 4 * u, p);
            // Label: the cover, with the title written along the bottom.
            RectF lab = new RectF(r.left + 9 * u, r.top + 36 * u, r.right - 9 * u, r.bottom - 2 * u);
            p.setColor(0xFFF4F2EC);
            c.drawRoundRect(lab, 2 * u, 2 * u, p);
            if (art != null) {
                RectF img = new RectF(lab.left + 2 * u, lab.top + 2 * u, lab.right - 2 * u, lab.bottom - 12 * u);
                float sw = art.getWidth(), sh = art.getHeight(), k = Math.max(img.width() / sw, img.height() / sh);
                float cw = img.width() / k, ch = img.height() / k;
                android.graphics.Rect src = new android.graphics.Rect((int) ((sw - cw) / 2), (int) ((sh - ch) / 2), (int) ((sw + cw) / 2), (int) ((sh + ch) / 2));
                c.drawBitmap(art, src, img, p);
            }
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(5 * u);
            p.setColor(0xFF1A1A1A);
            String t = label.length() > 28 ? label.substring(0, 27) + "…" : label;
            c.drawText(t.toUpperCase(Locale.GERMAN), lab.left + 3 * u, lab.bottom - 4 * u, p);
            c.restore();
            if (in < 1) postInvalidateOnAnimation();
        }
    }

    /** The track bar: drag or tap to jump; a soft white-blue shimmer floats along it. */
    private final class Seek extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long dragAt = -1;

        Seek() {
            super(PlayerActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), top = dp(12), bh = dp(8);
            long dur = duration(), pos = dragAt >= 0 ? dragAt : position();
            float f = dur > 0 ? Math.min(1, pos / (float) dur) : 0, head = w * f;
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(line);
            c.drawRoundRect(0, top, w, top + bh, bh / 2, bh / 2, p);
            p.setColor(rust);
            c.drawRoundRect(0, top, Math.max(bh, head), top + bh, bh / 2, bh / 2, p);
            float k = (SystemClock.uptimeMillis() % 3600) / 3600f, band = dp(70), sx = -band + (w + 2 * band) * k;
            p.setShader(new LinearGradient(sx - band, 0, sx + band, 0,
                new int[]{0x00BFE3FF, 0x88EAF6FF, 0xCCFFFFFF, 0x88BFE3FF, 0x00BFE3FF}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, top, w, top + bh, bh / 2, bh / 2, p);
            p.setShader(null);
            p.setColor(0x553C8CFF);
            c.drawCircle(head, top + bh / 2, dp(12), p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(head, top + bh / 2, dp(7), p);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(11));
            p.setColor(ink3);
            c.drawText(time(pos), 0, top + bh + dp(22), p);
            String d = dur > 0 ? time(dur) : "–:––";
            c.drawText(d, w - p.measureText(d), top + bh + dp(22), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            long dur = duration();
            if (dur <= 0 || controller == null) return false;
            getParent().requestDisallowInterceptTouchEvent(true);
            long at = (long) (Math.max(0, Math.min(1, e.getX() / getWidth())) * dur);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) dragAt = at;
            if (a == MotionEvent.ACTION_UP) {
                controller.getTransportControls().seekTo(at);
                dragAt = -1;
            }
            if (a == MotionEvent.ACTION_CANCEL) dragAt = -1;
            invalidate();
            return true;
        }
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private TextView mono(float sp, int colour) {
        TextView t = text(sp, colour, false);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.12f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView chip(String s, int colour) {
        TextView c = mono(11, colour);
        c.setText(s);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(14), dp(9), dp(14), dp(9));
        c.setBackground(box(0xFF161615, colour, 20));
        return c;
    }

    private TextView round(String s, int sizeDp, boolean filled) {
        TextView t = text(filled ? 26 : 20, filled ? 0xFFFFFFFF : ink, true);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(filled ? rust : 0xFF1A1A18);
        g.setStroke(dp(1.5f), filled ? rust : line);
        t.setBackground(g);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
}
