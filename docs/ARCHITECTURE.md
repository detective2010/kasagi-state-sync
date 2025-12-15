# KasagiEngine State Synchronization Service - Architecture Document

## 1. Overview

The **Kasagi State Sync Service** is a backend service responsible for synchronizing complex game state or application data in real-time across numerous connected clients. It serves as the backbone for KasagiEngine-powered multiplayer games and collaborative applications.

### Goals
- Real-time state synchronization with minimal latency
- Support for high volume of concurrent connections
- Efficient bandwidth usage through delta compression
- Horizontal scalability
- Fault tolerance and resilience

---

## 2. High-Level Architecture

```
                                    KASAGI STATE SYNC SERVICE
    ┌──────────────────────────────────────────────────────────────────────────────┐
    │                                                                              │
    │   ┌─────────────────────────────────────────────────────────────────────┐   │
    │   │                        LOAD BALANCER                                │   │
    │   │                   (Sticky Sessions by Room)                         │   │
    │   └─────────────────────────┬───────────────────────────────────────────┘   │
    │                             │                                                │
    │              ┌──────────────┼──────────────┐                                 │
    │              ▼              ▼              ▼                                 │
    │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                      │
    │   │   Server 1   │  │   Server 2   │  │   Server N   │   ← Horizontally     │
    │   │              │  │              │  │              │     Scalable         │
    │   └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                      │
    │          │                 │                 │                               │
    │          └─────────────────┼─────────────────┘                               │
    │                            ▼                                                 │
    │   ┌─────────────────────────────────────────────────────────────────────┐   │
    │   │                     REDIS PUB/SUB                                   │   │
    │   │              (Cross-server communication)                           │   │
    │   └─────────────────────────────────────────────────────────────────────┘   │
    │                                                                              │
    └──────────────────────────────────────────────────────────────────────────────┘

    CLIENTS (KasagiEngine Applications)
    ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
    │   Client 1   │  │   Client 2   │  │   Client 3   │  │   Client N   │
    │  (Player A)  │  │  (Player B)  │  │  (Player C)  │  │  (Player N)  │
    └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘
```

---

## 3. Single Server Architecture (Prototype Focus)

For the prototype, we focus on a single server implementation:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           SYNC SERVER (Java/Netty)                          │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                         NETWORK LAYER                                 │  │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐       │  │
│  │  │  Netty Boss     │  │  Netty Worker   │  │   WebSocket     │       │  │
│  │  │  EventLoop      │  │  EventLoops     │  │   Codec         │       │  │
│  │  │  (Accepts)      │  │  (I/O Threads)  │  │   (Encode/      │       │  │
│  │  │                 │  │                 │  │    Decode)      │       │  │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘       │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                       MESSAGE HANDLER LAYER                           │  │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐       │  │
│  │  │  Join Room      │  │  State Update   │  │  Leave Room     │       │  │
│  │  │  Handler        │  │  Handler        │  │  Handler        │       │  │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘       │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        SESSION LAYER                                  │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐ │  │
│  │  │                    SESSION MANAGER                              │ │  │
│  │  │  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐   │ │  │
│  │  │  │ Session 1 │  │ Session 2 │  │ Session 3 │  │ Session N │   │ │  │
│  │  │  │ (ClientA) │  │ (ClientB) │  │ (ClientC) │  │ (ClientN) │   │ │  │
│  │  │  └───────────┘  └───────────┘  └───────────┘  └───────────┘   │ │  │
│  │  └─────────────────────────────────────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                      │                                      │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                         STATE LAYER                                   │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐ │  │
│  │  │                     ROOM MANAGER                                │ │  │
│  │  │  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────┐   │ │  │
│  │  │  │     Room 1      │  │     Room 2      │  │    Room N     │   │ │  │
│  │  │  │  ┌───────────┐  │  │  ┌───────────┐  │  │               │   │ │  │
│  │  │  │  │GameState  │  │  │  │GameState  │  │  │     ...       │   │ │  │
│  │  │  │  │(Players,  │  │  │  │(Document, │  │  │               │   │ │  │
│  │  │  │  │ Entities) │  │  │  │ Cursors)  │  │  │               │   │ │  │
│  │  │  │  └───────────┘  │  │  └───────────┘  │  │               │   │ │  │
│  │  │  └─────────────────┘  └─────────────────┘  └───────────────┘   │ │  │
│  │  └─────────────────────────────────────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Component Details

### 4.1 Network Layer (Netty)

**Why Netty?**
- Non-blocking I/O (NIO) - handles thousands of connections with few threads
- Built-in WebSocket support
- Battle-tested in production (used by Minecraft, Twitter, etc.)

