package com.senko.droid;

import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * a heading filled with a slow band of colours that drifts across the letters.
 * the animator only runs while the view is on screen, so a hidden screen draws nothing
 */
final class ShimmerText extends TextView {
    private static final long PERIOD_MS = 4200;

    private final int[] colors;
    private final Matrix matrix = new Matrix();
    private LinearGradient shader;
    private int shaderWidth;
    private float shift;
    private Tween drift;

    ShimmerText(Context context, int[] colors) {
        super(context);
        this.colors = colors;
    }

    /** the heading with its text, size and the theme's bold face already set */
    static ShimmerText create(Context context, CharSequence text, float sp) {
        ShimmerText view = new ShimmerText(context, palette());
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Theme.color(Theme.ACCENT));
        android.graphics.Typeface face = Fonts.forTheme(context, true);
        view.setTypeface(face != null ? face : android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    /** neutral tones keep the heading readable on light and dark pages */
    private static int[] palette() {
        if (Theme.isLight())
            return new int[] { 0xff111111, 0xff555555, 0xff111111 };
        return new int[] { 0xffffffff, 0xffcccccc, 0xffffffff };
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) return;
        // the band repeats mirrored, so the loop has no seam when the offset wraps
        shader = new LinearGradient(0, 0, w, 0, colors, null, Shader.TileMode.MIRROR);
        shaderWidth = w;
    }

    private void syncDrift() {
        boolean enabled = Tween.enabled();
        if (getWindowToken() == null || getWindowVisibility() != View.VISIBLE || !enabled) {
            if (drift != null) {
                drift.cancel();
                drift = null;
            }
            return;
        }
        if (drift != null) return;
        final float start = shift;
        drift = Tween.ofFloat(start, start + 1f);
        drift.setDuration(PERIOD_MS);
        drift.setInterpolator(new LinearInterpolator());
        drift.setRepeatCount(Tween.INFINITE);
        drift.addUpdateListener(new Tween.Update() {
            @Override
            public void onUpdate(Tween animation) {
                shift = (Float) animation.getAnimatedValue() % 1f;
                invalidate();
            }
        });
        drift.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncDrift();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncDrift();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (drift != null) {
            drift.cancel();
            drift = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        if (shader != null) {
            // a full mirrored cycle is two widths long
            matrix.setTranslate(shift * 2f * shaderWidth, 0);
            shader.setLocalMatrix(matrix);
            getPaint().setShader(shader);
        }
        super.onDraw(canvas);
    }
}
