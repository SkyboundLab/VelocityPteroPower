/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.api;

import com.fasterxml.jackson.databind.JsonNode;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.model.BackupInfo;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of {@link PanelAPIClient} for Calagopus panels.
 *
 * <p>Calagopus exposes a Pterodactyl-compatible client API for most calls,
 * so this class extends {@link PterodactylAPIClient} and only overrides
 * the backup endpoints that differ:
 *
 * <ul>
 *   <li>Creating a backup: Calagopus (Rust/serde) requires an
 *       {@code ignored_files} JSON <em>array</em> in the payload — the field
 *       must be present even when empty. Pterodactyl's optional
 *       {@code ignored} newline-joined string is not accepted, and omitting
 *       the field fails with HTTP 400
 *       {@code "invalid payload: missing field `ignored_files`"}.
 *       See {@code backend/src/routes/api/client/servers/_server_/backups/mod.rs}
 *       in calagopus/panel ({@code post::Payload}).</li>
 *   <li>Listing backups: Calagopus nests the pagination under a
 *       {@code backups} key instead of returning it at the document root,
 *       so the Pterodactyl list parser would find nothing.</li>
 * </ul>
 *
 * <p>Permissions are also granular per endpoint: listing needs
 * {@code backups.read}, creating needs {@code backups.create} and pruning
 * needs {@code backups.delete}. A key missing one of those gets HTTP 403
 * on that call only — the other calls still work.
 */
public class CalagopusAPIClient extends PterodactylAPIClient {

    public CalagopusAPIClient(VelocityPteroPower plugin) {
        super(plugin);
    }

    /**
     * Creates a backup with Calagopus's payload shape: {@code name} plus a
     * mandatory {@code ignored_files} array (empty when nothing is excluded).
     */
    @Override
    public CompletableFuture<Boolean> createBackup(String serverId, String backupName, List<String> excludedPaths) {
        return CompletableFuture.supplyAsync(() -> {
            if (isBlank(serverId) || !canMakeRequest("backup creation", serverId)) return false;
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                if (!isBlank(backupName)) {
                    payload.put("name", backupName);
                }
                // Required by Calagopus even when empty — serde rejects a
                // missing field, and it must be an array, not a string.
                payload.put("ignored_files", excludedPaths == null ? List.of() : new ArrayList<>(excludedPaths));
                String body = JSON.writeValueAsString(payload);
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
     * Lists backups in Calagopus's shape: the page is nested under a
     * {@code backups} key, there is no {@code total_pages} (it is derived
     * from {@code total}/{@code per_page}), and each backup is a flat
     * {@code ApiServerBackup} ({@code uuid}/{@code name}/{@code created} —
     * not {@code attributes.*}/{@code created_at} as in Pterodactyl).
     */
    @Override
    public CompletableFuture<List<BackupInfo>> listBackups(String serverId) {
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
                    // Calagopus nests the page under "backups"; fall back to
                    // the Pterodactyl shape (data at the document root).
                    JsonNode pageNode = root.path("backups");
                    if (pageNode.isMissingNode() || pageNode.isNull()) {
                        pageNode = root;
                    }
                    for (JsonNode backup : pageNode.path("data")) {
                        // Calagopus items are flat; Pterodactyl nests fields
                        // under "attributes".
                        JsonNode attributes = backup.path("attributes");
                        JsonNode node = attributes.isMissingNode() ? backup : attributes;
                        String uuid = node.path("uuid").asText(null);
                        if (uuid != null) {
                            String created = node.path("created").asText(null);
                            if (created == null) created = node.path("created_at").asText(null);
                            backups.add(new BackupInfo(
                                    uuid,
                                    node.path("name").asText(null),
                                    parseTimestamp(created)));
                        }
                    }
                    int total = pageNode.path("total").asInt(-1);
                    int perPage = pageNode.path("per_page").asInt(100);
                    int totalPages;
                    if (total >= 0 && perPage > 0) {
                        totalPages = (total + perPage - 1) / perPage;
                    } else {
                        totalPages = pageNode.path("meta").path("pagination").path("total_pages").asInt(1);
                    }
                    if (page >= totalPages) break;
                    if (!rateLimitTracker.canMakeRequest()) break;
                }
            } catch (Exception e) {
                logger.error("Error listing backups for {}: {}", serverId, e.toString());
            }
            return backups;
        }, executorService);
    }
}
