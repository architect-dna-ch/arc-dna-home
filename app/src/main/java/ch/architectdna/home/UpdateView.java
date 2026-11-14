package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.view.View;

import java.util.Locale;

/**
 * The update, made visible: while ARC-DNA pulls a new version, a DNA helix assembles itself base
 * pair by base pair as the bytes arrive. Below it the real numbers: network (Wi-Fi / mobile and
 * Android's own bandwidth estimate before the first byte), measured speed, size and time left.
 * Fades out once Android takes over the install.
 */
final class UpdateView extends View {

    private static final int BG = 0xF20E0E0D, LINE = 0xFF2C2C2A, INK = 0xFFF4F2EC, INK2 = 0xFFA5A5A0, INK3 = 0xFF6F6E69, RUST = 0xFFD9472F, GREEN = 0xFF2BD96B;
    private static final int PAIRS = 26;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private final long born = SystemClock.uptimeMillis();
    private int phase, version;
    private long done, total;
    private float shown;                       // eased progress 0..1
    private String net = "", estimate = "";
    // speed: a short window of samples
    private long winT0, winB0;
    private double bps;
    private boolean finished, failed;

    UpdateView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        readNetwork(c);
        setElevation(10 * d);
        setAlpha(0f);
        animate().alpha(1f).setDuration(260).start();
    }

    private void readNetwork(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) { net = "OFFLINE"; return; }
            net = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WLAN"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "MOBIL"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "LAN" : "NETZ";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) net += " · VPN";
            int kbps = nc.getLinkDownstreamBandwidthKbps();
            if (kbps > 0) estimate = "≈ " + mbit(kbps * 1000.0) + " GESCHÄTZT";
        } catch (RuntimeException ignored) { }
    }

    void update(int phase, long done, long total, int version) {
        if (phase != this.phase || done < this.done) { winT0 = 0; }
        this.phase = phase;
        this.version = version;
        long now = SystemClock.uptimeMillis();
        if (winT0 == 0) { winT0 = now; winB0 = done; }
        else if (now - winT0 > 400) {
            double inst = (done - winB0) * 1000.0 / (now - winT0);
            bps = bps == 0 ? inst : bps * 0.6 + inst * 0.4;
            winT0 = now;
            winB0 = done;
        }
        this.done = done;
        this.total = total;
        if (phase == 3) finished = true;
        if (phase == -1) failed = true;
        invalidate();
    }

    boolean isFinished() { return finished || failed; }

    /** Overall progress across the phases: patch download, rebuild, write. */
    private float target() {
        float f = total > 0 ? Math.min(1f, (float) done / total) : 0f;
        switch (phase) {
            case 0: return f * 0.55f;
            case 1: return 0.55f + f * 0.45f;
            case 2: return f;
            case 3: return 1f;
            default: return shown;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        shown += (target() - shown) * 0.18f;
        float t = (SystemClock.uptimeMillis() - born) / 1000f;

        p.setStyle(Paint.Style.FILL);
        p.setColor(BG);
        RectF box = new RectF(0, 0, w, h);
        c.drawRoundRect(box, 16 * d, 16 * d, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d);
        p.setColor(LINE);
        c.drawRoundRect(box, 16 * d, 16 * d, p);
        p.setStyle(Paint.Style.FILL);

        float pad = 18 * d;
        // header
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(10.5f * d);
        p.setLetterSpacing(0.24f);
        p.setColor(failed ? RUST : finished ? GREEN : RUST);
        c.drawText(failed ? "— UPDATE ABGEBROCHEN" : "— ARC·DNA UPDATE", pad, pad + 8 * d, p);
        String ver = version > 0 ? "1.0." + version : "";
        p.setColor(INK3);
        c.drawText(ver, w - pad - p.measureText(ver), pad + 8 * d, p);

        // helix: two strands; assembled pairs are solid and joined by a rung
        float hx0 = pad, hx1 = w - pad, cy = pad + 52 * d, amp = 20 * d;
        int built = Math.round(shown * PAIRS);
        for (int i = 0; i < PAIRS; i++) {
            float x = hx0 + (hx1 - hx0) * i / (PAIRS - 1);
            float ph = i * 0.42f + t * 1.6f;
            float ya = cy + (float) Math.sin(ph) * amp, yb = cy + (float) Math.sin(ph + Math.PI) * amp;
            float za = (float) Math.cos(ph);                       // front / back
            boolean on = i < built;
            if (on) {
                p.setColor(LINE);
                p.setStrokeWidth(1.4f * d);
                c.drawLine(x, ya, x, yb, p);
            }
            float ra = (2.2f + 1.2f * (za + 1) / 2) * d, rb = (2.2f + 1.2f * (1 - za) / 2) * d;
            p.setColor(on ? (failed ? INK3 : finished ? GREEN : RUST) : INK3);
            p.setAlpha(on ? 255 : 70 + (int) (60 * (za + 1) / 2));
            c.drawCircle(x, ya, ra, p);
            p.setColor(on ? INK : INK3);
            p.setAlpha(on ? 230 : 70 + (int) (60 * (1 - za) / 2));
            c.drawCircle(x, yb, rb, p);
            p.setAlpha(255);
        }
        // the "polymerase": a bright marker where the next pair is joined
        if (!finished && !failed && built < PAIRS) {
            float x = hx0 + (hx1 - hx0) * Math.max(0, built) / (PAIRS - 1);
            p.setColor(RUST);
            p.setAlpha((int) (90 + 80 * Math.abs(Math.sin(t * 4))));
            c.drawCircle(x, cy, 9 * d, p);
            p.setAlpha(255);
        }

        // big percentage + phase
        float by = cy + amp + 44 * d;
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setLetterSpacing(-0.04f);
        p.setTextSize(36 * d);
        p.setColor(INK);
        String pct = failed ? "—" : Math.round(shown * 100) + " %";
        c.drawText(pct, pad, by, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setLetterSpacing(0f);
        p.setTextSize(14 * d);
        p.setColor(INK2);
        String what = failed ? "Später nochmal." : finished ? "Bereit. Android installiert." : phase == 0 ? "Lädt nur die Änderungen"
            : phase == 1 ? "Baut die neue Version" : total > 0 ? "Lädt die neue Version" : "Verbindet …";
        c.drawText(what, w - pad - p.measureText(what), by, p);

        // the numbers
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(10.5f * d);
        p.setLetterSpacing(0.14f);
        p.setColor(INK3);
        String speed = bps > 0 && phase != 1 ? mbit(bps * 8) : estimate;
        String size = total > 0 ? mb(done) + " / " + mb(total) : "";
        String eta = "";
        if (bps > 1000 && total > 0 && done < total && phase != 1) {
            long s = Math.max(1, Math.round((total - done) / bps));
            eta = "NOCH " + (s >= 60 ? s / 60 + " MIN" : s + " S");
        }
        String l1 = net + (speed.isEmpty() ? "" : "  ·  " + speed);
        String l2 = size + (eta.isEmpty() ? "" : "  ·  " + eta);
        c.drawText(l1, pad, by + 26 * d, p);
        c.drawText(l2, w - pad - p.measureText(l2), by + 26 * d, p);

        // thin progress line
        float ly = h - 12 * d;
        p.setColor(LINE);
        c.drawRect(pad, ly, w - pad, ly + 2 * d, p);
        p.setColor(failed ? INK3 : finished ? GREEN : RUST);
        c.drawRect(pad, ly, pad + (w - 2 * pad) * shown, ly + 2 * d, p);

        if (getAlpha() > 0) postInvalidateOnAnimation();
    }

    static float heightDp() { return 190; }

    private static String mbit(double bitsPerSecond) {
        double m = bitsPerSecond / 1_000_000.0;
        return (m >= 10 ? String.format(Locale.ROOT, "%.0f", m) : String.format(Locale.ROOT, "%.1f", m)) + " MBIT/S";
    }

    private static String mb(long b) {
        return String.format(Locale.ROOT, "%.1f MB", b / 1_000_000.0);
    }
}
