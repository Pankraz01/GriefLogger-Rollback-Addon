package eu.pankraz01.glra.rollback;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import eu.pankraz01.glra.Config;
import eu.pankraz01.glra.database.Action;
import eu.pankraz01.glra.database.ActionDAO;
import eu.pankraz01.glra.database.ContainerAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Rollback manager adapted to the griefLogger DB schema. Loads block + container entries and enqueues them.
 */
public class RollbackManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HolderLookup.Provider BUILTIN_PROVIDER = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glra-action-loader");
        t.setDaemon(true);
        return t;
    });

    private final Queue<QueuedAction> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean runningJob = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicLong processedTotal = new AtomicLong();
    private int ticksSinceProgressLog = 0;

    private final ActionDAO dao = new ActionDAO();

    /**
     * Start a rollback by loading block and inventory actions since `sinceMillis` (inclusive).
     * Player is an optional username filter.
     */
    public void startRollback(long sinceMillis, Optional<String> player, Optional<RollbackArea> area, RollbackKind kind) {
        if (runningJob.get()) {
            LOGGER.warn("A rollback job is already running");
            return;
        }

        RollbackKind effectiveKind = kind == null ? RollbackKind.BOTH : kind;

        runningJob.set(true);
        cancelFlag.set(false);
        processedTotal.set(0);
        ticksSinceProgressLog = 0;

        loader.submit(() -> {
            try {
                LOGGER.info("Loading actions since={} (player={}, area={}, scope={})", Instant.ofEpochMilli(sinceMillis), player.orElse("<any>"), area.map(RollbackArea::describe).orElse("<none>"), effectiveKind.describe());
                List<Action> blockActions = effectiveKind.includeBlocks() ? dao.loadBlockActions(sinceMillis, player) : List.of();
                List<ContainerAction> containerActions = effectiveKind.includeItems() ? dao.loadContainerActions(sinceMillis, player) : List.of();

                List<QueuedAction> combined = new ArrayList<>(blockActions.size() + containerActions.size());
                for (Action action : blockActions) {
                    if (area.isPresent() && !isWithinArea(area.get(), action)) continue;
                    combined.add(new BlockQueuedAction(action));
                }
                for (ContainerAction action : containerActions) {
                    if (area.isPresent() && !isWithinArea(area.get(), action)) continue;
                    combined.add(new ContainerQueuedAction(action));
                }

                combined.sort(Comparator.comparing(QueuedAction::timestamp).reversed()); // newest first to undo latest changes first
                combined.forEach(queue::offer);

                LOGGER.info("Loaded {} block actions and {} container actions, enqueued {}", blockActions.size(), containerActions.size(), combined.size());
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

            QueuedAction queued = queue.poll();
            if (queued == null) break;

            try {
                if (queued instanceof BlockQueuedAction block) {
                    applyBlockInverse(server, block.action());
                } else if (queued instanceof ContainerQueuedAction container) {
                    applyContainerInverse(server, container.action());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to apply action {}", queued, e);
            }

            processed++;
        }

        if (queue.isEmpty() && runningJob.get()) {
            LOGGER.info("Rollback job finished (queue empty)");
            runningJob.set(false);
        }
        return processed;
    }

    private void applyBlockInverse(MinecraftServer server, Action action) {
        ResourceKey<Level> levelKey = levelKeyFrom(action.levelId, action.levelName);
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
            default -> blockFromName(action.oldMaterialName);
        };

        boolean ok = level.setBlock(pos, Objects.requireNonNull(target.defaultBlockState()), Block.UPDATE_ALL);
        if (!ok) {
            LOGGER.warn("Rollback: setBlock returned false at {} in {}", pos, levelKey == null ? "overworld" : levelKey.location());
        }
    }

    private void applyContainerInverse(MinecraftServer server, ContainerAction action) {
        ResourceKey<Level> levelKey = levelKeyFrom(action.levelId, action.levelName);
        ServerLevel level = levelKey == null ? server.overworld() : server.getLevel(levelKey);
        if (level == null) {
            LOGGER.warn("Rollback: no level found for id={}, skipping container action at {},{},{}", action.levelId, action.x, action.y, action.z);
            return;
        }

        BlockPos pos = new BlockPos(action.x, action.y, action.z);
        if (!ensureChunkLoaded(level, pos)) {
            LOGGER.warn("Rollback: target chunk not loaded for {}, skipping container action", pos);
            return;
        }

        Container container = resolveContainer(level, pos);
        if (container == null) {
            LOGGER.warn("Rollback: no container found at {} in {}", pos, levelKey == null ? "overworld" : levelKey.location());
            return;
        }

        ItemStack template = itemFromNameAndData(action.materialName, action.data);
        if (template.isEmpty()) {
            LOGGER.warn("Rollback: unknown item '{}' (id={}) at {},{},{}", action.materialName, action.materialId, action.x, action.y, action.z);
            return;
        }

        int remaining = 0;
        boolean dropLeftover = false;
        switch (action.kind()) {
            case ADD -> {
                remaining = removeItems(container, template, action.amount);   // player put items in -> remove them
                if (remaining > 0) {
                    LOGGER.warn("Rollback: could not remove {}x {} from container at {}", remaining, action.materialName, pos);
                }
            }
            case REMOVE -> {
                remaining = addItems(container, template, action.amount);  // player took items out -> add them back
                dropLeftover = true;
            }
            default -> {
                LOGGER.warn("Rollback: unknown container action code {} at {}", action.actionCode, pos);
                return;
            }
        }

        if (container instanceof BlockEntity be) {
            be.setChanged();
        } else {
            container.setChanged();
        }

        if (dropLeftover && remaining > 0) {
            int still = remaining;
            while (still > 0) {
                ItemStack drop = template.copy();
                drop.setCount(Math.min(still, drop.getMaxStackSize()));
                ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop);
                entity.setDefaultPickUpDelay();
                level.addFreshEntity(entity);
                still -= drop.getCount();
            }
            LOGGER.info("Rollback: container at {} was full, dropped {}x {}", pos, remaining, action.materialName);
        }
    }

    private Container resolveContainer(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        var block = state.getBlock();

        if (block instanceof ChestBlock chest) {
            Container merged = ChestBlock.getContainer(chest, state, level, pos, false);
            if (merged != null) return merged;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            return container;
        }

        return null;
    }

    private int removeItems(Container container, ItemStack template, int amount) {
        int remaining = amount;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty() || !isSameItem(slot, template)) continue;
            int toRemove = Math.min(remaining, slot.getCount());
            slot.shrink(toRemove);
            if (slot.isEmpty()) container.setItem(i, ItemStack.EMPTY);
            remaining -= toRemove;
        }
        return remaining;
    }

    private int addItems(Container container, ItemStack template, int amount) {
        int remaining = amount;
        int maxStack = template.getMaxStackSize();

        // fill existing stacks first
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty() || !isSameItem(slot, template) || slot.getCount() >= maxStack) continue;
            int space = maxStack - slot.getCount();
            int toAdd = Math.min(space, remaining);
            slot.grow(toAdd);
            remaining -= toAdd;
        }

        // then use empty slots
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty()) continue;
            ItemStack newStack = template.copy();
            int toAdd = Math.min(maxStack, remaining);
            newStack.setCount(toAdd);
            container.setItem(i, newStack);
            remaining -= toAdd;
        }

        return remaining;
    }

    private boolean isSameItem(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    private ResourceLocation safeItemId(String name) {
        if (name == null || name.isBlank()) return ResourceLocation.parse("minecraft:air");
        try {
            return ResourceLocation.parse(name);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Rollback: invalid item id '{}', defaulting to air", name);
            return ResourceLocation.parse("minecraft:air");
        }
    }

    private CompoundTag readItemTag(byte[] data) {
        // Try gzip-compressed NBT first (what GriefLogger stores), then raw NBT as fallback.
        try (DataInputStream dis = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            return NbtIo.read(dis);
        } catch (Exception compressed) {
            try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
                return NbtIo.read(dis);
            } catch (Exception plain) {
                LOGGER.warn("Rollback: could not decode item NBT ({} bytes)", data.length);
                return null;
            }
        }
    }

    private ItemStack itemFromNameAndData(String name, byte[] data) {
        ResourceLocation itemId = safeItemId(name);
        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) return ItemStack.EMPTY;

        if (data != null && data.length > 0) {
            CompoundTag tag = readItemTag(data);
            if (tag != null) {
                try {
                    ItemStack stack = ItemStack.parseOptional(BUILTIN_PROVIDER, tag);
                    if (!stack.isEmpty()) {
                        return stack;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Rollback: failed to parse item NBT for {}", name, e);
                }
            }
        }

        return new ItemStack(item);
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
    private ResourceKey<Level> levelKeyFrom(int levelId, String levelName) {
        if (levelName != null && !levelName.isBlank()) {
            try {
                ResourceLocation loc = ResourceLocation.parse(levelName);
                return ResourceKey.create(Registries.DIMENSION, loc);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Rollback: invalid level name '{}' for id {}, falling back to id mapping", levelName, levelId);
            }
        }

        return switch (levelId) {
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
            ResourceKey<Level> actionLevel = levelKeyFrom(action.levelId, action.levelName);
            if (actionLevel != null && !actionLevel.equals(area.levelKey)) {
                return false;
            }
        }

        long dx = (long) action.x - area.center.getX();
        long dz = (long) action.z - area.center.getZ();
        long distSq = dx * dx + dz * dz; // horizontal distance only
        return distSq <= (long) area.radiusBlocks * (long) area.radiusBlocks;
    }

    private boolean isWithinArea(RollbackArea area, ContainerAction action) {
        if (area.levelKey != null) {
            ResourceKey<Level> actionLevel = levelKeyFrom(action.levelId, action.levelName);
            if (actionLevel != null && !actionLevel.equals(area.levelKey)) {
                return false;
            }
        }

        long dx = (long) action.x - area.center.getX();
        long dz = (long) action.z - area.center.getZ();
        long distSq = dx * dx + dz * dz; // horizontal distance only
        return distSq <= (long) area.radiusBlocks * (long) area.radiusBlocks;
    }

    public enum RollbackKind {
        BOTH,
        BLOCKS_ONLY,
        ITEMS_ONLY;

        boolean includeBlocks() {
            return this == BOTH || this == BLOCKS_ONLY;
        }

        boolean includeItems() {
            return this == BOTH || this == ITEMS_ONLY;
        }

        String describe() {
            return switch (this) {
                case BLOCKS_ONLY -> "blocks only";
                case ITEMS_ONLY -> "items only";
                default -> "blocks + items";
            };
        }
    }

    private sealed interface QueuedAction permits BlockQueuedAction, ContainerQueuedAction {
        Instant timestamp();
    }

    private record BlockQueuedAction(Action action) implements QueuedAction {
        @Override
        public Instant timestamp() {
            return action.timestamp;
        }
    }

    private record ContainerQueuedAction(ContainerAction action) implements QueuedAction {
        @Override
        public Instant timestamp() {
            return action.timestamp;
        }
    }

    public record RollbackArea(ResourceKey<Level> levelKey, BlockPos center, int radiusBlocks) {
        String describe() {
            return "center=%s radius=%d level=%s".formatted(center, radiusBlocks, levelKey == null ? "<overworld>" : levelKey.location());
        }
    }
}
