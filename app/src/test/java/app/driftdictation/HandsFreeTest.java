package app.driftdictation;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;
import org.junit.Test;

public class HandsFreeTest {
  final List<String> send = HandsFree.list(HandsFree.DEFAULT_SEND),
      end = HandsFree.list(HandsFree.DEFAULT_END),
      stop = HandsFree.list(HandsFree.DEFAULT_STOP);

  @Test
  public void wordListsComeFromTheSetting() {
    assertEquals(List.of("over", "send", "senden", "schick ab"), send);
    assertEquals(List.of("a", "b c"), HandsFree.list(" a ,, b c\n ,"));
    assertEquals(List.of(), HandsFree.list(""));
  }

  @Test
  public void aSendWordCountsOnlyAtTheEnd() {
    assertEquals("Book a table for Friday.", HandsFree.endsWith("Book a table for Friday. Over.", send));
    assertEquals("book a table", HandsFree.endsWith("book a table, send!", send));
    assertEquals("Buch einen Tisch.", HandsFree.endsWith("Buch einen Tisch. Schick ab.", send));
    assertEquals("", HandsFree.endsWith("Over.", send));
    // Not at the end, or only part of a word: keep listening.
    assertNull(HandsFree.endsWith("Over the weekend I want to rest.", send));
    assertNull(HandsFree.endsWith("We went to Hanover", send));
    assertNull(HandsFree.endsWith("I'll resend it", send));
    // Apostrophes, accents and case don't matter.
    assertEquals("Thanks.", HandsFree.endsWith("Thanks. That's all.", end));
    assertEquals("Danke", HandsFree.endsWith("Danke, das war's!", end));
    assertEquals("", HandsFree.endsWith("Das wars", end));
    assertEquals("", HandsFree.endsWith("Stopp.", stop));
    assertNull(HandsFree.endsWith("", send));
  }

  @Test
  public void silenceTextIsDropped() {
    assertTrue(HandsFree.hallucination("Thank you."));
    assertTrue(HandsFree.hallucination("Thank you. Thank you."));
    assertTrue(HandsFree.hallucination("Untertitel der Amara.org-Community"));
    assertTrue(HandsFree.hallucination("  "));
    assertFalse(HandsFree.hallucination("Thank you for booking the table."));
    assertFalse(HandsFree.hallucination("Over."));
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
    final List<String> transcripts = new CopyOnWriteArrayList<>(), sent = new CopyOnWriteArrayList<>(), spoken = new CopyOnWriteArrayList<>();
    boolean stopped;
    Runnable done;
    final HandsFreeSession session;

    Fake(String... texts) {
      transcripts.addAll(List.of(texts));
      session =
          new HandsFreeSession(
              1000,
              8000,
              HandsFree.list(HandsFree.DEFAULT_SEND),
              HandsFree.list(HandsFree.DEFAULT_END),
              HandsFree.list(HandsFree.DEFAULT_STOP),
              wav -> transcripts.remove(0),
              said -> {
                sent.add(said);
                return "r" + sent.size();
              },
              new HandsFreeSession.Speaker() {
                public void speak(String text, Runnable d) {
                  spoken.add(text);
                  done = d;
                }

                public void stop() {
                  stopped = true;
                  Runnable d = done;
                  done = null;
                  if (d != null) d.run();
                }
              },
              () -> {});
    }

    void talk() throws Exception {
      for (int i = 0; i < 25; i++) session.frame(frame(6000));
      for (int i = 0; i < 60; i++) session.frame(frame(50));
      Thread.sleep(150);
    }
  }

  static void until(java.util.function.BooleanSupplier c) throws Exception {
    for (int i = 0; i < 100 && !c.getAsBoolean(); i++) Thread.sleep(20);
    assertTrue(c.getAsBoolean());
  }

  @Test
  public void partsAddUpAndGoOnTheSendWord() throws Exception {
    Fake f = new Fake("I was thinking about the trip.", "Thank you.", "Let's book the hotel. Over.");
    f.talk();
    until(() -> f.session.pending().equals("I was thinking about the trip."));
    f.talk(); // Silence text is dropped.
    f.talk();
    until(() -> f.sent.size() == 1);
    assertEquals("I was thinking about the trip. Let's book the hotel.", f.sent.get(0));
    assertEquals("", f.session.pending());
    assertTrue(f.session.ours("r1"));
    assertFalse(f.session.ours("other"));
  }

  @Test
  public void theEndWordSendsWhatCameBeforeAndEnds() throws Exception {
    Fake f = new Fake("Cancel the meeting. That's all.");
    f.talk();
    until(() -> f.session.state() == HandsFreeSession.State.ENDED);
    assertEquals(List.of("Cancel the meeting."), f.sent);
  }

  @Test
  public void repliesAreReadInTurnAndAStopWordStopsReading() throws Exception {
    Fake f = new Fake("Stop.");
    f.session.reply("Good idea, let me check.");
    f.session.reply("The hotel is booked.");
    assertEquals(HandsFreeSession.State.READING, f.session.state());
    assertEquals(List.of("Good idea, let me check."), f.spoken);
    f.done.run(); // The first one finished.
    assertEquals(List.of("Good idea, let me check.", "The hotel is booked."), f.spoken);
    f.talk(); // "Stop." while reading.
    until(() -> f.stopped && f.session.state() == HandsFreeSession.State.LISTENING);
    assertTrue(f.sent.isEmpty());
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
    for (int i = 0; i < 400; i++) f.session.frame(frame(50)); // 8 s of silence.
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
    assertTrue(skill.contains("quick spoken reaction"));
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
