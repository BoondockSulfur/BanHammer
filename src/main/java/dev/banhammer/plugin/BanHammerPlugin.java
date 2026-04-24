package dev.banhammer.plugin;

import dev.banhammer.plugin.command.BanHammerCommand;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.MySQLDatabase;
import dev.banhammer.plugin.database.SQLiteDatabase;
import dev.banhammer.plugin.integration.DiscordWebhook;
import dev.banhammer.plugin.integration.EssentialsJailIntegration;
import dev.banhammer.plugin.listener.HammerListener;
import dev.banhammer.plugin.manager.JailManager;
import dev.banhammer.plugin.manager.PunishmentManager;
import dev.banhammer.plugin.scheduler.UnbanScheduler;
import dev.banhammer.plugin.util.Messages;
import dev.banhammer.plugin.util.Settings;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

/**
 * Main plugin class for BanHammer.
 * Handles initialization and shutdown of all components.
 *
 * @since 3.0.0
 */
public class BanHammerPlugin extends JavaPlugin {

    private static BanHammerPlugin instance;
    private Settings settings;
    private Messages messages;
    private NamespacedKey pdcKey;
    private dev.banhammer.plugin.util.ResourcePackSender resourcePackSender;

    // New 3.0 components
    private Database database;
    private DiscordWebhook discord;
    private EssentialsJailIntegration essentialsJail;
    private PunishmentManager punishmentManager;
    private UnbanScheduler unbanScheduler;
    private JailManager jailManager;
    private dev.banhammer.plugin.gui.StatisticsGUI statisticsGUI;
    private dev.banhammer.plugin.preset.PresetManager presetManager;
    private dev.banhammer.plugin.update.ModrinthUpdateChecker updateChecker;

