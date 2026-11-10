package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * runs the real engine through the jni entry points the app uses, on the host,
 * with a fake vpn service. it needs the host build of libsenko (make -C tests
 * host-jni, then SENKODROID_HOST_LIB_DIR) and is skipped without it
 */
public final class EngineJniTest {
    private final class FakeHost implements EngineHost {
        volatile boolean protectAnswer = true;
        volatile boolean throwOnTun;
        final AtomicInteger protectCalls = new AtomicInteger();
        final AtomicInteger tunCalls = new AtomicInteger();
        volatile int lastProtectFd = -1;
        volatile String address4;
        volatile int prefix4;
        volatile String address6;
        volatile String dns4;
        volatile int mtu;
        volatile String callerThread;

        @Override
        public boolean protect(int fd) {
            protectCalls.incrementAndGet();
            lastProtectFd = fd;
            return protectAnswer;
        }

        @Override
        public int openTun(String a4, int p4, String a6, int p6, String dns, int m) {
            tunCalls.incrementAndGet();
            address4 = a4;
            prefix4 = p4;
            address6 = a6;
            dns4 = dns;
            mtu = m;
            callerThread = Thread.currentThread().getName();
            if (throwOnTun) throw new IllegalStateException("fake vpn service exploded");
            return -1;
        }

        @Override
        public String tunError() {
            return "fake refusal";
        }
    }

    private final class UnixTransport implements ControlClient.Transport {
        @Override
        public ControlClient.Connection open() throws IOException {
            // unix domain channels are jdk 16 api, which the android jar the tests compile against lacks
            final SocketChannel channel;
            try {
                Class<?> family = Class.forName("java.net.StandardProtocolFamily");
                Object unix = family.getField("UNIX").get(null);
                channel = (SocketChannel) SocketChannel.class
                        .getMethod("open", ProtocolFamily.class).invoke(null, unix);
                Class<?> address = Class.forName("java.net.UnixDomainSocketAddress");
                Object target = address.getMethod("of", String.class)
                        .invoke(null, new File(dir, "ctl.sock").getAbsolutePath());
                channel.connect((SocketAddress) target);
            } catch (ReflectiveOperationException e) {
                throw new IOException("this jvm has no unix domain sockets", e);
            }
            final InputStream in = Channels.newInputStream(channel);
            final OutputStream out = Channels.newOutputStream(channel);
            return new ControlClient.Connection() {
                @Override
                public InputStream input() {
                    return in;
                }

                @Override
                public OutputStream output() {
                    return out;
                }

                @Override
                public void setReadTimeout(int ms) {
                    // a blocking channel has no timeout, the test timeout stops a stuck read
                }

                @Override
                public void close() throws IOException {
                    channel.close();
                }
            };
        }
    }

