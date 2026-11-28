package ch.architectdna.one;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A living brain, drawn with lines, turning slowly in 3D and breathing at 6 breaths a minute.
 *
 *  - pink: the tissue — the folded surface (gyri). The brain is about 60 % fat; pink is that mass.
 *  - blue: neurons inside the whole volume, each wired to its nearest neighbours (not just the
 *    surface), with a bridge of fibres between the halves like the corpus callosum.
 *  - green: signals. Bursts start at random neurons and race along the fibres in ~0.1 s per hop,
 *    branching on to the next neurons until they fade — a cascade, not a slideshow. A neuron that
 *    receives one flashes.
 *
 * The shape: two hemispheres (a flat inner wall, a flatter underside, a temporal lobe swelling at the
 * side); nothing below (every cerebellum/stem tried read as plates, stripes or a tail). The folds are
 * short meandering lines following a smooth direction field, seeded on an even grid. Runs as fast as
 * the display draws, on purpose: it only runs while it's on screen. Lines are drawn in a few
 * brightness buckets per colour with drawLines, so even ~10k segments stay cheap.
 *
 * Scale: 86 billion neurons in a real brain, ~700 here — the legend says so.
 */
final class BrainView extends View {

    private static final int PINK = 0xF4A0B8, BLUE = 0x4F9DFF, GREEN = 0x5CFF8F;
    private static final int BUCKETS = 6;
    private static final double A = 0.6, B = 0.58, C = 0.92;   // half width, height, length of a hemisphere
    static final int NEURONS = 700;
    private static final int K = 4;                            // nearest neighbours each neuron wires to
    private static final int EDGE_PTS = 5;                     // points per fibre (slightly curved)
    private static final int MAX_SIG = 260;

    // geometry: one flat float array per layer (x,y,z…) plus where each polyline starts
    private final float[] cortex;  private final int[] cortexStart;   // polylines
    private final float[] nodes;                                       // one point per neuron
    private final float[] fib;                                         // EDGE_PTS points per edge
    private final int[] edgeA, edgeB;                                  // neuron at each end
    private final int[][] adj;                                         // neuron → its edges
    private final float[] pc, pn, pf;                                  // projected sx,sy,depth
    private final float[][] bucket = new float[BUCKETS][];
    private final int[] bucketLen = new int[BUCKETS];
    private final float[] flash = new float[NEURONS];

    // signals: edge, direction (+1 a→b, -1 b→a), position 0..1, speed per ms, hops left
    private final int[] sE = new int[MAX_SIG], sD = new int[MAX_SIG], sH = new int[MAX_SIG];
    private final float[] sT = new float[MAX_SIG], sV = new float[MAX_SIG];
    private int sN;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
        legend = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random(11);
    private final long born = SystemClock.uptimeMillis();
    private long last;
    private boolean running, showLegend;
    private float alphaScale = 1f;

    // hold to zoom: a fixed 300 % around the finger, slide to look around, let go to zoom back out
    static final float ZOOM = 3f, LOCK_ZOOM = 2f;
    private boolean zoomable, zoomed, locked, holding;
    private long lockedAt;
    private float zoom = 1f, zoomTarget = 1f, fx, fy, downX, downY, lockX, lockY;
    private Runnable onTap;
    private final Runnable holdStart = () -> {
        zoomed = true; zoomTarget = ZOOM;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    };

