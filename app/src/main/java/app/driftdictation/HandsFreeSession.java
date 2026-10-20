package app.driftdictation;

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
 *   <li>A chief message arriving while you hold the floor is held. If your turn becomes a message,
 *       the held one is dropped and your message says which reply you didn't hear, so the chief can
 *       pick it up; if your turn comes to nothing, the held message is read.
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
   * BARGE_FACTOR times that pause the reply, and what was said is written down: the reply's own
   * words (echo) resume it, anything else interrupts it and starts your turn.
   */
  static final int ECHO_FRAMES = 35, BARGE_FRAMES = 15;

  static final double BARGE_FACTOR = 2.5;

  /** Once you hold the floor, a stretch this short still counts ("Over." on its own). */
  static final int SHORT_MS = 200;

  interface Transcriber {
    /** The text of a WAV file; throws when the speech model fails. */
    String transcribe(byte[] wav) throws Exception;
  }

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

    /** Pauses the reply (you may be talking); resume plays on from there. */
    default void pause() {}

    default void resume() {}

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

    Held(String request, String text) {
      this.request = request;
      this.text = text;
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
      voices = Executors.newSingleThreadExecutor();
  /** Messages handed to the outbox and not delivered yet. */
  private final AtomicInteger outgoing = new AtomicInteger();
  /** Chief messages waiting for an open floor (or to be dropped by your message). */
  private final ArrayDeque<Held> held = new ArrayDeque<>();
  /**
   * Notes for your next message about replies you didn't hear (whose reading failed) or heard only
   * up to where you interrupted.
   */
  private final List<String> unheard = new ArrayList<>();
  private final List<String> requests = new ArrayList<>();
  /** Request → {replies so far, sent at}: for "Chief is working". */
  private final java.util.Map<String, long[]> working = new java.util.HashMap<>();
  /** Stretches handed to the speech model and not back yet. */
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
  private volatile long mutedUntil;
  private volatile java.util.function.Consumer<String> events = e -> {};
  private int reading, sent;
  private Held current;
  private boolean playing;
  private long readingDeadline, readingSince;
  private final int pauseFrames;
  private final double gate;
  // Barge-in, while a reply plays (under this's lock).
  private int echoFrames, bargeLoud, snippetQuiet, pausedAt;
  private double echoLevel;
  private boolean paused, checking;
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
                transcribing.incrementAndGet();
                long at = clock.getAsLong();
                execute(() -> heard(pcm, at), () -> transcribing.decrementAndGet());
              }

              public void started(double level, double background) {
                boolean taking = !takenByYou;
                takenByYou = true;
                if (taking)
                  log(String.format(java.util.Locale.ROOT, "floor: you (level %.0f, background %.0f)", level, background));
                listener.changed();
              }

              public void noise(int speechMs, double level, double background) {
                log(String.format(java.util.Locale.ROOT, "noise ignored (%d ms, level %.0f, background %.0f)", speechMs, level, background));
                // Send now during a sound too short to count: what was said before still goes.
                if (sendAfterFlush) execute(() -> send(pending, "button"), null);
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

  /** A reply's audio is coming out (or paused for a barge-in check). */
  synchronized boolean audioPlaying() {
    return state == State.READING && playing;
  }

  /** Chief messages waiting to be read. */
  synchronized int held() {
    return held.size();
  }

  /** Chief messages that couldn't be read, not yet noted in a message. */
  synchronized int unheard() {
    return unheard.size();
  }

  /** Is your turn going on: speaking, being written down, unsent text, or going out? */
  private boolean floorIsYours() {
    return takenByYou || segmenter.speaking() || transcribing.get() > 0 || !pending.isEmpty() || outgoing.get() > 0;
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
    if (state == State.READING) {
      bargeFrame(frame);
      return;
    }
    if (state != State.LISTENING || clock.getAsLong() < mutedUntil) return;
    segmenter.frame(frame);
  }

  /** Barge-in: is that you over the reply? Pauses it, then lets the speech model tell. */
  private void bargeFrame(byte[] frame) {
    double level = HandsFree.Segmenter.level(frame, frame.length);
    byte[] pcm = null;
    String suspected = null;
    int token;
    synchronized (this) {
      if (state != State.READING || !playing) return; // Not before the reply's audio started.
      token = reading;
      if (checking) {
        if (overflow.size() < 30_000 / HandsFree.Segmenter.FRAME_MS) overflow.add(frame.clone());
        return;
      }
      recent.addLast(frame.clone());
      while (recent.size() > HandsFree.Segmenter.PAD_MS / HandsFree.Segmenter.FRAME_MS) recent.removeFirst();
      double floor = Math.max(segmenter.background() * gate, 600);
      if (!paused) {
        if (echoFrames < ECHO_FRAMES) { // The reply's own level coming back.
          echoFrames++;
          echoLevel = Math.max(echoLevel, level);
          return;
        }
        double threshold = Math.max(echoLevel * BARGE_FACTOR, floor);
        if (level > threshold) bargeLoud++;
        else {
          bargeLoud = Math.max(0, bargeLoud - 2);
          echoLevel = Math.max(echoLevel * 0.998, level); // Follows the reply's level.
        }
        if (bargeLoud < BARGE_FRAMES) return;
        paused = true;
        bargeLoud = 0;
        snippetQuiet = 0;
        snippet = new java.io.ByteArrayOutputStream();
        for (byte[] b : recent) snippet.write(b, 0, b.length);
        recent.clear();
        suspected =
            String.format(
                java.util.Locale.ROOT, "barge-in suspected (level %.0f, echo %.0f, threshold %.0f): reply paused", level, echoLevel, threshold);
      } else {
        snippet.write(frame, 0, frame.length);
        snippetQuiet = level > floor ? 0 : snippetQuiet + 1;
        if (snippetQuiet >= pauseFrames || snippet.size() > 30 * AudioFiles.BYTES_PER_SECOND) {
          pcm = snippet.toByteArray();
          snippet = null;
          checking = true;
        }
      }
    }
    if (suspected != null) {
      speaker.pause();
      int at = speaker.heardChars();
      synchronized (this) {
        pausedAt = at;
      }
      log(suspected + " at character " + at);
      listener.changed();
    }
    if (pcm != null) {
      byte[] said = pcm;
      execute(() -> checkBargeIn(token, said), null);
    }
  }

  /** The snippet said over the reply, written down: the reply's own words, or you interrupting. */
  private void checkBargeIn(int token, byte[] pcm) {
    String text;
    try {
      text = transcriber.transcribe(AudioFiles.wav(pcm)).trim();
    } catch (Exception ex) {
      text = null;
      log("barge-in not written down: " + ex.getMessage());
    }
    String replyText;
    int at;
    List<byte[]> more;
    synchronized (this) {
      if (token != reading || state != State.READING || current == null) return; // Ended meanwhile.
      replyText = current.text;
      at = pausedAt;
      more = new ArrayList<>(overflow);
      overflow.clear();
      checking = false;
      if (text == null || text.isEmpty() || echoOf(text, replyText, at)) {
        paused = false;
        readingDeadline = clock.getAsLong() + OVERRUN_MS;
      }
    }
    if (text == null || text.isEmpty() || echoOf(text, replyText, at)) {
      log("barge-in was the reply's echo (\"" + (text == null ? "" : text) + "\"): resumed");
      speaker.resume();
      listener.changed();
      return;
    }
    String note = heardUpTo(replyText, at);
    log("interrupted by you (\"" + text + "\"): " + note);
    endReading(token, note, false);
    speaker.stop();
    takenByYou = true;
    for (byte[] f : more) segmenter.frame(f); // What you went on saying meanwhile.
    took(text);
    readNext();
  }

  /** Normalized words of a text. */
  private static List<String> words(String text) {
    List<String> out = new ArrayList<>();
    for (String w : HandsFree.normalize(text).split(" ")) if (!w.isEmpty()) out.add(w);
    return out;
  }

  /**
   * Is what was said the reply's own words around where it paused (the voice coming back through
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
      // Paused for a barge-in check: it's waiting for you, not hung.
      if (late && (paused || checking) && now - readingSince < READING_CAP_MS) {
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
      log(playing ? "the reply played too long: listening again" : "the reply's audio didn't start: listening again");
      speaker.stop();
      finishReading(token, false);
      return;
    }
    if (state == State.LISTENING && !pending.isEmpty() && !segmenter.candidate() && transcribing.get() == 0
        && segmenter.quietMs() >= silenceSendMs)
      execute(() -> send(pending, "silence"), null);
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
    transcribing.incrementAndGet();
    long at = clock.getAsLong();
    execute(() -> heard(pcm, at), () -> transcribing.decrementAndGet());
  }

  /**
   * Send now (the panel's, the screen's, the notification's or the headset's button). It shows at
   * once (Sending…), since your message may wait for what you said last to be written down; a
   * second press meanwhile, or right after it went, does nothing (it would send the next words
   * you start on their own). Every press is logged, also the ones that do nothing.
   */
  void sendNow() {
    if (state != State.LISTENING) {
      log("send pressed while a reply is read: nothing sent (talk over the reply to interrupt it)");
      return;
    }
    if (sendPressed) {
      log("send pressed again: already sending");
      return;
    }
    if (clock.getAsLong() - sentAt < REPRESS_MS) {
      log("send pressed right after your message went: taken as the same press");
      return;
    }
    if (pending.isEmpty() && transcribing.get() == 0 && !segmenter.candidate()) {
      log("send pressed: nothing said yet");
      return;
    }
    sendPressed = true;
    listener.changed();
    if (segmenter.candidate()) {
      log("send pressed: ending what you're saying");
      sendAfterFlush = true;
      segmenter.flush();
    } else {
      log("send pressed" + (transcribing.get() > 0 ? ": goes once what you said is written down" : ""));
      execute(() -> send(pending, "button"), () -> sendPressed = false);
    }
  }

  /** Ends the call. What you said but didn't send yet isn't sent. */
  void end() {
    if (state == State.ENDED) return;
    state = State.ENDED;
    synchronized (this) {
      held.clear();
    }
    log("ended");
    speaker.stop();
    status = "ended";
    work.shutdown();
    outbox.shutdown(); // Messages on their way still go.
    voices.shutdownNow();
    listener.changed();
  }

  /**
   * A spoken message from the chief: its audio is fetched right away, while the floor stays open;
   * once ready it's read on an open floor, else held.
   */
  void reply(String request, String text) {
    if (state == State.ENDED) return;
    Held h = new Held(request, text);
    long since;
    synchronized (this) {
      long[] w = working.get(request);
      if (w != null) w[0]++;
      since = w == null ? -1 : clock.getAsLong() - w[1];
      held.addLast(h);
    }
    boolean taken = floorIsYours() || state == State.READING;
    log("chief message " + request + " (" + text.length() + " characters" + (since < 0 ? "" : ", " + since + " ms after your message went")
        + ")" + (taken ? ": held, the floor is taken" : ""));
    try {
      voices.execute(
          () -> {
            long start = clock.getAsLong();
            try {
              h.prepared = speaker.prepare(request, text);
              log("voice ready for " + request + " in " + (clock.getAsLong() - start) + " ms");
            } catch (RuntimeException ex) {
              log("voice not ready for " + request + ": " + ex.getMessage());
            }
            h.ready = true;
            readNext();
          });
    } catch (java.util.concurrent.RejectedExecutionException ended) {
      return;
    }
    listener.changed();
  }

  /** On an open floor, the chief takes it with the next held message, once its audio is ready. */
  private void readNext() {
    Held next;
    int token;
    synchronized (this) {
      if (state != State.LISTENING || held.isEmpty() || !held.peekFirst().ready || floorIsYours() || segmenter.candidate()) return;
      next = held.removeFirst();
      state = State.READING;
      reply = next.text;
      current = next;
      playing = false;
      paused = checking = false;
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
              log(String.format(java.util.Locale.ROOT, "audio started (%.1f s) after %d ms", durationMs / 1000.0, clock.getAsLong() - readingSince));
            }

            public void ended(boolean played) {
              finishReading(token, played);
            }
          });
    } catch (RuntimeException ex) {
      log("the voice failed: " + ex.getMessage());
      finishReading(token, false);
    }
  }

  /**
   * Reading number token ended (finished or given up): the floor is open again. A reply that didn't
   * play is noted in your next message as not heard.
   */
  private void finishReading(int token, boolean played) {
    boolean heard;
    String note;
    synchronized (this) {
      if (token != reading || state != State.READING) return;
      heard = played && playing;
      note = heard || current == null ? null : missed(current.text);
    }
    if (heard) log("reply played to the end");
    else log("reply not heard: noted in your next message");
    if (endReading(token, note, true)) readNext();
  }

  /**
   * Ends reading number token: note (if any) goes with your next message; tail mutes the
   * microphone a moment (not when you interrupted: then you're talking). False if it had ended.
   */
  private boolean endReading(int token, String note, boolean tail) {
    synchronized (this) {
      if (token != reading || state != State.READING) return false;
      if (note != null) unheard.add(note);
      current = null;
      paused = checking = false;
      snippet = null;
      mutedUntil = tail ? clock.getAsLong() + TAIL_MS : 0;
      segmenter.reset();
      reply = "";
      state = State.LISTENING;
    }
    status = "listening";
    log("floor: " + (tail ? "open" : "you"));
    listener.changed();
    return true;
  }

  /**
   * A stretch of your speech ended at endedAt (on the work thread). The log says how long it
   * waited for the work queue and how long the speech model took.
   */
  private void heard(byte[] pcm, long endedAt) {
    try {
      if (state == State.ENDED) return;
      double seconds = pcm.length / (double) AudioFiles.BYTES_PER_SECOND;
      long start = clock.getAsLong();
      String text;
      try {
        text = transcriber.transcribe(AudioFiles.wav(pcm)).trim();
      } catch (Exception ex) {
        status = "transcription failed: " + ex.getMessage();
        error = "Couldn't write down what you said: " + ex.getMessage();
        log(String.format(java.util.Locale.ROOT, "heard %.1f s, transcription failed after %d ms: %s", seconds, clock.getAsLong() - start, ex.getMessage()));
        // Nothing else of your turn left: the floor opens again.
        if (pending.isEmpty() && transcribing.get() <= 1 && !segmenter.candidate()) giveUpTurn("not written down");
        listener.changed();
        return;
      }
      long done = clock.getAsLong();
      log(String.format(java.util.Locale.ROOT, "heard %.1f s: \"%s\" (written down in %d ms, waited %d ms)", seconds, text, done - start, start - endedAt));
      took(text);
    } finally {
      transcribing.decrementAndGet();
      readNext();
    }
  }

  /** What you said, written down: added to your turn; a send or end word at its end acts. */
  private void took(String text) {
    {
      if (!text.isEmpty()) {
        pending = pending.isEmpty() ? text : pending + " " + text;
        listener.changed();
      }
      String rest = HandsFree.endsWith(pending, endWords);
      if (rest != null) {
        log("end phrase");
        if (!rest.isEmpty()) send(rest, "end phrase");
        end();
        return;
      }
      rest = HandsFree.endsWith(pending, sendWords);
      if (rest != null) {
        if (rest.isEmpty()) giveUpTurn("only the send word");
        else send(rest, "send word");
        return;
      }
      if (sendAfterFlush) {
        send(pending, "button");
        return;
      }
      if (pending.isEmpty() && transcribing.get() <= 1 && !segmenter.candidate()) giveUpTurn("nothing was said");
    }
  }

  /** Your turn came to nothing: the floor opens, and a held chief message is read. */
  private void giveUpTurn(String why) {
    pending = "";
    takenByYou = false;
    sendAfterFlush = false;
    sendPressed = false;
    log("your turn ended without a message (" + why + ")");
    listener.changed();
  }

  /**
   * Your message goes. Chief messages held meanwhile are dropped, and your message says which you
   * didn't hear, so the chief can work them into its next answer.
   */
  private void send(String text, String why) {
    sendAfterFlush = false;
    sendPressed = false;
    if (text.trim().isEmpty()) {
      giveUpTurn("empty");
      return;
    }
    StringBuilder said = new StringBuilder(text.trim());
    int dropped, failed;
    synchronized (this) {
      for (String u : unheard) said.append("\n\n").append(u);
      for (Held h : held) said.append("\n\n").append(missed(h.text));
      dropped = held.size();
      failed = unheard.size();
    }
    // The turn is over: you can go on talking at once (a new turn) while this one is delivered.
    pending = "";
    takenByYou = false;
    outgoing.incrementAndGet();
    sentAt = clock.getAsLong();
    listener.changed();
    long queued = clock.getAsLong();
    String message = said.toString();
    Runnable delivery = () -> deliver(message, text, why, dropped, failed, queued);
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
  private void deliver(String said, String text, String why, int dropped, int failed, long queued) {
    long start = clock.getAsLong();
    try {
      String request = sender.send(said);
      long done = clock.getAsLong();
      synchronized (this) {
        requests.add(request);
        working.put(request, new long[] {0, done});
        for (int i = 0; i < dropped && !held.isEmpty(); i++) held.removeFirst();
        for (int i = 0; i < failed && !unheard.isEmpty(); i++) unheard.remove(0);
      }
      if (dropped > 0) log("dropped " + dropped + " held chief message(s), noted in your message");
      sent++;
      sentAt = done;
      status = "sent (" + why + ")";
      error = "";
      log(String.format(java.util.Locale.ROOT, "sent %s (%s, %d characters; bb took %d ms, waited %d ms)", request, why, said.length(), done - start, start - queued));
    } catch (Exception ex) {
      // Kept, before whatever you said since, so the next send word or button tries again.
      pending = pending.isEmpty() ? text : text + " " + pending;
      status = "not sent: " + ex.getMessage();
      error = "Not sent: " + ex.getMessage();
      log(String.format(java.util.Locale.ROOT, "not sent (%s) after %d ms: %s", why, clock.getAsLong() - start, ex.getMessage()));
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
