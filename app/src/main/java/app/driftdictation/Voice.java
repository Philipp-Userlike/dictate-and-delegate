package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Reads the chief's replies aloud in hands-free mode: a speech model through OpenRouter
 * (POST /audio/speech, MP3, with the user's OpenRouter key), or Android's own voice offline and as
 * the fallback when OpenRouter fails.
 */
final class Voice {
  /** A choice in Settings → Hands-free: the OpenRouter model and voice, or Android's. */
  record Choice(String id, String name, String note, String model, String voice, String pricePerCharacter) {
    String label() {
      return name + " · " + note;
    }

    /** OpenRouter's list price, for when the real cost can't be looked up. */
    java.math.BigDecimal perCharacter() {
      return new java.math.BigDecimal(pricePerCharacter);
    }
  }

  static final Choice KOKORO = new Choice("kokoro", "Kokoro", "cheapest", "hexgrad/kokoro-82m", "af_heart", "0.00000062"),
      ELEVEN = new Choice("eleven", "ElevenLabs Flash v2.5", "best voice", "elevenlabs/eleven-flash-v2.5", "george", "0.00002"),
      ANDROID = new Choice("android", "Android's voice", "offline, free", "", "", "0");
  static final Choice[] CHOICES = {KOKORO, ELEVEN, ANDROID};

  static Choice choice(String id) {
    for (Choice c : CHOICES) if (c.id().equals(id)) return c;
    return KOKORO;
  }

  /** How a reading goes: audio started (and how long it will take), then it ended. */
  interface Playback {
    void started(long durationMs);

    /** Ended: played to the end, or failed or stopped (played false). */
    void ended(boolean played);
  }

  /** A reply ready to play: its audio (null: Android's voice reads the text) and OpenRouter's generation ID. */
  record Prepared(File mp3, String generation) {}

  private final Context context;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private MediaPlayer player;
  private TextToSpeech tts;
  private Playback current;
  /** The reply being read; for Android's voice, where its spoken part starts and how far it got. */
  private volatile String text = "";
  private volatile int ttsBase, ttsAt;
  private volatile boolean pausing;
  private int replies;

  /** Ends the current reading once, on the main thread. */
  private void finished(boolean played) {
    Playback p;
    synchronized (this) {
      p = current;
      current = null;
    }
    if (p != null)
      main.post(
          () -> {
            focus(false);
            p.ended(played);
          });
  }

  Voice(Context context) {
    this.context = context.getApplicationContext();
  }

  /** The request for OpenRouter's speech endpoint. */
  static JSONObject body(Choice c, String text) throws Exception {
    return new JSONObject().put("model", c.model()).put("voice", c.voice()).put("input", text).put("response_format", "mp3");
  }

  /**
   * Gets a reply ready: fetches its audio from OpenRouter (network: not on the main thread). Never
   * throws: without audio (Android's voice chosen, or OpenRouter failed) Android's voice reads it.
   */
  Prepared prepare(Choice choice, String text) {
    if (choice.model().isEmpty()) return new Prepared(null, "");
    try {
      String[] generation = {""};
      File mp3 = fetch(choice, text, generation);
      return new Prepared(mp3, generation[0]);
    } catch (Exception ex) {
      android.util.Log.w("Voice", "OpenRouter's voice failed, Android's reads instead", ex);
      return new Prepared(null, "");
    }
  }

  /** Plays a prepared reply; the playback hears when audio starts and when it ends. */
  void play(Prepared prepared, String text, Playback playback) {
    synchronized (this) {
      current = playback;
    }
    this.text = text;
    ttsBase = ttsAt = 0;
    pausing = false;
    focus(true);
    new Thread(
            () -> {
              AudioTrack warm = warmUp();
              synchronized (this) {
                if (current != playback) { // Stopped meanwhile.
                  if (warm != null) warm.release();
                  return;
                }
              }
              if (prepared.mp3() == null) speakAndroid(text, playback);
              else
                main.post(
                    () -> {
                      try {
                        play(prepared.mp3(), playback);
                      } catch (Exception ex) {
                        release();
                        prepared.mp3().delete();
                        speakAndroid(text, playback); // The audio doesn't play: Android's voice instead.
                      }
                    });
              // The silence keeps the route awake until the reply has started.
              main.postDelayed(
                  () -> {
                    if (warm != null) warm.release();
                  },
                  1500);
            },
            "voice-warm-up")
        .start();
  }

  /** Silence played first, so the route is awake for the first word. */
  static final int WARM_MS = 500;

  private static AudioAttributes attributes() {
    return new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();
  }

  /**
   * Half a second of silence on the voice's route. Right after the microphone, and over Bluetooth
   * especially, the route wakes up with the first sound and swallows it: this way that's silence,
   * not the reply's first words. Returns the track to release once the reply plays (or null).
   */
  private AudioTrack warmUp() {
    try {
      int rate = 24_000, bytes = rate * 2 * 2; // Two seconds of room; half a second is played first.
      AudioTrack t =
          new AudioTrack.Builder()
              .setAudioAttributes(attributes())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setSampleRate(rate)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build())
              .setTransferMode(AudioTrack.MODE_STATIC)
              .setBufferSizeInBytes(bytes)
              .build();
      t.write(new byte[bytes], 0, bytes);
      t.play();
      Thread.sleep(WARM_MS);
      return t;
    } catch (Exception ex) {
      return null; // No warm-up: the reply plays anyway.
    }
  }

  private AudioFocusRequest focusRequest;

  /** While a reply plays, other sound (music, navigation) gets quieter. */
  private void focus(boolean on) {
    AudioManager audio = context.getSystemService(AudioManager.class);
    if (on && focusRequest == null) {
      focusRequest =
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
              .setAudioAttributes(attributes())
              .build();
      audio.requestAudioFocus(focusRequest);
    } else if (!on && focusRequest != null) {
      audio.abandonAudioFocusRequest(focusRequest);
      focusRequest = null;
    }
  }

  /** Pauses the reply (you may be talking over it). */
  void pause() {
    MediaPlayer p;
    synchronized (this) {
      p = player;
    }
    try {
      if (p != null) {
        if (p.isPlaying()) p.pause();
        return;
      }
    } catch (IllegalStateException ignored) {
      return;
    }
    TextToSpeech t = tts;
    if (t != null && t.isSpeaking()) {
      pausing = true;
      ttsBase += ttsAt; // Android's voice can't pause: it stops, and resume speaks from here.
      ttsAt = 0;
      t.stop();
    }
  }

  /** Plays on from where it paused. */
  void resume() {
    MediaPlayer p;
    synchronized (this) {
      p = player;
    }
    try {
      if (p != null) {
        p.start();
        return;
      }
    } catch (IllegalStateException ignored) {
      return;
    }
    TextToSpeech t = tts;
    if (t != null && pausing) {
      pausing = false;
      String rest = text.substring(Math.min(ttsBase, text.length()));
      if (rest.isBlank() || t.speak(rest, TextToSpeech.QUEUE_FLUSH, null, "rest") != TextToSpeech.SUCCESS) finished(true);
    }
  }

  /**
   * How many characters of the reply have been heard: by the playback position (a third of a
   * second less, for the sound still on its way to the ear), or Android's voice's word.
   */
  int heardChars() {
    MediaPlayer p;
    synchronized (this) {
      p = player;
    }
    try {
      if (p != null) {
        int duration = p.getDuration();
        if (duration <= 0) return 0;
        long at = Math.max(0, p.getCurrentPosition() - 300L);
        return (int) Math.min(text.length(), text.length() * at / duration);
      }
    } catch (IllegalStateException ignored) {
      return 0;
    }
    return Math.min(text.length(), ttsBase + ttsAt);
  }

  /** Is reply audio still coming out (the session's watchdog asks)? */
  boolean playing() {
    MediaPlayer p;
    synchronized (this) {
      p = player;
    }
    try {
      if (p != null) return p.isPlaying();
    } catch (IllegalStateException ignored) {
      return false;
    }
    TextToSpeech t = tts;
    return t != null && t.isSpeaking();
  }

  /** What OpenRouter charged for a generation (GET /generation), or null if it can't say yet. */
  static java.math.BigDecimal cost(Context context, String generation) {
    if (generation == null || generation.isEmpty()) return null;
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        Thread.sleep(1500L * (attempt + 1)); // It shows up a moment after the call.
        String key = new Vault(context).get(OpenRouter.KEY);
        HttpURLConnection c =
            (HttpURLConnection) new URL(OpenRouter.base() + "/generation?id=" + java.net.URLEncoder.encode(generation, "UTF-8")).openConnection();
        try {
          c.setConnectTimeout(10_000);
          c.setReadTimeout(15_000);
          c.setRequestProperty("Authorization", "Bearer " + key);
          if (c.getResponseCode() >= 300) continue;
          try (InputStream in = c.getInputStream()) {
            JSONObject d = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).optJSONObject("data");
            if (d != null && d.has("total_cost")) return new java.math.BigDecimal(d.get("total_cost").toString());
          }
        } finally {
          c.disconnect();
        }
      } catch (Exception ignored) {
        /* Try again. */
      }
    }
    return null;
  }

  private File fetch(Choice choice, String text, String[] generation) throws Exception {
    String key = new Vault(context).get(OpenRouter.KEY);
    HttpURLConnection c = (HttpURLConnection) new URL(OpenRouter.base() + "/audio/speech").openConnection();
    try {
      byte[] payload = body(choice, text).toString().getBytes(StandardCharsets.UTF_8);
      c.setConnectTimeout(10_000);
      c.setReadTimeout(30_000);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Authorization", "Bearer " + key);
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("X-Title", "Dictate & Delegate");
      c.setFixedLengthStreamingMode(payload.length);
      try (OutputStream out = c.getOutputStream()) {
        out.write(payload);
      }
      if (c.getResponseCode() >= 300) throw new IOException(tr("The voice model answered with HTTP %1$d.", c.getResponseCode()));
      String id = c.getHeaderField("X-Generation-Id");
      generation[0] = id == null ? "" : id;
      // Each reply its own file: several may be ready while one plays.
      File f;
      synchronized (this) {
        f = new File(context.getCacheDir(), "reply-" + (++replies % 8) + ".mp3");
      }
      try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
        in.transferTo(out);
      }
      if (f.length() == 0) throw new IOException("The voice model sent no audio.");
      return f;
    } finally {
      c.disconnect();
    }
  }

  /** On the main thread. */
  private void play(File mp3, Playback playback) throws IOException {
    MediaPlayer p = new MediaPlayer();
    p.setAudioAttributes(attributes());
    p.setDataSource(mp3.getPath());
    p.setOnCompletionListener(
        mp -> {
          release();
          mp3.delete();
          finished(true);
        });
    p.setOnErrorListener(
        (mp, what, extra) -> {
          release();
          mp3.delete();
          finished(false);
          return true;
        });
    p.prepare();
    synchronized (this) {
      player = p;
    }
    p.start();
    playback.started(Math.max(0, p.getDuration()));
  }

  /** Android's voice; about 15 characters a second for how long it takes. */
  private void speakAndroid(String text, Playback playback) {
    main.post(
        () -> {
          if (tts != null) tts.shutdown();
          tts =
              new TextToSpeech(
                  context,
                  status -> {
                    if (status != TextToSpeech.SUCCESS) {
                      finished(false);
                      return;
                    }
                    tts.setLanguage(Locale.getDefault());
                    tts.setOnUtteranceProgressListener(
                        new UtteranceProgressListener() {
                          public void onStart(String id) {
                            if ("reply".equals(id)) main.post(() -> playback.started(text.length() * 1000L / 15));
                          }

                          public void onRangeStart(String id, int start, int end, int frame) {
                            ttsAt = start; // Words as they're spoken: how far you heard.
                          }

                          public void onDone(String id) {
                            finished(true);
                          }

                          public void onError(String id) {
                            finished(false);
                          }

                          public void onStop(String id, boolean interrupted) {
                            if (!pausing) finished(false); // Paused: resume speaks the rest.
                          }
                        });
                    if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply") != TextToSpeech.SUCCESS) finished(false);
                  });
        });
  }

  /** Stops reading at once; the playback still hears it ended. */
  void stop() {
    pausing = false;
    MediaPlayer p;
    synchronized (this) {
      p = player;
    }
    if (p != null) {
      try {
        p.stop();
      } catch (IllegalStateException ignored) {
        /* Not started yet or already done. */
      }
      release();
    }
    if (tts != null) tts.stop();
    finished(false);
  }

  private synchronized void release() {
    if (player != null) {
      player.release();
      player = null;
    }
  }

  void shutdown() {
    stop();
    if (tts != null) tts.shutdown();
    tts = null;
  }
}
