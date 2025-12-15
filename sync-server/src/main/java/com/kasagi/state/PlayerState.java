package com.kasagi.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents the state of a single player in a room.
 *
 * This class is designed to be immutable for thread safety.
 * When a player's state changes, create a new PlayerState object
 * rather than modifying the existing one.
 *
 * For the prototype, we track simple 2D position and appearance.
 * Production systems might include:
 * - 3D position and rotation
 * - Velocity for interpolation
 * - Animation state
 * - Inventory
 * - Health/status effects
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlayerState {

    private final String playerId;
    private final String playerName;
    private final String color;
    private final double x;
    private final double y;
    private final long lastUpdateTime;

    // Default constructor for Jackson
    public PlayerState() {
        this(null, null, null, 0, 0, 0);
    }

    public PlayerState(String playerId, String playerName, String color,
                       double x, double y, long lastUpdateTime) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.color = color;
        this.x = x;
        this.y = y;
        this.lastUpdateTime = lastUpdateTime;
    }

    // All getters - no setters (immutable)
    public String getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getColor() {
        return color;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * Creates a new PlayerState with updated position.
     * This follows the immutable pattern - returns a new object.
     */
    public PlayerState withPosition(double newX, double newY) {
        return new PlayerState(playerId, playerName, color, newX, newY, System.currentTimeMillis());
    }

    /**
     * Creates a new PlayerState with updated name.
     */
    public PlayerState withName(String newName) {
        return new PlayerState(playerId, newName, color, x, y, System.currentTimeMillis());
    }

    /**
     * Creates a new PlayerState with updated color.
     */
    public PlayerState withColor(String newColor) {
        return new PlayerState(playerId, playerName, newColor, x, y, System.currentTimeMillis());
    }

    /**
     * Builder for creating PlayerState objects.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String playerId;
        private String playerName;
        private String color = "#FFFFFF";
        private double x = 0;
        private double y = 0;

        public Builder playerId(String playerId) {
            this.playerId = playerId;
            return this;
        }

        public Builder playerName(String playerName) {
            this.playerName = playerName;
            return this;
        }

        public Builder color(String color) {
            this.color = color;
            return this;
        }

        public Builder x(double x) {
            this.x = x;
            return this;
        }

        public Builder y(double y) {
            this.y = y;
            return this;
        }

        public PlayerState build() {
            return new PlayerState(playerId, playerName, color, x, y, System.currentTimeMillis());
        }
    }

    @Override
    public String toString() {
        return "PlayerState{" +
                "playerId='" + playerId + '\'' +
                ", playerName='" + playerName + '\'' +
                ", x=" + x +
                ", y=" + y +
                '}';
    }
}
