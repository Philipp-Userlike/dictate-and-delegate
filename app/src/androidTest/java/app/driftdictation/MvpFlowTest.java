package app.driftdictation;

import static org.junit.Assert.*;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * Setup, the key, processing, costs and translation as a user meets them, against the fake
 * OpenRouter.
 */
@RunWith(AndroidJUnit4.class)
public class MvpFlowTest {
  Context context;
  UiDevice device;
  SharedPreferences prefs;

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
    fail("Expected " + state + "; got " + prefs.getString("state", "") + ": " + detail());
  }

  String detail() {
    return prefs.getString("detail", "");
  }

  void dictate() throws Exception {
    send(DictationService.RECORD);
    waitFor("recording");
    Thread.sleep(1200);
    send(DictationService.STOP);
    waitFor("copied");
  }

  HistoryEntry latest() throws Exception {
    try (HistoryStore h = new HistoryStore(context)) {
      return h.list(1, 0).get(0);
    }
  }

  /** The requests of one kind ("transcribe", "route", "text") the fake received, oldest first. */
  java.util.List<JSONObject> sent(String kind) throws Exception {
    java.util.List<JSONObject> out = new java.util.ArrayList<>();
    JSONArray all = TestServer.requests();
    for (int i = 0; i < all.length(); i++)
      if (all.getJSONObject(i).getString("kind").equals(kind))
        out.add(all.getJSONObject(i).getJSONObject("body"));
    return out;
  }

  void launch() throws Exception {
    context.startActivity(
        new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 5000);
    Thread.sleep(500);
  }

  UiObject2 find(String text) {
    UiObject2 found = device.wait(Until.findObject(By.text(text)), 5000);
    if (found == null) {
      StringBuilder seen = new StringBuilder();
      for (UiObject2 o : device.findObjects(By.textContains("")))
        seen.append(" | ").append(o.getText());
      fail("Expected on screen: " + text + "; saw" + seen);
    }
    return found;
  }

  /** Scrolls the page until the text is on screen, then taps it. */
  void tap(String text) throws Exception {
    UiScrollable page = new UiScrollable(new UiSelector().scrollable(true));
    if (page.exists()) {
      page.scrollIntoView(new UiSelector().text(text));
      // scrollIntoView stops as soon as the edge shows; keep it clear of the gesture bar.
      UiObject2 shown = find(text);
      if (shown.getVisibleBounds().bottom > device.getDisplayHeight() * 0.85) page.scrollToEnd(3);
    }
    Thread.sleep(700); // A tap during a fling only stops the fling.
    find(text).click();
    Thread.sleep(400);
  }

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    prefs = context.getSharedPreferences("settings", 0);
    device.executeShellCommand("pm grant app.driftdictation android.permission.RECORD_AUDIO");
    device.executeShellCommand("appops set app.driftdictation SYSTEM_ALERT_WINDOW allow");
    device.executeShellCommand("cmd locale set-app-locales app.driftdictation --locales en-US");
    device.executeShellCommand("input keyevent KEYCODE_WAKEUP");
    send(DictationService.DISCARD);
    TestServer.reset();
    TestServer.useKey(context);
    prefs
        .edit()
        .putString("model", "microsoft/mai-transcribe-2")
        .putString("bubbleMode", "recording")
        .putBoolean("onboarded", true)
        .putBoolean("processing", true)
        .remove("processingPrompt")
        .remove("profileWords")
        .commit();
    new Attachments(context).clearPending();
    TestServer.transcripts("um hi Jana see you at ten");
    TestServer.routes("text");
    TestServer.replies("Hi Jana, see you at ten.");
  }

  @After
  public void cleanup() throws Exception {
    if (DictationService.busy()) {
      send(DictationService.STOP);
      Thread.sleep(800);
    }
    send(DictationService.HIDE);
    TestServer.reset();
    TestServer.useKey(context);
    new Attachments(context).clearPending();
    device.executeShellCommand("cmd locale set-app-locales app.driftdictation --locales en-US");
    // The app's resources switch back asynchronously; later tests look for English labels.
    long deadline = SystemClock.elapsedRealtime() + 5000;
    while (SystemClock.elapsedRealtime() < deadline
        && !context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("en"))
      Thread.sleep(100);
    device.pressHome();
  }

  @Test
  public void setupChecksTheKeyThenTeachesStartStopAndPaste() throws Exception {
    prefs.edit().putBoolean("onboarded", false).commit();
    TestServer.removeKey(context);
    launch();
    find("Talk anywhere.");
    tap("Get started");
    find("Your OpenRouter key");
    UiObject2 field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5000);
    field.setText(TestServer.BAD_KEY);
    tap("Check and save");
    assertNotNull(device.wait(Until.findObject(By.textStartsWith("This key isn't valid")), 10000));
    assertFalse(OpenRouter.hasKey(context));
    field = device.findObject(By.clazz("android.widget.EditText"));
    field.setText(TestServer.KEY);
    tap("Check and save");
    assertNotNull(device.wait(Until.findObject(By.text("Microphone and privacy")), 10000));
    assertEquals(TestServer.KEY, new Vault(context).get(OpenRouter.KEY));
    tap("Continue");
    find("Transcript or text");
    tap("Continue");
    find("Start from any app");
    tap("Continue");
    find("Try it once");
    find("Start").click();
    waitFor("recording");
    Thread.sleep(1200);
    find("Stop").click();
    waitFor("copied");
    UiObject2 box = find("Paste here");
    box.click();
    device.pressKeyCode(android.view.KeyEvent.KEYCODE_PASTE);
    find("Hi Jana, see you at ten.");
    tap("Continue");
    // bb is optional: "Later" ends setup, and the app works as a dictation app.
    find("Connect bb");
    find("bb Connect");
    tap("Later");
    find("Start dictating");
    assertTrue(prefs.getBoolean("onboarded", false));
    find("Speech model");
  }

  @Test
  public void withoutAKeyNothingIsRecorded() throws Exception {
    TestServer.removeKey(context);
    send(DictationService.RECORD);
    waitFor("error");
    assertTrue(detail(), detail().contains("OpenRouter key"));
    assertFalse(DictationService.busy());
    assertFalse(DictationService.audio(context).exists());
    launch();
    find("Add your OpenRouter key to start dictating").click();
    find("OpenRouter key");
    find("Get a key on openrouter.ai");
  }

  @Test
  public void tileWithoutAKeyOpensTheKeyInsteadOfRecording() throws Exception {
    TestServer.removeKey(context);
    device.executeShellCommand("cmd statusbar add-tile app.driftdictation/.DictationTile");
    device.pressHome();
    device.executeShellCommand("cmd statusbar click-tile app.driftdictation/.DictationTile");
    find("Check and save");
    assertFalse(DictationService.busy());
    assertNotEquals("recording", prefs.getString("state", ""));
  }

  @Test
  public void noteIsProcessedCostsWhatOpenRouterReportedAndSwitchesToWhatYouSaid()
      throws Exception {
    new Profile(prefs).addWord("Jana");
    dictate();
    assertEquals("Hi Jana, see you at ten.", new Vault(context).get("transcript"));

    // Exactly what the fake reported: no fee on top.
    BigDecimal all = TestServer.SPEECH.add(TestServer.ROUTE).add(TestServer.TEXT);
    HistoryEntry e = latest();
    assertEquals(0, all.compareTo(e.cost()));
    assertFalse(e.incompleteCost());
    assertEquals("text", e.json.optString("route"));
    assertEquals(TextModel.MODEL, e.json.optString("textModel"));
    assertEquals(0, TestServer.ROUTE.compareTo(e.routeCost()));
    assertEquals(0, TestServer.TEXT.compareTo(e.textCost()));
    assertEquals("Hi Jana, see you at ten.", e.json.optString("processed"));
    assertEquals("um hi Jana see you at ten", e.json.optString("original"));
    // The activity log: speech, the check's answer, then the text model.
    JSONArray steps = e.json.getJSONArray("steps");
    assertEquals(3, steps.length());
    assertEquals("speech", steps.getJSONObject(0).getString("kind"));
    assertEquals("microsoft/mai-transcribe-2", steps.getJSONObject(0).getString("model"));
    assertEquals(
        0, TestServer.SPEECH.compareTo(new BigDecimal(steps.getJSONObject(0).getString("cost"))));
    assertEquals("check", steps.getJSONObject(1).getString("kind"));
    assertEquals(Router.MODEL, steps.getJSONObject(1).getString("model"));
    assertEquals("text", steps.getJSONObject(1).getString("detail"));
    assertEquals(
        0, TestServer.ROUTE.compareTo(new BigDecimal(steps.getJSONObject(1).getString("cost"))));
    assertEquals("text", steps.getJSONObject(2).getString("kind"));
    assertEquals(TextModel.MODEL, steps.getJSONObject(2).getString("model"));
    assertEquals(
        0, TestServer.TEXT.compareTo(new BigDecimal(steps.getJSONObject(2).getString("cost"))));
    assertFalse(steps.getJSONObject(2).has("detail"));

    // What went to OpenRouter: WAV audio with the words for Azure, a quick check without
    // thinking, then the text model; every chat call asks for its cost.
    JSONObject speech = sent("transcribe").get(0);
    assertEquals("microsoft/mai-transcribe-2", speech.getString("model"));
    assertEquals("wav", speech.getJSONObject("input_audio").getString("format"));
    assertEquals(
        "Jana",
        speech
            .getJSONObject("provider")
            .getJSONObject("options")
            .getJSONObject("azure")
            .getJSONObject("phraseList")
            .getJSONArray("phrases")
            .getString(0));
    JSONObject check = sent("route").get(0);
    assertFalse(check.getJSONObject("reasoning").getBoolean("enabled"));
    assertEquals(Router.MAX_TOKENS, check.getInt("max_tokens"));
    assertTrue(check.getJSONObject("usage").getBoolean("include"));
    JSONObject text = sent("text").get(0);
    assertEquals(TextModel.MODEL, text.getString("model"));
    assertEquals(TextModel.MAX_TOKENS, text.getInt("max_tokens"));
    assertTrue(text.getJSONObject("usage").getBoolean("include"));
    assertEquals(
        "um hi Jana see you at ten",
        text.getJSONArray("messages").getJSONObject(1).getString("content"));

    send(DictationService.HIDE);
    Thread.sleep(500);
    launch();
    find("Hi Jana, see you at ten.").click();
    find("Talk Router's version");
    find("What you said").click();
    find("um hi Jana see you at ten");
    tap("Copy");
    String[] clip = {null};
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              ClipData c = context.getSystemService(ClipboardManager.class).getPrimaryClip();
              clip[0] = c == null ? null : c.getItemAt(0).getText().toString();
            });
    assertEquals("um hi Jana see you at ten", clip[0]);

    // Settings → Activity shows the same steps.
    launch();
    tap("Settings");
    tap("Activity");
    find("Activity");
    assertNotNull(device.wait(Until.findObject(By.textStartsWith("Transcription · ")), 5000));
    assertNotNull(
        device.wait(
            Until.findObject(
                By.text(Pattern.compile("Request check · DeepSeek V4\\.1 Flash · .* → Text"))),
            5000));
    assertNotNull(
        device.wait(
            Until.findObject(By.textStartsWith("Text · " + TextModel.NAME + " · ")), 5000));
    tap("Hi Jana, see you at ten.");
    find("Talk Router's version");
  }

  @Test
  public void aDictationWithoutRequestStaysTheTranscript() throws Exception {
    TestServer.reset();
    TestServer.transcripts("Call Jana tomorrow at ten.");
    dictate();
    assertEquals("Call Jana tomorrow at ten.", new Vault(context).get("transcript"));
    // Only the speech model and the request check ran; no text model.
    HistoryEntry e = latest();
    assertEquals(0, TestServer.SPEECH.add(TestServer.ROUTE).compareTo(e.cost()));
    assertEquals("transcript", e.json.optString("route"));
    assertFalse(e.json.has("processed"));
    assertTrue(sent("text").isEmpty());
  }

  @Test
  public void processingOffKeepsWhatYouSaid() throws Exception {
    prefs.edit().putBoolean("processing", false).commit();
    dictate();
    assertEquals("um hi Jana see you at ten", new Vault(context).get("transcript"));
    // Only the transcription ran.
    assertEquals(0, TestServer.SPEECH.compareTo(latest().cost()));
    assertTrue(sent("route").isEmpty());
  }

  @Test
  public void failedProcessingStillDeliversWhatYouSaid() throws Exception {
    TestServer.reset();
    TestServer.transcripts("um hi Jana see you at ten");
    TestServer.routes("text");
    TestServer.replies((String) null);
    dictate();
    assertEquals("um hi Jana see you at ten", new Vault(context).get("transcript"));
    assertTrue(latest().json.has("processingFailed"));
  }

  @Test
  public void anUnknownCostIsNeverShownAsFree() throws Exception {
    TestServer.reset();
    TestServer.script("transcribe", new JSONObject().put("text", "Call Jana.").put("cost", JSONObject.NULL));
    prefs.edit().putBoolean("processing", false).commit();
    dictate();
    HistoryEntry e = latest();
    assertTrue(e.incompleteCost());
    assertEquals(0, e.cost().signum());
    assertFalse(e.json.getJSONArray("steps").getJSONObject(0).has("cost"));
    launch();
    find("Call Jana.").click();
    find("OpenRouter reported no cost for this note yet.");
  }

  @Test
  public void usedUpCreditsKeepTheRecording() throws Exception {
    TestServer.reset();
    TestServer.error("transcribe", "credits");
    send(DictationService.RECORD);
    waitFor("recording");
    Thread.sleep(1200);
    send(DictationService.STOP);
    waitFor("error");
    assertTrue(detail(), detail().contains("credits are used up"));
    assertTrue(DictationService.audio(context).exists());
  }

  @Test
  public void pastedTextGoesWithTheRequestAndStaysWithTheNote() throws Exception {
    new Attachments(context).addText("Two-room flat, Hauptstraße 5, 900 euros, viewing Saturday 10:00");
    TestServer.reset();
    TestServer.transcripts("write a short application for this flat");
    TestServer.routes("text");
    TestServer.replies("Application for Hauptstraße 5.");
    dictate();
    assertEquals("Application for Hauptstraße 5.", new Vault(context).get("transcript"));
    HistoryEntry e = latest();
    assertEquals(1, e.json.optInt("attached"));
    assertTrue(new Attachments(context).pending().isEmpty());
    assertEquals(1, new Attachments(context).of(e.recordingId()).size());
    // The check sees a summary; the text model gets the dictation and the pasted text as parts.
    assertTrue(
        sent("route").get(0).getJSONArray("messages").getJSONObject(0).getString("content").contains("Hauptstraße 5"));
    JSONArray parts =
        sent("text").get(0).getJSONArray("messages").getJSONObject(1).getJSONArray("content");
    assertEquals("write a short application for this flat", parts.getJSONObject(0).getString("text"));
    assertTrue(parts.getJSONObject(1).getString("text").contains("Hauptstraße 5"));
  }

  @Test
  public void interfaceFollowsTheAppLanguage() throws Exception {
    device.executeShellCommand("cmd locale set-app-locales app.driftdictation --locales de-DE");
    launch();
    find("Einstellungen").click();
    find("Sprachmodell");
    tap("Alle meine Daten löschen");
    assertNotNull(
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)alles löschen"))), 5000));
    device.pressBack();
  }
}
