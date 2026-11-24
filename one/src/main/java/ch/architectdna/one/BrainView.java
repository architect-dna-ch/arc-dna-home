package ch.architectdna.one;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A brain drawn only with lines, turning slowly in 3D and breathing at 6 breaths a minute. Now and then
 * a rust signal runs along a line — the brain's signals, visible but not urgent. Purely a calm visual:
 * no data, no buttons of its own.
 *
 * The shape is generated, not loaded: two hemispheres (ellipsoids with folds rippled in), a cerebellum
 * at the back and a short brain stem; each point is joined to its nearest neighbours, so the surface
 * reads as a net of direct lines. Plain Canvas, ~30 fps, and a still frame in power-saving mode.
 */
final class BrainView extends View {

    private static final int LINE = 0xF4F1EC, RUST = 0xE0573B;
    private final float[][] pts;               // x, y, z in [-1, 1]
    private final int[][] edges;
    private final float[][] proj;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random(7);
    private final List<float[]> signals = new ArrayList<>();   // {edge, t 0..1, speed}
    private final long born = SystemClock.uptimeMillis();
    private boolean running;
    private float alphaScale = 1f;

    BrainView(Context c) {
        super(c);
        List<float[]> p = new ArrayList<>();
        // two hemispheres, folds rippled into the radius
        for (int side = -1; side <= 1; side += 2) {
            int n = 210;
            for (int i = 0; i < n; i++) {
                double y = 1 - 2.0 * (i + 0.5) / n, r = Math.sqrt(1 - y * y), th = i * 2.399963;   // fibonacci sphere
                double x = Math.cos(th) * r, z = Math.sin(th) * r;
                if (x * side < -0.8) continue;                        // flat inner face: a narrow fissure between the halves
                double fold = 1 + 0.07 * Math.sin(7 * th + 3 * y) * Math.cos(5 * y + side);
                double fx = (0.46 * x * fold) + side * 0.44, fy = 0.6 * y * fold, fz = 0.88 * z * fold;
                if (fy < -0.36) fy = -0.36 + (fy + 0.36) * 0.35;      // flatter underside
                p.add(new float[]{(float) fx, (float) -fy, (float) fz});
            }
        }
        // cerebellum, back and low
        for (int i = 0; i < 26; i++) {
            double y = 1 - 2.0 * (i + 0.5) / 26, r = Math.sqrt(1 - y * y), th = i * 2.399963;
            p.add(new float[]{(float) (0.42 * Math.cos(th) * r), (float) (0.42 - 0.17 * y), (float) (-0.62 + 0.22 * Math.sin(th) * r)});
        }
        // brain stem
        for (int i = 0; i < 6; i++) p.add(new float[]{0, 0.5f + i * 0.07f, -0.28f - i * 0.02f});
        pts = p.toArray(new float[0][]);
        proj = new float[pts.length][3];

        // join each point to its 3 nearest neighbours
        List<int[]> e = new ArrayList<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < pts.length; i++) {
            int[] best = {-1, -1, -1}; float[] bd = {9, 9, 9};
            for (int j = 0; j < pts.length; j++) {
                if (i == j) continue;
                float dx = pts[i][0] - pts[j][0], dy = pts[i][1] - pts[j][1], dz = pts[i][2] - pts[j][2];
                float d = dx * dx + dy * dy + dz * dz;
                for (int k = 0; k < 3; k++) if (d < bd[k]) {
                    for (int m = 2; m > k; m--) { bd[m] = bd[m - 1]; best[m] = best[m - 1]; }
                    bd[k] = d; best[k] = j; break;
                }
            }
            for (int j : best) {
                if (j < 0 || bd[0] > 0.2f) continue;
                long key = Math.min(i, j) * 100_000L + Math.max(i, j);
                if (seen.add(key)) e.add(new int[]{i, j});
            }
        }
        edges = e.toArray(new int[0][]);
        line.setStrokeWidth(dp(1.1f));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Dimmer when it's only the backdrop of the home screen, full strength in calm mode. */
    void setIntensity(float a) { alphaScale = a; invalidate(); }

