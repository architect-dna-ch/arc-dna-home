package ch.architectdna.one;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * "ARC" / "Claude" — ARC quick commands by voice, fully offline.
 *
 * A small English speech model (Vosk, ~40 MB, downloaded once) listens on the phone itself, limited
 * to a handful of words — nothing is recorded, stored or sent anywhere. Say "Hey Claude", hear a
 * short beep, then a command (or both in one breath): siesta · music · goal · time · private ·
 * calm · guide · stop listening. Answers are spoken aloud in German. Runs as a foreground service
 * with a notification (Android requires that for the microphone); "Aus" in the notification stops it.
 */
public class HeyService extends Service {

    static final String PREF = "hey_on";
    private static final String CHANNEL = "hey", MODEL = "vosk-model-small-en-us-0.15";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL + ".zip";
    private static final int NOTE = 47;
    // the only phrases the recogniser may hear; anything else becomes [unk] and is ignored
    private static final String GRAMMAR = "[\"arc\", \"ark\", \"claude\", \"cloud\", \"siesta\", \"music\", \"music one\","
        + " \"my goal\", \"goal\", \"goal done\", \"new goal\", \"time\", \"private\", \"calm\", \"breathe\", \"guide\", \"boxing\", \"timer\","
        + " \"play\", \"pause\", \"next\", \"previous\", \"louder\", \"quieter\", \"light\", \"light off\", \"battery\", \"home\", \"checklist\","
        + " \"stop listening\", \"[unk]\"]";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Model model;
    private SpeechService speech;
    private TextToSpeech tts;
    private boolean ttsReady, stopping;
    private long armedUntil;

    static boolean enabled(Context c) { return c.getSharedPreferences("home", MODE_PRIVATE).getBoolean(PREF, false); }

    static void start(Context c) {
        c.getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean(PREF, true).apply();
        c.startForegroundService(new Intent(c, HeyService.class));
    }

