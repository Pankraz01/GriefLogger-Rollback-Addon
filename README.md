
Grieflogger Rollback Addon
==========================

This NeoForge addon reads GriefLogger data from MariaDB and reverts block actions in-game via command. It targets NeoForge 21.1.213 / Minecraft 1.21.1 and is server-side only: any logged block placements or breaks can be undone selectively.

Features
--------
- Rollback of block changes from the GriefLogger database (`blocks` table joined with `materials`, `users`, `levels`).
- Filters by time window, optional player name, and optional radius around the command executor.
- Ensures target chunks are loaded before changing blocks.
- Progress logging and batch processing per tick to keep the server responsive.
- Automatically disables itself on startup if no database connection is available.

Requirements
------------
- NeoForge 21.1.213 / Minecraft 1.21.1.
- A running MariaDB instance with the GriefLogger schema (tables `blocks`, `materials`, `users`, `levels`).
- MariaDB JDBC driver available at runtime (e.g., place `mariadb-java-client-3.x.jar` in `mods/` or otherwise on the classpath).
- Database credentials with read access.

Installation
------------
1. Place the mod JAR and the MariaDB JDBC driver JAR into the server `mods/` folder.
2. Start the server once to generate `config/grieflogger/griefloggerrollbackaddon-common.toml`.
3. Fill in the DB credentials in the config and restart the server.
4. Check the log for `[griefloggerrollbackaddon] Database connection succeeded`. If it fails, the addon remains disabled until the connection works.

Configuration (`config/grieflogger/griefloggerrollbackaddon-common.toml`)
------------------------------------------------------------------------
- `dbHost` (String, default `localhost`): MariaDB host.
- `dbPort` (Int, default `3306`): MariaDB port.
- `dbName` (String, default `grieflogger`): Database name.
- `dbUser` (String): Database user.
- `dbPassword` (String): Database password.
- `rollbackBatchSize` (Int, default `200`): Number of actions processed per tick.
- `progressTickInterval` (Int, default `20`): How many ticks between progress log messages.

Command: `/gl rollback`
-----------------------
Syntax: `/gl rollback t:<time> [u:<player>] [r:<radius|c<chunks>>]`

- `t:` Required time window to roll back. Supported units: `ms`, `s`, `m`, `h`, `d`, `M` (30 days), `y`. Examples: `t:30m`, `t:12h`, `t:90s`.
- `u:` Optional exact player name (matches the `users` table).
- `r:` Optional radius. Default uses blocks (`r:25`). Prefix `c` switches to chunks (`r:c4` = radius of 4 chunks). Prefix `b` forces blocks (`r:b40`).

Examples
- `/gl rollback t:2h` — Roll back all actions from the last 2 hours.
- `/gl rollback t:1d u:Griefer123` — Only that player’s actions in the last 24h.
- `/gl rollback t:30m r:c2` — Within 2 chunks around the executor.
- `/gl rollback t:10m u:User r:20` — Player filter plus 20-block radius.

How it works
------------
1. Server start: immediately tests a DB connection. On failure, the addon disables itself (no commands/events).
2. Command: `/gl rollback ...` starts a job:
   - Loads matching entries from `blocks` since the given time in a background thread, optionally filtered by player and/or radius.
   - Reconstructs the previous block state per coordinate (`oldMaterialName`) so placements and breaks can be inverted correctly.
   - Enqueues all actions for processing.
3. Server ticks: up to `rollbackBatchSize` actions are processed each tick:
   - `BREAK` logs restore the broken block from the DB entry.
   - `PLACE` logs restore the previous block state.
   - Unknown/other codes fall back to restoring the previous state (or air).
   - The target chunk is loaded before setting the block.
4. Progress: every `progressTickInterval` ticks, the queue size and processed count are logged. When the queue is empty, the job finishes.

Notes and limitations
---------------------
- Only block changes (`blocks` table) are covered; items/inventories are not.
- Dimension mapping uses `levels.name` (ResourceLocation) or falls back for IDs 1/2/3 (Overworld/End/Nether).
- Invalid or unknown block names default to `minecraft:air` with a warning.
- The JDBC driver is not bundled in the mod JAR; it must be provided separately.
- Large time windows can produce big queues. Adjust `rollbackBatchSize` and radius to control server load.

Development/Building
--------------------
- Java 21, Gradle wrapper included. Build locally with `./gradlew build` (or `gradlew.bat build` on Windows).
- MariaDB driver is declared as `localRuntime`; provide the driver JAR separately for production.
