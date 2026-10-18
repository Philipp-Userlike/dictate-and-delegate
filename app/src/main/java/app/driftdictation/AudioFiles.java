package app.driftdictation;

import static app.driftdictation.I18n.tr;

import java.io.*;
import java.nio.*;

/** 16 kHz mono PCM, with no silence-triggered termination. Chunking happens only after stop. */
final class AudioFiles {
  static final int RATE = 16000;
  static final int BYTES_PER_SECOND = RATE * 2;
  static final int CHUNK_BYTES = BYTES_PER_SECOND * 120;

  record Chunk(byte[] wav, long nextOffset) {}

  static Chunk readChunk(File pcm, long offset) throws IOException {
    try (RandomAccessFile file = new RandomAccessFile(pcm, "r")) {
      long end = file.length() & ~1L;
      if (offset < 0 || (offset & 1) != 0 || offset >= end)
        throw new IOException(tr("Recording contains no readable audio."));
      long boundary = Math.min(end, offset + CHUNK_BYTES);
      // Prefer a quiet 20 ms frame in the final 2 seconds, without dropping any samples.
      if (boundary < end) {
        long best = Long.MAX_VALUE, bestAt = boundary;
        byte[] frame = new byte[640];
        for (long at = boundary - BYTES_PER_SECOND * 2L; at < boundary; at += frame.length) {
          file.seek(at);
          file.readFully(frame);
          long energy = 0;
          for (int i = 0; i < frame.length; i += 2) {
            int sample = (short) ((frame[i] & 255) | (frame[i + 1] << 8));
            energy += Math.abs(sample);
          }
          if (energy < best) {
            best = energy;
            bestAt = at + frame.length / 2;
          }
        }
        boundary = bestAt;
      }
      int count = (int) (boundary - offset);
      ByteBuffer wav = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN);
      wav.put(new byte[] {'R', 'I', 'F', 'F'})
          .putInt(36 + count)
          .put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
          .putInt(16)
          .putShort((short) 1)
          .putShort((short) 1)
          .putInt(RATE)
          .putInt(BYTES_PER_SECOND)
          .putShort((short) 2)
          .putShort((short) 16)
          .put(new byte[] {'d', 'a', 't', 'a'})
          .putInt(count);
      file.seek(offset);
      file.readFully(wav.array(), 44, count);
      return new Chunk(wav.array(), boundary);
    }
  }

  /** 16 kHz 16-bit mono PCM as a WAV file. */
  static byte[] wav(byte[] pcm) {
    ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    wav.put(new byte[] {'R', 'I', 'F', 'F'})
        .putInt(36 + pcm.length)
        .put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(RATE)
        .putInt(BYTES_PER_SECOND)
        .putShort((short) 2)
        .putShort((short) 16)
        .put(new byte[] {'d', 'a', 't', 'a'})
        .putInt(pcm.length)
        .put(pcm);
    return wav.array();
  }

  static String append(String previous, String next) {
    if (next.trim().isEmpty()) return previous;
    return previous.isEmpty() ? next.trim() : previous + "\n" + next.trim();
  }

  static String elapsed(long millis) {
    long seconds = Math.max(0, millis / 1000);
    return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }
}
