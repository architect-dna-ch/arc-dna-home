package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

/**
 * The thumb-fans: press-hold a bottom-corner nub and a radial menu blooms along your thumb's arc.
 * Left = a flat fan (quick apps). Right = the nested Fokus (category ring → app ring, with a lock so
 * reaching an app never flips the category). Slide, release to launch. Overlay only — it grabs touches
 * on its two nubs and passes everything else through, so the rest of the home is untouched.
 */
public class ThumbFanView extends View {

    public static class Item {
        final String label; final Drawable icon; final Runnable action;
        public Item(String label, Drawable icon, Runnable action) { this.label = label; this.icon = icon; this.action = action; }
    }
    public static class Cat {
        final String label; final Item[] apps; final Runnable action;
        public Cat(String label, Item[] apps, Runnable action) { this.label = label; this.apps = apps; this.action = action; }
    }

    private Item[] left = new Item[0];
    private Cat[] right = new Cat[0];
    private int card, line, ink, ink2, orange, glow;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float dens, petalR, nubR, R1, R2, Rflat;
    private float lx, ly, rx, ry;
    private static final float A0 = 8, A1 = 88, SPAN = 24;

    private int side = 0;              // -1 left, 1 right, 0 none
    private boolean active = false;
    private int mode = 0;              // 0 = category ring, 1 = app ring (right only)
    private int flatHot = -1, catHot = -1, appHot = -1, activeCat = -1;

    public ThumbFanView(Context c) {
        super(c);
        dens = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * dens);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setColors(int card, int line, int ink, int ink2, int orange) {
        this.card = card; this.line = line; this.ink = ink; this.ink2 = ink2; this.orange = orange;
        this.glow = (orange & 0x00FFFFFF) | 0x33000000;
        invalidate();
    }
    public void setData(Item[] left, Cat[] right) { this.left = left; this.right = right; invalidate(); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        petalR = 25 * dens; nubR = 27 * dens;
        float base = Math.min(w, h);
        R1 = Math.min(base * 0.24f, 172 * dens);
        R2 = Math.min(base * 0.44f, 300 * dens);
        Rflat = Math.min(base * 0.34f, 245 * dens);
        lx = 42 * dens; ly = h - 42 * dens;
        rx = w - 120 * dens; ry = h - 42 * dens;
    }

