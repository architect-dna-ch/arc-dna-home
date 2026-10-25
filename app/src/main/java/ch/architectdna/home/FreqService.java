package ch.architectdna.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps VOID FREQ playing with the screen off or another app in front: holds the engine, the
 * focus timer and a quiet "läuft" notification (tap = open, ■ = stop). Stops itself when silent.
 */
public class FreqService extends Service {

    private static final String CHANNEL = "voidfreq";
    static final String STOP = "ch.architectdna.home.voidfreq.STOP";

    static final String[] NAMES = {"Deep Focus", "Rain Room", "Void", "Lo-Fi", "Neurons"};

    private static final FreqEngine engine = new FreqEngine();
    private static long timerEnd, timerMins;
    private static FreqService instance;

    private final Handler main = new Handler(Looper.getMainLooper());

    static FreqEngine engine() {
        return engine;
    }

    static long timerEnd() {
        return timerEnd;
    }

    static long timerMins() {
        return timerMins;
    }

    static String name(String key) {
        for (int i = 0; i < FreqEngine.KEYS.length; i++) if (FreqEngine.KEYS[i].equals(key)) return NAMES[i];
        return "";
    }

    /** Play a channel ("" = stop); starts / stops the service around it. */
    static void play(android.content.Context c, String key) {
        engine.play(key);
        if (!key.isEmpty()) {
            Intent i = new Intent(c, FreqService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } else if (instance != null) {
            instance.refresh();
        }
    }

    static void startTimer(int mins) {
        timerMins = mins;
        timerEnd = System.currentTimeMillis() + mins * 60_000L;
        if (instance != null) instance.refresh();
    }

    static void stopTimer() {
        timerEnd = 0;
        timerMins = 0;
        if (instance != null) instance.refresh();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (timerEnd > 0 && System.currentTimeMillis() >= timerEnd) {
                // Done: the chime, and a finished session of 25+ minutes counts.
                if (timerMins >= 25) {
                    android.content.SharedPreferences p = getSharedPreferences("voidfreq", MODE_PRIVATE);
                    p.edit().putInt("sessions", p.getInt("sessions", 0) + 1).apply();
                }
                engine.chime();
                timerEnd = 0;
                timerMins = 0;
            }
            refresh();
            if (instance != null) main.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // No audio focus on purpose: VOID FREQ runs underneath your music (YouTube Music keeps
        // playing), as a focus bed you mix with the volume slider.
        main.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            play(this, "");
            stopTimer();
        }
        refresh();
        return START_NOT_STICKY;
    }

    private void refresh() {
        String key = engine.channel();
        if (key.isEmpty()) {
            // Let the fade-out finish, then leave.
            main.postDelayed(() -> {
                if (engine.channel().isEmpty()) stopSelf();
            }, 900);
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "VOID FREQ", NotificationManager.IMPORTANCE_LOW));
        }
        String text = "Läuft";
        if (timerEnd > 0) {
            long rem = Math.max(0, timerEnd - System.currentTimeMillis()) / 1000;
            text = String.format(java.util.Locale.ROOT, "Timer %02d:%02d", rem / 60, rem % 60);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, FreqActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FreqService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle("VOID FREQ · " + name(key))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "■ Stop", stop).build())
            .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(7, n);
    }

    @Override
    public void onDestroy() {
        instance = null;
        main.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
