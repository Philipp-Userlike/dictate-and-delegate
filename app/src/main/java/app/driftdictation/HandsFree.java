package app.driftdictation;

import android.content.SharedPreferences;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hands-free mode: talking with the chief of staff without touching the phone. The phone listens
 * (voice activity, no model), transcribes each stretch of speech once when you pause, and sends the
 * message when it ends with one of your send words ("over"), or after a long silence; an end word
 * ends the mode. The chief's spoken replies are read aloud with the microphone off. These are the
 * settings and the pure parts: keyword matching and the voice activity segmenter.
 */
final class HandsFree {
  private HandsFree() {}

  /** Marks a message from hands-free mode for the chief (the dictate-and-delegate skill). */
  static final String TAG = "[hands-free]";

  /** What the chief hears when a call starts and ends: whether spoken messages are heard. */
  static final String CALL_STARTED = "[hands-free call started]", CALL_ENDED = "[hands-free call ended]";

  /** Prefs keys. */
  static final String SEND_WORDS = "hfSendWords",
      END_WORDS = "hfEndWords",
      PAUSE_MS = "hfPauseMs",
      SILENCE_SEND_MS = "hfSilenceSendMs",
      VOICE = "hfVoice";

  /** One English word per action by default; each setting may hold several, comma-separated. */
  static final String DEFAULT_SEND = "over", DEFAULT_END = "end the call";
  /**
   * A short pause ends a part: it's written down then, and a send or end word at its end acts at
   * once. Each part is transcribed once, so short parts cost the same as one long one.
   */
  static final int DEFAULT_PAUSE_MS = 800, DEFAULT_SILENCE_SEND_MS = 30_000;

  static List<String> words(SharedPreferences prefs, String key, String fallback) {
    return list(prefs.getString(key, fallback));
  }

  static String endSetting(SharedPreferences prefs) {
    return prefs.getString(END_WORDS, DEFAULT_END);
  }

  static int pauseMs(SharedPreferences prefs) {
    return Math.max(500, Math.min(15_000, prefs.getInt(PAUSE_MS, DEFAULT_PAUSE_MS)));
  }

  /** Prefs: quiet sounds for the key moments (on by default), and how long a voice model may take. */
  static final String SOUNDS = "hfSounds", VOICE_WAIT_MS = "hfVoiceWaitMs";

  /**
   * A voice model slower than this to send a reply's audio is given up for Android's voice:
   * Kokoro sometimes took 13 s. ElevenLabs answers in about half a second.
   */
  static final int DEFAULT_VOICE_WAIT_MS = 1500;

  static int voiceWaitMs(SharedPreferences prefs) {
    return Math.max(500, Math.min(60_000, prefs.getInt(VOICE_WAIT_MS, DEFAULT_VOICE_WAIT_MS)));
  }

  static int silenceSendMs(SharedPreferences prefs) {
    return Math.max(5_000, Math.min(300_000, prefs.getInt(SILENCE_SEND_MS, DEFAULT_SILENCE_SEND_MS)));
  }

  /** "over, send ,  senden" → [over, send, senden]: comma- or line-separated, blanks dropped. */
  static List<String> list(String setting) {
    List<String> out = new ArrayList<>();
    for (String w : (setting == null ? "" : setting).split("[,\\n]"))
      if (!normalize(w).isEmpty()) out.add(w.trim());
    return out;
  }

  // ------------------------------------------------------------------ keywords (unit-tested)

  /** Lower case, accents and punctuation gone, single spaces: "Das war's!" → "das wars". */
  static String normalize(String s) {
    String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    n = n.toLowerCase(Locale.ROOT).replaceAll("['’`]", "").replaceAll("[^\\p{L}\\p{N}]+", " ");
    return n.trim();
  }

  /**
   * If the text ends with one of the words (a phrase of one or more words, as a whole word, case,
   * punctuation and accents ignored), the text without it; otherwise null. "Book the table. Over."
   * with [over] → "Book the table."
   */
  static String endsWith(String text, List<String> words) {
    String t = text == null ? "" : text;
    String norm = normalize(t);
    for (String w : words) {
      String nw = normalize(w);
      if (nw.isEmpty()) continue;
      if (norm.equals(nw) || norm.endsWith(" " + nw)) return cut(t, nw.split(" ").length);
    }
    return null;
  }

