package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The track timeline under the music card's waveform, read like a DJ deck: the phases heard so far
 * (BUILD orange, DROP red, BREAK blue, VOCAL green, GROOVE grey) are painted onto the track as it
 * plays, with the playhead and "0:42 / 3:15". Tap or drag to jump; a tap on a DROP jumps to its start.
 * The phases come from the live sound (MusicCard), so a track fills in while you listen to it.
 */
final class PhaseBar extends View {

    interface Seeker {
        void seekTo(long ms);
    }

    enum Phase {
        GROOVE(0xFF8A8A86, "GROOVE"), BUILD(0xFFF29A1F, "BUILD ↗"), DROP(0xFFE0281E, "DROP 🔥"),
        BREAK(0xFF3C8DFF, "BREAK"), VOCAL(0xFF2BBF6A, "VOCAL");

        final int colour;
        final String label;

        Phase(int colour, String label) {
            this.colour = colour;
            this.label = label;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int ink, ink3;
    private final Seeker seeker;
    /** Start positions (ms) and phases heard in the current track. */
    private final List<long[]> marks = new ArrayList<>();
    private long position, duration;
    private boolean dragging;

    PhaseBar(Context c, int ink, int ink3, Seeker seeker) {
        super(c);
        this.ink = ink;
        this.ink3 = ink3;
        this.seeker = seeker;
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    void newTrack() {
        marks.clear();
        invalidate();
    }

    void update(long positionMs, long durationMs, Phase now) {
        if (positionMs < 0) return;
        if (!marks.isEmpty() && positionMs + 2000 < marks.get(marks.size() - 1)[0]) {
            // Jumped back: keep what was heard, drop marks after the new position.
            while (!marks.isEmpty() && marks.get(marks.size() - 1)[0] > positionMs) marks.remove(marks.size() - 1);
        }
        if (now != null && (marks.isEmpty() || marks.get(marks.size() - 1)[1] != now.ordinal())) {
            marks.add(new long[]{positionMs, now.ordinal()});
        }
        position = positionMs;
        duration = durationMs;
        invalidate();
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
        // Track background.
        paint.setColor((ink3 & 0x00FFFFFF) | 0x44000000);
        rect.set(0, barTop, w, barTop + barH);
        c.drawRoundRect(rect, barH / 2, barH / 2, paint);
        if (duration > 0) {
            // Phases heard so far, up to the playhead.
            for (int i = 0; i < marks.size(); i++) {
                long from = marks.get(i)[0];
                long to = i + 1 < marks.size() ? marks.get(i + 1)[0] : position;
                if (to <= from) continue;
                paint.setColor(Phase.values()[(int) marks.get(i)[1]].colour);
                rect.set(w * from / duration, barTop, w * Math.min(to, duration) / duration, barTop + barH);
                c.drawRect(rect, paint);
            }
            // Playhead.
            float x = w * Math.min(position, duration) / duration;
            paint.setColor(0xFF000000 | ink);
            rect.set(x - dp(1.5f), barTop - dp(3), x + dp(1.5f), barTop + barH + dp(3));
            c.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint);
        }
        // Time readout.
        paint.setTextSize(dp(10));
        paint.setColor(ink3);
        String t = duration > 0 ? time(position) + " / " + time(duration) : "ZEIT UNBEKANNT";
        c.drawText(t, 0, barTop + barH + dp(14), paint);
        String hint = "TIPPEN = SPRINGEN";
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
            // A tap inside a DROP (or any phase) lands on that phase's start; a drag lands where you let go.
            long target = at;
            if (!dragging) {
                for (int i = marks.size() - 1; i >= 0; i--) {
                    if (marks.get(i)[0] <= at) {
                        if (marks.get(i)[1] == Phase.DROP.ordinal()) target = marks.get(i)[0];
                        break;
                    }
                }
            }
            seeker.seekTo(target);
        }
        return true;
    }
}
