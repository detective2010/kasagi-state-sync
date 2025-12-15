package com.kasagi.protocol;

/**
 * Defines all message types for the sync protocol.
 *
 * Client → Server:
 * - JOIN_ROOM: Request to join a room
 * - LEAVE_ROOM: Request to leave current room
 * - STATE_UPDATE: Send local state changes
 *
 * Server → Client:
 * - FULL_STATE: Complete state snapshot
 * - DELTA_UPDATE: Incremental state changes
 * - PLAYER_JOINED: Notification of new player
 * - PLAYER_LEFT: Notification of player departure
 * - ERROR: Error notification
 */
public enum MessageType {
    // Client → Server
    JOIN_ROOM,
    LEAVE_ROOM,
    STATE_UPDATE,

    // Server → Client
    FULL_STATE,
    DELTA_UPDATE,
    PLAYER_JOINED,
    PLAYER_LEFT,
    ERROR
}
