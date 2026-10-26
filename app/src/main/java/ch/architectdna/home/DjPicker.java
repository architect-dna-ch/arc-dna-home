package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * "Wer legt auf?": before the music starts, five buddies offer themselves as DJ, each a retro floppy
 * disk with its kind of music on the label. Tap one: it slides into the drive, the LED blinks, the
 * playlist "loads" — then the music app gets the search and starts playing.
 */
final class DjPicker extends FrameLayout {

    static final class Dj {
        final String name, kind, about, query;
        final int art, colour;

        Dj(String name, String kind, String about, String query, int art, int colour) {
            this.name = name;
            this.kind = kind;
            this.about = about;
            this.query = query;
            this.art = art;
            this.colour = colour;
        }
    }

    interface Listener {
        void onPick(Dj dj);

        void onClose();
    }

    private final Listener listener;
    private final List<Dj> djs = new ArrayList<>();
    private final Stage stage;
    private int recommended;

    /** favourite: the most played artist of the week (or null). */
    DjPicker(Context c, String favourite, Listener l) {
        super(c);
        listener = l;
        djs.add(new Dj("DJ BÄR", "LO-FI & CHILL", "Zum Runterkommen. Langsam, warm, mit Knistern.",
            "lofi hip hop chill mix", R.drawable.bear, 0xFF8B5A2B));
        djs.add(new Dj("DJ F1", "DRUM & BASS · TECHNO", "Vollgas. 170 BPM, Boxenstopp nur zum Atmen.",
            "drum and bass mix", R.drawable.f1buddy, 0xFFB5271E));
        djs.add(new Dj("DJ FUCHS", favourite != null ? "DEINE WOCHE" : "DEUTSCHRAP",
            favourite != null ? "Mehr wie " + favourite + " – dein meistgehörter Name diese Woche." : "Deutschrap und Hip-Hop, frisch.",
            favourite != null ? favourite + " radio" : "deutschrap hits", R.drawable.fox, 0xFFE07A2E));
        djs.add(new Dj("DJ HASE", "POP & DANCE", "Gute Laune, Refrains zum Mitsingen.",
            "dance pop hits", R.drawable.rabbit, 0xFF2BA85A));
        djs.add(new Dj("DJ BUNNY", "INDIE & AKUSTIK", "Sanft: Gitarre, Stimme, Morgenkaffee.",
            "indie acoustic chill", R.drawable.bunnybuddy, 0xFF7A5AD9));
        // Who fits the time of day.
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        recommended = h < 10 ? 4 : h < 14 ? 3 : h < 18 ? 2 : 0;
        if (h >= 16 && h < 20 && favourite != null) recommended = 2;
        setClickable(true);
        stage = new Stage();
        addView(stage, new LayoutParams(-1, -1));
        setAlpha(0f);
        animate().alpha(1f).setDuration(200).start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Draws everything: dimmed back, title, the five disks, the drive; handles taps and the insert. */
    private final class Stage extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF[] cards = new RectF[5];
        private final RectF drive = new RectF();
        private final Drawable[] art = new Drawable[5];
        private int picked = -1;
        private long pickedAt, openedAt = SystemClock.uptimeMillis();
        private boolean fired;

        Stage() {
            super(DjPicker.this.getContext());
            for (int i = 0; i < 5; i++) {
                cards[i] = new RectF();
                try {
                    art[i] = getContext().getDrawable(djs.get(i).art);
                } catch (RuntimeException ignored) {
                }
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            long now = SystemClock.uptimeMillis();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xEE0E0E0D);
            c.drawRect(0, 0, w, h, p);
            // Title.
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setLetterSpacing(0.2f);
            p.setTextSize(dp(12));
            p.setColor(0xFF9A9A98);
            c.drawText("— WER LEGT AUF?", dp(24), dp(84), p);
            p.setLetterSpacing(0);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(dp(24));
            p.setColor(0xFFF4F2EC);
            c.drawText(picked < 0 ? "Wähl deinen DJ." : djs.get(picked).name + " legt auf …", dp(24), dp(118), p);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(11));
            p.setColor(0xFF6F6E69);
            c.drawText(picked < 0 ? "DISKETTE ANTIPPEN · ✕ = SPÄTER" : "PLAYLIST WIRD GELADEN", dp(24), dp(140), p);
            // Close.
            p.setTextSize(dp(20));
            p.setColor(0xFFF4F2EC);
            c.drawText("✕", w - dp(44), dp(86), p);

            // Disks: two columns (three on wide screens).
            int cols = w > dp(600) ? 3 : 2, rows = (5 + cols - 1) / cols;
            float gap = dp(14), top = dp(164), driveTop = h - dp(150);
            float cw = (w - dp(48) - gap * (cols - 1)) / cols, ch = cw * 1.12f;
            float fit = (driveTop - dp(16) - top - gap * (rows - 1)) / rows;      // all rows above the drive
            if (ch > fit) {
                ch = fit;
                cw = ch / 1.12f;
            }
            float left0 = (w - (cw * cols + gap * (cols - 1))) / 2;
            drive.set(w / 2 - dp(120), driveTop, w / 2 + dp(120), driveTop + dp(70));
            for (int i = 0; i < 5; i++) {
                int col = i % cols, row = i / cols;
                cards[i].set(left0 + col * (cw + gap), top + row * (ch + gap), left0 + col * (cw + gap) + cw, top + row * (ch + gap) + ch);
            }
            float appear = Math.min(1, (now - openedAt) / 400f);
            for (int i = 0; i < 5; i++) {
                if (i == picked) continue;
                float fade = picked < 0 ? 1 : Math.max(0, 1 - (now - pickedAt) / 250f);
                if (fade <= 0) continue;
                c.save();
                c.translate(0, (1 - appear) * dp(30) * (1 + i * 0.3f));
                floppy(c, cards[i], i, fade * appear, i == recommended && picked < 0);
                c.restore();
            }
            // The drive: a dark slot with a green LED.
            driveBox(c, now);
            if (picked >= 0) {
                // The chosen disk flies to the drive and slides in.
                float t = (now - pickedAt) / 1000f;
                float fly = ease(Math.min(1, t / 0.55f)), in = ease(Math.max(0, Math.min(1, (t - 0.55f) / 0.45f)));
                RectF from = cards[picked];
                float tw = drive.width() * 0.62f, th = tw * 1.12f;
                float cx = from.centerX() + (drive.centerX() - from.centerX()) * fly;
                float cy = from.centerY() + (drive.top - th / 2 + dp(8) - from.centerY()) * fly + in * th * 0.9f;
                float sw = from.width() + (tw - from.width()) * fly, sh = from.height() + (th - from.height()) * fly;
                RectF r = new RectF(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2);
                c.save();
                c.clipRect(0, 0, w, drive.top + dp(14));       // disappears into the slot
                floppy(c, r, picked, 1, false);
                c.restore();
                driveFront(c);
                if (t > 1.0f) {
                    // Loading line.
                    int dots = (int) ((t - 1) * 6) % 4;
                    p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                    p.setTextSize(dp(12));
                    p.setColor(0xFF2BD96B);
                    p.setTextAlign(Paint.Align.CENTER);
                    StringBuilder bar = new StringBuilder();
                    int fill = (int) Math.min(12, (t - 1) * 9);
                    for (int k = 0; k < 12; k++) bar.append(k < fill ? '▮' : '▯');
                    c.drawText(bar + "  " + djs.get(picked).kind + ".".repeat(dots), w / 2, drive.bottom + dp(28), p);
                    p.setTextAlign(Paint.Align.LEFT);
                }
                if (t > 2.4f && !fired) {
                    fired = true;
                    listener.onPick(djs.get(picked));
                }
            } else {
                driveFront(c);
            }
            postInvalidateOnAnimation();
        }

