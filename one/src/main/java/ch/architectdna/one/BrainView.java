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
 * a rust signal travels along a fold — the brain's signals, visible but not urgent. A calm visual only.
 *
 * The shape is generated: two hemispheres (a flat inner wall, a flatter underside, a temporal lobe
 * swelling at the side), a cerebellum of fine folia arcs at the back, and a short brain stem. The folds
 * are short meandering lines that follow a smooth direction field across the surface, seeded on an even
 * grid so they cover it without clumping — that reads as gyri from every angle, unlike rings or a mesh
 * (both tried first: plates, a melon, two balls). Drawn in a few brightness buckets with drawLines,
 * ~30 fps, and a still frame in power-saving mode.
 */
final class BrainView extends View {

    private static final int LINE = 0xF4F1EC, RUST = 0xE0573B;
    private static final int BUCKETS = 6;
    private static final double A = 0.6, B = 0.58, C = 0.92;   // half width, height, length of a hemisphere

    private final float[][] lines;               // each: x,y,z,x,y,z…
    private final float[][] proj;                // each: sx,sy,depth…
    private final float[][] bucketPts = new float[BUCKETS][];
    private final int[] bucketLen = new int[BUCKETS];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random(11);
    private final List<float[]> signals = new ArrayList<>();   // {line, position along it, speed}
    private final long born = SystemClock.uptimeMillis();
    private boolean running;
    private float alphaScale = 1f;

