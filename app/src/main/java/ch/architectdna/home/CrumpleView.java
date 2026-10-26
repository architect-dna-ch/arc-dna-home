package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Back from an app: the app as a sheet of paper whooshes up from the bottom, two fists crush it
 * into a ball, and it's thrown in an arc into a bin that pops up next to the buddy. Draws once
 * over the home screen, touches pass through, and it removes itself when done.
 */
final class CrumpleView extends View {

    private static final long WHOOSH = 260, CRUSH = 380, THROW = 520, DROP = 380;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Random rnd = new Random();
    private final float[] wobble = new float[24];
    private final int paper, line, ink, rust;
    private final Drawable icon;
    private final float binX, binY;
    private final Runnable done;
    private Runnable onCrush, onLand;
    private boolean crushed, landed;
    private long start = -1;

    /** Sounds and the buddy's reaction hook in here. */
    void hooks(Runnable crush, Runnable land) {
        onCrush = crush;
        onLand = land;
    }

    CrumpleView(Context c, Drawable icon, float binX, float binY, int paper, int line, int ink, int rust, Runnable done) {
        super(c);
        this.icon = icon;
        this.binX = binX;
        this.binY = binY;
        this.paper = paper;
        this.line = line;
        this.ink = ink;
        this.rust = rust;
        this.done = done;
        for (int i = 0; i < wobble.length; i++) wobble[i] = rnd.nextFloat();
        setClickable(false);
        setFocusable(false);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static float ease(float k) {
        k = Math.max(0, Math.min(1, k));
        return k < 0.5f ? 2 * k * k : 1 - (float) Math.pow(-2 * k + 2, 2) / 2;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (start < 0) start = now;
        long t = now - start;
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h * 0.45f;
        float sheetW = w * 0.62f, sheetH = h * 0.42f, ball = dp(26);

        // The bin pops up next to the buddy as soon as the crushing starts.
        float binA = t < WHOOSH ? 0 : Math.min(1, (t - WHOOSH) / 200f);
        if (t > WHOOSH + CRUSH + THROW + DROP) binA = Math.max(0, 1 - (t - (WHOOSH + CRUSH + THROW + DROP)) / 250f);
        float lid = t > WHOOSH + CRUSH + THROW ? (float) Math.sin(Math.min(1, (t - WHOOSH - CRUSH - THROW) / (float) DROP) * Math.PI) : 0;
        drawBin(c, binA, lid);

        if (!crushed && t >= WHOOSH) {
            crushed = true;
            if (onCrush != null) onCrush.run();
        }
        if (!landed && t >= WHOOSH + CRUSH + THROW) {
            landed = true;
            if (onLand != null) onLand.run();
        }
        if (t < WHOOSH) {
            // 1. Whoosh: the sheet flies up from below the screen into the middle.
            float k = ease(t / (float) WHOOSH);
            drawSheet(c, cx, h + sheetH - (h + sheetH - cy) * k, sheetW, sheetH, 0, 1);
        } else if (t < WHOOSH + CRUSH) {
            // 2. Crush: two fists press in from the sides; the sheet wrinkles into a ball.
            float k = ease((t - WHOOSH) / (float) CRUSH);
            float sw = sheetW + (ball - sheetW) * k, sh = sheetH + (ball - sheetH) * k;
            drawSheet(c, cx, cy, sw, sh, k, 1 - k);
            drawFist(c, cx - sw / 2 - dp(22), cy, -1, k);
            drawFist(c, cx + sw / 2 + dp(22), cy, 1, k);
        } else if (t < WHOOSH + CRUSH + THROW) {
            // 3. Throw: an arc into the bin, spinning.
            float k = (t - WHOOSH - CRUSH) / (float) THROW;
            float x = cx + (binX - cx) * k, y = cy + (binY - dp(30) - cy) * k - (float) Math.sin(k * Math.PI) * h * 0.22f;
            drawBall(c, x, y, ball * (1 - 0.35f * k), k * 720);
        } else if (t < WHOOSH + CRUSH + THROW + DROP * 0.5f) {
            float k = (t - WHOOSH - CRUSH - THROW) / (DROP * 0.5f);
            drawBall(c, binX, binY - dp(30) + dp(30) * k, ball * 0.65f, 720 + k * 90);
        }

        if (t < WHOOSH + CRUSH + THROW + DROP + 260) postInvalidateOnAnimation();
        else if (done != null) post(done);
    }

    /** The paper: a white sheet with lines and the app's icon; wrinkles grow while it's crushed. */
    private void drawSheet(Canvas c, float x, float y, float w, float h, float crush, float iconA) {
        path.reset();
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a = i / (double) n * Math.PI * 2;
            float jag = 1 + crush * 0.28f * (wobble[i] - 0.5f) * 2;
            float px = x + (float) Math.cos(a) * w / 2 * jag * (Math.abs(Math.cos(a)) > 0.7 ? 1 : 1.05f);
            float py = y + (float) Math.sin(a) * h / 2 * jag;
            // A rectangle that rounds and roughens as it's crushed.
            float rx = x + Math.signum((float) Math.cos(a)) * w / 2, ry = y + Math.signum((float) Math.sin(a)) * h / 2;
            float mx = rx + (px - rx) * Math.min(1, crush * 1.6f), my = ry + (py - ry) * Math.min(1, crush * 1.6f);
            if (i == 0) path.moveTo(mx, my);
            else path.lineTo(mx, my);
        }
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(paper);
        p.setShadowLayer(dp(10), 0, dp(4), 0x33000000);
        c.drawPath(path, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(line);
        c.drawPath(path, p);
        // Ruled lines and a centre fold, so it reads as paper (they fade as it's crushed).
        c.save();
        c.clipPath(path);
        p.setColor(rust);
        p.setAlpha((int) (90 * (1 - crush)));
        c.drawLine(x - w / 2 + w * 0.12f, y - h / 2, x - w / 2 + w * 0.12f, y + h / 2, p);
        p.setColor(line);
        p.setAlpha((int) (200 * (1 - crush)));
        for (float ly = y - h / 2 + h * 0.12f; ly < y + h / 2; ly += h * 0.08f) c.drawLine(x - w / 2, ly, x + w / 2, ly, p);
        p.setColor(ink);
        p.setAlpha((int) (40 * (1 - crush)));
        c.drawLine(x, y - h / 2, x, y + h / 2, p);
        c.restore();
        // Wrinkle lines.
        p.setColor(ink);
        p.setAlpha((int) (140 * crush));
        for (int i = 0; i < 6; i++) {
            float a = wobble[i + 12] * 6.28f, len = Math.min(w, h) * 0.45f;
            c.drawLine(x - (float) Math.cos(a) * len / 2, y - (float) Math.sin(a) * len / 2,
                x + (float) Math.cos(a) * len / 2, y + (float) Math.sin(a) * len / 2, p);
        }
        p.setAlpha(255);
        if (icon != null && iconA > 0.05f) {
            int s = (int) Math.min(dp(56), Math.min(w, h) * 0.4f);
            icon.setBounds((int) (x - s / 2f), (int) (y - s / 2f), (int) (x + s / 2f), (int) (y + s / 2f));
            icon.setAlpha((int) (255 * iconA));
            icon.draw(c);
            icon.setAlpha(255);
        }
    }

    /**
     * A fist seen from the side, knuckles towards the paper: a rounded palm, four knuckle bumps,
     * the thumb folded over, and a rust sleeve leading off the screen edge.
     */
    private void drawFist(Canvas c, float x, float y, int side, float k) {
        float s = dp(34);
        c.save();
        if (side > 0) c.scale(-1, 1, x, y);          // draw the left fist; mirror for the right
        p.setStyle(Paint.Style.FILL);
        // Sleeve and forearm.
        p.setColor(rust);
        r.set(x - s * 4, y - s * 0.42f, x - s * 0.95f, y + s * 0.42f);
        c.drawRoundRect(r, s * 0.12f, s * 0.12f, p);
        p.setColor(ink);
        r.set(x - s * 1.05f, y - s * 0.34f, x - s * 0.3f, y + s * 0.34f);
        c.drawRect(r, p);
        // Palm.
        r.set(x - s * 0.55f, y - s * 0.55f, x + s * 0.35f, y + s * 0.6f);
        c.drawRoundRect(r, s * 0.28f, s * 0.28f, p);
        // Four knuckles stacked along the front edge.
        for (int i = 0; i < 4; i++) {
            float ky = y - s * 0.42f + i * s * 0.29f;
            c.drawCircle(x + s * 0.32f, ky, s * 0.17f, p);
        }
        // Thumb folded across.
        p.setColor(paper);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.4f));
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x - s * 0.35f, y + s * 0.18f, x + s * 0.12f, y + s * 0.05f, p);
        for (int i = 1; i < 4; i++) {
            float ky = y - s * 0.42f + i * s * 0.29f - s * 0.145f;
            c.drawLine(x + s * 0.18f, ky, x + s * 0.42f, ky, p);
        }
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }

    /** The crumpled ball, spinning. */
    private void drawBall(Canvas c, float x, float y, float d, float deg) {
        c.save();
        c.rotate(deg, x, y);
        path.reset();
        for (int i = 0; i < 9; i++) {
            double a = i / 9.0 * Math.PI * 2;
            float rr = d / 2 * (0.82f + 0.3f * wobble[i]);
            float px = x + (float) Math.cos(a) * rr, py = y + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(paper);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(ink);
        c.drawPath(path, p);
        c.drawLine(x - d * 0.25f, y - d * 0.1f, x + d * 0.2f, y + d * 0.15f, p);
        c.drawLine(x - d * 0.05f, y - d * 0.3f, x + d * 0.1f, y + d * 0.25f, p);
        c.restore();
    }

    /** A small bin in ink with a rust band; the lid flips up as the ball drops in. */
    private void drawBin(Canvas c, float a, float lid) {
        if (a <= 0) return;
        float bw = dp(34), bh = dp(40), top = binY - bh / 2;
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
        p.setColor(ink);
        p.setAlpha((int) (255 * a));
        path.reset();
        path.moveTo(binX - bw / 2, top);
        path.lineTo(binX + bw / 2, top);
        path.lineTo(binX + bw * 0.4f, top + bh);
        path.lineTo(binX - bw * 0.4f, top + bh);
        path.close();
        c.drawPath(path, p);
        p.setColor(rust);
        p.setAlpha((int) (255 * a));
        c.drawRect(binX - bw * 0.47f, top + bh * 0.3f, binX + bw * 0.47f, top + bh * 0.4f, p);
        // Lid, hinged on the left.
        c.save();
        c.rotate(-lid * 50, binX - bw / 2, top - dp(3));
        p.setColor(ink);
        p.setAlpha((int) (255 * a));
        r.set(binX - bw / 2 - dp(2), top - dp(6), binX + bw / 2 + dp(2), top - dp(1));
        c.drawRoundRect(r, dp(2), dp(2), p);
        c.drawRect(binX - dp(4), top - dp(9), binX + dp(4), top - dp(6), p);
        c.restore();
        p.setAlpha(255);
    }
}
