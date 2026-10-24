package ch.architectdna.home;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener. Android only shares the active media sessions (title, cover,
 * play/pause of Spotify & co.) with apps that hold notification access; this is that entry.
 * It doesn't read or keep any notifications.
 */
public class MediaListener extends NotificationListenerService {
}
