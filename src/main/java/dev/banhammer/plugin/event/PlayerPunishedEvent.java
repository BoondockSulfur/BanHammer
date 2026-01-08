package dev.banhammer.plugin.event;

import dev.banhammer.plugin.database.model.PunishmentRecord;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Event fired after a player has been punished successfully.
 * This event cannot be cancelled as the punishment has already been applied.
 *
 * @since 3.0.0
 */
public class PlayerPunishedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player staff;
    private final String victimName;
    private final PunishmentRecord record;

    public PlayerPunishedEvent(Player staff, String victimName, PunishmentRecord record) {
        this.staff = staff;
        this.victimName = victimName;
        this.record = record;
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
     * Gets the name of the player who was punished.
     * (Player may already be disconnected)
     *
     * @return the victim's name
     */
    public String getVictimName() {
        return victimName;
    }

    /**
     * Gets the full punishment record.
     *
     * @return the punishment record
     */
    public PunishmentRecord getRecord() {
        return record;
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
