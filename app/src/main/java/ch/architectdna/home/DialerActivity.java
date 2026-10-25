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

    /** Blueprint grid behind everything, like the home screen and wallpaper. */
    private final class Grid extends View {
        private final android.graphics.Paint p = new android.graphics.Paint();

        Grid() {
            super(DialerActivity.this);
            p.setStrokeWidth(dp(1));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            p.setColor((ink & 0x00FFFFFF) | 0x0F000000);
            float cell = dp(32);
            for (float x = 0; x < getWidth(); x += cell) c.drawLine(x, 0, x, getHeight(), p);
            for (float y = 0; y < getHeight(); y += cell) c.drawLine(0, y, getWidth(), y, p);
        }
    }

    private TextView mono(float sp, int colour) {
        TextView t = text(sp, colour, true);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.16f);
        return t;
    }

    private View sectionLabel(String s) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackgroundColor(rust);
        row.addView(bar, new LinearLayout.LayoutParams(dp(22), dp(2)));
        TextView t = mono(11, ink3);
        t.setLetterSpacing(0.2f);
        t.setText(s.toUpperCase(Locale.GERMAN));
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private View build() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(bg);
        frame.addView(new Grid(), new FrameLayout.LayoutParams(-1, -1));
        // The window insets go to this wrapper, so the content keeps its own side margins.
        FrameLayout safe = new FrameLayout(this);
        safe.setFitsSystemWindows(true);
        frame.addView(safe, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        safe.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // — ARC-DNA · TELEFON ……… ● BEREIT
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(sectionLabel("Architect · DNA · Telefon"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ready = mono(11, ink3);
        android.text.SpannableString st = new android.text.SpannableString("● BEREIT");
        st.setSpan(new android.text.style.ForegroundColorSpan(0xFF1A7A3C), 0, 1, 0);
        ready.setText(st);
        top.addView(ready);
        root.addView(top);

        // Recent calls / matches in one card with dividers.
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listHead = (LinearLayout) sectionLabel("Zuletzt");
        list.addView(listHead);
        suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        suggestions.setBackground(box(card, line, 8));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(10);
        list.addView(suggestions, sl);
        sv.addView(list);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, 0, 1f);
        svl.topMargin = dp(20);
        root.addView(sv, svl);

        // The number, big and tight like the clock, with a blinking rust cursor; ⌫ deletes, long-press clears.
        LinearLayout numRow = new LinearLayout(this);
        numRow.setGravity(Gravity.CENTER_VERTICAL);
        numRow.setPadding(0, dp(14), 0, dp(6));
        display = text(40, ink, true);
        display.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        display.setLetterSpacing(-0.04f);
        display.setSingleLine(true);
        display.setEllipsize(android.text.TextUtils.TruncateAt.START);
        numRow.addView(display, new LinearLayout.LayoutParams(-2, -2));
        View cursor = new View(this);
        cursor.setBackgroundColor(rust);
        LinearLayout.LayoutParams cl0 = new LinearLayout.LayoutParams(dp(4), dp(40));
        cl0.leftMargin = dp(4);
        numRow.addView(cursor, cl0);
        android.animation.ObjectAnimator blink = android.animation.ObjectAnimator.ofFloat(cursor, View.ALPHA, 1f, 0f);
        blink.setDuration(530);
        blink.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        blink.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        blink.start();
        numRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView back = mono(13, ink2);
        back.setText("⌫ LÖSCHEN");
        back.setPadding(dp(12), dp(8), dp(12), dp(8));
        back.setBackground(box(card, line, 8));
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

        // Keypad: 3 × 4 sharp tiles.
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
                key.setPadding(0, dp(6), 0, dp(6));
                TextView d = text(26, ink, true);
                d.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                d.setText(k[0]);
                d.setGravity(Gravity.CENTER);
                key.addView(d);
                TextView sub = mono(9, k[0].equals("0") ? rust : ink3);
                sub.setLetterSpacing(0.25f);
                sub.setText(k[1].isEmpty() ? " " : k[1]);
                sub.setGravity(Gravity.CENTER);
                key.addView(sub);
                key.setOnClickListener(v -> press(k[0]));
                if (k[0].equals("0")) key.setOnLongClickListener(v -> {
                    press("+");
                    return true;
                });
                LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, dp(62), 1f);
                kl.setMargins(col == 0 ? 0 : dp(4), dp(4), col == 2 ? 0 : dp(4), dp(4));
                row.addView(key, kl);
            }
            pad.addView(row);
        }
        root.addView(pad, new LinearLayout.LayoutParams(-1, -2));

        // Call bar: full width, rust, sharp.
        TextView call = mono(15, 0xFFFFFFFF);
        call.setLetterSpacing(0.2f);
        call.setText("ANRUFEN  →");
        call.setGravity(Gravity.CENTER);
        call.setBackground(box(rust, 0, 8));
        call.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            call(number.toString());
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, dp(58));
        cl.topMargin = dp(10);
        root.addView(call, cl);
        return frame;
    }

    private LinearLayout listHead;

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
        display.setHint("Nummer");
        display.setHintTextColor(ink3);
        suggestions.removeAllViews();
        String q = number.toString();
        List<String[]> rows = q.isEmpty() ? recents() : matches(q);
        ((TextView) listHead.getChildAt(1)).setText(q.isEmpty() ? "ZULETZT" : rows.isEmpty() ? "KEIN KONTAKT" : "KONTAKTE · " + rows.size());
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                View div = new View(this);
                div.setBackgroundColor(line);
                suggestions.addView(div, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            String[] r = rows.get(i);
            suggestions.addView(row(r[0], r[1], r.length > 2 ? r[2] : null));
        }
        suggestions.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (rows.isEmpty() && q.isEmpty() && checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            suggestions.setVisibility(View.VISIBLE);
            TextView ask = text(13, ink2, false);
            ask.setPadding(dp(14), dp(12), dp(14), dp(12));
            ask.setText("Anrufliste und Kontakte erlauben, um sie hier zu sehen →");
            ask.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS}, REQ_READ));
            suggestions.addView(ask);
        }
    }

    private View row(String name, String num, String meta) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        String n = name == null || name.isEmpty() ? num : name;
        // Square monogram tile, ink outline; missed calls in rust.
        TextView initial = text(16, "verpasst".equals(meta) ? rust : ink, true);
        initial.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        initial.setGravity(Gravity.CENTER);
        initial.setText(n.isEmpty() ? "#" : n.substring(0, 1).toUpperCase(Locale.ROOT));
        initial.setBackground(box(bg, "verpasst".equals(meta) ? rust : line, 8));
        r.addView(initial, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView t1 = text(15, ink, true);
        t1.setText(n);
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t1);
        TextView t2 = mono(10, "verpasst".equals(meta) ? rust : ink3);
        t2.setLetterSpacing(0.1f);
        t2.setText((meta != null ? meta.toUpperCase(Locale.GERMAN) + " · " : "") + num);
        t2.setSingleLine(true);
        texts.addView(t2);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView go = mono(11, rust);
        go.setText("→");
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        go.setPadding(dp(10), 0, dp(4), 0);
        r.addView(go);
        r.setOnClickListener(v -> call(num));
        r.setOnLongClickListener(v -> {
            number.setLength(0);
            number.append(num.replaceAll("[^0-9+*#]", ""));
            refresh();
            return true;
        });
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
