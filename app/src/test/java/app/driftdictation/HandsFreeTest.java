package app.driftdictation;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;
import org.junit.Test;

public class HandsFreeTest {
  final List<String> send = HandsFree.list(HandsFree.DEFAULT_SEND), end = HandsFree.list(HandsFree.DEFAULT_END);

  @Test
  public void wordListsComeFromTheSetting() {
    // English, one word per action, by default.
    assertEquals(List.of("over"), send);
    assertEquals(List.of("end the call"), end);
    assertEquals(List.of("a", "b c"), HandsFree.list(" a ,, b c\n ,"));
    assertEquals(List.of(), HandsFree.list(""));
  }

  @Test
  public void aSendWordCountsOnlyAtTheEnd() {
    assertEquals("Book a table for Friday.", HandsFree.endsWith("Book a table for Friday. Over.", send));
    assertEquals("book a table", HandsFree.endsWith("book a table, over!", send));
    assertEquals("Buch einen Tisch.", HandsFree.endsWith("Buch einen Tisch. Schick ab.", HandsFree.list("over, schick ab")));
    assertEquals("", HandsFree.endsWith("Over.", send));
    // Not at the end, or only part of a word: keep listening.
    assertNull(HandsFree.endsWith("Over the weekend I want to rest.", send));
    assertNull(HandsFree.endsWith("We went to Hanover", send));
    assertNull(HandsFree.endsWith("I'll hand it over to her soon", send));
    // Apostrophes, accents and case don't matter.
    assertEquals("Thanks.", HandsFree.endsWith("Thanks. End the call.", end));
    assertEquals("Danke", HandsFree.endsWith("Danke, das war's!", HandsFree.list("das wars")));
    // "That's all" alone no longer ends hands-free.
    assertNull(HandsFree.endsWith("That's all.", end));

    assertNull(HandsFree.endsWith("", send));
  }

  // ------------------------------------------------------------------ voice activity

  static byte[] frame(int amplitude) {
    byte[] f = new byte[640];
    for (int i = 0; i < 320; i++) {
      int s = (int) (amplitude * Math.sin(i * 2 * Math.PI * 220 / 16000.0));
      f[2 * i] = (byte) s;
      f[2 * i + 1] = (byte) (s >> 8);
    }
    return f;
  }

  static void feed(HandsFree.Segmenter s, int amplitude, int ms) {
    for (int i = 0; i < ms / 20; i++) s.frame(frame(amplitude));
  }

  @Test
  public void speechBetweenPausesBecomesOneStretchWithoutTheSilence() {
    List<byte[]> out = new ArrayList<>();
    HandsFree.Segmenter s = new HandsFree.Segmenter(3000, out::add);
    feed(s, 50, 2000); // Room noise.
    feed(s, 6000, 1000); // Speech.
    feed(s, 50, 1500); // A short pause: still the same stretch.
    assertTrue(out.isEmpty());
    feed(s, 6000, 1000);
    feed(s, 50, 3100); // The pause that ends it.
    assertEquals(1, out.size());
    // 2 s of speech, the 1.5 s pause inside, and 0.3 s before and after; not the 3 s pause.
    double seconds = out.get(0).length / (double) AudioFiles.BYTES_PER_SECOND;
    assertEquals(4.1, seconds, 0.1);
    assertTrue(s.quietMs() >= 3000);
  }

  @Test
  public void aClickIsNotSpeechAndFlushEndsAStretch() {
    List<byte[]> out = new ArrayList<>();
    HandsFree.Segmenter s = new HandsFree.Segmenter(3000, out::add);
    feed(s, 50, 1000);
    feed(s, 9000, 20); // One loud frame.
    feed(s, 50, 4000);
    assertTrue(out.isEmpty());
    feed(s, 6000, 600);
    assertTrue(s.speaking());
    s.flush();
    assertEquals(1, out.size());
    assertFalse(s.speaking());
  }

  // ------------------------------------------------------------------ the session

  /** A session whose speech model returns queued texts, one per stretch. */
  static final class Fake {
    final List<String> transcripts = new CopyOnWriteArrayList<>(), sent = new CopyOnWriteArrayList<>(),
        spoken = new CopyOnWriteArrayList<>(), log = new CopyOnWriteArrayList<>();
    Runnable done;
    final HandsFreeSession session;

    Fake(String... texts) {
      transcripts.addAll(List.of(texts));
      session =
          new HandsFreeSession(
              1000,
              8000,
              HandsFree.DEFAULT_TAKE_MS,
              HandsFree.DEFAULT_GATE,
              HandsFree.LEVEL,
              HandsFree.list(HandsFree.DEFAULT_SEND),
              HandsFree.list(HandsFree.DEFAULT_END),
              wav -> transcripts.remove(0),
              said -> {
                sent.add(said);
                return "r" + sent.size();
              },
              new HandsFreeSession.Speaker() {
                public void speak(String request, String text, Runnable d) {
                  spoken.add(text);
                  done = d;
                }

                public void stop() {
                  Runnable d = done;
                  done = null;
                  if (d != null) d.run();
                }
              },
              () -> {});
      session.onEvent(log::add);
    }

