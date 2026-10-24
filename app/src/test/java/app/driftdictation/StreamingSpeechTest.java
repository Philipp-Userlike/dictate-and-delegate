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
