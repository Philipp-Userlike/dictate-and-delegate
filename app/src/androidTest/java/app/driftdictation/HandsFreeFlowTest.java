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

  /** 1.2 s of speech (enough to take the turn), then a second and a half of quiet: one part. */
  void talk() throws Exception {
    HandsFreeSession s = HandsFreeService.session();
    assertNotNull(s);
    for (int i = 0; i < 60; i++) s.frame(HandsFreeTestFrames.frame(6000));
    for (int i = 0; i < 75; i++) s.frame(HandsFreeTestFrames.frame(50));
  }

  /** The last message from the call (not the call's start or end signal). */
  String lastTell() throws Exception {
    List<String> snippets = TestServer.bbSnippets();
    for (int i = snippets.size() - 1; i >= 0; i--)
      if (snippets.get(i).contains("\"thread\",\"tell\"") && snippets.get(i).contains("[request:")) return snippets.get(i);
    return "";
  }

  boolean told(String line) throws Exception {
    for (String code : TestServer.bbSnippets()) if (code.contains("\"thread\",\"tell\"") && code.contains(line)) return true;
    return false;
  }

  @Test
  public void talkSendByWordHearTheReplyThenEndByWord() throws Exception {
    // At each longer pause the whole turn so far is written down and Jev asked whether it's done.
    TestServer.transcripts(
        "I've been thinking about the trip.",
        "I've been thinking about the trip, book a table for Friday. Over.",
        "End the call.");
    TestServer.turns("unfinished", "over", "end");
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.session() != null, "hands-free started");
    talk();
    until(() -> HandsFreeService.session().pending().equals("I've been thinking about the trip."), "the first part");
    assertEquals("", lastTell());
    talk();
    until(() -> lastTell().contains("[hands-free]"), "the message sent");
    String tell = lastTell();
    // The whole turn's text, from the second pass.
    assertTrue(tell, tell.contains("I've been thinking about the trip, book a table for Friday."));
    assertFalse(tell, tell.contains("Over"));
    Matcher m = Pattern.compile("\\[request: ([a-z0-9]+)\\]").matcher(tell);
    assertTrue(tell, m.find());
    String request = m.group(1);
    // The turn written down at each of its two pauses, and Jev asked each time.
    JSONArray r = TestServer.requests();
    int transcriptions = 0, decisions = 0;
    for (int i = 0; i < r.length(); i++) {
      if (r.getJSONObject(i).getString("kind").equals("transcribe")) transcriptions++;
      if (r.getJSONObject(i).getString("kind").equals("decide")) decisions++;
    }
    assertEquals(2, transcriptions);
    assertEquals(2, decisions);
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
    // Its audio is fetched first (the floor stays open meanwhile), then it's read.
    until(() -> HandsFreeService.session().state() == HandsFreeSession.State.READING, "reading the reply");
    until(() -> HandsFreeService.session().state() == HandsFreeSession.State.LISTENING, "listening again after reading");
    // Reading it cost $0.0001 (the fake's generation); the note of that message has it, on top of
    // the two transcriptions ($0.001 each) and Jev's two answers ($0.00002 each); the call total
    // counts them all.
    until(
        () -> {
          try (HistoryStore h = new HistoryStore(context)) {
            HistoryEntry e = h.list(1, 0).get(0);
            return e.voiceCost().compareTo(new java.math.BigDecimal("0.0001")) == 0;
          }
        },
        "the voice's cost on the note");
    try (HistoryStore h = new HistoryStore(context)) {
      HistoryEntry e = h.list(1, 0).get(0);
      assertEquals(0, new java.math.BigDecimal("0.00214").compareTo(e.cost()));
      assertEquals("hexgrad/kokoro-82m", e.json.optString("voiceModel"));
      JSONArray steps = e.json.getJSONArray("steps");
      assertEquals("voice", steps.getJSONObject(steps.length() - 1).getString("kind"));
      assertTrue(e.json.getJSONArray("handsFreeLog").length() > 0);
      assertFalse(e.json.optString("call").isEmpty());
    }
    assertEquals(0, new java.math.BigDecimal("0.00214").compareTo(HandsFreeService.callCost));
    int speech = 0;
    JSONArray all = TestServer.requests();
    for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("kind").equals("speech")) speech++;
    assertEquals(1, speech);
    // "End the call" ends hands-free (once the microphone is back after the reply's tail).
    Thread.sleep(HandsFreeSession.TAIL_MS + 300);
    talk();
    until(() -> HandsFreeService.instance == null, "hands-free ended by the end word");
    // The chief was told when the call started and ended.
    assertTrue(told("[hands-free call started]"));
    until(() -> told("[hands-free call ended]"), "the call's end told");
  }

  @Test
  public void theScreenStartsSendsAndEnds() throws Exception {
    TestServer.transcripts("Remind me to call Jana");
    TestServer.turns("unfinished"); // Not done at the pause: Send sends it.
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
    for (int i = 0; i < 60; i++) s.frame(HandsFreeTestFrames.frame(6000));
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
    // A first real answer; a final one only if the request needs work, so a minute more for it.
    long deadline = SystemClock.elapsedRealtime() + 5 * 60_000;
    List<String> spoken = new java.util.ArrayList<>();
    while (SystemClock.elapsedRealtime() < deadline && spoken.size() < 2) {
      spoken.clear();
      JSONArray all = TestServer.requests();
      for (int i = 0; i < all.length(); i++)
        if (all.getJSONObject(i).getString("kind").equals("speech"))
          spoken.add(all.getJSONObject(i).getJSONObject("body").getString("input"));
      if (spoken.size() == 1) deadline = Math.min(deadline, SystemClock.elapsedRealtime() + 60_000);
      Thread.sleep(1000);
    }
    for (String t : spoken) android.util.Log.i("TalkRouterReal", "spoken: " + t);
    assertFalse("an answer, read aloud", spoken.isEmpty());
    for (String t : spoken) {
      assertFalse(t, t.contains("|") || t.contains("**") || t.contains("```") || t.contains("\n- "));
    }
    assertFalse("a real first answer, not a filler: " + spoken.get(0), spoken.get(0).toLowerCase().contains("one moment"));
  }

  /**
   * Opt-in, against a real bb and ntfy (the same arguments as above): a turn ending in "over" and
   * one sent with the call bar's send button both reach a real chief, and its spoken replies play
   * to the end. Logs the hands-free events with their timings (tag TalkRouterReal).
   */
  @Test
  public void realChiefGetsTurnsByWordAndByButtonAndRepliesPlayToTheEnd() throws Exception {
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
    device.executeShellCommand("appops set app.driftdictation SYSTEM_ALERT_WINDOW allow");
    TestServer.transcripts(
        "Quick test call: is the Atlantic bigger than the Indian Ocean? Answer in one short sentence. Over.",
        "And which one is deeper? One short sentence");
    // The first turn ends at its pause (done), the second isn't done there and goes by the button.
    TestServer.turns("question", "unfinished");
    // Only this call's events count: the log goes on across calls.
    HandsFreeService.logFile(context).delete();
    try {
      realTurnsByWordAndButton();
    } finally {
      for (String line : HandsFreeService.log(context).split("\n")) android.util.Log.i("TalkRouterReal", line);
    }
  }

  private static int count(String log, String what) {
    return log.split(java.util.regex.Pattern.quote(what), -1).length - 1;
  }

  private void realTurnsByWordAndButton() throws Exception {
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.session() != null, "hands-free started");
    // 1. A turn ending in the send word.
    talk();
    until(() -> HandsFreeService.session().sent() == 1, "the turn ending in over sent");
    long deadline = SystemClock.elapsedRealtime() + 4 * 60_000;
    while (SystemClock.elapsedRealtime() < deadline && count(HandsFreeService.log(context), "reply played to the end") == 0)
      Thread.sleep(1000);
    assertTrue("the first reply played to the end", count(HandsFreeService.log(context), "reply played to the end") > 0);
    // Wait until nothing is read, so the next turn starts on an open floor.
    deadline = SystemClock.elapsedRealtime() + 2 * 60_000;
    while (SystemClock.elapsedRealtime() < deadline && HandsFreeService.session().phase() == HandsFreeSession.Phase.READING)
      Thread.sleep(500);
    // The microphone stays muted a moment after a reply (its echo), as on the phone.
    Thread.sleep(1500);
    // 2. A turn without a send word, sent with the call bar's send button.
    talk();
    UiObject2 send = device.wait(Until.findObject(By.desc("Send now")), 5000);
    assertNotNull("the call bar's send button", send);
    send.click();
    until(() -> HandsFreeService.session().sent() == 2, "the turn sent with the button");
    int played = count(HandsFreeService.log(context), "reply played to the end");
    deadline = SystemClock.elapsedRealtime() + 4 * 60_000;
    while (SystemClock.elapsedRealtime() < deadline && count(HandsFreeService.log(context), "reply played to the end") <= played)
      Thread.sleep(1000);
    String log = HandsFreeService.log(context);
    assertTrue("the second reply played to the end", count(log, "reply played to the end") > played);
    assertFalse("no reply cut off", log.contains("the reply played too long") || log.contains("the reply's audio didn't start"));
    HandsFreeService.send(context, HandsFreeService.END);
    until(() -> HandsFreeService.instance == null, "ended");
  }

  /** One message out ("over"), then the chief's spoken reply to it; returns the hands-free log afterwards. */
  private String callWithOneReply(String reply, long waitForMs, String expect) throws Exception {
    HandsFreeService.logFile(context).delete();
    TestServer.transcripts("Is the table booked? Over.", "Is the table booked? Over.");
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.session() != null, "hands-free started");
    talk();
    until(() -> lastTell().contains("[hands-free]"), "the message sent");
    Matcher m = Pattern.compile("\\[request: ([a-z0-9]+)\\]").matcher(lastTell());
    assertTrue(m.find());
    TestServer.ntfySpeak(new BbBridge(context).ntfyTopic(), m.group(1), reply);
    long deadline = SystemClock.elapsedRealtime() + waitForMs;
    while (SystemClock.elapsedRealtime() < deadline && !HandsFreeService.log(context).contains(expect)) Thread.sleep(200);
    String log = HandsFreeService.log(context);
    HandsFreeService.send(context, HandsFreeService.END);
    until(() -> HandsFreeService.instance == null, "ended");
    return log;
  }

  @Test
  public void aSlowVoiceAnswerIsBeatenByASecondRequest() throws Exception {
    // OpenRouter's voice stalls now and then (seconds, any provider): a second request after 1 s
    // wins, and the reply is still read in the chosen voice, not Android's.
    TestServer.script("speech", new JSONObject().put("delay", 4));
    String log = callWithOneReply("The table is booked for Friday at eight.", 15_000, "reply played to the end");
    assertTrue(log, log.contains("second request won"));
    assertFalse(log, log.contains("Android's voice reads it"));
    assertTrue(log, log.contains("reply played to the end"));
  }

  @Test
  public void wordsNotSentWhenTheCallEndsGoBeforeTheEndSignal() throws Exception {
    TestServer.transcripts("And also look at the latency");
    TestServer.turns("more"); // Not done at the pause.
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.session() != null, "hands-free started");
    talk();
    until(() -> HandsFreeService.session().pending().equals("And also look at the latency"), "the words");
    HandsFreeService.send(context, HandsFreeService.END);
    until(() -> told("[hands-free call ended]"), "the call's end told");
    int words = -1, end = -1;
    List<String> snippets = TestServer.bbSnippets();
    for (int i = 0; i < snippets.size(); i++) {
      if (snippets.get(i).contains("And also look at the latency")) words = i;
      if (snippets.get(i).contains("[hands-free call ended]")) end = i;
    }
    assertTrue("the last words sent", words >= 0);
    assertTrue("before the end signal", words < end);
    assertTrue(HandsFreeService.log(context).contains("they're sent before the call's end"));
  }

  @Test
  public void whenTheVoiceModelNeverAnswersAndroidsVoiceReadsTheWholeReply() throws Exception {
    // 3.2.7: Kokoro never answered, and Android's voice then failed too ("the voice stopped").
    for (int i = 0; i < 3; i++) TestServer.script("speech", new JSONObject().put("delay", 30));
    String log = callWithOneReply("The table is booked for Friday at eight.", 40_000, "reply played to the end");
    assertTrue(log, log.contains("Android's voice reads it"));
    assertTrue(log, log.contains("audio started"));
    assertTrue(log, log.contains("reply played to the end"));
    assertFalse(log, log.contains("trying again"));
    assertFalse(log, log.contains("reply not heard"));
  }

  @Test
  public void onAPhoneWithoutAndroidsVoiceASlowReplyIsWaitedForNotLost() throws Exception {
    // Some phones (GrapheneOS) have no text-to-speech engine: falling back to it lost the reply.
    device.executeShellCommand("pm disable-user --user 0 com.google.android.tts");
    try {
      Thread.sleep(500);
      TestServer.script("speech", new JSONObject().put("delay", 5));
      String log = callWithOneReply("The table is booked for Friday at eight.", 30_000, "reply played to the end");
      assertTrue(log, log.contains("Android's voice: not available"));
      assertTrue(log, log.contains("reply played to the end"));
      assertFalse(log, log.contains("Android's voice reads it") || log.contains("reply not heard"));
    } finally {
      device.executeShellCommand("pm enable com.google.android.tts");
    }
  }

  @Test
  public void androidsVoiceReadsOneReplyAfterAnother() throws Exception {
    // The second reply read by Android's voice must not be ended by the first one's engine.
    for (int i = 0; i < 6; i++) TestServer.script("speech", new JSONObject().put("delay", 30));
    HandsFreeService.logFile(context).delete();
    TestServer.transcripts("Is the table booked? Over.");
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.session() != null, "hands-free started");
    talk();
    until(() -> lastTell().contains("[hands-free]"), "the message sent");
    Matcher m = Pattern.compile("\\[request: ([a-z0-9]+)\\]").matcher(lastTell());
    assertTrue(m.find());
    TestServer.ntfySpeak(new BbBridge(context).ntfyTopic(), m.group(1), "First, the table is booked.");
    TestServer.ntfySpeak(new BbBridge(context).ntfyTopic(), m.group(1), "Second, it's for Friday at eight.");
    long deadline = SystemClock.elapsedRealtime() + 40_000;
    while (SystemClock.elapsedRealtime() < deadline && count(HandsFreeService.log(context), "reply played to the end") < 2) Thread.sleep(200);
    String log = HandsFreeService.log(context);
    assertEquals(log, 2, count(log, "reply played to the end"));
    assertFalse(log, log.contains("trying again") || log.contains("reply not heard"));
  }

  @Test
  public void onlyWhenEveryVoiceRequestIsSlowAndroidsVoiceReads() throws Exception {
    TestServer.script("speech", new JSONObject().put("delay", 6));
    TestServer.script("speech", new JSONObject().put("delay", 6));
    TestServer.script("speech", new JSONObject().put("delay", 6));
    String log = callWithOneReply("The table is booked for Friday at eight.", 20_000, "Android's voice reads it");
    assertTrue(log, log.contains("Kokoro: no answer in 3.0 s"));
    assertTrue(log, log.contains("Android's voice reads it"));
  }

  @Test
  public void aVoiceThatSendsRawSamplesIsPlayedToTheEnd() throws Exception {
    // Gemini and HeyGen send only PCM: wrapped as WAV at the rate their Content-Type says.
    prefs.edit().putString(HandsFree.VOICE, "gemini-kore").commit();
    try {
      String log = callWithOneReply("The table is booked for Friday at eight.", 15_000, "reply played to the end");
      assertTrue(log, log.contains("voice google/gemini-3.8-flash-lite-tts for"));
      assertTrue(log, log.contains("reply played to the end"));
      assertFalse(log, log.contains("Voice failed") || log.contains("Android's voice reads it"));
      JSONArray all = TestServer.requests();
      boolean pcm = false;
      for (int i = 0; i < all.length(); i++)
        if (all.getJSONObject(i).getString("kind").equals("speech")) pcm |= all.getJSONObject(i).getJSONObject("body").optString("response_format").equals("pcm");
      assertTrue("asked for PCM", pcm);
    } finally {
      prefs.edit().putString(HandsFree.VOICE, Voice.KOKORO.id()).commit();
    }
  }

  @Test
  public void aVoiceThatFailsSaysWhyAndTheReplyIsStillRead() throws Exception {
    for (int i = 0; i < 3; i++) TestServer.script("speech", new JSONObject().put("status", 503).put("message", "No provider available"));
    String log = callWithOneReply("The table is booked for Friday at eight.", 20_000, "reply played to the end");
    assertTrue(log, log.contains("HTTP 503: No provider available"));
    assertTrue(log, log.contains("→ Voice failed (Kokoro: HTTP 503: No provider available): Android's voice reads the reply"));
    assertTrue(log, log.contains("reply played to the end"));
  }

  @Test
  public void theDictationPanelsCallButtonSwitchesToHandsFreeKeepingWhatWasSaid() throws Exception {
    TestServer.transcripts("I was dictating about the trip");
    device.executeShellCommand("appops set app.driftdictation SYSTEM_ALERT_WINDOW allow");
    // The dictation panel somewhere else than the call panel's own place.
    prefs.edit()
        .putString("bubbleMode", "recording")
        .putInt("bubbleX", 140)
        .putInt("bubbleY", 900)
        .putInt(HandsFreePanel.X, 16)
        .putInt(HandsFreePanel.Y, 300)
        .commit();
    DictationService.send(context, DictationService.RECORD);
    long deadline = SystemClock.elapsedRealtime() + 10_000;
    while (!"recording".equals(prefs.getString("state", "")) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
    Thread.sleep(1200);
    UiObject2 call = device.wait(Until.findObject(By.desc("Hands-free call with your chief")), 8000);
    assertNotNull("the 📞 on the dictation panel", call);
    int[] dictationPanel = DictationService.panelPosition();
    call.click();
    until(() -> HandsFreeService.session() != null, "hands-free started from the dictation");
    until(() -> "I was dictating about the trip".equals(HandsFreeService.session().pending()), "the dictation became the first turn");
    // The dictation itself left no note and no saved audio.
    assertFalse(DictationService.audio(context).exists());
    // The call floats: its panel shows the state and End.
    assertNotNull(device.wait(Until.findObject(By.text("Not sent yet")), 5000));
    assertNotNull(device.wait(Until.findObject(By.desc("End hands-free")), 5000));
    // Exactly where the dictation panel was, and its own place stays as it was.
    assertArrayEquals(dictationPanel, HandsFreeService.panelPosition());
    assertEquals(300, prefs.getInt(HandsFreePanel.Y, 0));
  }

  @Test
  public void startedFromHomeTheCallPanelIsWhereItWasLastDragged() throws Exception {
    device.executeShellCommand("appops set app.driftdictation SYSTEM_ALERT_WINDOW allow");
    prefs.edit().putInt(HandsFreePanel.X, 60).putInt(HandsFreePanel.Y, 500).commit();
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.panelPosition() != null, "the call panel");
    assertArrayEquals(new int[] {60, 500}, HandsFreeService.panelPosition());
  }

  @Test
  public void aCallIsOneEntryInAllNotesWithItsMessagesAndCost() throws Exception {
    long now = System.currentTimeMillis();
    try (HistoryStore h = new HistoryStore(context)) {
      for (HistoryEntry old : h.all()) h.delete(old.id()); // Only this call on the list.
      String[] said = {"First message of the call.", "Second message of the call."};
      for (int i = 0; i < 2; i++) {
        HistoryEntry e = HistoryEntry.create(java.util.UUID.randomUUID().toString(), "m", 3);
        e.json
            .put("created", now - 60_000 + i * 20_000)
            .put("text", said[i])
            .put("status", "complete")
            .put("requests", 1)
            .put("handsFree", true)
            .put("route", "chief")
            .put("call", "call-test")
            .put("knownCost", "0.0011");
        h.save(e);
      }
    }
    prefs.edit().putLong("historyRevision", System.nanoTime()).commit();
    context.startActivity(
        new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    UiObject2 all = device.wait(Until.findObject(By.text("All notes")), 8000);
    assertNotNull(all);
    all.click();
    UiObject2 call = device.wait(Until.findObject(By.text("▸ 📞 Call with your chief · 2 messages")), 8000);
    assertNotNull("the call as one entry", call);
    // Its cost: both messages (transcription and voice).
    assertNotNull(device.wait(Until.findObject(By.textContains("$0.0022")), 3000));
    assertNull(device.findObject(By.text("Second message of the call.")));
    call.click();
    assertNotNull("opened, its messages follow", device.wait(Until.findObject(By.text("Second message of the call.")), 3000));
    assertNotNull(device.findObject(By.text("▾ 📞 Call with your chief · 2 messages")));
  }

  @Test
  public void theCallPanelIsCompactAndFitsTheScreenInEveryState() throws Exception {
    device.executeShellCommand("appops set app.driftdictation SYSTEM_ALERT_WINDOW allow");
    // Dragged to the right edge last time: it must still show every button.
    prefs.edit().putInt(HandsFreePanel.X, 5000).putInt(HandsFreePanel.Y, 600).commit();
    TestServer.transcripts("Book the hotel. Over.");
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.panelPosition() != null, "the call panel");
    assertNotNull(device.wait(Until.findObject(By.text("Listening")), 5000));
    until(HandsFreeService::panelFits, "every button on screen while listening");
    shot("call-bar-listening");
    talk();
    until(() -> HandsFreeService.session().sent() == 1, "the message sent");
    // Sent, then waiting for the chief: a short label on the bar, the full words for TalkBack;
    // send and hang-up as icons.
    assertNotNull(device.wait(Until.findObject(By.text("Working…")), 8000));
    assertNotNull(device.findObject(By.desc("Waiting for the chief · you can talk")));
    assertNotNull(device.findObject(By.desc("Send now"))); // The paper plane.
    assertNotNull(device.findObject(By.desc("End hands-free")));
    until(HandsFreeService::panelFits, "every button on screen while the chief works");
    shot("call-bar-working");
  }

  /**
   * With -e shots true only: the hands-free screen listening, with a reason, and with a reply
   * waiting while you talk; and the call bar (for the release QA).
   */
  @Test
  public void screenshotsOfTheLiveStateAndItsReasons() throws Exception {
    Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("shots")));
    device.executeShellCommand("appops set app.driftdictation SYSTEM_ALERT_WINDOW allow");
    prefs.edit().putInt(HandsFreePanel.X, 40).putInt(HandsFreePanel.Y, 1500).commit();
    TestServer.transcripts("Book a table for Friday. Over.", "Also check the train");
    TestServer.turns("over", "unfinished");
    context.startActivity(
        new Intent(context, MainActivity.class).putExtra("open", "handsfree").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    UiObject2 start = device.wait(Until.findObject(By.text("Start hands-free")), 8000);
    assertNotNull(start);
    start.click();
    UiObject2 deny = device.wait(Until.findObject(By.textStartsWith("Don")), 3000);
    if (deny != null) deny.click();
    until(() -> HandsFreeService.session() != null, "hands-free started");
    HandsFreeSession s = HandsFreeService.session();
    for (int i = 0; i < 50; i++) s.frame(HandsFreeTestFrames.frame(50));
    assertNotNull(device.wait(Until.findObject(By.textContains("Listening")), 5000));
    shot("hf-1-listening");
    // Send with nothing said: ignored, and the screen says why.
    device.wait(Until.findObject(By.text("Send now")), 5000).click();
    assertNotNull(device.wait(Until.findObject(By.textContains("Send ignored: nothing heard yet")), 5000));
    shot("hf-2-reason");
    // A message, then a reply arriving while you talk: it waits.
    talk();
    until(() -> s.sent() == 1, "the message sent");
    Matcher m = Pattern.compile("\\[request: ([a-z0-9]+)\\]").matcher(lastTell());
    assertTrue(m.find());
    Thread.sleep(3500); // Past Sent ✓.
    for (int i = 0; i < 60; i++) s.frame(HandsFreeTestFrames.frame(6000));
    TestServer.ntfySpeak(new BbBridge(context).ntfyTopic(), m.group(1), "The table is booked for Friday at eight.");
    until(() -> s.held() == 1, "the reply waiting");
    // A pause long enough to end the part, shorter than the reply's 1.5 s: it still waits.
    for (int i = 0; i < 55; i++) s.frame(HandsFreeTestFrames.frame(50));
    until(() -> s.pending().equals("Also check the train"), "your words");
    assertNotNull(device.wait(Until.findObject(By.textContains("Reply waiting")), 5000));
    shot("hf-3-reply-waiting");
    // The call bar over another app.
    device.pressHome();
    Thread.sleep(800);
    assertNotNull(device.wait(Until.findObject(By.desc("End hands-free")), 5000));
    shot("hf-4-call-bar");
  }

  /** With -e shots true: a screenshot on the device, in the app's files/panel. */
  void shot(String name) throws Exception {
    if (!"true".equals(InstrumentationRegistry.getArguments().getString("shots"))) return;
    Thread.sleep(700);
    java.io.File dir = new java.io.File(context.getExternalFilesDir(null), "panel");
    dir.mkdirs();
    device.takeScreenshot(new java.io.File(dir, name + ".png"));
    // Also where it outlives the test's uninstall: adb pull /data/local/tmp/drift-shots.
    device.executeShellCommand("mkdir -p /data/local/tmp/drift-shots");
    device.executeShellCommand("screencap -p /data/local/tmp/drift-shots/" + name + ".png");
  }

  @Test
  public void talkingOverAReplyStopsItAndYourMessageSaysWhatYouHeard() throws Exception {
    // The message; your words over the reply (not done yet); then "over" after them.
    TestServer.transcripts("Book a table for Friday. Over.", "Hold on, book the train instead", "Hold on, book the train instead. Over.");
    TestServer.turns("over", "unfinished", "over");
    HandsFreeService.send(context, HandsFreeService.START);
    until(() -> HandsFreeService.session() != null, "hands-free started");
    talk();
    until(() -> HandsFreeService.session().sent() == 1, "the message sent");
    Matcher m = Pattern.compile("\\[request: ([a-z0-9]+)\\]").matcher(lastTell());
    assertTrue(m.find());
    TestServer.ntfySpeak(
        new BbBridge(context).ntfyTopic(), m.group(1), "The flight leaves at nine in the morning from gate twelve, and the train costs about forty euros.");
    HandsFreeSession s = HandsFreeService.session();
    until(s::audioPlaying, "the reply playing");
    // What's left of its echo, then you over it: the reply stops, and what you said is your turn.
    for (int i = 0; i < 50; i++) s.frame(HandsFreeTestFrames.frame(300));
    assertTrue("its echo doesn't stop it", s.audioPlaying());
    for (int i = 0; i < 20; i++) s.frame(HandsFreeTestFrames.frame(8000));
    assertEquals("stopped at once", HandsFreeSession.State.LISTENING, s.state());
    for (int i = 0; i < 75; i++) s.frame(HandsFreeTestFrames.frame(50));
    until(() -> s.pending().equals("Hold on, book the train instead"), "your interruption, your turn");
    assertTrue(HandsFreeService.log(context).contains("barge-in: speech 300 ms → stopped"));
    // The rest of the reply is dropped: your next message says how far you heard it.
    talk();
    long deadline = SystemClock.elapsedRealtime() + 20_000;
    while (SystemClock.elapsedRealtime() < deadline && HandsFreeService.session().sent() < 2) Thread.sleep(100);
    assertEquals(HandsFreeService.log(context), 2, HandsFreeService.session().sent());
    String tell = lastTell();
    assertTrue(tell, tell.contains("Hold on, book the train instead"));
    assertTrue(tell, tell.contains("[heard up to: ") || tell.contains("[didn't hear your reply: "));
    Thread.sleep(3000);
    assertEquals("never played again", 1, count(HandsFreeService.log(context), "floor: chief, reading " + m.group(1)));
  }
}
