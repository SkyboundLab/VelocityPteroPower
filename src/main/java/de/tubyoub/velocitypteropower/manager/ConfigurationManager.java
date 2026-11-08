/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.manager;

import de.tubyoub.velocitypteropower.model.BackupEvent;
import de.tubyoub.velocitypteropower.model.BackupPolicy;
import de.tubyoub.velocitypteropower.model.BackupSchedule;
import de.tubyoub.velocitypteropower.model.PteroServerInfo;
import de.tubyoub.velocitypteropower.util.CronExpression;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.api.PanelType;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.MergeRule;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;

/**
 * This class manages the configuration for the VelocityPteroPower plugin.
 * It loads the configuration from a YAML file and provides methods to access the configuration values.
 */

public class ConfigurationManager {

    public enum ServerCheckMethod {
        VELOCITY_PING,
        PANEL_API
    }

    public enum ForcedHostOfflineBehavior {
        DISCONNECT,
        LOBBY_OR_LIMBO,
        LIMBO_ONLY
    }

    private Path dataDirectory;
    private YamlDocument config;
    private String panelUrl;
    private String apiKey;
    private PanelType panel;
    private boolean checkUpdate;
    private boolean printRateLimit;
    private boolean serverNotFoundMessage;
    private boolean whitelistAllowBypass;
    private String languageOverride;
    private int loggerLevel;
    private int apiThreads;
    private int pingTimeout;
    private int shutdownRetryDelay;
    private int shutdownRetries;
    private int idleStartShutdownTime;
    private int playerCommandCooldown;
    private int startupInitialCheckDelay;
    private int whitelistCheckInterval;
    private ServerCheckMethod serverCheckMethod;
    private List<String> stopAllIgnoreList;
    private int maxOnlineServers;
    private boolean maxOnlineAllowBypass;
    private List<String> maxOnlineExemptList;
    private boolean countLobbiesInMaxOnline;
    private boolean countLimbosInMaxOnline;
    private List<String> alwaysOnlineList;
    private int alwaysOnlineCheckInterval;
    private int resourceCacheSeconds;
    private boolean resourcePrefetchEnabled;
    private int idleShutdownCheckInterval;

    // Move history
    private boolean moveHistoryEnabled;
    private int moveHistoryMaxEntries;

    // Automatic backups
    private boolean backupsEnabled;
    private Set<BackupEvent> backupEvents = EnumSet.of(BackupEvent.SERVER_START, BackupEvent.SERVER_STOP);
    private int maxBackupsPerServer = 5;
    private int backupMinIntervalSeconds = 60;
    private boolean pruneBackupsBeforeCreate;
    private String backupNamePrefix = "vpp-auto";
    private List<String> backupExcludeServers = Collections.emptyList();
    private List<String> backupExcludePaths = Collections.emptyList();
    private BackupSchedule backupSchedule;

    // Lifecycle
    private List<String> shutdownOnProxyExitList;

    // Lobby/Limbo balancing configuration
    private List<String> balancerLobbies;
    private List<String> balancerLimbos;
    private int balancerLobbiesToUse;
    private String balancerStrategyName;
    private int balancerHealthCheckInterval;
    private boolean balancerAutoScaleEnabled;
    private int balancerMinOnline;
    private int balancerMaxOnline;
    private int balancerPlayersPerServer;
    private double balancerCpuScaleUpThreshold;
    private int balancerPreStartThresholdPercent;
    private int balancerStartFailureFallbackSeconds;
    private int balancerStartFailureCooldownSeconds;
    private boolean sendToLimboOnStart;
    private ForcedHostOfflineBehavior forcedHostOfflineBehavior = ForcedHostOfflineBehavior.DISCONNECT;

    private final VelocityPteroPower plugin;
    private final Logger logger;
    private Map<String, PteroServerInfo> serverInfoMap;

     /**
     * Constructor for the ConfigurationManager class.
     *
     * @param plugin the VelocityPteroPower plugin instance
     */
    public ConfigurationManager(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.dataDirectory = plugin.getDataDirectory();
    }

