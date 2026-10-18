package app.driftdictation;

import android.content.SharedPreferences;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hands-free mode: talking with the chief of staff without touching the phone. The phone listens
 * (voice activity, no model), transcribes each stretch of speech once when you pause, and sends the
 * message when it ends with one of your send words ("over", "send"), or after a long silence. A
 * stop word ends the reading of a reply; an end word ends the mode. The chief's spoken replies are
 * read aloud. These are the settings and the pure parts: keyword matching and the voice activity
 * segmenter.
 */
final class HandsFree {
  private HandsFree() {}

  /** Marks a message from hands-free mode for the chief (the dictate-and-delegate skill). */
  static final String TAG = "[hands-free]";

  /** Prefs keys. */
  static final String SEND_WORDS = "hfSendWords",
      END_WORDS = "hfEndWords",
      STOP_WORDS = "hfStopWords",
      PAUSE_MS = "hfPauseMs",
      SILENCE_SEND_MS = "hfSilenceSendMs",
      VOICE = "hfVoice";

  static final String DEFAULT_SEND = "over, send, senden, schick ab",
      DEFAULT_END = "that's all, das war's, das wars",
      DEFAULT_STOP = "stop, stopp";
  static final int DEFAULT_PAUSE_MS = 3000, DEFAULT_SILENCE_SEND_MS = 30_000;

  static List<String> words(SharedPreferences prefs, String key, String fallback) {
    return list(prefs.getString(key, fallback));
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

  /**
   * What a speech model writes for silence or noise ("Thank you.", "Thanks for watching!"), when a
   * stretch holds nothing else: dropped instead of sent.
   */
  static boolean hallucination(String text) {
    String n = normalize(text);
    return n.isEmpty()
        || n.matches(
            "(thank you|thanks|thank you for watching|thanks for watching|you|bye|okay|"
                + "untertitel (im auftrag des zdf|der amara org community).*|vielen dank( furs zuschauen)?|"
                + "danke( schon)?|tschuss)( (thank you|thanks|you))*");
  }

  // ------------------------------------------------------------------ voice activity (unit-tested)

  /**
   * Splits a stream of 20 ms frames into stretches of speech. A frame counts as speech when its
   * level is clearly above the noise floor (which follows the quiet frames); a stretch ends after
   * pauseMs without speech, and keeps a little audio before and after it. Silence in between never
   * goes to the speech model, which otherwise invents "Thank you." for it.
   */
  static final class Segmenter {
    static final int FRAME_MS = 20, PAD_MS = 300, MIN_SPEECH_MS = 200;

    interface Listener {
      /** A stretch of speech ended: its 16 kHz 16-bit mono PCM. */
      void segment(byte[] pcm);
    }

    private final int pauseFrames, padFrames = PAD_MS / FRAME_MS, minSpeechFrames = MIN_SPEECH_MS / FRAME_MS;
    private final Listener listener;
    private final java.util.ArrayDeque<byte[]> before = new java.util.ArrayDeque<>();
    private java.io.ByteArrayOutputStream current;
    private int silentFrames, speechFrames, streak;
    private double floor = 300;
    private long quietMs;

    Segmenter(int pauseMs, Listener listener) {
      this.pauseFrames = Math.max(1, pauseMs / FRAME_MS);
      this.listener = listener;
    }

    /** Milliseconds since the last speech (or since the start). */
    long quietMs() {
      return quietMs;
    }

    boolean speaking() {
      return current != null;
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
      boolean loud = level > Math.max(600, floor * 3);
      if (!loud) floor = floor * 0.995 + level * 0.005; // The floor follows quiet frames only.
      // Two loud frames in a row start speech, so a click doesn't.
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
          quietMs = 0;
        }
        return;
      }
      current.write(frame, 0, frame.length);
      if (loud) {
        speechFrames++;
        silentFrames = 0;
        quietMs = 0;
      } else {
        silentFrames++;
        quietMs += FRAME_MS;
      }
      if (silentFrames >= pauseFrames) finish();
    }

    /** Ends the current stretch now (Send pressed, or the mode ends). */
    void flush() {
      if (current != null) finish();
    }

    private void finish() {
      byte[] all = current.toByteArray();
      int frameBytes = AudioFiles.RATE * 2 * FRAME_MS / 1000;
      // Keep only padFrames of the trailing silence.
      int keep = Math.max(0, all.length - Math.max(0, silentFrames - padFrames) * frameBytes);
      boolean real = speechFrames >= minSpeechFrames;
      current = null;
      silentFrames = 0;
      speechFrames = 0;
      if (real) listener.segment(java.util.Arrays.copyOf(all, keep));
    }
  }
}
