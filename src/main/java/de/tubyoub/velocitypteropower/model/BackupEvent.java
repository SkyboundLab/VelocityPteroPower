/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.model;

import java.util.Locale;

public enum BackupEvent {
    SERVER_START("server_start"),
    SERVER_STOP("server_stop"),
    SERVER_JOIN("server_join"),
    SERVER_LEAVE("server_leave"),
    NETWORK_JOIN("network_join"),
    NETWORK_LEAVE("network_leave"),
    SCHEDULED("scheduled"),
    MANUAL("manual");

    private final String id;

    BackupEvent(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

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
