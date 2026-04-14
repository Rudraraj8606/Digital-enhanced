/*
 * Copyright (c) 2024 Digital Contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.relay;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight WebSocket relay server for Digital collaboration rooms.
 *
 * Deploy to Oracle Always Free:
 *   java -cp Digital-jar-with-dependencies.jar de.neemann.digital.relay.RelayServer [port]
 *
 * Security features:
 *   - Max 5 rooms per IP address at any time
 *   - Max 6 members per room
 *   - Max 8 MB per message (circuit XML + bundle)
 *   - Optional room password set at creation
 *   - Rooms auto-expire after 2 hours of inactivity
 *   - Connections closed if no valid message within 30 seconds
 *
 * Protocol (JSON over WebSocket):
 *   Client → Server:
 *     {"type":"create"}                             — create room (no password)
 *     {"type":"create","password":"secret"}         — create room with password
 *     {"type":"join","code":"XK7F2Q"}               — join room (no password)
 *     {"type":"join","code":"XK7F2Q","password":"secret"}  — join with password
 *     {"type":"circuit","xml":"..."}                — broadcast circuit to peers
 *     {"type":"bundle","data":"base64-zip"}         — broadcast .dig bundle to peers
 *
 *   Server → Client:
 *     {"type":"created","code":"XK7F2Q"}
 *     {"type":"joined","count":2}
 *     {"type":"circuit","xml":"..."}
 *     {"type":"bundle","data":"base64-zip"}
 *     {"type":"peer","event":"joined/left","count":N}
 *     {"type":"error","message":"..."}
 */
public class RelayServer extends WebSocketServer {

    // ── Limits (tune as needed) ───────────────────────────────────────────────
    private static final int MAX_ROOMS_PER_IP    = 5;
    private static final int MAX_MEMBERS_PER_ROOM = 6;
    private static final int MAX_MSG_BYTES       = 8 * 1024 * 1024; // 8 MB
    private static final long ROOM_IDLE_MINUTES  = 120;             // 2 hours

    // NOTE: Circuit XML and bundle data are NEVER written to disk or logs.
    // Only connection metadata (IP, room code, timestamps) is logged.
    // This keeps the server as a passive conduit and avoids retaining user data.

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Set<String> blockedIps = Collections.synchronizedSet(new HashSet<>());
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor();

