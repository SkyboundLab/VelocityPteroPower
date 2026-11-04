/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.model;

/**
 * This class represents the server information for a Pterodactyl server.
 * It includes the server ID, timeout, and join delay.
 */
public  class PteroServerInfo {
    private final String serverId;
    private final int timeout;
    private final int joinDelay;
    private final boolean whitelist;
    private final BackupPolicy backupPolicy;

    /**
     * Constructor for the PteroServerInfo class.
     *
     * @param serverId the ID of the server
     * @param timeout the timeout for the server
     * @param joinDelay the join delay for the server
     */
    public PteroServerInfo(String serverId, int timeout, int joinDelay, boolean whitelist) {
        this(serverId, timeout, joinDelay, whitelist, null);
    }

    /**
     * Constructor for the PteroServerInfo class with a per-server backup policy.
     *
     * @param serverId the ID of the server
     * @param timeout the timeout for the server
     * @param joinDelay the join delay for the server
     * @param whitelist whether whitelist enforcement is enabled
     * @param backupPolicy per-server backup policy (null = use global backups config)
     */
    public PteroServerInfo(String serverId, int timeout, int joinDelay, boolean whitelist, BackupPolicy backupPolicy) {
        this.serverId = serverId;
        this.timeout = timeout;
        this.joinDelay = joinDelay;
        this.whitelist = whitelist;
        this.backupPolicy = backupPolicy;
    }

    /**
     * This method returns the server ID.
     *
     * @return the server ID
     */
    public String getServerId() {
        return serverId;
    }

    /**
     * This method returns the timeout for the server.
     *
     * @return the timeout for the server
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * This method returns the join delay for the server.
     *
     * @return the join delay for the server
     */
    public int getJoinDelay() {
        return joinDelay;
    }
    public boolean isWhitelistEnabled() {
        return whitelist;
    }

    /**
     * @return the per-server backup policy, or null if the global backups config applies.
     */
    public BackupPolicy getBackupPolicy() {
        return backupPolicy;
    }
}