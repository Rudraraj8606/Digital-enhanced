/*
 * Copyright (c) 2024 Digital Contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.collab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Hosts a real-time collaboration session.
 * Accepts guest connections and broadcasts circuit XML on every change.
 * Also receives circuit updates from guests and forwards them to the host app
 * and to all other guests.
 */
public class CollabHost implements Closeable {

    /** Called when a guest sends a circuit update. */
    public interface UpdateListener {
        void onCircuitReceived(String xml);
    }

    private final ServerSocket serverSocket;
    private final List<PeerConnection> guests = Collections.synchronizedList(new ArrayList<>());
    private final Thread acceptThread;
    private volatile UpdateListener updateListener;

    /**
     * Creates and starts a host on the given port (0 = auto-assign).
     */
    public CollabHost(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        acceptThread = new Thread(this::acceptLoop, "collab-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** @return the actual port the server is listening on. */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** @return a best-effort local IP address to share with guests. */
    public String getLocalAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "localhost";
    }

    /** Register a listener that is called on the EDT when a guest sends a circuit update. */
    public void setUpdateListener(UpdateListener listener) {
        this.updateListener = listener;
    }

    /**
     * Broadcast the given circuit XML to all connected guests.
     * Called by the host app after every local circuit change.
     */
    public void broadcast(String xml) {
        broadcast(xml, null);
    }

    private void broadcast(String xml, PeerConnection except) {
        synchronized (guests) {
            guests.removeIf(g -> !g.send(xml));
        }
    }

    /** Called by a PeerConnection when it receives XML from its guest. */
    void onGuestUpdate(String xml, PeerConnection sender) {
        // Forward to host app
        UpdateListener l = updateListener;
        if (l != null) {
            javax.swing.SwingUtilities.invokeLater(() -> l.onCircuitReceived(xml));
        }
        // Relay to all other guests
        synchronized (guests) {
            guests.removeIf(g -> {
                if (g == sender) return false;
                return !g.send(xml);
            });
        }
    }

    private void acceptLoop() {
        try {
            while (!serverSocket.isClosed()) {
                Socket s = serverSocket.accept();
                PeerConnection conn = new PeerConnection(s, null, xml -> onGuestUpdate(xml, null));
                synchronized (guests) {
                    guests.add(conn);
                    // Override the listener to pass the correct sender reference
                    PeerConnection finalConn = conn;
                    conn.receiveListener = xml -> onGuestUpdate(xml, finalConn);
                }
                conn.start();
            }
        } catch (IOException ignored) {
        }
    }

    /** Send the current circuit to a newly joined guest (call after getting the XML). */
    public void sendToAll(String xml) {
        broadcast(xml);
    }

    @Override
    public void close() {
        try { serverSocket.close(); } catch (IOException ignored) {}
        acceptThread.interrupt();
        synchronized (guests) {
            for (PeerConnection g : guests) g.close();
        }
    }

    /** @return number of connected guests */
    public int getGuestCount() {
        return guests.size();
    }
}