    BrainView(Context c) {
        super(c);
        Random r = new Random(5);

        // --- pink: the folded surface ---
        List<float[]> out = new ArrayList<>();
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
        int total = 0;
        for (float[] l : out) total += l.length;
        cortex = new float[total];
        cortexStart = new int[out.size() + 1];
        int at = 0;
        for (int i = 0; i < out.size(); i++) {
            cortexStart[i] = at;
            System.arraycopy(out.get(i), 0, cortex, at, out.get(i).length);
            at += out.get(i).length;
        }
        cortexStart[out.size()] = at;

        // --- blue: neurons through the whole volume of each hemisphere ---
        nodes = new float[NEURONS * 3];
        for (int i = 0; i < NEURONS; i++) {
            int side = i % 2 == 0 ? -1 : 1;
            double th = Math.acos(1 - 2 * r.nextDouble()), ph = r.nextDouble() * Math.PI;
            th = Math.max(0.2, Math.min(2.94, th));
            double[] p = surf(side, th, ph);
            double cx = side * 0.3, cy = 0.02;                       // centre of the half
            double rr = 0.12 + 0.8 * Math.cbrt(r.nextDouble());       // fill the inside evenly
            nodes[i * 3] = (float) (cx + (p[0] - cx) * rr);
            nodes[i * 3 + 1] = (float) (cy + (p[1] - cy) * rr);
            nodes[i * 3 + 2] = (float) (p[2] * rr);
        }
        // each neuron to its K nearest (that crosses the midline where the halves meet — the bridge)
        List<int[]> edges = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        double[] best = new double[K]; int[] bi = new int[K];
        for (int i = 0; i < NEURONS; i++) {
            java.util.Arrays.fill(best, 1e9); java.util.Arrays.fill(bi, -1);
            for (int j = 0; j < NEURONS; j++) {
                if (j == i) continue;
                double dx = nodes[i * 3] - nodes[j * 3], dy = nodes[i * 3 + 1] - nodes[j * 3 + 1], dz = nodes[i * 3 + 2] - nodes[j * 3 + 2];
                double d = dx * dx + dy * dy + dz * dz;
                for (int k = 0; k < K; k++) if (d < best[k]) {
                    for (int m = K - 1; m > k; m--) { best[m] = best[m - 1]; bi[m] = bi[m - 1]; }
                    best[k] = d; bi[k] = j; break;
                }
            }
            for (int k = 0; k < K; k++) {
                int j = bi[k];
                if (j < 0) continue;
                long key = Math.min(i, j) * 100_000L + Math.max(i, j);
                if (seen.add(key)) edges.add(new int[]{i, j});
            }
        }
        for (int n = 0; n < 40; n++) {                                // a few long fibres between the halves
            int i = 2 * r.nextInt(NEURONS / 2), j = 2 * r.nextInt(NEURONS / 2) + 1;
            if (Math.abs(nodes[i * 3 + 1] - nodes[j * 3 + 1]) < 0.25 && Math.abs(nodes[i * 3 + 2] - nodes[j * 3 + 2]) < 0.4)
                edges.add(new int[]{i, j});
        }
        int E = edges.size();
        edgeA = new int[E]; edgeB = new int[E];
        fib = new float[E * EDGE_PTS * 3];
        int[] deg = new int[NEURONS];
        for (int e = 0; e < E; e++) {
            int a = edges.get(e)[0], b = edges.get(e)[1];
            edgeA[e] = a; edgeB[e] = b; deg[a]++; deg[b]++;
            // gently bowed: midpoint pushed sideways a little, like a real axon/dendrite
            float ox = (float) ((r.nextDouble() - 0.5) * 0.06), oy = (float) ((r.nextDouble() - 0.5) * 0.06), oz = (float) ((r.nextDouble() - 0.5) * 0.06);
            for (int s = 0; s < EDGE_PTS; s++) {
                float t = s / (float) (EDGE_PTS - 1), bow = 4 * t * (1 - t);
                for (int q = 0; q < 3; q++) {
                    float o = q == 0 ? ox : q == 1 ? oy : oz;
                    fib[(e * EDGE_PTS + s) * 3 + q] = nodes[a * 3 + q] + (nodes[b * 3 + q] - nodes[a * 3 + q]) * t + o * bow;
                }
            }
        }
        adj = new int[NEURONS][];
        for (int i = 0; i < NEURONS; i++) adj[i] = new int[deg[i]];
        int[] fill = new int[NEURONS];
        for (int e = 0; e < E; e++) { adj[edgeA[e]][fill[edgeA[e]]++] = e; adj[edgeB[e]][fill[edgeB[e]]++] = e; }

        pc = new float[cortex.length]; pn = new float[nodes.length]; pf = new float[fib.length];
        int segs = Math.max(cortex.length / 3, E * (EDGE_PTS - 1)) + 8;
        for (int b = 0; b < BUCKETS; b++) bucket[b] = new float[segs * 4];

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        dot.setStrokeCap(Paint.Cap.ROUND);
        legend.setTextSize(dp(10.5f));
        legend.setTypeface(android.graphics.Typeface.MONOSPACE);
        legend.setLetterSpacing(0.08f);
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

    /** The small key at the top: what the colours are, and how many neurons stand for the real ones. */
    void setLegend(boolean on) { showLegend = on; invalidate(); }

    /** Calm mode: hold to zoom in on the details; a short tap runs {@code tap}. */
    void setZoomable(Runnable tap) { zoomable = true; onTap = tap; setClickable(true); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!zoomable) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                holding = true;
                downX = e.getX(); downY = e.getY();
                if (!locked) { fx = downX; fy = downY; }
                postDelayed(holdStart, ViewConfiguration.getLongPressTimeout() / 2);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (zoomed) { fx = e.getX(); fy = e.getY(); }
                else if (Math.hypot(e.getX() - downX, e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop())
                    removeCallbacks(holdStart);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                holding = false;
                removeCallbacks(holdStart);
                if (zoomed) {                                   // let go after holding: lock at 200 % and drift across
                    zoomed = false; locked = true; lockedAt = SystemClock.uptimeMillis(); zoomTarget = LOCK_ZOOM;
                    lockX = fx; lockY = fy;
                    invalidate();
                } else if (locked && e.getActionMasked() == MotionEvent.ACTION_UP) {   // tap while locked: zoom out
                    locked = false; zoomTarget = 1f;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    invalidate();
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP && onTap != null) onTap.run();
                return true;
            default:
                return true;
        }
    }

