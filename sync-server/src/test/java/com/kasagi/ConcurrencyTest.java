package com.kasagi;

import com.kasagi.state.Delta;
import com.kasagi.state.GameRoom;
import com.kasagi.state.PlayerState;
import com.kasagi.state.RoomManager;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Concurrency & Event Loop Requirements:
 * - Event loop understanding
 * - Non-blocking operations
 * - Thread safety
 * - Deadlock prevention
 */
@DisplayName("Concurrency & Event Loop Tests")
class ConcurrencyTest {

    private RoomManager roomManager;

    @BeforeEach
    void setUp() {
        roomManager = new RoomManager();
    }

    // ==========================================
    // Test: Non-Blocking Operations
    // ==========================================

    @Test
    @DisplayName("State updates should be non-blocking")
    void testNonBlockingStateUpdates() {
        GameRoom room = roomManager.getOrCreateRoom("non-blocking-test");

        // Add a player
        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test")
                .color("#FF0000")
                .x(0)
                .y(0)
                .build();
        room.addPlayer("session-1", player);

        // Measure time for single update
        int iterations = 10000;
        long[] times = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            PlayerState newState = player.withPosition(i, i);
            room.updatePlayerState("player-1", newState);
            times[i] = System.nanoTime() - start;
        }

        // Calculate statistics
        Arrays.sort(times);
        long median = times[iterations / 2];
        long p99 = times[(int) (iterations * 0.99)];
        long max = times[iterations - 1];

        System.out.println("✓ Non-blocking state update performance:");
        System.out.println("  Median: " + (median / 1000.0) + "μs");
        System.out.println("  P99: " + (p99 / 1000.0) + "μs");
        System.out.println("  Max: " + (max / 1000.0) + "μs");

