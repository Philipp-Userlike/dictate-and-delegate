package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class DictationService extends Service {
  static final String ENABLE = "enable",
      RECORD = "record",
      STOP = "stop",
      RETRY = "retry",
      HIDE = "hide",
      DISCARD = "discard",
      CONFIGURE = "configure",
      COMPARE = "compare";
  static final String READY = "ready",
      RECORDING = "recording",
      STOPPING = "stopping",
      UPLOADING = "uploading",
      COPIED = "copied",
      ERROR = "error",
      EMPTY = "empty";
  static volatile DictationService instance;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private String state = READY, detail = tr("Tap the bubble to record");
  private volatile boolean alive;
  private long started;
  private PcmRecorder recorder;
  private PowerManager.WakeLock wake;
  private OverlayIcon bubble;
  /** Where the last tap on the floating button landed, to tell the 📎 from Stop. */
  private float touchX;
  private WindowManager windows;
  private WindowManager.LayoutParams layout;
  private Vault vault;
  private SharedPreferences prefs;

  static File audio(Context context) {
    return new File(context.getNoBackupFilesDir(), "pending.pcm");
  }

  static boolean busy() {
    return instance != null && instance.isBusy();
  }

  private boolean isBusy() {
    return RECORDING.equals(state) || STOPPING.equals(state) || UPLOADING.equals(state);
  }

  static void send(Context c, String action) {
    Intent intent = new Intent(c, DictationService.class).setAction(action);
    if (instance == null) c.startForegroundService(intent);
    else c.startService(intent);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    alive = true;
    instance = this;
    I18n.init(this);
    vault = new Vault(this);
    prefs = getSharedPreferences("settings", MODE_PRIVATE);
    QuietNotifications.initialize(this);
    if (audio(this).exists()) {
      state = ERROR;
      detail = tr("Saved recording found. Open Drift to retry or discard.");
      main.postDelayed(finishFeedback, 3500);
    }
    windows = getSystemService(WindowManager.class);
    wake =
        getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Drift:dictation");
    wake.setReferenceCounted(false);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    try {
      foreground(RECORDING.equals(state) || STOPPING.equals(state));
      String action = intent == null ? ENABLE : intent.getAction();
      if (ENABLE.equals(action)) {
        prefs.edit().putString("bubbleMode", "always").apply();
        update();
      } else if (RECORD.equals(action)) startRecording();
      else if (STOP.equals(action)) stopRecording(true);
      else if (COMPARE.equals(action) && !isBusy()) {
        if (audio(this).exists())
          throw new IOException(tr("Finish or discard the pending recording first."));
        String id = intent.getStringExtra("recordingId"), model = intent.getStringExtra("model");
        if (model == null || model.isBlank()) throw new IOException(tr("Choose a model first."));
        requireKey(this);
        File saved = new AudioArchive(this).get(id);
        if (saved == null)
          throw new IOException(tr("Audio has expired or was deleted. Text is still saved."));
        java.nio.file.Files.copy(saved.toPath(), audio(this).toPath());
        String session = intent.getStringExtra("sessionId");
        prefs
            .edit()
            .putString("recordingId", id)
            .putString("sessionModel", model)
            .putString(
                "sessionId",
                session == null || session.isBlank()
                    ? java.util.UUID.randomUUID().toString()
                    : session)
            .putLong("sessionCreated", System.currentTimeMillis())
            .commit();
        upload();
      } else if (RETRY.equals(action) && !isBusy()) upload();
      else if (DISCARD.equals(action) && !isBusy()) {
        clearPending();
        transition(READY, tr("Tap the bubble to record"));
      } else if (HIDE.equals(action)) {
        prefs.edit().putString("bubbleMode", "never").apply();
        update();
        if (!isBusy()) finishIdle();
      } else if (CONFIGURE.equals(action)) {
        update();
        if (!isBusy() && !"always".equals(mode())) finishIdle();
      }
    } catch (Exception ex) {
      transition(ERROR, friendly(ex));
    } finally {
      ResultReceiver callback =
          intent == null ? null : intent.getParcelableExtra("started", ResultReceiver.class);
      if (callback != null) callback.send(0, Bundle.EMPTY);
    }
    return START_NOT_STICKY; // Never silently restart capture after force-stop, crash or reboot.
  }

  private void foreground(boolean microphone) {
    startForeground(
        1,
        notification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            | (microphone ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0));
  }

  private Notification notification() {
    // Android requires a foreground-service notification object even without posting permission.
    return new Notification.Builder(this, QuietNotifications.CHANNEL)
        .setSmallIcon(app.driftdictation.R.drawable.ic_mic)
        .setContentTitle(tr("Drift is running"))
        .setContentText(tr("Use the bubble or open Drift to control dictation."))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build();
  }

  /**
   * After a note is copied, the floating button turns into "Ready to paste" for a moment. Without
   * it, and for problems, a toast says the same where the user is.
   */
  private void transition(String next, String message) {
    transition(next, message, false);
  }

  private void transition(String next, String message, boolean alsoToast) {
    main.removeCallbacks(finishFeedback);
    state = next;
    detail = message;
    update();
    if ((bubble == null || alsoToast)
        && (COPIED.equals(next) || ERROR.equals(next) || EMPTY.equals(next)))
      Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    if (COPIED.equals(next)) main.postDelayed(finishFeedback, 6000);
    else if (ERROR.equals(next) || EMPTY.equals(next)) main.postDelayed(finishFeedback, 3500);
    else if (READY.equals(next) && !"always".equals(mode())) finishIdle();
  }

  private void update() {
    if (!alive) return;
    prefs.edit().putString("state", state).putString("detail", detail).apply();
    syncBubble();
    DictationTile.refresh(this);
  }

  private String mode() {
    return prefs.getString("bubbleMode", "recording");
  }

  private final Runnable finishFeedback =
      () -> {
        if (ERROR.equals(state)) {
          finishIdle();
          return;
        }
        if (COPIED.equals(state) || EMPTY.equals(state)) {
          if ("always".equals(mode())) transition(READY, tr("Tap the microphone to record"));
          else finishIdle();
        }
      };

  private void finishIdle() {
    if (isBusy()) return;
    removeBubble();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  private void removeBubble() {
    if (bubble != null) {
      windows.removeView(bubble);
      bubble = null;
    }
  }

  private void endFeedback() {
    main.removeCallbacks(finishFeedback);
    finishFeedback.run();
  }

  private void syncBubble() {
    boolean visible =
        "always".equals(mode()) || ("recording".equals(mode()) && !READY.equals(state));
    if (visible && Settings.canDrawOverlays(this)) showBubble();
    else removeBubble();
    renderBubble();
  }

  private final Runnable ticker =
      new Runnable() {
        public void run() {
          if (!alive || !RECORDING.equals(state)) return;
          renderBubble();
          main.postDelayed(this, 1000);
        }
      };

  private void showBubble() {
    if (bubble != null || !Settings.canDrawOverlays(this)) return;
    bubble = new OverlayIcon(this);
    layout =
        new WindowManager.LayoutParams(
            dp(48),
            dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP | Gravity.LEFT;
    layout.x = prefs.getInt("bubbleX", 16);
    layout.y = prefs.getInt("bubbleY", 220);
    clampPosition();
    bubble.setOnClickListener(
        v -> {
          if (RECORDING.equals(state) && touchX < dp(OverlayIcon.ATTACH_DP)) {
            // The 📎: add context while recording goes on.
            startActivity(
                new Intent(this, AttachActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return;
          }
          if (RECORDING.equals(state)) stopRecording(true);
          else if (isBusy()) return;
          else if (COPIED.equals(state)) endFeedback(); // Tapping "Ready to paste" hides it.
          else if (audio(this).exists() || ERROR.equals(state)) {
            finishIdle();
            startActivity(
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
          } else
            startActivity(
                new Intent(this, RecordActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
    bubble.setOnTouchListener(
        new View.OnTouchListener() {
          float downX, downY;
          int x, y;
          boolean dragged;

          public boolean onTouch(View v, android.view.MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
              downX = e.getRawX();
              downY = e.getRawY();
              x = layout.x;
              y = layout.y;
              dragged = false;
              return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
              float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
              if (Math.hypot(dx, dy) > dp(8)) dragged = true;
              if (dragged) {
                layout.x = x + (int) dx;
                layout.y = y + (int) dy;
                clampPosition();
                windows.updateViewLayout(bubble, layout);
              }
              return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
              touchX = e.getX();
              if (!dragged) v.performClick();
              else prefs.edit().putInt("bubbleX", layout.x).putInt("bubbleY", layout.y).apply();
              return true;
            }
            return true;
          }
        });
    windows.addView(bubble, layout);
    renderBubble();
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private void clampPosition() {
    android.graphics.Rect bounds = windows.getCurrentWindowMetrics().getBounds();
    layout.x = Math.max(0, Math.min(layout.x, bounds.width() - layout.width));
    layout.y = Math.max(dp(32), Math.min(layout.y, bounds.height() - dp(80)));
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration config) {
    super.onConfigurationChanged(config);
    I18n.init(this); // The language may have changed while the service runs.
    if (bubble != null) {
      clampPosition();
      windows.updateViewLayout(bubble, layout);
    }
  }

  private void renderBubble() {
    if (bubble == null) return;
    int width =
        RECORDING.equals(state)
            ? dp(132 + OverlayIcon.ATTACH_DP)
            : COPIED.equals(state) ? bubble.copiedWidth() : dp(48);
    if (layout.width != width) {
      layout.width = width;
      clampPosition();
      windows.updateViewLayout(bubble, layout);
    }
    if (RECORDING.equals(state)) bubble.attached(new Attachments(this).pending().size());
    bubble.update(
        state,
        prefs.getInt("bubbleOpacity", 65),
        RECORDING.equals(state) ? SystemClock.elapsedRealtime() - started : 0);
  }

  static boolean bubbleVisible() {
    return instance != null && instance.bubble != null;
  }

  /**
   * Every call to OpenRouter starts here: recording, redo and processing again. Without a key
   * nothing is recorded that couldn't be transcribed.
   */
  static void requireKey(Context context) throws IOException {
    if (!OpenRouter.hasKey(context))
      throw new IOException(tr("Add your OpenRouter key in Drift's Settings first."));
  }

  /**
   * The request check, then whatever it asks for. Nothing asked: the note is the transcript, as
   * said. A request goes to the text model, with the template asked for and the attachments, if
   * any. If the check fails, the transcript stays; if the request fails, the note keeps what was
   * said and records why, so it can say so and offer to try again.
   */
  static void process(Context context, HistoryEntry entry, Profile profile) throws Exception {
    String said = entry.json.optString("original", entry.text());
    String note = entry.recordingId();
    Templates templates = new Templates(context);
    Attachments attachments = new Attachments(context);
    Router.Route route;
    long asked = SystemClock.elapsedRealtime();
    try {
      route = Router.route(context, said, templates.names(), attachments.summary(note));
      entry.json.remove("routeFailed");
      entry.step(
          "check",
          Router.MODEL,
          SystemClock.elapsedRealtime() - asked,
          route.result().cost(),
          route.kind() + (route.template().isEmpty() ? "" : " · " + route.template()));
    } catch (Exception ex) {
      route = new Router.Route(Router.TRANSCRIPT, "", null);
      entry.json.put("routeFailed", friendly(ex));
      entry.step("check", Router.MODEL, SystemClock.elapsedRealtime() - asked, null, friendly(ex));
    }
    if (route.result() != null) {
      entry.charge(route.result().cost());
      if (route.result().cost() != null)
        entry.json.put("routeCost", entry.routeCost().add(route.result().cost()).toPlainString());
    }
    entry.json.put("route", route.kind());
    if (route.template().isEmpty()) entry.json.remove("template");
    else entry.json.put("template", route.template());
    if (route.kind().equals(Router.TRANSCRIPT)) {
      entry.json.remove("processingFailed");
      return;
    }
    asked = SystemClock.elapsedRealtime();
    try {
      OpenRouter.Result r =
          TextModel.process(
              context,
              said,
              attachments.parts(note),
              profile.processingPrompt(),
              templates.prompt(route.template()),
              profile.context());
      entry.charge(r.cost());
      entry.step(Router.TEXT, TextModel.MODEL, SystemClock.elapsedRealtime() - asked, r.cost(), null);
      entry
          .json
          .put("processed", r.text())
          .put("text", r.text())
          .put("version", "processed")
          .put("textModel", TextModel.MODEL)
          .remove("processingFailed");
      // Kept apart too, so Usage can show the speech and the text models separately.
      if (r.cost() != null)
        entry.json.put("textCost", entry.textCost().add(r.cost()).toPlainString());
    } catch (Exception ex) {
      entry.json.put("processingFailed", friendly(ex));
      entry.step(Router.TEXT, TextModel.MODEL, SystemClock.elapsedRealtime() - asked, null, friendly(ex));
    }
  }

  private void startRecording() throws Exception {
    if (isBusy()) return;
    if (audio(this).exists()) {
      transition(ERROR, tr("A saved recording needs retry or discard first."));
      return;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
      throw new IOException(tr("Allow microphone access in Drift first."));
    requireKey(this);
    foreground(true);
    vault.remove("progress");
    prefs.edit().remove("recordingId").commit();
    prefs.edit().putString("sessionModel", new Profile(prefs).fixedModel()).commit();
    prefs
        .edit()
        .putString("sessionId", java.util.UUID.randomUUID().toString())
        .putLong("sessionCreated", System.currentTimeMillis())
        .commit();
    recorder = new PcmRecorder();
    try {
      recorder.start(
          audio(this),
          () ->
              main.post(
                  () -> {
                    if (RECORDING.equals(state)) stopRecording(false);
                  }));
    } catch (Exception ex) {
      recorder = null;
      foreground(false);
      throw ex;
    }
    // User controls the lifetime. No timeout or voice-activity/silence detector ends recording.
    wake.acquire();
    started = SystemClock.elapsedRealtime();
    transition(RECORDING, tr("Recording until you tap Stop. Switch apps freely."));
    main.post(ticker);
  }

  private void stopRecording(boolean transcribe) {
    if (!RECORDING.equals(state)) return;
    transition(STOPPING, tr("Saving recording…"));
    main.removeCallbacks(ticker);
    PcmRecorder current = recorder;
    recorder = null;
    worker.execute(
        () -> {
          Exception failure = current.stop();
          main.post(
              () -> {
                if (!alive) return;
                if (wake.isHeld()) wake.release();
                foreground(false);
                if (failure != null || !transcribe) {
                  transition(
                      ERROR,
                      failure == null
                          ? tr("Recording interrupted. Saved audio can be retried.")
                          : friendly(failure));
                } else upload();
              });
        });
  }

  private void upload() {
    File pcm = audio(this);
    if (pcm.length() < 2) {
      try {
        clearPending();
        transition(EMPTY, tr("No audio captured. Ready to try again."));
      } catch (IOException ex) {
        transition(ERROR, friendly(ex));
      }
      return;
    }
    transition(UPLOADING, tr("Transcribing… You can keep using other apps."));
    wake.acquire(30 * 60 * 1000L);
    String existingId = prefs.getString("sessionId", "");
    final String sessionId =
        existingId.isEmpty() ? java.util.UUID.randomUUID().toString() : existingId;
    prefs
        .edit()
        .putString("sessionId", sessionId)
        .putString("recordingId", prefs.getString("recordingId", sessionId))
        .commit();
    worker.execute(
        () -> {
          long uploadStarted = SystemClock.elapsedRealtime();
          try (HistoryStore history = new HistoryStore(this)) {
            String model = prefs.getString("sessionModel", new Profile(prefs).fixedModel());
            HistoryEntry entry = history.get(sessionId);
            if (entry == null) {
              entry =
                  HistoryEntry.create(
                      sessionId, model, pcm.length() / (double) AudioFiles.BYTES_PER_SECOND);
              entry.json.put("recordingId", prefs.getString("recordingId", sessionId));
              entry.json.put(
                  "created", prefs.getLong("sessionCreated", System.currentTimeMillis()));
              // Carry forward a version 1.0 checkpoint, whose billing metadata was not recorded.
              String legacy = vault.get("progress");
              if (!legacy.isEmpty()) {
                JSONObject old = new JSONObject(legacy);
                entry
                    .json
                    .put("offset", old.optLong("offset"))
                    .put("text", old.optString("text"))
                    .put("unpriced", old.optLong("offset") > 0 ? 1 : 0);
              }
              history.save(entry);
              vault.remove("progress");
            }
            OpenRouter openRouter = new OpenRouter(this);
            Profile profile = new Profile(prefs);
            // No language: the speech models detect it, also when you switch mid-note.
            java.util.List<String> words = profile.hintWords();
            while (entry.offset() < (pcm.length() & ~1L)) {
              if (Thread.currentThread().isInterrupted() || !alive) return;
              AudioFiles.Chunk chunk = AudioFiles.readChunk(pcm, entry.offset());
              entry.beginRequest();
              history.save(entry); // A lost response leaves an explicit unknown charge.
              long asked = SystemClock.elapsedRealtime();
              OpenRouter.Result result;
              try {
                result = openRouter.transcribe(chunk.wav(), model, words);
              } catch (Exception failed) {
                entry.step(
                    "speech", model, SystemClock.elapsedRealtime() - asked, null, friendly(failed));
                history.save(entry);
                throw failed;
              }
              entry.accept(result, chunk.nextOffset());
              entry.step(
                  "speech", model, SystemClock.elapsedRealtime() - asked, result.cost(), null);
              history.save(entry); // Text, cost, and resume offset commit together, including after
              // interruption.
              if (Thread.currentThread().isInterrupted() || !alive) return;
            }
            boolean empty = entry.text().isBlank();
            if (!empty && !entry.json.has("raw")) {
              // "raw" stays what the speech model wrote; "original" and "processed" are the two
              // versions you can switch between and edit.
              entry.json.put("raw", entry.text()).put("original", entry.text());
              // What was attached while recording belongs to this note (not to a redo of another).
              if (entry.recordingId().equals(entry.id())) {
                Attachments attachments = new Attachments(this);
                attachments.claimPending(entry.recordingId());
                int attached = attachments.of(entry.recordingId()).size();
                if (attached > 0) entry.json.put("attached", attached);
              }
              if (profile.processing()) {
                main.post(
                    () -> {
                      if (alive) transition(UPLOADING, tr("Checking for requests…"));
                    });
                process(this, entry, profile);
              }
            }
            entry.json.put("status", empty ? "no speech" : "complete");
            entry.json.put(
                "processingMs",
                entry.json.optLong("processingMs") + SystemClock.elapsedRealtime() - uploadStarted);
            history.save(entry);
            if (!empty && !entry.recordingId().equals(entry.id())) history.prefer(entry);
            new AudioArchive(this).retain(entry.recordingId(), pcm);
            if (empty) {
              main.post(
                  () -> {
                    if (!alive) return;
                    try {
                      clearPending();
                      transition(EMPTY, tr("No speech recognized. Ready to record again."));
                    } catch (IOException ex) {
                      transition(ERROR, friendly(ex));
                    } finally {
                      if (wake.isHeld()) wake.release();
                    }
                  });
              return;
            }
            prefs.edit().putLong("historyRevision", System.nanoTime()).apply();
            vault.put("transcript", entry.text());
            String transcript = entry.text();
            String failed = entry.json.optString("processingFailed", "");
            main.post(
                () -> {
                  if (!alive) return;
                  try {
                    copy(this, transcript);
                    clearPending();
                    // A failed processing step is worth a toast even when the button shows "Ready".
                    transition(
                        COPIED,
                        failed.isEmpty()
                            ? tr("Ready to paste")
                            : tr("Copied what you said. Processing failed: %1$s", failed),
                        !failed.isEmpty());
                  } catch (Exception ex) {
                    transition(ERROR, tr("Transcript saved in history. Open Drift to copy it."));
                  } finally {
                    if (wake.isHeld()) wake.release();
                  }
                });
          } catch (Exception ex) {
            try (HistoryStore history = new HistoryStore(this)) {
              HistoryEntry entry = history.get(sessionId);
              if (entry != null
                  && !entry.json.optString("status").equals("complete")
                  && !entry.json.optString("status").equals("no speech")) {
                entry.json.put("status", "needs attention");
                history.save(entry);
              }
            } catch (Exception ignored) {
              /* Keep the original error and recoverable audio. */
            }
            prefs.edit().putLong("historyRevision", System.nanoTime()).apply();
            main.post(
                () -> {
                  if (!alive) return;
                  if (wake.isHeld()) wake.release();
                  transition(ERROR, friendly(ex));
                });
          }
        });
  }

  static void copy(Context context, String text) {
    ClipData clip = ClipData.newPlainText("Drift transcript", text);
    PersistableBundle extras = new PersistableBundle();
    extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
    clip.getDescription().setExtras(extras);
    context.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
  }

  private void clearPending() throws IOException {
    if (audio(this).exists() && !audio(this).delete())
      throw new IOException(tr("Could not delete saved audio. Try again after restarting Drift."));
    try (HistoryStore history = new HistoryStore(this)) {
      HistoryEntry entry = history.get(prefs.getString("sessionId", ""));
      if (entry != null
          && !entry.json.optString("status").equals("complete")
          && !entry.json.optString("status").equals("no speech")) {
        entry.json.put("status", "audio discarded");
        history.save(entry);
      }
    } catch (Exception ex) {
      throw new IOException(tr("Could not update history."), ex);
    }
    vault.remove("progress");
    prefs
        .edit()
        .remove("sessionModel")
        .remove("sessionId")
        .remove("sessionCreated")
        .remove("recordingId")
        .putLong("historyRevision", System.nanoTime())
        .commit();
  }

  static String friendly(Exception ex) {
    // Drift's own errors are plain IOExceptions with a translated message. Subclasses come from
    // the network stack (connection reset, TLS, timeouts) and carry raw English, if anything.
    if (ex.getClass() == IOException.class && ex.getMessage() != null) return ex.getMessage();
    if (ex instanceof java.io.FileNotFoundException
        || ex instanceof java.nio.file.FileSystemException
        || ex instanceof java.io.EOFException
        || (ex.getMessage() != null && ex.getMessage().contains("ENOSPC")))
      return tr("Drift couldn't read or write its files. Check available storage, then retry.");
    if (ex instanceof IOException)
      return tr("The connection to OpenRouter was lost. Check your internet connection, then retry.");
    return tr(
            "Could not complete this action. Check microphone permission, available storage and"
                + " your saved key, then retry.");
  }

  @Override
  public void onDestroy() {
    alive = false;
    main.removeCallbacksAndMessages(null);
    if (recorder != null) recorder.stop();
    worker.shutdownNow();
    if (wake.isHeld()) wake.release();
    if (bubble != null) {
      windows.removeView(bubble);
      bubble = null;
    }
    instance = null;
    DictationTile.refresh(this);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
