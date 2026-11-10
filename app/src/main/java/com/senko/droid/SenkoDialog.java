package com.senko.droid;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * the app's own popup: a rounded card in the colors of the current theme with a sticker leaning
 * over its top edge. the builder takes the calls of AlertDialog.Builder the screens use, so a
 * screen changes only the class name. the system dialog looks different on every android from 2.3
 * to 14 and cannot carry a sticker
 */
final class SenkoDialog extends Dialog {
    static final int BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE;
    static final int BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE;
    static final int BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL;

    /** which sticker leans over the card */
    static final int MOOD_AUTO = -1;
    static final int MOOD_HAPPY = 0;   // a note that needs nothing from the user
    static final int MOOD_ASK = 1;     // a question or a choice
    static final int MOOD_INPUT = 2;   // a field to fill in
    static final int MOOD_ERROR = 3;   // something failed

    private static final int[] STICKERS = {
        R.drawable.sticker_smile, R.drawable.sticker_ask, R.drawable.sticker_input, R.drawable.sticker_error
    };
    private static final int CARD_MAX_DP = 400;
    private static final int CARD_MARGIN_DP = 20;

    private final Builder spec;
    private final View[] buttons = new View[3];

    private SenkoDialog(Context context, Builder spec) {
        super(context, android.R.style.Theme_Translucent_NoTitleBar);
        this.spec = spec;
    }

    /** the button view for BUTTON_POSITIVE, BUTTON_NEGATIVE or BUTTON_NEUTRAL, null when there is none */
    View getButton(int which) {
        int index = which == BUTTON_POSITIVE ? 0 : which == BUTTON_NEGATIVE ? 1 : 2;
        return buttons[index];
    }

