package com.kasagi.state;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the changes (delta) between two states.
 *
 * Delta compression is crucial for bandwidth efficiency:
 * - Instead of sending full state (~1KB), send only changes (~50 bytes)
 * - At 60 updates/second, this saves significant bandwidth
 *
 * Example:
 * Full state: {"playerId":"p1","name":"John","color":"#FF0000","x":100,"y":200}
 * Delta:      {"playerId":"p1","changes":{"x":101}}
 *
 * The version number allows clients to detect if they missed any updates.
 * If client has version 10 and receives version 15, they need a full state sync.
 */
public class Delta {

    private final String playerId;
    private final Map<String, Object> changes;
    private long version;
    private final long timestamp;

    public Delta(String playerId) {
        this.playerId = playerId;
        this.changes = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Adds a changed field to the delta.
     */
    public void addChange(String field, Object value) {
        changes.put(field, value);
    }

    /**
     * Checks if any fields changed.
     */
    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    public String getPlayerId() {
        return playerId;
    }

    public Map<String, Object> getChanges() {
        return changes;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Delta{" +
                "playerId='" + playerId + '\'' +
                ", changes=" + changes +
                ", version=" + version +
                '}';
    }
}
