package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ARC-DNA Telefon: the dialer in the launcher's own style (Android doesn't let apps restyle the
 * system phone app). Keypad with key tones, recent calls when nothing is typed, and while typing the
 * contacts whose number or name (T9) matches. The call itself runs in Android's in-call screen.
 * Contacts and call log are read on the phone only.
 */
public class DialerActivity extends Activity {

    private static final int REQ_READ = 1, REQ_CALL = 2;
    private static final String[][] KEYS = {
        {"1", ""}, {"2", "ABC"}, {"3", "DEF"}, {"4", "GHI"}, {"5", "JKL"}, {"6", "MNO"},
        {"7", "PQRS"}, {"8", "TUV"}, {"9", "WXYZ"}, {"*", ""}, {"0", "+"}, {"#", ""}};

    private int bg, ink, ink2, ink3, rust, card, line;
    private final StringBuilder number = new StringBuilder();
    private TextView display;
    private LinearLayout suggestions;
    private ToneGenerator tones;
    private final List<String[]> contacts = new ArrayList<>();   // name, number

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        ink = night ? 0xFFECEBE6 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA9A8A2 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFC9402F : 0xFFB5271E;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C29 : 0xFFE0E0D8;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        setContentView(build());
        try {
            tones = new ToneGenerator(AudioManager.STREAM_DTMF, 70);
        } catch (RuntimeException e) {
            tones = null;
        }
        String data = getIntent() != null && getIntent().getData() != null ? getIntent().getData().getSchemeSpecificPart() : null;
        if (data != null) number.append(data.replaceAll("[^0-9+*#]", ""));
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS}, REQ_READ);
        } else {
            loadContacts();
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tones != null) tones.release();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(20), dp(36), dp(20), dp(20));
        root.setFitsSystemWindows(true);

        // Label row, like the home screen.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackgroundColor(rust);
        top.addView(bar, new LinearLayout.LayoutParams(dp(22), dp(2)));
        TextView label = text(11, ink3, true);
        label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        label.setLetterSpacing(0.2f);
        label.setText("  ARC-DNA · TELEFON");
        top.addView(label);
        root.addView(top);

        // Suggestions: recent calls, or matches while typing.
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        sv.addView(suggestions);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, 0, 1f);
        svl.topMargin = dp(14);
        root.addView(sv, svl);

        // The number, big and tight like the clock; ⌫ deletes, long-press clears.
        LinearLayout numRow = new LinearLayout(this);
        numRow.setGravity(Gravity.CENTER_VERTICAL);
        display = text(40, ink, true);
        display.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        display.setLetterSpacing(-0.03f);
        display.setSingleLine(true);
        display.setEllipsize(android.text.TextUtils.TruncateAt.START);
        numRow.addView(display, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView back = text(26, ink2, false);
        back.setText("⌫");
        back.setPadding(dp(14), dp(6), dp(6), dp(6));
        back.setOnClickListener(v -> {
            if (number.length() > 0) number.setLength(number.length() - 1);
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            refresh();
        });
        back.setOnLongClickListener(v -> {
            number.setLength(0);
            refresh();
            return true;
        });
        numRow.addView(back);
        root.addView(numRow, new LinearLayout.LayoutParams(-1, -2));

        // Keypad: 3 × 4 tiles.
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < 3; col++) {
                String[] k = KEYS[r * 3 + col];
                LinearLayout key = new LinearLayout(this);
                key.setOrientation(LinearLayout.VERTICAL);
                key.setGravity(Gravity.CENTER);
                key.setBackground(box(card, line, 8));
                key.setPadding(0, dp(8), 0, dp(8));
                TextView d = text(26, ink, false);
                d.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                d.setText(k[0]);
                d.setGravity(Gravity.CENTER);
                key.addView(d);
                TextView sub = text(9, ink3, true);
                sub.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                sub.setLetterSpacing(0.2f);
                sub.setText(k[1].isEmpty() ? " " : k[1]);
                sub.setGravity(Gravity.CENTER);
                key.addView(sub);
                key.setOnClickListener(v -> press(k[0]));
                if (k[0].equals("0")) key.setOnLongClickListener(v -> {
                    press("+");
                    return true;
                });
                LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, dp(64), 1f);
                kl.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(key, kl);
            }
            pad.addView(row);
        }
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = dp(10);
        root.addView(pad, pl);

        // Call button.
        FrameLayout callWrap = new FrameLayout(this);
        TextView call = text(16, 0xFFFFFFFF, true);
        call.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        call.setLetterSpacing(0.15f);
        call.setText("ANRUFEN");
        call.setGravity(Gravity.CENTER);
        call.setBackground(box(rust, 0, 30));
        call.setOnClickListener(v -> call(number.toString()));
        callWrap.addView(call, new FrameLayout.LayoutParams(dp(200), dp(58), Gravity.CENTER));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(14);
        root.addView(callWrap, cl);
        return root;
    }

    private void press(String key) {
        number.append(key);
        if (tones != null) {
            int tone = key.equals("*") ? ToneGenerator.TONE_DTMF_S : key.equals("#") ? ToneGenerator.TONE_DTMF_P
                : key.equals("+") ? -1 : ToneGenerator.TONE_DTMF_0 + Integer.parseInt(key);
            if (tone >= 0) tones.startTone(tone, 120);
        }
        display.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        refresh();
    }

    private void refresh() {
        display.setText(number.length() == 0 ? "" : number);
        display.setHint("Nummer…");
        display.setHintTextColor(ink3);
        suggestions.removeAllViews();
        String q = number.toString();
        List<String[]> rows = q.isEmpty() ? recents() : matches(q);
        TextView head = text(11, ink3, true);
        head.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        head.setLetterSpacing(0.2f);
        head.setText(q.isEmpty() ? "ZULETZT" : rows.isEmpty() ? "KEIN KONTAKT" : "KONTAKTE");
        head.setPadding(0, 0, 0, dp(8));
        suggestions.addView(head);
        for (String[] r : rows) suggestions.addView(row(r[0], r[1], r.length > 2 ? r[2] : null));
        if (rows.isEmpty() && q.isEmpty() && checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            TextView ask = text(13, ink2, false);
            ask.setText("Anrufliste und Kontakte erlauben, um sie hier zu sehen →");
            ask.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS}, REQ_READ));
            suggestions.addView(ask);
        }
    }

    private View row(String name, String num, String meta) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(box(card, line, 8));
        r.setPadding(dp(14), dp(10), dp(10), dp(10));
        TextView initial = text(16, 0xFFFFFFFF, true);
        initial.setGravity(Gravity.CENTER);
        String n = name == null || name.isEmpty() ? num : name;
        initial.setText(n.isEmpty() ? "#" : n.substring(0, 1).toUpperCase(Locale.ROOT));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(rust);
        initial.setBackground(circle);
        r.addView(initial, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView t1 = text(15, ink, true);
        t1.setText(n);
        t1.setSingleLine(true);
        texts.addView(t1);
        TextView t2 = text(12, ink2, false);
        t2.setText(meta != null ? num + " · " + meta : num);
        t2.setSingleLine(true);
        texts.addView(t2);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = text(13, rust, true);
        go.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        go.setText("ANRUF");
        go.setPadding(dp(10), dp(8), dp(4), dp(8));
        r.addView(go);
        r.setOnClickListener(v -> call(num));
        r.setOnLongClickListener(v -> {
            number.setLength(0);
            number.append(num.replaceAll("[^0-9+*#]", ""));
            refresh();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    /** The last calls, one row per number, newest first. */
    private List<String[]> recents() {
        List<String[]> out = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return out;
        Map<String, String[]> seen = new LinkedHashMap<>();
        try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
            new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE},
            null, null, CallLog.Calls.DATE + " DESC")) {
            while (c != null && c.moveToNext() && seen.size() < 12) {
                String num = c.getString(0);
                if (num == null || num.isEmpty() || seen.containsKey(num)) continue;
                int type = c.getInt(2);
                String kind = type == CallLog.Calls.MISSED_TYPE ? "verpasst" : type == CallLog.Calls.OUTGOING_TYPE ? "ausgehend" : "eingehend";
                seen.put(num, new String[]{c.getString(1), num, kind});
            }
        } catch (RuntimeException ignored) {
        }
        out.addAll(seen.values());
        return out;
    }

    private void loadContacts() {
        contacts.clear();
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            while (c != null && c.moveToNext()) contacts.add(new String[]{c.getString(0), c.getString(1)});
        } catch (RuntimeException ignored) {
        }
    }

    /** Contacts whose number contains the typed digits, or whose name fits them on the keypad (T9). */
    private List<String[]> matches(String q) {
        List<String[]> out = new ArrayList<>();
        String digits = q.replaceAll("[^0-9]", "");
        for (String[] ct : contacts) {
            String num = ct[1] == null ? "" : ct[1].replaceAll("[^0-9]", "");
            boolean hit = !digits.isEmpty() && (num.contains(digits) || t9(ct[0]).contains(digits));
            if (hit) out.add(new String[]{ct[0], ct[1]});
            if (out.size() >= 12) break;
        }
        return out;
    }

    private static String t9(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : name.toUpperCase(Locale.ROOT).toCharArray()) {
            for (String[] k : KEYS) if (!k[1].isEmpty() && k[1].indexOf(ch) >= 0 && Character.isLetter(ch)) b.append(k[0]);
        }
        return b.toString();
    }

    private void call(String num) {
        String n = num == null ? "" : num.trim();
        if (n.isEmpty()) return;
        Uri uri = Uri.fromParts("tel", n, null);
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(new Intent(Intent.ACTION_CALL, uri));
                return;
            } catch (RuntimeException ignored) {
            }
        } else {
            pendingCall = n;
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL);
            return;
        }
        startActivity(new Intent(Intent.ACTION_DIAL, uri));
    }

    private String pendingCall;

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_READ) {
            loadContacts();
            refresh();
        } else if (code == REQ_CALL && pendingCall != null) {
            String n = pendingCall;
            pendingCall = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) call(n);
            else startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)));   // the system dialer takes over
        }
    }
}
