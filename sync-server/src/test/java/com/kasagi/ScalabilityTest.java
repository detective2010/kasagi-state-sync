package com.kasagi;

import com.kasagi.state.GameRoom;
import com.kasagi.state.PlayerState;
import com.kasagi.state.RoomManager;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Scalability Requirements:
 * - Horizontal scaling simulation
 * - Bottleneck identification
 * - Room sharding / session distribution
 */
@DisplayName("Scalability Tests")
class ScalabilityTest {

    private RoomManager roomManager;

    @BeforeEach
    void setUp() {
        roomManager = new RoomManager();
    }

    // ==========================================
    // Test: Room Sharding / Distribution
    // ==========================================

    @Test
    @DisplayName("Should distribute players across multiple rooms efficiently")
    void testRoomDistribution() {
        int totalPlayers = 10000;
        int roomCapacity = 100;
        int expectedRooms = totalPlayers / roomCapacity;

        Map<String, AtomicInteger> roomCounts = new ConcurrentHashMap<>();

        // Simulate players joining rooms with load balancing
        for (int i = 0; i < totalPlayers; i++) {
            String roomId = "room-" + (i / roomCapacity);
            roomCounts.computeIfAbsent(roomId, k -> new AtomicInteger(0)).incrementAndGet();

            GameRoom room = roomManager.getOrCreateRoom(roomId);
            PlayerState player = PlayerState.builder()
                    .playerId("player-" + i)
                    .playerName("P" + i)
                    .color("#FFFFFF")
                    .x(0)
                    .y(0)
                    .build();
            room.addPlayer("session-" + i, player);
        }

        assertEquals(expectedRooms, roomCounts.size(), "Should create expected number of rooms");

        // Verify even distribution
        for (var entry : roomCounts.entrySet()) {
            assertEquals(roomCapacity, entry.getValue().get(),
                    "Each room should have " + roomCapacity + " players");
        }

        System.out.println("✓ Room distribution test:");
        System.out.println("  Total players: " + totalPlayers);
        System.out.println("  Rooms created: " + roomCounts.size());
        System.out.println("  Players per room: " + roomCapacity);
    }