    /**
     * This method loads the configuration from a YAML file.
     * It reads the configuration values and stores them in instance variables.
     */
    public void loadConfig(){
        try{
            config = YamlDocument.create(new File(this.dataDirectory.toFile(), "config.yml"),
                                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                                GeneralSettings.DEFAULT,
                                LoaderSettings.builder().setAutoUpdate(true).build(),
                                DumperSettings.DEFAULT,
                                UpdaterSettings.builder().setVersioning(new BasicVersioning("fileversion"))
                                        .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS)
                                        .setMergeRule(MergeRule.MAPPINGS, true)
                                        .setMergeRule(MergeRule.MAPPING_AT_SECTION, false)
                                        .setMergeRule(MergeRule.SECTION_AT_MAPPING, false)
                                        .addIgnoredRoute("5", "servers", '.')
                                        .addIgnoredRoute("6", "servers", '.')
                                        .addIgnoredRoute("7", "servers", '.')
                                        .addIgnoredRoute("8", "servers", '.')
                                        .addIgnoredRoute("9", "servers", '.')
                                        .addIgnoredRoute("10", "servers", '.')
                                        .addIgnoredRoute("11", "servers", '.')
                                        .addIgnoredRoute("12", "servers", '.')
                                        .addIgnoredRoute("13", "servers", '.')
                                        .addIgnoredRoute("14", "servers", '.')
                                        .addIgnoredRoute("15", "servers", '.')
                                        .addIgnoredRoute("16", "servers", '.')
                                        .build());


            checkUpdate = (boolean) config.get("checkUpdate", true);
            printRateLimit = (boolean) config.get("printRateLimit", false);
            serverNotFoundMessage = (boolean) config.get("serverNotFoundMessage", false);
            whitelistAllowBypass = (boolean) config.get("whitelistAllowBypass", true);
            languageOverride = config.getString("languageOverride", "auto");

            loggerLevel = (int) config.get("loggerLevel", 20);
            pingTimeout = (int) config.get("pingTimeout", 1000);
            apiThreads = (int) config.get("apiThreads", 10);
            shutdownRetryDelay = (int) config.get("shutdownRetryDelay", 30);
            shutdownRetries = (int) config.get("shutdownRetries", 3);
            idleStartShutdownTime = (int) config.get("idleStartShutdownTime", 300);
            playerCommandCooldown = (int) config.get("playerStartCooldown", 10);
            startupInitialCheckDelay = (int) config.get("startupInitialCheckDelay", 10);
            whitelistCheckInterval = (int) config.get("whitelistCheckInterval", 10);
            maxOnlineServers = (int) config.get("maxOnlineServers", 0);
            maxOnlineAllowBypass = (boolean) config.get("maxOnlineAllowBypass", true);
            maxOnlineExemptList = config.getStringList("maxOnlineExempt");
            countLobbiesInMaxOnline = (boolean) config.get("countLobbiesInMaxOnline", false);
            countLimbosInMaxOnline = (boolean) config.get("countLimbosInMaxOnline", false);
            alwaysOnlineList = config.getStringList("alwaysOnline");
            alwaysOnlineCheckInterval = (int) config.get("alwaysOnlineCheckInterval", 60);
            resourceCacheSeconds = (int) config.get("resourceCacheSeconds", 10);
            resourcePrefetchEnabled = (boolean) config.get("resourcePrefetchEnabled", true);
            idleShutdownCheckInterval = (int) config.get("idleShutdownCheckInterval", 60);

            // Move history section
            Section mh = config.getSection("moveHistory");
            if (mh != null) {
                moveHistoryEnabled = mh.getBoolean("enabled", false);
                moveHistoryMaxEntries = mh.getInt("maxEntriesPerPlayer", 50);
            } else {
                moveHistoryEnabled = false;
                moveHistoryMaxEntries = 50;
            }

            // Backups section (defaults are set at field declaration)
            Section backups = config.getSection("backups");
            if (backups != null) {
                backupsEnabled = backups.getBoolean("enabled", false);
                maxBackupsPerServer = backups.getInt("maxBackupsPerServer", 5);
                backupMinIntervalSeconds = backups.getInt("minIntervalSeconds", 60);
                pruneBackupsBeforeCreate = backups.getBoolean("pruneBeforeCreate", false);
                backupNamePrefix = backups.getString("namePrefix", "vpp-auto");
                backupExcludeServers = backups.getStringList("excludeServers");
                backupExcludePaths = backups.getStringList("excludePaths").stream()
                        .filter(path -> path != null && !path.isBlank())
                        .toList();
                if (backups.contains("events")) {
                    backupEvents = parseBackupEvents(backups.getStringList("events"), "backups.events");
                }
                backupSchedule = parseBackupSchedule(backups.getSection("schedule"), "backups.schedule", true);
            }

            // Lifecycle section
            Section lifecycle = config.getSection("lifecycle");
            if (lifecycle != null) {
                shutdownOnProxyExitList = lifecycle.getStringList("shutdownOnProxyExit");
            } else {
                shutdownOnProxyExitList = java.util.Collections.emptyList();
            }

            // Lobby/Limbo balancer section (all optional, sensible defaults)
            Section lb = config.getSection("lobbyBalancer");
            if (lb != null) {
                balancerLobbies = lb.getStringList("lobbies");
                balancerLimbos = lb.getStringList("limbos");
                balancerLobbiesToUse = lb.getInt("lobbiesToUse", 0);
                String strat = lb.getString("strategy", "ROUND_ROBIN");
                balancerStrategyName = strat != null ? strat : "ROUND_ROBIN";
                balancerHealthCheckInterval = lb.getInt("healthCheckInterval", 15);
                balancerAutoScaleEnabled = lb.getBoolean("autoScaleEnabled", true);
                balancerMinOnline = lb.getInt("minOnline", 1);
                balancerMaxOnline = lb.getInt("maxOnline", 0);
                balancerPlayersPerServer = lb.getInt("playersPerServer", 80);
                try {
                    balancerCpuScaleUpThreshold = lb.getDouble("cpuScaleUpThreshold", 85.0);
                } catch (Exception ex) {
                    balancerCpuScaleUpThreshold = 85.0;
                }
                balancerPreStartThresholdPercent = lb.getInt("preStartThresholdPercent", 80);
                balancerStartFailureFallbackSeconds = lb.getInt("startFailureFallbackSeconds", 60);
                balancerStartFailureCooldownSeconds = lb.getInt("startFailureCooldownSeconds", 120);
                sendToLimboOnStart = lb.getBoolean("sendToLimboOnStart", false);
                String fho = lb.getString("forcedHostOfflineBehavior", "DISCONNECT");
                try {
                    forcedHostOfflineBehavior = ForcedHostOfflineBehavior.valueOf(fho.toUpperCase(Locale.ROOT));
                } catch (Exception ex) {
                    forcedHostOfflineBehavior = ForcedHostOfflineBehavior.LOBBY_OR_LIMBO;
                    logger.warn("Invalid forcedHostOfflineBehavior '{}' in config. Defaulting to LOBBY_OR_LIMBO.", fho);
                }
            } else {
                // defaults
                balancerLobbies = Collections.emptyList();
                balancerLimbos = Collections.emptyList();
                balancerLobbiesToUse = 0;
                balancerStrategyName = "ROUND_ROBIN";
                balancerHealthCheckInterval = 15;
                balancerAutoScaleEnabled = true;
                balancerMinOnline = 1;
                balancerMaxOnline = 0;
                balancerPlayersPerServer = 80;
                balancerCpuScaleUpThreshold = 85.0;
                balancerPreStartThresholdPercent = 80;
                balancerStartFailureFallbackSeconds = 60;
                balancerStartFailureCooldownSeconds = 120;
                sendToLimboOnStart = false;
                forcedHostOfflineBehavior = ForcedHostOfflineBehavior.LOBBY_OR_LIMBO;
            }

            // Migrate legacy 'limboServer' to 'lobbyBalancer.limbos'
            try {
                String legacyLimbo = config.getString("limboServer");
                if (legacyLimbo != null && !legacyLimbo.isBlank() && !"changeMe".equalsIgnoreCase(legacyLimbo)) {
                    Section lbSec = config.getSection("lobbyBalancer");
                    if (lbSec == null) {
                        config.set("lobbyBalancer.limbos", java.util.List.of(legacyLimbo));
                    } else {
                        java.util.List<String> limbos = lbSec.getStringList("limbos");
                        if (limbos == null) limbos = new java.util.ArrayList<>();
                        boolean exists = false;
                        for (String s : limbos) { if (s.equalsIgnoreCase(legacyLimbo)) { exists = true; break; } }
                        if (!exists) limbos.add(0, legacyLimbo);
                        config.set("lobbyBalancer.limbos", limbos);
                    }
                    config.remove(Route.fromString("limboServer"));
                    config.save();
                    logger.info("Migrated legacy 'limboServer' to 'lobbyBalancer.limbos' and removed the old key.");
                }
            } catch (Exception ignored) {}

            stopAllIgnoreList = config.getStringList("stopIdleIgnore");

            String checkMethodStr = config.getString("serverStatusCheckMethod", "VELOCITY_PING");
            try {
                this.serverCheckMethod = ServerCheckMethod.valueOf(checkMethodStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.error("Invalid serverStatusCheckMethod '{}' in config. Using default 'VELOCITY_PING'.", checkMethodStr);
                this.serverCheckMethod = ServerCheckMethod.VELOCITY_PING;
            }

            Section pterodactylSection = config.getSection("pterodactyl");
            Map<String, Object> pterodactyl = new HashMap<>();
            if (pterodactylSection != null) {
                for (Object keyObj : pterodactylSection.getKeys()) {
                    String key = String.valueOf(keyObj);
                    Route route = Route.fromString(key);
                    Object value = pterodactylSection.get(route);
                    pterodactyl.put(key, value);
                }
            }
            panelUrl = (String) pterodactyl.get("url");
            if (!panelUrl.endsWith("/")) {
                panelUrl += "/";
            }
            apiKey = (String) pterodactyl.get("apiKey");
            panel = detectPanelType(apiKey);


            Section serversSection = config.getSection("servers");
                if (serversSection != null) {
                    serverInfoMap = processServerSection(serversSection);
                } else {
                    logger.error("Servers section not found in configuration.");
                }
                } catch (IOException e) {
                    logger.error("Error creating/loading configuration: " + e.getMessage());
                }
            }

