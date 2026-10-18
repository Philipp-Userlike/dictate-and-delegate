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

  /** What the screen shows, and nothing else: you can talk, the chief has it, or a reply is read. */
  enum Phase {
    LISTENING,
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
  private final ExecutorService work = Executors.newSingleThreadExecutor(), voices = Executors.newSingleThreadExecutor();
  /** Chief messages waiting for an open floor (or to be dropped by your message). */
  private final ArrayDeque<Held> held = new ArrayDeque<>();
  /** Chief messages whose reading failed: your next message says you didn't hear them. */
  private final List<String> unheard = new ArrayList<>();
  private final List<String> requests = new ArrayList<>();
  /** Request → {replies so far, sent at}: for "Chief is working". */
  private final java.util.Map<String, long[]> working = new java.util.HashMap<>();
  /** Stretches handed to the speech model and not back yet. */
  private final AtomicInteger transcribing = new AtomicInteger();
  private volatile State state = State.LISTENING;
  private volatile String pending = "", status = "", error = "", reply = "";
  private volatile boolean sending, sendAfterFlush, takenByYou;
  private volatile long mutedUntil;
  private volatile java.util.function.Consumer<String> events = e -> {};
  private int reading, sent;
  private Held current;
  private boolean playing;
  private long readingDeadline;
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
                execute(() -> heard(pcm), () -> transcribing.decrementAndGet());
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
    return takenByYou || segmenter.speaking() || transcribing.get() > 0 || !pending.isEmpty() || sending;
  }

  Floor floor() {
    if (state == State.READING) return Floor.CHIEF;
    return floorIsYours() ? Floor.YOU : Floor.OPEN;
  }

  Phase phase() {
    if (state == State.READING) return Phase.READING;
    if (sending) return Phase.WORKING;
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
   * One 20 ms frame from the microphone (on the recording thread). While a reply is read, and for
   * TAIL_MS after, frames are dropped: nothing of the voice can end up in your turn.
   */
  void frame(byte[] frame) {
    if (state != State.LISTENING || clock.getAsLong() < mutedUntil) return;
    segmenter.frame(frame);
  }

  /** Once a second: a long silence sends anyway, a held reply may start, a hung voice is let go. */
  void tick() {
    if (state == State.ENDED) return;
    int token;
    boolean late;
    synchronized (this) {
      token = reading;
      late = state == State.READING && clock.getAsLong() > readingDeadline;
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
    execute(() -> heard(pcm), () -> transcribing.decrementAndGet());
  }

  /** Send now (the panel's, the screen's, the notification's or the headset's button). */
  void sendNow() {
    if (state != State.LISTENING) return;
    log("send pressed");
    if (segmenter.candidate()) {
      sendAfterFlush = true;
      segmenter.flush();
    } else execute(() -> send(pending, "button"), null);
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
    synchronized (this) {
      long[] w = working.get(request);
      if (w != null) w[0]++;
      held.addLast(h);
    }
    boolean taken = floorIsYours() || state == State.READING;
    log("chief message " + request + " (" + text.length() + " characters)" + (taken ? ": held, the floor is taken" : ""));
    try {
      voices.execute(
          () -> {
            try {
              h.prepared = speaker.prepare(request, text);
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
      token = ++reading;
      readingDeadline = clock.getAsLong() + START_MS;
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
    Held h;
    boolean heard;
    synchronized (this) {
      if (token != reading || state != State.READING) return;
      h = current;
      heard = played && playing;
      if (!heard && h != null) unheard.add(h.text);
      current = null;
      mutedUntil = clock.getAsLong() + TAIL_MS;
      segmenter.reset();
      reply = "";
      state = State.LISTENING;
    }
    status = "listening";
    if (!heard) log("reply " + (h == null ? "" : h.request + " ") + "not heard: noted in your next message");
    log("floor: open");
    listener.changed();
    readNext();
  }

  /** A stretch of your speech ended (on the work thread). */
  private void heard(byte[] pcm) {
    try {
      if (state == State.ENDED) return;
      double seconds = pcm.length / (double) AudioFiles.BYTES_PER_SECOND;
      String text;
      try {
        text = transcriber.transcribe(AudioFiles.wav(pcm)).trim();
      } catch (Exception ex) {
        status = "transcription failed: " + ex.getMessage();
        error = "Couldn't write down what you said: " + ex.getMessage();
        log(String.format(java.util.Locale.ROOT, "heard %.1f s, transcription failed: %s", seconds, ex.getMessage()));
        // Nothing else of your turn left: the floor opens again.
        if (pending.isEmpty() && transcribing.get() <= 1 && !segmenter.candidate()) giveUpTurn("not written down");
        listener.changed();
        return;
      }
      log(String.format(java.util.Locale.ROOT, "heard %.1f s: \"%s\"", seconds, text));
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
    } finally {
      transcribing.decrementAndGet();
      readNext();
    }
  }

  /** Your turn came to nothing: the floor opens, and a held chief message is read. */
  private void giveUpTurn(String why) {
    pending = "";
    takenByYou = false;
    sendAfterFlush = false;
    log("your turn ended without a message (" + why + ")");
    listener.changed();
  }

  /**
   * Your message goes. Chief messages held meanwhile are dropped, and your message says which you
   * didn't hear, so the chief can work them into its next answer.
   */
  private void send(String text, String why) {
    sendAfterFlush = false;
    if (text.trim().isEmpty()) {
      giveUpTurn("empty");
      return;
    }
    StringBuilder said = new StringBuilder(text.trim());
    int dropped, failed;
    synchronized (this) {
      for (String u : unheard) said.append("\n\n").append(missed(u));
      for (Held h : held) said.append("\n\n").append(missed(h.text));
      dropped = held.size();
      failed = unheard.size();
    }
    pending = "";
    sending = true;
    listener.changed();
    try {
      String request = sender.send(said.toString());
      synchronized (this) {
        requests.add(request);
        working.put(request, new long[] {0, clock.getAsLong()});
      }
      synchronized (this) {
        for (int i = 0; i < dropped && !held.isEmpty(); i++) held.removeFirst();
        for (int i = 0; i < failed && !unheard.isEmpty(); i++) unheard.remove(0);
      }
      if (dropped > 0) log("dropped " + dropped + " held chief message(s), noted in your message");
      sent++;
      status = "sent (" + why + ")";
      error = "";
      log("sent " + request + " (" + why + ", " + said.length() + " characters)");
    } catch (Exception ex) {
      pending = text; // Kept, so the next send word or button tries again.
      status = "not sent: " + ex.getMessage();
      error = "Not sent: " + ex.getMessage();
      log("not sent (" + why + "): " + ex.getMessage());
    }
    sending = false;
    takenByYou = false;
    listener.changed();
    readNext();
  }

  /** The note in your message about a chief message you didn't hear: its first words. */
  static String missed(String reply) {
    String[] words = reply.trim().split("\\s+");
    StringBuilder first = new StringBuilder();
    for (int i = 0; i < Math.min(12, words.length); i++) first.append(i == 0 ? "" : " ").append(words[i]);
    return "[didn't hear your reply: \"" + first + (words.length > 12 ? "…" : "") + "\"]";
  }
}
