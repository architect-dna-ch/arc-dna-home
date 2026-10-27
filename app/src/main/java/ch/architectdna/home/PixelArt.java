package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Pixel art in the wallpaper's blueprint grid: which squares are filled and with what colour.
 * The wallpaper paints them half see-through (the grid stays visible); PixelActivity edits them.
 * The geometry is the wallpaper's own: cells of 84 × (shorter side / 1080) px, the first column
 * at -2, the first row at 40 (same scale).
 */
final class PixelArt {

    private PixelArt() {
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "pixels.txt");
    }

    static long key(int col, int row) {
        return ((long) col << 32) | (row & 0xFFFFFFFFL);
    }

    static Map<Long, Integer> load(Context c) {
        Map<Long, Integer> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file(c)))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split(",");
                if (f.length < 3) continue;
                try {
                    m.put(key(Integer.parseInt(f[0]), Integer.parseInt(f[1])), (int) Long.parseLong(f[2], 16));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return m;
    }

    static void save(Context c, Map<Long, Integer> m) {
        try (FileWriter w = new FileWriter(file(c))) {
            for (Map.Entry<Long, Integer> e : m.entrySet()) {
                int col = (int) (e.getKey() >> 32), row = (int) (long) e.getKey();
                w.write(col + "," + row + "," + Integer.toHexString(e.getValue()) + "\n");
            }
        } catch (IOException ignored) {
        }
    }

    /** Scale of the wallpaper grid for a surface of w × h. */
    static float scale(float w, float h) {
        return Math.min(w, h) / 1080f;
    }

    static float cell(float s) {
        return 84 * s;
    }

    static float x0(float s) {
        return -2 * s;
    }

    static float y0(float s) {
        return 40 * s;
    }

    /** The filled squares, half see-through so the grid lines still show. */
    static void draw(Canvas c, Map<Long, Integer> m, float s, Paint p, int alpha) {
        if (m.isEmpty()) return;
        float cell = cell(s), x0 = x0(s), y0 = y0(s), inset = 1.5f * s;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        for (Map.Entry<Long, Integer> e : m.entrySet()) {
            int col = (int) (e.getKey() >> 32), row = (int) (long) e.getKey();
            float l = x0 + col * cell, t = y0 + row * cell;
            p.setColor((e.getValue() & 0x00FFFFFF) | (alpha << 24));
            c.drawRect(l + inset, t + inset, l + cell - inset, t + cell - inset, p);
        }
    }
}
