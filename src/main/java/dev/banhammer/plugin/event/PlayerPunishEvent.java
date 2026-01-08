package dev.banhammer.plugin.event;

import dev.banhammer.plugin.database.model.PunishmentType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Event fired when a player is about to be punished with the ban hammer.
 * Can be cancelled to prevent the punishment.
 *
 * @since 3.0.0
 */
public class PlayerPunishEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Player staff;
    private final Player victim;
    private final PunishmentType type;
    private String reason;
    private Duration duration;

    public PlayerPunishEvent(Player staff, Player victim, PunishmentType type, String reason, Duration duration) {
        this.staff = staff;
        this.victim = victim;
        this.type = type;
        this.reason = reason;
        this.duration = duration;
    }

    /**
     * Gets the staff member who issued the punishment.
     *
     * @return the staff player
     */
    public Player getStaff() {
        return staff;
    }

    /**
     * Gets the player being punished.
     *
     * @return the victim player
     */
    public Player getVictim() {
        return victim;
    }

    /**
     * Gets the type of punishment.
     *
     * @return the punishment type
     */
    public PunishmentType getType() {
        return type;
    }

    /**
     * Gets the punishment reason.
     *
     * @return the reason
     */
    public String getReason() {
        return reason;
    }

    /**
     * Sets the punishment reason.
     *
     * @param reason the new reason
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * Gets the punishment duration (null for permanent).
     *
     * @return the duration or null
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Sets the punishment duration.
     *
     * @param duration the new duration (null for permanent)
     */
    public void setDuration(Duration duration) {
        this.duration = duration;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
