package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Hands-free mode as a foreground service: the microphone (with the phone's echo cancelling, so a
 * reply read aloud isn't heard as you), a HandsFreeSession, the chief's spoken replies from ntfy,
 * and the voice. Send, stop reading and end work from the screen, the notification (also on the
 * lock screen) and the headset or car media button: while a reply is read it stops reading,
 * otherwise it sends.
 */
public final class HandsFreeService extends Service {
  static final String START = "start", SEND = "send", END = "end";

  /** START extra: a PCM file with what was said before (a dictation switched to hands-free). */
  static final String SEED = "seed";

  /** Extras of START: where the dictation panel was, so the call panel takes its place. */
  static final String AT_X = "atX", AT_Y = "atY";
  static final String CHANNEL = "hands_free";

  static volatile HandsFreeService instance;

  /** For the device tests: a session without the microphone, fed by the test. */
  static volatile boolean noMicrophone;

  private final Handler main = new Handler(Looper.getMainLooper());
  private HandsFreeSession session;
  private AudioRecord recorder;
  private Thread reader;
  private volatile boolean running;
  private ChiefText.Replies replies;
  private Voice voice;
  private MediaSession media;
  private HandsFreePanel panel;
  private PowerManager.WakeLock wake;
  private BigDecimal cost = BigDecimal.ZERO;
  /** Everything this call cost on OpenRouter: transcription and the voice. */
  static volatile BigDecimal callCost = BigDecimal.ZERO;
  private boolean unpriced;
  private double seconds;

  static HandsFreeSession session() {
    HandsFreeService s = instance;
    return s == null ? null : s.session;
  }

  /** Takes the call panel off while a file picker is open, and back (see DictationService.picking). */
  static void panelHidden(boolean hidden) {
    HandsFreeService s = instance;
    if (s == null) return;
    s.main.post(
        () -> {
          if (s.panel == null) return;
          try {
            if (hidden) s.panel.hide();
            else s.panel.show();
          } catch (RuntimeException ignored) {
            /* The overlay permission went meanwhile. */
          }
        });
  }

  /** Whether the call panel shows every button whole on screen (for the device tests). */
  static boolean panelFits() {
    HandsFreeService s = instance;
    return s != null && s.panel != null && s.panel.fits();
  }

  /** Where the call panel is (x, y), or null without one (for the device tests). */
  static int[] panelPosition() {
    HandsFreeService s = instance;
    HandsFreePanel p = s == null ? null : s.panel;
    return p == null ? null : p.position();
  }

  static void send(Context context, String action) {
    Intent i = new Intent(context, HandsFreeService.class).setAction(action);
    if (START.equals(action)) context.startForegroundService(i);
    else if (instance != null) context.startService(i);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    I18n.init(this);
    instance = this;
    NotificationChannel channel =
        new NotificationChannel(CHANNEL, tr("Hands-free mode"), NotificationManager.IMPORTANCE_LOW);
    channel.setSound(null, null);
    channel.enableVibration(false);
    channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? START : String.valueOf(intent.getAction());
    switch (action) {
      case START ->
          start(
              intent == null ? null : intent.getStringExtra(SEED),
              intent == null ? -1 : intent.getIntExtra(AT_X, -1),
              intent == null ? -1 : intent.getIntExtra(AT_Y, -1));
      case SEND -> {
        if (session != null) session.sendNow();
      }
      case END -> finish();
      default -> {}
    }
    return START_NOT_STICKY;
  }

  private void start(String seed, int atX, int atY) {
    startForeground(
        4,
        notification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    if (session != null) return;
    SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
    BbBridge bb = new BbBridge(this);
    voice = new Voice(this);
    Voice.Choice choice = Voice.choice(prefs.getString(HandsFree.VOICE, Voice.KOKORO.id()));
    Profile profile = new Profile(prefs);
    String model = profile.fixedModel();
    session =
        new HandsFreeSession(
            HandsFree.pauseMs(prefs),
            HandsFree.silenceSendMs(prefs),
            HandsFree.takeMs(prefs),
            HandsFree.gate(prefs),
            HandsFree.LEVEL,
            HandsFree.words(prefs, HandsFree.SEND_WORDS, HandsFree.DEFAULT_SEND),
            HandsFree.list(HandsFree.endSetting(prefs)),
            wav -> {
              OpenRouter.Result r = new OpenRouter(this).transcribe(wav, model, profile.hintWords());
              seconds += (wav.length - 44) / (double) AudioFiles.BYTES_PER_SECOND;
              if (r.cost() == null) unpriced = true;
              else {
                cost = cost.add(r.cost());
                callCost = callCost.add(r.cost());
              }
              return r.text();
            },
            said -> {
              String request = ChiefText.requestId();
              bb.sendHandsFree(bb.chiefId(), said, request);
              keepNote(said, request, model);
              return request;
            },
            new HandsFreeSession.Speaker() {
              public Object prepare(String request, String text) {
                Voice.Choice c = Voice.choice(prefs.getString(HandsFree.VOICE, Voice.KOKORO.id()));
                long asked = android.os.SystemClock.elapsedRealtime();
                Voice.Prepared p = voice.prepare(c, text);
                long ms = android.os.SystemClock.elapsedRealtime() - asked;
                if (p.mp3() != null) chargeVoice(request, c, text, p.generation(), ms);
                else if (!c.model().isEmpty()) event("voice " + c.model() + " failed: Android's voice reads " + request);
                return p;
              }

              public void play(String request, String text, Object prepared, HandsFreeSession.Playback playback) {
                voice.play(
                    prepared instanceof Voice.Prepared p ? p : new Voice.Prepared(null, ""),
                    text,
                    new Voice.Playback() {
                      public void started(long durationMs) {
                        playback.started(durationMs);
                      }

                      public void ended(boolean played) {
                        playback.ended(played);
                      }
                    });
              }

              public boolean playing() {
                return voice.playing();
              }

              public void pause() {
                voice.pause();
              }

              public void resume() {
                voice.resume();
              }

              public int heardChars() {
                return voice.heardChars();
              }

              public void stop() {
                voice.stop();
              }
            },
            () -> main.post(this::changed));
    session.onEvent(this::event);
    callCost = BigDecimal.ZERO;
    callId = UUID.randomUUID().toString();
    event(
        String.format(
            java.util.Locale.ROOT,
            "started · send %s · end %s · voice %s · pause %d ms · take %d ms · gate ×%.1f",
            session.sendWords,
            session.endWords,
            choice.model(),
            HandsFree.pauseMs(prefs),
            HandsFree.takeMs(prefs),
            HandsFree.gate(prefs)));
    signal(bb, HandsFree.CALL_STARTED);
    if (seed != null) {
      try {
        java.io.File f = new java.io.File(seed);
        byte[] pcm = java.nio.file.Files.readAllBytes(f.toPath());
        f.delete();
        session.seed(pcm);
      } catch (Exception ex) {
        event("couldn't take over the dictation: " + ex.getMessage());
      }
    }
    try {
      replies =
          new ChiefText.Replies(
              bb.ntfyServer(),
              bb.ntfyTopic(),
              System.currentTimeMillis() / 1000 - 5,
              request -> session != null && session.ours(request),
              (request, text) -> {
                HandsFreeSession s = session;
                if (s != null) s.reply(request, text);
              });
      replies.start();
    } catch (Exception ex) {
      /* No topic: replies can't be read aloud; messages still go. */
    }
    startMedia();
    wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dictate & Delegate:hands-free");
    wake.setReferenceCounted(false);
    wake.acquire(4 * 60 * 60_000L);
    if (!noMicrophone) startMicrophone();
    // The call floats over other apps (with the overlay permission the dictation panel uses too).
    if (android.provider.Settings.canDrawOverlays(this)) {
      panel =
          new HandsFreePanel(
              this,
              new HandsFreePanel.Actions() {
                public void open() {
                  startActivity(
                      new Intent(HandsFreeService.this, MainActivity.class)
                          .putExtra("open", "handsfree")
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                }

                public void send() {
                  HandsFreeSession s = session;
                  if (s != null) s.sendNow();
                }

                public void end() {
                  finish();
                }
              },
              atX,
              atY);
      try {
        panel.show();
      } catch (RuntimeException ex) {
        panel = null;
      }
    }
    main.post(ticker);
    changed();
  }

  @SuppressLint("MissingPermission") // Checked right here.
  private void startMicrophone() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      finish();
      return;
    }
    int minimum =
        AudioRecord.getMinBufferSize(AudioFiles.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    // VOICE_COMMUNICATION: the phone cancels its own speaker's sound, so a reply read aloud
    // doesn't count as speech.
    recorder =
        new AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioFiles.RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(minimum * 2, 8192));
    if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
      recorder.release();
      recorder = null;
      finish();
      return;
    }
    if (AcousticEchoCanceler.isAvailable()) {
      AcousticEchoCanceler aec = AcousticEchoCanceler.create(recorder.getAudioSessionId());
      if (aec != null) aec.setEnabled(true);
    }
    recorder.startRecording();
    running = true;
    reader =
        new Thread(
            () -> {
              byte[] frame = new byte[AudioFiles.RATE * 2 * HandsFree.Segmenter.FRAME_MS / 1000];
              while (running) {
                int n = 0;
                while (running && n < frame.length) {
                  int r = recorder.read(frame, n, frame.length - n);
                  if (r <= 0) break;
                  n += r;
                }
                if (n == frame.length) {
                  HandsFreeSession s = session;
                  if (s != null) s.frame(frame);
                }
              }
            },
            "hands-free-mic");
    reader.start();
  }

  /** The headset or car button: stop reading while a reply is read, otherwise send. */
  private void startMedia() {
    media = new MediaSession(this, "Dictate & Delegate hands-free");
    media.setCallback(
        new MediaSession.Callback() {
          @Override
          public boolean onMediaButtonEvent(Intent intent) {
            KeyEvent e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
            if (e == null || e.getAction() != KeyEvent.ACTION_UP) return true;
            mediaButton();
            return true;
          }

          @Override
          public void onPlay() {
            mediaButton();
          }

          @Override
          public void onPause() {
            mediaButton();
          }
        });
    media.setPlaybackState(
        new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0, 1)
            .build());
    media.setActive(true);
  }

  /** The headset or car button sends; while a reply is read it does nothing (no interrupting). */
  void mediaButton() {
    HandsFreeSession s = session;
    if (s != null && s.state() == HandsFreeSession.State.LISTENING) s.sendNow();
  }

  private final Runnable ticker =
      new Runnable() {
        public void run() {
          HandsFreeSession s = session;
          if (s == null) return;
          s.tick();
          if (panel != null) panel.render(s.phase());
          main.postDelayed(this, 1000);
        }
      };

  /**
   * What reading a reply cost, as OpenRouter reports it for its generation (looked up after it's
   * done), else at the model's price per character: added to the note of the message it answers.
   */
  private void chargeVoice(String request, Voice.Choice c, String text, String generation, long ms) {
    String call = callId;
    new Thread(
            () -> {
              BigDecimal price = Voice.cost(this, generation);
              boolean exact = price != null;
              if (price == null) price = c.perCharacter().multiply(BigDecimal.valueOf(text.length()));
              // Looked up a few seconds later: only this call's total, not the next one's.
              if (call.equals(callId)) callCost = callCost.add(price);
              event(String.format(java.util.Locale.ROOT, "voice %s: %d characters, $%s%s", c.model(), text.length(), price.stripTrailingZeros().toPlainString(), exact ? "" : " (at list price)"));
              BigDecimal charged = price;
              try {
                DictationService.updateByRequest(
                    this,
                    request,
                    e -> {
                      e.charge(charged);
                      e.addCost("voiceCost", charged);
                      e.json.put("voiceModel", c.model());
                      e.step("voice", c.model(), ms, charged, null);
                    });
              } catch (Exception ex) {
                event("voice cost not kept: " + ex.getMessage());
              }
              main.post(this::changed);
            },
            "voice-cost")
        .start();
  }

  /**
   * Tells the chief a call started or ended, so it knows whether spoken messages are heard. In the
   * background; returns the thread doing it, or null without a chief.
   */
  private Thread signal(BbBridge bb, String line) {
    return signal(bb, line, null);
  }

  /** As above, after the messages still on their way in that session are delivered (in order). */
  private Thread signal(BbBridge bb, String line, HandsFreeSession after) {
    String chief = bb.chiefId();
    if (chief.isEmpty()) return null;
    Thread t =
        new Thread(
            () -> {
              if (after != null && !after.awaitDelivered(10_000)) event("a message was still on its way when the call ended");
              try {
                bb.send(chief, line);
                event("told the chief: " + line);
              } catch (Exception ex) {
                event("couldn't tell the chief " + line + ": " + ex.getMessage());
              }
            },
            "call-signal");
    t.start();
    return t;
  }

  // ------------------------------------------------------------------ the log

  /** The last events of hands-free mode (Settings → Hands-free), to see what went wrong in a turn. */
  static java.io.File logFile(Context context) {
    return new java.io.File(context.getFilesDir(), "handsfree.log");
  }

  static String log(Context context) {
    try {
      return new String(java.nio.file.Files.readAllBytes(logFile(context).toPath()), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception ex) {
      return "";
    }
  }

  private final java.util.List<String> sinceLastNote = new java.util.ArrayList<>();

  /**
   * This call's ID: its messages' notes carry it, so the notes list shows the call as one entry.
   * Static like callCost: a late voice cost from an earlier call (another instance) mustn't count.
   */
  private static volatile String callId = "";

  synchronized void event(String e) {
    String line =
        new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + " " + e;
    sinceLastNote.add(line);
    try {
      java.util.List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(log(this).split("\n")));
      lines.removeIf(String::isEmpty);
      lines.add(line);
      while (lines.size() > 300) lines.remove(0);
      java.nio.file.Files.write(logFile(this).toPath(), (String.join("\n", lines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception ignored) {
      /* The log is a help, not a must. */
    }
  }

  /** Each message is kept as a note, with its transcription cost, like a dictation sent to the chief. */
  private void keepNote(String said, String request, String model) throws Exception {
    BbBridge bb = new BbBridge(this);
    HistoryEntry e = HistoryEntry.create(UUID.randomUUID().toString(), model, seconds);
    e.json
        .put("text", said)
        .put("raw", said)
        .put("original", said)
        .put("status", "complete")
        .put("requests", 1)
        .put("handsFree", true)
        .put("call", callId)
        .put("route", "chief")
        .put("chiefThread", bb.chiefId())
        .put("chiefLink", bb.threadLink(bb.chiefId()))
        .put("chiefSent", System.currentTimeMillis())
        .put("chiefRequest", request);
    synchronized (this) {
      // What happened in this turn, for a failed one: Activity shows the note, the log its events.
      e.json.put("handsFreeLog", new org.json.JSONArray(sinceLastNote));
      sinceLastNote.clear();
    }
    e.charge(unpriced ? null : cost);
    e.step("speech", model, 0, unpriced ? null : cost, null);
    e.step(DictationService.CHIEF_STEP, DictationService.CHIEF_TARGET, 0, null, null);
    try (HistoryStore h = new HistoryStore(this)) {
      h.save(e);
    }
    getSharedPreferences("settings", MODE_PRIVATE).edit().putLong("historyRevision", System.nanoTime()).apply();
    cost = BigDecimal.ZERO;
    unpriced = false;
    seconds = 0;
  }

  private void changed() {
    HandsFreeSession s = session;
    if (s != null && s.state() == HandsFreeSession.State.ENDED) {
      finish();
      return;
    }
    getSystemService(NotificationManager.class).notify(4, notification());
    if (panel != null && s != null) panel.render(s.phase());
  }

  /** The four things the screen and the notification say. */
  /** Send now is offered while you can talk and nothing is on its way. */
  static boolean canSend(HandsFreeSession.Phase p) {
    return p == HandsFreeSession.Phase.LISTENING || p == HandsFreeSession.Phase.WORKING;
  }

  static String phaseLabel(HandsFreeSession.Phase p) {
    return switch (p) {
      case READING -> tr("Reading reply");
      case SENDING -> tr("Sending…");
      // You can talk while the chief works: it says listening first.
      case WORKING -> tr("Listening · Chief is working…");
      default -> tr("Listening");
    };
  }

  private PendingIntent action(String a, int code) {
    return PendingIntent.getService(
        this, code, new Intent(this, HandsFreeService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE);
  }

  private Notification notification() {
    HandsFreeSession s = session;
    boolean reading = s != null && s.state() == HandsFreeSession.State.READING;
    String text =
        s == null
            ? tr("Starting…")
            : phaseLabel(s.phase()) + (s.pending().isEmpty() || reading ? "" : "\n" + s.pending());
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class).putExtra("open", "handsfree").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder b =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(tr("Hands-free with your chief"))
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open);
    // Only what fits now: while listening, Send now; a reply is stopped by saying "stop" or the
    // headset button.
    if (s != null && HandsFreeService.canSend(s.phase()))
      b.addAction(new Notification.Action.Builder(null, tr("Send now"), action(SEND, 1)).build());
    b.addAction(new Notification.Action.Builder(null, tr("End"), action(END, 3)).build());
    return b.build();
  }

  private void finish() {
    running = false;
    HandsFreeSession s = session;
    session = null;
    if (s != null) s.end();
    if (recorder != null) {
      try {
        recorder.stop();
      } catch (IllegalStateException ignored) {
        /* Never started. */
      }
      recorder.release();
      recorder = null;
    }
    event("call ended · $" + callCost.stripTrailingZeros().toPlainString() + " on OpenRouter");
    Thread ended = s == null ? null : signal(new BbBridge(this), HandsFree.CALL_ENDED, s);
    if (replies != null) replies.finish();
    if (voice != null) voice.shutdown();
    if (media != null) media.release();
    if (panel != null) {
      panel.hide();
      panel = null;
    }
    if (wake != null && wake.isHeld()) wake.release();
    main.removeCallbacks(ticker);
    if (ended == null) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return;
    }
    // Stays a foreground service until the chief knows the call ended, so the process isn't
    // frozen or killed with the message unsent (at most 15 s, messages still on their way first).
    new Thread(
            () -> {
              try {
                ended.join(15_000);
              } catch (InterruptedException ignored) {
                /* Stop now. */
              }
              main.post(
                  () -> {
                    if (session != null) return; // A new call started meanwhile.
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                  });
            },
            "call-end")
        .start();
  }

  @Override
  public void onDestroy() {
    if (session != null) finish();
    instance = null;
    super.onDestroy();
  }
}
