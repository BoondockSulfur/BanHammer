package dev.banhammer.plugin.database.model;

import java.util.UUID;

/**
 * Statistics for staff member's punishment issuance.
 *
 * @since 3.0.0
 */
public class PunishmentStatistics {
    private UUID staffUuid;
    private String staffName;
    private int totalPunishments;
    private int bans;
    private int kicks;
    private int mutes;
    private int warnings;
    private int jails;

    public PunishmentStatistics() {
    }

    public PunishmentStatistics(UUID staffUuid, String staffName) {
        this.staffUuid = staffUuid;
        this.staffName = staffName;
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

    public int getTotalPunishments() {
        return totalPunishments;
    }

    public void setTotalPunishments(int totalPunishments) {
        this.totalPunishments = totalPunishments;
    }

    public int getBans() {
        return bans;
    }

    public void setBans(int bans) {
        this.bans = bans;
    }

    public int getKicks() {
        return kicks;
    }

    public void setKicks(int kicks) {
        this.kicks = kicks;
    }

    public int getMutes() {
        return mutes;
    }

    public void setMutes(int mutes) {
        this.mutes = mutes;
    }

    public int getWarnings() {
        return warnings;
    }

    public void setWarnings(int warnings) {
        this.warnings = warnings;
    }

    /**
     * @return number of jail punishments issued; counted separately so the per-type figures
     *         add up to {@link #getTotalPunishments()}
     */
    public int getJails() {
        return jails;
    }

    public void setJails(int jails) {
        this.jails = jails;
    }

    @Override
    public String toString() {
        return "PunishmentStatistics{" +
                "staffName='" + staffName + '\'' +
                ", totalPunishments=" + totalPunishments +
                ", bans=" + bans +
                ", kicks=" + kicks +
                ", mutes=" + mutes +
                ", jails=" + jails +
                ", warnings=" + warnings +
                '}';
    }
}
