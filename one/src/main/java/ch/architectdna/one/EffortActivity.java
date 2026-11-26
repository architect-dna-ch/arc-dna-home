package ch.architectdna.one;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Aufwand & Wirkung — what you put in, what came back, and whose part it was.
 *
 * Each entry: what you did, the area (Amt, Job, Verein, Wohnen, Gesundheit, Menschen), the effort
 * (1–5), the result (offen · gewirkt · teilweise · nichts) and — the point of it all — "lag es an
 * mir?": bei mir · nicht bei mir · beides. The summary per area shows effort against effect, and how
 * much of what didn't work was out of your hands, so a blocked month at the Sozialdienst doesn't read
 * as your failure, and a calendar clash doesn't read as fate.
 */
public class EffortActivity extends Activity {

    private static final int INK = 0xFFF4F1EC, INK2 = 0xFFA9A39A, INK3 = 0xFF6F6A63,
        BG = 0xFF0E0D0C, CARD = 0xFF1B1916, LINE = 0xFF2B2824, RUST = 0xFFE0573B,
        GREEN = 0xFF7FD08A, YELLOW = 0xFFF2C66D, BLUE = 0xFF9DB8FF;
    static final String[] AREAS = {"Amt", "Job", "Verein", "Wohnen", "Gesundheit", "Menschen"};
    static final String[] RESULTS = {"offen", "gewirkt", "teilweise", "nichts"};
    static final String[] WHO = {"bei mir", "nicht bei mir", "beides"};

    private SharedPreferences prefs;
    private JSONArray items;
    private LinearLayout list, summary;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("effort", MODE_PRIVATE);
        try { items = new JSONArray(prefs.getString("items", "")); } catch (Exception e) { items = seed(); save(); }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

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
        col.setPadding(dp(22), dp(20), dp(22), dp(96));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView name = text(11, RUST, Typeface.create("monospace", Typeface.BOLD));
        name.setText("— ARC · AUFWAND & WIRKUNG");
        name.setLetterSpacing(0.2f);
        col.addView(name);
        TextView h = text(24, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        h.setText("Was du reingibst, was zurückkommt – und was an dir lag.");
        h.setPadding(0, dp(10), 0, dp(14));
        col.addView(h);

        summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        col.addView(summary);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        TextView add = pill("+ Eintrag", INK, BG, 16);
        add.setOnClickListener(v -> edit(-1));
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-2, dp(54), Gravity.BOTTOM | Gravity.END);
        al.setMargins(0, 0, dp(20), dp(24));
        add.setPadding(dp(22), 0, dp(22), 0);
        root.addView(add, al);

        setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        render();
    }

    /** Starts empty: entries are personal and live only on the phone. */
    private JSONArray seed() { return new JSONArray(); }

    private void save() { prefs.edit().putString("items", items.toString()).apply(); }

    /* ---------------- views ---------------- */

    private void render() {
        renderSummary();
        list.removeAllViews();
        TextView lh = text(12, INK2, Typeface.create("monospace", Typeface.BOLD));
        lh.setText("EINTRÄGE · NEUSTE ZUERST");
        lh.setLetterSpacing(0.12f);
        lh.setPadding(0, dp(26), 0, dp(8));
        list.addView(lh);
        if (items.length() == 0) {
            TextView e = text(15, INK2, Typeface.DEFAULT);
            e.setText("Noch leer. Tippe «+ Eintrag»: was du getan hast, wie viel Aufwand, was rauskam – und ob es an dir lag.");
            e.setLineSpacing(0, 1.2f);
            list.addView(e);
        }
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) list.addView(card(i, it));
        }
    }

    private void renderSummary() {
        summary.removeAllViews();
        int effort = 0, worked = 0, done = 0, notMe = 0, me = 0, misses = 0;
        Map<String, int[]> per = new LinkedHashMap<>();                 // effort, count, worked(×2 for partial), misses
        for (String a : AREAS) per.put(a, new int[4]);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            int[] p = per.get(it.optString("area"));
            if (p == null) continue;
            String res = it.optString("res"), who = it.optString("who");
            int e = it.optInt("effort", 1);
            effort += e; p[0] += e; p[1]++;
            if (!res.equals("offen")) done++;
            if (res.equals("gewirkt")) { worked += 2; p[2] += 2; }
            if (res.equals("teilweise")) { worked += 1; p[2] += 1; }
            if (res.equals("nichts") || res.equals("teilweise")) {
                misses++; p[3]++;
                if (who.equals("nicht bei mir")) notMe++;
                if (who.equals("bei mir")) me++;
            }
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable(); g.setColor(CARD); g.setCornerRadius(dp(14)); g.setStroke(dp(1), LINE);
        box.setBackground(g);
        TextView big = text(15, INK, Typeface.DEFAULT_BOLD);
        int rate = done == 0 ? 0 : Math.round(100f * worked / (2f * done));
        big.setText(items.length() + " Einträge · Aufwand " + effort + " · Wirkung " + rate + " %");
        box.addView(big);
        if (misses > 0) {
            TextView w = text(14, INK, Typeface.DEFAULT);
            w.setText("Von " + misses + " Dingen, die nicht (ganz) geklappt haben, lagen "
                + notMe + " nicht bei dir" + (me > 0 ? ", " + me + " bei dir" : "") + ".");
            w.setPadding(0, dp(6), 0, dp(4));
            w.setLineSpacing(0, 1.15f);
            box.addView(w);
        }
        for (Map.Entry<String, int[]> e : per.entrySet()) {
            int[] p = e.getValue();
            if (p[1] == 0) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            TextView n = text(13, INK2, Typeface.create("monospace", Typeface.BOLD));
            n.setText(e.getKey().toUpperCase(Locale.GERMAN));
            row.addView(n, new LinearLayout.LayoutParams(dp(96), -2));
            // effort bar (dim) with the effect share drawn over it (green)
            FrameLayout bar = new FrameLayout(this);
            View eff = new View(this); eff.setBackgroundColor(0x33F4F1EC);
            View got = new View(this); got.setBackgroundColor(GREEN);
            float share = p[1] == 0 ? 0 : p[2] / (2f * p[1]);
            int full = dp(150), w = Math.max(dp(6), Math.min(full, p[0] * dp(10)));
            bar.addView(eff, new FrameLayout.LayoutParams(w, dp(8), Gravity.CENTER_VERTICAL));
            bar.addView(got, new FrameLayout.LayoutParams(Math.round(w * share), dp(8), Gravity.CENTER_VERTICAL));
            row.addView(bar, new LinearLayout.LayoutParams(full, dp(14)));
            TextView s = text(12, INK3, Typeface.create("monospace", Typeface.NORMAL));
            s.setText("  " + p[1] + "× · " + Math.round(share * 100) + " %");
            row.addView(s);
            box.addView(row);
        }
        TextView key = text(11, INK3, Typeface.DEFAULT);
        key.setText("Balken: grau = Aufwand, grün = was davon gewirkt hat.");
        key.setPadding(0, dp(10), 0, 0);
        box.addView(key);
        summary.addView(box);
    }

    private View card(final int i, JSONObject it) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(12), dp(16), dp(12));
        String res = it.optString("res"), who = it.optString("who");
        int accent = res.equals("gewirkt") ? GREEN : res.equals("teilweise") ? YELLOW : res.equals("nichts") ? RUST : BLUE;
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x55000000);
        c.setBackground(bg);
        TextView meta = text(11, INK3, Typeface.create("monospace", Typeface.NORMAL));
        meta.setText(new SimpleDateFormat("d. MMM", Locale.GERMAN).format(new Date(it.optLong("at"))) + " · "
            + it.optString("area").toUpperCase(Locale.GERMAN) + " · Aufwand " + dots(it.optInt("effort", 1)));
        c.addView(meta);
        TextView t = text(16, INK, Typeface.DEFAULT_BOLD);
        t.setText(it.optString("t"));
        t.setPadding(0, dp(4), 0, 0);
        c.addView(t);
        String note = it.optString("note");
        if (!note.isEmpty()) {
            TextView n = text(13, INK2, Typeface.DEFAULT);
            n.setText(note);
            n.setPadding(0, dp(4), 0, 0);
            c.addView(n);
        }
        TextView r = text(13, accent, Typeface.DEFAULT_BOLD);
        r.setText(res + "  ·  " + (res.equals("offen") ? "" : who));
        r.setPadding(0, dp(8), 0, 0);
        c.addView(r);
        c.setOnClickListener(v -> edit(i));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.bottomMargin = dp(10);
        c.setLayoutParams(cl);
        return c;
    }

    private static String dots(int n) { StringBuilder s = new StringBuilder(); for (int i = 1; i <= 5; i++) s.append(i <= n ? "●" : "○"); return s.toString(); }

    /* ---------------- add / edit ---------------- */

    private void edit(final int index) {
        final JSONObject it = index >= 0 ? items.optJSONObject(index) : new JSONObject();
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(10), dp(20), 0);
        final EditText what = input("Was hast du getan?", it.optString("t"));
        f.addView(what);
        final String[] area = {it.optString("area", "Job")}, res = {it.optString("res", "offen")}, who = {it.optString("who", "beides")};
        final int[] effort = {it.optInt("effort", 3)};
        f.addView(label("Bereich"));
        f.addView(choice(AREAS, area));
        f.addView(label("Aufwand (1 klein · 5 riesig)"));
        f.addView(choice(new String[]{"1", "2", "3", "4", "5"}, null, effort));
        f.addView(label("Ergebnis"));
        f.addView(choice(RESULTS, res));
        f.addView(label("Lag es an dir?"));
        f.addView(choice(WHO, who));
        final EditText note = input("Notiz (optional)", it.optString("note"));
        f.addView(note);
        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(index >= 0 ? "Eintrag" : "Neuer Eintrag")
            .setView(sv)
            .setPositiveButton("Speichern", (d, w) -> {
                String t = what.getText().toString().trim();
                if (t.isEmpty()) return;
                try {
                    it.put("t", t).put("area", area[0]).put("effort", effort[0]).put("res", res[0]).put("who", who[0])
                        .put("note", note.getText().toString().trim());
                    if (!it.has("at")) it.put("at", System.currentTimeMillis());
                    if (index < 0) items.put(it);
                } catch (Exception ignored) { }
                save();
                render();
            })
            .setNegativeButton("Abbrechen", null);
        if (index >= 0) b.setNeutralButton("Löschen", (d, w) -> { items.remove(index); save(); render(); });
        b.show();
    }

    private View choice(String[] opts, String[] chosen) { return choice(opts, chosen, null); }

    /** A row of chips; one is selected. Strings go to chosen[0], numbers (1-based) to num[0]. */
    private View choice(final String[] opts, final String[] chosen, final int[] num) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        final TextView[] chips = new TextView[opts.length];
        for (int k = 0; k < opts.length; k++) {
            final int idx = k;
            boolean on = chosen != null ? opts[k].equals(chosen[0]) : num[0] == k + 1;
            chips[k] = pill(opts[k], on ? INK : CARD, on ? BG : INK, 13);
            chips[k].setPadding(dp(12), dp(8), dp(12), dp(8));
            chips[k].setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (chosen != null) chosen[0] = opts[idx]; else num[0] = idx + 1;
                for (int m = 0; m < chips.length; m++) tint(chips[m], m == idx ? INK : CARD, m == idx ? BG : INK);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(6);
            row.addView(chips[k], p);
        }
        return hs;
    }

    private TextView label(String s) {
        TextView t = text(11, INK3, Typeface.create("monospace", Typeface.BOLD));
        t.setText(s.toUpperCase(Locale.GERMAN));
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return e;
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
        tint(t, fill, ink);
        return t;
    }

    private void tint(TextView t, int fill, int ink) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(24));
        if (fill == CARD) g.setStroke(dp(1), LINE);
        t.setBackground(g); t.setTextColor(ink);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
