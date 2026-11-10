package com.senko.droid;

import android.content.Context;

import java.io.File;

/**
 * where the engine keeps its files. it takes the app's private directory once,
 * and these names are the ones core/senko_paths.h expects beside it
 */
final class Paths {
    static final String CA_BUNDLE = "cacert.pem";

    private Paths() {}

    static File socket(Context context) {
        return new File(context.getFilesDir(), "ctl.sock");
    }

    /** the unix socket of the root helper, android before 4.0 only */
    static File rootSocket(Context context) {
        return new File(context.getFilesDir(), "rootd.sock");
    }

    /** the engine writes it next to the socket, with the .token ending */
    static File token(Context context) {
        return new File(context.getFilesDir(), "ctl.token");
    }

    static File caBundle(Context context) {
        return new File(context.getFilesDir(), CA_BUNDLE);
    }

    static File awgDir(Context context) {
        return new File(context.getFilesDir(), "awg");
    }
}
