package ch.architectdna.home;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ARC Treasure crypto + storage. Zero-knowledge: the AES key is derived from the passphrase alone
 * (PBKDF2-HMAC-SHA256, 210k iterations), never from the device keystore — so the same encrypted
 * vault opens on any device with the right passphrase, and NOTHING opens it without. Each item is
 * sealed with AES-256-GCM (authenticated), so a wrong passphrase simply fails to decrypt. Only
 * ciphertext ever touches disk; forget the passphrase and the data is gone for good, by design.
 */
final class Treasure {
    private Treasure() { }

    private static final int ITER = 210_000, KEYBITS = 256, SALT = 16, IV = 12, TAG = 128;
    private static final byte[] MAGIC = "ARC-TREASURE-OK".getBytes();

    static final String T_IMAGE = "img", T_TEXT = "txt", T_TOKEN = "key", T_FILE = "bin";

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "treasure");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File meta(Context c) { return new File(dir(c), "vault.meta"); }

    static boolean exists(Context c) { return meta(c).exists(); }

    // ---- key handling ----

    private static SecretKey derive(char[] pass, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(pass, salt, ITER, KEYBITS)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    static byte[] seal(SecretKey key, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[IV];
        new SecureRandom().nextBytes(iv);
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[IV + ct.length];
        System.arraycopy(iv, 0, out, 0, IV);
        System.arraycopy(ct, 0, out, IV, ct.length);
        return out;
    }

    static byte[] open(SecretKey key, byte[] blob) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG, blob, 0, IV));
        return c.doFinal(blob, IV, blob.length - IV);
    }

    /** First run: pick a passphrase. Writes salt + an encrypted verifier so we can check it later. */
    static SecretKey create(Context c, char[] pass) throws Exception {
        byte[] salt = new byte[SALT];
        new SecureRandom().nextBytes(salt);
        SecretKey key = derive(pass, salt);
        byte[] verifier = seal(key, MAGIC);
        byte[] out = new byte[SALT + verifier.length];
        System.arraycopy(salt, 0, out, 0, SALT);
        System.arraycopy(verifier, 0, out, SALT, verifier.length);
        write(meta(c), out);
        return key;
    }

    /** Returns the key if the passphrase is right, else null (wrong passphrase can't be told from a tampered vault — both just fail). */
    static SecretKey unlock(Context c, char[] pass) {
        try {
            byte[] m = read(meta(c));
            byte[] salt = new byte[SALT];
            System.arraycopy(m, 0, salt, 0, SALT);
            byte[] verifier = new byte[m.length - SALT];
            System.arraycopy(m, SALT, verifier, 0, verifier.length);
            SecretKey key = derive(pass, salt);
            byte[] ok = open(key, verifier);
            return java.util.Arrays.equals(ok, MAGIC) ? key : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- items: <id>.hdr (name/type/size) + <id>.dat (payload), both sealed ----

    static final class Item { String id, name, type; long size; }

    static String add(Context c, SecretKey key, String name, String type, byte[] payload) throws Exception {
        String id = Long.toHexString(System.currentTimeMillis()) + Integer.toHexString(new SecureRandom().nextInt(0xffff));
        JSONObject h = new JSONObject();
        h.put("n", name); h.put("t", type); h.put("s", payload.length);
        write(new File(dir(c), id + ".hdr"), seal(key, h.toString().getBytes("UTF-8")));
        write(new File(dir(c), id + ".dat"), seal(key, payload));
        return id;
    }

    static List<Item> list(Context c, SecretKey key) {
        List<Item> out = new ArrayList<>();
        File[] hs = dir(c).listFiles((f, n) -> n.endsWith(".hdr"));
        if (hs == null) return out;
        java.util.Arrays.sort(hs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File hf : hs) {
            try {
                JSONObject h = new JSONObject(new String(open(key, read(hf)), "UTF-8"));
                Item it = new Item();
                it.id = hf.getName().substring(0, hf.getName().length() - 4);
                it.name = h.optString("n", "—"); it.type = h.optString("t", T_FILE); it.size = h.optLong("s", 0);
                if (new File(dir(c), it.id + ".dat").exists()) out.add(it);
            } catch (Exception ignored) { }
        }
        return out;
    }

    static byte[] data(Context c, SecretKey key, String id) throws Exception {
        return open(key, read(new File(dir(c), id + ".dat")));
    }

    /** Stream a file straight into the vault, encrypting in chunks — no size limit, no out-of-memory
     *  on big videos. The .dat layout (IV || ciphertext+tag) matches seal(), so data()/dataStream()
     *  read either. Returns the bytes written. */
    static long addStream(Context c, SecretKey key, String name, String type, InputStream in) throws Exception {
        String id = Long.toHexString(System.currentTimeMillis()) + Integer.toHexString(new SecureRandom().nextInt(0xffff));
        File dat = new File(dir(c), id + ".dat");
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[IV];
        new SecureRandom().nextBytes(iv);
        ci.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG, iv));
        long total = 0;
        try (OutputStream fo = new FileOutputStream(dat)) {
            fo.write(iv);
            try (CipherOutputStream co = new CipherOutputStream(fo, ci)) {
                byte[] buf = new byte[64 * 1024];
                for (int r; (r = in.read(buf)) != -1; ) { co.write(buf, 0, r); total += r; }
            }
        } catch (Exception e) { dat.delete(); throw e; }
        JSONObject h = new JSONObject();
        h.put("n", name); h.put("t", type); h.put("s", total);
        write(new File(dir(c), id + ".hdr"), seal(key, h.toString().getBytes("UTF-8")));
        return total;
    }

    /** Decrypt a stored item as a stream — so saving a big video back out doesn't load it all at once. */
    static InputStream dataStream(Context c, SecretKey key, String id) throws Exception {
        FileInputStream fi = new FileInputStream(new File(dir(c), id + ".dat"));
        byte[] iv = new byte[IV];
        int n = 0;
        while (n < IV) { int r = fi.read(iv, n, IV - n); if (r < 0) break; n += r; }
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG, iv));
        return new CipherInputStream(fi, ci);
    }

    static void delete(Context c, String id) {
        new File(dir(c), id + ".hdr").delete();
        new File(dir(c), id + ".dat").delete();
    }

    // ---- encrypted backup: zip the whole treasure dir (already ciphertext) ----

    static void exportTo(Context c, OutputStream os) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            File[] fs = dir(c).listFiles();
            if (fs == null) return;
            for (File f : fs) {
                if (f.isDirectory()) continue;
                z.putNextEntry(new ZipEntry(f.getName()));
                copy(new FileInputStream(f), z, false);
                z.closeEntry();
            }
        }
    }

    /** Restore replaces this device's vault with the backup's. Unlock afterwards with that passphrase. */
    static void importFrom(Context c, InputStream is) throws Exception {
        File d = dir(c);
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
        try (ZipInputStream z = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = new File(e.getName()).getName();        // flatten, ignore any path
                if (n.isEmpty()) continue;
                copy(z, new FileOutputStream(new File(d, n)), false);
                z.closeEntry();
            }
        }
    }

    // ---- io ----

    private static void write(File f, byte[] b) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) { o.write(b); }
    }

    private static byte[] read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            copy(in, o, false);
            return o.toByteArray();
        }
    }

    static void copy(InputStream in, OutputStream out, boolean close) throws Exception {
        byte[] buf = new byte[8192];
        for (int r; (r = in.read(buf)) != -1; ) out.write(buf, 0, r);
        if (close) { in.close(); out.close(); }
    }
}
