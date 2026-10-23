package app.driftdictation;

/**
 * A reply's audio as it comes in: 16-bit PCM samples, added while the voice model still sends
 * (decoded from MP3 first when that's what it sends), read by the player at the same time. So a
 * reply plays from its first fifth of a second instead of after its whole file. Its length is
 * known once it's complete.
 */
final class VoiceStream {
  /** Playing starts once this much audio is in (or all of it, if it's shorter). */
  static final int START_MS = 200;

  private int rate, channels;
  private byte[] pcm = new byte[64 * 1024];
  private int length;
  private boolean complete;
  private String failed;
  /** When it ended, ms after it was asked for (-1 while it's still coming). */
  private long allMs = -1;
  private java.util.function.Consumer<VoiceStream> whenDone;
  /** Stops the download (a reply you talked over isn't fetched on). */
  volatile Runnable cancel = () -> {};

  /** The samples' rate and channels, before the first are added. */
  synchronized void format(int rate, int channels) {
    this.rate = rate;
    this.channels = channels;
  }

  synchronized int rate() {
    return rate;
  }

  synchronized int channels() {
    return channels;
  }

  synchronized void add(byte[] b, int off, int n) {
    if (complete || n <= 0) return;
    if (length + n > pcm.length) pcm = java.util.Arrays.copyOf(pcm, Math.max(pcm.length * 2, length + n));
    System.arraycopy(b, off, pcm, length, n);
    length += n;
    notifyAll();
  }

  /** All of it is in (allMs: when, after it was asked for). */
  void end(long allMs) {
    done(null, allMs);
  }

  /** It stopped coming: why, in a few words. What came before still plays. */
  void fail(String why, long allMs) {
    done(why == null ? "failed" : why, allMs);
  }

  private void done(String why, long ms) {
    java.util.function.Consumer<VoiceStream> then;
    synchronized (this) {
      if (complete) return;
      complete = true;
      failed = why;
      allMs = ms;
      then = whenDone;
      whenDone = null;
      notifyAll();
    }
    if (then != null) then.accept(this);
  }

  /** Hears once when it's complete (at once if it is). */
  void whenDone(java.util.function.Consumer<VoiceStream> then) {
    synchronized (this) {
      if (!complete) {
        whenDone = then;
        return;
      }
    }
    then.accept(this);
  }

  synchronized boolean complete() {
    return complete;
  }

  /** Why it stopped coming, null if it came whole (or is still coming). */
  synchronized String failed() {
    return failed;
  }

  synchronized long allMs() {
    return allMs;
  }

  /** Bytes in so far. */
  synchronized int bytes() {
    return length;
  }

  /** Milliseconds of audio in so far (all of it, once complete). */
  synchronized long audioMs() {
    return rate <= 0 || channels <= 0 ? 0 : length / 2 / channels * 1000L / rate;
  }

  /** Enough is in to start playing: START_MS, or all of it (when it's shorter and came whole). */
  synchronized boolean playable() {
    return audioMs() >= START_MS || complete && failed == null && length >= 2 * Math.max(1, channels);
  }

  /**
   * Copies whole sample frames from byte position at into buffer, waiting at most waitMs for them
   * to come: the number of bytes copied, 0 if none came in time, -1 at the end.
   */
  synchronized int read(int at, byte[] buffer, long waitMs) throws InterruptedException {
    long until = System.currentTimeMillis() + waitMs;
    int frame = 2 * Math.max(1, channels);
    while (true) {
      int whole = length / frame * frame;
      if (whole > at) {
        int n = Math.min(buffer.length / frame * frame, whole - at);
        System.arraycopy(pcm, at, buffer, 0, n);
        return n;
      }
      if (complete) return -1;
      long left = until - System.currentTimeMillis();
      if (left <= 0) return 0;
      wait(left);
    }
  }

  /**
   * How many characters of a reply have been heard after playedMs of its audio: in proportion,
   * a third of a second less for the sound still on its way to the ear. While it's still coming
   * its whole length isn't known: then at most at charsPerSecond (a guess on the low side, so the
   * chief is never told you heard more than you did), and never more than its share of what's in.
   */
  static int heardChars(int textLength, long playedMs, long audioMs, boolean complete, double charsPerSecond) {
    long at = Math.max(0, playedMs - 300);
    if (audioMs <= 0 || at == 0) return 0;
    long share = Math.min(textLength, textLength * at / audioMs);
    if (complete) return (int) share;
    return (int) Math.min(share, (long) (at * charsPerSecond / 1000));
  }

