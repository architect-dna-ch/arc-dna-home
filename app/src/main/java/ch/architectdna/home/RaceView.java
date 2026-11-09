package ch.architectdna.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A minimal shared fox-run for the Bluetooth race. Both devices seed the same obstacle course, so
 * it is fair. Tap to jump the cubes. The partner's live distance is drawn as a ghost fox on the
 * same track, so you see who is ahead in real time. Reports its distance and crash to the Activity.
 */
final class RaceView extends View {

    interface Host {
        void onTick(int distance);   // called ~frequently with my distance
        void onCrash(int distance);  // called once when I hit a cube
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Host host;
    private Random rng;
    private boolean running, iCrashed;
    private float distance, speed, lift, vy;
    private boolean grounded = true;
    private long last;
    private int tick;

    // obstacles as distances (metres) where a cube stands
    private final List<Float> obstacles = new ArrayList<>();
    private float lastObstacle = 240;

    // partner
    private float oppDist;
    private boolean oppDone;
    private int oppFinal = -1;

    private final int paper = 0xFFF5F1E8, ink = 0xFF1B1613, ink2 = 0xFF6A5F54, line = 0xFFE2D9C8, rust = 0xFFB5271E;

    RaceView(Context c) {
        super(c);
        setBackgroundColor(paper);
    }

    void start(long seed, Host h) {
        host = h;
        rng = new Random(seed);
        distance = 0;
        speed = dp(150);            // px/s, climbs over time
        lift = 0; vy = 0; grounded = true; iCrashed = false; oppDone = false; oppFinal = -1; oppDist = 0;
        obstacles.clear();
        lastObstacle = dp(240);
        running = true;
        last = System.nanoTime();
        postInvalidateOnAnimation();
    }

    void setOpponent(float dist, boolean done, int finalDist) {
        oppDist = dist; oppDone = done; if (done) oppFinal = finalDist;
    }

    int distance() { return (int) (distance / dp(3)); }   // metres
    boolean crashed() { return iCrashed; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) jump();
        return true;
    }

    void jump() {
        if (running && !iCrashed && grounded) { vy = dp(1700); grounded = false; }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = System.nanoTime();
        float dt = Math.min(0.04f, (now - last) / 1e9f);
        last = now;

        float groundY = h * 0.72f;
        float foxX = w * 0.26f;
        float pxPerM = dp(3);

        if (running && !iCrashed) {
            speed += dp(6) * dt;                       // gentle acceleration
            distance += speed * dt;
            // jump physics
            if (!grounded) {
                lift += vy * dt; vy -= dp(5200) * dt;
                if (lift <= 0) { lift = 0; vy = 0; grounded = true; }
            }
            // spawn obstacles ahead
            while (lastObstacle < distance + w) {
                lastObstacle += dp(300) + rng.nextInt((int) dp(380));
                obstacles.add(lastObstacle);
            }
            // collision: a cube at foxX while the fox is low
            float cubeH = dp(26);
            for (Float od : obstacles) {
                float sx = foxX + (od - distance);
                if (Math.abs(sx - foxX) < dp(16) && lift < cubeH) { crash(); break; }
            }
            if ((++tick & 3) == 0 && host != null) host.onTick(distance());
        }

        // ground
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.5f)); p.setColor(line);
        c.drawLine(0, groundY, w, groundY, p);

        // obstacles
        p.setStyle(Paint.Style.FILL); p.setColor(rust);
        float cubeH = dp(26), cubeW = dp(14);
        for (Float od : obstacles) {
            float sx = foxX + (od - distance);
            if (sx < -cubeW || sx > w + cubeW) continue;
            c.drawRoundRect(sx - cubeW / 2, groundY - cubeH, sx + cubeW / 2, groundY, dp(3), dp(3), p);
        }

        // partner ghost on the same track (opp distance is in metres → to px)
        float ghostX = foxX + (oppDist * pxPerM - distance);
        if (ghostX > -dp(40) && ghostX < w + dp(40)) {
            p.setColor(0x55000000);
            p.setTextSize(dp(26)); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("🦊", ghostX, groundY - dp(2), p);
        }

        // me
        p.setColor(ink);
        float bob = running && !iCrashed && grounded ? Math.abs((float) Math.sin(distance / dp(16))) * dp(5) : 0;
        p.setTextSize(dp(34)); p.setTextAlign(Paint.Align.CENTER);
        c.save();
        if (iCrashed) c.rotate(35, foxX, groundY - dp(16));
        c.drawText(iCrashed ? "💥" : "🦊", foxX, groundY - lift - bob - dp(2), p);
        c.restore();

        // HUD
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        p.setColor(ink); p.setTextSize(dp(15));
        c.drawText(String.format(Locale.ROOT, "DU  %05d m", distance()), dp(20), dp(44), p);
        p.setColor(rust);
        String opp = oppDone ? String.format(Locale.ROOT, "PARTNER  %05d m ✓", oppFinal) : String.format(Locale.ROOT, "PARTNER  %05d m", (int) oppDist);
        c.drawText(opp, dp(20), dp(66), p);
        p.setColor(ink2); p.setTypeface(Typeface.DEFAULT); p.setTextSize(dp(12));
        if (!running || iCrashed) {
            String msg = resultMsg();
            if (msg != null) { p.setColor(rust); p.setTextSize(dp(20)); p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                p.setTextAlign(Paint.Align.CENTER); c.drawText(msg, w / 2f, h * 0.5f, p); p.setTextAlign(Paint.Align.LEFT); }
            else { c.drawText("Warte auf Partner …", dp(20), h - dp(26), p); }
        } else {
            c.drawText("TIPPEN = SPRINGEN", dp(20), h - dp(26), p);
        }

        if (running || !bothDone()) postInvalidateOnAnimation();
    }

    private void crash() {
        iCrashed = true; running = false;
        if (host != null) host.onCrash(distance());
        postInvalidateOnAnimation();
    }

    private boolean bothDone() { return iCrashed && oppDone; }

    private String resultMsg() {
        if (!bothDone()) return null;
        int me = distance(), them = oppFinal;
        if (me > them) return "DU GEWINNST! " + me + " m";
        if (them > me) return "PARTNER GEWINNT · " + them + " m";
        return "UNENTSCHIEDEN · " + me + " m";
    }
}
