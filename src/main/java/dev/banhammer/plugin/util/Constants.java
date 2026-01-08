package dev.banhammer.plugin.util;

/**
 * Constants used throughout the BanHammer plugin.
 * Centralizes all magic numbers for better maintainability.
 *
 * @since 3.0.0
 */
public final class Constants {

    private Constants() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ===== TARGETING =====

    /** Maximum distance in blocks for ray-tracing player targets */
    public static final double RAY_TRACE_MAX_DISTANCE = 5.0;

    /** Entity size for ray-trace collision detection */
    public static final double RAY_TRACE_SIZE = 0.2;

    // ===== EFFECTS - SOUND =====

    /** Volume for thunder sound effect */
    public static final float SOUND_THUNDER_VOLUME = 1.0f;

    /** Volume for explosion sound effect */
    public static final float SOUND_EXPLOSION_VOLUME = 0.6f;

    /** Pitch for all sound effects */
    public static final float SOUND_PITCH = 1.0f;

    // ===== EFFECTS - PARTICLES =====

    /** Number of critical hit particles to spawn */
    public static final int PARTICLE_CRIT_COUNT = 40;

    /** Number of smoke particles to spawn */
    public static final int PARTICLE_SMOKE_COUNT = 30;

    /** Y-axis offset for particle spawn location */
    public static final double PARTICLE_Y_OFFSET = 1.0;

    /** X-axis spread for critical particles */
    public static final double PARTICLE_CRIT_OFFSET_X = 0.4;

    /** Y-axis spread for critical particles */
    public static final double PARTICLE_CRIT_OFFSET_Y = 0.6;

    /** Z-axis spread for critical particles */
    public static final double PARTICLE_CRIT_OFFSET_Z = 0.4;

    /** Speed for critical particles */
    public static final double PARTICLE_CRIT_SPEED = 0.02;

    /** Spread for smoke particles (all axes) */
    public static final double PARTICLE_SMOKE_OFFSET = 0.5;

    /** Speed for smoke particles */
    public static final double PARTICLE_SMOKE_SPEED = 0.01;

    // ===== TIME CONVERSION =====

    /** Seconds in a day */
    public static final long SECONDS_PER_DAY = 86400L;

    /** Seconds in an hour */
    public static final long SECONDS_PER_HOUR = 3600L;

    /** Seconds in a minute */
    public static final long SECONDS_PER_MINUTE = 60L;

    /** Milliseconds in a second */
    public static final long MILLIS_PER_SECOND = 1000L;

    // ===== ITEM =====

    /** PDC marker value for identifying ban hammer items */
    public static final byte HAMMER_PDC_MARKER = (byte) 1;

    // ===== DATABASE =====

    /** Maximum pool size for database connections */
    public static final int DB_MAX_POOL_SIZE = 10;

    /** Minimum idle connections in pool */
    public static final int DB_MIN_IDLE = 2;

    /** Connection timeout in milliseconds */
    public static final long DB_CONNECTION_TIMEOUT = 30000L;

    /** Maximum connection lifetime in milliseconds (30 minutes) */
    public static final long DB_MAX_LIFETIME = 1800000L;

    // ===== VALIDATION =====

    /** Maximum length for ban/kick reasons */
    public static final int MAX_REASON_LENGTH = 500;

    /** Maximum length for player names */
    public static final int MAX_PLAYER_NAME_LENGTH = 16;

    // ===== API =====

    /** Default REST API port */
    public static final int DEFAULT_API_PORT = 8080;

    /** API rate limit requests per minute */
    public static final int API_RATE_LIMIT = 60;

    // ===== DISCORD =====

    /** Discord embed color for bans (red) */
    public static final int DISCORD_COLOR_BAN = 0xFF0000;

    /** Discord embed color for kicks (orange) */
    public static final int DISCORD_COLOR_KICK = 0xFFA500;

    /** Discord embed color for unbans (green) */
    public static final int DISCORD_COLOR_UNBAN = 0x00FF00;

    /** Discord embed color for mutes (yellow) */
    public static final int DISCORD_COLOR_MUTE = 0xFFFF00;

    // ===== GUI =====

    /** Inventory size for ban history GUI */
    public static final int GUI_BAN_HISTORY_SIZE = 54;

    /** Inventory size for appeals GUI */
    public static final int GUI_APPEALS_SIZE = 45;

    /** Items per page in paginated GUIs */
    public static final int GUI_ITEMS_PER_PAGE = 36;

    // ===== SCHEDULER =====

    /** Check for expired bans every X ticks (20 ticks = 1 second) */
    public static final long SCHEDULER_BAN_CHECK_INTERVAL = 1200L; // 60 seconds

    /** Delay before first ban check in ticks */
    public static final long SCHEDULER_BAN_CHECK_DELAY = 200L; // 10 seconds

    // ===== JAIL =====

    /** Default jail region name */
    public static final String DEFAULT_JAIL_REGION = "jail";

    /** Jail check interval in ticks */
    public static final long JAIL_CHECK_INTERVAL = 20L; // 1 second
}