  /** MP3 frames, read from the bytes as they come; the frames go to the decoder one by one. */
  static final class Mp3Frames {
    private static final int[] BITRATES_V1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
        BITRATES_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[][] RATES = {{11025, 12000, 8000}, {}, {22050, 24000, 16000}, {44100, 48000, 32000}};

    private byte[] in = new byte[32 * 1024];
    private int length;
    /** An ID3 tag's bytes still to skip. */
    private long skip;
    private boolean started;
    /** The first frame's rate, channels and samples per channel (0 until one is found). */
    int rate, channels, samples;

    /**
     * The frame's length in bytes if a Layer III frame header starts at b[i] (with its rate and
     * channels in format), else 0.
     */
    static int frameLength(byte[] b, int i, int[] format) {
      if (i + 4 > b.length || (b[i] & 0xff) != 0xff || (b[i + 1] & 0xe0) != 0xe0) return 0;
      int version = (b[i + 1] >> 3) & 3, layer = (b[i + 1] >> 1) & 3;
      int bitrate = (b[i + 2] >> 4) & 15, rateIndex = (b[i + 2] >> 2) & 3, padding = (b[i + 2] >> 1) & 1;
      if (version == 1 || layer != 1 || bitrate == 0 || bitrate == 15 || rateIndex == 3) return 0;
      int rate = RATES[version][rateIndex];
      int kbps = (version == 3 ? BITRATES_V1 : BITRATES_V2)[bitrate];
      if (format != null) {
        format[0] = rate;
        format[1] = ((b[i + 3] >> 6) & 3) == 3 ? 1 : 2;
      }
      return (version == 3 ? 144 : 72) * kbps * 1000 / rate + padding;
    }

    /** Adds bytes as they came. */
    void add(byte[] b, int off, int n) {
      if (length + n > in.length) in = java.util.Arrays.copyOf(in, Math.max(in.length * 2, length + n));
      System.arraycopy(b, off, in, length, n);
      length += n;
    }

    /**
     * The next whole frame, or null until more bytes come (last: no more will, so a last frame
     * cut short is dropped). Bytes that aren't a frame (an ID3 tag, junk) are skipped.
     */
    byte[] next(boolean last) {
      int[] format = new int[2];
      int i = 0;
      try {
        while (true) {
          if (skip > 0) {
            int n = (int) Math.min(skip, length - i);
            i += n;
            skip -= n;
            if (skip > 0) return null;
          }
          if (!started && length - i >= 10 && in[i] == 'I' && in[i + 1] == 'D' && in[i + 2] == '3') {
            // ID3v2: its size in four 7-bit bytes, after the 10-byte header (and a footer if flagged).
            skip = 10 + (((in[i + 6] & 0x7f) << 21) | ((in[i + 7] & 0x7f) << 14) | ((in[i + 8] & 0x7f) << 7) | (in[i + 9] & 0x7f))
                + ((in[i + 5] & 0x10) != 0 ? 10 : 0);
            continue;
          }
          if (length - i < 4) return null;
          if (!started && length - i < 10 && in[i] == 'I') return null; // Maybe a tag: wait for its header.
          int frame = frameLength(java.util.Arrays.copyOfRange(in, i, i + 4), 0, format);
          if (frame == 0) {
            i++;
            continue;
          }
          if (length - i < frame) return null;
          started = true;
          if (rate == 0) {
            rate = format[0];
            channels = format[1];
            samples = ((in[i + 1] >> 3) & 3) == 3 ? 1152 : 576;
          }
          byte[] out = java.util.Arrays.copyOfRange(in, i, i + frame);
          i += frame;
          return out;
        }
      } finally {
        // What's been read (or skipped) is dropped.
        System.arraycopy(in, i, in, 0, length - i);
        length -= i;
        if (last && length > 0 && skip == 0 && frameLength(java.util.Arrays.copyOf(in, Math.min(4, length)), 0, null) == 0) length = 0;
      }
    }
  }
}
