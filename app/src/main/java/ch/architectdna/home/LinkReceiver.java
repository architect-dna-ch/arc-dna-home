package ch.architectdna.home;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * ARC Link (the Mac keyboard over USB) talks to the tablet through this receiver; it is declared
 * in the manifest, so it works without any ARC screen open.
 *   ch.architectdna.home.TYPE      --es t "über"   → types text adb can't (ü, é, emoji) into the focused field
 *   ch.architectdna.home.KBD_BATT  --ei level 72 --ez charging false → the "⌨ 72%" chip
 * Typing uses the ARC-DNA Scroll accessibility service (it can edit the focused field); without
 * it, the text goes to the clipboard so a long-press → Paste still works.
 */
public class LinkReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if ("ch.architectdna.home.KBD_BATT".equals(a)) {
            int level = i.getIntExtra("level", -1);
            if (level >= 0 && KbdBatt.canShow(c)) KbdBatt.show(c.getApplicationContext(), level, i.getBooleanExtra("charging", false));
            return;
        }
        if ("ch.architectdna.home.CLOSE_ALL".equals(a)) { closeAll(c); return; }
        if (!"ch.architectdna.home.TYPE".equals(a)) return;
        String t = i.getStringExtra("t");
        if (t == null || t.isEmpty()) return;
        if (!typeInto(t)) {
            ClipboardManager cm = c.getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ARC Link", t));
        }
    }

    /**
     * Close every app: open Recents and press its "Clear all" (works on HyperOS, Pixel, One UI);
     * without the accessibility service, at least go home.
     */
    static void closeAll(Context c) {
        ScrollAssistService s = ScrollAssistService.instance;
        if (s == null) {
            try { c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) { }
            return;
        }
        s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS);
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> clickClearAll(s, 0), 650);
    }

    private static final String[] CLEAR = {"clear all", "close all", "alle schliessen", "alles schliessen", "alle löschen",
        "alles löschen", "alle entfernen", "clear", "tout effacer", "cancella tutto"};

    private static void clickClearAll(ScrollAssistService s, int tries) {
        AccessibilityNodeInfo hit = null;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                hit = find(root);
                if (hit != null) break;
            }
        } catch (RuntimeException ignored) { }
        if (hit != null) {
            AccessibilityNodeInfo n = hit;
            while (n != null && !n.isClickable()) n = n.getParent();
            (n != null ? n : hit).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return;
        }
        if (tries < 3) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> clickClearAll(s, tries + 1), 300);
        } else {
            s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);   // no button found: home at least
        }
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo n) {
        if (n == null) return null;
        String t = (n.getText() == null ? "" : n.getText().toString()) + "|" + (n.getContentDescription() == null ? "" : n.getContentDescription().toString());
        t = t.toLowerCase(java.util.Locale.ROOT);
        for (String k : CLEAR) {
            for (String part : t.split("\\|")) if (part.trim().equals(k) || (k.length() > 5 && part.contains(k))) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = find(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    /** Insert at the cursor of the focused text field (replacing a selection), like real typing. */
    private static boolean typeInto(String t) {
        ScrollAssistService s = ScrollAssistService.instance;
        if (s == null) return false;
        AccessibilityNodeInfo n;
        try { n = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); } catch (RuntimeException e) { return false; }
        if (n == null || !n.isEditable()) return false;
        CharSequence cur = n.getText();
        // A hint shows as text while the field is empty: don't keep it.
        String text = cur == null || (n.isShowingHintText()) ? "" : cur.toString();
        int start = n.getTextSelectionStart(), end = n.getTextSelectionEnd();
        if (start < 0 || start > text.length()) start = end = text.length();
        if (end < start || end > text.length()) end = start;
        String next = text.substring(0, start) + t + text.substring(end);
        Bundle set = new Bundle();
        set.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, set)) return false;
        Bundle sel = new Bundle();
        int caret = start + t.length();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        return true;
    }
}