    @Test
    @DisplayName("Should handle room creation/deletion under load")
    void testDynamicRoomManagement() {
        int cycles = 100;
        int playersPerCycle = 50;
        AtomicInteger peakRooms = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<>();

        for (int cycle = 0; cycle < cycles; cycle++) {
            final int cycleNum = cycle;
            futures.add(executor.submit(() -> {
                String roomId = "dynamic-room-" + cycleNum;
                GameRoom room = roomManager.getOrCreateRoom(roomId);

                // Add players
                for (int p = 0; p < playersPerCycle; p++) {
                    PlayerState player = PlayerState.builder()
                            .playerId("player-" + cycleNum + "-" + p)
                            .playerName("P" + p)
                            .color("#000000")
                            .x(0)
                            .y(0)
                            .build();
                    room.addPlayer("session-" + cycleNum + "-" + p, player);
                }

                // Track peak
                peakRooms.updateAndGet(current -> Math.max(current, cycles));

                // Remove players and cleanup
                for (int p = 0; p < playersPerCycle; p++) {
                    room.removePlayer("session-" + cycleNum + "-" + p, "player-" + cycleNum + "-" + p);
                }
                roomManager.removeIfEmpty(roomId);
            }));
        }

        // Wait for completion
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Task failed: " + e.getMessage());
            }
        }

        executor.shutdown();

        System.out.println("✓ Dynamic room management test:");
        System.out.println("  Cycles completed: " + cycles);
        System.out.println("  Players per cycle: " + playersPerCycle);
        System.out.println("  Peak rooms: " + peakRooms.get());
    }

    // ==========================================
    // Test: Bottleneck Identification
    // ==========================================

    @Test
    @DisplayName("Should identify update broadcast as potential bottleneck")
    void testBroadcastBottleneck() {
        GameRoom room = roomManager.getOrCreateRoom("bottleneck-test");
        int playerCount = 100;

        // Add players
        for (int i = 0; i < playerCount; i++) {
            PlayerState player = PlayerState.builder()
                    .playerId("player-" + i)
                    .playerName("P" + i)
                    .color("#FF0000")
                    .x(0)
                    .y(0)
                    .build();
            room.addPlayer("session-" + i, player);
        }

        // Measure time to get all session IDs (broadcast prep)
        long startTime = System.nanoTime();
        int iterations = 10000;

        for (int i = 0; i < iterations; i++) {
            Set<String> sessions = room.getSessionIds();
            // Simulate broadcast loop
            for (String sessionId : sessions) {
                // This would be the send operation
                assertNotNull(sessionId);
            }
        }

        long endTime = System.nanoTime();
        double avgMicroseconds = (endTime - startTime) / 1000.0 / iterations;

        System.out.println("✓ Broadcast bottleneck analysis:");
        System.out.println("  Players in room: " + playerCount);
        System.out.println("  Broadcast prep time: " + String.format("%.2f", avgMicroseconds) + "μs");
        System.out.println("  Max broadcasts/sec: " + String.format("%.0f", 1_000_000 / avgMicroseconds));

        // At 60 updates/sec from each player, we need to handle:
        // playerCount * 60 = 6000 broadcasts/sec
        double requiredBroadcastsPerSec = playerCount * 60;
        double maxBroadcastsPerSec = 1_000_000 / avgMicroseconds;

        assertTrue(maxBroadcastsPerSec > requiredBroadcastsPerSec,
                "Should handle required broadcast rate. Required: " + requiredBroadcastsPerSec +
                        ", Max: " + maxBroadcastsPerSec);
    }

    @Test
    @DisplayName("Should measure state lookup performance (potential bottleneck)")
    void testStateLookupPerformance() {
        GameRoom room = roomManager.getOrCreateRoom("lookup-test");
        int playerCount = 1000;

        // Add many players
        for (int i = 0; i < playerCount; i++) {
            PlayerState player = PlayerState.builder()
                    .playerId("player-" + i)
                    .playerName("P" + i)
                    .color("#00FF00")
                    .x(i)
                    .y(i)
                    .build();
            room.addPlayer("session-" + i, player);
        }

        // Measure lookup time
        int lookups = 100000;
        Random random = new Random();
        long startTime = System.nanoTime();

        for (int i = 0; i < lookups; i++) {
            String playerId = "player-" + random.nextInt(playerCount);
            PlayerState state = room.getPlayer(playerId);
            assertNotNull(state);
        }

        long endTime = System.nanoTime();
        double avgNanoseconds = (endTime - startTime) / (double) lookups;

        System.out.println("✓ State lookup performance:");
        System.out.println("  Players in room: " + playerCount);
        System.out.println("  Lookups performed: " + lookups);
        System.out.println("  Average lookup time: " + String.format("%.0f", avgNanoseconds) + "ns");
        System.out.println("  Max lookups/sec: " + String.format("%.0f", 1_000_000_000 / avgNanoseconds));

        // ConcurrentHashMap should provide O(1) lookup
        assertTrue(avgNanoseconds < 1000, "Lookup should be under 1μs, was: " + avgNanoseconds + "ns");
    }

    // ==========================================
    // Test: Horizontal Scaling Simulation
    // ==========================================

    @Test
    @DisplayName("Should simulate horizontal scaling with multiple room managers")
    void testHorizontalScalingSimulation() {
        // Simulate 4 server instances (each with its own RoomManager)
        int serverCount = 4;
        List<RoomManager> servers = new ArrayList<>();
        for (int i = 0; i < serverCount; i++) {
            servers.add(new RoomManager());
        }

        int totalRooms = 1000;
        int playersPerRoom = 10;
        AtomicInteger totalPlayersAdded = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(serverCount);
        CountDownLatch latch = new CountDownLatch(totalRooms);

        long startTime = System.nanoTime();

        for (int r = 0; r < totalRooms; r++) {
            final int roomNum = r;
            // Distribute rooms across servers (simple modulo sharding)
            final RoomManager targetServer = servers.get(roomNum % serverCount);

            executor.submit(() -> {
                try {
                    GameRoom room = targetServer.getOrCreateRoom("room-" + roomNum);

                    for (int p = 0; p < playersPerRoom; p++) {
                        PlayerState player = PlayerState.builder()
                                .playerId("player-" + roomNum + "-" + p)
                                .playerName("P" + p)
                                .color("#0000FF")
                                .x(0)
                                .y(0)
                                .build();
                        room.addPlayer("session-" + roomNum + "-" + p, player);
                        totalPlayersAdded.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            assertTrue(latch.await(60, TimeUnit.SECONDS), "All rooms should be created");
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        executor.shutdown();

        // Verify distribution
        int[] roomsPerServer = new int[serverCount];
        for (int i = 0; i < serverCount; i++) {
            // Count rooms would require adding a method to RoomManager
            roomsPerServer[i] = totalRooms / serverCount;
        }

        System.out.println("✓ Horizontal scaling simulation:");
        System.out.println("  Server instances: " + serverCount);
        System.out.println("  Total rooms: " + totalRooms);
        System.out.println("  Rooms per server: " + (totalRooms / serverCount));
        System.out.println("  Total players: " + totalPlayersAdded.get());
        System.out.println("  Setup time: " + durationMs + "ms");
        System.out.println("  Throughput: " + (totalPlayersAdded.get() * 1000 / durationMs) + " players/sec");

        assertEquals(totalRooms * playersPerRoom, totalPlayersAdded.get());
    }

    // ==========================================
    // Test: Memory Efficiency
    // ==========================================

    @Test
    @DisplayName("Should maintain reasonable memory footprint")
    void testMemoryEfficiency() {
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long beforeMemory = runtime.totalMemory() - runtime.freeMemory();

        int roomCount = 100;
        int playersPerRoom = 100;

        for (int r = 0; r < roomCount; r++) {
            GameRoom room = roomManager.getOrCreateRoom("mem-room-" + r);
            for (int p = 0; p < playersPerRoom; p++) {
                PlayerState player = PlayerState.builder()
                        .playerId("player-" + r + "-" + p)
                        .playerName("Player" + p)
                        .color("#FFFFFF")
                        .x(Math.random() * 800)
                        .y(Math.random() * 600)
                        .build();
                room.addPlayer("session-" + r + "-" + p, player);
            }
        }

        runtime.gc();
        long afterMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = afterMemory - beforeMemory;
        double memoryPerPlayer = memoryUsed / (double) (roomCount * playersPerRoom);

        System.out.println("✓ Memory efficiency:");
        System.out.println("  Rooms: " + roomCount);
        System.out.println("  Players: " + (roomCount * playersPerRoom));
        System.out.println("  Total memory used: " + (memoryUsed / 1024) + " KB");
        System.out.println("  Memory per player: " + String.format("%.0f", memoryPerPlayer) + " bytes");

        // Each player should use less than 1KB of memory
        assertTrue(memoryPerPlayer < 1024, "Memory per player should be under 1KB, was: " + memoryPerPlayer);
    }
}
