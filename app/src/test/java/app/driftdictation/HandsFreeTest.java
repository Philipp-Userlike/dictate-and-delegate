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
    /** While set, the speech model waits for it (a slow transcription). */
    volatile java.util.concurrent.CountDownLatch slow;
    /** While set, delivering to bb waits for it (a slow bb). */
    volatile java.util.concurrent.CountDownLatch slowBb;
    /** How often the speech model was asked. */
    final java.util.concurrent.atomic.AtomicInteger transcriptions = new java.util.concurrent.atomic.AtomicInteger();
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
              wav -> {
                java.util.concurrent.CountDownLatch l = slow;
                if (l != null) l.await();
                transcriptions.incrementAndGet();
                return transcripts.remove(0);
              },
              said -> {
                java.util.concurrent.CountDownLatch l = slowBb;
                if (l != null) l.await();
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
    // Each check writes down the whole turn so far.
    Fake f = new Fake("I was thinking about the trip.", "I was thinking about the trip. Let's book the hotel. Over.");
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

  /** The reasons on screen, newest first, as one text. */
  static String reasons(Fake f) {
    StringBuilder out = new StringBuilder();
    for (HandsFreeSession.Reason r : f.session.reasons()) out.append(r.text()).append('\n');
    return out.toString();
  }

  @Test
  public void aReplyWaitsWhileYouTalkIsNotDroppedBySendingAndPlaysAtThePause() throws Exception {
    Fake f = new Fake("Also check the train", "Also check the train to Lisbon. Over.");
    f.quiet(1000);
    f.speak(); // You take the floor.
    assertEquals(HandsFreeSession.Floor.YOU, f.session.floor());
    f.session.reply("r0", "The flight leaves at nine in the morning and costs about two hundred euros, I think.");
    Thread.sleep(100);
    assertTrue(f.spoken.isEmpty());
    assertEquals(1, f.session.held());
    assertTrue(reasons(f), reasons(f).contains("Reply waits: you're talking"));
    assertEquals(HandsFreeSession.Live.HEARING, f.session.live());
    f.quiet(1200); // A pause shorter than REPLY_PAUSE_MS: it still waits.
    Thread.sleep(150);
    assertTrue(f.spoken.isEmpty());
    f.talk();
    until(() -> f.sent.size() == 1);
    // Your message as said: nothing about the reply, which isn't dropped.
    assertEquals("Also check the train to Lisbon.", f.sent.get(0));
    // Your turn is over: it plays now.
    until(() -> f.spoken.size() == 1);
    assertEquals("The flight leaves at nine in the morning and costs about two hundred euros, I think.", f.spoken.get(0));
    f.done.run();
    assertEquals(0, f.session.unheard());
    assertEquals("", f.session.endNote());
  }

  @Test
  public void aReplyPlaysAtYourPauseEvenWithUnsentWordsAndYourTurnGoesOnAfterIt() throws Exception {
    Fake f = new Fake("Book the hotel", "Book the hotel for Friday. Over.");
    f.quiet(1000);
    f.speak();
    f.session.reply("r0", "Here's what I found.");
    f.quiet(1200);
    until(() -> f.session.pending().equals("Book the hotel"));
    Thread.sleep(100);
    assertTrue(f.spoken.isEmpty());
    assertEquals(HandsFreeSession.Live.REPLY_WAITING, f.session.live());
    f.quiet(400); // Now quiet for REPLY_PAUSE_MS.
    until(() -> f.spoken.size() == 1);
    assertTrue(reasons(f), reasons(f).contains("Reply plays: you paused"));
    assertEquals("Book the hotel", f.session.pending()); // Not sent, not lost.
    f.done.run();
    assertEquals(HandsFreeSession.Live.MUTED, f.session.live());
    assertTrue(reasons(f), reasons(f).contains("Mic paused 0.8 s after the reply (echo)"));
    f.now.addAndGet(HandsFreeSession.TAIL_MS + 1);
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals("Book the hotel for Friday.", f.sent.get(0));
  }

  @Test
  public void aCallEndingWithRepliesStillWaitingTellsTheChiefOnce() throws Exception {
    Fake f = new Fake("Hold on");
    f.quiet(1000);
    f.speak();
    f.session.reply("r0", "The meeting moved to three.");
    f.session.end();
    assertEquals("\n\n[didn't hear your reply: \"The meeting moved to three.\"]", f.session.endNote());
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
    // A click-like loud sound: under the 0.1 s a loud burst needs, far under the voice to take the turn.
    for (int i = 0; i < 4; i++) f.session.frame(frame(6000));
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
    Fake f = new Fake("Book the hotel", "Book the hotel, over");
    f.talk();
    until(() -> f.session.pending().equals("Book the hotel"));
    for (int i = 0; i < 20; i++) f.session.frame(frame(6000)); // 0.4 s: "Over."
    f.quiet(1200);
    until(() -> f.sent.size() == 1);
    assertEquals("Book the hotel", f.sent.get(0));
  }

  @Test
  public void aDictationSwitchedToHandsFreeStartsTheFirstTurn() throws Exception {
    Fake f = new Fake("I was dictating about the trip", "I was dictating about the trip and the budget. Over.");
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

  @Test
  public void theButtonPressedWhileTheLastPartIsBeingWrittenDownSendsIt() throws Exception {
    // The part ended (short pause) and the speech model is still working when the button is
    // pressed: it must go once written down, not be dropped as an empty turn.
    Fake f = new Fake("Book a table for four, tonight at eight");
    f.slow = new java.util.concurrent.CountDownLatch(1);
    f.talk();
    assertEquals("", f.session.pending());
    f.session.sendNow();
    f.slow.countDown();
    until(() -> f.sent.size() == 1);
    assertEquals("Book a table for four, tonight at eight", f.sent.get(0));
    assertEquals("", f.session.pending());
  }

  @Test
  public void theButtonWaitsForEveryPartStillBeingWrittenDown() throws Exception {
    Fake f = new Fake("First part.", "First part. Second part.");
    f.slow = new java.util.concurrent.CountDownLatch(1);
    f.talk();
    f.talk();
    f.session.sendNow();
    f.slow.countDown();
    until(() -> f.sent.size() == 1);
    Thread.sleep(200);
    assertEquals(1, f.sent.size());
    assertEquals("First part. Second part.", f.sent.get(0));
  }

  @Test
  public void aPressShowsSendingAtOnceAndASecondPressDoesNothing() throws Exception {
    Fake f = new Fake("Book the table");
    f.slow = new java.util.concurrent.CountDownLatch(1);
    f.talk();
    f.session.sendNow();
    assertEquals(HandsFreeSession.Phase.SENDING, f.session.phase());
    f.session.sendNow();
    f.slow.countDown();
    until(() -> f.sent.size() == 1);
    Thread.sleep(200);
    assertEquals(1, f.sent.size());
    assertTrue(f.log.stream().anyMatch(l -> l.contains("send pressed again")));
  }

  @Test
  public void aPressRightAfterAMessageWentDoesntSendTheNextWordsOnTheirOwn() throws Exception {
    // Philipp's "don't see the": a second press for the same message sent the next fragment.
    Fake f = new Fake("That was the whole thing.", "don't see the");
    f.talk();
    until(() -> !f.session.pending().isEmpty());
    f.session.sendNow();
    until(() -> f.sent.size() == 1);
    f.talk();
    until(() -> f.session.pending().equals("don't see the"));
    f.session.sendNow(); // Same instant on the fake clock: a repeated press.
    Thread.sleep(200);
    assertEquals(1, f.sent.size());
    assertEquals("don't see the", f.session.pending());
    f.now.addAndGet(HandsFreeSession.REPRESS_MS + 1);
    f.session.sendNow();
    until(() -> f.sent.size() == 2);
    assertEquals("don't see the", f.sent.get(1));
  }

  @Test
  public void whatYouSayNextIsWrittenDownWhileBbIsSlow() throws Exception {
    // Delivering a message must not hold up writing down the next turn, and messages keep their order.
    Fake f = new Fake("First message. Over.", "Second message. Over.");
    f.slowBb = new java.util.concurrent.CountDownLatch(1);
    f.talk();
    until(() -> f.session.phase() == HandsFreeSession.Phase.SENDING);
    f.talk();
    until(() -> f.log.stream().anyMatch(l -> l.contains("Second message")));
    assertEquals(0, f.sent.size());
    f.slowBb.countDown();
    until(() -> f.sent.size() == 2);
    assertEquals("First message.", f.sent.get(0));
    assertEquals("Second message.", f.sent.get(1));
  }

  @Test
  public void theLastMessageStillGoesWhenTheCallEndsWithIt() throws Exception {
    Fake f = new Fake("Order the parts, end the call");
    f.slowBb = new java.util.concurrent.CountDownLatch(1);
    f.talk();
    until(() -> f.session.state() == HandsFreeSession.State.ENDED);
    f.slowBb.countDown();
    assertTrue(f.session.awaitDelivered(2000));
    assertEquals(1, f.sent.size());
    assertEquals("Order the parts", f.sent.get(0));
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
    assertEquals("Wait, one more thing.", f.sent.get(0));
    until(() -> f.spoken.size() == 1); // Not dropped: it plays once your turn is over.
    assertEquals("Here's the answer.", f.spoken.get(0));
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
    // Tried again twice (its voice fetched afresh), then it counts as not heard.
    for (int i = 1; i <= 1 + HandsFreeSession.RETRIES; i++) {
      int tries = i;
      until(() -> f.spoken.size() == tries);
      f.now.addAndGet(HandsFreeSession.START_MS + 1);
      f.session.tick();
    }
    assertEquals(HandsFreeSession.State.LISTENING, f.session.state());
    assertEquals(1, f.session.unheard());
    assertEquals(3, f.spoken.size());
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
    for (int i = 1; i <= 1 + HandsFreeSession.RETRIES; i++) {
      int tries = i;
      until(() -> f.spoken.size() == tries);
      f.fail.run(); // The player reports an error before any audio.
    }
    assertEquals(HandsFreeSession.State.LISTENING, f.session.state());
    assertEquals(1, f.session.unheard());
  }

  @Test
  public void aReplyWhoseVoiceFailsIsTriedAgainAndHeard() throws Exception {
    Fake f = new Fake();
    f.quiet(1000);
    f.session.reply("r0", "The table is booked.");
    until(() -> f.spoken.size() == 1);
    f.fail.run(); // Android's voice couldn't play it.
    until(() -> f.spoken.size() == 2); // Its voice fetched again, and played.
    f.done.run();
    assertEquals(0, f.session.unheard());
    assertTrue(reasons(f), reasons(f).contains("Reply couldn't be played (the voice stopped): trying again"));
  }

  @Test
  public void writingDownThatHangsIsGivenUpTriedOnceMoreAndShown() throws Exception {
    long was = HandsFreeSession.writeTimeoutMs;
    HandsFreeSession.writeTimeoutMs = 300;
    try {
      Fake f = new Fake("Book the table", "Book the table");
      f.slow = new java.util.concurrent.CountDownLatch(1); // Both tries hang.
      f.talk();
      until(() -> f.session.checkStep().equals("writing"));
      assertEquals(HandsFreeSession.Live.WRITING, f.session.live());
      until(() -> reasons(f).contains("Writing down took over 0 s: trying once more"));
      until(() -> reasons(f).contains("Couldn't write down"));
      f.slow.countDown();
      // Kept: Send writes it down and sends it.
      f.session.sendNow();
      until(() -> f.sent.size() == 1);
    } finally {
      HandsFreeSession.writeTimeoutMs = was;
    }
  }

  @Test
  public void wordsNotSentWhenTheCallEndsAreSentBeforeItsEnd() throws Exception {
    Fake f = new Fake("And also look at the latency");
    f.session.judge = said -> verdict("more", 0.1, 0);
    f.talk();
    until(() -> f.session.pending().equals("And also look at the latency"));
    f.session.end();
    assertTrue(f.session.awaitDelivered(2000));
    assertEquals(List.of("And also look at the latency"), f.sent);
    assertTrue(String.join("\n", f.log), String.join("\n", f.log).contains("they're sent before the call's end"));
  }

  @Test
  public void wordsNotYetWrittenDownWhenTheCallEndsAreWrittenDownAndSent() throws Exception {
    Fake f = new Fake("Order the parts");
    f.quiet(1000);
    f.speak(); // Still talking when the call ends: the part ends with it.
    f.session.end();
    assertTrue(f.session.awaitDelivered(3000));
    assertEquals(List.of("Order the parts"), f.sent);
  }

  @Test
  public void aTranscriptionThatFailsKeepsYourWordsAndTheNextCheckTriesAgain() throws Exception {
    Fake f = new Fake(); // No transcripts queued: the speech model fails.
    f.quiet(1000);
    f.talk();
    until(() -> !f.session.error().isEmpty());
    // Your turn is kept; a reply still plays at your pause.
    assertEquals(HandsFreeSession.Floor.YOU, f.session.floor());
    f.session.reply("r0", "Read now.");
    f.quiet(400);
    until(() -> f.spoken.size() == 1);
    f.done.run();
    f.now.addAndGet(HandsFreeSession.TAIL_MS + 1);
    f.transcripts.add("Book the hotel. Over.");
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals("Book the hotel.", f.sent.get(0));
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
      "The flight leaves at nine in the morning from gate twelve. The train to Lisbon costs about forty euros if you book today.";

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
  public void talkingOverAReplyPausesItAndInterruptsItAndItPlaysLaterFromThatSentence() throws Exception {
    Fake f = playingReply("Hold on, book the train instead", "Over.");
    f.heardChars = LONG_REPLY.indexOf("Lisbon");
    for (int i = 0; i < 20; i++) f.session.frame(frame(8000)); // You, clearly louder.
    assertTrue("paused at once", f.paused);
    assertEquals(HandsFreeSession.Live.CHECKING, f.session.live());
    f.quiet(1100); // You pause: what you said is written down.
    until(() -> f.session.state() == HandsFreeSession.State.LISTENING);
    assertTrue("the reply stopped", f.stopped);
    until(() -> f.session.pending().equals("Hold on, book the train instead"));
    assertTrue(reasons(f), reasons(f).contains("Reply stopped: you talked over it. It plays again from \"The train to Lisbon costs about…\""));
    assertEquals(1, f.session.held());
    f.talk();
    until(() -> f.sent.size() == 1);
    // Your message as said; the reply isn't lost: it plays after your turn, from that sentence.
    assertEquals("Hold on, book the train instead", f.sent.get(0));
    until(() -> f.spoken.size() == 2);
    assertEquals("The train to Lisbon costs about forty euros if you book today.", f.spoken.get(1));
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
    assertTrue(HandsFreeSession.echoOf("from gate twelve the train", LONG_REPLY, LONG_REPLY.indexOf("train")));
    assertFalse(HandsFreeSession.echoOf("no wait, cancel the hotel", LONG_REPLY, LONG_REPLY.indexOf("train")));
  }

  @Test
  public void aReplyTalkedOverPlaysAgainFromTheSentenceYouStoppedHearing() {
    assertEquals(LONG_REPLY, HandsFreeSession.restFrom(LONG_REPLY, 10));
    assertEquals("The train to Lisbon costs about forty euros if you book today.", HandsFreeSession.restFrom(LONG_REPLY, LONG_REPLY.indexOf("Lisbon")));
    // Right at the start of the second sentence.
    assertEquals("The train to Lisbon costs about forty euros if you book today.", HandsFreeSession.restFrom(LONG_REPLY, LONG_REPLY.indexOf("The train")));
    assertEquals("Yes. Really?", HandsFreeSession.restFrom("Yes. Really?", 2));
  }

  // ------------------------------------------------------------------ taking the turn, and the send word

  @Test
  public void speechWithLittleGapsTakesTheTurn() throws Exception {
    // Real dead calls: "Hi, can you hear me" in short loud bits, never a second without a gap.
    Fake f = new Fake("Hi, can you hear me?");
    for (int i = 0; i < 100; i++) f.session.frame(frame(212)); // A room: background about 150.
    for (int b = 0; b < 3; b++) { // 0.2 s bits at about 5× the background, 0.2 s gaps.
      for (int i = 0; i < 10; i++) f.session.frame(frame(1100));
      for (int i = 0; i < 10; i++) f.session.frame(frame(212));
    }
    assertEquals(HandsFreeSession.Floor.YOU, f.session.floor());
    for (int i = 0; i < 60; i++) f.session.frame(frame(212));
    until(() -> f.session.pending().equals("Hi, can you hear me?"));
  }

  @Test
  public void aShortClearlyLoudBurstTakesTheTurn() throws Exception {
    Fake f = new Fake("Hello?");
    f.quiet(1000);
    for (int i = 0; i < 6; i++) f.session.frame(frame(6000)); // 0.12 s, far louder than the room.
    f.quiet(1200);
    until(() -> f.session.pending().equals("Hello?"));
  }

  @Test
  public void theBackgroundIsLearnedFastAtTheStart() {
    HandsFree.Segmenter s = new HandsFree.Segmenter(800, pcm -> {});
    feed(s, 50, 600);
    assertTrue("background " + s.background(), s.background() < 60);
  }

  @Test
  public void sendWritesDownSoundsThatDidntCountAsSpeech() throws Exception {
    Fake f = new Fake("Hi, can you hear me?");
    for (int i = 0; i < 100; i++) f.session.frame(frame(212));
    for (int b = 0; b < 2; b++) { // Two bits too short to take the turn.
      for (int i = 0; i < 4; i++) f.session.frame(frame(1100));
      for (int i = 0; i < 60; i++) f.session.frame(frame(212));
    }
    assertEquals(HandsFreeSession.Floor.OPEN, f.session.floor());
    f.session.sendNow();
    until(() -> f.sent.size() == 1);
    assertEquals("Hi, can you hear me?", f.sent.get(0));
    assertTrue(reasons(f), reasons(f).contains("Send: writing down what didn't count as speech"));
  }

  @Test
  public void theSendWordOnItsOwnAfterSendIsntAnEmptyTurn() throws Exception {
    // 22:37: Send pressed while the part was written down, then "over." came as a part of its own.
    Fake f = new Fake("Maybe it's also not needed", "over.");
    f.talk();
    until(() -> !f.session.pending().isEmpty());
    f.session.sendNow();
    until(() -> f.sent.size() == 1);
    f.talk();
    until(() -> reasons(f).contains("Send word heard: your message had already gone"));
    assertEquals(1, f.sent.size());
    assertFalse(String.join("\n", f.log), String.join("\n", f.log).contains("your turn ended without a message"));
  }

  @Test
  public void eachCheckWritesDownTheWholeTurnAndSendRightAfterReusesIt() throws Exception {
    Fake f = new Fake("Book the", "Book the hotel for Friday");
    f.talk();
    until(() -> f.session.pending().equals("Book the"));
    f.talk();
    until(() -> f.session.pending().equals("Book the hotel for Friday"));
    assertEquals(2, f.transcriptions.get());
    f.session.sendNow();
    until(() -> f.sent.size() == 1);
    assertEquals("Book the hotel for Friday", f.sent.get(0));
    assertEquals("Send after a check: nothing written down again", 2, f.transcriptions.get());
  }

  // ------------------------------------------------------------------ Jev: is the turn done?

  static HandsFreeSession.Verdict verdict(String choice, double send, double end) {
    return new HandsFreeSession.Verdict(choice, send, end, new java.math.BigDecimal("0.00002"));
  }

  @Test
  public void atAPauseJevDecidesWhetherYourTurnIsDoneAndSaysWhy() throws Exception {
    Fake f = new Fake("Can you check", "Can you check when the next train to Lisbon leaves?");
    List<String> asked = new CopyOnWriteArrayList<>();
    java.util.ArrayDeque<HandsFreeSession.Verdict> answers =
        new java.util.ArrayDeque<>(List.of(verdict("unfinished", 0.04, 0.0), verdict("question", 0.98, 0.0)));
    f.session.judge = said -> {
      asked.add(said);
      return answers.poll();
    };
    f.talk();
    until(() -> reasons(f).contains("Not sent: you seem mid-sentence (4% done)"));
    assertEquals(0, f.sent.size());
    assertEquals(HandsFreeSession.Live.WAITING_SEND, f.session.live());
    f.talk(); // On: the next pause asks again, with the whole turn.
    until(() -> f.sent.size() == 1);
    assertEquals("Can you check when the next train to Lisbon leaves?", f.sent.get(0));
    assertEquals(List.of("Can you check", "Can you check when the next train to Lisbon leaves?"), asked);
    assertTrue(reasons(f), reasons(f).contains("Sent: you asked your question (98%)"));
    assertTrue(String.join("\n", f.log), String.join("\n", f.log).contains("Jev in "));
  }

  @Test
  public void jevHearingYouWantToEndTheCallSendsWhatCameBeforeAndEnds() throws Exception {
    Fake f = new Fake("Thanks, that's all. End the call.");
    f.session.judge = said -> verdict("end", 0.5, 0.99);
    f.talk();
    until(() -> f.session.state() == HandsFreeSession.State.ENDED);
    assertEquals(List.of("Thanks, that's all."), f.sent);
    assertTrue(reasons(f), reasons(f).contains("Ending the call: you said goodbye"));
  }

  @Test
  public void sendAlwaysSendsAtOnceWithoutAskingJev() throws Exception {
    Fake f = new Fake("I was thinking about the trip and");
    java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
    f.session.judge = said -> {
      asked.incrementAndGet();
      return verdict("unfinished", 0.01, 0);
    };
    f.speak(); // Still talking: Send ends the part and sends it.
    f.session.sendNow();
    until(() -> f.sent.size() == 1);
    assertEquals("I was thinking about the trip and", f.sent.get(0));
    assertEquals(0, asked.get());
  }

  @Test
  public void talkingOnWhileJevIsAskedMeansNoDecision() throws Exception {
    Fake f = new Fake("Book the hotel", "Book the hotel and the car");
    java.util.concurrent.CountDownLatch asking = new java.util.concurrent.CountDownLatch(1), answer = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
    f.session.judge = said -> {
      if (asked.incrementAndGet() == 1) {
        asking.countDown();
        answer.await();
      }
      return verdict("request", 0.95, 0);
    };
    f.talk();
    asking.await();
    f.speak(); // You go on while Jev thinks.
    answer.countDown();
    Thread.sleep(150);
    assertEquals(0, f.sent.size());
    f.quiet(1200);
    until(() -> f.sent.size() == 1);
    assertEquals("Book the hotel and the car", f.sent.get(0));
  }

  @Test
  public void whenJevCantBeAskedTheSendWordStillWorks() throws Exception {
    Fake f = new Fake("Book the table", "Book the table. Over.");
    f.session.judge = said -> {
      throw new java.io.IOException("timeout");
    };
    f.talk();
    until(() -> reasons(f).contains("Couldn't ask whether you're done (timeout)"));
    assertEquals(0, f.sent.size());
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals("Book the table.", f.sent.get(0));
  }

  @Test
  public void jevsAnswerIsReadAsDoneAndEnd() throws Exception {
    JSONObject r =
        new JSONObject(
            "{\"answers\":{\"turn\":{\"type\":\"choice\",\"choice\":\"question\",\"probabilities\":{\"over\":0.02,\"question\":0.9,\"request\":0.05,\"end\":0.01,\"unfinished\":0.01,\"more\":0.01}}},\"usage\":{\"cost\":0.00002}}");
    HandsFreeSession.Verdict v = Jev.turnVerdict(r);
    assertEquals("question", v.choice());
    assertEquals(0.97, v.send(), 1e-9);
    assertEquals(0.01, v.end(), 1e-9);
    JSONObject body = Jev.turnBody("Hi", List.of("over"), List.of("end the call"));
    JSONObject criteria = body.getJSONObject("questions").getJSONObject("turn").getJSONObject("criteria");
    assertEquals("over", criteria.keys().next()); // First: a fake without a script says done.
    assertTrue(criteria.getString("over").contains("\"over\""));
    assertTrue(criteria.getString("end").contains("\"end the call\""));
  }

  @Test
  public void theBackgroundComesDownOnceTheStartsNoiseHasPassed() {
    HandsFree.Segmenter s = new HandsFree.Segmenter(800, pcm -> {});
    feed(s, 900, 2000); // Handling noise at the start: about 640.
    feed(s, 50, 1000); // Then the quiet car.
    assertTrue("background " + s.background(), s.background() < 100); // Was about 600 a second later.
  }

  // ------------------------------------------------------------------ every decision has a visible reason

  @Test
  public void everyEarlyReturnOfSendShowsItsReason() throws Exception {
    Fake f = new Fake("Book the table", "Next words");
    f.session.sendNow();
    assertEquals("Send ignored: nothing heard yet", f.session.reasons().get(0).text());
    // Already sending: the first press waits for the speech model.
    f.slow = new java.util.concurrent.CountDownLatch(1);
    f.talk();
    f.session.sendNow();
    f.session.sendNow();
    assertEquals("Send ignored: already sending", f.session.reasons().get(0).text());
    f.slow.countDown();
    until(() -> f.sent.size() == 1);
    // Right after the message went: the same press.
    f.slow = null;
    f.talk();
    until(() -> f.session.pending().equals("Next words"));
    f.session.sendNow();
    assertTrue(f.session.reasons().get(0).text(), f.session.reasons().get(0).text().startsWith("Send ignored: just sent ("));
    assertEquals("Next words", f.session.pending());
    // A reply playing and nothing of yours to send.
    f.now.addAndGet(HandsFreeSession.REPRESS_MS + 1);
    f.session.sendNow();
    until(() -> f.sent.size() == 2);
    f.session.reply("r2", "An answer.");
    until(() -> f.spoken.size() == 1);
    f.now.addAndGet(HandsFreeSession.REPRESS_MS + 1);
    f.session.sendNow();
    assertEquals("Send ignored: a reply is playing and you have nothing unsent", f.session.reasons().get(0).text());
    // The same reason again counts up instead of filling the list.
    f.session.sendNow();
    assertEquals(2, f.session.reasons().get(0).times());
    assertTrue(String.join("\n", f.log), String.join("\n", f.log).contains("→ Send ignored: nothing heard yet"));
  }

  @Test
  public void soundsMarkTheMomentsAndNeverStartYourSpeech() throws Exception {
    Fake f = new Fake("Book the table. Over.");
    List<HandsFreeSession.Cue> cues = new CopyOnWriteArrayList<>();
    f.session.cues = cues::add; // Each plays.
    f.quiet(1000);
    f.talk();
    until(() -> f.sent.size() == 1);
    until(() -> cues.size() == 2);
    assertEquals(List.of(HandsFreeSession.Cue.HEARD, HandsFreeSession.Cue.SENT), cues);
    // The sent sound coming back through the microphone: no speech.
    for (int i = 0; i < 15; i++) f.session.frame(frame(8000));
    assertFalse(f.session.speaking());
    assertEquals(HandsFreeSession.Live.SENT, f.session.live());
    f.session.reply("r1", "Done.");
    until(() -> cues.size() == 3);
    assertEquals(HandsFreeSession.Cue.REPLY, cues.get(2));
    for (int i = 1; i <= 1 + HandsFreeSession.RETRIES; i++) { // Tried again, then given up.
      int tries = i;
      until(() -> f.spoken.size() == tries);
      f.fail.run();
    }
    assertTrue(cues.toString(), cues.contains(HandsFreeSession.Cue.FAILED));
  }

  @Test
  public void soundTooQuietToCountIsShownWithItsLevels() throws Exception {
    Fake f = new Fake();
    for (int i = 0; i < 400; i++) f.session.frame(frame(560)); // A loud room: background about 400.
    for (int i = 0; i < 50; i++) f.session.frame(frame(1270)); // A voice not 3× louder.
    for (int i = 0; i < 40; i++) f.session.frame(frame(560));
    String r = reasons(f);
    assertTrue(r, r.contains("Didn't count as speech: too quiet (level 9"));
    assertTrue(r, r.contains(" < 3.0× background 4"));
    assertEquals(HandsFreeSession.Floor.OPEN, f.session.floor());
  }

  @Test
  public void aTranscriptionThatFailsShowsWhy() throws Exception {
    Fake f = new Fake();
    f.quiet(1000);
    f.talk();
    until(() -> !f.session.error().isEmpty());
    assertTrue(reasons(f), reasons(f).startsWith("Couldn't write down 1."));
  }
}
