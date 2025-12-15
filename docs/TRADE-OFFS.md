# Trade-offs and Design Decisions

This document explains the key decisions made in the Kasagi State Sync Service design and the trade-offs involved.

---

## 1. Programming Language: Java vs Node.js

### Decision: Java

| Criteria | Java | Node.js |
|----------|------|---------|
| Threading | True multi-threading | Single-threaded event loop |
| CPU-bound tasks | Parallel execution | Blocks event loop |
| Concurrency model | Thread pools, locks | Callbacks, promises |
| Memory management | GC with tuning options | V8 GC, less control |
| Ecosystem for games | Mature (Minecraft, etc.) | Growing |

### Why Java?
- **True parallelism**: State updates can process on multiple cores simultaneously
- **Blocking operations**: Heavy calculations don't affect other clients
- **Memory control**: Better GC tuning for consistent latency
- **Industry standard**: Most game servers use Java/C++/C#

### Trade-off Accepted
- More verbose code than Node.js
- Longer compilation cycle
- Higher memory footprint per instance

---

## 2. Networking Framework: Netty vs Spring WebSocket

### Decision: Netty

| Criteria | Netty | Spring WebSocket |
|----------|-------|------------------|
| Performance | ~500K+ connections | ~50K connections |
| Memory per connection | ~2KB | ~20KB |
| Control | Full low-level control | High-level abstraction |
| Complexity | More code | Less code |
| Learning curve | Steep | Gentle |

### Why Netty?
- **Performance**: Handles 10x more connections per server
- **Control**: Fine-grained control over threading and buffers
- **Production proven**: Used by Minecraft, Twitter, Apple
- **Non-blocking I/O**: Essential for real-time applications

### Trade-off Accepted
- More boilerplate code
- Steeper learning curve
- No automatic dependency injection

### When Spring Would Be Better
- If integrating with existing Spring ecosystem
- If developer familiarity is limited
- For prototypes where performance isn't critical

---

## 3. Communication Protocol: WebSockets vs WebRTC vs HTTP Polling

### Decision: WebSockets

| Criteria | WebSockets | WebRTC | HTTP Polling |
|----------|------------|--------|--------------|
| Latency | Low (~10-50ms) | Very Low (~1-10ms) | High (~100-500ms) |
| Complexity | Medium | High | Low |
| Server control | Full | Limited (P2P) | Full |
| NAT traversal | Easy | Complex (STUN/TURN) | Easy |
| Browser support | Universal | Good | Universal |

### Why WebSockets?
- **Balance**: Good latency without P2P complexity
- **Server authority**: All state goes through server (anti-cheat)
- **Simplicity**: Works everywhere, no STUN/TURN servers needed
- **Bidirectional**: Full-duplex communication

### Trade-off Accepted
- Higher latency than WebRTC (acceptable for most games)
- All traffic through server (more bandwidth cost)

### When WebRTC Would Be Better
- Ultra-low latency requirements (fighting games, FPS)
- Peer-to-peer voice/video alongside game data
- When server bandwidth cost is a concern

---

## 4. Serialization: JSON vs Protocol Buffers vs MessagePack

### Decision: JSON (with discussion of alternatives)

| Criteria | JSON | Protocol Buffers | MessagePack |
|----------|------|------------------|-------------|
| Size | Large (100%) | Small (~30%) | Small (~50%) |
| Parse speed | Slow | Fast (10x) | Medium (3x) |
| Human readable | Yes | No | No |
| Schema | No | Required (.proto) | No |
| Debugging | Easy | Hard | Medium |

### Why JSON (for prototype)?
- **Debugging**: Easy to read in browser dev tools
- **Simplicity**: No schema compilation step
- **Compatibility**: Works everywhere out of the box

### Production Recommendation: Protocol Buffers
```
// Example bandwidth savings for 100 players @ 60 updates/sec
JSON:     ~100 bytes/update × 100 × 60 = 600 KB/sec
Protobuf: ~30 bytes/update × 100 × 60 = 180 KB/sec
Savings: 420 KB/sec per server = 1.5 GB/hour
```

### Trade-off Accepted
- Higher bandwidth usage in prototype
- Slower parsing (acceptable at small scale)

---

## 5. State Management: Full Sync vs Delta Updates

### Decision: Delta Updates with Full Sync Fallback

```
┌─────────────────────────────────────────────────────────────┐
│                    UPDATE STRATEGIES                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  FULL SYNC:                                                 │
│  - Send complete state every update                         │
│  - Simple implementation                                    │
│  - High bandwidth: 1KB × 60fps × 100 players = 6 MB/sec    │
│                                                             │
│  DELTA SYNC (our choice):                                   │
│  - Send only what changed                                   │
│  - More complex implementation                              │
│  - Low bandwidth: 50B × 60fps × 100 players = 300 KB/sec   │
│                                                             │
│  FULL SYNC FALLBACK:                                        │
│  - Used when client misses updates (version mismatch)       │
│  - Used when joining room                                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Why Delta Updates?
- **20x bandwidth reduction** in typical scenarios
- **Lower latency**: Smaller payloads transmit faster
- **Scalability**: Support more concurrent players

### Trade-off Accepted
- More complex state management
- Need version tracking for consistency
- Occasional full sync when deltas are lost

---

## 6. Consistency Model: Server Authority vs Eventual Consistency

### Decision: Server as Source of Truth

| Model | Pros | Cons |
|-------|------|------|
| Server Authority | Consistent, anti-cheat | Higher latency feel |
| Client Prediction | Responsive | Complex reconciliation |
| Eventual Consistency | Low latency | Conflicts, cheating |

### Why Server Authority?
- **Consistency**: All clients see the same state
- **Anti-cheat**: Server validates all actions
- **Simplicity**: No conflict resolution needed

### Implementation
```
Client Input → Server Validates → Server Updates State → Broadcast to All
                    ↓
            (Reject if invalid)
