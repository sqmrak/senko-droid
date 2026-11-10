package com.senko.droid;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

/**
 * what a tablet or a television needs that a phone does not: a content column that stops
 * growing, and views a d-pad can reach. the ios app does the same with SenkoCardColumnWidth
 * and its pad branches
 */
final class Wide {
    private Wide() {}

    /** the widest a screen of rows grows, the 560 pt card column of ios plus its margins */
    static final int COLUMN_DP = 640;

    /** the short side is at least 600 dp, which is what the ios app calls an ipad */
    static boolean tablet() {
        DisplayMetrics metrics = android.content.res.Resources.getSystem().getDisplayMetrics();
        return Math.min(metrics.widthPixels, metrics.heightPixels) / metrics.density >= 600f;
    }

    static boolean television(Context context) {
        UiModeManager modes = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return modes != null && modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** a d-pad or a trackball moves the focus here, so views that only take taps must take focus too */
    static boolean dpad(Context context) {
        if (television(context)) return true;
        int navigation = context.getResources().getConfiguration().navigation;
        return navigation == Configuration.NAVIGATION_DPAD || navigation == Configuration.NAVIGATION_TRACKBALL;
    }

    static boolean hasCamera(Context context) {
        return context.getPackageManager().hasSystemFeature("android.hardware.camera")
                || context.getPackageManager().hasSystemFeature("android.hardware.camera.front");
    }

    /**
     * a plain View with a click listener is not focusable before api 26, so a remote could not
     * reach any row, button or switch. the walk runs after each layout pass, because rows are
     * added when a screen loads its data
     */
    static void reachable(View view) {
        if (view.isClickable() && !view.isFocusable()) view.setFocusable(true);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        // a list gives its rows the focus itself, through the selector
        if (group instanceof android.widget.AdapterView) return;
        for (int i = 0; i < group.getChildCount(); i++) reachable(group.getChildAt(i));
    }

    /**
     * one child in a column of at most COLUMN_DP, centred. the page background and the bar stay
     * full width, only the rows narrow, so a wallpaper still reaches the edges of a tablet
     */
    static final class Column extends ViewGroup implements android.view.ViewTreeObserver.OnGlobalLayoutListener {
        private final int cap = Ui.dp(COLUMN_DP);
        private final boolean walk;

        Column(Context context, boolean walk) {
            super(context);
            this.walk = walk;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (walk) getViewTreeObserver().addOnGlobalLayoutListener(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (walk) Compat.removeLayoutListener(getViewTreeObserver(), this);
            super.onDetachedFromWindow();
        }

        @Override
        public void onGlobalLayout() {
            reachable(this);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            View child = getChildCount() > 0 ? getChildAt(0) : null;
            if (child == null) return;
            int inner = Math.min(width, cap);
            child.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            View child = getChildCount() > 0 ? getChildAt(0) : null;
            if (child == null) return;
            int left = (r - l - child.getMeasuredWidth()) / 2;
            child.layout(left, 0, left + child.getMeasuredWidth(), b - t);
        }
    }
}