**Threading Model:**
```
┌─────────────────┐     ┌─────────────────────────────────────┐
│   Boss Group    │     │           Worker Group              │
│   (1 thread)    │     │         (N threads, N = CPU cores)  │
│                 │     │                                     │
│  Accepts new    │────▶│  Thread 1: Handles Clients 1,5,9... │
│  connections    │     │  Thread 2: Handles Clients 2,6,10.. │
│                 │     │  Thread 3: Handles Clients 3,7,11.. │
│                 │     │  Thread 4: Handles Clients 4,8,12.. │
└─────────────────┘     └─────────────────────────────────────┘
```

### 4.2 Session Manager

Tracks all connected clients using thread-safe data structures:

```java
ConcurrentHashMap<String, ClientSession> sessions;  // sessionId → session
ConcurrentHashMap<String, Set<String>> roomMembers; // roomId → set of sessionIds
```

### 4.3 Game State

Each room maintains its own isolated state:

```java
public class GameState {
    private final String roomId;
    private final ConcurrentHashMap<String, PlayerState> players;
    private final AtomicLong version;  // For conflict detection

    // Thread-safe update methods
    public synchronized Delta updatePlayer(String playerId, PlayerState newState);
}
```

### 4.4 Delta Compression

Instead of sending full state on every update:

```
FULL STATE (inefficient):
{
    "players": {
        "player1": {"x": 100, "y": 200, "health": 100, "score": 50},
        "player2": {"x": 300, "y": 400, "health": 80, "score": 30},
        "player3": {"x": 500, "y": 600, "health": 90, "score": 45}
    }
}

DELTA UPDATE (efficient):
{
    "type": "DELTA",
    "version": 142,
    "changes": {
        "player1": {"x": 101}  // Only the changed field!
    }
}
```

---

## 5. Message Protocol

### 5.1 Message Types

| Type | Direction | Purpose |
|------|-----------|---------|
| `JOIN_ROOM` | Client → Server | Join a room/session |
| `LEAVE_ROOM` | Client → Server | Leave current room |
| `STATE_UPDATE` | Client → Server | Send local state changes |
| `FULL_STATE` | Server → Client | Complete state snapshot |
| `DELTA_UPDATE` | Server → Client | Incremental state changes |
| `PLAYER_JOINED` | Server → Client | Notify of new player |
| `PLAYER_LEFT` | Server → Client | Notify of player departure |
| `ERROR` | Server → Client | Error notification |

### 5.2 Message Flow

```
CLIENT A                    SERVER                      CLIENT B
    │                          │                            │
    │──── JOIN_ROOM ──────────▶│                            │
    │◀─── FULL_STATE ─────────│                            │
    │                          │                            │
    │                          │◀──── JOIN_ROOM ───────────│
    │                          │───── FULL_STATE ─────────▶│
    │◀─── PLAYER_JOINED ──────│───── PLAYER_JOINED ──────▶│
    │                          │                            │
    │──── STATE_UPDATE ───────▶│                            │
    │                          │───── DELTA_UPDATE ───────▶│
    │                          │                            │
    │                          │◀──── STATE_UPDATE ────────│
    │◀─── DELTA_UPDATE ───────│                            │
    │                          │                            │
```

---

## 6. Concurrency Model

### 6.1 Thread Safety Strategy

```
┌────────────────────────────────────────────────────────────────────┐
│                    THREAD SAFETY APPROACH                          │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  1. IMMUTABLE OBJECTS                                              │
│     - PlayerState, Message objects are immutable                   │
│     - Safe to share across threads                                 │
│                                                                    │
│  2. CONCURRENT DATA STRUCTURES                                     │
│     - ConcurrentHashMap for sessions, rooms, players               │
│     - Lock-free reads, segmented writes                            │
│                                                                    │
│  3. ATOMIC OPERATIONS                                              │
│     - AtomicLong for version numbers                               │
│     - AtomicReference for state snapshots                          │
│                                                                    │
│  4. SYNCHRONIZED BLOCKS (only when necessary)                      │
│     - Delta calculation requires consistent read                   │
│     - Keep critical sections minimal                               │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### 6.2 Avoiding Blocking Operations

```java
// BAD - Blocks Netty's event loop
public void handleMessage(Message msg) {
    database.save(msg);  // Blocking I/O!
    Thread.sleep(100);   // Never do this!
}

// GOOD - Non-blocking approach
public void handleMessage(Message msg) {
    // Process in-memory (fast)
    gameState.update(msg);

    // Offload heavy work to separate thread pool
    asyncExecutor.submit(() -> database.saveAsync(msg));
}
```

---

## 7. Scalability Design

### 7.1 Horizontal Scaling Strategy

```
                         ┌─────────────────┐
                         │  Load Balancer  │
                         │ (Sticky by Room)│
                         └────────┬────────┘
                                  │
          ┌───────────────────────┼───────────────────────┐
          ▼                       ▼                       ▼
   ┌─────────────┐         ┌─────────────┐         ┌─────────────┐
   │  Server 1   │         │  Server 2   │         │  Server 3   │
   │             │         │             │         │             │
   │ Room A ───┐ │         │ Room C      │         │ Room E      │
   │ Room B    │ │         │ Room D      │         │ Room F      │
   └───────────┼─┘         └─────────────┘         └─────────────┘
               │                   │                       │
               └───────────────────┼───────────────────────┘
                                   ▼
                         ┌─────────────────┐
                         │   Redis Pub/Sub │
                         │                 │
                         │ (For cross-room │
                         │  communication) │
                         └─────────────────┘
