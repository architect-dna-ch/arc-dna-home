package ch.architectdna.home;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sonne & Welt – where you are right now in relation to the sun: the sky with the sun's path for
 * today, day/night, the season and where we are in the year, and what all of it means for your
 * sleep, light and health. Everything is computed on the phone from your location – no network.
 */
public class SunActivity extends Activity {

    private double lat = 46.948, lon = 7.447;
    private int bg, card, line, ink, ink2, ink3, accent;
    private SkyView sky;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int[] pl = Themes.current(getSharedPreferences("home", MODE_PRIVATE)).pal(night);
        bg = pl[Themes.BG] | 0xFF000000;
        ink = pl[Themes.INK];
        ink2 = pl[Themes.INK2];
        ink3 = pl[Themes.INK3];
        accent = pl[Themes.ACCENT];
        card = pl[Themes.PANEL] | 0xFF000000;
        line = pl[Themes.LINE];
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (android.graphics.Color.luminance(bg) > 0.5f && android.os.Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int bits = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(bits, bits);
        }
        locate();

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(44), dp(20), dp(36));
        col.setOnApplyWindowInsetsListener((v, in) -> {
            int top = in.getSystemWindowInsetTop(), bot = in.getSystemWindowInsetBottom();
            v.setPadding(dp(20), top + dp(16), dp(20), bot + dp(24));
            return in;
        });
        sv.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView tag = mono(11, accent);
        tag.setText("— SONNE & WELT");
        tag.setLetterSpacing(0.2f);
        head.addView(tag, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = text(20, ink, true);
        x.setText("✕");
        x.setPadding(dp(12), dp(4), dp(4), dp(4));
        x.setOnClickListener(v -> finish());
        head.addView(x);
        col.addView(head);

        sky = new SkyView();
        col.addView(sky, new LinearLayout.LayoutParams(-1, dp(260)));

        col.addView(statsCard());
        col.addView(yearCard());
        for (View c : infoCards()) col.addView(c);

        setContentView(sv);
    }

    @SuppressWarnings("MissingPermission")
    private void locate() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                LocationManager lm = getSystemService(LocationManager.class);
                Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (l == null) l = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (l != null) {
                    lat = l.getLatitude();
                    lon = l.getLongitude();
                }
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 61);
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == 61 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            locate();
            recreate();
        }
    }

    /* ================= Solar maths (NOAA-style, local) ================= */

    private static class Sun {
        double elevation, azimuth, declination;
        double sunriseMin = -1, sunsetMin = -1, noonMin, dayLenMin;
        boolean polarDay, polarNight;
    }

    private Sun solar(long when) {
        Sun s = new Sun();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);
        int doy = c.get(Calendar.DAY_OF_YEAR);
        TimeZone tz = c.getTimeZone();
        double tzHours = tz.getOffset(when) / 3600000.0;
        // Fractional year (radians).
        double g = 2 * Math.PI / 365.0 * (doy - 1 + (c.get(Calendar.HOUR_OF_DAY) - 12) / 24.0);
        double eqtime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
            - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g)
            - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
            - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        s.declination = Math.toDegrees(decl);
        double latR = Math.toRadians(lat);
        // Current elevation / azimuth.
        double mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0;
        double tst = mins + eqtime + 4 * lon - 60 * tzHours;              // true solar time (min)
        double ha = Math.toRadians(tst / 4 - 180);                        // hour angle
        double cosZen = Math.sin(latR) * Math.sin(decl) + Math.cos(latR) * Math.cos(decl) * Math.cos(ha);
        cosZen = Math.max(-1, Math.min(1, cosZen));
        double zen = Math.acos(cosZen);
        s.elevation = 90 - Math.toDegrees(zen);
        double az = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
            (Math.sin(decl) - Math.sin(latR) * Math.cos(zen)) / (Math.cos(latR) * Math.sin(zen) + 1e-9)))));
        s.azimuth = ha > 0 ? (540 - az) % 360 : az;
        // Sunrise / sunset hour angle (−0.833° for refraction + radius).
        double cosH = (Math.cos(Math.toRadians(90.833)) - Math.sin(latR) * Math.sin(decl)) / (Math.cos(latR) * Math.cos(decl));
        s.noonMin = 720 - 4 * lon - eqtime + 60 * tzHours;
        if (cosH > 1) {
            s.polarNight = true;
            s.dayLenMin = 0;
        } else if (cosH < -1) {
            s.polarDay = true;
            s.dayLenMin = 1440;
        } else {
            double ha0 = Math.toDegrees(Math.acos(cosH));
            s.sunriseMin = s.noonMin - ha0 * 4;
            s.sunsetMin = s.noonMin + ha0 * 4;
            s.dayLenMin = ha0 * 8;
        }
        return s;
    }

    /* ================= Sky view ================= */

    private final class SkyView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SkyView() {
            super(SunActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            Sun s = solar(System.currentTimeMillis());
            float elev = (float) s.elevation;
            // Sky colour by the sun's height: night → dawn → day → dusk.
            int top, bot;
            if (elev > 6) {
                top = 0xFF2E6DB4;
                bot = 0xFFBFE0FF;
            } else if (elev > -6) {                      // twilight
                top = 0xFF3A3A6A;
                bot = 0xFFF2945A;
            } else {
                top = 0xFF0B1026;
                bot = 0xFF1C2448;
            }
            p.setShader(new LinearGradient(0, 0, 0, h * 0.82f, top, bot, Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, w, h * 0.82f, p);
            p.setShader(null);
            // Stars at night.
            if (elev < -4) {
                p.setColor(0x99FFFFFF);
                java.util.Random r = new java.util.Random(42);
                for (int i = 0; i < 40; i++) c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.5f, dp(1 + r.nextFloat()), p);
            }
            // Horizon + ground.
            float hz = h * 0.82f;
            p.setColor(0xFF24351F);
            c.drawRect(0, hz, w, h, p);
            // The sun's arc today: map hour-angle to a half-circle from the horizon.
            float cx = w / 2, cy = hz, arcR = Math.min(w * 0.42f, h * 0.62f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(0x44FFFFFF);
            RectF arc = new RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
            c.drawArc(arc, 180, 180, false, p);
            // Sun position along the arc by its fraction of daylight.
            double frac;
            Calendar now = Calendar.getInstance();
            double nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            if (s.polarDay) frac = 0.5;
            else if (s.polarNight) frac = -1;
            else frac = (nowMin - s.sunriseMin) / Math.max(1, s.sunsetMin - s.sunriseMin);
            boolean up = frac >= 0 && frac <= 1;
            double ang = Math.PI * (1 - Math.max(0, Math.min(1, frac)));   // 180°→0°
            float sx = cx + (float) Math.cos(ang) * arcR;
            float syy = cy - (float) Math.sin(ang) * arcR * (up ? 1 : 0.0f);
            // Sunrise / sunset marks.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFF2B01F);
            c.drawCircle(cx - arcR, cy, dp(4), p);
            c.drawCircle(cx + arcR, cy, dp(4), p);
            if (up) {
                // Glow + sun.
                p.setShader(new RadialGradient(sx, syy, dp(40), 0x66FFD36B, 0x00FFD36B, Shader.TileMode.CLAMP));
                c.drawCircle(sx, syy, dp(40), p);
                p.setShader(null);
                p.setColor(0xFFFFC83D);
                c.drawCircle(sx, syy, dp(13), p);
            } else {
                // Moon below the horizon time.
                p.setColor(0xFFE8E8F0);
                c.drawCircle(cx, hz - arcR * 0.2f, dp(11), p);
                p.setColor(elev < -6 ? 0xFF0B1026 : 0xFF3A3A6A);
                c.drawCircle(cx + dp(5), hz - arcR * 0.2f - dp(3), dp(11), p);
            }
            // Labels.
            p.setColor(0xFFF4F2EC);
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(12));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.format(Locale.ROOT, "☀ %.0f°", elev), cx, h - dp(14), p);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("↑ " + hm(s.sunriseMin), dp(4), hz + dp(18), p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(hm(s.sunsetMin) + " ↓", w - dp(4), hz + dp(18), p);
            p.setTextAlign(Paint.Align.LEFT);
            postInvalidateDelayed(30_000);
        }
    }

    /* ================= Cards ================= */

    private View statsCard() {
        Sun s = solar(System.currentTimeMillis());
        LinearLayout b = cardBox();
        b.addView(cardTag("JETZT · " + String.format(Locale.GERMAN, "%.3f°, %.3f°", lat, lon)));
        b.addView(row("Sonnenhöhe", String.format(Locale.GERMAN, "%.1f°", s.elevation) + (s.elevation > 0 ? " über Horizont" : " unter Horizont")));
        b.addView(row("Richtung", compass(s.azimuth) + String.format(Locale.GERMAN, " (%.0f°)", s.azimuth)));
        b.addView(row("Sonnenaufgang", s.polarDay ? "—" : s.polarNight ? "—" : hm(s.sunriseMin)));
        b.addView(row("Sonnenuntergang", s.polarDay ? "—" : s.polarNight ? "—" : hm(s.sunsetMin)));
        b.addView(row("Höchststand", hm(s.noonMin)));
        b.addView(row("Tageslänge", s.polarNight ? "Polarnacht" : s.polarDay ? "Polartag (24 h)"
            : (int) (s.dayLenMin / 60) + " h " + String.format(Locale.ROOT, "%02d", (int) (s.dayLenMin % 60)) + " min"));
        // Trend: compare to yesterday.
        Sun y = solar(System.currentTimeMillis() - 86_400_000L);
        double diff = s.dayLenMin - y.dayLenMin;
        b.addView(row("Gegenüber gestern", (diff >= 0 ? "+" : "−") + Math.abs(Math.round(diff)) + " min Tageslicht " + (diff >= 0 ? "☀↑" : "🌙↓")));
        return b;
    }

    private View yearCard() {
        LinearLayout b = cardBox();
        Calendar c = Calendar.getInstance();
        int doy = c.get(Calendar.DAY_OF_YEAR);
        String season = season(doy);
        b.addView(cardTag("JAHRESZEIT · " + season.toUpperCase(Locale.GERMAN)));
        // A year ring with solstices/equinoxes and today's marker.
        b.addView(new YearRing(), new LinearLayout.LayoutParams(-1, dp(150)));
        b.addView(info(seasonInfo(doy)));
        return b;
    }

    private View[] infoCards() {
        Sun s = solar(System.currentTimeMillis());
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        // A "now" tip from the time of day.
        String tip;
        if (s.elevation > 0 && hour < 11) tip = "🌅 Jetzt ist die beste Zeit für Morgenlicht: 10–20 Min raus oder ans Fenster. Das stellt deine innere Uhr, hebt die Stimmung und macht dich abends müde zur richtigen Zeit.";
        else if (s.elevation > 10) tip = "☀️ Tageslicht tankt Vitamin D und Energie. Ein kurzer Gang draußen schlägt jede Tasse Kaffee fürs Wachsein.";
        else if (s.elevation > -6) tip = "🌇 Dämmerung: Licht wird warm und dimmt. Dein Körper fängt an, Melatonin zu bilden – gutes Signal, Bildschirme langsam runterzudimmen.";
        else tip = "🌙 Nacht: helles, blaues Licht (Handy, Decke) bremst jetzt deinen Schlaf. Warm und dunkel = leichter einschlafen.";

        LinearLayout now = cardBox();
        now.addView(cardTag("GERADE GUT FÜR DICH"));
        now.addView(info(tip));

        LinearLayout sleep = cardBox();
        sleep.addView(cardTag("LICHT & SCHLAF"));
        sleep.addView(info("• Morgens Licht, abends Dunkel – so lernt dein Körper, wann Tag und Nacht ist.\n"
            + "• 30–60 Min nach dem Aufwachen Tageslicht holt deinen Rhythmus nach vorn (leichter aufstehen).\n"
            + "• 1–2 h vor dem Schlaf Licht dimmen, warm statt blau.\n"
            + "• Gleiche Schlafenszeit zählt mehr als die Menge einer Nacht."));

        LinearLayout climate = cardBox();
        climate.addView(cardTag("KLIMA & KÖRPER"));
        double decl = s.declination;
        climate.addView(info("Die Sonne steht bei dir heute maximal " + String.format(Locale.GERMAN, "%.0f°", maxElev())
            + " hoch. " + (maxElev() < 25 ? "Flacher Winkel = schwächere Strahlung, kürzere Tage, kühler. Der Körper will mehr Schlaf und Licht – ganz normal." :
            maxElev() > 55 ? "Hoher Stand = starke Strahlung. Mittags Schatten/Wasser, Sonnencreme; Hitze kostet Schlafqualität – kühl und dunkel schlafen." :
            "Mittlerer Stand – milde Strahlung, angenehme Tageslänge.")));
        return new View[]{now, sleep, climate};
    }

    private double maxElev() {
        return solar(noonToday()).elevation;
    }

    private long noonToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        return c.getTimeInMillis();
    }

    /** A ring of the year: solstices and equinoxes, with today marked and the lit (day-long) side bright. */
    private final class YearRing extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        YearRing() {
            super(SunActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 - dp(26);
            // Ring coloured by day length through the year (brighter = longer days, for this hemisphere).
            boolean south = lat < 0;
            for (int i = 0; i < 360; i += 3) {
                double a = Math.toRadians(i - 90);
                int doy = (int) (i / 360.0 * 365) + 1;
                double len = dayLenFor(doy);
                float warm = (float) Math.max(0, Math.min(1, (len - 480) / 480.0));
                int col = blend(0xFF2A4A8A, 0xFFFFC83D, warm);
                p.setColor(col);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(10));
                c.drawArc(cx - r, cy - r, cx + r, cy + r, i - 90, 3.5f, false, p);
            }
            // Markers: solstices (~172, 355) and equinoxes (~80, 266).
            String[] names = {"❄ Winter", "🌱 Frühling", "☀ Sommer", "🍂 Herbst"};
            int[] days = {355, 80, 172, 266};
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            p.setTextSize(dp(9));
            for (int k = 0; k < 4; k++) {
                double a = Math.toRadians(days[k] / 365.0 * 360 - 90);
                float mx = cx + (float) Math.cos(a) * (r + dp(2)), my = cy + (float) Math.sin(a) * (r + dp(2));
                p.setStyle(Paint.Style.FILL);
                p.setColor(ink3);
                c.drawCircle(mx, my, dp(2.5f), p);
                p.setColor(ink2);
                p.setTextAlign(Paint.Align.CENTER);
                float tx = cx + (float) Math.cos(a) * (r + dp(16)), ty = cy + (float) Math.sin(a) * (r + dp(16)) + dp(3);
                c.drawText(south ? names[(k + 2) % 4] : names[k], tx, ty, p);
            }
            // Today's marker.
            int doy = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
            double at = Math.toRadians(doy / 365.0 * 360 - 90);
            float tx = cx + (float) Math.cos(at) * r, ty = cy + (float) Math.sin(at) * r;
            p.setColor(accent);
            c.drawCircle(tx, ty, dp(7), p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(tx, ty, dp(3), p);
            p.setColor(ink);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(13));
            c.drawText("HEUTE", cx, cy - dp(4), p);
            p.setColor(ink3);
            p.setTextSize(dp(10));
            c.drawText(new java.text.SimpleDateFormat("d. MMM", Locale.GERMAN).format(new java.util.Date()), cx, cy + dp(12), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    private double dayLenFor(int doy) {
        double decl = Math.toRadians(-23.44 * Math.cos(Math.toRadians(360.0 / 365 * (doy + 10))));
        double latR = Math.toRadians(lat);
        double cosH = -Math.tan(latR) * Math.tan(decl);
        if (cosH > 1) return 0;
        if (cosH < -1) return 1440;
        return Math.toDegrees(Math.acos(cosH)) * 8;
    }

    /* ================= strings / helpers ================= */

    private String season(int doy) {
        boolean south = lat < 0;
        String[] n = {"Winter", "Frühling", "Sommer", "Herbst"};
        int i = doy < 80 || doy >= 355 ? 0 : doy < 172 ? 1 : doy < 266 ? 2 : 3;
        if (south) i = (i + 2) % 4;
        return n[i];
    }

    private String seasonInfo(int doy) {
        double len = dayLenFor(doy), tomorrow = dayLenFor(doy + 1);
        boolean growing = tomorrow > len;
        return "Die Tage werden gerade " + (growing ? "länger ☀↑" : "kürzer 🌙↓")
            + ". " + (growing ? "Mehr Licht kommt – Energie und Stimmung steigen mit. Nutze die hellen Morgen."
            : "Weniger Licht – es ist normal, müder und gedämpfter zu sein. Hol dir aktiv Morgenlicht, das hält die innere Uhr stabil.");
    }

    private String compass(double az) {
        String[] d = {"N", "NO", "O", "SO", "S", "SW", "W", "NW"};
        return d[(int) Math.round(az / 45.0) % 8];
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg2 = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg2 - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private String hm(double min) {
        if (min < 0) min += 1440;
        min %= 1440;
        return String.format(Locale.ROOT, "%02d:%02d", (int) (min / 60), (int) (min % 60));
    }

    private LinearLayout cardBox() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(box(card, line, 18));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView cardTag(String s) {
        TextView t = mono(10, accent);
        t.setText("— " + s);
        t.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private View row(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(6), 0, dp(6));
        TextView a = text(14, ink2, false);
        a.setText(k);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = text(14, ink, true);
        b.setText(v);
        b.setGravity(Gravity.END);
        r.addView(b, new LinearLayout.LayoutParams(0, -2, 1.3f));
        return r;
    }

    private TextView info(String s) {
        TextView t = text(13, ink2, false);
        t.setText(s);
        t.setLineSpacing(0, 1.28f);
        return t;
    }

    private TextView text(float sp, int col, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private TextView mono(float sp, int col) {
        TextView t = text(sp, col, true);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return t;
    }

    private android.graphics.drawable.GradientDrawable box(int fill, int stroke, int rad) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(rad));
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