    private File dir;
    private Thread engineThread;
    private volatile int engineStatus = -99;
    private FakeHost host;
    private ControlClient client;

    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = Engine.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    @Before
    public void startEngine() throws Exception {
        assumeTrue("host libsenko not built", !System.getProperty("senko.hostLibDir", "").isEmpty());
        // a short path: a unix socket address holds about a hundred bytes
        dir = Files.createTempDirectory("senko-jni").toFile();
        host = new FakeHost();
        final Method run = method("nativeRun", String.class, String.class, String.class, EngineHost.class);
        engineThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engineStatus = (Integer) run.invoke(null, dir.getAbsolutePath(),
                            new File(dir, "ctl.sock").getAbsolutePath(), "", host);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }, "engine-under-test");
        engineThread.start();
        client = new ControlClient(new UnixTransport(), new File(dir, "ctl.token"));
        for (int i = 0; i < 300; ++i) {
            if (new File(dir, "ctl.token").isFile() && new File(dir, "ctl.sock").exists()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("the engine did not open its control socket");
    }

    @After
    public void stopEngine() throws Exception {
        if (engineThread == null) return;
        method("nativeStop").invoke(null);
        engineThread.join(10000);
        assertFalse("the engine thread ended", engineThread.isAlive());
        assertEquals("the engine returned its ok status", 0, engineStatus);
    }

    @Test(timeout = 60000)
    public void catalogRoundTripsThroughTheRealEngine() throws Exception {
        Protocol.State state = client.status();
        assertEquals("idle", state.name);

        client.command("ADDSRV vless://11111111-1111-4111-8111-111111111111@1.2.3.4:443"
                + "?security=none&type=tcp#first node", 5000);
        Model.Catalog catalog = client.list();
        assertEquals(1, catalog.servers.size());
        Model.Server server = catalog.servers.get(0);
        assertEquals("1.2.3.4", server.host);
        assertEquals(443, server.port);
        assertEquals("vless", server.proto);
        assertEquals("first node", server.remark);
        assertTrue(server.supported);

        List<String[]> settings = client.settings();
        boolean sawDns = false;
        for (String[] pair : settings) if (pair[0].equals("dns_upstream")) sawDns = true;
        assertTrue("the settings dump names the dns server", sawDns);

        client.command("SET dns_upstream 9.9.9.9", 5000);
        boolean changed = false;
        for (String[] pair : client.settings())
            if (pair[0].equals("dns_upstream") && pair[1].equals("9.9.9.9")) changed = true;
        assertTrue("a setting written over the socket reads back", changed);

        try {
            client.command("SET quantum_tunnel 1", 5000);
            throw new AssertionError("an unknown setting must be refused");
        } catch (ControlException expected) {
            assertNotNull(expected.getMessage());
        }

        String log = client.logs();
        assertTrue("the log comes back through base64: " + log, log.contains("engine ready"));
    }

    @Test(timeout = 60000)
    public void tunnelStartAsksTheVpnServiceForTheInterface() throws Exception {
        client.command("ADDSRV vless://11111111-1111-4111-8111-111111111111@1.2.3.4:443"
                + "?security=none&type=tcp#first", 5000);
        List<String> lines = client.exchange("CONNECT 0", ControlClient.OK_OR_ERR, 30000);
        String last = lines.get(lines.size() - 1);
        assertTrue("the refusal reaches the app: " + last, Protocol.isError(last));
        assertTrue("with the service's own words: " + last, last.contains("fake refusal"));

        assertEquals(1, host.tunCalls.get());
        assertEquals("198.18.0.1", host.address4);
        assertEquals(32, host.prefix4);
        assertEquals("fd00:5e4b::1", host.address6);
        assertEquals("8.8.8.8", host.dns4);
        assertEquals(1500, host.mtu);
        assertFalse("the call comes from an engine thread, not the test thread",
                host.callerThread.equals(Thread.currentThread().getName()));
        assertEquals("a failed start leaves the error state", "error", client.status().name);
    }

    @Test(timeout = 60000)
    public void disconnectReturnsWhenTheEngineReportsIdle() throws Exception {
        long started = System.nanoTime();
        client.disconnect();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("the STATE idle line ends it, it took " + ms + " ms", ms < 3000);
        assertEquals("idle", client.status().name);
    }

    @Test(timeout = 60000)
    public void aThrowingVpnServiceDoesNotTakeTheEngineDown() throws Exception {
        client.command("ADDSRV vless://11111111-1111-4111-8111-111111111111@1.2.3.4:443"
                + "?security=none&type=tcp#first", 5000);
        host.throwOnTun = true;
        List<String> lines = client.exchange("CONNECT 0", ControlClient.OK_OR_ERR, 30000);
        String last = lines.get(lines.size() - 1);
        assertTrue(last, Protocol.isError(last));
        assertTrue("the exception becomes a plain failure: " + last, last.contains("threw"));
        assertEquals("the engine still answers", "error", client.status().name);
    }

    @Test(timeout = 60000)
    public void everyOutboundSocketIsOfferedToTheVpnService() throws Exception {
        client.command("ADDSRV vless://11111111-1111-4111-8111-111111111111@1.2.3.4:443"
                + "?security=none&type=tcp#first", 5000);
        host.protectAnswer = false;
        List<String> lines = client.exchange("CHECK tcp 0", ControlClient.OK_OR_ERR, 30000);
        assertTrue("the probe asked to protect its socket", host.protectCalls.get() >= 1);
        assertTrue("with a real descriptor", host.lastProtectFd > 2);
        String last = lines.get(lines.size() - 1);
        assertTrue("a refused socket fails the check instead of leaving the tunnel: " + last,
                Protocol.isError(last) || !last.startsWith("OK"));
    }

    @Test(timeout = 30000)
    public void simultaneousChecksKeepTheirOwnStages() throws Exception {
        client.command("ADDSRV vless://11111111-1111-4111-8111-111111111111@1.2.3.4:443"
                + "?security=none&type=tcp#first", 5000);
        host.protectAnswer = false;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<List<String>> check = new java.util.concurrent.Callable<List<String>>() {
                @Override public List<String> call() throws IOException {
                    return client.exchange("CHECK tcp 0 stages", new ControlClient.Terminator() {
                        @Override public boolean isLast(String line) {
                            return line.startsWith("PONG ") || Protocol.isError(line);
                        }
                    }, 10000);
                }
            };
            java.util.concurrent.Future<List<String>> first = pool.submit(check);
            java.util.concurrent.Future<List<String>> second = pool.submit(check);
            for (java.util.concurrent.Future<List<String>> result : java.util.Arrays.asList(first, second)) {
                List<String> lines = result.get(10, TimeUnit.SECONDS);
                assertTrue("each check reports its own resolve stage: " + lines,
                        lines.toString().contains("resolve"));
                assertTrue("each check reports its own failure: " + lines,
                        Protocol.isError(lines.get(lines.size() - 1)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test(timeout = 60000)
    public void theEventStreamReportsTheEngineState() throws Exception {
        final CountDownLatch gotState = new CountDownLatch(1);
        final CountDownLatch gotConnection = new CountDownLatch(1);
        EventStream stream = new EventStream(client, new EventStream.Listener() {
            @Override
            public void onLine(String line) {
                if (line.startsWith("STATE idle")) gotState.countDown();
            }

            @Override
            public void onConnected(boolean connected) {
                if (connected) gotConnection.countDown();
            }
        });
        stream.start();
        try {
            assertTrue("the stream connected", gotConnection.await(10, TimeUnit.SECONDS));
            assertTrue("and said where things stand", gotState.await(10, TimeUnit.SECONDS));
        } finally {
            stream.stop();
        }
    }
}
