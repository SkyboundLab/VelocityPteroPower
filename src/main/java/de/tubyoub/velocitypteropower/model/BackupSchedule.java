/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.model;

import java.util.List;

public record BackupSchedule(
        Boolean enabled,
        String cron,
        String timezone,
        List<String> servers) {

    public String effectiveCron(String fallback) {
        return cron != null && !cron.isBlank() ? cron.trim() : fallback;
    }

    public String effectiveTimezone(String fallback) {
        return timezone != null && !timezone.isBlank() ? timezone.trim() : fallback;
    }
}
