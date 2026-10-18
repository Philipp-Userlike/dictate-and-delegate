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

  /** Reading longer than this means the voice hung: listening resumes. */
  static final long READING_MAX_MS = 3 * 60_000;

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
    /** Reads a reply to request aloud; done runs once it ended. */
    void speak(String request, String text, Runnable done);

    void stop();
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
  private final ExecutorService work = Executors.newSingleThreadExecutor();
  /** Chief messages waiting for an open floor (or to be dropped by your message): {request, text}. */
  private final ArrayDeque<String[]> held = new ArrayDeque<>();
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
  private long readingSince;

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

  /** Chief messages held while you have the floor. */
  synchronized int held() {
    return held.size();
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
    long now = System.currentTimeMillis();
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
    if (state != State.LISTENING || System.currentTimeMillis() < mutedUntil) return;
    segmenter.frame(frame);
  }

  /** Once a second: a long silence sends anyway, a held reply may start, a hung voice is let go. */
  void tick() {
    if (state == State.ENDED) return;
    if (state == State.READING && System.currentTimeMillis() - readingSince > READING_MAX_MS) {
      log("reading took too long: listening again");
      finishReading(reading);
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
    listener.changed();
  }

  /** A spoken message from the chief: read now on an open floor, else held. */
  void reply(String request, String text) {
    if (state == State.ENDED) return;
    synchronized (this) {
      long[] w = working.get(request);
      if (w != null) w[0]++;
      held.addLast(new String[] {request, text});
    }
    boolean taken = floorIsYours() || state == State.READING;
    log("chief message " + request + " (" + text.length() + " characters)" + (taken ? ": held, the floor is taken" : ""));
    readNext();
    listener.changed();
  }

  /** On an open floor, the chief takes it with the next held message. */
  private void readNext() {
    String[] next;
    int token;
    synchronized (this) {
      if (state != State.LISTENING || held.isEmpty() || floorIsYours() || segmenter.candidate()) return;
      next = held.removeFirst();
      state = State.READING;
      reply = next[1];
      token = ++reading;
      readingSince = System.currentTimeMillis();
    }
    status = "reading";
    log("floor: chief, reading " + next[0]);
    listener.changed();
    speaker.speak(next[0], next[1], () -> finishReading(token));
  }

  /** Reading number token ended (finished or given up): the floor is open again. */
  private void finishReading(int token) {
    synchronized (this) {
      if (token != reading || state != State.READING) return;
      mutedUntil = System.currentTimeMillis() + TAIL_MS;
      segmenter.reset();
      reply = "";
      state = State.LISTENING;
    }
    status = "listening";
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
    int dropped;
    synchronized (this) {
      for (String[] h : held) said.append("\n\n").append(missed(h[1]));
      dropped = held.size();
    }
    pending = "";
    sending = true;
    listener.changed();
    try {
      String request = sender.send(said.toString());
      synchronized (this) {
        requests.add(request);
        working.put(request, new long[] {0, System.currentTimeMillis()});
      }
      synchronized (this) {
        for (int i = 0; i < dropped && !held.isEmpty(); i++) held.removeFirst();
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