    /** Quiet room for a second, so the background is measured. */
    void quiet(int ms) {
      for (int i = 0; i < ms / 20; i++) session.frame(frame(50));
    }

    /** 1.2 s of speech close to the phone: enough to take the turn. */
    void speak() {
      for (int i = 0; i < 60; i++) session.frame(frame(6000));
    }

    /** Speech, then the pause that ends the stretch. */
    void talk() throws Exception {
      speak();
      quiet(1200);
      Thread.sleep(150);
    }
  }

  static void until(java.util.function.BooleanSupplier c) throws Exception {
    for (int i = 0; i < 100 && !c.getAsBoolean(); i++) Thread.sleep(20);
    assertTrue(c.getAsBoolean());
  }

  @Test
  public void partsAddUpAndGoOnTheSendWord() throws Exception {
    Fake f = new Fake("I was thinking about the trip.", "Let's book the hotel. Over.");
    f.talk();
    until(() -> f.session.pending().equals("I was thinking about the trip."));
    assertEquals(HandsFreeSession.Floor.YOU, f.session.floor());
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals("I was thinking about the trip. Let's book the hotel.", f.sent.get(0));
    assertEquals("", f.session.pending());
    assertTrue(f.session.ours("r1"));
    assertFalse(f.session.ours("other"));
    until(() -> f.session.floor() == HandsFreeSession.Floor.OPEN);
  }

  @Test
  public void theEndPhraseSendsWhatCameBeforeAndEnds() throws Exception {
    Fake f = new Fake("Cancel the meeting. End the call.");
    f.talk();
    until(() -> f.session.state() == HandsFreeSession.State.ENDED);
    assertEquals(List.of("Cancel the meeting."), f.sent);
  }

  @Test
  public void chiefFirstOnAnOpenFloorIsReadAndTheMicrophoneIsOffMeanwhile() throws Exception {
    Fake f = new Fake("Next question. Over.");
    f.quiet(1000);
    f.session.reply("r1", "First answer from what I know.");
    f.session.reply("r1", "The final answer.");
    assertEquals(HandsFreeSession.Floor.CHIEF, f.session.floor());
    assertEquals(List.of("First answer from what I know."), f.spoken);
    // The voice coming back through the microphone while reading: nothing is captured.
    f.talk();
    assertEquals(1, f.transcripts.size());
    f.done.run(); // The first finished; the floor opens and the second, held, is read.
    assertEquals(List.of("First answer from what I know.", "The final answer."), f.spoken);
    f.done.run();
    assertEquals(HandsFreeSession.Floor.OPEN, f.session.floor());
    // Its tail right after reading is dropped too.
    f.talk();
    assertEquals(1, f.transcripts.size());
    // After the tail, you're heard again, and nothing of the reply is in your turn.
    Thread.sleep(HandsFreeSession.TAIL_MS);
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals("Next question.", f.sent.get(0));
  }

  @Test
  public void youFirstTheChiefsMessageIsHeldThenDroppedAndNotedInYourMessage() throws Exception {
    Fake f = new Fake("Also check the train", "to Lisbon. Over.");
    f.quiet(1000);
    f.speak(); // You take the floor.
    assertEquals(HandsFreeSession.Floor.YOU, f.session.floor());
    f.session.reply("r0", "The flight leaves at nine in the morning and costs about two hundred euros, I think.");
    assertTrue(f.spoken.isEmpty());
    assertEquals(1, f.session.held());
    f.quiet(1200);
    Thread.sleep(150);
    f.talk();
    until(() -> f.sent.size() == 1);
    // Your message, then which reply you didn't hear; it's never read now.
    assertEquals(
        "Also check the train to Lisbon.\n\n[didn't hear your reply: \"The flight leaves at nine in the morning and costs about two…\"]",
        f.sent.get(0));
    assertEquals(0, f.session.held());
    Thread.sleep(100);
    assertTrue(f.spoken.isEmpty());
    assertTrue(String.join("\n", f.log), String.join("\n", f.log).contains("held, the floor is taken"));
  }

  @Test
  public void aTurnThatComesToNothingReleasesTheFloorAndTheHeldMessageIsRead() throws Exception {
    Fake f = new Fake("");
    f.quiet(1000);
    f.speak();
    f.session.reply("r0", "Here's what I found.");
    assertTrue(f.spoken.isEmpty());
    f.quiet(1200);
    until(() -> f.spoken.size() == 1);
    assertEquals("Here's what I found.", f.spoken.get(0));
    assertTrue(f.sent.isEmpty());
  }