    void start() { running = true; last = 0; invalidate(); }
    void stop() { running = false; }

    /** 0..1 breathing phase at 6 breaths/min: up 4 s (in), down 6 s (out). */
    static float breath(long t) {
        double c = (t % 10_000) / 1000.0;
        double v = c < 4 ? c / 4 : 1 - (c - 4) / 6;
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * v));
    }

    @Override protected void onDraw(Canvas cv) {
        long now = SystemClock.uptimeMillis(), t = now - born;
        float dt = last == 0 ? 16 : Math.min(50, now - last);
        last = now;
        float w = getWidth(), h = getHeight(), m = Math.min(w, h), cx = w / 2f;
        // in a wide, short slot (the home screen) the brain is limited by height: fill it and sit dead centre;
        // in a tall or square one (calm mode) keep the old framing with a little room for the legend above
        boolean wide = w > h * 1.25f;
        float cy = wide ? h / 2f : h / 2f - m * 0.04f;
        float scale = m * (wide ? 0.95f : 0.62f) * (1 + 0.035f * breath(t));
        double ay = t / 46_000.0 * Math.PI * 2 - 0.8;                    // one turn every ~46 s
        double ax = 0.25 + 0.05 * Math.sin(t / 9000.0);
        double sy = Math.sin(ay), cyy = Math.cos(ay), sx = Math.sin(ax), cxx = Math.cos(ax);

        if (locked && !holding) {                       // auto-scroll: a slow loop across the brain from where you let go
            double u = (now - lockedAt) / 1000.0;
            float rx = w * 0.26f, ry = h * 0.18f, ease = (float) Math.min(1, u / 2.5);
            float tx = cx + rx * (float) Math.sin(u * 0.21), ty = cy + ry * (float) Math.sin(u * 0.33 + 0.7);
            fx = lockX + (tx - lockX) * ease; fy = lockY + (ty - lockY) * ease;
        }
        zoom += (zoomTarget - zoom) * Math.min(1f, dt / 110f);
        if (Math.abs(zoom - zoomTarget) < 0.002f) zoom = zoomTarget;
        // sizes follow the brain: the small home-screen brain gets small dots (else it turns into a green blob)
        float sizeK = Math.max(0.3f, Math.min(1.3f, m / dp(360)));
        float iz = sizeK / zoom, idot = sizeK * (float) Math.pow(1f / zoom, 0.55);   // lines stay fine when zoomed
        cv.save();
        cv.translate(fx, fy); cv.scale(zoom, zoom); cv.translate(-fx, -fy);

        project(cortex, pc, cx, cy, scale, sy, cyy, sx, cxx);
        project(nodes, pn, cx, cy, scale, sy, cyy, sx, cxx);
        project(fib, pf, cx, cy, scale, sy, cyy, sx, cxx);

        // blue fibres first (inside), then the pink surface over them
        clearBuckets();
        for (int e = 0, E = edgeA.length; e < E; e++) {
            int base = e * EDGE_PTS * 3;
            for (int s = 0; s < EDGE_PTS - 1; s++) addSeg(pf, base + s * 3, base + s * 3 + 3);
        }
        line.setStrokeWidth(dp(0.7f) * iz);
        drawBuckets(cv, BLUE, 18, 120);

        clearBuckets();
        for (int l = 0; l + 1 < cortexStart.length; l++)
            for (int i = cortexStart[l]; i + 5 < cortexStart[l + 1]; i += 3) addSeg(pc, i, i + 3);
        line.setStrokeWidth(dp(1.05f) * iz);
        drawBuckets(cv, PINK, 22, 165);

        // neuron bodies, brighter when a signal just arrived
        for (int i = 0; i < NEURONS; i++) {
            float d = (pn[i * 3 + 2] + 1) / 2, f = flash[i];
            int a = (int) (alphaScale * (60 + 120 * d + 75 * f));
            dot.setColor((clamp(a) << 24) | (f > 0.55f ? GREEN : BLUE));
            cv.drawCircle(pn[i * 3], pn[i * 3 + 1], dp(1.1f + 0.9f * f) * idot, dot);
            flash[i] = f * 0.84f;
        }

        stepSignals(dt);
        for (int k = 0; k < sN; k++) {                                   // green signals with a short trail
            int base = sE[k] * EDGE_PTS * 3;
            for (int tr = 3; tr >= 0; tr--) {
                float pos = sD[k] > 0 ? sT[k] - tr * 0.07f : 1 - sT[k] + tr * 0.07f;
                if (pos < 0 || pos > 1) continue;
                float u = pos * (EDGE_PTS - 1);
                int i0 = Math.min(EDGE_PTS - 2, (int) u); float fr = u - i0;
                int p = base + i0 * 3;
                float px = pf[p] + (pf[p + 3] - pf[p]) * fr, py = pf[p + 1] + (pf[p + 4] - pf[p + 1]) * fr;
                float fade = 1 - tr / 4f;
                if (tr == 0) {
                    dot.setColor((clamp((int) (alphaScale * 55)) << 24) | GREEN);
                    cv.drawCircle(px, py, dp(3f) * idot, dot);
                }
                dot.setColor((clamp((int) (alphaScale * 255 * fade)) << 24) | GREEN);
                cv.drawCircle(px, py, dp(tr == 0 ? 1.5f : 0.9f) * idot, dot);
            }
        }

        cv.restore();
        if (showLegend) drawLegend(cv);
        if (running) nextFrame();
    }

    /** 60–120 fps only while a finger is on it or the zoom moves; otherwise 30 fps (15 in battery saver).
     *  The signals step by real time, so they look the same — the GPU just works a half to a quarter as often. */
    private void nextFrame() {
        if (holding || Math.abs(zoom - zoomTarget) > 0.01f) { postInvalidateOnAnimation(); return; }
        android.os.PowerManager pm = (android.os.PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
        postInvalidateDelayed(pm != null && pm.isPowerSaveMode() ? 66 : 33);
    }

    private void stepSignals(float dt) {
        int bursts = rnd.nextFloat() < dt / 30f ? 1 + rnd.nextInt(3) : 0;     // a few new bursts every second
        for (int b = 0; b < bursts; b++) fire(rnd.nextInt(NEURONS), -1, 3 + rnd.nextInt(6));
        for (int k = sN - 1; k >= 0; k--) {
            sT[k] += sV[k] * dt;
            if (sT[k] < 1) continue;
            int e = sE[k], to = sD[k] > 0 ? edgeB[e] : edgeA[e], hops = sH[k];
            remove(k);
            flash[to] = 1f;
            if (hops > 0) {
                fire(to, e, hops - 1);
                if (rnd.nextFloat() < 0.2f) fire(to, e, hops - 1);        // branch now and then
            }
        }
    }

    /** Send a signal from neuron n along one of its fibres (not back the way it came). */
    private void fire(int n, int cameFrom, int hops) {
        int[] es = adj[n];
        if (es.length == 0 || sN >= MAX_SIG) return;
        int e = es[rnd.nextInt(es.length)];
        for (int tries = 0; e == cameFrom && es.length > 1 && tries < 4; tries++) e = es[rnd.nextInt(es.length)];
        sE[sN] = e;
        sD[sN] = edgeA[e] == n ? 1 : -1;
        sT[sN] = 0;
        sV[sN] = 1f / (70 + rnd.nextInt(110));                           // 70–180 ms per hop
        sH[sN] = hops;
        sN++;
    }

    private void remove(int k) {
        sN--;
        sE[k] = sE[sN]; sD[k] = sD[sN]; sT[k] = sT[sN]; sV[k] = sV[sN]; sH[k] = sH[sN];
    }

    private void drawLegend(Canvas cv) {
        float x = dp(22), y = dp(28), gap = dp(16);
        String[][] rows = {
            {"●", "GEWEBE · ~60 % FETT", "pink"},
            {"●", NEURONS + " NEURONEN · ECHT: 86 MRD.", "blue"},
            {"●", "SIGNALE · " + sN + " GERADE UNTERWEGS", "green"},
        };
        int[] col = {PINK, BLUE, GREEN};
        for (int i = 0; i < rows.length; i++) {
            legend.setColor((clamp((int) (alphaScale * 230)) << 24) | col[i]);
            cv.drawText(rows[i][0], x, y + i * gap, legend);
            legend.setColor((clamp((int) (alphaScale * 150)) << 24) | 0xF4F1EC);
            cv.drawText(rows[i][1], x + dp(14), y + i * gap, legend);
        }
        legend.setColor((clamp((int) (alphaScale * 110)) << 24) | 0xF4F1EC);
        cv.drawText("1 PUNKT ≈ 120 MIO. NEURONEN", x + dp(14), y + 3 * gap, legend);
        cv.drawText(locked && !zoomed ? "ZOOM 200 % · FÄHRT DURCHS GEHIRN · TIPPEN = RAUS"
            : zoom > 1.05f ? "ZOOM " + Math.round(zoom * 100) + " % · LOSLASSEN = 200 % FIXIERT"
            : "HALTEN ZUM ZOOMEN", x + dp(14), y + 4 * gap, legend);
    }

    private static void project(float[] src, float[] dst, float cx, float cy, float scale,
                                double sy, double cyy, double sx, double cxx) {
        for (int i = 0; i < src.length; i += 3) {
            double x = src[i], y = src[i + 1], z = src[i + 2];
            double x1 = x * cyy + z * sy, z1 = -x * sy + z * cyy;     // turn around Y
            double y2 = y * cxx - z1 * sx, z2 = y * sx + z1 * cxx;    // tilt around X
            float ps = (float) (1 / (1.9 - z2 * 0.5));
            dst[i] = cx + (float) x1 * scale * ps;
            dst[i + 1] = cy + (float) y2 * scale * ps;
            dst[i + 2] = (float) z2;
        }
    }

    private void clearBuckets() { for (int b = 0; b < BUCKETS; b++) bucketLen[b] = 0; }

    private void addSeg(float[] P, int i, int j) {                       // nearer segments go to brighter buckets
        float depth = ((P[i + 2] + P[j + 2]) / 2 + 1) / 2;
        int b = Math.max(0, Math.min(BUCKETS - 1, (int) (depth * BUCKETS)));
        float[] dst = bucket[b];
        int n = bucketLen[b];
        dst[n] = P[i]; dst[n + 1] = P[i + 1]; dst[n + 2] = P[j]; dst[n + 3] = P[j + 1];
        bucketLen[b] = n + 4;
    }

    private void drawBuckets(Canvas cv, int rgb, int lo, int span) {
        for (int b = 0; b < BUCKETS; b++) {
            if (bucketLen[b] == 0) continue;
            line.setColor((clamp((int) (alphaScale * (lo + span * (b + 0.5f) / BUCKETS))) << 24) | rgb);
            cv.drawLines(bucket[b], 0, bucketLen[b], line);
        }
    }

    private static int clamp(int a) { return Math.max(0, Math.min(255, a)); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
