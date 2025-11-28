package eu.pankraz01.glra.database;

import java.time.Instant;
import java.util.Arrays;

/**
 * Represents a single logged inventory action from the GriefLogger `containers` table.
 */
public final class ContainerAction {
    public enum Kind {
        ADD,    // player put items into the container
        REMOVE, // player took items out of the container
        OTHER
    }

    public final Instant timestamp;
    public final int userId;
    public final String playerName;
    public final int levelId;
    public final String levelName;
    public final int x;
    public final int y;
    public final int z;
    public final int materialId;
    public final String materialName;
    public final byte[] data;
    public final int amount;
    public final int actionCode;

    public ContainerAction(long timeMillis, int userId, String playerName, int levelId, String levelName, int x, int y, int z, int materialId, String materialName, byte[] data, int amount, int actionCode) {
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
        this.data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        this.amount = amount;
        this.actionCode = actionCode;
    }

    public Kind kind() {
        return switch (actionCode) {
            case 1 -> Kind.ADD;
            case 0 -> Kind.REMOVE;
            default -> Kind.OTHER;
        };
    }
}