        // P99 should be under 1ms for non-blocking operations
        assertTrue(p99 < 1_000_000, "P99 latency should be under 1ms, was: " + (p99 / 1000.0) + "μs");
    }

    @Test
    @DisplayName("Reads should not block writes and vice versa")
    void testReadWriteConcurrency() throws Exception {
        GameRoom room = roomManager.getOrCreateRoom("rw-concurrent");

        // Add initial player
        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test")
                .color("#00FF00")
                .x(0)
                .y(0)
                .build();
        room.addPlayer("session-1", player);

        int threadCount = 10;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger reads = new AtomicInteger(0);
        AtomicInteger writes = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        // Half threads read, half write
        for (int t = 0; t < threadCount; t++) {
            final boolean isReader = t % 2 == 0;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    for (int i = 0; i < operationsPerThread; i++) {
                        try {
                            if (isReader) {
                                PlayerState state = room.getPlayer("player-1");
                                if (state != null) {
                                    reads.incrementAndGet();
                                }
                            } else {
                                PlayerState current = room.getPlayer("player-1");
                                if (current != null) {
                                    room.updatePlayerState("player-1",
                                            current.withPosition(Math.random() * 800, Math.random() * 600));
                                    writes.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long startTime = System.nanoTime();
        startLatch.countDown(); // Start all threads simultaneously

        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All operations should complete");
        long endTime = System.nanoTime();

        executor.shutdown();

        long durationMs = (endTime - startTime) / 1_000_000;
        int totalOps = reads.get() + writes.get();

        System.out.println("✓ Read/Write concurrency test:");
        System.out.println("  Reads: " + reads.get());
        System.out.println("  Writes: " + writes.get());
        System.out.println("  Errors: " + errors.get());
        System.out.println("  Duration: " + durationMs + "ms");
        System.out.println("  Throughput: " + (totalOps * 1000 / durationMs) + " ops/sec");

        assertEquals(0, errors.get(), "No errors should occur");
    }

    // ==========================================
    // Test: Deadlock Prevention
    // ==========================================

    @Test
    @DisplayName("Should not deadlock with multiple rooms accessed simultaneously")
    void testNoDeadlockMultipleRooms() throws Exception {
        int roomCount = 10;
        int threadCount = 20;
        int operationsPerThread = 500;

        // Create rooms
        for (int i = 0; i < roomCount; i++) {
            GameRoom room = roomManager.getOrCreateRoom("deadlock-test-" + i);
            PlayerState player = PlayerState.builder()
                    .playerId("player-" + i)
                    .playerName("P" + i)
                    .color("#0000FF")
                    .x(0)
                    .y(0)
                    .build();
            room.addPlayer("session-" + i, player);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger completedOps = new AtomicInteger(0);
        Random random = new Random();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        // Access two random rooms (potential for deadlock if locking is wrong)
                        int room1Idx = random.nextInt(roomCount);
                        int room2Idx = random.nextInt(roomCount);

                        GameRoom room1 = roomManager.getRoom("deadlock-test-" + room1Idx);
                        GameRoom room2 = roomManager.getRoom("deadlock-test-" + room2Idx);

                        if (room1 != null && room2 != null) {
                            // Read from one, write to another
                            room1.getAllPlayers();
                            PlayerState player = room2.getPlayer("player-" + room2Idx);
                            if (player != null) {
                                room2.updatePlayerState("player-" + room2Idx,
                                        player.withPosition(random.nextDouble() * 800, random.nextDouble() * 600));
                            }
                            completedOps.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // If there's a deadlock, this will timeout
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All threads should complete (no deadlock)");
        assertTrue(completedOps.get() > 0, "Operations should have completed");

        System.out.println("✓ No deadlock detected with " + threadCount + " threads accessing " + roomCount + " rooms");
        System.out.println("  Completed operations: " + completedOps.get());
    }

    @Test
    @DisplayName("Should handle contention on single resource")
    void testHighContentionSingleResource() throws Exception {
        GameRoom room = roomManager.getOrCreateRoom("contention-test");

        // Single player that everyone wants to update
        PlayerState player = PlayerState.builder()
                .playerId("contested-player")
                .playerName("Contested")
                .color("#FFFFFF")
                .x(0)
                .y(0)
                .build();
        room.addPlayer("session-contested", player);

        int threadCount = 50;
        int updatesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successfulUpdates = new AtomicInteger(0);
        AtomicLong totalVersion = new AtomicLong(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < updatesPerThread; i++) {
                        PlayerState current = room.getPlayer("contested-player");
                        if (current != null) {
                            Delta delta = room.updatePlayerState("contested-player",
                                    current.withPosition(Math.random() * 800, Math.random() * 600));
                            if (delta != null) {
                                successfulUpdates.incrementAndGet();
                                totalVersion.set(Math.max(totalVersion.get(), room.getVersion()));
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long startTime = System.nanoTime();
        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Should complete under high contention");
        long endTime = System.nanoTime();

        executor.shutdown();

        long durationMs = (endTime - startTime) / 1_000_000;
        int expectedUpdates = threadCount * updatesPerThread;

        System.out.println("✓ High contention test:");
        System.out.println("  Threads: " + threadCount);
        System.out.println("  Updates per thread: " + updatesPerThread);
        System.out.println("  Successful updates: " + successfulUpdates.get());
        System.out.println("  Final version: " + totalVersion.get());
        System.out.println("  Duration: " + durationMs + "ms");
        System.out.println("  Throughput: " + (successfulUpdates.get() * 1000 / durationMs) + " updates/sec");

        // All updates should succeed (synchronized method ensures this)
        assertEquals(expectedUpdates, successfulUpdates.get(), "All updates should succeed");
    }

    // ==========================================
    // Test: Event Loop Simulation
    // ==========================================

    @Test
    @DisplayName("Should simulate event-driven message processing")
    void testEventDrivenProcessing() throws Exception {
        // Simulate Netty's event loop model
        int eventLoopThreads = 4; // Like Netty worker threads
        int totalMessages = 10000;

        ExecutorService eventLoops = Executors.newFixedThreadPool(eventLoopThreads);
        BlockingQueue<Runnable> messageQueue = new LinkedBlockingQueue<>();
        AtomicInteger processedMessages = new AtomicInteger(0);
        CountDownLatch allProcessed = new CountDownLatch(totalMessages);

        // Start "event loop" threads
        for (int i = 0; i < eventLoopThreads; i++) {
            eventLoops.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Runnable task = messageQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (task != null) {
                            task.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        GameRoom room = roomManager.getOrCreateRoom("event-loop-test");

        // Add player
        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test")
                .color("#FF00FF")
                .x(0)
                .y(0)
                .build();
        room.addPlayer("session-1", player);

        // Submit messages
        long startTime = System.nanoTime();

        for (int i = 0; i < totalMessages; i++) {
            final int msgId = i;
            messageQueue.offer(() -> {
                // Simulate message processing
                PlayerState current = room.getPlayer("player-1");
                if (current != null) {
                    room.updatePlayerState("player-1",
                            current.withPosition(msgId % 800, msgId % 600));
                    processedMessages.incrementAndGet();
                }
                allProcessed.countDown();
            });
        }

        assertTrue(allProcessed.await(30, TimeUnit.SECONDS), "All messages should be processed");
        long endTime = System.nanoTime();

        eventLoops.shutdownNow();

        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.println("✓ Event-driven processing simulation:");
        System.out.println("  Event loop threads: " + eventLoopThreads);
        System.out.println("  Total messages: " + totalMessages);
        System.out.println("  Processed: " + processedMessages.get());
        System.out.println("  Duration: " + durationMs + "ms");
        System.out.println("  Throughput: " + (processedMessages.get() * 1000 / durationMs) + " msg/sec");

        assertEquals(totalMessages, processedMessages.get(), "All messages should be processed");
    }

    // ==========================================
    // Test: Thread Safety of Collections
    // ==========================================

    @Test
    @DisplayName("ConcurrentHashMap should handle concurrent modifications")
    void testConcurrentHashMapBehavior() throws Exception {
        GameRoom room = roomManager.getOrCreateRoom("collection-test");

        int threadCount = 20;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger addedPlayers = new AtomicInteger(0);
        AtomicInteger removedPlayers = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        String playerId = "player-" + threadId + "-" + i;
                        String sessionId = "session-" + threadId + "-" + i;

                        // Add player
                        PlayerState player = PlayerState.builder()
                                .playerId(playerId)
                                .playerName("P" + i)
                                .color("#AAAAAA")
                                .x(0)
                                .y(0)
                                .build();
                        room.addPlayer(sessionId, player);
                        addedPlayers.incrementAndGet();

                        // Immediately iterate over all players (concurrent read)
                        room.getAllPlayers().forEach((id, state) -> {
                            // This should not throw ConcurrentModificationException
                            assertNotNull(state.getPlayerId());
                        });

                        // Remove player
                        room.removePlayer(sessionId, playerId);
                        removedPlayers.incrementAndGet();
                    }
                } catch (ConcurrentModificationException e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "All operations should complete");
        executor.shutdown();

        System.out.println("✓ ConcurrentHashMap test:");
        System.out.println("  Added players: " + addedPlayers.get());
        System.out.println("  Removed players: " + removedPlayers.get());
        System.out.println("  ConcurrentModificationExceptions: " + errors.get());

        assertEquals(0, errors.get(), "No ConcurrentModificationException should occur");
    }

    // ==========================================
    // Test: Atomicity
    // ==========================================

    @Test
    @DisplayName("Version increments should be atomic")
    void testAtomicVersionIncrement() throws Exception {
        GameRoom room = roomManager.getOrCreateRoom("atomic-test");

        PlayerState player = PlayerState.builder()
                .playerId("player-1")
                .playerName("Test")
                .color("#000000")
                .x(0)
                .y(0)
                .build();
        room.addPlayer("session-1", player);

        int threadCount = 50;
        int incrementsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        long versionBefore = room.getVersion();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < incrementsPerThread; i++) {
                        PlayerState current = room.getPlayer("player-1");
                        if (current != null) {
                            room.updatePlayerState("player-1",
                                    current.withPosition(Math.random() * 100, Math.random() * 100));
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All increments should complete");
        executor.shutdown();

        long versionAfter = room.getVersion();
        long expectedIncrements = (long) threadCount * incrementsPerThread;

        System.out.println("✓ Atomic version increment test:");
        System.out.println("  Version before: " + versionBefore);
        System.out.println("  Version after: " + versionAfter);
        System.out.println("  Expected increments: " + expectedIncrements);
        System.out.println("  Actual increments: " + (versionAfter - versionBefore));

        assertEquals(versionBefore + expectedIncrements, versionAfter,
                "Version should increment exactly once per update");
    }
}
