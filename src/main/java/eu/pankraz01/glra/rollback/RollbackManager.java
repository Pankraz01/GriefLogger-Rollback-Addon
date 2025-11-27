package eu.pankraz01.glra.rollback;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import eu.pankraz01.glra.Config;
import eu.pankraz01.glra.database.Action;
import eu.pankraz01.glra.database.ActionDAO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Rollback manager adapted to the griefLogger DB schema. Loads `blocks` entries and enqueues them.
 */
public class RollbackManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glra-action-loader");
        t.setDaemon(true);
        return t;
    });

    private final Queue<Action> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean runningJob = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicLong processedTotal = new AtomicLong();
    private int ticksSinceProgressLog = 0;

    private final ActionDAO dao = new ActionDAO();

    /**
     * Start a rollback by loading block actions since `sinceMillis` (inclusive).
     * Player is an optional username filter.
     */
    public void startRollback(long sinceMillis, Optional<String> player, Optional<RollbackArea> area) {
        if (runningJob.get()) {
            LOGGER.warn("A rollback job is already running");
            return;
        }

        runningJob.set(true);
        cancelFlag.set(false);
        processedTotal.set(0);
        ticksSinceProgressLog = 0;

        loader.submit(() -> {
            try {
                LOGGER.info("Loading block actions since={} (player={}, area={})", Instant.ofEpochMilli(sinceMillis), player.orElse("<any>"), area.map(RollbackArea::describe).orElse("<none>"));
                List<Action> loaded = dao.loadBlockActions(sinceMillis, player);

                int enqueued = 0;
                for (Action action : loaded) {
                    if (area.isPresent() && !isWithinArea(area.get(), action)) {
                        continue;
                    }
                    queue.offer(action);
                    enqueued++;
                }

                LOGGER.info("Loaded {} block actions, {} matched filters and enqueued for processing", loaded.size(), enqueued);
            } catch (SQLException e) {
                LOGGER.error("Failed to load actions for rollback", e);
                runningJob.set(false);
            }
        });
    }

    public void cancelCurrent() {
        cancelFlag.set(true);
    }

    public boolean isCancelled() {
        return cancelFlag.get();
    }

    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Called each server tick from the server thread to process queued actions.
     */
    public void tick(MinecraftServer server) {
        if (server == null) return;
        if (!runningJob.get() && queue.isEmpty()) return;

        int batchSize = Math.max(1, Config.ROLLBACK_BATCH_SIZE.get());
        int processed = processBatch(server, batchSize);
        processedTotal.addAndGet(processed);

        ticksSinceProgressLog++;
        int progressInterval = Math.max(1, Config.PROGRESS_TICK_INTERVAL.get());
        if (ticksSinceProgressLog >= progressInterval) {
            ticksSinceProgressLog = 0;
            LOGGER.info("Rollback progress: remaining={}, processedTotal={}, runningJob={}", queue.size(), processedTotal.get(), runningJob.get());
        }
    }

    /**
     * Process up to `batchSize` actions from the queue. Call from the server thread.
     */
    public int processBatch(MinecraftServer server, int batchSize) {
        int processed = 0;
        while (processed < batchSize) {
            if (isCancelled()) {
                LOGGER.info("Rollback cancelled, clearing remaining {} actions", queue.size());
                queue.clear();
                runningJob.set(false);
                return processed;
            }

            Action action = queue.poll();
            if (action == null) break;

            try {
                applyInverse(server, action);
            } catch (Exception e) {
                LOGGER.error("Failed to apply action at {}:{}", action.x, action.z, e);
            }

            processed++;
        }

        if (queue.isEmpty() && runningJob.get()) {
            LOGGER.info("Rollback job finished (queue empty)");
            runningJob.set(false);
        }
        return processed;
    }

    private void applyInverse(MinecraftServer server, Action action) {
        ResourceKey<Level> levelKey = levelKeyFrom(action);
        ServerLevel level = levelKey == null ? server.overworld() : server.getLevel(levelKey);
        if (level == null) {
            LOGGER.warn("Rollback: no level found for id={}, skipping action at {},{},{}", action.levelId, action.x, action.y, action.z);
            return;
        }

        BlockPos pos = new BlockPos(action.x, action.y, action.z);

        if (!ensureChunkLoaded(level, pos)) {
            LOGGER.warn("Rollback: target chunk not loaded for {}, skipping", pos);
            return;
        }

        Block target = switch (action.kind()) {
            case BREAK -> blockFromName(action.materialName); // undo a break by restoring the broken block
            case PLACE -> blockFromName(action.oldMaterialName); // undo a placement by restoring the previous block
            default -> {
                LOGGER.warn("Rollback: unknown actionCode {} at {},{},{} – using previous material fallback", action.actionCode, action.x, action.y, action.z);
                yield blockFromName(action.oldMaterialName);
            }
        };

        boolean ok = level.setBlock(pos, Objects.requireNonNull(target.defaultBlockState()), Block.UPDATE_ALL);
        if (!ok) {
            LOGGER.warn("Rollback: setBlock returned false at {} in {}", pos, levelKey == null ? "overworld" : levelKey.location());
        }
    }

    private ResourceLocation safeBlockId(String name) {
        if (name == null || name.isBlank()) return ResourceLocation.parse("minecraft:air");
        try {
            return ResourceLocation.parse(name);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Rollback: invalid block id '{}', defaulting to air", name);
            return ResourceLocation.parse("minecraft:air");
        }
    }

    private Block blockFromName(String name) {
        ResourceLocation blockId = safeBlockId(name);
        return BuiltInRegistries.BLOCK.getOptional(blockId).orElse(Blocks.AIR);
    }


    @SuppressWarnings("null")
    private ResourceKey<Level> levelKeyFrom(Action action) {
        if (action.levelName != null && !action.levelName.isBlank()) {
            try {
                ResourceLocation loc = ResourceLocation.parse(action.levelName);
                return ResourceKey.create(Registries.DIMENSION, loc);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Rollback: invalid level name '{}' for id {}, falling back to id mapping", action.levelName, action.levelId);
            }
        }

        return switch (action.levelId) {
            case 1 -> Level.OVERWORLD;
            case 2 -> Level.END;
            case 3 -> Level.NETHER;
            default -> Level.OVERWORLD; // fallback if DB id is unknown
        };
    }


    private boolean ensureChunkLoaded(ServerLevel level, BlockPos pos) {
        try {
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, Objects.requireNonNull(ChunkStatus.FULL), true);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Rollback: failed to load chunk for {}", pos, e);
            return false;
        }
    }

    private boolean isWithinArea(RollbackArea area, Action action) {
        if (area.levelKey != null) {
            ResourceKey<Level> actionLevel = levelKeyFrom(action);
            if (actionLevel != null && !actionLevel.equals(area.levelKey)) {
                return false;
            }
        }

        long dx = (long) action.x - area.center.getX();
        long dz = (long) action.z - area.center.getZ();
        long distSq = dx * dx + dz * dz; // horizontal distance only
        return distSq <= (long) area.radiusBlocks * (long) area.radiusBlocks;
    }

    public record RollbackArea(ResourceKey<Level> levelKey, BlockPos center, int radiusBlocks) {
        String describe() {
            return "center=%s radius=%d level=%s".formatted(center, radiusBlocks, levelKey == null ? "<overworld>" : levelKey.location());
        }
    }
}
