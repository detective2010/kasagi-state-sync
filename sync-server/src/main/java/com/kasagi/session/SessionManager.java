package com.kasagi.session;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all connected client sessions.
 *
 * Thread Safety:
 * - Uses ConcurrentHashMap for thread-safe operations
 * - All methods can be called from any thread safely
 *
 * Design Notes:
 * - Session lookup by channel ID for fast access from Netty handlers
 * - Session lookup by session ID for game logic operations
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    // Map channel ID to session for fast lookup from Netty handlers
    private final Map<String, ClientSession> sessionsByChannelId;

    // Map session ID to session for game logic operations
    private final Map<String, ClientSession> sessionsBySessionId;

    public SessionManager() {
        // ConcurrentHashMap provides thread-safe operations without external synchronization
        this.sessionsByChannelId = new ConcurrentHashMap<>();
        this.sessionsBySessionId = new ConcurrentHashMap<>();
    }

    /**
     * Creates and registers a new session for a connected channel.
     *
     * @param channel The Netty channel for the new connection
     * @return The created ClientSession
     */
    public ClientSession createSession(Channel channel) {
        ClientSession session = new ClientSession(channel);
        String channelId = channel.id().asLongText();

        sessionsByChannelId.put(channelId, session);
        sessionsBySessionId.put(session.getSessionId(), session);

        logger.info("Session created: {} (channel: {})", session.getSessionId(), channelId);
        logger.debug("Total active sessions: {}", sessionsBySessionId.size());

        return session;
    }

    /**
     * Removes a session when the client disconnects.
     *
     * @param channel The Netty channel that disconnected
     * @return The removed session, or null if not found
     */
    public ClientSession removeSession(Channel channel) {
        String channelId = channel.id().asLongText();
        ClientSession session = sessionsByChannelId.remove(channelId);

        if (session != null) {
            sessionsBySessionId.remove(session.getSessionId());
            logger.info("Session removed: {} (channel: {})", session.getSessionId(), channelId);
            logger.debug("Total active sessions: {}", sessionsBySessionId.size());
        }

        return session;
    }

    /**
     * Gets a session by its Netty channel.
     */
    public ClientSession getSessionByChannel(Channel channel) {
        return sessionsByChannelId.get(channel.id().asLongText());
    }

    /**
     * Gets a session by its session ID.
     */
    public ClientSession getSessionById(String sessionId) {
        return sessionsBySessionId.get(sessionId);
    }

    /**
     * Returns all active sessions.
     * Note: Returns a snapshot - may not reflect concurrent modifications.
     */
    public Collection<ClientSession> getAllSessions() {
        return sessionsBySessionId.values();
    }

    /**
     * Returns the current number of active sessions.
     */
    public int getSessionCount() {
        return sessionsBySessionId.size();
    }

    /**
     * Checks if a session exists for the given channel.
     */
    public boolean hasSession(Channel channel) {
        return sessionsByChannelId.containsKey(channel.id().asLongText());
    }
}
