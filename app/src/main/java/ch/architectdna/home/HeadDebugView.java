package ch.architectdna.home;

import android.content.Context;
import android.graphics.Bitmap;
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
        record(d, decision);
    }

    /* ---------- Recording: while the debug corner runs, the last ~20 s are kept to share ---------- */

    private static final int KEEP = 48;                 // snapshots, one every 0.4 s
    private static final java.util.ArrayDeque<Bitmap> SHOTS = new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<String> ROWS = new java.util.ArrayDeque<>();
    private static long recStart, lastShot;

    private void record(HeadTracker.Debug d, String decision) {
        long now = android.os.SystemClock.uptimeMillis();
        if (recStart == 0) recStart = now;
        float t = (now - recStart) / 1000f;
        ROWS.addLast(String.format(java.util.Locale.ROOT, "%.2f,%d,%.1f,%.1f,%.3f,%.3f,\"%s\"", t, d.face ? 1 : 0, d.pitch, d.yaw,
            d.gazeV, d.gazeH, decision == null ? "" : decision.replace("\"", "'")));
        while (ROWS.size() > 3000) ROWS.removeFirst();
        if (now - lastShot < 400 || getWidth() == 0) return;
        lastShot = now;
        post(() -> {
            if (getWidth() == 0) return;
            Bitmap b = Bitmap.createBitmap(getWidth() / 2, getHeight() / 2, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFF121211);
            c.scale(0.5f, 0.5f);
            draw(c);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFFFFFFFF);
            tp.setTextSize(dp(9));
            tp.setTypeface(Typeface.MONOSPACE);
            c.drawText(String.format(java.util.Locale.ROOT, "%.1fs", t), dp(6), dp(12), tp);
            SHOTS.addLast(b);
            while (SHOTS.size() > KEEP) SHOTS.removeFirst().recycle();
        });
    }

    static boolean hasRecording() {
        return !SHOTS.isEmpty();
    }

    /**
     * Saves the recording – a contact sheet (snapshots in a grid, time-stamped) to Pictures/ARC-DNA
     * and every reading as CSV to Download – and opens the share sheet, e.g. to send to Claude.
     */
    static void saveAndShare(android.app.Activity a) {
        if (SHOTS.isEmpty()) {
            android.widget.Toast.makeText(a, "Noch keine Aufnahme – Kamera-Debug in der Mediathek laufen lassen.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        java.util.List<Bitmap> shots = new java.util.ArrayList<>(SHOTS);
        String csv = "t_s,face,pitch_deg,yaw_deg,gaze_v,gaze_h,decision\n" + String.join("\n", ROWS) + "\n";
        int cols = 6, rows = (shots.size() + cols - 1) / cols, tw = shots.get(0).getWidth(), th = shots.get(0).getHeight();
        Bitmap sheet = Bitmap.createBitmap(cols * tw, rows * th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        c.drawColor(0xFF000000);
        for (int i = 0; i < shots.size(); i++) c.drawBitmap(shots.get(i), (i % cols) * tw, (i / cols) * th, null);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
        java.util.ArrayList<android.net.Uri> out = new java.util.ArrayList<>();
        try {
            android.content.ContentResolver cr = a.getContentResolver();
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "arc-tracking-" + stamp + ".png");
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ARC-DNA");
            android.net.Uri img = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (img != null) {
                try (java.io.OutputStream os = cr.openOutputStream(img)) {
                    sheet.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                out.add(img);
            }
            android.content.ContentValues t = new android.content.ContentValues();
            t.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "arc-tracking-" + stamp + ".csv");
            t.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            t.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download");
            android.net.Uri doc = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, t);
            if (doc != null) {
                try (java.io.OutputStream os = cr.openOutputStream(doc)) {
                    os.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                out.add(doc);
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(a, "Speichern fehlgeschlagen: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            return;
        } finally {
            sheet.recycle();
        }
        for (Bitmap b : SHOTS) b.recycle();
        SHOTS.clear();
        ROWS.clear();
        recStart = 0;
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, out)
            .putExtra(android.content.Intent.EXTRA_TEXT, "ARC-DNA Kopf-Tracking Aufnahme " + stamp)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(android.content.Intent.createChooser(send, "Tracking-Aufnahme teilen"));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        // Wide strip (along the top): picture left, numbers right. Tall box: picture on top, numbers below.
        boolean wide = getWidth() > getHeight() * 1.4f;
        float w = getWidth(), textH = wide ? 0 : dp(64), ih = getHeight() - textH;
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
        // ● REC: what you see here is being kept to share (Kamera-Debug off = save & share).
        if (((android.os.SystemClock.uptimeMillis() / 600) % 2) == 0) {
            line.setStyle(Paint.Style.FILL);
            line.setColor(0xFFE5482C);
            c.drawCircle(w - dp(14), dp(14), dp(4), line);
            line.setStyle(Paint.Style.STROKE);
        }
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
        float pw = wide ? Math.min(w * 0.45f, ih * fw / fh) : w;
        float s = Math.min(pw / fw, ih / fh), dw = fw * s, dh = fh * s, ox = (pw - dw) / 2, oy = (ih - dh) / 2;
        c.save();
        c.clipRect(0, 0, pw, ih);
        c.scale(-1, 1, pw / 2, 0);
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
        text.setTextSize(dp(wide ? 11 : 9.5f));
        float tx = wide ? pw + dp(12) : dp(8), y = wide ? dp(26) : ih + dp(14), step = dp(wide ? 18 : 13);
        text.setColor(d.face ? 0xFFFFFFFF : rust);
        c.drawText(d.face ? String.format(java.util.Locale.ROOT, "KOPF  ↕%+5.1f°  ↔%+5.1f°", d.pitch, d.yaw) : "KEIN GESICHT", tx, y, text);
        y += step;
        text.setColor(0xFF1FA8FF);
        c.drawText(Float.isNaN(d.gazeV) ? "AUGEN  –" : String.format(java.util.Locale.ROOT, "AUGEN ↕%+.2f  ↔%+.2f", d.gazeV, d.gazeH), tx, y, text);
        y += step;
        text.setColor(rust);
        c.drawText(decision, tx, y, text);
        y += step;
        text.setColor(0x99FFFFFF);
        c.drawText("ROT NICKEN · BLAU/GELB AUGEN", tx, y, text);
    }
}
