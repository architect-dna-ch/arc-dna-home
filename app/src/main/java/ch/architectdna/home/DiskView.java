package ch.architectdna.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/** The ARC Player's medium (jukebox, vinyl, cassette, CD, floppy), shared by ARC Player and ARC Remote. */
final class DiskView extends View {

    interface Host { boolean isPlaying(); long position(); long duration(); }

    private final Host host;
    private final int bg, line, rust, ink3;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path path = new Path();
        private Bitmap art;
        private String label = "";
        private int shell = 0xFF3A3A37;
        private long changedAt;

        DiskView(Context c, Host host, int bg, int line, int rust, int ink3) {
            super(c);
            this.host = host; this.bg = bg; this.line = line; this.rust = rust; this.ink3 = ink3;
        }

        private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
        private boolean isPlaying() { return host.isPlaying(); }
        private long position() { return host.position(); }
        private long duration() { return host.duration(); }

        void set(Bitmap a, String l, int colour) {
            // Only a new title slides in (the cover comes as a fresh copy on every play / pause).
            if (!l.equals(label)) changedAt = SystemClock.uptimeMillis();
            art = a;
            label = l;
            shell = colour == 0 ? 0xFF3A3A37 : colour;
            invalidate();
        }

        /* ---- Eras: jukebox, vinyl, cassette, CD, floppy. Each song gets one; tap = next, hold = automatic again ---- */

        static final int JUKEBOX = 0, VINYL = 1, CASSETTE = 2, CD = 3, FLOPPY = 4;
        private final String[] ERA = {L.t("JUKEBOX · 50ER"), L.t("VINYL · 70ER"), L.t("KASSETTE · 80ER"), L.t("CD · 90ER"), L.t("DISKETTE · COMPUTER")};
        private float spin, arm, lastT;

        int era() {
            return PlayerActivity.eraFor(getContext(), label);
        }

        void nextEra() {
            getContext().getSharedPreferences("player", Context.MODE_PRIVATE).edit().putInt("era", (era() + 1) % 5).apply();
            changedAt = SystemClock.uptimeMillis();
            invalidate();
        }

