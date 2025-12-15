package com.kasagi.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all game rooms in the server.
 *
 * Design Decisions:
 * - Rooms are created on-demand when the first player joins
 * - Empty rooms are automatically cleaned up
 * - Each room is independent (good for sharding in the future)
 *
 * Thread Safety:
 * - ConcurrentHashMap for room storage
 * - computeIfAbsent for atomic room creation
 */
public class RoomManager {

    private static final Logger logger = LoggerFactory.getLogger(RoomManager.class);

    private final Map<String, GameRoom> rooms;

    public RoomManager() {
        this.rooms = new ConcurrentHashMap<>();
    }

    /**
     * Gets or creates a room with the given ID.
     * Thread-safe: uses computeIfAbsent for atomic creation.
     */
    public GameRoom getOrCreateRoom(String roomId) {
        return rooms.computeIfAbsent(roomId, id -> {
            logger.info("Creating new room: {}", id);
            return new GameRoom(id);
        });
    }

    /**
     * Gets a room by ID, returns null if it doesn't exist.
     */
    public GameRoom getRoom(String roomId) {
        return rooms.get(roomId);
    }

    /**
     * Removes a room if it's empty.
     * Returns true if the room was removed.
     */
    public boolean removeIfEmpty(String roomId) {
        GameRoom room = rooms.get(roomId);
        if (room != null && room.isEmpty()) {
            rooms.remove(roomId);
            logger.info("Removed empty room: {}", roomId);
            return true;
        }
        return false;
    }

    /**
     * Returns all active rooms.
     */
    public Collection<GameRoom> getAllRooms() {
        return rooms.values();
    }

    /**
     * Returns the number of active rooms.
     */
    public int getRoomCount() {
        return rooms.size();
    }

    /**
     * Checks if a room exists.
     */
    public boolean hasRoom(String roomId) {
        return rooms.containsKey(roomId);
    }

    /**
     * Gets total player count across all rooms.
     * Useful for monitoring/metrics.
     */
    public int getTotalPlayerCount() {
        return rooms.values().stream()
                .mapToInt(GameRoom::getPlayerCount)
                .sum();
    }
}
