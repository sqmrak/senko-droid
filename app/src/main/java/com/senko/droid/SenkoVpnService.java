package com.senko.droid;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

/**
 * the engine's host from android 4.0 on. the engine owns the packet loop and every server
 * connection, and calls back here for the two things only a vpn service may do: keep a socket
 * outside the tunnel, and open the tun interface. android before 4.0 has SenkoRootService
 */
@android.annotation.TargetApi(14)
public final class SenkoVpnService extends VpnService implements EngineHost {
    private static final String TAG = "senko";

    private volatile String tunError = "";
    private ServiceCore core;

    @Override
    public void onCreate() {
        super.onCreate();
        core = new ServiceCore(this, this);
        core.create();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return core.command(intent);
    }

    @Override
    public void onDestroy() {
        core.destroy();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        core.revoked();
        super.onRevoke();
    }


    /**
     * the engine protects every socket it opens toward a server, a ping and a subscription among
     * them. protect() refuses while this service holds no tunnel, which made every ping of an idle
     * app fail. with no tun device there is nothing for a socket to loop into, so the refusal is
     * not an error then. once the tunnel is up the answer of the system counts
     */
    @Override
    public boolean protect(int socket) {
        if (super.protect(socket)) return true;
        return !core.tunnelIsOpen();
    }

    @Override
    public int openTun(String address4, int prefix4, String address6, int prefix6,
                       String dns4, int mtu) {
        Builder builder = new Builder();
        try {
            builder.setSession(getString(R.string.app_name));
            builder.setMtu(mtu);
            builder.addAddress(address4, prefix4);
            builder.addRoute("0.0.0.0", 0);
            // the builder takes ipv6 addresses and routes from android 5.0. earlier
            // versions are not checked against a device, so they stay ipv4 only
            if (Build.VERSION.SDK_INT >= 21 && address6 != null && !address6.isEmpty()) {
                builder.addAddress(address6, prefix6);
                builder.addRoute("::", 0);
            }
            builder.addDnsServer(dns4);
            if (Build.VERSION.SDK_INT >= 21) {
                for (String pkg : Prefs.appDirect()) {
                    try {
                        builder.addDisallowedApplication(pkg);
                    } catch (PackageManager.NameNotFoundException e) {
                        // the app was uninstalled since the rule was made, nothing to exclude
                        Log.w(TAG, "direct app is gone: " + pkg);
                    }
                }
            }
            builder.setConfigureIntent(core.openAppIntent());
            ParcelFileDescriptor tun = builder.establish();
            if (tun == null) {
                tunError = "the vpn permission was revoked";
                return -1;
            }
            core.tunnelOpened();
            return tun.detachFd();
        } catch (IllegalArgumentException e) {
            tunError = "the system refused the tunnel parameters: " + e.getMessage();
        } catch (IllegalStateException e) {
            tunError = "the vpn service is not ready: " + e.getMessage();
        } catch (SecurityException e) {
            tunError = "the vpn permission is missing: " + e.getMessage();
        }
        Log.e(TAG, tunError);
        return -1;
    }

    @Override
    public String tunError() {
        return tunError;
    }
}
