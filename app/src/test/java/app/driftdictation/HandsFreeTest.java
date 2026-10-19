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
    /** Ends the reply being read, played to the end. */
    volatile Runnable done, fail;
    /** The voice: its audio starts at once (as long as this is true), or never. */
    volatile boolean audioStarts = true, audioPlaying, paused, stopped;
    volatile int heardChars;
    /** What preparing a reply does (fetching its audio); by default nothing. */
    volatile java.util.function.Function<String, Object> prepare = text -> text;
    final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
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
                volatile HandsFreeSession.Playback playback;

                public Object prepare(String request, String text) {
                  return prepare.apply(text);
                }

                public void play(String request, String text, Object prepared, HandsFreeSession.Playback p) {
                  playback = p;
                  done = () -> p.ended(true);
                  fail = () -> p.ended(false);
                  spoken.add(text);
                  if (audioStarts) p.started(2000);
                }

                public boolean playing() {
                  return audioPlaying;
                }

                public void pause() {
                  paused = true;
                }

                public void resume() {
                  paused = false;
                }

                public int heardChars() {
                  return heardChars;
                }

                public void stop() {
                  stopped = true;
                  HandsFreeSession.Playback p = playback;
                  playback = null;
                  done = null;
                  if (p != null) p.ended(false);
                }
              },
              () -> {});
      session.onEvent(log::add);
      session.clock = now::get;
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
    until(() -> f.session.floor() == HandsFreeSession.Floor.CHIEF);
    assertEquals(List.of("First answer from what I know."), f.spoken);
    // The voice coming back through the microphone while reading: nothing is captured.
    f.talk();
    assertEquals(1, f.transcripts.size());
    f.done.run(); // The first finished; the floor opens and the second, held, is read.
    until(() -> f.spoken.size() == 2);
    assertEquals(List.of("First answer from what I know.", "The final answer."), f.spoken);
    f.done.run();
    assertEquals(HandsFreeSession.Floor.OPEN, f.session.floor());
    // Its tail right after reading is dropped too.
    f.talk();
    assertEquals(1, f.transcripts.size());
    // After the tail, you're heard again, and nothing of the reply is in your turn.
    f.now.addAndGet(HandsFreeSession.TAIL_MS + 1);
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
    until(() -> f.spoken.size() == 1);
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
    until(() -> f.spoken.size() == 1);
    assertEquals(HandsFreeSession.Phase.READING, f.session.phase());
    f.done.run();
    assertEquals(HandsFreeSession.Phase.WORKING, f.session.phase());
    f.session.reply("r1", "The hotel is booked.");
    until(() -> f.spoken.size() == 2);
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

  // ------------------------------------------------------------------ the voice never leaves you stuck

  @Test
  public void whileAReplysAudioIsFetchedTheFloorStaysOpenAndYouCanTalk() throws Exception {
    Fake f = new Fake("Wait, one more thing. Over.");
    java.util.concurrent.CountDownLatch fetched = new java.util.concurrent.CountDownLatch(1);
    f.prepare =
        text -> {
          try {
            fetched.await();
          } catch (InterruptedException ignored) {
            /* Go on. */
          }
          return text;
        };
    f.quiet(1000);
    f.session.reply("r0", "Here's the answer.");
    Thread.sleep(100);
    // Not read yet: the microphone is on and the floor open.
    assertEquals(HandsFreeSession.State.LISTENING, f.session.state());
    assertEquals(HandsFreeSession.Floor.OPEN, f.session.floor());
    f.speak(); // You take it meanwhile.
    fetched.countDown();
    Thread.sleep(100);
    assertTrue(f.spoken.isEmpty()); // Held: the floor is yours.
    f.quiet(1200);
    until(() -> f.sent.size() == 1);
    assertEquals("Wait, one more thing.\n\n[didn't hear your reply: \"Here's the answer.\"]", f.sent.get(0));
  }

  @Test
  public void audioThatNeverStartsIsGivenUpSoonAndNotedAsNotHeard() throws Exception {
    Fake f = new Fake("What did you say? Over.");
    f.audioStarts = false;
    f.quiet(1000);
    f.session.reply("r0", "The meeting moved to three.");
    until(() -> f.spoken.size() == 1);
    assertEquals(HandsFreeSession.State.READING, f.session.state());
    f.session.tick();
    assertEquals(HandsFreeSession.State.READING, f.session.state()); // Not yet.
    f.now.addAndGet(HandsFreeSession.START_MS + 1);
    f.session.tick();
    // Listening again, and the reply counts as not heard.
    assertEquals(HandsFreeSession.State.LISTENING, f.session.state());
    assertEquals(1, f.session.unheard());
    f.now.addAndGet(HandsFreeSession.TAIL_MS + 1);
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals("What did you say?\n\n[didn't hear your reply: \"The meeting moved to three.\"]", f.sent.get(0));
    assertEquals(0, f.session.unheard());
  }

  @Test
  public void audioThatPlaysFarTooLongIsLetGo() throws Exception {
    Fake f = new Fake();
    f.quiet(1000);
    f.session.reply("r0", "A reply of two seconds.");
    until(() -> f.spoken.size() == 1);
    f.now.addAndGet(2000 + HandsFreeSession.OVERRUN_MS - 100);
    f.session.tick();
    assertEquals(HandsFreeSession.State.READING, f.session.state()); // Still within its length.
    f.now.addAndGet(200);
    f.session.tick();
    assertEquals(HandsFreeSession.State.LISTENING, f.session.state());
    assertTrue(String.join("\n", f.log), String.join("\n", f.log).contains("played too long"));
  }

  @Test
  public void aVoiceThatFailsReturnsToListeningAtOnce() throws Exception {
    Fake f = new Fake();
    f.quiet(1000);
    f.session.reply("r0", "Can't be played.");
    until(() -> f.spoken.size() == 1);
    f.fail.run(); // The player reports an error before any audio.
    assertEquals(HandsFreeSession.State.LISTENING, f.session.state());
    assertEquals(1, f.session.unheard());
  }

  @Test
  public void aTranscriptionThatFailsGivesTheFloorBack() throws Exception {
    Fake f = new Fake(); // No transcripts queued: the speech model fails.
    f.quiet(1000);
    f.talk();
    until(() -> !f.session.error().isEmpty());
    until(() -> f.session.floor() == HandsFreeSession.Floor.OPEN);
    f.session.reply("r0", "Read now.");
    until(() -> f.spoken.size() == 1);
  }

  @Test
  public void aReplyLongerThanItsEstimateIsNeverCutWhileItsAudioPlays() throws Exception {
    Fake f = new Fake();
    f.quiet(1000);
    f.session.reply("r0", "A long reply that takes longer than the voice said.");
    until(() -> f.spoken.size() == 1);
    f.audioPlaying = true;
    for (int i = 0; i < 30; i++) { // A minute past its two seconds, still playing.
      f.now.addAndGet(2000);
      f.session.tick();
    }
    assertEquals(HandsFreeSession.State.READING, f.session.state());
    f.done.run(); // Played to the end: heard.
    assertEquals(HandsFreeSession.State.LISTENING, f.session.state());
    assertEquals(0, f.session.unheard());
  }

  // ------------------------------------------------------------------ talking over a reply

  static final String LONG_REPLY =
      "The flight leaves at nine in the morning from gate twelve, and the train to Lisbon costs about forty euros if you book today.";

  /** A reply playing, its echo measured at a moderate level. */
  static Fake playingReply(String... transcripts) throws Exception {
    Fake f = new Fake(transcripts);
    f.quiet(1000);
    f.session.reply("r1", LONG_REPLY);
    until(() -> f.spoken.size() == 1);
    for (int i = 0; i < HandsFreeSession.ECHO_FRAMES + 20; i++) f.session.frame(frame(1500)); // Its echo.
    assertFalse("the echo alone doesn't pause it", f.paused);
    f.heardChars = LONG_REPLY.indexOf("from gate");
    return f;
  }

  @Test
  public void talkingOverAReplyPausesItAndInterruptsIt() throws Exception {
    Fake f = playingReply("Hold on, book the train instead", "Over.");
    for (int i = 0; i < 20; i++) f.session.frame(frame(8000)); // You, clearly louder.
    assertTrue("paused at once", f.paused);
    f.quiet(1100); // You pause: what you said is written down.
    until(() -> f.session.state() == HandsFreeSession.State.LISTENING);
    assertTrue("the reply stopped", f.stopped);
    assertEquals("Hold on, book the train instead", f.session.pending());
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals(
        "Hold on, book the train instead\n\n[heard up to: \"The flight leaves at nine in the morning\"]", f.sent.get(0));
    assertTrue(String.join("\n", f.log), String.join("\n", f.log).contains("barge-in suspected"));
  }

  @Test
  public void theRepliesOwnVoiceComingBackResumesIt() throws Exception {
    Fake f = playingReply("the morning from gate twelve");
    for (int i = 0; i < 20; i++) f.session.frame(frame(8000)); // A loud stretch of its own voice.
    assertTrue(f.paused);
    f.quiet(1100);
    until(() -> !f.paused);
    assertEquals(HandsFreeSession.State.READING, f.session.state());
    assertFalse(f.stopped);
    assertEquals("", f.session.pending());
    assertTrue(String.join("\n", f.log), String.join("\n", f.log).contains("echo"));
    f.done.run();
    assertEquals(0, f.session.unheard());
  }

  @Test
  public void theNotesSayHowMuchOfAReplyWasHeard() {
    assertEquals("[heard up to: \"…leaves at nine in the morning from gate\"]", HandsFreeSession.heardUpTo(LONG_REPLY, LONG_REPLY.indexOf("twelve")));
    // A word cut in the middle isn't counted as heard.
    assertEquals("[heard up to: \"The flight\"]", HandsFreeSession.heardUpTo(LONG_REPLY, LONG_REPLY.indexOf("leaves") + 3));
    // Hardly anything heard: as if not heard at all.
    assertTrue(HandsFreeSession.heardUpTo(LONG_REPLY, 3).startsWith("[didn't hear your reply:"));
    assertTrue(HandsFreeSession.echoOf("from gate twelve and the", LONG_REPLY, LONG_REPLY.indexOf("and the")));
    assertFalse(HandsFreeSession.echoOf("no wait, cancel the hotel", LONG_REPLY, LONG_REPLY.indexOf("and the")));
  }
}
