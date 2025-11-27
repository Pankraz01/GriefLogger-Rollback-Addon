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

    // Computed old material (determined from neighboring log entries). Mutable so DAO can fill it.
    public String oldMaterialName;

    public Action(long timeMillis, int userId, String playerName, int levelId, String levelName, int x, int y, int z, int materialId, String materialName, int actionCode) {
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
        this.oldMaterialName = null;
    }

    public String coordKey() {
        return x + ":" + y + ":" + z + ":" + levelId;
    }

    public Kind kind() {
        return switch (actionCode) {
            case 0 -> Kind.BREAK;
            case 1 -> Kind.PLACE;
            default -> Kind.OTHER;
        };
    }
}
