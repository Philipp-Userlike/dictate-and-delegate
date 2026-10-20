package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The hands-free call over other apps, as a call bar: a status dot and short label (Listening,
 * Working…, Reading), send while you can talk, and hang up. Never takes focus; drag it anywhere,
 * tap the status to open the full screen; it always stays whole on screen.
 */
final class HandsFreePanel extends LinearLayout {
  interface Actions {
    void open();

    void send();

    void end();
  }

  private final TextView state;
  private final ImageView send, end;
  private final GradientDrawable dot = new GradientDrawable();
  private final WindowManager windows;
  private final WindowManager.LayoutParams layout;
  private final float density;
  private float downX, downY;
  private int fromX, fromY;
  private boolean dragging;

  /** Prefs: where the call panel was last dragged to. */
  static final String X = "hfPanelX", Y = "hfPanelY";

  private final android.content.SharedPreferences prefs;

  /**
   * A call bar: a status dot with a short label (tap: the full screen), send (paper plane) while
   * you can talk, and a red hang-up. At x, y (from the dictation panel the call came from), or
   * where it was last dragged to when x is negative.
   */
  HandsFreePanel(Context context, Actions actions, int x, int y) {
    super(context);
    prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    density = getResources().getDisplayMetrics().density;
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setPadding(dp(6), dp(6), dp(6), dp(6));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xf21b1f24);
    bg.setCornerRadius(dp(28));
    setBackground(bg);
    state = new TextView(context);
    state.setTextSize(13);
    state.setTextColor(0xffffffff);
    state.setTypeface(Ui.MEDIUM);
    state.setSingleLine(true);
    state.setGravity(Gravity.CENTER_VERTICAL);
    dot.setShape(GradientDrawable.OVAL);
    dot.setSize(dp(9), dp(9));
    state.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
    state.setCompoundDrawablePadding(dp(8));
    state.setPadding(dp(12), 0, dp(12), 0);
    state.setMinHeight(dp(40));
    state.setOnClickListener(v -> actions.open());
    addView(state, new LayoutParams(-2, dp(40)));
    send = round(R.drawable.ic_send, 0x33ffffff, tr("Send now"), v -> actions.send());
    end = round(R.drawable.ic_call_end, 0xffe5484d, tr("End hands-free"), v -> actions.end());
    windows = context.getSystemService(WindowManager.class);
    layout =
        new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP | Gravity.LEFT;
    layout.x = x >= 0 ? x : prefs.getInt(X, dp(16));
    layout.y = x >= 0 ? y : prefs.getInt(Y, dp(120));
  }

  private int dp(float n) {
    return Math.round(n * density);
  }

  /** A round icon button, 40 dp, its name for TalkBack and on a long press. */
  private ImageView round(int icon, int color, String name, View.OnClickListener click) {
    ImageView b = new ImageView(getContext());
    b.setImageResource(icon);
    b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    b.setPadding(dp(9), dp(9), dp(9), dp(9));
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.OVAL);
    d.setColor(color);
    // Pressed state: a ripple shows at once that the press was taken.
    b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x66ffffff), d, null));
    b.setContentDescription(name);
    b.setTooltipText(name);
    b.setOnClickListener(click);
    LayoutParams l = new LayoutParams(dp(40), dp(40));
    l.leftMargin = dp(6);
    addView(b, l);
    return b;
  }

  /** Where it is on screen: x, y. */
  int[] position() {
    return new int[] {layout.x, layout.y};
  }

  void show() {
    if (getParent() != null) return;
    fit();
    windows.addView(this, layout);
  }

  /** Every button on screen: the panel is moved in from an edge when it would stick out. */
  private void fit() {
    android.graphics.Rect bounds = windows.getCurrentWindowMetrics().getBounds();
    measure(
        MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.AT_MOST),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    layout.x = Math.max(dp(8), Math.min(layout.x, bounds.width() - getMeasuredWidth() - dp(8)));
    layout.y = Math.max(dp(32), Math.min(layout.y, bounds.height() - getMeasuredHeight() - dp(48)));
  }

  /** Whether every button shows whole on screen (for the device tests). */
  boolean fits() {
    android.graphics.Rect bounds = windows.getCurrentWindowMetrics().getBounds();
    for (View t : new View[] {state, send, end}) {
      if (t.getVisibility() != VISIBLE) continue;
      int[] at = new int[2];
      t.getLocationOnScreen(at);
      if (at[0] < 0 || at[0] + t.getWidth() > bounds.width()) return false;
    }
    return true;
  }

  void hide() {
    if (getParent() != null) windows.removeView(this);
  }

  /**
   * The state, short (the full words for TalkBack and on a long press), its colour, and only the
   * buttons that fit it.
   */
  void render(HandsFreeSession.Phase phase) {
    state.setText(
        switch (phase) {
          case READING -> tr("Reading");
          case SENDING -> tr("Sending…");
          case WORKING -> tr("Working…");
          default -> tr("Listening");
        });
    state.setContentDescription(HandsFreeService.phaseLabel(phase));
    state.setTooltipText(HandsFreeService.phaseLabel(phase));
    // Green: you can talk; amber: you can talk, the chief works; violet: a reply plays.
    dot.setColor(
        switch (phase) {
          case READING -> 0xffb197fc;
          case SENDING, WORKING -> 0xfff5b84a;
          default -> 0xff3fd6a1;
        });
    send.setVisibility(HandsFreeService.canSend(phase) ? VISIBLE : GONE);
    if (getParent() != null) {
      fit();
      windows.updateViewLayout(this, layout);
    }
  }

  // Dragging: a move past the touch slop anywhere on the panel moves it; taps still reach buttons.

  @Override
  public boolean onInterceptTouchEvent(MotionEvent e) {
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        downX = e.getRawX();
        downY = e.getRawY();
        fromX = layout.x;
        fromY = layout.y;
        dragging = false;
        return false;
      }
      case MotionEvent.ACTION_MOVE -> {
        if (!dragging && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > dp(8)) dragging = true;
        return dragging;
      }
      default -> {
        return dragging;
      }
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
      if (!dragging && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > dp(8)) dragging = true;
      if (dragging && getParent() != null) {
        layout.x = fromX + (int) (e.getRawX() - downX);
        layout.y = fromY + (int) (e.getRawY() - downY);
        windows.updateViewLayout(this, layout);
      }
    } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      if (dragging) {
        if (getParent() != null) {
          fit();
          windows.updateViewLayout(this, layout);
        }
        prefs.edit().putInt(X, layout.x).putInt(Y, layout.y).apply();
      }
      dragging = false;
    }
    return true;
  }
}
