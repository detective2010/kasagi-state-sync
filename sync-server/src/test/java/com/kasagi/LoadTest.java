package com.kasagi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kasagi.server.SyncServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Load Tests for Stress Testing the System
 *
 * These tests simulate real-world load scenarios to verify:
 * - Maximum concurrent connections
 * - Message throughput under load
 * - System behavior under stress
 * - Resource usage patterns
 */
@DisplayName("Load Tests")
@Tag("load") // Can be excluded from regular test runs
class LoadTest {

    private static SyncServer server;
    private static final int TEST_PORT = 9092;
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
        Thread.sleep(2000); // Give server more time to start
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
    // Load Test: Maximum Connections
    // ==========================================

    @Test
    @DisplayName("Should handle 100 concurrent WebSocket connections")
    void testMaxConcurrentConnections() throws Exception {
        int targetConnections = 100;
        CountDownLatch connectLatch = new CountDownLatch(targetConnections);
        List<WebSocketClient> clients = new CopyOnWriteArrayList<>();
        AtomicInteger connectionErrors = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Create and connect clients
        ExecutorService executor = Executors.newFixedThreadPool(20);

        for (int i = 0; i < targetConnections; i++) {
            executor.submit(() -> {
                try {
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
                            connectionErrors.incrementAndGet();
                            connectLatch.countDown();
                        }
                    };
                    client.connect();
                } catch (Exception e) {
                    connectionErrors.incrementAndGet();
                    connectLatch.countDown();
                }
            });
        }

        connectLatch.await(60, TimeUnit.SECONDS);
        long connectionTime = System.currentTimeMillis() - startTime;

        executor.shutdown();

        System.out.println("\n========================================");
        System.out.println("LOAD TEST: Maximum Concurrent Connections");
        System.out.println("========================================");
        System.out.println("Target connections: " + targetConnections);
        System.out.println("Successful connections: " + clients.size());
        System.out.println("Connection errors: " + connectionErrors.get());
        System.out.println("Connection time: " + connectionTime + "ms");
        System.out.println("Connections/second: " + (clients.size() * 1000 / connectionTime));
        System.out.println("========================================\n");

        // Cleanup
        for (WebSocketClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                // ignore
            }
        }

        assertTrue(clients.size() >= targetConnections * 0.95,
                "At least 95% of connections should succeed");
    }

    // ==========================================
    // Load Test: Message Throughput
    // ==========================================

    @Test
    @DisplayName("Should handle high message throughput")
    void testMessageThroughput() throws Exception {
        int clientCount = 10;
        int messagesPerClient = 100;
        int totalMessages = clientCount * messagesPerClient;

        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch joinedLatch = new CountDownLatch(clientCount);
        AtomicInteger messagesSent = new AtomicInteger(0);
        AtomicInteger messagesReceived = new AtomicInteger(0);
        List<WebSocketClient> clients = new CopyOnWriteArrayList<>();

        String roomId = "throughput-test-" + System.currentTimeMillis();

        // Create clients
        for (int i = 0; i < clientCount; i++) {
            WebSocketClient client = new WebSocketClient(new URI(WS_URL)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    clients.add(this);
                    connectLatch.countDown();
                }

                @Override
                public void onMessage(String message) {
                    messagesReceived.incrementAndGet();
                    try {
                        JsonNode msg = objectMapper.readTree(message);
                        if ("FULL_STATE".equals(msg.get("type").asText())) {
                            joinedLatch.countDown();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {}

                @Override
                public void onError(Exception ex) {
                    connectLatch.countDown();
                }
            };
            client.connect();
        }

        assertTrue(connectLatch.await(30, TimeUnit.SECONDS), "All clients should connect");

        // All clients join the same room
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i).send("{\"type\": \"JOIN_ROOM\", \"roomId\": \"" + roomId +
                    "\", \"payload\": {\"playerName\": \"LoadClient" + i + "\"}}");
        }

        assertTrue(joinedLatch.await(30, TimeUnit.SECONDS), "All clients should join room");

        // Reset message counter after join
        messagesReceived.set(0);

        // Send messages from all clients
        long startTime = System.nanoTime();

        ExecutorService sender = Executors.newFixedThreadPool(clientCount);
        CountDownLatch sendLatch = new CountDownLatch(totalMessages);

        for (int c = 0; c < clientCount; c++) {
            final WebSocketClient client = clients.get(c);
            sender.submit(() -> {
                for (int m = 0; m < messagesPerClient; m++) {
                    client.send("{\"type\": \"STATE_UPDATE\", \"roomId\": \"" + roomId +
                            "\", \"payload\": {\"x\": " + (m * 10) + ", \"y\": " + (m * 5) + "}}");
                    messagesSent.incrementAndGet();
                    sendLatch.countDown();
                }
            });
        }

        assertTrue(sendLatch.await(30, TimeUnit.SECONDS), "All messages should be sent");

        // Wait for messages to be broadcast
        Thread.sleep(2000);

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        sender.shutdown();

        // Each message is broadcast to (clientCount - 1) other clients
        int expectedReceived = totalMessages * (clientCount - 1);

        System.out.println("\n========================================");
        System.out.println("LOAD TEST: Message Throughput");
        System.out.println("========================================");
        System.out.println("Clients: " + clientCount);
        System.out.println("Messages per client: " + messagesPerClient);
        System.out.println("Total messages sent: " + messagesSent.get());
        System.out.println("Messages received: " + messagesReceived.get());
        System.out.println("Expected received: " + expectedReceived);
        System.out.println("Duration: " + durationMs + "ms");
        System.out.println("Send throughput: " + (messagesSent.get() * 1000 / durationMs) + " msg/sec");
        System.out.println("========================================\n");

        // Cleanup
        for (WebSocketClient client : clients) {
            client.close();
        }

        assertTrue(messagesSent.get() == totalMessages, "All messages should be sent");
    }

    // ==========================================
    // Load Test: Sustained Load
    // ==========================================

    @Test
    @DisplayName("Should handle sustained load over time")
    void testSustainedLoad() throws Exception {
        int clientCount = 5;
        int durationSeconds = 5;
        int updatesPerSecond = 30; // 30 FPS simulation

        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch joinedLatch = new CountDownLatch(clientCount);
        AtomicLong totalMessagesSent = new AtomicLong(0);
        AtomicLong totalMessagesReceived = new AtomicLong(0);
        List<WebSocketClient> clients = new CopyOnWriteArrayList<>();

        String roomId = "sustained-test-" + System.currentTimeMillis();

        // Create and connect clients
        for (int i = 0; i < clientCount; i++) {
            WebSocketClient client = new WebSocketClient(new URI(WS_URL)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    clients.add(this);
                    connectLatch.countDown();
                }

                @Override
                public void onMessage(String message) {
                    totalMessagesReceived.incrementAndGet();
                    try {
                        JsonNode msg = objectMapper.readTree(message);
                        if ("FULL_STATE".equals(msg.get("type").asText())) {
                            joinedLatch.countDown();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {}

                @Override
                public void onError(Exception ex) {
                    connectLatch.countDown();
                }
            };
            client.connect();
        }

        assertTrue(connectLatch.await(30, TimeUnit.SECONDS));

        // Join room
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i).send("{\"type\": \"JOIN_ROOM\", \"roomId\": \"" + roomId +
                    "\", \"payload\": {\"playerName\": \"SustainedClient" + i + "\"}}");
        }

        assertTrue(joinedLatch.await(30, TimeUnit.SECONDS));

        // Reset counters
        totalMessagesReceived.set(0);

        // Run sustained load
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(clientCount);
        long intervalMs = 1000 / updatesPerSecond;

        long startTime = System.currentTimeMillis();

        List<ScheduledFuture<?>> futures = new ArrayList<>();
        for (int c = 0; c < clientCount; c++) {
            final WebSocketClient client = clients.get(c);
            final AtomicInteger counter = new AtomicInteger(0);

            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                int count = counter.incrementAndGet();
                client.send("{\"type\": \"STATE_UPDATE\", \"roomId\": \"" + roomId +
                        "\", \"payload\": {\"x\": " + (count % 800) + ", \"y\": " + (count % 600) + "}}");
                totalMessagesSent.incrementAndGet();
            }, 0, intervalMs, TimeUnit.MILLISECONDS);

            futures.add(future);
        }

        // Run for specified duration
        Thread.sleep(durationSeconds * 1000L);

        // Stop sending
        for (ScheduledFuture<?> future : futures) {
            future.cancel(false);
        }
        scheduler.shutdown();

        // Wait for remaining messages
        Thread.sleep(1000);

        long endTime = System.currentTimeMillis();
        long actualDuration = endTime - startTime;

        int expectedReceived = (int) (totalMessagesSent.get() * (clientCount - 1));

        System.out.println("\n========================================");
        System.out.println("LOAD TEST: Sustained Load");
        System.out.println("========================================");
        System.out.println("Clients: " + clientCount);
        System.out.println("Target updates/sec per client: " + updatesPerSecond);
        System.out.println("Test duration: " + actualDuration + "ms");
        System.out.println("Messages sent: " + totalMessagesSent.get());
        System.out.println("Messages received: " + totalMessagesReceived.get());
        System.out.println("Expected received: " + expectedReceived);
        System.out.println("Actual send rate: " + (totalMessagesSent.get() * 1000 / actualDuration) + " msg/sec");
        System.out.println("========================================\n");

        // Cleanup
        for (WebSocketClient client : clients) {
            client.close();
        }

        // Should achieve at least 80% of target rate
        long expectedSent = (long) clientCount * updatesPerSecond * durationSeconds;
        assertTrue(totalMessagesSent.get() >= expectedSent * 0.8,
                "Should achieve at least 80% of target send rate");
    }

    // ==========================================
    // Load Test: Burst Traffic
    // ==========================================

    @Test
    @DisplayName("Should handle burst traffic")
    void testBurstTraffic() throws Exception {
        int clientCount = 20;
        int messagesPerBurst = 50;

        CountDownLatch connectLatch = new CountDownLatch(clientCount);
        CountDownLatch joinedLatch = new CountDownLatch(clientCount);
        AtomicInteger messagesSent = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        List<WebSocketClient> clients = new CopyOnWriteArrayList<>();

        String roomId = "burst-test-" + System.currentTimeMillis();

        // Create clients
        for (int i = 0; i < clientCount; i++) {
            WebSocketClient client = new WebSocketClient(new URI(WS_URL)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    clients.add(this);
                    connectLatch.countDown();
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonNode msg = objectMapper.readTree(message);
                        if ("FULL_STATE".equals(msg.get("type").asText())) {
                            joinedLatch.countDown();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {}

                @Override
                public void onError(Exception ex) {
                    errors.incrementAndGet();
                    connectLatch.countDown();
                }
            };
            client.connect();
        }

        assertTrue(connectLatch.await(30, TimeUnit.SECONDS));

        // Join room
        for (int i = 0; i < clients.size(); i++) {
            clients.get(i).send("{\"type\": \"JOIN_ROOM\", \"roomId\": \"" + roomId +
                    "\", \"payload\": {\"playerName\": \"BurstClient" + i + "\"}}");
        }

        assertTrue(joinedLatch.await(30, TimeUnit.SECONDS));

        // Send burst of messages (all at once)
        long startTime = System.nanoTime();

        ExecutorService burst = Executors.newFixedThreadPool(clientCount);
        CountDownLatch burstLatch = new CountDownLatch(clientCount);

        for (WebSocketClient client : clients) {
            burst.submit(() -> {
                for (int m = 0; m < messagesPerBurst; m++) {
                    try {
                        client.send("{\"type\": \"STATE_UPDATE\", \"roomId\": \"" + roomId +
                                "\", \"payload\": {\"x\": " + m + ", \"y\": " + m + "}}");
                        messagesSent.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
                burstLatch.countDown();
            });
        }

        assertTrue(burstLatch.await(30, TimeUnit.SECONDS), "Burst should complete");

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        burst.shutdown();

        System.out.println("\n========================================");
        System.out.println("LOAD TEST: Burst Traffic");
        System.out.println("========================================");
        System.out.println("Clients: " + clientCount);
        System.out.println("Messages per client: " + messagesPerBurst);
        System.out.println("Total messages: " + (clientCount * messagesPerBurst));
        System.out.println("Successfully sent: " + messagesSent.get());
        System.out.println("Errors: " + errors.get());
        System.out.println("Burst duration: " + durationMs + "ms");
        System.out.println("Burst rate: " + (messagesSent.get() * 1000 / Math.max(1, durationMs)) + " msg/sec");
        System.out.println("========================================\n");

        // Cleanup
        for (WebSocketClient client : clients) {
            client.close();
        }

        assertEquals(0, errors.get(), "No errors should occur during burst");
    }
}
