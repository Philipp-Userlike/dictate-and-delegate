package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/**
 * The floating panel over other apps. While recording: 📎, the timer with stop, and Refine and
 * Chief to choose ahead. Afterwards: ✕, Insert, Refine (then Insert refined), Chief (then Open chat
 * ↗), and Insert from chief when the chief sent text back. Ready, error and empty show the small
 * icon instead. Its window never takes focus, so the cursor stays in the app you're in; drag it
 * anywhere.
 */
final class FloatingPanel extends FrameLayout {
  /** What the panel shows; DictationService builds it from its state and the session. */
  record Model(
      String state,
      long elapsed,
      int attached,
      String refine,
      String chief,
      boolean chiefAvailable,
      boolean hint,
      boolean reply,
      String message,
      int opacity) {}

  interface Actions {
    void close();

    void stop();

    void attach();

    void insert();

    void refine();

    void chief();

    void insertFromChief();

    void tapIcon();
  }

  interface Drag {
    void start();

    void move(float dx, float dy);

    void end();
  }

  private static final int INK = 0xf2182420, PILL = 0x2effffff, TEAL = 0xff28b89a, AMBER = 0xffedb14a;

  private final OverlayIcon icon;
  private final LinearLayout bar, extra;
  private final TextView attach, timer, close, insert, refine, chief, fromChief, status;
  private final Actions actions;
  private final Drag drag;
  private final float density;
  private final int slop;
  private float downX, downY;
  private boolean dragging, small = true;

