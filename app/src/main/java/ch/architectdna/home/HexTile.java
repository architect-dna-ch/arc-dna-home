package ch.architectdna.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The card behind an app: a rounded hexagon stretched to the tile (points left and right, flat
 * top and bottom, every corner rounded off) instead of a rounded rectangle.
 */
final class HexTile extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float stroke, corner;

    HexTile(int fillColour, int edgeColour, float stroke, float corner) {
        fill.setColor(fillColour);
        edge.setColor(edgeColour);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
        edge.setStrokeJoin(Paint.Join.ROUND);
        this.stroke = stroke;
        this.corner = corner;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float s = stroke / 2, l = b.left + s, t = b.top + s, r = b.right - s, bt = b.bottom - s, cy = (t + bt) / 2;
        float ix = Math.min((bt - t) * 0.24f, (r - l) * 0.2f);
        float[] x = {l, l + ix, r - ix, r, r - ix, l + ix};
        float[] y = {cy, t, t, cy, bt, bt};
        path.reset();
        for (int i = 0; i < 6; i++) {
            int p = (i + 5) % 6, n = (i + 1) % 6;
            float dp = (float) Math.hypot(x[p] - x[i], y[p] - y[i]), dn = (float) Math.hypot(x[n] - x[i], y[n] - y[i]);
            float cp = Math.min(corner, dp / 2) / dp, cn = Math.min(corner, dn / 2) / dn;
            float ax = x[i] + (x[p] - x[i]) * cp, ay = y[i] + (y[p] - y[i]) * cp;
            float bx = x[i] + (x[n] - x[i]) * cn, by = y[i] + (y[n] - y[i]) * cn;
            if (i == 0) path.moveTo(ax, ay);
            else path.lineTo(ax, ay);
            path.quadTo(x[i], y[i], bx, by);
        }
        path.close();
    }

    @Override
    public void draw(Canvas c) {
        c.drawPath(path, fill);
        if (stroke > 0) c.drawPath(path, edge);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        edge.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        edge.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
