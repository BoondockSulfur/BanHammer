package dev.banhammer.plugin.preset;

import java.time.Duration;

/**
 * Represents a kick/jail preset.
 * Can be either a kick (no duration) or a jail (with duration).
 *
 * @since 3.0.0
 */
public class KickJailPreset {

    private final String id;
    private final String displayName;
    private final String reason;
    private final Duration duration; // null = kick, non-null = jail
    private final String sound;
    private final PresetType type;

    public KickJailPreset(String id, String displayName, String reason, Duration duration, String sound) {
        this.id = id;
        this.displayName = displayName;
        this.reason = reason;
        this.duration = duration;
        this.sound = sound;
        this.type = (duration == null) ? PresetType.KICK : PresetType.JAIL;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getReason() {
        return reason;
    }

    public Duration getDuration() {
        return duration;
    }

    public String getSound() {
        return sound;
    }

    public PresetType getType() {
        return type;
    }

    public boolean isKick() {
        return type == PresetType.KICK;
    }

    public boolean isJail() {
        return type == PresetType.JAIL;
    }

    /**
     * Returns a formatted display string for the duration.
     */
    public String getDurationDisplay() {
        if (duration == null) {
            return "Kick";
        }

        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d");
        if (hours > 0) sb.append(hours).append("h");
        if (minutes > 0) sb.append(minutes).append("m");
        if (seconds > 0) sb.append(seconds).append("s");

        return sb.length() > 0 ? sb.toString() + " Jail" : "Jail";
    }

    public enum PresetType {
        KICK,
        JAIL
    }
}
