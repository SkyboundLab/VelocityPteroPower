/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.model;

/**
 * Represents a backup of a panel server as reported by the panel API.
 *
 * @param uuid             The unique identifier of the backup on the panel.
 * @param name             The name of the backup.
 * @param createdAtMillis  Creation time of the backup in epoch milliseconds (0 if unknown).
 */
public record BackupInfo(String uuid, String name, long createdAtMillis) {
}
