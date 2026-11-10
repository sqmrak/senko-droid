package com.senko.droid;

import android.content.Context;

/**
 * the native engine: the same c core as the ios daemon, inside the app process.
 * it runs on a thread of its own and is driven over the control socket
 */
final class Engine {
    static {
        System.loadLibrary("senko");
    }

    // status codes of engine_run
    static final int OK = 0;

    private static native int nativeRun(String filesDir, String socketPath, String coreLibrary, EngineHost host);

    private static native void nativeStop();

    // the root helper of android 2.3 to 3.x, see rootd/. they talk to its unix socket

    /** a tun descriptor from the helper, owned by the caller */
    static native int nativeRootOpenTun(String socketPath, String address4, int prefix4, String dns4, int mtu)
            throws java.io.IOException;

    /** null when the socket now bypasses the tunnel, otherwise why not */
    static native String nativeRootProtect(String socketPath, int fd);

    /** null when the helper answers, otherwise why not */
    static native String nativeRootPing(String socketPath);

    /** the amnezia decoder of the engine. data is a vpn:// link, a .vpn body or its json */
    static native boolean nativeAmneziaLooksLike(byte[] data);

    /** the amneziawg config inside the bundle, null when the data is no bundle, IOException when it is an unusable one */
    static native String nativeAmneziaExtract(byte[] data) throws java.io.IOException;

    // the frames of an amnezia profile shown as a series of qr codes
    static native long nativeQrNew();

    static native void nativeQrFree(long series);

    /** 0 not a frame, 1 part collected, 2 repeat, 3 done, -1 no room */
    static native int nativeQrAdd(long series, String text);

    static native String nativeQrResult(long series);

    /**
     * whether libsenkocore.so, the go build of xray, may be loaded. its runtime was started on
     * android 4.4 on an x86 emulator and on nothing older. an arm device below android 5.0 has not
     * been tried, and a go runtime that fails while loading ends the process, so there the engine's
     * own backend is kept
     */
    @SuppressWarnings("deprecation")
    static boolean coreSupported() {
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk >= 21) return true;
        return sdk >= 19 && android.os.Build.CPU_ABI != null && android.os.Build.CPU_ABI.startsWith("x86");
    }

    private static Thread thread;

    private Engine() {}

    interface ExitListener {
        void onExit(int status);
    }

    /** starts the engine unless one runs. false when it was already running */
    static synchronized boolean start(Context context, final EngineHost host,
                                      final ExitListener listener) {
        if (thread != null && thread.isAlive()) return false;
        final String files = context.getFilesDir().getAbsolutePath();
        final String socket = Paths.socket(context).getAbsolutePath();
        // by absolute path: android before 5.0 does not search the app's library directory for a dlopen by name
        final String core = coreSupported()
                ? new java.io.File(context.getApplicationInfo().nativeLibraryDir, "libsenkocore.so").getAbsolutePath()
                : "";
        // the engine keeps a few large tables on the stack of its thread
        thread = new Thread(null, new Runnable() {
            @Override
            public void run() {
                int status = nativeRun(files, socket, core, host);
                if (listener != null) listener.onExit(status);
            }
        }, "senko-engine", 2 * 1024 * 1024);
        thread.start();
        return true;
    }

    /** asks the engine to return and waits for it, at most timeoutMs */
    static void stop(long timeoutMs) {
        Thread running;
        synchronized (Engine.class) {
            running = thread;
        }
        if (running == null || !running.isAlive()) return;
        nativeStop();
        try {
            running.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static synchronized boolean isRunning() {
        return thread != null && thread.isAlive();
    }
}
