package app.driftdictation;

import static org.junit.Assert.*;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

/**
 * Hands-free mode against the fakes: the microphone is replaced by frames the test feeds, speech
 * and the voice come from the fake OpenRouter, the chief from the fake bb, replies from the fake
 * ntfy.
 */
@RunWith(AndroidJUnit4.class)
public class HandsFreeFlowTest {
  static final String LINKS = "https://someone.getbb.app";
  Context context;
  UiDevice device;
  SharedPreferences prefs;

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    prefs = context.getSharedPreferences("settings", 0);
    device.executeShellCommand("pm grant app.driftdictation android.permission.RECORD_AUDIO");
    device.executeShellCommand("cmd locale set-app-locales app.driftdictation --locales en-US");
    device.executeShellCommand("input keyevent KEYCODE_WAKEUP");
    TestServer.reset();
    TestServer.bbReset();
    TestServer.useKey(context);
    prefs
        .edit()
        .putBoolean("onboarded", true)
        .putInt(HandsFree.PAUSE_MS, 1000)
        .putInt(HandsFree.SILENCE_SEND_MS, 30_000)
        .remove(HandsFree.SEND_WORDS)
        .remove(HandsFree.END_WORDS)
        .remove(HandsFree.STOP_WORDS)
        .putString(HandsFree.VOICE, Voice.KOKORO.id())
        .commit();
    BbBridge bb = new BbBridge(context);
    bb.useCustom(TestServer.bbUrl(), "", LINKS);
    bb.setChief(new BbBridge.ThreadInfo(TestServer.CHIEF, "Chief of staff", "idle"));
    bb.setNtfyServer(TestServer.ntfyRoot());
    TestServer.skillsCurrent(context);
    HandsFreeService.noMicrophone = true;
  }

  @After
  public void tearDown() throws Exception {
    HandsFreeService.send(context, HandsFreeService.END);
    until(() -> HandsFreeService.instance == null, "hands-free ended");
    HandsFreeService.noMicrophone = false;
  }

  void until(Callable<Boolean> c, String what) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 20_000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (c.call()) return;
      Thread.sleep(100);
    }
    fail("Never happened: " + what);
  }

  /** Half a second of speech, then a second and a half of quiet: one part. */
  void talk() throws Exception {
    HandsFreeSession s = HandsFreeService.session();
    assertNotNull(s);
    for (int i = 0; i < 25; i++) s.frame(HandsFreeTestFrames.frame(6000));
    for (int i = 0; i < 75; i++) s.frame(HandsFreeTestFrames.frame(50));
  }

  String lastTell() throws Exception {
    List<String> snippets = TestServer.bbSnippets();
    for (int i = snippets.size() - 1; i >= 0; i--) if (snippets.get(i).contains("\"thread\",\"tell\"")) return snippets.get(i);
    return "";
  }

  @Test
  public void talkSendByWordHearTheReplyThenEndByWord() throws Exception {
    TestServer.transcripts("I've been thinking about the trip.", "Book a table for Friday. Over.", "That's all.");
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.session() != null, "hands-free started");
    talk();
    until(() -> HandsFreeService.session().pending().equals("I've been thinking about the trip."), "the first part");
    assertEquals("", lastTell());
    talk();
    until(() -> lastTell().contains("[hands-free]"), "the message sent");
    String tell = lastTell();
    assertTrue(tell, tell.contains("I've been thinking about the trip. Book a table for Friday."));
    assertFalse(tell, tell.contains("Over"));
    Matcher m = Pattern.compile("\\[request: ([a-z0-9]+)\\]").matcher(tell);
    assertTrue(tell, m.find());
    String request = m.group(1);
    // Each part transcribed once: two transcriptions so far.
    JSONArray r = TestServer.requests();
    int transcriptions = 0;
    for (int i = 0; i < r.length(); i++) if (r.getJSONObject(i).getString("kind").equals("transcribe")) transcriptions++;
    assertEquals(2, transcriptions);
    // The note keeps it, with the chief's thread.
    try (HistoryStore h = new HistoryStore(context)) {
      HistoryEntry e = h.list(1, 0).get(0);
      assertTrue(e.json.optBoolean("handsFree"));
      assertEquals(request, e.json.optString("chiefRequest"));
    }
    // The chief's quick reaction is read aloud, then it listens again.
    TestServer.ntfySpeak(new BbBridge(context).ntfyTopic(), "notours", "Not for this session.");
    TestServer.ntfySpeak(new BbBridge(context).ntfyTopic(), request, "Good idea, let me check.");
    until(
        () -> {
          JSONArray all = TestServer.requests();
          for (int i = 0; i < all.length(); i++) {
            JSONObject q = all.getJSONObject(i);
            if (q.getString("kind").equals("speech")) return q.getJSONObject("body").getString("input").equals("Good idea, let me check.");
          }
          return false;
        },
        "the reply sent to the voice model");
    until(() -> HandsFreeService.session().state() == HandsFreeSession.State.LISTENING, "listening again after reading");
    int speech = 0;
    JSONArray all = TestServer.requests();
    for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("kind").equals("speech")) speech++;
    assertEquals(1, speech);
    // "That's all" ends hands-free.
    talk();
    until(() -> HandsFreeService.instance == null, "hands-free ended by the end word");
  }

  @Test
  public void theScreenStartsSendsAndEnds() throws Exception {
    TestServer.transcripts("Remind me to call Jana");
    context.startActivity(
        new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    UiObject2 entry = device.wait(Until.findObject(By.text("🎧 Hands-free with your chief")), 8000);
    assertNotNull(entry);
    entry.click();
    UiObject2 start = device.wait(Until.findObject(By.text("Start hands-free")), 5000);
    assertNotNull(start);
    start.click();
    // Android asks for notifications (the lock-screen controls); the mode works either way.
    UiObject2 deny = device.wait(Until.findObject(By.textStartsWith("Don")), 3000);
    if (deny != null) deny.click();
    until(() -> HandsFreeService.session() != null, "hands-free started");
    // Speech without a send word, then the screen's Send now.
    HandsFreeSession s = HandsFreeService.session();
    for (int i = 0; i < 25; i++) s.frame(HandsFreeTestFrames.frame(6000));
    for (int i = 0; i < 75; i++) s.frame(HandsFreeTestFrames.frame(50));
    until(() -> HandsFreeService.session().pending().equals("Remind me to call Jana"), "the part");
    UiObject2 send = device.wait(Until.findObject(By.text("Send now")), 5000);
    if (send == null) {
      StringBuilder seen = new StringBuilder();
      for (UiObject2 o : device.findObjects(By.textContains(""))) seen.append(" | ").append(o.getText());
      fail("No Send now; saw" + seen);
    }
    send.click();
    until(() -> lastTell().contains("Remind me to call Jana"), "sent by the button");
    device.wait(Until.findObject(By.text("End hands-free")), 5000).click();
    until(() -> HandsFreeService.instance == null, "ended on screen");
  }

  /**
   * Opt-in check against a real bb and ntfy (as PanelTest's: -e realMcp, realLinks, realNtfy,
   * realTitle): a test chief with the filled skills as its first message gets a hands-free message
   * and has to answer with a quick spoken reaction and then a spoken answer, both read aloud.
   */
  @Test
  public void realChiefAnswersHandsFreeInSpeakableReplies() throws Exception {
    android.os.Bundle args = InstrumentationRegistry.getArguments();
    Assume.assumeTrue(args.getString("realMcp") != null);
    BbBridge bb = new BbBridge(context);
    bb.useCustom(args.getString("realMcp"), "", args.getString("realLinks"));
    bb.setNtfyServer(args.getString("realNtfy"));
    TestServer.skillsCurrent(context);
    java.util.Map<String, String> files = ChiefSkills.filled(context);
    String prompt =
        "For this test, follow these two skills as if they were installed, then reply \"ready\".\n\n"
            + files.get(ChiefSkills.CHIEF)
            + "\n"
            + files.get(ChiefSkills.ROUTER);
    JSONObject created =
        bb.run(BbBridge.spawnSnippet(prompt).replace(JSONObject.quote(BbBridge.CHIEF_TITLE), JSONObject.quote(args.getString("realTitle"))));
    android.util.Log.i("TalkRouterReal", "chief " + created.getString("id"));
    bb.setChief(new BbBridge.ThreadInfo(created.getString("id"), args.getString("realTitle"), created.optString("status")));
    TestServer.transcripts("Which is bigger, the Atlantic or the Indian Ocean, and by roughly how much? Over.");
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.session() != null, "hands-free started");
    talk();
    until(() -> HandsFreeService.session().sent() == 1, "the message sent");
    long deadline = SystemClock.elapsedRealtime() + 5 * 60_000;
    List<String> spoken = new java.util.ArrayList<>();
    while (SystemClock.elapsedRealtime() < deadline && spoken.size() < 2) {
      spoken.clear();
      JSONArray all = TestServer.requests();
      for (int i = 0; i < all.length(); i++)
        if (all.getJSONObject(i).getString("kind").equals("speech"))
          spoken.add(all.getJSONObject(i).getJSONObject("body").getString("input"));
      Thread.sleep(1000);
    }
    for (String t : spoken) android.util.Log.i("TalkRouterReal", "spoken: " + t);
    assertTrue("a quick reaction and an answer, read aloud: " + spoken, spoken.size() >= 2);
    for (String t : spoken) {
      assertFalse(t, t.contains("|") || t.contains("**") || t.contains("```") || t.contains("\n- "));
    }
  }
}
