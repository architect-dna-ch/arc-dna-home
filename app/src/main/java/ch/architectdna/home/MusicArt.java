package ch.architectdna.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * Small on-device cache of album-art thumbnails, keyed by title+artist, filled as tracks play
 * (from ArcMediaNotice). Lets the Music Mediathek show real covers for songs you've heard; the rest
 * fall back to a generated tile. ~160 px JPEGs, capped at a few hundred, nothing leaves the phone.
 */
final class MusicArt {
    private static final int SIZE = 160, CAP = 600;
    private MusicArt() { }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "musicart");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String fname(String title, String artist) {
        int h = Math.abs(((title == null ? "" : title) + "\u0001" + (artist == null ? "" : artist)).hashCode());
        return Integer.toHexString(h) + ".jpg";
    }

    static void save(Context c, String title, String artist, Bitmap bm) {
        if (bm == null || (title == null && artist == null)) return;
        File f = new File(dir(c), fname(title, artist));
        if (f.exists()) { f.setLastModified(System.currentTimeMillis()); return; }
        try {
            float r = Math.min((float) SIZE / bm.getWidth(), (float) SIZE / bm.getHeight());
            if (r <= 0) return;
            Bitmap s = r < 1 ? Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * r)), Math.max(1, Math.round(bm.getHeight() * r)), true) : bm;
            try (FileOutputStream o = new FileOutputStream(f)) { s.compress(Bitmap.CompressFormat.JPEG, 82, o); }
            prune(c);
        } catch (Exception ignored) { }
    }

    static Drawable load(Context c, String title, String artist) {
        try {
            File f = new File(dir(c), fname(title, artist));
            if (!f.exists()) return null;
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            return bm == null ? null : new BitmapDrawable(c.getResources(), bm);
        } catch (Exception e) { return null; }
    }

    private static void prune(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null || fs.length <= CAP) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < fs.length - CAP; i++) try { fs[i].delete(); } catch (Exception ignored) { }
    }
}