    BrainView(Context c) {
        super(c);
        List<float[]> out = new ArrayList<>();
        Random r = new Random(5);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 13; i++) for (int j = 0; j < 11; j++) {
                double th = 0.32 + 2.5 * (i + r.nextDouble()) / 13, ph = 0.14 + 2.6 * (j + r.nextDouble()) / 11;
                int steps = 10 + r.nextInt(10);
                float[] buf = new float[steps * 3];
                int n = 0;
                for (int s = 0; s < steps; s++) {
                    double[] p = surf(side, th, ph);
                    buf[n++] = (float) p[0]; buf[n++] = (float) p[1]; buf[n++] = (float) p[2];
                    double a = field(th, ph, side);
                    th += 0.05 * Math.cos(a);
                    ph += 0.05 * Math.sin(a) / Math.max(0.4, Math.sin(th));
                    if (!(th > 0.22 && th < 2.95 && ph > 0.08 && ph < 2.85)) break;
                }
                if (n / 3 > 4) out.add(java.util.Arrays.copyOf(buf, n));
            }
        }
        for (int k = 0; k < 9; k++) {                                   // cerebellum: folia arcs, back half
            double v = -0.9 + 1.8 * k / 8, s = Math.sqrt(1 - v * v);
            float[] buf = new float[41 * 3];
            for (int i = 0; i <= 40; i++) {
                double t = Math.PI + Math.PI * i / 40;
                buf[i * 3] = (float) (0.5 * s * Math.cos(t));
                buf[i * 3 + 1] = (float) (0.42 + 0.17 * v);
                buf[i * 3 + 2] = (float) (-0.62 + 0.26 * s * Math.sin(t));
            }
            out.add(buf);
        }
        for (double dx : new double[]{-0.06, 0, 0.06}) {                // brain stem
            float[] buf = new float[11 * 3];
            for (int j = 0; j <= 10; j++) {
                buf[j * 3] = (float) (dx * (1 - 0.3 * j / 10));
                buf[j * 3 + 1] = (float) (0.4 + 0.05 * j);
                buf[j * 3 + 2] = (float) (-0.26 - 0.02 * j);
            }
            out.add(buf);
        }
        lines = out.toArray(new float[0][]);
        proj = new float[lines.length][];
        int segs = 0;
        for (int i = 0; i < lines.length; i++) { proj[i] = new float[lines[i].length]; segs += lines[i].length / 3 - 1; }
        for (int b = 0; b < BUCKETS; b++) bucketPts[b] = new float[segs * 4];
        line.setStrokeWidth(dp(1.05f));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    private static double[] surf(int side, double th, double ph) {
        double x = side * (0.035 + A * Math.sin(th) * Math.sin(ph));
        double y = -B * Math.sin(th) * Math.cos(ph);
        double z = C * Math.cos(th);
        if (y > 0.28) y = 0.28 + (y - 0.28) * 0.5;                    // flatter underside
        if (ph > 1.55 && z > -0.15 && z < 0.5) x *= 1 + 0.13 * Math.min(1, (ph - 1.55) / 0.5);   // temporal lobe
        if (z < -0.55) y *= 0.92;                                       // occipital tapers a little
        return new double[]{x, y, z};
    }

    /** Smooth direction field on the surface: lines that follow it meander like gyri. */
    private static double field(double th, double ph, int side) {
        return 2.2 * Math.sin(2.3 * th + 1.7 * ph + side) + 1.6 * Math.cos(3.1 * ph - 1.3 * th);
    }

    /** Dimmer as the home screen's backdrop, full strength in calm mode. */
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
        float w = getWidth(), h = getHeight(), m = Math.min(w, h), cx = w / 2f, cy = h / 2f - m * 0.06f;
        float scale = m * 0.62f * (1 + 0.035f * (still ? 0.5f : breath(t)));
        double ay = still ? -0.8 : t / 46_000.0 * Math.PI * 2 - 0.8;    // one turn every ~46 s
        double ax = 0.25 + (still ? 0 : 0.05 * Math.sin(t / 9000.0));
        double sy = Math.sin(ay), cyy = Math.cos(ay), sx = Math.sin(ax), cxx = Math.cos(ax);

        for (int b = 0; b < BUCKETS; b++) bucketLen[b] = 0;
        for (int li = 0; li < lines.length; li++) {
            float[] L = lines[li], P = proj[li];
            for (int i = 0; i < L.length; i += 3) {
                double x = L[i], y = L[i + 1], z = L[i + 2];
                double x1 = x * cyy + z * sy, z1 = -x * sy + z * cyy;     // turn around Y
                double y2 = y * cxx - z1 * sx, z2 = y * sx + z1 * cxx;    // tilt around X
                float ps = (float) (1 / (1.9 - z2 * 0.5));
                P[i] = cx + (float) x1 * scale * ps;
                P[i + 1] = cy + (float) y2 * scale * ps;
                P[i + 2] = (float) z2;
            }
            for (int i = 0; i + 5 < P.length; i += 3) {                 // nearer segments go to brighter buckets
                float depth = ((P[i + 2] + P[i + 5]) / 2 + 1) / 2;
                int b = Math.max(0, Math.min(BUCKETS - 1, (int) (depth * BUCKETS)));
                float[] dst = bucketPts[b];
                int n = bucketLen[b];
                dst[n] = P[i]; dst[n + 1] = P[i + 1]; dst[n + 2] = P[i + 3]; dst[n + 3] = P[i + 4];
                bucketLen[b] = n + 4;
            }
        }
        for (int b = 0; b < BUCKETS; b++) {
            if (bucketLen[b] == 0) continue;
            int a = (int) (alphaScale * (25 + 170 * (b + 0.5f) / BUCKETS));
            line.setColor((Math.max(0, Math.min(255, a)) << 24) | LINE);
            cv.drawLines(bucketPts[b], 0, bucketLen[b], line);
        }

        if (!still) {                                                    // signals travelling along folds
            if (signals.size() < 6 && rnd.nextFloat() < 0.05f)
                signals.add(new float[]{rnd.nextInt(lines.length), 0, 0.12f + rnd.nextFloat() * 0.18f});
            for (int i = signals.size() - 1; i >= 0; i--) {
                float[] s = signals.get(i);
                float[] P = proj[(int) s[0]];
                int pts = P.length / 3;
                s[1] += s[2];
                if (s[1] >= pts - 1) { signals.remove(i); continue; }
                int k = (int) s[1]; float f = s[1] - k;
                float px = P[k * 3] + (P[k * 3 + 3] - P[k * 3]) * f, py = P[k * 3 + 1] + (P[k * 3 + 4] - P[k * 3 + 1]) * f;
                float fade = (float) Math.sin(Math.PI * s[1] / (pts - 1));
                dot.setColor(((int) (alphaScale * 80 * fade) << 24) | RUST);
                cv.drawCircle(px, py, dp(4.5f), dot);
                dot.setColor(((int) (alphaScale * 235 * fade) << 24) | RUST);
                cv.drawCircle(px, py, dp(1.8f), dot);
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
