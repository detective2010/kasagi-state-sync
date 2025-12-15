# Testing Plan for Kasagi State Sync Service

This document outlines the strategy for testing the scalability, reliability, and correctness of the State Synchronization Service.

---

## 1. Testing Levels Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      TESTING PYRAMID                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│                         /\                                      │
│                        /  \    E2E Tests                        │
│                       /    \   (Few, Slow)                      │
│                      /──────\                                   │
│                     /        \  Integration Tests               │
│                    /          \ (Some, Medium)                  │
│                   /────────────\                                │
│                  /              \ Unit Tests                    │
│                 /________________\ (Many, Fast)                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Unit Tests

### 2.1 State Management Tests

```java
// Example test cases for GameRoom.java
class GameRoomTest {

    @Test
    void shouldAddPlayerToRoom() {
        GameRoom room = new GameRoom("test-room");
        PlayerState player = PlayerState.builder()
            .playerId("player-1")
            .playerName("TestPlayer")
            .x(100).y(200)
            .build();

        long version = room.addPlayer("session-1", player);

        assertEquals(1, room.getPlayerCount());
        assertEquals(1, version);
        assertNotNull(room.getPlayer("player-1"));
    }

    @Test
    void shouldCalculateDeltaOnPositionChange() {
        GameRoom room = new GameRoom("test-room");
        PlayerState initial = PlayerState.builder()
            .playerId("player-1")
            .playerName("TestPlayer")
            .x(100).y(200)
            .build();
        room.addPlayer("session-1", initial);

        PlayerState updated = initial.withPosition(150, 200);
        Delta delta = room.updatePlayerState("player-1", updated);

        assertTrue(delta.hasChanges());
        assertEquals(150.0, delta.getChanges().get("x"));
        assertFalse(delta.getChanges().containsKey("y")); // Y didn't change
    }

    @Test
    void shouldIncrementVersionOnEachUpdate() {
        GameRoom room = new GameRoom("test-room");
        PlayerState player = PlayerState.builder()
            .playerId("player-1").build();

        room.addPlayer("session-1", player);  // version 1
        room.updatePlayerState("player-1", player.withPosition(10, 10));  // version 2
        room.updatePlayerState("player-1", player.withPosition(20, 20));  // version 3

        assertEquals(3, room.getVersion());
    }
}
```

### 2.2 Session Management Tests

```java
class SessionManagerTest {

    @Test
    void shouldCreateAndRetrieveSession() {
        SessionManager manager = new SessionManager();
        Channel mockChannel = createMockChannel();

        ClientSession session = manager.createSession(mockChannel);

        assertNotNull(session);
        assertNotNull(session.getSessionId());
        assertEquals(session, manager.getSessionByChannel(mockChannel));
        assertEquals(session, manager.getSessionById(session.getSessionId()));
    }

    @Test
    void shouldRemoveSessionOnDisconnect() {
        SessionManager manager = new SessionManager();
        Channel mockChannel = createMockChannel();

        ClientSession session = manager.createSession(mockChannel);
        ClientSession removed = manager.removeSession(mockChannel);

        assertEquals(session, removed);
        assertNull(manager.getSessionByChannel(mockChannel));
        assertEquals(0, manager.getSessionCount());
    }
}
```

### 2.3 Serialization Tests

```java
class MessageSerializerTest {

    @Test
    void shouldSerializeAndDeserializeMessage() {
        MessageSerializer serializer = new MessageSerializer();
        Message original = Message.builder()
            .type(MessageType.STATE_UPDATE)
            .roomId("room-1")
            .playerId("player-1")
            .version(42L)
            .build();

        String json = serializer.serialize(original);
        Message deserialized = serializer.deserialize(json);

        assertEquals(original.getType(), deserialized.getType());
        assertEquals(original.getRoomId(), deserialized.getRoomId());
        assertEquals(original.getPlayerId(), deserialized.getPlayerId());
        assertEquals(original.getVersion(), deserialized.getVersion());
    }
}
```

---

## 3. Integration Tests

### 3.1 WebSocket Connection Tests

```java
class WebSocketIntegrationTest {

    private SyncServer server;
    private int port = 8081;

    @BeforeEach
    void setUp() throws Exception {
        server = new SyncServer(port);
        // Start server in background thread
        new Thread(() -> {
            try { server.start(); }
            catch (Exception e) { /* handle */ }
        }).start();
        Thread.sleep(1000); // Wait for server to start
    }

    @AfterEach
    void tearDown() {
        server.shutdown();
    }

    @Test
    void shouldAcceptWebSocketConnection() {
        WebSocketClient client = new WebSocketClient("ws://localhost:" + port + "/sync");

        assertTrue(client.connect(5, TimeUnit.SECONDS));
        assertTrue(client.isConnected());

        client.close();
    }

    @Test
    void shouldJoinRoomAndReceiveFullState() {
        WebSocketClient client = new WebSocketClient("ws://localhost:" + port + "/sync");
        client.connect(5, TimeUnit.SECONDS);

        client.send("""
            {
                "type": "JOIN_ROOM",
                "roomId": "test-room",
                "payload": {"playerName": "Tester"}
            }
        """);

        String response = client.receiveMessage(5, TimeUnit.SECONDS);
        Message message = new MessageSerializer().deserialize(response);

        assertEquals(MessageType.FULL_STATE, message.getType());
        assertNotNull(message.getPayload().get("players"));
    }
}
```

