/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.api;

import de.tubyoub.velocitypteropower.model.BackupInfo;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface defining the contract for interacting with a Panel's API (Pterodactyl or Pelican).
 * Provides methods for controlling server power state and querying status.
 */
public interface PanelAPIClient {
    /**
     * Sends a power signal (start, stop, restart, kill) to the specified server.
     * Implementations should handle API requests and rate limiting updates.
     *
     * @param serverId The unique identifier of the server on the panel.
     * @param signal   The power action to perform (START, STOP, RESTART, KILL).
     */
    void powerServer(String serverId, PowerSignal signal);
    /**
     * Checks if a server is currently online and running according to the configured method.
     * This might involve pinging the server via Velocity or querying the Panel API.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @param serverId   The unique identifier of the server on the panel.
     * @return {@code true} if the server is considered online, {@code false} otherwise.
     */
    boolean isServerOnline(String serverName, String serverId);
    /**
     * Checks if a server registered in Velocity currently has any players connected.
     *
     * @param serverName The name of the server as registered in Velocity.
     * @return {@code true} if the server has no players connected or is not found in Velocity, {@code false} otherwise.
     */
    boolean isServerEmpty(String serverName);
    /**
     * Fetch the whitelist.json file content from a server
     * @param serverId The server ID
     * @return A CompletableFuture containing the whitelist JSON as a string
     */
    CompletableFuture<String> fetchWhitelistFile(String serverId);
    /**
     * Fetch live resource usage for a server, if supported by the panel implementation.
     * Implementations may return an "unavailable" object if not supported.
     * @param serverId The server ID on the panel
     * @return A future with resource usage information
     */
    CompletableFuture<de.tubyoub.velocitypteropower.model.ServerResourceUsage> fetchServerResources(String serverId);
    /**
     * Creates a backup of a server on the panel.
     * Default implementation reports "unsupported" (used by panels without a backup API, e.g. Mc Server Soft).
     *
     * @param serverId   The unique identifier of the server on the panel.
     * @param backupName The name for the new backup (may be null/blank to let the panel choose).
     * @return A future that completes with {@code true} if the backup creation was accepted.
     */
    default CompletableFuture<Boolean> createBackup(String serverId, String backupName) {
        return CompletableFuture.completedFuture(false);
    }
    /**
     * Lists the backups of a server on the panel.
     * Default implementation returns an empty list (unsupported panels).
     *
     * @param serverId The unique identifier of the server on the panel.
     * @return A future with the backups of the server (possibly empty, never null).
     */
    default CompletableFuture<List<BackupInfo>> listBackups(String serverId) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
    /**
     * Deletes a backup of a server on the panel.
     * Default implementation reports "unsupported".
     *
     * @param serverId   The unique identifier of the server on the panel.
     * @param backupUuid The unique identifier of the backup to delete.
     * @return A future that completes with {@code true} if the backup was deleted.
     */
    default CompletableFuture<Boolean> deleteBackup(String serverId, String backupUuid) {
        return CompletableFuture.completedFuture(false);
    }
    /**
    * Checks if the ApiKey is valid
    * @param apiKey ApiKey which need to be checked if its valid
     * @return {@code true} apiKey is considered valid {@code false} apiKey is considered invalid
    */
    boolean isApiKeyValid(String apiKey);
    /**
     * Shuts down any resources used by the API client, such as thread pools.
     * Should be called when the plugin is disabled.
     */
    void shutdown();
}
