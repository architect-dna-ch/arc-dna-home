package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen's music card: whatever is playing (via media sessions, needs notification
 * access), play/pause/skip (works even without access, via media keys), the output device such
 * as a JBL speaker with when it connected, a music volume slider, and an audio spectrum
 * (Visualizer on the output mix, needs the microphone permission; nothing is recorded).
 */
final class MusicCard {

    interface Host {
        void open(Intent intent);

        void askAudioPermission();

        /** Overall loudness 0..1 roughly 20 times a second, for the seismograph. */
        void onLevel(float level);

        /** Short message for the fox's bubble. */
        void say(String text);
    }

    private static final int BARS = 28;

    private final Activity activity;
    private final Host host;
    private final int ink, ink2, ink3, rust, card, line;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final MediaSessionManager sessions;
    private final ComponentName listener;

    private LinearLayout root;
    private ImageView art, playButton;
    private TextView title, artist, source, output, accessHint, vizHint;
    private SeekBar volume;
    /** A touch that started in the slider band of the card. */
    private boolean sliding;
    private Spectrum spectrum;

    private MediaController controller;
    private Visualizer visualizer;
    private boolean started;
    private String btName;
    private long btSince;

    MusicCard(Activity activity, int[] colours, Host host) {
        this.activity = activity;
        this.host = host;
        ink = colours[0];
        ink2 = colours[1];
        ink3 = colours[2];
        rust = colours[3];
        card = colours[4];
        line = colours[5];
        audio = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
        sessions = (MediaSessionManager) activity.getSystemService(Activity.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(activity, MediaListener.class);
    }

    /* ---------------- View ---------------- */

    View view() {
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));
        root.setBackground(box(card, line, 8));
        root.setForeground(frame);

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(box(card, line, 6));
        art.setClipToOutline(true);
        top.addView(art, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        title = label(15, ink, true);
        title.setOnClickListener(v -> openPlayer());
        artist = label(13, ink2, false);
        source = label(10, ink3, true);
        source.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        source.setLetterSpacing(0.12f);
        text.addView(title);
        text.addView(artist);
        text.addView(source);
        top.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top);

