package de.tubyoub.velocitypteropower.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import de.tubyoub.velocitypteropower.model.BackupInfo;
import de.tubyoub.velocitypteropower.util.RateLimitTracker;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

public abstract class AbstractPanelAPIClient implements PanelAPIClient {

    protected static class CacheEntry {
        final long timestamp;
        final de.tubyoub.velocitypteropower.model.ServerResourceUsage data;
        CacheEntry(long timestamp, de.tubyoub.velocitypteropower.model.ServerResourceUsage data) {
            this.timestamp = timestamp;
            this.data = data;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    // per-server resource usage cache
    protected final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> resourceCache = new java.util.concurrent.ConcurrentHashMap<>();

    protected final Logger logger;
    protected final ConfigurationManager configurationManager;
    protected final ProxyServer proxyServer;
    protected final VelocityPteroPower plugin;
    protected final RateLimitTracker rateLimitTracker;

    protected final HttpClient httpClient;
    protected final ExecutorService executorService;

    /**
     * Constructs a base API client with common dependencies.
     *
     * @param plugin The main VelocityPteroPower plugin instance.
     */
    public AbstractPanelAPIClient(VelocityPteroPower plugin) {
        this.plugin = plugin;
        this.logger = plugin.getFilteredLogger();
        this.configurationManager = plugin.getConfigurationManager();
        this.proxyServer = plugin.getProxyServer();
        this.rateLimitTracker = plugin.getRateLimitTracker();

        int configuredThreads = configurationManager.getApiThreads();
        int servers = 0;
        try {
            java.util.Map<String, de.tubyoub.velocitypteropower.model.PteroServerInfo> map = plugin.getServerInfoMap();
            if (map != null) servers = map.size();
        } catch (Exception ignored) {}
        int computedThreads = Math.max(configuredThreads, Math.max(4, Math.min(64, servers * 2)));
        this.executorService = java.util.concurrent.Executors.newFixedThreadPool(computedThreads);
        logger.info("API executor threads: configured={}, servers={}, using={}", configuredThreads, servers, computedThreads);
        this.httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Checks if a server is online using the configured method (Velocity Ping or Panel API).
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The panel server identifier (UUID).
     * @return {@code true} if the server is considered online, {@code false} otherwise.
     */
    @Override
    public boolean isServerOnline(String serverName, String serverId) {
        ConfigurationManager.ServerCheckMethod method =
            configurationManager.getServerCheckMethod();

        switch (method) {
            case VELOCITY_PING:
                return checkOnlineViaVelocityPing(serverName);
            case PANEL_API:
                return checkOnlineViaPanelApi(serverName, serverId);
            default:
                // Should not happen with enum, but just in case
                logger.error(
                    "Unknown ServerCheckMethod: {}. Defaulting to false.",
                    method
                );
                return false;
        }
    }

    /**
     * Checks if a server is online by pinging it through Velocity.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server responds to ping, {@code false} otherwise.
     */
    protected boolean checkOnlineViaVelocityPing(String serverName) {
        Optional<RegisteredServer> serverOptional =
            proxyServer.getServer(serverName);
        if (serverOptional.isEmpty()) {
            logger.debug(
                "Cannot perform PING check: Server '{}' not registered in Velocity.",
                serverName
            );
            return false; // Server not known to Velocity
        }

        RegisteredServer server = serverOptional.get();
        try {
            CompletableFuture<ServerPing> pingFuture = server.ping();
            ServerPing pingResult = pingFuture.get(
                configurationManager.getPingTimeout(),
                TimeUnit.MILLISECONDS
            );
            boolean online = pingResult != null;
            logger.debug(
                "Ping check for {}: {}",
                serverName,
                online ? "Success" : "Failed (No result/Timeout)"
            );
            return online;
        } catch (TimeoutException e) {
            logger.debug(
                "Ping check for {} timed out after {}ms.",
                serverName,
                configurationManager.getPingTimeout()
            );
            return false;
        } catch (ExecutionException e) {
            // Log the underlying cause if possible
            Throwable cause = e.getCause();
            logger.debug(
                "Ping check for {} failed: {}",
                serverName,
                cause != null ? cause.getMessage() : e.getMessage()
            );
            return false;
        } catch (InterruptedException e) {
            logger.warn("Ping check for {} interrupted.", serverName);
            Thread.currentThread().interrupt(); // Re-interrupt the thread
            return false;
        } catch (Exception e) { // Catch unexpected errors during ping
            logger.warn(
                "Unexpected error pinging server {}: {}",
                serverName,
                e.getMessage(),
                e
            );
            return false;
        }
    }

    /**
     * Checks if a server is online by querying the panel API.
     * This method should be implemented by each specific API client.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The panel server identifier (UUID).
     * @return {@code true} if the server is online according to the panel, {@code false} otherwise.
     */
    protected abstract boolean checkOnlineViaPanelApi(String serverName, String serverId);

    /**
     * Checks if a server registered in Velocity has any players connected.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server has no players or is not found, {@code false} otherwise.
     */
    @Override
    public boolean isServerEmpty(String serverName) {
        return proxyServer.getServer(serverName)
            .map(server -> server.getPlayersConnected().isEmpty())
            .orElse(true); // Treat non-existent server as empty
    }

    /**
     * Shuts down the executor service used for API requests.
     */
    public void shutdown() {
        executorService.shutdownNow();
    }

    /**
     * Creates a backup for a server using the Pterodactyl/Pelican client API.
     */
    protected CompletableFuture<Boolean> createBackupRequest(String serverId, String backupName) {
        return CompletableFuture.supplyAsync(() -> {
            if (isBlank(serverId) || !canMakeRequest("backup creation", serverId)) return false;
            try {
                String body = isBlank(backupName) ? "{}" : JSON.writeValueAsString(Map.of("name", backupName));
                HttpRequest request = clientApiRequest(serverId, "/backups")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = send(request);
                boolean ok = isSuccess(response.statusCode());
                if (ok) {
                    logger.debug("Backup creation accepted for {} (HTTP {}).", serverId, response.statusCode());
                } else {
                    logger.warn("Backup creation for {} failed with HTTP {}: {}",
                            serverId, response.statusCode(), abbreviate(response.body()));
                }
                return ok;
            } catch (Exception e) {
                logger.error("Error creating backup for {}: {}", serverId, e.toString());
                return false;
            }
        }, executorService);
    }

    /**
     * Lists all backups of a server using the Pterodactyl/Pelican client API.
     */
    protected CompletableFuture<List<BackupInfo>> listBackupsRequest(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            List<BackupInfo> backups = new ArrayList<>();
            if (isBlank(serverId) || !canMakeRequest("backup list", serverId)) return backups;
            try {
                for (int page = 1; page <= 50; page++) {
                    HttpRequest request = clientApiRequest(serverId, "/backups?per_page=100&page=" + page)
                            .GET()
                            .timeout(Duration.ofSeconds(15))
                            .build();
                    HttpResponse<String> response = send(request);
                    if (response.statusCode() != 200 || response.body() == null) {
                        logger.warn("Backup list for {} (page {}) failed with HTTP {}", serverId, page, response.statusCode());
                        break;
                    }
                    JsonNode root = JSON.readTree(response.body());
                    for (JsonNode backup : root.path("data")) {
                        JsonNode attributes = backup.path("attributes");
                        String uuid = attributes.path("uuid").asText(null);
                        if (uuid != null) {
                            backups.add(new BackupInfo(
                                    uuid,
                                    attributes.path("name").asText(null),
                                    parseTimestamp(attributes.path("created_at").asText(null))));
                        }
                    }
                    if (page >= root.path("meta").path("pagination").path("total_pages").asInt(1)) break;
                    if (!rateLimitTracker.canMakeRequest()) break;
                }
            } catch (Exception e) {
                logger.error("Error listing backups for {}: {}", serverId, e.toString());
            }
            return backups;
        }, executorService);
    }

