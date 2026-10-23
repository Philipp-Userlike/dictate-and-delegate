package app.driftdictation;

import static app.driftdictation.I18n.tr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One hands-free call, without Android: microphone frames go in, messages to the chief and replies
 * to read aloud come out. It's strictly turn-based, with an open floor: when nobody holds the turn,
 * whoever starts first gets it.
 *
 * <ul>
 *   <li>You take the floor by speaking (the segmenter's three layers decide what counts). You hold
 *       it while you talk, while your words are written down, and while you have unsent text,
 *       until the message goes (send word, Send now, long silence) or your turn comes to nothing.
 *   <li>A chief message arriving on an open floor is read aloud; the microphone is off meanwhile
 *       and a moment after, and nothing interrupts it.
 *   <li>A chief message arriving while you talk (or while what you said is written down) waits,
 *       and is read at your next pause, also when you have unsent words: your turn goes on after
 *       it. Sending never drops it. A reply you talk over goes back to waiting and plays again
 *       later, from the start of the sentence you stopped hearing it in.
 *   <li>Every decision it makes is a reason on screen ({@link #reasons}), in plain words, and in
 *       the log; a few moments get a quiet sound ({@link Cue}).
 * </ul>
 *
 * HandsFreeService wires it to the microphone, bb, ntfy and the voice.
 */
final class HandsFreeSession {
  enum State {
    LISTENING,
    READING,
    ENDED
  }

  /**
   * What the screen shows, and nothing else: you can talk, your message is on its way, the chief
   * has it, or a reply is read.
   */
  enum Phase {
    LISTENING,
    SENDING,
    WORKING,
    READING
  }

  /** Who holds the turn. */
  enum Floor {
    OPEN,
    YOU,
    CHIEF
  }

  /** What's happening right now, in more detail than Phase: the live line on screen. */
  enum Live {
    LISTENING,
    HEARING,
    WRITING,
    WAITING_SEND,
    SENDING,
    SENT,
    WAITING_CHIEF,
    REPLY_WAITING,
    REPLY_COMING,
    PLAYING,
    CHECKING,
    MUTED,
    ENDED
  }

  /** The moments that get a quiet sound. */
  enum Cue {
    HEARD,
    SENT,
    REPLY,
    FAILED
  }

  /** A decision and its reason, as shown on screen; times counts the same one in a row. */
  record Reason(long at, String text, int times) {}

  /**
   * A reply waiting while your turn goes on plays once you've been quiet this long (and nothing
   * of yours is being written down).
   */
  static final long REPLY_PAUSE_MS = 1500;

  /**
   * A reply whose voice is ready waits at most this long: then it plays, unless you're speaking
   * right then (speech the segmenter is in the middle of). Not for an open turn, a check of it,
   * or a sound that hasn't counted as speech: noise kept replies waiting until the call ended.
   */
  static final long REPLY_WAIT_MS = 12_000;

  /** How long the screen says Sent ✓ after a message went. */
  static final long SENT_SHOWN_MS = 3000;

  /** While a sound plays (and a moment after) the microphone can't start your speech with it. */
  static final int CUE_MS = 300;

  /** How many reasons the screen keeps. */
  static final int REASONS = 8;

  /** A message counts as being worked on until the chief's second reply, or this long. */
  static final long WORKING_MS = 10 * 60_000;

  /** After a reply is read the microphone stays off this long, so its last word isn't caught. */
  static final long TAIL_MS = 800;

  /**
   * The microphone is off only while a reply plays: audio has to start this soon after the floor
   * is taken, and end this long after its own length, or the reading is given up and listening
   * resumes. The audio is fetched before, while the floor is still open.
   */
  static final long START_MS = 6000, OVERRUN_MS = 3000;

  /**
   * Past its length a reply keeps playing as long as the voice says audio is coming out; only one
   * that stalls, or plays this long in all, is given up.
   */
  static final long READING_CAP_MS = 10 * 60_000;

  /**
   * Talking while a reply plays (barge-in): for ECHO_FRAMES after its audio starts the session
   * measures how loud the reply comes back through the microphone; then BARGE_FRAMES louder than
   * BARGE_FACTOR times that turn the reply down (it plays on), and what was said is written down:
   * only real words that aren't the reply's own (echo) stop it and start your turn. A sound alone
   * never stops a reply. What's written down ends at your pause, or after SNIPPET_MS.
   */
  static final int ECHO_FRAMES = 35, BARGE_FRAMES = 15, SNIPPET_MS = 8000;

  static final double BARGE_FACTOR = 2.5;

  /** Once you hold the floor, a stretch this short still counts ("Over." on its own). */
  static final int SHORT_MS = 200;

  interface Transcriber {
    /** The text of a WAV file; throws when the speech model fails. */
    String transcribe(byte[] wav) throws Exception;
  }

  /**
   * Is your turn done? Asked at each longer pause with everything you said in it so far (Jev).
   * Throws when it can't tell (failed, too slow).
   */
  interface Judge {
    Verdict judge(String said) throws Exception;
  }

  /**
   * Jev's answer: where you are (over, question, request, end, unfinished, more), how sure it is
   * that the turn is done and that you want to end the call, and what asking cost (null unknown).
   */
  record Verdict(String choice, double send, double end, java.math.BigDecimal cost) {}

  /** At these probabilities a turn is sent, or the call ended, without a word from you. */
  static final double SEND_AT = 0.7, END_AT = 0.8;

  interface Sender {
    /** Sends a message to the chief; returns its request ID. */
    String send(String said) throws Exception;
  }

  interface Speaker {
    /**
     * Gets a reply ready to play, such as fetching its audio (on a background thread; may be slow).
     * Never throws: what it returns may simply play with a fallback voice.
     */
    Object prepare(String request, String text);

    /** Plays a prepared reply, telling the playback when audio started and when it ended. */
    void play(String request, String text, Object prepared, Playback playback);

    /** Is audio of the reply still coming out (for the watchdog)? */
    default boolean playing() {
      return false;
    }

    /** Turns the reply down (you may be talking over it), or back up; it plays on either way. */
    default void duck(boolean down) {}

    /** How many characters of the reply's text have been played (heard) so far. */
    default int heardChars() {
      return 0;
    }

    void stop();
  }

  interface Playback {
    /** Audio is coming out; it will take about this long. */
    void started(long durationMs);

    /** It's over: played to the end, or failed or stopped (played false). */
    void ended(boolean played);
  }

  /** A chief message waiting to be read: being prepared until ready. */
  private static final class Held {
    final String request, text;
    volatile Object prepared;
    volatile boolean ready;
    /** Why it waited was shown (once per reply). */
    boolean told;
    /** The seconds its voice has been fetched for, as last shown (0: not shown). */
    long voiceShown;
    /** Tries to play it that failed before (its voice fetched afresh each time). */
    int failures;
    /** When it arrived and when its voice was ready, for the timings in the log. */
    final long arrived;
    volatile long readyAt;

    Held(String request, String text, long arrived) {
      this.request = request;
      this.text = text;
      this.arrived = arrived;
    }
  }

  interface Listener {
    /** Something changed: floor, state, your text so far. */
    void changed();
  }

  final List<String> sendWords, endWords;
  final int silenceSendMs;
  private final Transcriber transcriber;
  private final Sender sender;
  private final Speaker speaker;
  private final Listener listener;
  private final HandsFree.Segmenter segmenter;
  /**
   * Three queues, so nothing waits on what it doesn't need: work writes down what you say (in
   * order) and checks barge-ins; outbox delivers your messages to bb (in order), so writing down
   * your next words never waits for bb and a message never waits for transcription of words
   * that came after it; voices fetches the chief's audio.
   */
  private final ExecutorService work = Executors.newSingleThreadExecutor(),
      outbox = Executors.newSingleThreadExecutor(),
      voices = Executors.newSingleThreadExecutor(),
      speech = Executors.newCachedThreadPool();
  /**
   * Your turn: nothing is written down while you talk. Its audio is collected part by part (a
   * part ends at a longer pause); at each such pause the whole turn so far is written down in one
   * pass and the judge (Jev) is asked whether you're done. Null judge: the send and end words.
   */
  volatile Judge judge;
  /** The audio of this turn's parts, in order (under its lock), and what changes it counts. */
  private final List<byte[]> turnAudio = new ArrayList<>();
  private int turnVersion;
  /** The last check's text and the turn version it was for: Send right after reuses it. */
  private String checkedText = "";
  private int checkedVersion = -1;
  /** Words of your turn said over a reply (written down then): they come before its audio. */
  private volatile String turnPrefix = "";
  /** Writing down took this long: shown as slow. */
  static final long SLOW_WRITING_MS = 4000;

  /** Writing down a turn is given up after this long and tried once more. */
  static volatile long writeTimeoutMs = 6000;

  /** What a running check is doing: "writing" (the speech model) or "asking" (Jev); "" none. */
  private volatile String checkStep = "";
  /** A check (writing down, then Jev) started (for the live line's seconds), 0 when none runs. */
  private volatile long writingSince;
  /** Messages handed to the outbox and not delivered yet. */
  private final AtomicInteger outgoing = new AtomicInteger();
  /** Chief messages waiting to be read: at your next pause, or once the one playing ends. */
  private final ArrayDeque<Held> held = new ArrayDeque<>();
  /** Notes for your next message about replies you didn't hear (their reading failed). */
  private final List<String> unheard = new ArrayList<>();
  /** The latest decisions with their reasons, oldest first (under its own lock). */
  private final ArrayDeque<Reason> reasons = new ArrayDeque<>();
  /** What the call's end tells the chief: replies still waiting, or cut off by the end. */
  private volatile String endNote = "";
  private final List<String> requests = new ArrayList<>();
  /** Request → {replies so far, sent at}: for "Chief is working". */
  private final java.util.Map<String, long[]> working = new java.util.HashMap<>();
  /** Checks of your turn (writing down, Jev) queued or running. */
  private final AtomicInteger transcribing = new AtomicInteger();
  private volatile State state = State.LISTENING;
  private volatile String pending = "", status = "", error = "", reply = "";
  private volatile boolean sendAfterFlush, takenByYou;
  /**
   * Send was pressed and your message hasn't gone yet (what you said may still be written down):
   * the screen says Sending… at once, and pressing again does nothing.
   */
  private volatile boolean sendPressed;
  /** When your last message went: a press right after it belongs to it, not to what follows. */
  private volatile long sentAt = Long.MIN_VALUE / 2;
  /** A press this soon after a message went is taken as a repeated press for that message. */
  static final long REPRESS_MS = 1500;
  private volatile long mutedUntil, deliveredAt = Long.MIN_VALUE / 2;
  /** Until when a sound of ours may be in the microphone's frames. */
  private volatile long cueUntil;
  /** A sound was heard while the microphone was paused after a reply (said once per reply). */
  private volatile boolean tailHeard;
  private int sinceCheck;
  private volatile java.util.function.Consumer<String> events = e -> {};
  /** Plays a sound, true if it did; HandsFreeService's are quiet and short, and may be off. */
  volatile java.util.function.Predicate<Cue> cues = c -> false;
  private int reading, sent;
  private Held current;
  private boolean playing;
  private long readingDeadline, readingSince;
  private final int pauseFrames;
  private final double gate;
  // Barge-in, while a reply plays (under this's lock). Suspect: a sound over the reply is being
  // collected or checked, the reply turned down meanwhile; suspectAt is where it was then.
  private int echoFrames, bargeLoud, snippetQuiet, suspectAt;
  private double echoLevel;
  private boolean suspect, checking;
  private java.io.ByteArrayOutputStream snippet;
  private final ArrayDeque<byte[]> recent = new ArrayDeque<>();
  /** Your speech while a snippet is checked: the start of your turn if it was you. */
  private final List<byte[]> overflow = new ArrayList<>();
  /** The time; tests set their own. */
  java.util.function.LongSupplier clock = System::currentTimeMillis;

  HandsFreeSession(
      int pauseMs,
      int silenceSendMs,
      List<String> sendWords,
      List<String> endWords,
      Transcriber transcriber,
      Sender sender,
      Speaker speaker,
      Listener listener) {
    this(pauseMs, silenceSendMs, HandsFree.DEFAULT_TAKE_MS, HandsFree.DEFAULT_GATE, HandsFree.LEVEL, sendWords, endWords, transcriber, sender, speaker, listener);
  }

  HandsFreeSession(
      int pauseMs,
      int silenceSendMs,
      int takeMs,
      double gate,
      HandsFree.Detector detector,
      List<String> sendWords,
      List<String> endWords,
      Transcriber transcriber,
      Sender sender,
      Speaker speaker,
      Listener listener) {
    this.silenceSendMs = silenceSendMs;
    this.pauseFrames = Math.max(1, pauseMs / HandsFree.Segmenter.FRAME_MS);
    this.gate = gate;
    this.sendWords = sendWords;
    this.endWords = endWords;
    this.transcriber = transcriber;
    this.sender = sender;
    this.speaker = speaker;
    this.listener = listener;
    segmenter =
        new HandsFree.Segmenter(
            pauseMs,
            // Taking the floor needs takeMs of speech; once it's yours, short stretches count too.
            () -> floorIsYours() ? SHORT_MS : takeMs,
            gate,
            detector,
            new HandsFree.Segmenter.Listener() {
              public void segment(byte[] pcm) {
                addPart(pcm);
                // A longer pause: is the turn done? With Send pressed: it goes, no question.
                check(sendAfterFlush ? "button" : "pause");
              }

              public void started(double level, double background) {
                boolean taking = !takenByYou;
                takenByYou = true;
                if (taking) {
                  log(String.format(java.util.Locale.ROOT, "floor: you (level %.0f, background %.0f)", level, background));
                  cue(Cue.HEARD);
                }
                listener.changed();
              }

              public void noise(int speechMs, double level, double background) {
                log(String.format(java.util.Locale.ROOT, "noise ignored (%d ms, level %.0f, background %.0f)", speechMs, level, background));
                // Clicks are left out of the reasons; anything you might have meant is in.
                if (speechMs >= 100)
                  reason("Didn't count as speech: too short (%1$d ms, %2$d ms needed)", speechMs, floorIsYours() ? SHORT_MS : takeMs);
                // Send now during a sound too short to count: what was said before still goes.
                if (sendAfterFlush) check("button");
              }

              public void tooQuiet(int ms, double level, double background) {
                log(String.format(java.util.Locale.ROOT, "too quiet to count (%d ms, level %.0f, background %.0f, gate ×%.1f)", ms, level, background, gate));
                reason("Didn't count as speech: too quiet (level %1$.0f < %2$.1f× background %3$.0f)", level, gate, background);
              }
            });
  }

  State state() {
    return state;
  }

  /** What you said since the last message. */
  String pending() {
    return pending;
  }

  /** The reply being read aloud, "" when none is. */
  String reply() {
    return reply;
  }

  /** The last thing that happened. */
  String status() {
    return status;
  }

  /** The last error (a part not written down, a message not sent), "" when the last try worked. */
  String error() {
    return error;
  }

  int sent() {
    return sent;
  }

  boolean speaking() {
    return segmenter.speaking();
  }

  /** A reply's audio is coming out (also turned down for a barge-in check). */
  synchronized boolean audioPlaying() {
    return state == State.READING && playing;
  }

  /** Chief messages waiting to be read. */
  synchronized int held() {
    return held.size();
  }

  /** What's happening right now, for the live line on screen, the call bar and the notification. */
  Live live() {
    if (state == State.ENDED) return Live.ENDED;
    long now = clock.getAsLong();
    boolean voiceReady;
    synchronized (this) {
      if (state == State.READING) return !playing ? Live.REPLY_COMING : suspect || checking ? Live.CHECKING : Live.PLAYING;
      voiceReady = held.isEmpty() || held.peekFirst().ready;
    }
    if (now < mutedUntil) return Live.MUTED;
    if (segmenter.speaking()) return Live.HEARING;
    if (transcribing.get() > 0) return Live.WRITING;
    if (sendPressed || outgoing.get() > 0) return Live.SENDING;
    if (held() > 0) return voiceReady ? Live.REPLY_WAITING : Live.REPLY_COMING;
    if (hasTurn()) return Live.WAITING_SEND;
    if (now - deliveredAt < SENT_SHOWN_MS) return Live.SENT;
    synchronized (this) {
      for (long[] w : working.values()) if (w[0] < 2 && now - w[1] < WORKING_MS) return Live.WAITING_CHIEF;
    }
    return Live.LISTENING;
  }

  /** The latest decisions with their reasons, newest first. */
  List<Reason> reasons() {
    synchronized (reasons) {
      List<Reason> out = new ArrayList<>(reasons);
      java.util.Collections.reverse(out);
      return out;
    }
  }

  /**
   * A decision, with its reason in plain words: on screen (translated), and in the log (in
   * English). The same one again in a row counts up instead of filling the list.
   */
  void reason(String english, Object... args) {
    log("→ " + (args.length == 0 ? english : String.format(java.util.Locale.ROOT, english, args)));
    show(english, false, args);
  }

  /**
   * A reason that counts up while it lasts ("… (3 s)"): it takes the place of the last one when
   * that's the same reason, and is logged only the first time.
   */
  private void ongoing(String english, Object... args) {
    boolean first;
    synchronized (reasons) {
      first = !english.equals(lastReason);
    }
    if (first) log("→ " + String.format(java.util.Locale.ROOT, english, args));
    show(english, true, args);
  }

  /** The English of the last reason shown (under reasons' lock). */
  private String lastReason = "";

  private void show(String english, boolean inPlace, Object... args) {
    String text = tr(english, args);
    long now = clock.getAsLong();
    synchronized (reasons) {
      Reason last = reasons.peekLast();
      if (last != null && inPlace && english.equals(lastReason)) {
        reasons.removeLast();
        reasons.addLast(new Reason(now, text, 1));
      } else if (last != null && last.text().equals(text)) {
        reasons.removeLast();
        reasons.addLast(new Reason(now, text, last.times() + 1));
      } else reasons.addLast(new Reason(now, text, 1));
      lastReason = english;
      while (reasons.size() > REASONS) reasons.removeFirst();
    }
    listener.changed();
  }

  /**
   * A quiet sound; for CUE_MS the microphone's frames can't start your speech or a barge-in. Not
   * for HEARD: it plays while you're talking, and your speech goes on through it.
   */
  private void cue(Cue c) {
    try {
      if (cues.test(c) && c != Cue.HEARD) cueUntil = clock.getAsLong() + CUE_MS;
    } catch (RuntimeException ignored) {
      /* A sound is a help, not a must. */
    }
  }

  /** May a sound of ours be in the frame coming in now? */
  private boolean inCue() {
    return clock.getAsLong() < cueUntil;
  }

  /**
   * What the call's end tells the chief, after the end signal: the replies that never played
   * (still waiting), or the one cut off by the end. "" when every reply was heard.
   */
  String endNote() {
    return endNote;
  }

  /** Chief messages that couldn't be read, not yet noted in a message. */
  synchronized int unheard() {
    return unheard.size();
  }

  /** Is your turn going on: speaking, being written down, unsent text, or going out? */
  private boolean floorIsYours() {
    return takenByYou || segmenter.speaking() || transcribing.get() > 0 || hasTurn() || outgoing.get() > 0;
  }

  Floor floor() {
    if (state == State.READING) return Floor.CHIEF;
    return floorIsYours() ? Floor.YOU : Floor.OPEN;
  }

  Phase phase() {
    if (state == State.READING) return Phase.READING;
    if (outgoing.get() > 0 || sendPressed) return Phase.SENDING;
    if (floorIsYours()) return Phase.LISTENING;
    long now = clock.getAsLong();
    synchronized (this) {
      for (long[] w : working.values()) if (w[0] < 2 && now - w[1] < WORKING_MS) return Phase.WORKING;
    }
    return Phase.LISTENING;
  }

  /** Each thing that happens, for the hands-free log (what went wrong in a turn). */
  void onEvent(java.util.function.Consumer<String> listener) {
    events = listener;
  }

  private void log(String event) {
    events.accept(event);
  }

  /** Is this one of the requests sent in this call? */
  synchronized boolean ours(String request) {
    return requests.contains(request);
  }

  /**
   * One 20 ms frame from the microphone (on the recording thread). While a reply plays it's
   * checked for you talking over it (barge-in); for TAIL_MS after a reply ended frames are dropped,
   * so its last word doesn't start your turn.
   */
  void frame(byte[] frame) {
    boolean cue = inCue();
    if (state == State.READING) {
      bargeFrame(frame, cue);
      return;
    }
    if (state != State.LISTENING) return;
    long now = clock.getAsLong();
    if (now < mutedUntil) {
      if (!tailHeard && HandsFree.Segmenter.level(frame, frame.length) > Math.max(600, segmenter.background() * gate)) {
        tailHeard = true;
        reason("A sound right after the reply was ignored (mic paused)");
      }
      return;
    }
    // A sound of ours just played: it can't start a stretch of speech (one going on goes on).
    if (cue && !segmenter.candidate()) return;
    segmenter.frame(frame);
    // A waiting reply plays at your pause: checked a few times a second.
    if (++sinceCheck >= 10) {
      sinceCheck = 0;
      if (held() > 0) readNext();
    }
  }

  /**
   * Barge-in: is that you over the reply? A sound louder than its echo turns it down (it plays
   * on), and what's said over it is written down: only real words stop it (checkBargeIn).
   */
  private void bargeFrame(byte[] frame, boolean cue) {
    double level = HandsFree.Segmenter.level(frame, frame.length);
    byte[] pcm = null;
    String suspected = null, replyText = null;
    int token;
    synchronized (this) {
      if (state != State.READING || !playing) return; // Not before the reply's audio started.
      if (!suspect && cue) return; // A sound of ours, not you.
      token = reading;
      if (checking) {
        if (overflow.size() < 30_000 / HandsFree.Segmenter.FRAME_MS) overflow.add(frame.clone());
        return;
      }
      recent.addLast(frame.clone());
      while (recent.size() > HandsFree.Segmenter.PAD_MS / HandsFree.Segmenter.FRAME_MS) recent.removeFirst();
      double floor = Math.max(segmenter.background() * gate, 600);
      double threshold = Math.max(echoLevel * BARGE_FACTOR, floor);
      if (!suspect) {
        if (echoFrames < ECHO_FRAMES) { // The reply's own level coming back.
          echoFrames++;
          echoLevel = Math.max(echoLevel, level);
          return;
        }
        if (level > threshold) bargeLoud++;
        else {
          bargeLoud = Math.max(0, bargeLoud - 2);
          echoLevel = Math.max(echoLevel * 0.998, level); // Follows the reply's level.
        }
        if (bargeLoud < BARGE_FRAMES) return;
        suspect = true;
        bargeLoud = 0;
        snippetQuiet = 0;
        snippet = new java.io.ByteArrayOutputStream();
        for (byte[] b : recent) snippet.write(b, 0, b.length);
        recent.clear();
        suspected =
            String.format(
                java.util.Locale.ROOT, "barge-in suspected (level %.0f, echo %.0f, threshold %.0f): reply turned down, plays on", level, echoLevel, threshold);
      } else if (snippet != null) { // Null once the check started (until the reading ends).
        snippet.write(frame, 0, frame.length);
        // The reply goes on, turned down: its echo stays under the threshold, your voice doesn't.
        snippetQuiet = level > threshold ? 0 : snippetQuiet + 1;
        if (snippetQuiet >= pauseFrames || snippet.size() > SNIPPET_MS / 1000 * AudioFiles.BYTES_PER_SECOND) {
          pcm = snippet.toByteArray();
          snippet = null;
          checking = true;
          replyText = current.text;
        }
      }
    }
    if (suspected != null) {
      speaker.duck(true);
      int at = speaker.heardChars();
      synchronized (this) {
        suspectAt = at;
      }
      log(suspected + " at character " + at);
      reason("Reply turned down: is that you talking over it?");
    }
    if (pcm != null) {
      byte[] said = pcm;
      String text = replyText;
      execute(() -> checkBargeIn(token, said, text), null);
    }
  }

  /**
   * What was said over the reply, written down: no words, or the reply's own (echo), and it plays
   * on at full volume; real words stop it and start your turn. If the reply ended meanwhile, those
   * words still start your turn.
   */
  private void checkBargeIn(int token, byte[] pcm, String replyText) {
    String text;
    try {
      text = transcriber.transcribe(AudioFiles.wav(pcm)).trim();
    } catch (Exception ex) {
      text = null;
      log("barge-in not written down: " + ex.getMessage());
      reason("Couldn't check what was said over the reply (%1$s): it plays on", ex.getMessage());
    }
    int heard = speaker.heardChars();
    String request = null;
    int at = 0;
    boolean live, echo;
    List<byte[]> more = List.of();
    synchronized (this) {
      if (state == State.ENDED) return;
      live = token == reading && state == State.READING && current != null;
      // The echo is looked for around where the reply is now (all of it, if it ended).
      echo = text == null || text.isEmpty() || echoOf(text, replyText, live ? heard : replyText.length());
      if (live) {
        request = current.request;
        at = suspectAt;
        more = new ArrayList<>(overflow);
        overflow.clear();
        checking = false;
        if (echo) {
          suspect = false;
          readingDeadline = Math.max(readingDeadline, clock.getAsLong() + OVERRUN_MS);
        }
      }
    }
    if (echo) {
      log("barge-in was " + (text == null || text.isEmpty() ? "no words" : "the reply's echo (\"" + text + "\")") + (live ? ": plays on" : ""));
      if (!live) return;
      if (text != null && !text.isEmpty()) reason("Reply plays on: that was its own voice (echo)");
      else if (text != null) reason("Reply plays on: no words were said over it");
      speaker.duck(false);
      listener.changed();
      return;
    }
    if (!live) { // It ended while this was written down: your words start your turn all the same.
      log("words over the reply as it ended (\"" + text + "\"): your turn starts with them");
      takenByYou = true;
      turnPrefix = (turnPrefix + " " + text).trim();
      pending = (pending + " " + text).trim();
      listener.changed();
      if (!segmenter.candidate()) check("pause");
      return;
    }
    String rest = restFrom(replyText, at);
    log("interrupted by you (\"" + text + "\"): the reply waits, from \"" + firstWords(rest, 8) + "\"");
    endReading(token, null, false);
    speaker.stop();
    takenByYou = true;
    // It plays again later, from the start of the sentence you stopped hearing it in.
    Held again = new Held(request, rest, clock.getAsLong());
    synchronized (this) {
      held.addFirst(again);
    }
    prepare(again);
    reason("Reply stopped: you talked over it. It plays again from \"%1$s\" at your next pause", firstWords(rest, 6));
    // Your words over the reply start your turn (written down already: the snippet has the reply
    // in it too, so it isn't written down again); then what you went on saying meanwhile.
    turnPrefix = (turnPrefix + " " + text).trim();
    pending = (pending + " " + text).trim();
    listener.changed();
    for (byte[] f : more) segmenter.frame(f);
    if (!segmenter.candidate()) check("pause"); // You stopped already: is that all?
    readNext();
  }

  /**
   * Where a reply you talked over plays again: the start of the sentence that was playing at
   * heardChars (the whole reply if you hardly heard any of it).
   */
  static String restFrom(String reply, int heardChars) {
    int at = Math.max(0, Math.min(heardChars, reply.length()));
    int start = 0;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("[.!?…]+[\"”’)]*\\s+").matcher(reply);
    while (m.find() && m.end() <= at) start = m.end();
    return reply.substring(start).trim();
  }

  /** The first n words of a text, with … if there's more. */
  static String firstWords(String text, int n) {
    String[] w = text.trim().split("\\s+");
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < Math.min(n, w.length); i++) out.append(i == 0 ? "" : " ").append(w[i]);
    return out + (w.length > n ? "…" : "");
  }

  /** Normalized words of a text. */
  private static List<String> words(String text) {
    List<String> out = new ArrayList<>();
    for (String w : HandsFree.normalize(text).split(" ")) if (!w.isEmpty()) out.add(w);
    return out;
  }

  /**
   * Is what was said the reply's own words around where it is (the voice coming back through
   * the microphone)? Most of its words are among the reply's last 30 words before that point
   * and the next 8.
   */
  static boolean echoOf(String said, String reply, int heardChars) {
    List<String> s = words(said);
    if (s.isEmpty()) return true;
    List<String> r = words(reply);
    int at = words(reply.substring(0, Math.max(0, Math.min(heardChars, reply.length())))).size();
    java.util.Set<String> near = new java.util.HashSet<>(r.subList(Math.max(0, at - 30), Math.min(r.size(), at + 8)));
    int hits = 0;
    for (String w : s) if (near.contains(w)) hits++;
    return hits >= Math.ceil(s.size() * 0.6);
  }

  /** The note for a reply you interrupted: its last words you heard (or that you heard none). */
  static String heardUpTo(String reply, int heardChars) {
    String part = reply.substring(0, Math.max(0, Math.min(heardChars, reply.length()))).trim();
    // A word cut in the middle wasn't heard.
    if (heardChars < reply.length() && heardChars > 0 && Character.isLetterOrDigit(reply.charAt(heardChars))
        && Character.isLetterOrDigit(reply.charAt(heardChars - 1)))
      part = part.replaceAll("\\S+$", "").trim();
    String[] w = part.isEmpty() ? new String[0] : part.split("\\s+");
    if (w.length < 2) return missed(reply);
    StringBuilder last = new StringBuilder();
    for (int i = Math.max(0, w.length - 8); i < w.length; i++) last.append(last.length() == 0 ? "" : " ").append(w[i]);
    return "[heard up to: \"" + (w.length > 8 ? "…" : "") + last + "\"]";
  }

  /** Once a second: a long silence sends anyway, a held reply may start, a hung voice is let go. */
  void tick() {
    if (state == State.ENDED) return;
    int token;
    boolean late;
    long now = clock.getAsLong();
    synchronized (this) {
      token = reading;
      late = state == State.READING && now > readingDeadline;
      // Turned down for a barge-in check: it's waiting for you, not hung.
      if (late && (suspect || checking) && now - readingSince < READING_CAP_MS) {
        readingDeadline = now + OVERRUN_MS;
        late = false;
      }
      // Longer than it said, but audio still coming out: let it finish.
      if (late && playing && now - readingSince < READING_CAP_MS && speaker.playing()) {
        readingDeadline = now + OVERRUN_MS;
        late = false;
      }
    }
    if (late) {
      boolean started;
      synchronized (this) {
        started = playing;
      }
      log(started ? "the reply played too long: listening again" : "the reply's audio didn't start: listening again");
      speaker.stop();
      finishReading(token, false, started ? tr("it played far too long") : tr("its audio didn't start"));
      return;
    }
    if (state == State.LISTENING && hasTurn() && !segmenter.candidate() && transcribing.get() == 0
        && segmenter.quietMs() >= silenceSendMs)
      check("silence");
    readNext();
  }

  private void execute(Runnable r, Runnable rejected) {
    try {
      work.execute(r);
    } catch (java.util.concurrent.RejectedExecutionException ex) {
      if (rejected != null) rejected.run(); // Ended.
    }
  }

  /**
   * What was said before hands-free started (the dictation it was switched to from): written down
   * as the start of your first turn, which it holds the floor for.
   */
  void seed(byte[] pcm) {
    if (pcm.length == 0) return;
    log(String.format(java.util.Locale.ROOT, "started from a dictation (%.1f s)", pcm.length / (double) AudioFiles.BYTES_PER_SECOND));
    takenByYou = true;
    addPart(pcm);
    check("seed"); // Shown; you go on talking, so not judged yet.
  }

  /**
   * Send now (the panel's, the screen's, the notification's or the headset's button). It shows at
   * once (Sending…), since your message may wait for what you said last to be written down; a
   * second press meanwhile, or right after it went, does nothing (it would send the next words
   * you start on their own). Every press is logged, also the ones that do nothing.
   */
  void sendNow() {
    if (state == State.ENDED) return;
    if (sendPressed) {
      log("send pressed again: already sending");
      reason("Send ignored: already sending");
      return;
    }
    long since = clock.getAsLong() - sentAt;
    if (since < REPRESS_MS) {
      log("send pressed right after your message went: taken as the same press");
      reason("Send ignored: just sent (%1$.1f s ago)", since / 1000.0);
      return;
    }
    // Sounds that didn't count as speech just before: Send means they were meant, so they're
    // written down and sent.
    byte[] noise = state == State.LISTENING && !hasTurn() && transcribing.get() == 0 && !segmenter.candidate() ? segmenter.takeNoise() : null;
    if (noise != null) {
      sendPressed = true;
      takenByYou = true;
      log(String.format(java.util.Locale.ROOT, "send pressed: writing down the last %.1f s of sounds that didn't count as speech", noise.length / (double) AudioFiles.BYTES_PER_SECOND));
      reason("Send: writing down what didn't count as speech, then it goes");
      addPart(noise);
      check("button");
      return;
    }
    if (!hasTurn() && transcribing.get() == 0 && !segmenter.candidate()) {
      if (state == State.READING) {
        log("send pressed while a reply is read: nothing of yours to send (talk over the reply to interrupt it)");
        reason("Send ignored: a reply is playing and you have nothing unsent");
      } else {
        log("send pressed: nothing said yet");
        reason("Send ignored: nothing heard yet");
      }
      return;
    }
    sendPressed = true;
    listener.changed();
    if (segmenter.candidate()) {
      log("send pressed: ending what you're saying");
      reason("Send: ending what you're saying, then it goes");
      sendAfterFlush = true;
      segmenter.flush(true); // Written down even if it didn't count as speech yet.
    } else {
      // Your unsent words go also while a reply plays: sending doesn't stop it. Written down
      // first (after a check that's running), never judged: Send always sends.
      log("send pressed: writing down your turn, then it goes" + (state == State.READING ? " (a reply plays on)" : ""));
      reason("Send: writing down your turn, then it goes");
      check("button");
    }
  }

  /** Ends the call. What you said but didn't send yet isn't sent. */
  void end() {
    if (state == State.ENDED) return;
    // What you're saying right now is part of your last words.
    if (state == State.LISTENING) segmenter.flush(true);
    int at = state == State.READING ? speaker.heardChars() : 0;
    StringBuilder note = new StringBuilder();
    int waiting;
    synchronized (this) {
      if (state == State.ENDED) return; // Ended once only (End and an end phrase at once).
      // The chief hears once, with the call's end, which replies never reached you.
      if (state == State.READING && current != null) note.append("\n\n").append(heardUpTo(current.text, at));
      for (Held h : held) note.append("\n\n").append(missed(h.text));
      waiting = held.size();
      held.clear();
      state = State.ENDED;
    }
    endNote = note.toString();
    if (waiting > 0) log("ended with " + waiting + " reply(ies) still waiting: the chief is told");
    sendUnsent();
    log("ended");
    speaker.stop();
    status = "ended";
    work.shutdown();
    outbox.shutdown(); // Messages on their way still go.
    voices.shutdownNow();
    speech.shutdown();
    listener.changed();
  }

  /**
   * A spoken message from the chief: its audio is fetched right away, while the floor stays open;
   * once ready it's read on an open floor, else held.
   */
  void reply(String request, String text) {
    reply(request, text, 0);
  }

  /** As above; published is when ntfy got it (unix ms, 0 if unknown), for the log's timings. */
  void reply(String request, String text, long published) {
    if (state == State.ENDED) return;
    Held h = new Held(request, text, clock.getAsLong());
    long since;
    synchronized (this) {
      long[] w = working.get(request);
      if (w != null) w[0]++;
      since = w == null ? -1 : clock.getAsLong() - w[1];
      held.addLast(h);
    }
    // ntfy's time is in whole seconds (and the server's clock): about how long delivery took.
    long ntfy = published <= 0 ? -1 : Math.max(0, System.currentTimeMillis() - published);
    log("chief message " + request + " (" + text.length() + " characters" + (since < 0 ? "" : ", " + since + " ms after your message went")
        + (ntfy < 0 ? "" : ", ntfy delivered it ~" + ntfy / 1000 + " s after it was posted") + ")");
    prepare(h);
    readNext(); // Says why it waits, if it does.
    listener.changed();
  }

  /** Fetches a reply's audio (on the voices queue); once ready it plays when it may. */
  private void prepare(Held h) {
    try {
      voices.execute(
          () -> {
            long start = clock.getAsLong();
            try {
              h.prepared = speaker.prepare(h.request, h.text);
              log("voice ready for " + h.request + " in " + (clock.getAsLong() - start) + " ms");
            } catch (RuntimeException ex) {
              log("voice not ready for " + h.request + ": " + ex.getMessage());
            }
            h.readyAt = clock.getAsLong();
            h.ready = true;
            readNext();
          });
    } catch (java.util.concurrent.RejectedExecutionException ended) {
      /* The call ended. */
    }
  }

  /**
   * The next waiting reply plays, once its audio is ready, unless you're talking or what you said
   * is being written down; while your turn goes on (unsent words) it waits for a pause of
   * REPLY_PAUSE_MS. Once ready it waits REPLY_WAIT_MS at most: then only speech going on holds it.
   * Why it waits is shown once per reply, and when it plays after waiting; while its voice is
   * fetched the seconds count up on screen.
   */
  private void readNext() {
    Held next;
    int token;
    String waits, plays = null;
    long now = clock.getAsLong(), fetching = 0;
    synchronized (this) {
      if (state != State.LISTENING || held.isEmpty()) return;
      Held first = held.peekFirst();
      waits = holds(first, now);
      if ("voice".equals(waits)) {
        // Shown from a second on (a voice is usually ready by then), and counted up each second.
        fetching = (now - first.arrived) / 1000;
        if (fetching < 1 || fetching == first.voiceShown) return;
        first.voiceShown = fetching;
      } else if (waits != null) {
        if (first.told) return;
        first.told = true;
      } else if (first.told) {
        boolean turn = takenByYou || hasTurn();
        plays = now - first.readyAt >= REPLY_WAIT_MS && wouldWait(turn) ? "capped" : turn ? "pause" : "open";
      }
    }
    if (waits != null) {
      switch (waits) {
        case "voice" -> {
          ongoing("Reply waits: its voice is being fetched (%1$d s)", fetching);
          return;
        }
        case "talking" -> reason("Reply waits: you're talking");
        case "writing" -> reason("Reply waits: checking whether you're done");
        default -> reason("Reply waits for your pause (%1$.1f s quiet)", REPLY_PAUSE_MS / 1000.0);
      }
      readNext(); // It may play right away after all (it was told, so this doesn't repeat).
      return;
    }
    if (plays != null) {
      switch (plays) {
        case "capped" -> reason("Reply plays: waited %1$d s", REPLY_WAIT_MS / 1000);
        case "pause" -> reason("Reply plays: you paused %1$.1f s; your turn goes on after it", REPLY_PAUSE_MS / 1000.0);
        default -> reason("Reply plays: you stopped talking");
      }
    }
    // Played after waiting its most: a sound that hasn't counted as speech yet ends as noise (if
    // it was you, talking on over the reply interrupts it).
    if (!segmenter.speaking()) segmenter.flush(false);
    synchronized (this) {
      if (state != State.LISTENING || held.isEmpty() || holds(held.peekFirst(), clock.getAsLong()) != null) return;
      next = held.removeFirst();
      state = State.READING;
      reply = next.text;
      current = next;
      playing = false;
      suspect = checking = false;
      echoFrames = bargeLoud = 0;
      echoLevel = 0;
      snippet = null;
      recent.clear();
      overflow.clear();
      token = ++reading;
      readingSince = clock.getAsLong();
      readingDeadline = readingSince + START_MS;
    }
    status = "reading";
    log("floor: chief, reading " + next.request);
    cue(Cue.REPLY);
    listener.changed();
    try {
      speaker.play(
          next.request,
          next.text,
          next.prepared,
          new Playback() {
            public void started(long durationMs) {
              synchronized (HandsFreeSession.this) {
                if (token != reading || state != State.READING) return;
                playing = true;
                readingDeadline = clock.getAsLong() + durationMs + OVERRUN_MS;
              }
              long now = clock.getAsLong();
              log(String.format(java.util.Locale.ROOT, "audio started (%.1f s) after %d ms", durationMs / 1000.0, now - readingSince));
              // Where the time went between the reply arriving and you hearing it.
              log(String.format(
                  java.util.Locale.ROOT,
                  "timing %s: voice %d ms, then waiting %d ms, then starting %d ms (%.1f s from arrival)",
                  next.request, next.readyAt - next.arrived, readingSince - next.readyAt, now - readingSince, (now - next.arrived) / 1000.0));
            }

            public void ended(boolean played) {
              finishReading(token, played, tr("the voice stopped"));
            }
          });
    } catch (RuntimeException ex) {
      log("the voice failed: " + ex.getMessage());
      finishReading(token, false, tr("the voice failed (%1$s)", ex.getMessage()));
    }
  }

  /**
   * Why the first waiting reply can't play now: "talking", "writing", "pause" or "voice" (its
   * audio isn't ready), null when it can. Having waited REPLY_WAIT_MS once ready, only speech
   * going on holds it.
   */
  private String holds(Held first, long now) {
    if (segmenter.speaking()) return "talking";
    boolean capped = first.ready && now - first.readyAt >= REPLY_WAIT_MS;
    if (!capped && wouldWait(takenByYou || hasTurn())) {
      if (segmenter.candidate()) return "talking";
      return transcribing.get() > 0 ? "writing" : "pause";
    }
    return first.ready ? null : "voice";
  }

  /** A sound that may become speech, a check of your turn, or your turn without its pause yet. */
  private boolean wouldWait(boolean turn) {
    return segmenter.candidate() || transcribing.get() > 0 || turn && segmenter.quietMs() < REPLY_PAUSE_MS;
  }

  /**
   * Reading number token ended (finished or given up): the floor is open again. A reply that didn't
   * play is noted in your next message as not heard.
   */
  private void finishReading(int token, boolean played, String why) {
    boolean heard;
    Held failed;
    synchronized (this) {
      if (token != reading || state != State.READING) return;
      heard = played && playing;
      failed = heard ? null : current;
    }
    String note = null;
    Held again = null;
    if (heard) log("reply played to the end");
    else if (failed != null && failed.failures < RETRIES) {
      // Never given up at once: its voice is fetched again (the voice model, else Android's).
      again = new Held(failed.request, failed.text, clock.getAsLong());
      again.failures = failed.failures + 1;
      again.told = true;
      log("reply not played (" + why + "): trying again (" + again.failures + " of " + RETRIES + ")");
      reason("Reply couldn't be played (%1$s): trying again", why);
    } else {
      note = failed == null ? null : missed(failed.text);
      log("reply not heard: noted in your next message");
      cue(Cue.FAILED);
      reason("Reply couldn't be played: %1$s. Your next message tells the chief", why);
    }
    if (!endReading(token, note, true)) return;
    if (again != null) {
      synchronized (this) {
        held.addFirst(again);
      }
      prepare(again);
    }
    readNext();
  }

  /** A reply that couldn't be played is tried once more before your message notes it. */
  static final int RETRIES = 1;

  /**
   * Ends reading number token: note (if any) goes with your next message; tail mutes the
   * microphone a moment (not when you interrupted: then you're talking). False if it had ended.
   */
  private boolean endReading(int token, String note, boolean tail) {
    byte[] said = null;
    String replyText;
    List<byte[]> more = List.of();
    synchronized (this) {
      if (token != reading || state != State.READING) return false;
      replyText = current == null ? "" : current.text;
      if (note != null) unheard.add(note);
      // It ended while a sound over it was collected or checked: that may be you, so it's
      // checked all the same, the microphone stays on, and what came meanwhile is yours.
      if (suspect) tail = false;
      if (snippet != null && snippet.size() > 0) said = snippet.toByteArray();
      if (checking) more = new ArrayList<>(overflow);
      overflow.clear();
      current = null;
      suspect = checking = false;
      snippet = null;
      mutedUntil = tail ? clock.getAsLong() + TAIL_MS : 0;
      tailHeard = false;
      segmenter.reset();
      reply = "";
      state = State.LISTENING;
    }
    status = "listening";
    log("floor: " + (tail ? "open" : "you"));
    if (tail) reason("Mic paused %1$.1f s after the reply (echo)", TAIL_MS / 1000.0);
    for (byte[] f : more) segmenter.frame(f);
    if (said != null) {
      byte[] pcm = said;
      String text = replyText;
      execute(() -> checkBargeIn(token, pcm, text), null);
    }
    listener.changed();
    return true;
  }

  /**
   * The call ended with words of yours not sent yet: they go now (written down first, if they
   * weren't), on the outbox, so before the chief hears the call ended.
   */
  private void sendUnsent() {
    List<byte[]> audio;
    String prefix, cached;
    synchronized (turnAudio) {
      if (turnAudio.isEmpty() && turnPrefix.isEmpty()) return;
      audio = new ArrayList<>(turnAudio);
      prefix = turnPrefix;
      cached = checkedVersion == turnVersion ? checkedText : null;
      turnAudio.clear();
      turnPrefix = "";
      turnVersion++;
    }
    log("ended with words not sent yet (" + audio.size() + " part(s)): they're sent before the call's end");
    outgoing.incrementAndGet();
    long queued = clock.getAsLong();
    Runnable last =
        () -> {
          String text = cached;
          if (text == null) {
            text = prefix;
            if (!audio.isEmpty())
              try {
                text = (prefix + " " + writeDown(joined(audio), "end")).trim();
              } catch (Exception ex) {
                log("your last words couldn't be written down (" + ex.getMessage() + "): not sent");
                outgoing.decrementAndGet();
                return;
              }
          }
          if (text.isEmpty()) {
            log("your last words had no words in them: nothing sent");
            outgoing.decrementAndGet();
            return;
          }
          deliver(text, text, "end of the call", 0, queued);
        };
    try {
      outbox.execute(last);
    } catch (java.util.concurrent.RejectedExecutionException ended) {
      new Thread(last, "last-words").start();
    }
  }

  /** Is there a turn of yours not sent yet (audio or words)? */
  private boolean hasTurn() {
    synchronized (turnAudio) {
      return !turnAudio.isEmpty() || !turnPrefix.isEmpty();
    }
  }

  /** A part of your turn: its audio, written down at the next check. */
  private void addPart(byte[] pcm) {
    synchronized (turnAudio) {
      turnAudio.add(pcm);
      turnVersion++;
    }
    takenByYou = true;
  }

  /**
   * Checks your turn, on the work queue: why is "pause" (a longer pause: written down, then Jev
   * asked whether you're done), "button" or "silence" (written down and sent, no question) or
   * "seed" (written down to show it).
   */
  private void check(String why) {
    transcribing.incrementAndGet();
    execute(() -> checkNow(why), () -> transcribing.decrementAndGet());
  }

  /** What a running check is doing: "writing", "asking" or "". */
  String checkStep() {
    return transcribing.get() == 0 ? "" : checkStep;
  }

  /**
   * Writes down audio with the speech model, at most writeTimeoutMs a try, twice: a request that
   * hangs is given up (and logged) rather than waited for.
   */
  private String writeDown(byte[] pcm, String why) throws Exception {
    byte[] wav = AudioFiles.wav(pcm);
    Exception last = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
      java.util.concurrent.Future<String> f;
      try {
        f = speech.submit(() -> transcriber.transcribe(wav));
      } catch (java.util.concurrent.RejectedExecutionException ended) {
        return transcriber.transcribe(wav).trim(); // The call ended: your last words, here.
      }
      try {
        return f.get(writeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).trim();
      } catch (java.util.concurrent.TimeoutException slow) {
        f.cancel(true);
        log(String.format(java.util.Locale.ROOT, "writing down (%s) took over %.0f s: %s", why, writeTimeoutMs / 1000.0, attempt == 1 ? "trying once more" : "given up"));
        if (attempt == 1) reason("Writing down took over %1$.0f s: trying once more", writeTimeoutMs / 1000.0);
        last = new java.io.IOException(tr("the speech model took over %1$.0f s", writeTimeoutMs / 1000.0));
      } catch (java.util.concurrent.ExecutionException ex) {
        throw ex.getCause() instanceof Exception e ? e : ex;
      }
    }
    throw last;
  }

  private void checkNow(String why) {
    boolean button = why.equals("button");
    try {
      if (state == State.ENDED) return;
      List<byte[]> audio;
      String prefix;
      int version;
      synchronized (turnAudio) {
        audio = new ArrayList<>(turnAudio);
        prefix = turnPrefix;
        version = turnVersion;
      }
      if (audio.isEmpty() && prefix.isEmpty()) {
        if (button) {
          sendAfterFlush = false;
          sendPressed = false;
          if (clock.getAsLong() - sentAt < 10_000) {
            log("send: your message had already gone");
            reason("Send: your message had already gone");
          } else reason("Nothing to send: no words were written down");
          listener.changed();
        }
        return;
      }
      long start = clock.getAsLong();
      writingSince = start;
      listener.changed();
      String text = prefix;
      int bytes = 0;
      for (byte[] p : audio) bytes += p.length;
      double seconds = bytes / (double) AudioFiles.BYTES_PER_SECOND;
      String cached;
      synchronized (turnAudio) {
        cached = checkedVersion == version ? checkedText : null;
      }
      if (cached != null) text = cached; // Written down already, unchanged since: no second pass.
      else if (!audio.isEmpty()) {
        try {
          checkStep = "writing";
          String t = writeDown(joined(audio), why);
          text = (prefix + " " + t).trim();
        } catch (Exception ex) {
          status = "transcription failed: " + ex.getMessage();
          error = "Couldn't write down what you said: " + ex.getMessage();
          log(String.format(java.util.Locale.ROOT, "check (%s): %.1f s, transcription failed after %d ms: %s", why, seconds, clock.getAsLong() - start, ex.getMessage()));
          cue(Cue.FAILED);
          reason("Couldn't write down %1$.1f s of what you said: %2$s", seconds, ex.getMessage());
          // Kept: the next pause or Send tries again with it.
          sendAfterFlush = false;
          sendPressed = false;
          listener.changed();
          return;
        }
      }
      long written = clock.getAsLong() - start;
      if (state == State.ENDED) return; // The call's end sends it (end()).
      synchronized (turnAudio) {
        checkedText = text;
        checkedVersion = version;
      }
      error = "";
      pending = text;
      listener.changed();
      log(String.format(java.util.Locale.ROOT, "check (%s): %d part(s), %.1f s, %s in %d ms: \"%s\"", why, audio.size(), seconds, cached != null ? "written down already" : "written down", written, text));
      if (written >= SLOW_WRITING_MS) reason("Writing down took %1$.1f s (the speech model was slow)", written / 1000.0);
      if (why.equals("seed")) return;
      if (text.isEmpty()) {
        if (stale(version) && !button) return;
        reason("The speech model heard no words: your turn ends");
        giveUpTurn("nothing was said");
        return;
      }
      if (!why.equals("pause")) {
        send(text, why, audio.size(), prefix);
        return;
      }
      if (stale(version)) {
        log("you went on talking: checked again at your next pause");
        return;
      }
      decide(text, audio.size(), prefix, version);
    } finally {
      writingSince = 0;
      checkStep = "";
      transcribing.decrementAndGet();
      listener.changed();
      readNext();
    }
  }

  /** Did the turn change (a new part) or are you talking again, since the check began? */
  private boolean stale(int version) {
    synchronized (turnAudio) {
      if (turnVersion != version) return true;
    }
    return segmenter.candidate();
  }

  /** The parts' audio in one, 0.3 s of silence between them. */
  private static byte[] joined(List<byte[]> parts) {
    java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
    byte[] gap = new byte[AudioFiles.BYTES_PER_SECOND * 3 / 10 & ~1];
    for (byte[] p : parts) {
      if (all.size() > 0) all.write(gap, 0, gap.length);
      all.write(p, 0, p.length);
    }
    return all.toByteArray();
  }

  /**
   * At a longer pause: asks the judge whether your turn is done, and acts: sends it, ends the
   * call, or listens on. Without a judge, or when it fails, the send and end words decide.
   */
  private void decide(String text, int parts, String prefix, int version) {
    Verdict v = null;
    long start = clock.getAsLong();
    String failed = null;
    checkStep = "asking";
    listener.changed();
    if (judge != null) {
      try {
        v = judge.judge(text);
      } catch (Exception ex) {
        failed = String.valueOf(ex.getMessage());
      }
    }
    long ms = clock.getAsLong() - start;
    if (v != null)
      log(String.format(
          java.util.Locale.ROOT,
          "Jev in %d ms%s: %s (done %.2f, end the call %.2f)",
          ms,
          v.cost() == null ? "" : ", $" + v.cost().stripTrailingZeros().toPlainString(),
          v.choice(),
          v.send(),
          v.end()));
    else if (failed != null) log("Jev failed after " + ms + " ms (" + failed + "): the send and end words decide");
    if (state == State.ENDED) return; // The call's end sent it.
    if (stale(version)) {
      log("you went on talking: checked again at your next pause");
      return;
    }
    String ended = HandsFree.endsWith(text, endWords), over = HandsFree.endsWith(text, sendWords);
    boolean end = v != null ? v.end() >= END_AT : ended != null;
    boolean done = v != null ? v.send() >= SEND_AT : over != null;
    if (failed != null) reason("Couldn't ask whether you're done (%1$s): say %2$s or press Send", failed, sendWords.isEmpty() ? "Send" : "\"" + sendWords.get(0) + "\"");
    if (end) {
      log("end of the call");
      reason("Ending the call: you said goodbye");
      String rest = ended != null ? ended : text;
      if (!rest.isEmpty()) send(rest, "end phrase", parts, prefix);
      end();
      return;
    }
    int pct = v == null ? 0 : (int) Math.round(100 * v.send());
    if (done) {
      String rest = over != null ? over : text;
      if (rest.isEmpty() && clock.getAsLong() - sentAt < 10_000) { // "Over" just after Send.
        log("the send word came after your message went: nothing more to send");
        reason("Send word heard: your message had already gone");
        endTurn();
        return;
      }
      if (rest.isEmpty()) { // Only "over".
        reason("Only the send word was heard: nothing to send");
        giveUpTurn("only the send word");
        return;
      }
      if (v == null) reason("Sent: you said %1$s", "\"" + sendWords.get(0) + "\"");
      else
        switch (v.choice()) {
          case "question" -> reason("Sent: you asked your question (%1$d%%)", pct);
          case "request" -> reason("Sent: you finished what you were saying (%1$d%%)", pct);
          default -> reason("Sent: you said you're done (%1$d%%)", pct);
        }
      send(rest, "jev", parts, prefix);
      return;
    }
    if (v == null) {
      if (failed == null) reason("Not sent: no %1$s yet", sendWords.isEmpty() ? "send word" : "\"" + sendWords.get(0) + "\"");
    } else
      switch (v.choice()) {
        case "unfinished" -> reason("Not sent: you seem mid-sentence (%1$d%% done)", pct);
        case "more" -> reason("Not sent: you seem to have more to say (%1$d%% done)", pct);
        default -> reason("Not sent: not clearly done yet (%1$d%%)", pct);
      }
    listener.changed();
  }

  /** How long the current check (writing down, Jev) has been running, 0 when none is. */
  long writingMs() {
    long since = writingSince;
    return since == 0 || transcribing.get() == 0 ? 0 : Math.max(0, clock.getAsLong() - since);
  }

  /** Your turn is over without a message, quietly (nothing was lost). */
  private void endTurn() {
    pending = "";
    takenByYou = false;
    sendAfterFlush = false;
    sendPressed = false;
    synchronized (turnAudio) {
      turnAudio.clear();
      turnPrefix = "";
      turnVersion++;
    }
    listener.changed();
  }

  /** Your turn came to nothing: the floor opens, and a waiting chief message is read. */
  private void giveUpTurn(String why) {
    log("your turn ended without a message (" + why + ")");
    endTurn();
  }

  /**
   * Your message goes, with a note for each reply that couldn't be played. Replies waiting
   * meanwhile stay waiting: they play at your next pause.
   */
  private void send(String text, String why, int parts, String prefix) {
    sendAfterFlush = false;
    sendPressed = false;
    if (text.trim().isEmpty()) {
      reason("Nothing to send: no words were written down");
      giveUpTurn("empty");
      return;
    }
    StringBuilder notes = new StringBuilder();
    int failed;
    synchronized (this) {
      for (String u : unheard) notes.append("\n\n").append(u);
      failed = unheard.size();
    }
    // The turn is over: the parts written down go; what you said since starts your next turn.
    boolean more;
    synchronized (turnAudio) {
      turnAudio.subList(0, Math.min(parts, turnAudio.size())).clear();
      if (turnPrefix.equals(prefix)) turnPrefix = "";
      turnVersion++;
      more = !turnAudio.isEmpty() || !turnPrefix.isEmpty();
    }
    pending = "";
    takenByYou = more;
    outgoing.incrementAndGet();
    sentAt = clock.getAsLong();
    listener.changed();
    long queued = clock.getAsLong();
    String note = notes.toString();
    Runnable delivery = () -> deliver(text.trim() + note, text, why, failed, queued);
    try {
      outbox.execute(delivery);
    } catch (java.util.concurrent.RejectedExecutionException ended) {
      delivery.run(); // The call ended with this message (an end phrase): it still goes.
    }
  }

  /**
   * Delivers a message to the chief (on the outbox): in order, without holding up what you say
   * next. The log says how long it waited for earlier messages and how long bb took.
   */
  private void deliver(String said, String text, String why, int failed, long queued) {
    long start = clock.getAsLong();
    try {
      String request = sender.send(said);
      long done = clock.getAsLong();
      synchronized (this) {
        requests.add(request);
        working.put(request, new long[] {0, done});
        for (int i = 0; i < failed && !unheard.isEmpty(); i++) unheard.remove(0);
      }
      sent++;
      sentAt = done;
      deliveredAt = done;
      status = "sent (" + why + ")";
      error = "";
      log(String.format(java.util.Locale.ROOT, "sent %s (%s, %d characters; bb took %d ms, waited %d ms)", request, why, said.length(), done - start, start - queued));
      cue(Cue.SENT);
      switch (why) {
        case "jev" -> reason("Sent ✓");
        case "silence" -> reason("Sent ✓ after %1$d s of silence", silenceSendMs / 1000);
        case "end phrase", "end of the call" -> reason("Sent ✓ before the call ended");
        default -> reason("Sent ✓ by the Send button");
      }
    } catch (Exception ex) {
      // Kept, before whatever you said since, so the next check or Send tries again.
      synchronized (turnAudio) {
        turnPrefix = (text + " " + turnPrefix).trim();
        turnVersion++;
      }
      pending = pending.isEmpty() ? text : text + " " + pending;
      takenByYou = true;
      status = "not sent: " + ex.getMessage();
      error = "Not sent: " + ex.getMessage();
      log(String.format(java.util.Locale.ROOT, "not sent (%s) after %d ms: %s", why, clock.getAsLong() - start, ex.getMessage()));
      cue(Cue.FAILED);
      reason("Not sent: %1$s. Your words are kept; send again", ex.getMessage());
    } finally {
      outgoing.decrementAndGet();
      listener.changed();
      readNext();
    }
  }

  /** Waits (at most ms) until every message of the call is delivered: before telling the chief it ended. */
  boolean awaitDelivered(long ms) {
    try {
      return outbox.awaitTermination(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      return false;
    }
  }

  /** The note in your message about a chief message you didn't hear: its first words. */
  static String missed(String reply) {
    String[] words = reply.trim().split("\\s+");
    StringBuilder first = new StringBuilder();
    for (int i = 0; i < Math.min(12, words.length); i++) first.append(i == 0 ? "" : " ").append(words[i]);
    return "[didn't hear your reply: \"" + first + (words.length > 12 ? "…" : "") + "\"]";
  }
}
