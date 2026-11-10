package com.senko.droid;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** the questions the screens ask: a choice, a yes or no, and a line of text */
final class Dialogs {
    private Dialogs() {}

    interface Pick {
        void picked(int index);
    }

    interface Text {
        void entered(String text);
    }

    /** a list of choices. a negative index is never reported, cancelling does nothing */
    static void menu(Context context, String englishTitle, List<String> labels, final Pick pick) {
        CharSequence[] items = new CharSequence[labels.size()];
        for (int i = 0; i < items.length; ++i) items[i] = labels.get(i);
        new SenkoDialog.Builder(context)
                .setTitle(Tr.t(englishTitle))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pick.picked(which);
                    }
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    static void confirm(Context context, String englishTitle, String englishMessage, String englishAction,
                        final Runnable action) {
        new SenkoDialog.Builder(context)
                .setTitle(Tr.t(englishTitle))
                .setMessage(Tr.t(englishMessage))
                .setNegativeButton(Tr.t("Cancel"), null)
                .setPositiveButton(Tr.t(englishAction), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        action.run();
                    }
                })
                .show();
    }

    static void message(Context context, String title, String text) {
        new SenkoDialog.Builder(context)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(Tr.t("OK"), null)
                .show();
    }

    /** a failure: the sad sticker leans over the card */
    static void error(Context context, String title, String text) {
        new SenkoDialog.Builder(context)
                .setMood(SenkoDialog.MOOD_ERROR)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(Tr.t("OK"), null)
                .show();
    }

    static String clipboard(Context context) {
        return Compat.clipboardText(context);
    }

    static void copy(Context context, String label, String text) {
        Compat.copyText(context, label, text);
    }

    /** a line of text with a paste button, the way a link is usually entered */
    static void prompt(Context context, String englishTitle, String englishMessage, String initial,
                       int inputType, String englishAction, final Text done) {
        final EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(initial == null ? "" : initial);
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(20), Ui.dp(4), Ui.dp(20), 0);
        if (englishMessage != null) {
            TextView hint = new TextView(context);
            hint.setText(Tr.t(englishMessage));
            box.addView(hint);
        }
        box.addView(input);
        final Context ctx = context;
        SenkoDialog dialog = new SenkoDialog.Builder(context)
                .setTitle(Tr.t(englishTitle))
                .setView(box)
                .setNegativeButton(Tr.t("Cancel"), null)
                .setNeutralButton(Tr.t("Paste"), null)
                .setPositiveButton(Tr.t(englishAction), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String text = input.getText().toString().trim();
                        if (!text.isEmpty()) done.entered(text);
                    }
                })
                .create();
        dialog.show();
        // the dialog stays open, so the pasted text can be read before it is used
        dialog.getButton(SenkoDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String text = clipboard(ctx);
                if (!text.isEmpty()) {
                    input.setText(text);
                    input.setSelection(text.length());
                }
            }
        });
    }

    static int urlInput() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    static int plainInput() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    /** several lines of text, for a config the user edits whole */
    static void editor(Context context, String englishTitle, String initial, String englishAction, final Text done) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(8);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextSize(12);
        input.setText(initial == null ? "" : initial);
        LinearLayout box = new LinearLayout(context);
        box.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new SenkoDialog.Builder(context)
                .setTitle(Tr.t(englishTitle))
                .setView(box)
                .setNegativeButton(Tr.t("Cancel"), null)
                .setPositiveButton(Tr.t(englishAction), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String text = input.getText().toString();
                        if (!text.trim().isEmpty()) done.entered(text);
                    }
                })
                .show();
    }
}
