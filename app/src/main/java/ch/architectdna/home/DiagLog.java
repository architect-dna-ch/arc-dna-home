package ch.architectdna.home;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A small on-device diagnostics log (crashes, visualizer reconnects, wallpaper, alarm, updates)
 * that can be shared from the Mediathek. Only technical events: no app names, song titles,
 * tokens or anything personal goes in here.
 */
final class DiagLog {

    private static final long MAX_BYTES = 120_000;
    private static File file;

    private DiagLog() {
    }

    static synchronized void init(Context c) {
        if (file != null) return;
        file = new File(c.getApplicationContext().getFilesDir(), "diag.log");
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            log("CRASH in " + t.getName() + ": " + sw);
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    static synchronized void log(String event) {
        if (file == null) return;
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + "  " + event + "\n";
        try {
            if (file.length() > MAX_BYTES) {
                // Keep the newer half.
                byte[] all = Files.readAllBytes(file.toPath());
                int from = all.length / 2;
                while (from < all.length && all[from] != '\n') from++;
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(all, Math.min(all.length, from + 1), Math.max(0, all.length - from - 1));
                }
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Logging must never break the launcher.
        }
    }

    static synchronized String read() {
        if (file == null || !file.exists()) return "(leer)";
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(nicht lesbar: " + e + ")";
        }
    }
}
