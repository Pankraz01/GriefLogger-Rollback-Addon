# TODO / Backlog

- `/gl rollback undo [steps]` to revert recent rollbacks (optional `steps` count; default = last rollback).
- Unify message design (chat/console/web) with consistent GLRA prefix, colors, and English fallbacks.
- Long-term: optional internal GriefLogger-compatible logic to reduce hard dependency on the GriefLogger mod.
- Add version compatibility layer / builds for additional Minecraft versions.
- Define API surface for other mods to depend on GLRA (events, services, rollback hooks).
- Additional ideas to consider:
  - Configurable notification routing (who gets which GLRA messages).
  - Web UI quality-of-life: remember last inputs, better error surfacing, optional auth modes.
  - More granular rollback filters (dimensions, action types, block whitelist/blacklist).
  - Metrics/telemetry toggles (counts, durations) for rollback operations.
  - CLI/admin endpoints for scheduled/automated rollbacks.
  - Add tests around parsing, DB access, and rollback batch execution.
