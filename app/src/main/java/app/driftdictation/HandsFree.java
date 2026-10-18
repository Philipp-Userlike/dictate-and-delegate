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
  static final int DEFAULT_PAUSE_MS = 3000, DEFAULT_SILENCE_SEND_MS = 30_000;

  static List<String> words(SharedPreferences prefs, String key, String fallback) {
    return list(prefs.getString(key, fallback));
  }

  static String endSetting(SharedPreferences prefs) {
    return prefs.getString(END_WORDS, DEFAULT_END);
  }

  static int pauseMs(SharedPreferences prefs) {
    return Math.max(800, Math.min(15_000, prefs.getInt(PAUSE_MS, DEFAULT_PAUSE_MS)));
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
  static final int DEFAULT_TAKE_MS = 1000;

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
   * don't. A stretch only counts once it holds minSpeechMs of such frames (3); shorter ones are
   * noise. It ends after pauseMs without speech, and keeps a little audio before and after it.
   * Silence in between never goes to the speech model.
   */
  static final class Segmenter {
    static final int FRAME_MS = 20, PAD_MS = 300;

    interface Listener {
      /** A stretch of speech ended: its 16 kHz 16-bit mono PCM. */
      void segment(byte[] pcm);

      /** It just became speech (minSpeechMs reached): level and background, for the log. */
      default void started(double level, double background) {}

      /** It ended too short: noise, dropped. */
      default void noise(int speechMs, double level, double background) {}
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
    long quietMs() {
      return quietMs;
    }

    /** Speech is going on (it reached minSpeechMs and hasn't ended yet). */
    boolean speaking() {
      return current != null && confirmed;
    }

    /** Something loud started but isn't confirmed speech yet. */
    boolean candidate() {
      return current != null;
    }

    double background() {
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
    void frame(byte[] frame) {
      double level = level(frame, frame.length);
      boolean loud = detector.speech(frame, level, background) && level >= background * gate;
      if (!loud) background = background * 0.995 + level * 0.005; // Follows the quiet frames only.
      // Two loud frames in a row start a candidate, so a click doesn't.
      streak = loud ? streak + 1 : 0;
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
      // A candidate that isn't confirmed ends after a short gap; speech after the full pause.
      if (silentFrames >= (confirmed ? pauseFrames : Math.min(pauseFrames, 500 / FRAME_MS))) finish();
    }

    private void confirm(double level) {
      if (confirmed || speechFrames * FRAME_MS < minSpeechMs.getAsInt()) return;
      confirmed = true;
      quietMs = 0;
      listener.started(peak, background);
    }

    /** Ends the current stretch now (Send pressed, or the mode ends). */
    void flush() {
      if (current != null) finish();
    }

    /** Forgets everything in progress (a reply was read meanwhile). */
    void reset() {
      current = null;
      before.clear();
      silentFrames = 0;
      speechFrames = 0;
      streak = 0;
      confirmed = false;
      quietMs = 0;
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
      if (real) listener.segment(java.util.Arrays.copyOf(all, keep));
      else listener.noise(ms, peak, background);
    }
  }
}
