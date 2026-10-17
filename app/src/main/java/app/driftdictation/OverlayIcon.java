package app.driftdictation;

import static app.driftdictation.I18n.tr;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/**
 * A 24dp glyph on a transparent 48dp target: the microphone (start), progress, a check, or a
 * warning. The floating panel (FloatingPanel) shows it when there are no buttons to show.
 */
final class OverlayIcon extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private String state = DictationService.READY;

  OverlayIcon(Context context) {
    super(context);
    setBackgroundColor(Color.TRANSPARENT);
    setClickable(true);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  void update(String next, int opacity) {
    state = next;
    setAlpha(Math.max(0.25f, Math.min(1f, opacity / 100f)));
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

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