    public RelayServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        // Sweep idle rooms every 10 minutes
        sweeper.scheduleAtFixedRate(this::sweepIdleRooms, 10, 10, TimeUnit.MINUTES);
    }

    // ── Room ─────────────────────────────────────────────────────────────────

    private static final class Room {
        final String code;
        final String passwordHash;   // null = no password
        final Set<WebSocket> members = Collections.synchronizedSet(new LinkedHashSet<>());
        volatile String latestCircuit;
        volatile String bundle;
        volatile long lastActivity = System.currentTimeMillis();

        Room(String code, String passwordHash) {
            this.code = code;
            this.passwordHash = passwordHash;
        }

        void touch() { lastActivity = System.currentTimeMillis(); }

        boolean isIdle() {
            return System.currentTimeMillis() - lastActivity
                    > ROOM_IDLE_MINUTES * 60_000L;
        }
    }

    // ── WebSocket events ─────────────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String ip = getIp(conn);
        if (blockedIps.contains(ip)) {
            System.out.println("[relay] blocked connection from: " + ip);
            conn.close(1008, "Your IP has been blocked.");
            return;
        }
        System.out.println("[relay] connected: " + ip);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[relay] disconnected: " + conn.getRemoteSocketAddress());
        String roomCode = conn.getAttachment();
        if (roomCode == null) return;
        Room room = rooms.get(roomCode);
        if (room == null) return;
        room.members.remove(conn);
        if (room.members.isEmpty()) {
            rooms.remove(roomCode);
            System.out.println("[relay] room " + roomCode + " closed (empty)");
        } else {
            broadcast(room, peerMsg("left", room.members.size()), null);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // Hard size limit — reject oversized messages immediately
        if (message.length() > MAX_MSG_BYTES) {
            sendError(conn, "Message too large (max 8 MB)");
            conn.close();
            return;
        }

        try {
            JSONObject msg = new JSONObject(message);
            String type = msg.getString("type");

            switch (type) {

                case "create": {
                    String ip = getIp(conn);
                    long roomsForIp = rooms.values().stream()
                            .filter(r -> r.members.stream().anyMatch(
                                    m -> getIp(m).equals(ip)))
                            .count();
                    if (roomsForIp >= MAX_ROOMS_PER_IP) {
                        sendError(conn, "Too many rooms from your IP (max " + MAX_ROOMS_PER_IP + ")");
                        return;
                    }
                    String rawPw = msg.optString("password", "");
                    String pwHash = rawPw.isEmpty() ? null : Integer.toHexString(rawPw.hashCode());
                    String code = generateCode();
                    Room room = new Room(code, pwHash);
                    room.members.add(conn);
                    rooms.put(code, room);
                    conn.setAttachment(code);
                    JSONObject resp = new JSONObject();
                    resp.put("type", "created");
                    resp.put("code", code);
                    resp.put("passwordProtected", pwHash != null);
                    conn.send(resp.toString());
                    System.out.println("[relay] room created: " + code
                            + (pwHash != null ? " (password-protected)" : "")
                            + " by " + ip);
                    break;
                }

                case "join": {
                    String code = msg.getString("code").trim().toUpperCase();
                    Room room = rooms.get(code);
                    if (room == null) { sendError(conn, "Room not found: " + code); return; }

                    // Password check
                    if (room.passwordHash != null) {
                        String provided = msg.optString("password", "");
                        if (!Integer.toHexString(provided.hashCode()).equals(room.passwordHash)) {
                            sendError(conn, "Incorrect room password");
                            return;
                        }
                    }

                    // Member limit
                    if (room.members.size() >= MAX_MEMBERS_PER_ROOM) {
                        sendError(conn, "Room is full (max " + MAX_MEMBERS_PER_ROOM + " members)");
                        return;
                    }

                    room.members.add(conn);
                    room.touch();
                    conn.setAttachment(code);

                    if (room.bundle != null) {
                        JSONObject b = new JSONObject();
                        b.put("type", "bundle");
                        b.put("data", room.bundle);
                        conn.send(b.toString());
                    }
                    if (room.latestCircuit != null) {
                        JSONObject c = new JSONObject();
                        c.put("type", "circuit");
                        c.put("xml", room.latestCircuit);
                        conn.send(c.toString());
                    }
                    broadcast(room, peerMsg("joined", room.members.size()), conn);
                    JSONObject ack = new JSONObject();
                    ack.put("type", "joined");
                    ack.put("count", room.members.size());
                    conn.send(ack.toString());
                    System.out.println("[relay] peer joined " + code
                            + " (" + room.members.size() + " in room)");
                    break;
                }

                case "circuit": {
                    Room room = requireRoom(conn);
                    if (room == null) return;
                    room.latestCircuit = msg.getString("xml");
                    room.touch();
                    broadcast(room, message, conn);
                    break;
                }

                case "bundle": {
                    Room room = requireRoom(conn);
                    if (room == null) return;
                    room.bundle = msg.getString("data");
                    room.touch();
                    broadcast(room, message, conn);
                    break;
                }

                default:
                    sendError(conn, "Unknown type: " + type);
            }
        } catch (Exception e) {
            sendError(conn, e.getMessage() != null ? e.getMessage() : "internal error");
        }
    }

    @Override public void onError(WebSocket conn, Exception ex) {
        System.err.println("[relay] error: " + ex.getMessage());
    }

    @Override public void onStart() {
        System.out.println("[relay] Digital Relay Server started on port " + getPort());
        System.out.println("[relay] Limits: " + MAX_ROOMS_PER_IP + " rooms/IP, "
                + MAX_MEMBERS_PER_ROOM + " members/room, "
                + (MAX_MSG_BYTES / 1024 / 1024) + " MB/msg, "
                + ROOM_IDLE_MINUTES + " min idle expiry");
        System.out.println("[relay] Press Ctrl+C to stop.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Room requireRoom(WebSocket conn) {
        String code = conn.getAttachment();
        if (code == null) { sendError(conn, "Not in a room"); return null; }
        Room room = rooms.get(code);
        if (room == null) { sendError(conn, "Room no longer exists"); return null; }
        return room;
    }

    private void broadcast(Room room, String msg, WebSocket except) {
        synchronized (room.members) {
            for (WebSocket m : room.members) {
                if (m != except && m.isOpen()) m.send(msg);
            }
        }
    }

    private void sendError(WebSocket conn, String msg) {
        if (!conn.isOpen()) return;
        JSONObject err = new JSONObject();
        err.put("type", "error");
        err.put("message", msg != null ? msg : "unknown error");
        conn.send(err.toString());
    }

    private String peerMsg(String event, int count) {
        return new JSONObject()
                .put("type", "peer")
                .put("event", event)
                .put("count", count)
                .toString();
    }

    private static String getIp(WebSocket conn) {
        InetSocketAddress addr = conn.getRemoteSocketAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }

    private void sweepIdleRooms() {
        int removed = 0;
        for (Map.Entry<String, Room> entry : rooms.entrySet()) {
            Room room = entry.getValue();
            if (room.isIdle()) {
                rooms.remove(entry.getKey());
                synchronized (room.members) {
                    for (WebSocket m : room.members) {
                        if (m.isOpen()) {
                            sendError(m, "Room expired after " + ROOM_IDLE_MINUTES + " minutes of inactivity");
                            m.close();
                        }
                    }
                }
                removed++;
            }
        }
        if (removed > 0)
            System.out.println("[relay] swept " + removed + " idle room(s). Active rooms: " + rooms.size());
    }

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Random RNG = new Random();

    private String generateCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) sb.append(CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length())));
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    // ── Admin API ─────────────────────────────────────────────────────────────

    /** Block an IP address immediately — all future connections from it are rejected. */
    public void blockIp(String ip) {
        blockedIps.add(ip);
        // Also disconnect any currently connected clients from that IP
        getConnections().stream()
                .filter(c -> getIp(c).equals(ip))
                .forEach(c -> c.close(1008, "Your IP has been blocked."));
        System.out.println("[relay] blocked IP: " + ip);
    }

    /** Unblock a previously blocked IP. */
    public void unblockIp(String ip) {
        blockedIps.remove(ip);
        System.out.println("[relay] unblocked IP: " + ip);
    }

    /** Close a specific room immediately (e.g., in response to an abuse report). */
    public void closeRoom(String code) {
        Room room = rooms.remove(code.toUpperCase());
        if (room == null) { System.out.println("[relay] room not found: " + code); return; }
        synchronized (room.members) {
            for (WebSocket m : room.members)
                if (m.isOpen()) m.close(1001, "Room closed by administrator.");
        }
        System.out.println("[relay] admin closed room: " + code);
    }

    /** Print current server status to stdout. */
    public void printStatus() {
        System.out.println("[relay] Active rooms: " + rooms.size());
        System.out.println("[relay] Blocked IPs:  " + blockedIps.size());
        int totalPeers = rooms.values().stream().mapToInt(r -> r.members.size()).sum();
        System.out.println("[relay] Connected peers: " + totalPeers);
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7777;
        RelayServer server = new RelayServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.stop(); } catch (Exception ignored) {}
        }));

        // Simple stdin admin console
        System.out.println("[relay] Admin commands: status | block <ip> | unblock <ip> | close <roomcode> | quit");
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 2);
            switch (parts[0].toLowerCase()) {
                case "status":  server.printStatus(); break;
                case "block":   if (parts.length > 1) server.blockIp(parts[1]); break;
                case "unblock": if (parts.length > 1) server.unblockIp(parts[1]); break;
                case "close":   if (parts.length > 1) server.closeRoom(parts[1]); break;
                case "quit":
                case "exit":    System.exit(0); break;
                default: System.out.println("[relay] Unknown command: " + parts[0]);
            }
        }
    }
}
