package com.kasagi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kasagi.server.SyncServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Networking Requirements:
 * - WebSocket protocol verification
 * - Real-time communication
 * - Message format validation
 */
@DisplayName("Networking Tests")
class NetworkingTest {

    private static SyncServer server;
    private static final int TEST_PORT = 9090;
    private static final String WS_URL = "ws://localhost:" + TEST_PORT + "/sync";
    private static ExecutorService serverExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void startServer() throws Exception {
        server = new SyncServer(TEST_PORT);
        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.submit(() -> {
            try {
                server.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait for server to start
        Thread.sleep(1000);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.shutdown();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    // ==========================================
    // Test: WebSocket Connection
    // ==========================================

    @Test
    @DisplayName("Should establish WebSocket connection successfully")
    void testWebSocketConnection() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        AtomicReference<Boolean> connected = new AtomicReference<>(false);

        WebSocketClient client = createClient(connectLatch, connected, null, null);
        client.connect();

        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Should connect within timeout");
        assertTrue(connected.get(), "Connection should be established");

        client.close();
        System.out.println("✓ WebSocket connection established successfully");
    }

    @Test
    @DisplayName("Should handle multiple simultaneous connections")
    void testMultipleConnections() throws Exception {
        int connectionCount = 50;
        CountDownLatch connectLatch = new CountDownLatch(connectionCount);
        CopyOnWriteArrayList<WebSocketClient> clients = new CopyOnWriteArrayList<>();

        for (int i = 0; i < connectionCount; i++) {
            WebSocketClient client = new WebSocketClient(new URI(WS_URL)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    clients.add(this);
                    connectLatch.countDown();
                }

                @Override
                public void onMessage(String message) {}

                @Override
                public void onClose(int code, String reason, boolean remote) {}

                @Override
                public void onError(Exception ex) {
                    connectLatch.countDown();
                }
            };
            client.connect();
        }

        assertTrue(connectLatch.await(10, TimeUnit.SECONDS), "All connections should establish");
        assertEquals(connectionCount, clients.size(), "All clients should be connected");

        // Cleanup
        clients.forEach(WebSocketClient::close);
        System.out.println("✓ Successfully handled " + connectionCount + " simultaneous connections");
    }

    // ==========================================
    // Test: Message Protocol
    // ==========================================

    @Test
    @DisplayName("Should receive FULL_STATE after JOIN_ROOM")
    void testJoinRoomProtocol() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        WebSocketClient client = createClient(connectLatch, new AtomicReference<>(false), messageLatch, receivedMessage);
        client.connect();

        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Should connect");

        // Send JOIN_ROOM
        String joinMessage = """
            {
                "type": "JOIN_ROOM",
                "roomId": "test-room",
                "payload": {
                    "playerName": "TestPlayer",
                    "color": "#FF0000"
                }
            }
            """;
        client.send(joinMessage);

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "Should receive response");

        // Verify response
        JsonNode response = objectMapper.readTree(receivedMessage.get());
        assertEquals("FULL_STATE", response.get("type").asText(), "Should receive FULL_STATE");
        assertTrue(response.has("playerId"), "Should include playerId");
        assertTrue(response.has("payload"), "Should include payload");
        assertTrue(response.get("payload").has("players"), "Payload should include players");

        client.close();
        System.out.println("✓ JOIN_ROOM protocol works correctly");
    }

    @Test
    @DisplayName("Should broadcast PLAYER_JOINED to other players")
    void testPlayerJoinedBroadcast() throws Exception {
        CountDownLatch connect1 = new CountDownLatch(1);
        CountDownLatch connect2 = new CountDownLatch(1);
        CountDownLatch player1Message = new CountDownLatch(2); // FULL_STATE + PLAYER_JOINED
        AtomicReference<String> lastMessage = new AtomicReference<>();

        // First player connects and joins
        WebSocketClient client1 = new WebSocketClient(new URI(WS_URL)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connect1.countDown();
            }

            @Override
            public void onMessage(String message) {
                lastMessage.set(message);
                player1Message.countDown();
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
        };
        client1.connect();
        assertTrue(connect1.await(5, TimeUnit.SECONDS));

        client1.send("""
            {"type": "JOIN_ROOM", "roomId": "broadcast-test", "payload": {"playerName": "Player1"}}
            """);

        Thread.sleep(500); // Wait for FULL_STATE

        // Second player joins
        WebSocketClient client2 = new WebSocketClient(new URI(WS_URL)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connect2.countDown();
            }

            @Override
            public void onMessage(String message) {}

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
        };
        client2.connect();
        assertTrue(connect2.await(5, TimeUnit.SECONDS));

