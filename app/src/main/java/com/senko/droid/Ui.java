package com.senko.droid;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * small helpers that paint views from the theme. the ios app draws everything
 * in code, and so does this one: a screen asks for a plate, a label or a row
 * and the colours come from Theme at the moment it is built
 */
final class Ui {
    private Ui() {}

    static float density() {
        return Resources.getSystem().getDisplayMetrics().density;
    }

    static int dp(float value) {
        return Math.round(value * density());
    }

    static int alpha(int color, float fraction) {
        int a = Math.round(Color.alpha(color) * fraction);
        return (color & 0x00ffffff) | (a << 24);
    }

    /** a step toward white (positive) or black (negative) */
    static int shade(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        float target = amount >= 0 ? 255f : 0f;
        float t = Math.abs(amount);
        return Color.argb(Color.alpha(color), Math.round(r + (target - r) * t),
                Math.round(g + (target - g) * t), Math.round(b + (target - b) * t));
    }

    /** the readable ink on a coloured fill: white on dark colours, black on light ones */
    static int onColor(int fill) {
        double luminance = (0.299 * Color.red(fill) + 0.587 * Color.green(fill) + 0.114 * Color.blue(fill)) / 255.0;
        return luminance > 0.62 ? 0xff111111 : 0xffffffff;
    }

    static GradientDrawable gradient(int top, int bottom, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { top, bottom });
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    static GradientDrawable solid(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    /** the page: the background colour fading to its lower tone, under the wallpaper of themes that have one */
    static Drawable page() {
        return Decor.page();
    }

    private static GradientDrawable plateFill(float radiusDp, int base) {
        int top = base == 0 ? Theme.color(Theme.CELL_HI) : base;
        int bottom = base == 0 ? Theme.color(Theme.CELL_LO) : shade(base, -0.08f);
        return plateFill(radiusDp, top, bottom);
    }

    /** setColors on a gradient drawable needs android 4.1, so the colors go in at construction */
    private static GradientDrawable plateFill(float radiusDp, int top, int bottom) {
        GradientDrawable fill = gradient(top, bottom, radiusDp);
        fill.setStroke(Math.max(1, dp(0.6f)), alpha(Theme.ink(), Theme.isLight() ? 0.10f : 0.14f));
        return fill;
    }

    /**
     * the Material Design theme lifts its cards: a soft strip under the card stands in for the
     * elevation shadow, which a view background cannot cast before api 21. fields keep a flat well
     */
    private static Drawable elevated(GradientDrawable fill, float radiusDp, int base) {
        if (base != 0 || !Theme.current().flags.contains("material5")) return fill;
        fill.setStroke(0, 0);
        int shadowColor = Theme.isLight() ? 0x2e000000 : 0x66000000;
        LayerDrawable layers = new LayerDrawable(new Drawable[] { solid(shadowColor, radiusDp), fill });
        layers.setLayerInset(0, 0, dp(1.5f), 0, 0);
        layers.setLayerInset(1, 0, 0, 0, dp(2));
        return layers;
    }

    /** the rounded card every home and list control sits on */
    static Drawable plate(float radiusDp, int base) {
        return elevated(plateFill(radiusDp, base), radiusDp, base);
    }

    static Drawable plate() {
        return plate(Theme.current().radius, 0);
    }

    /** a plate that darkens a little under a finger */
    static Drawable pressablePlate(float radiusDp, int base) {
        StateListDrawable states = new StateListDrawable();
        int top = base == 0 ? Theme.color(Theme.CELL_HI) : base;
        GradientDrawable pressed = plateFill(radiusDp, shade(top, -0.10f), shade(top, -0.18f));
        states.addState(new int[] { android.R.attr.state_pressed }, elevated(pressed, radiusDp, base));
        states.addState(new int[] { android.R.attr.state_focused }, elevated(focusRing(radiusDp, top), radiusDp, base));
        states.addState(new int[] {}, plate(radiusDp, base));
        return states;
    }

    /** a transparent row that lightens under a finger */
    static Drawable rowPress() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed },
                new ColorDrawable(alpha(Theme.ink(), 0.10f)));
        states.addState(new int[] { android.R.attr.state_focused },
                new ColorDrawable(alpha(Theme.ink(), 0.16f)));
        states.addState(new int[] {}, new ColorDrawable(0));
        return states;
    }

    /** the plate with an accent outline, where a d-pad or a remote has put the focus */
    private static GradientDrawable focusRing(float radiusDp, int top) {
        GradientDrawable ring = plateFill(radiusDp, top, shade(top, -0.08f));
        ring.setStroke(dp(2), Theme.color(Theme.ACCENT));
        return ring;
    }

    /** the highlight of a list row under a d-pad. a finger leaves the rows as they were */
    static Drawable listSelector() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_focused }, new ColorDrawable(alpha(Theme.ink(), 0.16f)));
        states.addState(new int[] {}, new ColorDrawable(0));
        return states;
    }

    /**
     * the view sinks a little under a finger and springs back on release. the listener never
     * consumes the touch, so clicks and long presses behave as before
     */
    static void pressEffect(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    float scale = Shapes.expressive() ? 0.96f : 0.92f;
                    ViewFx.scaleTo(v, scale, Shapes.expressive() ? 110 : 80,
                            new android.view.animation.DecelerateInterpolator());
                } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                    // released, the view wobbles back like the buttons of the exteria editor
                    ViewFx.scaleTo(v, 1f, Shapes.expressive() ? Motion.WOBBLE_MS : Motion.SETTLE_MS,
                            Shapes.expressive() ? Motion.WOBBLE : Motion.SETTLE);
                }
                return false;
            }
        });
    }

    static void background(View view, Drawable drawable) {
        Compat.setBackground(view, drawable);
    }

    static TextView text(Context context, CharSequence text, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        Typeface face = Fonts.forTheme(context, bold);
        if (face != null) view.setTypeface(face);
        else if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    static TextView label(Context context, CharSequence text) {
        return text(context, text, 16, Theme.ink(), false);
    }

    static TextView caption(Context context, CharSequence text) {
        return text(context, text, 13, Theme.muted(), false);
    }

    static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(alpha(Theme.ink(), 0.12f));
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(0.5f))));
        return line;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    static FrameLayout.LayoutParams centered(int width, int height) {
        return new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
    }

    static void padding(View view, float left, float top, float right, float bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    /** a drawable made of two layers, the lower one first */
    static Drawable layers(Drawable... drawables) {
        return new LayerDrawable(drawables);
    }
}
