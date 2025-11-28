# Release Notes

## 1.21.1-neoforge1.0.0

- Rollback: `/gl rollback` with time/player/radius filters; web UI/API for rollbacks; optional notifications; initiator excluded from broadcast; English defaults for messages/UI.
- Web tokens: `/gl web token add/remove/list` with player-name autocomplete, copy-to-clipboard tokens, and translated messages.
- Web server control: `/gl web start|stop` with GLRA-styled status messages and English fallbacks.
- Config: database backends (SQLite/MySQL/MariaDB), batch size, web bind/port/token, unauthorized access logging toggles.
- Permissions: per-feature permission nodes for commands and notifications (LuckPerms/permission mods supported; op-level fallback).
- Stability: fallback English text in web UI and in-game notifications; PlayerList API fixes; TODO backlog for future work.
- Web server start/stop commands now use GLRA-styled messages with English fallbacks (“GLRA web server started/stopped”).
- Rollback notifications default to English and no longer ping the initiating player (they already get direct feedback).
- Web UI defaults switched to English for all static labels/toasts when translations are missing.
- `/gl web token add/remove` gains in-game player name autocompletion and fixes the PlayerList API usage.
- TODO backlog added (`TODO.md`) outlining undo support, unified messaging, GriefLogger independence, multi-version targets, mod API surface, and other future tasks.
