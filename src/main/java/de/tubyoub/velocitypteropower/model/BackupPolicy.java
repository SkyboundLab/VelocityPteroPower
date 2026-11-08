/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.model;

import java.util.Set;

/**
 * Per-server backup policy. A {@code null} field inherits the corresponding
 * value from the global 'backups' config.
 */
public record BackupPolicy(
        Boolean enabled,
        Set<BackupEvent> events,
        Integer maxBackups,
        Integer minIntervalSeconds,
        BackupSchedule schedule) {
}
