package app.driftdictation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One hands-free session, without Android: microphone frames go in, messages to the chief and
 * replies to read aloud come out. Each stretch of speech is transcribed once, when you pause; the
 * text so far is sent when it ends with a send word, or after a long silence. While a reply is read
 * aloud, speech only counts if it's a stop word. An end word ends the session (sending what came
 * before it). HandsFreeService wires it to the microphone, bb, ntfy and the voice.
 */
final class HandsFreeSession {
  enum State {
    LISTENING,
    READING,
    ENDED
  }

  interface Transcriber {
    /** The text of a WAV file; throws when the speech model fails. */
    String transcribe(byte[] wav) throws Exception;
  }

  interface Sender {
    /** Sends a message to the chief; returns its request ID. */
    String send(String said) throws Exception;
  }

  interface Speaker {
    void speak(String text, Runnable done);

    void stop();
  }

  interface Listener {
    /** Something changed: state, the text so far, the last thing that happened. */
    void changed();
  }

  final List<String> sendWords, endWords, stopWords;
  final int silenceSendMs;
  private final Transcriber transcriber;
  private final Sender sender;
  private final Speaker speaker;
  private final Listener listener;
  private final HandsFree.Segmenter segmenter;
  private final ExecutorService work = Executors.newSingleThreadExecutor();
  private final ArrayDeque<String> replies = new ArrayDeque<>();
  private final List<String> requests = new ArrayList<>();
  private volatile State state = State.LISTENING;
  private volatile String pending = "", status = "";
  private volatile boolean sendAfterFlush;
  private int sent;

  HandsFreeSession(
      int pauseMs,
      int silenceSendMs,
      List<String> sendWords,
      List<String> endWords,
      List<String> stopWords,
      Transcriber transcriber,
      Sender sender,
      Speaker speaker,
      Listener listener) {
    this.silenceSendMs = silenceSendMs;
    this.sendWords = sendWords;
    this.endWords = endWords;
    this.stopWords = stopWords;
    this.transcriber = transcriber;
    this.sender = sender;
    this.speaker = speaker;
    this.listener = listener;
    segmenter = new HandsFree.Segmenter(pauseMs, pcm -> work.execute(() -> heard(pcm)));
  }

  State state() {
    return state;
  }

  /** What you said since the last message. */
  String pending() {
    return pending;
  }

  /** The last thing that happened, for the screen. */
  String status() {
    return status;
  }

  int sent() {
    return sent;
  }

  boolean speaking() {
    return segmenter.speaking();
  }

  /** Is this one of the requests sent in this session? */
  synchronized boolean ours(String request) {
    return requests.contains(request);
  }

  /** One 20 ms frame from the microphone (on the recording thread). */
  void frame(byte[] frame) {
    if (state == State.ENDED) return;
    segmenter.frame(frame);
  }

  /** Once a second: after a long silence, what you said goes anyway. */
  void tick() {
    if (state != State.LISTENING || pending.isEmpty() || segmenter.speaking()) return;
    if (segmenter.quietMs() >= silenceSendMs) work.execute(() -> send(pending, "silence"));
  }

  /** Send now (the screen's, the notification's or the headset's button). */
  void sendNow() {
    if (state == State.ENDED) return;
    if (segmenter.speaking()) {
      sendAfterFlush = true;
      segmenter.flush();
    } else work.execute(() -> send(pending, "button"));
  }

  /** Stop reading (button or spoken stop word); the queue of replies goes too. */
  void stopReading() {
    synchronized (this) {
      replies.clear();
    }
    speaker.stop();
  }

  /** Ends the session. What you said but didn't send yet isn't sent. */
  void end() {
    if (state == State.ENDED) return;
    state = State.ENDED;
    synchronized (this) {
      replies.clear();
    }
    speaker.stop();
    status = "ended";
    work.shutdown();
    listener.changed();
  }

  /** A spoken reply from the chief: read aloud after anything already being read. */
  void reply(String text) {
    if (state == State.ENDED) return;
    synchronized (this) {
      replies.addLast(text);
    }
    readNext();
  }

  private void readNext() {
    String next;
    synchronized (this) {
      if (state != State.LISTENING || replies.isEmpty()) return;
      next = replies.removeFirst();
      state = State.READING;
    }
    status = "reading";
    listener.changed();
    speaker.speak(
        next,
        () -> {
          if (state == State.READING) state = State.LISTENING;
          status = "listening";
          listener.changed();
          readNext();
        });
  }

  /** A stretch of speech ended (on the work thread). */
  private void heard(byte[] pcm) {
    if (state == State.ENDED) return;
    String text;
    try {
      text = transcriber.transcribe(AudioFiles.wav(pcm)).trim();
    } catch (Exception ex) {
      status = "transcription failed: " + ex.getMessage();
      listener.changed();
      return;
    }
    if (state == State.READING) {
      // While reading, only a stop word counts (anything else may be the voice itself).
      if (HandsFree.endsWith(text, stopWords) != null) stopReading();
      return;
    }
    if (HandsFree.hallucination(text)) {
      if (sendAfterFlush) {
        sendAfterFlush = false;
        send(pending, "button");
      }
      return;
    }
    pending = pending.isEmpty() ? text : pending + " " + text;
    listener.changed();
    String rest = HandsFree.endsWith(pending, endWords);
    if (rest != null) {
      if (!rest.isEmpty()) send(rest, "end word");
      end();
      return;
    }
    rest = HandsFree.endsWith(pending, sendWords);
    if (rest != null) {
      if (rest.isEmpty()) pending = "";
      else send(rest, "send word");
      return;
    }
    if (sendAfterFlush) {
      sendAfterFlush = false;
      send(pending, "button");
    }
  }

  private void send(String text, String why) {
    sendAfterFlush = false;
    if (text.trim().isEmpty()) return;
    pending = "";
    try {
      String request = sender.send(text.trim());
      synchronized (this) {
        requests.add(request);
      }
      sent++;
      status = "sent (" + why + ")";
    } catch (Exception ex) {
      pending = text; // Kept, so the next send word or button tries again.
      status = "not sent: " + ex.getMessage();
    }
    listener.changed();
  }
}
