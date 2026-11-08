/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.api;

public enum PanelType {
    pterodactyl,
    pelican,
    /**
     * Calagopus. Its client API is Pterodactyl-compatible for most calls,
     * but the backup endpoints differ: creating a backup requires an
     * {@code ignored_files} JSON array, and the backup list is nested under
     * a {@code backups} key. See {@link CalagopusAPIClient}.
     */
    calagopus,
    mcServerSoft,
    error
}
