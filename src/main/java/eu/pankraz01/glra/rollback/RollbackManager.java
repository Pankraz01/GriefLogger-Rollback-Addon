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
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
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

import eu.pankraz01.glra.gui.ActionBarNotifier;

/**
 * Rollback manager adapted to the griefLogger DB schema. Loads block + container entries and enqueues them.
 */
public class RollbackManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    @SuppressWarnings("null")
    private static final HolderLookup.Provider BUILTIN_PROVIDER = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static final String LANG_ACTIONBAR_BASE = "message.griefloggerrollbackaddon.actionbar.";
    private static final String LANG_SCOPE_BASE = "message.griefloggerrollbackaddon.rollback.scope.";
    private static final String ACTIONBAR_DONE_KEY = LANG_ACTIONBAR_BASE + "done";
    private static final String ACTIONBAR_FAILED_KEY = LANG_ACTIONBAR_BASE + "failed";
    private static final String ACTIONBAR_CANCELLED_KEY = LANG_ACTIONBAR_BASE + "cancelled";
    private static final String ACTIONBAR_PREFIX_KEY = LANG_ACTIONBAR_BASE + "prefix";
    private static final String ACTIONBAR_PROGRESS_KEY = LANG_ACTIONBAR_BASE + "progress";
    private static final String ACTIONBAR_PROGRESS_SIMPLE_KEY = LANG_ACTIONBAR_BASE + "progress_simple";
    private static final String ACTIONBAR_REMAINING_KEY = LANG_ACTIONBAR_BASE + "remaining";
    private static final String ACTIONBAR_ELAPSED_KEY = LANG_ACTIONBAR_BASE + "elapsed";
    private static final String ACTIONBAR_ETA_KEY = LANG_ACTIONBAR_BASE + "eta";
    private static final String ACTIONBAR_TIME_KEY = LANG_ACTIONBAR_BASE + "time";
    private static final String ACTIONBAR_PLAYER_KEY = LANG_ACTIONBAR_BASE + "player";
    private static final String ACTIONBAR_RADIUS_KEY = LANG_ACTIONBAR_BASE + "radius";
    private static final String ACTIONBAR_SCOPE_KEY = LANG_ACTIONBAR_BASE + "scope";
    private static final String ACTIONBAR_ERRORS_KEY = LANG_ACTIONBAR_BASE + "errors";
    private static final String ACTIONBAR_STATUS_RUNNING_KEY = LANG_ACTIONBAR_BASE + "status.running";
    private static final String ACTIONBAR_STATUS_LOADING_KEY = LANG_ACTIONBAR_BASE + "status.loading";
    private static final String ACTIONBAR_STATUS_CANCELLING_KEY = LANG_ACTIONBAR_BASE + "status.cancelling";
    private static final String ACTIONBAR_STATUS_FINISHED_KEY = LANG_ACTIONBAR_BASE + "status.done";
    private static final String ACTIONBAR_STATUS_FAILED_KEY = LANG_ACTIONBAR_BASE + "status.failed";
    private static final String ACTIONBAR_STATUS_CANCELLED_KEY = LANG_ACTIONBAR_BASE + "status.cancelled";

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glra-action-loader");
        t.setDaemon(true);
        return t;
    });

    private final Queue<QueuedAction> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean runningJob = new AtomicBoolean(false);
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicLong processedTotal = new AtomicLong();
    private final AtomicLong expectedTotal = new AtomicLong();
    private final AtomicLong errorTotal = new AtomicLong();
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicBoolean completionMessagePending = new AtomicBoolean(false);
    private volatile CompletionReason lastCompletion = CompletionReason.NONE;
    private volatile RollbackJobInfo jobInfo;
    private long jobStartMillis = 0L;
    private int ticksSinceProgressLog = 0;
    private final ActionBarNotifier actionBarNotifier = new ActionBarNotifier();

    private final ActionDAO dao = new ActionDAO();

    /**
     * Start a rollback by loading block and inventory actions since `sinceMillis` (inclusive).
     * Player is an optional username filter.
     */
    public void startRollback(long sinceMillis, String timeLabel, Optional<String> player, Optional<RollbackArea> area, Optional<String> radiusLabel, RollbackKind kind) {
        if (runningJob.get()) {
            LOGGER.warn("A rollback job is already running");
            return;
        }

        RollbackKind effectiveKind = kind == null ? RollbackKind.BOTH : kind;

        runningJob.set(true);
        cancelFlag.set(false);
        processedTotal.set(0);
        expectedTotal.set(0);
        errorTotal.set(0);
        loading.set(true);
        completionMessagePending.set(false);
        lastCompletion = CompletionReason.NONE;
        jobStartMillis = System.currentTimeMillis();
        Optional<String> safeRadiusLabel = radiusLabel == null ? Optional.empty() : radiusLabel;
        jobInfo = new RollbackJobInfo(timeLabel == null ? "provided time" : timeLabel, player, safeRadiusLabel, effectiveKind);
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

                expectedTotal.set(combined.size());
                loading.set(false);
                LOGGER.info("Loaded {} block actions and {} container actions, enqueued {}", blockActions.size(), containerActions.size(), combined.size());
            } catch (SQLException e) {
                LOGGER.error("Failed to load actions for rollback", e);
                loading.set(false);
                runningJob.set(false);
                lastCompletion = CompletionReason.FAILED;
                completionMessagePending.set(true);
            }
        });
    }

    public void cancelCurrent() {
        cancelFlag.set(true);
    }

    public void trackActionBar(ServerPlayer player) {
        actionBarNotifier.track(player);
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
        boolean pendingCompletion = completionMessagePending.get();
        if (!runningJob.get() && queue.isEmpty()) {
            if (pendingCompletion) {
                sendActionBarUpdate(server);
                completionMessagePending.set(false);
                actionBarNotifier.clear();
                jobInfo = null;
            }
            return;
        }

        int batchSize = Math.max(1, Config.ROLLBACK_BATCH_SIZE.get());
        int processed = processBatch(server, batchSize);
        processedTotal.addAndGet(processed);

        ticksSinceProgressLog++;
        int progressInterval = Math.max(1, Config.PROGRESS_TICK_INTERVAL.get());
        if (ticksSinceProgressLog >= progressInterval) {
            ticksSinceProgressLog = 0;
            LOGGER.info("Rollback progress: remaining={}, processedTotal={}, runningJob={}", queue.size(), processedTotal.get(), runningJob.get());
            sendActionBarUpdate(server);
        }

        if (completionMessagePending.get()) {
            sendActionBarUpdate(server);
            completionMessagePending.set(false);
            actionBarNotifier.clear();
            jobInfo = null;
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
                lastCompletion = CompletionReason.CANCELLED;
                loading.set(false);
                completionMessagePending.set(true);
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
                errorTotal.incrementAndGet();
                LOGGER.error("Failed to apply action {}", queued, e);
            }

            processed++;
        }

        if (queue.isEmpty() && runningJob.get() && !loading.get()) {
            LOGGER.info("Rollback job finished (queue empty)");
            runningJob.set(false);
            loading.set(false);
            lastCompletion = CompletionReason.FINISHED;
            completionMessagePending.set(true);
        }
        return processed;
    }

    private void sendActionBarUpdate(MinecraftServer server) {
        if (server == null || jobInfo == null || !actionBarNotifier.hasWatchers()) return;

        Component text = buildActionBarComponent();
        if (text == null) return;

        actionBarNotifier.send(server, text);
    }

    @SuppressWarnings("null")
    private Component buildActionBarComponent() {
        RollbackJobInfo info = jobInfo;
        if (info == null) return null;

        long expected = expectedTotal.get();
        long processed = processedTotal.get();
        int queued = queue.size();
        long remaining = expected > 0 ? Math.max(0, expected - processed) : queued;
        long elapsedMs = jobStartMillis == 0 ? 0L : Math.max(0L, System.currentTimeMillis() - jobStartMillis);
        boolean running = runningJob.get();
        boolean cancelling = cancelFlag.get();
        boolean isLoading = loading.get();
        CompletionReason completion = lastCompletion;
        long etaMs = estimateEtaMs(processed, remaining, elapsedMs);

        if (completion == CompletionReason.FINISHED && !running) {
            return coloredCompletion(ACTIONBAR_DONE_KEY, 0x55FF55);
        }
        if (completion == CompletionReason.FAILED && !running) {
            return coloredCompletion(ACTIONBAR_FAILED_KEY, 0xFF5555);
        }
        if (completion == CompletionReason.CANCELLED && !running) {
            return coloredCompletion(ACTIONBAR_CANCELLED_KEY, 0xAAAAAA);
        }

        MutableComponent text = Component.translatable(ACTIONBAR_PREFIX_KEY, statusLabel(running, cancelling, isLoading, completion));

        if (expected > 0) {
            text = text.append(space()).append(Component.translatable(ACTIONBAR_PROGRESS_KEY,
                    Component.literal(formatPercent(processed, expected)),
                    Component.literal(formatCount(processed)),
                    Component.literal(formatCount(expected))));
        } else {
            text = text.append(space()).append(Component.translatable(ACTIONBAR_PROGRESS_SIMPLE_KEY,
                    Component.literal(formatCount(processed))));
        }

        text = text.append(space()).append(Component.translatable(ACTIONBAR_REMAINING_KEY, Component.literal(formatCount(remaining))));
        if (elapsedMs > 0) {
            text = text.append(space()).append(Component.translatable(ACTIONBAR_ELAPSED_KEY, Component.literal(formatDuration(elapsedMs))));
            if (etaMs >= 0 && completion == CompletionReason.NONE && !isLoading) {
                text = text.append(space()).append(Component.translatable(ACTIONBAR_ETA_KEY, Component.literal(formatDuration(etaMs))));
            }
        }

        text = text.append(space()).append(Component.translatable(ACTIONBAR_TIME_KEY, info.timeLabel()));

        String playerName = info.player().orElse(null);
        if (playerName != null) {
            text = text.append(space()).append(Component.translatable(ACTIONBAR_PLAYER_KEY, playerName));
        }

        String radius = info.radiusLabel().orElse(null);
        if (radius != null) {
            text = text.append(space()).append(Component.translatable(ACTIONBAR_RADIUS_KEY, radius));
        }

        text = text.append(space()).append(Component.translatable(ACTIONBAR_SCOPE_KEY, describeKindComponent(info.kind())));

        long errors = errorTotal.get();
        if (errors > 0) {
            text = text.append(space()).append(Component.translatable(ACTIONBAR_ERRORS_KEY, Component.literal(formatCount(errors))));
        }
        return text;
    }

    @SuppressWarnings("null")
    private Component coloredCompletion(String key, int rgb) {
        return Component.translatable(key).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    private MutableComponent statusLabel(boolean running, boolean cancelling, boolean isLoading, CompletionReason completion) {
        if (completion == CompletionReason.FINISHED && !running) return Component.translatable(ACTIONBAR_STATUS_FINISHED_KEY);
        if (completion == CompletionReason.CANCELLED && !running) return Component.translatable(ACTIONBAR_STATUS_CANCELLED_KEY);
        if (completion == CompletionReason.FAILED && !running) return Component.translatable(ACTIONBAR_STATUS_FAILED_KEY);
        if (cancelling) return Component.translatable(ACTIONBAR_STATUS_CANCELLING_KEY);
        if (isLoading) return Component.translatable(ACTIONBAR_STATUS_LOADING_KEY);
        return Component.translatable(ACTIONBAR_STATUS_RUNNING_KEY);
    }

    private MutableComponent describeKindComponent(RollbackKind kind) {
        return switch (kind) {
            case BLOCKS_ONLY -> Component.translatable(LANG_SCOPE_BASE + "blocks_only");
            case ITEMS_ONLY -> Component.translatable(LANG_SCOPE_BASE + "items_only");
            default -> Component.translatable(LANG_SCOPE_BASE + "both");
        };
    }

    private long estimateEtaMs(long processed, long remaining, long elapsedMs) {
        if (processed <= 0 || elapsedMs <= 0) return -1;
        double perActionMs = (double) elapsedMs / processed;
        return Math.round(perActionMs * remaining);
    }

    private String formatPercent(long processed, long expected) {
        if (expected <= 0) return "0%";
        long pct = Math.round((double) processed * 100.0 / (double) expected);
        if (pct > 100) pct = 100;
        return pct + "%";
    }

    private String formatCount(long value) {
        if (value >= 1_000_000) {
            return formatShort(value / 1_000_000.0, "m");
        }
        if (value >= 1_000) {
            return formatShort(value / 1_000.0, "k");
        }
        return Long.toString(value);
    }

    private String formatShort(double value, String suffix) {
        String formatted = String.format("%.1f", value);
        if (formatted.endsWith(".0")) {
            formatted = formatted.substring(0, formatted.length() - 2);
        }
        return formatted + suffix;
    }

    private Component space() {
        return Component.literal(" ");
    }

    private String formatDuration(long millis) {
        if (millis < 0) return "--";
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%02d:%02d", minutes, secs);
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
        @SuppressWarnings("null")
        var state = level.getBlockState(pos);
        var block = state.getBlock();

        if (block instanceof ChestBlock chest) {
            @SuppressWarnings("null")
            Container merged = ChestBlock.getContainer(chest, state, level, pos, false);
            if (merged != null) return merged;
        }

        @SuppressWarnings("null")
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            return container;
        }

        return null;
    }

    @SuppressWarnings("null")
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

    @SuppressWarnings("null")
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
                    @SuppressWarnings("null")
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

    private enum CompletionReason {
        NONE,
        FINISHED,
        CANCELLED,
        FAILED
    }

    private record RollbackJobInfo(String timeLabel, Optional<String> player, Optional<String> radiusLabel, RollbackKind kind) {}

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
