package app.driftdictation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;

/** The small view kit behind every screen: colours, type and controls from the Talk Router design. */
final class Ui {
  static final int BG = 0xffffffff,
      SOFT_BG = 0xfff6f8f7,
      PANEL = 0xfff1f4f3,
      LINE = 0xffdce4e1,
      DIVIDER = 0xffeef2f0,
      INK = 0xff0e1a16,
      BODY = 0xff34413c,
      MUTED = 0xff56635e,
      TEAL = 0xff0b7a5e,
      TEAL_SOFT = 0xffd8f0e7,
      TEAL_TINT = 0xfff2faf6,
      BAR = 0xffa8dcc8,
      REC = 0xffe5484d,
      WARN = 0xff8a2a16;

  static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
  static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

  private final Context c;

  Ui(Context context) {
    c = context;
  }

  int dp(float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  TextView text(CharSequence s, float sp, int color) {
    TextView t = new TextView(c);
    t.setText(s);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setLineSpacing(0, 1.15f);
    return t;
  }

  TextView title(CharSequence s, float sp) {
    TextView t = text(s, sp, INK);
    t.setTypeface(BOLD);
    t.setLetterSpacing(-0.02f);
    return t;
  }

  TextView strong(CharSequence s, float sp) {
    TextView t = text(s, sp, INK);
    t.setTypeface(MEDIUM);
    return t;
  }

  TextView mono(CharSequence s, float sp, int color) {
    TextView t = text(s, sp, color);
    t.setTypeface(Typeface.MONOSPACE);
    return t;
  }

  TextView label(String s) {
    TextView t = text(s.toUpperCase(java.util.Locale.ROOT), 12, MUTED);
    t.setTypeface(MEDIUM);
    t.setLetterSpacing(0.06f);
    return t;
  }

  GradientDrawable shape(int fill, int stroke, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    if (stroke != 0) d.setStroke(dp(1), stroke);
    d.setCornerRadius(dp(radius));
    return d;
  }

  LinearLayout vertical() {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout horizontal() {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  LinearLayout card(int fill, int stroke) {
    LinearLayout l = vertical();
    l.setPadding(dp(16), dp(14), dp(16), dp(14));
    l.setBackground(shape(fill, stroke, 18));
    return l;
  }

  /** Adds a full-width child with a top gap. */
  <T extends View> T add(ViewGroup parent, T child, float gapDp) {
    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
    l.topMargin = dp(gapDp);
    parent.addView(child, l);
    return child;
  }

  <T extends View> T addWeighted(LinearLayout row, T child, float leftGapDp) {
    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
    l.leftMargin = dp(leftGapDp);
    row.addView(child, l);
    return child;
  }

  enum Kind {
    PRIMARY,
    DARK,
    RECORDING,
    OUTLINE,
    QUIET
  }

  Button button(String label, Kind kind, View.OnClickListener click) {
    Button b = new Button(c);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(kind == Kind.QUIET ? 15 : 16);
    b.setTypeface(MEDIUM);
    b.setStateListAnimator(null);
    b.setMinHeight(dp(kind == Kind.QUIET ? 44 : 54));
    b.setPadding(dp(16), dp(8), dp(16), dp(8));
    switch (kind) {
      case PRIMARY -> {
        b.setTextColor(BG);
        b.setBackground(shape(TEAL, 0, 16));
      }
      case DARK -> {
        b.setTextColor(BG);
        b.setBackground(shape(INK, 0, 16));
      }
      case RECORDING -> {
        b.setTextColor(BG);
        b.setBackground(shape(REC, 0, 16));
      }
      case OUTLINE -> {
        b.setTextColor(INK);
        b.setBackground(shape(BG, LINE, 16));
      }
      case QUIET -> {
        b.setTextColor(TEAL);
        b.setBackground(null);
      }
    }
    b.setOnClickListener(click);
    return b;
  }

  TextView back(String label, View.OnClickListener click) {
    TextView t = text("‹  " + label, 15, MUTED);
    t.setMinHeight(dp(44));
    t.setGravity(Gravity.CENTER_VERTICAL);
    t.setOnClickListener(click);
    return t;
  }

  TextView chip(String label, boolean selected, View.OnClickListener click) {
    TextView t = text(label, 14, INK);
    t.setTypeface(selected ? MEDIUM : Typeface.DEFAULT);
    t.setPadding(dp(14), dp(9), dp(14), dp(9));
    t.setBackground(selected ? shape(TEAL_TINT, TEAL, 999) : shape(BG, LINE, 999));
    if (selected) ((GradientDrawable) t.getBackground()).setStroke(dp(2), TEAL);
    t.setOnClickListener(click);
    return t;
  }

  void styleChip(TextView t, boolean selected) {
    t.setTypeface(selected ? MEDIUM : Typeface.DEFAULT);
    GradientDrawable d = shape(selected ? TEAL_TINT : BG, selected ? TEAL : LINE, 999);
    if (selected) d.setStroke(dp(2), TEAL);
    t.setBackground(d);
  }

  EditText input(String value, String hint, boolean multiLine) {
    EditText e = new EditText(c);
    e.setText(value);
    e.setHint(hint);
    e.setTextSize(16);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setBackground(shape(BG, LINE, 14));
    e.setPadding(dp(14), dp(12), dp(14), dp(12));
    if (multiLine) {
      e.setInputType(
          InputType.TYPE_CLASS_TEXT
              | InputType.TYPE_TEXT_FLAG_MULTI_LINE
              | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
      e.setGravity(Gravity.TOP | Gravity.START);
      e.setMinLines(3);
    } else e.setSingleLine(true);
    return e;
  }

  Switch toggle(boolean checked) {
    Switch s = new Switch(c);
    s.setChecked(checked);
    int[][] states = {{android.R.attr.state_checked}, {}};
    s.setThumbTintList(new ColorStateList(states, new int[] {BG, BG}));
    s.setTrackTintList(new ColorStateList(states, new int[] {TEAL, 0xffc9d3cf}));
    return s;
  }

  /** A settings row: title, optional subtitle, optional trailing view, chevron when clickable. */
  LinearLayout row(String title, String subtitle, View trailing, View.OnClickListener click) {
    LinearLayout r = horizontal();
    r.setPadding(dp(16), dp(13), dp(16), dp(13));
    r.setMinimumHeight(dp(56));
    LinearLayout texts = vertical();
    texts.addView(text(title, 16, INK));
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView s = text(subtitle, 13, MUTED);
      s.setMaxLines(2);
      s.setEllipsize(android.text.TextUtils.TruncateAt.END);
      texts.addView(s);
    }
    r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    if (trailing != null) r.addView(trailing);
    if (click != null) {
      if (trailing == null) {
        TextView chevron = text("›", 22, MUTED);
        chevron.setPadding(dp(10), 0, 0, 0);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(chevron);
      }
      r.setOnClickListener(click);
    }
    return r;
  }

  View divider() {
    View v = new View(c);
    v.setBackgroundColor(DIVIDER);
    v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
    return v;
  }

  /** Wraps children onto new lines, for chips. */
  static final class Flow extends ViewGroup {
    private final int gap;

    Flow(Context context, int gapPx) {
      super(context);
      gap = gapPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int max = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
      int x = 0, y = 0, line = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View v = getChildAt(i);
        measureChild(v, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), heightSpec);
        if (x > 0 && x + v.getMeasuredWidth() > max) {
          x = 0;
          y += line + gap;
          line = 0;
        }
        x += v.getMeasuredWidth() + gap;
        line = Math.max(line, v.getMeasuredHeight());
      }
      setMeasuredDimension(
          MeasureSpec.getSize(widthSpec), y + line + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int max = r - l - getPaddingLeft() - getPaddingRight();
      int x = 0, y = 0, line = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View v = getChildAt(i);
        if (x > 0 && x + v.getMeasuredWidth() > max) {
          x = 0;
          y += line + gap;
          line = 0;
        }
        v.layout(
            getPaddingLeft() + x,
            getPaddingTop() + y,
            getPaddingLeft() + x + v.getMeasuredWidth(),
            getPaddingTop() + y + v.getMeasuredHeight());
        x += v.getMeasuredWidth() + gap;
        line = Math.max(line, v.getMeasuredHeight());
      }
    }
  }

  Flow flow() {
    return new Flow(c, dp(8));
  }
}
