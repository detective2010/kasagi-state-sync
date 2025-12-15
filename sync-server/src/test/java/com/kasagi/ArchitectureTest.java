package com.kasagi;

import com.kasagi.state.GameRoom;
import com.kasagi.state.PlayerState;
import com.kasagi.state.RoomManager;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Architecture Requirements:
 * - High volume of concurrent connections
 * - Frequent state updates
 * - Non-blocking operations
 */
@DisplayName("Architecture Tests")
class ArchitectureTest {

    private RoomManager roomManager;

    @BeforeEach
    void setUp() {
        roomManager = new RoomManager();
    }

    // ==========================================
    // Test: High Volume Concurrent Connections
    // ==========================================

    @Test
    @DisplayName("Should handle 1000 concurrent players in a single room")
    void testHighVolumePlayersInRoom() {
        GameRoom room = roomManager.getOrCreateRoom("stress-test-room");
        int playerCount = 1000;

        // Add 1000 players concurrently
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(playerCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < playerCount; i++) {
            final int playerId = i;
            executor.submit(() -> {
                try {
                    PlayerState player = PlayerState.builder()
                            .playerId("player-" + playerId)
                            .playerName("Player " + playerId)
                            .color("#FF0000")
                            .x(Math.random() * 800)
                            .y(Math.random() * 600)
                            .build();

                    room.addPlayer("session-" + playerId, player);
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "All players should be added within timeout");
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        executor.shutdown();
        assertEquals(playerCount, room.getPlayerCount(), "All players should be in the room");
        assertEquals(playerCount, successCount.get(), "All additions should succeed");

        System.out.println("✓ Successfully handled " + playerCount + " concurrent players in a room");
    }

    @Test
    @DisplayName("Should handle multiple rooms with players simultaneously")
    void testMultipleRoomsConcurrently() {
        int roomCount = 100;
        int playersPerRoom = 50;

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(roomCount * playersPerRoom);
        AtomicInteger totalPlayers = new AtomicInteger(0);

        for (int r = 0; r < roomCount; r++) {
            final int roomId = r;
            for (int p = 0; p < playersPerRoom; p++) {
                final int playerId = p;
                executor.submit(() -> {
                    try {
                        GameRoom room = roomManager.getOrCreateRoom("room-" + roomId);
                        PlayerState player = PlayerState.builder()
                                .playerId("player-" + roomId + "-" + playerId)
                                .playerName("Player " + playerId)
                                .color("#00FF00")
                                .x(100)
                                .y(100)
                                .build();

                        room.addPlayer("session-" + roomId + "-" + playerId, player);
                        totalPlayers.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "All operations should complete");
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        executor.shutdown();
        assertEquals(roomCount * playersPerRoom, totalPlayers.get());

        System.out.println("✓ Successfully managed " + roomCount + " rooms with " +
                playersPerRoom + " players each = " + totalPlayers.get() + " total players");
    }

    // ==========================================
    // Test: Frequent State Updates
    // ==========================================

    @Test
    @DisplayName("Should handle high frequency state updates (simulating 60 FPS)")
    void testHighFrequencyStateUpdates() {
        GameRoom room = roomManager.getOrCreateRoom("update-test");

        // Add initial player
        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test Player")
                .color("#0000FF")
                .x(0)
                .y(0)
                .build();
        room.addPlayer("session-1", player);

        // Simulate 60 updates per second for 1 second = 60 updates
        int updateCount = 60;
        long startTime = System.nanoTime();

        for (int i = 0; i < updateCount; i++) {
            PlayerState newState = player.withPosition(i * 10, i * 5);
            room.updatePlayerState("player-1", newState);
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        assertTrue(durationMs < 100, "60 updates should complete in under 100ms, took: " + durationMs + "ms");

        System.out.println("✓ Completed " + updateCount + " state updates in " + durationMs + "ms");
        System.out.println("  Average: " + (durationMs / (double) updateCount) + "ms per update");
    }

    @Test
    @DisplayName("Should handle concurrent state updates from multiple players")
    void testConcurrentStateUpdates() {
        GameRoom room = roomManager.getOrCreateRoom("concurrent-update-test");
        int playerCount = 100;
        int updatesPerPlayer = 100;

        // Add players
        for (int i = 0; i < playerCount; i++) {
            PlayerState player = PlayerState.builder()
                    .playerId("player-" + i)
                    .playerName("Player " + i)
                    .color("#FF00FF")
                    .x(0)
                    .y(0)
                    .build();
            room.addPlayer("session-" + i, player);
        }

        // Concurrent updates
        ExecutorService executor = Executors.newFixedThreadPool(playerCount);
        CountDownLatch latch = new CountDownLatch(playerCount * updatesPerPlayer);
        AtomicInteger updateSuccess = new AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int p = 0; p < playerCount; p++) {
            final int playerId = p;
            executor.submit(() -> {
                for (int u = 0; u < updatesPerPlayer; u++) {
                    try {
                        PlayerState currentState = room.getPlayer("player-" + playerId);
                        if (currentState != null) {
                            PlayerState newState = currentState.withPosition(
                                    Math.random() * 800,
                                    Math.random() * 600
                            );
                            room.updatePlayerState("player-" + playerId, newState);
                            updateSuccess.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "All updates should complete");
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        executor.shutdown();

        int totalUpdates = playerCount * updatesPerPlayer;
        assertEquals(totalUpdates, updateSuccess.get(), "All updates should succeed");

        System.out.println("✓ Completed " + totalUpdates + " concurrent updates in " + durationMs + "ms");
        System.out.println("  Throughput: " + (totalUpdates * 1000 / durationMs) + " updates/second");
    }

    // ==========================================
    // Test: Thread Safety
    // ==========================================

    @Test
    @DisplayName("Room state should remain consistent under concurrent access")
    void testRoomStateConsistency() {
        GameRoom room = roomManager.getOrCreateRoom("consistency-test");

        // Add a player
        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test")
                .color("#FFFFFF")
                .x(0)
                .y(0)
                .build();
        room.addPlayer("session-1", player);

        // Concurrent reads and writes
        int threadCount = 50;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final boolean isWriter = t % 2 == 0;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        if (isWriter) {
                            PlayerState current = room.getPlayer("player-1");
                            if (current != null) {
                                room.updatePlayerState("player-1",
                                        current.withPosition(Math.random() * 800, Math.random() * 600));
                            }
                        } else {
                            // Reader
                            PlayerState state = room.getPlayer("player-1");
                            if (state == null) {
                                errors.incrementAndGet();
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "All operations should complete");
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        executor.shutdown();
        assertEquals(0, errors.get(), "No read errors should occur during concurrent access");

        System.out.println("✓ Room state remained consistent across " +
                (threadCount * operationsPerThread) + " concurrent operations");
    }

    @Test
    @DisplayName("Version numbers should be monotonically increasing")
    void testVersionMonotonicity() {
        GameRoom room = roomManager.getOrCreateRoom("version-test");

        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test")
                .color("#000000")
                .x(0)
                .y(0)
                .build();

        long initialVersion = room.addPlayer("session-1", player);

        List<Long> versions = new ArrayList<>();
        versions.add(initialVersion);

        // Perform updates and track versions
        for (int i = 0; i < 100; i++) {
            PlayerState newState = player.withPosition(i, i);
            room.updatePlayerState("player-1", newState);
            versions.add(room.getVersion());
        }

        // Verify monotonically increasing
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i) > versions.get(i - 1),
                    "Version should be strictly increasing: " + versions.get(i - 1) + " -> " + versions.get(i));
        }

        System.out.println("✓ Version numbers are monotonically increasing (1 to " + room.getVersion() + ")");
    }
}
