package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Ruheinsel – the crisis screen. When everything is too much and the playful home would only
 * distract, this is one calm, quiet place: a slow breathing circle, 5-4-3-2-1 grounding, one steady
 * sentence, and the helpline one tap away. It pins the screen (nothing else can pull you away) –
 * but it is never a trap: holding "Mir geht's wieder etwas besser" for three seconds always lets
 * you out. A locked door in a crisis can make things worse; a quiet room with the handle still
 * there is the wiser version of "lock in".
 */
public class CrisisActivity extends Activity {

    private static final String[] STEPS = {
        "Nenne 5 Dinge, die du JETZT siehst.",
        "4 Dinge, die du hören kannst.",
        "3 Dinge, die du berühren kannst.",
        "2 Dinge, die du riechst.",
        "1 langsamer, tiefer Atemzug.",
    };
    // Steady sentences – the same ones as the Kompass rules, so it sounds like your own voice.
    private static final String[] LINES = {
        "Der Zweifel entscheidet nicht. Du entscheidest.",
        "Das hier geht vorbei. Es ist immer vorbeigegangen.",
        "Du musst es nicht lösen. Du musst nur atmen.",
        "Ein Gefühl ist kein Befehl und keine Tatsache.",
        "Schlaf und Essen zuerst. Der Rest wartet.",
        "Du bist nicht allein damit. 143 ist echt und rund um die Uhr da.",
        "Der kleinste nächste Atemzug reicht für jetzt.",
    };

