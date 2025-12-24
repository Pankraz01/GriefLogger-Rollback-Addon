package eu.pankraz01.glra.database;

import java.time.Instant;

/**
 * Represents a single logged DB action from the griefLogger schema (blocks/items).
 * This mirrors the `blocks` table: (time, user, level, x,y,z, type, action)
 */
public final class Action {
    public enum Kind {
        PLACE,
        BREAK,
        OTHER
    }

    // timestamp of the logged action (epoch millis)
    public final Instant timestamp;
    // player id and name (if available)
    public final int userId;
    public final String playerName;
    // level/world id and name (from level table)
    public final int levelId;
    public final String levelName;
    // coordinates
    public final int x;
    public final int y;
    public final int z;
    // material id and name (material at the time of the log)
    public final int materialId;
    public final String materialName;
    // action code from the DB (semantics depend on log generator)
    public final int actionCode;
    // action name from the DB actions table (if joined)
    public final String actionName;

    // Computed old material (determined from neighboring log entries). Mutable so DAO can fill it.
    public String oldMaterialName;

    public Action(long timeMillis, int userId, String playerName, int levelId, String levelName, int x, int y, int z, int materialId, String materialName, int actionCode, String actionName) {
        this.timestamp = Instant.ofEpochMilli(timeMillis);
        this.userId = userId;
        this.playerName = playerName;
        this.levelId = levelId;
        this.levelName = levelName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.materialId = materialId;
        this.materialName = materialName == null ? "" : materialName;
        this.actionCode = actionCode;
        this.actionName = actionName == null ? "" : actionName;
        this.oldMaterialName = null;
    }

    public String coordKey() {
        return x + ":" + y + ":" + z + ":" + levelId;
    }

    /**
     * The concrete GriefLogger action code as a semantic enum (ids documented by the mod).
     */
    public ActionType actionType() {
        return ActionType.fromDb(actionName, actionCode);
    }

    public Kind kind() {
        // Group action codes by how they affect the world state for rollback.
        return switch (actionType()) {
            case BREAK_BLOCK, TNT_IGNITE, TNT_EXPLOSION, TNT_REDSTONE, UNKNOWN_BREAK -> Kind.BREAK;
            case PLACE_BLOCK -> Kind.PLACE;
            default -> Kind.OTHER;
        };
    }

    /**
     * Full list of known GriefLogger block action codes.
     */
    public enum ActionType {
        BREAK_BLOCK(0, "BREAK_BLOCK"),
        PLACE_BLOCK(1, "PLACE_BLOCK"),
        INTERACT_BLOCK(2, "INTERACT_BLOCK"),
        KILL_ENTITY(3, "KILL_ENTITY"),
        TNT_IGNITE(4, "TNT_IGNITE"),
        TNT_EXPLOSION(5, "TNT_EXPLOSION"),
        TNT_REDSTONE(6, "TNT_REDSTONE"),
        UNKNOWN_BREAK(7, "UNKNOWN_BREAK"),
        UNKNOWN(-1, "UNKNOWN");

        public final int code;
        public final String dbName;

        ActionType(int code, String dbName) {
            this.code = code;
            this.dbName = dbName;
        }

        public static ActionType fromDb(String name, int code) {
            if (name != null && !name.isBlank()) {
                for (ActionType type : values()) {
                    if (type.dbName.equalsIgnoreCase(name.trim())) return type;
                }
            }
            // Fallback to legacy numeric mapping if the name is unknown/not joined
            for (ActionType type : values()) {
                if (type.code == code) return type;
            }
            return UNKNOWN;
        }
    }
}
