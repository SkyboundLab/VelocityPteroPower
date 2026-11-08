/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.model;

import java.util.Locale;

/**
 * Events that can trigger an automatic backup of a managed server.
 * The {@link #getId()} value is used in the configuration file (backups.events list).
 */
public enum BackupEvent {
    SERVER_START("server_start"),
    SERVER_STOP("server_stop"),
    SERVER_JOIN("server_join"),
    SERVER_LEAVE("server_leave"),
    NETWORK_JOIN("network_join"),
    NETWORK_LEAVE("network_leave"),
    /** Fired by the cron scheduler (backups.schedule), not by a game event. */
    SCHEDULED("scheduled"),
    /** Fired by an admin running /ptero backup. */
    MANUAL("manual");

    private final String id;

    BackupEvent(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /**
     * Parses a config value into a BackupEvent. Accepts the id (e.g. "server_start")
     * as well as the enum name (e.g. "SERVER_START"), case-insensitive.
     *
     * @param value the raw config string
     * @return the matching BackupEvent, or null if unknown
     */
    public static BackupEvent fromString(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BackupEvent event : values()) {
            if (event.id.equals(normalized) || event.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return event;
            }
        }
        return null;
    }
}
