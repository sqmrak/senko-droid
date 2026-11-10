package com.senko.droid;

import android.app.Application;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/** loads what every screen needs before the first one is built */
public final class SenkoApp extends Application {
    private static final String TAG = "senko";

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Decor.init(this);
        try {
            load("i18n.tsv", false);
            load("i18n_extra.tsv", false);
            load("errors.tsv", true);
            InputStream themes = getAssets().open("themes.tsv");
            try {
                Theme.load(themes);
            } finally {
                themes.close();
            }
            InputStream droid = getAssets().open("themes_droid.tsv");
            try {
                Theme.add(droid);
            } finally {
                droid.close();
            }
        } catch (IOException e) {
            // the screens still run: english text and the default colours
            Log.e(TAG, "assets could not be read", e);
        }
        Tr.setLanguage(Prefs.language());
        CustomThemes.register();
        Theme.apply(Prefs.theme(), Prefs.light(), Prefs.ios6Accent());
        Senko.init(this);
    }

    private void load(String name, boolean errors) throws IOException {
        InputStream in = getAssets().open(name);
        try {
            if (errors) Tr.loadErrors(in);
            else Tr.load(in);
        } finally {
            in.close();
        }
    }
}
