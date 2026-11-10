package com.senko.droid;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * the engine's host on android 2.3 to 3.x, where VpnService does not exist. the tun device and the
 * socket protection come from the root helper (see RootHelper), which the home screen starts when
 * the user taps connect
 */
public final class SenkoRootService extends Service {
    private ServiceCore core;

    @Override
    public void onCreate() {
        super.onCreate();
        core = new ServiceCore(this, new RootHost(this));
        core.create();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return core.command(intent);
    }

    @Override
    public void onDestroy() {
        core.destroy();
        RootHelper.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
