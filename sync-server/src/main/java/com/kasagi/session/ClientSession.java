package com.kasagi.session;

import io.netty.channel.Channel;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a connected client's session.
 *
 * Each WebSocket connection has an associated ClientSession that tracks:
 * - Unique session ID
 * - The Netty channel for sending messages
 * - Current room membership
 * - Player information
 *
 * Thread Safety:
 * - Session ID and channel are immutable after creation
 * - Room ID uses AtomicReference for thread-safe updates
 */
public class ClientSession {

    private final String sessionId;
    private final Channel channel;
    private final long connectedAt;

    // Mutable state - uses atomic reference for thread safety
    private final AtomicReference<String> currentRoomId;
    private final AtomicReference<String> playerName;
    private final AtomicReference<String> playerColor;

    public ClientSession(Channel channel) {
        this.sessionId = UUID.randomUUID().toString();
        this.channel = channel;
        this.connectedAt = System.currentTimeMillis();
        this.currentRoomId = new AtomicReference<>(null);
        this.playerName = new AtomicReference<>("Anonymous");
        this.playerColor = new AtomicReference<>("#FFFFFF");
    }

    // Immutable getters
    public String getSessionId() {
        return sessionId;
    }

    public Channel getChannel() {
        return channel;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    // Thread-safe getters and setters for mutable state
    public String getCurrentRoomId() {
        return currentRoomId.get();
    }

    public void setCurrentRoomId(String roomId) {
        currentRoomId.set(roomId);
    }

    public String getPlayerName() {
        return playerName.get();
    }

    public void setPlayerName(String name) {
        playerName.set(name);
    }

    public String getPlayerColor() {
        return playerColor.get();
    }

    public void setPlayerColor(String color) {
        playerColor.set(color);
    }

    /**
     * Sends a text message to this client via the WebSocket channel.
     * This is thread-safe - Netty handles message queuing.
     */
    public void send(String message) {
        if (channel.isActive()) {
            channel.writeAndFlush(new io.netty.handler.codec.http.websocketx.TextWebSocketFrame(message));
        }
    }

    /**
     * Checks if the session is still active (channel open).
     */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    /**
     * Checks if the client is currently in a room.
     */
    public boolean isInRoom() {
        return currentRoomId.get() != null;
    }

    @Override
    public String toString() {
        return "ClientSession{" +
                "sessionId='" + sessionId + '\'' +
                ", playerName='" + playerName.get() + '\'' +
                ", roomId='" + currentRoomId.get() + '\'' +
                ", active=" + isActive() +
                '}';
    }
}
