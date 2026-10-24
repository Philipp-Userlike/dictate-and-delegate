package app.driftdictation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;

public class StreamingSpeechTest {
  @Test
  public void assemblyAisTurnsAreLiveTextThenFinalAndTakeEndsTheTurn() throws Exception {
    StreamingSpeech s = StreamingSpeech.create(StreamingSpeech.AAI_PRO, "k", List.of());
    List<String> changed = new CopyOnWriteArrayList<>();
    s.changed = changed::add;
    s.message("{\"type\":\"Turn\",\"turn_order\":0,\"end_of_turn\":false,\"transcript\":\"\",\"words\":[{\"text\":\"Book\"},{\"text\":\"the\"}]}");
    assertEquals("Book the", s.text());
    s.message("{\"type\":\"Turn\",\"turn_order\":0,\"end_of_turn\":true,\"transcript\":\"Book the table.\",\"words\":[]}");
    assertEquals("Book the table.", s.text());
    s.message("{\"type\":\"Turn\",\"turn_order\":1,\"end_of_turn\":false,\"transcript\":\"Over\"}");
    assertEquals("Book the table. Over", s.text());
    assertEquals(List.of("Book the", "Book the table.", "Book the table. Over"), changed);
    // Not connected: what's live is taken as it is.
    assertEquals("Book the table. Over", s.take(0));
    assertEquals("", s.text());
    s.message("{\"type\":\"Turn\",\"turn_order\":2,\"end_of_turn\":false,\"transcript\":\"Next\"}");
    assertEquals("the next turn starts after the taken one", "Next", s.text());
    s.discard();
    assertEquals("", s.text());
  }

  @Test
  public void universalStreamingEnglishCountsTheFormattedEndOfATurn() throws Exception {
    StreamingSpeech s = StreamingSpeech.create(StreamingSpeech.AAI_EN, "k", List.of("over", "that's all"));
    assertTrue(s.url(), s.url().contains("speech_model=universal-streaming-english&format_turns=true"));
    assertTrue(s.url(), s.url().contains("&keyterms_prompt=%5B%22over%22%2C%22that%27s+all%22%5D"));
    assertFalse(StreamingSpeech.create(StreamingSpeech.AAI_PRO, "k", List.of()).url().contains("format_turns"));
    s.message("{\"type\":\"Turn\",\"end_of_turn\":true,\"turn_is_formatted\":false,\"transcript\":\"book the table over\"}");
    assertEquals("book the table over", s.text());
    s.message("{\"type\":\"Turn\",\"end_of_turn\":true,\"turn_is_formatted\":true,\"transcript\":\"Book the table. Over.\"}");
    assertEquals("Book the table. Over.", s.text());
  }

  @Test
  public void elevenLabsPartialsAndCommitsAndAnErrorEndsIt() throws Exception {
    StreamingSpeech s = StreamingSpeech.create(StreamingSpeech.ELEVEN, "k", List.of());
    assertTrue(s.url(), s.url().endsWith("?model_id=scribe_v2_realtime&audio_format=pcm_16000&language_code=en&commit_strategy=manual"));
    List<String> failures = new CopyOnWriteArrayList<>();
    s.onFailure = failures::add;
    s.message("{\"message_type\":\"session_started\",\"session_id\":\"x\"}");
    s.message("{\"message_type\":\"partial_transcript\",\"text\":\"Remind me\"}");
    assertEquals("Remind me", s.text());
    s.message("{\"message_type\":\"committed_transcript\",\"text\":\"Remind me to call Jana.\"}");
    s.message("{\"message_type\":\"committed_transcript_with_timestamps\",\"text\":\"Remind me to call Jana.\",\"words\":null}");
    assertEquals("Remind me to call Jana.", s.text());
    assertTrue(s.working());
    s.message("{\"message_type\":\"quota_exceeded\",\"error\":\"You have used up your quota\"}");
    assertFalse(s.working());
    assertEquals(List.of("quota_exceeded: You have used up your quota"), failures);
    try {
      s.take(0);
      throw new AssertionError("a failed one can't be taken from");
    } catch (java.io.IOException expected) {
      assertEquals("quota_exceeded: You have used up your quota", expected.getMessage());
    }
  }

