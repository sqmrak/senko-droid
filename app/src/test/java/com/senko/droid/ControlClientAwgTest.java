package com.senko.droid;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class ControlClientAwgTest {
    private ControlClient.AwgStatus status(final String reply) throws Exception {
        File token = File.createTempFile("senko-token", ".txt");
        try {
            FileOutputStream file = new FileOutputStream(token);
            file.write("1234567890\n".getBytes("UTF-8"));
            file.close();
            ControlClient client = new ControlClient(new ControlClient.Transport() {
                @Override public ControlClient.Connection open() throws java.io.IOException {
                    final InputStream input = new ByteArrayInputStream(("OK authed\n" + reply).getBytes("UTF-8"));
                    return new ControlClient.Connection() {
                        @Override public InputStream input() { return input; }
                        @Override public OutputStream output() { return new ByteArrayOutputStream(); }
                        @Override public void setReadTimeout(int ms) {}
                        @Override public void close() throws java.io.IOException { input.close(); }
                    };
                }
            }, token);
            return client.awgStatusWithUptime();
        } finally {
            token.delete();
        }
    }

    @Test public void connectedStatusRetainsEngineUptime() throws Exception {
        ControlClient.AwgStatus connected = status("AWG connected\nAWGUP 125\nAWGEND 0 0\n");
        assertEquals("connected", connected.state);
        assertEquals(125, connected.uptime);
        assertEquals(0, status("AWG connected\nAWGUP 0\nAWGEND 0 0\n").uptime);
        assertEquals(-1, status("AWG idle\nAWGEND 0 0\n").uptime);
        assertEquals(-1, status("AWG connected\nAWGUP bad\nAWGEND 0 0\n").uptime);
    }
}
