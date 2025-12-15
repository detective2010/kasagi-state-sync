# Kasagi State Sync Service

Real-time state synchronization service for KasagiEngine multiplayer games and collaborative applications.

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.6+

### Build & Run

```bash
# Navigate to server directory
cd sync-server

# Build the project
mvn clean package

# Run the server
java -jar target/kasagi-sync-server-1.0.0-SNAPSHOT.jar
```

Server starts on `ws://localhost:8080/sync`

### Test with Demo Client

1. Open `demo-client/index.html` in a browser
2. Click **Connect**
3. Open multiple browser tabs to see real-time sync
4. Click anywhere on the canvas to move your player

## Project Structure

```
kasagi-state-sync/
├── docs/
│   ├── ARCHITECTURE.md      # System design and diagrams
│   ├── TRADE-OFFS.md        # Design decisions explained
│   └── TESTING-PLAN.md      # Testing strategy
├── sync-server/             # Java backend
│   ├── pom.xml
│   └── src/main/java/com/kasagi/
│       ├── Main.java
│       ├── server/          # Netty WebSocket server
│       ├── handler/         # Message processing
│       ├── state/           # Game state management
│       ├── session/         # Client sessions
│       └── protocol/        # Message types
└── demo-client/
    └── index.html           # Browser test client
```

## Key Features

- **WebSocket-based** real-time communication
- **Delta compression** - only send what changed
- **Thread-safe** state management
- **Room-based** isolation for multiple game sessions
- **Graceful** disconnect handling

## Architecture Highlights

- **Netty** for high-performance networking
- **ConcurrentHashMap** for thread-safe state
- **Immutable objects** for safe sharing across threads
- **Server authority** model for consistency

## Documentation

- [Architecture Document](docs/ARCHITECTURE.md) - Detailed system design
- [Trade-offs Document](docs/TRADE-OFFS.md) - Design decisions
- [Testing Plan](docs/TESTING-PLAN.md) - How to test scalability

## API

### Message Types

| Type | Direction | Description |
|------|-----------|-------------|
| `JOIN_ROOM` | Client → Server | Join a room |
| `LEAVE_ROOM` | Client → Server | Leave current room |
| `STATE_UPDATE` | Client → Server | Send position update |
| `FULL_STATE` | Server → Client | Complete state snapshot |
| `DELTA_UPDATE` | Server → Client | Incremental changes |
| `PLAYER_JOINED` | Server → Client | New player notification |
| `PLAYER_LEFT` | Server → Client | Player left notification |

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

**Update Position:**
```json
{
    "type": "STATE_UPDATE",
    "roomId": "game-lobby-1",
    "payload": {
        "x": 150,
        "y": 200
    }
}
```
