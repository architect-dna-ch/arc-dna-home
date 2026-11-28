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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
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
    private int bg = 0xFF0E0E0D, card = 0xFF1A1A18, ink = 0xFFF4F2EC, ink2 = 0xFFA5A5A0, ink3 = 0xFF6F6E69, line = 0xFF2C2C2A, rust = 0xFFD9472F;
    private FrameLayout root;
    private DiskView disk;
    private Seek seek;
    private TextView title, artist, source, playBtn, status;
    private LinearLayout queue;
    private Flow actions;
    private String actionKey = "";
    private DjPicker dj;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        themeColours();
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

        // Preset for the window size. A narrow floating window (HyperOS Workstation, split, or a
        // phone-sized pane) drops to a compact face — smaller disk, no volume lever, trimmed header —
        // so it reads cleanly instead of cramped. The activity re-runs onCreate when you resize it.
        final int wdp = getResources().getConfiguration().screenWidthDp;
        final boolean compact = wdp < 480 && (isInMultiWindowMode() || wdp < 360);

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
        TextView remB = chip("🔊 Tablet", ink2);
        remB.setOnClickListener(v -> startActivity(new Intent(this, RemoteActivity.class)));
        if (!compact) {
            head.addView(remB);
            View rg = new View(this);
            head.addView(rg, new LinearLayout.LayoutParams(dp(8), 1));
        }
        TextView djB = chip("🎧 DJ", rust);
        djB.setOnClickListener(v -> openDj());
        head.addView(djB);
        TextView close = mono(18, ink);
        close.setText("✕");
        close.setPadding(dp(16), dp(4), dp(2), dp(4));
        close.setOnClickListener(v -> finish());
        head.addView(close);
        col.addView(head);

        disk = new DiskView(this, new DiskView.Host() {
            @Override public boolean isPlaying() { return PlayerActivity.this.isPlaying(); }
            @Override public long position() { return PlayerActivity.this.position(); }
            @Override public long duration() { return PlayerActivity.this.duration(); }
        }, bg, line, rust, ink3);
        disk.setOnClickListener(v -> disk.nextEra());
        disk.setOnLongClickListener(v -> {
            disk.autoEra();
            android.widget.Toast.makeText(this, L.t("Ära wieder automatisch (pro Song)."), android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        // The medium with a volume lever standing beside it, like a mixer fader.
        FrameLayout deck = new FrameLayout(this);
        FrameLayout.LayoutParams dkl = new FrameLayout.LayoutParams(-1, -1);
        dkl.rightMargin = dp(compact ? 0 : 50);
        deck.addView(disk, dkl);
        Lever lever = new Lever();
        FrameLayout.LayoutParams lvl = new FrameLayout.LayoutParams(dp(46), -1, Gravity.END);
        lvl.topMargin = dp(10);
        lvl.bottomMargin = dp(28);
        if (!compact) deck.addView(lever, lvl);   // narrow window: drop the volume lever, give the disk the full width
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, dp(compact ? (wdp < 340 ? 150 : 215) : 320));
        kl.topMargin = dp(compact ? 10 : 18);
        col.addView(deck, kl);

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

        // Netflix-style extras: −10 s, speed, sleep timer, +10 s
        LinearLayout extras = new LinearLayout(this);
        extras.setGravity(Gravity.CENTER);
        TextView back10 = chip("⟲ 10", ink);
        back10.setOnClickListener(v -> MediaExtras.seekBy(controller, -10_000));
        TextView speed = chip("1×", ink2);
        speed.setOnClickListener(v -> speed.setText(MediaExtras.speedLabel(MediaExtras.nextSpeed(controller))));
        TextView sleepT = chip("☾ " + L.t("AUS"), ink2);
        sleepT.setOnClickListener(v -> {
            int left = MediaExtras.sleepLeft(), mins = 0;
            for (int m : MediaExtras.SLEEP_MIN) if (m > left) { mins = m; break; }
            MediaExtras.sleepIn(controller, mins);
            sleepT.setText(mins == 0 ? "☾ " + L.t("AUS") : "☾ " + mins + " MIN");
        });
        TextView fwd10 = chip("10 ⟳", ink);
        fwd10.setOnClickListener(v -> MediaExtras.seekBy(controller, 10_000));
        for (TextView x : new TextView[]{back10, speed, sleepT, fwd10}) {
            LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(0, -2, 1f);
            xl.leftMargin = xl.rightMargin = dp(3);
            extras.addView(x, xl);
        }
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
        el.topMargin = dp(16);
        col.addView(extras, el);

        actions = new Flow(this, dp(8));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(18);
        col.addView(actions, al);

        status = text(14, ink3, false);
        status.setPadding(0, dp(16), 0, 0);
        col.addView(status);

        TextView qh = mono(11, ink3);
        qh.setLetterSpacing(0.24f);
        qh.setText(L.t("— ALS NÄCHSTES"));
        qh.setPadding(0, dp(24), 0, dp(8));
        col.addView(qh);
        queue = new LinearLayout(this);
        queue.setOrientation(LinearLayout.VERTICAL);
        col.addView(queue);

        // Tablet across: the medium big on the left, title, controls and queue on the right.
        android.content.res.Configuration cfg = getResources().getConfiguration();
        if (cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenWidthDp >= 720) {
            col.removeView(deck);
            root.removeView(sv);
            LinearLayout across = new LinearLayout(this);
            FrameLayout left = new FrameLayout(this);
            left.setPadding(dp(28), dp(28), dp(12), dp(28));
            dkl.rightMargin = dp(64);
            lvl.width = dp(56);
            lvl.topMargin = dp(60);
            lvl.bottomMargin = dp(80);
            left.addView(deck, new FrameLayout.LayoutParams(-1, -1));
            across.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));
            across.addView(sv, new LinearLayout.LayoutParams(0, -1, 1.15f));
            root.addView(across, new FrameLayout.LayoutParams(-1, -1));
        }
        setContentView(root);
        barIcons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            sessions.addOnActiveSessionsChangedListener(changed, listenerName);
            pick(sessions.getActiveSessions(listenerName));
        } catch (SecurityException e) {
            pick(null);
            status.setText(L.t("ARC Player braucht den Benachrichtigungszugriff für ARC-DNA. Hier antippen."));
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
            title.setText(L.t("Nichts läuft"));
            artist.setText(L.t("🎧 DJ antippen – ein Buddy legt auf."));
            source.setText("");
            disk.set(null, L.t("LEER"), 0);
        } else {
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE), a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
            title.setText(t != null ? t : "–");
            artist.setText(a != null ? a : "");
            source.setText(appName(controller.getPackageName()).toUpperCase(Locale.GERMAN) + L.t(" ↗ · DORT ÖFFNEN"));
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            disk.set(art, t != null ? t : "", colourOf(art));
        }
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        paintPlay();
        // The app's own extra buttons: like, shuffle, repeat … whatever it offers.
        // Wrapped onto lines (no sideways scroll that hid "Like"), like first and in rust;
        // rebuilt only when the buttons change.
        List<PlaybackState.CustomAction> cas = new ArrayList<>();
        if (s != null) cas.addAll(s.getCustomActions());
        cas.sort((a, b) -> Boolean.compare(isLike(b), isLike(a)));
        StringBuilder key = new StringBuilder();
        for (PlaybackState.CustomAction ca : cas) key.append(ca.getAction()).append('|').append(ca.getName()).append('\n');
        if (!key.toString().equals(actionKey)) {
            actionKey = key.toString();
            actions.removeAllViews();
            for (PlaybackState.CustomAction ca : cas) {
                boolean like = isLike(ca);
                String name = String.valueOf(ca.getName()).toUpperCase(Locale.GERMAN);
                TextView c = chip(like ? "♥ " + name : name, like ? rust : ink2);
                c.setOnClickListener(v -> {
                    if (controller == null) return;
                    controller.getTransportControls().sendCustomAction(ca, null);
                    if (like) {
                        // The app answers a moment later: show the new state right away (dimmed until it does).
                        boolean wasLiked = name.contains("UNDO") || name.contains("ENTFERNEN") || name.contains("UNLIKE");
                        c.setText(wasLiked ? "♡ LIKE" : "♥ UNDO LIKE");
                        c.setAlpha(0.6f);       // rebuilt as soon as the app's new button name arrives
                    }
                });
                actions.addView(c);
            }
        }
        // The queue: tap = play from there.
        queue.removeAllViews();
        List<MediaSession.QueueItem> q = controller == null ? null : controller.getQueue();
        long active = s == null ? -1 : s.getActiveQueueItemId();
        if (q == null || q.isEmpty()) {
            TextView none = text(14, ink3, false);
            none.setText(controller == null ? "–" : L.t("Diese App zeigt ihre Warteschlange nicht."));
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
                none.setText(L.t("Ende der Warteschlange."));
                queue.addView(none);
            }
        }
        disk.invalidate();
    }

    private View queueRow(MediaSession.QueueItem it) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(10), dp(14), dp(10));
        r.setBackground(box(card, line, 12));
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
        if (MusicCard.openSession(this, controller.getSessionActivity())) return;
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
    static int colourOf(Bitmap b) {
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
    /** Volume lever: a tall fader for the music stream, with the A/B save points as notches.
     *  Drag the cap; tap a notch to jump there, long-press it to store the current level. */
    private final class Lever extends View {
        private final android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shown = -1;
        private boolean dragging;
        private long downAt;
        private float downY;
        private final Runnable poll = new Runnable() {
            @Override public void run() {
                invalidate();
                postDelayed(this, 400);           // follows the hardware keys too
            }
        };

        Lever() {
            super(PlayerActivity.this);
            setContentDescription(L.t("Lautstärke"));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(poll);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(poll);
            super.onDetachedFromWindow();
        }

        private int max() {
            return Math.max(1, audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        }

        private float level() {
            return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) max();
        }

        private int preset(String key) {
            return getSharedPreferences("home", MODE_PRIVATE).getInt("vol" + key, key.equals("A") ? 70 : 25);
        }

        private float top() { return dp(26); }
        private float bottom() { return getHeight() - dp(30); }

        private float yOf(float f) {
            return bottom() - (bottom() - top()) * f;
        }

        private float fOf(float y) {
            return Math.max(0, Math.min(1, (bottom() - y) / Math.max(1, bottom() - top())));
        }

        private void set(float f) {
            int v = Math.round(f * max());
            if (v != audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) {
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0);
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
            invalidate();
        }

        /** The A/B notch under the finger, if any. */
        private String notchAt(float y) {
            for (String k : new String[]{"A", "B"})
                if (Math.abs(yOf(preset(k) / 100f) - y) < dp(16)) return k;
            return null;
        }

        private final Runnable hold = () -> {
            String k = notchAt(downY);
            if (k == null || dragging) return;
            int pct = Math.round(100 * level());
            getSharedPreferences("home", MODE_PRIVATE).edit().putInt("vol" + k, pct).apply();
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            android.widget.Toast.makeText(PlayerActivity.this, k + L.t(" gespeichert: ") + pct + "%", android.widget.Toast.LENGTH_SHORT).show();
            downAt = 0;
            invalidate();
        };

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                downY = e.getY();
                downAt = SystemClock.uptimeMillis();
                dragging = Math.abs(yOf(level()) - downY) < dp(26);
                postDelayed(hold, 550);
                return true;
            }
            if (a == MotionEvent.ACTION_MOVE) {
                if (!dragging && Math.abs(e.getY() - downY) > dp(8)) dragging = true;
                if (dragging) {
                    removeCallbacks(hold);
                    set(fOf(e.getY()));
                }
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(hold);
                if (a == MotionEvent.ACTION_UP && !dragging && downAt != 0) {
                    String k = notchAt(downY);
                    set(k != null ? preset(k) / 100f : fOf(downY));
                }
                dragging = false;
                invalidate();
                return true;
            }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas c) {
            float f = level();
            shown = shown < 0 || dragging ? f : shown + (f - shown) * 0.35f;
            if (Math.abs(shown - f) > 0.002f) postInvalidateOnAnimation();
            float cx = getWidth() / 2f, t = top(), b = bottom(), y = yOf(shown);
            // Slot with the filled part in rust.
            p.setStyle(Paint.Style.FILL);
            p.setColor(line);
            c.drawRoundRect(cx - dp(3), t, cx + dp(3), b, dp(3), dp(3), p);
            p.setColor(rust);
            c.drawRoundRect(cx - dp(3), y, cx + dp(3), b, dp(3), dp(3), p);
            // Scale ticks.
            p.setColor(ink3);
            for (int i = 0; i <= 10; i++) {
                float ty = yOf(i / 10f), w = i % 5 == 0 ? dp(9) : dp(5);
                c.drawRect(cx - dp(8) - w, ty - dp(0.6f), cx - dp(8), ty + dp(0.6f), p);
            }
            // A/B save points as notches on the right.
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(10));
            p.setTextAlign(Paint.Align.LEFT);
            for (String k : new String[]{"A", "B"}) {
                float ny = yOf(preset(k) / 100f);
                p.setColor(ink2);
                c.drawRect(cx + dp(6), ny - dp(1), cx + dp(13), ny + dp(1), p);
                c.drawText(k, cx + dp(15), ny + dp(3.5f), p);
            }
            // The lever cap: a grippy block with a centre line.
            float cw = dp(17), ch = dp(11);
            p.setColor(card);
            c.drawRoundRect(cx - cw, y - ch, cx + cw, y + ch, dp(5), dp(5), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(dragging ? rust : ink2);
            c.drawRoundRect(cx - cw, y - ch, cx + cw, y + ch, dp(5), dp(5), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(rust);
            c.drawRect(cx - cw + dp(4), y - dp(1), cx + cw - dp(4), y + dp(1), p);
            p.setColor(ink3);
            for (int i = -1; i <= 1; i += 2)
                c.drawRect(cx - cw + dp(6), y + i * dp(5) - dp(0.5f), cx + cw - dp(6), y + i * dp(5) + dp(0.5f), p);
            // Labels: VOL on top, the percent below.
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(ink3);
            p.setTextSize(dp(9));
            c.drawText("VOL", cx, t - dp(12), p);
            p.setColor(dragging ? rust : ink);
            p.setTextSize(dp(11));
            c.drawText(Math.round(100 * f) + "%", cx, b + dp(20), p);
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
        if (s >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);     // not 183:36
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
        c.setBackground(box(card, colour, 20));
        return c;
    }

    private TextView round(String s, int sizeDp, boolean filled) {
        TextView t = text(filled ? 26 : 20, filled ? 0xFFFFFFFF : ink, true);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(filled ? rust : card);
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
    /** Day or night like the system (they were always dark). */
    private void themeColours() {
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!night) {
            bg = 0xFFFAFAF8;
            card = 0xFFFFFFFF;
            line = 0xFFE0E0D8;
            ink = 0xFF1A1A1A;
            ink2 = 0xFF5A5A58;
            ink3 = 0xFF9A9A98;
            rust = 0xFFB5271E;
        }
    }

    /** Dark bar icons on the light day background (after setContentView). */
    private void barIcons() {
        if (Build.VERSION.SDK_INT < 30 || getWindow().getInsetsController() == null) return;
        boolean light = bg != 0xFF0E0E0D && bg != 0xFF141413;
        int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(light ? bits : 0, bits);
    }


    /** A thumbs up / like / favourite button (by name or action id). */
    private static boolean isLike(PlaybackState.CustomAction ca) {
        String n = (ca.getName() + " " + ca.getAction()).toLowerCase(Locale.ROOT);
        return (n.contains("like") || n.contains("thumbs_up") || n.contains("thumb") || n.contains("gefällt") || n.contains("fav")
            || n.contains("heart")) && !n.contains("dislike") && !n.contains("down") && !n.contains("nicht");
    }

    /** Lays children left to right and wraps onto new lines. */
    static final class Flow extends android.view.ViewGroup {
        private final int gap;

        Flow(android.content.Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + v.getMeasuredWidth() > w) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                x += v.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, v.getMeasuredHeight());
            }
            setMeasuredDimension(w, y + rowH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int cw = v.getMeasuredWidth(), ch = v.getMeasuredHeight();
                if (x > 0 && x + cw > w) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                v.layout(x, y, x + cw, y + ch);
                x += cw + gap;
                rowH = Math.max(rowH, ch);
            }
        }
    }


    /** The era of a song (the one picked in the player, else one per title): also the home card's button. */
    static int eraFor(android.content.Context c, String title) {
        int pinned = c.getSharedPreferences("player", MODE_PRIVATE).getInt("era", -1);
        return pinned >= 0 ? pinned : Math.abs(String.valueOf(title).hashCode()) % 5;
    }

}