```

### 7.2 Sharding Strategy

Rooms are naturally isolated units - perfect for sharding:

| Strategy | How It Works |
|----------|--------------|
| Room-based sharding | Each server handles specific rooms |
| Consistent hashing | `hash(roomId) % serverCount = targetServer` |
| Dynamic rebalancing | Move rooms between servers based on load |

---

## 8. Resilience & Fault Tolerance

### 8.1 Handling Disconnections

```
┌─────────────────────────────────────────────────────────────────┐
│                    DISCONNECTION HANDLING                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. DETECTION                                                   │
│     - WebSocket close event                                     │
│     - Heartbeat timeout (no ping response in 30s)               │
│                                                                 │
│  2. GRACE PERIOD                                                │
│     - Keep player state for 60 seconds                          │
│     - Allow reconnection with same session token                │
│                                                                 │
│  3. CLEANUP                                                     │
│     - After grace period, remove player from room               │
│     - Broadcast PLAYER_LEFT to remaining clients                │
│     - Clean up session resources                                │
│                                                                 │
│  4. RECONNECTION                                                │
│     - Client sends JOIN with previous sessionToken              │
│     - Server restores player state if within grace period       │
│     - Client receives FULL_STATE to resync                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 8.2 Data Consistency

**Server is the Source of Truth:**

```
Client A sends: "My position is (100, 200)"
                        │
                        ▼
              ┌─────────────────┐
              │     SERVER      │
              │                 │
              │ Validates:      │
              │ - Is move legal?│
              │ - Anti-cheat    │
              │                 │
              │ Authoritative   │
              │ state update    │
              └────────┬────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
      Client A     Client B     Client C
      (confirmed)  (sees A)     (sees A)
```

### 8.3 Conflict Resolution

When two clients update the same entity:

```
Time 0: State version = 100, Player X at (50, 50)

Time 1: Client A sends "Move X to (60, 50)" (based on v100)
Time 1: Client B sends "Move X to (50, 60)" (based on v100)

Server Resolution (Last-Write-Wins with timestamp):
- A's message arrives at T=1001ms → Apply, version = 101
- B's message arrives at T=1002ms → Apply, version = 102
- Final: Player X at (50, 60)

Both clients receive DELTA with final position.
```

---

## 9. Technology Stack

| Layer | Technology | Justification |
|-------|------------|---------------|
| Language | Java 17+ | True multi-threading, mature ecosystem |
| Networking | Netty 4.x | High-performance NIO, WebSocket support |
| Serialization | Jackson JSON | Simple for prototype; can swap to Protobuf |
| Concurrency | java.util.concurrent | ConcurrentHashMap, ExecutorService |
| Build | Maven | Standard, widely supported |
| Scaling (future) | Redis | Pub/Sub for cross-server messaging |

---

## 10. API Reference

### WebSocket Endpoint
```
ws://hostname:8080/sync
```

### Message Format (JSON)
```json
{
    "type": "MESSAGE_TYPE",
    "roomId": "room-123",
    "payload": { ... },
    "timestamp": 1234567890,
    "version": 42
}
```

### Example Messages

**Join Room:**
```json
{
    "type": "JOIN_ROOM",
    "roomId": "game-lobby-1",
    "payload": {
        "playerName": "Player1",
        "color": "#FF0000"
    }
}
```

**State Update:**
```json
{
    "type": "STATE_UPDATE",
    "roomId": "game-lobby-1",
    "payload": {
        "x": 150,
        "y": 200
    },
    "version": 41
}
```

**Delta Update (Server → Client):**
```json
{
    "type": "DELTA_UPDATE",
    "roomId": "game-lobby-1",
    "payload": {
        "players": {
            "player-uuid-123": {
                "x": 150,
                "y": 200
            }
        }
    },
    "version": 42
}
```

---

## 11. Summary

This architecture provides:

- **Real-time sync** via WebSockets with Netty's efficient NIO
- **Thread safety** through immutable objects and concurrent collections
- **Scalability path** via room-based sharding and Redis Pub/Sub
- **Resilience** through graceful disconnection handling and server authority
- **Efficiency** through delta compression and minimal bandwidth usage

The prototype implements the core single-server functionality, while the design supports future horizontal scaling.
