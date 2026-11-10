package com.senko.droid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * an on/off switch drawn in code: android.widget.Switch cannot take the theme
 * colours on api 16 and looks different on every vendor skin
 */
final class Toggle extends View {
    interface Listener {
        void changed(Toggle toggle, boolean on);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final android.graphics.Path blob = new android.graphics.Path();
    private boolean on;
    // 0 is off and 1 is on, the knob and the fill follow it while a tap animates
    private float position;
    private Tween slide;
    private Listener listener;

    Toggle(Context context) {
        super(context);
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean next = !on;
                setChecked(next, true);
                if (listener != null) listener.changed(Toggle.this, next);
            }
        });
    }

    void setListener(Listener next) {
        listener = next;
    }

    boolean isChecked() {
        return on;
    }

    void setChecked(boolean value) {
        setChecked(value, false);
    }

    private void setChecked(boolean value, boolean animate) {
        if (slide != null) {
            slide.cancel();
            slide = null;
        }
        if (on == value && position == (value ? 1f : 0f)) return;
        on = value;
        if (!animate || getWindowToken() == null) {
            position = value ? 1f : 0f;
            invalidate();
            return;
        }
        slide = Tween.ofFloat(position, value ? 1f : 0f);
        slide.setDuration(Shapes.expressive() ? Motion.SETTLE_MS : 170);
        if (Shapes.expressive()) slide.setInterpolator(Motion.SETTLE);
        slide.addUpdateListener(new Tween.Update() {
            @Override
            public void onUpdate(Tween animation) {
                position = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        slide.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (slide != null) {
            slide.cancel();
            slide = null;
            position = on ? 1f : 0f;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Ui.dp(50), Ui.dp(48));
    }

    @android.annotation.TargetApi(14)
    @Override
    public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(on);
    }

    @android.annotation.TargetApi(14)
    @Override
    public void onInitializeAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName("android.widget.Switch");
        event.setChecked(on);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float top = (h - Ui.dp(24)) / 2f;
        box.set(Ui.dp(1), top, w - Ui.dp(1), top + Ui.dp(24));
        float radius = box.height() / 2;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isEnabled() ? Theme.color(Theme.WELL) : Ui.alpha(Theme.ink(), 0.15f));
        canvas.drawRoundRect(box, radius, radius, paint);
        // the knob may swing past either end while it settles, the fill and the swell stay within 0..1
        float shown = Motion.clamp(position);
        if (isEnabled() && shown > 0f) {
            paint.setColor(Ui.alpha(Theme.color(Theme.ACCENT), shown));
            canvas.drawRoundRect(box, radius, radius, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, Ui.dp(0.7f)));
        paint.setColor(Ui.alpha(Theme.ink(), 0.25f));
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffffffff);
        float cx = box.left + radius + (box.width() - 2 * radius) * position;
        if (Shapes.expressive()) {
            // a small thumb when off, a big one when on, and a lobed blob in between that turns as it slides
            float thumb = radius - Ui.dp(6) + Ui.dp(4) * shown;
            float swell = 0.16f * (float) Math.sin(Math.PI * shown);
            canvas.drawPath(Shapes.cookie(blob, cx, box.centerY(), thumb / (1 + swell), swell, 6,
                    position * Math.PI), paint);
        } else {
            canvas.drawCircle(cx, box.centerY(), radius - Ui.dp(2), paint);
        }
    }
}
