package ch.architectdna.home;

import android.content.SharedPreferences;

/**
 * The looks you can pick (Einstellungen → Startseite → Theme), for the home and the ARC-DNA Live
 * wallpaper (home + lock screen) alike. A new theme = one more entry in ALL.
 */
final class Themes {

    // Palette slots.
    static final int BG = 0, INK = 1, INK2 = 2, INK3 = 3, ACCENT = 4, CARD = 5, PANEL = 6, LINE = 7, GLOW_A = 8, GLOW_B = 9, GLOW_C = 10;

    static final class Theme {
        final String id, name, desc;
        /** Draws the glow backdrop (home) and glows (wallpaper); the classic look keeps the plain paper. */
        final boolean glow;
        final int[] day, night;

        Theme(String id, String name, String desc, boolean glow, int[] day, int[] night) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.glow = glow;
            this.day = day;
            this.night = night;
        }

        int[] pal(boolean dark) {
            return dark ? night : day;
        }
    }

    static final Theme[] ALL = {
        new Theme("spicy", "Spicy", "Beige, Pink, Grün · Glühen beim Scrollen", true,
            new int[]{0xFFF2E8D5, 0xFF13261D, 0xFF46594D, 0xFF8F9A8A, 0xFFFF3D7F, 0xE6FFF8EC, 0xF2FFF8EC, 0xFFE3D3B6, 0xFFFF3D7F, 0xFF1FB86E, 0xFFFFB3CB},
            new int[]{0xFF0E1A14, 0xFFF2E8D5, 0xFFBFC9BA, 0xFF718273, 0xFFFF5C93, 0xE0152A20, 0xF2152A20, 0xFF2A4535, 0xFFFF5C93, 0xFF3DDC8F, 0xFF7A2E4C}),
        new Theme("classic", "Klassisch", "Tinte & Rost · das Original", false,
            new int[]{0xFFFAFAF8, 0xFF1A1A1A, 0xFF5A5A58, 0xFF9A9A98, 0xFFB5271E, 0xD9FAFAF8, 0xF2FAFAF8, 0xFFE0E0D8, 0, 0, 0},
            new int[]{0xFF121211, 0xFFECEBE6, 0xFFA9A8A2, 0xFF6F6E69, 0xFFC9402F, 0xD9121211, 0xF2121211, 0xFF2C2C29, 0, 0, 0}),
        new Theme("neon", "Neon", "Mitternacht, Cyan & Magenta · immer dunkel", true,
            new int[]{0xFF0B0B14, 0xFFEDEBFF, 0xFFA9A6D6, 0xFF5E5B8A, 0xFF00E5FF, 0xE0151530, 0xF2151530, 0xFF2A2A50, 0xFFFF2BD6, 0xFF00E5FF, 0xFF7A3CFF},
            new int[]{0xFF0B0B14, 0xFFEDEBFF, 0xFFA9A6D6, 0xFF5E5B8A, 0xFF00E5FF, 0xE0151530, 0xF2151530, 0xFF2A2A50, 0xFFFF2BD6, 0xFF00E5FF, 0xFF7A3CFF}),
        new Theme("matcha", "Matcha", "Creme, Matcha & Terrakotta · ruhig", true,
            new int[]{0xFFF4F1E6, 0xFF2B3A1F, 0xFF5B6B4B, 0xFF9AA38C, 0xFFC8643B, 0xE6FFFDF4, 0xF2FFFDF4, 0xFFDCD6C2, 0xFFA8C686, 0xFFE8B98A, 0xFFD6E5B8},
            new int[]{0xFF161B12, 0xFFEEF0E2, 0xFFB8BFA6, 0xFF707A60, 0xFFE08A5C, 0xE01E2618, 0xF21E2618, 0xFF2E3A24, 0xFF5E7E3A, 0xFFA0603A, 0xFF3A4A2A}),
        new Theme("sunset", "Sonnenuntergang", "Pfirsich, Koralle & Violett · warm", true,
            new int[]{0xFFFFF1E6, 0xFF3A1E2E, 0xFF7A4258, 0xFFB08A98, 0xFFF2563B, 0xFFFFF8F0, 0xF2FFF8F0, 0xFFF3D9C8, 0xFFFF7A45, 0xFFB0439B, 0xFFFFC27A},
            new int[]{0xFF1C1018, 0xFFFFE8DC, 0xFFCFA6B4, 0xFF8A6472, 0xFFFF7A5C, 0xE02A1522, 0xF22A1522, 0xFF462432, 0xFFFF7A45, 0xFFC055A8, 0xFFB85A2E}),
    };

    static Theme current(SharedPreferences prefs) {
        String id = prefs.getString("theme", "spicy");
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return ALL[0];
    }

    private Themes() {
    }
}
