package ch.architectdna.home;

import android.content.SharedPreferences;

/**
 * The tutorial that grows with you. ARC-DNA has a lot – apps, buddies, gestures, games, easter
 * eggs – and all of it at once on day one would drown a new user. So the coach reveals ONE tip at
 * a time, at most one a day, in order: the essentials first, the playful and hidden things only
 * after you've settled in. Each tip has a "not before day N" gate, so even a power user meets the
 * secrets slowly. Seen tips are remembered; nothing repeats.
 */
final class Coach {

    static final class Tip {
        final String id, title, body;
        final int minDay;        // days since install before this may appear

        Tip(String id, int minDay, String title, String body) {
            this.id = id;
            this.minDay = minDay;
            this.title = title;
            this.body = body;
        }
    }

    // Order = teaching order. minDay paces the reveal even for someone who opens it constantly.
    static final Tip[] TIPS = {
        new Tip("welcome", 0, "Willkommen 👋",
            "Das ist dein Zuhause-Bildschirm. Wisch hoch und runter zum Blättern, tippe eine App zum Öffnen. Mehr zeig ich dir nach und nach – kein Stress."),
        new Tip("apps", 0, "Deine Apps",
            "Tippe „▦ Apps“ für alle Apps, „Zuletzt“ für die letzten. Lange auf eine freie Stelle = Mediathek."),
        new Tip("sos", 0, "🆘 wenn's zu viel wird",
            "Oben der 🆘-Knopf: hält Grübel- und Zwangsschleifen an und führt dich Schritt für Schritt raus. Immer erreichbar, auch im ruhigen Modus."),
        new Tip("buddy", 1, "Dein Buddy unten links",
            "Einmal tippen = er sagt was. Doppelt tippen = sein eigenes Spiel/Welt. Gedrückt halten = mit ihm schreiben."),
        new Tip("fab", 1, "Der Würfel unten rechts",
            "Tippt auf den Würfel öffnet das Menü: Claude, Mediathek, Aufräumen und die Ruheinsel. Lange halten = direkt zu Claude."),
        new Tip("mood", 2, "Wie geht's dir?",
            "In Flexplan oben stimmst du kurz deine Stimmung ab. Mit der Zeit lernt ARC-DNA, wann dir was leichter fällt, und legt Schweres in gute Zeiten."),
        new Tip("theme", 2, "Ein Look: ARC·DNA",
            "ARC-DNA trägt jetzt eine einzige, ruhige Handschrift – Papier, Tinte, Terrakotta – auf Home und Sperrbildschirm. Nichts einzustellen, nichts blinkt."),
        new Tip("daynight", 3, "Tag/Nacht wischen",
            "Wisch quer über den Bildschirm: links = dunkel, rechts = hell. Schnell und ohne Menü."),
        new Tip("kompass", 3, "Kompass & Flexplan",
            "Zwei Werkzeuge für schwere Tage: der Kompass (Anker, „Tut mir das gut?“, Zweifel parken) und Flexplan (kleine Schritte, fragt „jetzt?“ statt zu nerven)."),
        new Tip("head", 4, "Mit dem Kopf scrollen",
            "In der Mediathek kannst du pro App das Kopf-Nicken zum Scrollen einschalten – die Frontkamera liest nur die Neigung, nichts wird gespeichert."),
        new Tip("peek", 5, "Das Halb-Zifferblatt oben",
            "Oben am Rand: tippen wechselt die Anzeige (Akku, Tag, Woche, Wecker). Gedrückt halten und drehen = Lünette wie bei einer Uhr."),
        new Tip("energy", 6, "Energie-Center",
            "Wisch von der oberen rechten Ecke nach unten: Akku, Schnellschalter und was gerade Strom zieht."),
        new Tip("gallery", 7, "Mehr Buddies",
            "Einstellungen ⚙ → Startseite → Buddy: eine Galerie. Panda, Eisbär, Eule und Drache schaltest du mit Zeit zusammen oder Münzen frei."),
        new Tip("cube", 9, "Geheimnis: den Würfel drehen",
            "Zieh mit dem Finger einen Kreis auf dem Bildschirm – der grosse Würfel im Wallpaper dreht sich mit."),
        new Tip("sweep", 11, "Geheimnis: Aufräumen ☕",
            "Wenn's zu voll wird: im Würfel-Menü „Aufräumen“. Ein Besen fegt alles weg bis auf 3 Apps, Ordner, Wetter und Musik. „Alles zurück“ holt es wieder."),
        new Tip("dino", 14, "Geheimnis: Besuch 🦖",
            "Ab und zu kommt ein kleiner Dino vorbei – und wenn du wegschaust, ein schwarzes Loch. Nur ein Spass, der dich kurz aus dem Kopf holt."),
        new Tip("race", 18, "Geheimnis: Rennen 🏎️",
            "Mit dem F1-Racer als Buddy und offenem F1-Board fährt eine Rennstrecke über den Bildschirm – tippe die Pille unter der Kamera und fahr selbst."),
    };

    private static SharedPreferences p(android.content.Context c) {
        return c.getSharedPreferences("coach", android.content.Context.MODE_PRIVATE);
    }

    private static int daysSinceInstall(SharedPreferences p) {
        long install = p.getLong("installAt", 0);
        if (install == 0) return 0;
        return (int) ((System.currentTimeMillis() - install) / 86_400_000L);
    }

    /** First unseen tip whose day-gate has passed, or null. At most one new tip per calendar day. */
    static Tip next(android.content.Context c) {
        SharedPreferences p = p(c);
        if (p.getLong("installAt", 0) == 0) p.edit().putLong("installAt", System.currentTimeMillis()).apply();
        if (p.getBoolean("off", false)) return null;
        long today = System.currentTimeMillis() / 86_400_000L;
        if (p.getLong("shownDay", -1) == today) return null;      // one a day, no flooding
        int day = daysSinceInstall(p);
        for (Tip t : TIPS) {
            if (day < t.minDay) continue;
            if (p.getBoolean("seen_" + t.id, false)) continue;
            return t;
        }
        return null;
    }

    static void markSeen(android.content.Context c, String id) {
        long today = System.currentTimeMillis() / 86_400_000L;
        p(c).edit().putBoolean("seen_" + id, true).putLong("shownDay", today).apply();
    }

    /** For the settings list: every tip already unlocked, so it can be read again any time. */
    static java.util.List<Tip> unlocked(android.content.Context c) {
        SharedPreferences p = p(c);
        int day = daysSinceInstall(p);
        java.util.List<Tip> out = new java.util.ArrayList<>();
        for (Tip t : TIPS) if (day >= t.minDay || p.getBoolean("seen_" + t.id, false)) out.add(t);
        return out;
    }

    static int seenCount(android.content.Context c) {
        SharedPreferences p = p(c);
        int n = 0;
        for (Tip t : TIPS) if (p.getBoolean("seen_" + t.id, false)) n++;
        return n;
    }

    static boolean isOff(android.content.Context c) {
        return p(c).getBoolean("off", false);
    }

    static void setOff(android.content.Context c, boolean off) {
        p(c).edit().putBoolean("off", off).apply();
    }

    /** Let the user start over (show all tips again from the beginning). */
    static void reset(android.content.Context c) {
        SharedPreferences.Editor e = p(c).edit();
        for (Tip t : TIPS) e.remove("seen_" + t.id);
        e.remove("shownDay").putBoolean("off", false).apply();
    }

    private Coach() {
    }
}
