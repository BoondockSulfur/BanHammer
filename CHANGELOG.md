# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [4.1.0] - 2026-08-08

Result of a full audit of the code base. Nothing in this release adds a new feature for its
own sake; it makes the existing ones behave as documented.

### 🔒 Security

- **MiniMessage injection through player input.** Every message was built by string
  substitution and *then* parsed as MiniMessage, so an appeal text such as
  `<click:run_command:'/op me'>…</click>` became a real clickable component in staff chat -
  and `/appeal` is available to every player by default. Substituted values are now escaped.
- **Statistics GUI could be opened without permission.** The GUI was recognised by its window
  title, so renaming a shulker box to "Leaderboard" and putting an item called "Zurück" inside
  it opened the staff leaderboard. Menus are now identified by an `InventoryHolder` and every
  screen re-checks `banhammer.stats`. This also stops BanHammer from cancelling clicks in
  unrelated inventories that happen to share a word in their title.
- **`banhammer.bypass` was ignored by all commands.** Only the hammer honoured it, so a
  moderator with `banhammer.mute` could mute or jail a protected player. There is now a single
  `canPunish` check used by every path, including self-punishment protection.
- **`banhammer.ipban` was never checked.** It existed in `plugin.yml` but appeared nowhere in
  the code; anyone with `banhammer.use` could issue IP bans via a preset.
- **Webhook token written to the log.** A webhook URL that failed the format check was logged
  in full - including a valid `canary.discord.com` link. The URL is no longer logged.
- **Discord markdown injection.** Appeal texts and reasons went unescaped into embed fields,
  which render markdown, allowing masked links in the staff channel.

### 🐛 Bug fixes

- **An unreadable duration no longer means "permanent".** `/mute Steve 1w`, `/mute Steve 5` or
  any typo silently produced a *permanent* punishment. Parsing now distinguishes "explicitly
  permanent" from "not understood" and reports the latter. Weeks, months and years are
  supported, negative and absurd values are rejected, and the duplicate parser in
  `PunishmentCommands` (which the actual commands used) is gone.
- **Auto-ban repeated forever.** The warning count had no `active` filter and no time window,
  and was never reset, so *every* warning past the threshold triggered another ban. Warnings
  are now consumed by the ban they trigger and can expire (`warnings.expireAfterDays`).
- **`/unmute` did nothing without a database.** The cache was only cleared in the database
  branch, making mutes unliftable on servers running without one.
- **IP-ban records had no IP.** The address was read *after* the player was kicked, by which
  time `getAddress()` returns null.
- **A failed jail was reported as success.** If jailing failed, an active JAIL record was still
  written and staff were told it worked, while the player walked around free.
- **`BanList` was modified from async threads** by the unban scheduler and the appeal-approval
  path. `banned-players.json` is not thread-safe, and on Folia these calls throw outright -
  auto-unban was simply broken there.
- **Expired punishments could be processed forever.** A failing database write left the record
  active, so the same expiry was re-announced to Discord every 60 seconds. Deactivation is now
  a compare-and-set that runs first; only the winner performs the visible side effects.
- **Releasing from an unloaded world threw.** `Location#getWorld()` throws rather than
  returning null once the world is gone, aborting the release after the tracking maps had
  already been cleared - leaving the player stuck in jail.
- **MySQL was likely unusable.** The driver was never registered (SQLite did it, MySQL did
  not), and the shade plugin had no `ServicesResourceTransformer`, so the two drivers'
  service files overwrote each other in the jar.
- **No schema migration existed.** `CREATE TABLE IF NOT EXISTS` silently does nothing on an
  existing table, so a column added by an update only surfaced as "no such column" *after* a
  punishment had been applied. There is now a schema version and column reconciliation.
- **MySQL rejected long names.** `VARCHAR(16)` made Geyser/Bedrock names fail with "data too
  long" while SQLite accepted the identical punishment. Name columns are widened on upgrade.
