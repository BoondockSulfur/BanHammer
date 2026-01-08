package dev.banhammer.plugin.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a punishment record in the database.
 *
 * @since 3.0.0
 */
public class PunishmentRecord {
    private int id;
    private UUID victimUuid;
    private String victimName;
    private String victimIp;
    private UUID staffUuid;
    private String staffName;
    private PunishmentType type;
    private String reason;
    private Instant issuedAt;
    private Instant expiresAt;
    private boolean active;
    private UUID unbanStaffUuid;
    private String unbanReason;
    private Instant unbannedAt;
    private String serverName;

    public PunishmentRecord() {
    }

    public PunishmentRecord(UUID victimUuid, String victimName, String victimIp,
                           UUID staffUuid, String staffName, PunishmentType type,
                           String reason, Instant issuedAt, Instant expiresAt) {
        this.victimUuid = victimUuid;
        this.victimName = victimName;
        this.victimIp = victimIp;
        this.staffUuid = staffUuid;
        this.staffName = staffName;
        this.type = type;
        this.reason = reason;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.active = true;
    }

    // Getters and Setters

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public UUID getVictimUuid() {
        return victimUuid;
    }

    public void setVictimUuid(UUID victimUuid) {
        this.victimUuid = victimUuid;
    }

    public String getVictimName() {
        return victimName;
    }

    public void setVictimName(String victimName) {
        this.victimName = victimName;
    }

    public String getVictimIp() {
        return victimIp;
    }

    public void setVictimIp(String victimIp) {
        this.victimIp = victimIp;
    }

    public UUID getStaffUuid() {
        return staffUuid;
    }

    public void setStaffUuid(UUID staffUuid) {
        this.staffUuid = staffUuid;
    }

    public String getStaffName() {
        return staffName;
    }

    public void setStaffName(String staffName) {
        this.staffName = staffName;
    }

    public PunishmentType getType() {
        return type;
    }

    public void setType(PunishmentType type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID getUnbanStaffUuid() {
        return unbanStaffUuid;
    }

    public void setUnbanStaffUuid(UUID unbanStaffUuid) {
        this.unbanStaffUuid = unbanStaffUuid;
    }

    public String getUnbanReason() {
        return unbanReason;
    }

    public void setUnbanReason(String unbanReason) {
        this.unbanReason = unbanReason;
    }

    public Instant getUnbannedAt() {
        return unbannedAt;
    }

    public void setUnbannedAt(Instant unbannedAt) {
        this.unbannedAt = unbannedAt;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public boolean isExpired() {
        if (expiresAt == null) return false;
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isPermanent() {
        return expiresAt == null;
    }

    @Override
    public String toString() {
        return "PunishmentRecord{" +
                "id=" + id +
                ", victimName='" + victimName + '\'' +
                ", type=" + type +
                ", reason='" + reason + '\'' +
                ", issuedAt=" + issuedAt +
                ", active=" + active +
                '}';
    }
}
