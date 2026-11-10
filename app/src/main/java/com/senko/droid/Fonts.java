package com.senko.droid;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

/** the Rubik files of the Material You theme, read from the assets once and kept */
final class Fonts {
    private Fonts() {}

    private static Typeface regular;
    private static Typeface bold;

    /** the face a theme asks for, null when it uses the system font or the file cannot be read */
    static synchronized Typeface forTheme(Context context, boolean wantBold) {
        if (!Theme.current().flags.contains("rubik")) return null;
        if (wantBold) {
            if (bold == null) bold = load(context, "fonts/Rubik-Bold.ttf");
            return bold;
        }
        if (regular == null) regular = load(context, "fonts/Rubik-Regular.ttf");
        return regular;
    }

    private static Typeface load(Context context, String path) {
        try {
            return Typeface.createFromAsset(context.getApplicationContext().getAssets(), path);
        } catch (RuntimeException e) {
            // a missing file leaves the system font, the screens still read
            Log.w("senko", "font " + path + " could not be read", e);
            return null;
        }
    }
}
