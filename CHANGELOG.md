# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [3.0.1] - 2026-04-18

### 🐛 Bug Fixes

#### Critical
- **Unjail Teleport nicht funktioniert:** Spieler wurden beim Release aus dem Jail nicht an ihren ursprünglichen Ort zurückteleportiert
  - **Ursache:** JailListener hat den Release-Teleport gecancelt, weil der Spieler noch im Jail-Cache war
  - **Fix:** Cache-Entfernung erfolgt jetzt VOR dem Teleport in `JailManager.releasePlayer()`
  - Gilt sowohl für `/unjail` Befehl als auch automatischen Ablauf

- **SimpleDateFormat nicht thread-safe:** `BanHammerCommand` nutzte statisches `SimpleDateFormat` aus async CompletableFuture-Callbacks
  - **Fix:** Ersetzt durch thread-sicheren `DateTimeFormatter`

- **loadActiveMutes() doppelte DB-Queries:** `CompletableFuture.allOf()` wurde aufgerufen und ignoriert, dann die gleichen 2 Queries nochmal einzeln
  - **Fix:** Sinnlosen allOf-Block entfernt (4 Queries → 2)

- **Discord Shutdown Hook Leak:** Bei jedem `/bh reload` wurde ein neuer `Runtime.addShutdownHook()` registriert ohne den alten zu entfernen
  - **Fix:** Hook-Referenz wird gespeichert und bei `shutdown()` entfernt

#### High Priority
- **getDatabase() NullPointerException:** `handleAppeals()` und `reviewAppeal()` riefen `plugin.getDatabase()` ohne Null-Check auf
  - **Fix:** Null-Checks vor allen direkten `getDatabase()`-Aufrufen in BanHammerCommand

- **unbanPlayer() findet nur BAN-Typ:** Temp-Bans und IP-Bans konnten per `/bh unban` nicht entfernt werden
  - **Fix:** Sucht jetzt nach allen Ban-Typen (BAN, TEMP_BAN, IP_BAN) und entfernt auch IP-Bans aus der IP-Banliste

- **unmutePlayer() findet nur MUTE-Typ:** Temp-Mutes konnten per `/unmute` nicht entfernt werden
  - **Fix:** Sucht jetzt nach allen Mute-Typen (MUTE, TEMP_MUTE)

- **IPv6-Validation immer true:** `isValidIP()` in UnbanScheduler gab für jede IPv6-Adresse true zurück, auch gehashte IPs
  - **Fix:** Gehashte IPs werden erkannt (kein `.`/`:` → sofort false)

- **HttpURLConnection Leak:** ModrinthUpdateChecker hat `connection.disconnect()` nie aufgerufen
  - **Fix:** `disconnect()` im finally-Block; zusätzlich Null-Checks für JSON-Response-Felder

#### Medium Priority
- **PunishmentManager Race Condition:** Manager wurde mit null-Database erstellt und bei DB-Init komplett neu erstellt — Listener behielten alte Referenz
  - **Fix:** DB/Discord-Referenzen sind jetzt `volatile` und updatebar via `updateDatabase()`/`updateDiscord()`

- **Cooldown Maps Memory Leak:** `cooldowns`, `switchCooldowns`, `switchKickJailCooldowns` in HammerListener wuchsen unbegrenzt
  - **Fix:** Cleanup bei `PlayerQuitEvent`

- **Auto-Unjail ohne Teleport:** UnbanScheduler nutzte `releasePlayerByUUID()` für abgelaufene Jails, was keinen Teleport zurück auslöst
  - **Fix:** Online-Spieler werden jetzt per `releasePlayer()` (mit Teleport) auf dem Main-Thread freigelassen

- **Essentials Jail Release ohne Rück-Teleport:** Bei Essentials-Release wurde der Spieler nicht zum Original-Ort zurückteleportiert
  - **Fix:** Return-Location wird auch bei Essentials-Release verwendet

### 🔧 Improvements
- Modrinth API Response: Robustere JSON-Parsing mit Null-Checks für `version_number`, `url` und `files`
- Doppelter `releasePlayer()`-Aufruf in `unjailPlayer()` entfernt (Caller ist verantwortlich)

---

## [3.0.0] - 2026-01-08

### 🎉 Main Features

#### Ban Presets System (NEW!)
- **Shift + Right-Click** to switch between predefined ban presets
- Actionbar feedback shows current preset when switching
- Sound feedback individually configurable per preset
- Unlimited presets definable in config.yml
- Each preset with own duration, reason, IP-ban flag and sound
- Per-player tracking of active preset
- Configurable switch cooldown (250ms default)
- Works everywhere: on players, blocks or in the air