    /**
     * This method processes the server section of the configuration.
     * It creates a map of server names to PteroServerInfo objects.
     *
     * @param serversSection the server section of the configuration
     * @return a map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> processServerSection(Section serversSection) {
            Map<String, PteroServerInfo> serverInfoMap = new HashMap<>();
            for (Object keyObj : serversSection.getKeys()) {
                String key = String.valueOf(keyObj);
                Route route = Route.fromString(key);
                Object serverInfoDataObj = serversSection.get(route);
                if (serverInfoDataObj instanceof Section) {
                    Section serverInfoDataSection = (Section) serverInfoDataObj;
                    try {
                        Object idObj = serverInfoDataSection.get("id");
                        if (idObj == null) {
                            throw new IllegalArgumentException("Missing 'id' for server '" + key + "'");
                        }

                        if (!(idObj instanceof String)) {
                            // YAML likely parsed an unquoted ID (e.g., 91e62747) as a number (scientific notation),
                            // which can overflow to Infinity. We cannot recover the original text.
                            throw new IllegalArgumentException("Invalid type for 'id' (" + idObj.getClass().getSimpleName() + ") at path 'servers." + key + ".id'. YAML parsed an unquoted value as a number. Edit your config and quote the ID, e.g.: id: \"91e62747\" then restart/reload.");
                        }

                        String id = ((String) idObj).trim();
                        if (id.equalsIgnoreCase("infinity") || id.equalsIgnoreCase("nan") || id.isEmpty()) {
                            throw new IllegalArgumentException("Invalid 'id' value '" + id + "' for server '" + key + "'. Quote the ID in YAML, e.g.: id: \"91e62747\"");
                        }

                        // Basic sanity check: IDs are typically short alphanumeric (pterodactyl short uuid)
                        if (!id.matches("^[A-Za-z0-9_-]{4,64}$")) {
                            logger.warn("Suspicious server id '{}' for server '{}'. Expected alphanumeric/underscore/dash. Continuing anyway.", id, key);
                        }

                        if (!Objects.equals(id, "1234abcd")){
                            int timeout = serverInfoDataSection.getInt("timeout", -1);
                            int startupJoinDelay = serverInfoDataSection.getInt("startupJoinDelay", 10);
                            boolean whitelist = serverInfoDataSection.getBoolean("whitelist", false);
                            BackupPolicy backupPolicy = parseBackupPolicy(serverInfoDataSection, key);
                            serverInfoMap.put(key, new PteroServerInfo(id, timeout, startupJoinDelay, whitelist, backupPolicy));
                            logger.info("Registered Server: " + id + " successfully");
                        }
                    } catch (Exception e) {
                        logger.warn("Error processing server '" + key + "': " + e.getMessage());
                    }
                }
            }
            return serverInfoMap;
        }

    /**
     * Parses the optional per-server 'backups' section into a {@link BackupPolicy}.
     * Unset fields stay null and inherit the global 'backups' config.
     *
     * @param serverSection the section of a single server under 'servers'
     * @param serverKey the server name (for logging)
     * @return the parsed policy, or null if the server has no 'backups' section
     */
    private BackupPolicy parseBackupPolicy(Section serverSection, String serverKey) {
        Section section = serverSection.getSection("backups");
        if (section == null) return null;

        return new BackupPolicy(
                section.getOptionalBoolean("enabled").orElse(null),
                section.getOptionalStringList("events")
                        .map(events -> parseBackupEvents(events, "servers." + serverKey + ".backups.events"))
                        .orElse(null),
                section.getOptionalInt("maxBackups").orElse(null),
                section.getOptionalInt("minIntervalSeconds").orElse(null),
                parseBackupSchedule(section.getSection("schedule"), "servers." + serverKey + ".backups.schedule", false));
    }

