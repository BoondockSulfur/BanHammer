package dev.banhammer.plugin;

import dev.banhammer.plugin.command.AppealCommand;
import dev.banhammer.plugin.command.BanHammerCommand;
import dev.banhammer.plugin.command.PunishmentCommands;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.MySQLDatabase;
import dev.banhammer.plugin.database.SQLiteDatabase;
import dev.banhammer.plugin.gui.StatisticsGUI;
import dev.banhammer.plugin.integration.DiscordWebhook;
import dev.banhammer.plugin.integration.EssentialsJailIntegration;
import dev.banhammer.plugin.listener.GUIListener;
import dev.banhammer.plugin.listener.HammerListener;
import dev.banhammer.plugin.listener.JailListener;
import dev.banhammer.plugin.listener.JoinNotificationListener;
import dev.banhammer.plugin.listener.MuteListener;
import dev.banhammer.plugin.manager.JailManager;
import dev.banhammer.plugin.manager.PunishmentManager;
import dev.banhammer.plugin.preset.PresetManager;
import dev.banhammer.plugin.scheduler.UnbanScheduler;
import dev.banhammer.plugin.update.ModrinthUpdateChecker;
import dev.banhammer.plugin.util.ConfigValidator;
import dev.banhammer.plugin.util.FoliaScheduler;
import dev.banhammer.plugin.util.Messages;
import dev.banhammer.plugin.util.Settings;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main plugin class for BanHammer.
 *
 * @since 3.0.0
 */
public class BanHammerPlugin extends JavaPlugin {

    private static BanHammerPlugin instance;

    private Settings settings;
    private Messages messages;
    private NamespacedKey pdcKey;

    private volatile Database database;
    private volatile DiscordWebhook discord;
    private EssentialsJailIntegration essentialsJail;
    private PunishmentManager punishmentManager;
    private volatile UnbanScheduler unbanScheduler;
    private JailManager jailManager;
    private StatisticsGUI statisticsGUI;
    private PresetManager presetManager;
    private ModrinthUpdateChecker updateChecker;

    /** Guards against two overlapping database initializations (e.g. a rapid double reload). */
    private final AtomicBoolean databaseInitializing = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        new org.bstats.bukkit.Metrics(this, 31076);

        FoliaScheduler.init();
        if (FoliaScheduler.isFolia()) {
            getSLF4JLogger().info("Folia detected - using region-based schedulers");
        }

        saveDefaultConfig();

        if (!ConfigValidator.validate(getConfig(), getSLF4JLogger())) {
            getSLF4JLogger().error("Configuration validation failed - please fix the errors listed above.");
            getSLF4JLogger().error("BanHammer will run with defaults for the invalid values.");
        }

        generateHashSaltIfNeeded();

        settings = new Settings(this);
        messages = new Messages(this);
        pdcKey = new NamespacedKey(this, "ban_hammer");

        initializeDiscord();

        essentialsJail = new EssentialsJailIntegration(this, getSLF4JLogger(), settings.jail().useEssentials());
        jailManager = new JailManager(this, essentialsJail);
        jailManager.start();
        punishmentManager = new PunishmentManager(this, null, discord);
        presetManager = new PresetManager(this);

        updateChecker = new ModrinthUpdateChecker(this);
        updateChecker.start();

        registerListeners();
        registerCommands();

        if (getConfig().getBoolean("database.enabled", false)) {
            initializeDatabaseAsync();
        } else {
            getSLF4JLogger().info("Database is disabled - using the vanilla ban system only.");
        }

        getSLF4JLogger().info("BanHammer v{} enabled successfully!", getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (unbanScheduler != null) {
            unbanScheduler.stop();
        }
        if (jailManager != null) {
            jailManager.shutdown();
        }

        if (database != null) {
            // Bounded: a hung database must not stop the server from shutting down.
            awaitShutdown(database, 15);
            database = null;
        }

        if (discord != null) {
            discord.shutdown();
        }

        getSLF4JLogger().info("BanHammer disabled.");
    }

    private void awaitShutdown(Database target, int timeoutSeconds) {
        try {
            target.shutdown().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            getSLF4JLogger().warn("Database did not shut down within {}s: {}", timeoutSeconds, e.toString());
        }
    }

    // ==================== Registration ====================

