package com.senko.droid;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

/** what the engine asks of its host on android before 4.0: the root helper stands in for VpnService */
final class RootHost implements EngineHost {
    private static final String TAG = "senko";

    private final String socket;
    private volatile String tunError = "";

    RootHost(Context context) {
        socket = Paths.rootSocket(context).getAbsolutePath();
    }

    @Override
    public boolean protect(int fd) {
        String why = Engine.nativeRootProtect(socket, fd);
        if (why == null) return true;
        Log.w(TAG, "a socket could not be kept out of the tunnel: " + why);
        return false;
    }

    /** ipv6 is not carried: the helper routes ipv4 only, so address6 is ignored */
    @Override
    public int openTun(String address4, int prefix4, String address6, int prefix6, String dns4, int mtu) {
        try {
            int fd = Engine.nativeRootOpenTun(socket, address4, prefix4, dns4, mtu);
            tunError = "";
            return fd;
        } catch (IOException e) {
            tunError = e.getMessage() == null ? "the root helper refused the tunnel" : e.getMessage();
            Log.e(TAG, tunError);
            return -1;
        }
    }

    @Override
    public String tunError() {
        return tunError;
    }
}
