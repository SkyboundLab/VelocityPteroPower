/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import de.tubyoub.velocitypteropower.VelocityPteroPower;
import de.tubyoub.velocitypteropower.manager.BackupManager;
import de.tubyoub.velocitypteropower.model.BackupEvent;

/**
 * Triggers backups for player join/leave events on managed servers.
 *
 * Network events describe joining/leaving the proxy, while server events describe
 * joining/leaving a specific backend server. On the first server connection after
 * login both apply; on disconnect both apply as well. {@link BackupManager} deduplicates
 * them, so at most one backup is created per server at a time.
 */
public class BackupEventListener {

    private final VelocityPteroPower plugin;

    public BackupEventListener(VelocityPteroPower plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        BackupManager backups = plugin.getBackupManager();
        if (backups == null) return;

        String serverName = event.getServer().getServerInfo().getName();
        boolean firstServer = event.getPreviousServer().isEmpty();
        if (firstServer) {
            // First backend connection after login = joined the network
            backups.triggerBackup(serverName, BackupEvent.NETWORK_JOIN);
        }
        backups.triggerBackup(serverName, BackupEvent.SERVER_JOIN);

        event.getPreviousServer().ifPresent(previous -> {
            String previousName = previous.getServerInfo().getName();
            if (!previousName.equalsIgnoreCase(serverName)) {
                backups.triggerBackup(previousName, BackupEvent.SERVER_LEAVE);
            }
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        BackupManager backups = plugin.getBackupManager();
        if (backups == null) return;

        event.getPlayer().getCurrentServer().ifPresent(connection -> {
            String serverName = connection.getServer().getServerInfo().getName();
            backups.triggerBackup(serverName, BackupEvent.NETWORK_LEAVE);
            backups.triggerBackup(serverName, BackupEvent.SERVER_LEAVE);
        });
    }
}
