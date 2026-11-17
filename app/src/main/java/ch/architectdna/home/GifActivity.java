package ch.architectdna.home;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;

import static ch.architectdna.home.ArcUi.t;

/**
 * ARC Gifs: your own loop collection. Add from the gallery, from a link, or by sharing a GIF to
 * ARC; they live on the device and play in a calm two-up grid. Tap one for full screen, long-press
 * to drop it. Built in the ARC Player look — paper, ink, one rust accent.
 */
public class GifActivity extends Activity {

    private ArcUi ui;
    private GridLayout grid;
    private TextView empty;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int cell;
    private static final int PICK = 0x61F0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        int w = getResources().getDisplayMetrics().widthPixels;
        cell = (w - ui.dp(22) * 2 - ui.dp(10)) / 2;

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.bg);
        sv.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(40));
        sv.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.label("ARC GIFS", true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = ui.mono("✕", ui.ink2, 14);
        x.setOnClickListener(v -> finish());
        head.addView(x);
        col.addView(head);

        col.addView(ui.title(t("Your loop\ncollection.", "Deine Loop-\nSammlung."), 36),
            new LinearLayout.LayoutParams(-1, -2) {{ topMargin = ui.dp(14); }});

        LinearLayout add = new LinearLayout(this);
        TextView fromGallery = ui.button(t("＋  Gallery", "＋  Galerie"), 1, v -> pick());
        TextView fromLink = ui.button(t("🔗  Link", "🔗  Link"), 2, v -> askLink());
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1f); a1.rightMargin = ui.dp(8);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, -2, 1f);
        add.addView(fromGallery, a1);
        add.addView(fromLink, a2);
        col.addView(add, new LinearLayout.LayoutParams(-1, -2) {{ topMargin = ui.dp(18); bottomMargin = ui.dp(18); }});

        empty = ui.body(t("No gifs yet. Add one from your gallery or a link — or share a GIF to ARC.",
            "Noch keine Gifs. Füg eins aus der Galerie oder per Link hinzu — oder teile ein GIF an ARC."), ui.ink3, 15);
        col.addView(empty);

        grid = new GridLayout(this);
        grid.setColumnCount(2);
        col.addView(grid);
        setContentView(sv);

        handleShare(getIntent());
        refresh();
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); handleShare(i); refresh(); }

    /** A GIF shared to ARC (Share sheet → ARC Gifs) lands here. */
    private void handleShare(Intent i) {
        if (i == null) return;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) saveFrom(u);
        }
    }

    private File dir() {
        File d = new File(getFilesDir(), "gifs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void refresh() {
        File[] files = dir().listFiles((f, n) -> n.endsWith(".gif"));
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        empty.setVisibility(files.length == 0 ? View.VISIBLE : View.GONE);
        grid.removeAllViews();
        for (File f : files) grid.addView(makeCell(f));
    }

    private FrameLayout makeCell(final File f) {
        FrameLayout cv = new FrameLayout(this);
        cv.setBackground(ui.box(ui.card, ui.line, 12));
        cv.setClipToOutline(true);
        cv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), ui.dp(12)); }
        });
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cv.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = cell; lp.height = cell;
        lp.setMargins(0, 0, ui.dp(10), ui.dp(10));
        cv.setLayoutParams(lp);
        cv.setOnClickListener(v -> full(f));
        cv.setOnLongClickListener(v -> { confirmDelete(f); return true; });
        loadGif(f, iv, true);
        return cv;
    }

    /** Decode off the main thread, then play. Animated GIFs loop; older devices show the first frame. */
    private void loadGif(final File f, final ImageView iv, final boolean thumb) {
        new Thread(() -> {
            Drawable d = null;
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(f);
                    d = android.graphics.ImageDecoder.decodeDrawable(src, (dec, info, s) -> { if (thumb) dec.setTargetSampleSize(2); });
                } else {
                    d = Drawable.createFromPath(f.getAbsolutePath());
                }
            } catch (Throwable ignored) { }
            final Drawable fd = d;
            main.post(() -> {
                if (fd == null) return;
                iv.setImageDrawable(fd);
                if (fd instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) fd).start();
            });
        }, "gif-decode").start();
    }

    /** Full-screen loop on a dim backdrop; tap to close, a quiet Delete at the bottom. */
    private void full(final File f) {
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xF2000000);
        root.setOnClickListener(v -> dlg.dismiss());
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-1, -1);
        il.setMargins(ui.dp(16), ui.dp(48), ui.dp(16), ui.dp(96));
        root.addView(iv, il);
        loadGif(f, iv, false);
        TextView del = ui.mono(t("DELETE", "LÖSCHEN"), 0xFFFFFFFF, 12);
        del.setBackground(ui.box(ui.rust, 0, 24));
        del.setPadding(ui.dp(22), ui.dp(12), ui.dp(22), ui.dp(12));
        del.setOnClickListener(v -> { f.delete(); dlg.dismiss(); refresh(); });
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = ui.dp(36);
        root.addView(del, dl);
        dlg.setContentView(root);
        dlg.show();
    }

    private void confirmDelete(final File f) {
        new android.app.AlertDialog.Builder(this)
            .setMessage(t("Remove this gif?", "Dieses Gif entfernen?"))
            .setPositiveButton(t("Remove", "Entfernen"), (d, i) -> { f.delete(); refresh(); })
            .setNegativeButton(t("Keep", "Behalten"), null)
            .show();
    }

    private void pick() {
        try {
            Intent i = Build.VERSION.SDK_INT >= 33
                ? new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/gif")
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/gif").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, PICK);
        } catch (Exception e) {
            try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) saveFrom(data.getData());
    }

    private void askLink() {
        final android.widget.EditText in = ui.input("https://…/something.gif", false, 500);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout w = new LinearLayout(this);
        w.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        w.addView(in, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("Add from link", "Per Link hinzufügen"))
            .setView(w)
            .setPositiveButton(t("Add", "Hinzufügen"), (d, i) -> download(in.getText().toString().trim()))
            .setNegativeButton(t("Cancel", "Abbrechen"), null)
            .show();
    }

    /** Copy a picked/shared gif into the collection. */
    private void saveFrom(final Uri uri) {
        new Thread(() -> {
            boolean ok = false;
            File f = new File(dir(), System.currentTimeMillis() + ".gif");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(f)) {
                if (in != null) { copy(in, out); ok = true; }
            } catch (Exception ignored) { }
            done(ok, f);
        }, "gif-save").start();
    }

    private void download(final String url) {
        if (url.isEmpty()) return;
        Toast.makeText(this, t("Fetching …", "Hole …"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = false;
            File f = new File(dir(), System.currentTimeMillis() + ".gif");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "ARC-Gifs");
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    copy(in, out); ok = true;
                }
            } catch (Exception ignored) { }
            // keep only if it actually looks like a GIF
            if (ok && !isGif(f)) { f.delete(); ok = false; }
            done(ok, f);
        }, "gif-dl").start();
    }

    private void done(final boolean ok, final File f) {
        main.post(() -> {
            if (ok) refresh();
            else { if (f.exists()) f.delete(); Toast.makeText(this, t("Couldn't add that one.", "Ging nicht."), Toast.LENGTH_SHORT).show(); }
        });
    }

    private static boolean isGif(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[4]; int n = in.read(h);
            return n >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
        } catch (Exception e) { return false; }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[8192];
        for (int r; (r = in.read(buf)) != -1; ) out.write(buf, 0, r);
    }
}
