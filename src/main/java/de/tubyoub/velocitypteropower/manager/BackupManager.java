/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelAPIClient;
import de.tubyoub.velocitypteropower.api.PowerSignal;
import de.tubyoub.velocitypteropower.model.BackupEvent;
import de.tubyoub.velocitypteropower.model.BackupInfo;
import de.tubyoub.velocitypteropower.model.BackupPolicy;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.FilteredComponentLogger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates automatic backups of managed servers when configured events occur
 * (e.g. server start/stop, player join/leave) and enforces a retention limit
 * by deleting the oldest automatic backups of a server.
 *
 * Only backups whose name starts with the configured name prefix are counted
 * and deleted, so manually created backups on the panel are left untouched.
 */
public class BackupManager {

    private final VelocityPteroPower plugin;
    private final FilteredComponentLogger logger;
    private final ConfigurationManager config;
    private final MessagesManager messages;

    /** Servers with a backup operation currently in flight (prevents overlapping create/prune runs). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** Last time a backup was triggered per server id (for the min-interval throttle). */
    private final Map<String, Long> lastBackupAt = new ConcurrentHashMap<>();

    public BackupManager(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.config = plugin.getConfigurationManager();
        this.messages = plugin.getMessagesManager();
    }

    /**
     * Triggers a backup for a server if the power signal maps to an enabled backup event.
     * START maps to {@link BackupEvent#SERVER_START}, STOP to {@link BackupEvent#SERVER_STOP}.
     */
    public void onPowerSignal(String serverName, PowerSignal signal) {
        // Deliberately avoid an enum switch here: it would generate a synthetic
        // BackupManager$1 switch-map class, one more entry that a broken/truncated
        // plugin jar on the server would fail to load (NoClassDefFoundError).
        BackupEvent event = null;
        if (signal == PowerSignal.START) {
            event = BackupEvent.SERVER_START;
        } else if (signal == PowerSignal.STOP) {
            event = BackupEvent.SERVER_STOP;
        }
        // RESTART/KILL do not trigger backups
        if (event != null) {
            triggerBackup(serverName, event);
        }
    }

    /**
     * Triggers the backup a cron schedule resolved for this server, if any.
     * Equivalent to {@link #triggerBackup(String, BackupEvent)} with
     * {@link BackupEvent#SCHEDULED}.
     */
    public void triggerScheduledBackup(String serverName) {
        triggerBackup(serverName, BackupEvent.SCHEDULED);
    }