    void start() { running = true; invalidate(); }
    void stop() { running = false; }

    /** 0..1 breathing phase at 6 breaths/min: up 4 s (in), down 6 s (out). */
    static float breath(long t) {
        double c = (t % 10_000) / 1000.0;
        double v = c < 4 ? c / 4 : 1 - (c - 4) / 6;
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * v));
    }

    @Override protected void onDraw(Canvas cv) {
        long t = SystemClock.uptimeMillis() - born;
        boolean still = isPowerSave();
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float scale = Math.min(w, h) * 0.72f * (1 + 0.035f * (still ? 0.5f : breath(t)));
        double ay = still ? -0.6 : t / 46_000.0 * Math.PI * 2 - 0.6;   // one turn every ~46 s
        double ax = 0.22 + (still ? 0 : 0.05 * Math.sin(t / 9000.0));
        double sy = Math.sin(ay), cyy = Math.cos(ay), sx = Math.sin(ax), cxx = Math.cos(ax);
        for (int i = 0; i < pts.length; i++) {
            float x = pts[i][0], y = pts[i][1], z = pts[i][2];
            double x1 = x * cyy + z * sy, z1 = -x * sy + z * cyy;     // turn around Y
            double y2 = y * cxx - z1 * sx, z2 = y * sx + z1 * cxx;    // tilt around X
            float persp = (float) (1 / (1.9 - z2 * 0.55));
            proj[i][0] = cx + (float) x1 * scale * persp;
            proj[i][1] = cy + (float) y2 * scale * persp;
            proj[i][2] = (float) z2;
        }
        for (int[] e : edges) {
            float z = (proj[e[0]][2] + proj[e[1]][2]) / 2;             // nearer lines are brighter
            int a = (int) (alphaScale * (34 + 120 * (z + 1) / 2));
            line.setColor((Math.max(0, Math.min(255, a)) << 24) | LINE);
            cv.drawLine(proj[e[0]][0], proj[e[0]][1], proj[e[1]][0], proj[e[1]][1], line);
        }
        // signals: a few rust sparks running along lines
        if (!still) {
            if (signals.size() < 5 && rnd.nextFloat() < 0.04f)
                signals.add(new float[]{rnd.nextInt(edges.length), 0, 0.012f + rnd.nextFloat() * 0.02f});
            for (int i = signals.size() - 1; i >= 0; i--) {
                float[] s = signals.get(i);
                s[1] += s[2];
                if (s[1] >= 1) {                                       // hop on to a connected line, or fade
                    int[] cur = edges[(int) s[0]];
                    int next = -1;
                    for (int k = 0; k < 6 && next < 0; k++) {
                        int cand = rnd.nextInt(edges.length);
                        if (edges[cand][0] == cur[1] || edges[cand][1] == cur[1]) next = cand;
                    }
                    if (next < 0 || rnd.nextFloat() < 0.3f) { signals.remove(i); continue; }
                    s[0] = next; s[1] = 0;
                }
                int[] e = edges[(int) s[0]];
                float px = proj[e[0]][0] + (proj[e[1]][0] - proj[e[0]][0]) * s[1];
                float py = proj[e[0]][1] + (proj[e[1]][1] - proj[e[0]][1]) * s[1];
                dot.setColor(((int) (alphaScale * 90) << 24) | RUST);
                cv.drawCircle(px, py, dp(4.5f), dot);
                dot.setColor(((int) (alphaScale * 235) << 24) | RUST);
                cv.drawCircle(px, py, dp(1.9f), dot);
            }
        }
        if (running && !still) postInvalidateDelayed(33);
    }

    private boolean isPowerSave() {
        PowerManager pm = getContext().getSystemService(PowerManager.class);
        return pm != null && pm.isPowerSaveMode();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
