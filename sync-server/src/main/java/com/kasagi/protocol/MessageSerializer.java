package com.kasagi.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles serialization/deserialization of messages.
 *
 * Current Implementation: JSON using Jackson
 * - Pros: Human-readable, easy to debug, widely supported
 * - Cons: Larger payload size, slower than binary formats
 *
 * For production, consider:
 * - Protocol Buffers: ~3-10x smaller, much faster parsing
 * - MessagePack: Binary JSON, good balance of size/compatibility
 * - FlatBuffers: Zero-copy access, best for real-time games
 *
 * The serializer is thread-safe - ObjectMapper is thread-safe after configuration.
 */
public class MessageSerializer {

    private static final Logger logger = LoggerFactory.getLogger(MessageSerializer.class);

    // ObjectMapper is thread-safe and should be reused
    private final ObjectMapper objectMapper;

    public MessageSerializer() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Serializes a Message to JSON string.
     *
     * @param message The message to serialize
     * @return JSON string representation
     */
    public String serialize(Message message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize message: {}", message, e);
            throw new RuntimeException("Serialization failed", e);
        }
    }

    /**
     * Deserializes a JSON string to Message.
     *
     * @param json The JSON string to deserialize
     * @return Deserialized Message object
     */
    public Message deserialize(String json) {
        try {
            return objectMapper.readValue(json, Message.class);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize message: {}", json, e);
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    /**
     * Creates a new JSON object node for building payloads.
     */
    public ObjectNode createObjectNode() {
        return objectMapper.createObjectNode();
    }

    /**
     * Gets the underlying ObjectMapper for advanced operations.
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Estimates the byte size of a message for bandwidth monitoring.
     * Useful for implementing bandwidth limits and monitoring.
     */
    public int estimateSize(Message message) {
        return serialize(message).getBytes().length;
    }
}
