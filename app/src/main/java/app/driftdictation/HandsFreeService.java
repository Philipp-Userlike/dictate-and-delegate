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
  static final String START = "start", SEND = "send", STOP_READING = "stopReading", END = "end";
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
  private PowerManager.WakeLock wake;
  private BigDecimal cost = BigDecimal.ZERO;
  private boolean unpriced;
  private double seconds;

  static HandsFreeSession session() {
    HandsFreeService s = instance;
    return s == null ? null : s.session;
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
      case START -> start();
      case SEND -> {
        if (session != null) session.sendNow();
      }
      case STOP_READING -> {
        if (session != null) session.stopReading();
      }
      case END -> finish();
      default -> {}
    }
    return START_NOT_STICKY;
  }

  private void start() {
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
            HandsFree.words(prefs, HandsFree.SEND_WORDS, HandsFree.DEFAULT_SEND),
            HandsFree.words(prefs, HandsFree.END_WORDS, HandsFree.DEFAULT_END),
            HandsFree.words(prefs, HandsFree.STOP_WORDS, HandsFree.DEFAULT_STOP),
            wav -> {
              OpenRouter.Result r = new OpenRouter(this).transcribe(wav, model, profile.hintWords());
              seconds += (wav.length - 44) / (double) AudioFiles.BYTES_PER_SECOND;
              if (r.cost() == null) unpriced = true;
              else cost = cost.add(r.cost());
              return r.text();
            },
            said -> {
              String request = ChiefText.requestId();
              bb.sendHandsFree(bb.chiefId(), said, request);
              keepNote(said, request, model);
              return request;
            },
            new HandsFreeSession.Speaker() {
              public void speak(String text, Runnable done) {
                Voice.Choice c = Voice.choice(prefs.getString(HandsFree.VOICE, Voice.KOKORO.id()));
                new Thread(() -> voice.speak(c, text, done::run), "voice").start();
              }

              public void stop() {
                voice.stop();
              }
            },
            () -> main.post(this::changed));
    try {
      replies =
          new ChiefText.Replies(
              bb.ntfyServer(),
              bb.ntfyTopic(),
              System.currentTimeMillis() / 1000 - 5,
              request -> session != null && session.ours(request),
              (request, text) -> {
                HandsFreeSession s = session;
                if (s != null) s.reply(text);
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

  void mediaButton() {
    HandsFreeSession s = session;
    if (s == null) return;
    if (s.state() == HandsFreeSession.State.READING) s.stopReading();
    else s.sendNow();
  }

  private final Runnable ticker =
      new Runnable() {
        public void run() {
          HandsFreeSession s = session;
          if (s == null) return;
          s.tick();
          main.postDelayed(this, 1000);
        }
      };

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
        .put("route", "chief")
        .put("chiefThread", bb.chiefId())
        .put("chiefLink", bb.threadLink(bb.chiefId()))
        .put("chiefSent", System.currentTimeMillis())
        .put("chiefRequest", request);
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
            : reading
                ? tr("Reading your chief's reply")
                : s.pending().isEmpty() ? tr("Listening. Say your send word when you're done.") : s.pending();
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
    if (reading)
      b.addAction(new Notification.Action.Builder(null, tr("Stop reading"), action(STOP_READING, 2)).build());
    else b.addAction(new Notification.Action.Builder(null, tr("Send"), action(SEND, 1)).build());
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
    if (replies != null) replies.finish();
    if (voice != null) voice.shutdown();
    if (media != null) media.release();
    if (wake != null && wake.isHeld()) wake.release();
    main.removeCallbacks(ticker);
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    if (session != null) finish();
    instance = null;
    super.onDestroy();
  }
}
