package ch.architectdna.home;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.crypto.SecretKey;

import static ch.architectdna.home.ArcUi.t;

/**
 * ARC Treasure: a passphrase-locked vault for what's truly yours — personal photos, pictures of
 * documents, notes, tokens. Everything is sealed with your passphrase (see Treasure), so even a lab
 * can't open it without you, and a forgotten passphrase means it's gone. Keep an encrypted backup on
 * the tablet (and the tablet's on the phone): no single device to lose. ARC Player look throughout.
 */
public class TreasureActivity extends Activity {

    private ArcUi ui;
    private SecretKey key;                 // null = locked
    private FrameLayout root;
    private LinearLayout listCol;
    private final Handler main = new Handler(Looper.getMainLooper());
    private byte[] pendingSave;            // decrypted bytes waiting for a "save a copy" target

    private static final int PICK_IMG = 1, PICK_FILE = 2, EXPORT = 3, IMPORT = 4, SAVE_COPY = 5;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE); // no screenshots / recents peek
        ui = new ArcUi(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(ui.bg);
        root.setFitsSystemWindows(true);
        setContentView(root);
    }

    @Override protected void onStart() { super.onStart(); if (key == null) showLock(); }
    @Override protected void onStop() { super.onStop(); key = null; pendingSave = null; }   // lock when it leaves the screen

    // ---------- lock / create ----------

    private void showLock() {
        root.removeAllViews();
        boolean fresh = !Treasure.exists(this);
        LinearLayout col = pad();
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(ui.label("ARC TREASURE", true));
        col.addView(ui.title(fresh ? t("Lock it to\nyou alone.", "Nur für\ndich allein.")
                                    : t("Welcome\nback.", "Willkommen\nzurück."), 40),
            gap(ui.dp(16)));
        col.addView(ui.body(fresh
            ? t("Pick a passphrase. It's the only key — not stored anywhere, not on our side, not Google's. Forget it and everything inside is gone for good. Write it down somewhere safe.",
                "Wähl eine Passphrase. Sie ist der einzige Schlüssel — nirgends gespeichert, nicht bei uns, nicht bei Google. Vergisst du sie, ist alles darin für immer weg. Schreib sie dir sicher auf.")
            : t("Your passphrase opens the vault.", "Deine Passphrase öffnet den Tresor."), ui.ink2, 15), gap(ui.dp(14)));

        final EditText p1 = pwField(t("Passphrase", "Passphrase"));
        col.addView(p1, gap(ui.dp(20)));
        final EditText p2 = fresh ? pwField(t("Repeat passphrase", "Passphrase wiederholen")) : null;
        if (fresh) col.addView(p2, gap(ui.dp(10)));

        TextView go = ui.button(fresh ? t("Create vault", "Tresor erstellen") : t("Unlock", "Öffnen"), 1, v -> {
            String a = p1.getText().toString();
            if (a.length() < 6) { Toast.makeText(this, t("At least 6 characters.", "Mindestens 6 Zeichen."), Toast.LENGTH_SHORT).show(); return; }
            if (fresh) {
                if (!a.equals(p2.getText().toString())) { Toast.makeText(this, t("The two don't match.", "Die beiden stimmen nicht überein."), Toast.LENGTH_SHORT).show(); return; }
                createVault(a.toCharArray());
            } else unlock(a.toCharArray());
        });
        col.addView(go, gap(ui.dp(22)));

        if (!fresh) {
            TextView imp = ui.mono(t("Restore from a backup", "Aus Backup wiederherstellen"), ui.ink3, 12);
            imp.setPadding(0, ui.dp(16), 0, 0);
            imp.setOnClickListener(v -> openDoc(IMPORT, "application/zip", "*/*"));
            col.addView(imp);
        }
        root.addView(wrap(col));
    }

    private void createVault(char[] pass) {
        new Thread(() -> {
            SecretKey k = null;
            try { k = Treasure.create(this, pass); } catch (Exception ignored) { }
            final SecretKey fk = k;
            main.post(() -> { if (fk != null) { key = fk; showVault(); } else Toast.makeText(this, t("Couldn't create the vault.", "Tresor-Erstellung fehlgeschlagen."), Toast.LENGTH_SHORT).show(); });
        }, "treasure-create").start();
    }

    private void unlock(char[] pass) {
        Toast.makeText(this, t("Opening …", "Öffne …"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final SecretKey k = Treasure.unlock(this, pass);
            main.post(() -> { if (k != null) { key = k; showVault(); } else Toast.makeText(this, t("Wrong passphrase.", "Falsche Passphrase."), Toast.LENGTH_LONG).show(); });
        }, "treasure-unlock").start();
    }

    // ---------- vault ----------

    private void showVault() {
        root.removeAllViews();
        LinearLayout col = pad();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.label("ARC TREASURE", true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView backup = ui.mono("⇄", ui.ink2, 18);
        backup.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        backup.setOnClickListener(v -> backupMenu());
        head.addView(backup);
        TextView lock = ui.mono("🔒", ui.ink2, 15);
        lock.setOnClickListener(v -> { key = null; showLock(); });
        head.addView(lock);
        col.addView(head);

        col.addView(ui.title(t("Your\ntreasure.", "Dein\nSchatz."), 38), gap(ui.dp(12)));

        LinearLayout addRow = new LinearLayout(this);
        addRow.addView(ui.button(t("🖼", "🖼"), 2, v -> pickImage()), flex(ui.dp(6)));
        addRow.addView(ui.button(t("📄 File", "📄 Datei"), 2, v -> openDoc(PICK_FILE, "*/*", "*/*")), flex(ui.dp(6)));
        addRow.addView(ui.button(t("📝 Note", "📝 Notiz"), 2, v -> addText(Treasure.T_TEXT)), flex(ui.dp(6)));
        addRow.addView(ui.button(t("🔑 Token", "🔑 Token"), 2, v -> addText(Treasure.T_TOKEN)), flex(0));
        col.addView(addRow, gap(ui.dp(18)));

        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        col.addView(listCol);
        root.addView(wrap(col));
        refresh();
    }

    private void refresh() {
        if (key == null || listCol == null) return;
        new Thread(() -> {
            final List<Treasure.Item> items = Treasure.list(this, key);
            main.post(() -> {
                listCol.removeAllViews();
                if (items.isEmpty()) {
                    listCol.addView(ui.body(t("Empty. Add a photo, a document picture, a note, or a token — it's sealed the moment it lands here.",
                        "Leer. Füg ein Foto, ein Dokumentbild, eine Notiz oder einen Token hinzu — alles wird sofort verschlüsselt."), ui.ink3, 15));
                    return;
                }
                for (final Treasure.Item it : items) {
                    String icon = Treasure.T_IMAGE.equals(it.type) ? "🖼" : Treasure.T_TOKEN.equals(it.type) ? "🔑" : Treasure.T_TEXT.equals(it.type) ? "📝" : "📄";
                    LinearLayout r = ui.row(icon, it.name, size(it.size), v -> openItem(it));
                    r.setOnLongClickListener(v -> { confirmDelete(it); return true; });
                    listCol.addView(r, gap(ui.dp(8)));
                }
            });
        }, "treasure-list").start();
    }

    // ---------- open an item ----------

    private void openItem(final Treasure.Item it) {
        new Thread(() -> {
            byte[] data;
            try { data = Treasure.data(this, key, it.id); } catch (Exception e) { toast(t("Couldn't open it.", "Konnte nicht öffnen.")); return; }
            if (Treasure.T_IMAGE.equals(it.type)) {
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 2;
                final Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                main.post(() -> showImage(bm));
            } else if (Treasure.T_FILE.equals(it.type)) {
                pendingSave = data;
                main.post(() -> createDoc(SAVE_COPY, it.name));
            } else {
                final String txt = new String(data);
                main.post(() -> showText(it.name, txt));
            }
        }, "treasure-open").start();
    }

    private void showImage(Bitmap bm) {
        if (bm == null) { toast(t("Can't show this image.", "Bild nicht anzeigbar.")); return; }
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xF2000000);
        f.setOnClickListener(v -> d.dismiss());
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bm);
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(-1, -1);
        l.setMargins(ui.dp(12), ui.dp(40), ui.dp(12), ui.dp(40));
        f.addView(iv, l);
        d.setContentView(f);
        d.show();
    }

    private void showText(String name, String txt) {
        TextView tv = ui.body(txt, ui.ink, 16);
        tv.setTextIsSelectable(true);
        tv.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        ScrollView s = new ScrollView(this); s.addView(tv);
        new android.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setView(s)
            .setPositiveButton(t("Copy", "Kopieren"), (dl, i) -> {
                android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("ARC Treasure", txt));
                Toast.makeText(this, t("Copied.", "Kopiert."), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(t("Close", "Schliessen"), null)
            .show();
    }

    private void confirmDelete(final Treasure.Item it) {
        new android.app.AlertDialog.Builder(this)
            .setMessage(t("Delete \"" + it.name + "\"? This can't be undone.", "„" + it.name + "“ löschen? Nicht rückgängig machbar."))
            .setPositiveButton(t("Delete", "Löschen"), (d, i) -> { Treasure.delete(this, it.id); refresh(); })
            .setNegativeButton(t("Keep", "Behalten"), null)
            .show();
    }

    // ---------- add ----------

    private void pickImage() {
        try {
            Intent i = Build.VERSION.SDK_INT >= 33
                ? new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, PICK_IMG);
        } catch (Exception e) { toast(t("No picker found.", "Keine Auswahl möglich.")); }
    }

    private void openDoc(int code, String primary, String fallback) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(fallback), code);
        } catch (Exception e) { toast(t("No file picker.", "Kein Dateiwähler.")); }
    }

    private void createDoc(int code, String name) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name), code);
        } catch (Exception e) { toast(t("Can't save here.", "Speichern nicht möglich.")); }
    }

    private void addText(final String type) {
        final EditText in = ui.input(type.equals(Treasure.T_TOKEN) ? t("Paste the token / secret", "Token / Geheimnis einfügen") : t("Write your note", "Notiz schreiben"), true, 20000);
        LinearLayout w = new LinearLayout(this); w.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        w.addView(in, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this)
            .setTitle(type.equals(Treasure.T_TOKEN) ? t("New token", "Neuer Token") : t("New note", "Neue Notiz"))
            .setView(w)
            .setPositiveButton(t("Seal", "Verschlüsseln"), (d, i) -> {
                String txt = in.getText().toString();
                if (txt.isEmpty()) return;
                String name = txt.length() > 28 ? txt.substring(0, 28).replace('\n', ' ') + "…" : txt.replace('\n', ' ');
                try { byte[] p = txt.getBytes("UTF-8"); Treasure.add(this, key, name, type, p); refresh(); }
                catch (Exception e) { toast(t("Couldn't save.", "Speichern fehlgeschlagen.")); }
            })
            .setNegativeButton(t("Cancel", "Abbrechen"), null)
            .show();
    }

    // ---------- backup ----------

    private void backupMenu() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("Backup", "Backup"))
            .setItems(new CharSequence[]{
                t("Save encrypted backup", "Verschlüsseltes Backup speichern"),
                t("Restore from backup (replaces this vault)", "Aus Backup wiederherstellen (ersetzt diesen Tresor)")
            }, (d, which) -> {
                if (which == 0) createDoc(EXPORT, "arc-treasure-" + System.currentTimeMillis() + ".bak");
                else openDoc(IMPORT, "application/zip", "*/*");
            })
            .show();
    }

    // ---------- results ----------

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || (data.getData() == null && req != 0)) return;
        final Uri uri = data.getData();
        if (req == PICK_IMG || req == PICK_FILE) {
            final String type = req == PICK_IMG ? Treasure.T_IMAGE : Treasure.T_FILE;
            new Thread(() -> {
                try {
                    byte[] bytes = readAll(uri);
                    if (bytes.length > 40 * 1024 * 1024) { toast(t("That file's too big (40 MB max).", "Datei zu gross (max. 40 MB).")); return; }
                    Treasure.add(this, key, nameOf(uri, type), type, bytes);
                    main.post(this::refresh);
                } catch (Exception e) { toast(t("Couldn't add that.", "Konnte nicht hinzufügen.")); }
            }, "treasure-add").start();
        } else if (req == EXPORT) {
            new Thread(() -> {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { Treasure.exportTo(this, os); toast(t("Backup saved. Keep a copy on the tablet.", "Backup gespeichert. Leg eine Kopie aufs Tablet.")); }
                catch (Exception e) { toast(t("Backup failed.", "Backup fehlgeschlagen.")); }
            }, "treasure-export").start();
        } else if (req == IMPORT) {
            new Thread(() -> {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    Treasure.importFrom(this, is);
                    key = null;
                    main.post(() -> { Toast.makeText(this, t("Restored. Unlock with that vault's passphrase.", "Wiederhergestellt. Mit der Passphrase dieses Tresors öffnen."), Toast.LENGTH_LONG).show(); showLock(); });
                } catch (Exception e) { toast(t("Restore failed.", "Wiederherstellung fehlgeschlagen.")); }
            }, "treasure-import").start();
        } else if (req == SAVE_COPY) {
            final byte[] b = pendingSave; pendingSave = null;
            if (b == null) return;
            new Thread(() -> {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(b); toast(t("Saved a decrypted copy.", "Entschlüsselte Kopie gespeichert.")); }
                catch (Exception e) { toast(t("Save failed.", "Speichern fehlgeschlagen.")); }
            }, "treasure-save").start();
        }
    }

    // ---------- helpers ----------

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            Treasure.copy(in, o, false);
            return o.toByteArray();
        }
    }

    private String nameOf(Uri uri, String type) {
        String n = null;
        try (android.database.Cursor cur = getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) n = cur.getString(idx);
            }
        } catch (Exception ignored) { }
        if (n == null || n.isEmpty()) n = (Treasure.T_IMAGE.equals(type) ? "Image " : "File ") + System.currentTimeMillis();
        return n;
    }

    private static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0);
    }

    private void toast(final String s) { main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    private EditText pwField(String hint) {
        EditText e = ui.input(hint, false, 200);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private LinearLayout pad() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(40));
        return col;
    }

    private ScrollView wrap(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    private LinearLayout.LayoutParams gap(int top) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = top; return l; }
    private LinearLayout.LayoutParams flex(int right) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f); l.rightMargin = right; return l; }
}
