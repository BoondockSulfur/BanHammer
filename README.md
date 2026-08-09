# BanHammer 4.1.0 - Enhanced Edition

<div align="center">

**The ultimate ban hammer for Paper & Folia 26.1.x servers with extended moderation features**

[![Minecraft Version](https://img.shields.io/badge/Minecraft-26.1.x-brightgreen.svg)](https://papermc.io/)
[![Folia Support](https://img.shields.io/badge/Folia-Supported-blue.svg)](https://papermc.io/software/folia)
[![Java Version](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## 📋 Table of Contents

- [Overview](#overview)
- [Resource Pack](#resource-pack)
- [Features](#features)
- [Installation](#installation)
- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration](#configuration)
- [Database](#database)
- [Discord Integration](#discord-integration)
- [Events](#events)
- [FAQ](#faq)

---

## 🎯 Overview

BanHammer is a powerful moderation plugin for Minecraft Paper servers that provides a special "Ban Hammer" item. With this, administrators can ban or kick players in a dramatic and entertaining way - including lightning, particles, and sound effects!

**Version 4.1.0** is a correctness release on top of 4.0: see `CHANGELOG.md` for the full list.

**Highlights since 3.x:**
- ✅ **Paper 26.1.x Support** - Fully modernized for the new Minecraft versioning
- ✅ **Folia Support** - Dual-compatible with Paper and Folia from a single JAR
- ✅ **Ban Presets System** - Quick switching between predefined ban types
- ✅ **Kick/Jail Presets System** - Left-click preset cycling for kicks and jails
- ✅ Complete database system (SQLite & MySQL)
- ✅ Ban history with detailed tracking
- ✅ Automatic unbanning for temporary bans
- ✅ Discord integration with webhooks
- ✅ IP banning support
- ✅ Extended punishment types (Mute, Jail, Warnings)
- ✅ Ban appeals system
- ✅ Statistics and leaderboards
- ✅ Multi-language support
- ✅ Custom events for developers
- ✅ Modrinth update checker with game version filtering

---

## 🎨 Resource Pack

The BanHammer texture pack is available as a **separate download**:

➡️ **[BS-BanHammer Resource Pack on Modrinth](https://modrinth.com/resourcepack/bs-banhammer-resource-pack)**

Staff members with the `banhammer.use` permission will see a clickable download hint on join. This can be disabled in `config.yml`:

```yaml
resourcePackHint:
  enabled: false  # Set to false to disable the join hint
```

---

## ✨ Features

### 🔨 Core Features

**Ban Presets System:**
- 🎯 **Shift + Right-Click:** Switch between predefined ban presets
- 📊 **Actionbar Feedback:** Shows active preset when switching
- 🔊 **Sound Feedback:** Individual sound per preset
- ⚡ **Quick Switching:** 250ms cooldown (configurable)
- 📝 **Unlimited Presets:** Define your own ban categories
- 🎨 **Fully Configurable:** Duration, reason, IP-ban, sound per preset

**Kick/Jail Presets System:**
- 🎯 **Shift + Left-Click:** Switch between predefined kick/jail presets
- 👊 **Left-Click on Player:** Execute kick or jail with active preset
- 📊 **Actionbar Feedback:** Shows active kick/jail preset
- 🔊 **Sound Feedback:** Individual sound per preset
- 📝 **Mix Kicks & Jails:** Combine instant kicks with timed jails in one preset list
- 🎨 **Fully Configurable:** Duration (null=kick, set=jail), reason, sound per preset

**Dual-Action Hammer:**
- **Right-Click on Player:** Ban with currently selected ban preset
- **Shift + Right-Click:** Switch ban preset mode (works anywhere)
- **Left-Click on Player:** Kick or jail with active kick/jail preset
- **Shift + Left-Click:** Switch kick/jail preset mode (works anywhere)
- **Left-Click in Air:** Ray-trace targeting (up to 5 blocks)

**Visual Effects:**
- ⚡ Lightning effect at target location
- 🔊 Thunder & explosion sounds
- ✨ Particles (critical & smoke)
- 💨 Optional knockback

**Security:**
- ⏱️ Cooldown system against spam
- 🛡️ Bypass permission for protected players
- 🚫 Self-protection (no self-bans)
- 🔒 Block-breaking prevention with hammer

### 📊 Database Features

**SQLite & MySQL Support:**
```yaml
database:
  enabled: true
  type: SQLITE  # or MYSQL
```

**Tracking Features:**
- Complete ban history for every player
- Staff statistics (who issued how many bans)
- Server names for multi-server networks
- IP address tracking (optional)
- Automatic cleanup of expired bans

**Query Options:**
- History by player
- Active punishments
- Filter punishments by type
- Statistics by staff member

### ⏰ Auto-Unban System

```yaml
tempBans:
  enabled: true
  checkInterval: 60  # seconds
  notifyOnExpire: true
```

- Automatic unbanning when temporary bans expire
- Configurable check intervals
- Event triggers for external integrations
- Discord notifications

### 🎯 Ban Presets System

Define preconfigured ban types and switch between them quickly:

```yaml
presets:
  temp_1h:
    displayName: "1 Hour Ban"
    reason: "Temporary ban - 1 hour"
    duration: "1h"
    ipBan: false
    sound: "BLOCK_NOTE_BLOCK_PLING"

  temp_1d:
    displayName: "1 Day Ban"
    reason: "Temporary ban - 1 day"
    duration: "1d"
    ipBan: false
    sound: "BLOCK_NOTE_BLOCK_PLING"

  permanent:
    displayName: "Permanent Ban"
    reason: "Permanently banned"
    duration: "permanent"
    ipBan: false
    sound: "BLOCK_NOTE_BLOCK_BASS"

  permanent_ip:
    displayName: "Permanent IP-Ban"
    reason: "Permanently IP-banned"
    duration: "permanent"
    ipBan: true
    sound: "BLOCK_ANVIL_LAND"

# Cooldown for preset switching (milliseconds)
presetSwitchCooldown: 250
```

**Usage:**
1. **Shift + Right-Click** (anywhere) → Switches to next preset
2. **Actionbar** shows: `Preset: <Name> (<Duration>)`
3. **Sound** is played
4. **Right-Click on Player** → Ban with active preset

### 👊 Kick/Jail Presets System

Define preconfigured kick/jail types and switch between them quickly:

```yaml
kickJailPresets:
  quick_kick:
    displayName: "Quick Kick"
    reason: "Kicked from server"
    # No duration = kick
    sound: "BLOCK_NOTE_BLOCK_PLING"

  jail_10m:
    displayName: "10 Min Jail"
    reason: "Jailed for 10 minutes"
    duration: "10m"
    sound: "BLOCK_NOTE_BLOCK_BASS"

  jail_30m:
    displayName: "30 Min Jail"
    reason: "Jailed for 30 minutes"
    duration: "30m"
    sound: "BLOCK_NOTE_BLOCK_BASS"

  jail_1h:
    displayName: "1 Hour Jail"
    reason: "Jailed for 1 hour"
    duration: "1h"
    sound: "BLOCK_ANVIL_LAND"
```

**Usage:**
1. **Shift + Left-Click** (anywhere) → Switches to next kick/jail preset
2. **Actionbar** shows: `Kick/Jail Preset: <Name> (<Duration>)`
3. **Sound** is played
4. **Left-Click on Player** → Execute kick or jail with active preset

### 🎭 Extended Punishment Types

**Mute System:**
```yaml
punishmentTypes:
  mute:
    enabled: true
    preventChat: true
    preventCommands: true
    # A sign or a book is a chat channel too
    preventSigns: true
    preventBooks: true
```

A muted player keeps access to `/appeal`, so they can always contest the punishment.

**Jail System:**
```yaml
punishmentTypes:
  jail:
    enabled: true
    preventTeleport: true
    preventDamage: true
    # Only /appeal, /help and private messages remain usable
    preventCommands: false
    maxDistance: 10.0
```

**Essentials Jail Integration:**
- Soft dependency - no hard requirement
- Automatically uses Essentials jails if available
- Falls back to built-in jail system
- Works with existing Essentials jail configurations

Configure jail location:
```bash
# With Essentials: Configure in Essentials/jails.yml
# Built-in: Use /setjail command in-game
```

**Warnings System:**
```yaml
punishmentTypes:
  warnings:
    enabled: true
    autoBanThreshold: 3
    autoBanDuration: "7d"
    # Warnings older than this stop counting (0 = never expire)
    expireAfterDays: 90
```

The warnings that trigger an auto-ban are consumed by it, so the counter starts over rather
than banning the player again on every subsequent warning.

### ⏱️ Duration Syntax

Every command and preset that takes a duration accepts the same format:

| Unit | Meaning | Example |
|---|---|---|
| `s` | seconds | `45s` |
| `m` | minutes | `30m` |
| `h` | hours | `1h30m` |
| `d` | days | `7d` |
| `w` | weeks | `2w` |
| `mo` | months (30 days) | `3mo` |
| `y` | years (365 days) | `1y` |

ISO-8601 (`PT24H`, `P7D`) works as well, and `permanent` / `perm` never expires.

> Anything that cannot be understood is **rejected with an error**. A typo such as
> `/mute Steve 1woche` will not silently become a permanent punishment.

### 🔒 Privacy & Data Retention

```yaml
privacy:
  # NONE | PARTIAL | HASH | FULL
  ipAnonymization: "PARTIAL"
  ipHashSalt: "generated on first start"

  dataRetention:
    enabled: false
    deleteAfterDays: 365
    keepActivePunishments: true
```

| Level | Stored | Reversible |
|---|---|---|
| `NONE` | the full address | - |
| `PARTIAL` | last octet dropped (`192.168.1.0`) | no |
| `HASH` | keyed digest (HMAC-SHA256) | **yes, by anyone who knows the salt** |
| `FULL` | masked entirely | no |

`HASH` lets you correlate repeat offenders without storing the address, but it is
pseudonymisation, not anonymisation - treat `ipHashSalt` as a secret and never change it, or
every hash already stored stops matching.

> With any level other than `NONE`, an IP ban cannot be lifted automatically, because the
> stored value is no longer the real address. Use `/pardon-ip` in that case.

`dataRetention` **deletes** punishment history older than `deleteAfterDays`. It is off by
default; switch it on only if you want that.

### 🎨 Discord Integration

```yaml
discord:
  enabled: true
  webhookUrl: "https://discord.com/api/webhooks/..."
  notifications:
    bans: true
    kicks: true
    unbans: true
    mutes: true
    warnings: true
    appeals: true
```

**Features:**
- Color-coded embeds for different actions
- Automatic notifications for bans/kicks/unbans
- Staff names and server information
- Duration display for temporary bans

### 🔄 Modrinth Update Checker

```yaml
updateChecker:
  enabled: true
  checkOnStartup: true
  notifyAdmins: true
  checkInterval: 6  # hours
  modrinthProjectId: "bs-banhammer"
```

**Features:**
- Automatic update checking on startup
- Periodic checks (configurable interval)
- Admin notifications on login (with permission)
- **Game version filtering** - 1.21.x users won't see 26.x updates and vice versa
- Clickable download links, shown as `Download here: [Modrinth] [CurseForge]`

Configure which providers appear (an entry with an empty URL is hidden):

```yaml
downloadLinks:
  Modrinth: "https://modrinth.com/plugin/bs-banhammer"
  CurseForge: ""
```

The resource pack hint uses the same format under `resourcePackHint.links`, and can be turned
off entirely with `resourcePackHint.enabled: false`.

### 📝 Ban Appeals

```yaml
appeals:
  enabled: true
  minLength: 20
  cooldown: 24  # hours
  maxAppealsPerPunishment: 3
```

Players contest an **active** punishment (mute, jail, warning or ban) with `/appeal <text>`;
staff review them with `/bh appeals`, `/bh approve <id>` and `/bh deny <id>`.

> Note: a banned player cannot log in, so in-game appeals are in practice used for mutes,
> jails and warnings. Approving an appeal also lifts a matching ban if one is recorded.

### 📈 Statistics & Leaderboards

- Tracking of all punishments per staff member
- Leaderboard system
- GUI view available (`/bh gui`)

### 🌍 Multi-Language Support

```yaml
language: "en"  # or "de"
```

**Available Languages:**
- 🇩🇪 **German (de)** - Vollständig übersetzt
- 🇬🇧 **English (en)** - Fully translated

### 🔌 Developer API

**Custom Events:**
```java
// Fired BEFORE a player is punished (cancellable)
// Since 4.1.0 `staff` is a CommandSender, so console punishments are covered too.
PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, type, reason, duration);

// Fired AFTER a player was punished
PlayerPunishedEvent event = new PlayerPunishedEvent(staff, victimName, record);

// Fired when punishment is removed
PlayerUnpunishedEvent event = new PlayerUnpunishedEvent(staff, record, reason, automatic);
```

**PunishmentManager API:**
```java
PunishmentManager pm = plugin.getPunishmentManager();

// Ban a player
pm.banPlayer(staff, victim, reason, duration, ipBan)
    .thenAccept(id -> {
        // Ban successful, id is the database record ID
    });

// Get player history
pm.getHistory(playerUuid, 50)
    .thenAccept(history -> {
        // Process history
    });
```

---

## 🚀 Installation

1. **Requirements:**
   - Paper or Folia Server 26.1.x or higher
   - Java 25 or higher
   - Optional: MySQL Server (for MySQL mode)
   - Optional: Essentials Plugin (for enhanced jail system)

2. **Install Plugin:**
   ```bash
   cp banhammer-4.1.0.jar server/plugins/
   ```

   > The plugin jar is ~266 KB. Its database and Discord libraries are downloaded from Maven
   > Central by Paper on first start and cached in the server's `libraries` folder, so the
   > server needs internet access once.

   ```bash
   ```

3. **Download Resource Pack (optional):**
   - [BS-BanHammer Resource Pack](https://modrinth.com/resourcepack/bs-banhammer-resource-pack)

4. **Start server** and adjust `plugins/BanHammer/config.yml`

> **Looking for 1.21.x support?** Use [BanHammer v3.1.1](https://github.com/BoondockSulfur/BanHammer/releases/tag/v3.1.1) instead.

---

## 📝 Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/banhammer` | Main command, shows help | `banhammer.command` |
| `/bh give <player>` | Give BanHammer to player | `banhammer.give` |
| `/bh reload` | Reload config (reinitializes Discord & DB) | `banhammer.reload` |
| `/bh history <player> [page]` | Show punishment history | `banhammer.history` |
| `/bh unban <player> [reason]` | Unban a player | `banhammer.unban` |
| `/bh stats [player]` | Show statistics | `banhammer.stats` |
| `/bh gui` | Open statistics GUI | `banhammer.stats` |
| `/bh appeals` | Show pending appeals | `banhammer.appeals` |
| `/bh approve <id> [response]` | Approve appeal | `banhammer.appeals.review` |
| `/bh deny <id> [response]` | Deny appeal | `banhammer.appeals.review` |
| `/appeal <text>` | Submit appeal | `banhammer.appeal` |
| `/mute <player> <duration> [reason]` | Mute a player | `banhammer.mute` |
| `/unmute <player> [reason]` | Unmute a player | `banhammer.mute` |
| `/jail <player> <duration> [cell] [reason]` | Jail a player (`[cell]` only with EssentialsX) | `banhammer.jail` |
| `/unjail <player> [reason]` | Release from jail | `banhammer.jail` |
| `/setjail` | Set jail location | `banhammer.setjail` |
| `/warn <player> <reason>` | Warn a player | `banhammer.warn` |

**Alias:** `/bh` is shorthand for `/banhammer`

---

## 🔑 Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `banhammer.command` | Basic command access | op |
| `banhammer.use` | Can use the BanHammer | op |
| `banhammer.give` | Can give hammer to others | op |
| `banhammer.reload` | Can reload config | op |
| `banhammer.bypass` | Immune to BanHammer | op |
| `banhammer.history` | Can view history | op |
| `banhammer.history.others` | Can view others' history | op |
| `banhammer.unban` | Can unban players | op |
| `banhammer.stats` | Can view statistics and the GUI | op |
| `banhammer.stats.others` | Can view other players' statistics | op |
| `banhammer.appeals` | Can view appeals | op |
| `banhammer.appeals.review` | Can review appeals | op |
| `banhammer.appeal` | Can submit appeals | true |
| `banhammer.ipban` | Can issue IP bans with the hammer | op |
| `banhammer.mute` | Can mute players | op |
| `banhammer.jail` | Can jail players | op |
| `banhammer.setjail` | Can set jail location | op |
| `banhammer.warn` | Can warn players | op |
| `banhammer.updatenotify` | Receive update notifications | op |

---

## ⚙️ Configuration

### Basic Configuration (config.yml)

```yaml
# Item settings
item:
  material: CARROT_ON_A_STICK
  name: "<gold>Ban Hammer</gold>"
  customModelData: 812345

# Ban settings (reason and duration come from the presets)
ban:
  broadcast: true

# Give the hammer to staff on join
item:
  giveOnJoin: false

# Cooldown in seconds
cooldownSeconds: 3
presetSwitchCooldown: 250

# Effects
effects:
  lightning: true
  sound: true
  particles: true
```

### Advanced Configuration

`config.yml` documents every option inline. Every key it contains is read by the plugin -
there are no placeholder settings.

**Most Important Options:**

```yaml
# Enable database
database:
  enabled: true
  type: SQLITE

# Discord notifications
discord:
  enabled: true
  webhookUrl: "..."

# Auto-unban
tempBans:
  enabled: true
  checkInterval: 60

# Appeals system
appeals:
  enabled: true
  cooldown: 24

# Update checker
updateChecker:
  enabled: true
  checkInterval: 6

# Resource pack join hint
resourcePackHint:
  enabled: true
```

---

## 🗄️ Database

### SQLite (Default)

Automatically enabled, no additional configuration needed:

```yaml
database:
  enabled: true
  type: SQLITE
  sqlite:
    file: "banhammer.db"
```

### MySQL

For large networks with multiple servers:

```yaml
database:
  enabled: true
  type: MYSQL
  mysql:
    host: "localhost"
    port: 3306
    database: "banhammer"
    username: "root"
    password: ""
    # Require an encrypted connection; leave false for a database on localhost
    useSsl: false
```

**MySQL Advantages:**
- Shared database for all servers
- Better performance with many entries
- External access possibilities

---

## 💬 Discord Integration

### Webhook Setup

1. **Create Discord Webhook:**
   - Go to Server Settings → Integrations → Webhooks
   - Create new webhook
   - Copy webhook URL

2. **Enter in Config:**
   ```yaml
   discord:
     enabled: true
     webhookUrl: "https://discord.com/api/webhooks/..."
   ```

3. **Configure Notifications:**
   ```yaml
   notifications:
     bans: true
     kicks: true
     mutes: true
     warnings: true
     unbans: true
     appeals: true
   ```

### Embed Colors

- 🔴 **Bans:** Red (#FF0000)
- 🟠 **Kicks:** Orange (#FFA500)
- 🟢 **Unbans:** Green (#00FF00)
- 🟡 **Mutes:** Yellow (#FFFF00)

---

## 🎪 Events

### For Plugin Developers

**PlayerPunishEvent (Cancellable):**
```java
@EventHandler
public void onPunish(PlayerPunishEvent event) {
    Player staff = event.getStaff();
    Player victim = event.getVictim();
    PunishmentType type = event.getType();

    // Modify reason
    event.setReason("Modified reason");

    // Modify duration
    event.setDuration(Duration.ofDays(7));

    // Cancel event
    if (someCondition) {
        event.setCancelled(true);
    }
}
```

**PlayerPunishedEvent:**
```java
@EventHandler
public void onPunished(PlayerPunishedEvent event) {
    PunishmentRecord record = event.getRecord();
    String victim = event.getVictimName();
    // Perform own actions after punishment
}
```

**PlayerUnpunishedEvent:**
```java
@EventHandler
public void onUnpunished(PlayerUnpunishedEvent event) {
    PunishmentRecord record = event.getRecord();
    boolean automatic = event.isAutomatic();
    // Was automatically unbanned (temp ban expired)?
}
```

---

## ❓ FAQ

<details>
<summary><b>How do I enable the database?</b></summary>

Set in `config.yml`:
```yaml
database:
  enabled: true
  type: SQLITE  # or MYSQL
```
</details>

<details>
<summary><b>How do I create temporary bans?</b></summary>

Duration comes from the ban preset you have selected, or from the command argument:

```yaml
presets:
  temp_7d:
    displayName: "7 Days Ban"
    reason: "Temporary ban - 7 days"
    duration: "7d"      # 30m, 1h30m, 2w, 3mo, permanent, PT24H ...
```

Cycle presets with Shift + Right-Click and apply with Right-Click on a player. `/mute` and
`/jail` take the duration directly as an argument.

> The old `ban.duration` / `ban.reason` settings were removed in 4.1.0 - the preset system
> replaced them.
</details>

<details>
<summary><b>Why does the plugin download files on first start?</b></summary>

Since 4.1.0 the database drivers, HikariCP and the Discord webhook library are declared under
`libraries:` in `plugin.yml`. Paper fetches them from Maven Central on first start and caches
them in the server's `libraries` folder, which keeps the plugin jar at ~266 KB instead of 22 MB
and prevents clashes with other plugins shipping the same libraries.

The server therefore needs internet access once. Afterwards it starts offline as usual.
</details>

<details>
<summary><b>Does it work with Folia?</b></summary>

Yes! BanHammer fully supports Folia. The plugin automatically detects whether it's running on Paper or Folia at startup and uses the correct scheduler APIs. No configuration needed — just use the same JAR on both platforms.
</details>

<details>
<summary><b>Does it work with Velocity/BungeeCord?</b></summary>

Yes! With MySQL database all servers can use the same database:
```yaml
database:
  type: MYSQL
  mysql:
    host: "shared-mysql-server"
```
</details>

<details>
<summary><b>Can I use the plugin without a database?</b></summary>

Yes! Simply set `database.enabled: false`. The plugin will then only use Minecraft's built-in ban system. Features like history, statistics, and appeals won't be available.
</details>

<details>
<summary><b>Where is the resource pack?</b></summary>

The resource pack is available as a separate download on Modrinth:
[BS-BanHammer Resource Pack](https://modrinth.com/resourcepack/bs-banhammer-resource-pack)

You can also configure it via `server.properties` to auto-send it to players.
</details>

<details>
<summary><b>How does the preset system work?</b></summary>

The preset system allows quick switching between ban types:

1. **Define presets** in `config.yml`
2. **Shift + Right-Click** with hammer → Switches ban preset
3. **Shift + Left-Click** with hammer → Switches kick/jail preset
4. **Right-Click on Player** → Ban with active preset
5. **Left-Click on Player** → Kick/jail with active preset

Each staff member has their own active preset with visual and audio feedback.
</details>

---

## 📜 License

MIT License - see [LICENSE](LICENSE) file

---

## 🤝 Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📞 Support

- **Discord:** [Join our Discord](https://discord.gg/xEJjF65K46)
- **Issues:** [GitHub Issues](https://github.com/BoondockSulfur/BanHammer/issues)
- **Source Code:** [GitHub Repository](https://github.com/BoondockSulfur/BanHammer)

---

## 🙏 Credits

- **Plugin Development:** [BoondockSulfur](https://github.com/BoondockSulfur)
- **Resource Pack:** [BS-BanHammer Resource Pack](https://modrinth.com/resourcepack/bs-banhammer-resource-pack)

**Dependencies:**
- [Paper API](https://papermc.io/)
- [HikariCP](https://github.com/brettwooldridge/HikariCP)
- [Discord Webhooks](https://github.com/MinnDevelopment/discord-webhooks)
- [Gson](https://github.com/google/gson)
- [Adventure](https://github.com/KyoriPowered/adventure)