    @Override
    public void onEnable() {
        instance = this;

        // Detect Folia vs Paper
        dev.banhammer.plugin.util.FoliaScheduler.init();
        if (dev.banhammer.plugin.util.FoliaScheduler.isFolia()) {
            getSLF4JLogger().info("Folia detected - using region-based schedulers");
        }

        // Save and load config
        saveDefaultConfig();

        // Validate configuration
        if (!dev.banhammer.plugin.util.ConfigValidator.validate(getConfig(), getSLF4JLogger())) {
            getSLF4JLogger().error("Configuration validation failed! Plugin may not work correctly.");
            getSLF4JLogger().error("Please fix the errors in config.yml and reload the plugin.");
            // Don't disable plugin - let it run with warnings
        }

        // Generate hash salt if needed
        generateHashSaltIfNeeded();

        settings = new Settings(this);
        messages = new Messages(this);
        resourcePackSender = new dev.banhammer.plugin.util.ResourcePackSender(this);
        pdcKey = new NamespacedKey(this, "ban_hammer");

        // Initialize database if enabled (async to prevent blocking main thread)
        if (getConfig().getBoolean("database.enabled", false)) {
            initializeDatabaseAsync();
        } else {
            getSLF4JLogger().debug("Database is disabled. Using vanilla ban system only.");
        }

        // Initialize Discord webhook
        initializeDiscord();

        // Initialize Essentials jail integration (soft dependency)
        essentialsJail = new EssentialsJailIntegration(getSLF4JLogger());

        // Initialize jail manager
        jailManager = new JailManager(this, essentialsJail);

        // Initialize punishment manager (database might be null initially if async init is still running)
        punishmentManager = new PunishmentManager(this, database, discord);

        // Initialize preset manager
        presetManager = new dev.banhammer.plugin.preset.PresetManager(this);

        // Initialize update checker
        updateChecker = new dev.banhammer.plugin.update.ModrinthUpdateChecker(this);
        updateChecker.start();

        // Note: UnbanScheduler and loadActiveMutes() will be initialized
        // in initializeDatabaseDependentComponents() after database is ready

        // Register events
        getServer().getPluginManager().registerEvents(new HammerListener(this), this);
        getServer().getPluginManager().registerEvents(new dev.banhammer.plugin.listener.ResourcePackListener(this), this);

        // Register mute listener if mute system is enabled
        if (getConfig().getBoolean("punishmentTypes.mute.enabled", true)) {
            getServer().getPluginManager().registerEvents(new dev.banhammer.plugin.listener.MuteListener(this), this);
        }

        // Register jail listener if jail system is enabled
        if (getConfig().getBoolean("punishmentTypes.jail.enabled", true)) {
            getServer().getPluginManager().registerEvents(new dev.banhammer.plugin.listener.JailListener(this), this);
            // Load jailed players
            jailManager.loadJailedPlayers();
        }

        // Initialize statistics GUI
        statisticsGUI = new dev.banhammer.plugin.gui.StatisticsGUI(this);
        getServer().getPluginManager().registerEvents(new dev.banhammer.plugin.listener.GUIListener(this, statisticsGUI), this);

        // Register commands
        var cmd = new BanHammerCommand(this);
        var handle = Objects.requireNonNull(getCommand("banhammer"),
                "Command 'banhammer' nicht in plugin.yml registriert");
        handle.setExecutor(cmd);
        handle.setTabCompleter(cmd);

        // Register appeal command
        var appealCmd = new dev.banhammer.plugin.command.AppealCommand(this);
        var appealHandle = Objects.requireNonNull(getCommand("appeal"),
                "Command 'appeal' nicht in plugin.yml registriert");
        appealHandle.setExecutor(appealCmd);

        // Register punishment commands
        var punishCmd = new dev.banhammer.plugin.command.PunishmentCommands(this);
        Objects.requireNonNull(getCommand("mute")).setExecutor(punishCmd);
        Objects.requireNonNull(getCommand("unmute")).setExecutor(punishCmd);
        Objects.requireNonNull(getCommand("jail")).setExecutor(punishCmd);
        Objects.requireNonNull(getCommand("unjail")).setExecutor(punishCmd);
        Objects.requireNonNull(getCommand("warn")).setExecutor(punishCmd);
        Objects.requireNonNull(getCommand("setjail")).setExecutor(punishCmd);
        Objects.requireNonNull(getCommand("mute")).setTabCompleter(punishCmd);
        Objects.requireNonNull(getCommand("jail")).setTabCompleter(punishCmd);

        getSLF4JLogger().info("BanHammer v3.1.0 enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Stop update checker
        if (updateChecker != null) {
            updateChecker.stop();
        }

        // Stop scheduler
        if (unbanScheduler != null) {
            unbanScheduler.stop();
        }

        // Stop jail manager cleanup task
        if (jailManager != null) {
            jailManager.shutdown();
        }

        // Close database
        if (database != null) {
            database.shutdown().join();
        }

        // Close Discord webhook
        if (discord != null) {
            discord.shutdown();
        }

        getSLF4JLogger().info("BanHammer disabled.");
    }

    /**
     * Initializes the database based on config settings asynchronously.
     */
    private void initializeDatabaseAsync() {
        String type = getConfig().getString("database.type", "SQLITE").toUpperCase();

        Database tempDatabase = switch (type) {
            case "SQLITE" -> new SQLiteDatabase(getSLF4JLogger(), getDataFolder());
            case "MYSQL" -> {
                String host = getConfig().getString("database.mysql.host", "localhost");
                int port = getConfig().getInt("database.mysql.port", 3306);
                String db = getConfig().getString("database.mysql.database", "banhammer");
                String user = getConfig().getString("database.mysql.username", "root");
                String pass = getConfig().getString("database.mysql.password", "password");
                yield new MySQLDatabase(getSLF4JLogger(), host, port, db, user, pass);
            }
            default -> {
                getSLF4JLogger().error("Invalid database type: {}. Using vanilla ban system.", type);
                yield null;
            }
        };

        if (tempDatabase == null) {
            return;
        }

        // Initialize database asynchronously with configurable timeout
        long timeoutSeconds = getConfig().getLong("database.initializationTimeoutSeconds", 60);
        tempDatabase.initialize()
            .orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    if (throwable instanceof java.util.concurrent.TimeoutException) {
                        getSLF4JLogger().error("Database initialization timed out after {} seconds. " +
                                "Try increasing 'database.initializationTimeoutSeconds' in config.", timeoutSeconds);
                    } else {
                        getSLF4JLogger().error("Failed to initialize database", throwable);
                    }
                    database = null;
                } else {
                    database = tempDatabase;
                    getSLF4JLogger().info("Database initialized successfully");

                    // Initialize database-dependent components on main/global thread
                    dev.banhammer.plugin.util.FoliaScheduler.runGlobal(this, this::initializeDatabaseDependentComponents);
                }
            });
    }

    /**
     * Initializes components that depend on the database being ready.
     * Must be called on main thread.
     */
    private void initializeDatabaseDependentComponents() {
        if (database == null) {
            return;
        }

        // Update punishment manager with database reference (instead of creating new instance)
        punishmentManager.updateDatabase(database);

        // Load active mutes into cache
        punishmentManager.loadActiveMutes();

        // Initialize auto-unban scheduler if enabled
        if (getConfig().getBoolean("tempBans.enabled", true)) {
            unbanScheduler = new UnbanScheduler(this, database, discord);
            unbanScheduler.start();
        }

        getSLF4JLogger().info("Database-dependent components initialized");
    }

    /**
     * Initializes the Discord webhook based on config settings.
     */
    private void initializeDiscord() {
        boolean discordEnabled = getConfig().getBoolean("discord.enabled", false);
        String webhookUrl = getConfig().getString("discord.webhookUrl", "");

        if (discordEnabled) {
            discord = new DiscordWebhook(getSLF4JLogger(), webhookUrl, true);
        } else {
            getSLF4JLogger().debug("Discord notifications are disabled in config.yml");
            discord = null;
        }
    }

    /**
     * Re-initializes the Discord webhook (called on reload).
     * Shuts down existing webhook client before creating a new one.
     */
    public void reinitializeDiscord() {
        getSLF4JLogger().debug("Re-initializing Discord webhook...");

        // Shutdown existing webhook if present
        if (discord != null) {
            discord.shutdown();
        }

        // Re-initialize with new config
        boolean discordEnabled = getConfig().getBoolean("discord.enabled", false);
        String webhookUrl = getConfig().getString("discord.webhookUrl", "");

        if (discordEnabled) {
            discord = new DiscordWebhook(getSLF4JLogger(), webhookUrl, true);
        } else {
            getSLF4JLogger().debug("Discord notifications are disabled in config.yml");
            discord = null;
        }

        // Update punishment manager with new Discord instance
        if (punishmentManager != null) {
            punishmentManager.updateDiscord(discord);
            getSLF4JLogger().debug("PunishmentManager updated with new Discord webhook");
        }
    }

    /**
     * Re-initializes the database (called on reload).
     * This method handles enabling/disabling the database and changing settings.
     */
    public void reinitializeDatabase() {
        getSLF4JLogger().debug("Re-initializing database...");

        boolean databaseEnabled = getConfig().getBoolean("database.enabled", false);
        boolean wasEnabled = (database != null);

        // Case 1: Database was enabled, now disabled
        if (wasEnabled && !databaseEnabled) {
            getSLF4JLogger().info("Disabling database...");

            // Stop scheduler
            if (unbanScheduler != null) {
                unbanScheduler.stop();
                unbanScheduler = null;
            }

            // Shutdown database
            database.shutdown().join();
            database = null;

            // Update punishment manager to remove database reference
            punishmentManager.updateDatabase(null);

            getSLF4JLogger().info("Database disabled. Using vanilla ban system.");
        }
        // Case 2: Database was disabled, now enabled OR settings changed
        else if (!wasEnabled && databaseEnabled) {
            getSLF4JLogger().info("Enabling database...");
            initializeDatabaseAsync();
        }
        // Case 3: Database was enabled and still enabled (may need to reinit if settings changed)
        else if (wasEnabled && databaseEnabled) {
            getSLF4JLogger().debug("Database settings may have changed, reinitializing...");

            // Stop scheduler
            if (unbanScheduler != null) {
                unbanScheduler.stop();
                unbanScheduler = null;
            }

            // Shutdown old database
            database.shutdown().join();
            database = null;

            // Initialize new database
            initializeDatabaseAsync();
        }
        else {
            getSLF4JLogger().debug("Database remains disabled.");
        }
    }

    /**
     * Generates a unique hash salt if one doesn't exist or is the default value.
     * Also validates existing salts for minimum security requirements.
     */
    private void generateHashSaltIfNeeded() {
        String currentSalt = getConfig().getString("privacy.ipHashSalt", "");

        // Check if salt needs to be generated or is invalid
        boolean needsNewSalt = currentSalt.isEmpty() ||
                              currentSalt.equals("change-me-to-random-salt") ||
                              !isValidSalt(currentSalt);

        if (needsNewSalt) {
            if (!currentSalt.isEmpty() && !currentSalt.equals("change-me-to-random-salt")) {
                getSLF4JLogger().warn("⚠ Existing salt is too weak (length: {}). Generating new salt...", currentSalt.length());
            }

            String newSalt = generateRandomSalt(32);
            getConfig().set("privacy.ipHashSalt", newSalt);

            // Improved error handling for config saving
            try {
                saveConfig();
                getSLF4JLogger().info("Generated unique IP hash salt for this server");
                getSLF4JLogger().warn("⚠ WICHTIG: Dein Hash-Salt wurde automatisch generiert.");
                getSLF4JLogger().warn("⚠ Lösche ihn NICHT aus der config.yml, sonst können IPs nicht mehr zugeordnet werden!");
            } catch (Exception e) {
                getSLF4JLogger().error("Failed to save config with new hash salt! " +
                        "Please ensure the plugin has write permissions to the config directory.", e);
                getSLF4JLogger().error("⚠ IP anonymization may not work correctly until config is writable!");
                // Don't fail plugin startup - use in-memory salt as fallback
            }
        } else {
            // Salt exists and is valid
            getSLF4JLogger().info("Using existing IP hash salt (length: {})", currentSalt.length());
        }
    }

    /**
     * Validates that a salt meets minimum security requirements.
     *
     * @param salt The salt to validate
     * @return true if salt is strong enough, false otherwise
     */
    private boolean isValidSalt(String salt) {
        if (salt == null || salt.length() < 16) {
            return false; // Minimum 16 characters
        }

        // Check for sufficient character variety (at least 3 different character types)
        boolean hasLower = salt.chars().anyMatch(Character::isLowerCase);
        boolean hasUpper = salt.chars().anyMatch(Character::isUpperCase);
        boolean hasDigit = salt.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = salt.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));

        int variety = (hasLower ? 1 : 0) + (hasUpper ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);

        return variety >= 3; // At least 3 types of characters
    }

    /**
     * Generates a random alphanumeric salt.
     *
     * @param length The length of the salt
     * @return A random salt string
     */
    private String generateRandomSalt(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*-_=+";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder salt = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            salt.append(chars.charAt(random.nextInt(chars.length())));
        }

        return salt.toString();
    }

    // Getters

    public static BanHammerPlugin get() {
        return instance;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public NamespacedKey pdcKey() {
        return pdcKey;
    }

    public Database getDatabase() {
        return database;
    }

    public DiscordWebhook getDiscord() {
        return discord;
    }

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public JailManager getJailManager() {
        return jailManager;
    }

    public dev.banhammer.plugin.gui.StatisticsGUI getStatisticsGUI() {
        return statisticsGUI;
    }

    public dev.banhammer.plugin.preset.PresetManager getPresetManager() {
        return presetManager;
    }

    public dev.banhammer.plugin.update.ModrinthUpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public dev.banhammer.plugin.util.ResourcePackSender resourcePackSender() {
        return resourcePackSender;
    }
}