    /**
     * Deletes a backup of a server using the Pterodactyl/Pelican client API.
     */
    protected CompletableFuture<Boolean> deleteBackupRequest(String serverId, String backupUuid) {
        return CompletableFuture.supplyAsync(() -> {
            if (isBlank(serverId) || isBlank(backupUuid) || !canMakeRequest("backup deletion", serverId)) return false;
            try {
                HttpRequest request = clientApiRequest(serverId, "/backups/" + backupUuid)
                        .DELETE()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = send(request);
                boolean ok = isSuccess(response.statusCode());
                if (ok) {
                    logger.debug("Backup {} deleted for {} (HTTP {}).", backupUuid, serverId, response.statusCode());
                } else {
                    logger.warn("Backup deletion for {}/{} failed with HTTP {}", serverId, backupUuid, response.statusCode());
                }
                return ok;
            } catch (Exception e) {
                logger.error("Error deleting backup {} for {}: {}", backupUuid, serverId, e.toString());
                return false;
            }
        }, executorService);
    }

    private HttpRequest.Builder clientApiRequest(String serverId, String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(configurationManager.getPterodactylUrl() + "api/client/servers/" + serverId + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + configurationManager.getPterodactylApiKey());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        rateLimitTracker.updateRateLimitInfo(response);
        return response;
    }

    private boolean canMakeRequest(String action, String serverId) {
        if (rateLimitTracker.canMakeRequest()) return true;
        logger.warn("Rate limit reached. Skipping {} for {}.", action, serverId);
        return false;
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String abbreviate(String value) {
        return value == null ? "null" : value.substring(0, Math.min(value.length(), 200));
    }

    private static long parseTimestamp(String iso) {
        if (isBlank(iso)) return 0L;
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * Helper to fetch resource usage with per-server caching.
     */
    protected java.util.concurrent.CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> fetchWithCache(
            String serverId,
            java.util.function.Supplier<de.tubyoub.velocitypteropower.model.ServerResourceUsage> supplier
    ) {
        int ttl = configurationManager.getResourceCacheSeconds();
        long now = System.currentTimeMillis();
        if (ttl > 0) {
            CacheEntry e = resourceCache.get(serverId);
            if (e != null) {
                long age = now - e.timestamp;
                if (age < ttl * 1000L) {
                    logger.debug("Resource cache HIT for {} (age={}ms, ttl={}s).", serverId, age, ttl);
                    return java.util.concurrent.CompletableFuture.completedFuture(e.data);
                } else {
                    logger.debug("Resource cache EXPIRED for {} (age={}ms > ttl={}s).", serverId, age, ttl);
                }
            } else {
                logger.debug("Resource cache MISS for {} (ttl={}s).", serverId, ttl);
            }
        } else {
            logger.debug("Resource cache DISABLED (ttl=0) for {}.", serverId);
        }
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                de.tubyoub.velocitypteropower.model.ServerResourceUsage data = supplier.get();
                if (ttl > 0 && data != null) {
                    resourceCache.put(serverId, new CacheEntry(System.currentTimeMillis(), data));
                    logger.debug("Resource cache STORE for {} (ttl={}s).", serverId, ttl);
                }
                return data == null ? de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable() : data;
            } catch (Exception ex) {
                logger.error("Error fetching resources for {}: {}", serverId, ex.toString());
                return de.tubyoub.velocitypteropower.model.ServerResourceUsage.unavailable();
            }
        }, executorService);
    }
}
