package com.senko.droid;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;

/** the calls that need a newer android than 2.3, each with the fallback the old one has */
final class Compat {
    private Compat() {
    }

    /** View.setBackground arrived with android 4.1, the older name is the same call */
    @SuppressWarnings("deprecation")
    static void setBackground(View view, Drawable drawable) {
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            view.setBackground(drawable);
        } else {
            view.setBackgroundDrawable(drawable);
        }
    }

    /** removeOnGlobalLayoutListener arrived with android 4.1, the older name is the same call */
    @SuppressWarnings("deprecation")
    static void removeLayoutListener(android.view.ViewTreeObserver observer,
            android.view.ViewTreeObserver.OnGlobalLayoutListener listener) {
        if (android.os.Build.VERSION.SDK_INT >= 16) {
            observer.removeOnGlobalLayoutListener(listener);
        } else {
            observer.removeGlobalOnLayoutListener(listener);
        }
    }

    /** Activity.recreate starts at android 3.0, below it the screen is started again in place */
    static void recreate(Activity activity) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            activity.recreate();
            return;
        }
        Intent again = activity.getIntent();
        activity.finish();
        activity.overridePendingTransition(0, 0);
        activity.startActivity(again);
        activity.overridePendingTransition(0, 0);
    }

    /** text selection by long press exists from android 3.0, the text stays plain below it */
    static void selectable(TextView view, boolean value) {
        if (android.os.Build.VERSION.SDK_INT >= 11) view.setTextIsSelectable(value);
    }

    /** android 2.3 keeps string sets out of shared preferences, so they are one newline joined string */
    static Set<String> stringSet(SharedPreferences store, String key) {
        Set<String> out = new HashSet<String>();
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            Set<String> saved = store.getStringSet(key, null);
            if (saved != null) out.addAll(saved);
            return out;
        }
        String joined = store.getString(key, "");
        if (joined == null || joined.isEmpty()) return out;
        for (String line : joined.split("\n")) {
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    static void putStringSet(SharedPreferences.Editor editor, String key, Set<String> values) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            editor.putStringSet(key, values);
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (value.indexOf('\n') >= 0) continue;
            if (joined.length() > 0) joined.append('\n');
            joined.append(value);
        }
        editor.putString(key, joined.toString());
    }

    @SuppressWarnings("deprecation")
    static String clipboardText(Context context) {
        Object service = context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 11) return Api11.clipboardText(context, service);
        CharSequence text = ((android.text.ClipboardManager) service).getText();
        return text == null ? "" : text.toString().trim();
    }

    @SuppressWarnings("deprecation")
    static void copyText(Context context, String label, String text) {
        Object service = context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            Api11.copyText(service, label, text);
            return;
        }
        ((android.text.ClipboardManager) service).setText(text);
    }

    /** an ongoing notification. Notification.Builder starts at 3.0 and build() at 4.1 */
    @SuppressWarnings("deprecation")
    static Notification ongoing(Context context, String title, String text, int icon, PendingIntent open) {
        if (android.os.Build.VERSION.SDK_INT >= 16) return Api11.ongoing(context, title, text, icon, open, true);
        if (android.os.Build.VERSION.SDK_INT >= 11) return Api11.ongoing(context, title, text, icon, open, false);
        Notification note = new Notification(icon, title, System.currentTimeMillis());
        note.flags |= Notification.FLAG_ONGOING_EVENT;
        // setLatestEventInfo left the sdk in android 6, only 2.3 and 3.x reach this line
        try {
            Notification.class.getMethod("setLatestEventInfo", Context.class, CharSequence.class,
                    CharSequence.class, PendingIntent.class).invoke(note, context, title, text, open);
        } catch (Exception e) {
            throw new IllegalStateException("no setLatestEventInfo on api " + android.os.Build.VERSION.SDK_INT, e);
        }
        return note;
    }

    /** kept apart so the classes of android 3.0 are never loaded on 2.3 */
    @android.annotation.TargetApi(16)
    private static final class Api11 {
        static String clipboardText(Context context, Object service) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) service;
            android.content.ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return "";
            CharSequence text = data.getItemAt(0).coerceToText(context);
            return text == null ? "" : text.toString().trim();
        }

        static void copyText(Object service, String label, String text) {
            ((android.content.ClipboardManager) service)
                    .setPrimaryClip(android.content.ClipData.newPlainText(label, text));
        }

        @SuppressWarnings("deprecation")
        static Notification ongoing(Context context, String title, String text, int icon, PendingIntent open,
                                    boolean build) {
            Notification.Builder builder = new Notification.Builder(context)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(icon)
                    .setContentIntent(open)
                    .setOngoing(true);
            return build ? builder.build() : builder.getNotification();
        }
    }
}
