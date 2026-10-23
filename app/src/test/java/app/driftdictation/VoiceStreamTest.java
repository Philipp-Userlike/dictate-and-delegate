package app.driftdictation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class VoiceStreamTest {
  /**
   * The fake OpenRouter's reply: a real MP3 (MPEG-2 Layer III, 24 kHz mono, 64 kbps). It isn't in
   * git (*.mp3 is ignored): without it the tests that need it are skipped.
   */
  static byte[] replyMp3() throws Exception {
    Path p = Path.of("../scripts/fakes/reply.mp3");
    org.junit.Assume.assumeTrue("scripts/fakes/reply.mp3 isn't there", Files.exists(p));
    return Files.readAllBytes(p);
  }

  static List<byte[]> frames(byte[] mp3, int chunk) {
    VoiceStream.Mp3Frames f = new VoiceStream.Mp3Frames();
    List<byte[]> out = new ArrayList<>();
    for (int i = 0; i < mp3.length; i += chunk) {
      f.add(mp3, i, Math.min(chunk, mp3.length - i));
      for (byte[] frame; (frame = f.next(false)) != null; ) out.add(frame);
    }
    for (byte[] frame; (frame = f.next(true)) != null; ) out.add(frame);
    return out;
  }

  @Test
  public void anMp3ComingInPiecesIsCutIntoTheSameFramesAsWhole() throws Exception {
    byte[] mp3 = replyMp3();
    List<byte[]> whole = frames(mp3, mp3.length);
    // 64 kbps at 24 kHz: 192-byte frames of 24 ms; the file is about two seconds.
    assertTrue(String.valueOf(whole.size()), whole.size() > 60);
    for (int chunk : new int[] {1, 7, 100, 1000, 4096}) {
      List<byte[]> pieces = frames(mp3, chunk);
      assertEquals("chunks of " + chunk, whole.size(), pieces.size());
      for (int i = 0; i < whole.size(); i++) assertArrayEquals(whole.get(i), pieces.get(i));
    }
    VoiceStream.Mp3Frames f = new VoiceStream.Mp3Frames();
    f.add(mp3, 0, mp3.length);
    f.next(false);
    assertEquals(24000, f.rate);
    assertEquals(1, f.channels);
    assertEquals(576, f.samples);
  }

  @Test
  public void anId3TagAndJunkBeforeTheFramesAreSkipped() throws Exception {
    byte[] mp3 = replyMp3();
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // ID3v2 with 20 bytes of tag data (one of them 0xFF, which mustn't count as a frame).
    b.writeBytes(new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 20});
    byte[] tag = new byte[20];
    tag[3] = (byte) 0xff;
    tag[4] = (byte) 0xfb;
    b.writeBytes(tag);
    b.writeBytes(new byte[] {1, 2, 3}); // Junk.
    b.writeBytes(mp3);
    List<byte[]> plain = frames(mp3, 4096);
    for (int chunk : new int[] {3, 11, 4096}) assertEquals(plain.size(), frames(b.toByteArray(), chunk).size());
  }

  @Test
  public void aFrameHeaderGivesItsLength() {
    // MPEG-1 Layer III, 128 kbps, 44.1 kHz, no padding: 417 bytes; stereo.
    int[] format = new int[2];
    assertEquals(417, VoiceStream.Mp3Frames.frameLength(new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x00}, 0, format));
    assertEquals(44100, format[0]);
    assertEquals(2, format[1]);
    // With padding: one more.
    assertEquals(418, VoiceStream.Mp3Frames.frameLength(new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0x92, 0x00}, 0, null));
    // MPEG-2, 64 kbps, 24 kHz, mono: 192.
    assertEquals(192, VoiceStream.Mp3Frames.frameLength(new byte[] {(byte) 0xff, (byte) 0xf3, (byte) 0x84, (byte) 0xc0}, 0, format));
    assertEquals(1, format[1]);
    // Not Layer III, a bad bitrate, a bad rate, no sync: none.
    assertEquals(0, VoiceStream.Mp3Frames.frameLength(new byte[] {(byte) 0xff, (byte) 0xfd, (byte) 0x90, 0}, 0, null));
    assertEquals(0, VoiceStream.Mp3Frames.frameLength(new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0xf0, 0}, 0, null));
    assertEquals(0, VoiceStream.Mp3Frames.frameLength(new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0x9c, 0}, 0, null));
    assertEquals(0, VoiceStream.Mp3Frames.frameLength(new byte[] {(byte) 0xfe, (byte) 0xfb, (byte) 0x90, 0}, 0, null));
  }

  @Test
  public void itPlaysFromAFifthOfASecondAndIsReadWhileItComes() throws Exception {
    VoiceStream s = new VoiceStream();
    s.format(24000, 1);
    s.add(new byte[24000 * 2 / 10], 0, 24000 * 2 / 10); // 100 ms.
    assertFalse(s.playable());
    assertEquals(100, s.audioMs());
    s.add(new byte[24000 * 2 / 10 + 1], 0, 24000 * 2 / 10 + 1); // 200 ms and half a sample.
    assertTrue(s.playable());
    byte[] buffer = new byte[100_000];
    assertEquals("whole samples only", 9600, s.read(0, buffer, 0));
    assertEquals("nothing more yet", 0, s.read(9600, buffer, 10));
    // More comes while the player waits for it.
    new Thread(
            () -> {
              try {
                Thread.sleep(50);
              } catch (InterruptedException ignored) {
              }
              s.add(new byte[99], 0, 99);
            })
        .start();
    assertEquals(100, s.read(9600, buffer, 2000));
    s.end(4000);
    assertEquals(-1, s.read(9700, buffer, 10));
    assertTrue(s.complete());
    assertNull(s.failed());
    assertEquals(4000, s.allMs());
  }

  @Test
  public void aShortReplyPlaysOnceItsAllInAndAFailedOneSaysWhy() {
    VoiceStream s = new VoiceStream();
    s.format(24000, 1);
    s.add(new byte[480], 0, 480); // 10 ms.
    assertFalse(s.playable());
    List<VoiceStream> told = new ArrayList<>();
    s.whenDone(told::add);
    s.end(300);
    assertTrue(s.playable());
    assertEquals(1, told.size());
    VoiceStream failed = new VoiceStream();
    failed.format(24000, 1);
    failed.add(new byte[480], 0, 480);
    failed.fail("its audio stopped coming after 30 s", 31_000);
    assertFalse(failed.playable());
    assertEquals("its audio stopped coming after 30 s", failed.failed());
    failed.whenDone(told::add); // Already done: at once.
    assertEquals(2, told.size());
  }

  @Test
  public void howFarYouHeardIsInProportionOnceItsAllInAndAtMostAGuessBefore() {
    String text = "x".repeat(400);
    // All in: 26 s of audio for 400 characters; 13.3 s played is half (less the 0.3 s on its way).
    assertEquals(200, VoiceStream.heardChars(400, 13_300, 26_000, true, 15));
    // Still coming, 5 s of it in after 1.3 s played: not 80 characters (its share of what's in), 15 a second at most.
    assertEquals(15, VoiceStream.heardChars(text.length(), 1300, 5000, false, 15));
    // Most of it in: its share is the smaller.
    assertEquals(40, VoiceStream.heardChars(400, 2300, 20_000, false, 30));
    assertEquals(0, VoiceStream.heardChars(400, 200, 5000, false, 15));
    assertEquals(0, VoiceStream.heardChars(400, 5000, 0, false, 15));
  }
}
