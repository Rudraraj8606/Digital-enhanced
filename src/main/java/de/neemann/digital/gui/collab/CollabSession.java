/*
 * Copyright (c) 2024 Digital Contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.collab;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;

/**
 * Manages a real-time collaboration session with the Digital relay server.
 * After construction, call {@link #createRoom()} or {@link #joinRoom(String)}.
 */
public class CollabSession extends WebSocketClient {

    /** Callbacks — all invoked on the Swing EDT. */
    public interface Listener {
        /** Called after {@link #createRoom()} succeeds. */
        void onRoomCreated(String code);
        /** Called after {@link #joinRoom(String)} succeeds. */
        void onJoined(int peerCount);
        /** A peer sent a circuit update. */
        void onCircuitReceived(String xml);
        /** A peer sent a .dig file bundle (base64 zip). */
        void onBundleReceived(String base64Zip);
        /** Relay sent an error. */
        void onRelayError(String message);
        /** Connection lost. */
        void onDisconnected();
    }

    private volatile Listener listener;

    /**
     * Connect to the relay server.
     *
     * @param relayUrl ws://host:port  or  wss://host:port
     * @param listener event callbacks (called on EDT)
     */
    public CollabSession(String relayUrl, Listener listener) throws Exception {
        super(new URI(relayUrl));
        this.listener = listener;
        connectBlocking(); // blocks until connected (or throws on failure)
    }

    // ── WebSocketClient callbacks ─────────────────────────────────────────────

    @Override public void onOpen(ServerHandshake h) { /* connected */ }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject msg = new JSONObject(message);
            String type = msg.getString("type");
            switch (type) {
                case "created":
                    edt(() -> listener.onRoomCreated(msg.getString("code")));
                    break;
                case "joined":
                    edt(() -> listener.onJoined(msg.getInt("count")));
                    break;
                case "circuit":
                    edt(() -> listener.onCircuitReceived(msg.getString("xml")));
                    break;
                case "bundle":
                    edt(() -> listener.onBundleReceived(msg.getString("data")));
                    break;
                case "error":
                    edt(() -> listener.onRelayError(msg.optString("message", "relay error")));
                    break;
                // "peer" join/leave notifications — ignored for now
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        edt(listener::onDisconnected);
    }

    @Override
    public void onError(Exception ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        edt(() -> listener.onRelayError(msg));
    }

    // ── Outbound messages ─────────────────────────────────────────────────────

    /** Ask the relay to create a new room (no password). Response arrives via {@link Listener#onRoomCreated}. */
    public void createRoom() {
        send(new JSONObject().put("type", "create").toString());
    }

    /** Ask the relay to create a password-protected room. */
    public void createRoom(String password) {
        JSONObject msg = new JSONObject().put("type", "create");
        if (password != null && !password.isEmpty()) msg.put("password", password);
        send(msg.toString());
    }

    /** Join an existing room by code (no password). Response arrives via {@link Listener#onJoined}. */
    public void joinRoom(String code) {
        send(new JSONObject().put("type", "join").put("code", code.trim().toUpperCase()).toString());
    }

    /** Join a password-protected room. */
    public void joinRoom(String code, String password) {
        JSONObject msg = new JSONObject().put("type", "join").put("code", code.trim().toUpperCase());
        if (password != null && !password.isEmpty()) msg.put("password", password);
        send(msg.toString());
    }

    /** Broadcast a circuit XML update to all peers in the room. */
    public void sendCircuit(String xml) {
        if (!isOpen()) return;
        send(new JSONObject().put("type", "circuit").put("xml", xml).toString());
    }

    /** Broadcast a bundle of .dig files (base64-encoded zip) to all peers. */
    public void sendBundle(String base64Zip) {
        if (!isOpen()) return;
        send(new JSONObject().put("type", "bundle").put("data", base64Zip).toString());
    }

    /**
     * Swap the event listener — used to rewire from the bootstrap listener to
     * the dedicated collab window after it has been created.
     */
    public void setWindowListener(Listener newListener) {
        this.listener = newListener;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static void edt(Runnable r) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) r.run();
        else javax.swing.SwingUtilities.invokeLater(r);
    }
}
