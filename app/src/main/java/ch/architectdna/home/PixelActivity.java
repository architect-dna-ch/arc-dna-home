package ch.architectdna.home;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pixel art for the home background: the ARC-DNA wallpaper shows through, its grid squares are
 * your pixels. Tap or drag to fill, pick a colour below, the rubber clears. FERTIG saves; the
 * wallpaper paints the squares half see-through, so the grid stays visible on the home.
 */
public class PixelActivity extends Activity {

    private static final int[] PALETTE = {0xFFB5271E, 0xFF1A1A1A, 0xFFFFC400, 0xFF2BBF6A, 0xFF3C8DFF, 0xFFA77BFF, 0xFFF29A1F, 0xFFF4F2EC, 0xFF6B3E1E, 0xFFFF7FB0};
    private final Map<Long, Integer> cells = new HashMap<>();
    private final List<Map<Long, Integer>> undo = new ArrayList<>();
    private int colour = PALETTE[0];
    private boolean rubber;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        cells.putAll(PixelArt.load(this));
        setContentView(new Canvas_());
    }

    private void done() {
        PixelArt.save(this, cells);
        finish();
    }

    private final class Canvas_ extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF[] swatches = new RectF[PALETTE.length];
        private final RectF rubberBtn = new RectF(), undoBtn = new RectF(), clearBtn = new RectF(), doneBtn = new RectF();
        private final int[] at = new int[2];
        private float barTop;
        private long lastKey = Long.MIN_VALUE;

        Canvas_() {
            super(PixelActivity.this);
            for (int i = 0; i < swatches.length; i++) swatches[i] = new RectF();
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        /** Screen size = the wallpaper's surface (same grid). */
        private float[] screen() {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            return new float[]{dm.widthPixels, dm.heightPixels};
        }

        @Override
        protected void onDraw(Canvas c) {
            getLocationOnScreen(at);
            float[] sc = screen();
            float s = PixelArt.scale(sc[0], sc[1]), cell = PixelArt.cell(s), x0 = PixelArt.x0(s) - at[0], y0 = PixelArt.y0(s) - at[1];
            float w = getWidth(), h = getHeight();
            // The grid, a little stronger than on the wallpaper, so you see where squares are.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0x33B5271E);
            for (float x = x0; x < w; x += cell) c.drawLine(x, 0, x, h, p);
            for (float y = y0; y < h; y += cell) c.drawLine(0, y, w, y, p);
            // The pixels as they'll look (half see-through), translated to this window.
            c.save();
            c.translate(-at[0], -at[1]);
            PixelArt.draw(c, cells, s, p, 150);
            c.restore();

            // The bar at the bottom.
            barTop = h - dp(150);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xE6121211);
            c.drawRoundRect(dp(12), barTop, w - dp(12), h - dp(24), dp(24), dp(24), p);
            float sw = Math.min(dp(40), (w - dp(48)) / PALETTE.length - dp(6));
            float sx = dp(28);
            for (int i = 0; i < PALETTE.length; i++) {
                swatches[i].set(sx, barTop + dp(16), sx + sw, barTop + dp(16) + sw);
                p.setColor(PALETTE[i]);
                c.drawRoundRect(swatches[i], dp(6), dp(6), p);
                if (!rubber && PALETTE[i] == colour) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(3));
                    p.setColor(0xFFFFFFFF);
                    c.drawRoundRect(swatches[i], dp(6), dp(6), p);
                    p.setStyle(Paint.Style.FILL);
                }
                sx += sw + dp(6);
            }
            float by = barTop + dp(16) + sw + dp(14), bw = (w - dp(56) - dp(24)) / 4;
            button(c, rubberBtn, dp(28), by, bw, rubber ? "◻ RADIERER ✓" : "◻ RADIERER", rubber);
            button(c, undoBtn, dp(28) + (bw + dp(8)), by, bw, "↶ ZURÜCK", false);
            button(c, clearBtn, dp(28) + 2 * (bw + dp(8)), by, bw, "LEEREN", false);
            button(c, doneBtn, dp(28) + 3 * (bw + dp(8)), by, bw, "FERTIG ✓", true);
            // Hint on top.
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(11));
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(0xCC121211);
            String hint = "QUADRATE TIPPEN / ZIEHEN = MALEN · " + cells.size() + " PIXEL";
            float hw = p.measureText(hint) + dp(24);
            c.drawRoundRect(w / 2 - hw / 2, dp(48), w / 2 + hw / 2, dp(78), dp(15), dp(15), p);
            p.setColor(0xFFFFFFFF);
            c.drawText(hint, w / 2, dp(68), p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        private void button(Canvas c, RectF r, float x, float y, float w, String label, boolean on) {
            r.set(x, y, x + w, y + dp(40));
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? 0xFFB5271E : 0xFF2A2A28);
            c.drawRoundRect(r, dp(20), dp(20), p);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(dp(11));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(label, r.centerX(), r.centerY() + dp(4), p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            int a = e.getActionMasked();
            if (y >= barTop) {
                if (a != MotionEvent.ACTION_UP) return true;
                for (int i = 0; i < swatches.length; i++) {
                    if (swatches[i].contains(x, y)) {
                        colour = PALETTE[i];
                        rubber = false;
                    }
                }
                if (rubberBtn.contains(x, y)) rubber = !rubber;
                if (undoBtn.contains(x, y) && !undo.isEmpty()) {
                    cells.clear();
                    cells.putAll(undo.remove(undo.size() - 1));
                }
                if (clearBtn.contains(x, y)) {
                    undo.add(new HashMap<>(cells));
                    cells.clear();
                }
                if (doneBtn.contains(x, y)) done();
                invalidate();
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN) {
                undo.add(new HashMap<>(cells));
                if (undo.size() > 40) undo.remove(0);
                lastKey = Long.MIN_VALUE;
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                for (int hst = 0; hst < e.getHistorySize(); hst++) paintAt(e.getHistoricalX(hst), e.getHistoricalY(hst));
                paintAt(x, y);
                invalidate();
            }
            return true;
        }

        private void paintAt(float x, float y) {
            float[] sc = screen();
            float s = PixelArt.scale(sc[0], sc[1]), cell = PixelArt.cell(s);
            float sxp = x + at[0], syp = y + at[1];
            int col = (int) Math.floor((sxp - PixelArt.x0(s)) / cell), row = (int) Math.floor((syp - PixelArt.y0(s)) / cell);
            long k = PixelArt.key(col, row);
            if (k == lastKey) return;
            lastKey = k;
            if (rubber) cells.remove(k);
            else cells.put(k, colour);
        }
    }

    @Override
    public void onBackPressed() {
        done();
    }
}
