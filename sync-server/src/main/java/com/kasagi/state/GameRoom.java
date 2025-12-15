package com.kasagi.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a game room with synchronized state.
 *
 * Thread Safety Strategy:
 * 1. ConcurrentHashMap for player states - allows concurrent reads
 * 2. AtomicLong for version number - lock-free incrementing
 * 3. Synchronized blocks only for operations requiring consistency
 *    (like delta calculation that needs stable state)
 *
 * Each room is independent - no cross-room synchronization needed.
 * This makes horizontal scaling easier (rooms can be on different servers).
 */
public class GameRoom {

    private static final Logger logger = LoggerFactory.getLogger(GameRoom.class);

    private final String roomId;
    private final long createdAt;

    // Thread-safe collections
    private final ConcurrentHashMap<String, PlayerState> players;
    private final Set<String> sessionIds;  // Players currently in this room

    // Version number for conflict detection and delta updates
    // Clients track their last known version to detect missed updates
    private final AtomicLong version;

    public GameRoom(String roomId) {
        this.roomId = roomId;
        this.createdAt = System.currentTimeMillis();
        this.players = new ConcurrentHashMap<>();
        this.sessionIds = ConcurrentHashMap.newKeySet();
        this.version = new AtomicLong(0);
    }

    // === Player Management ===

    /**
     * Adds a new player to the room.
     * Returns the current state version for the joining player.
     */
    public long addPlayer(String sessionId, PlayerState playerState) {
        sessionIds.add(sessionId);
        players.put(playerState.getPlayerId(), playerState);
        long newVersion = version.incrementAndGet();

        logger.info("Player {} joined room {} (version: {})",
                playerState.getPlayerName(), roomId, newVersion);

        return newVersion;
    }

    /**
     * Removes a player from the room.
     */
    public PlayerState removePlayer(String sessionId, String playerId) {
        sessionIds.remove(sessionId);
        PlayerState removed = players.remove(playerId);
        version.incrementAndGet();

        if (removed != null) {
            logger.info("Player {} left room {}", removed.getPlayerName(), roomId);
        }

        return removed;
    }

    /**
     * Updates a player's state and returns what changed (for delta updates).
     *
     * This method is synchronized to ensure consistent delta calculation.
     * The critical section is kept minimal.
     */
    public synchronized Delta updatePlayerState(String playerId, PlayerState newState) {
        PlayerState oldState = players.get(playerId);

        if (oldState == null) {
            logger.warn("Attempted to update non-existent player: {}", playerId);
            return null;
        }

        // Calculate what changed before updating
        Delta delta = calculateDelta(playerId, oldState, newState);

        // Apply the update
        players.put(playerId, newState);
        long newVersion = version.incrementAndGet();
        delta.setVersion(newVersion);

        return delta;
    }

    /**
     * Calculates the difference between old and new state.
     * Only changed fields are included in the delta.
     */
    private Delta calculateDelta(String playerId, PlayerState oldState, PlayerState newState) {
        Delta delta = new Delta(playerId);

        // Check each field for changes
        if (oldState.getX() != newState.getX()) {
            delta.addChange("x", newState.getX());
        }
        if (oldState.getY() != newState.getY()) {
            delta.addChange("y", newState.getY());
        }
        if (!oldState.getColor().equals(newState.getColor())) {
            delta.addChange("color", newState.getColor());
        }
        if (!oldState.getPlayerName().equals(newState.getPlayerName())) {
            delta.addChange("playerName", newState.getPlayerName());
        }

        return delta;
    }

    // === State Retrieval ===

    /**
     * Returns an unmodifiable view of all players.
     * Safe to iterate while other threads modify the map.
     */
    public Map<String, PlayerState> getAllPlayers() {
        return Collections.unmodifiableMap(players);
    }

    /**
     * Gets a specific player's state.
     */
    public PlayerState getPlayer(String playerId) {
        return players.get(playerId);
    }

    /**
     * Returns the set of session IDs in this room.
     */
    public Set<String> getSessionIds() {
        return Collections.unmodifiableSet(sessionIds);
    }

    // === Room Info ===

    public String getRoomId() {
        return roomId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version.get();
    }

    public int getPlayerCount() {
        return players.size();
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    /**
     * Checks if a session is in this room.
     */
    public boolean hasSession(String sessionId) {
        return sessionIds.contains(sessionId);
    }

    @Override
    public String toString() {
        return "GameRoom{" +
                "roomId='" + roomId + '\'' +
                ", playerCount=" + players.size() +
                ", version=" + version.get() +
                '}';
    }
}