- **Staff statistics split on a name change.** Grouping included `staff_name`, so a rename
  produced two rows for one UUID - the single-staff query then reported only the first.
- **Errors vanished.** Roughly a dozen database chains had no error handler: with the database
  down, a ban was applied in-game, the record was lost, and staff got no message at all.
- **A jailed player could escape during relog.** The enforcement cache was rebuilt only after
  an async lookup returned; players are now blocked first and released if the lookup says so.
- **Effects ran before the permission check.** Lightning, sound and knockback were applied and
  *then* the action was refused - a grief tool for anyone holding a hammer.
- **Reload was half-applied.** The mute and jail listeners were registered only if enabled at
  startup, and the blocked-command list, jail location and Essentials toggle needed a full
  restart. Listeners are always registered and read their configuration per event.
- Further fixes: double `UnbanScheduler` after a fast double reload; leaked HikariCP pool on
  initialization timeout; a closed database left wired into the punishment manager; `/bh give`
  reporting success with a full inventory; `/bh history` loading 1000 rows to show ten; N+1
  queries when restoring jails; items lost by dragging into the GUI; `AIR` as `item.material`
  throwing on every `/bh give`.

### ✨ Changed

- **Console replies are delivered.** Command output produced by a database query used to be
  scheduled for the next tick; RCON closes its connection as soon as the command returns, so
  those replies were lost without a trace. Replies now go out synchronously when already on the
  main thread, and an RCON caller's deferred replies are mirrored to the server log.
- **Console and RCON can punish.** All punishment methods take a `CommandSender`; console
  actions are attributed to a reserved UUID. **API change:** `getStaff()` on the three
  BanHammer events now returns `CommandSender`; use `getStaffPlayer()` for a `Player`.
- **`/appeal` works against any active punishment** (mute, jail, warning, ban), not only bans.
  Restricted to bans it was unreachable: a banned player cannot log in to type it.
- **Bans are keyed on the account, not the name.** A name ban is shed by simply renaming, and
  whoever later claims the freed name inherits it. Bans now go through Paper's profile ban list
  (UUID + name). Both views write the same `banned-players.json`, so no migration is needed, and
  pardoning still clears legacy name-only entries left by older versions or vanilla `/ban`.
- **Download links are clickable labels.** Update and resource-pack notices now read
  `Download here: [Modrinth] [CurseForge]` instead of printing a raw URL that wraps across
  lines. Providers are configurable under `downloadLinks` and `resourcePackHint.links`; an
  entry with an empty URL is hidden.
- **Message files are kept up to date.** Keys added by a plugin update are now written into the
  server's `messages_*.yml` (after a one-time `.backup`) and logged, instead of only resolving
  invisibly against the bundled defaults. Existing values are never overwritten.
- **The `/banhammer` usage line is generated.** Its subcommands were maintained by hand in three
  places - dispatch, tab completion and the message text - which is how the usage line kept
  advertising a `pack` subcommand that no longer exists, forever, on any server whose message
  file predated its removal. There is now one `Subcommand` list feeding all three, and the usage
  text only supplies the sentence around a `{commands}` placeholder. It also lists just the
  subcommands the sender may actually use.
- **Menus and Discord messages are translatable.** The previously hard-coded English GUI labels
  and Discord embed titles moved into `messages_*.yml` (`gui:` and `discord:` sections). Menu
  buttons are identified by data stored on the item, so renaming a label cannot break
  navigation. The history GUI now pages instead of stopping after 36 entries.
- **Configuration is honest.** Options that were documented but never read are now implemented:
  `logging.*`, `privacy.dataRetention.*`, `discord.notifications.*`, `discord.showStaffName`,
  `discord.showServerName`, `tempBans.notifyOnExpire`, `ipBan.enabled`, `ipBan.autoIpBan`,
  `database.sqlite.file`, `item.giveOnJoin`, `validation.requireReason`. Options superseded by
  the preset system (`ban.mode`, `ban.reason`, `ban.duration`, `kick.*`) were removed.