  /**
   * A part said on its own (one or two words) that sounds like a one-word send word: one letter
   * off ("ovr", "oh ver"), or a known mishearing ("ova"). The send word, or null. Only for parts
   * on their own, so "the best day ever" never sends.
   */
  static String nearWord(String text, List<String> words) {
    String n = normalize(text);
    if (n.isEmpty()) return null;
    String[] w = n.split(" ");
    if (w.length > 2) return null;
    String last = w[w.length - 1], joined = n.replace(" ", "");
    for (String word : words) {
      String nw = normalize(word);
      if (nw.contains(" ") || nw.length() < 4) continue;
      if (nw.equals("over") && (joined.equals("ova") || joined.equals("ovah"))) return word.trim();
      // One word on its own, or two that join into it ("oh ver"): never a part with other words.
      if (w.length == 1 && distance(last, nw) <= 1 || distance(joined, nw) <= 1) return word.trim();
    }
    return null;
  }

  /** Edit distance (insert, delete, change a letter). */
  static int distance(String a, String b) {
    int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++)
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return prev[b.length()];
  }

  /** The text without its last n words, and without the punctuation left before them. */
  private static String cut(String text, int n) {
    String t = text.replaceAll("[^\\p{L}\\p{N}]+$", "");
    for (int i = 0; i < n; i++) {
      t = t.replaceAll("[\\p{L}\\p{N}'’`]+$", "");
      if (i < n - 1) t = t.replaceAll("[^\\p{L}\\p{N}]+$", "");
    }
    return t.replaceAll("[\\s,;:–—-]+$", "").trim();
  }

  // ------------------------------------------------------------------ voice activity (unit-tested)

  /** Prefs: how much louder than the background your voice has to be, and how long you speak to take the floor. */
  static final String GATE = "hfGate", TAKE_MS = "hfTakeMs";

  static final double DEFAULT_GATE = 3.0;
  /**
   * Voice needed to take the turn, counted within the last TAKE_WINDOW_MS, so natural speech with
   * its little gaps counts ("Hi, can you hear me"); 1 s without gaps was too much for it.
   */
  static final int DEFAULT_TAKE_MS = 400;

  static double gate(SharedPreferences prefs) {
    return Math.max(1.5, Math.min(20, prefs.getFloat(GATE, (float) DEFAULT_GATE)));
  }

  static int takeMs(SharedPreferences prefs) {
    return Math.max(200, Math.min(5000, prefs.getInt(TAKE_MS, DEFAULT_TAKE_MS)));
  }

  /** Layer 1: is this frame speech at all? Today by its level; a model (Silero) can take its place. */
  interface Detector {
    boolean speech(byte[] frame, double level, double background);
  }

  /** The level-based detector: clearly above the background, and above a fixed minimum. */
  static final Detector LEVEL = (frame, level, background) -> level > Math.max(600, background * 2);

  /**
   * Splits a stream of 20 ms frames into stretches of your speech, in three layers: a frame counts
   * if the detector calls it speech (1) and it's at least gate times louder than the measured
   * background (2), so your voice close to the phone counts and distant voices or music mostly
   * don't. A stretch only counts once it holds minSpeechMs of such frames within the last
   * WINDOW_MS, or a burst of BURST_FRAMES at least BURST times louder than the background (3);
   * shorter ones are noise. It ends after pauseMs without speech, and keeps a little audio before
   * and after it. Silence in between never goes to the speech model.
   */
  static final class Segmenter {
    // Synchronized: the microphone feeds it while Send, the ticker and a barge-in use it too.
    static final int FRAME_MS = 20, PAD_MS = 300, WINDOW_MS = 1500, BURST_FRAMES = 5;
    static final double BURST = 10;
    /** The background is learned fast over the first quiet frames (the default is a guess), then slowly. */
    static final int LEARN_FRAMES = 50;

    interface Listener {
      /** A stretch of speech ended: its 16 kHz 16-bit mono PCM. */
      void segment(byte[] pcm);

      /** It just became speech (minSpeechMs reached): level and background, for the log. */
      default void started(double level, double background) {}

      /** It ended too short: noise, dropped. */
      default void noise(int speechMs, double level, double background) {}

      /**
       * Something like a voice went on for a while but never was gate times louder than the
       * background, so it didn't count (only reported, for the reasons on screen).
       */
      default void tooQuiet(int ms, double level, double background) {}
    }

    private final int pauseFrames, padFrames = PAD_MS / FRAME_MS;
    private final java.util.function.IntSupplier minSpeechMs;
    private final double gate;
    private final Detector detector;
    private final Listener listener;
    private final java.util.ArrayDeque<byte[]> before = new java.util.ArrayDeque<>();
    private java.io.ByteArrayOutputStream current;
    private int silentFrames, speechFrames, streak;
    private boolean confirmed;
    private double background = 300, peak;
    private long quietMs;
    // A stretch of sound that's too quiet to count (for the reasons only; it changes nothing).
    private int nearFrames, nearGap;
    private double nearPeak;
    /** Frame numbers of the recent loud frames (within the window), and frames seen in all. */
    private final java.util.ArrayDeque<Long> voiced = new java.util.ArrayDeque<>();
    private long frameNo;
    private int quietFrames;
    /** The audio of sounds that didn't count, since the last stretch that did: Send writes it down. */
    private java.io.ByteArrayOutputStream noiseAudio;
    private long noiseAt;

    Segmenter(int pauseMs, Listener listener) {
      this(pauseMs, () -> 200, DEFAULT_GATE, LEVEL, listener);
    }

    Segmenter(int pauseMs, java.util.function.IntSupplier minSpeechMs, double gate, Detector detector, Listener listener) {
      this.pauseFrames = Math.max(1, pauseMs / FRAME_MS);
      this.minSpeechMs = minSpeechMs;
      this.gate = gate;
      this.detector = detector;
      this.listener = listener;
    }

    /** Milliseconds since the last confirmed speech (or since the start). */
    synchronized long quietMs() {
      return quietMs;
    }

    /** Speech is going on (it reached minSpeechMs and hasn't ended yet). */
    synchronized boolean speaking() {
      return current != null && confirmed;
    }

    /** Something loud started but isn't confirmed speech yet. */
    synchronized boolean candidate() {
      return current != null;
    }

    synchronized double background() {
      return background;
    }

    /** Root mean square of a frame of 16-bit little-endian samples. */
    static double level(byte[] frame, int length) {
      double sum = 0;
      int n = length / 2;
      for (int i = 0; i + 1 < length; i += 2) {
        int s = (short) ((frame[i] & 0xff) | (frame[i + 1] << 8));
        sum += (double) s * s;
      }
      return n == 0 ? 0 : Math.sqrt(sum / n);
    }

    /** Feeds one 20 ms frame (640 bytes at 16 kHz). */
    synchronized void frame(byte[] frame) {
      double level = level(frame, frame.length);
      boolean loud = detector.speech(frame, level, background) && level >= background * gate;
      if (!loud) { // Follows the quiet frames only.
        double a = quietFrames++ < LEARN_FRAMES ? 0.1 : 0.005;
        background = background * (1 - a) + level * a;
      }
      frameNo++;
      if (loud) voiced.addLast(frameNo);
      long window = Math.max(WINDOW_MS, minSpeechMs.getAsInt() * 3 / 2) / FRAME_MS;
      while (!voiced.isEmpty() && voiced.peekFirst() <= frameNo - window) voiced.removeFirst();
      // Two loud frames in a row start a candidate, so a click doesn't.
      streak = loud ? streak + 1 : 0;
      nearly(loud, level);
      if (current == null) {
        before.addLast(frame.clone());
        while (before.size() > padFrames) before.removeFirst();
        quietMs += FRAME_MS;
        if (streak >= 2) {
          current = new java.io.ByteArrayOutputStream();
          for (byte[] b : before) current.write(b, 0, b.length);
          before.clear();
          speechFrames = streak;
          silentFrames = 0;
          confirmed = false;
          peak = level;
          confirm(level);
        }
        return;
      }
      current.write(frame, 0, frame.length);
      if (loud) {
        speechFrames++;
        silentFrames = 0;
        peak = Math.max(peak, level);
        confirm(level);
      } else {
        silentFrames++;
        if (confirmed) quietMs += FRAME_MS;
      }
      // Both end after the pause: a candidate's little gaps (between words) don't end it.
      if (silentFrames >= pauseFrames) finish();
    }

    /**
     * Notes sound that's clearly there (half again the background, above the detector's minimum)
     * but not loud enough to count: once it's gone for half a second after at least 0.3 s of it,
     * the listener hears it was too quiet. Thresholds stay as they are; this only reports.
     */
    private void nearly(boolean loud, double level) {
      boolean near = !loud && current == null && level > Math.max(600, background * 1.5);
      if (near) {
        nearFrames++;
        nearGap = 0;
        nearPeak = Math.max(nearPeak, level);
        return;
      }
      if (nearFrames == 0) return;
      if (loud || current != null) { // It became speech after all.
        nearFrames = 0;
        nearPeak = 0;
        return;
      }
      if (++nearGap < 500 / FRAME_MS) return;
      if (nearFrames * FRAME_MS >= 300) listener.tooQuiet(nearFrames * FRAME_MS, nearPeak, background);
      nearFrames = 0;
      nearGap = 0;
      nearPeak = 0;
    }

    double gate() {
      return gate;
    }

    /** Enough voice within the window, or a clearly loud burst. */
    private void confirm(double level) {
      if (confirmed) return;
      boolean enough = voiced.size() * FRAME_MS >= minSpeechMs.getAsInt();
      boolean burst = speechFrames >= BURST_FRAMES && peak >= background * BURST;
      if (!enough && !burst) return;
      confirmed = true;
      quietMs = 0;
      listener.started(peak, background);
    }

    /** Ends the current stretch now (the mode ends). */
    void flush() {
      flush(false);
    }

    /**
     * Ends the current stretch now; with force (Send pressed) a stretch that didn't count yet is
     * written down anyway, unless it's only a click.
     */
    synchronized void flush(boolean force) {
      if (current == null) return;
      if (force && speechFrames >= 2) confirmed = true;
      finish();
    }

    /**
     * The sounds that didn't count as speech in the last ten seconds (since the last stretch that
     * did), or null: Send pressed with nothing written down writes them down. Taken once.
     */
    synchronized byte[] takeNoise() {
      java.io.ByteArrayOutputStream n = noiseAudio;
      noiseAudio = null;
      if (n == null || frameNo - noiseAt > 10_000 / FRAME_MS) return null;
      return n.toByteArray();
    }

    /** Forgets everything in progress (a reply was read meanwhile). */
    synchronized void reset() {
      current = null;
      before.clear();
      silentFrames = 0;
      speechFrames = 0;
      streak = 0;
      confirmed = false;
      quietMs = 0;
      nearFrames = nearGap = 0;
      nearPeak = 0;
      voiced.clear();
      noiseAudio = null;
    }

    private void finish() {
      byte[] all = current.toByteArray();
      int frameBytes = AudioFiles.RATE * 2 * FRAME_MS / 1000;
      // Keep only padFrames of the trailing silence.
      int keep = Math.max(0, all.length - Math.max(0, silentFrames - padFrames) * frameBytes);
      boolean real = confirmed;
      int ms = speechFrames * FRAME_MS;
      current = null;
      silentFrames = 0;
      speechFrames = 0;
      confirmed = false;
      voiced.clear();
      if (real) {
        noiseAudio = null;
        listener.segment(java.util.Arrays.copyOf(all, keep));
      } else {
        if (noiseAudio == null || frameNo - noiseAt > 10_000 / FRAME_MS) noiseAudio = new java.io.ByteArrayOutputStream();
        if (noiseAudio.size() < 15 * AudioFiles.BYTES_PER_SECOND) noiseAudio.write(all, 0, keep);
        noiseAt = frameNo;
        listener.noise(ms, peak, background);
      }
    }
  }
}
