package app.driftdictation;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Talk Router's Accessibility service, used for two things only: holding a volume key starts or
 * stops a dictation, and Insert puts text at the cursor of the field you're typing in. It asks for
 * no accessibility events, so it never sees what happens on screen; it looks up the focused field
 * only when you tap Insert. While it runs, it also takes the Copy button of the chief's ntfy
 * notifications (ntfy sends that as an implicit broadcast, which only a running receiver gets).
 */
public final class TalkAccessibility extends AccessibilityService {
  static volatile TalkAccessibility instance;

  /** Settings: which volume key starts (off, down, up) and how long to hold it. */
  static final String VOLUME_KEY = "volumeStart", HOLD_MS = "holdMs";

  static final String OFF = "off", DOWN = "down", UP = "up";
  static final int DEFAULT_HOLD_MS = 500;

  /** ntfy's broadcast action on the chief's paste message: copy that request's text. */
  static final String COPY_ACTION = "app.driftdictation.COPY_CHIEF_TEXT";

  private final Handler main = new Handler(Looper.getMainLooper());
  private SharedPreferences prefs;
  private boolean holding, fired;
  private final Runnable held =
      () -> {
        fired = true;
        holdFired();
      };
  private BroadcastReceiver copy;

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    instance = this;
    I18n.init(this);
    prefs = getSharedPreferences("settings", MODE_PRIVATE);
    copy = new ChiefText.CopyReceiver();
    registerReceiver(copy, new IntentFilter(COPY_ACTION), RECEIVER_EXPORTED);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    /* None are requested. */
  }

  @Override
  public void onInterrupt() {}

  @Override
  public boolean onUnbind(Intent intent) {
    stop();
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    stop();
    super.onDestroy();
  }

  private void stop() {
    instance = null;
    main.removeCallbacksAndMessages(null);
    if (copy != null) {
      try {
        unregisterReceiver(copy);
      } catch (IllegalArgumentException ignored) {
        /* Already gone. */
      }
      copy = null;
    }
  }

  /** Whether Android has the service switched on for Talk Router (it may still be starting). */
  static boolean enabled(Context context) {
    String on =
        android.provider.Settings.Secure.getString(
            context.getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    ComponentName me = new ComponentName(context, TalkAccessibility.class);
    if (on == null) return false;
    for (String s : on.split(":")) {
      ComponentName c = ComponentName.unflattenFromString(s);
      if (me.equals(c)) return true;
    }
    return false;
  }

  // ------------------------------------------------------------------ volume keys

  /** The key code that starts a dictation, or 0 when the volume keys aren't used. */
  static int startKey(SharedPreferences prefs) {
    return switch (prefs.getString(VOLUME_KEY, OFF)) {
      case DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN;
      case UP -> KeyEvent.KEYCODE_VOLUME_UP;
      default -> 0;
    };
  }

  static int holdMs(SharedPreferences prefs) {
    return Math.max(200, Math.min(2000, prefs.getInt(HOLD_MS, DEFAULT_HOLD_MS)));
  }

  /**
   * The chosen volume key is held back from Android until we know what it is: held long enough,
   * it starts or stops a dictation; a short press changes the volume as usual, re-sent here. With
   * the screen off or locked, or during a call, the key is left alone.
   */
  @Override
  protected boolean onKeyEvent(KeyEvent e) {
    int key = prefs == null ? 0 : startKey(prefs);
    if (key == 0 || e.getKeyCode() != key) return false;
    if (e.getAction() == KeyEvent.ACTION_DOWN) {
      if (e.getRepeatCount() > 0) return holding;
      if (!available()) return false;
      holding = true;
      fired = false;
      main.postDelayed(held, holdMs(prefs));
      return true;
    }
    if (e.getAction() != KeyEvent.ACTION_UP || !holding) return false;
    holding = false;
    main.removeCallbacks(held);
    if (!fired) {
      // A short press while recording stops; otherwise it's the volume, as if we weren't here.
      if (recording()) DictationService.send(this, DictationService.STOP);
      else
        getSystemService(AudioManager.class)
            .adjustSuggestedStreamVolume(
                key == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI);
    }
    return true;
  }

  /** Screen on and unlocked, and no call going on. */
  private boolean available() {
    if (!getSystemService(PowerManager.class).isInteractive()) return false;
    if (getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) return false;
    int mode = getSystemService(AudioManager.class).getMode();
    return mode != AudioManager.MODE_IN_CALL
        && mode != AudioManager.MODE_IN_COMMUNICATION
        && mode != AudioManager.MODE_RINGTONE;
  }

  private static boolean recording() {
    return DictationService.instance != null
        && DictationService.RECORDING.equals(DictationService.instance.state());
  }

  private void holdFired() {
    if (recording()) {
      DictationService.send(this, DictationService.STOP);
      return;
    }
    if (DictationService.busy()) return;
    // The same short-lived window as the tile: Android lets a visible app start the microphone.
    startActivity(
        new Intent(this, RecordActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
  }

  // ------------------------------------------------------------------ insert

  /** Where a text was put: the field, and the range it took there (start -1 when unknown). */
  record Inserted(AccessibilityNodeInfo field, String text, int start) {}

  /** The text field that has input focus in the app you're in, or null. */
  AccessibilityNodeInfo field() {
    // Without events Android never updates its node cache, so always ask the app afresh.
    clearCache();
    AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    if (n == null || !n.refresh()) return null;
    if (n.isEditable()) return n;
    // Web content (Chrome, WebView) reports its host view as focused: the field is inside it.
    return focusedEditable(n);
  }

  /** The focused editable node below a view, breadth first, looking at a bounded number of nodes. */
  private static AccessibilityNodeInfo focusedEditable(AccessibilityNodeInfo root) {
    java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
    queue.add(root);
    for (int seen = 0; !queue.isEmpty() && seen < 3000; seen++) {
      AccessibilityNodeInfo n = queue.poll();
      if (n.isFocused() && n.isEditable()) return n;
      for (int i = 0; i < n.getChildCount(); i++) {
        AccessibilityNodeInfo child = n.getChild(i);
        if (child != null) queue.add(child);
      }
    }
    return null;
  }

  /** A field's text, "" while it only shows its hint. */
  static String text(AccessibilityNodeInfo n) {
    if (n.isShowingHintText()) return "";
    CharSequence t = n.getText();
    return t == null ? "" : t.toString();
  }

  /**
   * Puts the text at the cursor of the focused field, replacing a selection, the way pasting does:
   * through the clipboard and the field's own paste, else by setting the field's text with the
   * text spliced in at the cursor. Null when there's no field or the app refused both.
   */
  Inserted insert(Context context, String text) {
    AccessibilityNodeInfo n = field();
    if (n == null) return null;
    String before = text(n);
    int s = n.getTextSelectionStart(), e = n.getTextSelectionEnd();
    if (s < 0 || e < 0 || s > before.length() || e > before.length()) s = e = before.length();
    if (s > e) {
      int t = s;
      s = e;
      e = t;
    }
    return put(context, n, before, s, e, text);
  }

  /**
   * Replaces an inserted text with another (the refined version), if the field still contains it
   * unchanged; returns null otherwise, and the caller inserts at the cursor instead.
   */
  Inserted replace(Context context, Inserted earlier, String text) {
    AccessibilityNodeInfo n = field();
    if (n == null || earlier == null || !n.equals(earlier.field())) return null;
    String now = text(n);
    int at = locate(now, earlier.text(), earlier.start());
    if (at < 0) return null;
    return put(context, n, now, at, at + earlier.text().length(), text);
  }

  /** Where the inserted text is now: at its old place, or its only occurrence; -1 if gone. */
  static int locate(String field, String inserted, int start) {
    if (inserted.isEmpty()) return -1;
    // Web editors turn spaces at the edges into non-breaking ones.
    field = field.replace('\u00a0', ' ');
    inserted = inserted.replace('\u00a0', ' ');
    if (start >= 0 && field.startsWith(inserted, start)) return start;
    int first = field.indexOf(inserted);
    return first >= 0 && first == field.lastIndexOf(inserted) ? first : -1;
  }

  /** before[0, s) + text + before[e, …): what the field should hold afterwards. */
  static String spliced(String before, int s, int e, String text) {
    return before.substring(0, s) + text + before.substring(e);
  }

  private Inserted put(
      Context context, AccessibilityNodeInfo n, String before, int s, int e, String text) {
    String expected = spliced(before, s, e, text);
    Bundle range = new Bundle();
    range.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s);
    range.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e);
    if (s != n.getTextSelectionStart() || e != n.getTextSelectionEnd())
      n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range);
    DictationService.copy(context, text);
    if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
      String after = settle(n, before);
      if (after.equals(expected)) return new Inserted(n, text, s);
      if (!after.equals(before)) {
        // Pasted, but the app changed something around it: find it, if we can.
        int at = locate(after, text, s);
        return new Inserted(n, text, at);
      }
    }
    // The app ignored the paste: set its text, with ours spliced in at the cursor.
    Bundle args = new Bundle();
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected);
    if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return null;
    Bundle cursor = new Bundle();
    cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s + text.length());
    cursor.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, s + text.length());
    n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursor);
    String after = settle(n, before);
    return after.equals(before) ? null : new Inserted(n, text, locate(after, text, s));
  }

  /** The field's text once it changed (apps paste asynchronously), at most about half a second. */
  private static String settle(AccessibilityNodeInfo n, String before) {
    String now = before;
    for (int i = 0; i < 10; i++) {
      n.refresh();
      now = text(n);
      if (!now.equals(before)) return now;
      SystemClock.sleep(50);
    }
    return now;
  }
}