        void autoEra() {
            getContext().getSharedPreferences("player", Context.MODE_PRIVATE).edit().putInt("era", -1).apply();
            changedAt = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float t = now / 1000f, dt = lastT == 0 ? 0 : Math.min(0.05f, t - lastT);
            lastT = t;
            boolean playing = isPlaying();
            if (playing) spin += dt * 200;            // ~33 rpm look
            arm += ((playing ? 1 : 0) - arm) * Math.min(1, dt * 4);
            int e = era();
            float h = getHeight() - dp(18), size = Math.min(getWidth(), h) * 0.94f, x = (getWidth() - size) / 2, y = (h - size) / 2;
            float in = Math.min(1, (now - changedAt) / 380f);
            in = 1 - (1 - in) * (1 - in);
            if (e == FLOPPY) {
                drawFloppy(c);
            } else {
                c.save();
                c.translate(0, (1 - in) * dp(60));
                if (e == VINYL) vinyl(c, x, y, size);
                else if (e == CASSETTE) cassette(c, x, y, size, t);
                else if (e == CD) cd(c, x, y, size);
                else jukebox(c, x, y, size, t);
                c.restore();
            }
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(9));
            p.setColor(ink3);
            p.setTextAlign(Paint.Align.CENTER);
            p.setAlpha(255);
            c.drawText("♪ " + ERA[e] + L.t(" · TIPPEN = ÄRA WECHSELN"), getWidth() / 2f, getHeight() - dp(2), p);
            p.setTextAlign(Paint.Align.LEFT);
            if (e != FLOPPY || in < 1) postInvalidateOnAnimation();
        }

        /** The cover into a rect (centre crop), or into a circle when round. */
        private void cover(Canvas c, android.graphics.RectF r, boolean round) {
            if (art == null) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(shell);
                if (round) c.drawOval(r, p);
                else c.drawRect(r, p);
                return;
            }
            c.save();
            if (round) {
                Path clip = new Path();
                clip.addOval(r, Path.Direction.CW);
                c.clipPath(clip);
            }
            float sw = art.getWidth(), sh = art.getHeight(), k = Math.max(r.width() / sw, r.height() / sh);
            float cw = r.width() / k, ch = r.height() / k;
            android.graphics.Rect src = new android.graphics.Rect((int) ((sw - cw) / 2), (int) ((sh - ch) / 2), (int) ((sw + cw) / 2), (int) ((sh + ch) / 2));
            c.drawBitmap(art, src, r, p);
            c.restore();
        }

        /** Vinyl: grooves, the cover as the centre label, turning while it plays; the tonearm swings on. */
        private void vinyl(Canvas c, float x, float y, float s) {
            float R = s * 0.46f, cx = x + s * 0.47f, cy = y + s * 0.5f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0B0B0B);
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            for (float r = R * 0.36f; r < R * 0.97f; r += dp(2.2f)) {
                p.setColor(0x22FFFFFF);
                c.drawCircle(cx, cy, r, p);
            }
            // Light catching the grooves (stays put while the record turns).
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(R * 0.5f);
            p.setColor(0x14FFFFFF);
            android.graphics.RectF o = new android.graphics.RectF(cx - R * 0.68f, cy - R * 0.68f, cx + R * 0.68f, cy + R * 0.68f);
            c.drawArc(o, -60, 30, false, p);
            c.drawArc(o, 120, 30, false, p);
            c.save();
            c.rotate(spin, cx, cy);
            cover(c, new android.graphics.RectF(cx - R * 0.34f, cy - R * 0.34f, cx + R * 0.34f, cy + R * 0.34f), true);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFF4F2EC);
            c.drawCircle(cx + R * 0.22f, cy, dp(2.5f), p);          // a mark on the label shows the turning
            c.restore();
            p.setColor(0xFF0B0B0B);
            c.drawCircle(cx, cy, dp(4), p);
            // Tonearm: pivot top right, swings onto the record while playing.
            float px = x + s * 0.97f, py = y + s * 0.1f, len = s * 0.62f;
            double a = Math.toRadians(100 + 22 * arm);
            float ex = px + (float) Math.cos(a) * len, ey = py + (float) Math.sin(a) * len;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(5));
            p.setColor(0xFFC4C4BE);
            c.drawLine(px, py, ex, ey, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF8A8A86);
            c.drawCircle(px, py, dp(12), p);
            c.save();
            c.rotate((float) Math.toDegrees(a) + 90, ex, ey);
            p.setColor(rust);
            c.drawRoundRect(ex - dp(7), ey - dp(4), ex + dp(7), ey + dp(14), dp(3), dp(3), p);
            c.restore();
        }

        /** Cassette: the cover as the sticker, two reels turning, the tape wanders left → right with the song. */
        private void cassette(Canvas c, float x, float y, float s, float t) {
            float w = s, h = s * 0.64f, top = y + (s - h) / 2;
            android.graphics.RectF b = new android.graphics.RectF(x, top, x + w, top + h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(shell);
            c.drawRoundRect(b, dp(10), dp(10), p);
            // Sticker: cover on the left, the title on lines.
            android.graphics.RectF st = new android.graphics.RectF(x + w * 0.06f, top + h * 0.08f, x + w * 0.94f, top + h * 0.62f);
            p.setColor(0xFFF4F2EC);
            c.drawRoundRect(st, dp(6), dp(6), p);
            // Above the reel window only (the cover used to run under the left reel).
            float cs = top + h * 0.40f - st.top - dp(12);
            cover(c, new android.graphics.RectF(st.left + dp(6), st.top + dp(6), st.left + dp(6) + cs, st.top + dp(6) + cs), false);
            p.setColor(0xFF1A1A1A);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(12));
            String l = label.toUpperCase(Locale.GERMAN);
            float room = st.right - dp(10) - (st.left + cs + dp(14));
            if (p.measureText(l) > room) {
                while (l.length() > 3 && p.measureText(l + "…") > room) l = l.substring(0, l.length() - 1);
                l = l + "…";
            }
            c.drawText(l, st.left + cs + dp(14), st.top + dp(22), p);
            p.setColor(0x33B5271E);
            for (int i = 1; i <= 3 && st.top + dp(23) + i * dp(12) < top + h * 0.40f - dp(4); i++) {
                c.drawRect(st.left + cs + dp(14), st.top + dp(22) + i * dp(12), st.right - dp(10), st.top + dp(23) + i * dp(12), p);
            }
            // Window with the two reels.
            android.graphics.RectF win = new android.graphics.RectF(x + w * 0.22f, top + h * 0.40f, x + w * 0.78f, top + h * 0.6f);
            long dur = duration();
            float f = dur > 0 ? Math.min(1, position() / (float) dur) : 0;
            float rMin = h * 0.07f, rMax = h * 0.17f;
            float[] reelX = {x + w * 0.32f, x + w * 0.68f};
            float[] tape = {rMin + (rMax - rMin) * (1 - f), rMin + (rMax - rMin) * f};
            float ry = top + h * 0.5f;
            p.setColor(0xFF2A1A12);
            for (int i = 0; i < 2; i++) c.drawCircle(reelX[i], ry, tape[i], p);
            p.setColor(0x881A1A1A);
            c.drawRoundRect(win, dp(6), dp(6), p);
            for (int i = 0; i < 2; i++) {
                p.setColor(0xFFF4F2EC);
                c.drawCircle(reelX[i], ry, rMin * 0.8f, p);
                c.save();
                c.rotate(spin * (i == 0 ? 1.3f : 1f), reelX[i], ry);
                p.setColor(0xFF1A1A1A);
                for (int k = 0; k < 6; k++) {
                    c.rotate(60, reelX[i], ry);
                    c.drawRect(reelX[i] - dp(1.5f), ry - rMin * 0.75f, reelX[i] + dp(1.5f), ry - rMin * 0.4f, p);
                }
                c.restore();
            }
            // The trapezoid at the bottom with its holes.
            path.reset();
            path.moveTo(x + w * 0.2f, top + h);
            path.lineTo(x + w * 0.26f, top + h * 0.8f);
            path.lineTo(x + w * 0.74f, top + h * 0.8f);
            path.lineTo(x + w * 0.8f, top + h);
            path.close();
            p.setColor(0x33000000);
            c.drawPath(path, p);
            p.setColor(0xFF1A1A1A);
            c.drawCircle(x + w * 0.38f, top + h * 0.9f, dp(3), p);
            c.drawCircle(x + w * 0.62f, top + h * 0.9f, dp(3), p);
        }

        /** CD: the case with the cover, the disc sliding out to the right, rainbow shimmer, spinning. */
        private void cd(Canvas c, float x, float y, float s) {
            float cs = s * 0.74f, top = y + (s - cs) / 2, R = cs * 0.46f;
            float cx = x + cs * 0.62f + R * 0.6f, cy = top + cs / 2;
            p.setStyle(Paint.Style.FILL);
            c.save();
            c.rotate(spin * 1.5f, cx, cy);
            p.setShader(new android.graphics.SweepGradient(cx, cy, new int[]{0xFFD8D8D8, 0xFFB8E0FF, 0xFFF0B8FF, 0xFFFFF0B0, 0xFFB8FFD0,
                0xFFD8D8D8}, null));
            c.drawCircle(cx, cy, R, p);
            p.setShader(null);
            c.restore();
            p.setColor(0x55FFFFFF);
            c.drawCircle(cx, cy, R * 0.32f, p);
            p.setColor(bg);
            c.drawCircle(cx, cy, R * 0.12f, p);
            // The jewel case on top.
            android.graphics.RectF caseR = new android.graphics.RectF(x, top, x + cs, top + cs);
            cover(c, caseR, false);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0x88C4C4BE);
            c.drawRect(caseR, p);
            p.setColor(0x33FFFFFF);
            p.setStrokeWidth(dp(10));
            c.drawLine(x + dp(5), top, x + dp(5), top + cs, p);          // the hinge
            p.setStyle(Paint.Style.FILL);
        }

        /** Jukebox: the neon arch in changing colours, the cover in the window, bubbles rising in the side tubes. */
        private void jukebox(Canvas c, float x, float y, float s, float t) {
            float w = s * 0.78f, left = x + (s - w) / 2, top = y, bottom = y + s;
            path.reset();
            path.moveTo(left, bottom);
            path.lineTo(left, top + w / 2);
            path.arcTo(new android.graphics.RectF(left, top, left + w, top + w), 180, 180);
            path.lineTo(left + w, bottom);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(0, top, 0, bottom, new int[]{0xFF5A2A14, 0xFF8B4A22, 0xFF3A1A0C}, null,
                android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(6));
            p.setColor(android.graphics.Color.HSVToColor(new float[]{(t * 40) % 360, 0.8f, 1f}));
            c.drawPath(path, p);
            p.setStrokeWidth(dp(2));
            p.setColor(0xFFFFE6A8);
            c.drawPath(path, p);
            // Window with the cover.
            android.graphics.RectF win = new android.graphics.RectF(left + w * 0.18f, top + w * 0.22f, left + w * 0.82f, top + w * 0.22f + w * 0.64f);
            cover(c, win, false);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0xFFFFE6A8);
            c.drawRect(win, p);
            // Bubble tubes left and right.
            p.setStyle(Paint.Style.FILL);
            for (int side = 0; side < 2; side++) {
                float tx = side == 0 ? left + w * 0.08f : left + w * 0.92f;
                p.setColor(0x66FF8A3C);
                c.drawRoundRect(tx - dp(6), top + w * 0.5f, tx + dp(6), bottom - dp(10), dp(6), dp(6), p);
                for (int i = 0; i < 5; i++) {
                    float k = ((t * 0.4f + i / 5f + side * 0.1f) % 1f);
                    p.setColor(0xCCFFF2C0);
                    c.drawCircle(tx, bottom - dp(14) - k * (bottom - dp(24) - top - w * 0.5f), dp(3), p);
                }
            }
            // The selection strip with the title.
            p.setColor(0xFFF4F2EC);
            float sy = win.bottom + dp(12);
            c.drawRoundRect(left + w * 0.18f, sy, left + w * 0.82f, sy + dp(24), dp(4), dp(4), p);
            p.setColor(0xFFB5271E);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(11));
            p.setTextAlign(Paint.Align.CENTER);
            // Fitted to the strip (it ran out over the edges).
            String l = "A1 · " + label.toUpperCase(Locale.GERMAN);
            float room = w * 0.64f - dp(14);
            if (p.measureText(l) > room) {
                while (l.length() > 6 && p.measureText(l + "…") > room) l = l.substring(0, l.length() - 1);
                l = l + "…";
            }
            c.drawText(l, left + w / 2, sy + dp(16), p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        private void drawFloppy(Canvas c) {
            float h = getHeight() - dp(18), size = Math.min(getWidth(), h) * 0.94f, x = (getWidth() - size) / 2, y = (h - size) / 2;
            // New track: the disk slides in from below.
            float in = Math.min(1, (SystemClock.uptimeMillis() - changedAt) / 380f);
            in = 1 - (1 - in) * (1 - in);
            c.save();
            c.translate(0, (1 - in) * dp(60));
            p.setAlpha(255);
            float u = size / 100f;
            RectF r = new RectF(x, y, x + size, y + size);
            path.reset();
            path.moveTo(r.left + 3 * u, r.top);
            path.lineTo(r.right - 8 * u, r.top);
            path.lineTo(r.right, r.top + 8 * u);
            path.lineTo(r.right, r.bottom - 3 * u);
            path.quadTo(r.right, r.bottom, r.right - 3 * u, r.bottom);
            path.lineTo(r.left + 3 * u, r.bottom);
            path.quadTo(r.left, r.bottom, r.left, r.bottom - 3 * u);
            path.lineTo(r.left, r.top + 3 * u);
            path.quadTo(r.left, r.top, r.left + 3 * u, r.top);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(shell);
            c.drawPath(path, p);
            // Window + sliding shutter: while it plays, the metal shutter slides open and the magnetic
            // disk spins inside, caught by the light – like a drive reading it.
            float open = arm;                                   // 0 closed … 1 open while playing
            RectF win = new RectF(r.left + 26 * u, r.top + 2 * u, r.right - 22 * u, r.top + 27 * u);
            c.save();
            Path wc = new Path();
            wc.addRoundRect(win, 2 * u, 2 * u, Path.Direction.CW);
            c.clipPath(wc);
            p.setColor(0xFF15110E);
            c.drawRect(win, p);
            // The brown mylar cookie: a big disk centred below, spinning.
            float ckx = win.centerX(), cky = r.top + 60 * u, ckR = 44 * u;
            c.save();
            c.rotate(spin, ckx, cky);
            p.setColor(0xFF4A2F1E);
            c.drawCircle(ckx, cky, ckR, p);
            p.setColor(0x33000000);
            for (float rr = ckR * 0.3f; rr < ckR; rr += 4 * u) c.drawCircle(ckx, cky, rr, p);
            p.setColor(0x55FFC98A);                              // a light glint that turns with it
            c.drawCircle(ckx + ckR * 0.4f, cky - ckR * 0.4f, 4 * u, p);
            c.restore();
            c.restore();
            // The shutter: metal, slides right as it opens; a notch line down the middle.
            float shw = win.width() * 0.62f, sx = win.left - shw * 0.02f + open * (win.width() - shw);
            p.setColor(0xFFBFBFB8);
            c.drawRect(sx, r.top, sx + shw, r.top + 28 * u, p);
            p.setColor(0xFF8E8E88);
            c.drawRect(sx + shw * 0.46f, r.top + 2 * u, sx + shw * 0.5f, r.top + 26 * u, p);
            p.setColor(0x33000000);
            c.drawRect(sx, r.top + 26 * u, sx + shw, r.top + 28 * u, p);
            // Write-protect hole, bottom corners.
            c.drawRect(r.left + 4 * u, r.bottom - 9 * u, r.left + 9 * u, r.bottom - 4 * u, p);
            // Label: the cover, with the title written along the bottom.
            RectF lab = new RectF(r.left + 9 * u, r.top + 36 * u, r.right - 9 * u, r.bottom - 2 * u);
            p.setColor(0xFFF4F2EC);
            c.drawRoundRect(lab, 2 * u, 2 * u, p);
            if (art != null) {
                RectF img = new RectF(lab.left + 2 * u, lab.top + 2 * u, lab.right - 2 * u, lab.bottom - 12 * u);
                float sw = art.getWidth(), sh = art.getHeight(), k = Math.max(img.width() / sw, img.height() / sh);
                float cw = img.width() / k, ch = img.height() / k;
                android.graphics.Rect src = new android.graphics.Rect((int) ((sw - cw) / 2), (int) ((sh - ch) / 2), (int) ((sw + cw) / 2), (int) ((sh + ch) / 2));
                c.drawBitmap(art, src, img, p);
            }
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(5 * u);
            p.setColor(0xFF1A1A1A);
            // Fitted inside the label (long titles ran out over the disk's edge).
            String t = label.toUpperCase(Locale.GERMAN);
            float room = lab.width() - 6 * u;
            if (p.measureText(t) > room) {
                while (t.length() > 3 && p.measureText(t + "…") > room) t = t.substring(0, t.length() - 1);
                t = t + "…";
            }
            c.drawText(t, lab.left + 3 * u, lab.bottom - 4 * u, p);
            c.restore();
            if (isPlaying() || in < 1 || arm > 0.02f) postInvalidateOnAnimation();
        }
    }
