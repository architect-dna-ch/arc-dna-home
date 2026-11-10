package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Ambient background data — faint line-diagrams drawn behind the home, never clickable.
 * Four cells: weather, battery, network and free storage. It paints only; it consumes no touch
 * (not clickable), so taps fall straight through to whatever sits beneath it.
 */
public class AmbientView extends View {

    private int ink, ink2, ink3, orange;
    private Integer battery;         // 0..100
    private String net = "—";        // WLAN / 5G / LTE / Offline
    private Float tempC;             // null until weather loads
    private String sky = "";         // short word: klar / wolkig / Regen …
    private Float freeGb, totalGb;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dens;

    public AmbientView(Context c) {
        super(c);
        dens = getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        t.setTextAlign(Paint.Align.LEFT);
    }

    public void setColors(int ink, int ink2, int ink3, int orange) {
        this.ink = ink; this.ink2 = ink2; this.ink3 = ink3; this.orange = orange; invalidate();
    }
    public void setBattery(Integer b) { battery = b; invalidate(); }
    public void setNet(String n) { net = n == null ? "—" : n; invalidate(); }
    public void setWeather(Float temp, String skyWord) { tempC = temp; sky = skyWord == null ? "" : skyWord; invalidate(); }
    public void setStorage(Float free, Float total) { freeGb = free; totalGb = total; invalidate(); }

    // Never eat a touch — the home and its fans live underneath.
    @Override public boolean onTouchEvent(android.view.MotionEvent e) { return false; }

    private float dp(float v) { return v * dens; }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        // A 2×2 block of cells, centred horizontally, sitting in the mid-lower empty band.
        float cellW = Math.min(dp(150), (w - dp(72)) / 2f);
        float cellH = dp(96);
        float gx = dp(16), gy = dp(26);
        float blockW = cellW * 2 + gx, blockH = cellH * 2 + gy;
        float x0 = (w - blockW) / 2f;
        float y0 = h * 0.52f - blockH / 2f;

        cell(c, x0,            y0,            cellW, cellH, 0);
        cell(c, x0 + cellW+gx, y0,            cellW, cellH, 1);
        cell(c, x0,            y0 + cellH+gy, cellW, cellH, 2);
        cell(c, x0 + cellW+gx, y0 + cellH+gy, cellW, cellH, 3);
    }

    private void cell(Canvas c, float x, float y, float w, float h, int which) {
        // faint label
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        t.setColor(ink3); t.setAlpha(150); t.setTextSize(dp(9)); t.setLetterSpacing(0.14f);
        String label = which == 0 ? "WETTER" : which == 1 ? "AKKU" : which == 2 ? "NETZ" : "SPEICHER";
        c.drawText(label, x, y + dp(2), t);
        t.setLetterSpacing(0);

        float dy = y + dp(20);           // where the diagram starts
        switch (which) {
            case 0: weather(c, x, dy, w, h - dp(20)); break;
            case 1: akku(c, x, dy, w, h - dp(20)); break;
            case 2: netz(c, x, dy, w, h - dp(20)); break;
            case 3: speicher(c, x, dy, w, h - dp(20)); break;
        }
    }

    private void bigValue(Canvas c, String s, float x, float baseY) {
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setColor(ink2); t.setAlpha(190); t.setTextSize(dp(30));
        c.drawText(s, x, baseY, t); t.setAlpha(255);
    }
    private void sub(Canvas c, String s, float x, float y) {
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        t.setColor(ink3); t.setAlpha(150); t.setTextSize(dp(10));
        c.drawText(s, x, y, t); t.setAlpha(255);
    }

    private void weather(Canvas c, float x, float y, float w, float h) {
        // sun + cloud glyph
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.6f)); p.setColor(orange); p.setAlpha(120);
        float sx = x + w - dp(26), sy = y + dp(12), r = dp(9);
        c.drawCircle(sx, sy, r, p);
        p.setColor(ink3); p.setAlpha(120);
        RectF cl = new RectF(x + w - dp(40), y + dp(14), x + w - dp(8), y + dp(30));
        c.drawRoundRect(cl, dp(8), dp(8), p);
        p.setAlpha(255);
        bigValue(c, tempC == null ? "—" : Math.round(tempC) + "°", x, y + dp(30));
        sub(c, tempC == null ? "lädt · Bern" : (sky.isEmpty() ? "Bern" : sky + " · Bern"), x, y + dp(48));
    }

    private void akku(Canvas c, float x, float y, float w, float h) {
        bigValue(c, battery == null ? "—" : battery + "%", x, y + dp(30));
        // battery bar
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.4f)); p.setColor(ink3); p.setAlpha(140);
        RectF b = new RectF(x, y + dp(40), x + w - dp(14), y + dp(52));
        c.drawRoundRect(b, dp(3), dp(3), p);
        c.drawRect(b.right + dp(2), b.top + dp(3), b.right + dp(5), b.bottom - dp(3), p);
        if (battery != null) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(battery <= 20 ? 0xFFE8503A : orange); p.setAlpha(150);
            float fw = (b.width() - dp(4)) * Math.max(0.03f, battery / 100f);
            c.drawRoundRect(new RectF(b.left + dp(2), b.top + dp(2), b.left + dp(2) + fw, b.bottom - dp(2)), dp(2), dp(2), p);
        }
        p.setAlpha(255);
    }

    private void netz(Canvas c, float x, float y, float w, float h) {
        bigValue(c, net, x, y + dp(30));
        // three rising bars as a mini signal diagram
        p.setStyle(Paint.Style.FILL); p.setColor(ink3); p.setAlpha(140);
        boolean off = net.toLowerCase().contains("off") || net.equals("—");
        for (int i = 0; i < 3; i++) {
            float bh = dp(6 + i * 6), bx = x + i * dp(9);
            if (off) p.setAlpha(60); else p.setAlpha(140);
            c.drawRect(bx, y + dp(52) - bh, bx + dp(6), y + dp(52), p);
        }
        p.setAlpha(255);
    }

    private void speicher(Canvas c, float x, float y, float w, float h) {
        String v = freeGb == null ? "—" : Math.round(freeGb) + "G";
        bigValue(c, v, x, y + dp(30));
        sub(c, "frei", x, y + dp(48));
        // ring gauge: used fraction
        if (freeGb != null && totalGb != null && totalGb > 0) {
            float cx = x + w - dp(22), cy = y + dp(26), rr = dp(16);
            RectF o = new RectF(cx - rr, cy - rr, cx + rr, cy + rr);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3)); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(ink3); p.setAlpha(70); c.drawArc(o, 0, 360, false, p);
            float used = 1f - freeGb / totalGb;
            p.setColor(orange); p.setAlpha(150); c.drawArc(o, -90, 360 * used, false, p);
            p.setAlpha(255);
        }
    }
}
