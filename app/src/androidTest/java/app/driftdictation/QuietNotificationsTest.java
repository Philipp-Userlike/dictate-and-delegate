package app.driftdictation;

import static org.junit.Assert.*;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class QuietNotificationsTest {
  @Test
  public void oldAlertChannelsAreRemovedAndForegroundChannelIsSilent() {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var manager = context.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel("results", "Old results", NotificationManager.IMPORTANCE_DEFAULT));
    manager.createNotificationChannel(
        new NotificationChannel("dictation", "Old controls", NotificationManager.IMPORTANCE_LOW));
    manager.createNotificationChannel(
        new NotificationChannel("problems", "Old problems", NotificationManager.IMPORTANCE_DEFAULT));
    QuietNotifications.initialize(context);
    assertNull(manager.getNotificationChannel("results"));
    assertNull(manager.getNotificationChannel("dictation"));
    assertNull(manager.getNotificationChannel("problems"));
    var channel = manager.getNotificationChannel(QuietNotifications.CHANNEL);
    assertNotNull(channel);
    assertNull(channel.getSound());
    assertFalse(channel.shouldVibrate());
    assertFalse(channel.canShowBadge());
    assertFalse(manager.areNotificationsEnabled());
  }
}
