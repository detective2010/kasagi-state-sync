package com.kasagi.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents a message in the sync protocol.
 *
 * This class is immutable for thread safety - once created, it cannot be modified.
 * This allows safe sharing across threads without synchronization.
 *
 * JSON format:
 * {
 *     "type": "STATE_UPDATE",
 *     "roomId": "room-123",
 *     "playerId": "player-456",
 *     "payload": { ... },
 *     "version": 42,
 *     "timestamp": 1234567890
 * }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {

    private MessageType type;
    private String roomId;
    private String playerId;
    private JsonNode payload;  // Flexible payload using Jackson's tree model
    private Long version;
    private Long timestamp;

    // Default constructor for Jackson deserialization
    public Message() {
    }

    // Private constructor - use builder for creation
    private Message(MessageType type, String roomId, String playerId,
                    JsonNode payload, Long version, Long timestamp) {
        this.type = type;
        this.roomId = roomId;
        this.playerId = playerId;
        this.payload = payload;
        this.version = version;
        this.timestamp = timestamp;
    }

    // Getters (no setters - immutable after construction via builder)
    public MessageType getType() {
        return type;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getPlayerId() {
        return playerId;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Long getVersion() {
        return version;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    // Setters for Jackson deserialization
    public void setType(MessageType type) {
        this.type = type;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Builder pattern for creating immutable Message objects.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private MessageType type;
        private String roomId;
        private String playerId;
        private JsonNode payload;
        private Long version;
        private Long timestamp;

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder roomId(String roomId) {
            this.roomId = roomId;
            return this;
        }

        public Builder playerId(String playerId) {
            this.playerId = playerId;
            return this;
        }

        public Builder payload(JsonNode payload) {
            this.payload = payload;
            return this;
        }

        public Builder version(Long version) {
            this.version = version;
            return this;
        }

        public Builder timestamp(Long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Message build() {
            return new Message(type, roomId, playerId, payload, version,
                    timestamp != null ? timestamp : System.currentTimeMillis());
        }
    }

    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", roomId='" + roomId + '\'' +
                ", playerId='" + playerId + '\'' +
                ", version=" + version +
                '}';
    }
}
