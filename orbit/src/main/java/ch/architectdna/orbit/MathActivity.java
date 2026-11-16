package ch.architectdna.orbit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import static ch.architectdna.orbit.ArcUi.t;

/**
 * ARC Math: type a problem, get the solution in about a second. Steps, just the result, a simple
 * explanation, or a check of your own attempt. Runs on Groq (gpt-oss-120b), one of the fastest
 * ways to run a strong model; no account, problems stay in a small history on this phone.
 */
public class MathActivity extends Activity {

    private static final String ORIGIN = "https://einspruch.architect-dna.ch";
    private static final String[] SYMBOLS = {"²", "³", "√", "π", "×", "÷", "(", ")", "%", "≈", "CHF "};

    private ArcUi ui;
    private SharedPreferences prefs;
    private LinearLayout col, out;
    private EditText input;
    private String mode = "steps";
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new ArcUi(this);
        prefs = getSharedPreferences("math", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg);
        scroll.setFitsSystemWindows(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(44));
        scroll.addView(col);
        setContentView(scroll);
        build();
    }

    private void build() {
        col.removeAllViews();
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(ui.label("ARC Math", true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = ui.mono("✕", ui.ink2, 13);
        x.setPadding(ui.dp(12), ui.dp(6), 0, ui.dp(6));
        x.setOnClickListener(v -> finish());
        h.addView(x);
        col.addView(h);
        col.addView(ui.title(t("Solve it.\nGet it.", "Rechnen.\nVerstehen."), 40), ui.lp(-1, -2, ui.dp(22)));

        input = ui.input(t("Type the problem, e.g. 1'200 CHF at 2.5 % for 3 years, compound interest?",
            "Aufgabe eingeben, z.B. 1'200 CHF zu 2.5 % während 3 Jahren, Zinseszins?"), true, 2000);
        input.setMinLines(4);
        input.setText(prefs.getString("draft", ""));
        col.addView(input, ui.lp(-1, -2, ui.dp(22)));

        // A photo of the problem: worksheet, book, whiteboard
        LinearLayout cam = new LinearLayout(this);
        cam.addView(ui.button(t("📷  Photo", "📷  Foto"), 0, v -> takePhoto()), new LinearLayout.LayoutParams(0, ui.dp(50), 1f));
        View gap = new View(this);
        cam.addView(gap, new LinearLayout.LayoutParams(ui.dp(8), 1));
        cam.addView(ui.button(t("🖼  From gallery", "🖼  Aus Galerie"), 2, v -> pickImage()), new LinearLayout.LayoutParams(0, ui.dp(50), 1f));
        col.addView(cam, ui.lp(-1, -2, ui.dp(10)));

        // Symbols the phone keyboard hides
        ArcUi.Wrap sym = new ArcUi.Wrap(this, ui.dp(6));
        for (String s : SYMBOLS) {
            TextView c = ui.chip(s.trim(), false, v -> {
                int p = Math.max(0, input.getSelectionStart());
                input.getText().insert(p, s);
            });
            c.setTypeface(Typeface.MONOSPACE);
            sym.addView(c);
        }
        col.addView(sym, ui.lp(-1, -2, ui.dp(10)));

        col.addView(ui.label(t("How", "Wie"), false), ui.lp(-1, -2, ui.dp(22)));
        ArcUi.Wrap modes = new ArcUi.Wrap(this, ui.dp(8));
        String[][] ms = {{"steps", t("🪜 Steps", "🪜 Schritte")}, {"answer", t("⚡ Just the result", "⚡ Nur Ergebnis")},
            {"explain", t("🧒 Explain simply", "🧒 Einfach erklärt")}, {"check", t("✓ Check my attempt", "✓ Meine Lösung prüfen")}};
        for (String[] m : ms) modes.addView(ui.chip(m[1], m[0].equals(mode), v -> { mode = m[0]; keepDraft(); build(); }));
        col.addView(modes, ui.lp(-1, -2, ui.dp(10)));

        col.addView(ui.button(t("Solve →", "Lösen →"), 1, v -> solve(null)), ui.lp(-1, ui.dp(56), ui.dp(20)));

        out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        col.addView(out, ui.lp(-1, -2, ui.dp(8)));

        JSONArray hist = history();
        if (hist.length() > 0) {
            col.addView(ui.label(t("Recent", "Zuletzt"), false), ui.lp(-1, -2, ui.dp(30)));
            for (int i = 0; i < Math.min(hist.length(), 12); i++) {
                JSONObject o = hist.optJSONObject(i);
                if (o == null) continue;
                String p = o.optString("p"), r = o.optString("r");
                col.addView(ui.row(null, p.length() > 70 ? p.substring(0, 70) + "…" : p, r.isEmpty() ? "" : "→ " + r,
                    v -> { input.setText(p); input.setSelection(p.length()); show(o.optString("t"), r, 0); }), ui.lp(-1, -2, ui.dp(8)));
            }
        }
    }

    private void keepDraft() {
        if (input != null) prefs.edit().putString("draft", input.getText().toString()).apply();
    }

    private void solve() { solve(null); }

    /** image = a base64 JPEG of the problem, or null for typed text only. */
    private void solve(String image) {
        String p = input.getText().toString().trim();
        if ((p.isEmpty() && image == null) || busy) return;
        busy = true;
        keepDraft();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        out.removeAllViews();
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.rust));
        out.addView(pb, ui.lp(-1, ui.dp(6), ui.dp(14)));
        if (image != null) out.addView(ui.mono(t("📷 READING THE PHOTO …", "📷 LIEST DAS FOTO …"), ui.ink3, 10.5f), ui.lp(-1, -2, ui.dp(8)));
        JSONObject body = new JSONObject();
        try {
            body.put("problem", p);
            if (image != null) body.put("image", image);
            body.put("mode", mode);
            body.put("lang", ArcUi.de() ? "de" : "en");
        } catch (Exception ignored) { }
        long t0 = System.currentTimeMillis();
        ui.post(ORIGIN, "/api/math", body, r -> {
            busy = false;
            out.removeAllViews();
            if (r.code == 200 && !r.json.optString("text").isEmpty()) {
                String text = r.json.optString("text"), res = r.json.optString("result"), read = r.json.optString("read");
                if (!read.isEmpty() && p.isEmpty()) { input.setText(read); keepDraft(); }   // show what was read from the photo
                save(p.isEmpty() ? (read.isEmpty() ? "📷" : read) : p, text, res);
                show(text, res, System.currentTimeMillis() - t0);
            } else {
                String msg = r.code == 422 ? t("No problem found on the photo. Closer, sharper, more light?", "Keine Aufgabe auf dem Foto erkannt. Näher, schärfer, mehr Licht?")
                    : r.code == 413 ? t("Photo too large.", "Foto zu gross.")
                    : r.code == 429 ? t("Lots of problems in a short time. Try again in a bit.", "Viele Aufgaben in kurzer Zeit. Gleich nochmal.")
                    : r.code == 0 ? t("No connection.", "Keine Verbindung.") : t("Didn't work. Try again.", "Hat nicht geklappt. Nochmal.");
                out.addView(ui.body(msg, ui.rust, 15), ui.lp(-1, -2, ui.dp(12)));
            }
        });
    }

    private void show(String text, String result, long ms) {
        out.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        card.setBackground(ui.box(ui.card, ui.line, 8));
        if (!result.isEmpty()) {
            card.addView(ui.label(t("Result", "Ergebnis"), true));
            TextView big = ui.title(result, 28);
            big.setTextIsSelectable(true);
            card.addView(big, ui.lp(-1, -2, ui.dp(8)));
        }
        String steps = text;
        int cut = steps.lastIndexOf("\n→");
        if (!result.isEmpty() && cut > 0) steps = steps.substring(0, cut).trim();
        if (!steps.isEmpty() && !(result.isEmpty() ? false : steps.equals("→ " + result))) {
            TextView s = ui.body(steps, ui.ink, 15.5f);
            s.setTypeface(Typeface.MONOSPACE);
            s.setLineSpacing(0, 1.35f);
            s.setTextIsSelectable(true);
            card.addView(s, ui.lp(-1, -2, ui.dp(result.isEmpty() ? 0 : 16)));
        }
        LinearLayout acts = new LinearLayout(this);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        TextView speed = ui.mono(ms > 0 ? String.format(Locale.ROOT, "⚡ %.1f S", ms / 1000f) : "", ui.ink3, 10.5f);
        acts.addView(speed, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView copy = ui.mono(t("COPY", "KOPIEREN"), ui.rust, 11);
        copy.setPadding(ui.dp(12), ui.dp(8), 0, ui.dp(8));
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ARC Math", text));
            Toast.makeText(this, t("Copied", "Kopiert"), Toast.LENGTH_SHORT).show();
        });
        acts.addView(copy);
        card.addView(acts, ui.lp(-1, -2, ui.dp(14)));
        out.addView(card, ui.lp(-1, -2, ui.dp(16)));
        out.addView(ui.button(t("New problem", "Neue Aufgabe"), 2, v -> { input.setText(""); keepDraft(); out.removeAllViews(); input.requestFocus(); }),
            ui.lp(-1, ui.dp(50), ui.dp(10)));
    }

    /* ================= camera & gallery ================= */

    private android.net.Uri photoUri;

    private void takePhoto() {
        // ARC-DNA holds the camera permission (head scroll), so Android wants it granted before the camera app may run.
        if (declaresCamera() && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 60);
            return;
        }
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "arc-math-" + System.currentTimeMillis() + ".jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (android.os.Build.VERSION.SDK_INT >= 29) cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ARC Math");
            photoUri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            android.content.Intent i = new android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri)
                .addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(i, 61);
        } catch (RuntimeException e) {
            Toast.makeText(this, t("No camera app found. Pick a photo from the gallery instead.", "Keine Kamera-App gefunden. Nimm ein Foto aus der Galerie."), Toast.LENGTH_LONG).show();
        }
    }

    private boolean declaresCamera() {
        try {
            String[] req = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (req != null) for (String r : req) if (android.Manifest.permission.CAMERA.equals(r)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 60 && res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) takePhoto();
    }

    private void pickImage() {
        try {
            startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*"), 62);
        } catch (RuntimeException ignored) { }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 61) {
            android.net.Uri u = photoUri;
            if (res == RESULT_OK && u != null) send(u);
            if (u != null) try { getContentResolver().delete(u, null, null); } catch (RuntimeException ignored) { }   // don't keep it in the gallery
        } else if (req == 62 && res == RESULT_OK && data != null && data.getData() != null) {
            send(data.getData());
        }
    }

    /** Shrink to ≤ 1600 px, upright, JPEG ~85 % → a few hundred kB, then solve. */
    private void send(android.net.Uri u) {
        String b64 = null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 3200) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            android.graphics.Bitmap bm;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) { bm = android.graphics.BitmapFactory.decodeStream(in, null, o); }
            if (bm == null) throw new java.io.IOException("decode");
            int rot = 0;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                int or = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            } catch (Exception ignored) { }
            float scale = Math.min(1f, 1600f / Math.max(bm.getWidth(), bm.getHeight()));
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postScale(scale, scale);
            m.postRotate(rot);
            android.graphics.Bitmap small = android.graphics.Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bo);
            b64 = android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Toast.makeText(this, t("Couldn't open the photo.", "Foto liess sich nicht öffnen."), Toast.LENGTH_LONG).show();
        }
        if (b64 != null) solve(b64);
    }

    private JSONArray history() {
        try { return new JSONArray(prefs.getString("hist", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void save(String p, String text, String r) {
        JSONArray old = history(), n = new JSONArray();
        try {
            n.put(new JSONObject().put("p", p).put("t", text).put("r", r));
            for (int i = 0; i < Math.min(old.length(), 29); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !p.equals(o.optString("p"))) n.put(o);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString("hist", n.toString()).apply();
    }
}