- **`config-enhanced.yml` deleted.** It described a REST API, Redis, LuckPerms/Vault hooks,
  WorldGuard region bans and "ML ban evasion" - none of which exist - while omitting the
  features that do. `config.yml` is now the single, accurate reference.
- **IP hashing uses HMAC-SHA256** instead of an unkeyed `SHA-256(ip + salt)`, and the
  documentation no longer calls it anonymisation: with the salt it is reversible.
- The IP hash salt is no longer silently regenerated. The old strength check replaced any
  admin-chosen salt that lacked three character classes, invalidating every stored hash -
  exactly what the warning printed beside it told admins to avoid.
- Mutes now also cover signs and books (`mute.preventSigns`, `mute.preventBooks`).
- Muted or jailed players keep access to `/appeal` and private messages.

### 📦 Packaging

- **The jar shrank from 22 MB to ~266 KB.** HikariCP, both JDBC drivers, Gson and the Discord
  webhook library (which drags in OkHttp and the Kotlin stdlib) are now declared under
  `libraries:` in `plugin.yml`: Paper resolves them from Maven Central on first start and loads
  them in an isolated class loader. That also removes every hand-maintained relocation and any
  possibility of clashing with another plugin's copy of the same library.
  **Note:** the server needs internet access the first time it starts this version; the
  artifacts are then cached in the server's `libraries` folder.

### ✅ Quality

- **Test suite added** - 52 tests, including integration tests that run the SQL layer against a
  real SQLite database: schema creation, migration from a pre-4.1 schema, the compare-and-set
  updates, the warning window, statistics grouping after a name change, and the retention purge.
- **Verified on a live server** - Paper 26.1.2 on Java 25, upgrading in place from 4.0.1 with an
  existing SQLite database: the runtime library loading, the schema migration (11 existing
  records preserved), the Essentials hook, `/bh reload`, and the history and statistics commands
  were all exercised end to end.
- **Compiler diagnostics** - `-Xlint:all` is on and the build is warning-free. A SpotBugs setup
  is included behind `-Pstatic-analysis`; it is off by default only because SpotBugs cannot yet
  read Java 25 bytecode.

### 🔧 Internal

- SQLite and MySQL share one `AbstractSqlDatabase`; all queries exist exactly once, which is
  what stopped the two backends from drifting apart.
- Database work runs on a dedicated bounded thread pool instead of the common ForkJoinPool.
- Settings are read once per reload into an immutable snapshot, so async tasks no longer read
  a `FileConfiguration` that `/bh reload` is replacing underneath them.
- `teleportAsync` no longer performs a synchronous teleport on Paper (chunk generation on the
  main thread).
- Essentials reflection is resolved and cached at startup, with a compatibility check, instead
  of being re-resolved on every movement packet.
- Dead code removed (`ReflectionUtil`, `Hex`, unused constants); Paper API version pinned;
  OkHttp, Okio and the Kotlin stdlib are now relocated.

---

## [4.0.1] - 2026-06-03

### 🐛 Bug Fixes