        client2.send("""
            {"type": "JOIN_ROOM", "roomId": "broadcast-test", "payload": {"playerName": "Player2"}}
            """);

        assertTrue(player1Message.await(5, TimeUnit.SECONDS), "Player1 should receive messages");

        // Verify Player1 received PLAYER_JOINED notification
        JsonNode notification = objectMapper.readTree(lastMessage.get());
        assertEquals("PLAYER_JOINED", notification.get("type").asText(), "Should receive PLAYER_JOINED");
        assertEquals("Player2", notification.get("payload").get("playerName").asText());

        client1.close();
        client2.close();
        System.out.println("✓ PLAYER_JOINED broadcast works correctly");
    }

    @Test
    @DisplayName("Should broadcast DELTA_UPDATE for state changes")
    void testDeltaUpdateBroadcast() throws Exception {
        CountDownLatch connect1 = new CountDownLatch(1);
        CountDownLatch connect2 = new CountDownLatch(1);
        CountDownLatch deltaReceived = new CountDownLatch(1);
        AtomicReference<String> deltaMessage = new AtomicReference<>();

        // First player
        WebSocketClient client1 = new WebSocketClient(new URI(WS_URL)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connect1.countDown();
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonNode msg = objectMapper.readTree(message);
                    if ("DELTA_UPDATE".equals(msg.get("type").asText())) {
                        deltaMessage.set(message);
                        deltaReceived.countDown();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
        };

        // Second player
        WebSocketClient client2 = new WebSocketClient(new URI(WS_URL)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connect2.countDown();
            }

            @Override
            public void onMessage(String message) {}

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
        };

        // Connect both
        client1.connect();
        client2.connect();
        assertTrue(connect1.await(5, TimeUnit.SECONDS));
        assertTrue(connect2.await(5, TimeUnit.SECONDS));

        // Both join same room
        String roomId = "delta-test-" + System.currentTimeMillis();
        client1.send("{\"type\": \"JOIN_ROOM\", \"roomId\": \"" + roomId + "\", \"payload\": {\"playerName\": \"P1\"}}");
        Thread.sleep(300);
        client2.send("{\"type\": \"JOIN_ROOM\", \"roomId\": \"" + roomId + "\", \"payload\": {\"playerName\": \"P2\"}}");
        Thread.sleep(300);

        // Player2 moves
        client2.send("{\"type\": \"STATE_UPDATE\", \"roomId\": \"" + roomId + "\", \"payload\": {\"x\": 200, \"y\": 300}}");

        assertTrue(deltaReceived.await(5, TimeUnit.SECONDS), "Player1 should receive delta update");

        JsonNode delta = objectMapper.readTree(deltaMessage.get());
        assertEquals("DELTA_UPDATE", delta.get("type").asText());
        assertTrue(delta.has("version"), "Delta should include version");

        client1.close();
        client2.close();
        System.out.println("✓ DELTA_UPDATE broadcast works correctly");
    }

    @Test
    @DisplayName("Should handle malformed messages gracefully")
    void testMalformedMessageHandling() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        WebSocketClient client = new WebSocketClient(new URI(WS_URL)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connectLatch.countDown();
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonNode msg = objectMapper.readTree(message);
                    if ("ERROR".equals(msg.get("type").asText())) {
                        errorMessage.set(message);
                        errorLatch.countDown();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
        };

        client.connect();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        // Send invalid JSON
        client.send("not valid json {{{");

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Should receive error response");
        assertTrue(errorMessage.get().contains("ERROR"), "Should be an error message");

        client.close();
        System.out.println("✓ Malformed messages handled gracefully");
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private WebSocketClient createClient(CountDownLatch connectLatch, AtomicReference<Boolean> connected,
                                         CountDownLatch messageLatch, AtomicReference<String> message) throws Exception {
        return new WebSocketClient(new URI(WS_URL)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connected.set(true);
                connectLatch.countDown();
            }

            @Override
            public void onMessage(String msg) {
                if (message != null) {
                    message.set(msg);
                }
                if (messageLatch != null) {
                    messageLatch.countDown();
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {
                connectLatch.countDown();
            }
        };
    }
}
