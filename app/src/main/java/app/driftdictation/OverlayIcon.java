package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/**
 * A 24dp glyph on a transparent 48dp target; while recording, a red pill with timer and stop; once
 * the note is copied, a green "Ready to paste" pill.
 */
final class OverlayIcon extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private String state = DictationService.READY;
  private long elapsed;
  private int attached;

  /** While recording, the pill's left end is the 📎: the width of that touch area. */
  static final int ATTACH_DP = 46;

  /** Attachments waiting for this dictation, shown next to the 📎. */
  void attached(int count) {
    if (attached == count) return;
    attached = count;
    invalidate();
  }

  OverlayIcon(Context context) {
    super(context);
    setBackgroundColor(Color.TRANSPARENT);
    setClickable(true);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  void update(String next, int opacity) {
    update(next, opacity, 0);
  }

  void update(String next, int opacity, long elapsedMillis) {
    state = next;
    elapsed = elapsedMillis;
    float alpha = Math.max(0.25f, Math.min(1f, opacity / 100f));
    boolean pill = state.equals(DictationService.RECORDING) || state.equals(DictationService.COPIED);
    setAlpha(pill ? Math.max(alpha, 0.92f) : alpha);
    setContentDescription(
        switch (state) {
          case DictationService.RECORDING -> tr("Stop recording");
          case DictationService.UPLOADING, DictationService.STOPPING -> tr("Transcribing");
          case DictationService.EMPTY -> tr("No speech recognized");
          case DictationService.ERROR -> tr("Open recording recovery");
          case DictationService.COPIED -> tr("Ready to paste");
          default -> tr("Start recording");
        });
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float d = getResources().getDisplayMetrics().density;
    if (state.equals(DictationService.RECORDING) && getWidth() > getHeight() * 1.5f) {
      pill(canvas, d);
      return;
    }
    if (state.equals(DictationService.COPIED) && getWidth() > getHeight() * 1.5f) {
      ready(canvas, d);
      return;
    }
    canvas.save();
    canvas.translate(getWidth() / 2f - 12 * d, getHeight() / 2f - 12 * d);
    canvas.scale(d, d);
    paint.setColor(state.equals(DictationService.RECORDING) ? 0xffff535d : 0xff28b89a);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2.2f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    switch (state) {
      case DictationService.RECORDING -> {
        canvas.drawCircle(12, 12, 10, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(8, 8, 16, 16, 1, 1, paint);
      }
      case DictationService.UPLOADING, DictationService.STOPPING -> {
        canvas.drawArc(3, 3, 21, 21, (SystemClock.uptimeMillis() % 1000) * .36f, 250, false, paint);
        if (isAttachedToWindow()) postInvalidateOnAnimation();
      }
      case DictationService.COPIED -> {
        Path p = new Path();
        p.moveTo(3, 12);
        p.lineTo(9, 18);
        p.lineTo(21, 5);
        canvas.drawPath(p, paint);
      }
      case DictationService.EMPTY -> canvas.drawLine(6, 12, 18, 12, paint);
      case DictationService.ERROR -> {
        paint.setColor(0xffed9b35);
        canvas.drawCircle(12, 12, 9, paint);
        canvas.drawLine(12, 6, 12, 13, paint);
        canvas.drawPoint(12, 17, paint);
      }
      default -> {
        canvas.drawRoundRect(9, 2, 15, 14, 3, 3, paint);
        canvas.drawArc(5, 6, 19, 18, 0, 180, false, paint);
        canvas.drawLine(5, 10, 5, 12, paint);
        canvas.drawLine(19, 10, 19, 12, paint);
        canvas.drawLine(12, 18, 12, 22, paint);
        canvas.drawLine(8, 22, 16, 22, paint);
      }
    }
    canvas.restore();
  }

  private void pill(Canvas canvas, float d) {
    float w = getWidth(), h = getHeight(), r = h / 2f - 2 * d;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xffe5484d);
    canvas.drawRoundRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, r, r, paint);
    // 📎 on the left: add context to this dictation.
    paint.setColor(0x38ffffff);
    canvas.drawCircle(h / 2f, h / 2f, 16 * d, paint);
    paint.setColor(0xffffffff);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2f * d);
    paint.setStrokeCap(Paint.Cap.ROUND);
    float cx = h / 2f, cy = h / 2f;
    canvas.drawRoundRect(cx - 4 * d, cy - 8 * d, cx + 4 * d, cy + 8 * d, 4 * d, 4 * d, paint);
    canvas.drawLine(cx, cy - 4 * d, cx, cy + 4 * d, paint);
    paint.setStyle(Paint.Style.FILL);
    if (attached > 0) {
      paint.setTextSize(11 * d);
      paint.setTypeface(Typeface.DEFAULT_BOLD);
      canvas.drawText(String.valueOf(attached), cx + 9 * d, cy - 6 * d, paint);
    }
    float x = ATTACH_DP * d;
    canvas.drawCircle(x + 8 * d, h / 2f, 4.5f * d, paint);
    paint.setTextSize(15 * d);
    paint.setTypeface(Typeface.MONOSPACE);
    Paint.FontMetrics m = paint.getFontMetrics();
    canvas.drawText(
        AudioFiles.elapsed(elapsed), x + 20 * d, h / 2f - (m.ascent + m.descent) / 2f, paint);
    paint.setColor(0x38ffffff);
    canvas.drawCircle(w - h / 2f, h / 2f, 16 * d, paint);
    paint.setColor(0xffffffff);
    canvas.drawRoundRect(
        w - h / 2f - 6 * d,
        h / 2f - 6 * d,
        w - h / 2f + 6 * d,
        h / 2f + 6 * d,
        2 * d,
        2 * d,
        paint);
  }

  /** Width of the "Ready to paste" pill: check mark, text and padding. */
  int copiedWidth() {
    float d = getResources().getDisplayMetrics().density;
    paint.setTextSize(15 * d);
    paint.setTypeface(Typeface.DEFAULT_BOLD);
    return Math.round(paint.measureText(tr("Ready to paste")) + 54 * d);
  }

  private void ready(Canvas canvas, float d) {
    float w = getWidth(), h = getHeight(), r = h / 2f - 2 * d;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xff0b7a5e);
    canvas.drawRoundRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, r, r, paint);
    paint.setColor(0xffffffff);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2.4f * d);
    paint.setStrokeCap(Paint.Cap.ROUND);
    Path check = new Path();
    check.moveTo(13 * d, h / 2f);
    check.lineTo(18 * d, h / 2f + 5 * d);
    check.lineTo(27 * d, h / 2f - 5 * d);
    canvas.drawPath(check, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setTextSize(15 * d);
    paint.setTypeface(Typeface.DEFAULT_BOLD);
    Paint.FontMetrics m = paint.getFontMetrics();
    canvas.drawText(tr("Ready to paste"), 36 * d, h / 2f - (m.ascent + m.descent) / 2f, paint);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