#### Jail System
- **Fixed jail escape via relogging** — jailed players are now re-enforced on join (`JailListener#onJoin` → `JailManager#restoreJailOnJoin`): the enforcement cache is rebuilt on every join (even within the offline-cleanup window) and restoration also works without a database (the periodic cleanup keeps active jails in memory when no DB is configured). Previously the cache was cleared on quit and never restored, so a player could leave jail simply by reconnecting.
- **Fixed jails not surviving server restarts** — `loadJailedPlayers()` now runs in `initializeDatabaseDependentComponents()` after the (asynchronous) database is ready, instead of during `onEnable` when it was still `null`.
- **`/unjail` now works for offline players** — the database record is released even when the target is not online (previously rejected outright).
- **Fixed jail enforcement being blocked by the plugin's own teleports** — `JailListener#onTeleport` now exempts `PLUGIN`-cause teleports. Previously the plugin's own "teleport back into jail" (and, on Folia, the initial jailing teleport) was cancelled by the very teleport-prevention handler, so a jailed player could leave the jail radius and never be pulled back.
- **Temporary jails now auto-release without a database** — `JailManager` tracks an in-memory expiry as a fallback when no database is configured. With a database the `UnbanScheduler` remains the sole authority, so there is no duplicate handling or leaked entry.
- **Fixed return-location being clobbered on re-jail** — the pre-jail location is now saved with `putIfAbsent`, so restoring a jail (relog/restart) no longer overwrites the player's real return location with the jail spot.

#### Essentials Integration
- **Essentials is now always preferred when hooked** — when Essentials is present, jails are created and managed in Essentials; the built-in jail system is no longer used as a fallback in that case.
- **Auto-creates an Essentials jail when none exists** — if Essentials is hooked but no jail has been configured, BanHammer registers its configured jail location as an Essentials jail (via `setJail`) instead of falling back to the built-in system. An existing, admin-configured Essentials jail is preferred when present.
- **Cell selection for `/jail`** — new syntax `/jail <player> <duration> [cell] [reason]`. The optional `cell` targets a specific Essentials jail; when omitted, the configurable default cell is used. Tab-completion suggests existing cells, and a non-existent cell is rejected with the list of available ones. The hammer and reason-only commands use the default cell. Case-insensitive cell matching.
- **`/jail` no longer requires a built-in jail location when Essentials is hooked** — the location now comes from Essentials.

#### Configuration
- **New `punishmentTypes.jail.useEssentials` toggle** — enable/disable the Essentials jail hook from the config (default `true`). When `false`, BanHammer always uses its built-in jail even if Essentials is installed. Applies on (re)start.
- **New `punishmentTypes.jail.essentialsDefaultJail`** (default `"1"`) — the Essentials cell used by the hammer and by `/jail` when no cell is specified.

#### Punishments / IP Bans
- **Fixed IP-ban removal with anonymization enabled** — `/unban` no longer attempts to pardon an anonymized IP. The real IP is only pardoned when `privacy.ipAnonymization: NONE`; otherwise a hint to use the vanilla `/pardon-ip` is logged. Applied to both manual unban and the auto-unban scheduler.
- **Muted players can no longer bypass chat blocking via namespaced commands** (e.g. `/minecraft:msg`).

#### Presets
- **Fixed memory leak** — per-player preset selections are now cleared on quit.
- **Thread-safety** — preset lists are immutable snapshots swapped atomically on reload, preventing a possible `ArithmeticException` (modulo by zero) if a player cycled presets during `/bh reload`.
- **Modernized sound resolution** — new registry-based `Sounds` utility resolves both namespaced keys (`block.note_block.pling`) and legacy constants (`BLOCK_NOTE_BLOCK_PLING`), removing the deprecated `Sound.valueOf` warnings.

#### Stability / Logging
- Removed noisy `INFO`-level debug logging from the jail, hammer-use, ban and Discord code paths (now `debug`).
- `ModrinthUpdateChecker` shared fields (`latestVersion`, `downloadUrl`, `changelogUrl`, `lastCheck`) are now `volatile` for correct visibility between the async check and the main thread.
- `GUIListener` no longer risks a `NullPointerException` on inventory items without a display name.

---

## [4.0.0] - 2026-05-02

### 🚀 Major Release - Paper 26.1.x Support

#### Breaking Changes
- **Minimum Java version:** 25 (up from 21)
- **Minimum Minecraft version:** 26.1.x (Paper 26.1.2+)
- **api-version:** `26.1` in plugin.yml
- Not compatible with 1.21.x servers (use v3.1.1 for 1.21.x)