  @Test
  public void aWebSocketUpgradesSendsMaskedFramesAndReadsTextAndClose() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      List<String> got = new CopyOnWriteArrayList<>();
      String[] path = {""};
      Thread t =
          new Thread(
              () -> {
                try (Socket c = server.accept()) {
                  InputStream in = c.getInputStream();
                  OutputStream out = c.getOutputStream();
                  StringBuilder head = new StringBuilder();
                  while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
                  path[0] = head.toString().split(" ")[1];
                  String key = head.toString().replaceAll("(?s).*Sec-WebSocket-Key: ([^\r]+).*", "$1");
                  assertTrue(head.toString(), head.toString().contains("Authorization: secret\r\n"));
                  out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + WebSocket.expectedAccept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                  // The client's frame: masked text.
                  int b0 = in.read(), b1 = in.read();
                  assertEquals(0x81, b0);
                  assertTrue("masked", (b1 & 0x80) != 0);
                  byte[] mask = in.readNBytes(4), payload = in.readNBytes(b1 & 0x7f);
                  for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                  got.add(new String(payload, StandardCharsets.UTF_8));
                  // A text message in two fragments, then a close with its code and reason.
                  out.write(new byte[] {0x01, 3, 'h', 'e', 'l'});
                  out.write(new byte[] {(byte) 0x80, 2, 'l', 'o'});
                  out.write(new byte[] {(byte) 0x88, 6, 0x03, (byte) 0xf0, 'b', 'u', 's', 'y'});
                  out.flush();
                  in.readNBytes(8); // Its answer to the close.
                } catch (Exception ex) {
                  got.add("server: " + ex);
                }
              });
      t.start();
      List<String> messages = new CopyOnWriteArrayList<>();
      int[] code = {0};
      String[] why = {null};
      WebSocket ws =
          WebSocket.open(
              "ws://127.0.0.1:" + server.getLocalPort() + "/v3/ws?sample_rate=16000",
              java.util.Map.of("Authorization", "secret"),
              new WebSocket.Listener() {
                public void message(String text) {
                  messages.add(text);
                }

                public void ended(int c, String w) {
                  code[0] = c;
                  why[0] = w;
                }
              },
              2000);
      ws.send("{\"type\":\"ForceEndpoint\"}");
      t.join(3000);
      for (int i = 0; i < 100 && why[0] == null; i++) Thread.sleep(20);
      assertEquals("/v3/ws?sample_rate=16000", path[0]);
      assertEquals(List.of("{\"type\":\"ForceEndpoint\"}"), got);
      assertEquals(List.of("hello"), messages);
      assertEquals(1008, code[0]);
      assertEquals("busy", why[0]);
      assertFalse(ws.open());
    }
  }

  @Test
  public void aLateFinalOfATakenOrDroppedStretchStaysOutOfTheNextTurn() throws Exception {
    String was = StreamingSpeech.elevenLabsUrl;
    try (Server server = new Server(0, true)) {
      StreamingSpeech.elevenLabsUrl = server.url();
      lateFinals();
    } finally {
      StreamingSpeech.elevenLabsUrl = was;
    }
  }

  private void lateFinals() throws Exception {
    StreamingSpeech s = StreamingSpeech.create(StreamingSpeech.ELEVEN, "k", List.of());
    s.speech();
    for (int i = 0; i < 100 && s.openedMs < 0; i++) Thread.sleep(20);
    s.message("{\"message_type\":\"partial_transcript\",\"text\":\"send the report over\"}");
    assertEquals("send the report over", s.take(100)); // No final in time: its live text is taken.
    s.message("{\"message_type\":\"partial_transcript\",\"text\":\"send the report over\"}");
    s.message("{\"message_type\":\"committed_transcript\",\"text\":\"Send the report. Over.\"}");
    assertEquals("its final, late, isn't the next turn's", "", s.text());
    s.message("{\"message_type\":\"partial_transcript\",\"text\":\"Mm\"}");
    s.discard();
    s.message("{\"message_type\":\"committed_transcript\",\"text\":\"Mm.\"}");
    assertEquals("", s.text());
    s.message("{\"message_type\":\"partial_transcript\",\"text\":\"Next\"}");
    assertEquals("Next", s.text());
    s.close();
    // AssemblyAI: a turn that came final twice counts once.
    StreamingSpeech a = StreamingSpeech.create(StreamingSpeech.AAI_PRO, "k", List.of());
    a.message("{\"type\":\"Turn\",\"turn_order\":0,\"end_of_turn\":true,\"transcript\":\"Book it.\"}");
    a.message("{\"type\":\"Turn\",\"turn_order\":0,\"end_of_turn\":true,\"transcript\":\"Book it.\"}");
    assertEquals("Book it.", a.text());
  }

  /** A server that answers the upgrade (after delayMs), then reads what comes and reports when it ends. */
  static final class Server implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0);
    final java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);

    Server(long delayMs, boolean read) throws Exception {
      new Thread(
              () -> {
                try (Socket c = socket.accept()) {
                  InputStream in = c.getInputStream();
                  StringBuilder head = new StringBuilder();
                  while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
                  Thread.sleep(delayMs);
                  String key = head.toString().replaceAll("(?s).*Sec-WebSocket-Key: ([^\r]+).*", "$1");
                  c.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + WebSocket.expectedAccept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                  if (!read) {
                    Thread.sleep(10_000);
                    return;
                  }
                  byte[] b = new byte[4096];
                  while (true) {
                    int n = in.read(b);
                    if (n < 0) break;
                    if (n >= 1 && (b[0] & 0x0f) == 0x8) break; // A close frame.
                  }
                  ended.countDown();
                } catch (Exception ex) {
                  ended.countDown();
                }
              })
          .start();
    }

    String url() {
      return "ws://127.0.0.1:" + socket.getLocalPort() + "/v3/ws";
    }

    @Override
    public void close() throws Exception {
      socket.close();
    }
  }

  /** Connected to a server that takes what comes, with drops lasting 300 ms. */
  private static void connected(String provider, Server server, java.util.concurrent.Callable<Void> test) throws Exception {
    String aai = StreamingSpeech.assemblyAiUrl, el = StreamingSpeech.elevenLabsUrl;
    long drop = StreamingSpeech.DROP_MS;
    try {
      StreamingSpeech.assemblyAiUrl = server.url();
      StreamingSpeech.elevenLabsUrl = server.url();
      StreamingSpeech.DROP_MS = 300;
      test.call();
    } finally {
      StreamingSpeech.assemblyAiUrl = aai;
      StreamingSpeech.elevenLabsUrl = el;
      StreamingSpeech.DROP_MS = drop;
    }
  }

  private static StreamingSpeech open(StreamingSpeech.Choice c) throws Exception {
    StreamingSpeech s = StreamingSpeech.create(c, "k", List.of());
    s.speech();
    for (int i = 0; i < 100 && s.openedMs < 0; i++) Thread.sleep(20);
    return s;
  }

  @Test
  public void wordsStillComingOfAStretchWrittenDownFromItsAudioStayOutOfTheNextTurn() throws Exception {
    // Send before its first live words: the turn went written down from its audio; then they come.
    try (Server server = new Server(0, true)) {
      connected("elevenlabs", server, () -> {
        StreamingSpeech s = open(StreamingSpeech.ELEVEN);
        assertEquals("", s.take(100));
        s.discard();
        s.message("{\"message_type\":\"partial_transcript\",\"text\":\"Book the table\"}");
        s.message("{\"message_type\":\"committed_transcript\",\"text\":\"Book the table.\"}");
        assertEquals("", s.text());
        s.message("{\"message_type\":\"partial_transcript\",\"text\":\"Next\"}");
        assertEquals("Next", s.text());
        s.close();
        return null;
      });
    }
  }

  @Test
  public void anElevenLabsDropWhoseFinalNeverComesEndsAfterAWhile() throws Exception {
    try (Server server = new Server(0, true)) {
      connected("elevenlabs", server, () -> {
        StreamingSpeech s = open(StreamingSpeech.ELEVEN);
        s.message("{\"message_type\":\"partial_transcript\",\"text\":\"Mm\"}");
        s.discard(); // Its final never comes.
        s.message("{\"message_type\":\"partial_transcript\",\"text\":\"Mm\"}");
        assertEquals("", s.text());
        Thread.sleep(400);
        s.message("{\"message_type\":\"partial_transcript\",\"text\":\"Book the table\"}");
        assertEquals("the next stretch's words show", "Book the table", s.text());
        s.close();
        return null;
      });
    }
  }

  @Test
  public void assemblyAiDropsTheTurnStillComingAndNotTheOneAfter() throws Exception {
    try (Server server = new Server(0, true)) {
      connected("assemblyai", server, () -> {
        StreamingSpeech s = open(StreamingSpeech.AAI_PRO);
        s.message("{\"type\":\"Turn\",\"turn_order\":0,\"end_of_turn\":true,\"transcript\":\"Hello.\"}");
        assertEquals("Hello.", s.take(100));
        s.discard(); // Turn 1's words haven't come yet.
        s.message("{\"type\":\"Turn\",\"turn_order\":1,\"end_of_turn\":false,\"transcript\":\"Book the\"}");
        s.message("{\"type\":\"Turn\",\"turn_order\":1,\"end_of_turn\":true,\"transcript\":\"Book the table.\"}");
        assertEquals("", s.text());
        s.message("{\"type\":\"Turn\",\"turn_order\":2,\"end_of_turn\":false,\"transcript\":\"Next\"}");
        assertEquals("Next", s.text());
        // Dropped with nothing coming after it: a turn starting much later is new speech.
        s.take(100);
        s.discard();
        Thread.sleep(400);
        s.message("{\"type\":\"Turn\",\"turn_order\":3,\"end_of_turn\":false,\"transcript\":\"Later\"}");
        assertEquals("Later", s.text());
        s.close();
        return null;
      });
    }
  }

  @Test
  public void closedWhileItConnectsTheNewConnectionIsClosedAtOnce() throws Exception {
    String was = StreamingSpeech.assemblyAiUrl;
    try (Server server = new Server(400, true)) {
      StreamingSpeech.assemblyAiUrl = server.url().replace("/v3/ws", "/v3/ws");
      StreamingSpeech s = StreamingSpeech.create(StreamingSpeech.AAI_PRO, "k", List.of());
      s.speech(); // Connecting (the call starts).
      Thread.sleep(100);
      s.close(); // The call ends before it's open.
      assertTrue("the connection that opened after was closed", server.ended.await(3, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      StreamingSpeech.assemblyAiUrl = was;
    }
  }

  @Test
  public void askingForTheFinalTextThatHangsFailsInsteadOfFiringLater() throws Exception {
    String was = StreamingSpeech.assemblyAiUrl;
    try (Server server = new Server(0, true)) {
      StreamingSpeech.assemblyAiUrl = server.url();
      java.util.concurrent.CountDownLatch stuck = new java.util.concurrent.CountDownLatch(1);
      StreamingSpeech s =
          new StreamingSpeech.AssemblyAi(StreamingSpeech.AAI_PRO, "k", List.of()) {
            @Override
            void force(WebSocket ws) throws Exception {
              stuck.await(); // The connection's write hangs.
            }
          };
      s.speech();
      for (int i = 0; i < 100 && s.openedMs < 0; i++) Thread.sleep(20);
      s.message("{\"type\":\"Turn\",\"turn_order\":0,\"end_of_turn\":false,\"transcript\":\"Book the\"}");
      long start = System.currentTimeMillis();
      try {
        s.take(300);
        throw new AssertionError("it should fail");
      } catch (java.io.IOException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("couldn't ask for the final text"));
      }
      assertTrue(System.currentTimeMillis() - start < 2000);
      assertFalse(s.working());
      assertTrue("the stuck connection is closed (it's billed while open)", server.ended.await(2, java.util.concurrent.TimeUnit.SECONDS));
      s.close();
    } finally {
      StreamingSpeech.assemblyAiUrl = was;
    }
  }

  @Test
  public void aTlsHandshakeThatStallsGivesUp() throws Exception {
    try (ServerSocket silent = new ServerSocket(0)) {
      new Thread(
              () -> {
                try (Socket c = silent.accept()) {
                  Thread.sleep(5000); // Accepts, says nothing.
                } catch (Exception ignored) {
                }
              })
          .start();
      long start = System.currentTimeMillis();
      try {
        WebSocket.open("wss://127.0.0.1:" + silent.getLocalPort() + "/", java.util.Map.of(), null, 500);
        throw new AssertionError("it should give up");
      } catch (java.io.IOException expected) {
        assertTrue(String.valueOf(System.currentTimeMillis() - start), System.currentTimeMillis() - start < 3000);
      }
    }
  }

  @Test
  public void closeDoesntWaitBehindAWriteThatHangs() throws Exception {
    try (Server server = new Server(0, false)) { // It never reads: writes fill up and hang.
      WebSocket ws =
          WebSocket.open(
              server.url(),
              java.util.Map.of(),
              new WebSocket.Listener() {
                public void message(String text) {}

                public void ended(int code, String why) {}
              },
              2000);
      Thread writer =
          new Thread(
              () -> {
                byte[] big = new byte[1 << 20];
                try {
                  while (true) ws.send(big, 0, big.length);
                } catch (Exception gone) {
                  /* Closed under it. */
                }
              });
      writer.start();
      Thread.sleep(800); // By now a write hangs.
      long start = System.currentTimeMillis();
      ws.close();
      assertTrue(String.valueOf(System.currentTimeMillis() - start), System.currentTimeMillis() - start < 500);
      writer.join(2000);
      assertFalse("the hanging write ended too", writer.isAlive());
    }
  }

  @Test
  public void aLongFrameHasItsLengthInTwoOrEightBytes() {
    byte[] mask = {1, 2, 3, 4};
    byte[] f = WebSocket.encode(0x2, new byte[200], mask);
    assertEquals(0x82, f[0] & 0xff);
    assertEquals(0x80 | 126, f[1] & 0xff);
    assertEquals(200, ((f[2] & 0xff) << 8) | (f[3] & 0xff));
    assertEquals(4 + 4 + 200, f.length);
    byte[] g = WebSocket.encode(0x2, new byte[70_000], mask);
    assertEquals(0x80 | 127, g[1] & 0xff);
    assertEquals(70_000, ((g[6] & 0xff) << 24) | ((g[7] & 0xff) << 16) | ((g[8] & 0xff) << 8) | (g[9] & 0xff));
    assertEquals(2 + 8 + 4 + 70_000, g.length);
  }
}
