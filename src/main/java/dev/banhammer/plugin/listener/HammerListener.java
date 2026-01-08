package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.util.ItemFactory;
import dev.banhammer.plugin.util.Messages;
import dev.banhammer.plugin.util.Settings;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Optional (Paper): wir unterstützen Component UND String
import net.kyori.adventure.text.Component;

import static dev.banhammer.plugin.util.Constants.*;
import static dev.banhammer.plugin.util.ReflectionUtil.*;

public final class HammerListener implements Listener {

    private final BanHammerPlugin plugin;
    private final Settings settings;
    private final Messages messages;

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> switchCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> switchKickJailCooldowns = new ConcurrentHashMap<>();

    public HammerListener(BanHammerPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.settings();
        this.messages = plugin.messages();
    }

    /* =========================
                EVENTS
       ========================= */

    // RECHTSKLICK auf Spieler → BAN (ohne Sneak) ODER Preset-Wechsel (mit Sneak)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityRightClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // nur Main-Hand

        Player staff = e.getPlayer();
        if (!isHammerInMainHand(staff)) return;

        e.setCancelled(true);

        // Shift + Rechtsklick = Preset wechseln
        if (staff.isSneaking()) {
            handlePresetSwitch(staff);
            return;
        }

        // Normaler Rechtsklick auf Spieler = Ban
        if (e.getRightClicked() instanceof Player target) {
            handleBanUse(staff, target);
        }
    }

    // LINKS-KLICK auf Spieler (Attacke) → KICK/JAIL (ohne Sneak) ODER Preset-Wechsel (mit Sneak)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player staff)) return;
        if (!(e.getEntity() instanceof Player target)) return;
        if (!isHammerInMainHand(staff)) return;

        e.setCancelled(true); // keinen Schaden verursachen

        // Shift + Linksklick = Kick/Jail-Preset wechseln
        if (staff.isSneaking()) {
            handleKickJailPresetSwitch(staff);
            return;
        }

        // Normaler Linksklick auf Spieler = Kick/Jail
        handleKickUse(staff, target);
    }

    // LINKS-KLICK auf Luft/Block → anpeilen → KICK/JAIL (ohne Sneak) ODER Preset-Wechsel (mit Sneak)
    // RECHTS-KLICK (mit Sneak) auf Luft/Block → Ban-Preset wechseln
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player staff = e.getPlayer();
        if (!isHammerInMainHand(staff)) return;

        Action a = e.getAction();

        // Shift + Rechtsklick auf Block/Luft = Ban-Preset wechseln
        if (staff.isSneaking() && (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK)) {
            e.setCancelled(true);
            handlePresetSwitch(staff);
            return;
        }

        // Shift + Linksklick auf Block/Luft = Kick/Jail-Preset wechseln
        if (staff.isSneaking() && (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK)) {
            e.setCancelled(true);
            handleKickJailPresetSwitch(staff);
            return;
        }

        // LINKS-KLICK auf Luft/Block = Ray-Trace Kick/Jail
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            Player target = findTarget(staff, RAY_TRACE_MAX_DISTANCE);
            if (target == null) {
                sendCompat(staff, messages.noTarget());
                return;
            }

            e.setCancelled(true);
            handleKickUse(staff, target);
        }
    }

    // BLOCK-ZERSTÖRUNG verhindern, wenn BanHammer in der Hand
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent e) {
        Player player = e.getPlayer();
        if (!isHammerInMainHand(player)) return;

        e.setCancelled(true);
    }

    /* =========================
              CORE FLOW
       ========================= */

    private boolean isHammerInMainHand(Player p) {
        return ItemFactory.isHammer(plugin, p.getInventory().getItemInMainHand());
    }

    // „Kick/Jail-Flow" für Linksklick - verwendet aktuelles Kick/Jail-Preset
    private void handleKickUse(Player staff, Player victim) {
        plugin.getSLF4JLogger().info("===== HANDLE KICK USE CALLED =====");
        plugin.getSLF4JLogger().info("Staff: {}, Victim: {}", staff.getName(), victim.getName());

        if (!staff.hasPermission("banhammer.use")) {
            plugin.getSLF4JLogger().warn("Staff {} has no banhammer.use permission!", staff.getName());
            return;
        }

        if (isOnCooldown(staff)) {
            plugin.getSLF4JLogger().info("Staff {} is on cooldown", staff.getName());
            sendCompat(staff, messages.cooldown(cooldownRemaining(staff)));
            return;
        }
        if (!canPunish(staff, victim)) {
            plugin.getSLF4JLogger().warn("Cannot punish {} (bypass or self-target)", victim.getName());
            sendCompat(staff, messages.cannotBan()); // vorhandene Msg wiederverwenden
            return;
        }

        setCooldown(staff);
        doFx(staff, victim);

        // Get active kick/jail preset
        plugin.getSLF4JLogger().info("Getting active kick/jail preset for {}", staff.getName());
        dev.banhammer.plugin.preset.KickJailPreset preset = plugin.getPresetManager().getActiveKickJailPreset(staff.getUniqueId());
        plugin.getSLF4JLogger().info("Active preset: {} (Type: {})", preset.getDisplayName(), preset.getType());

        // Use preset values
        String reason = preset.getReason();
        Duration dur = preset.getDuration();

        // Debug log
        plugin.getSLF4JLogger().info("Kick/Jail with preset '{}': {} ({})",
                preset.getDisplayName(),
                victim.getName(),
                preset.getDurationDisplay());

        // Check if this is a kick (no duration) or jail (with duration)
        if (preset.isKick()) {
            // Execute kick
            if (plugin.getPunishmentManager().isDatabaseEnabled()) {
                plugin.getPunishmentManager().kickPlayer(staff, victim, reason)
                    .thenAccept(id -> {
                        if (id >= 0) {
                            sendCompat(staff, messages.kickedStaff(victim.getName()));
                        }
                    });
            } else {
                kickCompat(staff, victim, reason);
            }
        } else {
            // Execute jail
            if (!plugin.getConfig().getBoolean("punishmentTypes.jail.enabled", true)) {
                sendCompat(staff, "<red>Jail system is disabled!</red>");
                return;
            }

            if (!staff.hasPermission("banhammer.jail")) {
                sendCompat(staff, "<red>You don't have permission to jail players!</red>");
                return;
            }

            plugin.getPunishmentManager().jailPlayer(staff, victim, reason, dur)
                .thenAccept(id -> {
                    if (id > 0) {
                        String durHuman = formatDurationHuman(dur);
                        sendCompat(staff, "<green>" + victim.getName() + " was jailed for " + durHuman + "</green>");
                    }
                });
        }
    }

    // „Ban-Flow" für Rechtsklick - verwendet aktuelles Preset
    private void handleBanUse(Player staff, Player victim) {
        if (!staff.hasPermission("banhammer.use")) return;

        if (isOnCooldown(staff)) {
            sendCompat(staff, messages.cooldown(cooldownRemaining(staff)));
            return;
        }
        if (!canPunish(staff, victim)) {
            sendCompat(staff, messages.cannotBan());
            return;
        }

        setCooldown(staff);
        doFx(staff, victim);

        // Get active preset
        dev.banhammer.plugin.preset.BanPreset preset = plugin.getPresetManager().getActivePreset(staff.getUniqueId());

        // Use preset values
        String reason = preset.getReason();
        Duration dur = preset.getDuration();
        boolean ipBan = preset.isIpBan();

        // Debug log
        plugin.getSLF4JLogger().info("Ban with preset '{}': {} ({}{})",
                preset.getDisplayName(),
                victim.getName(),
                preset.getDurationDisplay(),
                ipBan ? ", IP-Ban" : "");

        if (plugin.getPunishmentManager().isDatabaseEnabled()) {
            plugin.getPunishmentManager().banPlayer(staff, victim, reason, dur, ipBan)
                .thenAccept(id -> {
                    if (id > 0) {
                        String durHuman = (dur == null || dur.isZero()) ? "" : " " + formatDurationHuman(dur);
                        sendCompat(staff, messages.bannedStaff(victim.getName(), durHuman));
                        if (plugin.getConfig().getBoolean("ban.broadcast", true)) {
                            broadcastCompat(messages.bannedBroadcast(staff.getName(), victim.getName(), durHuman));
                        }
                    }
                });
        } else {
            performBanCompat(staff, victim, reason, dur);
        }
    }

    // Wechselt zum nächsten Preset (cycle)
    private void handlePresetSwitch(Player staff) {
        if (!staff.hasPermission("banhammer.use")) return;

        // Check switch cooldown
        if (isOnSwitchCooldown(staff)) {
            // Silent fail - keine Spam-Messages
            return;
        }

        setSwitchCooldown(staff);

        // Cycle to next preset
        dev.banhammer.plugin.preset.BanPreset preset = plugin.getPresetManager().cyclePreset(staff.getUniqueId());

        // Actionbar feedback
        String presetInfo = "<gold>Preset: <yellow>" + preset.getDisplayName() +
                           " <gray>(" + preset.getDurationDisplay() +
                           (preset.isIpBan() ? ", IP-Ban" : "") + ")</gray></gold>";
        sendActionBar(staff, presetInfo);

        // Play sound
        if (preset.getSound() != null && !preset.getSound().isEmpty()) {
            try {
                Sound sound = Sound.valueOf(preset.getSound());
                staff.playSound(staff.getLocation(), sound, 0.7f, 1.0f);
            } catch (IllegalArgumentException e) {
                plugin.getSLF4JLogger().warn("Invalid sound for preset {}: {}", preset.getId(), preset.getSound());
            }
        }

        plugin.getSLF4JLogger().debug("Player {} switched to preset: {}", staff.getName(), preset.getDisplayName());
    }

    // Wechselt zum nächsten Kick/Jail-Preset (cycle)
    private void handleKickJailPresetSwitch(Player staff) {
        if (!staff.hasPermission("banhammer.use")) return;

        // Check switch cooldown
        if (isOnKickJailSwitchCooldown(staff)) {
            // Silent fail - keine Spam-Messages
            return;
        }

        setKickJailSwitchCooldown(staff);

        // Cycle to next preset
        dev.banhammer.plugin.preset.KickJailPreset preset = plugin.getPresetManager().cycleKickJailPreset(staff.getUniqueId());

        // Actionbar feedback
        String presetInfo = "<gold>Kick/Jail Preset: <yellow>" + preset.getDisplayName() +
                           " <gray>(" + preset.getDurationDisplay() + ")</gray></gold>";
        sendActionBar(staff, presetInfo);

        // Play sound
        if (preset.getSound() != null && !preset.getSound().isEmpty()) {
            try {
                Sound sound = Sound.valueOf(preset.getSound());
                staff.playSound(staff.getLocation(), sound, 0.7f, 1.0f);
            } catch (IllegalArgumentException e) {
                plugin.getSLF4JLogger().warn("Invalid sound for kick/jail preset {}: {}", preset.getId(), preset.getSound());
            }
        }

        plugin.getSLF4JLogger().debug("Player {} switched to kick/jail preset: {}", staff.getName(), preset.getDisplayName());
    }

    private boolean canPunish(Player staff, Player victim) {
        if (staff.getUniqueId().equals(victim.getUniqueId())) return false;
        if (victim.hasPermission("banhammer.bypass") || victim.isOp()) return false;
        return true;
    }

    /* =========================
              TARGETING
       ========================= */

    private Player findTarget(Player p, double maxDistance) {
        try {
            Entity direct = p.getTargetEntity((int) Math.ceil(maxDistance));
            if (direct instanceof Player dp && !dp.getUniqueId().equals(p.getUniqueId())) {
                return dp;
            }
        } catch (Throwable ignored) {}

        RayTraceResult rt = p.getWorld().rayTraceEntities(
                p.getEyeLocation(),
                p.getEyeLocation().getDirection(),
                maxDistance,
                RAY_TRACE_SIZE,
                ent -> ent instanceof Player && !ent.getUniqueId().equals(p.getUniqueId())
        );
        if (rt != null && rt.getHitEntity() instanceof Player hit) {
            return hit;
        }
        return null;
    }

    /* =========================
                 FX
       ========================= */

    private void doFx(Player staff, Player victim) {
        if (cfgFxLightning()) fxLightning(victim);
        if (cfgFxSound())     fxSound(victim);
        if (cfgFxParticles()) fxParticles(victim);
        if (cfgKnockbackEnabled()) knockback(staff, victim);
    }

    private void fxLightning(Player victim) {
        victim.getWorld().strikeLightningEffect(victim.getLocation());
    }

    private void fxSound(Player victim) {
        World w = victim.getWorld();
        Location loc = victim.getLocation();
        w.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SOUND_THUNDER_VOLUME, SOUND_PITCH);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SOUND_EXPLOSION_VOLUME, SOUND_PITCH);
    }

    private void fxParticles(Player victim) {
        World w = victim.getWorld();
        Location loc = victim.getLocation().add(0, PARTICLE_Y_OFFSET, 0);
        w.spawnParticle(Particle.CRIT, loc, PARTICLE_CRIT_COUNT,
                PARTICLE_CRIT_OFFSET_X, PARTICLE_CRIT_OFFSET_Y, PARTICLE_CRIT_OFFSET_Z, PARTICLE_CRIT_SPEED);
        w.spawnParticle(Particle.SMOKE, loc, PARTICLE_SMOKE_COUNT,
                PARTICLE_SMOKE_OFFSET, PARTICLE_SMOKE_OFFSET, PARTICLE_SMOKE_OFFSET, PARTICLE_SMOKE_SPEED);
    }

    private void knockback(Player staff, Player victim) {
        double h = Math.max(0.0, cfgKnockbackHorizontal());
        double v = Math.max(0.0, cfgKnockbackVertical());
        Vector dir = victim.getLocation().toVector().subtract(staff.getLocation().toVector()).normalize();
        Vector vel = new Vector(dir.getX() * h, v, dir.getZ() * h);
        victim.setVelocity(vel);
    }

    /* =========================
                BAN/KICK
       ========================= */

    private void performBanCompat(Player staff, Player victim, Object reason, Duration duration) {
        Instant expires = (duration == null || duration.isZero() || duration.isNegative())
                ? null
                : Instant.now().plus(duration);

        // Debug logging
        if (expires == null) {
            plugin.getSLF4JLogger().info("Vanilla ban: PERMANENT ban for {}", victim.getName());
        } else {
            long seconds = java.time.Duration.between(Instant.now(), expires).getSeconds();
            plugin.getSLF4JLogger().info("Vanilla ban: TEMPORARY ban for {} ({} seconds, expires at {})",
                    victim.getName(), seconds, expires);
        }

        // Apply ban to Minecraft ban list
        try {
            Bukkit.getBanList(BanList.Type.NAME).addBan(
                    victim.getName(),
                    reason == null ? "" : reason.toString(),
                    (expires == null) ? null : java.util.Date.from(expires),
                    staff.getName()
            );
        } catch (Exception ex) {
            plugin.getSLF4JLogger().error("Failed to add ban to ban list", ex);
            sendCompat(staff, "<red>Fehler beim Bannen: " + ex.getMessage() + "</red>");
            return;
        }

        // Kick player (separate try-catch to ensure kick happens even if ban had issues)
        try {
            kickOnlyCompat(victim, reason);
        } catch (Exception ex) {
            plugin.getSLF4JLogger().error("Failed to kick player after ban (player is banned but still online)", ex);
            // Continue anyway - player is banned, so they can't do much
        }

        // Send success messages
        try {
            String durHuman = (expires == null) ? "" : " " + formatDurationHuman(duration);
            sendCompat(staff, messages.bannedStaff(victim.getName(), durHuman));
            if (plugin.getConfig().getBoolean("ban.broadcast", true)) {
                broadcastCompat(messages.bannedBroadcast(staff.getName(), victim.getName(), durHuman));
            }
        } catch (Exception ex) {
            plugin.getSLF4JLogger().error("Failed to send ban confirmation messages", ex);
        }
    }

    private void kickCompat(Player staff, Player victim, Object reason) {
        kickOnlyCompat(victim, reason);
        sendCompat(staff, messages.kickedStaff(victim.getName()));
    }

    /* =========================
          COOLDOWN & UTIL
       ========================= */

    private boolean isOnCooldown(Player p) {
        long now = System.currentTimeMillis();
        long cdMs = Math.max(0, cfgCooldownSeconds()) * MILLIS_PER_SECOND;
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        return now - last < cdMs;
    }

    private int cooldownRemaining(Player p) {
        long now = System.currentTimeMillis();
        long cdMs = Math.max(0, cfgCooldownSeconds()) * MILLIS_PER_SECOND;
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        long left = Math.max(0, cdMs - (now - last));
        return (int) Math.ceil(left / (double) MILLIS_PER_SECOND);
    }

    private void setCooldown(Player p) {
        cooldowns.put(p.getUniqueId(), System.currentTimeMillis());
    }

    // ---- Duration-Parsing & Anzeige ----

    private Duration parseDuration(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Explicit handling for "permanent" keyword
        if (s.equalsIgnoreCase("permanent") || s.equalsIgnoreCase("perm")) {
            return null; // null = permanent
        }

        // Try ISO-8601 format (case-insensitive)
        if (s.toUpperCase().startsWith("P")) {
            try {
                return Duration.parse(s.toUpperCase());
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Invalid ISO-8601 duration format: {}", s);
                // Fall through to custom parsing
            }
        }

        // Custom format: "7d", "1h30m", "2d12h", etc.
        String tmp = s.toLowerCase();
        long days = extractNumber(tmp, "d").orElse(0L);
        long hours = extractNumber(tmp, "h").orElse(0L);
        long minutes = extractNumber(tmp, "m").orElse(0L);
        long seconds = extractNumber(tmp, "s").orElse(0L);

        // Check if any time unit was found
        if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
            plugin.getSLF4JLogger().warn("Could not parse duration: '{}'. Use format like '7d', '1h30m', or 'permanent'", raw);
            return null; // Invalid format, default to permanent
        }

        Duration d = Duration.ZERO;
        if (days > 0) d = d.plusDays(days);
        if (hours > 0) d = d.plusHours(hours);
        if (minutes > 0) d = d.plusMinutes(minutes);
        if (seconds > 0) d = d.plusSeconds(seconds);

        return d;
    }

    private Optional<Long> extractNumber(String s, String unit) {
        int i = s.indexOf(unit);
        if (i <= 0) return Optional.empty();
        int j = i - 1;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) j--;
        int end = j + 1;
        while (j >= 0 && Character.isDigit(s.charAt(j))) j--;
        String num = s.substring(j + 1, end);
        try { return Optional.of(Long.parseLong(num)); }
        catch (NumberFormatException ex) { return Optional.empty(); }
    }

    private String formatDurationHuman(Duration d) {
        if (d == null) return "";
        long totalSeconds = d.getSeconds();
        long days = totalSeconds / SECONDS_PER_DAY;
        long hours = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR;
        long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d");
        if (hours > 0) { if (sb.length() > 0) sb.append(" "); sb.append(hours).append("h"); }
        if (minutes > 0) { if (sb.length() > 0) sb.append(" "); sb.append(minutes).append("m"); }
        if (seconds > 0 && sb.length() == 0) { sb.append(seconds).append("s"); }
        return (sb.length() == 0) ? "" : " (" + sb + ")";
    }

    /* =========================
        SETTINGS GETTER (REFACTORED)
       ========================= */

    // Direct access to Settings instead of Reflection
    private Object  cfgKickReason()          { return plugin.getConfig().getString("kick.reason", settings.reason()); }
    private boolean cfgFxLightning()         { return settings.fxLightning(); }
    private boolean cfgFxSound()             { return settings.fxSound(); }
    private boolean cfgFxParticles()         { return settings.fxParticles(); }
    private boolean cfgKnockbackEnabled()    { return settings.knockbackEnabled(); }
    private double  cfgKnockbackHorizontal() { return settings.knockbackHorizontal(); }
    private double  cfgKnockbackVertical()   { return settings.knockbackVertical(); }
    private int     cfgCooldownSeconds()     { return settings.cooldownSeconds(); }
    private Object  cfgBanReason()           { return settings.reason(); }
    private String  cfgBanDuration()         { return settings.duration(); }

    /* =========================
       MESSAGE/KICK KOMPAT-HILFEN
       ========================= */

    private void sendCompat(Player p, Object msg) {
        try {
            if (msg instanceof Component comp) { p.sendMessage(comp); return; }
        } catch (Throwable ignored) {}
        p.sendMessage(msg == null ? "" : String.valueOf(msg));
    }

    private void broadcastCompat(Object msg) {
        try {
            if (msg instanceof Component comp) {
                try { Bukkit.getServer().broadcast(comp); return; }
                catch (Throwable ignored) {}
                for (Player pl : Bukkit.getOnlinePlayers()) pl.sendMessage(comp);
                return;
            }
        } catch (Throwable ignored) {}
        Bukkit.broadcastMessage(msg == null ? "" : String.valueOf(msg));
    }

    private void kickOnlyCompat(Player victim, Object reason) {
        try {
            if (reason instanceof Component comp) { victim.kick(comp); return; }
        } catch (Throwable ignored) {}
        victim.kickPlayer(reason == null ? "" : String.valueOf(reason));
    }

    private void sendActionBar(Player player, String message) {
        try {
            // Try Paper/Adventure API first
            net.kyori.adventure.text.minimessage.MiniMessage mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
            Component component = mm.deserialize(message);
            player.sendActionBar(component);
        } catch (Throwable e) {
            // Fallback: Send as regular message if ActionBar not supported
            plugin.getSLF4JLogger().debug("ActionBar not supported, using chat message");
            sendCompat(player, message);
        }
    }

    /* =========================
           COOLDOWN METHODS
       ========================= */

    private boolean isOnSwitchCooldown(Player p) {
        Long last = switchCooldowns.get(p.getUniqueId());
        if (last == null) return false;

        long cooldownMs = plugin.getConfig().getLong("presetSwitchCooldown", 250);
        long elapsed = System.currentTimeMillis() - last;
        return elapsed < cooldownMs;
    }

    private void setSwitchCooldown(Player p) {
        switchCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
    }

    private boolean isOnKickJailSwitchCooldown(Player p) {
        Long last = switchKickJailCooldowns.get(p.getUniqueId());
        if (last == null) return false;

        long cooldownMs = plugin.getConfig().getLong("presetSwitchCooldown", 250);
        long elapsed = System.currentTimeMillis() - last;
        return elapsed < cooldownMs;
    }

    private void setKickJailSwitchCooldown(Player p) {
        switchKickJailCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
    }
}
