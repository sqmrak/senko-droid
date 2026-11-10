package com.senko.droid;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * grouped rows on plates, the settings idiom of the ios app: a muted section
 * title, a card of rows with a glyph, a title and a value or a switch
 */
final class Rows {
    private final BaseActivity activity;
    private final ScrollView scroll;
    private final LinearLayout root;
    private LinearLayout group;

    /** a row after it is added, for the screen to change its value */
    static final class Line {
        final LinearLayout view;
        final TextView title;
        final TextView value;

        Line(LinearLayout view, TextView title, TextView value) {
            this.view = view;
            this.title = title;
            this.value = value;
        }
    }

    private final long created = android.os.SystemClock.uptimeMillis();
    private int entered;

    Rows(BaseActivity activity) {
        this.activity = activity;
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));
    }

    View view() {
        return scroll;
    }

    LinearLayout root() {
        return root;
    }

    void clear() {
        root.removeAllViews();
        group = null;
    }

    /** starts a group of rows. the title view comes back so a screen can make it tappable, null without a title */
    TextView section(String english) {
        TextView title = null;
        if (english != null) {
            title = Ui.text(activity, Tr.t(english), 14, Theme.heading(), true);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            title.setPadding(Ui.dp(8), Ui.dp(16), Ui.dp(8), Ui.dp(6));
            root.addView(title);
        } else {
            View gap = new View(activity);
            root.addView(gap, new LinearLayout.LayoutParams(-1, Ui.dp(10)));
        }
        group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        Ui.background(group, Ui.plate(Theme.current().radius, 0));
        root.addView(group, new LinearLayout.LayoutParams(-1, -2));
        enter(title != null ? title : group, group);
        return title;
    }

    /**
     * the groups of a screen rise and fade in one after another as it opens. only groups added in the
     * first moments animate, so a list that is rebuilt later on a change does not replay it
     */
    private void enter(View heading, View card) {
        if (android.os.SystemClock.uptimeMillis() - created > 400 || entered >= 8) return;
        long delay = entered++ * (Shapes.expressive() ? 35L : 55L);
        for (View view : heading == card ? new View[] { card } : new View[] { heading, card }) {
            if (Shapes.expressive()) {
                // each card grows from a little smaller and wobbles into place, the fade runs apart
                ViewFx.enter(view, 0f, 0.9f, delay, Motion.SPRING_MS, Motion.SPRING);
                continue;
            }
            ViewFx.enter(view, Ui.dp(20), 1f, delay, Motion.ENTER_MS, Motion.ENTER);
        }
    }

    void footer(String text) {
        TextView view = Ui.text(activity, text, 12, Theme.color(Theme.INK_MUTED), false);
        view.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), 0);
        if (Decor.wallpaperName() != null) {
            // muted ink on a photograph cannot be read: a plate in the card color goes behind the line
            view.setTextColor(Theme.ink());
            view.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
            Ui.background(view, Ui.solid(Ui.alpha(Theme.color(Theme.CELL_HI), 0.88f), 10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = Ui.dp(8);
            root.addView(view, params);
            return;
        }
        root.addView(view);
    }

    private LinearLayout shell(String glyph) {
        if (group == null) section(null);
        if (group.getChildCount() > 0) group.addView(Ui.divider(activity));
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(Ui.dp(54));
        line.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        if (glyph != null) {
            Drawable icon = Icons.glyph(activity, glyph, Theme.ink());
            if (icon != null) {
                ImageView image = new ImageView(activity);
                image.setImageDrawable(icon);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
                params.rightMargin = Ui.dp(12);
                line.addView(image, params);
            }
        }
        group.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return line;
    }

    /** a row that opens something, with the current value at the right */
    Line action(String glyph, String english, String value, boolean chevron, View.OnClickListener click) {
        LinearLayout line = shell(glyph);
        TextView title = Ui.text(activity, Tr.t(english), 16, Theme.ink(), false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(title, Ui.weighted(1));
        TextView text = Ui.text(activity, value == null ? "" : value, 15, Theme.color(Theme.INK_MUTED), false);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setGravity(Gravity.END);
        text.setPadding(Ui.dp(8), 0, 0, 0);
        line.addView(text, new LinearLayout.LayoutParams(-2, -2));
        if (chevron) {
            View arrow = new View(activity);
            Ui.background(arrow, Icons.shape(activity, Icons.Shape.CHEVRON, Theme.color(Theme.INK_MUTED)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(14));
            params.leftMargin = Ui.dp(8);
            line.addView(arrow, params);
        }
        if (click != null) {
            Ui.background(line, Ui.rowPress());
            line.setOnClickListener(click);
        }
        return new Line(line, title, text);
    }

    Line action(String glyph, String english, String value, View.OnClickListener click) {
        return action(glyph, english, value, true, click);
    }

    /** a row of text along the left edge, title over a value that may wrap, with the glyph at the right */
    Line detail(String glyph, String english, String value, View.OnClickListener click) {
        LinearLayout line = shell(null);
        line.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(activity, Tr.t(english), 16, Theme.ink(), false);
        column.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView text = Ui.text(activity, value == null ? "" : value, 14, Theme.color(Theme.INK_MUTED), false);
        text.setGravity(Gravity.START);
        if (value != null && !value.isEmpty()) column.addView(text, new LinearLayout.LayoutParams(-1, -2));
        line.addView(column, Ui.weighted(1));
        Drawable icon = glyph == null ? null : Icons.glyph(activity, glyph, Theme.ink());
        if (icon != null) {
            ImageView image = new ImageView(activity);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
            params.leftMargin = Ui.dp(12);
            line.addView(image, params);
        }
        if (click != null) {
            Ui.background(line, Ui.rowPress());
            line.setOnClickListener(click);
        }
        return new Line(line, title, text);
    }

    /** a row with a switch */
    Toggle toggle(String glyph, String english, boolean on, boolean enabled, Toggle.Listener listener) {
        LinearLayout line = shell(glyph);
        TextView title = Ui.text(activity, Tr.t(english), 16, Theme.ink(), false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(title, Ui.weighted(1));
        Toggle toggle = new Toggle(activity);
        toggle.setContentDescription(Tr.t(english));
        toggle.setChecked(on);
        toggle.setEnabled(enabled);
        toggle.setListener(listener);
        line.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return toggle;
    }

    /** a row that holds any view, such as a segmented control or a swatch strip */
    void custom(View content) {
        if (group == null) section(null);
        if (group.getChildCount() > 0) group.addView(Ui.divider(activity));
        group.addView(content, new LinearLayout.LayoutParams(-1, -2));
    }
}