### 3.2 Multi-Client Synchronization Tests

```java
@Test
void shouldBroadcastPlayerJoinedToOtherClients() {
    WebSocketClient client1 = connectAndJoinRoom("player1", "test-room");
    WebSocketClient client2 = new WebSocketClient(serverUrl);
    client2.connect(5, TimeUnit.SECONDS);

    // Client 2 joins
    client2.send("""
        {"type": "JOIN_ROOM", "roomId": "test-room", "payload": {"playerName": "player2"}}
    """);

    // Client 1 should receive PLAYER_JOINED
    String notification = client1.receiveMessage(5, TimeUnit.SECONDS);
    Message message = parseMessage(notification);

    assertEquals(MessageType.PLAYER_JOINED, message.getType());
    assertEquals("player2", message.getPayload().get("playerName").asText());
}

@Test
void shouldBroadcastDeltaUpdatesToOtherClients() {
    WebSocketClient client1 = connectAndJoinRoom("player1", "test-room");
    WebSocketClient client2 = connectAndJoinRoom("player2", "test-room");

    // Client 1 moves
    client1.send("""
        {"type": "STATE_UPDATE", "roomId": "test-room", "payload": {"x": 500, "y": 300}}
    """);

    // Client 2 should receive delta update
    String update = client2.receiveMessage(5, TimeUnit.SECONDS);
    Message message = parseMessage(update);

    assertEquals(MessageType.DELTA_UPDATE, message.getType());
    assertTrue(message.getPayload().get("players").has(client1PlayerId));
}
```

---

## 4. Load Testing

### 4.1 Tools

| Tool | Purpose |
|------|---------|
| **Gatling** | WebSocket load testing with Scala DSL |
| **k6** | Modern load testing with JavaScript |
| **Artillery** | YAML-based load testing |
| **Custom Java client** | Precise control over test scenarios |

### 4.2 Load Test Scenarios

#### Scenario 1: Connection Capacity
```
Goal: Find maximum concurrent connections

Test:
- Ramp up connections: 100 → 1000 → 5000 → 10000
- Each client joins a room but doesn't send updates
- Measure: Memory usage, CPU, connection success rate

Expected:
- 10,000 connections < 4GB RAM
- Connection success rate > 99.9%
```

#### Scenario 2: Message Throughput
```
Goal: Find maximum messages per second

Test:
- 1000 concurrent clients in same room
- Each client sends position update every 16ms (60fps)
- Total: 60,000 messages/sec into server, 60M messages/sec out (broadcast)

Measure:
- Message latency (p50, p95, p99)
- CPU usage
- Dropped messages

Expected:
- p50 latency < 10ms
- p99 latency < 50ms
- Zero dropped messages
```

#### Scenario 3: Room Isolation
```
Goal: Verify rooms don't affect each other

Test:
- 100 rooms, 100 players each (10,000 total)
- Each room has high activity
- Measure cross-room latency impact

Expected:
- Room A's activity shouldn't increase Room B's latency
```

### 4.3 Load Test Script Example (k6)

```javascript
// load-test.js
import ws from 'k6/ws';
import { check } from 'k6';

export const options = {
    stages: [
        { duration: '30s', target: 100 },   // Ramp to 100 users
        { duration: '1m', target: 1000 },   // Ramp to 1000 users
        { duration: '2m', target: 1000 },   // Stay at 1000
        { duration: '30s', target: 0 },     // Ramp down
    ],
};

export default function () {
    const url = 'ws://localhost:8080/sync';
    const roomId = `room-${Math.floor(Math.random() * 10)}`;

    const res = ws.connect(url, {}, function (socket) {
        socket.on('open', () => {
            socket.send(JSON.stringify({
                type: 'JOIN_ROOM',
                roomId: roomId,
                payload: { playerName: `player-${__VU}` }
            }));
        });

        socket.on('message', (msg) => {
            const data = JSON.parse(msg);
            check(data, {
                'received valid message': (d) => d.type !== undefined,
            });
        });

        // Send position updates every 100ms
        socket.setInterval(() => {
            socket.send(JSON.stringify({
                type: 'STATE_UPDATE',
                roomId: roomId,
                payload: {
                    x: Math.random() * 800,
                    y: Math.random() * 600
                }
            }));
        }, 100);

        socket.setTimeout(() => {
            socket.close();
        }, 60000);
    });

    check(res, { 'Connected successfully': (r) => r && r.status === 101 });
}
```

### 4.4 Running Load Tests

