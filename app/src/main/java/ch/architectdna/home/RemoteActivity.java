package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ARC Remote: the phone in your hand steers the music on the tablet (bathtub, kitchen, couch).
 * Big targets for wet hands, the phone's volume keys turn the tablet up and down, and "Übernehmen"
 * moves what plays on the phone over to the tablet. See BtRemote for the link.
 */
public class RemoteActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final int bg = 0xFF0E0E0D, card = 0xFF1A1A18, ink = 0xFFF4F2EC, ink2 = 0xFFA5A5A0, ink3 = 0xFF6F6E69,
        line = 0xFF2C2C2A, rust = 0xFFD9472F, green = 0xFF2BD96B;

    private volatile BtRemote.Link link;
    private volatile boolean started;
    private Thread connecting;

    private TextView dot, peer, title, artist, app, note, playBtn;
    private ImageView cover;
    private Seek seek;
    private VolBar volBar;
    private EditText search;
    private LinearLayout queue;

    // the tablet's state
    private boolean playing;
    private long pos, dur, at;      // at = our uptime when pos was true
    private float speed = 1f;
    private int vol, volMax = 15;
    private String artKey = "";
    private TextView speedBtn, sleepBtn;
    private int sleepMin;
    private long lastTapAt;
    private float lastTapX;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // in the tub, the screen stays ready
        offerTile();

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        sv.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(18), dp(22), dp(40));
        sv.addView(col);

        // header: — ARC REMOTE · device · dot
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = mono("— ARC REMOTE", rust, 11);
        head.addView(lab, new LinearLayout.LayoutParams(0, -2, 1f));
        peer = mono(L.t("SUCHT …"), ink3, 11);
        head.addView(peer);
        dot = new TextView(this);
        dot.setText(" ●");
        dot.setTextColor(rust);
        dot.setTextSize(13);
        head.addView(dot);
        head.setOnClickListener(v -> pickDevice());
        col.addView(head);

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(round(card, line, 10));
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
        });
        cover.setClipToOutline(true);
        cover.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            long now = SystemClock.uptimeMillis();
            boolean right = e.getX() > v.getWidth() / 2f;
            if (now - lastTapAt < 320 && (lastTapX > v.getWidth() / 2f) == right) {
                send(right ? "SEEKBY\t10000" : "SEEKBY\t-10000");
                pos = Math.max(0, nowPos() + (right ? 10_000 : -10_000));
                at = now;
                flash(right ? "10 ⟳" : "⟲ 10");
                lastTapAt = 0;
            } else {
                lastTapAt = now;
                lastTapX = e.getX();
            }
            return true;
        });
        FrameLayout coverBox = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
        };
        coverBox.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        col.addView(coverBox, lp(-1, -2, dp(22)));

        app = mono("", ink3, 10.5f);
        col.addView(app, lp(-1, -2, dp(18)));
        title = new TextView(this);
        title.setTextColor(ink);
        title.setTextSize(26);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setLetterSpacing(-0.02f);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(L.t("Verbinde mit dem Tablet …"));
        col.addView(title, lp(-1, -2, dp(4)));
        artist = new TextView(this);
        artist.setTextColor(ink2);
        artist.setTextSize(16);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(artist, lp(-1, -2, dp(2)));

        seek = new Seek();
        col.addView(seek, lp(-1, dp(44), dp(14)));

        // transport: big targets
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER);
        tr.addView(circle("⏮", card, ink, 72, v -> send("PREV")));
        tr.addView(new View(this), new LinearLayout.LayoutParams(dp(22), 1));
        playBtn = circle("▶", ink, bg, 96, v -> {
            playing = !playing;                 // answer the finger now, the tablet confirms a moment later
            at = SystemClock.uptimeMillis();
            paintPlay();
            send("TOGGLE");
        });
        tr.addView(playBtn);
        tr.addView(new View(this), new LinearLayout.LayoutParams(dp(22), 1));
        tr.addView(circle("⏭", card, ink, 72, v -> send("NEXT")));
        col.addView(tr, lp(-1, -2, dp(10)));

        // Netflix-style extras: −10 s · speed · sleep timer · +10 s (double-tap the cover left/right works too)
        LinearLayout ex = new LinearLayout(this);
        TextView b10 = pill("⟲ 10", v -> { pos = Math.max(0, nowPos() - 10_000); at = SystemClock.uptimeMillis(); send("SEEKBY\t-10000"); });
        speedBtn = pill("1×", v -> send("SPEEDNEXT"));
        sleepBtn = pill("☾", v -> {
            int next = 0;
            for (int m : MediaExtras.SLEEP_MIN) if (m > sleepMin) { next = m; break; }
            sleepMin = next;
            sleepBtn.setText(next == 0 ? "☾" : "☾ " + next + "'");
            send("SLEEP\t" + next);
        });
        TextView f10 = pill("10 ⟳", v -> { pos = nowPos() + 10_000; at = SystemClock.uptimeMillis(); send("SEEKBY\t10000"); });
        for (TextView x : new TextView[]{b10, speedBtn, sleepBtn, f10}) {
            LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(0, dp(48), 1f);
            xl.leftMargin = xl.rightMargin = dp(3);
            ex.addView(x, xl);
        }
        col.addView(ex, lp(-1, -2, dp(16)));

        // volume
        LinearLayout vr = new LinearLayout(this);
        vr.setGravity(Gravity.CENTER_VERTICAL);
        vr.addView(circle("−", card, ink, 52, v -> send("VOLSTEP\t-1")));
        volBar = new VolBar();
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        vlp.leftMargin = dp(12);
        vlp.rightMargin = dp(12);
        vr.addView(volBar, vlp);
        vr.addView(circle("+", card, ink, 52, v -> send("VOLSTEP\t1")));
        col.addView(vr, lp(-1, -2, dp(24)));
        TextView keys = mono(L.t("LAUTSTÄRKETASTEN DES HANDYS STEUERN DAS TABLET"), ink3, 9.5f);
        keys.setGravity(Gravity.CENTER);
        col.addView(keys, lp(-1, -2, dp(8)));

        note = new TextView(this);
        note.setTextColor(rust);
        note.setTextSize(13.5f);
        note.setGravity(Gravity.CENTER);
        col.addView(note, lp(-1, -2, dp(12)));

        // hand over what plays on this phone
        TextView hand = pill(L.t("📱 → 🔊   Vom Handy übernehmen"), v -> handOver());
        col.addView(hand, lp(-1, dp(54), dp(16)));

        // ARC Flow: the other direction — bring the tablet's music onto this phone, same spot
        TextView bring = pill(L.t("🔊 → 📱   Vom Tablet hierher holen"), v -> bringHere());
        col.addView(bring, lp(-1, dp(54), dp(10)));

        // search → plays on the tablet
        col.addView(mono(L.t("— AUF DEM TABLET SPIELEN"), ink3, 11), lp(-1, -2, dp(26)));
        TextView pushNow = new TextView(this);
        pushNow.setText(L.t("⬆  Handy-Song aufs Tablet (überspielt die Wiedergabe)"));
        pushNow.setTextColor(0xFFFFFFFF);
        pushNow.setTextSize(14.5f);
        pushNow.setTypeface(pushNow.getTypeface(), Typeface.BOLD);
        pushNow.setGravity(Gravity.CENTER);
        pushNow.setBackground(round(rust, rust, 10));
        pushNow.setPadding(0, dp(13), 0, dp(13));
        pushNow.setOnClickListener(v -> castPhoneTrack());
        col.addView(pushNow, lp(-1, -2, dp(10)));
        LinearLayout sr = new LinearLayout(this);
        search = new EditText(this);
        search.setHint("Song, Artist, Playlist …");
        search.setTextColor(ink);
        search.setHintTextColor(ink3);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(round(card, line, 8));
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setOnEditorActionListener((v, id, e) -> { playSearch(); return true; });
        sr.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView go = circle("▶", rust, 0xFFFFFFFF, 52, v -> playSearch());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(52), dp(52));
        glp.leftMargin = dp(8);
        sr.addView(go, glp);
        col.addView(sr, lp(-1, -2, dp(10)));

        col.addView(mono(L.t("— ALS NÄCHSTES"), ink3, 11), lp(-1, -2, dp(26)));
        queue = new LinearLayout(this);
        queue.setOrientation(LinearLayout.VERTICAL);
        col.addView(queue, lp(-1, -2, dp(8)));

        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 41);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == 41 && started) connect();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        connect();
        main.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        main.removeCallbacks(tick);
        BtRemote.Link l = link;
        link = null;
        if (l != null) l.close();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (link != null && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            int step = code == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1;
            vol = Math.max(0, Math.min(volMax, vol + step));
            volBar.invalidate();
            send("VOLSTEP\t" + step);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /* ---------------- link ---------------- */

    /** First time here on Android 13+, offer to drop an ARC Remote tile into the Quick-Settings shade. */
    private void offerTile() {
        if (Build.VERSION.SDK_INT < 33) return;
        android.content.SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
        if (p.getBoolean("remoteTileAsked", false)) return;
        p.edit().putBoolean("remoteTileAsked", true).apply();
        try {
            android.app.StatusBarManager sb = getSystemService(android.app.StatusBarManager.class);
            if (sb != null) sb.requestAddTileService(
                new android.content.ComponentName(this, RemoteTile.class), "ARC Remote",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_music),
                getMainExecutor(), r -> { });
        } catch (Exception ignored) { }
    }

    private void connect() {
        if (link != null || (connecting != null && connecting.isAlive())) return;
        if (BtRemote.adapter(this) == null) {
            status(false, L.t("BLUETOOTH AUS"));
            note.setText(L.t("Bluetooth einschalten (und ARC-DNA die Berechtigung geben)."));
            return;
        }
        status(false, L.t("SUCHT …"));
        String addr = getSharedPreferences("home", MODE_PRIVATE).getString("remoteAddr", "");
        connecting = new Thread(() -> {
            BtRemote.Link l = BtRemote.connect(this, addr);
            main.post(() -> {
                if (l == null) {
                    status(false, L.t("NICHT ERREICHBAR"));
                    note.setText(L.t("Tablet nicht gefunden. Beide gekoppelt? Auf dem Tablet ARC-DNA mit Benachrichtigungszugriff?"));
                    if (started) main.postDelayed(this::connect, 4000);
                    return;
                }
                if (!started) { l.close(); return; }
                link = l;
                note.setText("");
                status(true, l.peerName.toUpperCase(Locale.GERMAN));
                l.readLoop(new BtRemote.Handler2() {
                    @Override public void onFrame(String msg) { main.post(() -> onFrameMain(msg)); }
                    @Override public void onClosed() {
                        main.post(() -> {
                            if (link == l) link = null;
                            status(false, L.t("GETRENNT"));
                            if (started) main.postDelayed(RemoteActivity.this::connect, 1200);
                        });
                    }
                });
                l.send("HELLO");
            });
        }, "arc-remote-connect");
        connecting.start();
    }

    private void send(String cmd) {
        BtRemote.Link l = link;
        if (l == null) { connect(); return; }
        playBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        l.send(cmd);
    }

    private void status(boolean on, String who) {
        dot.setTextColor(on ? green : rust);
        peer.setText(who);
    }

    private void onFrameMain(String msg) {
        String[] p = msg.split("\t", -1);
        switch (p[0]) {
            case "STATE":
                if (p.length < 10) return;
                String t = p[1];
                title.setText(t.isEmpty() ? L.t("Nichts läuft auf dem Tablet") : t);
                artist.setText(p[2]);
                app.setText(p[3].isEmpty() ? "" : ("— " + p[3]).toUpperCase(Locale.GERMAN));
                playing = "1".equals(p[4]);
                pos = parseL(p[5]);
                dur = parseL(p[6]);
                try { speed = Float.parseFloat(p[7]); } catch (NumberFormatException e) { speed = 1f; }
                at = SystemClock.uptimeMillis();
                vol = (int) parseL(p[8]);
                if (speedBtn != null) speedBtn.setText(MediaExtras.speedLabel(speed));
                volMax = Math.max(1, (int) parseL(p[9]));
                paintPlay();
                seek.invalidate();
                volBar.invalidate();
                if (t.isEmpty()) cover.setImageDrawable(null);
                break;
            case "ART":
                if (p.length < 3) return;
                if (p[2].isEmpty()) { cover.setImageDrawable(null); return; }
                if (p[1].equals(artKey)) return;
                artKey = p[1];
                try {
                    byte[] raw = Base64.decode(p[2], Base64.DEFAULT);
                    Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    cover.setImageBitmap(bm);
                    cover.setAlpha(0f);
                    cover.animate().alpha(1f).setDuration(220).start();
                } catch (RuntimeException ignored) { }
                break;
            case "QUEUE":
                queue.removeAllViews();
                for (int i = 1; i < p.length && i <= 25; i++) {
                    String[] q = p[i].split("\\|", 3);
                    if (q.length < 2) continue;
                    long id = parseL(q[0]);
                    queue.addView(queueRow(q[1], q.length > 2 ? q[2] : "", () -> send("QITEM\t" + id)));
                }
                if (queue.getChildCount() == 0) queue.addView(mono(L.t("KEINE WARTESCHLANGE"), ink3, 10.5f));
                break;
            case "NOTE":
                if (p.length > 1) note.setText(p[1]);
                break;
            default:
                break;
        }
    }

    private static long parseL(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    /** A short "⟲ 10" over the cover, like a video player. */
    private void flash(String s) {
        note.setText(s);
        note.setAlpha(1f);
        note.animate().alpha(0.4f).setDuration(700).start();
    }

    private long nowPos() {
        if (!playing) return pos;
        long p = pos + (long) ((SystemClock.uptimeMillis() - at) * speed);
        return dur > 0 ? Math.min(p, dur) : p;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            seek.invalidate();
            main.postDelayed(this, 500);
        }
    };

    private void paintPlay() {
        playBtn.setText(playing ? "❚❚" : "▶");
    }

    /* ---------------- actions ---------------- */

    /** Grab whatever's playing on THIS phone and tell the tablet to play it — replacing its playlist. */
    private void castPhoneTrack() {
        String[] now = localNow();
        if (now == null) { toast(L.t("Auf dem Handy läuft gerade kein Song.")); return; }
        send("SEARCH\t" + (now[0] + " " + now[1]).trim().replace('\t', ' '));
        note.setText(L.t("Aufs Tablet überspielt: ") + now[0]);
        toast("„" + now[0] + L.t("“ läuft jetzt auf dem Tablet."));
    }

    /** Title + artist of the song playing on this phone right now (prefers an actively-playing session). */
    private String[] localNow() {
        try {
            MediaSessionManager sm = getSystemService(MediaSessionManager.class);
            java.util.List<MediaController> list = sm.getActiveSessions(new ComponentName(this, MediaListener.class));
            MediaController best = null;
            for (MediaController m : list) {
                MediaMetadata md = m.getMetadata();
                if (md == null || md.getString(MediaMetadata.METADATA_KEY_TITLE) == null) continue;
                PlaybackState ps = m.getPlaybackState();
                boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
                if (best == null || playing) { best = m; if (playing) break; }
            }
            if (best == null) return null;
            MediaMetadata md = best.getMetadata();
            String ti = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (ti == null || ti.isEmpty()) return null;
            String ar = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            return new String[]{ti, ar == null ? "" : ar};
        } catch (Exception e) { return null; }
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    private void playSearch() {
        String q = search.getText().toString().trim();
        if (q.isEmpty()) return;
        send("SEARCH\t" + q.replace('\t', ' '));
        note.setText(L.t("Spielt auf dem Tablet: ") + q);
        search.setText("");
        search.clearFocus();
        try {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(search.getWindowToken(), 0);
        } catch (RuntimeException ignored) { }
    }

    /** What plays on this phone moves to the tablet: same song, same spot; the phone pauses. */
    /** ARC Flow: pull whatever is playing on the tablet onto THIS phone, at roughly the same spot, and
     *  pause the tablet. The reverse of handOver(). */
    private void bringHere() {
        note.setText(L.t("Hole die Musik vom Tablet …"));
        final Runnable watchdog = () -> note.setText(L.t("Keine Verbindung zum Tablet – ist ARC dort offen und Bluetooth an?"));
        main.postDelayed(watchdog, 9000);
        new Thread(() -> {
            String[] t = null;
            try { t = BtRemote.pullFromPeer(this, true); } catch (RuntimeException ignored) { }
            final String[] tr = t;
            main.post(() -> {
                main.removeCallbacks(watchdog);
                if (tr != null) {
                    long p = 0; try { p = Long.parseLong(tr[2]); } catch (NumberFormatException ignored) { }
                    LocalMedia.playHere(this, tr[0], tr[1], p);
                    note.setText(L.t("Hierher geholt: ") + tr[0]);
                } else {
                    note.setText(L.t("Auf dem Tablet läuft gerade nichts, oder keine Verbindung."));
                }
            });
        }, "arc-bring-here").start();
    }

    private void handOver() {
        MediaController mine = null;
        try {
            MediaSessionManager m = getSystemService(MediaSessionManager.class);
            for (MediaController c : m.getActiveSessions(new ComponentName(this, MediaListener.class))) {
                PlaybackState s = c.getPlaybackState();
                if (c.getMetadata() != null && (mine == null || s != null && s.getState() == PlaybackState.STATE_PLAYING)) mine = c;
            }
        } catch (SecurityException e) {
            note.setText(L.t("Dafür braucht ARC-DNA den Benachrichtigungszugriff auf dem Handy."));
            return;
        }
        if (mine == null || mine.getMetadata() == null) { note.setText(L.t("Auf dem Handy läuft gerade nichts.")); return; }
        MediaMetadata md = mine.getMetadata();
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE), a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (t == null) { note.setText(L.t("Auf dem Handy läuft gerade nichts.")); return; }
        PlaybackState s = mine.getPlaybackState();
        long p = s == null ? 0 : s.getPosition() + (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0
            ? SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime() : 0);
        String q = (t + (a == null ? "" : " " + a)).replace('\t', ' ');
        try { mine.getTransportControls().pause(); } catch (RuntimeException ignored) { }
        send("SEARCH\t" + q);
        long target = p + 2600;     // the tablet needs a moment to find and start the song
        if (p > 8000) main.postDelayed(() -> send("SEEK\t" + target), 2600);
        note.setText(L.t("Übernommen: ") + t);
    }

    private void pickDevice() {
        BluetoothAdapter a = BtRemote.adapter(this);
        if (a == null) { note.setText(L.t("Bluetooth einschalten.")); return; }
        List<BluetoothDevice> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                list.add(d);
                names.add(d.getName() == null ? d.getAddress() : d.getName());
            }
        } catch (SecurityException e) { return; }
        if (list.isEmpty()) { note.setText(L.t("Erst Handy und Tablet per Bluetooth koppeln.")); return; }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Welches Gerät spielt?"))
            .setItems(names.toArray(new String[0]), (dlg, i) -> {
                getSharedPreferences("home", MODE_PRIVATE).edit().putString("remoteAddr", list.get(i).getAddress()).apply();
                BtRemote.Link l = link;
                link = null;
                if (l != null) l.close();
                else connect();
            })
            .show();
    }

    /* ---------------- views ---------------- */

    private final class Seek extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float drag = -1;

        Seek() { super(RemoteActivity.this); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), y = dp(14);
            float f = drag >= 0 ? drag : dur > 0 ? (float) nowPos() / dur : 0;
            p.setColor(line);
            c.drawRoundRect(new RectF(0, y - dp(2), w, y + dp(2)), dp(2), dp(2), p);
            p.setColor(rust);
            c.drawRoundRect(new RectF(0, y - dp(2), w * f, y + dp(2)), dp(2), dp(2), p);
            c.drawCircle(w * f, y, dp(drag >= 0 ? 10 : 7), p);
            p.setColor(ink3);
            p.setTextSize(dp(11));
            p.setTypeface(Typeface.MONOSPACE);
            long shown = drag >= 0 ? (long) (drag * dur) : nowPos();
            c.drawText(time(shown), 0, y + dp(24), p);
            String d = time(dur);
            c.drawText(d, w - p.measureText(d), y + dp(24), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (dur <= 0) return false;
            float f = Math.max(0, Math.min(1, e.getX() / getWidth()));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE:
                    drag = f;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    long to = (long) (f * dur);
                    pos = to;
                    at = SystemClock.uptimeMillis();
                    send("SEEK\t" + to);
                    drag = -1;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    drag = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }

    private final class VolBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        VolBar() { super(RemoteActivity.this); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int n = Math.max(1, volMax);
            float gap = dp(3), bw = (w - gap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                float bh = h * (0.3f + 0.7f * (i + 1) / n);
                p.setColor(i < vol ? rust : line);
                c.drawRoundRect(new RectF(i * (bw + gap), h - bh, i * (bw + gap) + bw, h), dp(2), dp(2), p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
                int v = Math.round(Math.max(0, Math.min(1, e.getX() / getWidth())) * volMax);
                if (v != vol || a == MotionEvent.ACTION_UP) {
                    vol = v;
                    invalidate();
                    if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_MOVE) send("VOL\t" + v);
                }
            }
            return true;
        }
    }

    private View queueRow(String t, String sub, Runnable onTap) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        r.setBackground(round(card, line, 8));
        TextView a = new TextView(this);
        a.setText(t);
        a.setTextColor(ink);
        a.setTextSize(15);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(a);
        if (!sub.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(sub);
            b.setTextColor(ink3);
            b.setTextSize(13);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(b);
        }
        r.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.bottomMargin = dp(6);
        r.setLayoutParams(l);
        return r;
    }

    private TextView circle(String s, int fill, int colour, int sizeDp, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(sizeDp > 80 ? 30 : sizeDp > 60 ? 24 : 22);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (fill == card) g.setStroke(dp(1), line);
        t.setBackground(g);
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return t;
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(ink);
        t.setTextSize(15);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(card, line, 8));
        t.setOnClickListener(l);
        return t;
    }

    private TextView mono(String s, int colour, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(sp);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.24f);
        return t;
    }

    private GradientDrawable round(int fill, int stroke, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        return p;
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
