package ch.architectdna.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Whiteboard, native: draw with a finger or stylus (pressure makes the line thicker), two fingers
 * move and zoom the endless board. Colours from the ARC-DNA palette, a few widths, eraser, undo /
 * redo. Every board is saved on the device and gets a pseudo doctor title ("Dr. Overloaded",
 * renamable by tapping it). Share sends the board as a picture.
 */
public class BoardActivity extends Activity {

    static final String[] TITLES = {"Dr. Overloaded", "Dr. Überladen", "Prof. Chaos", "Dr. Kritzel", "Dr. Deadline",
        "Prof. Brainstorm", "Dr. Kaffee", "Dr. Später", "Prof. Pfeil", "Dr. Zuviel", "Dr. Tabula Rasa", "Prof. Plan B",
        "Dr. Mindmap", "Dr. Gekritzel", "Prof. Eigentlich", "Dr. Multitask", "Dr. Notfall", "Prof. Skizze"};

    private SharedPreferences prefs;
    private boolean night;
    private int bg, card, line, ink, ink2, ink3, rust;
    private int[] colours;
    private Board board;
    private TextView title;
    private String boardId;
    private final List<View> colourDots = new ArrayList<>(), widthChips = new ArrayList<>();
    private TextView eraserChip;

    /* ---------- strokes ---------- */

    static final class Stroke {
        int colour;
        float width;
        boolean eraser;
        /** A picture instead of a line: file in board_img/, pts = x, y, width, height on the board. */
        String image;
        float[] pts = new float[64];     // x, y, pressure …
        int n;

        void add(float x, float y, float p) {
            if (n + 3 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[n++] = x;
            pts[n++] = y;
            pts[n++] = p;
        }
    }

