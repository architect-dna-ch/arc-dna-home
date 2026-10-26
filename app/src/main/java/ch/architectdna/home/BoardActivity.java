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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.setMargins(dp(4), 0, dp(4), 0);
            bar.addView(dot, lp);
        }
        bar.addView(divider());
        float[] widths = {2, 4, 9, 18};
        for (float w : widths) {
            TextView t = chipView(w == 2 ? "·" : w == 4 ? "•" : w == 9 ? "●" : "⬤");
            t.setTag(w);
            t.setOnClickListener(v -> {
                width = w;
                paintTools();
            });
            widthChips.add(t);
            bar.addView(t);
        }
        bar.addView(divider());
        eraserChip = chipView("RADIERER");
        eraserChip.setOnClickListener(v -> {
            eraser = !eraser;
            paintTools();
        });
        bar.addView(eraserChip);
        TextView undo = chipView("↶");
        undo.setOnClickListener(v -> {
            if (strokes.isEmpty()) return;
            redo.add(strokes.remove(strokes.size() - 1));
            board.rebuild();
            save();
        });
        bar.addView(undo);
        TextView again = chipView("↷");
        again.setOnClickListener(v -> {
            if (redo.isEmpty()) return;
            strokes.add(redo.remove(redo.size() - 1));
            board.rebuild();
            save();
        });
        bar.addView(again);
        TextView clear = chipView("LEEREN");
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
        bar.addView(clear);
        hs.addView(bar);
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
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(boardFile(id))))) {
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
                strokes.add(s);
            }
        } catch (IOException ignored) {
        }
        putBoard(id, titleOf(id));
        title.setText(titleOf(id));
        board.reset();
        board.rebuild();
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

    private void showBoards() {
        save();
        JSONArray a = listBoards();
        String[] names = new String[a.length()];
        String[] ids = new String[a.length()];
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d.M. HH:mm", java.util.Locale.GERMAN);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            ids[i] = o.optString("id");
            names[i] = o.optString("title") + "  ·  " + f.format(new java.util.Date(o.optLong("at")));
        }
        new AlertDialog.Builder(this)
            .setTitle("Boards")
            .setItems(names, (d, w) -> open(ids[w]))
            .setPositiveButton("+ Neu", (d, w) -> newBoard())
            .setNegativeButton("Löschen…", (d, w) -> deleteBoard())
            .show();
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
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            boolean on = !eraser && c == colour;
            g.setStroke(dp(on ? 3 : 1), on ? (c == ink ? rust : ink) : line);
            d.setBackground(g);
            d.setScaleX(on ? 1.15f : 1f);
            d.setScaleY(on ? 1.15f : 1f);
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
        t.setMinWidth(dp(40));
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
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

        float[] bounds() {
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            for (Stroke s : strokes) {
                if (s.eraser) continue;
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

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
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
}
