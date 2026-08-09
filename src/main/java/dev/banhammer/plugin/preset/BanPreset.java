package dev.banhammer.plugin.preset;

import java.time.Duration;

/**
 * Represents a configurable ban preset with name, duration, reason, and type.
 *
 * @since 3.0.0
 */
public class BanPreset {

    private final String id;
    private final String displayName;
    private final String reason;
    private final Duration duration; // null = permanent
    private final boolean ipBan;
    private final String sound; // Optional sound to play on selection

    public BanPreset(String id, String displayName, String reason, Duration duration, boolean ipBan, String sound) {
        this.id = id;
        this.displayName = displayName;
        this.reason = reason;
        this.duration = duration;
        this.ipBan = ipBan;
        this.sound = sound;
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

    public boolean isIpBan() {
        return ipBan;
    }

    public String getSound() {
        return sound;
    }

    public boolean isPermanent() {
        return duration == null;
    }

    /**
     * Gets a human-readable duration string for display.
     *
     * @return Duration string like "7d", "1h30m", or "Permanent"
     */
    public String getDurationDisplay() {
        if (duration == null) {
            return "Permanent";
        }
        return dev.banhammer.plugin.util.DurationParser.formatHuman(duration);
    }

    @Override
    public String toString() {
        return "BanPreset{" +
                "id='" + id + '\'' +
                ", displayName='" + displayName + '\'' +
                ", duration=" + getDurationDisplay() +
                ", ipBan=" + ipBan +
                '}';
    }
}
