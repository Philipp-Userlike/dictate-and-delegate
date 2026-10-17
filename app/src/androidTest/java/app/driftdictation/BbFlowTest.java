package app.driftdictation;

import static org.junit.Assert.*;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * bb and the chief of staff, against the fake OpenRouter and the fake bb MCP server
 * (scripts/fakes/fake_bb_mcp.py) in custom-address mode.
 */
@RunWith(AndroidJUnit4.class)
public class BbFlowTest {
  static final String LINKS = "https://someone.getbb.app";
  static final String CHIEF_LINK = LINKS + "/threads/" + TestServer.CHIEF;

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

  /** The latest note once it matches, e.g. once the chief got it. */
  HistoryEntry latestOnce(java.util.function.Predicate<HistoryEntry> ready, String what) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 15000;
    while (SystemClock.elapsedRealtime() < deadline) {
      HistoryEntry e = latest();
      if (ready.test(e)) return e;
      Thread.sleep(150);
    }
    fail("The note never got " + what + ": " + latest().json);
    return null;
  }

  void dictate(String finalState) throws Exception {
    send(DictationService.RECORD);
    waitFor("recording");
    Thread.sleep(1200);
    send(DictationService.STOP);
    waitFor(finalState);
  }

  HistoryEntry latest() throws Exception {
    try (HistoryStore h = new HistoryStore(context)) {
      return h.list(1, 0).get(0);
    }
  }

  List<JSONObject> sent(String kind) throws Exception {
    List<JSONObject> out = new java.util.ArrayList<>();
    JSONArray all = TestServer.requests();
    for (int i = 0; i < all.length(); i++)
      if (all.getJSONObject(i).getString("kind").equals(kind))
        out.add(all.getJSONObject(i).getJSONObject("body"));
    return out;
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

  void launch() throws Exception {
    context.startActivity(
        new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 5000);
    Thread.sleep(500);
  }

  UiObject2 find(String text) {
    return find(By.text(text), text);
  }

  UiObject2 find(BySelector selector, String what) {
    UiObject2 found = device.wait(Until.findObject(selector), 8000);
    if (found == null) {
      StringBuilder seen = new StringBuilder();
      for (UiObject2 o : device.findObjects(By.textContains("")))
        seen.append(" | ").append(o.getText());
      fail("Expected on screen: " + what + "; saw" + seen);
    }
    return found;
  }

  void tap(String text) throws Exception {
    UiScrollable page = new UiScrollable(new UiSelector().scrollable(true));
    if (page.exists()) {
      page.scrollIntoView(new UiSelector().text(text));
      UiObject2 shown = find(text);
      if (shown.getVisibleBounds().bottom > device.getDisplayHeight() * 0.85) page.scrollToEnd(3);
    }
    Thread.sleep(700);
    find(text).click();
    Thread.sleep(400);
  }

  /** bb in custom-address mode at the fake, with its chief, as Settings → bb leaves it. */
  void connectFake() throws Exception {
    BbBridge bb = new BbBridge(context);
    bb.useCustom(TestServer.bbUrl(), "", LINKS);
    bb.setChief(new BbBridge.ThreadInfo(TestServer.CHIEF, "Chief of staff", "idle"));
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
    TestServer.bbReset();
    TestServer.useKey(context);
    new BbBridge(context).disconnect();
    prefs
        .edit()
        .putString("model", "microsoft/mai-transcribe-2")
        .putString("bubbleMode", "recording")
        .putBoolean("onboarded", true)
        .putBoolean(Profile.AUTO_REFINE, true)
        .putBoolean(Profile.AUTO_CHIEF, true)
        .remove(BbBridge.MODE)
        .remove(BbBridge.HANDLE)
        .remove(BbBridge.URL)
        .remove(BbBridge.LINK_BASE)
        .remove(BbBridge.CHIEF_ID)
        .remove(BbBridge.CHIEF_NAME)
        .remove(BbBridge.NTFY_SERVER)
        .commit();
    new Attachments(context).clearPending();
  }

  @After
  public void cleanup() throws Exception {
    if (DictationService.busy()) {
      send(DictationService.STOP);
      Thread.sleep(800);
    }
    send(DictationService.HIDE);
    TestServer.reset();
    TestServer.bbReset();
    new BbBridge(context).disconnect();
    prefs.edit().remove(BbBridge.CHIEF_ID).remove(BbBridge.CHIEF_NAME).remove(BbBridge.MODE).commit();
    new Attachments(context).clearPending();
    device.pressHome();
  }

  @Test
  public void settingsConnectACustomAddressThenCreateTheChief() throws Exception {
    launch();
    tap("Settings");
    tap("Connection");
    find("bb Connect");
    tap("Custom address");
    find("https://…/mcp").setText(TestServer.bbUrl());
    find("https://<handle>.getbb.app").setText(LINKS);
    tap("Save and test");
    BbBridge bb = new BbBridge(context);
    long deadline = SystemClock.elapsedRealtime() + 15000;
    while (!bb.connected() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
    assertTrue(bb.connected());
    Thread.sleep(1000);
    assertEquals(BbBridge.CUSTOM, bb.mode());
    assertEquals(LINKS, bb.linkBase());
    // The test asked for the tools.
    JSONArray mcp = TestServer.bbRequests();
    boolean listed = false;
    for (int i = 0; i < mcp.length(); i++)
      listed |= mcp.getJSONObject(i).getString("method").equals("tools/list");
    assertTrue(listed);

    device.pressBack();
    find(By.textStartsWith("Custom address · 127.0.0.1"), "the connection");
    tap("Chief of staff");
    tap("Create chief of staff");
    deadline = SystemClock.elapsedRealtime() + 15000;
    while (new BbBridge(context).chiefId().isEmpty() && SystemClock.elapsedRealtime() < deadline)
      Thread.sleep(200);
    assertEquals(TestServer.CHIEF, new BbBridge(context).chiefId());
    String spawn = TestServer.bbSnippets().get(0);
    assertTrue(spawn, spawn.contains("talkrouter-paste"));
    assertTrue(prefs.getBoolean(BbBridge.CHIEF_KNOWS_PASTE, false));
    assertTrue(spawn, spawn.contains("\"thread\",\"spawn\",\"--project\",\"proj_personal\",\"--title\",\"Chief of staff\""));
    String topic = new Vault(context).get(BbBridge.NTFY_TOPIC);
    assertTrue(topic, topic.matches("talkrouter-[A-Za-z0-9_-]{24}"));
    assertTrue(spawn.contains("https:\\/\\/ntfy.sh\\/" + topic) || spawn.contains("https://ntfy.sh/" + topic));
    assertTrue(spawn.contains("threads\\/$BB_THREAD_ID") || spawn.contains("threads/$BB_THREAD_ID"));
    find("Open in bb ↗");

    device.pressBack();
    find("Chief of staff · " + TestServer.CHIEF);
    tap("Notifications");
    find(topic);
    find("Open in ntfy");
  }

  @Test
  public void addressedExplicitlyTheTaskGoesToTheChiefWithThePastedText() throws Exception {
    connectFake();
    new Attachments(context).addText("Flights: TP 1234 on 3 May, FR 88 on 4 May");
    TestServer.transcripts("tell my chief of staff to find the cheapest of these flights");
    TestServer.decide(0.05, 0.97, "none");
    dictate("copied");
    HistoryEntry e = latestOnce(n -> n.json.has("chiefThread"), "the chief");
    assertEquals(DictationService.DONE, DictationService.session().chief);

    // Jev was asked about the chief; Refine didn't run; the transcript came first, copied.
    assertTrue(sent("decide").get(0).getJSONObject("questions").has("chief"));
    assertTrue(sent("text").isEmpty());
    assertEquals("tell my chief of staff to find the cheapest of these flights", clipboard());

    // One tell to the chief, queued, with what was said, the pasted text and a request ID.
    List<String> snippets = TestServer.bbSnippets();
    assertEquals(1, snippets.size());
    String tell = snippets.get(0);
    assertTrue(tell, tell.contains("\"thread\",\"tell\",\"" + TestServer.CHIEF + "\",\"--mode\",\"queue\""));
    assertTrue(tell, tell.contains("tell my chief of staff to find the cheapest of these flights"));
    assertTrue(tell, tell.contains("Flights: TP 1234 on 3 May, FR 88 on 4 May"));
    String request = e.json.optString("chiefRequest");
    assertTrue(request, request.matches("[a-z0-9]{6}"));
    assertTrue(tell, tell.contains("[request: " + request + "]"));

    assertEquals(TestServer.CHIEF, e.json.optString("chiefThread"));
    assertEquals(CHIEF_LINK, e.json.optString("chiefLink"));
    assertFalse(e.json.has("chiefFailed"));
    assertEquals(0, TestServer.SPEECH.add(TestServer.JEV).compareTo(e.cost()));
    JSONArray steps = e.json.getJSONArray("steps");
    JSONObject bb = steps.getJSONObject(steps.length() - 1);
    assertEquals("bb", bb.getString("kind"));
    assertEquals("chief of staff", bb.getString("model"));
    assertFalse(bb.has("cost"));
    assertFalse(bb.has("detail"));
    assertEquals("refine 0.05 · chief 0.97", steps.getJSONObject(steps.length() - 2).getString("answer"));

    // The panel's Chief button became Open chat ↗.
    find("Open chat ↗");
    send(DictationService.HIDE);
    Thread.sleep(500);
    launch();
    tap("tell my chief of staff to find the cheapest of these flights");
    find("Sent to chief of staff ↗");
    find("What you said");

    launch();
    tap("Settings");
    tap("Activity");
    find(By.text(Pattern.compile("Jev · .* → refine 0\\.05 · chief 0\\.97")), "Jev's step");
    find(By.text(Pattern.compile("bb · Chief of staff · \\d+[.,]\\d s")), "the bb step");

    // The link opens the thread in the browser.
    launch();
    tap("tell my chief of staff to find the cheapest of these flights");
    find("Sent to chief of staff ↗").click();
    assertTrue(
        "the browser opens",
        device.wait(Until.gone(By.pkg(context.getPackageName()).depth(0)), 8000));
    device.pressBack();
  }

  @Test
  public void aBareImperativeIsNotSentButChiefIsSuggested() throws Exception {
    connectFake();
    TestServer.transcripts("look into why the nightly build failed and fix it");
    TestServer.decide(0.05, 0.62, "none");
    dictate("copied");
    long deadline = SystemClock.elapsedRealtime() + 8000;
    while (!DictationService.session().decided && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
    Thread.sleep(500);
    assertTrue(DictationService.session().hint);
    assertEquals(DictationService.IDLE, DictationService.session().chief);
    assertTrue(TestServer.bbSnippets().isEmpty());
    find(By.desc("Chief, suggested: this may be a task for your chief of staff"), "the hint");
    // One tap sends it.
    find("🧑‍💼 Chief").click();
    latestOnce(n -> n.json.has("chiefThread"), "the chief");
    assertEquals(1, TestServer.bbSnippets().size());
    find("Open chat ↗");
  }

  @Test
  public void chosenWhileRecordingTheChiefGetsItOnceTheTranscriptIsReady() throws Exception {
    connectFake();
    TestServer.transcripts("the build is red again");
    send(DictationService.RECORD);
    waitFor("recording");
    find("🧑‍💼 Chief").click();
    find("🧑‍💼 Chief ✓");
    assertTrue(TestServer.bbSnippets().isEmpty());
    send(DictationService.STOP);
    waitFor("copied");
    latestOnce(n -> n.json.has("chiefThread"), "the chief");
    assertTrue(TestServer.bbSnippets().get(0).contains("the build is red again"));
  }

  @Test
  public void aChiefFromBeforeIsToldOnceHowToSendTextBack() throws Exception {
    connectFake();
    prefs.edit().remove(BbBridge.CHIEF_KNOWS_PASTE).commit();
    TestServer.transcripts("chief of staff, book a table", "chief of staff, call the plumber");
    TestServer.decide(0, 0.98, "none");
    TestServer.decide(0, 0.98, "none");
    dictate("copied");
    latestOnce(n -> n.json.has("chiefThread"), "the chief");
    List<String> snippets = TestServer.bbSnippets();
    assertEquals(2, snippets.size());
    assertTrue(snippets.get(0).contains("talkrouter-paste"));
    assertTrue(snippets.get(1).contains("book a table"));
    send(DictationService.HIDE);
    Thread.sleep(500);
    dictate("copied");
    latestOnce(n -> n.json.optString("original").contains("plumber") && n.json.has("chiefThread"), "the chief");
    assertEquals(3, TestServer.bbSnippets().size());
  }

  @Test
  public void whenBbFailsTheNoteKeepsWhatYouSaidAndSendsAgain() throws Exception {
    connectFake();
    TestServer.bbFail("tell", "http503");
    TestServer.transcripts("ask my agent to book the table for Friday");
    TestServer.decide(0, 0.95, "none");
    dictate("copied");
    HistoryEntry e = latestOnce(n -> n.json.has("chiefFailed"), "the failure");
    assertTrue(e.json.optString("chiefFailed"), e.json.optString("chiefFailed").contains("isn't reachable right now (HTTP 503)"));
    JSONArray steps = e.json.getJSONArray("steps");
    assertTrue(steps.getJSONObject(steps.length() - 1).getString("detail").contains("HTTP 503"));
    assertEquals(DictationService.FAILED, DictationService.session().chief);
    find(By.textStartsWith("Not sent to your chief of staff:"), "the panel's message");
    find("🧑‍💼 Send again");

    send(DictationService.HIDE);
    Thread.sleep(500);
    launch();
    assertEquals("ask my agent to book the table for Friday", clipboard());
    tap("ask my agent to book the table for Friday");
    find(By.textStartsWith("Not sent to your chief of staff:"), "the failure");
    find("Copy");
    tap("Send again");
    find("Sent to chief of staff ↗");
    e = latest();
    assertFalse(e.json.has("chiefFailed"));
    assertEquals(CHIEF_LINK, e.json.optString("chiefLink"));
    List<String> snippets = TestServer.bbSnippets();
    assertTrue(snippets.get(snippets.size() - 1).contains("ask my agent to book the table for Friday"));
  }

  @Test
  public void withoutAChiefJevIsNotAskedAboutIt() throws Exception {
    TestServer.transcripts("tell my chief of staff to call the plumber");
    TestServer.decide(0, 0.99, "none"); // Even if Jev answered, nothing goes to bb.
    dictate("copied");
    long deadline = SystemClock.elapsedRealtime() + 8000;
    while (!DictationService.session().decided && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
    assertFalse(sent("decide").get(0).getJSONObject("questions").has("chief"));
    assertEquals(0, TestServer.bbRequests().length());
    assertNull(device.findObject(By.text("🧑‍💼 Chief")));
    send(DictationService.HIDE);
    launch();
    assertEquals("tell my chief of staff to call the plumber", clipboard());
  }

  @Test
  public void aCustomTokenGoesAsBearerAndAWrongOneIsExplained() throws Exception {
    TestServer.bbToken("s3cret");
    BbBridge bb = new BbBridge(context);
    bb.useCustom(TestServer.bbUrl(), "s3cret", LINKS);
    bb.test();
    JSONArray mcp = TestServer.bbRequests();
    assertEquals("Bearer s3cret", mcp.getJSONObject(mcp.length() - 1).getString("auth"));
    bb.useCustom(TestServer.bbUrl(), "wrong", LINKS);
    try {
      bb.test();
      fail("A wrong token must fail");
    } catch (java.io.IOException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("refused the token"));
    }
  }

  @Test
  public void anExistingThreadCanBeTheChiefAndIsToldWhereToNotify() throws Exception {
    BbBridge bb = new BbBridge(context);
    bb.useCustom(TestServer.bbUrl(), "", LINKS);
    BbBridge.ThreadInfo t = bb.useExistingChief(LINKS + "/threads/thr_existing7");
    assertEquals("thr_existing7", t.id());
    assertEquals("thr_existing7", bb.chiefId());
    List<String> snippets = TestServer.bbSnippets();
    assertTrue(snippets.get(0).contains("\"thread\",\"show\",\"thr_existing7\""));
    assertTrue(snippets.get(1).contains("\"thread\",\"tell\",\"thr_existing7\""));
    assertTrue(snippets.get(1).contains(bb.ntfyTopic()));
    TestServer.bbFail("show", "tool");
    try {
      bb.useExistingChief("thr_gone");
      fail("A missing thread must fail");
    } catch (java.io.IOException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().startsWith("bb couldn't do it:"));
    }
    assertEquals("thr_existing7", bb.chiefId());
  }
}