  FloatingPanel(Context context, Actions actions, Drag drag) {
    super(context);
    this.actions = actions;
    this.drag = drag;
    density = getResources().getDisplayMetrics().density;
    slop = dp(8);
    icon = new OverlayIcon(context);
    icon.setClickable(false);
    addView(icon, new LayoutParams(dp(48), dp(48)));
    bar = new LinearLayout(context);
    bar.setOrientation(LinearLayout.VERTICAL);
    bar.setPadding(dp(6), dp(6), dp(6), dp(6));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(INK);
    bg.setCornerRadius(dp(24));
    bar.setBackground(bg);
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    bar.addView(row);
    attach = pill(row, "📎", v -> actions.attach());
    attach.setContentDescription(tr("Add context to this dictation"));
    timer = pill(row, "", v -> actions.stop());
    timer.setContentDescription(tr("Stop recording"));
    timer.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    close = pill(row, "✕", v -> actions.close());
    close.setContentDescription(tr("Close"));
    insert = pill(row, tr("Insert"), v -> actions.insert());
    refine = pill(row, "", v -> actions.refine());
    chief = pill(row, "", v -> actions.chief());
    extra = new LinearLayout(context);
    extra.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2);
    el.topMargin = dp(6);
    bar.addView(extra, el);
    fromChief = pill(extra, tr("Insert from chief"), v -> actions.insertFromChief());
    status = new TextView(context);
    status.setTextColor(0xccffffff);
    status.setTextSize(12);
    status.setMaxLines(2);
    status.setEllipsize(TextUtils.TruncateAt.END);
    status.setPadding(dp(8), dp(4), dp(8), dp(2));
    status.setMaxWidth(dp(320));
    bar.addView(status);
    addView(bar, new LayoutParams(-2, -2));
    bar.setVisibility(GONE);
  }

  private int dp(float n) {
    return Math.round(n * density);
  }

  private TextView pill(LinearLayout row, String text, View.OnClickListener click) {
    TextView t = new TextView(getContext());
    t.setText(text);
    t.setTextSize(14);
    t.setTextColor(0xffffffff);
    t.setTypeface(Ui.MEDIUM);
    t.setGravity(Gravity.CENTER);
    t.setSingleLine(true);
    t.setMinHeight(dp(40));
    t.setMinWidth(dp(40));
    t.setPadding(dp(12), 0, dp(12), 0);
    t.setOnClickListener(click);
    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, dp(40));
    if (row.getChildCount() > 0) l.leftMargin = dp(6);
    row.addView(t, l);
    style(t, PILL, 0);
    return t;
  }

  private void style(TextView t, int fill, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(20));
    if (stroke != 0) d.setStroke(dp(2), stroke);
    t.setBackground(d);
  }

  /** Whether the small icon is shown (and taps on it count). */
  boolean small() {
    return small;
  }

  void render(Model m) {
    boolean recording = DictationService.RECORDING.equals(m.state());
    boolean working = DictationService.STOPPING.equals(m.state()) || DictationService.UPLOADING.equals(m.state());
    boolean ready = DictationService.done(m.state());
    small = !recording && !working && !ready;
    icon.setVisibility(small ? VISIBLE : GONE);
    bar.setVisibility(small ? GONE : VISIBLE);
    if (small) {
      icon.update(m.state(), m.opacity());
      setAlpha(1f);
      return;
    }
    setAlpha(1f);
    attach.setVisibility(recording ? VISIBLE : GONE);
    attach.setText(m.attached() > 0 ? "📎 " + m.attached() : "📎");
    timer.setVisibility(recording ? VISIBLE : GONE);
    timer.setText("● " + AudioFiles.elapsed(m.elapsed()) + "  ■");
    style(timer, 0xffe5484d, 0);
    close.setVisibility(recording ? GONE : VISIBLE);
    insert.setVisibility(recording ? GONE : VISIBLE);
    insert.setText(working ? tr("Transcribing…") : tr("Insert"));
    insert.setEnabled(ready);
    insert.setAlpha(ready ? 1f : 0.6f);
    style(insert, ready ? TEAL : PILL, 0);
    refine.setText(
        switch (m.refine()) {
          case DictationService.QUEUED -> tr("✨ Refine ✓");
          case DictationService.RUNNING -> tr("✨ Refining…");
          case DictationService.DONE -> tr("Insert refined");
          case DictationService.FAILED -> tr("✨ Refine again");
          default -> tr("✨ Refine");
        });
    style(
        refine,
        m.refine().equals(DictationService.DONE) ? TEAL : PILL,
        m.refine().equals(DictationService.QUEUED) || m.refine().equals(DictationService.RUNNING) ? TEAL : 0);
    chief.setVisibility(m.chiefAvailable() ? VISIBLE : GONE);
    chief.setText(
        switch (m.chief()) {
          case DictationService.QUEUED -> tr("🧑‍💼 Chief ✓");
          case DictationService.RUNNING -> tr("🧑‍💼 Sending…");
          case DictationService.DONE -> tr("Open chat ↗");
          case DictationService.FAILED -> tr("🧑‍💼 Send again");
          default -> tr("🧑‍💼 Chief");
        });
    boolean chiefBusy = m.chief().equals(DictationService.QUEUED) || m.chief().equals(DictationService.RUNNING);
    style(chief, PILL, chiefBusy ? TEAL : m.hint() && m.chief().equals(DictationService.IDLE) ? AMBER : 0);
    chief.setContentDescription(
        m.hint() && m.chief().equals(DictationService.IDLE)
            ? tr("Chief, suggested: this may be a task for your chief of staff")
            : null);
    extra.setVisibility(m.reply() ? VISIBLE : GONE);
    style(fromChief, TEAL, 0);
    status.setText(m.message());
    status.setVisibility(m.message().isEmpty() ? GONE : VISIBLE);
  }

  // Dragging: a move past the touch slop anywhere on the panel moves its window; taps still reach
  // the buttons.

  @Override
  public boolean onInterceptTouchEvent(MotionEvent e) {
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        downX = e.getRawX();
        downY = e.getRawY();
        dragging = false;
        drag.start();
        return false;
      }
      case MotionEvent.ACTION_MOVE -> {
        if (!dragging && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) dragging = true;
        return dragging;
      }
      default -> {
        return dragging;
      }
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        downX = e.getRawX();
        downY = e.getRawY();
        dragging = false;
        drag.start();
      }
      case MotionEvent.ACTION_MOVE -> {
        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
        if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
        if (dragging) drag.move(dx, dy);
      }
      case MotionEvent.ACTION_UP -> {
        if (dragging) drag.end();
        else if (small) performClick();
        dragging = false;
      }
      case MotionEvent.ACTION_CANCEL -> {
        if (dragging) drag.end();
        dragging = false;
      }
      default -> {}
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    if (small) actions.tapIcon();
    return true;
  }
}
