package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The hands-free call over other apps: its state (Listening, also while the chief works, or
 * Reading reply), Send now while you can talk, and End. Never takes focus; drag it anywhere, tap the state to open
 * the full screen.
 */
final class HandsFreePanel extends LinearLayout {
  interface Actions {
    void open();

    void send();

    void end();
  }

  private final TextView state, send, end;
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
   * At x, y (from the dictation panel the call came from), or where it was last dragged to when
   * x is negative.
   */
  HandsFreePanel(Context context, Actions actions, int x, int y) {
    super(context);
    prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    density = getResources().getDisplayMetrics().density;
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setPadding(dp(6), dp(6), dp(6), dp(6));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xf2182420);
    bg.setCornerRadius(dp(24));
    setBackground(bg);
    state = pill("", v -> actions.open());
    send = pill(tr("Send now"), v -> actions.send());
    end = pill(tr("End"), v -> actions.end());
    end.setContentDescription(tr("End hands-free"));
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

  private TextView pill(String text, View.OnClickListener click) {
    TextView t = new TextView(getContext());
    t.setText(text);
    t.setTextSize(14);
    t.setTextColor(0xffffffff);
    t.setTypeface(Ui.MEDIUM);
    t.setGravity(Gravity.CENTER);
    t.setSingleLine(true);
    t.setMinHeight(dp(40));
    t.setPadding(dp(14), 0, dp(14), 0);
    t.setOnClickListener(click);
    GradientDrawable d = new GradientDrawable();
    d.setColor(0x2effffff);
    d.setCornerRadius(dp(20));
    t.setBackground(d);
    LayoutParams l = new LayoutParams(-2, dp(40));
    if (getChildCount() > 0) l.leftMargin = dp(6);
    addView(t, l);
    return t;
  }

  /** Where it is on screen: x, y. */
  int[] position() {
    return new int[] {layout.x, layout.y};
  }

  void show() {
    if (getParent() == null) windows.addView(this, layout);
  }

  void hide() {
    if (getParent() != null) windows.removeView(this);
  }

  /** The state in its words and colour, and only the buttons that fit it. */
  void render(HandsFreeSession.Phase phase) {
    state.setText(
        switch (phase) {
              case READING -> "🔊  ";
              default -> "🎙  ";
            }
            + HandsFreeService.phaseLabel(phase));
    GradientDrawable d = new GradientDrawable();
    d.setColor(
        switch (phase) {
          case READING -> 0xff6b4fa8;
          default -> 0xff1d8a6e;
        });
    d.setCornerRadius(dp(20));
    state.setBackground(d);
    send.setVisibility(phase != HandsFreeSession.Phase.READING ? VISIBLE : GONE);
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
      if (dragging) prefs.edit().putInt(X, layout.x).putInt(Y, layout.y).apply();
      dragging = false;
    }
    return true;
  }
}
