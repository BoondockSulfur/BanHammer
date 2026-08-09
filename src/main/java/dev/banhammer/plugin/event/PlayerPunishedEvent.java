package dev.banhammer.plugin.event;

import dev.banhammer.plugin.database.model.PunishmentRecord;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event fired after a player has been punished successfully.
 * This event cannot be cancelled as the punishment has already been applied.
 *
 * <p><b>API change in 4.1.0:</b> {@link #getStaff()} returns a {@link CommandSender} rather
 * than a {@link Player}; see {@link PlayerPunishEvent}.
 *
 * @since 3.0.0
 */
public class PlayerPunishedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender staff;
    private final String victimName;
    private final PunishmentRecord record;

    public PlayerPunishedEvent(CommandSender staff, String victimName, PunishmentRecord record) {
        this.staff = staff;
        this.victimName = victimName;
        this.record = record;
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
