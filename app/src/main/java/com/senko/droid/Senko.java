package com.senko.droid;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * what every screen shares: the engine connection, the state of the tunnel,
 * the catalog and the ping results. state changes happen on the main thread,
 * the requests run on worker threads and post their results back. a screen
 * listens while it is visible and reads the getters when it is told something
 * changed
 */
final class Senko {
    static final int STATE = 1;
    static final int TRAFFIC = 2;
    static final int CATALOG = 4;
    static final int PING = 8;
    static final int ERROR = 16;
    static final int ENGINE = 32;
    static final int BUSY = 64;

    interface Listener {
        void onChange(int what);
    }

    interface Done<T> {
        void done(T value, IOException error);
    }

    private static Senko instance;

    static synchronized Senko get() {
        return instance;
    }

    static synchronized void init(Context context) {
        if (instance == null) instance = new Senko(context.getApplicationContext());
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ControlClient client;
    private final EventStream events;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    // requests share one thread, so two taps cannot interleave their replies
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // a CONNECT waits up to 90 s for the engine, so it and the DISCONNECT that must be able to
    // interrupt it each get a thread, and neither waits behind a list or a ping on the worker
    private final ExecutorService connection = Executors.newSingleThreadExecutor();
    private final ExecutorService stopper = Executors.newSingleThreadExecutor();
    private volatile boolean disconnecting;
    private int connectionGeneration;
    // the engine runs one thread per check and holds 16 control connections, of which the event
    // stream and the status polls use a few. the core runs six probes at once (probeParallel in
    // xraycore/main.go): more requests in flight only queue there, and a queued check still burns
    // the 15 s reply timeout of its control connection
    private static final int PING_PARALLEL = 6;
    private final ExecutorService pinger = Executors.newFixedThreadPool(PING_PARALLEL);
    // the list shows what a request through the node takes (a GET of generate_204, as a url test
    // does), not only the tcp handshake with the server's front, which is a cdn edge for many nodes
    private static final String PING_MODE = "real";
    private int watchers;

    private boolean engineUp;
    private String serverState = "idle";
    private String awgState = "idle";
    private long uptimeBase;
    private long uptimeAt;
    private boolean uptimeKnown;
    private long up;
    private long down;
    private long upRate;
    private long downRate;
    /** the last HISTORY rate samples of this tunnel, newest last, for the throughput chart */
    static final int HISTORY = 90;
    private final long[] downHistory = new long[HISTORY];
    private final long[] upHistory = new long[HISTORY];
    private int historyCount;
    private long peakDown;
    private long peakUp;
    private long trafficAt;
    private Model.Catalog catalog = new Model.Catalog();
    private boolean catalogLoaded;
    private int picked = -1;
    private String lastError = "";
    private int busy;
    private final Map<String, Integer> pings = new HashMap<String, Integer>();
    private final Set<Integer> pinging = new HashSet<Integer>();
    private final AtomicInteger pingGeneration = new AtomicInteger();
    // the auto server run has its own counter: a ping sweep starting meanwhile must not cancel it
    private final AtomicInteger autoGeneration = new AtomicInteger();
    /** quick connect measures at most this many servers, a panel feed can hold hundreds and three checks at a time take minutes */
    private static final int AUTO_PROBE_LIMIT = 24;
    /** after the first server answers, quick connect waits this long for a faster one */
    private static final long AUTO_SETTLE_MS = 400;
    private String hwid = "";

    private Senko(Context context) {
        this.context = context;
        client = LocalTransport.client(context);
        events = new EventStream(client, new EventStream.Listener() {
            @Override
            public void onLine(final String line) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        handleLine(line);
                    }
                });
            }

