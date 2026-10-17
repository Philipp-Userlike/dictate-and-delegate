package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * The silent channel Android requires for the foreground service. Talk Router never asks for permission
 * to post notifications, so Android doesn't show it in the shade; problems show on Home instead.
 */
final class QuietNotifications {
  static final String CHANNEL = "foreground_quiet";

  static void initialize(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    // Clean up alerts and channels left by earlier versions, including on first launch.
    manager.cancel(2);
    manager.cancel(3);
    manager.deleteNotificationChannel("results");
    manager.deleteNotificationChannel("dictation");
    manager.deleteNotificationChannel("problems");
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL, tr("Background dictation"), NotificationManager.IMPORTANCE_LOW);
    channel.setSound(null, null);
    channel.enableVibration(false);
    channel.setShowBadge(false);
    manager.createNotificationChannel(channel);
  }
}
