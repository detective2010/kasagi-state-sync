# Kasagi State Sync Service

Real-time state synchronization service for KasagiEngine multiplayer games and collaborative applications.

## Prerequisites

### Required Software

| Software | Version | Download |
|----------|---------|----------|
| **Java JDK** | 17 or higher | [Eclipse Adoptium](https://adoptium.net/) or [Oracle JDK](https://www.oracle.com/java/technologies/downloads/) |
| **Git** | Any recent version | [git-scm.com](https://git-scm.com/downloads) |

> **Note:** Maven is NOT required - this project includes a Maven Wrapper (`mvnw`)

### Verify Java Installation

```bash
java -version
```

You should see output like: `openjdk version "17.0.x"` or higher.

If not installed, download and install Java JDK 17+ from the links above.

### Set JAVA_HOME (Windows)

1. Find your Java installation path (e.g., `C:\Program Files\Eclipse Adoptium\jdk-17.0.x`)
2. Open **System Properties** → **Environment Variables**
3. Add new System Variable:
   - Name: `JAVA_HOME`
   - Value: `C:\Program Files\Eclipse Adoptium\jdk-17.0.x` (your actual path)
4. Restart your terminal

---

## Getting Started

### Step 1: Clone the Repository

```bash
git clone https://github.com/detective2010/kasagi-state-sync.git
cd kasagi-state-sync
```

### Step 2: Build the Project

**Windows:**
```bash
cd sync-server
.\mvnw.cmd clean package
```

**Linux/Mac:**
```bash
cd sync-server
chmod +x mvnw
./mvnw clean package
```

> First build will download dependencies automatically (~2-3 minutes)

### Step 3: Run the Server

```bash
java -jar target/kasagi-sync-server-1.0.0-SNAPSHOT.jar
```

You should see:
```
===========================================
  Kasagi State Sync Service
  Starting on port 8080
===========================================
Server started successfully!
WebSocket endpoint: ws://localhost:8080/sync
```

### Step 4: Test with Demo Client

1. Open `demo-client/index.html` in a browser (just double-click it)
2. Enter your name and click **Connect**
3. Use **WASD keys** to move your player
4. Open multiple browser tabs to test multiplayer sync!

---

## Libraries & Dependencies

All dependencies are managed automatically by Maven. Here's what the project uses:

| Library | Version | Purpose |
|---------|---------|---------|
| **Netty** | 4.1.100 | High-performance networking & WebSocket |
| **Jackson** | 2.15.3 | JSON serialization/deserialization |
| **SLF4J** | 2.0.9 | Logging facade |
| **Logback** | 1.4.11 | Logging implementation |
| **JUnit 5** | 5.10.0 | Unit testing |
| **Awaitility** | 4.2.0 | Async testing utilities |
| **Java-WebSocket** | 1.5.4 | WebSocket client for tests |

---

## Running Tests

```bash
cd sync-server

# Run all tests
.\mvnw.cmd test

# Run specific test class
.\mvnw.cmd test -Dtest=ArchitectureTest
.\mvnw.cmd test -Dtest=PerformanceTest
.\mvnw.cmd test -Dtest=ConcurrencyTest
```

---

## Custom Port

To run on a different port:

```bash
java -jar target/kasagi-sync-server-1.0.0-SNAPSHOT.jar 9000
```

---

## Troubleshooting

### "mvnw is not recognized"
- Make sure you're in the `sync-server` directory
- On Windows, use `.\mvnw.cmd` (not `mvnw`)

### "JAVA_HOME not set"
- Set JAVA_HOME environment variable to your JDK installation path
- Restart your terminal after setting it

### "Port 8080 already in use"
- Use a different port: `java -jar target/kasagi-sync-server-1.0.0-SNAPSHOT.jar 9000`
- Or stop the process using port 8080

---

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
