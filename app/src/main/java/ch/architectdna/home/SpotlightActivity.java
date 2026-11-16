package ch.architectdna.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static ch.architectdna.home.ArcUi.t;

/**
 * ARC Spotlight: like ⌘+Space on a Mac, for the tablet. From anywhere (ARC Link sends ⌘+Space),
 * a search card drops over whatever is open: type, Enter opens the top hit, ↑/↓ choose, Esc or a
 * tap outside closes. Finds every app plus ARC's own screens (Math, Bücher, Remote …).
 */
public class SpotlightActivity extends Activity {

    private static final class Item {
        String label, low;
        Drawable icon;
        String emoji;
        Intent intent;
    }

    private ArcUi ui;
    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private LinearLayout list;
    private EditText field;
    private int sel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);
        root.setOnClickListener(v -> finish());             // tap outside = close

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        card.setBackground(ui.box(ui.card, ui.line, 14));
        card.setElevation(ui.dp(18));
        card.setClickable(true);                           // taps inside don't close
        int w = Math.min(ui.dp(640), (int) (getResources().getDisplayMetrics().widthPixels * 0.92f));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(w, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cl.topMargin = (int) (getResources().getDisplayMetrics().heightPixels * 0.12f);
        root.addView(card, cl);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView mag = new TextView(this);
        mag.setText("⌕");
        mag.setTextSize(24);
        mag.setTextColor(ui.ink3);
        bar.addView(mag);
        field = new EditText(this);
        field.setHint(t("Open app …", "App öffnen …"));
        field.setHintTextColor(ui.ink3);
        field.setTextColor(ui.ink);
        field.setTextSize(22);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setPadding(ui.dp(10), ui.dp(8), ui.dp(4), ui.dp(8));
        field.setOnEditorActionListener((v, id, e) -> { launch(sel); return true; });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { sel = 0; filter(s.toString()); }
        });
        bar.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView esc = ui.mono("ESC", ui.ink3, 10);
        esc.setPadding(ui.dp(8), ui.dp(6), ui.dp(4), ui.dp(6));
        esc.setOnClickListener(v -> finish());
        bar.addView(esc);
        card.addView(bar);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list, ui.lp(-1, -2, ui.dp(6)));
        setContentView(root);

        field.requestFocus();
        new Thread(this::load, "arc-spotlight").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();                                          // gone as soon as something else is in front
    }

    private void load() {
        PackageManager pm = getPackageManager();
        List<Item> items = new ArrayList<>();
        // ARC's own screens first, so "math" or "bücher" finds them
        Object[][] arc = {
            {"∑", "ARC Math", MathActivity.class}, {"📚", t("Books", "Bücher"), BuecherActivity.class},
            {"🔊", "ARC Remote", RemoteActivity.class}, {"💾", "ARC Player", PlayerActivity.class},
            {"⚖️", "Einspruch", EinspruchActivity.class}, {"⚓", "Anchor", AnchorActivity.class},
            {"🧬", "ARC-DNA Hub", HubActivity.class}, {"🐻", t("Bear alarm", "Bär-Wecker"), WeckerActivity.class},
            {"🔑", t("Passwords", "Passwörter"), VaultActivity.class},
        };
        for (Object[] a : arc) {
            Item it = new Item();
            it.emoji = (String) a[0];
            it.label = (String) a[1];
            it.intent = new Intent(this, (Class<?>) a[2]);
            items.add(it);
        }
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            Item it = new Item();
            it.label = String.valueOf(ri.loadLabel(pm));
            it.icon = ri.loadIcon(pm);
            it.intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            items.add(it);
        }
        Item close = new Item();                      // "close" → Enter: every app closed
        close.emoji = "🧹";
        close.label = t("Close all apps", "Alle Apps schliessen");
        close.intent = null;
        items.add(0, close);
                for (Item it : items) it.low = norm(it.label);
        runOnUiThread(() -> {
            all.clear();
            all.addAll(items);
            filter(field.getText().toString());
        });
    }

    private static String norm(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Starts with > a word starts with > contains > letters in order ("ytm" → YouTube Music). */
    private void filter(String q) {
        String n = norm(q.trim());
        shown.clear();
        if (n.isEmpty()) {
            for (int i = 0; i < Math.min(8, all.size()); i++) shown.add(all.get(i));
        } else {
            List<Item> a = new ArrayList<>(), b = new ArrayList<>(), c = new ArrayList<>(), d = new ArrayList<>();
            for (Item it : all) {
                if (it.low.startsWith(n)) a.add(it);
                else if (it.low.contains(" " + n) || it.low.contains("-" + n)) b.add(it);
                else if (it.low.contains(n)) c.add(it);
                else if (inOrder(it.low, n)) d.add(it);
            }
            for (List<Item> l : java.util.Arrays.asList(a, b, c, d)) for (Item it : l) if (shown.size() < 8) shown.add(it);
        }
        sel = Math.max(0, Math.min(sel, shown.size() - 1));
        paint();
    }

    private static boolean inOrder(String s, String q) {
        int i = 0;
        for (char ch : s.toCharArray()) if (i < q.length() && ch == q.charAt(i)) i++;
        return i == q.length();
    }

    private void paint() {
        list.removeAllViews();
        for (int i = 0; i < shown.size(); i++) {
            Item it = shown.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
            if (i == sel) r.setBackground(ui.box(ui.dark ? 0xFF2A2522 : 0xFFF3ECE6, 0, 8));
            if (it.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(it.icon);
                r.addView(iv, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
            } else {
                TextView e = new TextView(this);
                e.setText(it.emoji);
                e.setTextSize(22);
                e.setGravity(Gravity.CENTER);
                r.addView(e, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
            }
            TextView l = ui.body(it.label, ui.ink, 17);
            if (i == sel) l.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            l.setPadding(ui.dp(14), 0, 0, 0);
            r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
            if (i == sel) r.addView(ui.mono("↵", ui.rust, 13));
            int idx = i;
            r.setOnClickListener(v -> launch(idx));
            list.addView(r);
        }
    }

    private void launch(int i) {
        if (i < 0 || i >= shown.size()) return;
        Intent in = shown.get(i).intent;
        finish();
        if (in == null) { LinkReceiver.closeAll(getApplicationContext()); return; }
        try { startActivity(in); } catch (RuntimeException ignored) { }
        overridePendingTransition(0, 0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_ESCAPE: finish(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (sel < shown.size() - 1) { sel++; paint(); } return true;
                case KeyEvent.KEYCODE_DPAD_UP: if (sel > 0) { sel--; paint(); } return true;
                case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: launch(sel); return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }
}