    private BreathView breath;
    private TextView stepText, lineText;
    private int step;
    private long holdStart;
    private boolean pinned;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(0xFF0B0F14);
        getWindow().setNavigationBarColor(0xFF0B0F14);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F14);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(28), dp(56), dp(28), dp(32));
        col.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(dp(28), top + dp(40), dp(28), bottom + dp(28));
            return insets;
        });
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView hi = text(26, 0xFFEAF2F7, true);
        hi.setText("Ruheinsel");
        hi.setGravity(Gravity.CENTER);
        col.addView(hi);
        TextView sub = text(15, 0xFF8FA3B0, false);
        sub.setText("Nur hier. Nur atmen. Alles andere wartet.");
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, lp(0, dp(6), 0, dp(20)));

        breath = new BreathView(this);
        col.addView(breath, new LinearLayout.LayoutParams(dp(220), dp(220)));

        lineText = text(19, 0xFFEAF2F7, true);
        lineText.setGravity(Gravity.CENTER);
        lineText.setLineSpacing(0, 1.2f);
        lineText.setText(LINES[(int) (System.currentTimeMillis() / 60000 % LINES.length)]);
        col.addView(lineText, lp(0, dp(22), 0, dp(4)));

        // 5-4-3-2-1 grounding: tap to move on.
        stepText = text(16, 0xFF9FD8C0, false);
        stepText.setGravity(Gravity.CENTER);
        stepText.setPadding(dp(18), dp(16), dp(18), dp(16));
        stepText.setBackground(box(0xFF11202A, 0xFF24424F, 18));
        stepText.setText(STEPS[0] + "\n\nAntippen = weiter");
        stepText.setOnClickListener(v -> {
            step = (step + 1) % STEPS.length;
            stepText.setText(STEPS[step] + (step == STEPS.length - 1 ? "\n\nAntippen = von vorn" : "\n\nAntippen = weiter"));
        });
        col.addView(stepText, lp(0, dp(22), 0, dp(10)));

        // Help, always one tap away.
        col.addView(callButton("📞  143 · Die Dargebotene Hand", "Rund um die Uhr. Anonym. Kostenlos.", "143"), lp(0, dp(16), 0, dp(10)));
        col.addView(callButton("📞  147 · Für junge Menschen", "Beratung Tag und Nacht.", "147"), lp(0, 0, 0, dp(10)));
        col.addView(callButton("🚑  144 · Notfall (Sanität)", "Wenn es um Leben geht.", "144"), lp(0, 0, 0, dp(18)));

        // Gentle exit: hold three seconds. Never trapped.
        TextView exit = text(15, 0xFF8FA3B0, false);
        exit.setText("Mir geht's wieder etwas besser\n(3 Sekunden halten)");
        exit.setGravity(Gravity.CENTER);
        exit.setPadding(dp(18), dp(16), dp(18), dp(16));
        exit.setBackground(box(0xFF10151B, 0xFF2A3742, 18));
        exit.setOnTouchListener((v, e) -> {
            int act = e.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN) {
                holdStart = SystemClock.uptimeMillis();
                v.animate().alpha(0.6f).setDuration(3000).start();
                v.postDelayed(exitCheck, 3000);
            } else if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) {
                v.removeCallbacks(exitCheck);
                v.animate().cancel();
                v.setAlpha(1f);
            }
            return true;
        });
        col.addView(exit, lp(0, 0, 0, dp(8)));

        TextView pinNote = text(12, 0xFF5E7280, false);
        pinNote.setGravity(Gravity.CENTER);
        pinNote.setText("Ablenkung gesperrt: andere Apps und Mitteilungen sind aus, bis du hältst.");
        col.addView(pinNote, lp(0, dp(4), 0, 0));

        setContentView(root);
    }

    private final Runnable exitCheck = () -> leave();

    private void leave() {
        if (pinned && Build.VERSION.SDK_INT >= 21) {
            try {
                stopLockTask();
            } catch (RuntimeException ignored) {
            }
            pinned = false;
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        breath.start();
        // Pin the screen so nothing pulls you away. If the phone needs the user to confirm pinning,
        // Android shows its own prompt once; we don't force it, and the hold-to-exit still works.
        if (Build.VERSION.SDK_INT >= 21 && !pinned) {
            try {
                startLockTask();
                pinned = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        breath.stop();
    }

    @Override
    public void onBackPressed() {
        // Back alone does not leave – only the deliberate hold does.
    }

    private TextView callButton(String title, String sub, String number) {
        TextView t = text(17, 0xFFEAF2F7, true);
        t.setText(title + "\n" + sub);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(16), dp(18), dp(16));
        t.setBackground(box("143".equals(number) ? 0xFF143A2A : 0xFF14222E, "143".equals(number) ? 0xFF2BBF6A : 0xFF2A4A5A, 18));
        t.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
            } catch (RuntimeException ignored) {
            }
        });
        return t;
    }

    /* ---- helpers ---- */

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private LinearLayout.LayoutParams lp(int l, int top, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(l, top, r, b);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    private android.graphics.drawable.GradientDrawable box(int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** A slow breathing circle: 4 s in, 2 s hold, 6 s out, with the word changing. */
    private static final class BreathView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long startAt;
        private boolean running;

        BreathView(android.content.Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }

        void start() {
            if (startAt == 0) startAt = SystemClock.uptimeMillis();
            running = true;
            postInvalidateOnAnimation();
        }

        void stop() {
            running = false;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float dp = getResources().getDisplayMetrics().density;
            float cycle = 12f;                       // 4 in + 2 hold + 6 out
            float t = ((SystemClock.uptimeMillis() - startAt) / 1000f) % cycle;
            float scale;
            String word;
            if (t < 4) {
                scale = 0.55f + 0.45f * ease(t / 4);
                word = "Einatmen";
            } else if (t < 6) {
                scale = 1f;
                word = "Halten";
            } else {
                scale = 1f - 0.45f * ease((t - 6) / 6);
                word = "Ausatmen";
            }
            float r = Math.min(w, h) / 2 * 0.9f * scale;
            fill.setColor(0xFF12303A);
            fill.setAlpha((int) (120 + 90 * (scale - 0.55f) / 0.45f));
            c.drawCircle(cx, cy, r, fill);
            ring.setColor(0xFF3DD0A8);
            ring.setStrokeWidth(3 * dp);
            c.drawCircle(cx, cy, r, ring);
            txt.setColor(0xFFCFE8DF);
            txt.setTextSize(20 * dp);
            c.drawText(word, cx, cy + 7 * dp, txt);
            if (running) postInvalidateOnAnimation();
        }

        private float ease(float t) {
            t = Math.max(0, Math.min(1, t));
            return t * t * (3 - 2 * t);
        }
    }
}