    private final List<Stroke> strokes = new ArrayList<>(), redo = new ArrayList<>();
    private int colour;
    private float width = 4;
    private boolean eraser;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.app_slide_up, R.anim.home_hold);
        else overridePendingTransition(R.anim.app_slide_up, R.anim.home_hold);
        prefs = getSharedPreferences("board", MODE_PRIVATE);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121211 : 0xFFFAFAF8;
        card = night ? 0xFF1C1C1A : 0xFFFFFFFF;
        line = night ? 0xFF2C2C2A : 0xFFE0E0D8;
        ink = night ? 0xFFEDEDE8 : 0xFF1A1A1A;
        ink2 = night ? 0xFFA5A5A0 : 0xFF5A5A58;
        ink3 = night ? 0xFF6F6E69 : 0xFF9A9A98;
        rust = night ? 0xFFD9472F : 0xFFB5271E;
        colours = new int[]{ink, rust, 0xFFFFC400, 0xFF2BBF6A, 0xFF3C8DFF, 0xFFA77BFF, 0xFFF29A1F};
        colour = ink;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        board = new Board();
        root.addView(board, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout ui = new LinearLayout(this);
        ui.setOrientation(LinearLayout.VERTICAL);
        guide = new Guide();
        root.addView(guide, new FrameLayout.LayoutParams(-1, -1));
        mischief = new Mischief();
        root.addView(mischief, new FrameLayout.LayoutParams(-1, -1));
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));

        // Top: label, the board's doctor title (tap = rename), boards, share.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(12), dp(10), dp(8));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        View dash = new View(this);
        dash.setBackgroundColor(rust);
        labelRow.addView(dash, new LinearLayout.LayoutParams(dp(22), dp(2)));
        TextView label = mono(10, ink3);
        label.setText("WHITEBOARD");
        label.setLetterSpacing(0.24f);
        label.setPadding(dp(10), 0, 0, 0);
        labelRow.addView(label);
        heading.addView(labelRow);
        title = new TextView(this);
        title.setTextColor(ink);
        title.setTextSize(24);
        title.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        title.setLetterSpacing(-0.03f);
        title.setOnClickListener(v -> rename());
        heading.addView(title);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(chip("BOARDS", v -> showBoards()));
        cloudChip = chip(cloudLabel(), v -> {
            prefs.edit().putBoolean("clouds", !prefs.getBoolean("clouds", true)).apply();
            cloudChip.setText(cloudLabel());
            board.invalidate();
        });
        top.addView(cloudChip);
        buddyChip = chip(buddyLabel(), v -> {
            if (mischief.foxHere()) {
                mischief.callPolice();             // the fox is about: the robo police comes
                return;
            }
            prefs.edit().putBoolean("buddy", !prefs.getBoolean("buddy", false)).apply();
            buddyChip.setText(buddyLabel());
            mischief.planNext();
            if (prefs.getBoolean("buddy", false)) Toast.makeText(this, "Fuchs darf Chaos machen. Viel Glück.", Toast.LENGTH_SHORT).show();
        });
        top.addView(buddyChip);
        top.addView(chip("✏ LERNEN", v -> pickGuide()));
        top.addView(chip("+ BILD", v -> pickImage()));
        top.addView(chip("+ NEU", v -> newBoard()));
        top.addView(chip("TEILEN", v -> share()));
        ui.addView(top);
        ui.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        // Bottom toolbar: colours, widths, eraser, undo / redo, clear.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(card);
        barBg.setStroke(dp(1), line);
        barBg.setCornerRadius(dp(28));
        bar.setBackground(barBg);
        for (int c : colours) {
            View dot = new View(this);
            dot.setOnClickListener(v -> {
                colour = c;
                eraser = false;
                paintTools();
            });
            dot.setTag(c);
            colourDots.add(dot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.setMargins(dp(2), 0, dp(2), 0);
            bar.addView(dot, lp);
        }
        bar.addView(divider());
        float[] widths = {2, 4, 9, 18};
        // Narrow screen: colours on top, tools below, both inside the pill (nothing runs off the edge).
        LinearLayout tools = bar;
        if (getResources().getConfiguration().screenWidthDp < 600) {
            tools = new LinearLayout(this);
            tools.setGravity(Gravity.CENTER);
            bar.removeViewAt(bar.getChildCount() - 1);      // the divider
        }
        LinearLayout row2 = tools;
        for (float w : widths) {
            TextView t = chipView(w == 2 ? "·" : w == 4 ? "•" : w == 9 ? "●" : "⬤");
            t.setTag(w);
            t.setOnClickListener(v -> {
                width = w;
                paintTools();
            });
            widthChips.add(t);
            row2.addView(t);
        }
        if (row2 == bar) bar.addView(divider());
        eraserChip = chipView("RADIERER");
        eraserChip.setOnClickListener(v -> {
            eraser = !eraser;
            paintTools();
        });
        row2.addView(eraserChip);
        TextView undo = chipView("↶");
        undo.setOnClickListener(v -> {
            if (strokes.isEmpty()) return;
            if (placing == strokes.get(strokes.size() - 1)) finishPlacing();
            redo.add(strokes.remove(strokes.size() - 1));
            board.rebuild();
            save();
        });
        row2.addView(undo);
        TextView again = chipView("↷");
        again.setOnClickListener(v -> {
            if (redo.isEmpty()) return;
            strokes.add(redo.remove(redo.size() - 1));
            board.rebuild();
            save();
        });
        row2.addView(again);
        TextView clear = chipView(row2 == bar ? "LEEREN" : "✕");
        clear.setOnClickListener(v -> Toast.makeText(this, "Lange drücken = Board leeren", Toast.LENGTH_SHORT).show());
        clear.setOnLongClickListener(v -> {
            redo.clear();
            redo.addAll(strokes);
            java.util.Collections.reverse(redo);
            strokes.clear();
            board.rebuild();
            save();
            Toast.makeText(this, "Geleert · ↷ holt alles zurück", Toast.LENGTH_SHORT).show();
            return true;
        });
        row2.addView(clear);
        if (row2 != bar) {
            LinearLayout both = new LinearLayout(this);
            both.setOrientation(LinearLayout.VERTICAL);
            both.setGravity(Gravity.CENTER_HORIZONTAL);
            both.setPadding(dp(10), dp(8), dp(10), dp(8));
            both.setBackground(bar.getBackground());
            bar.setBackground(null);
            bar.setPadding(0, 0, 0, dp(6));
            bar.setGravity(Gravity.CENTER);
            both.addView(bar);
            both.addView(row2);
            hs.addView(both);
        } else hs.addView(bar);
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(10), 0, dp(10), dp(14));
        bottom.addView(hs);
        ui.addView(bottom);

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null && !night) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(light, light);
        }
        root.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                ui.setPadding(i.left, i.top, i.right, i.bottom);
            }
            return in;
        });

        boardId = prefs.getString("current", null);
        if (boardId == null || !boardFile(boardId).exists() && listBoards().length() == 0) newBoard();
        else open(boardId);
        paintTools();
        takeShared(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    /* ---------- boards: list in prefs, strokes in files ---------- */

    private File boardFile(String id) {
        File dir = new File(getFilesDir(), "boards");
        dir.mkdirs();
        return new File(dir, id + ".wb");
    }

    private JSONArray listBoards() {
        try {
            return new JSONArray(prefs.getString("boards", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private String titleOf(String id) {
        JSONArray a = listBoards();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o.optString("title", TITLES[0]);
        }
        return TITLES[0];
    }

    private void putBoard(String id, String t) {
        JSONArray a = listBoards(), out = new JSONArray();
        try {
            out.put(new JSONObject().put("id", id).put("title", t).put("at", System.currentTimeMillis()));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id"))) out.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("boards", out.toString()).putString("current", id).apply();
    }

    private String freshTitle() {
        JSONArray a = listBoards();
        List<String> used = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) used.add(a.optJSONObject(i).optString("title"));
        Random r = new Random();
        for (int k = 0; k < 40; k++) {
            String t = TITLES[r.nextInt(TITLES.length)];
            if (!used.contains(t)) return t;
        }
        return TITLES[r.nextInt(TITLES.length)] + " " + (a.length() + 1);
    }

    private void newBoard() {
        if (boardId != null) save();
        boardId = Long.toString(System.currentTimeMillis(), 36);
        strokes.clear();
        redo.clear();
        String t = freshTitle();
        putBoard(boardId, t);
        title.setText(t);
        if (board != null) {
            board.reset();
            board.rebuild();
        }
        save();
    }

    private void open(String id) {
        boardId = id;
        strokes.clear();
        redo.clear();
        strokes.addAll(readStrokes(boardFile(id)));
        putBoard(id, titleOf(id));
        title.setText(titleOf(id));
        board.reset();
        board.rebuild();
    }

    /** A board's strokes from its file (empty if missing or broken). */
    private static List<Stroke> readStrokes(File file) {
        List<Stroke> strokes = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(file)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                Stroke s = new Stroke();
                s.colour = in.readInt();
                s.width = in.readFloat();
                s.eraser = in.readBoolean();
                int n = in.readInt();
                s.pts = new float[Math.max(3, n)];
                for (int k = 0; k < n; k++) s.pts[k] = in.readFloat();
                s.n = n;
                if (s.width < 0) s.image = in.readUTF();          // width -1 marks a picture
                strokes.add(s);
            }
        } catch (IOException ignored) {
        }
        return strokes;
    }

    private void save() {
        if (boardId == null) return;
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(boardFile(boardId))))) {
            out.writeInt(strokes.size());
            for (Stroke s : strokes) {
                out.writeInt(s.colour);
                out.writeFloat(s.width);
                out.writeBoolean(s.eraser);
                out.writeInt(s.n);
                for (int k = 0; k < s.n; k++) out.writeFloat(s.pts[k]);
                if (s.image != null) out.writeUTF(s.image);
            }
        } catch (IOException ignored) {
        }
    }

    private void rename() {
        EditText e = new EditText(this);
        e.setText(title.getText());
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new AlertDialog.Builder(this)
            .setTitle("Titel")
            .setView(e)
            .setPositiveButton("OK", (d, w) -> {
                String t = e.getText().toString().trim();
                if (t.isEmpty()) return;
                title.setText(t);
                putBoard(boardId, t);
            })
            .setNeutralButton("Zufall", (d, w) -> {
                String t = freshTitle();
                title.setText(t);
                putBoard(boardId, t);
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    /*
     * The boards overview, full screen: two thirds a grid of your boards with a small preview of
     * each (tap = open, hold = delete), one third a sheet of doodles to get you going – flowers that
     * sway, a sun, and maths that's almost right (2 + 2 = 3).
     */
    private FrameLayout overview;

    private void showBoards() {
        save();
        if (overview != null) return;
        FrameLayout root = (FrameLayout) board.getParent();
        boolean wide = root.getWidth() > root.getHeight();
        overview = new FrameLayout(this);
        overview.setBackgroundColor(bg);
        overview.setClickable(true);
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int top = 0, bottom = 0;
        if (Build.VERSION.SDK_INT >= 30 && root.getRootWindowInsets() != null) {
            android.graphics.Insets in = root.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = in.top;
            bottom = in.bottom;
        }
        split.setPadding(0, top, 0, bottom);
        overview.addView(split, new FrameLayout.LayoutParams(-1, -1));

        // The boards.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(24), dp(16), dp(16), dp(16));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView small = mono(10, ink3);
        small.setText("— WHITEBOARD");
        small.setLetterSpacing(0.24f);
        titles.addView(small);
        TextView big = new TextView(this);
        big.setText("Deine Boards");
        big.setTextColor(ink);
        big.setTextSize(30);
        big.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        big.setLetterSpacing(-0.04f);
        titles.addView(big);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(chip("+ NEU", v -> {
            closeOverview();
            newBoard();
        }));
        TextView x = chip("✕", v -> closeOverview());
        head.addView(x);
        left.addView(head);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        int cols = wide ? 3 : 2;
        grid.setColumnCount(cols);
        sv.addView(grid);
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, 0, 1f);
        svl.topMargin = dp(14);
        left.addView(sv, svl);
        split.addView(left, wide ? new LinearLayout.LayoutParams(0, -1, 2f) : new LinearLayout.LayoutParams(-1, 0, 2f));

        // The doodles.
        Doodles doodles = new Doodles();
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(card);
        dbg.setStroke(dp(1), line);
        dbg.setCornerRadius(dp(24));
        doodles.setBackground(dbg);
        LinearLayout.LayoutParams dl = wide ? new LinearLayout.LayoutParams(0, -1, 1f) : new LinearLayout.LayoutParams(-1, 0, 1f);
        dl.setMargins(dp(8), dp(16), dp(16), dp(16));
        split.addView(doodles, dl);

        root.addView(overview, new FrameLayout.LayoutParams(-1, -1));
        overview.setAlpha(0f);
        overview.setTranslationY(dp(30));
        overview.animate().alpha(1f).translationY(0).setDuration(240).start();

        JSONArray a = listBoards();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d. MMM · HH:mm", java.util.Locale.GERMAN);
        overview.post(() -> {
            int cellW = (sv.getWidth() - dp(12) * (cols - 1)) / cols, cellH = cellW * 2 / 3;
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o == null) continue;
                String id = o.optString("id");
                LinearLayout cardV = new LinearLayout(this);
                cardV.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable cb = new GradientDrawable();
                cb.setColor(card);
                cb.setStroke(dp(id.equals(boardId) ? 2 : 1), id.equals(boardId) ? rust : line);
                cb.setCornerRadius(dp(16));
                cardV.setBackground(cb);
                cardV.setClipToOutline(true);
                android.widget.ImageView thumb = new android.widget.ImageView(this);
                thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                cardV.addView(thumb, new LinearLayout.LayoutParams(-1, cellH));
                TextView name = new TextView(this);
                name.setText(o.optString("title"));
                name.setTextColor(ink);
                name.setTextSize(16);
                name.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
                name.setPadding(dp(12), dp(8), dp(12), 0);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cardV.addView(name);
                TextView when = mono(10, ink3);
                when.setText(f.format(new java.util.Date(o.optLong("at"))).toUpperCase(java.util.Locale.GERMAN));
                when.setPadding(dp(12), dp(2), dp(12), dp(10));
                cardV.addView(when);
                cardV.setOnClickListener(v -> {
                    closeOverview();
                    open(id);
                });
                cardV.setOnLongClickListener(v -> {
                    closeOverview();
                    open(id);
                    deleteBoard();
                    return true;
                });
                android.widget.GridLayout.LayoutParams gl = new android.widget.GridLayout.LayoutParams();
                gl.width = cellW;
                gl.setMargins(0, 0, (k % cols) < cols - 1 ? dp(12) : 0, dp(12));
                grid.addView(cardV, gl);
                // Little pop-in, one after the other.
                cardV.setAlpha(0f);
                cardV.setScaleX(0.92f);
                cardV.setScaleY(0.92f);
                cardV.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(40L * k).setDuration(220).start();
                int w = Math.max(1, cellW), h = Math.max(1, cellH);
                thumb.post(() -> thumb.setImageBitmap(preview(id, w, h)));
            }
        });
    }

    /** A board drawn small, fitted into w × h on the board colour. */
    private Bitmap preview(String id, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(bg);
        List<Stroke> list = id.equals(boardId) ? new ArrayList<>(strokes) : readStrokes(boardFile(id));
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (Stroke st : list) {
            if (st.eraser) continue;
            if (st.image != null && st.n >= 4) {
                l = Math.min(l, st.pts[0]);
                t = Math.min(t, st.pts[1]);
                r = Math.max(r, st.pts[0] + st.pts[2]);
                b = Math.max(b, st.pts[1] + st.pts[3]);
                continue;
            }
            for (int k = 0; k + 1 < st.n; k += 3) {
                l = Math.min(l, st.pts[k]);
                r = Math.max(r, st.pts[k]);
                t = Math.min(t, st.pts[k + 1]);
                b = Math.max(b, st.pts[k + 1]);
            }
        }
        if (l > r) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(ink3);
            p.setTextSize(dp(13));
            p.setTypeface(android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("noch leer …", w / 2f, h / 2f, p);
            return out;
        }
        float pad = dp(14), sc = Math.min((w - 2 * pad) / Math.max(1, r - l), (h - 2 * pad) / Math.max(1, b - t));
        Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas lc = new Canvas(layer);
        lc.translate((w - (r - l) * sc) / 2, (h - (b - t) * sc) / 2);
        lc.scale(sc, sc);
        lc.translate(-l, -t);
        for (Stroke st : list) board.drawStroke(lc, st);
        c.drawBitmap(layer, 0, 0, null);
        return out;
    }

    private void closeOverview() {
        FrameLayout o = overview;
        overview = null;
        if (o == null) return;
        o.animate().alpha(0f).translationY(dp(30)).setDuration(200)
            .withEndAction(() -> ((FrameLayout) o.getParent()).removeView(o)).start();
    }

    @Override
    public void onBackPressed() {
        if (overview != null) closeOverview();
        else super.onBackPressed();
    }

    /** The inspiration sheet: hand-drawn flowers that sway, a sun, a spiral and nearly-right maths. */
    private final class Doodles extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final String[] maths = {"2 + 2 = 3 ?!", "e^iπ + 1 = 0", "∫ Kaffee dt = Code", "π ≈ 3", "1 + 1 = 10 (binär)",
            "a² + b² = c²", "∞ / 0 = 🤯", "x = Hausaufgaben"};

        Doodles() {
            super(BoardActivity.this);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), t = SystemClock.uptimeMillis() / 1000f, u = Math.min(w, h) / 100f;
            android.graphics.Typeface hand = android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL);
            p.setTypeface(hand);
            p.setStyle(Paint.Style.FILL);
            p.setColor(ink3);
            p.setTextSize(dp(11));
            c.drawText("INSPIRATION", dp(18), dp(28), p);
            // The maths, tilted a little each, down the sheet.
            p.setTextSize(Math.max(dp(15), u * 6));
            for (int i = 0; i < maths.length; i++) {
                float y = h * (0.12f + i * 0.105f);
                if (y > h * 0.62f) break;
                c.save();
                c.rotate(i % 2 == 0 ? -4 : 3, dp(18), y);
                p.setColor(colours[1 + i % (colours.length - 1)]);
                c.drawText(maths[i], dp(18) + (i % 3) * dp(10), y, p);
                c.restore();
            }
            // A sun top right.
            float sx = w - dp(46), sy = dp(56);
            p.setColor(0xFFFFC400);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            c.drawCircle(sx, sy, dp(16), p);
            for (int k = 0; k < 10; k++) {
                double a = k * Math.PI / 5 + t * 0.3;
                c.drawLine(sx + (float) Math.cos(a) * dp(22), sy + (float) Math.sin(a) * dp(22),
                    sx + (float) Math.cos(a) * dp(30), sy + (float) Math.sin(a) * dp(30), p);
            }
            // Flowers along the bottom, swaying.
            int n = Math.max(3, (int) (w / dp(70)));
            for (int i = 0; i < n; i++) {
                float fx = w * (i + 0.5f) / n, ground = h - dp(18), stem = h * (0.18f + 0.06f * ((i * 7) % 3));
                float sway = (float) Math.sin(t * 1.3 + i) * dp(8);
                p.setColor(0xFF2BBF6A);
                p.setStrokeWidth(dp(2.2f));
                path.reset();
                path.moveTo(fx, ground);
                path.quadTo(fx + sway * 0.3f, ground - stem / 2, fx + sway, ground - stem);
                c.drawPath(path, p);
                // A leaf.
                path.reset();
                path.moveTo(fx + sway * 0.15f, ground - stem * 0.35f);
                path.quadTo(fx + dp(16), ground - stem * 0.45f, fx + dp(14) + sway * 0.2f, ground - stem * 0.6f);
                c.drawPath(path, p);
                // Petals.
                float hx = fx + sway, hy = ground - stem;
                int col = colours[1 + (i * 3) % (colours.length - 1)];
                p.setStyle(Paint.Style.STROKE);
                p.setColor(col);
                for (int k = 0; k < 6; k++) {
                    double a = k * Math.PI / 3 + t * 0.4 * (i % 2 == 0 ? 1 : -1);
                    c.drawCircle(hx + (float) Math.cos(a) * dp(9), hy + (float) Math.sin(a) * dp(9), dp(6), p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFC400);
                c.drawCircle(hx, hy, dp(4.5f), p);
                p.setStyle(Paint.Style.STROKE);
            }
            // A spiral in the middle right.
            p.setColor(colours[5]);
            p.setStrokeWidth(dp(2));
            path.reset();
            float cx = w * 0.72f, cy = h * 0.66f;
            for (int k = 0; k < 120; k++) {
                double a = k * 0.18 + t * 0.6, rr = k * dp(0.28f);
                float px = cx + (float) (Math.cos(a) * rr), py = cy + (float) (Math.sin(a) * rr);
                if (k == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            c.drawPath(path, p);
            postInvalidateDelayed(33);
        }
    }

    private void deleteBoard() {
        new AlertDialog.Builder(this)
            .setTitle("„" + title.getText() + "“ löschen?")
            .setPositiveButton("Löschen", (d, w) -> {
                boardFile(boardId).delete();
                JSONArray a = listBoards(), out = new JSONArray();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && !boardId.equals(o.optString("id"))) out.put(o);
                }
                prefs.edit().putString("boards", out.toString()).apply();
                boardId = null;
                if (out.length() > 0) open(out.optJSONObject(0).optString("id"));
                else newBoard();
            })
            .setNegativeButton("Abbrechen", null)
            .show();
    }

    /** The board as a PNG (strokes only, on the board's colour), via the share sheet. */
    private void share() {
        save();
        float[] b = board.bounds();
        if (b == null) {
            Toast.makeText(this, "Noch leer", Toast.LENGTH_SHORT).show();
            return;
        }
        float pad = 60, w = b[2] - b[0] + 2 * pad, h = b[3] - b[1] + 2 * pad;
        float scale = Math.min(1f, 3000f / Math.max(w, h));
        int bw = Math.max(1, (int) (w * scale)), bh = Math.max(1, (int) (h * scale));
        // Strokes on their own layer (the eraser clears), then onto the board colour.
        Bitmap layer = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas lc = new Canvas(layer);
        lc.scale(scale, scale);
        lc.translate(pad - b[0], pad - b[1]);
        board.drawStrokes(lc);
        if (prefs.getBoolean("clouds", true)) board.drawClouds(lc, 1f);
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(bg);
        c.drawBitmap(layer, 0, 0, null);
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Teilen braucht Android 10 oder neuer", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            // Saved to Pictures/ARC-DNA (no permission needed), then shared from there.
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, title.getText() + " " + System.currentTimeMillis() + ".png");
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ARC-DNA");
            Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("kein Speicherplatz");
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Intent send = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, title.getText().toString()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, title.getText()));
        } catch (Exception e) {
            Toast.makeText(this, "Teilen ging nicht: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /* ---------- UI bits ---------- */

    private void paintTools() {
        for (View d : colourDots) {
            int c = (Integer) d.getTag();
            boolean on = !eraser && c == colour;
            // Selected: an outer ring with a gap, drawn inside the dot's own bounds so nothing is cut off.
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(c);
            fill.setStroke(dp(1), line);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(0);
            ring.setStroke(dp(on ? 2.5f : 0), c == ink ? rust : ink);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{ring, fill});
            int in = on ? dp(5) : dp(3);
            ld.setLayerInset(1, in, in, in, in);
            ld.setLayerInset(0, dp(1), dp(1), dp(1), dp(1));
            d.setBackground(ld);
        }
        for (View v : widthChips) styleChip((TextView) v, (Float) v.getTag() == width);
        styleChip(eraserChip, eraser);
    }

    private void styleChip(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(on ? rust : card);
        g.setStroke(dp(1), on ? rust : line);
        t.setBackground(g);
        t.setTextColor(on ? 0xFFFFFFFF : ink);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(26));
        lp.setMargins(dp(8), 0, dp(8), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView chipView(String s) {
        TextView t = mono(12, ink);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
        t.setMinWidth(dp(narrow ? 34 : 40));
        t.setPadding(dp(narrow ? 8 : 12), dp(8), dp(narrow ? 8 : 12), dp(8));
        styleChip(t, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView chip(String s, View.OnClickListener l) {
        TextView t = chipView(s);
        t.setTextColor(rust);
        t.setOnClickListener(l);
        return t;
    }

    private TextView mono(int sp, int col) {
        TextView t = new TextView(this);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(sp);
        t.setTextColor(col);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /* ---------- the board ---------- */

    private final class Board extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Matrix view = new Matrix(), inverse = new Matrix();
        private float tx, ty, zoom = 1;
        private Bitmap cache;
        private Canvas cacheCanvas;
        private Stroke current;
        private int drawId = -1;
        private boolean panning;
        private float lastMx, lastMy, lastDist;
        private final float[] tmp = new float[2];

        Board() {
            super(BoardActivity.this);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        void reset() {
            tx = ty = 0;
            zoom = 1;
            update();
        }

        private void update() {
            view.reset();
            view.postScale(zoom, zoom);
            view.postTranslate(tx, ty);
            view.invert(inverse);
            rebuild();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w > 0 && h > 0) {
                cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                cacheCanvas = new Canvas(cache);
                rebuild();
            }
        }

        /** Redraw all finished strokes into the cache (after undo, pan, zoom, load). */
        void rebuild() {
            if (cacheCanvas == null) return;
            cache.eraseColor(0);
            cacheCanvas.save();
            cacheCanvas.concat(view);
            drawStrokes(cacheCanvas);
            cacheCanvas.restore();
            invalidate();
        }

        void drawStrokes(Canvas c) {
            for (Stroke s : strokes) drawStroke(c, s);
        }

        /** Short segments so pressure can change the width along the line. */
        void drawStroke(Canvas c, Stroke s) {
            if (s.image != null) {
                Bitmap b = picture(s.image);
                if (b != null && s.n >= 4) {
                    p.setXfermode(null);
                    p.setFilterBitmap(true);
                    c.drawBitmap(b, null, new android.graphics.RectF(s.pts[0], s.pts[1], s.pts[0] + s.pts[2], s.pts[1] + s.pts[3]), p);
                }
                return;
            }
            p.setStyle(Paint.Style.STROKE);
            if (s.eraser) {
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                p.setColor(0xFF000000);
            } else {
                p.setXfermode(null);
                p.setColor(s.colour);
            }
            if (s.n == 3) {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(s.pts[0], s.pts[1], s.width * (0.5f + s.pts[2]) / 2, p);
                p.setStyle(Paint.Style.STROKE);
            }
            for (int k = 3; k + 2 < s.n; k += 3) {
                p.setStrokeWidth(s.width * (s.eraser ? 3 : 0.5f + s.pts[k + 2]));
                c.drawLine(s.pts[k - 3], s.pts[k - 2], s.pts[k], s.pts[k + 1], p);
            }
            p.setXfermode(null);
        }

        /*
         * Clouds: what you wrote close together is one exercise; each gets a hand-drawn cloud round
         * it and a little handwritten "Aufgabe n" (Android's casual font). All on the phone,
         * recomputed only when the strokes change.
         */
        private int cloudsFor = -1;
        private final List<float[]> clusters = new ArrayList<>();

        private void cluster() {
            int sig = strokes.size() * 31 + (strokes.isEmpty() ? 0 : System.identityHashCode(strokes.get(strokes.size() - 1)));
            if (sig == cloudsFor) return;
            cloudsFor = sig;
            clusters.clear();
            float gap = dp(56);
            List<float[]> boxes = new ArrayList<>();          // l, t, r, b, count
            for (Stroke st : strokes) {
                if (st.eraser || st.n < 3) continue;
                float l, t, r, b;
                if (st.image != null) {
                    l = st.pts[0];
                    t = st.pts[1];
                    r = l + st.pts[2];
                    b = t + st.pts[3];
                } else {
                    l = t = Float.MAX_VALUE;
                    r = b = -Float.MAX_VALUE;
                    for (int k = 0; k + 1 < st.n; k += 3) {
                        l = Math.min(l, st.pts[k]);
                        r = Math.max(r, st.pts[k]);
                        t = Math.min(t, st.pts[k + 1]);
                        b = Math.max(b, st.pts[k + 1]);
                    }
                }
                boxes.add(new float[]{l, t, r, b, 1});
            }
            // Merge boxes that come within the gap of each other until nothing changes.
            boolean merged = true;
            while (merged && boxes.size() > 1) {
                merged = false;
                for (int i = 0; i < boxes.size() && !merged; i++) {
                    for (int j = i + 1; j < boxes.size(); j++) {
                        float[] a = boxes.get(i), b = boxes.get(j);
                        if (a[0] - gap < b[2] && b[0] - gap < a[2] && a[1] - gap < b[3] && b[1] - gap < a[3]) {
                            a[0] = Math.min(a[0], b[0]);
                            a[1] = Math.min(a[1], b[1]);
                            a[2] = Math.max(a[2], b[2]);
                            a[3] = Math.max(a[3], b[3]);
                            a[4] += b[4];
                            boxes.remove(j);
                            merged = true;
                            break;
                        }
                    }
                }
            }
            for (float[] b : boxes) if (b[4] >= 3) clusters.add(b);       // a lone dot isn't an exercise
            // Reading order: top to bottom, then left to right.
            clusters.sort((a, b) -> Math.abs(a[1] - b[1]) > dp(80) ? Float.compare(a[1], b[1]) : Float.compare(a[0], b[0]));
        }

        void drawClouds(Canvas c, float z) {
            cluster();
            Path cloud = new Path();
            android.graphics.Typeface hand = android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL);
            int[] tints = {0xFF3C8DFF, 0xFFA77BFF, 0xFF2BBF6A, 0xFFF29A1F, rust};
            for (int i = 0; i < clusters.size(); i++) {
                float[] b = clusters.get(i);
                float pad = dp(22), l = b[0] - pad, t = b[1] - pad, r = b[2] + pad, bt = b[3] + pad;
                Random rnd = new Random((long) (b[0] * 7 + b[1] * 13));      // the same wobble every frame
                // Puffs along the rectangle's outline, each bulging outwards.
                float bump = dp(26);
                List<float[]> pts = new ArrayList<>();
                int nx = Math.max(2, (int) ((r - l) / bump)), ny = Math.max(2, (int) ((bt - t) / bump));
                for (int k = 0; k < nx; k++) pts.add(new float[]{l + (r - l) * k / nx, t});
                for (int k = 0; k < ny; k++) pts.add(new float[]{r, t + (bt - t) * k / ny});
                for (int k = 0; k < nx; k++) pts.add(new float[]{r - (r - l) * k / nx, bt});
                for (int k = 0; k < ny; k++) pts.add(new float[]{l, bt - (bt - t) * k / ny});
                float cx = (l + r) / 2, cy = (t + bt) / 2;
                cloud.reset();
                cloud.moveTo(pts.get(0)[0], pts.get(0)[1]);
                for (int k = 0; k < pts.size(); k++) {
                    float[] p0 = pts.get(k), p1 = pts.get((k + 1) % pts.size());
                    float mx = (p0[0] + p1[0]) / 2, my = (p0[1] + p1[1]) / 2;
                    float dx = mx - cx, dy = my - cy, len = (float) Math.hypot(dx, dy);
                    float out = bump * (0.55f + 0.35f * rnd.nextFloat());
                    cloud.quadTo(mx + dx / len * out, my + dy / len * out, p1[0], p1[1]);
                }
                cloud.close();
                int col = tints[i % tints.length];
                p.setXfermode(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor((col & 0x00FFFFFF) | 0x10000000);
                c.drawPath(cloud, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2.2f) / Math.max(0.5f, z));
                p.setColor((col & 0x00FFFFFF) | 0xB0000000);
                c.drawPath(cloud, p);
                // The handwritten tag, a little tilted, on the cloud's top left.
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(hand);
                p.setTextSize(dp(17));
                p.setColor((col & 0x00FFFFFF) | 0xE6000000);
                c.save();
                c.rotate(-5, l, t - bump * 0.9f);
                c.drawText("Aufgabe " + (i + 1), l + dp(4), t - bump * 0.9f, p);
                c.restore();
                p.setTypeface(null);
            }
        }

        float[] bounds() {
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            for (Stroke s : strokes) {
                if (s.eraser) continue;
                if (s.image != null && s.n >= 4) {
                    l = Math.min(l, s.pts[0]);
                    t = Math.min(t, s.pts[1]);
                    r = Math.max(r, s.pts[0] + s.pts[2]);
                    b = Math.max(b, s.pts[1] + s.pts[3]);
                    continue;
                }
                for (int k = 0; k + 1 < s.n; k += 3) {
                    l = Math.min(l, s.pts[k]);
                    r = Math.max(r, s.pts[k]);
                    t = Math.min(t, s.pts[k + 1]);
                    b = Math.max(b, s.pts[k + 1]);
                }
            }
            return l > r ? null : new float[]{l, t, r, b};
        }

        @Override
        protected void onDraw(Canvas c) {
            // Dot grid that moves and scales with the board.
            float cell = dp(32) * zoom;
            while (cell < dp(16)) cell *= 2;
            float ox = ((tx % cell) + cell) % cell, oy = ((ty % cell) + cell) % cell;
            p.setXfermode(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(line);
            for (float x = ox; x < getWidth(); x += cell) {
                for (float y = oy; y < getHeight(); y += cell) c.drawCircle(x, y, dp(1.2f), p);
            }
            if (cache != null) c.drawBitmap(cache, 0, 0, null);
            if (current == null && prefs.getBoolean("clouds", true)) {
                c.save();
                c.concat(view);
                drawClouds(c, zoom);
                c.restore();
            }
            if (placing != null && placing.n >= 4) {
                // The picture being placed: dashed frame round it.
                c.save();
                c.concat(view);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2) / zoom);
                p.setColor(rust);
                p.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(8) / zoom, dp(6) / zoom}, 0));
                c.drawRect(placing.pts[0], placing.pts[1], placing.pts[0] + placing.pts[2], placing.pts[1] + placing.pts[3], p);
                p.setPathEffect(null);
                c.restore();
            }
            if (current != null) {
                c.save();
                c.concat(view);
                if (current.eraser) {
                    // Show where the eraser is going.
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(1) / zoom);
                    p.setColor(ink3);
                    if (current.n >= 3) c.drawCircle(current.pts[current.n - 3], current.pts[current.n - 2], current.width * 1.5f, p);
                } else {
                    drawStroke(c, current);
                }
                c.restore();
            }
        }

        private float placeX, placeY, placeDist;
        private boolean placeMoved;

        /** While a picture is being placed: drag = move, two fingers = size, a tap beside it = done. */
        private boolean placeTouch(MotionEvent e) {
            int a = e.getActionMasked();
            Stroke s = placing;
            if (e.getPointerCount() >= 2) {
                float dist = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                if (a == MotionEvent.ACTION_POINTER_DOWN || placeDist <= 0) placeDist = dist;
                else if (a == MotionEvent.ACTION_MOVE) {
                    float f = Math.max(0.2f, Math.min(5f, dist / placeDist));
                    float cx = s.pts[0] + s.pts[2] / 2, cy = s.pts[1] + s.pts[3] / 2;
                    float nw = Math.max(dp(40) / zoom, s.pts[2] * f), nh = nw * s.pts[3] / s.pts[2];
                    s.pts[0] = cx - nw / 2;
                    s.pts[1] = cy - nh / 2;
                    s.pts[2] = nw;
                    s.pts[3] = nh;
                    placeDist = dist;
                    placeMoved = true;
                    rebuild();
                }
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN) {
                placeX = e.getX();
                placeY = e.getY();
                placeDist = 0;
                placeMoved = false;
            } else if (a == MotionEvent.ACTION_MOVE) {
                float dx = (e.getX() - placeX) / zoom, dy = (e.getY() - placeY) / zoom;
                if (Math.abs(dx) + Math.abs(dy) > 0) {
                    if (Math.hypot(e.getX() - placeX, e.getY() - placeY) > dp(4)) placeMoved = true;
                    if (placeMoved) {
                        s.pts[0] += dx;
                        s.pts[1] += dy;
                        placeX = e.getX();
                        placeY = e.getY();
                        rebuild();
                    }
                }
            } else if (a == MotionEvent.ACTION_UP && !placeMoved) {
                tmp[0] = e.getX();
                tmp[1] = e.getY();
                inverse.mapPoints(tmp);
                boolean inside = tmp[0] >= s.pts[0] && tmp[0] <= s.pts[0] + s.pts[2] && tmp[1] >= s.pts[1] && tmp[1] <= s.pts[1] + s.pts[3];
                if (!inside) finishPlacing();
            }
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (placing != null) return placeTouch(e);
            if (e.getPointerCount() >= 2) {
                // Two fingers: move and zoom; a line just started is dropped.
                if (current != null && !panning) {
                    current = null;
                    rebuild();
                }
                panning = true;
                float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                float dist = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                if (a == MotionEvent.ACTION_POINTER_DOWN) {
                    lastMx = mx;
                    lastMy = my;
                    lastDist = dist;
                } else if (a == MotionEvent.ACTION_MOVE && lastDist > 0) {
                    float f = Math.max(0.2f, Math.min(6f, zoom * dist / lastDist)) / zoom;
                    tx = mx - (lastMx - tx) * f;
                    ty = my - (lastMy - ty) * f;
                    zoom *= f;
                    lastMx = mx;
                    lastMy = my;
                    lastDist = dist;
                    update();
                }
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN) {
                panning = false;
                current = new Stroke();
                current.colour = colour;
                current.eraser = eraser;
                current.width = dp(width) / zoom;
                drawId = e.getPointerId(0);
                addPoint(e.getX(), e.getY(), e);
                invalidate();
                return true;
            }
            if (panning) {
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) panning = false;
                return true;
            }
            if (a == MotionEvent.ACTION_MOVE && current != null) {
                for (int h = 0; h < e.getHistorySize(); h++) addPoint(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h), e);
                addPoint(e.getX(), e.getY(), e);
                if (current.eraser) {
                    // The eraser works live on the cache.
                    cacheCanvas.save();
                    cacheCanvas.concat(view);
                    drawStroke(cacheCanvas, current);
                    cacheCanvas.restore();
                }
                invalidate();
            } else if ((a == MotionEvent.ACTION_UP) && current != null) {
                strokes.add(current);
                redo.clear();
                cacheCanvas.save();
                cacheCanvas.concat(view);
                drawStroke(cacheCanvas, current);
                cacheCanvas.restore();
                current = null;
                invalidate();
                save();
            } else if (a == MotionEvent.ACTION_CANCEL) {
                current = null;
                invalidate();
            }
            return true;
        }

        private void addPoint(float x, float y, MotionEvent e) {
            addPoint(x, y, e.getPressure(), e);
        }

        private void addPoint(float x, float y, float pressure, MotionEvent e) {
            tmp[0] = x;
            tmp[1] = y;
            inverse.mapPoints(tmp);
            boolean stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS;
            float pr = stylus ? Math.max(0.05f, Math.min(1f, pressure)) : 0.5f;   // fingers: even line
            current.add(tmp[0], tmp[1], pr);
        }
    }

    /* ---------- pictures ---------- */

    private static final int PICK = 41;
    /** The picture just inserted: it can be moved and sized until a tap beside it. */
    private Stroke placing;
    private TextView placeHint;
    private final java.util.Map<String, Bitmap> pictures = new java.util.HashMap<>();

    private void pickImage() {
        Intent i = Build.VERSION.SDK_INT >= 33 ? new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
            : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, PICK);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Keine Bilder-Auswahl gefunden", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) insertImage(data.getData());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeShared(intent);
    }

    /** A picture shared to the whiteboard (Teilen → Dr. Overloaded). */
    private void takeShared(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        i.setAction(Intent.ACTION_MAIN);          // only once (not again after a rotation)
        if (u != null) board.post(() -> insertImage(u));
    }

    private File pictureDir() {
        File d = new File(getFilesDir(), "board_img");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private Bitmap picture(String name) {
        Bitmap b = pictures.get(name);
        if (b == null) {
            b = android.graphics.BitmapFactory.decodeFile(new File(pictureDir(), name).getPath());
            if (b != null) pictures.put(name, b);
        }
        return b;
    }

    /** Copies the picture into the app (at most 2048 px), puts it in the middle of the view, ready to place. */
    private void insertImage(Uri uri) {
        if (boardId == null) return;
        if (placing != null) finishPlacing();
        new Thread(() -> {
            Bitmap b = null;
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    b = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(getContentResolver(), uri),
                        (dec, info, src) -> {
                            int w = info.getSize().getWidth(), h = info.getSize().getHeight(), sample = 1;
                            while (Math.max(w, h) / sample > 2048) sample *= 2;
                            dec.setTargetSampleSize(sample);
                            dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
                } else {
                    //noinspection deprecation
                    b = android.provider.MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                }
            } catch (Exception | OutOfMemoryError ignored) {
            }
            if (b == null) {
                runOnUiThread(() -> Toast.makeText(this, "Bild ließ sich nicht öffnen", Toast.LENGTH_SHORT).show());
                return;
            }
            String name = Long.toString(System.currentTimeMillis(), 36) + ".png";
            try (FileOutputStream out = new FileOutputStream(new File(pictureDir(), name))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Bild ließ sich nicht speichern", Toast.LENGTH_SHORT).show());
                return;
            }
            Bitmap pic = b;
            runOnUiThread(() -> {
                pictures.put(name, pic);
                Stroke s = new Stroke();
                s.image = name;
                s.width = -1;
                // 60 % of the visible width, in the middle of what you see.
                float vw = board.getWidth() / board.zoom, vh = board.getHeight() / board.zoom;
                float w = vw * 0.6f, h = w * pic.getHeight() / pic.getWidth();
                if (h > vh * 0.6f) {
                    h = vh * 0.6f;
                    w = h * pic.getWidth() / pic.getHeight();
                }
                float cx = (board.getWidth() / 2f - board.tx) / board.zoom, cy = (board.getHeight() / 2f - board.ty) / board.zoom;
                s.pts = new float[]{cx - w / 2, cy - h / 2, w, h};
                s.n = 4;
                strokes.add(s);
                redo.clear();
                placing = s;
                board.rebuild();
                showPlaceHint();
                save();
            });
        }, "arc-board-img").start();
    }

    private void showPlaceHint() {
        if (placeHint == null) {
            placeHint = mono(11, 0xFFFFFFFF);
            placeHint.setText("BILD: ZIEHEN · 2 FINGER = GRÖSSE · DANEBEN TIPPEN = FERTIG");
            placeHint.setPadding(dp(14), dp(8), dp(14), dp(8));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xE6121211);
            g.setStroke(dp(1.5f), rust);
            g.setCornerRadius(dp(18));
            placeHint.setBackground(g);
            placeHint.setOnClickListener(v -> finishPlacing());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            lp.topMargin = dp(84);
            ((FrameLayout) board.getParent()).addView(placeHint, lp);
        }
        placeHint.setVisibility(View.VISIBLE);
    }

    private void finishPlacing() {
        placing = null;
        if (placeHint != null) placeHint.setVisibility(View.GONE);
        board.rebuild();
        save();
    }


    private TextView cloudChip;

    private String cloudLabel() {
        return prefs.getBoolean("clouds", true) ? "☁ WOLKEN" : "☁ AUS";
    }


    /* ---------- the fox (only when you allow mess) ---------- */

    private Mischief mischief;
    private TextView buddyChip;

    private String buddyLabel() {
        if (mischief != null && mischief.foxHere()) return "🚓 ROBO-POLIZEI";
        return prefs.getBoolean("buddy", false) ? "🦊 CHAOS AN" : "🦊 AUS";
    }

    /*
     * Every half minute or so the fox trots in, goes to something you wrote, rubs it out with its
     * tail and laughs. ↶ won't help – but ↷ (redo) brings it back, and so does the police: tap
     * 🚓 ROBO-POLIZEI and the robot rolls in with blue lights, rubs the fox out with its eraser
     * beam and hands back what was stolen. Touches go through to the board.
     */
    private final class Mischief extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private android.graphics.drawable.Drawable foxArt;
        private static final int GONE_ = 0, IN = 1, ERASE = 2, LAUGH = 3, OUT = 4, POLICE = 5;
        private int state = GONE_;
        private long stateAt, nextAt;
        private float fx, fy, tx, ty, sx, sy;
        private Stroke victim;
        private final List<Stroke> stolen = new ArrayList<>();
        private float robotX;

        Mischief() {
            super(BoardActivity.this);
            setClickable(false);
            foxArt = getDrawable(R.drawable.fox);
            planNext();
        }

        boolean foxHere() {
            return state != GONE_ && state != POLICE;
        }

        void planNext() {
            nextAt = SystemClock.uptimeMillis() + 20_000 + rnd.nextInt(25_000);
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;
        }

        private void set(int s) {
            state = s;
            stateAt = SystemClock.uptimeMillis();
            if (buddyChip != null) buddyChip.setText(buddyLabel());
        }

        void callPolice() {
            if (!foxHere()) return;
            set(POLICE);
            robotX = getWidth() + dp(80);
        }

        /** Picks a recent line on screen and heads for it. */
        private boolean start() {
            List<Stroke> onScreen = new ArrayList<>();
            for (int i = strokes.size() - 1; i >= 0 && onScreen.size() < 12; i--) {
                Stroke st = strokes.get(i);
                if (st.eraser || st.image != null || st.n < 3 || st == placing) continue;
                float[] pt = {st.pts[0], st.pts[1]};
                board.view.mapPoints(pt);
                if (pt[0] > dp(40) && pt[0] < getWidth() - dp(40) && pt[1] > dp(120) && pt[1] < getHeight() - dp(120)) onScreen.add(st);
            }
            if (onScreen.isEmpty()) return false;
            victim = onScreen.get(rnd.nextInt(onScreen.size()));
            float[] pt = {victim.pts[0], victim.pts[1]};
            board.view.mapPoints(pt);
            tx = pt[0];
            ty = pt[1];
            boolean fromLeft = rnd.nextBoolean();
            sx = fx = fromLeft ? -dp(90) : getWidth() + dp(90);
            sy = fy = ty + (rnd.nextFloat() - 0.5f) * dp(120);
            set(IN);
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            boolean allowed = prefs.getBoolean("buddy", false);
            if (state == GONE_) {
                if (allowed && now > nextAt && overview == null && placing == null && !start()) planNext();
                if (state == GONE_) {
                    postInvalidateDelayed(1000);
                    return;
                }
            }
            if (!allowed && state != POLICE && state != OUT) set(OUT);
            float t = (now - stateAt) / 1000f;
            switch (state) {
                case IN: {
                    float k = Math.min(1, t / 1.6f);
                    k = k * k * (3 - 2 * k);
                    fx = sx + (tx - dp(30) - sx) * k;
                    fy = sy + (ty - dp(20) - sy) * k;
                    if (k >= 1) set(ERASE);
                    break;
                }
                case ERASE:
                    // Tail wags over the line, then it's gone (kept for the police / redo).
                    fx = tx - dp(30) + (float) Math.sin(t * 18) * dp(10);
                    if (t > 1.2f) {
                        if (strokes.remove(victim)) {
                            stolen.add(victim);
                            redo.add(victim);
                            board.rebuild();
                            save();
                        }
                        set(LAUGH);
                    }
                    break;
                case LAUGH:
                    fy = ty - dp(20) - Math.abs((float) Math.sin(t * 9)) * dp(10);
                    if (t > 2.4f) {
                        sx = fx;
                        sy = fy;
                        set(OUT);
                    }
                    break;
                case OUT: {
                    float k = Math.min(1, t / 1.4f);
                    fx = sx + ((sx < getWidth() / 2f ? -dp(120) : getWidth() + dp(120)) - sx) * k * k;
                    if (k >= 1) {
                        state = GONE_;
                        if (buddyChip != null) buddyChip.setText(buddyLabel());
                        planNext();
                    }
                    break;
                }
                case POLICE: {
                    // The robot rolls in, flashing; then its eraser beam rubs the fox out.
                    float target = fx + dp(110);
                    robotX += (target - robotX) * Math.min(1, 0.08f);
                    boolean beam = t > 1.4f && t < 3.0f;
                    drawRobot(c, robotX, fy, now, beam);
                    if (t > 3.0f) {
                        // Evidence returned.
                        for (Stroke st : stolen) {
                            redo.remove(st);
                            strokes.add(st);
                        }
                        if (!stolen.isEmpty()) {
                            board.rebuild();
                            save();
                        }
                        stolen.clear();
                    }
                    if (t > 4.2f) {
                        state = GONE_;
                        if (buddyChip != null) buddyChip.setText(buddyLabel());
                        nextAt = now + 120_000;          // two minutes of peace
                    }
                    break;
                }
                default:
                    break;
            }
            // The fox (rubbed out from the top by the beam).
            float fw = dp(96), fh = dp(64);
            float erased = state == POLICE ? Math.max(0, Math.min(1, (t - 1.4f) / 1.5f)) : 0;
            if (erased < 1) {
                c.save();
                c.clipRect(fx - fw / 2, fy - fh / 2 + fh * erased, fx + fw / 2, fy + fh / 2);
                boolean left = state == OUT ? sx < getWidth() / 2f : tx < fx;
                if (left) c.scale(-1, 1, fx, fy);
                foxArt.setBounds((int) (fx - fw / 2), (int) (fy - fh / 2), (int) (fx + fw / 2), (int) (fy + fh / 2));
                foxArt.draw(c);
                c.restore();
            }
            if (state == LAUGH) bubble(c, "HAHAHA! 😂", fx, fy - fh / 2 - dp(8));
            if (state == ERASE) bubble(c, "Was ist das denn?", fx, fy - fh / 2 - dp(8));
            if (state == POLICE && t > 3.0f) bubble(c, "Festgenommen. Beweis zurück. 🤖", robotX, fy - dp(54));
            postInvalidateOnAnimation();
        }

        private void drawRobot(Canvas c, float x, float y, long now, boolean beam) {
            boolean blue = (now / 180) % 2 == 0;
            p.setStyle(Paint.Style.FILL);
            p.setColor(blue ? 0xFF3C8DFF : 0xFFE8231A);
            c.drawCircle(x, y - dp(40), dp(6), p);
            p.setColor(blue ? 0x553C8DFF : 0x55E8231A);
            c.drawCircle(x, y - dp(40), dp(16), p);
            p.setColor(0xFF1A1A1A);
            c.drawRoundRect(x - dp(22), y - dp(32), x + dp(22), y + dp(6), dp(8), dp(8), p);
            p.setColor(0xFF2BD9FF);
            c.drawCircle(x - dp(9), y - dp(14), dp(4), p);
            c.drawCircle(x + dp(9), y - dp(14), dp(4), p);
            p.setColor(0xFF1A1A1A);
            c.drawRect(x - dp(14), y + dp(6), x + dp(14), y + dp(16), p);
            c.drawCircle(x - dp(12), y + dp(20), dp(6), p);
            c.drawCircle(x + dp(12), y + dp(20), dp(6), p);
            if (beam) {
                p.setColor(0xAAF4F2EC);
                p.setStrokeWidth(dp(10));
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(x - dp(24), y - dp(10), fx + dp(20), fy, p);
                p.setStyle(Paint.Style.FILL);
            }
        }

        private void bubble(Canvas c, String text, float x, float y) {
            p.setTypeface(android.graphics.Typeface.create("casual", android.graphics.Typeface.BOLD));
            p.setTextSize(dp(15));
            float w = p.measureText(text) + dp(20);
            x = Math.max(w / 2 + dp(6), Math.min(getWidth() - w / 2 - dp(6), x));
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xF2FFFFFF);
            c.drawRoundRect(x - w / 2, y - dp(30), x + w / 2, y, dp(12), dp(12), p);
            p.setColor(0xFF1A1A1A);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(text, x, y - dp(10), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }


    /* ---------- drawing guide for kids ---------- */

    /*
     * Step by step: the next stroke to draw shows as a dashed, pulsing rust shape in the middle of
     * the screen; you trace it with your finger or pen on the board, then "Weiter". Earlier steps
     * stay faint as a guide. Shapes in −1 … 1 around the screen's middle.
     * c x y r = circle · o l t r b = oval · l x1 y1 x2 y2 = line · p x y x y … = polyline ·
     * a l t r b start sweep = arc
     */
    private static final String[] MOTIFS = {"🐱 Katze", "🏠 Haus", "☀ Sonne", "🐟 Fisch", "🌷 Blume", "🚗 Auto"};
    private static final String[][][] STEPS = {
        {{"Ein grosser Kreis für den Kopf", "c 0 0.05 0.55"},
            {"Zwei spitze Ohren oben", "p -0.5 -0.2 -0.42 -0.75 -0.15 -0.45", "p 0.15 -0.45 0.42 -0.75 0.5 -0.2"},
            {"Zwei Augen", "o -0.3 -0.12 -0.12 0.08", "o 0.12 -0.12 0.3 0.08"},
            {"Eine kleine Nase und ein Mund wie ein W", "p -0.06 0.15 0.06 0.15 0 0.23 -0.06 0.15", "p -0.18 0.3 -0.09 0.36 0 0.25 0.09 0.36 0.18 0.3"},
            {"Schnurrhaare – drei auf jeder Seite", "l -0.25 0.22 -0.75 0.12", "l -0.25 0.27 -0.75 0.28", "l -0.25 0.32 -0.72 0.45",
                "l 0.25 0.22 0.75 0.12", "l 0.25 0.27 0.75 0.28", "l 0.25 0.32 0.72 0.45"}},
        {{"Ein Viereck als Haus", "p -0.55 -0.05 0.55 -0.05 0.55 0.7 -0.55 0.7 -0.55 -0.05"},
            {"Ein Dreieck als Dach", "p -0.7 -0.05 0 -0.7 0.7 -0.05"},
            {"Eine Tür in der Mitte", "p -0.13 0.7 -0.13 0.3 0.13 0.3 0.13 0.7"},
            {"Zwei Fenster", "p -0.42 0.1 -0.2 0.1 -0.2 0.3 -0.42 0.3 -0.42 0.1", "p 0.2 0.1 0.42 0.1 0.42 0.3 0.2 0.3 0.2 0.1"},
            {"Ein Kamin mit Rauch", "p 0.3 -0.35 0.3 -0.6 0.45 -0.6 0.45 -0.2", "a 0.32 -0.85 0.5 -0.68 180 270"}},
        {{"Ein Kreis in der Mitte", "c 0 0 0.35"},
            {"Strahlen rundherum", "l 0 -0.45 0 -0.75", "l 0 0.45 0 0.75", "l -0.45 0 -0.75 0", "l 0.45 0 0.75 0",
                "l 0.32 -0.32 0.53 -0.53", "l -0.32 -0.32 -0.53 -0.53", "l 0.32 0.32 0.53 0.53", "l -0.32 0.32 -0.53 0.53"},
            {"Augen und ein Lächeln", "c -0.12 -0.08 0.04", "c 0.12 -0.08 0.04", "a -0.18 -0.1 0.18 0.2 20 140"}},
        {{"Ein langes Oval als Körper", "o -0.6 -0.32 0.4 0.32"},
            {"Ein Dreieck als Schwanz", "p 0.38 0 0.75 -0.32 0.75 0.32 0.38 0"},
            {"Ein Auge", "c -0.35 -0.08 0.06"},
            {"Ein Mund und Schuppen", "a -0.62 -0.02 -0.48 0.12 270 120", "a -0.2 -0.2 0 0.2 300 120", "a 0 -0.2 0.2 0.2 300 120"},
            {"Blasen nach oben", "c -0.75 -0.45 0.05", "c -0.85 -0.62 0.07", "c -0.72 -0.8 0.09"}},
        {{"Ein Stiel von unten nach oben", "l 0 0.8 0 -0.1"},
            {"Zwei Blätter am Stiel", "o -0.32 0.32 0 0.48", "o 0 0.12 0.32 0.28"},
            {"Ein Kreis als Blütenmitte", "c 0 -0.3 0.13"},
            {"Fünf Blütenblätter rundherum", "c 0 -0.55 0.13", "c 0.24 -0.38 0.13", "c 0.15 -0.08 0.13", "c -0.15 -0.08 0.13", "c -0.24 -0.38 0.13"}},
        {{"Der Körper: ein langes Rechteck", "p -0.75 0 0.75 0 0.75 0.35 -0.75 0.35 -0.75 0"},
            {"Das Dach oben drauf", "p -0.45 0 -0.3 -0.35 0.3 -0.35 0.5 0"},
            {"Zwei Fenster", "p -0.35 -0.03 -0.25 -0.28 -0.03 -0.28 -0.03 -0.03", "p 0.05 -0.03 0.05 -0.28 0.27 -0.28 0.4 -0.03"},
            {"Zwei Räder", "c -0.42 0.38 0.17", "c 0.42 0.38 0.17"},
            {"Licht vorne und hinten", "o 0.62 0.05 0.75 0.15", "o -0.75 0.05 -0.65 0.15"}}};

    private Guide guide;
    private LinearLayout guideBar;
    private TextView guideText;
    private int guideMotif = -1, guideStep;

    private void pickGuide() {
        new AlertDialog.Builder(this)
            .setTitle("Was möchtest du zeichnen?")
            .setItems(MOTIFS, (d, w) -> startGuide(w))
            .show();
    }

    private void startGuide(int motif) {
        guideMotif = motif;
        guideStep = 0;
        FrameLayout root = (FrameLayout) board.getParent();
        if (guideBar == null) {
            guideBar = new LinearLayout(this);
            guideBar.setOrientation(LinearLayout.VERTICAL);
            guideBar.setPadding(dp(18), dp(14), dp(18), dp(14));
            GradientDrawable g = new GradientDrawable();
            g.setColor(card);
            g.setStroke(dp(1.5f), rust);
            g.setCornerRadius(dp(22));
            guideBar.setBackground(g);
            guideBar.setElevation(dp(6));
            guideText = new TextView(this);
            guideText.setTextColor(ink);
            guideText.setTextSize(18);
            guideText.setTypeface(android.graphics.Typeface.create("casual", android.graphics.Typeface.BOLD));
            guideBar.addView(guideText);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.END);
            row.setPadding(0, dp(10), 0, 0);
            row.addView(chip("✕ ENDE", v -> stopGuide()));
            row.addView(chip("← ZURÜCK", v -> {
                guideStep = Math.max(0, guideStep - 1);
                paintGuide();
            }));
            row.addView(chip("WEITER →", v -> {
                guideStep++;
                if (guideStep >= STEPS[guideMotif].length) {
                    guideText.setText("Super gemacht! 🎉 Jetzt ausmalen – mit den Farben unten.");
                    guide.postDelayed(this::stopGuide, 2600);
                    guideStep = STEPS[guideMotif].length;
                    guide.invalidate();
                    return;
                }
                paintGuide();
            }));
            guideBar.addView(row);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.setMargins(dp(14), 0, dp(14), dp(110));
            root.addView(guideBar, lp);
        }
        guideBar.setVisibility(View.VISIBLE);
        paintGuide();
    }

    private void paintGuide() {
        if (guideMotif < 0) return;
        String[] step = STEPS[guideMotif][guideStep];
        guideText.setText("Schritt " + (guideStep + 1) + "/" + STEPS[guideMotif].length + ": " + step[0] + ". Fahr der gestrichelten Linie nach!");
        guide.invalidate();
    }

    private void stopGuide() {
        guideMotif = -1;
        if (guideBar != null) guideBar.setVisibility(View.GONE);
        guide.invalidate();
    }

    private final class Guide extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final android.graphics.RectF r = new android.graphics.RectF();

        Guide() {
            super(BoardActivity.this);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (guideMotif < 0) return;
            float cx = getWidth() / 2f, cy = getHeight() * 0.45f, k = Math.min(getWidth(), getHeight()) * 0.36f;
            long now = SystemClock.uptimeMillis();
            String[][] steps = STEPS[guideMotif];
            for (int i = 0; i < steps.length && i <= guideStep; i++) {
                boolean cur = i == guideStep;
                p.setPathEffect(cur ? new android.graphics.DashPathEffect(new float[]{dp(12), dp(9)}, -(now / 30f) % dp(21)) : null);
                p.setStrokeWidth(cur ? dp(4) : dp(2));
                p.setColor(cur ? ((int) (160 + 95 * Math.abs(Math.sin(now / 300.0))) << 24) | (rust & 0x00FFFFFF) : (ink3 & 0x00FFFFFF) | 0x55000000);
                for (int j = 1; j < steps[i].length; j++) shape(c, steps[i][j], cx, cy, k);
            }
            p.setPathEffect(null);
            if (guideStep < steps.length) postInvalidateOnAnimation();
        }

        private void shape(Canvas c, String spec, float cx, float cy, float k) {
            String[] t = spec.split(" ");
            float[] v = new float[t.length - 1];
            for (int i = 1; i < t.length; i++) v[i - 1] = Float.parseFloat(t[i]);
            switch (t[0]) {
                case "c":
                    c.drawCircle(cx + v[0] * k, cy + v[1] * k, v[2] * k, p);
                    break;
                case "o":
                    c.drawOval(cx + v[0] * k, cy + v[1] * k, cx + v[2] * k, cy + v[3] * k, p);
                    break;
                case "l":
                    c.drawLine(cx + v[0] * k, cy + v[1] * k, cx + v[2] * k, cy + v[3] * k, p);
                    break;
                case "a":
                    r.set(cx + v[0] * k, cy + v[1] * k, cx + v[2] * k, cy + v[3] * k);
                    c.drawArc(r, v[4], v[5], false, p);
                    break;
                default:
                    path.reset();
                    path.moveTo(cx + v[0] * k, cy + v[1] * k);
                    for (int i = 2; i + 1 < v.length; i += 2) path.lineTo(cx + v[i] * k, cy + v[i + 1] * k);
                    c.drawPath(path, p);
                    break;
            }
        }
    }

}
