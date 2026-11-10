package com.senko.droid;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * the trust roots the engine verifies servers against. android before 7.1.1
 * lacks current roots and the engine has no system store path, so the bundle
 * ships in the apk and is copied beside the other files
 */
final class Roots {
    private Roots() {}

    static void ensure(Context context) throws IOException {
        File target = Paths.caBundle(context);
        InputStream in = context.getAssets().open(Paths.CA_BUNDLE);
        try {
            long size = 0;
            byte[] buffer = new byte[16 * 1024];
            File temp = new File(target.getPath() + ".new");
            FileOutputStream out = new FileOutputStream(temp);
            try {
                int got;
                while ((got = in.read(buffer)) > 0) {
                    out.write(buffer, 0, got);
                    size += got;
                }
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (size == 0) {
                temp.delete();
                throw new IOException("the bundled trust roots are empty");
            }
            // a replaced bundle keeps the same name, so a running engine never
            // sees a half written file
            if (!temp.renameTo(target)) {
                temp.delete();
                throw new IOException("cannot install the trust roots");
            }
        } finally {
            in.close();
        }
    }
}