    /**
     * Registers all listeners unconditionally.
     *
     * <p>The mute and jail listeners used to be registered only when their feature was
     * enabled at startup, so enabling the feature and running {@code /bh reload} produced a
     * half-working state: {@code /mute} wrote a record but the player could still talk.
     * Each listener now checks its own toggle when an event arrives.
     */
    private void registerListeners() {
        var pluginManager = getServer().getPluginManager();

        pluginManager.registerEvents(new HammerListener(this), this);
        pluginManager.registerEvents(new JoinNotificationListener(this), this);
        pluginManager.registerEvents(new MuteListener(this), this);

        JailListener jailListener = new JailListener(this);
        pluginManager.registerEvents(jailListener, this);
        jailManager.setJailListener(jailListener);

        statisticsGUI = new StatisticsGUI(this);
        pluginManager.registerEvents(new GUIListener(this, statisticsGUI), this);
    }

    private void registerCommands() {
        var cmd = new BanHammerCommand(this);
        var handle = Objects.requireNonNull(getCommand("banhammer"),
                "Command 'banhammer' is not declared in plugin.yml");
        handle.setExecutor(cmd);
        handle.setTabCompleter(cmd);

        var appealCmd = new AppealCommand(this);
        Objects.requireNonNull(getCommand("appeal"),
                "Command 'appeal' is not declared in plugin.yml").setExecutor(appealCmd);

        var punishCmd = new PunishmentCommands(this);
        for (String name : new String[]{"mute", "unmute", "jail", "unjail", "warn", "setjail"}) {
            var command = Objects.requireNonNull(getCommand(name),
                    "Command '" + name + "' is not declared in plugin.yml");
            command.setExecutor(punishCmd);
            command.setTabCompleter(punishCmd);
        }
    }

    // ==================== Database ====================

    /**
     * Creates and initializes the configured database off the main thread.
     */
    private void initializeDatabaseAsync() {
        if (!databaseInitializing.compareAndSet(false, true)) {
            getSLF4JLogger().warn("Database initialization is already in progress - ignoring this request.");
            return;
        }

        Database tempDatabase = createDatabase();
        if (tempDatabase == null) {
            databaseInitializing.set(false);
            return;
        }

        long timeoutSeconds = getConfig().getLong("database.initializationTimeoutSeconds", 60);

        CompletableFuture<Void> init = tempDatabase.initialize();
        init.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        if (throwable instanceof java.util.concurrent.TimeoutException) {
                            getSLF4JLogger().error("Database initialization timed out after {} seconds. "
                                    + "Increase 'database.initializationTimeoutSeconds' in config.yml.", timeoutSeconds);
                        } else {
                            getSLF4JLogger().error("Failed to initialize database", throwable);
                        }

                        // orTimeout only completes the *derived* future; the initialization
                        // itself keeps running and may still open a pool. Shut it down once it
                        // settles, otherwise every timeout leaks a HikariCP pool and its threads.
                        init.whenComplete((ignored, ignoredError) -> tempDatabase.shutdown());

                        database = null;
                        databaseInitializing.set(false);
                        return;
                    }

                    database = tempDatabase;
                    databaseInitializing.set(false);
                    getSLF4JLogger().info("Database initialized successfully");

