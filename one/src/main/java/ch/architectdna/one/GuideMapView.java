package ch.architectdna.one;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * The Guidebook as a mind map: "ARC" in the middle, the chapters around it, each chapter's pages as
 * small dots around its node. Read pages glow in the chapter's colour, unread ones are faint rings;
 * the page you're on pulses. Tap a chapter node to jump to its first unread page.
 */
final class GuideMapView extends View {

    private final GuideActivity g;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
        ring = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] nx = new float[0], ny = new float[0];
    private final long born = SystemClock.uptimeMillis();

    GuideMapView(Context c, GuideActivity g) {
        super(c);
        this.g = g;
        line.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    @Override protected void onDraw(Canvas cv) {
        int n = g.chapters.size();
        if (n == 0) return;
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) * 0.34f, r = Math.min(w, h) * 0.085f;
        float t = (SystemClock.uptimeMillis() - born) / 1000f;
        if (nx.length != n) { nx = new float[n]; ny = new float[n]; }

        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / n;
            nx[i] = cx + (float) Math.cos(a) * R;
            ny[i] = cy + (float) Math.sin(a) * R;
        }
        // spokes from the centre, brighter the more of the chapter is read
        line.setStrokeWidth(g.dp(1.2f));
        for (int i = 0; i < n; i++) {
            GuideActivity.Chapter ch = g.chapters.get(i);
            float done = doneShare(ch);
            line.setColor(withAlpha(ch.color, 40 + (int) (150 * done)));
            cv.drawLine(cx, cy, nx[i], ny[i], line);
        }
        // centre
        fill.setColor(0xFF1B1916);
        cv.drawCircle(cx, cy, r * 1.05f, fill);
        ring.setStrokeWidth(g.dp(1.2f));
        ring.setColor(0xFFE0573B);
        cv.drawCircle(cx, cy, r * 1.05f, ring);
        label.setColor(0xFFF4F1EC);
        label.setTextSize(g.dp(13));
        cv.drawText("ARC", cx, cy + g.dp(5), label);

        for (int i = 0; i < n; i++) {
            GuideActivity.Chapter ch = g.chapters.get(i);
            float done = doneShare(ch);
            // pages around the chapter node
            float pr = r * 1.75f;
            for (int k = 0; k < ch.count; k++) {
                int idx = ch.first + k;
                double a = -Math.PI / 2 + 2 * Math.PI * k / Math.max(1, ch.count) + i;
                float px = nx[i] + (float) Math.cos(a) * pr, py = ny[i] + (float) Math.sin(a) * pr;
                line.setStrokeWidth(g.dp(0.8f));
                line.setColor(withAlpha(ch.color, 50));
                cv.drawLine(nx[i], ny[i], px, py, line);
                if (g.read.contains(idx)) {
                    fill.setColor(withAlpha(ch.color, 70));
                    cv.drawCircle(px, py, g.dp(7), fill);
                    fill.setColor(ch.color);
                    cv.drawCircle(px, py, g.dp(3.6f), fill);
                } else if (g.skipped.contains(idx)) {                     // set aside for later: a faint dash
                    line.setStrokeWidth(g.dp(1.4f));
                    line.setColor(withAlpha(ch.color, 70));
                    cv.drawLine(px - g.dp(3.5f), py, px + g.dp(3.5f), py, line);
                } else {
                    ring.setStrokeWidth(g.dp(1));
                    ring.setColor(withAlpha(ch.color, 110));
                    cv.drawCircle(px, py, g.dp(3.6f), ring);
                }
                if (idx == currentPage()) {
                    float pulse = 0.5f + 0.5f * (float) Math.sin(t * 3);
                    ring.setStrokeWidth(g.dp(1.5f));
                    ring.setColor(withAlpha(0xFFF4F1EC, 120 + (int) (120 * pulse)));
                    cv.drawCircle(px, py, g.dp(7 + 3 * pulse), ring);
                }
            }
            // the chapter node
            fill.setColor(0xFF1B1916);
            cv.drawCircle(nx[i], ny[i], r, fill);
            fill.setColor(withAlpha(ch.color, 40 + (int) (120 * done)));
            cv.drawCircle(nx[i], ny[i], r, fill);
            ring.setStrokeWidth(g.dp(1.5f));
            ring.setColor(ch.color);
            cv.drawCircle(nx[i], ny[i], r, ring);
            label.setColor(0xFFF4F1EC);
            label.setTextSize(g.dp(14));
            cv.drawText(String.valueOf(i + 1), nx[i], ny[i] + g.dp(5), label);
        }
        postInvalidateDelayed(40);
    }

    private int currentPage() {
        return g.getSharedPreferences("guide", Context.MODE_PRIVATE).getInt("at", 0);
    }

    private float doneShare(GuideActivity.Chapter ch) {
        int got = 0;
        for (int k = ch.first; k < ch.first + ch.count; k++) if (g.read.contains(k)) got++;
        return ch.count == 0 ? 0 : got / (float) ch.count;
    }

    private static int withAlpha(int color, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float r = Math.min(getWidth(), getHeight()) * 0.085f * 1.6f;
        for (int i = 0; i < nx.length; i++) {
            if (Math.hypot(e.getX() - nx[i], e.getY() - ny[i]) < r) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                g.jumpToChapter(i);
                return true;
            }
        }
        return true;
    }
}
