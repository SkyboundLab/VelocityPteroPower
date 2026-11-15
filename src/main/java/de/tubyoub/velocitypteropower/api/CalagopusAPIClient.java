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

public class CalagopusAPIClient extends PterodactylAPIClient {

    public CalagopusAPIClient(VelocityPteroPower plugin) {
        super(plugin);
    }

    @Override
    public CompletableFuture<Boolean> createBackup(String serverId, String backupName, List<String> excludedPaths) {
        return CompletableFuture.supplyAsync(() -> {
            if (isBlank(serverId) || !canMakeRequest("backup creation", serverId)) return false;
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                if (!isBlank(backupName)) {
                    payload.put("name", backupName);
                }
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
                    JsonNode pageNode = root.path("backups");
                    if (pageNode.isMissingNode() || pageNode.isNull()) {
                        pageNode = root;
                    }
                    for (JsonNode backup : pageNode.path("data")) {
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
