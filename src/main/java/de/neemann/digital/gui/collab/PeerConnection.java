/*
 * Copyright (c) 2024 Digital Contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.collab;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A single TCP connection to a collaboration peer.
 * Messages are framed as: 4-byte big-endian length + UTF-8 bytes.
 */
class PeerConnection implements Closeable {

    interface MessageListener {
        void onMessage(String xml);
    }

    private final Socket socket;
    private final DataOutputStream out;
    private final Thread readThread;
    volatile MessageListener receiveListener;

    PeerConnection(Socket socket, String initialSend, MessageListener listener) throws IOException {
        this.socket = socket;
        this.receiveListener = listener;
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        if (initialSend != null) {
            doSend(initialSend);
        }

        readThread = new Thread(this::readLoop, "collab-read-" + socket.getRemoteSocketAddress());
        readThread.setDaemon(true);
    }

    void start() {
        readThread.start();
    }

    /** Send an XML message. Returns false if the connection is broken. */
    boolean send(String xml) {
        try {
            doSend(xml);
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    private synchronized void doSend(String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private void readLoop() {
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!socket.isClosed()) {
                int len = in.readInt();
                if (len <= 0 || len > 8 * 1024 * 1024) break; // sanity check: max 8 MB
                byte[] buf = new byte[len];
                in.readFully(buf);
                String xml = new String(buf, StandardCharsets.UTF_8);
                MessageListener l = receiveListener;
                if (l != null) l.onMessage(xml);
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
