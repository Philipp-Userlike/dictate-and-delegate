package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.media.AudioAttributes;
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

  interface Done {
    /** Reading ended, finished or stopped. */
    void done();
  }

  private final Context context;
  private MediaPlayer player;
  private TextToSpeech tts;
  private volatile boolean stopped;
  private Done current;

  /** Runs the pending done once, on the main thread. */
  private void finished() {
    Done d;
    synchronized (this) {
      d = current;
      current = null;
    }
    if (d != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(d::done);
  }

  Voice(Context context) {
    this.context = context.getApplicationContext();
  }

  /** The request for OpenRouter's speech endpoint. */
  static JSONObject body(Choice c, String text) throws Exception {
    return new JSONObject().put("model", c.model()).put("voice", c.voice()).put("input", text).put("response_format", "mp3");
  }

  /**
   * Reads text aloud; done runs on the main thread once it ends. Returns OpenRouter's generation ID
   * for the cost ("" for Android's voice or a failure). Network: not on the main thread.
   */
  String speak(Choice choice, String text, Done done) {
    stopped = false;
    synchronized (this) {
      current = done;
    }
    if (choice.model().isEmpty()) {
      speakAndroid(text);
      return "";
    }
    try {
      File mp3 = fetch(choice, text);
      if (stopped) {
        finished();
        return generation;
      }
      play(mp3);
      return generation;
    } catch (Exception ex) {
      speakAndroid(text); // OpenRouter failed: Android's voice instead.
      return "";
    }
  }

  private volatile String generation = "";

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

  private File fetch(Choice choice, String text) throws Exception {
    String key = new Vault(context).get(OpenRouter.KEY);
    HttpURLConnection c = (HttpURLConnection) new URL(OpenRouter.base() + "/audio/speech").openConnection();
    try {
      byte[] payload = body(choice, text).toString().getBytes(StandardCharsets.UTF_8);
      c.setConnectTimeout(15_000);
      c.setReadTimeout(60_000);
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
      generation = id == null ? "" : id;
      File f = new File(context.getCacheDir(), "reply.mp3");
      try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
        in.transferTo(out);
      }
      return f;
    } finally {
      c.disconnect();
    }
  }

  private void play(File mp3) throws IOException {
    MediaPlayer p = new MediaPlayer();
    p.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build());
    p.setDataSource(mp3.getPath());
    p.setOnCompletionListener(
        mp -> {
          release();
          finished();
        });
    p.setOnErrorListener(
        (mp, what, extra) -> {
          release();
          finished();
          return true;
        });
    p.prepare();
    synchronized (this) {
      player = p;
    }
    p.start();
  }

  private void speakAndroid(String text) {
    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    main.post(
        () -> {
          if (tts != null) tts.shutdown();
          tts =
              new TextToSpeech(
                  context,
                  status -> {
                    if (status != TextToSpeech.SUCCESS || stopped) {
                      finished();
                      return;
                    }
                    tts.setLanguage(Locale.getDefault());
                    tts.setOnUtteranceProgressListener(
                        new UtteranceProgressListener() {
                          public void onStart(String id) {}

                          public void onDone(String id) {
                            finished();
                          }

                          public void onError(String id) {
                            finished();
                          }

                          public void onStop(String id, boolean interrupted) {
                            finished();
                          }
                        });
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply");
                  });
        });
  }

  /** Stops reading at once; done still runs. */
  void stop() {
    stopped = true;
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
    finished();
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
