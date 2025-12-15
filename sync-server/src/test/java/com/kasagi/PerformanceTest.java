package com.kasagi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kasagi.protocol.Message;
import com.kasagi.protocol.MessageSerializer;
import com.kasagi.protocol.MessageType;
import com.kasagi.state.Delta;
import com.kasagi.state.GameRoom;
import com.kasagi.state.PlayerState;
import com.kasagi.state.RoomManager;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Performance Requirements:
 * - Efficient serialization/deserialization
 * - Delta compression / partial state updates
 * - Bandwidth optimization
 */
@DisplayName("Performance Tests")
class PerformanceTest {

    private MessageSerializer serializer;
    private RoomManager roomManager;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
        roomManager = new RoomManager();
    }

    // ==========================================
    // Test: Serialization Performance
    // ==========================================

    @Test
    @DisplayName("Should serialize messages efficiently (< 1ms average)")
    void testSerializationPerformance() {
        ObjectNode payload = serializer.createObjectNode();
        payload.put("x", 100.5);
        payload.put("y", 200.5);
        payload.put("playerName", "TestPlayer");
        payload.put("color", "#FF0000");

        Message message = Message.builder()
                .type(MessageType.STATE_UPDATE)
                .roomId("test-room")
                .playerId("player-123")
                .payload(payload)
                .version(1000L)
                .build();

        // Warmup
        for (int i = 0; i < 1000; i++) {
            serializer.serialize(message);
        }

        // Benchmark
        int iterations = 10000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            serializer.serialize(message);
        }

        long endTime = System.nanoTime();
        double avgMicroseconds = (endTime - startTime) / 1000.0 / iterations;

        assertTrue(avgMicroseconds < 1000, "Serialization should take less than 1ms, took: " + avgMicroseconds + "μs");

        System.out.println("✓ Serialization performance:");
        System.out.println("  Average: " + String.format("%.2f", avgMicroseconds) + "μs per message");
        System.out.println("  Throughput: " + String.format("%.0f", 1_000_000 / avgMicroseconds) + " messages/second");
    }

    @Test
    @DisplayName("Should deserialize messages efficiently (< 1ms average)")
    void testDeserializationPerformance() {
        String json = """
            {
                "type": "STATE_UPDATE",
                "roomId": "test-room",
                "playerId": "player-123",
                "payload": {"x": 100.5, "y": 200.5, "playerName": "TestPlayer"},
                "version": 1000
            }
            """;

        // Warmup
        for (int i = 0; i < 1000; i++) {
            serializer.deserialize(json);
        }

        // Benchmark
        int iterations = 10000;
        long startTime = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            serializer.deserialize(json);
        }

        long endTime = System.nanoTime();
        double avgMicroseconds = (endTime - startTime) / 1000.0 / iterations;

        assertTrue(avgMicroseconds < 1000, "Deserialization should take less than 1ms, took: " + avgMicroseconds + "μs");

        System.out.println("✓ Deserialization performance:");
        System.out.println("  Average: " + String.format("%.2f", avgMicroseconds) + "μs per message");
        System.out.println("  Throughput: " + String.format("%.0f", 1_000_000 / avgMicroseconds) + " messages/second");
    }

    // ==========================================
    // Test: Delta Compression
    // ==========================================

    @Test
    @DisplayName("Delta updates should be smaller than full state")
    void testDeltaCompression() {
        GameRoom room = roomManager.getOrCreateRoom("delta-test");

        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("LongPlayerNameForTesting")
                .color("#FF00FF")
                .x(100.0)
                .y(200.0)
                .build();

        room.addPlayer("session-1", player);

        // Create full state message
        ObjectNode fullStatePayload = serializer.createObjectNode();
        ObjectNode playerNode = serializer.createObjectNode();
        playerNode.put("playerId", player.getPlayerId());
        playerNode.put("playerName", player.getPlayerName());
        playerNode.put("color", player.getColor());
        playerNode.put("x", player.getX());
        playerNode.put("y", player.getY());
        fullStatePayload.set("player-1", playerNode);

        Message fullStateMessage = Message.builder()
                .type(MessageType.FULL_STATE)
                .roomId("delta-test")
                .payload(fullStatePayload)
                .version(1L)
                .build();

        // Update position (small change)
        PlayerState newState = player.withPosition(101.0, 201.0);
        Delta delta = room.updatePlayerState("player-1", newState);

        // Create delta message
        ObjectNode deltaPayload = serializer.createObjectNode();
        ObjectNode changes = serializer.createObjectNode();
        for (var entry : delta.getChanges().entrySet()) {
            if (entry.getValue() instanceof Double) {
                changes.put(entry.getKey(), (Double) entry.getValue());
            }
        }
        deltaPayload.set("player-1", changes);

        Message deltaMessage = Message.builder()
                .type(MessageType.DELTA_UPDATE)
                .roomId("delta-test")
                .payload(deltaPayload)
                .version(2L)
                .build();

        // Compare sizes
        int fullStateSize = serializer.estimateSize(fullStateMessage);
        int deltaSize = serializer.estimateSize(deltaMessage);
        double compressionRatio = (1.0 - (double) deltaSize / fullStateSize) * 100;

        assertTrue(deltaSize < fullStateSize, "Delta should be smaller than full state");

        System.out.println("✓ Delta compression efficiency:");
        System.out.println("  Full state size: " + fullStateSize + " bytes");
        System.out.println("  Delta size: " + deltaSize + " bytes");
        System.out.println("  Compression ratio: " + String.format("%.1f", compressionRatio) + "% smaller");
    }

    @Test
    @DisplayName("Delta should only include changed fields")
    void testDeltaOnlyChangedFields() {
        GameRoom room = roomManager.getOrCreateRoom("partial-test");

        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test")
                .color("#000000")
                .x(0.0)
                .y(0.0)
                .build();

        room.addPlayer("session-1", player);

        // Only change X coordinate
        PlayerState newState = player.withPosition(100.0, 0.0);
        Delta delta = room.updatePlayerState("player-1", newState);

        assertTrue(delta.hasChanges(), "Delta should have changes");
        assertEquals(1, delta.getChanges().size(), "Delta should only have X change");
        assertTrue(delta.getChanges().containsKey("x"), "Delta should contain x");
        assertFalse(delta.getChanges().containsKey("y"), "Delta should NOT contain y (unchanged)");
        assertEquals(100.0, delta.getChanges().get("x"));

        System.out.println("✓ Delta correctly tracks only changed fields");
        System.out.println("  Changed fields: " + delta.getChanges().keySet());
    }

    @Test
    @DisplayName("No delta should be created when state is unchanged")
    void testNoDeltaForUnchangedState() {
        GameRoom room = roomManager.getOrCreateRoom("no-change-test");

        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test")
                .color("#000000")
                .x(50.0)
                .y(50.0)
                .build();

        room.addPlayer("session-1", player);

        // "Update" with same values
        PlayerState sameState = player.withPosition(50.0, 50.0);
        Delta delta = room.updatePlayerState("player-1", sameState);

        assertFalse(delta.hasChanges(), "No changes should be detected");

        System.out.println("✓ No delta created for unchanged state (bandwidth saved)");
    }

    // ==========================================
    // Test: Bandwidth Estimation
    // ==========================================

    @Test
    @DisplayName("Should estimate bandwidth for game scenario")
    void testBandwidthEstimation() {
        // Typical game scenario: 10 players, 30 updates/second each
        int playerCount = 10;
        int updatesPerSecond = 30;

        // Typical delta message size (just x, y changes)
        ObjectNode deltaPayload = serializer.createObjectNode();
        ObjectNode changes = serializer.createObjectNode();
        changes.put("x", 123.456);
        changes.put("y", 789.012);
        deltaPayload.set("player-1", changes);

        Message deltaMessage = Message.builder()
                .type(MessageType.DELTA_UPDATE)
                .roomId("test")
                .payload(deltaPayload)
                .version(1L)
                .build();

        int messageSize = serializer.estimateSize(deltaMessage);

        // Each player sends updates to (playerCount - 1) other players
        int messagesPerSecond = playerCount * updatesPerSecond * (playerCount - 1);
        int bytesPerSecond = messagesPerSecond * messageSize;
        double kbPerSecond = bytesPerSecond / 1024.0;
        double mbPerMinute = kbPerSecond * 60 / 1024.0;

        System.out.println("✓ Bandwidth estimation for " + playerCount + " players @ " + updatesPerSecond + " updates/sec:");
        System.out.println("  Message size: " + messageSize + " bytes");
        System.out.println("  Messages/second: " + messagesPerSecond);
        System.out.println("  Bandwidth: " + String.format("%.2f", kbPerSecond) + " KB/s");
        System.out.println("  Bandwidth: " + String.format("%.2f", mbPerMinute) + " MB/min");

        // Assert reasonable bandwidth (should be under 1 MB/s for 10 players)
        assertTrue(kbPerSecond < 1024, "Bandwidth should be reasonable for 10 players");
    }

    // ==========================================
    // Test: Message Size Optimization
    // ==========================================

    @Test
    @DisplayName("Should keep message sizes compact")
    void testMessageSizeCompactness() {
        // Full state with 10 players
        ObjectNode fullPayload = serializer.createObjectNode();
        ObjectNode players = serializer.createObjectNode();

        for (int i = 0; i < 10; i++) {
            ObjectNode player = serializer.createObjectNode();
            player.put("playerId", "player-" + i);
            player.put("playerName", "Player" + i);
            player.put("color", "#FF0000");
            player.put("x", Math.random() * 800);
            player.put("y", Math.random() * 600);
            players.set("player-" + i, player);
        }
        fullPayload.set("players", players);

        Message fullState = Message.builder()
                .type(MessageType.FULL_STATE)
                .roomId("size-test")
                .payload(fullPayload)
                .version(1L)
                .build();

        int fullStateSize = serializer.estimateSize(fullState);

        // Full state for 10 players should be under 2KB
        assertTrue(fullStateSize < 2048, "Full state for 10 players should be under 2KB, was: " + fullStateSize);

        System.out.println("✓ Message size optimization:");
        System.out.println("  Full state (10 players): " + fullStateSize + " bytes");
        System.out.println("  Average per player: " + (fullStateSize / 10) + " bytes");
    }
}
