package com.senko.droid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

/**
 * what a service that hosts the engine does, whichever way it gets its tun device. VpnService
 * does not exist before android 4.0 and cannot be a superclass there, so SenkoVpnService and
 * SenkoRootService both hold one of these
 */
final class ServiceCore implements EventStream.Listener {
    static final String ACTION_START = "com.senko.droid.action.START";
    static final String ACTION_STOP = "com.senko.droid.action.STOP";
    static final String ACTION_PAUSE = "com.senko.droid.action.PAUSE";
    static final String ACTION_RESUME = "com.senko.droid.action.RESUME";

    private static final String TAG = "senko";
    private static final String CHANNEL = "senko";
    private static final int NOTIFICATION_ID = 1;
    // an idle engine with no screen open has nothing to do, so it ends
    private static final long IDLE_STOP_MS = 20000;

    private static volatile int visibleScreens;

    private final Service service;
    private final EngineHost host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idleStop = new Runnable() {
        @Override
        public void run() {
            if (tunnelWanted) return;
            if (visibleScreens == 0) service.stopSelf();
            else main.postDelayed(this, IDLE_STOP_MS);
        }
    };
    private final NetworkChanges networks = new NetworkChanges();
    private final BroadcastReceiver connectivity = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            NetworkInfo info = (NetworkInfo) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            if (info == null || info.getType() == 17) return;
            ConnectivityManager manager = (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo active = manager == null ? null : manager.getActiveNetworkInfo();
            if (info.isConnected() && active != null && active.getType() != 17
                    && active.getType() != info.getType()) return;
            boolean changed = networks.update(info.getType(), info.getExtraInfo(), info.isConnected());
            if (!changed || !tunnelWanted) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        LocalTransport.client(service).networkChanged();
                    } catch (IOException e) {
                        Log.w(TAG, "could not notify the engine of the network change", e);
                    }
                }
            }, "senko-network").start();
        }
    };

    private volatile boolean tunnelWanted;
    // the tun device exists: only then can a socket loop into it, and only then does protect() mean anything
    private volatile boolean tunnelOpen;
    private EventStream events;
    private boolean foreground;
    // the user paused from the notification: the tunnel is down, the notification stays with a start button
    private boolean paused;

    ServiceCore(Service service, EngineHost host) {
        this.service = service;
        this.host = host;
    }

    /** the screens say when they are in front, so an idle engine can end */
    static void screenShown() {
        visibleScreens++;
    }

    static void screenHidden() {
        if (visibleScreens > 0) visibleScreens--;
    }

    /** the host opened a tunnel, so the service must outlive the screens */
    void tunnelOpened() {
        tunnelWanted = true;
        tunnelOpen = true;
    }

    boolean tunnelIsOpen() {
        return tunnelOpen;
    }

    void create() {
        service.registerReceiver(connectivity, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    int command(Intent intent) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            service.stopSelf();
            return Service.START_NOT_STICKY;
        }
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            pause();
            return Service.START_STICKY;
        }
        if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
            resume();
            return Service.START_STICKY;
        }
        startEngine();
        // a restarted service brings the engine back, and the engine's own
        // auto connect setting decides whether a tunnel follows
        return Service.START_STICKY;
    }

    private void pause() {
        paused = true;
        showState("idle");
        Senko senko = Senko.get();
        if (senko != null && senko.isActive()) senko.disconnect(null);
    }

    private void resume() {
        Senko senko = Senko.get();
        if (senko == null || !senko.canConnect()) {
            Log.w(TAG, "resume from the notification has nothing to connect");
            return;
        }
        paused = false;
        showState("connecting");
        senko.connect(null);
    }

    private void startEngine() {
        if (Engine.isRunning()) return;
        try {
            Roots.ensure(service);
        } catch (IOException e) {
            Log.e(TAG, "trust roots unavailable", e);
            service.stopSelf();
            return;
        }
        boolean started = Engine.start(service, host, new Engine.ExitListener() {
            @Override
            public void onExit(final int status) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (status != Engine.OK) Log.e(TAG, "the engine stopped with status " + status);
                        service.stopSelf();
                    }
                });
            }
        });
        if (started) {
            events = new EventStream(LocalTransport.client(service), this);
            events.start();
        }
        main.removeCallbacks(idleStop);
        main.postDelayed(idleStop, IDLE_STOP_MS);
    }

    void destroy() {
        main.removeCallbacks(idleStop);
        service.unregisterReceiver(connectivity);
        if (events != null) events.stop();
        events = null;
        Engine.stop(5000);
    }

    /** the user or another vpn app took the tunnel away */
    void revoked() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LocalTransport.client(service).disconnect();
                } catch (IOException e) {
                    Log.w(TAG, "the revoked tunnel could not be stopped", e);
                }
            }
        }, "senko-revoke").start();
    }


    @Override
    public void onLine(String line) {
        final Protocol.State state = Protocol.parseState(line);
        if (state == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                showState(state.name);
            }
        });
    }

    @Override
    public void onConnected(boolean connected) {
        // nothing to do: the first STATE line says where things stand
    }

    private void showState(String state) {
        tunnelWanted = Protocol.isActive(state);
        main.removeCallbacks(idleStop);
        if (tunnelWanted) {
            paused = false;
            // a tunnel must outlive the screen, so the process is made foreground
            service.startForeground(NOTIFICATION_ID, notification(
                    service.getString("connected".equals(state) ? R.string.state_connected
                                                                : R.string.state_connecting),
                    actionPending(ACTION_PAUSE, R.string.notification_pause),
                    actionPending(ACTION_STOP, R.string.notification_stop)));
            foreground = true;
            return;
        }
        tunnelOpen = false;
        if (paused) {
            // the notification outlives the tunnel until the user starts it again or stops the service
            service.startForeground(NOTIFICATION_ID, notification(service.getString(R.string.state_paused),
                    actionPending(ACTION_RESUME, R.string.notification_start)));
            foreground = true;
            return;
        }
        if (foreground) {
            service.stopForeground(true);
            foreground = false;
        }
        main.postDelayed(idleStop, IDLE_STOP_MS);
    }

    PendingIntent openAppIntent() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(service, 0, new Intent(service, HomeActivity.class), flags);
    }

    private Compat.Action actionPending(String action, int label) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Intent intent = new Intent(service, service.getClass()).setAction(action);
        // a service intent from a notification button: the service is already foreground, so a plain one works
        return new Compat.Action(service.getString(label), PendingIntent.getService(service, action.hashCode(), intent, flags));
    }

    private Notification notification(String text, Compat.Action... actions) {
        String title = service.getString(R.string.app_name);
        if (Build.VERSION.SDK_INT >= 26) return Oreo.notification(service, title, text, openAppIntent(), actions);
        return Compat.ongoing(service, title, text, R.drawable.ic_stat, openAppIntent(), actions);
    }

    /** the channel classes arrived with android 8 */
    @android.annotation.TargetApi(26)
    private static final class Oreo {
        static Notification notification(Context context, String title, String text, PendingIntent open,
                                         Compat.Action... actions) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, title,
                    NotificationManager.IMPORTANCE_LOW));
            Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentIntent(open)
                    .setOngoing(true);
            for (Compat.Action action : actions) builder.addAction(0, action.label, action.intent);
            // android 12 and newer hold a foreground service notification back for up to 10 seconds
            if (Build.VERSION.SDK_INT >= 31) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            }
            return builder.build();
        }
    }
}
