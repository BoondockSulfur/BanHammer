package dev.banhammer.plugin.util;

import dev.banhammer.plugin.BanHammerPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class Settings {

    private final BanHammerPlugin plugin;

    public Settings(BanHammerPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    // cache
    private String itemMaterial;
    private String itemName;
    private List<String> itemLore;
    private int itemCustomModelData;
    private boolean itemUnbreakable;
    private boolean itemHideFlags;
    private boolean itemGiveOnJoin;

    private boolean banEnabled;
    private String banMode;
    private String reason;
    private String duration;
    private boolean broadcast;

    private int cooldownSeconds;

    private boolean fxLightning;
    private boolean fxSound;
    private boolean fxParticles;
    private boolean knockbackEnabled;
    private double knockbackHorizontal;
    private double knockbackVertical;

    private boolean rpEnabled;
    private String rpUrl;
    private String rpHash;
    private String rpPrompt;
    private boolean rpForce;
    private boolean rpSendOnJoin;
    private int rpDelayTicks;

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        itemMaterial = c.getString("item.material", "CARROT_ON_A_STICK");
        itemName = c.getString("item.name", "<gold>Ban Hammer</gold>");
        itemLore = c.getStringList("item.lore");
        itemCustomModelData = c.getInt("item.customModelData", 0);
        itemUnbreakable = c.getBoolean("item.unbreakable", true);
        itemHideFlags = c.getBoolean("item.hideFlags", true);
        itemGiveOnJoin = c.getBoolean("item.giveOnJoin", false);

        banEnabled = c.getBoolean("ban.enabled", true);
        banMode = c.getString("ban.mode", "BAN");
        reason = c.getString("ban.reason", "Du wurdest vom BanHammer getroffen.");
        duration = c.getString("ban.duration", "permanent");
        broadcast = c.getBoolean("ban.broadcast", true);

        cooldownSeconds = Math.max(0, c.getInt("cooldownSeconds", 3));

        fxLightning = c.getBoolean("effects.lightning", true);
        fxSound = c.getBoolean("effects.sound", true);
        fxParticles = c.getBoolean("effects.particles", true);
        knockbackEnabled = c.getBoolean("effects.knockback.enabled", false);
        knockbackHorizontal = c.getDouble("effects.knockback.horizontal", 0.8);
        knockbackVertical = c.getDouble("effects.knockback.vertical", 0.35);

        rpEnabled = c.getBoolean("resourcePack.enabled", true);
        rpUrl = c.getString("resourcePack.url", "");
        rpHash = c.getString("resourcePack.hash", "");
        rpPrompt = c.getString("resourcePack.prompt", "Dieses Pack liefert die BanHammer-Textur. Akzeptieren?");
        rpForce = c.getBoolean("resourcePack.force", false);
        rpSendOnJoin = c.getBoolean("resourcePack.sendOnJoin", true);
        rpDelayTicks = Math.max(0, c.getInt("resourcePack.delayTicks", 40));
    }

    // getters
    public String itemMaterial() { return itemMaterial; }
    public String itemName() { return itemName; }
    public List<String> itemLore() { return itemLore; }
    public int itemCustomModelData() { return itemCustomModelData; }
    public boolean itemUnbreakable() { return itemUnbreakable; }
    public boolean itemHideFlags() { return itemHideFlags; }
    public boolean giveOnJoin() { return itemGiveOnJoin; }

    public boolean banEnabled() { return banEnabled; }
    public String banMode() { return banMode; }
    public String reason() { return reason; }
    public String duration() { return duration; }
    public boolean broadcast() { return broadcast; }

    public int cooldownSeconds() { return cooldownSeconds; }

    public boolean fxLightning() { return fxLightning; }
    public boolean fxSound() { return fxSound; }
    public boolean fxParticles() { return fxParticles; }
    public boolean knockbackEnabled() { return knockbackEnabled; }
    public double knockbackHorizontal() { return knockbackHorizontal; }
    public double knockbackVertical() { return knockbackVertical; }

    public boolean rpEnabled() { return rpEnabled; }
    public String rpUrl() { return rpUrl; }
    public String rpHash() { return rpHash; }
    public String rpPrompt() { return rpPrompt; }
    public boolean rpForce() { return rpForce; }
    public boolean rpSendOnJoin() { return rpSendOnJoin; }
    public int rpDelayTicks() { return rpDelayTicks; }
}
