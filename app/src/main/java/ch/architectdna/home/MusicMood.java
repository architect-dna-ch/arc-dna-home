package ch.architectdna.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Guesses your mood from what you listen to – only a guess, and it says so.
 *
 * Per track: how energetic it is (tempo and loudness from the music card's beat detection when it
 * was on screen, otherwise words in the title / artist like "chill", "phonk", "remix"), how
 * bright or dark it reads (words like "sad", "enttäuscht" vs. "sommer", "party"; your star rating
 * and likes count too) and whether you skipped it. Every ~25 min of listening the last half hour
 * becomes one guess (e.g. "melancholisch", "aufgedreht", "unruhig – suchst was"), written into the
 * Stimmung log as an "auto" entry that the patterns weigh lighter than what you tap yourself.
 */
final class MusicMood {

    private static final String PREFS = "musicmood";

    private static final String[] SAD = {"sad", "traurig", "tears", "träne", "cry", "wein", "alone", "allein", "lonely", "einsam",
        "broken", "kaputt", "enttäusch", "schmerz", "pain", "hurt", "goodbye", "abschied", "lost", "verloren", "dark", "dunkel",
        "rain", "regen", "miss you", "vermiss", "dead", "tot", "hate", "hass", "psycho", "revenge", "rache", "cold", "kalt", "ghost",
        "empty", "leer", "lonely", "night", "nacht", "sorry", "why", "warum"};
    private static final String[] HAPPY = {"happy", "glück", "love", "liebe", "sun", "sonne", "summer", "sommer", "party", "dance",
        "tanz", "smile", "lächel", "fly", "free", "frei", "gold", "paradise", "paradies", "vibes", "good", "joy", "freude", "baby",
        "kiss", "kuss", "fun", "spaß", "beach", "strand", "celebrate", "feier", "best", "beautiful", "schön"};
    private static final String[] CALM = {"lofi", "lo-fi", "chill", "ambient", "piano", "sleep", "schlaf", "relax", "acoustic",
        "akustik", "focus", "study", "slow", "calm", "ruhe", "meditation", "jazz", "soft", "beats to"};
    private static final String[] HYPE = {"drill", "trap", "techno", "rave", "hardstyle", "phonk", "remix", "sped up", "hype",
        "funk", "mc ", "dj ", "bass", "drop", "turn up", "banger", "club", "type beat", "rap", "hard"};

    static final String[][] LABELS = {
        {"aufgewühlt", "😤", "2"}, {"melancholisch", "😔", "2"}, {"unruhig – suchst was", "🔀", "3"},
        {"ruhig, fokussiert", "😌", "3"}, {"zufrieden", "🙂", "4"}, {"aufgedreht", "🔥", "5"}, {"neutral", "😐", "3"}};

