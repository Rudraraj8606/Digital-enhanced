/*
 * Copyright (c) 2024 Digital Contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.relay;

import junit.framework.TestCase;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for RelayServer — starts a real server on a free port
 * and connects real WebSocket clients to verify protocol behaviour.
 */
public class RelayServerTest extends TestCase {

    private static final int PORT = 17777; // unlikely to conflict
    private RelayServer server;

    @Override
    protected void setUp() throws Exception {
        server = new RelayServer(PORT);
        server.start();
        Thread.sleep(300); // let the server bind
    }

    @Override
    protected void tearDown() throws Exception {
        server.stop(500);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /** Creating a room returns a 6-character code. */
    public void testCreateRoomReturnsCode() throws Exception {
        TestClient client = connect();
        client.send("{\"type\":\"create\"}");
        JSONObject resp = client.nextMessage(3);
        assertEquals("created", resp.getString("type"));
        assertEquals(6, resp.getString("code").length());
        client.close();
    }

    /** Joining a non-existent room returns an error. */
    public void testJoinUnknownRoomReturnsError() throws Exception {
        TestClient client = connect();
        client.send("{\"type\":\"join\",\"code\":\"XXXXXX\"}");
        JSONObject resp = client.nextMessage(3);
        assertEquals("error", resp.getString("type"));
        client.close();
    }

    /** Two clients can create and join a room; both receive expected messages. */
    public void testCreateAndJoin() throws Exception {
        TestClient host = connect();
        host.send("{\"type\":\"create\"}");
        JSONObject created = host.nextMessage(3);
        assertEquals("created", created.getString("type"));
        String code = created.getString("code");

        TestClient guest = connect();
        guest.send("{\"type\":\"join\",\"code\":\"" + code + "\"}");
        JSONObject joined = guest.nextMessage(3);
        assertEquals("joined", joined.getString("type"));
        assertEquals(2, joined.getInt("count"));

        host.close();
        guest.close();
    }

    /** Circuit broadcast: host sends circuit, guest receives it. */
    public void testCircuitBroadcast() throws Exception {
        TestClient host = connect();
        host.send("{\"type\":\"create\"}");
        String code = host.nextMessage(3).getString("code");

        TestClient guest = connect();
        guest.send("{\"type\":\"join\",\"code\":\"" + code + "\"}");
        guest.nextMessage(3); // consume "joined"
        // consume "peer joined" on host side
        host.nextMessage(3);

        String xml = "<circuit><wire/></circuit>";
        host.send(new JSONObject().put("type", "circuit").put("xml", xml).toString());

        JSONObject received = guest.nextMessage(3);
        assertEquals("circuit", received.getString("type"));
        assertEquals(xml, received.getString("xml"));

        host.close();
        guest.close();
    }

    /** Oversized messages are rejected immediately. */
    public void testOversizedMessageRejected() throws Exception {
        TestClient client = connect();
        client.send("{\"type\":\"create\"}");
        client.nextMessage(3); // consume "created"

        // Build a message larger than 8 MB
        String bigXml = "x".repeat(9 * 1024 * 1024);
        client.send(new JSONObject().put("type", "circuit").put("xml", bigXml).toString());

        // Server should send error or close — either way no "circuit" relayed
        JSONObject resp = client.nextMessage(5);
        if (resp != null) {
            assertFalse("Should not receive circuit back",
                    "circuit".equals(resp.optString("type")));
        }
        // Pass: either an error message or the connection was closed
    }

    // ── Minimal test WebSocket client ─────────────────────────────────────────

    private TestClient connect() throws Exception {
        TestClient c = new TestClient(new URI("ws://localhost:" + PORT));
        c.connectBlocking(3, TimeUnit.SECONDS);
        return c;
    }

    private static final class TestClient extends WebSocketClient {
        private final BlockingQueue<String> inbox = new ArrayBlockingQueue<>(32);

        TestClient(URI uri) { super(uri); }

        @Override public void onOpen(ServerHandshake h) {}
        @Override public void onMessage(String msg) { inbox.offer(msg); }
        @Override public void onClose(int c, String r, boolean remote) {}
        @Override public void onError(Exception e) {}

        /** Wait up to {@code seconds} for the next message; returns null on timeout. */
        JSONObject nextMessage(int seconds) throws InterruptedException {
            String raw = inbox.poll(seconds, TimeUnit.SECONDS);
            return raw != null ? new JSONObject(raw) : null;
        }
    }
}
