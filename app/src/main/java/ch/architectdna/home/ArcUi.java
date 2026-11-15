package ch.architectdna.home;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The ARC Player look as a small kit, for native screens of the Architect-DNA services:
 * paper/ink in light, near-black in dark, one rust accent, "— LABEL" mono captions,
 * big tight headlines and 8 dp corners.
 */
final class ArcUi {

    final Activity a;
    final boolean dark;
    final int bg, card, line, ink, ink2, ink3, rust, onRust;
    private final float d;

    ArcUi(Activity a) {
        this.a = a;
        dark = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF0E0E0D : 0xFFFAFAF8;
        card = dark ? 0xFF1A1A18 : 0xFFFFFFFF;
        line = dark ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = dark ? 0xFFF4F2EC : 0xFF1A1A1A;
        ink2 = dark ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = dark ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = dark ? 0xFFD9472F : 0xFFB5271E;
        onRust = 0xFFFFFFFF;
        d = a.getResources().getDisplayMetrics().density;
        a.getWindow().setStatusBarColor(bg);
        a.getWindow().setNavigationBarColor(bg);
        if (!dark) a.getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
    }

    int dp(float v) { return Math.round(v * d); }

    /** German if the phone is set to German, English for everything else. */
    static boolean de() { return "de".equals(java.util.Locale.getDefault().getLanguage()); }

    static String t(String en, String de) { return de() ? de : en; }

    TextView label(String s, boolean accent) {
        TextView t = new TextView(a);
        t.setText("— " + s.toUpperCase(java.util.Locale.ROOT));
        t.setTextColor(accent ? rust : ink3);
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.24f);
        return t;
    }

    TextView mono(String s, int colour, float sp) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(sp);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.14f);
        return t;
    }

    TextView title(CharSequence s, float sp) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(ink);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setLetterSpacing(-0.03f);
        t.setLineSpacing(0, 0.95f);
        return t;
    }

    TextView body(CharSequence s, int colour, float sp) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(sp);
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    GradientDrawable box(int fill, int stroke, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    /** Solid (ink or rust) or ghost button, 52 dp. */
    TextView button(String s, int style, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setPadding(dp(18), 0, dp(18), 0);
        if (style == 0) { t.setTextColor(bg); t.setBackground(box(ink, 0, 8)); }
        else if (style == 1) { t.setTextColor(onRust); t.setBackground(box(rust, 0, 8)); }
        else { t.setTextColor(ink); t.setBackground(box(0, line, 8)); }
        t.setOnClickListener(l);
        t.setMinHeight(dp(52));
        return t;
    }

    /** A tappable card: emoji, title, subtitle, arrow. */
    LinearLayout row(String emoji, String head, String sub, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(15), dp(16), dp(15));
        r.setBackground(box(card, line, 8));
        if (emoji != null) {
            TextView e = new TextView(a);
            e.setText(emoji);
            e.setTextSize(24);
            e.setGravity(Gravity.CENTER);
            r.addView(e, new LinearLayout.LayoutParams(dp(40), -2));
        }
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(emoji == null ? 0 : dp(10), 0, dp(8), 0);
        TextView h = body(head, ink, 16.5f);
        h.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.addView(h);
        if (sub != null && !sub.isEmpty()) {
            TextView s = body(sub, ink2, 13.5f);
            s.setPadding(0, dp(2), 0, 0);
            c.addView(s);
        }
        r.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arr = new TextView(a);
        arr.setText("→");
        arr.setTextColor(rust);
        arr.setTextSize(16);
        r.addView(arr);
        if (l != null) r.setOnClickListener(l);
        return r;
    }

    EditText input(String hint, boolean multi, int maxLen) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setTextColor(ink);
        e.setHintTextColor(ink3);
        e.setTextSize(16);
        e.setBackground(box(card, line, 8));
        e.setPadding(dp(14), dp(13), dp(14), dp(13));
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLen)});
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(5);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            e.setSingleLine(true);
            e.setEllipsize(TextUtils.TruncateAt.END);
        }
        return e;
    }

    /** Selectable pill; selected = ink fill. */
    TextView chip(String s, boolean on, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(dp(13), dp(9), dp(13), dp(9));
        t.setTextColor(on ? bg : ink2);
        t.setBackground(on ? box(ink, 0, 20) : box(card, line, 20));
        t.setOnClickListener(l);
        return t;
    }

    /** Thin segmented progress, like the ARC onboarding. */
    LinearLayout segments(int n, int upto) {
        LinearLayout r = new LinearLayout(a);
        for (int i = 0; i < n; i++) {
            View v = new View(a);
            v.setBackground(box(i <= upto ? rust : line, 0, 2));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, dp(3), 1f);
            if (i > 0) l.leftMargin = dp(5);
            r.addView(v, l);
        }
        return r;
    }

    LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        return p;
    }

    /** Wraps children like flex-wrap. */
    static final class Wrap extends android.view.ViewGroup {
        private final int gap;
        Wrap(android.content.Context c, int gap) { super(c); this.gap = gap; }
        @Override protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws), x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + v.getMeasuredWidth() > max) { x = 0; y += rowH + gap; rowH = 0; }
                x += v.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, v.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + rowH);
        }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (x > 0 && x + v.getMeasuredWidth() > max) { x = 0; y += rowH + gap; rowH = 0; }
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                x += v.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, v.getMeasuredHeight());
            }
        }
    }

    /* ---------------- network: JSON POST to the service, off the main thread ---------------- */

    static final class Reply {
        final int code;
        final JSONObject json;
        Reply(int code, JSONObject json) { this.code = code; this.json = json; }
    }

    interface Done { void on(Reply r); }

    /** POSTs JSON as the service's own web page would (same Origin), delivers on the main thread. */
    void post(String origin, String path, JSONObject body, Done done) {
        new Thread(() -> {
            Reply r;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(origin + path).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15_000);
                c.setReadTimeout(90_000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Origin", origin);
                c.setRequestProperty("User-Agent", "ARC-DNA-Android");
                try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String txt = "";
                if (in != null) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                    in.close();
                    txt = b.toString("UTF-8");
                }
                JSONObject j;
                try { j = new JSONObject(txt); } catch (Exception e) { j = new JSONObject(); }
                r = new Reply(code, j);
            } catch (Exception e) {
                r = new Reply(0, new JSONObject());
            }
            Reply fr = r;
            a.runOnUiThread(() -> { if (!a.isFinishing()) done.on(fr); });
        }, "arc-service").start();
    }

    /** Stripe Checkout URLs carry the session id; it becomes the purchase token after payment. */
    static String sessionIdFrom(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("cs_(?:test|live)_[A-Za-z0-9]+").matcher(url == null ? "" : url);
        return m.find() ? m.group() : null;
    }
}