#### Kick/Jail Presets System (NEW!)
- **Shift + Left-Click** to switch between predefined kick/jail presets
- Mix kicks and jails in one preset list (no duration = kick, with duration = jail)
- Actionbar feedback shows current preset when switching
- Sound feedback individually configurable per preset
- Unlimited presets definable in config.yml
- Per-player tracking of active kick/jail preset
- Same configurable cooldown as ban presets
- **Left-Click on Player** executes kick or jail with active preset

#### Modrinth Update Checker (NEW!)
- Automatic update checking on server startup
- Periodic checks with configurable interval (default: 6 hours)
- Admin notifications on login (permission: `banhammer.updatenotify`)
- Semantic version comparison
- Download URLs and changelog links
- Rate limiting (max 1 check per hour)
- Fully configurable in config.yml

#### Database System
- **SQLite** support for single-server (no setup required)
- **MySQL** support for multi-server networks
- Complete ban history with detailed tracking
- Automatic schema creation and migrations
- Connection pooling with HikariCP for optimal performance
- WAL mode for SQLite (better concurrency)
- Optimized indexes for fast queries

#### Auto-Unban System
- Automatic unbanning when temporary bans expire
- Configurable check intervals (default: 60 seconds)
- Asynchronous processing (doesn't block main thread)
- Event triggers for external plugins
- Discord notifications on auto-unbans
- Supports temp-bans, temp-mutes and jail times

#### Discord Integration
- Webhook-based notifications
- Color-coded embeds for different actions:
  - 🔴 Bans (Red)
  - 🟠 Kicks (Orange)
  - 🟢 Unbans (Green)
  - 🟡 Mutes (Yellow)
- Staff names, player names, duration and reason in embeds
- Server names for multi-server setups
- Configurable which events are sent
- **NEW:** Discord now works independently of database (no database required)
- **NEW:** Discord webhook reinitializes on `/bh reload` without server restart

#### Essentials Jail Integration (NEW!)
- **Soft Dependency:** Optional Essentials plugin integration via `softdepend` in plugin.yml
- **Reflection-Based:** No hard dependency - uses reflection to access Essentials API
- **Smart Preference System:** Automatically uses Essentials jail if available, falls back to built-in
- **Automatic Detection:** Checks for Essentials on startup and enables integration
- **Built-in Fallback:** Own jail system continues to work if Essentials not installed
- **Seamless Integration:** Works with existing Essentials jail configurations
- **Debug Logging:** Comprehensive DEBUG TELEPORT logs for troubleshooting

### ✨ New Features

#### Extended Punishment Types
- **Mute System**: Blocks chat and commands
  - Permanent and temporary
  - Cache-based for performance
  - Shows remaining time
- **Jail System**: Locks players in place
  - Configurable jail location via `/setjail`
  - Prevents teleportation and movement
  - Optional: Prevents damage and commands
- **Warning System**: Warnings with auto-ban
  - Counts warnings per player
  - Auto-ban after X warnings (configurable)
  - Non-active punishment (only tracking)

#### Ban Appeals System
- Players can submit appeals via `/appeal <text>`
- Cooldown system (default: 24 hours)
- Maximum appeals per punishment (default: 3)
- Staff can approve/deny appeals
- Discord notifications for new appeals
- Appeal status tracking (PENDING, APPROVED, DENIED)

#### Statistics & Leaderboards
- Tracking of all punishments per staff member
- Total counts: Bans, kicks, mutes, warnings
- Leaderboard system
- GUI view via `/bh stats`
- Exportable via REST API

#### GDPR & Privacy Features
- IP anonymization with 4 levels:
  - `NONE`: Full IP storage
  - `PARTIAL`: Last octet removed (192.168.1.0)
  - `HASH`: One-way hash with server salt
  - `FULL`: Complete masking
- Automatic salt generation
- Salt validation (min. 16 characters, 3 character types)
- Configurable data retention

### 🔧 Improvements

#### Performance Optimizations
- Asynchronous database initialization (no longer blocks server start)
- `loadActiveMutes()` loads mutes into cache on startup
- Optimized warning count via `SELECT COUNT(*)` instead of N+1 query
- SQLite connection pool set to 1 (single-writer)
- Race condition in `getActiveMute()` fixed via `computeIfPresent()`
- Composite indexes for common query patterns

#### Error Handling & Robustness
- Separate try-catch blocks in ban flow (prevents partial state)
- Improved Discord webhook validation
  - URL format check
  - Clear error messages
  - Graceful degradation
- Robust duration parsing
  - Supports: "7d", "1h30m", "PT24H", "permanent"
  - Case-insensitive
  - Warnings for invalid format
- Improved IPv6 validation via `InetAddress`

#### Thread-Safety
- Mute cache removes expired entries atomically
- Switch cooldown via ConcurrentHashMap
- Database operations fully async
- Events fired on main thread

### 📝 Commands

New commands:
- `/bh appeals` - Shows pending appeals
- `/bh approve <id> [response]` - Approves appeal
- `/bh deny <id> [response]` - Denies appeal
- `/bh stats [player]` - Shows statistics
- `/bh reload` - **IMPROVED:** Now reinitializes Discord webhook and Database connection
- `/appeal <text>` - Submits appeal
- `/mute <player> <duration> [reason]` - Mutes player
- `/unmute <player> [reason]` - Unmutes player
- `/jail <player> <duration> [reason]` - Jails player (uses Essentials if available)
- `/unjail <player> [reason]` - Releases from jail (works without database)
- `/setjail` - Sets jail location (only needed for built-in jail)
- `/warn <player> [reason]` - Warns player

### 🔑 Permissions

New permissions:
- `banhammer.appeals` - Can view appeals
- `banhammer.appeals.review` - Can review appeals
- `banhammer.appeal` - Can submit appeals (default: true)
- `banhammer.mute` - Can mute players
- `banhammer.jail` - Can jail players
- `banhammer.warn` - Can warn players
- `banhammer.stats` - Can view statistics
- `banhammer.updatenotify` - Receive update notifications

### 🎨 API

#### Custom Events
- `PlayerPunishEvent` - Fired BEFORE punishment (cancellable)
- `PlayerPunishedEvent` - Fired AFTER successful punishment
- `PlayerUnpunishedEvent` - Fired when punishment removed

#### PunishmentManager API
```java
// Fully async with CompletableFuture
pm.banPlayer(staff, victim, reason, duration, ipBan)
pm.mutePlayer(staff, victim, reason, duration)
pm.jailPlayer(staff, victim, reason, duration)
pm.warnPlayer(staff, victim, reason)
pm.getHistory(playerUuid, limit)
pm.getWarningCount(playerUuid)
```

#### Database Interface
- Abstract interface for SQLite/MySQL
- All operations via CompletableFuture
- Prepared statements against SQL injection

### 🐛 Bug Fixes

#### Critical Bug Fixes
- ✅ **Expired Punishment Spam (CRITICAL):** Fixed infinite "Found 1 expired punishment(s), processing..." spam
  - **Root Cause:** NULL UUID handling in `deactivatePunishment()` caused NullPointerException
  - **Fix:** Added null check for staffUuid in SQLiteDatabase.java:396 and MySQLDatabase.java:406
  - **Impact:** Prevented database corruption and console spam

- ✅ **Jail Auto-Release Not Working (CRITICAL):** Players weren't automatically released after jail time expired
  - **Root Cause:** Missing JAIL case in UnbanScheduler switch statement
  - **Fix:** Added JAIL case to UnbanScheduler.java:110-114
  - **Impact:** Jail time now properly expires and releases players automatically

- ✅ **Unjail Required Database:** `/unjail` command didn't work without database even with Essentials
  - **Root Cause:** Database check blocked everything in handleUnjail()
  - **Fix:** Removed database requirement, calls JailManager.releasePlayer() directly
  - **Impact:** Unjail now works with both Essentials and built-in jail without database

- ✅ **Resourcepack Not Loading:** Resourcepack wasn't being sent to players
  - **Root Cause:** Empty URL and hash in config.yml
  - **Fix:** Added proper URL and SHA-1 hash to config.yml
  - **Impact:** Resourcepack now loads correctly for all players

#### Translation & Localization Fixes
- ✅ **Incomplete Translations:** Many messages were hardcoded in German
  - **Fix:** Added 30+ new message keys to messages_de.yml and messages_en.yml
  - **Fix:** Replaced 28 hardcoded strings across PunishmentCommands.java (15), BanHammerCommand.java (11), and AppealCommand.java (2)
  - **Fix:** Extended Messages.java with 30+ new methods
  - **Impact:** Plugin is now fully translatable, no hardcoded strings remaining

#### Reload Functionality Fixes
- ✅ **Discord Not Reinitializing on Reload:** Discord webhook required server restart after config changes
  - **Fix:** Added `reinitializeDiscord()` method in BanHammerPlugin.java
  - **Impact:** Discord webhook now reloads with `/bh reload` command

- ✅ **Database Not Reinitializing on Reload:** Database connection required server restart after config changes
  - **Fix:** Added `reinitializeDatabase()` method in BanHammerPlugin.java
  - **Impact:** Database connection now reloads with `/bh reload` command

#### Essentials Integration Fixes
- ✅ **Essentials Jail Teleport Errors:** "wrong number of arguments" and "Location.getLocation()" errors
  - **Fix:** Fixed reflection to get Location directly from getJail() method
  - **Fix:** Get jail list from Essentials and use first available jail
  - **Impact:** Essentials jail integration now works correctly

- ✅ **Jail Messages Not Colored:** Jail/unjail messages showed raw MiniMessage tags instead of colors
  - **Fix:** Added proper jail/unjail message calls to both Essentials and built-in jail paths
  - **Impact:** Messages now display with proper colors

#### Logging Improvements
- ✅ **Console Spam:** Too many DEBUG messages during normal operation
  - **Fix:** Reduced routine logs to DEBUG level, only show important info (Jail, Essentials, Discord, critical problems)
  - **Impact:** Console is now clean and readable

- ✅ **Missing Debug Logs for Troubleshooting:** Hard to diagnose teleport and resourcepack issues
  - **Fix:** Added comprehensive DEBUG TELEPORT logs (EssentialsJailIntegration.java:107-133)
  - **Fix:** Added comprehensive DEBUG RESOURCEPACK logs (ResourcePackSender.java)
  - **Impact:** Easier troubleshooting with debug mode enabled

#### Previous Bug Fixes
- ✅ Temporal bans were permanent → Duration parsing fixed
- ✅ Discord webhook no error messages → Validation added
- ✅ Server start blocks on DB init → Async initialization
- ✅ Mute cache memory leak → UnbanScheduler cleans cache
- ✅ Race condition in getActiveMute() → Atomic operation
- ✅ IPv6 validation incomplete → InetAddress validation
- ✅ SQL indexes wrongly defined → Separate CREATE INDEX statements
- ✅ N+1 query for warning count → Optimized COUNT query
- ✅ Resource leaks in ban flow → Separate error handling

### 🔄 Breaking Changes

**No breaking changes** - Version 3.0.0 is fully backwards compatible with 2.x configurations.

New features are optional and must be explicitly enabled:
```yaml
database:
  enabled: false  # Default: off
discord:
  enabled: false  # Default: off
presets:
  # Created automatically if not present
kickJailPresets:
  # Created automatically if not present
updateChecker:
  enabled: true  # Default: on
```

### 📦 Dependencies

**Required:**
- Paper API: 1.21.1-R0.1-SNAPSHOT
- Java: 21
- HikariCP: 5.1.0
- SQLite JDBC: 3.47.1.0
- MySQL Connector/J: 9.1.0
- Gson: 2.11.0
- Discord Webhooks: 0.8.4

**Optional (Soft Dependencies):**
- Essentials (any recent version) - For enhanced jail system integration

### 📋 Technical Details

#### Code Quality
- Modern Java 21 features (Records, Text Blocks, Switch Expressions)
- Thread-safe implementations
- Async-first approach with CompletableFuture
- Event-based architecture
- Clean code principles

#### Architecture
- Manager pattern for business logic
- Database abstraction layer
- Event system for extensibility
- Preset system for flexible ban types
- Cache layer for performance

---

## [2.x] - Legacy

Earlier versions were simpler and had the following features:
- Basic ban/kick with BanHammer item
- Visual effects (lightning, sound, particles)
- Resource pack support
- Cooldown system
- Permission-based

**Note:** Upgrade from 2.x to 3.0.0 is seamless. All 2.x features are preserved.

**Links:**
- [GitHub Repository](https://github.com/BoondockSulfur/BanHammer)
- [Issues & Bug Reports](https://github.com/BoondockSulfur/BanHammer/issues)
- [Discord Support](https://discord.gg/xEJjF65K46)

---

## Legend

- `Added` - New features
- `Changed` - Changes to existing features
- `Deprecated` - Features to be removed soon
- `Removed` - Removed features
- `Fixed` - Bug fixes
- `Security` - Security fixes
