package dev.banhammer.plugin.database.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a ban appeal in the database.
 *
 * @since 3.0.0
 */
public class AppealRecord {
    private int id;
    private int punishmentId;
    private UUID playerUuid;
    private String playerName;
    private String appealText;
    private Instant submittedAt;
    private AppealStatus status;
    private UUID reviewedBy;
    private String reviewerName;
    private String reviewResponse;
    private Instant reviewedAt;

    public AppealRecord() {
    }

    public AppealRecord(int punishmentId, UUID playerUuid, String playerName, String appealText) {
        this.punishmentId = punishmentId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.appealText = appealText;
        this.submittedAt = Instant.now();
        this.status = AppealStatus.PENDING;
    }

    public enum AppealStatus {
        PENDING,
        APPROVED,
        DENIED
    }

    // Getters and Setters

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getPunishmentId() {
        return punishmentId;
    }

    public void setPunishmentId(int punishmentId) {
        this.punishmentId = punishmentId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getAppealText() {
        return appealText;
    }

    public void setAppealText(String appealText) {
        this.appealText = appealText;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public AppealStatus getStatus() {
        return status;
    }

    public void setStatus(AppealStatus status) {
        this.status = status;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(UUID reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getReviewerName() {
        return reviewerName;
    }

    public void setReviewerName(String reviewerName) {
        this.reviewerName = reviewerName;
    }

    public String getReviewResponse() {
        return reviewResponse;
    }

    public void setReviewResponse(String reviewResponse) {
        this.reviewResponse = reviewResponse;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    @Override
    public String toString() {
        return "AppealRecord{" +
                "id=" + id +
                ", playerName='" + playerName + '\'' +
                ", status=" + status +
                ", submittedAt=" + submittedAt +
                '}';
    }
}