    private int nubAt(float x, float y) {
        if (Math.hypot(x - lx, y - ly) < nubR * 1.7f) return -1;
        if (Math.hypot(x - rx, y - ry) < nubR * 1.7f) return 1;
        return 0;
    }
    private float catAng(int i) { int n = right.length; return n <= 1 ? (A0 + A1) / 2 : A0 + (A1 - A0) * i / (n - 1); }
    private float appAng(int ci, int j) {
        int n = right[ci].apps.length; float c = catAng(ci);
        float a = n <= 1 ? c : c - SPAN + 2 * SPAN * j / (n - 1);
        return Math.max(4, Math.min(92, a));
    }
    private float flatAng(int i) { int n = left.length; return n <= 1 ? (A0 + A1) / 2 : A0 + (A1 - A0) * i / (n - 1); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                int s = nubAt(x, y);
                if (s == 0) return false;                 // not on a nub → let it pass through
                side = s; active = true; mode = 0; flatHot = catHot = appHot = activeCat = -1;
                update(x, y); invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                if (!active) return false;
                update(x, y); invalidate(); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!active) return false;
                release(); active = false; side = 0; invalidate(); return true;
        }
        return false;
    }

    private void update(float x, float y) {
        if (side == -1) {
            float dx = x - lx, dy = ly - y, dist = (float) Math.hypot(dx, dy), ang = (float) Math.toDegrees(Math.atan2(dy, dx));
            int best = -1; float bd = 1e9f;
            if (dist > 55 * dens && ang > -8 && ang < 96) for (int i = 0; i < left.length; i++) { float d = Math.abs(ang - flatAng(i)); if (d < bd) { bd = d; best = i; } }
            flatHot = bd > 24 ? -1 : best;
        } else {
            float dx = rx - x, dy = ry - y, dist = (float) Math.hypot(dx, dy), ang = (float) Math.toDegrees(Math.atan2(dy, dx));
            float enterApp = R1 + (R2 - R1) * 0.42f, backCat = R1 * 0.92f;
            if (mode == 1) {
                if (dist < backCat) { mode = 0; appHot = -1; }
                else { if (ang < -12 || ang > 102) return;
                    int best = -1; float bd = 1e9f;
                    for (int j = 0; j < right[activeCat].apps.length; j++) { float d = Math.abs(ang - appAng(activeCat, j)); if (d < bd) { bd = d; best = j; } }
                    appHot = bd > 32 ? -1 : best; return; }
            }
            if (ang < -12 || ang > 102) return;
            int best = -1; float bd = 1e9f;
            for (int i = 0; i < right.length; i++) { float d = Math.abs(ang - catAng(i)); if (d < bd) { bd = d; best = i; } }
            best = bd > 28 ? -1 : best;
            if (best != catHot) { catHot = best; activeCat = best; appHot = -1; }
            if (dist > enterApp && activeCat >= 0) mode = 1;
        }
    }

    private void release() {
        try {
            if (side == -1) { if (flatHot >= 0 && flatHot < left.length) left[flatHot].action.run(); }
            else if (side == 1) {
                if (mode == 1 && appHot >= 0 && activeCat >= 0) right[activeCat].apps[appHot].action.run();
                else if (catHot >= 0 && catHot < right.length) right[catHot].action.run();
            }
        } catch (RuntimeException ignored) { }
    }

    @Override
    protected void onDraw(Canvas c) {
        drawNub(c, lx, ly);
        drawNub(c, rx, ry);
        if (!active) return;
        if (side == -1) {
            for (int i = 0; i < left.length; i++) {
                float a = (float) Math.toRadians(flatAng(i));
                petal(c, lx + Rflat * (float) Math.cos(a), ly - Rflat * (float) Math.sin(a), left[i].label, left[i].icon, i == flatHot, false);
            }
        } else {
            for (int i = 0; i < right.length; i++) {
                float a = (float) Math.toRadians(catAng(i));
                boolean dim = mode == 1 && i != catHot;
                petal(c, rx - R1 * (float) Math.cos(a), ry - R1 * (float) Math.sin(a), right[i].label, null, i == catHot, dim);
            }
            if (activeCat >= 0) {
                Item[] apps = right[activeCat].apps;
                for (int j = 0; j < apps.length; j++) {
                    float a = (float) Math.toRadians(appAng(activeCat, j));
                    petal(c, rx - R2 * (float) Math.cos(a), ry - R2 * (float) Math.sin(a), apps[j].label, apps[j].icon, mode == 1 && j == appHot, false);
                }
            }
        }
    }

    private void drawNub(Canvas c, float cx, float cy) {
        fill.setColor(card); c.drawCircle(cx, cy, nubR, fill);
        stroke.setColor(orange); stroke.setStrokeWidth(1.8f * dens); c.drawCircle(cx, cy, nubR, stroke);
        // the ARC overlapping-square mark
        float s = 11 * dens; stroke.setStrokeWidth(1.5f * dens);
        c.drawRect(cx - s / 2 - 2 * dens, cy - s / 2 + 2 * dens, cx + s / 2 - 2 * dens, cy + s / 2 + 2 * dens, stroke);
        c.drawRect(cx - s / 2 + 2 * dens, cy - s / 2 - 2 * dens, cx + s / 2 + 2 * dens, cy + s / 2 - 2 * dens, stroke);
    }

    private void petal(Canvas c, float cx, float cy, String label, Drawable icon, boolean hot, boolean dim) {
        int a = dim ? 110 : 255;
        fill.setColor(hot ? orange : card); fill.setAlpha(dim ? 150 : 255); c.drawCircle(cx, cy, petalR, fill); fill.setAlpha(255);
        stroke.setColor(hot ? orange : line); stroke.setStrokeWidth((hot ? 2f : 1.5f) * dens); stroke.setAlpha(a); c.drawCircle(cx, cy, petalR, stroke); stroke.setAlpha(255);
        if (icon != null) {
            int sz = (int) (petalR * 1.1f);
            icon.setBounds((int) (cx - sz / 2f), (int) (cy - sz / 2f), (int) (cx + sz / 2f), (int) (cy + sz / 2f));
            icon.setAlpha(a); icon.draw(c); icon.setAlpha(255);
            tp.setColor(hot ? Color.WHITE : ink2); tp.setAlpha(a); tp.setTextSize(9 * dens);
            c.drawText(clip(label, 11), cx, cy + petalR + 12 * dens, tp); tp.setAlpha(255);
        } else {
            tp.setColor(hot ? Color.WHITE : ink); tp.setAlpha(a); tp.setTextSize(label.length() > 7 ? 10 * dens : 12 * dens);
            c.drawText(clip(label, 10), cx, cy + 4 * dens, tp); tp.setAlpha(255);
        }
    }
    private String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
}
