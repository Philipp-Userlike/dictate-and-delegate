package app.driftdictation;

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
 * (POST /audio/speech, MP3, or PCM wrapped as WAV, with the user's OpenRouter key), or Android's
 * own voice offline and as the fallback when OpenRouter fails. Replies play as call audio, so the
 * phone's echo cancellation takes them out of the microphone.
 */
final class Voice {
  /**
   * A choice in Settings → Models & providers → Text-to-speech: the OpenRouter model and voice
   * ("" when the model needs none), or Android's; format is what's asked for, "mp3" or "pcm" (for
   * the models that only send raw samples).
   */
  record Choice(String id, String name, String note, String model, String voice, String pricePerCharacter, String format) {
    Choice(String id, String name, String note, String model, String voice, String pricePerCharacter) {
      this(id, name, note, model, voice, pricePerCharacter, "mp3");
    }

    String label() {
      return name + " · " + note;
    }

    /** For the log: "fish-audio/drama-3 (933563…)", the model and the voice it was asked for. */
    String tag() {
      return model.isEmpty() ? "Android's voice" : voice.isEmpty() ? model : model + " (" + voice + ")";
    }

    /** OpenRouter's list price, for when the real cost can't be looked up. */
    java.math.BigDecimal perCharacter() {
      return new java.math.BigDecimal(pricePerCharacter);
    }
  }

  static final String MAI = "microsoft/mai-voice-2.1-flash", VOXTRAL = "mistralai/voxtral-mini-tts-3-0",
      HEYGEN = "heygen/heygen-voice-1-turbo", GEMINI = "google/gemini-3.8-flash-lite-tts", FISH = "fish-audio/drama-3";

  static final Choice KOKORO = new Choice("kokoro", "Kokoro", "cheapest", "hexgrad/kokoro-82m", "af_heart", "0.00000062"),
      ELEVEN = new Choice("eleven", "ElevenLabs Flash v2.5", "best voice", "elevenlabs/eleven-flash-v2.5", "george", "0.00004"),
      ANDROID = new Choice("android", "Android's voice", "offline, free", "", "", "0");

  /**
   * Prices are OpenRouter's per character; Gemini's is per token (audio out at $6 a million), about
   * $10 a million characters. Each voice answered once in about 0.5 to 2 s (October 2026).
   */
  static final Choice[] CHOICES = {
    KOKORO,
    // Fish takes a voice's reference ID (fish.audio); without one each reply had another speaker.
    new Choice("fish-drama", "Fish Audio Drama 3 · Sarah", "fast, cheap", FISH, "933563129e564b19a115bedd57b7406a", "0.000002"),
    new Choice("fish-friendly", "Fish Audio Drama 3 · Friendly", "fast, cheap", FISH, "b545c585f631496c914815291da4e893", "0.000002"),
    new Choice("fish-adrian", "Fish Audio Drama 3 · Adrian", "fast, cheap", FISH, "bf322df2096a46f18c579d0baa36f41d", "0.000002"),
    new Choice("fish-ethan", "Fish Audio Drama 3 · Ethan", "fast, cheap", FISH, "536d3a5e000945adb7038665781a4aca", "0.000002"),
    new Choice("gemini-kore", "Gemini Flash Lite TTS · Kore", "cheap", GEMINI, "Kore", "0.00001", "pcm"),
    new Choice("gemini-puck", "Gemini Flash Lite TTS · Puck", "cheap", GEMINI, "Puck", "0.00001", "pcm"),
    new Choice("gemini-charon", "Gemini Flash Lite TTS · Charon", "cheap", GEMINI, "Charon", "0.00001", "pcm"),
    new Choice("gemini-aoede", "Gemini Flash Lite TTS · Aoede", "cheap", GEMINI, "Aoede", "0.00001", "pcm"),
    new Choice("heygen-bram", "HeyGen Voice 1 Turbo · Bram", "cheap", HEYGEN, "bram", "0.000004", "pcm"),
    new Choice("heygen-nadine", "HeyGen Voice 1 Turbo · Nadine", "cheap", HEYGEN, "nadine", "0.000004", "pcm"),
    new Choice("heygen-lucian", "HeyGen Voice 1 Turbo · Lucian", "cheap", HEYGEN, "lucian", "0.000004", "pcm"),
    new Choice("heygen-tabitha", "HeyGen Voice 1 Turbo · Tabitha", "cheap", HEYGEN, "tabitha", "0.000004", "pcm"),
    new Choice("voxtral-paul", "Voxtral Mini TTS · Paul", "fast", VOXTRAL, "en_paul_neutral", "0.000013"),
    new Choice("voxtral-paul-cheerful", "Voxtral Mini TTS · Paul, cheerful", "fast", VOXTRAL, "en_paul_cheerful", "0.000013"),
    new Choice("voxtral-jane", "Voxtral Mini TTS · Jane (British)", "fast", VOXTRAL, "gb_jane_neutral", "0.000013"),
    new Choice("voxtral-oliver", "Voxtral Mini TTS · Oliver (British)", "fast", VOXTRAL, "gb_oliver_neutral", "0.000013"),
    new Choice("mai-harper", "MAI-Voice 2.1 Flash · Harper", "fastest", MAI, "en-US-Harper:MAI-Voice-2.1-Flash", "0.000015"),
    new Choice("mai-iris", "MAI-Voice 2.1 Flash · Iris", "fastest", MAI, "en-US-Iris:MAI-Voice-2.1-Flash", "0.000015"),
    new Choice("mai-jasper", "MAI-Voice 2.1 Flash · Jasper", "fastest", MAI, "en-US-Jasper:MAI-Voice-2.1-Flash", "0.000015"),
    new Choice("mai-ethan", "MAI-Voice 2.1 Flash · Ethan", "fastest", MAI, "en-US-Ethan:MAI-Voice-2.1-Flash", "0.000015"),
    ELEVEN,
    ANDROID
  };

  /** The hands-free voice until another is chosen: the cheapest. */
  static final Choice DEFAULT = KOKORO;

  static Choice choice(String id) {
    for (Choice c : CHOICES) if (c.id().equals(id)) return c;
    return DEFAULT;
  }

  /** How a reading goes: audio started (and how long it will take), then it ended. */
  interface Playback {
    void started(long durationMs);

    /** Ended: played to the end, or failed or stopped (played false). */
    void ended(boolean played);
  }

  /**
   * A reply ready to play: its audio (null: Android's voice reads the text), OpenRouter's
   * generation ID, and how it went: when the first byte came and when all of it (ms after asking,
   * -1 when it didn't), and why Android's voice reads it ("" when it doesn't).
   */
  /** how: which request delivered it ("", or that a second request was sent, and which won). */
  /** audio: an MP3 or WAV file. fallback: why there's none, its cause in a few words. */
  record Prepared(File audio, String generation, long firstByteMs, long allMs, String fallback, String how) {
    Prepared(File audio, String generation) {
      this(audio, generation, -1, -1, "", "");
    }
  }

  /** A voice model's request failed: why, in a few words (HTTP status and what it said). */
  static final class Failed extends IOException {
    Failed(String why) {
      super(why);
    }
  }

  /**
   * A voice request that hasn't delivered after this long gets a second one in parallel; the first
   * to deliver plays, the other is dropped. OpenRouter picks the provider itself (routing is ignored
   * for speech), and its slow answers are single stalls, so a second request cuts them off.
   */
  static final long SECOND_REQUEST_MS = 1000;

  /** One request for a reply's audio, running in its own thread. */
  private final class Attempt {
    final long[] first = {-1};
    final String[] generation = {""};
    final HttpURLConnection[] open = {null};
    final java.util.concurrent.FutureTask<File> task;

    Attempt(Choice choice, String text, long asked) {
      task = new java.util.concurrent.FutureTask<>(() -> fetch(choice, text, generation, first, open, asked));
      new Thread(task, "voice-fetch").start();
    }

    /** Given up: not downloaded on. */
    void drop() {
      HttpURLConnection c = open[0];
      if (c != null && !task.isDone()) c.disconnect();
      task.cancel(true);
    }
  }

  private final Context context;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private MediaPlayer player;
  private volatile TextToSpeech tts;
  private Playback current;
  /** The reply being read; for Android's voice, how far it got. */
  private volatile String text = "";
  private volatile int ttsAt;
  private int replies;
  /**
   * Android's voice: one engine for the whole call, started at once (it takes a moment to be
   * ready); what's spoken before then waits for it. Its callbacks only act on the utterance being
   * read now (utterance), so a stopped or earlier one can never end the next reply.
   */
  private boolean ttsReady, ttsFailed;
  private final java.util.List<Runnable> whenReady = new java.util.ArrayList<>();
  private volatile String utterance = "";
  private int utterances;
  /** Hears once how Android's voice started: "" ready, else why not (some phones have none). */
  volatile java.util.function.Consumer<String> androidStatus = s -> {};
  /** Android's voice couldn't start on this phone (no engine, or it failed). */
  volatile boolean androidUnavailable;

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
    main.post(this::startAndroid);
  }

  /** Starts Android's voice (on the main thread): English, on the replies' audio route. */
  private void startAndroid() {
    if (tts != null) return;
    tts =
        new TextToSpeech(
            context,
            status ->
                main.post(
                    () -> {
                      TextToSpeech t = tts;
                      if (t == null) return; // Shut down meanwhile.
                      if (status == TextToSpeech.SUCCESS) {
                        // The replies are English (whatever the phone's language is).
                        int lang = t.setLanguage(Locale.US);
                        if (lang < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.getDefault());
                        t.setAudioAttributes(attributes());
                        t.setOnUtteranceProgressListener(listener);
                        ttsReady = true;
                        androidUnavailable = false;
                        androidStatus.accept(
                            "ready: " + t.getDefaultEngine() + (lang < TextToSpeech.LANG_AVAILABLE ? ", no English voice (" + Locale.getDefault() + " instead)" : ", English"));
                      } else {
                        ttsFailed = true;
                        androidUnavailable = true;
                        String engine = t.getDefaultEngine();
                        androidStatus.accept("not available (" + (engine == null || engine.isEmpty() ? "no text-to-speech engine on this phone" : engine + " failed to start") + ")");
                      }
                      java.util.List<Runnable> waiting = new java.util.ArrayList<>(whenReady);
                      whenReady.clear();
                      for (Runnable r : waiting) r.run();
                    }));
  }

  /** Android's voice telling how the utterance being read now goes; others are ignored. */
  private final UtteranceProgressListener listener =
      new UtteranceProgressListener() {
        public void onStart(String id) {
          if (!id.equals(utterance) || !id.startsWith("reply")) return;
          Playback p;
          synchronized (Voice.this) {
            p = current;
          }
          String t = text;
          if (p != null) main.post(() -> p.started(t.length() * 1000L / 15));
        }

        public void onRangeStart(String id, int start, int end, int frame) {
          if (id.equals(utterance)) ttsAt = start; // Words as they're spoken: how far you heard.
        }

        public void onDone(String id) {
          if (id.equals(utterance)) finished(true);
        }

        public void onError(String id) {
          onError(id, -1);
        }

        public void onError(String id, int code) {
          if (!id.equals(utterance)) return;
          androidFailed("couldn't speak (error " + code + ")");
          finished(false);
        }

        public void onStop(String id, boolean interrupted) {
          if (id.equals(utterance)) finished(false);
        }
      };

  /** Android's voice failed on this phone: from now on the voice model is waited for instead. */
  private void androidFailed(String why) {
    if (!androidUnavailable) androidStatus.accept(why + ": the voice model is waited for from now on");
    androidUnavailable = true;
  }

  /** Runs r once Android's voice is ready or failed to start (on the main thread). */
  private void whenAndroidReady(Runnable r) {
    if (ttsReady) {
      r.run();
      return;
    }
    whenReady.add(r);
    if (ttsFailed && tts != null) { // It failed to start before: once more.
      tts.shutdown();
      tts = null;
      ttsFailed = false;
    }
    startAndroid();
  }

  /** The request for OpenRouter's speech endpoint. */
  static JSONObject body(Choice c, String text) throws Exception {
    JSONObject b = new JSONObject().put("model", c.model()).put("input", text).put("response_format", c.format());
    if (!c.voice().isEmpty()) b.put("voice", c.voice());
    return b;
  }

  /** "audio/pcm;rate=44100;channels=1" → {44100, 1}; null if it isn't PCM with a rate. */
  static int[] pcmFormat(String contentType) {
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("audio/pcm")) return null;
    int rate = 0, channels = 1;
    for (String part : contentType.split(";")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length < 2) continue;
      try {
        if (kv[0].trim().equalsIgnoreCase("rate")) rate = Integer.parseInt(kv[1].trim());
        else if (kv[0].trim().equalsIgnoreCase("channels")) channels = Integer.parseInt(kv[1].trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return rate <= 0 || channels <= 0 ? null : new int[] {rate, channels};
  }

  /** The 44-byte header that makes 16-bit little-endian PCM a WAV file of that many bytes. */
  static byte[] wavHeader(int rate, int channels, long pcmBytes) {
    java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    h.put(new byte[] {'R', 'I', 'F', 'F'})
        .putInt((int) (36 + pcmBytes))
        .put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) channels)
        .putInt(rate)
        .putInt(rate * channels * 2)
        .putShort((short) (channels * 2))
        .putShort((short) 16)
        .put(new byte[] {'d', 'a', 't', 'a'})
        .putInt((int) pcmBytes);
    return h.array();
  }

  /**
   * Why a request failed, in a few words: "HTTP 503: <what it said>", its JSON error message if
   * it sent one, cut to a line.
   */
  static String httpError(int status, String body) {
    String said = body == null ? "" : body.trim();
    try {
      JSONObject j = new JSONObject(said);
      JSONObject e = j.optJSONObject("error");
      String m = e != null ? e.optString("message") : j.optString("message");
      if (!m.isEmpty()) said = m;
    } catch (Exception notJson) {
      /* As it came. */
    }
    said = said.replaceAll("\\s+", " ");
    if (said.length() > 160) said = said.substring(0, 160) + "…";
    return "HTTP " + status + (said.isEmpty() ? "" : ": " + said);
  }

  /**
   * Gets a reply ready: fetches its audio from OpenRouter (network: not on the main thread). Never
   * throws: without audio (Android's voice chosen, or OpenRouter failed) Android's voice reads it.
   */
  Prepared prepare(Choice choice, String text) {
    return prepare(choice, text, 0);
  }

  /**
   * As above, but when the audio isn't all there within waitMs (0: no limit) the request is given
   * up and Android's voice reads the reply, so a slow voice model never keeps you waiting.
   */
  Prepared prepare(Choice choice, String text, long waitMs) {
    if (choice.model().isEmpty()) return new Prepared(null, "");
    long asked = android.os.SystemClock.elapsedRealtime();
    long deadline = waitMs > 0 ? asked + waitMs : Long.MAX_VALUE;
    Attempt a = new Attempt(choice, text, asked), b = null;
    int sent = 1;
    Throwable failed = null;
    try {
      while (true) {
        long now = android.os.SystemClock.elapsedRealtime();
        Attempt done = a != null && a.task.isDone() ? a : b != null && b.task.isDone() ? b : null;
        if (done != null) {
          Attempt other = done == a ? b : a;
          try {
            File mp3 = done.task.get();
            if (other != null) other.drop();
            String how = b == null ? "" : done == b ? "second request won (sent after " + SECOND_REQUEST_MS + " ms)" : "first request won (a second was sent after " + SECOND_REQUEST_MS + " ms)";
            return new Prepared(mp3, done.generation[0], done.first[0], now - asked, "", how);
          } catch (java.util.concurrent.ExecutionException ex) {
            failed = ex.getCause() == null ? ex : ex.getCause();
            // One request failed: the other may still deliver; if there's none yet, send one.
            if (done == a) a = null;
            else b = null;
            if (a == null && b == null) {
              if (now < deadline && sent < 3) {
                a = new Attempt(choice, text, asked);
                sent++;
              } else break;
            }
          }
        } else if (b == null && a != null && sent < 3 && now - asked >= SECOND_REQUEST_MS && now < deadline) {
          b = new Attempt(choice, text, asked);
          sent++;
        } else if (now >= deadline) {
          if (a != null) a.drop();
          if (b != null) b.drop();
          boolean answered = a != null && a.first[0] >= 0 || b != null && b.first[0] >= 0;
          return new Prepared(null, "", a == null ? -1 : a.first[0], -1,
              String.format(Locale.ROOT, answered ? "%1$s: its audio wasn't all there in %2$.1f s" : "%1$s: no answer in %2$.1f s", choice.name(), waitMs / 1000.0),
              b == null ? "" : "two requests");
        }
        Thread.sleep(25);
      }
    } catch (InterruptedException ex) {
      // The call ended: not downloaded on.
      if (a != null) a.drop();
      if (b != null) b.drop();
      Thread.currentThread().interrupt();
      failed = ex;
    }
    android.util.Log.w("Voice", "OpenRouter's voice failed, Android's reads instead", failed);
    return new Prepared(null, "", -1, -1, choice.name() + ": " + (failed instanceof Failed ? failed.getMessage() : String.valueOf(failed == null ? "failed" : failed.getMessage())), "");
  }

  /**
   * Opens the connection to OpenRouter before the first reply (in the background), so that reply
   * doesn't wait for it; later ones reuse it (keep-alive).
   */
  void warm() {
    new Thread(
            () -> {
              try {
                HttpURLConnection c = (HttpURLConnection) new URL(OpenRouter.base() + "/key").openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                c.setRequestProperty("Authorization", "Bearer " + new Vault(context).get(OpenRouter.KEY));
                c.getResponseCode();
                try (InputStream in = c.getResponseCode() >= 300 ? c.getErrorStream() : c.getInputStream()) {
                  if (in != null) in.readAllBytes(); // Read to the end: the connection stays open for reuse.
                }
              } catch (Exception ignored) {
                /* Only a head start. */
              }
            },
            "voice-warm")
        .start();
  }

  /** Plays a prepared reply; the playback hears when audio starts and when it ends. */
  void play(Prepared prepared, String text, Playback playback) {
    synchronized (this) {
      current = playback;
    }
    this.text = text;
    ttsAt = 0;
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
              if (prepared.audio() == null) speakAndroid(text, playback);
              else
                main.post(
                    () -> {
                      synchronized (this) {
                        if (current != playback) return; // Stopped meanwhile.
                      }
                      try {
                        play(prepared.audio(), playback);
                      } catch (Exception ex) {
                        release();
                        prepared.audio().delete();
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

  /**
   * The replies' audio: call audio, so the phone's echo cancellation removes it from the
   * microphone (it only takes out what plays on the communication path) and you can talk over a
   * reply. Also the hands-free media session's, so the volume keys change its volume (the call
   * volume). The sounds play on it too.
   */
  static AudioAttributes attributes() {
    return new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
    return Math.min(text.length(), ttsAt);
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
    Generation g = generation(context, generation);
    return g == null ? null : g.cost();
  }

  /** What OpenRouter says about a generation: its cost and which provider served it. */
  record Generation(java.math.BigDecimal cost, String provider, long latencyMs) {}

  /** The generation's details (GET /generation), or null if OpenRouter can't say yet. */
  static Generation generation(Context context, String generation) {
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
            if (d != null && d.has("total_cost"))
              return new Generation(new java.math.BigDecimal(d.get("total_cost").toString()), d.optString("provider_name"), d.optLong("latency", -1));
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

  /**
   * Fetches a reply's audio; first[0] gets when its first byte came (ms after asked). The
   * connection isn't closed after a whole answer, so the next reply reuses it (keep-alive).
   */
  private File fetch(Choice choice, String text, String[] generation, long[] first, HttpURLConnection[] open, long asked) throws Exception {
    String key = new Vault(context).get(OpenRouter.KEY);
    HttpURLConnection c = (HttpURLConnection) new URL(OpenRouter.base() + "/audio/speech").openConnection();
    open[0] = c;
    boolean whole = false;
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
      int status;
      try {
        status = c.getResponseCode();
      } catch (java.net.SocketTimeoutException ex) {
        throw new Failed("no answer in " + (android.os.SystemClock.elapsedRealtime() - asked) / 1000 + " s");
      }
      if (status >= 300) {
        String said = "";
        try (InputStream err = c.getErrorStream()) {
          if (err != null) said = new String(err.readNBytes(4096), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
          /* The status says enough. */
        }
        throw new Failed(httpError(status, said));
      }
      first[0] = android.os.SystemClock.elapsedRealtime() - asked;
      String id = c.getHeaderField("X-Generation-Id");
      generation[0] = id == null ? "" : id;
      // Raw samples (some models send nothing else): wrapped as WAV, with the rate they came at.
      int[] pcm = pcmFormat(c.getContentType());
      // Each reply its own file: several may be ready while one plays.
      File f;
      synchronized (this) {
        f = new File(context.getCacheDir(), "reply-" + (++replies % 8) + (pcm == null ? ".mp3" : ".wav"));
      }
      try (InputStream in = c.getInputStream(); java.io.RandomAccessFile out = new java.io.RandomAccessFile(f, "rw")) {
        out.setLength(0);
        if (pcm != null) out.write(wavHeader(pcm[0], pcm[1], 0));
        byte[] buffer = new byte[16 * 1024];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        if (pcm != null) { // The sizes, now that they're known.
          out.seek(0);
          out.write(wavHeader(pcm[0], pcm[1], out.length() - 44));
        }
      } catch (java.net.SocketTimeoutException ex) {
        throw new Failed("its audio stopped coming after " + (android.os.SystemClock.elapsedRealtime() - asked) / 1000 + " s");
      }
      if (f.length() <= (pcm == null ? 0 : 44)) throw new Failed("it sent no audio");
      whole = true;
      return f;
    } finally {
      if (!whole) c.disconnect();
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
        () ->
            whenAndroidReady(
                () -> {
                  synchronized (this) {
                    if (current != playback) return; // Stopped meanwhile.
                  }
                  TextToSpeech t = tts;
                  if (!ttsReady || t == null) {
                    android.util.Log.w("Voice", "Android's voice isn't available");
                    finished(false);
                    return;
                  }
                  String id = "reply-" + (++utterances);
                  utterance = id;
                  if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                    androidFailed("refused to speak");
                    finished(false);
                  }
                }));
  }

  /** Stops reading at once; the playback still hears it ended. */
  void stop() {
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
    utterance = ""; // Whatever the engine says about it now no longer counts.
    TextToSpeech t = tts;
    if (t != null) t.stop();
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
    main.post(
        () -> {
          if (tts != null) tts.shutdown();
          tts = null;
          ttsReady = false;
        });
  }
}