#### Modernized API Usage
- **Removed all deprecated `kickPlayer(String)` calls** — now uses `Player.kick(Component)` everywhere
- **Removed `Bukkit.broadcastMessage(String)`** — now uses `Bukkit.broadcast(Component)`
- **Removed legacy `§` color codes** — Update Checker now uses Adventure Component API with clickable links
- **Removed deprecated `setResourcePack()` fallbacks** — now uses Adventure `ResourcePackRequest` API exclusively
- **Removed deprecated `Server.getResourcePack()` fallback** — uses `Server.getServerResourcePack()` only
- **Removed reflection-based ResourcePack sending** — direct API calls now that Paper 26.x provides them natively
- **Improved `getOfflinePlayer(String)` usage** — uses `getOfflinePlayerIfCached()` first, deprecated call only as last resort
- **Replaced `Material.CHAIN`** (removed in 26.1) with `Material.IRON_BARS` in Statistics GUI

#### Build System Updates
- Updated `maven-compiler-plugin` to 3.14.0 (Java 25 support)
- Updated `maven-shade-plugin` to 3.6.1 (class file version 69 / Java 25 support)
- Paper API dependency uses new version format: `[26.1.2.build,)`

#### Resource Pack
- **Removed built-in resource pack sending** — pack is now a separate download on [Modrinth](https://modrinth.com/resourcepack/bs-banhammer-resource-pack)
- Removed `ResourcePackSender`, `ResourcePackListener`, and all `resourcePack.*` config options
- Added clickable join hint for staff (`resourcePackHint.enabled: true` in config, disable if not needed)

#### Metrics
- Added [bStats](https://bstats.org) integration (Plugin ID: 31076)

#### Update Checker
- Game version filter (from v3.1.1) ensures 1.21.x users don't see 4.0.0 updates

---

## [3.1.1] - 2026-05-02

### 🔧 Improvement

#### Update Checker - Game Version Filter
- **Update notifications are now filtered by Minecraft version** — servers only see updates compatible with their game version
- Uses `Bukkit.getMinecraftVersion()` to detect the running server version
- Passes `game_versions` parameter to Modrinth API to filter releases
- **Why:** Prepares for Paper 26.x support — 1.21.x users won't receive update notifications for incompatible 26.x releases (and vice versa)

---

## [3.1.0] - 2026-04-24

### 🎉 New Feature

#### Folia Support (Dual-Compatibility)
- **Paper + Folia** from a single JAR — automatically detects the platform at startup
- New `FoliaScheduler` utility class abstracts all scheduler, teleport and kick operations
- `plugin.yml` declares `folia-supported: true`

**Migrated APIs:**
- All `Bukkit.getScheduler().runTask()` calls → `FoliaScheduler.runGlobal()` / `runOnEntity()`
- All `runTaskTimerAsynchronously()` calls → `FoliaScheduler.runAsyncRepeating()`
- All `player.teleport()` calls → `FoliaScheduler.teleportAsync()` (uses `teleportAsync()` on Folia)
- All `BukkitTask` fields → `Object` with `FoliaScheduler.cancelTask()`
- GUI `openInventory()` calls run on entity scheduler for correct thread ownership
- Resource pack sending uses entity-delayed scheduler

**Files changed:**
- `BanHammerPlugin.java` — Folia detection on startup
- `PunishmentManager.java` — 13 scheduler replacements
- `UnbanScheduler.java` — async scheduler, entity scheduler for jail release
- `JailManager.java` — 4 teleport migrations, scheduler migration
- `ModrinthUpdateChecker.java` — async scheduler, global scheduler for notifications
- `StatisticsGUI.java` — entity scheduler for inventory operations
- `ResourcePackListener.java` — entity-delayed scheduler
- `EssentialsJailIntegration.java` — async teleport

**No changes needed:** `HammerListener.java`, `AppealCommand.java`, `BanHammerCommand.java` — events already fire on correct regional threads.

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
