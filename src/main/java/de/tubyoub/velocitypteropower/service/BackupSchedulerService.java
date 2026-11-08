/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.service;

import com.velocitypowered.api.scheduler.ScheduledTask;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.McServerSoftApiClient;
import de.tubyoub.velocitypteropower.manager.BackupManager;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.BackupSchedule;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.CronExpression;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs automatic backups on a cron schedule ({@code backups.schedule}).
 *
 * <p>Every 30 seconds it checks each effective schedule — the global one and
 * any per-server override — against the current time in that schedule's
 * timezone. When the current minute matches the cron expression, every server
 * the schedule covers gets one scheduled backup via
 * {@link BackupManager#triggerScheduledBackup(String)}. A schedule only fires
 * once per minute (tracked per schedule key), so a slow or repeated tick can
 * never double-fire, and a minute missed while the proxy was down is skipped
 * rather than backfilled.
 *
 * <p>The effective schedule is re-read from the live config on every tick, so
 * {@code /ptero reload} applies a changed cron expression without a restart.
 * Scheduled backups do not require the server to be online — the panel backs
 * up the files on disk — and they ignore {@code backups.events} and
 * {@code minIntervalSeconds} (the cron expression is the interval).
 */
public class BackupSchedulerService {

    /** How often the scheduler wakes up to check whether a minute matches. */
    private static final int CHECK_INTERVAL_SECONDS = 30;

    private final VelocityPteroPower plugin;
    private final FilteredComponentLogger logger;
    private ScheduledTask task;

    /** Schedule key -> the minute (in that schedule's timezone) it last fired for. */
    private final Map<String, String> lastFiredMinute = new ConcurrentHashMap<>();
    /** Cron expressions / timezones already warned about, so a bad config doesn't spam every tick. */
    private final Set<String> warnedInvalid = ConcurrentHashMap.newKeySet();

    public BackupSchedulerService(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
    }

    /** Starts the periodic check. Safe to call when no schedule is configured — it just never fires. */
    public void start() {
        if (plugin.getApiClient() instanceof McServerSoftApiClient) {
            logger.warn("Scheduled backups are disabled: Mc Server Soft does not support the backup API.");
            return;
        }
        logScheduleSummary();
        task = plugin.getProxyServer().getScheduler()
                .buildTask(plugin, this::checkSchedules)
                .delay(10, TimeUnit.SECONDS)
                .repeat(CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .schedule();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Logs every effective schedule with its next run, so a typo'd cron is visible at startup/reload. */
    public void logScheduleSummary() {
        try {
            List<ResolvedSchedule> schedules = resolveSchedules();
            if (schedules.isEmpty()) {
                logger.debug("No backup schedules configured (backups.schedule.enabled is false everywhere).");
                return;
            }
            for (ResolvedSchedule schedule : schedules) {
                ZonedDateTime next = schedule.cron.nextAfter(ZonedDateTime.now(schedule.zone));
                logger.info("Scheduled backups: '{}' ({}) for {} — next run {}.",
                        schedule.cron.getExpression(), schedule.zone.getId(),
                        String.join(", ", schedule.servers),
                        next != null ? next.toString() : "none in the next 4 years");
            }
        } catch (Exception ex) {
            logger.debug("Could not log backup schedule summary: {}", ex.toString());
        }
    }

    /** One tick: fire every schedule whose cron matches the current minute in its timezone. */
    public void checkSchedules() {
        try {
            BackupManager backups = plugin.getBackupManager();
            if (backups == null) return;
            for (ResolvedSchedule schedule : resolveSchedules()) {
                ZonedDateTime now = ZonedDateTime.now(schedule.zone).truncatedTo(ChronoUnit.MINUTES);
                if (!schedule.cron.matches(now)) continue;
                String minuteKey = now.toString();
                // put() returns the minute this schedule last fired for; if it is
                // this minute, a previous tick already fired it — never double-fire.
                if (minuteKey.equals(lastFiredMinute.put(schedule.key, minuteKey))) {
                    continue;
                }
                logger.info("Scheduled backup time reached ('{}' in {}), backing up {}.",
                        schedule.cron.getExpression(), schedule.zone.getId(),
                        String.join(", ", schedule.servers));
                for (String serverName : schedule.servers) {
                    try {
                        backups.triggerScheduledBackup(serverName);
                    } catch (Exception ex) {
                        logger.debug("Scheduled backup for '{}' failed: {}", serverName, ex.toString());
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("Scheduled backup check failed: {}", ex.toString());
        }
    }

    /**
     * Resolves the effective schedule for every managed server from the live config.
     * A per-server schedule with {@code enabled: true} replaces the global one
     * for that server (inheriting the global cron/timezone for any field it
     * leaves unset); {@code enabled: false} opts the server out of scheduled
     * backups entirely. Servers not covered by any enabled schedule are absent.
     */
    private List<ResolvedSchedule> resolveSchedules() {
        ConfigurationManager config = plugin.getConfigurationManager();
        Map<String, PteroServerInfo> servers = plugin.getServerInfoMap();
        List<ResolvedSchedule> resolved = new ArrayList<>();
        if (config == null || servers == null || servers.isEmpty()) return resolved;

        BackupSchedule global = config.getBackupSchedule();
        boolean globalEnabled = global != null && Boolean.TRUE.equals(global.enabled());
        String globalCron = globalEnabled ? global.effectiveCron(null) : null;
        String globalTimezone = globalEnabled ? global.effectiveTimezone("UTC") : null;
        List<String> globalServerFilter = globalEnabled && global.servers() != null ? global.servers() : List.of();

        // Servers covered by the global schedule, before per-server overrides.
        Map<String, String> globalServers = new LinkedHashMap<>();
        if (globalEnabled && globalCron != null && !globalCron.isBlank()) {
            for (String name : servers.keySet()) {
                if (!globalServerFilter.isEmpty()
                        && globalServerFilter.stream().noneMatch(s -> s.equalsIgnoreCase(name))) {
                    continue;
                }
                globalServers.put(name, name);
            }
        }

        for (Map.Entry<String, PteroServerInfo> entry : servers.entrySet()) {
            String name = entry.getKey();
            BackupSchedule own = entry.getValue() != null && entry.getValue().getBackupPolicy() != null
                    ? entry.getValue().getBackupPolicy().schedule() : null;
            if (own == null) continue;
            if (Boolean.FALSE.equals(own.enabled())) {
                globalServers.remove(name);
                continue;
            }
            if (Boolean.TRUE.equals(own.enabled())) {
                globalServers.remove(name);
                String cronText = own.effectiveCron(globalCron);
                String zoneText = own.effectiveTimezone(globalTimezone != null ? globalTimezone : "UTC");
                ResolvedSchedule schedule = resolveOne("server:" + name, cronText, zoneText, List.of(name));
                if (schedule != null) resolved.add(schedule);
            }
        }

        if (!globalServers.isEmpty()) {
            ResolvedSchedule schedule = resolveOne("global", globalCron, globalTimezone, new ArrayList<>(globalServers.keySet()));
            if (schedule != null) resolved.add(schedule);
        }
        return resolved;
    }

    private ResolvedSchedule resolveOne(String key, String cronText, String zoneText, List<String> serverNames) {
        if (cronText == null || cronText.isBlank()) {
            warnOnce("cron-blank:" + key, "Backup schedule '{}' has no cron expression; scheduled backups disabled for it.", key);
            return null;
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(cronText);
        } catch (IllegalArgumentException ex) {
            warnOnce("cron-invalid:" + cronText, "Invalid backup cron expression '{}': {} — scheduled backups disabled for it.", cronText, ex.getMessage());
            return null;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneText != null && !zoneText.isBlank() ? zoneText.trim() : "UTC");
        } catch (Exception ex) {
            warnOnce("tz-invalid:" + zoneText, "Invalid backup schedule timezone '{}': {} — scheduled backups disabled for it.", zoneText, ex.getMessage());
            return null;
        }
        return new ResolvedSchedule(key, cron, zone, serverNames);
    }

    private void warnOnce(String warnKey, String message, Object... args) {
        if (warnedInvalid.add(warnKey)) {
            logger.warn(message, args);
        }
    }

    private record ResolvedSchedule(String key, CronExpression cron, ZoneId zone, List<String> servers) {
    }
}