    /**
     * Parses an optional 'schedule' section (global or per-server) into a {@link BackupSchedule}.
     * Returns null when there is no schedule section. An enabled schedule with an invalid
     * cron expression or timezone is logged and returned disabled, so a typo can never
     * silently schedule backups at the wrong time — or crash config loading.
     *
     * @param section the schedule section, or null
     * @param configPath the config path, for log messages
     * @param global whether this is the global schedule (only it has a 'servers' list)
     * @return the parsed schedule, or null if there is no schedule section
     */
    private BackupSchedule parseBackupSchedule(Section section, String configPath, boolean global) {
        if (section == null) return null;
        Boolean enabled = section.getOptionalBoolean("enabled").orElse(null);
        String cron = section.contains("cron") ? section.getString("cron") : null;
        String timezone = section.contains("timezone") ? section.getString("timezone") : null;
        List<String> servers = global ? section.getStringList("servers") : null;
        if (Boolean.TRUE.equals(enabled)) {
            try {
                if (cron == null || cron.isBlank()) {
                    throw new IllegalArgumentException("no cron expression set");
                }
                CronExpression.parse(cron);
            } catch (IllegalArgumentException ex) {
                logger.error("Invalid cron expression '{}' in '{}': {} — scheduled backups disabled for it.",
                        cron, configPath, ex.getMessage());
                return new BackupSchedule(false, cron, timezone, servers);
            }
            try {
                ZoneId.of(timezone != null && !timezone.isBlank() ? timezone.trim() : "UTC");
            } catch (Exception ex) {
                logger.error("Invalid timezone '{}' in '{}': {} — scheduled backups disabled for it.",
                        timezone, configPath, ex.getMessage());
                return new BackupSchedule(false, cron, timezone, servers);
            }
        }
        return new BackupSchedule(enabled, cron, timezone, servers);
    }