        private void driveBox(Canvas c, long now) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2A28);
            c.drawRoundRect(drive, dp(10), dp(10), p);
            p.setColor(0xFF121211);
            c.drawRoundRect(drive.left + dp(30), drive.top + dp(10), drive.right - dp(30), drive.top + dp(18), dp(3), dp(3), p);
            boolean busy = picked >= 0 && (now - pickedAt) > 1000;
            p.setColor(busy && (now / 120) % 2 == 0 ? 0xFF2BD96B : picked >= 0 ? 0xFF1F5E37 : 0xFF3A3A37);
            c.drawCircle(drive.right - dp(22), drive.bottom - dp(18), dp(4), p);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(9));
            p.setColor(0xFF6F6E69);
            c.drawText("ARC-DNA · 3½\" DJ-DRIVE", drive.left + dp(16), drive.bottom - dp(15), p);
        }

        /** The drive's front lip over the inserted disk. */
        private void driveFront(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2A28);
            c.drawRect(drive.left + dp(24), drive.top + dp(14), drive.right - dp(24), drive.top + dp(22), p);
        }

        /** A 3½" floppy: coloured shell with a notch, metal shutter, white label with the DJ. */
        private void floppy(Canvas c, RectF r, int i, float alpha, boolean glow) {
            Dj d = djs.get(i);
            int a = (int) (255 * alpha);
            float u = r.width() / 100f;
            if (glow) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(0xFFFFC400);
                p.setAlpha((int) (a * (0.5f + 0.5f * Math.abs(Math.sin(SystemClock.uptimeMillis() / 400.0)))));
                c.drawRoundRect(r.left - dp(4), r.top - dp(4), r.right + dp(4), r.bottom + dp(4), dp(10), dp(10), p);
            }
            p.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(r.left + 4 * u, r.top);
            path.lineTo(r.right - 9 * u, r.top);
            path.lineTo(r.right, r.top + 9 * u);          // the cut corner
            path.lineTo(r.right, r.bottom - 4 * u);
            path.quadTo(r.right, r.bottom, r.right - 4 * u, r.bottom);
            path.lineTo(r.left + 4 * u, r.bottom);
            path.quadTo(r.left, r.bottom, r.left, r.bottom - 4 * u);
            path.lineTo(r.left, r.top + 4 * u);
            path.quadTo(r.left, r.top, r.left + 4 * u, r.top);
            path.close();
            p.setColor(d.colour);
            p.setAlpha(a);
            c.drawPath(path, p);
            // Shutter.
            p.setColor(0xFFB8B8B2);
            p.setAlpha(a);
            c.drawRect(r.left + 26 * u, r.top, r.right - 22 * u, r.top + 30 * u, p);
            p.setColor(0xFF3A3A37);
            p.setAlpha(a);
            c.drawRect(r.left + 58 * u, r.top + 5 * u, r.left + 68 * u, r.top + 25 * u, p);
            // Label.
            RectF label = new RectF(r.left + 10 * u, r.top + 40 * u, r.right - 10 * u, r.bottom - 3 * u);
            p.setColor(0xFFF4F2EC);
            p.setAlpha(a);
            c.drawRoundRect(label, 3 * u, 3 * u, p);
            float ls = label.height() * 0.42f;
            Drawable dr = art[i];
            if (dr != null) {
                float aw = ls * 1.4f;
                dr.setBounds((int) (label.left + 4 * u), (int) (label.top + 4 * u), (int) (label.left + 4 * u + aw), (int) (label.top + 4 * u + ls));
                dr.setAlpha(a);
                dr.draw(c);
            }
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(Math.max(dp(9), 8 * u));
            p.setColor(0xFF1A1A1A);
            p.setAlpha(a);
            c.drawText(d.name, label.left + 4 * u, label.top + ls + 14 * u, p);
            p.setTextSize(Math.max(dp(8), 6.4f * u));
            p.setColor(d.colour);
            p.setAlpha(a);
            c.drawText(d.kind, label.left + 4 * u, label.top + ls + 23 * u, p);
            // Two lines of description.
            p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextSize(Math.max(dp(9), 6.2f * u));
            p.setColor(0xFF5A5A58);
            p.setAlpha(a);
            float y = label.top + ls + 32 * u;
            for (String line : wrap(d.about, label.width() - 8 * u, 2)) {
                c.drawText(line, label.left + 4 * u, y, p);
                y += p.getTextSize() * 1.2f;
            }
            p.setAlpha(255);
        }

        private List<String> wrap(String text, float max, int maxLines) {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (p.measureText(next) > max && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                    if (out.size() == maxLines) return out;
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (line.length() > 0 && out.size() < maxLines) out.add(line.toString());
            return out;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP || picked >= 0) return true;
            float x = e.getX(), y = e.getY();
            if (x > getWidth() - dp(70) && y < dp(110)) {
                close();
                return true;
            }
            for (int i = 0; i < 5; i++) {
                if (cards[i].contains(x, y)) {
                    picked = i;
                    pickedAt = SystemClock.uptimeMillis();
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                    return true;
                }
            }
            return true;
        }
    }

    void close() {
        animate().alpha(0f).setDuration(200).withEndAction(listener::onClose).start();
    }

    private static float ease(float k) {
        return k < 0.5f ? 2 * k * k : 1 - (float) Math.pow(-2 * k + 2, 2) / 2;
    }
}
