package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class EventStreamTest {
    private static final class Connection implements ControlClient.Connection {
        final PipedInputStream input = new PipedInputStream();
        final PipedOutputStream replies;
        final CountDownLatch closed = new CountDownLatch(1);

        Connection() throws IOException {
            replies = new PipedOutputStream(input);
            replies.write("OK authed\nSTATE idle\n".getBytes("UTF-8"));
            replies.flush();
        }

        @Override public InputStream input() { return input; }
        @Override public OutputStream output() { return new ByteArrayOutputStream(); }
        @Override public void setReadTimeout(int ms) {}
        @Override public void close() throws IOException {
            input.close();
            replies.close();
            closed.countDown();
        }
    }

    @Test(timeout = 10000)
    public void oldConnectionCannotReplaceRestartedStream() throws Exception {
        File token = File.createTempFile("senko-token", ".txt");
        FileOutputStream file = new FileOutputStream(token);
        file.write("1234567890\n".getBytes("UTF-8"));
        file.close();
        final CountDownLatch firstOpening = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger connectedCalls = new AtomicInteger();
        final AtomicInteger disconnectedCalls = new AtomicInteger();
        final Connection first = new Connection();
        final Connection second = new Connection();
        ControlClient.Transport transport = new ControlClient.Transport() {
            @Override public ControlClient.Connection open() throws IOException {
                if (opens.getAndIncrement() == 0) {
                    firstOpening.countDown();
                    try {
                        while (!releaseFirst.await(1, TimeUnit.SECONDS)) {}
                    } catch (InterruptedException e) {
                        try { releaseFirst.await(); } catch (InterruptedException again) {
                            throw new IOException(again);
                        }
                    }
                    return first;
                }
                return second;
            }
        };
        EventStream stream = new EventStream(new ControlClient(transport, token), new EventStream.Listener() {
            @Override public void onLine(String line) {}
            @Override public void onConnected(boolean up) {
                if (up) { connectedCalls.incrementAndGet(); connected.countDown(); }
                else disconnectedCalls.incrementAndGet();
            }
        });
        try {
            stream.start();
            assertTrue(firstOpening.await(2, TimeUnit.SECONDS));
            stream.stop();
            stream.start();
            assertTrue(connected.await(2, TimeUnit.SECONDS));
            releaseFirst.countDown();
            assertTrue(first.closed.await(2, TimeUnit.SECONDS));
            assertEquals(1, connectedCalls.get());
            assertEquals(0, disconnectedCalls.get());
        } finally {
            releaseFirst.countDown();
            stream.stop();
            token.delete();
        }
    }
}
