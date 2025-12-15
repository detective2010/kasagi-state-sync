package com.kasagi.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kasagi.protocol.Message;
import com.kasagi.protocol.MessageSerializer;
import com.kasagi.protocol.MessageType;
import com.kasagi.session.ClientSession;
import com.kasagi.session.SessionManager;
import com.kasagi.state.Delta;
import com.kasagi.state.GameRoom;
import com.kasagi.state.PlayerState;
import com.kasagi.state.RoomManager;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Handles WebSocket messages for state synchronization.
 *
 * This is where the core sync logic lives:
 * - JOIN_ROOM: Add player to room, send current state
 * - STATE_UPDATE: Update player state, broadcast delta to others
 * - LEAVE_ROOM: Remove player, notify others
 *
 * Threading Model:
 * - Each channel is handled by a single Netty worker thread
 * - No need for synchronization within a single channel's handler
 * - Cross-channel operations (broadcasts) use thread-safe collections
 *
 * Important: Never block in this handler! Use async operations for I/O.
 */
public class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameHandler.class);

    private final SessionManager sessionManager;
    private final RoomManager roomManager;
    private final MessageSerializer serializer;

    public WebSocketFrameHandler(SessionManager sessionManager, RoomManager roomManager) {
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.serializer = new MessageSerializer();
    }

    /**
     * Called when a new WebSocket connection is established.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ClientSession session = sessionManager.createSession(ctx.channel());
        logger.info("New connection: {}", session.getSessionId());
    }

    /**
     * Called when a WebSocket connection is closed.
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        ClientSession session = sessionManager.removeSession(ctx.channel());
        if (session != null) {
            handleDisconnect(session);
        }
    }

    /**
     * Called when a WebSocket frame is received.
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // We only handle text frames (JSON messages)
        if (!(frame instanceof TextWebSocketFrame)) {
            logger.warn("Unsupported frame type: {}", frame.getClass().getName());
            return;
        }

        String json = ((TextWebSocketFrame) frame).text();
        ClientSession session = sessionManager.getSessionByChannel(ctx.channel());

        if (session == null) {
            logger.error("Received message from unknown channel");
            return;
        }

        try {
            Message message = serializer.deserialize(json);
            handleMessage(session, message);
        } catch (Exception e) {
            logger.error("Failed to process message: {}", json, e);
            sendError(session, "Invalid message format");
        }
    }

    /**
     * Routes the message to the appropriate handler.
     */
    private void handleMessage(ClientSession session, Message message) {
        logger.debug("Received {} from {}", message.getType(), session.getSessionId());

        switch (message.getType()) {
            case JOIN_ROOM -> handleJoinRoom(session, message);
            case LEAVE_ROOM -> handleLeaveRoom(session, message);
            case STATE_UPDATE -> handleStateUpdate(session, message);
            default -> sendError(session, "Unknown message type: " + message.getType());
        }
    }

    /**
     * Handles a player joining a room.
     */
    private void handleJoinRoom(ClientSession session, Message message) {
        String roomId = message.getRoomId();
        JsonNode payload = message.getPayload();

        if (roomId == null || roomId.isEmpty()) {
            sendError(session, "Room ID is required");
            return;
        }

        // Leave current room if in one
        if (session.isInRoom()) {
            handleLeaveRoom(session, message);
        }

        // Extract player info from payload
        String playerName = payload != null && payload.has("playerName")
                ? payload.get("playerName").asText()
                : "Player-" + session.getSessionId().substring(0, 8);
        String playerColor = payload != null && payload.has("color")
                ? payload.get("color").asText()
                : generateRandomColor();

        session.setPlayerName(playerName);
        session.setPlayerColor(playerColor);
        session.setCurrentRoomId(roomId);

        // Create player state with random initial position
        PlayerState playerState = PlayerState.builder()
                .playerId(session.getSessionId())
                .playerName(playerName)
                .color(playerColor)
                .x(Math.random() * 800)  // Random position in game area
                .y(Math.random() * 600)
                .build();

        // Add to room
        GameRoom room = roomManager.getOrCreateRoom(roomId);
        long version = room.addPlayer(session.getSessionId(), playerState);

        // Send full state to the joining player
        sendFullState(session, room);

        // Notify other players in the room
        broadcastPlayerJoined(room, session, playerState);

        logger.info("Player {} joined room {} ({} players)",
                playerName, roomId, room.getPlayerCount());
    }

    /**
     * Handles a player leaving a room.
     */
    private void handleLeaveRoom(ClientSession session, Message message) {
        String roomId = session.getCurrentRoomId();
        if (roomId == null) {
            return;
        }

        GameRoom room = roomManager.getRoom(roomId);
        if (room != null) {
            room.removePlayer(session.getSessionId(), session.getSessionId());

            // Notify other players
            broadcastPlayerLeft(room, session);

            // Clean up empty room
            roomManager.removeIfEmpty(roomId);
        }

        session.setCurrentRoomId(null);
        logger.info("Player {} left room {}", session.getPlayerName(), roomId);
    }

    /**
     * Handles a state update from a player.
     * This is the hot path - needs to be fast!
     */
    private void handleStateUpdate(ClientSession session, Message message) {
        String roomId = session.getCurrentRoomId();
        if (roomId == null) {
            sendError(session, "Not in a room");
            return;
        }

        GameRoom room = roomManager.getRoom(roomId);
        if (room == null) {
            sendError(session, "Room not found");
            return;
        }

        JsonNode payload = message.getPayload();
        if (payload == null) {
            return;
        }

        // Get current state and create updated version
        PlayerState currentState = room.getPlayer(session.getSessionId());
        if (currentState == null) {
            return;
        }

        // Extract updated values (keep current if not provided)
        double newX = payload.has("x") ? payload.get("x").asDouble() : currentState.getX();
        double newY = payload.has("y") ? payload.get("y").asDouble() : currentState.getY();

        // Create new immutable state
        PlayerState newState = currentState.withPosition(newX, newY);

        // Update state and get delta
        Delta delta = room.updatePlayerState(session.getSessionId(), newState);

        // Broadcast delta to other players (not the sender)
        if (delta != null && delta.hasChanges()) {
            broadcastDelta(room, session.getSessionId(), delta);
        }
    }

    /**
     * Handles client disconnection.
     */
    private void handleDisconnect(ClientSession session) {
        String roomId = session.getCurrentRoomId();
        if (roomId != null) {
            GameRoom room = roomManager.getRoom(roomId);
            if (room != null) {
                room.removePlayer(session.getSessionId(), session.getSessionId());
                broadcastPlayerLeft(room, session);
                roomManager.removeIfEmpty(roomId);
            }
        }
        logger.info("Player {} disconnected", session.getPlayerName());
    }

    // === Message Sending Methods ===

    /**
     * Sends the full room state to a player.
     * Used when joining a room or after reconnection.
     */
    private void sendFullState(ClientSession session, GameRoom room) {
        ObjectNode playersNode = serializer.createObjectNode();

        for (Map.Entry<String, PlayerState> entry : room.getAllPlayers().entrySet()) {
            PlayerState player = entry.getValue();
            ObjectNode playerNode = serializer.createObjectNode();
            playerNode.put("playerId", player.getPlayerId());
            playerNode.put("playerName", player.getPlayerName());
            playerNode.put("color", player.getColor());
            playerNode.put("x", player.getX());
            playerNode.put("y", player.getY());
            playersNode.set(entry.getKey(), playerNode);
        }

        ObjectNode payload = serializer.createObjectNode();
        payload.set("players", playersNode);

        Message response = Message.builder()
                .type(MessageType.FULL_STATE)
                .roomId(room.getRoomId())
                .playerId(session.getSessionId())
                .payload(payload)
                .version(room.getVersion())
                .build();

        session.send(serializer.serialize(response));
    }

    /**
     * Broadcasts a player joined event to all players in the room.
     */
    private void broadcastPlayerJoined(GameRoom room, ClientSession joiningSession, PlayerState playerState) {
        ObjectNode payload = serializer.createObjectNode();
        payload.put("playerId", playerState.getPlayerId());
        payload.put("playerName", playerState.getPlayerName());
        payload.put("color", playerState.getColor());
        payload.put("x", playerState.getX());
        payload.put("y", playerState.getY());

        Message notification = Message.builder()
                .type(MessageType.PLAYER_JOINED)
                .roomId(room.getRoomId())
                .payload(payload)
                .version(room.getVersion())
                .build();

        String json = serializer.serialize(notification);
        broadcastToRoom(room, joiningSession.getSessionId(), json);
    }

    /**
     * Broadcasts a player left event to all players in the room.
     */
    private void broadcastPlayerLeft(GameRoom room, ClientSession leavingSession) {
        ObjectNode payload = serializer.createObjectNode();
        payload.put("playerId", leavingSession.getSessionId());
        payload.put("playerName", leavingSession.getPlayerName());

        Message notification = Message.builder()
                .type(MessageType.PLAYER_LEFT)
                .roomId(room.getRoomId())
                .payload(payload)
                .version(room.getVersion())
                .build();

        String json = serializer.serialize(notification);
        broadcastToRoom(room, leavingSession.getSessionId(), json);
    }

    /**
     * Broadcasts a delta update to all players except the sender.
     */
    private void broadcastDelta(GameRoom room, String excludeSessionId, Delta delta) {
        ObjectNode playerChanges = serializer.createObjectNode();
        ObjectNode changes = serializer.createObjectNode();

        for (Map.Entry<String, Object> entry : delta.getChanges().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Double) {
                changes.put(entry.getKey(), (Double) value);
            } else if (value instanceof String) {
                changes.put(entry.getKey(), (String) value);
            } else if (value instanceof Integer) {
                changes.put(entry.getKey(), (Integer) value);
            }
        }
        playerChanges.set(delta.getPlayerId(), changes);

        ObjectNode payload = serializer.createObjectNode();
        payload.set("players", playerChanges);

        Message update = Message.builder()
                .type(MessageType.DELTA_UPDATE)
                .roomId(room.getRoomId())
                .payload(payload)
                .version(delta.getVersion())
                .build();

        String json = serializer.serialize(update);
        broadcastToRoom(room, excludeSessionId, json);
    }

    /**
     * Broadcasts a message to all players in a room except one.
     */
    private void broadcastToRoom(GameRoom room, String excludeSessionId, String json) {
        for (String sessionId : room.getSessionIds()) {
            if (!sessionId.equals(excludeSessionId)) {
                ClientSession session = sessionManager.getSessionById(sessionId);
                if (session != null && session.isActive()) {
                    session.send(json);
                }
            }
        }
    }

    /**
     * Sends an error message to a client.
     */
    private void sendError(ClientSession session, String errorMessage) {
        ObjectNode payload = serializer.createObjectNode();
        payload.put("message", errorMessage);

        Message error = Message.builder()
                .type(MessageType.ERROR)
                .payload(payload)
                .build();

        session.send(serializer.serialize(error));
    }

    /**
     * Generates a random hex color for a player.
     */
    private String generateRandomColor() {
        String[] colors = {"#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFEAA7", "#DDA0DD", "#98D8C8", "#F7DC6F"};
        return colors[(int) (Math.random() * colors.length)];
    }

    // === Netty Event Handlers ===

    /**
     * Handles idle state events (heartbeat timeout).
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                logger.warn("Connection idle timeout, closing: {}", ctx.channel().id());
                ctx.close();
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * Handles exceptions.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket error", cause);
        ctx.close();
    }
}