        spectrum = new Spectrum();
        spectrum.setOnClickListener(v -> {
            if (!hasAudioPermission()) {
                host.askAudioPermission();
                return;
            }
            // Tap: waveform (like a DJ deck) ↔ bars.
            android.content.SharedPreferences p = activity.getSharedPreferences("home", Activity.MODE_PRIVATE);
            p.edit().putBoolean("vizBars", !p.getBoolean("vizBars", false)).apply();
            spectrum.invalidate();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(46));
        slp.topMargin = dp(12);
        root.addView(spectrum, slp);

        // DJ read-out: the phase you're in, quick jumps, and the track timeline with the phases heard so far.
        LinearLayout phaseRow = new LinearLayout(activity);
        phaseRow.setGravity(Gravity.CENTER_VERTICAL);
        phaseLabel = label(11, 0xFFFFFFFF, true);
        phaseLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        phaseLabel.setLetterSpacing(0.1f);
        phaseLabel.setPadding(dp(10), dp(4), dp(10), dp(4));
        paintPhase(PhaseBar.Phase.GROOVE);
        phaseRow.addView(phaseLabel);
        phaseRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        phaseRow.addView(jumpChip("→ 0:20", () -> seekTo(20_000)));
        phaseRow.addView(jumpChip("+30 S", () -> seekTo(currentPosition() + 30_000)));
        LinearLayout.LayoutParams prl = new LinearLayout.LayoutParams(-1, -2);
        prl.topMargin = dp(10);
        root.addView(phaseRow, prl);
        phaseBar = new PhaseBar(activity, ink, ink3, this::seekTo);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(-1, dp(34));
        pbl.topMargin = dp(8);
        root.addView(phaseBar, pbl);

        vizHint = label(10, ink3, true);
        vizHint.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        vizHint.setLetterSpacing(0.12f);
        vizHint.setText("VISUALIZER: HIER TIPPEN ZUM AKTIVIEREN");
        vizHint.setOnClickListener(v -> host.askAudioPermission());
        root.addView(vizHint);

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        controls.addView(control(R.drawable.ic_prev, KeyEvent.KEYCODE_MEDIA_PREVIOUS), weight());
        playButton = control(R.drawable.ic_play, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        controls.addView(playButton, weight());
        controls.addView(control(R.drawable.ic_next, KeyEvent.KEYCODE_MEDIA_NEXT), weight());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(44));
        clp.topMargin = dp(6);
        root.addView(controls, clp);

        output = label(11, ink2, true);
        output.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        output.setLetterSpacing(0.08f);
        // Speaker line: tap = Android's output switcher (JBL ↔ phone …), long-press = the JBL app or Bluetooth settings.
        output.setOnClickListener(v -> showOutputSwitcher());
        output.setOnLongClickListener(v -> {
            openSpeakerApp();
            return true;
        });
        // Speaker row: the line plus "ANC ↗" while a JBL is connected (ANC lives in JBL's own app).
        // Device card: a battery ring with the device drawn inside, name + details, and two buttons.
        outputRow = new LinearLayout(activity);
        outputRow.setGravity(Gravity.CENTER_VERTICAL);
        outputRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        outputRow.setBackground(box(card, line, 8));
        gauge = new DeviceGauge();
        gauge.setOnClickListener(v -> showOutputSwitcher());
        outputRow.addView(gauge, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout devTexts = new LinearLayout(activity);
        devTexts.setOrientation(LinearLayout.VERTICAL);
        devTexts.setPadding(dp(12), 0, dp(6), 0);
        deviceName = label(14, ink, true);
        deviceName.setMaxLines(1);
        devTexts.addView(deviceName);
        output.setMaxLines(1);
        devTexts.addView(output);
        outputRow.addView(devTexts, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.VERTICAL);
        TextView sw = label(11, ink2, true);
        sw.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        sw.setText("⇄ AUSGANG");
        sw.setPadding(dp(10), dp(5), dp(10), dp(5));
        sw.setBackground(box(card, line, 14));
        sw.setOnClickListener(v -> showOutputSwitcher());
        buttons.addView(sw);
        anc = label(11, rust, true);
        anc.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        anc.setText("ANC ↗");
        anc.setPadding(dp(10), dp(5), dp(10), dp(5));
        anc.setBackground(box(card, rust, 14));
        anc.setVisibility(View.GONE);
        anc.setOnClickListener(v -> openSpeakerApp());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(6);
        anc.setGravity(Gravity.CENTER);
        buttons.addView(anc, al);
        outputRow.addView(buttons);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = dp(8);
        root.addView(outputRow, olp);

        volume = new SeekBar(activity);
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(rust));
        volume.setThumbTintList(android.content.res.ColorStateList.valueOf(rust));
        volume.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(ink3));
        volume.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, dp(36));
        root.addView(volume, vlp);

        accessHint = label(12, ink, true);
        accessHint.setText("Titel & Cover von Spotify & Co. anzeigen → Benachrichtigungszugriff für ARC-DNA erlauben");
        accessHint.setLineSpacing(0, 1.2f);
        accessHint.setPadding(0, dp(8), 0, 0);
        accessHint.setOnClickListener(v -> host.open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(accessHint);

        // Only the cover opens the playing app; the rest of the card belongs to the slider: a touch
        // anywhere in the band around it (device line above, card edge below) moves the volume.
        art.setOnClickListener(v -> openPlayer());
        art.setContentDescription("Player-App öffnen");
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) sliding = e.getY() >= outputRow.getBottom() + dp(2);
            if (!sliding) return false;
            // Hand the touch to the slider in its own coordinates, so the thumb lands under the finger.
            MotionEvent m = MotionEvent.obtain(e);
            m.setLocation(e.getX() - volume.getLeft(), volume.getHeight() / 2f);
            volume.dispatchTouchEvent(m);
            m.recycle();
            return true;
        });

        // Page dots (player · stats) in the top row; swipe the card left / right to switch.
        dots = label(11, ink3, true);
        dots.setPadding(dp(8), 0, 0, 0);
        top.addView(dots);
        for (int i = 0; i < root.getChildCount(); i++) playerViews.add(root.getChildAt(i));
        stats = new LinearLayout(activity);
        stats.setOrientation(LinearLayout.VERTICAL);
        stats.setVisibility(View.GONE);
        // Takes up whatever height the card keeps from the player page; the chart grows into it.
        root.addView(stats, new LinearLayout.LayoutParams(-1, 0, 1f));
        paintDots();

        renderTrack();
        return root;
    }

    /* ---------------- DJ read-out: phases, timeline, jumps ---------------- */

    private TextView phaseLabel;
    private PhaseBar phaseBar;
    private PhaseBar.Phase phase = PhaseBar.Phase.GROOVE, candidate = PhaseBar.Phase.GROOVE;
    private long candidateSince;
    private float loS, midS, hiS, loL, midL, hiL;   // fast (≈0.3 s) and slow (≈8 s) band averages
    private float totM, hiM;                        // mid speed (≈2 s)
    private final float[] trend = new float[8];     // ≈4 s of 2-s levels, every 0.5 s
    private int trendHead;
    private long trendAt, phaseSince;
    private int warm;
    private boolean built;                          // a BUILD came first: the next bass surge is a DROP
    private String phaseTrack = "";

    /** The phase map belongs to one song: title + artist (nothing is kept without a real title). */
    private void followTrack() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String key = t == null ? "" : t + "\u0001" + (a == null ? "" : a);
        if (!key.equals(phaseTrack)) {
            phaseTrack = key;
            warm = 0;
            lastVocalAt = System.currentTimeMillis();
            phaseBar.newTrack(key);
        }
    }

    private TextView jumpChip(String text, Runnable action) {
        TextView chip = label(11, ink2, true);
        chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chip.setText(text);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(box(card, line, 14));
        chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void paintPhase(PhaseBar.Phase p) {
        phaseLabel.setText(p.label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.colour);
        bg.setCornerRadius(dp(12));
        phaseLabel.setBackground(bg);
    }

    long currentPosition() {
        if (controller == null || controller.getPlaybackState() == null) return -1;
        PlaybackState st = controller.getPlaybackState();
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        return pos;
    }

    private long duration() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        return m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    void seekTo(long ms) {
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        if (st == null || (st.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) {
            DiagLog.log("seek not possible: " + (controller == null ? "no media session" : "app doesn't allow seeking"));
            android.widget.Toast.makeText(activity, controller == null
                ? "Keine Musik-App verbunden (Benachrichtigungszugriff?)"
                : appName(controller.getPackageName()) + " erlaubt kein Springen im Titel.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long d = duration();
        controller.getTransportControls().seekTo(Math.max(0, d > 0 ? Math.min(ms, d - 1000) : ms));
    }

    /**
     * Reads the phase from the live sound: BREAK when it gets much quieter, BUILD when the highs rise
     * while the bass thins out, DROP when the bass surges (above all right after a build), VOCAL when
     * the mids lead. A phase has to hold ~0.6 s before it shows, so it doesn't flicker.
     */
    private void readPhase(float lo, float mid, float hi) {
        float tot = lo + mid + hi;
        // Three speeds (≈60 frames/s): fast ≈0.3 s, mid ≈2 s, slow ≈8 s.
        loS += (lo - loS) * 0.06f; midS += (mid - midS) * 0.06f; hiS += (hi - hiS) * 0.06f;
        totM += (tot - totM) * 0.008f; hiM += (hi - hiM) * 0.008f;
        // Warm-up: the slow averages catch up quickly at first, so a song's start doesn't read as a drop.
        float slow = warm < 240 ? 0.05f : 0.002f;
        warm++;
        loL += (lo - loL) * slow; midL += (mid - midL) * slow; hiL += (hi - hiL) * slow;
        float totS = loS + midS + hiS, totL = loL + midL + hiL;
        // A rising trend: the 2-s level against itself 4 s ago (sampled every ~0.5 s).
        long t = System.currentTimeMillis();
        if (t - trendAt > 500) {
            trendAt = t;
            trend[trendHead] = totM;
            trendHead = (trendHead + 1) % trend.length;
        }
        float fourAgo = trend[trendHead];                 // the oldest sample ≈ 4 s back
        boolean rising = fourAgo > 0.02f && totM > fourAgo * 1.12f && hiM > hiL * 1.06f;
        boolean bassUp = loS > loL * 1.15f;
        PhaseBar.Phase p;
        if (totL < 0.04f || warm < 240) p = PhaseBar.Phase.GROOVE;
        else if ((totS > totL * 1.18f && bassUp) || (built && totS > totL * 1.08f && bassUp) || totS > totL * 1.4f) {
            p = PhaseBar.Phase.DROP;                      // chorus / drop: clearly fuller than usual
        } else if (totM < totL * 0.7f) p = PhaseBar.Phase.BREAK;
        else if (rising) p = PhaseBar.Phase.BUILD;        // pre-chorus: energy climbing for seconds
        else if (midS > totS * 0.42f) p = PhaseBar.Phase.VOCAL;
        else p = PhaseBar.Phase.GROOVE;
        // No voice for 20 s and nothing else going on: an instrumental stretch.
        if (midS > totS * 0.42f) lastVocalAt = t;
        if (p == PhaseBar.Phase.GROOVE && warm >= 240 && t - lastVocalAt > 20_000) p = PhaseBar.Phase.INSTRUMENTAL;
        // A drop holds at least 2 s, so a chorus doesn't flicker between beats.
        if (phase == PhaseBar.Phase.DROP && p != PhaseBar.Phase.DROP && t - phaseSince < 2000) p = PhaseBar.Phase.DROP;
        long now = System.currentTimeMillis();
        if (p != candidate) {
            candidate = p;
            candidateSince = now;
        } else if (phaseLabel != null && "KEIN SIGNAL".contentEquals(phaseLabel.getText())) {
            paintPhase(phase);
        } else if (p != phase && now - candidateSince > 600) {
            phase = p;
            phaseSince = now;
            if (p == PhaseBar.Phase.BUILD) built = true;
            if (p == PhaseBar.Phase.DROP || p == PhaseBar.Phase.BREAK) built = false;
            if (phaseLabel != null) paintPhase(p);
        }
        // Every kick nods the ring animal along; lastBeat keeps it to real beats (≥ 0.25 s apart).
        if (lo > loS * 1.25f && lo > 0.04f && now - lastBeat > 380) {
            lastBeat = now;
            if (gauge != null) gauge.pulse();
        }
        // On a drop every kick flashes one of the bunny's ears, left, right, left …
        if (phase == PhaseBar.Phase.DROP && lo > loS * 1.3f && lo > 0.05f && now - lastKick > 180) {
            lastKick = now;
            if (gauge != null) gauge.beat();
        }
        if (phaseBar != null) {
            followTrack();
            phaseBar.update(currentPosition(), duration(), audio.isMusicActive() ? phase : null);
        }
    }

    /* ---------------- Second page: listening stats ---------------- */

    private LinearLayout stats;
    private TextView dots;
    private final java.util.List<View> playerViews = new java.util.ArrayList<>();
    private final java.util.Map<View, Integer> playerVisibility = new java.util.HashMap<>();
    private boolean onStats;

    /** True if a touch at these screen coordinates is on the card (for the swipe). */
    boolean isOnCard(float rawX, float rawY) {
        if (root == null || !root.isShown()) return false;
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        return rawX >= at[0] && rawX <= at[0] + root.getWidth() && rawY >= at[1] && rawY <= at[1] + root.getHeight();
    }

    /** Swipe on the card: -1 = to the left (next page), +1 = to the right. */
    /** A session that was already open and starts playing later sends no "sessions changed": re-check every 2 s. */
    private final Runnable repick = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            try {
                if (hasAccess()) pick(sessions.getActiveSessions(listener));
            } catch (SecurityException ignored) {
            }
            main.postDelayed(this, 2000);
        }
    };

    /** Every 30 s the card turns by itself (player ↔ stats); a swipe by hand restarts the 30 s. */
    private static final long AUTO_FLIP_MS = 30_000;
    private final Runnable autoFlip = new Runnable() {
        @Override
        public void run() {
            // Forth to the stats (sliding left), back to the player (sliding right).
            if (root != null && root.isShown() && root.getWidth() > 0) flip(onStats ? 1 : -1);
            else main.postDelayed(this, AUTO_FLIP_MS);
        }
    };

    void flip(int dir) {
        main.removeCallbacks(autoFlip);
        main.postDelayed(autoFlip, AUTO_FLIP_MS);
        boolean toStats = !onStats;
        float w = root.getWidth();
        root.animate().cancel();
        // Both pages keep the taller page's height, so the sections below never jump.
        root.setMinimumHeight(Math.max(root.getMinimumHeight(), root.getHeight()));
        root.animate().translationX(dir * w * 0.3f).alpha(0f).setDuration(140).withEndAction(() -> {
            showStats(toStats);
            root.setTranslationX(-dir * w * 0.3f);
            root.animate().translationX(0).alpha(1f).setDuration(200).start();
        }).start();
    }

    private void showStats(boolean show) {
        onStats = show;
        if (show) {
            for (View v : playerViews) {
                if (v == playerViews.get(0)) continue;       // the top row (cover, title, dots) stays
                playerVisibility.put(v, v.getVisibility());
                v.setVisibility(View.GONE);
            }
            renderStats();
            stats.setVisibility(View.VISIBLE);
        } else {
            stats.setVisibility(View.GONE);
            for (java.util.Map.Entry<View, Integer> e : playerVisibility.entrySet()) e.getKey().setVisibility(e.getValue());
            playerVisibility.clear();
        }
        paintDots();
    }

    private void paintDots() {
        if (dots == null) return;
        dots.setText(onStats ? "○ ●" : "● ○");
        dots.setTextColor(rust);
    }

    private void renderStats() {
        stats.removeAllViews();
        if (!hasAccess()) {
            TextView ask = label(12, ink, true);
            ask.setText("Statistiken brauchen den Benachrichtigungszugriff für ARC-DNA →");
            ask.setPadding(0, dp(12), 0, dp(4));
            ask.setOnClickListener(v -> host.open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            stats.addView(ask);
            return;
        }
        long[] week = MusicStats.week(activity);
        long today = week[6], total = 0;
        for (long m : week) total += m;
        TextView head = label(11, ink3, true);
        head.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        head.setLetterSpacing(0.12f);
        head.setText("HEUTE GEHÖRT · NUR AUF DEM HANDY");
        head.setPadding(0, dp(16), 0, dp(2));
        stats.addView(head);
        TextView big = label(46, ink, true);
        big.setLetterSpacing(-0.03f);
        big.setText(today >= 60 ? (today / 60) + " h " + (today % 60) + " min" : today + " min");
        stats.addView(big);
        TextView sub = label(15, ink2, false);
        sub.setText(MusicStats.tracksToday(activity) + " Titel heute · " + (total / 60) + " h " + (total % 60) + " min in 7 Tagen");
        stats.addView(sub);

        // Last 7 days as bars, today in rust.
        View chart = new View(activity) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();

            @Override
            protected void onDraw(Canvas c) {
                long max = 1;
                for (long m : week) max = Math.max(max, m);
                float gap = dp(6), bw = (getWidth() - 6 * gap) / 7f, h = getHeight() - dp(20), top = dp(16);
                String[] days = {"MO", "DI", "MI", "DO", "FR", "SA", "SO"};
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.DAY_OF_YEAR, -6);
                p.setTextSize(dp(11));
                p.setTypeface(Typeface.MONOSPACE);
                for (int i = 0; i < 7; i++) {
                    float x = i * (bw + gap), bh = Math.max(dp(2), (h - top) * week[i] / (float) max);
                    // The week as a rainbow: every weekday its own colour (Mo red … So violet); today full strength.
                    int[] rainbow = {0xFFFF3B3B, 0xFFFF9A1F, 0xFFFFD21F, 0xFF2BC96B, 0xFF1FA8FF, 0xFF5B5BFF, 0xFFA04BFF};
                    int weekday = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;   // 0 = Monday
                    p.setColor(week[i] > 0 ? rainbow[weekday] : ink3);
                    p.setAlpha(week[i] > 0 && i != 6 ? 190 : 255);
                    r.set(x, h - bh, x + bw, h);
                    c.drawRoundRect(r, dp(3), dp(3), p);
                    p.setAlpha(255);
                    p.setColor(rainbow[weekday]);
                    String d = days[(cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7];
                    c.drawText(d, x + bw / 2 - p.measureText(d) / 2, getHeight() - dp(3), p);
                    // Hours above each bar that has any.
                    if (week[i] > 0) {
                        String v = week[i] >= 60 ? (week[i] / 60) + "h" : week[i] + "′";
                        p.setColor(i == 6 ? ink : ink2);
                        c.drawText(v, x + bw / 2 - p.measureText(v) / 2, h - bh - dp(4), p);
                    }
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                }
            }
        };
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.topMargin = dp(14);
        chart.setMinimumHeight(dp(100));
        stats.addView(chart, clp);

        // Today's top artists, one per line, ranked.
        java.util.List<String> top = MusicStats.topArtists(activity);
        TextView topHead = label(11, ink3, true);
        topHead.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        topHead.setLetterSpacing(0.12f);
        topHead.setText(top.isEmpty() ? "NOCH KEINE KÜNSTLER HEUTE" : "TOP HEUTE");
        topHead.setPadding(0, dp(14), 0, dp(4));
        stats.addView(topHead);
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView rank = label(13, rust, true);
            rank.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            rank.setText((i + 1) + "  ");
            row.addView(rank);
            TextView name = label(17, ink, true);
            name.setText(top.get(i));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name);
            row.setPadding(0, dp(2), 0, dp(2));
            stats.addView(row);
        }
        TextView history = label(13, rust, true);
        history.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        history.setText("VERLAUF ALLER TITEL →");
        history.setPadding(0, dp(14), 0, dp(2));
        history.setOnClickListener(v -> host.open(new Intent(activity, HistoryActivity.class)));
        stats.addView(history);
        TextView back = label(11, ink3, true);
        back.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        back.setText("← WISCHEN = PLAYER");
        back.setPadding(0, dp(6), 0, 0);
        stats.addView(back);
    }

    private void openPlayer() {
        if (controller == null) return;
        android.app.PendingIntent session = controller.getSessionActivity();
        try {
            if (session != null) {
                session.send();
                return;
            }
        } catch (android.app.PendingIntent.CanceledException ignored) {
        }
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
        if (launch != null) host.open(launch);
    }

    private ImageView control(int icon, int keyCode) {
        ImageView b = new ImageView(activity);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setOnClickListener(v -> press(keyCode));
        return b;
    }

    /** True if a touch at these screen coordinates lands on the volume slider (so it isn't a swipe). */
    boolean isOnSlider(float rawX, float rawY) {
        // The timeline is dragged sideways too: it belongs to the slider family (no card swipe there).
        if (phaseBar != null && phaseBar.isShown()) {
            int[] pb = new int[2];
            phaseBar.getLocationOnScreen(pb);
            if (rawX >= pb[0] && rawX <= pb[0] + phaseBar.getWidth() && rawY >= pb[1] - dp(8)
                && rawY <= pb[1] + phaseBar.getHeight() + dp(8)) return true;
        }
        if (volume == null || !volume.isShown()) return false;
        int[] at = new int[2];
        volume.getLocationOnScreen(at);
        int[] card = new int[2];
        root.getLocationOnScreen(card);
        return rawX >= card[0] && rawX <= card[0] + root.getWidth()
            && rawY >= at[1] - dp(12) - output.getHeight() && rawY <= card[1] + root.getHeight();
    }

    /* ---------------- Lifecycle ---------------- */

    void start() {
        main.removeCallbacks(repick);
        main.postDelayed(repick, 2000);
        main.removeCallbacks(autoFlip);
        main.postDelayed(autoFlip, AUTO_FLIP_MS);
        started = true;
        syncVolume();
        refreshOutput(false);
        audio.registerAudioDeviceCallback(devices, main);
        activity.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver);
        if (hasAccess()) {
            accessHint.setVisibility(View.GONE);
            try {
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main);
                pick(sessions.getActiveSessions(listener));
            } catch (SecurityException e) {
                accessHint.setVisibility(View.VISIBLE);
            }
        } else {
            accessHint.setVisibility(View.VISIBLE);
            pick(null);
        }
        startVisualizer();
        spectrum.run();
    }

    void stop() {
        if (phaseBar != null) phaseBar.save();
        main.removeCallbacks(repick);
        main.removeCallbacks(autoFlip);
        started = false;
        audio.unregisterAudioDeviceCallback(devices);
        activity.getContentResolver().unregisterContentObserver(volumeObserver);
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (RuntimeException ignored) {
        }
        if (controller != null) controller.unregisterCallback(controllerCallback);
        controller = null;
        stopVisualizer();
    }

    void onAudioPermission(boolean granted) {
        if (granted && started) startVisualizer();
    }

    /* ---------------- Now playing ---------------- */

    private boolean hasAccess() {
        String flat = Settings.Secure.getString(activity.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(activity.getPackageName());
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    /** Prefers the session that is playing; otherwise the most recent one. */
    private void pick(List<MediaController> list) {
        // Best session: playing with track info > playing > has track info > the first one.
        MediaController best = null;
        int bestScore = -1;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                MediaMetadata m = c.getMetadata();
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                boolean info = m != null && m.getString(MediaMetadata.METADATA_KEY_TITLE) != null;
                int score = (playing ? 2 : 0) + (info ? 1 : 0);
                if (score > bestScore) {
                    best = c;
                    bestScore = score;
                }
            }
        }
        if (controller != null && best != null && controller.getSessionToken().equals(best.getSessionToken())) {
            renderTrack();
            return;
        }
        if (controller != null) controller.unregisterCallback(controllerCallback);
        controller = best;
        if (controller != null) controller.registerCallback(controllerCallback, main);
        renderTrack();
    }

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            renderTrack();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            renderTrack();
        }

        @Override
        public void onSessionDestroyed() {
            pick(hasAccess() ? sessions.getActiveSessions(listener) : null);
        }
    };

    private void renderTrack() {
        if (title == null) return;
        MediaMetadata meta = controller == null ? null : controller.getMetadata();
        if (meta == null) {
            title.setText(audio.isMusicActive() ? "Musik läuft" : "Nichts läuft");
            artist.setText(hasAccess() ? "▶ setzt die letzte Musik fort" : "▶ ⏮ ⏭ steuern die aktuelle Musik-App");
            source.setText("");
            art.setImageResource(R.drawable.ic_music);
            art.setScaleType(ImageView.ScaleType.CENTER);
        } else {
            String t = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = meta.getString(MediaMetadata.METADATA_KEY_ALBUM);
            title.setText(t != null ? t : "–");
            artist.setText(a != null ? a : "");
            Bitmap cover = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (cover == null) cover = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (cover == null) cover = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (cover != null) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageBitmap(cover);
            } else {
                art.setScaleType(ImageView.ScaleType.CENTER);
                art.setImageResource(R.drawable.ic_music);
            }
            source.setText(appName(controller.getPackageName()).toUpperCase(Locale.GERMAN));
        }
        playButton.setImageResource(isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        frame.run(isPlaying());
        if (gauge != null) gauge.playing(isPlaying());
    }

    private final RainbowFrame frame = new RainbowFrame();

    /** The lock screen's flowing rainbow border, on the card while music plays. */
    private final class RainbowFrame extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private final int[] hues = {0xFFFF3B3B, 0xFFFF9A1F, 0xFFFFE31F, 0xFF2BD96B, 0xFF1FA8FF, 0xFF8A4BFF, 0xFFFF3BB0, 0xFFFF3B3B};
        private android.graphics.LinearGradient flow;
        private float flowW;
        private boolean running;
        private float shown;

        void run(boolean on) {
            if (on == running) return;
            running = on;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            // Fade in / out over ~0.4 s instead of popping.
            shown = Math.max(0f, Math.min(1f, shown + (running ? 0.08f : -0.08f)));
            if (shown > 0) {
                float w = b.width();
                if (flow == null || flowW != w) {
                    flowW = w;
                    flow = new android.graphics.LinearGradient(0, 0, w * 0.9f, b.height() * 0.15f, hues, null,
                        android.graphics.Shader.TileMode.REPEAT);
                }
                float t = android.os.SystemClock.uptimeMillis() / 1000f;
                m.setTranslate((t * w * 0.35f) % (w * 0.9f), 0);
                flow.setLocalMatrix(m);
                p.setShader(flow);
                p.setStyle(Paint.Style.STROKE);
                float rad = dp(8);
                // A soft inner glow, then the crisp line on the card's edge.
                p.setStrokeWidth(dp(7));
                p.setAlpha((int) (55 * shown));
                r.set(b.left + dp(3.5f), b.top + dp(3.5f), b.right - dp(3.5f), b.bottom - dp(3.5f));
                c.drawRoundRect(r, rad - dp(2), rad - dp(2), p);
                p.setStrokeWidth(dp(2.5f));
                p.setAlpha((int) (240 * shown));
                r.set(b.left + dp(1.25f), b.top + dp(1.25f), b.right - dp(1.25f), b.bottom - dp(1.25f));
                c.drawRoundRect(r, rad, rad, p);
            }
            if (running || shown > 0) scheduleSelf(this::invalidateSelf, android.os.SystemClock.uptimeMillis() + 33);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private boolean isPlaying() {
        if (controller != null && controller.getPlaybackState() != null) {
            return controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
        }
        return audio.isMusicActive();
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Uses the session's controls when we have it, otherwise plain media keys (no permission needed). */
    private void press(int keyCode) {
        // Only steer a session directly when it really holds a track; otherwise let Android route the
        // key to the music app you used last (that's what resumes YouTube Music & co. properly).
        boolean usable = controller != null && controller.getMetadata() != null
            && controller.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE) != null;
        if (usable) {
            MediaController.TransportControls tc = controller.getTransportControls();
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) tc.skipToNext();
            else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) tc.skipToPrevious();
            else if (isPlaying()) tc.pause();
            else tc.play();
        } else {
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
        main.postDelayed(this::renderTrack, 400);
        // The app that starts playing may only now show its session: follow it right away.
        for (long d : new long[]{500, 1500}) {
            main.postDelayed(() -> {
                try {
                    if (started && hasAccess()) pick(sessions.getActiveSessions(listener));
                } catch (SecurityException ignored) {
                }
            }, d);
        }
    }

    /* ---------------- Output device & volume ---------------- */

    private static boolean isBluetooth(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET
            || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
    }

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            refreshOutput(true);
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            refreshOutput(true);
        }
    };

    /** Shows where the music goes; remembers when a Bluetooth speaker (e.g. the JBL) connected. */
    /* ---- Speaker control: output switcher, battery, the speaker's own app ---- */

    private int lastBattery = -1;
    private DeviceGauge gauge;
    private long lastKick, lastBeat, lastVocalAt = System.currentTimeMillis();
    private TextView deviceName;

    /**
     * A battery ring like Android's device page (green, orange under 30 %, red under 15 %) with the
     * device drawn inside: 0 = earbuds, 1 = speaker, 2 = phone. Without a level the ring stays grey.
     */
    private final class DeviceGauge extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int kind = 2, level = -1;

        DeviceGauge() {
            super(activity);
        }

        /* Bunny life: now and then one ear flicks; on a drop the ears flash in turn with the kicks. */
        private long flickAt = -1, nextFlick;
        private int flickSide = 1, beatSide = 1;
        private final long[] flashAt = {-1, -1};
        private final java.util.Random rnd = new java.util.Random();
        private final Runnable flicker = new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) return;
                if (kind == 0 && isShown()) {
                    flickAt = android.os.SystemClock.uptimeMillis();
                    flickSide = rnd.nextBoolean() ? 1 : -1;
                    invalidate();
                }
                postDelayed(this, 2500 + rnd.nextInt(4500));
            }
        };

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            removeCallbacks(flicker);
            postDelayed(flicker, 2000);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(flicker);
            super.onDetachedFromWindow();
        }

        private boolean playing;
        private long beatAt = -1;
        private float vibe;

        void playing(boolean on) {
            if (on == playing) return;
            playing = on;
            invalidate();
        }

        /** Any kick while music plays: the animal nods along. */
        void pulse() {
            beatAt = android.os.SystemClock.uptimeMillis();
        }

        /** Exhaust flames behind the F1 helmet, flickering like a car at the limiter. */
        private void flames(Canvas c, long tNow) {
            int[] colours = {rust, 0xFFF29A1F, 0xFFFFE680};
            float[] ys = {-1.6f, 0.4f, 2.2f};
            for (int i = 0; i < 3; i++) {
                double ph = tNow / 1000.0 * (9 + i * 2.3) + i * 1.7;
                float flick = (float) (0.5 + 0.25 * Math.sin(ph) + 0.25 * Math.sin(ph * 2.7 + 1));
                for (int layer = 0; layer < 3; layer++) {
                    float len = (1.2f + 2.6f * flick) * (1 - layer * 0.3f) + (beatAt >= 0 && tNow - beatAt < 200 ? 0.8f : 0);
                    float half = 0.75f * (1 - layer * 0.3f);
                    float bx = -3.4f;
                    poly(c, colours[layer], bx, ys[i] - half, bx, ys[i] + half, bx - len, ys[i] + 0.2f * (float) Math.sin(ph * 1.3));
                }
            }
        }

        void beat() {
            if (kind != 0) return;
            beatSide = -beatSide;
            flashAt[beatSide < 0 ? 0 : 1] = android.os.SystemClock.uptimeMillis();
            invalidate();
        }

        void set(int k, int lvl) {
            kind = k;
            level = lvl;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, rad = Math.min(w, h) / 2 - dp(3);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(4));
            p.setColor(line);
            c.drawCircle(cx, cy, rad, p);
            if (level >= 0) {
                p.setColor(level < 15 ? 0xFFE0281E : level < 30 ? 0xFFF29A1F : 0xFF1E9E4A);
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                c.drawArc(r, -90, 360f * level / 100f, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            u = rad / 10f;
            ox = cx;
            oy = cy;
            // While music plays: a gentle sway that eases in over ~1 s (and out on pause), plus a
            // soft nod on each kick that rises and falls smoothly instead of snapping.
            long tNow = android.os.SystemClock.uptimeMillis();
            vibe += ((playing ? 1f : 0f) - vibe) * 0.05f;
            if (vibe < 0.002f) vibe = 0;
            float k = beatAt >= 0 ? (tNow - beatAt) / 320f : 1;
            float bob = k < 1 ? (float) Math.sin(Math.PI * k) * vibe : 0;
            float sway = (float) Math.sin(tNow / 1000.0 * Math.PI * 1.1) * vibe;
            c.save();
            if (vibe > 0) {
                c.translate(0, -bob * 0.45f * u);
                c.rotate(sway * 3f - bob * 3f, ox, oy + 4f * u);
            }
            if (kind == 3 && playing) flames(c, tNow);
            if (kind == 0) {
                // Bunny — long ears for the in-ears (left ear = left bud, right ear = right bud).
                long now = android.os.SystemClock.uptimeMillis();
                boolean busy = false;
                for (int side = -1; side <= 1; side += 2) {
                    float angle = side * 12.6f;
                    // Flick: the ear twitches outwards twice, quickly, then settles.
                    long fk = now - flickAt;
                    if (flickAt >= 0 && side == flickSide && fk < 520) {
                        angle += side * 26f * (float) Math.abs(Math.sin(Math.PI * 2 * fk / 520f)) * (1 - fk / 620f);
                        busy = true;
                    }
                    long fl = now - flashAt[side < 0 ? 0 : 1];
                    float flash = flashAt[side < 0 ? 0 : 1] >= 0 && fl < 260 ? 1 - fl / 260f : 0;
                    if (flash > 0) busy = true;
                    c.save();
                    c.rotate(angle, ox + side * 1.5f * u, oy - 1.2f * u);
                    if (flash > 0) {
                        // A glow at the ear's tip.
                        p.setColor(0xFFFFE680);
                        p.setAlpha((int) (110 * flash));
                        c.drawCircle(ox + side * 1.5f * u, oy - 5.6f * u, (1.4f + 1.4f * flash) * u, p);
                        p.setAlpha(255);
                    }
                    p.setColor(ink);
                    r.set(ox + (side * 1.5f - 0.95f) * u, oy - 6f * u, ox + (side * 1.5f + 0.95f) * u, oy - 0.8f * u);
                    c.drawOval(r, p);
                    p.setColor(flash > 0 ? mix(rust, 0xFFFFE680, flash) : rust);
                    r.set(ox + (side * 1.5f - 0.43f) * u, oy - 5f * u, ox + (side * 1.5f + 0.43f) * u, oy - 1.4f * u);
                    c.drawOval(r, p);
                    c.restore();
                }
                if (busy) postInvalidateOnAnimation();
                dot(0, 2, 3.3f, ink, c);
                dot(-1.2f, 1.5f, 0.45f, card, c);
                dot(1.2f, 1.5f, 0.45f, card, c);
                dot(0, 2.7f, 0.5f, rust, c);
            } else if (kind == 1) {
                // Bear — the big bass of the speaker.
                dot(-2.9f, -2.4f, 1.6f, ink, c);
                dot(2.9f, -2.4f, 1.6f, ink, c);
                dot(-2.9f, -2.4f, 0.75f, rust, c);
                dot(2.9f, -2.4f, 0.75f, rust, c);
                dot(0, 0.6f, 3.9f, ink, c);
                p.setColor(card);
                r.set(ox - 1.9f * u, oy + 1.3f * u, ox + 1.9f * u, oy + 4f * u);
                c.drawOval(r, p);
                dot(0, 2f, 0.6f, ink, c);
                dot(-1.5f, -0.3f, 0.45f, card, c);
                dot(1.5f, -0.3f, 0.45f, card, c);
            } else if (kind == 2) {
                // Fox — the phone's own speaker, home turf.
                poly(c, rust, -4.4f, -4.2f, -1.6f, -2f, 1.6f, -2f, 4.4f, -4.2f, 4.2f, 0.2f, 0, 4.6f, -4.2f, 0.2f);
                poly(c, ink, -3.7f, -3f, -2.3f, -1.9f, -3.5f, -1f);
                poly(c, ink, 3.7f, -3f, 2.3f, -1.9f, 3.5f, -1f);
                poly(c, card, -4.2f, 0.2f, -0.9f, 1.6f, 0, 4.6f);
                poly(c, card, 4.2f, 0.2f, 0.9f, 1.6f, 0, 4.6f);
                dot(0, 4f, 0.55f, ink, c);
                dot(-1.6f, -0.2f, 0.5f, ink, c);
                dot(1.6f, -0.2f, 0.5f, ink, c);
            } else {
                // F1 helmet — on the cable.
                p.setColor(ink);
                r.set(ox - 4.2f * u, oy - 3.8f * u, ox + 4.2f * u, oy + 4f * u);
                c.drawRoundRect(r, 3.6f * u, 3.6f * u, p);
                p.setColor(card);
                r.set(ox - 0.4f * u, oy - 1.3f * u, ox + 4.2f * u, oy + 0.7f * u);
                c.drawRoundRect(r, u, u, p);
                p.setColor(rust);
                r.set(ox - 3.7f * u, oy + 1.8f * u, ox + 3.7f * u, oy + 2.5f * u);
                c.drawRect(r, p);
                if (playing) {
                    // A glint sweeping over the visor every ~2.5 s.
                    float g = (tNow % 2500) / 600f;
                    if (g < 1) {
                        c.save();
                        r.set(ox - 0.4f * u, oy - 1.3f * u, ox + 4.2f * u, oy + 0.7f * u);
                        c.clipRect(r);
                        p.setColor(0x99FFFFFF);
                        p.setStrokeWidth(0.6f * u);
                        p.setStyle(Paint.Style.STROKE);
                        float gx = ox + (-1.4f + g * 6.4f) * u;
                        c.drawLine(gx, oy + 0.9f * u, gx + 1.2f * u, oy - 1.5f * u, p);
                        p.setStyle(Paint.Style.FILL);
                        c.restore();
                    }
                }
            }
            c.restore();
            if (playing || vibe > 0) postInvalidateOnAnimation();
        }

        private float u, ox, oy;

        private int mix(int a, int b, float k) {
            int r1 = (a >> 16) & 255, g1 = (a >> 8) & 255, b1 = a & 255;
            int r2 = (b >> 16) & 255, g2 = (b >> 8) & 255, b2 = b & 255;
            return 0xFF000000 | ((int) (r1 + (r2 - r1) * k) << 16) | ((int) (g1 + (g2 - g1) * k) << 8) | (int) (b1 + (b2 - b1) * k);
        }
        private final Path path = new Path();

        private void dot(float x, float y, float rr, int color, Canvas c) {
            p.setColor(color);
            c.drawCircle(ox + x * u, oy + y * u, rr * u, p);
        }

        private void poly(Canvas c, int color, float... xy) {
            path.reset();
            path.moveTo(ox + xy[0] * u, oy + xy[1] * u);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(ox + xy[i] * u, oy + xy[i + 1] * u);
            path.close();
            p.setColor(color);
            c.drawPath(path, p);
        }
    }
    private LinearLayout outputRow;
    private TextView anc;

    /** Android's own output switcher (moves the music between JBL, phone speaker, other devices). */
    private void showOutputSwitcher() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                if (android.media.MediaRouter2.getInstance(activity).showSystemOutputSwitcher()) return;
            } catch (RuntimeException ignored) {
            }
        }
        try {
            activity.sendBroadcast(new Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui").putExtra("package_name", activity.getPackageName()));
            return;
        } catch (RuntimeException ignored) {
        }
        host.open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    /** The speaker maker's app if installed (JBL / Harman), else Bluetooth settings. */
    private void openSpeakerApp() {
        android.content.pm.PackageManager pm = activity.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (pkg.contains("jbl") || pkg.contains("harman") || label.contains("jbl")) {
                Intent launch = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                if (launch != null) {
                    host.open(launch);
                    return;
                }
            }
        }
        host.open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    /** Battery of the connected speaker, if it reports one (needs "Nearby devices"); -1 = unknown. */
    private int speakerBattery() {
        if (android.os.Build.VERSION.SDK_INT >= 31
            && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return -1;
        }
        try {
            android.bluetooth.BluetoothManager bm = activity.getSystemService(android.bluetooth.BluetoothManager.class);
            android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) return lastBattery;
            for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) {
                if (btName == null || !btName.equals(d.getName())) continue;
                Object level = d.getClass().getMethod("getBatteryLevel").invoke(d);
                if (level instanceof Integer && (Integer) level >= 0) lastBattery = (Integer) level;
            }
        } catch (Exception ignored) {
            // Not every phone lets apps read it; then the battery just isn't shown.
        }
        return lastBattery;
    }

    private void askBluetoothOnce() {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences p = activity.getSharedPreferences("home", Activity.MODE_PRIVATE);
        if (p.getBoolean("askedBtConnect", false)) return;
        p.edit().putBoolean("askedBtConnect", true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 31);
    }

    private void refreshOutput(boolean announce) {
        AudioDeviceInfo bt = null, wired = null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (isBluetooth(t) && bt == null) bt = d;
            else if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || t == AudioDeviceInfo.TYPE_USB_HEADSET) wired = d;
        }
        String name = bt != null ? String.valueOf(bt.getProductName()) : null;
        if (name != null && !name.equals(btName)) {
            btName = name;
            btSince = System.currentTimeMillis();
            if (announce) host.say(name + " verbunden!");
        } else if (name == null && btName != null) {
            if (announce) host.say(btName + " getrennt.");
            btName = null;
        }
        if (anc != null) {
            anc.setVisibility(btName != null && btName.toUpperCase(Locale.ROOT).contains("JBL") ? View.VISIBLE : View.GONE);
        }
        if (btName != null) {
            String since = new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(btSince));
            askBluetoothOnce();
            int battery = speakerBattery();
            deviceName.setText(btName);
            output.setText((battery >= 0 ? battery + " % · " : "") + "BLUETOOTH · SEIT " + since);
            output.setTextColor(rust);
            gauge.set(btName.toLowerCase(Locale.ROOT).matches(".*(buds|beam|pods|tune|ear|wf-|tws|free).*") ? 0 : 1, battery);
        } else if (wired != null) {
            deviceName.setText(String.valueOf(wired.getProductName()));
            output.setText("KABEL");
            output.setTextColor(ink2);
            gauge.set(3, -1);
        } else {
            deviceName.setText("Handy-Lautsprecher");
            output.setText("TIPPEN = AUSGANG WECHSELN");
            output.setTextColor(ink3);
            gauge.set(2, -1);
        }
        syncVolume();
    }

    private final ContentObserver volumeObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            syncVolume();
        }
    };

    private void syncVolume() {
        if (volume == null) return;
        volume.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    /* ---------------- Visualizer ---------------- */

    private boolean hasAudioPermission() {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startVisualizer() {
        if (visualizer != null) return;
        if (!hasAudioPermission()) {
            vizHint.setVisibility(View.VISIBLE);
            return;
        }
        vizHint.setVisibility(View.GONE);
        try {
            visualizer = new Visualizer(0);       // 0 = the phone's whole output mix
            visualizer.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            visualizer.setCaptureSize(Math.max(range[0], Math.min(512, range[1])));
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int rate) {
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                    spectrum.feed(fft);
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20000), false, true);
            visualizer.setEnabled(true);
        } catch (RuntimeException e) {
            visualizer = null;          // Spectrum falls back to a gentle pulse while music plays.
        }
    }

    private void stopVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (RuntimeException ignored) {
            }
            visualizer = null;
        }
    }

    /**
     * Log-spaced spectrum bars, ink with every sixth in rust (like the VOID FREQ dial).
     * If no real signal arrives while music plays (e.g. Bluetooth offload hides the mix),
     * it shows a calm pulse instead of sitting flat.
     */
    private final class Spectrum extends View {
        private final float[] target = new float[BARS], shown = new float[BARS];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long lastSignal, lastRestart;
        private boolean wasPlaying;
        private int failedRestarts;
        private boolean running;

        Spectrum() {
            super(activity);
        }

        void feed(byte[] fft) {
            int bins = fft.length / 2;
            float total = 0;
            for (int b = 0; b < BARS; b++) {
                int lo = (int) Math.max(1, Math.pow(bins, (double) b / BARS));
                int hi = (int) Math.max(lo + 1, Math.pow(bins, (double) (b + 1) / BARS));
                float peak = 0;
                for (int k = lo; k < hi && k < bins; k++) {
                    float re = fft[2 * k], im = fft[2 * k + 1];
                    peak = Math.max(peak, (float) Math.hypot(re, im));
                }
                float v = (float) Math.min(1, Math.log1p(peak) / Math.log1p(90));
                target[b] = Math.max(target[b], v);
                total += v;
            }
            float level = total / BARS;
            if (level > 0.03f) lastSignal = System.currentTimeMillis();
            host.onLevel(level);
        }

        /**
         * Android drops the output-mix visualizer while nothing plays and doesn't reattach it
         * when music resumes. So reconnect when playback starts again, and retry while music
         * plays but no signal arrives: every 3 s, then every 15 s after three misses (e.g. when
         * Bluetooth offload hides the mix for good).
         */
        private void keepVisualizerAlive(boolean playing, boolean noSignal) {
            long now = System.currentTimeMillis();
            if (!noSignal) failedRestarts = 0;
            boolean resumed = playing && !wasPlaying;
            wasPlaying = playing;
            long wait = failedRestarts >= 3 ? 15000 : 3000;
            if (!hasAudioPermission()) return;
            if (resumed || (playing && noSignal && now - lastRestart > wait)) {
                if (!resumed) failedRestarts++;
                lastRestart = now;
                DiagLog.log("visualizer reconnect (" + (resumed ? "resumed" : "no signal, try " + failedRestarts) + ")");
                stopVisualizer();
                startVisualizer();
            }
        }

        void run() {
            if (running) return;
            running = true;
            postOnAnimation(frame);
        }

        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (!started || !isAttachedToWindow()) {
                    running = false;
                    return;
                }
                boolean playing = audio.isMusicActive();
                boolean noSignal = System.currentTimeMillis() - lastSignal > 1500;
                keepVisualizerAlive(playing, noSignal);
                long t = System.currentTimeMillis();
                for (int i = 0; i < BARS; i++) {
                    if (playing && noSignal) {
                        float pulse = 0.18f + 0.22f * (float) Math.abs(Math.sin(t / 380.0 + i * 0.55))
                            * (float) (0.6 + 0.4 * Math.sin(t / 900.0 + i));
                        target[i] = Math.max(target[i], pulse);
                    }
                    shown[i] += (target[i] - shown[i]) * 0.35f;
                    target[i] *= 0.9f;
                }
                signal = !noSignal;
                pushColumn();
                invalidate();
                postOnAnimation(this);
            }
        };

        /* Waveform history: one column per frame, coloured by sound like a DJ deck – bass red, mids green, highs blue. */
        private final float[] colAmp = new float[400];
        private final int[] colColour = new int[400];
        private int colHead;

        /** True while the real sound arrives; the placeholder pulse never goes into the waveform or the phases. */
        boolean signal;

        private void pushColumn() {
            if (!signal) {
                colHead = (colHead + 1) % colAmp.length;
                colAmp[colHead] = 0;
                colColour[colHead] = 0;
                if (phaseLabel != null && audio.isMusicActive()) {
                    phaseLabel.setText("KEIN SIGNAL");
                    phase = PhaseBar.Phase.GROOVE;
                }
                if (phaseBar != null) {
                    followTrack();
                    phaseBar.update(currentPosition(), duration(), null);
                }
                return;
            }
            float lo = 0, mid = 0, hi = 0;
            for (int i = 0; i < BARS; i++) {
                if (i < BARS / 3) lo += shown[i];
                else if (i < 2 * BARS / 3) mid += shown[i];
                else hi += shown[i];
            }
            float max = Math.max(0.001f, Math.max(lo, Math.max(mid, hi)));
            float amp = Math.min(1f, (lo + mid + hi) / BARS * 1.6f);
            int r = (int) (60 + 195 * lo / max), g = (int) (40 + 215 * mid / max * 0.9f), b = (int) (60 + 195 * hi / max);
            readPhase(lo / (BARS / 3f), mid / (BARS / 3f), hi / (BARS / 3f));
            colHead = (colHead + 1) % colAmp.length;
            colAmp[colHead] = amp;
            colColour[colHead] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        private boolean bars() {
            return activity.getSharedPreferences("home", Activity.MODE_PRIVATE).getBoolean("vizBars", false);
        }

        private void drawWave(Canvas canvas, float w, float h) {
            float col = dp(2), mid = h / 2f, head = w * 0.82f;     // the playhead, with the past to its left
            int n = (int) (head / col);
            for (int k = 0; k < n && k < colAmp.length; k++) {
                int idx = (colHead - k + colAmp.length) % colAmp.length;
                float a = Math.max(0.03f, colAmp[idx]) * (h / 2f - dp(2));
                float x = head - k * col;
                paint.setColor(colColour[idx] == 0 ? (ink & 0x00FFFFFF) | 0x40000000 : colColour[idx]);
                paint.setAlpha((int) (255 * (0.35f + 0.65f * (1 - k / (float) n))));   // older = fainter
                rect.set(x - col * 0.8f, mid - a, x, mid + a);
                canvas.drawRect(rect, paint);
            }
            // The part "ahead" stays a calm line, and the red playhead marks now.
            paint.setAlpha(255);
            paint.setColor((ink & 0x00FFFFFF) | 0x33000000);
            canvas.drawRect(head, mid - dp(0.5f), w, mid + dp(0.5f), paint);
            paint.setColor(0xFFE0281E);
            canvas.drawRect(head, 0, head + dp(1.5f), h, paint);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (!bars()) {
                drawWave(canvas, w, h);
                return;
            }
            float gap = dp(3), bw = (w - gap * (BARS - 1)) / BARS;
            for (int i = 0; i < BARS; i++) {
                float v = Math.max(0.05f, shown[i]);
                float bh = v * h;
                float x = i * (bw + gap);
                rect.set(x, h - bh, x + bw, h);
                paint.setColor(i % 6 == 0 ? rust : (ink & 0x00FFFFFF) | 0xB3000000);
                canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint);
            }
        }
    }

    /* ---------------- Helpers ---------------- */

    private TextView label(float sp, int colour, boolean bold) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -1, 1f);
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
