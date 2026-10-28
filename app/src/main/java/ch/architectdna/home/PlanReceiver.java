package ch.architectdna.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Flexplan live check-ins: when a block starts, a notification asks "jetzt?" with three answers –
 * "Mach ich", "Nope, nicht jetzt" (moves it to an easier time right away) and "Nur 10 min".
 * The plan bends in real time instead of sitting there as a rigid list you ignore.
 */
public class PlanReceiver extends BroadcastReceiver {

    static final String CHECK = "ch.architectdna.home.PLAN_CHECK", YES = "ch.architectdna.home.PLAN_YES",
        NOPE = "ch.architectdna.home.PLAN_NOPE", SHORT = "ch.architectdna.home.PLAN_SHORT";
    private static final String CHANNEL = "plan";
    private static final int NOTE = 7301;

    @Override
    public void onReceive(Context ctx, Intent in) {
        String a = in.getAction();
        PlanStore st = new PlanStore(ctx);
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            st.arm();
            return;
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Flexplan · Jetzt?", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Fragt beim Start eines Blocks, ob es jetzt passt.");
            nm.createNotificationChannel(ch);
        }
        if (CHECK.equals(a)) {
            PlanStore.Block b = st.current();
            st.arm();                                        // the one after this
            if (b != null) ask(ctx, nm, st, b);
            return;
        }
        PlanStore.Block b = st.block(in.getLongExtra("id", -1));
        if (b == null) {
            nm.cancel(NOTE);
            return;
        }
        String said;
        if (YES.equals(a)) {
            st.markDone(b);
            PlanStore.Task t = b.task != 0 ? st.task(b.task) : null;
            said = "Los geht's! " + st.emoji(b) + " eingetragen" + (t != null ? " · " + Math.min(t.done, t.sessions) + "/" + t.sessions + " geschafft." : ".");
        } else if (SHORT.equals(a)) {
            b.min = 10;
            st.markDone(b);
            said = "Okay, nur 10 Minuten " + st.emoji(b) + " – kleinster Schritt, zählt trotzdem.";
        } else {
            String r = st.notNow(b, true);
            said = r != null ? r : "Diese Woche ist nichts Leichteres frei – mach nur 10 Minuten?";
        }
        st.save();
        Notification n = new Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle("Flexplan")
            .setContentText(said)
            .setStyle(new Notification.BigTextStyle().bigText(said))
            .setContentIntent(open(ctx))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build();
        nm.notify(NOTE, n);
    }

    private static void ask(Context ctx, NotificationManager nm, PlanStore st, PlanStore.Block b) {
        String title = st.emoji(b) + " " + st.name(b) + " – jetzt?";
        String text = "Passt's gerade? Wenn nicht, such ich dir eine Zeit, wo's dir meist leichter fällt.";
        PlanStore.Task t = b.task != 0 ? st.task(b.task) : null;
        if (t != null && t.due > 0) {
            long days = (t.due - System.currentTimeMillis()) / (24 * 3600_000L);
            text = (days <= 2 ? "Frist bald (" + st.when(t.due) + ")! " : "") + (t.sessions - t.done) + " von " + t.sessions
                + " Etappen offen. Nur " + b.min + " min – oder ich verschieb's leichter.";
        }
        Notification n = new Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(ctx))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(new Notification.Action.Builder(null, "✓ Mach ich", act(ctx, YES, b.id)).build())
            .addAction(new Notification.Action.Builder(null, "😮‍💨 Nicht jetzt", act(ctx, NOPE, b.id)).build())
            .addAction(new Notification.Action.Builder(null, "⏱ Nur 10 min", act(ctx, SHORT, b.id)).build())
            .build();
        nm.notify(NOTE, n);
    }

    private static PendingIntent act(Context ctx, String action, long id) {
        Intent i = new Intent(ctx, PlanReceiver.class).setAction(action).putExtra("id", id);
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent open(Context ctx) {
        return PendingIntent.getActivity(ctx, 7302, new Intent(ctx, PlanActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
