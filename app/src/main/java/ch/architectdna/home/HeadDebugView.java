package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Kamera-Debug: what the front camera sees, mirrored like a selfie, with everything the tracker
 * computes drawn on top — the face box and ML Kit's contours (what NICKEN uses), the eye lids,
 * corners and iris centres from the face mesh (what BLICK used), and the numbers below:
 * head pitch / yaw, the eye values and the scroll decision that comes out right now.
 */
final class HeadDebugView extends View {

    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final int rust;
    private HeadTracker.Debug d;
    private String decision = "";

    HeadDebugView(Context c, int rust) {
        super(c);
        this.rust = rust;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setColor(0xFFFFFFFF);
        img.setAlpha(175);              // see-through: the apps underneath stay visible
        setClickable(false);            // and touches go straight through to them
        setFocusable(false);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        return false;
    }

    void update(HeadTracker.Debug d, String decision) {
        this.d = d;
        this.decision = decision;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), textH = dp(64), ih = getHeight() - textH;
        line.setStyle(Paint.Style.FILL);
        line.setColor(0x66121211);
        r.set(0, 0, w, getHeight());
        c.drawRoundRect(r, dp(14), dp(14), line);
        // A neon rim that breathes: the flex.
        float beat = 0.5f + 0.5f * (float) Math.sin(android.os.SystemClock.uptimeMillis() / 1000f * 3);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.5f));
        line.setColor(((int) (90 + 120 * beat) << 24) | (rust & 0x00FFFFFF));
        r.inset(dp(0.75f), dp(0.75f));
        c.drawRoundRect(r, dp(14), dp(14), line);
        postInvalidateDelayed(50);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(0, 0, w, getHeight(), dp(14), dp(14), android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        if (d == null || d.frame == null) {
            text.setTextSize(dp(10));
            c.drawText("KAMERA STARTET …", dp(8), dp(18), text);
            return;
        }
        // The frame, fitted and mirrored (selfie view); points are mirrored the same way.
        float fw = d.frame.getWidth(), fh = d.frame.getHeight();
        float s = Math.min(w / fw, ih / fh), dw = fw * s, dh = fh * s, ox = (w - dw) / 2, oy = 0;
        c.save();
        c.clipRect(0, 0, w, ih);
        c.scale(-1, 1, w / 2, 0);
        r.set(ox, oy, ox + dw, oy + dh);
        c.drawBitmap(d.frame, null, r, img);
        if (d.box != null) {
            line.setColor(0xFF2BBF6A);
            line.setStrokeWidth(dp(1.5f));
            c.drawRect(ox + d.box.left * dw, oy + d.box.top * dh, ox + d.box.right * dw, oy + d.box.bottom * dh, line);
        }
        line.setColor(rust);
        line.setStrokeWidth(dp(1.2f));
        for (float[] pts : d.contours) {
            if (pts.length < 4) continue;
            path.reset();
            path.moveTo(ox + pts[0] * dw, oy + pts[1] * dh);
            for (int i = 2; i + 1 < pts.length; i += 2) path.lineTo(ox + pts[i] * dw, oy + pts[i + 1] * dh);
            c.drawPath(path, line);
        }
        if (d.eyes != null) {
            line.setColor(0xFF1FA8FF);
            line.setStrokeWidth(dp(1.2f));
            for (int e = 0; e < 2; e++) {
                path.reset();
                for (int k = 0; k < 4; k++) {
                    float x = ox + d.eyes[e * 8 + 2 * k] * dw, y = oy + d.eyes[e * 8 + 2 * k + 1] * dh;
                    if (k == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                c.drawPath(path, line);
            }
        }
        if (d.iris != null) {
            line.setStyle(Paint.Style.FILL);
            line.setColor(0xFFFFE31F);
            for (int k = 0; k < 2; k++) c.drawCircle(ox + d.iris[2 * k] * dw, oy + d.iris[2 * k + 1] * dh, dp(2.5f), line);
            line.setStyle(Paint.Style.STROKE);
        }
        c.restore();

        // Read-out.
        text.setTextSize(dp(9.5f));
        float y = ih + dp(14);
        text.setColor(d.face ? 0xFFFFFFFF : rust);
        c.drawText(d.face ? String.format(java.util.Locale.ROOT, "KOPF  ↕%+5.1f°  ↔%+5.1f°", d.pitch, d.yaw) : "KEIN GESICHT", dp(8), y, text);
        y += dp(13);
        text.setColor(0xFF1FA8FF);
        c.drawText(Float.isNaN(d.gazeV) ? "AUGEN  –" : String.format(java.util.Locale.ROOT, "AUGEN ↕%+.2f  ↔%+.2f", d.gazeV, d.gazeH), dp(8), y, text);
        y += dp(13);
        text.setColor(rust);
        c.drawText(decision, dp(8), y, text);
        y += dp(13);
        text.setColor(0x99FFFFFF);
        c.drawText("ROT NICKEN · BLAU/GELB AUGEN", dp(8), y, text);
    }
}
