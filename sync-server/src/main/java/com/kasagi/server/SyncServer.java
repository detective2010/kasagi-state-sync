package com.kasagi.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.timeout.IdleStateHandler;

import com.kasagi.handler.WebSocketFrameHandler;
import com.kasagi.session.SessionManager;
import com.kasagi.state.RoomManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Main WebSocket server using Netty's NIO for high-performance networking.
 *
 * Threading Model:
 * - Boss Group: 1 thread that accepts incoming connections
 * - Worker Group: N threads (CPU cores) that handle I/O operations
 *
 * This non-blocking design allows handling thousands of concurrent connections
 * with minimal thread overhead.
 */
public class SyncServer {

    private static final Logger logger = LoggerFactory.getLogger(SyncServer.class);
    private static final String WEBSOCKET_PATH = "/sync";

    private final int port;
    private final SessionManager sessionManager;
    private final RoomManager roomManager;

    // Netty event loop groups
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public SyncServer(int port) {
        this.port = port;
        this.sessionManager = new SessionManager();
        this.roomManager = new RoomManager();
    }

    /**
     * Starts the WebSocket server.
     * This method blocks until the server is shut down.
     */
    public void start() throws InterruptedException {
        // Boss group: accepts incoming connections (1 thread is enough)
        bossGroup = new NioEventLoopGroup(1);

        // Worker group: handles I/O for accepted connections
        // Default: 2 * number of CPU cores
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    // TCP options
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true) // Disable Nagle for low latency
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();

                            // Idle state detection for heartbeat/timeout
                            // Read timeout: 60s, Write timeout: 30s
                            pipeline.addLast(new IdleStateHandler(60, 30, 0, TimeUnit.SECONDS));

                            // HTTP codec for WebSocket handshake
                            pipeline.addLast(new HttpServerCodec());

                            // Aggregate HTTP messages (needed for WebSocket handshake)
                            pipeline.addLast(new HttpObjectAggregator(65536));

                            // WebSocket compression (optional, saves bandwidth)
                            pipeline.addLast(new WebSocketServerCompressionHandler());

                            // WebSocket protocol handler (handles handshake, ping/pong)
                            pipeline.addLast(new WebSocketServerProtocolHandler(
                                    WEBSOCKET_PATH,
                                    null,      // subprotocols
                                    true,      // allow extensions
                                    65536,     // max frame size
                                    false,     // allow mask mismatch
                                    true,      // check starting slash
                                    10000L     // handshake timeout ms
                            ));

                            // Our custom handler for game messages
                            pipeline.addLast(new WebSocketFrameHandler(sessionManager, roomManager));
                        }
                    });

            // Bind and start accepting connections
            serverChannel = bootstrap.bind(port).sync().channel();

            logger.info("Server started successfully!");
            logger.info("WebSocket endpoint: ws://localhost:{}{}", port, WEBSOCKET_PATH);

            // Block until the server channel is closed
            serverChannel.closeFuture().sync();

        } finally {
            shutdown();
        }
    }

    /**
     * Gracefully shuts down the server.
     * - Stops accepting new connections
     * - Waits for existing connections to complete
     * - Releases all resources
     */
    public void shutdown() {
        logger.info("Shutting down server...");

        if (serverChannel != null) {
            serverChannel.close();
        }

        // Graceful shutdown of event loops
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        logger.info("Server shutdown complete.");
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public RoomManager getRoomManager() {
        return roomManager;
    }
}
