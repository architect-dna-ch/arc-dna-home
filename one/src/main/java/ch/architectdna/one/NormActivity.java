package ch.architectdna.one;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;

/**
 * "Wie die meisten" — the defaults most people in Switzerland just have (a case and a screen
 * protector, liability insurance, a family doctor, the ticket before boarding…), each with why and
 * what skipping it costs. Mark each: ✓ mache ich · später · bewusst anders. The rule on top: be
 * special where it's cheap, like everyone where mistakes are expensive. Content: assets/norm.json.
 */
public class NormActivity extends Activity {

    private static final int INK = 0xFFF4F1EC, INK2 = 0xFFA9A39A, INK3 = 0xFF6F6A63,
        BG = 0xFF0E0D0C, CARD = 0xFF1B1916, LINE = 0xFF2B2824, RUST = 0xFFE0573B, GREEN = 0xFF7FD08A, BLUE = 0xFF9DB8FF;
    private static final String[] STATES = {"", "ok", "later", "own"};

    private SharedPreferences prefs;
    private JSONObject data;
    private LinearLayout list;
    private TextView summary;
    private int filter;                      // 0 alle · 1 offen · 2 bewusst anders

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("norm", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        try (InputStream in = getAssets().open("norm.json")) {
            byte[] buf = new byte[in.available()];
            int n = 0;
            while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; }
            data = new JSONObject(new String(buf, 0, n, "UTF-8"));
        } catch (Exception e) {
            data = new JSONObject();
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins.consumeSystemWindowInsets();
        });
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(20), dp(22), dp(40));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView name = text(11, RUST, Typeface.create("monospace", Typeface.BOLD));
        name.setText("— ARC · WIE DIE MEISTEN");
        name.setLetterSpacing(0.2f);
        col.addView(name);
        TextView rule = text(24, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        rule.setText(data.optString("rule"));
        rule.setLineSpacing(0, 1.05f);
        rule.setPadding(0, dp(10), 0, dp(14));
        col.addView(rule);
        summary = text(12, INK2, Typeface.create("monospace", Typeface.NORMAL));
        col.addView(summary);

        LinearLayout chips = new LinearLayout(this);
        String[] names = {"Alle", "Offen", "Bewusst anders"};
        final TextView[] cs = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int f = i;
            cs[i] = pill(names[i], i == 0 ? INK : CARD, i == 0 ? BG : INK, 13);
            cs[i].setOnClickListener(v -> {
                filter = f;
                for (int k = 0; k < 3; k++) tint(cs[k], k == f ? INK : CARD, k == f ? BG : INK);
                render();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(8);
            chips.addView(cs[i], p);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(14); cl.bottomMargin = dp(6);
        col.addView(chips, cl);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        render();
    }

    private String key(int g, int i) { return g + "." + i; }

    private void render() {
        list.removeAllViews();
        JSONArray groups = data.optJSONArray("groups");
        if (groups == null) return;
        int total = 0, ok = 0, own = 0, later = 0;
        for (int g = 0; g < groups.length(); g++) {
            JSONObject grp = groups.optJSONObject(g);
            JSONArray items = grp.optJSONArray("items");
            if (items == null) continue;
            boolean header = false;
            for (int i = 0; i < items.length(); i++) {
                String st = prefs.getString(key(g, i), "");
                total++;
                if (st.equals("ok")) ok++; else if (st.equals("own")) own++; else if (st.equals("later")) later++;
                boolean show = filter == 0 || (filter == 1 && (st.isEmpty() || st.equals("later"))) || (filter == 2 && st.equals("own"));
                if (!show) continue;
                if (!header) {
                    header = true;
                    TextView h = text(13, INK2, Typeface.create("monospace", Typeface.BOLD));
                    h.setText(grp.optString("icon") + "  " + grp.optString("t").toUpperCase(java.util.Locale.GERMAN));
                    h.setLetterSpacing(0.12f);
                    h.setPadding(0, dp(24), 0, dp(8));
                    list.addView(h);
                }
                list.addView(card(g, i, items.optJSONObject(i), st));
            }
        }
        summary.setText(ok + " von " + total + " wie die meisten" + (own > 0 ? " · " + own + " bewusst anders" : "")
            + (later > 0 ? " · " + later + " später" : ""));
        if (list.getChildCount() == 0) {
            TextView e = text(15, INK2, Typeface.DEFAULT);
            e.setText(filter == 1 ? "Nichts mehr offen. Stark." : "Noch nichts bewusst anders gewählt.");
            e.setPadding(0, dp(30), 0, 0);
            list.addView(e);
        }
    }

    private View card(final int g, final int i, JSONObject it, String st) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), st.equals("ok") ? 0x557FD08A : st.equals("own") ? 0x559DB8FF : LINE);
        c.setBackground(bg);

        TextView t = text(17, INK, Typeface.DEFAULT_BOLD);
        t.setText((st.equals("ok") ? "✓  " : st.equals("own") ? "◆  " : "") + it.optString("t"));
        c.addView(t);
        TextView n = text(14, INK, Typeface.DEFAULT);
        n.setText("Die meisten: " + it.optString("n"));
        n.setPadding(0, dp(6), 0, 0);
        n.setLineSpacing(0, 1.15f);
        c.addView(n);
        TextView w = text(13, INK2, Typeface.DEFAULT);
        w.setText("Warum: " + it.optString("w"));
        w.setPadding(0, dp(4), 0, 0);
        w.setLineSpacing(0, 1.15f);
        c.addView(w);
        String cost = it.optString("c");
        if (!cost.isEmpty() && !cost.equals("—")) {
            TextView k = text(12, INK3, Typeface.create("monospace", Typeface.NORMAL));
            k.setText(cost);
            k.setPadding(0, dp(6), 0, 0);
            c.addView(k);
        }

        LinearLayout row = new LinearLayout(this);
        String[] labels = {"✓ mache ich", "später", "bewusst anders"};
        String[] vals = {"ok", "later", "own"};
        int[] colors = {GREEN, INK2, BLUE};
        for (int k = 0; k < 3; k++) {
            final String val = vals[k];
            boolean on = st.equals(val);
            TextView p = pill(labels[k], on ? colors[k] : BG, on ? BG : INK2, 12);
            p.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                prefs.edit().putString(key(g, i), st.equals(val) ? "" : val).apply();   // tap again to clear
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(6);
            row.addView(p, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = dp(12);
        c.addView(row, rl);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.bottomMargin = dp(10);
        c.setLayoutParams(cl);
        return c;
    }

    private TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color); t.setTypeface(tf);
        return t;
    }

    private TextView pill(String s, int fill, int ink, float sp) {
        TextView t = text(sp, ink, Typeface.DEFAULT_BOLD);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        tint(t, fill, ink);
        return t;
    }

    private void tint(TextView t, int fill, int ink) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(20));
        if (fill == CARD || fill == BG) g.setStroke(dp(1), LINE);
        t.setBackground(g); t.setTextColor(ink);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
