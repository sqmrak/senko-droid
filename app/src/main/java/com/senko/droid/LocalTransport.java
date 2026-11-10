package com.senko.droid;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** the engine's control socket, a unix socket in the app's private directory */
final class LocalTransport implements ControlClient.Transport {
    private final File socketFile;

    LocalTransport(File socketFile) {
        this.socketFile = socketFile;
    }

    @Override
    public ControlClient.Connection open() throws IOException {
        final LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(socketFile.getAbsolutePath(),
                    LocalSocketAddress.Namespace.FILESYSTEM));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        return new ControlClient.Connection() {
            @Override
            public InputStream input() throws IOException {
                return socket.getInputStream();
            }

            @Override
            public OutputStream output() throws IOException {
                return socket.getOutputStream();
            }

            @Override
            public void setReadTimeout(int ms) throws IOException {
                socket.setSoTimeout(ms);
            }

            @Override
            public void close() throws IOException {
                socket.close();
            }
        };
    }

    /** the client every screen and the service use */
    static ControlClient client(android.content.Context context) {
        return new ControlClient(new LocalTransport(Paths.socket(context)), Paths.token(context));
    }
}
