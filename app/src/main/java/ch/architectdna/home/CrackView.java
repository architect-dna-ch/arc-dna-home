package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The glass crack from architect-dna.ch: on a quake 6–8 jagged lines shoot out from one point,
 * draw themselves in ~0.35 s, hold, then fade. Touches pass straight through.
 */
final class CrackView extends View {

    private static final long DRAW_MS = 350, HOLD_MS = 1400, FADE_MS = 800;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final List<Path> lines = new ArrayList<>();
    private final List<Float> lengths = new ArrayList<>();
    private final Path part = new Path();
    private long startedAt = -1;

    CrackView(Context c, int ink) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(ink);
        setClickable(false);
        setFocusable(false);
    }

    void crack(float x, float y) {
        float d = getResources().getDisplayMetrics().density;
        lines.clear();
        lengths.clear();
        int n = 6 + random.nextInt(3);
        for (int i = 0; i < n; i++) {
            double ang = (double) i / n * Math.PI * 2 + random.nextDouble() * 0.6;
            float px = x, py = y, len = 0;
            Path p = new Path();
            p.moveTo(x, y);
            int segs = 5 + random.nextInt(5);
            for (int s = 0; s < segs; s++) {
                float step = (18 + random.nextFloat() * 34) * d;
                ang += (random.nextDouble() - .5) * 0.9;
                px += (float) Math.cos(ang) * step;
                py += (float) Math.sin(ang) * step;
                p.lineTo(px, py);
                len += step;
            }
            lines.add(p);
            lengths.add(len);
        }
        startedAt = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (startedAt < 0) return;
        long t = SystemClock.uptimeMillis() - startedAt;
        if (t > DRAW_MS + HOLD_MS + FADE_MS) {
            startedAt = -1;
            lines.clear();
            return;
        }
        float drawn = Math.min(1f, t / (float) DRAW_MS);
        drawn = 1 - (1 - drawn) * (1 - drawn);                       // ease-out
        float fade = t < DRAW_MS + HOLD_MS ? 1f : 1f - (t - DRAW_MS - HOLD_MS) / (float) FADE_MS;
        paint.setAlpha((int) (153 * fade));                           // 0.6 like the web
        for (int i = 0; i < lines.size(); i++) {
            if (drawn >= 1) {
                c.drawPath(lines.get(i), paint);
            } else {
                PathMeasure m = new PathMeasure(lines.get(i), false);
                part.reset();
                m.getSegment(0, m.getLength() * drawn, part, true);
                c.drawPath(part, paint);
            }
        }
        postInvalidateOnAnimation();
    }
}
