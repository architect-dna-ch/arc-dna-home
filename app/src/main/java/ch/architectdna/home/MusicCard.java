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
            if (!hasAudioPermission()) host.askAudioPermission();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(46));
        slp.topMargin = dp(12);
        root.addView(spectrum, slp);

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
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = dp(8);
        root.addView(output, olp);

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
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) sliding = e.getY() >= output.getTop() - dp(4);
            if (!sliding) return false;
            // Hand the touch to the slider in its own coordinates, so the thumb lands under the finger.
            MotionEvent m = MotionEvent.obtain(e);
            m.setLocation(e.getX() - volume.getLeft(), volume.getHeight() / 2f);
            volume.dispatchTouchEvent(m);
            m.recycle();
            return true;
        });

        renderTrack();
        return root;
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
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
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
        if (controller != null) {
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
        if (btName != null) {
            String since = new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(btSince));
            output.setText("● " + btName + " · BLUETOOTH · SEIT " + since);
            output.setTextColor(rust);
        } else if (wired != null) {
            output.setText("● " + wired.getProductName() + " · KABEL");
            output.setTextColor(ink2);
        } else {
            output.setText("○ HANDY-LAUTSPRECHER");
            output.setTextColor(ink3);
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
                invalidate();
                postOnAnimation(this);
            }
        };

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
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