    private MusicMood() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ---------- live audio from the music card (only while it's on screen) ---------- */

    private static int beats;
    private static long firstBeat, lastBeat;
    private static float levelSum;
    private static int levelN;
    private static float briSum;        // treble share (hi / total): bright vs. bass-heavy
    private static int briN;

    static synchronized void beat() {
        long now = System.currentTimeMillis();
        if (firstBeat == 0) firstBeat = now;
        lastBeat = now;
        beats++;
    }

    static synchronized void level(float l) {
        if (l < 0.02f) return;
        levelSum += l;
        levelN++;
    }

    /** Low / mid / high band energy from the spectrum: how bright the track is. */
    static synchronized void timbre(float lo, float mid, float hi) {
        float t = lo + mid + hi;
        if (t < 0.05f) return;
        briSum += hi / t;
        briN++;
    }

    /* ---------- track changes (from the media listener, always running) ---------- */

    static synchronized void onUpdate(Context c, boolean playing, String title, String artist) {
        SharedPreferences p = prefs(c);
        String key = (title == null ? "" : title) + "\u0001" + (artist == null ? "" : artist);
        String cur = p.getString("cur", "");
        long now = System.currentTimeMillis();
        if (title == null || key.equals(cur)) return;
        // The previous track is done: score it.
        if (!cur.isEmpty()) {
            long started = p.getLong("curAt", now), heard = now - started;
            String[] ta = cur.split("\u0001", -1);
            if (heard > 4000) addTrack(c, ta[0], ta.length > 1 ? ta[1] : "", heard);
        }
        p.edit().putString("cur", key).putLong("curAt", now).apply();
        beats = 0;
        firstBeat = lastBeat = 0;
        levelSum = 0;
        levelN = 0;
        briSum = 0;
        briN = 0;
        maybeGuess(c);
    }

    private static int hits(String s, String[] words) {
        int n = 0;
        for (String w : words) if (s.contains(w)) n++;
        return n;
    }

    private static void addTrack(Context c, String title, String artist, long heardMs) {
        String s = (" " + title + " " + artist + " ").toLowerCase(Locale.GERMAN);
        float valence = Math.max(-1, Math.min(1, (hits(s, HAPPY) - hits(s, SAD)) * 0.45f));
        int stars = MusicStats.rating(c, title, artist);
        if (stars > 0) valence += (stars - 3) * 0.15f;
        if (MusicStats.isFavourite(c, title, artist)) valence += 0.1f;
        float energy = 0.5f + hits(s, HYPE) * 0.18f - hits(s, CALM) * 0.25f;
        boolean audio = beats >= 6 && lastBeat > firstBeat;
        float brightness = briN > 0 ? briSum / briN : -1;
        if (audio) {
            float bpm = (beats - 1) * 60000f / (lastBeat - firstBeat);
            float lvl = levelN > 0 ? levelSum / levelN : 0.3f;
            // Louder and faster = more energetic; spread wider than before so it isn't stuck mid.
            float a = Math.max(0, Math.min(1, (bpm - 80) / 90f * 0.55f + lvl * 1.1f - 0.1f));
            energy = 0.25f * energy + 0.75f * a;
        }
        energy = Math.max(0, Math.min(1, energy));
        // Brightness is a real second axis: bright, trebly music reads lighter, bass-only darker.
        if (brightness >= 0) valence += (brightness - 0.33f) * 1.4f;
        valence = Math.max(-1, Math.min(1, valence));
        boolean skipped = heardMs < 30_000;
        try {
            JSONArray a = new JSONArray(prefs(c).getString("tracks", "[]"));
            a.put(new JSONObject().put("at", System.currentTimeMillis()).put("e", energy).put("v", valence).put("skip", skipped)
                .put("audio", audio).put("bri", brightness).put("t", title));
            while (a.length() > 200) a.remove(0);
            prefs(c).edit().putString("tracks", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** {label index, energy, valence, tracks, skip rate} for the last 30 minutes, or null with too little music. */
    static float[] current(Context c) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("tracks", "[]"));
            long since = System.currentTimeMillis() - 30 * 60_000L;
            float e = 0, v = 0, n = 0, skips = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("at") < since) continue;
                boolean sk = o.optBoolean("skip");
                if (sk) skips++;
                float w = sk ? 0.3f : 1f;                 // a skipped song says less about you than one you kept
                e += (float) o.optDouble("e", 0.5) * w;
                v += (float) o.optDouble("v", 0) * w;
                n += w;
            }
            int count = 0;
            for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optLong("at") >= since) count++;
            if (count < 3 || n <= 0) return null;
            e /= n;
            v /= n;
            float skipRate = skips / count;
            int label;
            if (skipRate > 0.55f) label = 2;                         // restless, searching
            else if (v > 0.22f) label = e > 0.58f ? 5 : 4;           // bright: hyped / content
            else if (v < -0.22f) label = e > 0.58f ? 0 : 1;          // dark: agitated / melancholic
            else {
                // Weak brightness signal: let energy alone decide, leaving only a thin neutral band.
                if (e > 0.64f) label = 5;                            // aufgedreht
                else if (e > 0.52f) label = 4;                       // zufrieden / lebendig
                else if (e < 0.34f) label = 3;                       // ruhig, fokussiert
                else if (e < 0.46f) label = v < 0 ? 1 : 3;           // low + a little dark = melancholic
                else label = 6;                                      // genuinely flat
            }
            return new float[]{label, e, v, count, skipRate};
        } catch (Exception ex) {
            return null;
        }
    }

    /** Every 25 min of music at most: the guess goes into the Stimmung log (as "auto", weighed lighter). */
    private static void maybeGuess(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("guessAt", 0) < 25 * 60_000L) return;
        float[] g = current(c);
        if (g == null) return;
        String[] l = LABELS[(int) g[0]];
        try {
            SharedPreferences mp = c.getSharedPreferences("mood", Context.MODE_PRIVATE);
            JSONArray log = new JSONArray(mp.getString("log", "[]"));
            log.put(new JSONObject().put("at", now).put("m", Integer.parseInt(l[2])).put("n", "🎵 geschätzt: " + l[0]).put("auto", true));
            mp.edit().putString("log", log.toString()).apply();
        } catch (Exception ignored) {
        }
        p.edit().putLong("guessAt", now).putString("last", l[1] + " " + l[0]).apply();
        DiagLog.log("music mood guess: " + l[0] + String.format(Locale.ROOT, " (e %.2f, v %.2f, %d tracks, skips %.0f%%)", g[1], g[2], (int) g[3], g[4] * 100));
    }

    /** "🎵 klingt nach melancholisch 😔" for the music card, or null. */
    static String line(Context c) {
        float[] g = current(c);
        if (g == null) return null;
        String[] l = LABELS[(int) g[0]];
        return "🎵 KLINGT NACH: " + l[0].toUpperCase(Locale.GERMAN) + " " + l[1];
    }

    static int guessedMood(Context c) {
        float[] g = current(c);
        return g == null ? -1 : Integer.parseInt(LABELS[(int) g[0]][2]);
    }
}