```

### Trade-off Accepted
- Input feels slightly delayed (server round-trip)
- Server is a bottleneck

### Optimization for Production: Client-Side Prediction
```
1. Client sends input AND applies locally (optimistic)
2. Server validates and broadcasts authoritative state
3. Client reconciles if server state differs

This gives responsive feel while maintaining server authority.
```

---

## 7. Scalability: Single Server vs Distributed

### Decision: Single Server (with scaling design documented)

### Current Implementation
- One server handles all rooms
- Good for ~10,000 concurrent connections
- Simple deployment

### Scaling Path (documented but not implemented)

```
                    ┌─────────────────┐
                    │  Load Balancer  │
                    └────────┬────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│  Server 1   │      │  Server 2   │      │  Server 3   │
│ Rooms A-H   │      │ Rooms I-P   │      │ Rooms Q-Z   │
└──────┬──────┘      └──────┬──────┘      └──────┬──────┘
       │                    │                    │
       └────────────────────┼────────────────────┘
                            ▼
                   ┌─────────────────┐
                   │   Redis Pub/Sub │
                   └─────────────────┘
```

### Sharding Strategy Options
1. **Room-based sharding**: Hash roomId to determine server
2. **Geographic sharding**: Route players to nearest region
3. **Dynamic sharding**: Move hot rooms to dedicated servers

### Trade-off Accepted
- Single point of failure in prototype
- Limited to one server's capacity

---

## 8. Fault Tolerance: Immediate Cleanup vs Grace Period

### Decision: Grace Period for Reconnection

```
┌────────────────────────────────────────────────────────────┐
│                DISCONNECTION STRATEGIES                    │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  IMMEDIATE CLEANUP:                                        │
│  + Simple implementation                                   │
│  + Resources freed immediately                             │
│  - Bad UX on temporary disconnects                         │
│  - Player loses progress                                   │
│                                                            │
│  GRACE PERIOD (our choice):                                │
│  + Player can reconnect and resume                         │
│  + Good UX for unstable connections                        │
│  - Resources held during grace period                      │
│  - More complex session management                         │
│                                                            │
│  Implementation:                                           │
│  - 60 second grace period                                  │
│  - Session token for reconnection                          │
│  - State preserved until timeout                           │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### Why Grace Period?
- Mobile users often have unstable connections
- Better player experience
- Industry standard practice

### Trade-off Accepted
- Memory held for disconnected players
- Need session token management
- Delayed cleanup of abandoned sessions

---

## 9. Threading: Synchronized Blocks vs Lock-Free

### Decision: Hybrid Approach

```java
// Lock-free for simple operations
ConcurrentHashMap<String, PlayerState> players;
AtomicLong version;

// Synchronized for compound operations
public synchronized Delta updatePlayerState(...) {
    // Read old state
    // Calculate delta
    // Write new state
    // Must be atomic
}
```

### Why Hybrid?
- **Lock-free reads**: Most operations are reads (fast)
- **Synchronized writes**: Updates need consistency (rare)
- **Practical**: Easier to reason about than fully lock-free

### Trade-off Accepted
- Some contention on writes
- Not maximum theoretical performance

### Lock-Free Alternative (more complex)
```java
// Compare-and-swap loop
do {
    oldState = players.get(id);
    newState = calculateNewState(oldState, input);
} while (!players.replace(id, oldState, newState));
```

---

## 10. Summary of Key Trade-offs

| Decision | Chose | Over | Reason |
|----------|-------|------|--------|
| Language | Java | Node.js | True multi-threading |
| Framework | Netty | Spring | Performance |
| Protocol | WebSocket | WebRTC | Simplicity + server control |
| Serialization | JSON | Protobuf | Debug-ability (prototype) |
| Updates | Delta | Full sync | Bandwidth efficiency |
| Consistency | Server authority | Client prediction | Simplicity + anti-cheat |
| Scale | Single server | Distributed | Scope (prototype) |
| Disconnect | Grace period | Immediate | User experience |
| Threading | Hybrid locks | Full lock-free | Maintainability |

---

## Production Recommendations

If taking this prototype to production, prioritize these changes:

1. **Switch to Protocol Buffers** - 70% bandwidth reduction
2. **Add client-side prediction** - Better responsiveness
3. **Implement Redis Pub/Sub** - Enable horizontal scaling
4. **Add monitoring** - Prometheus metrics, latency tracking
5. **Implement rate limiting** - Prevent abuse
6. **Add authentication** - JWT tokens for security
