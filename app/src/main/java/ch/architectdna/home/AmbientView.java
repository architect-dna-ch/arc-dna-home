package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Ambient data strip — four faint cells (weather, battery, network, free storage) in one low row,
 * sitting just above the dock so it never collides with the home's content card. It only paints;
 * it is not clickable, so taps fall straight through to whatever sits beneath it.
 */
public class AmbientView extends View {

    private int ink2, ink3, orange;
    private Integer battery;          // 0..100
    private String net = "—";         // WLAN / Mobil / Offline
    private Float tempC;              // null until weather loads
    private String sky = "";          // klar / wolkig / Regen …
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

    public void setColors(int ink2, int ink3, int orange) { this.ink2 = ink2; this.ink3 = ink3; this.orange = orange; invalidate(); }
    public void setBattery(Integer b) { battery = b; invalidate(); }
    public void setNet(String n) { net = n == null ? "—" : n; invalidate(); }
    public void setWeather(Float temp, String skyWord) { tempC = temp; sky = skyWord == null ? "" : skyWord; invalidate(); }
    public void setStorage(Float free, Float total) { freeGb = free; totalGb = total; invalidate(); }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) { return false; }   // never eat a touch

    private float dp(float v) { return v * dens; }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float pad = dp(22);
        float cellW = (w - pad * 2) / 4f;
        float top = h - dp(214);          // a low strip, clear of the dock below and the content above
        for (int i = 0; i < 4; i++) cell(c, pad + i * cellW, top, cellW, i);
    }

    private void cell(Canvas c, float x, float y, float w, int which) {
        float innerW = w - dp(12);
        // label
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        t.setColor(ink3); t.setAlpha(140); t.setTextSize(dp(8.5f)); t.setLetterSpacing(0.14f);
        String label = which == 0 ? "WETTER" : which == 1 ? "AKKU" : which == 2 ? "NETZ" : "SPEICHER";
        c.drawText(label, x, y, t);
        t.setLetterSpacing(0);

        // value
        String val; float frac; boolean low = false;
        switch (which) {
            case 0:  val = tempC == null ? "—" : Math.round(tempC) + "°"; frac = tempC == null ? 0 : Math.max(0.03f, Math.min(1f, (tempC + 10f) / 45f)); break;
            case 1:  val = battery == null ? "—" : battery + "%"; frac = battery == null ? 0 : battery / 100f; low = battery != null && battery <= 20; break;
            case 2:  val = net; frac = net.toLowerCase().contains("off") || net.equals("—") ? 0.08f : net.equals("WLAN") ? 1f : 0.6f; break;
            default: val = freeGb == null ? "—" : Math.round(freeGb) + "G";
                     frac = (freeGb != null && totalGb != null && totalGb > 0) ? Math.max(0.03f, freeGb / totalGb) : 0; break;
        }
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setColor(ink2); t.setAlpha(200); t.setTextSize(dp(23));
        c.drawText(val, x, y + dp(24), t); t.setAlpha(255);

        // a thin diagram bar encoding the value
        float by = y + dp(34), bw = innerW;
        p.setStyle(Paint.Style.FILL);
        p.setColor(ink3); p.setAlpha(55);
        c.drawRoundRect(new RectF(x, by, x + bw, by + dp(3.5f)), dp(2), dp(2), p);
        p.setColor(low ? 0xFFE8503A : orange); p.setAlpha(150);
        c.drawRoundRect(new RectF(x, by, x + bw * Math.max(0.03f, frac), by + dp(3.5f)), dp(2), dp(2), p);
        p.setAlpha(255);

        // weather gets its little sky word under the bar
        if (which == 0 && tempC != null && !sky.isEmpty()) {
            t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            t.setColor(ink3); t.setAlpha(130); t.setTextSize(dp(9));
            c.drawText(sky, x, y + dp(50), t); t.setAlpha(255);
        }
    }
}