    /**
     * Triggers an automatic backup for the given server if the event is enabled in the config.
     * The backup is created asynchronously; afterwards old automatic backups are pruned
     * so that at most 'maxBackupsPerServer' remain.
     *
     * <p>A {@link BackupEvent#SCHEDULED} trigger comes from the cron scheduler
     * ({@code backups.schedule}), so it is not gated on the {@code backups.events}
     * list and not throttled by {@code minIntervalSeconds} — the cron expression
     * itself is the schedule. It still respects the enabled flag, the server
     * exclusions, retention and the in-flight guard, and an enabled schedule
     * is enough to allow it even when the event-based {@code backups.enabled}
     * is off (a per-server {@code backups.enabled: false} still opts out).
     */
    public void triggerBackup(String serverName, BackupEvent event) {
        String trackedServerId = null;
        try {
            if (event == null || serverName == null) return;
            boolean scheduled = event == BackupEvent.SCHEDULED;
            if (config.isBackupExcluded(serverName)) {
                logger.debug("Backup for '{}' skipped: server matches a backups.excludeServers pattern.", serverName);
                return;
            }

            var infoMap = plugin.getServerInfoMap();
            PteroServerInfo info = infoMap == null ? null : infoMap.get(serverName);
            if (info == null || info.getServerId() == null || info.getServerId().isBlank()) return;
            final String serverId = info.getServerId();
            trackedServerId = serverId;

            // Per-server settings override the global 'backups' config
            BackupPolicy policy = info.getBackupPolicy();
            boolean enabled;
            if (policy != null && policy.enabled() != null) {
                enabled = policy.enabled();
            } else if (scheduled) {
                enabled = config.isBackupsEnabled() || config.isBackupScheduleEnabled()
                        || (policy != null && policy.schedule() != null
                                && Boolean.TRUE.equals(policy.schedule().enabled()));
            } else {
                enabled = config.isBackupsEnabled();
            }
            if (!enabled) return;

            int maxBackups = inherit(policy != null ? policy.maxBackups() : null, config.getMaxBackupsPerServer());

            if (!scheduled) {
                Set<BackupEvent> events = inherit(policy != null ? policy.events() : null, config.getBackupEvents());
                if (!events.contains(event)) return;

                int minInterval = inherit(policy != null ? policy.minIntervalSeconds() : null, config.getBackupMinIntervalSeconds());
                long now = System.currentTimeMillis();
                Long last = lastBackupAt.get(serverId);
                if (minInterval > 0 && last != null && now - last < minInterval * 1000L) {
                    logger.debug("Backup for '{}' ({}) skipped: min interval of {}s not reached yet (event: {}).",
                            serverName, serverId, minInterval, event.getId());
                    return;
                }
            }
            long now = System.currentTimeMillis();
            if (!inFlight.add(serverId)) {
                logger.debug("Backup for '{}' ({}) skipped: another backup is already in progress (event: {}).",
                        serverName, serverId, event.getId());
                return;
            }
            lastBackupAt.put(serverId, now);

            PanelAPIClient api = plugin.getApiClient();
            if (api == null) {
                inFlight.remove(serverId);
                return;
            }

            String backupName = buildBackupName(serverName, event);
            logger.debug("Triggering automatic backup '{}' for server '{}' ({}) due to event '{}'.",
                    backupName, serverName, serverId, event.getId());

            List<String> excludedPaths = config.getBackupExcludePaths();
            CompletableFuture<Boolean> creation;
            if (config.isPruneBackupsBeforeCreate()) {
                // Keep one slot free so the new backup fits within the limit
                int keep = maxBackups > 0 ? maxBackups - 1 : -1;
                creation = api.listBackups(serverId)
                        .thenCompose(backups -> pruneOldBackups(serverName, serverId, backups, keep))
                        .thenCompose(unused -> api.createBackup(serverId, backupName, excludedPaths));
            } else {
                creation = api.createBackup(serverId, backupName, excludedPaths);
            }

            creation.thenCompose(created -> {
                logBackupCreation(serverName, backupName, event, created);
                if (config.isPruneBackupsBeforeCreate()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                int keep = maxBackups > 0 ? maxBackups : -1;
                return api.listBackups(serverId)
                        .thenCompose(backups -> pruneOldBackups(serverName, serverId, backups, keep));
            }).whenComplete((ignored, error) -> {
                inFlight.remove(serverId);
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    logger.warn(messages.mm(MessageKey.BACKUP_FAILED, Map.of(
                            "server", serverName,
                            "reason", String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause))));
                }
            });
        } catch (Exception ex) {
            if (trackedServerId != null) {
                inFlight.remove(trackedServerId);
            }
            logger.warn("Backup trigger for '{}' failed: {}", serverName, ex.toString());
        }
    }

    /**
     * Deletes the oldest automatic backups of a server so that at most
     * {@code keep} backups with the configured name prefix remain.
     * A negative value means unlimited (no deletion).
     *
     * @return a future that completes when all deletions have finished
     */
    private CompletableFuture<Void> pruneOldBackups(String serverName, String serverId, List<BackupInfo> backups, int keep) {
        if (keep < 0 || backups == null || backups.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            String prefix = config.getBackupNamePrefix() + "-";
            List<BackupInfo> managed = backups.stream()
                    .filter(b -> b != null && b.uuid() != null && b.name() != null && b.name().startsWith(prefix))
                    .sorted(Comparator.comparingLong(BackupInfo::createdAtMillis))
                    .toList();

            int excess = managed.size() - keep;
            if (excess <= 0) return CompletableFuture.completedFuture(null);

            PanelAPIClient api = plugin.getApiClient();
            if (api == null) return CompletableFuture.completedFuture(null);

            List<CompletableFuture<Void>> deletions = new ArrayList<>();
            for (int i = 0; i < excess; i++) {
                BackupInfo victim = managed.get(i);
                deletions.add(api.deleteBackup(serverId, victim.uuid()).handle((deleted, error) -> {
                    if (error != null || !Boolean.TRUE.equals(deleted)) {
                        logger.warn(messages.mm(MessageKey.BACKUP_FAILED, Map.of(
                                "server", serverName,
                                "reason", "could not delete old backup '" + victim.name() + "'")));
                    } else {
                        logger.info(messages.mm(MessageKey.BACKUP_DELETED, Map.of(
                                "server", serverName,
                                "backup", victim.name())));
                    }
                    return null;
                }));
            }
            logger.debug("Pruning {} old automatic backup(s) for '{}' (keeping at most {}).", excess, serverName, keep);
            return CompletableFuture.allOf(deletions.toArray(CompletableFuture[]::new));
        } catch (Exception ex) {
            logger.debug("Backup prune error for '{}': {}", serverName, ex.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    private void logBackupCreation(String serverName, String backupName, BackupEvent event, boolean created) {
        if (created) {
            logger.info(messages.mm(MessageKey.BACKUP_CREATED, Map.of(
                    "server", serverName,
                    "backup", backupName,
                    "event", event.getId())));
        } else {
            logger.warn(messages.mm(MessageKey.BACKUP_FAILED, Map.of(
                    "server", serverName,
                    "reason", "panel rejected the backup creation request (event: " + event.getId() + ")")));
        }
    }

    /**
     * Builds a filesystem/panel-safe backup name, e.g. "vpp-auto-server_stop-survival-20261104-003000".
     */
    private String buildBackupName(String serverName, BackupEvent event) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
        String safeServer = serverName.isBlank()
                ? "server"
                : serverName.replaceAll("[^A-Za-z0-9_.-]", "_");
        return config.getBackupNamePrefix() + "-" + event.getId() + "-" + safeServer + "-" + timestamp;
    }

    private static <T> T inherit(T override, T fallback) {
        return override != null ? override : fallback;
    }
}
