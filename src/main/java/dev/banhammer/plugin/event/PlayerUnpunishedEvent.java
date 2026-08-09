package dev.banhammer.plugin.event;

import dev.banhammer.plugin.database.model.PunishmentRecord;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event fired when a punishment is removed (unban/unmute/unjail).
 *
 * <p><b>API change in 4.1.0:</b> {@link #getStaff()} returns a {@link CommandSender} rather
 * than a {@link Player}; see {@link PlayerPunishEvent}.
 *
 * @since 3.0.0
 */
public class PlayerUnpunishedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender staff;
    private final PunishmentRecord record;
    private final String reason;
    private final boolean automatic;

    public PlayerUnpunishedEvent(@Nullable CommandSender staff, PunishmentRecord record, String reason, boolean automatic) {
        this.staff = staff;
        this.record = record;
        this.reason = reason;
        this.automatic = automatic;
    }

    /**
     * Gets the staff member who removed the punishment.
     *
     * @return the issuing sender, or {@code null} if the removal was automatic
     */
    @Nullable
    public CommandSender getStaff() {
        return staff;
    }

    /**
     * Gets the staff member as a player.
     *
     * @return the staff player, or {@code null} for console or automatic removals
     */
    @Nullable
    public Player getStaffPlayer() {
        return staff instanceof Player player ? player : null;
    }

    /**
     * Gets the punishment record that was deactivated.
     *
     * @return the punishment record
     */
    public PunishmentRecord getRecord() {
        return record;
    }

    /**
     * Gets the reason for removal.
     *
     * @return the reason
     */
    public String getReason() {
        return reason;
    }

    /**
     * Checks if this was an automatic removal (expired temporary punishment).
     *
     * @return true if automatic, false if manual
     */
    public boolean isAutomatic() {
        return automatic;
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
