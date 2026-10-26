package ch.architectdna.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The recorder keeps running with the screen off or another app in front: holds the MediaRecorder
 * and a quiet "Aufnahme läuft" notification (tap = open, ■ = stop and save). Recordings go to
 * Recordings/ARC-DNA (Android 12+) or Music/ARC-DNA Aufnahmen, so other apps see them too.
 */
public class RecorderService extends Service {

    private static final String CHANNEL = "recorder";
    static final String STOP = "ch.architectdna.home.recorder.STOP";
    static final String FOLDER = "ARC-DNA";

    private static MediaRecorder rec;
    private static ParcelFileDescriptor pfd;
    private static Uri uri;
    private static File legacy;
    private static long startedAt, pausedAt, pausedTotal;
    private static boolean paused;
    /** Called on the main thread when a recording was saved (or dropped). */
    static Runnable onChange;

    static boolean recording() {
        return rec != null;
    }

    static boolean paused() {
        return paused;
    }

    static long elapsed() {
        if (rec == null) return 0;
        long now = paused ? pausedAt : SystemClock.elapsedRealtime();
        return now - startedAt - pausedTotal;
    }

    /** 0 … 32767 since the last call. */
    static int amplitude() {
        try {
            return rec == null || paused ? 0 : rec.getMaxAmplitude();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static String relativePath() {
        return Build.VERSION.SDK_INT >= 31 ? Environment.DIRECTORY_RECORDINGS + "/" + FOLDER
            : Environment.DIRECTORY_MUSIC + "/" + FOLDER + " Aufnahmen";
    }

    static File legacyDir(Context c) {
        File d = new File(c.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Aufnahmen");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static void start(Context ctx) throws IOException {
        if (rec != null) return;
        Context c = ctx.getApplicationContext();
        String name = "Aufnahme " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(new Date()) + ".m4a";
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(c) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(128_000);
            r.setAudioSamplingRate(44_100);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath());
                v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                uri = c.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("Speicher nicht verfügbar");
                pfd = c.getContentResolver().openFileDescriptor(uri, "rw");
                if (pfd == null) throw new IOException("Datei nicht beschreibbar");
                r.setOutputFile(pfd.getFileDescriptor());
            } else {
                legacy = new File(legacyDir(c), name);
                r.setOutputFile(legacy.getAbsolutePath());
            }
            r.prepare();
            r.start();
        } catch (IOException | RuntimeException e) {
            r.release();
            discard(c);
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
        rec = r;
        paused = false;
        pausedTotal = 0;
        startedAt = SystemClock.elapsedRealtime();
        Intent i = new Intent(c, RecorderService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void pause() {
        if (rec == null || paused) return;
        try {
            rec.pause();
            paused = true;
            pausedAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException ignored) {
        }
    }

    static void resume() {
        if (rec == null || !paused) return;
        try {
            rec.resume();
            pausedTotal += SystemClock.elapsedRealtime() - pausedAt;
            paused = false;
        } catch (RuntimeException ignored) {
        }
    }

    /** Stops and saves; true if a recording was kept. */
    static boolean stop(Context ctx) {
        if (rec == null) return false;
        Context c = ctx.getApplicationContext();
        long ms = elapsed();
        boolean ok = true;
        try {
            if (paused) rec.resume();
            rec.stop();
        } catch (RuntimeException e) {
            ok = false;              // too short: nothing was written
        }
        rec.release();
        rec = null;
        paused = false;
        closePfd();
        if (!ok) {
            discard(c);
        } else {
            if (uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.IS_PENDING, 0);
                c.getContentResolver().update(uri, v, null, null);
                c.getSharedPreferences("recorder", MODE_PRIVATE).edit().putLong("dur:" + uri.getLastPathSegment(), ms).apply();
            } else if (legacy != null) {
                c.getSharedPreferences("recorder", MODE_PRIVATE).edit().putLong("dur:" + legacy.getName(), ms).apply();
            }
            DiagLog.log("recorder saved, " + ms / 1000 + " s");
        }
        uri = null;
        legacy = null;
        c.stopService(new Intent(c, RecorderService.class));
        Runnable l = onChange;
        if (l != null) l.run();
        return ok;
    }

    private static void discard(Context c) {
        closePfd();
        if (uri != null) {
            try {
                c.getContentResolver().delete(uri, null, null);
            } catch (RuntimeException ignored) {
            }
        }
        if (legacy != null) {
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
        uri = null;
        legacy = null;
    }

    private static void closePfd() {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
        pfd = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stop(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Rekorder", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, RecorderActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecorderService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle("Aufnahme läuft")
            .setContentText("Antippen = Rekorder öffnen")
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - elapsed())
            .addAction(new Notification.Action.Builder(null, "■ Stopp & sichern", stop).build())
            .build();
        if (Build.VERSION.SDK_INT >= 30) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(9, n);
        if (rec == null) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
