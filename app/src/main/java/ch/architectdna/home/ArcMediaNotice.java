package ch.architectdna.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;
import java.util.Locale;

/**
 * The ARC-DNA player as an extra notification next to Android's own media player (which apps
 * can't restyle): the cover in the song's era (floppy, vinyl, cassette, CD, jukebox), title,
 * artist, the progress, and ⏮ ⏯ ⏭ ♥ as buttons. Tap = ARC Player. Fed by MediaListener; it
 * stays while something plays or is paused and goes when the music app ends its session.
 */
final class ArcMediaNotice {

    private static final String CHANNEL = "arc_player";
    private static final int ID = 7300;
    private static final String[] ERA = {"JUKEBOX", "VINYL", "KASSETTE", "CD", "DISKETTE"};

    private static String artFor;
    private static int artEra = -1;
    private static Bitmap art;

    private ArcMediaNotice() {
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("notice", true)
            && (android.os.Build.VERSION.SDK_INT < 33
            || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    /** Shows / refreshes the notice for this session, or removes it (null). */
    static void show(Context c, MediaController mc) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        MediaMetadata m = mc == null ? null : mc.getMetadata();
        PlaybackState st = mc == null ? null : mc.getPlaybackState();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (!enabled(c) || title == null || st == null) {
            nm.cancel(ID);
            return;
        }
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "ARC Player", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        boolean playing = st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING;
        int era = PlayerActivity.eraFor(c, title);
        if (!title.equals(artFor) || era != artEra || art == null) {
            Bitmap cover = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (cover == null) cover = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            art = eraArt(cover, era, 256);
            artFor = title;
            artEra = era;
        }
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long pos = st.getPosition();
        if (playing && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        pos = Math.max(0, dur > 0 ? Math.min(dur, pos) : pos);
        boolean liked = false, canLike = false;
        for (PlaybackState.CustomAction a : st.getCustomActions()) {
            if (isLike(a)) {
                canLike = true;
                liked = likedNow(a);
            }
        }
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, PlayerActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setLargeIcon(art)
            .setContentTitle(title)
            .setContentText(artist != null ? artist : "")
            .setSubText("ARC PLAYER · " + ERA[era] + (dur > 0 ? " · " + time(pos) + " / " + time(dur) : ""))
            .setColor(0xFFB5271E)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(c, "prev", "⏮"))
            .addAction(action(c, "toggle", playing ? "⏸  PAUSE" : "▶  PLAY"))
            .addAction(action(c, "next", "⏭"));
        if (canLike) b.addAction(action(c, "like", liked ? "♥" : "♡"));
        if (dur > 0) b.setProgress(1000, (int) (pos * 1000 / dur), false);
        try {
            nm.notify(ID, b.build());
        } catch (RuntimeException e) {
            DiagLog.log("arc player notice: " + e.getMessage());
        }
    }

    static void cancel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
    }

