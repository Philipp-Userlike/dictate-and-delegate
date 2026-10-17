package app.driftdictation;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.content.*;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import java.util.List;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * The floating panel with Talk Router's Accessibility service on: Insert into the test pad's text
 * field, Insert refined replacing it in place, the copy fallback, the chief's text back, ntfy's
 * Copy broadcast, and the volume-key start. UiAutomation is told not to suppress other
 * accessibility services, so ours runs while the test drives the screen.
 *
 * <p>Screenshots for the docs: run with -e shots true; they go to the app's external files dir
 * (Android/data/app.driftdictation/files/panel).
 */
@RunWith(AndroidJUnit4.class)
public class PanelTest {
  static final String SERVICE = "app.driftdictation/app.driftdictation.TalkAccessibility";
  static final String LINKS = "https://someone.getbb.app";

  Context context;
  UiDevice device;
  SharedPreferences prefs;
  boolean shots;

  void shell(String command) throws Exception {
    device.executeShellCommand(command);
  }

  void send(String action) {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> DictationService.send(context, action));
  }

  void waitFor(String state) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 20000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (state.equals(prefs.getString("state", ""))) return;
      Thread.sleep(100);
    }
    fail("Expected " + state + "; got " + prefs.getString("state", "") + ": " + prefs.getString("detail", ""));
  }

  void until(String what, java.util.function.BooleanSupplier ready) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 15000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (ready.getAsBoolean()) return;
      Thread.sleep(150);
    }
    fail("Never happened: " + what);
  }

  UiObject2 find(String text) {
    return find(By.text(text), text);
  }

  UiObject2 find(BySelector selector, String what) {
    UiObject2 found = device.wait(Until.findObject(selector), 10000);
    if (found == null) {
      StringBuilder seen = new StringBuilder();
      for (UiObject2 o : device.findObjects(By.textContains("")))
        seen.append(" | ").append(o.getText());
      fail("Expected on screen: " + what + "; saw" + seen);
    }
    return found;
  }

  HistoryEntry latest() throws Exception {
    try (HistoryStore h = new HistoryStore(context)) {
      return h.list(1, 0).get(0);
    }
  }

  /** Talk Router in front: Android only lets the app in focus read the clipboard. */
  void front() {
    context.startActivity(
        new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 5000);
  }

  String clipboard() {
    String[] clip = {null};
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              ClipData c = context.getSystemService(ClipboardManager.class).getPrimaryClip();
              clip[0] = c == null ? null : String.valueOf(c.getItemAt(0).getText());
            });
    return clip[0];
  }

  /** The test pad's text field, with this text in it and the cursor at its end. */
  UiObject2 pad(String text) throws Exception {
    shell("am start -W -n app.driftdictation.test/app.driftdictation.TestPadActivity");
    UiObject2 field = find(By.clazz("android.widget.EditText"), "the test pad's field");
    field.setText(text);
    field.click();
    Thread.sleep(400);
    device.pressKeyCode(KeyEvent.KEYCODE_MOVE_END);
    Thread.sleep(300);
    return field;
  }

  String padText() {
    UiObject2 field = device.findObject(By.clazz("android.widget.EditText").pkg("app.driftdictation.test"));
    if (field == null) return null;
    String text = field.getText();
    return text == null || text.equals("Paste your transcription") ? "" : text;
  }

  /** This dictation's note. */
  HistoryEntry note() throws Exception {
    try (HistoryStore h = new HistoryStore(context)) {
      return h.get(DictationService.session().noteId);
    }
  }

  void shot(String name) throws Exception {
    if (!shots) return;
    Thread.sleep(700);
    File dir = new File(context.getExternalFilesDir(null), "panel");
    dir.mkdirs();
    device.takeScreenshot(new File(dir, name + ".png"));
  }

  void dictate() throws Exception {
    send(DictationService.RECORD);
    waitFor("recording");
    Thread.sleep(1200);
    send(DictationService.STOP);
    waitFor("copied");
  }

  void accessibility(boolean on) throws Exception {
    // Off first: after the app was reinstalled, a changed setting makes Android bind it again.
    shell("settings delete secure enabled_accessibility_services");
    if (on) {
      until("the service stops", () -> TalkAccessibility.instance == null);
      shell("settings put secure enabled_accessibility_services " + SERVICE);
    }
    shell("settings put secure accessibility_enabled " + (on ? 1 : 0));
    until(on ? "the service starts" : "the service stops", () -> (TalkAccessibility.instance != null) == on);
  }

  @Before
  public void setup() throws Exception {
    // Before the first UiDevice: keep other accessibility services (ours) running.
    Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    prefs = context.getSharedPreferences("settings", 0);
    shots = "true".equals(InstrumentationRegistry.getArguments().getString("shots"));
    shell("pm grant app.driftdictation android.permission.RECORD_AUDIO");
    shell("appops set app.driftdictation SYSTEM_ALERT_WINDOW allow");
    shell("cmd locale set-app-locales app.driftdictation --locales en-US");
    shell("input keyevent KEYCODE_WAKEUP");
    shell("wm dismiss-keyguard");
    send(DictationService.DISCARD);
    TestServer.reset();
    TestServer.bbReset();
    TestServer.ntfyReset();
    TestServer.useKey(context);
    new BbBridge(context).disconnect();
    prefs
        .edit()
        .putString("model", "microsoft/mai-transcribe-2")
        .putString("bubbleMode", "recording")
        .putBoolean("onboarded", true)
        .putBoolean(Profile.AUTO_REFINE, true)
        .putBoolean(Profile.AUTO_CHIEF, true)
        .putInt("bubbleX", 16)
        .putInt("bubbleY", 900) // Clear of the test pad's field and of Android's clipboard chip.
        .remove(TalkAccessibility.VOLUME_KEY)
        .remove(BbBridge.CHIEF_ID)
        .remove(BbBridge.CHIEF_NAME)
        .remove(BbBridge.NTFY_SERVER)
        .commit();
    Templates templates = new Templates(context);
    for (Templates.Template t : templates.all()) templates.delete(t.id());
    new Attachments(context).clearPending();
    accessibility(true);
  }

  @After
  public void cleanup() throws Exception {
    if (DictationService.busy()) {
      send(DictationService.STOP);
      Thread.sleep(800);
    }
    send(DictationService.HIDE);
    Thread.sleep(300);
    accessibility(false);
    TestServer.reset();
    TestServer.bbReset();
    TestServer.ntfyReset();
    new BbBridge(context).disconnect();
    prefs
        .edit()
        .remove(BbBridge.CHIEF_ID)
        .remove(BbBridge.CHIEF_NAME)
        .remove(BbBridge.MODE)
        .remove(BbBridge.NTFY_SERVER)
        .remove(TalkAccessibility.VOLUME_KEY)
        .commit();
    device.pressHome();
  }

  @Test
  public void insertPutsTheTranscriptAtTheCursorThenRefinedReplacesItInPlace() throws Exception {
    TestServer.transcripts("um so see you at ten I mean eleven");
    TestServer.decide(0.93, 0, "none");
    TestServer.replies("See you at eleven.");
    pad("Hi Jana, ");
    send(DictationService.RECORD);
    waitFor("recording");
    find("✨ Refine");
    find(By.desc("Stop recording"), "the stop button");
    shot("1-recording");
    send(DictationService.STOP);
    waitFor("copied");
    find("Insert");
    shot("2-transcript");
    find("Insert").click();
    until("the transcript is in the field", () -> "Hi Jana, um so see you at ten I mean eleven".equals(padText()));
    find("Inserted");
    // Refine started by itself (Jev 0.93) and its button became Insert refined.
    find("Insert refined");
    shot("3-refined");
    find("Insert refined").click();
    until("the refined text replaced it", () -> "Hi Jana, See you at eleven.".equals(padText()));
    find("Replaced with the refined text");
    front();
    until("copied", () -> "See you at eleven.".equals(clipboard()));
    HistoryEntry e = note();
    assertEquals("See you at eleven.", e.json.optString("processed"));
    assertEquals("um so see you at ten I mean eleven", e.json.optString("original"));
  }

  @Test
  public void editedMeanwhileTheRefinedTextGoesAtTheCursorInstead() throws Exception {
    TestServer.transcripts("see you at ten");
    TestServer.decide(0.1, 0, "none");
    TestServer.replies("See you at 10.");
    UiObject2 field = pad("");
    dictate();
    find("Insert").click();
    until("the transcript is in the field", () -> "see you at ten".equals(padText()));
    // Text around it doesn't matter: the transcript is still there, unchanged.
    field.setText("Ok, see you at ten, then");
    find("✨ Refine").click();
    find("Insert refined").click();
    until("replaced in place", () -> "Ok, See you at 10., then".equals(padText()));
    // Changed by hand: the refined text goes at the cursor instead.
    field.setText("see you at 10 then");
    field.click();
    device.pressKeyCode(KeyEvent.KEYCODE_MOVE_END);
    Thread.sleep(300);
    find("Insert refined").click();
    until("inserted at the cursor", () -> "see you at 10 thenSee you at 10.".equals(padText()));
  }

  @Test
  public void refineChosenWhileRecordingRunsOnceTheTranscriptIsReady() throws Exception {
    TestServer.transcripts("hello there");
    TestServer.replies("Hello there!");
    send(DictationService.RECORD);
    waitFor("recording");
    find("✨ Refine").click();
    find("✨ Refine ✓");
    assertTrue(TestServer.requests().length() == 0);
    send(DictationService.STOP);
    waitFor("copied");
    find("Insert refined");
    assertEquals("Hello there!", note().json.optString("processed"));
  }

  @Test
  public void withoutTheServiceInsertCopiesInstead() throws Exception {
    accessibility(false);
    TestServer.transcripts("copy me please");
    pad("");
    dictate();
    find("Insert").click();
    find(By.textStartsWith("Copied, paste with a long press"), "the fallback message");
    shot("6-copied-fallback");
    assertEquals("", padText());
    front();
    until("copied", () -> "copy me please".equals(clipboard()));
  }

  @Test
  public void closeHidesThePanelAndTheNoteStays() throws Exception {
    TestServer.transcripts("a note to keep");
    dictate();
    find("Insert");
    find(By.desc("Close"), "✕").click();
    until("the panel is gone", () -> !DictationService.bubbleVisible());
    assertEquals("a note to keep", latest().text());
  }

  /** bb at the fake in custom-address mode with its chief, and ntfy at the fake. */
  BbBridge chief() throws Exception {
    BbBridge bb = new BbBridge(context);
    bb.useCustom(TestServer.bbUrl(), "", LINKS);
    bb.setChief(new BbBridge.ThreadInfo(TestServer.CHIEF, "Chief of staff", "idle"));
    bb.setNtfyServer(TestServer.ntfyRoot());
    return bb;
  }

  @Test
  public void theChiefsTextBackAppearsInThePanelAndInserts() throws Exception {
    BbBridge bb = chief();
    TestServer.transcripts("chief of staff, write Tom that I'm running late");
    TestServer.decide(0.3, 0.96, "none");
    pad("");
    dictate();
    find("Open chat ↗");
    shot("4-chief-sent");
    String request = note().json.optString("chiefRequest");
    assertFalse(request.isEmpty());
    // The panel listens to the topic from the moment it was sent.
    until(
        "the panel subscribes to the topic",
        () -> {
          try {
            JSONArray r = TestServer.ntfyRequests();
            for (int i = 0; i < r.length(); i++)
              if (r.getJSONObject(i).optString("method").equals("subscribe")) return true;
            return false;
          } catch (Exception ex) {
            return false;
          }
        });
    TestServer.ntfyPaste(bb.ntfyTopic(), "other1", "Not for this request.");
    TestServer.ntfyPaste(bb.ntfyTopic(), request, "Hi Tom, I'm running about ten minutes late.");
    find("Insert from chief");
    shot("5-insert-from-chief");
    find("Insert from chief").click();
    until("the chief's text is in the field", () -> "Hi Tom, I'm running about ten minutes late.".equals(padText()));
    until(
        "the note keeps it",
        () -> {
          try {
            return "Hi Tom, I'm running about ten minutes late.".equals(note().json.optString("chiefReply"));
          } catch (Exception ex) {
            return false;
          }
        });
  }

  @Test
  public void ntfysCopyButtonCopiesTheChiefsText() throws Exception {
    BbBridge bb = chief();
    TestServer.ntfyPaste(bb.ntfyTopic(), "abc234", "The text from the notification.");
    front(); // The receiver copies either way; the test may only read it in front.
    // What ntfy's broadcast action sends: an implicit broadcast with the request as an extra.
    shell("am broadcast -a " + TalkAccessibility.COPY_ACTION + " --es request abc234");
    until("copied", () -> "The text from the notification.".equals(clipboard()));
    // Only an ID goes in: text in the broadcast itself is never copied.
    shell("am broadcast -a " + TalkAccessibility.COPY_ACTION + " --es request '../x' --es text evil");
    Thread.sleep(1000);
    assertEquals("The text from the notification.", clipboard());
  }

  /** Hands the service a key event as Android's input filter would. */
  boolean key(int action, int code) {
    boolean[] consumed = {false};
    long t = SystemClock.uptimeMillis();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> consumed[0] = TalkAccessibility.instance.onKeyEvent(new KeyEvent(t, t, action, code, 0)));
    return consumed[0];
  }

  @Test
  public void holdingTheVolumeKeyStartsAndStopsAShortPressIsTheVolume() throws Exception {
    TestServer.transcripts("started with the volume key");
    prefs.edit().putString(TalkAccessibility.VOLUME_KEY, TalkAccessibility.DOWN).putInt(TalkAccessibility.HOLD_MS, 300).commit();
    device.pressHome();
    // Other keys, and the other volume key, are left alone.
    assertFalse(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));
    assertFalse(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
    // A short press is held back, then sent on as a volume change; nothing records.
    assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN));
    Thread.sleep(100);
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN));
    Thread.sleep(800);
    assertFalse(DictationService.busy());
    // Held: recording starts (through the tile's short-lived window), then a hold stops it.
    assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN));
    Thread.sleep(500);
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN));
    waitFor("recording");
    Thread.sleep(1000);
    assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN));
    Thread.sleep(500);
    assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN));
    waitFor("copied");
    assertEquals("started with the volume key", note().text());
    // Switched off, the key is Android's again.
    prefs.edit().putString(TalkAccessibility.VOLUME_KEY, TalkAccessibility.OFF).commit();
    assertFalse(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN));
  }

  @Test
  public void thePanelStaysDraggableAndNeverTakesFocus() throws Exception {
    TestServer.transcripts("drag me");
    UiObject2 field = pad("");
    dictate();
    UiObject2 insert = find("Insert");
    android.graphics.Rect before = insert.getVisibleBounds();
    // Drag by the panel's edge (the status line area is part of the panel too).
    device.drag(before.left - 30, before.centerY(), before.left - 30, before.centerY() - 400, 30);
    Thread.sleep(600);
    android.graphics.Rect after = find("Insert").getVisibleBounds();
    assertTrue(before + " → " + after, after.centerY() < before.centerY() - 200);
    // The field still has focus: Insert lands in it.
    assertTrue(field.isFocused());
    find("Insert").click();
    until("inserted", () -> "drag me".equals(padText()));
    List<UiObject2> unused = device.findObjects(By.text("Insert"));
    assertFalse(unused.isEmpty());
  }
}
