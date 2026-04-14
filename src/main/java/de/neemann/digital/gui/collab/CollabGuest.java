/*
 * Copyright (c) 2024 Digital Contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.collab;

import java.io.*;
import java.net.*;

/**
 * Joins an existing collaboration session hosted by a {@link CollabHost}.
 * Receives circuit updates from the host and can send local changes back.
 */
public class CollabGuest implements Closeable {

    /** Called when the host sends a circuit update. */
    public interface UpdateListener {
        void onCircuitReceived(String xml);
    }

    private final PeerConnection conn;

    /**
     * Connect to a host.
     *
     * @param host     hostname or IP
     * @param port     port
     * @param listener called (on EDT) when the host sends a circuit update
     * @throws IOException if connection fails
     */
    public CollabGuest(String host, int port, UpdateListener listener) throws IOException {
        Socket socket = new Socket(host, port);
        conn = new PeerConnection(socket, null,
                xml -> javax.swing.SwingUtilities.invokeLater(() -> listener.onCircuitReceived(xml)));
        conn.start();
    }

    /**
     * Send a local circuit update to the host (e.g., after the user makes an edit).
     *
     * @param xml circuit XML
     */
    public void sendUpdate(String xml) {
        conn.send(xml);
    }

    @Override
    public void close() {
        conn.close();
    }
}
