package com.kasagi;

import com.kasagi.server.SyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Kasagi State Synchronization Service.
 *
 * This service provides real-time state synchronization for KasagiEngine
 * applications including multiplayer games and collaborative tools.
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;

        // Allow port override via command line argument
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.warn("Invalid port argument '{}', using default port {}", args[0], DEFAULT_PORT);
            }
        }

        logger.info("===========================================");
        logger.info("  Kasagi State Sync Service");
        logger.info("  Starting on port {}", port);
        logger.info("===========================================");

        SyncServer server = new SyncServer(port);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping server...");
            server.shutdown();
        }));

        try {
            server.start();
        } catch (Exception e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }
}
