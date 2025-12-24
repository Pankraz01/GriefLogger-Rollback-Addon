package eu.pankraz01.glra.commands;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

import eu.pankraz01.glra.GriefloggerRollbackAddon;
import eu.pankraz01.glra.Messages;
import eu.pankraz01.glra.Permissions;
import eu.pankraz01.glra.rollback.RollbackManager;
import eu.pankraz01.glra.rollback.RollbackInputParser;
import eu.pankraz01.glra.database.dao.RollbackHistoryDAO;

public final class RollbackCommand {
    private static final String LANG_BASE = "message.griefloggerrollbackaddon.rollback.";
    private static final String USAGE_KEY = LANG_BASE + "usage";
    private static final String DISABLED_KEY = LANG_BASE + "disabled";
    private static final String NOT_INITIALIZED_KEY = LANG_BASE + "not_initialized";
    private static final String DUPLICATE_KIND_KEY = LANG_BASE + "parse.duplicate_kind";
    private static final String INVALID_ARGUMENT_KEY = LANG_BASE + "parse.invalid_argument";
    private static final String DUPLICATE_USER_KEY = LANG_BASE + "parse.duplicate_user";
    private static final String DUPLICATE_TIME_KEY = LANG_BASE + "parse.duplicate_time";
    private static final String INVALID_TIME_KEY = LANG_BASE + "parse.invalid_time";
    private static final String DUPLICATE_RADIUS_KEY = LANG_BASE + "parse.duplicate_radius";
    private static final String INVALID_RADIUS_KEY = LANG_BASE + "parse.invalid_radius";
    private static final String UNKNOWN_ARGUMENT_KEY = LANG_BASE + "parse.unknown_argument";
    private static final String MISSING_TIME_KEY = LANG_BASE + "parse.missing_time";
    private static final String STARTED_KEY = LANG_BASE + "started";
    private static final String WINDOW_LABEL_KEY = LANG_BASE + "window.label";
    private static final String WINDOW_VALUE_KEY = LANG_BASE + "window.value";
    private static final String PLAYER_LABEL_KEY = LANG_BASE + "player.label";
    private static final String RADIUS_LABEL_KEY = LANG_BASE + "radius.label";
    private static final String RADIUS_BLOCKS_VALUE_KEY = LANG_BASE + "radius.value.blocks";
    private static final String RADIUS_CHUNKS_VALUE_KEY = LANG_BASE + "radius.value.chunks";
    private static final String SCOPE_LABEL_KEY = LANG_BASE + "scope.label";
    private static final String SCOPE_BLOCKS_KEY = LANG_BASE + "scope.blocks_only";
    private static final String SCOPE_ITEMS_KEY = LANG_BASE + "scope.items_only";
    private static final String SCOPE_BOTH_KEY = LANG_BASE + "scope.both";
    private static final String TRIGGERED_BY_LABEL_KEY = LANG_BASE + "triggered_by";
    private static final RollbackHistoryDAO HISTORY = new RollbackHistoryDAO();

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gl")
                .then(Commands.literal("rollback")
                        .requires(src -> Permissions.has(src, Permissions.COMMAND_ROLLBACK, Permissions.defaultOpLevel()))
                        .executes(RollbackCommand::showUsage)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, StringArgumentType.getString(ctx, "args"))))));
    }

    @SuppressWarnings("null")
    private static int showUsage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(tr(USAGE_KEY, "Usage: /gl rollback u:<user> t:<time (s|m|h|d|M|y)> r:<radius|c<chunks>> [i|b]"));
        return 0;
    }

    @SuppressWarnings("null")
    private static int execute(CommandContext<CommandSourceStack> ctx, String rawArgs) {
        if (!GriefloggerRollbackAddon.isEnabled()) {
            ctx.getSource().sendFailure(tr(DISABLED_KEY, "Rollback addon is disabled because no database connection was available during startup"));
            return 0;
        }

        ParseResult result = parseArgs(rawArgs);
        if (!result.success()) {
            ctx.getSource().sendFailure(result.errorComponent());
            return 0;
        }

        ParsedArgs args = result.args();

        long since = Instant.now().toEpochMilli() - args.durationMs();
        if (since < 0) {
            since = 0;
        }

        var mgr = GriefloggerRollbackAddon.ROLLBACK_MANAGER;
        if (mgr == null) {
            ctx.getSource().sendFailure(tr(NOT_INITIALIZED_KEY, "Rollback manager not initialized"));
            return 0;
        }

        var area = buildArea(ctx, args.radius());

        long historyId = -1;
        try {
            String actorName = ctx.getSource().getTextName();
            Integer actorId = null; // no user-id lookup here to avoid dependency on token DAO
            historyId = HISTORY.recordAndReturnId(actorId, actorName, "ingame", args.timeLabel(), args.durationMs(), args.player(), args.radiusLabel(), args.kind());
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.warn(GriefloggerRollbackAddon.MOD_PREFIX + "Could not record rollback history", e);
        }

        mgr.startRollback(since, args.timeLabel(), args.player(), area, args.radiusLabel(), args.kind(), historyId);
        try {
            ServerLevel level = ctx.getSource().getLevel();
            if (level != null) {
                var player = ctx.getSource().getPlayer();
                if (player != null) {
                    mgr.trackActionBar(player);
                }
            }
        } catch (Exception ignored) {
            // No player attached to the command source (e.g., console).
        }
        Supplier<Component> msg = () -> buildSuccessComponent(args);
        ctx.getSource().sendSuccess(msg, true);
        notifyRollback(ctx.getSource(), args);
        return 1;
    }

    @SuppressWarnings("null")
    private static MutableComponent buildSuccessComponent(ParsedArgs args) {
        MutableComponent header = tr(STARTED_KEY, "Rollback started")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);

        MutableComponent body = Component.empty()
                .append(tr(WINDOW_LABEL_KEY, "Window: ").withStyle(ChatFormatting.GRAY))
                .append(tr(WINDOW_VALUE_KEY, "last %s", args.timeLabel()).withStyle(ChatFormatting.WHITE));

        if (args.player().isPresent()) {
            body = body.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(tr(PLAYER_LABEL_KEY, "Player: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(args.player().get()).withStyle(ChatFormatting.AQUA));
        }

        if (args.radius().isPresent()) {
            body = body.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(tr(RADIUS_LABEL_KEY, "Radius: ").withStyle(ChatFormatting.GRAY))
                    .append(radiusDisplay(args.radius().get()));
        }

        body = body.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(tr(SCOPE_LABEL_KEY, "Scope: ").withStyle(ChatFormatting.GRAY))
                .append(describeKindComponent(args.kind()).withStyle(ChatFormatting.GOLD));

        return Component.empty()
                .append(header)
                .append(Component.literal("\n"))
                .append(body);
    }

    private static ParseResult parseArgs(String raw) {
        Optional<String> player = Optional.empty();
        Long durationMs = null;
        String timeLabel = null;
        RollbackInputParser.Radius radius = null;
        RollbackManager.RollbackKind kind = RollbackManager.RollbackKind.BOTH;

        for (String token : raw.split("\\s+")) {
            if (token.isBlank()) continue;

            String trimmed = token.trim();
            String normalized = trimmed.toLowerCase();

            if (normalized.equals("i")) {
                if (kind != RollbackManager.RollbackKind.BOTH) return ParseResult.error(DUPLICATE_KIND_KEY);
                kind = RollbackManager.RollbackKind.ITEMS_ONLY;
                continue;
            }
            if (normalized.equals("b")) {
                if (kind != RollbackManager.RollbackKind.BOTH) return ParseResult.error(DUPLICATE_KIND_KEY);
                kind = RollbackManager.RollbackKind.BLOCKS_ONLY;
                continue;
            }

            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                return ParseResult.error(INVALID_ARGUMENT_KEY, token);
            }

            String key = trimmed.substring(0, colon).toLowerCase();
            String value = trimmed.substring(colon + 1);

            switch (key) {
                case "u":
                    if (player.isPresent()) return ParseResult.error(DUPLICATE_USER_KEY);
                    player = Optional.of(value);
                    break;
                case "t":
                    if (durationMs != null) return ParseResult.error(DUPLICATE_TIME_KEY);
                    long parsedDuration = RollbackInputParser.parseDurationToMillis(value);
                    if (parsedDuration <= 0) return ParseResult.error(INVALID_TIME_KEY, value);
                    durationMs = parsedDuration;
                    timeLabel = value;
                    break;
                case "r":
                    if (radius != null) return ParseResult.error(DUPLICATE_RADIUS_KEY);
                    radius = RollbackInputParser.parseRadius(value);
                    if (radius == null) return ParseResult.error(INVALID_RADIUS_KEY, value);
                    break;
                case "i":
                case "items":
                    if (kind != RollbackManager.RollbackKind.BOTH) return ParseResult.error(DUPLICATE_KIND_KEY);
                    kind = RollbackManager.RollbackKind.ITEMS_ONLY;
                    break;
                case "b":
                case "blocks":
                    if (kind != RollbackManager.RollbackKind.BOTH) return ParseResult.error(DUPLICATE_KIND_KEY);
                    kind = RollbackManager.RollbackKind.BLOCKS_ONLY;
                    break;
                default:
                    return ParseResult.error(UNKNOWN_ARGUMENT_KEY, key);
            }
        }

        if (durationMs == null) {
            return ParseResult.error(MISSING_TIME_KEY);
        }

        return ParseResult.success(new ParsedArgs(player, durationMs, timeLabel == null ? "provided time" : timeLabel, Optional.ofNullable(radius), kind));
    }

    private record ParsedArgs(Optional<String> player, long durationMs, String timeLabel, Optional<RollbackInputParser.Radius> radius, RollbackManager.RollbackKind kind) {
        Optional<String> radiusLabel() {
            return radius.map(RollbackInputParser::radiusLabel);
        }
    }

    private record ParseResult(ParsedArgs args, String errorKey, Object[] errorArgs) {
        static ParseResult success(ParsedArgs args) {
            return new ParseResult(args, null, new Object[0]);
        }

        static ParseResult error(String key, Object... args) {
            return new ParseResult(null, key, args == null ? new Object[0] : args);
        }

        boolean success() {
            return errorKey == null && args != null;
        }

        Component errorComponent() {
            return tr(errorKey, fallbackFor(errorKey), errorArgs);
        }
    }

    private static Optional<RollbackManager.RollbackArea> buildArea(CommandContext<CommandSourceStack> ctx, Optional<RollbackInputParser.Radius> radiusOpt) {
        if (radiusOpt.isEmpty()) return Optional.empty();
        RollbackInputParser.Radius radius = radiusOpt.get();

        try {
            ServerLevel level = ctx.getSource().getLevel();
            if (level == null) return Optional.empty();

            @SuppressWarnings("null")
            BlockPos center = BlockPos.containing(ctx.getSource().getPosition());
            int radiusBlocks = RollbackInputParser.radiusInBlocks(radius);
            return Optional.of(new RollbackManager.RollbackArea(level.dimension(), center, radiusBlocks));
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.warn(GriefloggerRollbackAddon.MOD_PREFIX + "Could not determine rollback area from command source", e);
            return Optional.empty();
        }
    }

    private static MutableComponent describeKindComponent(RollbackManager.RollbackKind kind) {
        return switch (kind) {
            case BLOCKS_ONLY -> tr(SCOPE_BLOCKS_KEY, "blocks only");
            case ITEMS_ONLY -> tr(SCOPE_ITEMS_KEY, "items only");
            default -> tr(SCOPE_BOTH_KEY, "blocks + items");
        };
    }

    private static void notifyRollback(CommandSourceStack source, ParsedArgs args) {
        MinecraftServer server = source.getServer();
        if (server == null) return;

        String actor = source.getTextName();
        ServerPlayer initiator = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String time = args.timeLabel();
        String playerLabel = args.player().orElse("all");
        String radiusLabel = args.radiusLabel().orElse("global");
        String scope = describeKindComponent(args.kind()).getString();

        MutableComponent header = Messages.header(STARTED_KEY, "Rollback started", ChatFormatting.GREEN);
        MutableComponent body = Component.empty()
                .append(Messages.labelValue(WINDOW_LABEL_KEY, "Window: ", tr(WINDOW_VALUE_KEY, "last %s", time), ChatFormatting.WHITE))
                .append(Messages.separator())
                .append(Messages.labelValue(PLAYER_LABEL_KEY, "Player: ", Component.literal(playerLabel), ChatFormatting.AQUA))
                .append(Messages.separator())
                .append(Messages.labelValue(RADIUS_LABEL_KEY, "Radius: ", Component.literal(radiusLabel), ChatFormatting.YELLOW))
                .append(Messages.separator())
                .append(Messages.labelValue(SCOPE_LABEL_KEY, "Scope: ", describeKindComponent(args.kind()), ChatFormatting.GOLD))
                .append(Messages.separator())
                .append(Messages.labelValue(TRIGGERED_BY_LABEL_KEY, "Triggered by: ", Component.literal(actor), ChatFormatting.GREEN));

        Component message = Component.empty().append(header).append(Component.literal("\n")).append(body);
        broadcast(server, message, initiator);
    }

    private static void broadcast(MinecraftServer server, Component message, ServerPlayer initiator) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (initiator != null && player.getUUID().equals(initiator.getUUID())) {
                continue; // initiator already receives direct feedback
            }
            if (Permissions.has(player, Permissions.NOTIFY_ROLLBACK, Permissions.defaultOpLevel())) {
                player.sendSystemMessage(message);
            }
        }
        try {
            server.sendSystemMessage(message);
        } catch (Exception ignored) {
            // If console is not available, ignore.
        }
    }

    private static MutableComponent radiusDisplay(RollbackInputParser.Radius radius) {
        String key = radius.inChunks() ? RADIUS_CHUNKS_VALUE_KEY : RADIUS_BLOCKS_VALUE_KEY;
        return tr(key, radius.value() + (radius.inChunks() ? " chunks" : " blocks")).withStyle(ChatFormatting.YELLOW);
    }

    private static MutableComponent tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    private static String fallbackFor(String key) {
        return switch (key) {
            case USAGE_KEY -> "Usage: /gl rollback u:<user> t:<time (s|m|h|d|M|y)> r:<radius|c<chunks>> [i|b]";
            case DISABLED_KEY -> "Rollback addon is disabled because no database connection was available during startup";
            case NOT_INITIALIZED_KEY -> "Rollback manager not initialized";
            case DUPLICATE_KIND_KEY -> "Duplicate block/item argument";
            case INVALID_ARGUMENT_KEY -> "Invalid argument: %s";
            case DUPLICATE_USER_KEY -> "Duplicate user argument";
            case DUPLICATE_TIME_KEY -> "Duplicate time argument";
            case INVALID_TIME_KEY -> "Invalid time value: %s";
            case DUPLICATE_RADIUS_KEY -> "Duplicate radius argument";
            case INVALID_RADIUS_KEY -> "Invalid radius value: %s";
            case UNKNOWN_ARGUMENT_KEY -> "Unknown argument: %s";
            case MISSING_TIME_KEY -> "Missing time argument (t:)";
            case TRIGGERED_BY_LABEL_KEY -> "Triggered by: ";
            default -> key;
        };
    }
}

