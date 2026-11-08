/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.model;

import java.util.List;

/**
 * An optional cron schedule for automatic backups. A {@code null} field
 * inherits the corresponding value from the global {@code backups.schedule}
 * section (per-server schedules only; the global schedule has no fallback).
 *
 * <p>An absent schedule (null record) means "no scheduled backups of my own —
 * use the global schedule". A schedule with {@code enabled=false} opts out
 * entirely, even when the global schedule is enabled.
 */
public record BackupSchedule(
        Boolean enabled,
        String cron,
        String timezone,
        List<String> servers) {

    /** Effective cron expression, falling back to {@code fallback} when unset. */
    public String effectiveCron(String fallback) {
        return cron != null && !cron.isBlank() ? cron.trim() : fallback;
    }

    /** Effective timezone id, falling back to {@code fallback} when unset. */
    public String effectiveTimezone(String fallback) {
        return timezone != null && !timezone.isBlank() ? timezone.trim() : fallback;
    }
}