    @Override
    protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int input = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (spec.view != null && hasField(spec.view)) input |= WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        window.setSoftInputMode(input);
        setCancelable(spec.cancelable);
        setCanceledOnTouchOutside(spec.cancelable);
        setContentView(build(), new ViewGroup.LayoutParams(-1, -1));
    }

    private static boolean hasField(View view) {
        if (view instanceof EditText) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (hasField(group.getChildAt(i))) return true;
        return false;
    }

    private int mood() {
        if (spec.mood != MOOD_AUTO) return spec.mood;
        if (spec.items != null) return MOOD_ASK;
        if (spec.view != null) return MOOD_INPUT;
        return spec.negativeText != null ? MOOD_ASK : MOOD_HAPPY;
    }

    private View build() {
        final Context context = getContext();
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        boolean short_screen = metrics.heightPixels < Ui.dp(460);
        int sticker = Ui.dp(short_screen ? 64 : 104);
        int overlap = sticker * 3 / 4;
        int cardWidth = Math.min(Ui.dp(CARD_MAX_DP), metrics.widthPixels - Ui.dp(2 * CARD_MARGIN_DP));

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (spec.cancelable) cancel();
            }
        });

        FrameLayout holder = new FrameLayout(context);
        FrameLayout.LayoutParams holderParams = new FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER);
        root.addView(holder, holderParams);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        Ui.background(card, Ui.plate(Theme.current().radius + 6, 0));
        card.setPadding(Ui.dp(18), sticker - overlap + Ui.dp(6), Ui.dp(18), Ui.dp(14));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -2);
        cardParams.topMargin = overlap;
        holder.addView(card, cardParams);

        ImageView face = new ImageView(context);
        face.setImageResource(STICKERS[mood()]);
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams faceParams = new FrameLayout.LayoutParams(sticker, sticker,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        holder.addView(face, faceParams);

        if (spec.title != null && spec.title.length() > 0) {
            TextView title = Ui.text(context, spec.title, 19, Theme.color(Theme.INK), true);
            title.setGravity(Gravity.CENTER);
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        if (spec.message != null && spec.message.length() > 0) {
            TextView message = Ui.text(context, spec.message, 15, Theme.color(Theme.INK_MUTED), false);
            message.setGravity(Gravity.CENTER);
            message.setPadding(0, Ui.dp(8), 0, Ui.dp(4));
            Compat.selectable(message, false);
            body.addView(message, new LinearLayout.LayoutParams(-1, -2));
        }
        if (spec.view != null) {
            // a view from a screen sits in a parent of its own once, which a second show would break
            ViewGroup old = (ViewGroup) spec.view.getParent();
            if (old != null) old.removeView(spec.view);
            body.addView(spec.view, new LinearLayout.LayoutParams(-1, -2));
        }
        if (spec.items != null) body.addView(items(context), new LinearLayout.LayoutParams(-1, -2));
        CappedScroll scroll = new CappedScroll(context, metrics.heightPixels * 55 / 100);
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -2);
        scrollParams.topMargin = Ui.dp(4);
        card.addView(scroll, scrollParams);

        LinearLayout row = buttonRow(context);
        if (row != null) {
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = Ui.dp(14);
            card.addView(row, rowParams);
        }

        // the sticker pops in over the card, which rises after it
        ViewFx.enter(card, Ui.dp(18), 1f, 0, Motion.POPUP_ENTER_MS, Motion.POPUP_ENTER);
        ViewFx.enter(face, 0f, 0.4f, 60, Motion.WOBBLE_MS, Motion.WOBBLE);
        return root;
    }

    private View items(Context context) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < spec.items.length; i++) {
            final int index = i;
            list.addView(Ui.divider(context));
            TextView item = Ui.text(context, spec.items[i], 16, Theme.color(Theme.ACCENT), false);
            item.setGravity(Gravity.CENTER);
            item.setPadding(Ui.dp(8), Ui.dp(13), Ui.dp(8), Ui.dp(13));
            Ui.background(item, Ui.rowPress());
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (spec.itemListener != null) spec.itemListener.onClick(SenkoDialog.this, index);
                }
            });
            list.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        return list;
    }

    private LinearLayout buttonRow(Context context) {
        CharSequence[] texts = { spec.positiveText, spec.negativeText, spec.neutralText };
        DialogInterface.OnClickListener[] listeners = { spec.positive, spec.negative, spec.neutral };
        int[] which = { BUTTON_POSITIVE, BUTTON_NEGATIVE, BUTTON_NEUTRAL };
        int count = 0;
        for (CharSequence text : texts) if (text != null) count++;
        if (count == 0) return null;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // cancel on the left, the action on the right, as the system dialogs of android 4 and later do
        int[] order = { 1, 2, 0 };
        boolean first = true;
        for (int index : order) {
            if (texts[index] == null) continue;
            TextView button = button(context, texts[index], index == 0 && count > 1 || count == 1);
            final DialogInterface.OnClickListener listener = listeners[index];
            final int id = which[index];
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (listener != null) listener.onClick(SenkoDialog.this, id);
                }
            });
            buttons[index] = button;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(44), 1f);
            if (!first) params.leftMargin = Ui.dp(8);
            row.addView(button, params);
            first = false;
        }
        return row;
    }

    private static TextView button(Context context, CharSequence text, boolean filled) {
        int accent = Theme.color(Theme.ACCENT);
        TextView button = Ui.text(context, text, 15, filled ? Ui.onColor(accent) : accent, true);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        float radius = Math.max(8, Theme.current().radius);
        if (filled) Ui.background(button, Ui.solid(accent, radius));
        else Ui.background(button, Ui.plate(radius, 0));
        Ui.pressEffect(button);
        return button;
    }

    /** a scroll view that never grows past a share of the screen, so a long message keeps the buttons in reach */
    private static final class CappedScroll extends ScrollView {
        private final int max;

        CappedScroll(Context context, int max) {
            super(context);
            this.max = max;
            setVerticalScrollBarEnabled(false);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(heightSpec);
            int limit = mode == MeasureSpec.UNSPECIFIED ? max : Math.min(max, MeasureSpec.getSize(heightSpec));
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    /** the calls of AlertDialog.Builder that the screens use */
    static final class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private View view;
        private CharSequence[] items;
        private DialogInterface.OnClickListener itemListener;
        private CharSequence positiveText;
        private CharSequence negativeText;
        private CharSequence neutralText;
        private DialogInterface.OnClickListener positive;
        private DialogInterface.OnClickListener negative;
        private DialogInterface.OnClickListener neutral;
        private int mood = MOOD_AUTO;
        private boolean cancelable = true;

        Builder(Context context) {
            this.context = context;
        }

        Builder setTitle(CharSequence value) {
            title = value;
            return this;
        }

        Builder setMessage(CharSequence value) {
            message = value;
            return this;
        }

        Builder setView(View value) {
            view = value;
            return this;
        }

        Builder setItems(CharSequence[] values, DialogInterface.OnClickListener listener) {
            items = values;
            itemListener = listener;
            return this;
        }

        Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            positiveText = text;
            positive = listener;
            return this;
        }

        Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            negativeText = text;
            negative = listener;
            return this;
        }

        Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
            neutralText = text;
            neutral = listener;
            return this;
        }

        /** MOOD_* picks the sticker. without it the content decides: a choice asks, a field inputs, a note smiles */
        Builder setMood(int value) {
            mood = value;
            return this;
        }

        Builder setCancelable(boolean value) {
            cancelable = value;
            return this;
        }

        SenkoDialog create() {
            return new SenkoDialog(context, this);
        }

        SenkoDialog show() {
            SenkoDialog dialog = create();
            dialog.show();
            return dialog;
        }
    }
}
