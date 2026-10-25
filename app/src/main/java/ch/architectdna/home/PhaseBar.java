package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * The track timeline under the music card's waveform, read like a DJ deck: BUILD orange, DROP red,
 * BREAK blue, VOCAL green, GROOVE grey, with the playhead and "0:42 / 3:15". Tap or drag to jump;
 * a tap inside a DROP jumps to where the drop starts.
 *
 * The phases are remembered per song (title + artist) on the phone: the track is split into 120
 * slices and every listen adds votes for the phase heard in each slice. Each slice shows its most
 * voted phase, so the map gets better with every play and is there from the start next time,
 * drops included.
 */
final class PhaseBar extends View {

    interface Seeker {
        void seekTo(long ms);
    }

    enum Phase {
        GROOVE(0xFF8A8A86, "GROOVE"), BUILD(0xFFF29A1F, "BUILD ↗"), DROP(0xFFE0281E, "DROP 🔥"),
        BREAK(0xFF3C8DFF, "BREAK"), VOCAL(0xFF2BBF6A, "VOCAL"),
        // Appended last: stored maps index phases by position.
        INSTRUMENTAL(0xFFA77BFF, "INSTRUMENTAL");

        final int colour;
        final String label;

        Phase(int colour, String label) {
            this.colour = colour;
            this.label = label;
        }
    }

    private static final int SLICES = 120, PHASES = Phase.values().length;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int ink, ink3;
    private final Seeker seeker;
    private final SharedPreferences store;
    private int[][] votes = new int[SLICES][PHASES];
    private String track = "";
    private int plays;
    private long position, duration, lastSave;
    private boolean dirty, dragging, counted;

    PhaseBar(Context c, int ink, int ink3, Seeker seeker) {
        super(c);
        this.ink = ink;
        this.ink3 = ink3;
        this.seeker = seeker;
        store = c.getSharedPreferences("phases2", Context.MODE_PRIVATE);   // v2: maps from the pop-aware reading
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    /** A new song: save the old one's map, load (or start) this one's and count the play. */
    void newTrack(String key) {
        save();
        track = key == null ? "" : key;
        votes = new int[SLICES][PHASES];
        plays = 0;
        String saved = track.isEmpty() ? null : store.getString(track, null);
        if (saved != null) {
            // "plays;6;cells" now; older maps were "plays;cells" with 5 phases per slice.
            String[] parts = saved.split(";", 3);
            try {
                plays = Integer.parseInt(parts[0]);
                int stride = parts.length > 2 ? Integer.parseInt(parts[1]) : 5;
                String body = parts.length > 2 ? parts[2] : parts.length > 1 ? parts[1] : "";
                String[] cells = body.isEmpty() ? new String[0] : body.split(",");
                for (int i = 0; i < cells.length && i < SLICES * stride; i++) {
                    if (!cells[i].isEmpty() && i % stride < PHASES) votes[i / stride][i % stride] = Integer.parseInt(cells[i], 36);
                }
            } catch (NumberFormatException ignored) {
                votes = new int[SLICES][PHASES];
            }
        }
        counted = false;
        invalidate();
    }

    void save() {
        if (!dirty || track.isEmpty()) return;
        StringBuilder b = new StringBuilder().append(plays).append(';').append(PHASES).append(';');
        for (int s = 0; s < SLICES; s++) {
            for (int p = 0; p < PHASES; p++) {
                if (votes[s][p] > 0) b.append(Integer.toString(votes[s][p], 36));
                b.append(',');
            }
        }
        store.edit().putString(track, b.toString()).apply();
        dirty = false;
        lastSave = System.currentTimeMillis();
    }

    /** Called every frame while the card runs; now = the phase heard right now (null = no signal). */
    void update(long positionMs, long durationMs, Phase now) {
        if (positionMs < 0) return;
        position = positionMs;
        duration = durationMs;
        // A play counts once, when the song is heard from (near) its start.
        if (!counted && now != null && !track.isEmpty() && positionMs < 10_000) {
            counted = true;
            plays++;
            dirty = true;
        }
        if (now != null && durationMs > 0 && !dragging) {
            int s = (int) Math.min(SLICES - 1, positionMs * SLICES / durationMs);
            int[] v = votes[s];
            v[now.ordinal()]++;
            if (v[now.ordinal()] > 1500) for (int p = 0; p < PHASES; p++) v[p] /= 2;   // keep it bounded
            dirty = true;
        }
        if (dirty && System.currentTimeMillis() - lastSave > 10_000) save();
        invalidate();
    }

    /** The most voted phase of a slice, or -1 if it was never heard. */
    private int phaseOf(int s) {
        int best = -1, bestVotes = 0;
        for (int p = 0; p < PHASES; p++) {
            if (votes[s][p] > bestVotes) {
                best = p;
                bestVotes = votes[s][p];
            }
        }
        return best;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), barTop = dp(4), barH = dp(10);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((ink3 & 0x00FFFFFF) | 0x44000000);
        rect.set(0, barTop, w, barTop + barH);
        c.drawRoundRect(rect, barH / 2, barH / 2, paint);
        if (duration > 0) {
            // The remembered map: every slice in its most heard phase; what lies ahead is a bit paler.
            float sw = w / SLICES, head = w * Math.min(position, duration) / duration;
            for (int s = 0; s < SLICES; s++) {
                int p = phaseOf(s);
                if (p < 0) continue;
                float x = s * sw;
                paint.setColor(Phase.values()[p].colour);
                paint.setAlpha(x + sw <= head ? 255 : 150);
                rect.set(x, barTop, x + sw + 0.5f, barTop + barH);
                c.drawRect(rect, paint);
            }
            paint.setAlpha(255);
            paint.setColor(0xFF000000 | ink);
            rect.set(head - dp(1.5f), barTop - dp(3), head + dp(1.5f), barTop + barH + dp(3));
            c.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint);
        }
        paint.setTextSize(dp(10));
        paint.setColor(ink3);
        String t = duration > 0 ? time(position) + " / " + time(duration) : "ZEIT UNBEKANNT";
        c.drawText(t, 0, barTop + barH + dp(14), paint);
        String hint = (plays > 1 ? plays + "× GEHÖRT · " : "") + "TIPPEN = SPRINGEN";
        c.drawText(hint, w - paint.measureText(hint), barTop + barH + dp(14), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (duration <= 0) return false;
        int a = e.getActionMasked();
        getParent().requestDisallowInterceptTouchEvent(true);
        long at = (long) (Math.max(0, Math.min(1, e.getX() / getWidth())) * duration);
        if (a == MotionEvent.ACTION_DOWN) dragging = false;
        if (a == MotionEvent.ACTION_MOVE) {
            dragging = true;
            position = at;
            invalidate();
        }
        if (a == MotionEvent.ACTION_UP) {
            // A tap inside a DROP lands where that drop starts; a drag lands where you let go.
            long target = at;
            int s = (int) Math.min(SLICES - 1, at * SLICES / duration);
            if (!dragging && phaseOf(s) == Phase.DROP.ordinal()) {
                while (s > 0 && phaseOf(s - 1) == Phase.DROP.ordinal()) s--;
                target = s * duration / SLICES;
            }
            dragging = false;
            seeker.seekTo(target);
        }
        return true;
    }
}
