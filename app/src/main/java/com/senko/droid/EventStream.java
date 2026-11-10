package com.senko.droid;

import java.io.IOException;

/**
 * one authenticated connection that stays open and passes on every line the
 * engine broadcasts (STATE changes and STAT traffic samples). it reconnects
 * while it runs, because the engine socket only exists while the engine does
 */
final class EventStream {
    interface Listener {
        /** a line the engine sent on its own, off the main thread */
        void onLine(String line);

        /** the connection came up or went down */
        void onConnected(boolean connected);
    }

    private static final int RETRY_MS = 1500;

    private final ControlClient client;
    private final Listener listener;
    private boolean running;
    private int generation;
    private ControlClient.Session session;
    private Thread thread;

    EventStream(ControlClient client, Listener listener) {
        this.client = client;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        final int current = ++generation;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(current);
            }
        }, "senko-events");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        generation++;
        ControlClient.Session open = session;
        if (open != null) open.close();
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private synchronized boolean current(int value) {
        return running && generation == value;
    }

    private void loop(int value) {
        while (current(value)) {
            ControlClient.Session open = null;
            try {
                // auth must finish even when stop races the connection attempt
                open = client.open(3000);
                synchronized (this) {
                    if (!current(value)) return;
                    session = open;
                    open.connection.setReadTimeout(0);
                    listener.onConnected(true);
                }
                // the first answer to STATUS tells where things stand now
                open.send("STATUS");
                for (;;) {
                    String line = open.next();
                    if (line == null) break;
                    synchronized (this) {
                        if (!current(value)) return;
                        listener.onLine(line);
                    }
                }
            } catch (IOException e) {
                // the engine is not up yet, or it went away: wait and try again
            } finally {
                synchronized (this) {
                    if (session == open) session = null;
                    if (open != null) {
                        open.close();
                        if (current(value)) listener.onConnected(false);
                    }
                }
            }
            if (!current(value)) return;
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