    private Set<BackupEvent> parseBackupEvents(List<String> rawEvents, String configPath) {
        Set<BackupEvent> events = EnumSet.noneOf(BackupEvent.class);
        for (String raw : rawEvents) {
            BackupEvent event = BackupEvent.fromString(raw);
            if (event != null) {
                events.add(event);
            } else {
                logger.warn("Unknown backup event '{}' in '{}' (valid: server_start, server_stop, server_join, server_leave, network_join, network_leave).",
                        raw, configPath);
            }
        }
        return events;
    }

    private PanelType detectPanelType(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
          return PanelType.pterodactyl;
        }

        String prefix = apiKey.split("_")[0];
        return switch (prefix) {
            case "ptlc" -> {
                logger.debug("Detected pterodactyl panel from apiKey");
                yield PanelType.pterodactyl;
            }
            case "c7sp" -> {
                // Calagopus uses the Pterodactyl-compatible client API (/api/client/... with Bearer auth)
                logger.debug("Detected Calagopus panel from apiKey (Pterodactyl-compatible API)");
                yield PanelType.pterodactyl;
            }
            case "plcn", "pacc"-> {
                logger.debug("Detected pelican panel from apiKey");
                yield PanelType.pelican;
            }
            case "ptla", "peli" -> {
                logger.debug("Detected Application Api Key please change this to a client key");
                yield PanelType.error;
            }
            default -> {
                logger.warn("API Key has no recognized prefix ('{}'), assuming mcServerSoft. If you use Pterodactyl/Pelican/Calagopus, use a client API key (ptlc_/plcn_/pacc_/c7sp_).", prefix);
                yield PanelType.mcServerSoft;
            }
        };
    }

    /**
     * This method returns the map of server names to PteroServerInfo objects.
     *
     * @return the map of server names to PteroServerInfo objects
     */
    public Map<String, PteroServerInfo> getServerInfoMap() {
        return serverInfoMap;
    }

    // Balancer getters
    public List<String> getBalancerLobbies() { return balancerLobbies == null ? Collections.emptyList() : balancerLobbies; }
    public List<String> getBalancerLimbos() { return balancerLimbos == null ? Collections.emptyList() : balancerLimbos; }
    public int getBalancerLobbiesToUse() { return balancerLobbiesToUse; }
    public String getBalancerStrategyName() { return balancerStrategyName == null ? "ROUND_ROBIN" : balancerStrategyName; }
    public int getBalancerHealthCheckInterval() { return balancerHealthCheckInterval <= 0 ? 15 : balancerHealthCheckInterval; }
    public boolean isBalancerAutoScaleEnabled() { return balancerAutoScaleEnabled; }
    public int getBalancerMinOnline() { return Math.max(0, balancerMinOnline); }
    public int getBalancerMaxOnline() { return Math.max(0, balancerMaxOnline); }
    public int getBalancerPlayersPerServer() { return Math.max(1, balancerPlayersPerServer); }
    public double getBalancerCpuScaleUpThreshold() { return balancerCpuScaleUpThreshold <= 0 ? 85.0 : balancerCpuScaleUpThreshold; }
    public int getBalancerPreStartThresholdPercent() { return balancerPreStartThresholdPercent <= 0 ? 80 : Math.min(100, balancerPreStartThresholdPercent); }
    public int getBalancerStartFailureFallbackSeconds() { return balancerStartFailureFallbackSeconds <= 0 ? 60 : balancerStartFailureFallbackSeconds; }
    public int getBalancerStartFailureCooldownSeconds() { return balancerStartFailureCooldownSeconds <= 0 ? 120 : balancerStartFailureCooldownSeconds; }
    public boolean isSendToLimboOnStart() { return sendToLimboOnStart; }
    public ForcedHostOfflineBehavior getForcedHostOfflineBehavior() { return forcedHostOfflineBehavior; }

    /**
     * This method returns the Pterodactyl URL.
     *
     * @return the Pterodactyl URL
     */
    public String getPterodactylUrl() {
        return panelUrl;
    }

    /**
     * This method returns the Pterodactyl API key.
     *
     * @return the Pterodactyl API key
     */
    public String getPterodactylApiKey() {
        return apiKey;
    }

    /**
     * This method returns whether to check for updates.
     *
     * @return true if updates should be checked, false otherwise
     */
    public boolean isCheckUpdate() {
        return checkUpdate;
    }

    public boolean isServerNotFoundMessage() {
        return serverNotFoundMessage;
    }

    public boolean isWhitelistAllowBypass() {
        return whitelistAllowBypass;
    }

    public PanelType getPanelType(){
        return panel;
    }

    public Level getLoggerLevel() {
        try {
            return Level.intToLevel(loggerLevel);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid logger level: {}. Defaulting to INFO.", loggerLevel);
            return Level.INFO;
        }
    }

    public int getApiThreads() {
        return apiThreads;
    }

    public boolean isPrintRateLimit() {
        return printRateLimit;
    }

    public String getLanguageOverride() {
        return languageOverride;
    }

    public int getPingTimeout() {
        return pingTimeout;
    }

    public int getShutdownRetries() {
        return shutdownRetries;
    }

    public int getShutdownRetryDelay(){
        return shutdownRetryDelay;
    }

    public int getPlayerCommandCooldown() {
        return playerCommandCooldown;
    }
    public int getIdleStartShutdownTime(){
        return idleStartShutdownTime;
    }

    public  int getStartupInitialCheckDelay(){
        return startupInitialCheckDelay;
    }

    public int getWhitelistCheckInterval(){
        return whitelistCheckInterval;
    }


    public List<String> getStopAllIgnoreList() {
        return stopAllIgnoreList;
    }

    public ServerCheckMethod getServerCheckMethod() {
        return serverCheckMethod;
    }
    
    public int getMaxOnlineServers() {
        return maxOnlineServers;
    }

    public boolean isMaxOnlineAllowBypass() {
        return maxOnlineAllowBypass;
    }

    public java.util.List<String> getMaxOnlineExemptList() {
        return maxOnlineExemptList == null ? java.util.Collections.emptyList() : maxOnlineExemptList;
    }

    public boolean isCountLobbiesInMaxOnline() { return countLobbiesInMaxOnline; }
    public boolean isCountLimbosInMaxOnline() { return countLimbosInMaxOnline; }

    public java.util.List<String> getAlwaysOnlineList() {
        return alwaysOnlineList == null ? java.util.Collections.emptyList() : alwaysOnlineList;
    }

    public int getAlwaysOnlineCheckInterval() {
        return alwaysOnlineCheckInterval;
    }

    public int getResourceCacheSeconds() {
        return Math.max(0, resourceCacheSeconds);
    }

    public boolean isResourcePrefetchEnabled() {
        return resourcePrefetchEnabled;
    }

    public int getIdleShutdownCheckInterval() {
        return Math.max(0, idleShutdownCheckInterval);
    }

    public boolean isMoveHistoryEnabled() { return moveHistoryEnabled; }
    public int getMoveHistoryMaxEntries() { return Math.max(1, moveHistoryMaxEntries); }

    public boolean isBackupsEnabled() { return backupsEnabled; }

    /** The global cron schedule for automatic backups, or null if none is configured. */
    public BackupSchedule getBackupSchedule() { return backupSchedule; }

    /** Whether any global cron schedule is enabled (per-server schedules are checked separately). */
    public boolean isBackupScheduleEnabled() {
        return backupSchedule != null && Boolean.TRUE.equals(backupSchedule.enabled());
    }

    public Set<BackupEvent> getBackupEvents() {
        return backupEvents == null ? Collections.emptySet() : backupEvents;
    }

    /** Maximum number of automatic backups kept per server. 0 or less = unlimited (no deletion). */
    public int getMaxBackupsPerServer() { return maxBackupsPerServer; }

    /** Minimum seconds between two automatic backups of the same server. 0 or less = no throttle. */
    public int getBackupMinIntervalSeconds() { return backupMinIntervalSeconds; }

    /** Whether old automatic backups are pruned before the new backup is created (instead of after). */
    public boolean isPruneBackupsBeforeCreate() { return pruneBackupsBeforeCreate; }

    public String getBackupNamePrefix() {
        return (backupNamePrefix == null || backupNamePrefix.isBlank()) ? "vpp-auto" : backupNamePrefix;
    }

    public List<String> getBackupExcludeServers() {
        return backupExcludeServers == null ? Collections.emptyList() : backupExcludeServers;
    }

    /** Glob paths excluded from automatic backup archives (Pterodactyl/Pelican 'ignored' field). */
    public List<String> getBackupExcludePaths() {
        return backupExcludePaths == null ? Collections.emptyList() : backupExcludePaths;
    }

    /**
     * Checks whether a server name matches any of the configured 'backups.excludeServers'
     * glob patterns (case-insensitive, '*' matches any sequence, '?' any single char).
     *
     * @param serverName the Velocity name of the server
     * @return true if the server is excluded from automatic backups
     */
    public boolean isBackupExcluded(String serverName) {
        if (serverName == null || backupExcludeServers == null) return false;
        String name = serverName.toLowerCase(Locale.ROOT);
        for (String pattern : backupExcludeServers) {
            if (pattern == null || pattern.isBlank()) continue;
            if (globMatches(pattern.trim().toLowerCase(Locale.ROOT), name)) return true;
        }
        return false;
    }

    private static boolean globMatches(String glob, String value) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\');
                    regex.append(c);
                }
            }
        }
        return value.matches(regex.toString());
    }

    public java.util.List<String> getShutdownOnProxyExitList() {
        return shutdownOnProxyExitList == null ? java.util.Collections.emptyList() : shutdownOnProxyExitList;
    }
}