  @Test
  public void noiseBelowTheThresholdsNeverTakesTheFloor() throws Exception {
    Fake f = new Fake("should not be transcribed");
    f.quiet(2000);
    // Distant voices: not much louder than the background.
    for (int i = 0; i < 100; i++) f.session.frame(frame(120));
    // A short loud sound: under the second needed to take the turn.
    for (int i = 0; i < 20; i++) f.session.frame(frame(6000));
    f.quiet(1000);
    Thread.sleep(150);
    assertEquals(1, f.transcripts.size());
    assertEquals(HandsFreeSession.Floor.OPEN, f.session.floor());
    // So a chief message is read right away.
    f.session.reply("r0", "An answer.");
    assertEquals(List.of("An answer."), f.spoken);
    assertTrue(String.join("\n", f.log), String.join("\n", f.log).contains("noise ignored"));
  }

  @Test
  public void onceYouHaveTheFloorAShortOverStillCounts() throws Exception {
    Fake f = new Fake("Book the hotel", "Over.");
    f.talk();
    until(() -> f.session.pending().equals("Book the hotel"));
    for (int i = 0; i < 20; i++) f.session.frame(frame(6000)); // 0.4 s: "Over."
    f.quiet(1200);
    until(() -> f.sent.size() == 1);
    assertEquals("Book the hotel", f.sent.get(0));
  }

  @Test
  public void aDictationSwitchedToHandsFreeStartsTheFirstTurn() throws Exception {
    Fake f = new Fake("I was dictating about the trip", "and the budget. Over.");
    f.session.seed(new byte[32_000]);
    until(() -> f.session.pending().equals("I was dictating about the trip"));
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals("I was dictating about the trip and the budget.", f.sent.get(0));
  }

  @Test
  public void thePhaseIsListeningWorkingOrReading() throws Exception {
    Fake f = new Fake("Book the hotel. Over.");
    assertEquals(HandsFreeSession.Phase.LISTENING, f.session.phase());
    f.talk();
    until(() -> f.sent.size() == 1);
    until(() -> f.session.phase() == HandsFreeSession.Phase.WORKING);
    f.session.reply("r1", "On it, first thoughts.");
    assertEquals(HandsFreeSession.Phase.READING, f.session.phase());
    f.done.run();
    assertEquals(HandsFreeSession.Phase.WORKING, f.session.phase());
    f.session.reply("r1", "The hotel is booked.");
    f.done.run();
    assertEquals(HandsFreeSession.Phase.LISTENING, f.session.phase());
    assertEquals("", f.session.error());
  }

  @Test
  public void theButtonSendsAndALongSilenceSendsToo() throws Exception {
    Fake f = new Fake("Remind me to call Jana", "and buy milk");
    f.talk();
    until(() -> !f.session.pending().isEmpty());
    f.session.sendNow();
    until(() -> f.sent.size() == 1);
    assertEquals("Remind me to call Jana", f.sent.get(0));
    f.talk();
    until(() -> !f.session.pending().isEmpty());
    f.quiet(8000);
    f.session.tick();
    until(() -> f.sent.size() == 2);
    assertEquals("and buy milk", f.sent.get(1));
  }

  // ------------------------------------------------------------------ the chief's side

  @Test
  public void spokenRepliesAreFoundByTagAndRequest() throws Exception {
    JSONObject m = new JSONObject().put("event", "message").put("tags", new org.json.JSONArray().put("talkrouter-speak").put("req-abc234"));
    assertEquals("abc234", ChiefText.spokenRequest(m));
    JSONObject paste = new JSONObject().put("tags", new org.json.JSONArray().put("talkrouter-paste").put("req-abc234"));
    assertEquals("", ChiefText.spokenRequest(paste));
    assertEquals("", ChiefText.spokenRequest(new JSONObject().put("event", "keepalive")));
  }

  @Test
  public void handsFreeMessagesAreTaggedAndTheSkillExplainsThem() throws Exception {
    String m = BbBridge.message("Book a table", List.of(), HandsFree.TAG, "k3x9qa");
    assertEquals("Book a table\n\n[hands-free]\n\n[request: k3x9qa]", m);
    String skill =
        new String(
            java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/assets/skills/dictate-and-delegate/SKILL.md")),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(skill.contains("[hands-free]"));
    assertTrue(skill.contains("Tags: talkrouter-speak,req-<id>"));
    assertTrue(skill.contains("first spoken answer"));
    assertTrue(skill.contains("[hands-free call started]") && skill.contains("[hands-free call ended]"));
    assertTrue(skill.contains("[didn't hear your reply:"));
    assertFalse(skill.contains("quick spoken reaction"));
  }

  @Test
  public void theVoiceRequestNamesModelAndVoice() throws Exception {
    JSONObject b = Voice.body(Voice.KOKORO, "Hello");
    assertEquals("hexgrad/kokoro-82m", b.getString("model"));
    assertEquals("af_heart", b.getString("voice"));
    assertEquals("mp3", b.getString("response_format"));
    assertEquals(Voice.KOKORO, Voice.choice("nonsense"));
    assertEquals(Voice.ELEVEN, Voice.choice("eleven"));
  }
}
