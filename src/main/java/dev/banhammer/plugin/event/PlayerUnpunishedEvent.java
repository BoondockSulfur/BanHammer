package dev.banhammer.plugin.event;

import dev.banhammer.plugin.database.model.PunishmentRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event fired when a punishment is removed (unban/unmute).
 *
 * @since 3.0.0
 */
public class PlayerUnpunishedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player staff;
    private final PunishmentRecord record;
    private final String reason;
    private final boolean automatic;

    public PlayerUnpunishedEvent(@Nullable Player staff, PunishmentRecord record, String reason, boolean automatic) {
        this.staff = staff;
        this.record = record;
        this.reason = reason;
        this.automatic = automatic;
    }

    /**
     * Gets the staff member who removed the punishment (null if automatic).
     *
     * @return the staff player or null
     */
    @Nullable
    public Player getStaff() {
        return staff;
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
