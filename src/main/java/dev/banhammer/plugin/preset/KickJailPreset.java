package dev.banhammer.plugin.preset;

import dev.banhammer.plugin.util.DurationParser;

import java.time.Duration;

/**
 * Represents a kick/jail preset.
 *
 * <p>The type is stored explicitly rather than being derived from {@code duration == null},
 * because a {@code null} duration is ambiguous: it means "kick" for a preset without a
 * {@code duration} key, but "permanent jail" for {@code duration: permanent}.
 *
 * @since 3.0.0
 */
public class KickJailPreset {

    private final String id;
    private final String displayName;
    private final String reason;
    /** {@code null} means permanent when {@link #type} is {@link PresetType#JAIL}. */
    private final Duration duration;
    private final String sound;
    private final PresetType type;

    public KickJailPreset(String id, String displayName, String reason, PresetType type, Duration duration, String sound) {
        this.id = id;
        this.displayName = displayName;
        this.reason = reason;
        this.type = type;
        this.duration = (type == PresetType.KICK) ? null : duration;
        this.sound = sound;
    }

    /**
     * Creates a kick preset.
     */
    public static KickJailPreset kick(String id, String displayName, String reason, String sound) {
        return new KickJailPreset(id, displayName, reason, PresetType.KICK, null, sound);
    }

    /**
     * Creates a jail preset.
     *
     * @param duration the jail duration, or {@code null} for a permanent jail
     */
    public static KickJailPreset jail(String id, String displayName, String reason, Duration duration, String sound) {
        return new KickJailPreset(id, displayName, reason, PresetType.JAIL, duration, sound);
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

    /**
     * @return the jail duration, or {@code null} for a kick or a permanent jail
     */
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
        if (isKick()) {
            return "Kick";
        }
        if (duration == null) {
            return "Permanent Jail";
        }
        return DurationParser.formatHuman(duration) + " Jail";
    }

    public enum PresetType {
        KICK,
        JAIL
    }
}