    private static Notification.Action action(Context c, String cmd, String label) {
        Intent i = new Intent(c, Receiver.class).setAction("ch.architectdna.home.PLAYER_" + cmd);
        PendingIntent pi = PendingIntent.getBroadcast(c, cmd.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(null, label, pi).build();
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static boolean isLike(PlaybackState.CustomAction a) {
        String n = (a.getAction() + " " + a.getName()).toLowerCase(Locale.ROOT);
        if (n.contains("dislike") || n.contains("thumbs_down") || n.contains("thumb_down")) return false;
        return n.contains("like") || n.contains("thumb") || n.contains("heart") || n.contains("favorit") || n.contains("gefällt");
    }

    static boolean likedNow(PlaybackState.CustomAction a) {
        String n = (a.getAction() + " " + a.getName()).toLowerCase(Locale.ROOT);
        return n.contains("undo") || n.contains("remove") || n.contains("unlike") || n.contains("entfernen") || n.contains("rückgängig");
    }

    /** The cover dressed as the era's medium. */
    static Bitmap eraArt(Bitmap cover, int era, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float u = size / 100f;
        RectF r = new RectF();
        switch (era) {
            case 0:       // jukebox: rust arch, the cover in the glowing window, a grille below
                p.setColor(0xFFB5271E);
                r.set(14 * u, 4 * u, 86 * u, 96 * u);
                c.drawRoundRect(r, 36 * u, 36 * u, p);
                coverInto(c, cover, new RectF(24 * u, 14 * u, 76 * u, 62 * u), 26 * u);
                p.setColor(0xFF2A1A12);
                for (int i = 0; i < 4; i++) c.drawRect(24 * u, (68 + i * 7) * u, 76 * u, (71 + i * 7) * u, p);
                break;
            case 1:       // vinyl: black disc, grooves, the cover as the label
                p.setColor(0xFF111110);
                c.drawCircle(50 * u, 50 * u, 48 * u, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(0.8f * u);
                p.setColor(0xFF3A3A38);
                for (int i = 0; i < 5; i++) c.drawCircle(50 * u, 50 * u, (28 + i * 4) * u, p);
                p.setStyle(Paint.Style.FILL);
                coverInto(c, cover, new RectF(28 * u, 28 * u, 72 * u, 72 * u), 22 * u);
                p.setColor(0xFFF4F2EC);
                c.drawCircle(50 * u, 50 * u, 2.5f * u, p);
                break;
            case 2:       // cassette: the cover in the window, two reels
                p.setColor(0xFF2A2A28);
                r.set(2 * u, 18 * u, 98 * u, 82 * u);
                c.drawRoundRect(r, 6 * u, 6 * u, p);
                coverInto(c, cover, new RectF(8 * u, 24 * u, 92 * u, 60 * u), 3 * u);
                p.setColor(0xCC111110);
                c.drawCircle(32 * u, 42 * u, 8 * u, p);
                c.drawCircle(68 * u, 42 * u, 8 * u, p);
                p.setColor(0xFF6F6E69);
                c.drawRect(30 * u, 66 * u, 70 * u, 78 * u, p);
                break;
            case 3:       // CD: the cover on a disc with a rainbow rim and the hole
                coverInto(c, cover, new RectF(2 * u, 2 * u, 98 * u, 98 * u), 48 * u);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(5 * u);
                p.setShader(new android.graphics.SweepGradient(50 * u, 50 * u,
                    new int[]{0xFFD8D8D2, 0xFF9AD0FF, 0xFFFFB3E6, 0xFFFFF0A0, 0xFFB8F0C0, 0xFFD8D8D2}, null));
                c.drawCircle(50 * u, 50 * u, 46 * u, p);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFD8D8D2);
                c.drawCircle(50 * u, 50 * u, 11 * u, p);
                p.setColor(0xFF111110);
                c.drawCircle(50 * u, 50 * u, 5 * u, p);
                break;
            default:      // floppy: blue disk, metal shutter, the cover as the label
                p.setColor(0xFF00305F);
                r.set(4 * u, 4 * u, 96 * u, 96 * u);
                c.drawRoundRect(r, 4 * u, 4 * u, p);
                p.setColor(0xFFC4C4BE);
                c.drawRect(26 * u, 4 * u, 74 * u, 32 * u, p);
                p.setColor(0xFF2A2A28);
                c.drawRect(56 * u, 8 * u, 66 * u, 28 * u, p);
                p.setColor(0xFFF4F2EC);
                c.drawRect(14 * u, 40 * u, 86 * u, 94 * u, p);
                coverInto(c, cover, new RectF(18 * u, 44 * u, 82 * u, 90 * u), 2 * u);
                break;
        }
        return out;
    }

    /** The cover centre-cropped into a rounded rectangle (rust if there is none). */
    private static void coverInto(Canvas c, Bitmap cover, RectF into, float radius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (cover == null || cover.isRecycled()) {
            p.setColor(0xFFB5271E);
            c.drawRoundRect(into, radius, radius, p);
            return;
        }
        BitmapShader sh = new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float s = Math.max(into.width() / cover.getWidth(), into.height() / cover.getHeight());
        Matrix mx = new Matrix();
        mx.setScale(s, s);
        mx.postTranslate(into.centerX() - cover.getWidth() * s / 2, into.centerY() - cover.getHeight() * s / 2);
        sh.setLocalMatrix(mx);
        p.setShader(sh);
        c.drawRoundRect(into, radius, radius, p);
    }

    /** The notice's buttons: they steer the session that's playing (or paused) right now. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            MediaController mc = current(c);
            if (mc == null) return;
            MediaController.TransportControls t = mc.getTransportControls();
            PlaybackState st = mc.getPlaybackState();
            switch (a.substring(a.lastIndexOf('_') + 1)) {
                case "prev":
                    t.skipToPrevious();
                    break;
                case "next":
                    t.skipToNext();
                    break;
                case "toggle":
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause();
                    else t.play();
                    break;
                case "like":
                    if (st != null) {
                        for (PlaybackState.CustomAction ca : st.getCustomActions()) {
                            if (isLike(ca)) {
                                t.sendCustomAction(ca, null);
                                break;
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        private static MediaController current(Context c) {
            try {
                MediaSessionManager sm = c.getSystemService(MediaSessionManager.class);
                List<MediaController> list = sm.getActiveSessions(new ComponentName(c, MediaListener.class));
                MediaController paused = null;
                for (MediaController mc : list) {
                    PlaybackState s = mc.getPlaybackState();
                    if (s == null) continue;
                    if (s.getState() == PlaybackState.STATE_PLAYING) return mc;
                    if (paused == null && s.getState() == PlaybackState.STATE_PAUSED) paused = mc;
                }
                return paused;
            } catch (SecurityException | NullPointerException e) {
                return null;
            }
        }
    }
}