            @Override
            public void onConnected(final boolean connected) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        engineUp = connected;
                        if (connected) {
                            loadCatalog(null);
                            refreshTunnelState();
                        }
                        notifyChange(ENGINE);
                    }
                });
            }
        });
        loadPings();
    }

    ControlClient client() {
        return client;
    }

    Context context() {
        return context;
    }

    Handler handler() {
        return main;
    }

    // listeners and the event stream, held open while a screen is in front

    void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChange(int what) {
        for (Listener listener : listeners) listener.onChange(what);
    }

    // the engine pushes the server tunnel's state, and the awg state is asked for
    // every few seconds while a screen is in front. a tunnel that came up, dropped
    // or was replaced outside the app would otherwise stay invisible
    private static final long HEARTBEAT_MS = 5000;
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (watchers == 0) return;
            if (busy == 0) refreshTunnelState();
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    /** a screen came to the front: the engine is started and the event stream held */
    void screenStarted() {
        watchers++;
        // the service ends an idle engine 20 s after it starts unless a screen is counted
        ServiceCore.screenShown();
        startEngine();
        if (watchers == 1) {
            events.start();
            main.postDelayed(heartbeat, HEARTBEAT_MS);
        }
    }

    void screenStopped() {
        if (watchers > 0) {
            watchers--;
            ServiceCore.screenHidden();
        }
        if (watchers == 0) {
            events.stop();
            main.removeCallbacks(heartbeat);
        }
    }

    void startEngine() {
        // VpnService starts at android 4.0, below it the root helper stands in for it
        // by name, so the class that extends VpnService is never resolved where VpnService is absent
        String host = RootHelper.needed() ? "com.senko.droid.SenkoRootService" : "com.senko.droid.SenkoVpnService";
        Intent start = new Intent().setClassName(context, host).setAction(ServiceCore.ACTION_START);
        context.startService(start);
    }


    boolean engineUp() {
        return engineUp;
    }

    /** the state the screen shows: the amneziawg tunnel when it is up, otherwise the server tunnel */
    String state() {
        return Protocol.isActive(awgState) ? awgState : serverState;
    }

    boolean isActive() {
        return Protocol.isActive(state());
    }

    /** whether a connect has something to start, the same choice connect() makes */
    boolean canConnect() {
        if (awgSelected()) return true;
        if (Prefs.autoServer()) {
            for (Model.Server server : catalog.servers) if (server.supported) return true;
            return false;
        }
        return picked >= 0;
    }

    /** which backend carries the traffic now */
    int activeBackend() {
        if (Protocol.isActive(awgState)) return Prefs.BACKEND_AWG;
        if (Protocol.isActive(serverState)) return Prefs.BACKEND_SERVER;
        return -1;
    }

    /** seconds the tunnel has been up, -1 when it is not connected or the age is unknown */
    long uptimeSeconds() {
        if (!"connected".equals(state()) || !uptimeKnown) return -1;
        return uptimeBase + (SystemClock.elapsedRealtime() - uptimeAt) / 1000;
    }

    long up() {
        return up;
    }

    long down() {
        return down;
    }

    /** bytes per second since the last sample */
    /** the samples taken so far, oldest first */
    long[] downHistory() {
        return java.util.Arrays.copyOfRange(downHistory, HISTORY - historyCount, HISTORY);
    }

    long[] upHistory() {
        return java.util.Arrays.copyOfRange(upHistory, HISTORY - historyCount, HISTORY);
    }

    long peakDown() {
        return peakDown;
    }

    long peakUp() {
        return peakUp;
    }

    long upRate() {
        return upRate;
    }

    long downRate() {
        return downRate;
    }

    Model.Catalog catalog() {
        return catalog;
    }

    boolean catalogLoaded() {
        return catalogLoaded;
    }

    int picked() {
        return picked;
    }

    void pick(int index) {
        picked = index;
        Model.Server server = catalog.server(index);
        if (server != null) Prefs.setPickedIdentity(server.identity);
        Prefs.setSelectedBackend(Prefs.BACKEND_SERVER);
        notifyChange(CATALOG);
    }

    Model.Server pickedServer() {
        return catalog.server(picked);
    }

    String lastError() {
        return lastError;
    }

    void clearError() {
        if (lastError.isEmpty()) return;
        lastError = "";
        notifyChange(ERROR);
    }

    boolean busy() {
        return busy > 0;
    }

    String hwid() {
        return hwid;
    }


    private void handleLine(String line) {
        Protocol.State parsed = Protocol.parseState(line);
        if (parsed != null) {
            setState(parsed.name, parsed.uptime);
            return;
        }
        long[] traffic = Protocol.parseTraffic(line);
        if (traffic != null) {
            long now = SystemClock.elapsedRealtime();
            if (trafficAt != 0 && now > trafficAt) {
                long ms = now - trafficAt;
                upRate = Math.max(0, (traffic[0] - up) * 1000 / ms);
                downRate = Math.max(0, (traffic[1] - down) * 1000 / ms);
                System.arraycopy(downHistory, 1, downHistory, 0, HISTORY - 1);
                System.arraycopy(upHistory, 1, upHistory, 0, HISTORY - 1);
                downHistory[HISTORY - 1] = downRate;
                upHistory[HISTORY - 1] = upRate;
                if (historyCount < HISTORY) historyCount++;
                peakDown = Math.max(peakDown, downRate);
                peakUp = Math.max(peakUp, upRate);
            }
            trafficAt = now;
            up = traffic[0];
            down = traffic[1];
            notifyChange(TRAFFIC);
            return;
        }
        if (Protocol.isError(line)) setError(Protocol.message(line));
    }

    private void setState(String next, long uptime) {
        boolean wasConnected = "connected".equals(state());
        serverState = next;
        if ("connected".equals(next)) {
            if (uptime > 0 || !wasConnected || !uptimeKnown) {
                uptimeBase = uptime;
                uptimeAt = SystemClock.elapsedRealtime();
                uptimeKnown = true;
            }
        } else {
            uptimeKnown = false;
            uptimeBase = 0;
            up = 0;
            down = 0;
            upRate = 0;
            downRate = 0;
            trafficAt = 0;
            historyCount = 0;
            peakDown = 0;
            peakUp = 0;
        }
        if ("connected".equals(next)) lastError = "";
        notifyChange(STATE | TRAFFIC | ERROR);
        // the selection moves with a connect, so the list is read again
        if (!next.equals("connecting")) loadCatalog(null);
    }

    private void setError(String text) {
        lastError = text == null ? "" : text;
        notifyChange(ERROR);
    }

    private void begin() {
        if (busy++ == 0) notifyChange(BUSY);
    }

    private void end() {
        if (busy > 0 && --busy == 0) notifyChange(BUSY);
    }


    interface Work<T> {
        T run(ControlClient client) throws IOException;
    }

    <T> void submit(final ExecutorService pool, final boolean busyWhileRunning, final Work<T> work,
                    final Done<T> done) {
        if (busyWhileRunning) begin();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                T value = null;
                IOException error = null;
                try {
                    value = work.run(client);
                } catch (IOException e) {
                    error = e;
                }
                final T result = value;
                final IOException failure = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (busyWhileRunning) end();
                        if (done != null) done.done(result, failure);
                    }
                });
            }
        });
    }

    <T> void run(boolean busyWhileRunning, Work<T> work, Done<T> done) {
        submit(worker, busyWhileRunning, work, done);
    }


    void loadCatalog(final Done<Model.Catalog> done) {
        submit(worker, false, new Work<Model.Catalog>() {
            @Override
            public Model.Catalog run(ControlClient c) throws IOException {
                return c.list();
            }
        }, new Done<Model.Catalog>() {
            @Override
            public void done(Model.Catalog value, IOException error) {
                if (value != null) applyCatalog(value);
                if (done != null) done.done(value, error);
            }
        });
    }

    private void applyCatalog(Model.Catalog next) {
        catalog = next;
        catalogLoaded = true;
        Model.Server selected = next.selected();
        // the choice is remembered by identity, so a reordered or refreshed list keeps it
        String identity = Prefs.pickedIdentity();
        Model.Server remembered = null;
        if (!identity.isEmpty())
            for (Model.Server server : next.servers)
                if (identity.equals(server.identity)) remembered = server;
        if (remembered != null) picked = remembered.index;
        else if (next.server(picked) == null) picked = selected != null ? selected.index : -1;
        notifyChange(CATALOG);
    }


    /** connects the picked server, or the fastest one when the user chose auto */
    void connect(final Done<Protocol.State> done) {
        if (awgSelected()) {
            startAwg(new Done<String>() {
                @Override
                public void done(String value, IOException error) {
                    if (done != null) done.done(null, error);
                }
            });
            return;
        }
        if (Prefs.autoServer()) {
            connectFastest(done);
            return;
        }
        if (picked < 0) {
            setError("no configuration is selected");
            if (done != null) done.done(null, new ControlException("no configuration is selected"));
            return;
        }
        connect(picked, done);
    }

    void connect(final int index, final Done<Protocol.State> done) {
        autoGeneration.incrementAndGet();
        final int generation = ++connectionGeneration;
        clearError();
        serverState = "connecting";
        notifyChange(STATE);
        submit(connection, true, new Work<Protocol.State>() {
            @Override
            public Protocol.State run(ControlClient c) throws IOException {
                return c.connect(index);
            }
        }, new Done<Protocol.State>() {
            @Override
            public void done(Protocol.State value, IOException error) {
                if (generation != connectionGeneration) {
                    if (done != null) done.done(null, new ControlException("connection cancelled"));
                    return;
                }
                if (error != null) {
                    setError(error.getMessage());
                    // the engine publishes its own state, the next STATUS settles it
                    serverState = "error";
                    notifyChange(STATE);
                } else if (value != null) {
                    setState(value.name, value.uptime);
                }
                if (done != null) done.done(value, error);
            }
        });
    }

    boolean disconnecting() {
        return disconnecting;
    }

    void disconnect(final Done<Void> done) {
        final boolean awg = Protocol.isActive(awgState);
        connectionGeneration++;
        // an auto server run that is still pinging must not connect after the user said stop
        autoGeneration.incrementAndGet();
        disconnecting = true;
        notifyChange(STATE);
        submit(stopper, true, new Work<Void>() {
            @Override
            public Void run(ControlClient c) throws IOException {
                if (awg) {
                    String status = c.awgStop();
                    if (!status.startsWith("idle")) throw new ControlException("could not stop amneziawg");
                } else {
                    c.disconnect();
                }
                return null;
            }
        }, new Done<Void>() {
            @Override
            public void done(Void value, IOException error) {
                disconnecting = false;
                if (error != null) {
                    setError(error.getMessage());
                    notifyChange(STATE);
                } else if (awg) {
                    awgState = "idle";
                    uptimeKnown = false;
                    notifyChange(STATE | TRAFFIC);
                } else {
                    setState("idle", 0);
                }
                if (done != null) done.done(value, error);
            }
        });
    }

    /** servers measured before go first, fastest first, so a recent sweep decides, unmeasured ones fill the rest in catalog order */
    private List<Integer> autoCandidates() {
        List<Model.Server> measured = new ArrayList<Model.Server>();
        List<Model.Server> unknown = new ArrayList<Model.Server>();
        for (Model.Server server : catalog.servers) {
            if (!server.supported) continue;
            if (ping(server) >= 0) measured.add(server);
            else unknown.add(server);
        }
        java.util.Collections.sort(measured, new java.util.Comparator<Model.Server>() {
            @Override
            public int compare(Model.Server a, Model.Server b) {
                return ping(a) - ping(b);
            }
        });
        measured.addAll(unknown);
        List<Integer> indexes = new ArrayList<Integer>();
        for (Model.Server server : measured) {
            if (indexes.size() >= AUTO_PROBE_LIMIT) break;
            indexes.add(server.index);
        }
        return indexes;
    }

    /** checks up to AUTO_PROBE_LIMIT servers, three at a time, and connects the fastest one that answered */
    void connectFastest(final Done<Protocol.State> done) {
        clearError();
        serverState = "connecting";
        notifyChange(STATE);
        final List<Integer> candidates = autoCandidates();
        if (candidates.isEmpty()) {
            setError("no configuration is selected");
            serverState = "idle";
            notifyChange(STATE);
            if (done != null) done.done(null, new ControlException("no servers"));
            return;
        }
        begin();
        final int generation = autoGeneration.incrementAndGet();
        final int[] best = { -1, Integer.MAX_VALUE };
        final int[] pending = { candidates.size() };
        final boolean[] armed = { false };
        final Runnable settle = new Runnable() {
            private boolean settled;

            @Override
            public void run() {
                if (settled) return;
                settled = true;
                if (generation != autoGeneration.get()) return;
                if (best[0] < 0) {
                    setError("Could not connect to the server. Check the address, network, and server availability.");
                    serverState = "idle";
                    notifyChange(STATE);
                    if (done != null) done.done(null, new ControlException("no working server"));
                    return;
                }
                picked = best[0];
                connect(best[0], done);
            }
        };
        Runnable launch = new Runnable() {
            @Override
            public void run() {
                // never more than the pool size is in flight, the rest wait in the queue
                for (int i = 0; i < candidates.size(); ++i) {
                    final int index = candidates.get(i);
                    submit(pinger, false, new Work<Integer>() {
                        @Override
                        public Integer run(ControlClient c) throws IOException {
                            if (generation != autoGeneration.get()) return null;
                            return c.check(index, PING_MODE).ms;
                        }
                    }, new Done<Integer>() {
                        @Override
                        public void done(Integer ms, IOException error) {
                            pending[0]--;
                            if (generation != autoGeneration.get()) {
                                if (pending[0] == 0) end();
                                return;
                            }
                            if (ms != null && ms >= 0) {
                                setPing(index, ms);
                                if (ms < best[1]) {
                                    best[0] = index;
                                    best[1] = ms;
                                }
                                // the first answer starts a short window for faster ones, so the
                                // connect does not wait for the dead servers of a long list
                                if (!armed[0]) {
                                    armed[0] = true;
                                    main.postDelayed(settle, AUTO_SETTLE_MS);
                                }
                            } else {
                                setPing(index, -1);
                            }
                            if (pending[0] > 0) return;
                            end();
                            main.removeCallbacks(settle);
                            settle.run();
                        }
                    });
                }
            }
        };
        launch.run();
    }


    /** the profile the power button starts when the user chose it */
    java.io.File awgProfile() {
        return new java.io.File(Paths.awgDir(context), "senko.conf");
    }

    boolean hasAwgProfile() {
        return awgProfile().isFile();
    }

    boolean awgSelected() {
        return Prefs.selectedBackend() == Prefs.BACKEND_AWG && hasAwgProfile();
    }

    void selectAwg() {
        Prefs.setSelectedBackend(Prefs.BACKEND_AWG);
        notifyChange(CATALOG);
    }

    void selectServer() {
        Prefs.setSelectedBackend(Prefs.BACKEND_SERVER);
        notifyChange(CATALOG);
    }

    void startAwg(final Done<String> done) {
        if (!hasAwgProfile()) {
            setError("amneziawg config not found");
            if (done != null) done.done(null, new ControlException("amneziawg config not found"));
            return;
        }
        clearError();
        selectAwg();
        awgState = "connecting";
        notifyChange(STATE);
        final String path = awgProfile().getAbsolutePath();
        submit(worker, true, new Work<String>() {
            @Override
            public String run(ControlClient c) throws IOException {
                // starting the tunnel takes a running server tunnel down first
                String status = c.awgStart(path);
                if (status.startsWith("error")) throw new ControlException(status.substring(5).trim());
                return status;
            }
        }, new Done<String>() {
            @Override
            public void done(String value, IOException error) {
                if (error != null) {
                    setError(error.getMessage());
                    awgState = "error";
                    notifyChange(STATE);
                } else {
                    serverState = "idle";
                    awgState = "connecting";
                    notifyChange(STATE);
                    refreshTunnelState();
                }
                if (done != null) done.done(value, error);
            }
        });
    }

    /** the status of both backends, read from the engine. the engine does not push the awg state */
    void refreshTunnelState() {
        if (!engineUp) return;
        submit(worker, false, new Work<ControlClient.AwgStatus>() {
            @Override
            public ControlClient.AwgStatus run(ControlClient c) throws IOException {
                return c.awgStatusWithUptime();
            }
        }, new Done<ControlClient.AwgStatus>() {
            @Override
            public void done(ControlClient.AwgStatus value, IOException error) {
                if (value == null) return;
                String next = value.state;
                boolean up = Protocol.isActive(next);
                boolean changed = !next.equals(awgState);
                if (up && "connected".equals(next) && (value.uptime >= 0 || !uptimeKnown)) {
                    uptimeBase = Math.max(0, value.uptime);
                    uptimeAt = SystemClock.elapsedRealtime();
                    uptimeKnown = true;
                }
                if (next.startsWith("error")) {
                    // an awg failure counts only while the profile is the chosen backend
                    if (Prefs.selectedBackend() == Prefs.BACKEND_AWG && lastError.isEmpty()) setError(next);
                    next = "idle";
                }
                awgState = up ? next : "idle";
                if (changed) notifyChange(STATE | TRAFFIC);
                if ("connecting".equals(awgState))
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refreshTunnelState();
                        }
                    }, 2000);
            }
        });
    }

    /** writes a profile after the engine has accepted it, and starts it */
    void saveAwgConfig(final String text, final boolean start, final Done<String> done) {
        final java.io.File target = awgProfile();
        String config = text.startsWith("\ufeff") ? text.substring(1) : text;
        final String body = config.endsWith("\n") ? config : config + "\n";
        submit(worker, true, new Work<String>() {
            @Override
            public String run(ControlClient c) throws IOException {
                java.io.File dir = target.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new ControlException("could not save native AmneziaWG config");
                java.io.File draft = new java.io.File(dir, "draft.conf");
                java.io.FileOutputStream out = new java.io.FileOutputStream(draft);
                try {
                    out.write(body.getBytes("UTF-8"));
                    out.getFD().sync();
                } finally {
                    out.close();
                }
                String result = c.awgValidate(draft.getAbsolutePath());
                if (!result.startsWith("VALID")) {
                    draft.delete();
                    throw new ControlException(result.isEmpty() ? "invalid native AmneziaWG config" : result);
                }
                // the engine validated the draft, so it replaces the profile
                if (!draft.renameTo(target)) throw new ControlException("could not save native AmneziaWG config");
                return result;
            }
        }, new Done<String>() {
            @Override
            public void done(String value, IOException error) {
                if (error == null) {
                    selectAwg();
                    if (start) startAwg(null);
                } else {
                    setError(error.getMessage());
                }
                if (done != null) done.done(value, error);
            }
        });
    }

    void removeAwgProfile(final Done<Void> done) {
        final boolean running = Protocol.isActive(awgState);
        submit(worker, true, new Work<Void>() {
            @Override
            public Void run(ControlClient c) throws IOException {
                if (running && !c.awgStop().startsWith("idle")) throw new ControlException("could not stop amneziawg");
                awgProfile().delete();
                return null;
            }
        }, new Done<Void>() {
            @Override
            public void done(Void value, IOException error) {
                if (error == null) {
                    awgState = "idle";
                    Prefs.setSelectedBackend(Prefs.BACKEND_SERVER);
                    clearError();
                    notifyChange(STATE | CATALOG);
                } else {
                    setError(error.getMessage());
                }
                if (done != null) done.done(value, error);
            }
        });
    }

    /** a handshake with the profile's server, nothing else: milliseconds, or -1 */
    void probeAwg(final Done<Integer> done) {
        final String path = awgProfile().getAbsolutePath();
        submit(pinger, false, new Work<Integer>() {
            @Override
            public Integer run(ControlClient c) throws IOException {
                String reply = c.awgProbe(path);
                int mark = reply.indexOf("PING ");
                if (mark < 0) return -1;
                try {
                    return Integer.parseInt(reply.substring(mark + 5).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }, done);
    }


    private static String pingKey(Model.Server server) {
        return server.identity.isEmpty() ? "#" + server.index : server.identity;
    }

    /** -2 unknown, -3 running, -1 no answer, else milliseconds */
    int ping(Model.Server server) {
        if (pinging.contains(server.index)) return -3;
        Integer ms = pings.get(pingKey(server));
        return ms == null ? -2 : ms;
    }

    private void setPing(int index, int ms) {
        Model.Server server = catalog.server(index);
        if (server != null) pings.put(pingKey(server), ms);
        pinging.remove(index);
        persistPings();
        notifyChange(PING);
    }

    boolean isPinging(int index) {
        return pinging.contains(index);
    }

    /** the servers of one subscription group, or every server when group is -2 */
    void pingGroup(int group, final Done<Void> done) {
        List<Integer> indexes = new ArrayList<Integer>();
        for (Model.Server server : catalog.servers)
            if (server.supported && (group == -2 || server.group == group)) indexes.add(server.index);
        pingServers(indexes, done);
    }

    void pingServers(final List<Integer> indexes, final Done<Void> done) {
        if (indexes.isEmpty()) {
            if (done != null) done.done(null, null);
            return;
        }
        final int generation = pingGeneration.incrementAndGet();
        final int[] remaining = { indexes.size() };
        begin();
        for (int index : indexes) pinging.add(index);
        notifyChange(PING);
        for (final int index : indexes) {
            submit(pinger, false, new Work<Integer>() {
                @Override
                public Integer run(ControlClient c) throws IOException {
                    return c.check(index, PING_MODE).ms;
                }
            }, new Done<Integer>() {
                @Override
                public void done(Integer ms, IOException error) {
                    if (generation == pingGeneration.get()) setPing(index, ms == null ? -1 : ms);
                    else pinging.remove(index);
                    if (--remaining[0] > 0) return;
                    end();
                    notifyChange(PING);
                    if (done != null) done.done(null, null);
                }
            });
        }
    }

    /** one server, with a stage list for the diagnostics screen */
    void check(final int index, final String mode, final Done<Model.CheckResult> done) {
        submit(pinger, false, new Work<Model.CheckResult>() {
            @Override
            public Model.CheckResult run(ControlClient c) throws IOException {
                return c.check(index, mode);
            }
        }, done);
    }

    private void persistPings() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : pings.entrySet())
            if (!entry.getKey().startsWith("#"))
                text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        Prefs.setPings(text.toString());
    }

    private void loadPings() {
        for (String line : Prefs.pings().split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                pings.put(line.substring(0, eq), Integer.parseInt(line.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // a damaged line is a ping nobody measured
            }
        }
    }

    void forgetPings() {
        pings.clear();
        persistPings();
        notifyChange(PING);
    }


    void loadHwid() {
        submit(worker, false, new Work<String>() {
            @Override
            public String run(ControlClient c) throws IOException {
                return c.hwid();
            }
        }, new Done<String>() {
            @Override
            public void done(String value, IOException error) {
                if (value != null) {
                    hwid = value;
                    notifyChange(ENGINE);
                }
            }
        });
    }

    /** a message for the user from a failed request */
    static String describe(IOException error) {
        return Errors.human(error == null ? "" : error.getMessage());
    }
}