    static void stop(Context c) {
        c.getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean(PREF, false).apply();
        c.stopService(new Intent(c, HeyService.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "off".equals(i.getAction())) { stop(this); return START_NOT_STICKY; }
        goForeground("Startet …");
        if (speech == null && model == null) new Thread(this::prepare, "arc-hey").start();
        return START_STICKY;
    }

    private void goForeground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Hey Claude", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, OneHomeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, 1, new Intent(this, HeyService.class).setAction("off"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_one)
            .setContentTitle("Hey Claude")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Aus", off).build())
            .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTE, n);
    }

    private void note(String text) { main.post(() -> { if (!stopping) goForeground(text); }); }

    /* ---------------- model: download once, then listen ---------------- */

    private void prepare() {
        try {
            File dir = new File(getFilesDir(), "vosk"), m = new File(dir, MODEL);
            if (!new File(m, "am").exists()) {
                note("Lädt Sprachmodell (einmalig, ~40 MB) …");
                download(dir);
            }
            note("Lädt …");
            model = new Model(m.getAbsolutePath());
            main.post(this::listen);
        } catch (Exception e) {
            note("Ging nicht: " + e.getMessage() + " · tippen zum Öffnen");
        }

    }

    private void download(File dir) throws Exception {
        dir.mkdirs();
        HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        if (c.getResponseCode() != 200) throw new java.io.IOException("Download " + c.getResponseCode());
        long total = c.getContentLengthLong(), got = 0;
        int lastPct = -1;
        File zip = new File(dir, "model.zip");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) != -1; ) {
                out.write(buf, 0, r);
                got += r;
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (pct / 5 != lastPct / 5) { lastPct = pct; note("Lädt Sprachmodell … " + pct + " %"); }
            }
        }
        String root = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new java.io.FileInputStream(zip))) {
            byte[] buf = new byte[64 * 1024];
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                File f = new File(dir, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) continue;          // no paths outside the folder
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { for (int r; (r = z.read(buf)) != -1; ) o.write(buf, 0, r); }
            }
        }
        zip.delete();
    }

    private void listen() {
        if (stopping || model == null) return;
        try {
            Recognizer rec = new Recognizer(model, 16000.0f, GRAMMAR);
            speech = new SpeechService(rec, 16000.0f);
            speech.startListening(listener);
            tell("listening");
            note("Hört auf «Hey Claude» · offline");
        } catch (Exception e) {
            note("Mikrofon nicht verfügbar · " + e.getMessage());
            main.postDelayed(this::restart, 10_000);
        }
    }

    private void restart() {
        if (stopping) return;
        if (speech != null) { try { speech.stop(); speech.shutdown(); } catch (RuntimeException ignored) { } speech = null; }
        listen();
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onPartialResult(String hyp) { heard(hyp, true); }
        @Override public void onResult(String hyp) { heard(hyp, false); }
        @Override public void onFinalResult(String hyp) { heard(hyp, false); }
        @Override public void onError(Exception e) { main.postDelayed(HeyService.this::restart, 3000); }
        @Override public void onTimeout() { main.postDelayed(HeyService.this::restart, 500); }
    };

    /* ---------------- what was said ---------------- */

    private long actedAt;

    /** Acts on the partial result already (no waiting for the end of the sentence); once per phrase. */
    private void heard(String json, boolean partial) {
        String t;
        try { JSONObject o = new JSONObject(json); t = o.optString(partial ? "partial" : "text", ""); } catch (Exception e) { return; }
        if (System.currentTimeMillis() - actedAt < 2500) return;      // the same phrase again (partial, then final)
        t = t.replace("[unk]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return;
        long now = System.currentTimeMillis();
        // wake words: "ARC" (short, so only together with a command in one breath) or "Claude" (also alone, then a beep)
        String w = " " + t + " ";
        boolean arc = w.contains(" arc ") || w.contains(" ark "), claude = w.contains(" claude ") || w.contains(" cloud ");
        boolean wake = arc || claude;
        String rest = w.replace(" arc ", " ").replace(" ark ", " ").replace(" claude ", " ").replace(" cloud ", " ").trim();
        // while music plays, the recogniser hears command words in the lyrics: then only the deliberate two-step
        // counts — "Claude" on its own (beep), then the command — and only on finished phrases, never "ARC …"
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        boolean music = am != null && am.isMusicActive();
        if (music && partial) return;
        if (music && !claude && now > armedUntil) return;
        if (music && claude && !rest.isEmpty()) return;
        if (claude && rest.isEmpty()) {                                    // "Claude" … (wait for the command)
            if (now > armedUntil) ping();
            tell("armed");
            armedUntil = now + 6000;
            return;
        }
        if (wake && rest.isEmpty()) return;                               // a lone "arc" is too easy to mishear: ignore
        if (!wake && now > armedUntil) return;                            // a command word without a wake word: ignore
        if (rest.isEmpty() || !isCommand(rest)) return;
        armedUntil = 0;
        actedAt = now;
        tell("heard:" + rest);
        run(rest);
    }

    private static boolean isCommand(String r) {
        for (String w : new String[]{"siesta", "music", "goal", "time", "private", "calm", "breathe", "guide", "boxing", "timer", "stop listening",
            "play", "pause", "next", "previous", "louder", "quieter", "light", "battery", "home", "checklist"}) if (r.contains(w)) return true;
        return false;
    }

    /** The home screen listens for these to show what the voice is doing (null when it's not on screen). */
    interface Ui { void state(String s); }
    static volatile Ui ui;
    static volatile String lastState = "";

    static void tell(String s) { lastState = s; Ui u = ui; if (u != null) u.state(s); }

    private void ping() {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            main.postDelayed(tg::release, 400);
        } catch (RuntimeException ignored) { }
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(40, 120));
        } catch (RuntimeException ignored) { }
    }

    private void run(String cmd) {
        ping();
        SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
        String hhmm = new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date());
        if (cmd.contains("stop listening")) { say("«Hey Claude» aus."); stop(this); return; }
        if (cmd.matches(".*\\btime\\b.*")) { say("Es ist " + hhmm + "."); return; }   // "time", not "timer"
        // --- music without opening anything: the same keys a headset sends ---
        if (cmd.contains("pause")) { mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); say("Pause."); return; }
        if (cmd.matches(".*\\bplay\\b.*")) { mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY); say("Play."); return; }
        if (cmd.contains("next")) { mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT); say("Nächster Song."); return; }
        if (cmd.contains("previous")) { mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); say("Zurück."); return; }
        if (cmd.contains("louder") || cmd.contains("quieter")) {
            android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
            if (am != null) for (int k = 0; k < 2; k++)
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    cmd.contains("louder") ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
            return;
        }
        // --- phone ---
        if (cmd.contains("light")) {
            boolean on = !cmd.contains("off") && !torchOn;
            try {
                android.hardware.camera2.CameraManager cm = getSystemService(android.hardware.camera2.CameraManager.class);
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) { cm.setTorchMode(id, on); torchOn = on; say(on ? "Licht an." : "Licht aus."); return; }
                }
            } catch (Exception e) { say("Taschenlampe geht gerade nicht."); }
            return;
        }
        if (cmd.contains("battery")) {
            android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
            int pct = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            say(pct < 0 ? "Akku unbekannt." : "Akku " + pct + " %" + (bm.isCharging() ? ", lädt." : "."));
            return;
        }
        // --- your goal ---
        if (cmd.contains("goal done") || cmd.contains("new goal")) {
            String what = cmd.contains("done") ? "done" : "newgoal";
            say(what.equals("done") ? "Erledigt? Bestätige auf dem Bildschirm." : "Neues Ziel.");
            try {
                startActivity(new Intent(this, OneHomeActivity.class).putExtra("hey", what)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (RuntimeException ignored) { }
            return;
        }
        if (cmd.contains("goal")) {
            String g = p.getString("one_text", "");
            say(g.isEmpty() ? "Du hast gerade kein Ziel aufgeschrieben."
                : p.getBoolean("one_private", false) ? "Privat-Modus ist an. Schau aufs Handy." : "Dein Ziel: " + g);
            return;
        }
        if (cmd.contains("private")) {
            boolean on = !p.getBoolean("one_private", false);
            p.edit().putBoolean("one_private", on).apply();
            say(on ? "Privat-Modus an." : "Privat-Modus aus.");
            return;
        }
        if (cmd.contains("siesta")) {
            if (Siesta.endsAt(this) > System.currentTimeMillis()) { Siesta.stop(this); say("Siesta abgebrochen."); return; }
            if (Siesta.start(this)) {
                say("Siesta neunzig. Ich wecke dich um " + new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(Siesta.endsAt(this))) + ". Schlaf gut.");
            } else say("Der Wecker ging nicht. Öffne ARC One und tippe auf Siesta.");
            return;
        }
        String ui = cmd.contains("music") ? "music" : (cmd.contains("calm") || cmd.contains("breathe")) ? "calm" : cmd.contains("guide") ? "guide"
            : (cmd.contains("boxing") || cmd.contains("timer")) ? "boxing" : cmd.contains("checklist") ? "norm" : cmd.contains("home") ? "home" : null;
        if (ui == null) return;
        say(ui.equals("music") ? "Music One." : ui.equals("calm") ? "Atme mit." : ui.equals("boxing") ? "Rundentimer."
            : ui.equals("norm") ? "Checkliste." : ui.equals("home") ? "ARC One." : "Guidebook.");
        try {
            startActivity(new Intent(this, OneHomeActivity.class).putExtra("hey", ui)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException ignored) { }
    }

    private boolean torchOn;

    private void mediaKey(int code) {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
    }

    /** No voice: the action happens at once, confirmed by a vibration; text only as a short note. */
    private void say(String s) {
        main.post(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show());
    }

    @Override public void onDestroy() {
        stopping = true;
        tell("off");
        main.removeCallbacksAndMessages(null);
        if (speech != null) { try { speech.stop(); speech.shutdown(); } catch (RuntimeException ignored) { } }
        if (model != null) { try { model.close(); } catch (RuntimeException ignored) { } }
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
