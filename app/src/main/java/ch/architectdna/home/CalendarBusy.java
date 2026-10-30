package ch.architectdna.home;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the busy / blocked times from the phone's synced calendars (Google included) so Flexplan
 * plans around them. Uses the system calendar provider – no network, no Google login; it reads
 * whatever calendars are already on the device. Needs READ_CALENDAR.
 */
final class CalendarBusy {

    static final class Event {
        final String title;
        final long start, end;
        final boolean allDay;

        Event(String title, long start, long end, boolean allDay) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.allDay = allDay;
        }
    }

    private CalendarBusy() {
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Busy events overlapping [from, to), earliest first; empty without permission or on error. */
    static List<Event> between(Context c, long from, long to) {
        List<Event> out = new ArrayList<>();
        if (!allowed(c)) return out;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] proj = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.AVAILABILITY};
        try (Cursor cur = c.getContentResolver().query(b.build(), proj,
            CalendarContract.Instances.AVAILABILITY + "!=" + CalendarContract.Instances.AVAILABILITY_FREE, null,
            CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String title = cur.isNull(0) ? "Termin" : cur.getString(0);
                long s = cur.getLong(1), e = cur.getLong(2);
                boolean allDay = cur.getInt(3) == 1;
                if (e <= s) e = s + 30 * 60_000L;
                out.add(new Event(title == null || title.isEmpty() ? "Termin" : title, s, e, allDay));
            }
        } catch (RuntimeException ex) {
            DiagLog.log("calendar read: " + ex.getMessage());
        }
        return out;
    }
}
