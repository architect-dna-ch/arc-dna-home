package ch.architectdna.stats;

import java.util.HashMap;
import java.util.Locale;

/**
 * Language: German on a German phone, English everywhere else. The source keeps the German text;
 * L.t() looks up the English version (generated table below). Unknown text stays as it is.
 */
final class L {

    /** Read each time: Android 13+ can switch an app's language while it runs. */
    static boolean de() { return "de".equals(Locale.getDefault().getLanguage()); }
    private static HashMap<String, String> map;

    private L() { }

    static String t(String de) {
        if (de == null || de()) return de;
        HashMap<String, String> m = map;
        if (m == null) m = load();
        String en = m.get(de);
        return en == null ? de : en;
    }

    private static synchronized HashMap<String, String> load() {
        if (map != null) return map;
        HashMap<String, String> m = new HashMap<>(4096);
        for (String chunk : DATA) {
            for (String e : chunk.split("\u0001")) {
                int i = e.indexOf('\u0002');
                if (i > 0) m.put(e.substring(0, i), e.substring(i + 1));
            }
        }
        map = m;
        return m;
    }

    private static final String[] DATA = {
        "Aktualisiert auf \u0002Updated to \u0001",
        " GESCHÄTZT\u0002 ESTIMATED\u0001",
        "Baut die neue Version\u0002Building the new version\u0001",
        "Bereit. Android installiert.\u0002Ready. Android is installing.\u0001",
        "Lädt die neue Version\u0002Loading the new version\u0001",
        "Lädt nur die Änderungen\u0002Loading only the changes\u0001",
        "MOBIL\u0002MOBILE\u0001",
        "NETZ\u0002NETWORK\u0001",
        "NOCH \u0002LEFT \u0001",
        "Später nochmal.\u0002Try again later.\u0001",
        "Verbindet …\u0002Connecting …\u0001",
        "— UPDATE ABGEBROCHEN\u0002— UPDATE CANCELLED\u0001",
        "Updates: Token einmal einfügen →\u0002Updates: paste the token once →\u0001",
        "Aktuell · 0.\u0002Up to date · 0.\u0001",
        "Suche Updates …\u0002Checking for updates …\u0001",
        "Token ungültig oder ohne Zugriff (\u0002Token invalid or without access (\u0001",
        "Update bereit · Installieren erlauben →\u0002Update ready · allow installing →\u0001",
        "Lädt Update 0.\u0002Downloading update 0.\u0001",
        "Installiert 0.\u0002Installing 0.\u0001",
        "Update-Prüfung fehlgeschlagen\u0002Update check failed\u0001",
        "Update abgebrochen\u0002Update cancelled\u0001",
        "Updates einrichten\u0002Set up updates\u0001",
        "GitHub → Fine-grained token, nur „arc-dna-home“, Contents: Read-only. Einmal einfügen, dann aktualisiert sich die App selbst.\u0002GitHub → fine-grained token, only \"arc-dna-home\", Contents: read-only. Paste it once, then the app updates itself.\u0001",
        "Speichern & prüfen\u0002Save & check\u0001",
        "Token auf GitHub erstellen\u0002Create token on GitHub\u0001",
        "SPEICHER\u0002STORAGE\u0001",
        "Apps suchen\u0002Search apps\u0001",
        "Updates einrichten\u0002Set up updates\u0001",
        "Speichern & prüfen\u0002Save & check\u0001",
        "Token auf GitHub erstellen\u0002Create token on GitHub\u0001",
        "AKKU\u0002BATTERY\u0001",
        "HEUTE\u0002TODAY\u0001",
        " am Bildschirm\u0002 on screen\u0001",
        "Peak um %02d:00 · jetzt (%02d:00) rot\u0002Peak at %02d:00 · now (%02d:00) red\u0001",
        "Noch keine Nutzungsdaten.\u0002No usage data yet.\u0001",
        "Nutzungszugriff erlauben →\u0002Allow usage access →\u0001",
        "VORSCHLÄGE FÜR JETZT\u0002SUGGESTIONS FOR NOW\u0001",
        "🔊 Musik aufs Tablet\u0002🔊 Music to the tablet\u0001",
        "Dafür ARC-DNA Home installieren.\u0002Install ARC-DNA Home for this.\u0001",
        "Suchen   ·   ↑ alle Apps   ·   ← Statistik\u0002Search   ·   ↑ all apps   ·   ← stats\u0001",
        "STATISTIK   ·   → zurück\u0002STATS   ·   → back\u0001",
        "Nutzungszugriff fehlt\u0002Usage access missing\u0001",
        "Damit das Zuhause deine Muster modellieren kann, braucht es den Nutzungszugriff. Die Zahlen bleiben auf diesem Gerät.\u0002For home to model your patterns it needs usage access. The numbers stay on this device.\u0001",
        "Zugriff erlauben\u0002Allow access\u0001",
        "MODELL — AUSTÜFTELN\u0002MODEL — FIGURE IT OUT\u0001",
        "Häufigkeit\u0002Frequency\u0001",
        "Kürzlich\u0002Recent\u0001",
        "Tageszeit\u0002Time of day\u0001",
        "HEUTE AM BILDSCHIRM\u0002TODAY ON SCREEN\u0001",
        "NUTZUNG NACH STUNDE · 7 TAGE\u0002USAGE BY HOUR · 7 DAYS\u0001",
        "Gute Nacht.\u0002Good night.\u0001",
        "Guten Morgen.\u0002Good morning.\u0001",
        "Guten Tag.\u0002Good afternoon.\u0001",
        "Guten Abend.\u0002Good evening.\u0001",
        " heute\u0002 today\u0001",
        "Noch keine Nutzungsdaten\u0002No usage data yet\u0001",
        "noch keine Daten\u0002no data yet\u0001",
        "um diese Zeit meistens\u0002usually at this time\u0001",
        "gerade erst offen gehabt\u0002just had it open\u0001",
        "dein Dauer-Begleiter\u0002your constant companion\u0001",
        "Suche Updates …\u0002Looking for updates …\u0001",
        "Update abgebrochen\u0002Update cancelled\u0001",
        "Tor Browser startet, aber kein VPN ist verbunden. Dein Netz sieht, dass du Tor nutzt.\u0002Tor Browser is starting, but no VPN is connected. Your network can see that you use Tor.\u0001",
        "🛡  VPN verbinden\u0002🛡  Connect VPN\u0001",
        "Trotzdem öffnen\u0002Open anyway\u0001",
        "Ungeschützte Verbindung\u0002Unprotected connection\u0001"
    };
}