                    FoliaScheduler.runGlobal(this, this::initializeDatabaseDependentComponents);
                });
    }

    private Database createDatabase() {
        String type = getConfig().getString("database.type", "SQLITE");
        type = type == null ? "SQLITE" : type.trim().toUpperCase(Locale.ROOT);

        return switch (type) {
            case "SQLITE" -> new SQLiteDatabase(getSLF4JLogger(), getDataFolder(),
                    getConfig().getString("database.sqlite.file", "banhammer.db"));
            case "MYSQL", "MARIADB" -> new MySQLDatabase(getSLF4JLogger(),
                    getConfig().getString("database.mysql.host", "localhost"),
                    getConfig().getInt("database.mysql.port", 3306),
                    getConfig().getString("database.mysql.database", "banhammer"),
                    getConfig().getString("database.mysql.username", "root"),
                    getConfig().getString("database.mysql.password", ""),
                    getConfig().getBoolean("database.mysql.useSsl", false));
            default -> {
                getSLF4JLogger().error("Invalid database type '{}' - using the vanilla ban system.", type);
                yield null;
            }
        };
    }

    /**
     * Wires up everything that needs a ready database. Runs on the main/global thread.
     */
    private void initializeDatabaseDependentComponents() {
        Database current = database;
        if (current == null) {
            return;
        }

        punishmentManager.updateDatabase(current);
        punishmentManager.loadActiveMutes();

        if (settings.jail().enabled()) {
            jailManager.loadJailedPlayers();
        }

        // Replace rather than add: a second reload used to leave the previous scheduler
        // running, so every expiry was processed twice.
        if (unbanScheduler != null) {
            unbanScheduler.stop();
        }
        unbanScheduler = new UnbanScheduler(this, current, discord);
        unbanScheduler.start();

        getSLF4JLogger().info("Database-dependent components initialized");
    }

    /**
     * Re-initializes the database after a configuration reload.
     */
    public void reinitializeDatabase() {
        boolean shouldBeEnabled = getConfig().getBoolean("database.enabled", false);
        Database current = database;

        // Always tear down first. Keying off "database != null" alone was wrong: while an
        // initialization was still running or had failed, the field was null and a reload
        // started a *second* initialization on top of the first.
        if (current != null) {
            getSLF4JLogger().info("Closing the current database connection...");
            if (unbanScheduler != null) {
                unbanScheduler.stop();
                unbanScheduler = null;
            }
            database = null;
            punishmentManager.updateDatabase(null);
            punishmentManager.clearMuteCache();
            // Closing waits for in-flight queries, so keep it off the main thread.
            current.shutdown();
        }

        if (shouldBeEnabled) {
            initializeDatabaseAsync();
        } else {
            getSLF4JLogger().info("Database disabled - using the vanilla ban system.");
        }
    }

    // ==================== Discord ====================

    private void initializeDiscord() {
        Settings.DiscordSettings discordSettings = settings.discord();
        discord = discordSettings.enabled() ? new DiscordWebhook(getSLF4JLogger(), messages, discordSettings) : null;
        if (discord == null) {
            getSLF4JLogger().debug("Discord notifications are disabled in config.yml");
        }
    }

    /**
     * Re-initializes the Discord webhook (called on reload).
     */
    public void reinitializeDiscord() {
        if (discord != null) {
            discord.shutdown();
            discord = null;
        }

        Settings.DiscordSettings discordSettings = settings.discord();
        if (discordSettings.enabled()) {
            discord = new DiscordWebhook(getSLF4JLogger(), messages, discordSettings);
        } else {
            getSLF4JLogger().debug("Discord notifications are disabled in config.yml");
        }

        if (punishmentManager != null) {
            punishmentManager.updateDiscord(discord);
        }
        if (unbanScheduler != null) {
            unbanScheduler.updateDiscord(discord);
        }
    }

    /**
     * Applies a configuration reload to every component that caches settings.
     */
    public void reloadAll() {
        reloadConfig();
        ConfigValidator.validate(getConfig(), getSLF4JLogger());

        settings.reload();
        messages.load();
        presetManager.reload();
        jailManager.reload();
        reinitializeDiscord();
        reinitializeDatabase();
    }

    // ==================== Hash salt ====================

    /**
     * Generates an IP hash salt on first start.
     *
     * <p>An existing salt is never replaced. The previous version regenerated any salt that
     * did not use at least three character classes, which silently invalidated every IP hash
     * already stored - the exact outcome the warning printed right below it told admins to
     * avoid.
     */
    private void generateHashSaltIfNeeded() {
        String currentSalt = getConfig().getString("privacy.ipHashSalt", "");
        boolean unset = currentSalt == null || currentSalt.isBlank()
                || currentSalt.equals("change-me-to-random-salt");

        if (!unset) {
            if (currentSalt.length() < 16) {
                getSLF4JLogger().warn("privacy.ipHashSalt is only {} characters long. A longer, random salt is "
                        + "strongly recommended - but it is kept as-is, because changing it would make every "
                        + "IP hash already stored unmatchable.", currentSalt.length());
            }
            return;
        }

        String newSalt = generateRandomSalt(32);
        getConfig().set("privacy.ipHashSalt", newSalt);

        try {
            saveConfig();
            getSLF4JLogger().info("Generated a unique IP hash salt for this server.");
            getSLF4JLogger().warn("Keep privacy.ipHashSalt in config.yml: deleting or changing it makes "
                    + "previously stored IP hashes unmatchable.");
        } catch (Exception e) {
            getSLF4JLogger().error("Failed to save config.yml with the new hash salt. Check the file permissions "
                    + "of the plugin folder; IP hashing will not be stable until this is fixed.", e);
        }
    }

    private String generateRandomSalt(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder salt = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            salt.append(chars.charAt(random.nextInt(chars.length())));
        }
        return salt.toString();
    }

    // ==================== Getters ====================

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

    public StatisticsGUI getStatisticsGUI() {
        return statisticsGUI;
    }

    public PresetManager getPresetManager() {
        return presetManager;
    }

    public ModrinthUpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}
