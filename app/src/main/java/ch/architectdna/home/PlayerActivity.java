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
        disk.setOnClickListener(v -> disk.nextEra());
        disk.setOnLongClickListener(v -> {
            disk.autoEra();
            android.widget.Toast.makeText(this, "Ära wieder automatisch (pro Song).", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, dp(320));
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
        paintPlay();
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

    /** Playing (or about to: buffering, connecting) → pause sign; otherwise play. */
    private boolean isPlaying() {
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_CONNECTING
            || st == PlaybackState.STATE_FAST_FORWARDING || st == PlaybackState.STATE_REWINDING;
    }

    private long toggledAt;

    private void paintPlay() {
        if (SystemClock.uptimeMillis() - toggledAt < 700) return;      // give the app a moment to follow the tap
        String want = isPlaying() ? "❚❚" : "▶";
        if (!want.contentEquals(playBtn.getText())) playBtn.setText(want);
    }

    private void togglePlay() {
        if (controller == null) {
            openDj();
            return;
        }
        if (isPlaying()) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
        playBtn.setText(isPlaying() ? "▶" : "❚❚");      // answer at once; the tick corrects it if the app disagrees
        toggledAt = SystemClock.uptimeMillis();
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
            paintPlay();          // follows the real state, also when the app changes it without telling us
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

        /* ---- Eras: jukebox, vinyl, cassette, CD, floppy. Each song gets one; tap = next, hold = automatic again ---- */

        static final int JUKEBOX = 0, VINYL = 1, CASSETTE = 2, CD = 3, FLOPPY = 4;
        private final String[] ERA = {"JUKEBOX · 50ER", "VINYL · 70ER", "KASSETTE · 80ER", "CD · 90ER", "DISKETTE · COMPUTER"};
        private float spin, arm, lastT;

        int era() {
            int pinned = getSharedPreferences("player", MODE_PRIVATE).getInt("era", -1);
            return pinned >= 0 ? pinned : Math.abs(label.hashCode()) % 5;
        }

        void nextEra() {
            getSharedPreferences("player", MODE_PRIVATE).edit().putInt("era", (era() + 1) % 5).apply();
            changedAt = SystemClock.uptimeMillis();
            invalidate();
        }

        void autoEra() {
            getSharedPreferences("player", MODE_PRIVATE).edit().putInt("era", -1).apply();
            changedAt = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float t = now / 1000f, dt = lastT == 0 ? 0 : Math.min(0.05f, t - lastT);
            lastT = t;
            boolean playing = isPlaying();
            if (playing) spin += dt * 200;            // ~33 rpm look
            arm += ((playing ? 1 : 0) - arm) * Math.min(1, dt * 4);
            int e = era();
            float h = getHeight() - dp(18), size = Math.min(getWidth(), h) * 0.94f, x = (getWidth() - size) / 2, y = (h - size) / 2;
            float in = Math.min(1, (now - changedAt) / 380f);
            in = 1 - (1 - in) * (1 - in);
            if (e == FLOPPY) {
                drawFloppy(c);
            } else {
                c.save();
                c.translate(0, (1 - in) * dp(60));
                if (e == VINYL) vinyl(c, x, y, size);
                else if (e == CASSETTE) cassette(c, x, y, size, t);
                else if (e == CD) cd(c, x, y, size);
                else jukebox(c, x, y, size, t);
                c.restore();
            }
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(9));
            p.setColor(ink3);
            p.setTextAlign(Paint.Align.CENTER);
            p.setAlpha(255);
            c.drawText("♪ " + ERA[e] + " · TIPPEN = ÄRA WECHSELN", getWidth() / 2f, getHeight() - dp(2), p);
            p.setTextAlign(Paint.Align.LEFT);
            if (e != FLOPPY || in < 1) postInvalidateOnAnimation();
        }

        /** The cover into a rect (centre crop), or into a circle when round. */
        private void cover(Canvas c, android.graphics.RectF r, boolean round) {
            if (art == null) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(shell);
                if (round) c.drawOval(r, p);
                else c.drawRect(r, p);
                return;
            }
            c.save();
            if (round) {
                Path clip = new Path();
                clip.addOval(r, Path.Direction.CW);
                c.clipPath(clip);
            }
            float sw = art.getWidth(), sh = art.getHeight(), k = Math.max(r.width() / sw, r.height() / sh);
            float cw = r.width() / k, ch = r.height() / k;
            android.graphics.Rect src = new android.graphics.Rect((int) ((sw - cw) / 2), (int) ((sh - ch) / 2), (int) ((sw + cw) / 2), (int) ((sh + ch) / 2));
            c.drawBitmap(art, src, r, p);
            c.restore();
        }

        /** Vinyl: grooves, the cover as the centre label, turning while it plays; the tonearm swings on. */
        private void vinyl(Canvas c, float x, float y, float s) {
            float R = s * 0.46f, cx = x + s * 0.47f, cy = y + s * 0.5f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0B0B0B);
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            for (float r = R * 0.36f; r < R * 0.97f; r += dp(2.2f)) {
                p.setColor(0x22FFFFFF);
                c.drawCircle(cx, cy, r, p);
            }
            // Light catching the grooves (stays put while the record turns).
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(R * 0.5f);
            p.setColor(0x14FFFFFF);
            android.graphics.RectF o = new android.graphics.RectF(cx - R * 0.68f, cy - R * 0.68f, cx + R * 0.68f, cy + R * 0.68f);
            c.drawArc(o, -60, 30, false, p);
            c.drawArc(o, 120, 30, false, p);
            c.save();
            c.rotate(spin, cx, cy);
            cover(c, new android.graphics.RectF(cx - R * 0.34f, cy - R * 0.34f, cx + R * 0.34f, cy + R * 0.34f), true);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFF4F2EC);
            c.drawCircle(cx + R * 0.22f, cy, dp(2.5f), p);          // a mark on the label shows the turning
            c.restore();
            p.setColor(0xFF0B0B0B);
            c.drawCircle(cx, cy, dp(4), p);
            // Tonearm: pivot top right, swings onto the record while playing.
            float px = x + s * 0.97f, py = y + s * 0.1f, len = s * 0.62f;
            double a = Math.toRadians(100 + 22 * arm);
            float ex = px + (float) Math.cos(a) * len, ey = py + (float) Math.sin(a) * len;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(5));
            p.setColor(0xFFC4C4BE);
            c.drawLine(px, py, ex, ey, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF8A8A86);
            c.drawCircle(px, py, dp(12), p);
            c.save();
            c.rotate((float) Math.toDegrees(a) + 90, ex, ey);
            p.setColor(rust);
            c.drawRoundRect(ex - dp(7), ey - dp(4), ex + dp(7), ey + dp(14), dp(3), dp(3), p);
            c.restore();
        }

        /** Cassette: the cover as the sticker, two reels turning, the tape wanders left → right with the song. */
        private void cassette(Canvas c, float x, float y, float s, float t) {
            float w = s, h = s * 0.64f, top = y + (s - h) / 2;
            android.graphics.RectF b = new android.graphics.RectF(x, top, x + w, top + h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(shell);
            c.drawRoundRect(b, dp(10), dp(10), p);
            // Sticker: cover on the left, the title on lines.
            android.graphics.RectF st = new android.graphics.RectF(x + w * 0.06f, top + h * 0.08f, x + w * 0.94f, top + h * 0.62f);
            p.setColor(0xFFF4F2EC);
            c.drawRoundRect(st, dp(6), dp(6), p);
            float cs = st.height() - dp(12);
            cover(c, new android.graphics.RectF(st.left + dp(6), st.top + dp(6), st.left + dp(6) + cs, st.top + dp(6) + cs), false);
            p.setColor(0xFF1A1A1A);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(12));
            String l = label.length() > 22 ? label.substring(0, 21) + "…" : label;
            c.drawText(l.toUpperCase(Locale.GERMAN), st.left + cs + dp(14), st.top + dp(22), p);
            p.setColor(0x33B5271E);
            for (int i = 1; i <= 3; i++) c.drawRect(st.left + cs + dp(14), st.top + dp(22) + i * dp(12), st.right - dp(10), st.top + dp(23) + i * dp(12), p);
            // Window with the two reels.
            android.graphics.RectF win = new android.graphics.RectF(x + w * 0.22f, top + h * 0.40f, x + w * 0.78f, top + h * 0.6f);
            long dur = duration();
            float f = dur > 0 ? Math.min(1, position() / (float) dur) : 0;
            float rMin = h * 0.07f, rMax = h * 0.17f;
            float[] reelX = {x + w * 0.32f, x + w * 0.68f};
            float[] tape = {rMin + (rMax - rMin) * (1 - f), rMin + (rMax - rMin) * f};
            float ry = top + h * 0.5f;
            p.setColor(0xFF2A1A12);
            for (int i = 0; i < 2; i++) c.drawCircle(reelX[i], ry, tape[i], p);
            p.setColor(0x881A1A1A);
            c.drawRoundRect(win, dp(6), dp(6), p);
            for (int i = 0; i < 2; i++) {
                p.setColor(0xFFF4F2EC);
                c.drawCircle(reelX[i], ry, rMin * 0.8f, p);
                c.save();
                c.rotate(spin * (i == 0 ? 1.3f : 1f), reelX[i], ry);
                p.setColor(0xFF1A1A1A);
                for (int k = 0; k < 6; k++) {
                    c.rotate(60, reelX[i], ry);
                    c.drawRect(reelX[i] - dp(1.5f), ry - rMin * 0.75f, reelX[i] + dp(1.5f), ry - rMin * 0.4f, p);
                }
                c.restore();
            }
            // The trapezoid at the bottom with its holes.
            path.reset();
            path.moveTo(x + w * 0.2f, top + h);
            path.lineTo(x + w * 0.26f, top + h * 0.8f);
            path.lineTo(x + w * 0.74f, top + h * 0.8f);
            path.lineTo(x + w * 0.8f, top + h);
            path.close();
            p.setColor(0x33000000);
            c.drawPath(path, p);
            p.setColor(0xFF1A1A1A);
            c.drawCircle(x + w * 0.38f, top + h * 0.9f, dp(3), p);
            c.drawCircle(x + w * 0.62f, top + h * 0.9f, dp(3), p);
        }

        /** CD: the case with the cover, the disc sliding out to the right, rainbow shimmer, spinning. */
        private void cd(Canvas c, float x, float y, float s) {
            float cs = s * 0.74f, top = y + (s - cs) / 2, R = cs * 0.46f;
            float cx = x + cs * 0.62f + R * 0.6f, cy = top + cs / 2;
            p.setStyle(Paint.Style.FILL);
            c.save();
            c.rotate(spin * 1.5f, cx, cy);
            p.setShader(new android.graphics.SweepGradient(cx, cy, new int[]{0xFFD8D8D8, 0xFFB8E0FF, 0xFFF0B8FF, 0xFFFFF0B0, 0xFFB8FFD0,
                0xFFD8D8D8}, null));
            c.drawCircle(cx, cy, R, p);
            p.setShader(null);
            c.restore();
            p.setColor(0x55FFFFFF);
            c.drawCircle(cx, cy, R * 0.32f, p);
            p.setColor(bg);
            c.drawCircle(cx, cy, R * 0.12f, p);
            // The jewel case on top.
            android.graphics.RectF caseR = new android.graphics.RectF(x, top, x + cs, top + cs);
            cover(c, caseR, false);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0x88C4C4BE);
            c.drawRect(caseR, p);
            p.setColor(0x33FFFFFF);
            p.setStrokeWidth(dp(10));
            c.drawLine(x + dp(5), top, x + dp(5), top + cs, p);          // the hinge
            p.setStyle(Paint.Style.FILL);
        }

        /** Jukebox: the neon arch in changing colours, the cover in the window, bubbles rising in the side tubes. */
        private void jukebox(Canvas c, float x, float y, float s, float t) {
            float w = s * 0.78f, left = x + (s - w) / 2, top = y, bottom = y + s;
            path.reset();
            path.moveTo(left, bottom);
            path.lineTo(left, top + w / 2);
            path.arcTo(new android.graphics.RectF(left, top, left + w, top + w), 180, 180);
            path.lineTo(left + w, bottom);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(0, top, 0, bottom, new int[]{0xFF5A2A14, 0xFF8B4A22, 0xFF3A1A0C}, null,
                android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(6));
            p.setColor(android.graphics.Color.HSVToColor(new float[]{(t * 40) % 360, 0.8f, 1f}));
            c.drawPath(path, p);
            p.setStrokeWidth(dp(2));
            p.setColor(0xFFFFE6A8);
            c.drawPath(path, p);
            // Window with the cover.
            android.graphics.RectF win = new android.graphics.RectF(left + w * 0.18f, top + w * 0.22f, left + w * 0.82f, top + w * 0.22f + w * 0.64f);
            cover(c, win, false);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0xFFFFE6A8);
            c.drawRect(win, p);
            // Bubble tubes left and right.
            p.setStyle(Paint.Style.FILL);
            for (int side = 0; side < 2; side++) {
                float tx = side == 0 ? left + w * 0.08f : left + w * 0.92f;
                p.setColor(0x66FF8A3C);
                c.drawRoundRect(tx - dp(6), top + w * 0.5f, tx + dp(6), bottom - dp(10), dp(6), dp(6), p);
                for (int i = 0; i < 5; i++) {
                    float k = ((t * 0.4f + i / 5f + side * 0.1f) % 1f);
                    p.setColor(0xCCFFF2C0);
                    c.drawCircle(tx, bottom - dp(14) - k * (bottom - dp(24) - top - w * 0.5f), dp(3), p);
                }
            }
            // The selection strip with the title.
            p.setColor(0xFFF4F2EC);
            float sy = win.bottom + dp(12);
            c.drawRoundRect(left + w * 0.18f, sy, left + w * 0.82f, sy + dp(24), dp(4), dp(4), p);
            p.setColor(0xFFB5271E);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(11));
            p.setTextAlign(Paint.Align.CENTER);
            String l = label.length() > 24 ? label.substring(0, 23) + "…" : label;
            c.drawText("A1 · " + l.toUpperCase(Locale.GERMAN), left + w / 2, sy + dp(16), p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        private void drawFloppy(Canvas c) {
            float h = getHeight() - dp(18), size = Math.min(getWidth(), h) * 0.94f, x = (getWidth() - size) / 2, y = (h - size) / 2;
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
            // The bar is inset by the knob's halo, so the knob is whole at 0:00 and at the end.
            float pad = dp(13), w = getWidth(), l = pad, r = w - pad, top = dp(12), bh = dp(8);
            long dur = duration(), pos = dragAt >= 0 ? dragAt : position();
            float f = dur > 0 ? Math.min(1, pos / (float) dur) : 0, head = l + (r - l) * f;
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(line);
            c.drawRoundRect(l, top, r, top + bh, bh / 2, bh / 2, p);
            p.setColor(rust);
            c.drawRoundRect(l, top, Math.max(l + bh, head), top + bh, bh / 2, bh / 2, p);
            float k = (SystemClock.uptimeMillis() % 3600) / 3600f, band = dp(70), sx = l - band + (r - l + 2 * band) * k;
            p.setShader(new LinearGradient(sx - band, 0, sx + band, 0,
                new int[]{0x00BFE3FF, 0x88EAF6FF, 0xCCFFFFFF, 0x88BFE3FF, 0x00BFE3FF}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(l, top, r, top + bh, bh / 2, bh / 2, p);
            p.setShader(null);
            p.setColor(0x553C8CFF);
            c.drawCircle(head, top + bh / 2, dp(12), p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(head, top + bh / 2, dp(7), p);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(11));
            p.setColor(ink3);
            c.drawText(time(pos), l, top + bh + dp(24), p);
            String d = dur > 0 ? time(dur) : "–:––";
            c.drawText(d, r - p.measureText(d), top + bh + dp(24), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            long dur = duration();
            if (dur <= 0 || controller == null) return false;
            getParent().requestDisallowInterceptTouchEvent(true);
            float pad = dp(13);
            long at = (long) (Math.max(0, Math.min(1, (e.getX() - pad) / (getWidth() - 2 * pad))) * dur);
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
