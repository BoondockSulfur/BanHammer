package dev.banhammer.plugin.util;

import dev.banhammer.plugin.BanHammerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.packs.ResourcePack;

import java.lang.reflect.Method;

public final class ResourcePackSender {

    public enum Result {
        NONE,
        SERVER,
        PLUGIN
    }

    private final BanHammerPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    public ResourcePackSender(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    public Result sendPreferredPack(Player player) {
        plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Attempting to send resourcepack to {}", player.getName());

        PackData serverPack = resolveServerPack();
        if (serverPack != null) {
            plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Using SERVER resourcepack");
            send(player, serverPack);
            return Result.SERVER;
        }

        PackData pluginPack = resolvePluginPack();
        if (pluginPack != null) {
            plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Using PLUGIN resourcepack: {}", pluginPack.url());
            send(player, pluginPack);
            return Result.PLUGIN;
        }

        plugin.getSLF4JLogger().warn("DEBUG RESOURCEPACK: No resourcepack available (neither server nor plugin)");
        return Result.NONE;
    }

    private PackData resolveServerPack() {
        try {
            ResourcePack serverPack = plugin.getServer().getServerResourcePack();
            if (serverPack != null) {
                return new PackData(
                        serverPack.getUrl(),
                        decodeHash(serverPack.getHash(), "server"),
                        textPrompt(serverPack.getPrompt()),
                        serverPack.isRequired()
                );
            }
        } catch (Throwable ignored) {
            // Paper only API  fall back to the vanilla getters below
        }

        String url = Bukkit.getServer().getResourcePack();
        if (url == null || url.isBlank()) return null;
        return new PackData(
                url,
                decodeHash(Bukkit.getServer().getResourcePackHash(), "server"),
                textPrompt(Bukkit.getServer().getResourcePackPrompt()),
                Bukkit.getServer().isResourcePackRequired()
        );
    }

    private PackData resolvePluginPack() {
        var settings = plugin.settings();
        plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Plugin pack enabled: {}", settings.rpEnabled());
        if (!settings.rpEnabled()) return null;
        String url = settings.rpUrl();
        plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Plugin pack URL: {}", url);
        if (url == null || url.isBlank()) return null;
        Component prompt = deserializePrompt(settings.rpPrompt());
        byte[] hash = decodeHash(settings.rpHash(), "config");
        return new PackData(url, hash, prompt, settings.rpForce());
    }

    private Component deserializePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;
        try {
            return mini.deserialize(prompt);
        } catch (IllegalArgumentException invalid) {
            plugin.getSLF4JLogger().warn("resourcePack.prompt konnte nicht geparst werden: {}", invalid.getMessage());
            return Component.text(prompt);
        }
    }

    private Component textPrompt(String prompt) {
        return (prompt == null || prompt.isBlank()) ? null : Component.text(prompt);
    }

    private byte[] decodeHash(String hex, String source) {
        if (hex == null || hex.isBlank()) return null;
        if (hex.length() != 40) {
            plugin.getSLF4JLogger().warn("resourcePack.hash ({}-Quelle) muss 40 Hex-Zeichen lang sein. Ignoriere Hash.", source);
            return null;
        }
        try {
            return Hex.decodeHex(hex);
        } catch (IllegalArgumentException ex) {
            plugin.getSLF4JLogger().warn("Kann Hash aus {} nicht dekodieren: {}", source, ex.getMessage());
            return null;
        }
    }

    private void send(Player player, PackData data) {
        plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Sending pack to {} - URL: {}, Has Hash: {}, Force: {}",
            player.getName(), data.url(), data.hash() != null, data.force());
        try {
            Class<?> reqClass = Class.forName("org.bukkit.entity.Player$ResourcePackRequest");
            Method builderMethod = reqClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            builder.getClass().getMethod("url", String.class).invoke(builder, data.url());
            if (data.hash() != null) builder.getClass().getMethod("hash", byte[].class).invoke(builder, (Object) data.hash());
            if (data.prompt() != null) builder.getClass().getMethod("prompt", Component.class).invoke(builder, data.prompt());
            builder.getClass().getMethod("force", boolean.class).invoke(builder, data.force());
            Object request = builder.getClass().getMethod("build").invoke(builder);
            player.getClass().getMethod("setResourcePack", reqClass).invoke(player, request);
            plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Successfully sent pack using new API");
        } catch (Throwable reflectFail) {
            plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: New API failed, using fallback: {}", reflectFail.getMessage());
            fallbackSend(player, data);
        }
    }

    private void fallbackSend(Player player, PackData data) {
        byte[] hash = data.hash();
        Component prompt = data.prompt();
        boolean force = data.force();
        plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Using fallback send method (hash={}, prompt={}, force={})",
            hash != null, prompt != null, force);
        if (hash != null && prompt != null) {
            player.setResourcePack(data.url(), hash, prompt, force);
        } else if (hash != null) {
            player.setResourcePack(data.url(), hash);
        } else {
            player.setResourcePack(data.url());
        }
        plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Fallback send completed");
    }

    private record PackData(String url, byte[] hash, Component prompt, boolean force) {}
}