```bash
# Install k6
# Windows: choco install k6
# Mac: brew install k6

# Run load test
k6 run load-test.js

# Run with HTML report
k6 run --out json=results.json load-test.js
```

---

## 5. Reliability Testing

### 5.1 Chaos Testing Scenarios

| Scenario | Test | Expected Behavior |
|----------|------|-------------------|
| Client disconnect | Kill client mid-session | Other clients notified, state cleaned up |
| Network partition | Block traffic for 30s | Client reconnects, state recovers |
| High latency | Add 500ms delay | System remains functional |
| Message flood | Client sends 1000 msg/sec | Rate limited, not crash |
| Malformed messages | Send invalid JSON | Error response, no crash |

### 5.2 Disconnect Recovery Test

```java
@Test
void shouldHandleClientDisconnectAndReconnect() {
    WebSocketClient client1 = connectAndJoinRoom("player1", "test-room");
    String originalSessionId = client1.getSessionId();

    // Simulate disconnect
    client1.forceClose();
    Thread.sleep(1000);

    // Reconnect within grace period
    WebSocketClient client1Reconnected = new WebSocketClient(serverUrl);
    client1Reconnected.connect(5, TimeUnit.SECONDS);
    client1Reconnected.send("""
        {"type": "JOIN_ROOM", "roomId": "test-room",
         "payload": {"playerName": "player1", "sessionToken": "%s"}}
    """.formatted(originalSessionId));

    // Should restore previous state
    String response = client1Reconnected.receiveMessage(5, TimeUnit.SECONDS);
    // Verify state is restored
}
```

### 5.3 Memory Leak Testing

```bash
# Run server with memory profiling
java -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/tmp/heap.hprof \
     -Xmx2g \
     -jar kasagi-sync-server.jar

# Connect/disconnect 10,000 clients repeatedly
# Check heap dump for leaked sessions/rooms
```

---

## 6. Performance Benchmarks

### 6.1 Metrics to Track

| Metric | Target | Critical |
|--------|--------|----------|
| Connection time | < 100ms | < 500ms |
| Message latency (p50) | < 10ms | < 50ms |
| Message latency (p99) | < 50ms | < 200ms |
| Memory per connection | < 5KB | < 20KB |
| CPU per 1000 msg/sec | < 5% | < 20% |

### 6.2 Benchmark Test

```java
@Test
void benchmarkMessageThroughput() {
    int numClients = 100;
    int messagesPerClient = 1000;

    List<WebSocketClient> clients = createAndConnectClients(numClients);

    long startTime = System.currentTimeMillis();

    // Send messages
    for (WebSocketClient client : clients) {
        for (int i = 0; i < messagesPerClient; i++) {
            client.send(createPositionUpdate());
        }
    }

    // Wait for all messages to be processed
    waitForAllMessages(clients, numClients * messagesPerClient);

    long duration = System.currentTimeMillis() - startTime;
    double throughput = (numClients * messagesPerClient * 1000.0) / duration;

    System.out.println("Throughput: " + throughput + " msg/sec");
    assertTrue(throughput > 10000, "Should handle at least 10K msg/sec");
}
```

---

## 7. Test Environment

### 7.1 Local Testing
```
Machine: Developer laptop
Specs: 16GB RAM, 8 cores
Connections: Up to 1,000
```

### 7.2 Staging Testing
```
Machine: Cloud VM (c5.2xlarge equivalent)
Specs: 16GB RAM, 8 vCPUs
Connections: Up to 10,000
```

### 7.3 Production Testing
```
Machine: Dedicated game server
Specs: 32GB RAM, 16 cores
Connections: 50,000+
Load balancer: For multi-server testing
```

---

## 8. Continuous Integration

### 8.1 CI Pipeline

```yaml
# .github/workflows/test.yml
name: Test Suite

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - run: mvn test

  integration-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - run: mvn verify -P integration-tests

  load-tests:
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v3
      - run: |
          docker run -d -p 8080:8080 kasagi-sync-server
          k6 run load-test.js
```

---

## 9. Test Checklist

### Pre-Release Testing

- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Load test: 1000 connections sustained for 10 minutes
- [ ] Load test: p99 latency < 100ms under load
- [ ] Chaos test: Random disconnects handled correctly
- [ ] Memory test: No leaks after 1 hour of operation
- [ ] Security test: Invalid messages don't crash server

### Production Monitoring

- [ ] Connection count dashboard
- [ ] Message latency percentiles
- [ ] Error rate alerts
- [ ] Memory usage alerts
- [ ] CPU usage alerts

---

## 10. Summary

This testing plan covers:

1. **Unit Tests**: Verify individual components work correctly
2. **Integration Tests**: Verify components work together
3. **Load Tests**: Verify system handles expected load
4. **Reliability Tests**: Verify system handles failures gracefully
5. **Performance Benchmarks**: Establish baseline metrics

Run unit and integration tests on every commit. Run load tests before releases and periodically in production-like environments.
