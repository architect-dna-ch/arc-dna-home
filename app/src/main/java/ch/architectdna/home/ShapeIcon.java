package ch.architectdna.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * App icons in Material You shapes instead of squares: rounded hexagon, 4-leaf clover, rounded
 * diamond, soft triangle, scalloped cookie. Each app keeps its shape and pastel (from its package
 * name). Where the app has a themed (monochrome) icon it's drawn like Pixel's themed icons: the
 * glyph in a deep tone of the pastel; otherwise the app's own icon is cut into the shape.
 */
final class ShapeIcon extends Drawable {

    static final int HEXAGON = 0, CLOVER = 1, DIAMOND = 2, TRIANGLE = 3, COOKIE = 4;
    private static final int[][] PASTELS = {     // {fill, glyph}
        {0xFFFFF1B8, 0xFF6B5300}, {0xFFC9F2DD, 0xFF0E5A36}, {0xFFE6DBFF, 0xFF4A2F99},
        {0xFFFFD9E4, 0xFF8A1F45}, {0xFFD3E7FF, 0xFF1D4E8F}, {0xFFFFE0CC, 0xFF8A3A0E}};

    private final Drawable icon;
    private final int shape, fillColour, glyph;
    private final Path path = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ShapeIcon(Drawable icon, String key) {
        this.icon = icon.mutate();
        int h = key == null ? 0 : key.hashCode() & 0x7FFFFFFF;
        shape = h % 5;
        int[] p = PASTELS[(h / 5) % PASTELS.length];
        fillColour = p[0];
        glyph = p[1];
        fill.setColor(fillColour);
    }

    static Drawable wrap(Drawable d, String key) {
        return d == null || d instanceof ShapeIcon ? d : new ShapeIcon(d, key);
    }

    private final Rect shaped = new Rect();

    @Override
    protected void onBoundsChange(Rect b) {
        shaped.set(b);
        float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f;
        path.reset();
        if (shape == HEXAGON) polygon(cx, cy, r, 6, -90, 0.28f);
        else if (shape == DIAMOND) polygon(cx, cy, r * 1.04f, 4, -90, 0.3f);
        else if (shape == TRIANGLE) polygon(cx, cy + r * 0.14f, r * 1.2f, 3, -90, 0.34f);
        else if (shape == CLOVER) polar(cx, cy, r, 4, 0.2f, 45);
        else polar(cx, cy, r, 9, 0.07f, 0);
    }

    /** A regular polygon whose corners are rounded off by a curve through each vertex. */
    private void polygon(float cx, float cy, float r, int n, float startDeg, float round) {
        float[] vx = new float[n], vy = new float[n];
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(startDeg + i * 360.0 / n);
            vx[i] = cx + (float) Math.cos(a) * r;
            vy[i] = cy + (float) Math.sin(a) * r;
        }
        for (int i = 0; i < n; i++) {
            int p = (i + n - 1) % n, q = (i + 1) % n;
            float ax = vx[i] + (vx[p] - vx[i]) * round, ay = vy[i] + (vy[p] - vy[i]) * round;
            float bx = vx[i] + (vx[q] - vx[i]) * round, by = vy[i] + (vy[q] - vy[i]) * round;
            if (i == 0) path.moveTo(ax, ay);
            else path.lineTo(ax, ay);
            path.quadTo(vx[i], vy[i], bx, by);
        }
        path.close();
    }

    /** Lobed shapes (clover, cookie): radius waves with the angle. */
    private void polar(float cx, float cy, float r, int lobes, float depth, float rotDeg) {
        int steps = 144;
        for (int i = 0; i <= steps; i++) {
            double a = Math.PI * 2 * i / steps;
            float rr = r * (1 - depth + depth * (float) Math.cos(lobes * (a - Math.toRadians(rotDeg))));
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (!b.equals(shaped)) onBoundsChange(b);     // shared between tiles of different sizes
        c.save();
        c.clipPath(path);
        Drawable mono = Build.VERSION.SDK_INT >= 33 && icon instanceof AdaptiveIconDrawable
            ? ((AdaptiveIconDrawable) icon).getMonochrome() : null;
        int grow = Math.round(b.width() * 0.25f);
        Rect big = new Rect(b.left - grow, b.top - grow, b.right + grow, b.bottom + grow);
        if (mono != null) {
            // Themed: pastel shape, glyph in its deep tone.
            c.drawPath(path, fill);
            mono.setBounds(big);
            mono.setColorFilter(new PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN));
            mono.draw(c);
        } else if (icon instanceof AdaptiveIconDrawable) {
            // The visible part of an adaptive icon is the middle 2/3 of its layers.
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            if (a.getBackground() != null) {
                a.getBackground().setBounds(big);
                a.getBackground().draw(c);
            } else {
                c.drawPath(path, fill);
            }
            if (a.getForeground() != null) {
                a.getForeground().setBounds(big);
                a.getForeground().draw(c);
            }
        } else {
            c.drawPath(path, fill);
            int in = Math.round(b.width() * 0.2f);
            icon.setBounds(b.left + in, b.top + in, b.right - in, b.bottom - in);
            icon.draw(c);
        }
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        icon.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        icon.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.max(1, icon.getIntrinsicWidth());
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.max(1, icon.getIntrinsicHeight());
    }
}
