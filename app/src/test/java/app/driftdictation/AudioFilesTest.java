package app.driftdictation;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class AudioFilesTest {
  @Test
  public void wavHasValidHeaderAndExactSamples() throws Exception {
    File pcm = File.createTempFile("drift", ".pcm");
    try {
      byte[] samples = new byte[32000];
      new Random(1).nextBytes(samples);
      Files.write(pcm.toPath(), samples);
      AudioFiles.Chunk chunk = AudioFiles.readChunk(pcm, 0);
      ByteBuffer b = ByteBuffer.wrap(chunk.wav()).order(ByteOrder.LITTLE_ENDIAN);
      assertEquals("RIFF", new String(chunk.wav(), 0, 4));
      assertEquals(32036, b.getInt(4));
      assertEquals(16000, b.getInt(24));
      assertEquals(1, b.getShort(22));
      assertEquals(16, b.getShort(34));
      assertEquals(32000, b.getInt(40));
      assertArrayEquals(samples, Arrays.copyOfRange(chunk.wav(), 44, chunk.wav().length));
      assertEquals(samples.length, chunk.nextOffset());
    } finally {
      pcm.delete();
    }
  }

  @Test
  public void longAudioChunksPreserveEverySampleExactlyOnce() throws Exception {
    File pcm = File.createTempFile("drift", ".pcm");
    try {
      byte[] input = new byte[AudioFiles.CHUNK_BYTES * 2 + 1234];
      new Random(7).nextBytes(input);
      Files.write(pcm.toPath(), input);
      ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
      long offset = 0;
      int chunks = 0;
      while (offset < input.length) {
        AudioFiles.Chunk part = AudioFiles.readChunk(pcm, offset);
        assertTrue(part.wav().length <= AudioFiles.CHUNK_BYTES + 44);
        assertTrue(part.nextOffset() > offset);
        rebuilt.write(part.wav(), 44, part.wav().length - 44);
        offset = part.nextOffset();
        chunks++;
      }
      assertEquals(3, chunks);
      assertArrayEquals(input, rebuilt.toByteArray());
    } finally {
      pcm.delete();
    }
  }

  @Test
  public void silenceIsPreserved() throws Exception {
    File pcm = File.createTempFile("drift", ".pcm");
    try {
      Files.write(pcm.toPath(), new byte[AudioFiles.CHUNK_BYTES + 32000]);
      long offset = 0, count = 0;
      while (offset < pcm.length()) {
        var part = AudioFiles.readChunk(pcm, offset);
        count += part.wav().length - 44;
        offset = part.nextOffset();
      }
      assertEquals(pcm.length(), count);
    } finally {
      pcm.delete();
    }
  }

  @Test
  public void interruptedHalfSampleIsSafelyExcluded() throws Exception {
    File pcm = File.createTempFile("drift", ".pcm");
    try {
      Files.write(pcm.toPath(), new byte[] {1, 2, 3});
      assertEquals(2, AudioFiles.readChunk(pcm, 0).nextOffset());
    } finally {
      pcm.delete();
    }
  }

  @Test
  public void emptyRecordingIsRejected() throws Exception {
    File pcm = File.createTempFile("drift", ".pcm");
    try {
      assertThrows(IOException.class, () -> AudioFiles.readChunk(pcm, 0));
    } finally {
      pcm.delete();
    }
  }

  @Test
  public void elapsedSupportsLongRecordings() {
    assertEquals("00:00", AudioFiles.elapsed(-1));
    assertEquals("01:05", AudioFiles.elapsed(65000));
    assertEquals("125:00", AudioFiles.elapsed(7500000));
  }

  @Test
  public void transcriptsKeepOrderWithoutInventingWords() {
    assertEquals("first\nsecond", AudioFiles.append("first", " second "));
    assertEquals("first", AudioFiles.append("first", "   "));
  }
}
