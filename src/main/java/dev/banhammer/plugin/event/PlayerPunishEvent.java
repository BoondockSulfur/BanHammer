package dev.banhammer.plugin.event;

import dev.banhammer.plugin.database.model.PunishmentType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * Event fired when a player is about to be punished.
 * Can be cancelled to prevent the punishment.
 *
 * <p><b>API change in 4.1.0:</b> {@link #getStaff()} returns a {@link CommandSender} rather
 * than a {@link Player}, because punishments can now also be issued from the console, RCON
 * or a command block. Use {@link #getStaffPlayer()} when you specifically need a player.
 *
 * @since 3.0.0
 */
public class PlayerPunishEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final CommandSender staff;
    private final Player victim;
    private final PunishmentType type;
    private String reason;
    private Duration duration;

    public PlayerPunishEvent(CommandSender staff, Player victim, PunishmentType type, String reason, Duration duration) {
        this.staff = staff;
        this.victim = victim;
        this.type = type;
        this.reason = reason;
        this.duration = duration;
    }

    /**
     * Gets the staff member who issued the punishment.
     *
     * @return the issuing sender; may be the console
     */
    @NotNull
    public CommandSender getStaff() {
        return staff;
    }

    /**
     * Gets the staff member as a player.
     *
     * @return the staff player, or {@code null} if the punishment came from the console
     */
    @Nullable
    public Player getStaffPlayer() {
        return staff instanceof Player player ? player : null;
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
     * Gets the type of punishment as originally requested.
     *
     * <p>Changing the duration via {@link #setDuration(Duration)} also changes the effective
     * type: {@code null} makes the punishment permanent, and BanHammer re-derives
     * BAN/TEMP_BAN (and MUTE/TEMP_MUTE) from the final duration after this event, so the
     * stored record never says TEMP_BAN with no expiry.
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
