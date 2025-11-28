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

import eu.pankraz01.glra.GriefloggerRollbackAddon;
import eu.pankraz01.glra.rollback.RollbackManager;

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

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gl")
                .then(Commands.literal("rollback")
                        .executes(RollbackCommand::showUsage)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, StringArgumentType.getString(ctx, "args"))))));
    }

    @SuppressWarnings("null")
    private static int showUsage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.translatable(USAGE_KEY));
        return 0;
    }

    @SuppressWarnings("null")
    private static int execute(CommandContext<CommandSourceStack> ctx, String rawArgs) {
        if (!GriefloggerRollbackAddon.isEnabled()) {
            ctx.getSource().sendFailure(Component.translatable(DISABLED_KEY));
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
            ctx.getSource().sendFailure(Component.translatable(NOT_INITIALIZED_KEY));
            return 0;
        }

        var area = buildArea(ctx, args.radius());

        mgr.startRollback(since, args.timeLabel(), args.player(), area, args.radiusLabel(), args.kind());
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
        return 1;
    }

    @SuppressWarnings("null")
    private static MutableComponent buildSuccessComponent(ParsedArgs args) {
        MutableComponent header = Component.translatable(STARTED_KEY)
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);

        MutableComponent body = Component.empty()
                .append(Component.translatable(WINDOW_LABEL_KEY).withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(WINDOW_VALUE_KEY, args.timeLabel()).withStyle(ChatFormatting.WHITE));

        if (args.player().isPresent()) {
            body = body.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable(PLAYER_LABEL_KEY).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(args.player().get()).withStyle(ChatFormatting.AQUA));
        }

        if (args.radius().isPresent()) {
            body = body.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable(RADIUS_LABEL_KEY).withStyle(ChatFormatting.GRAY))
                    .append(radiusDisplay(args.radius().get()));
        }

        body = body.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable(SCOPE_LABEL_KEY).withStyle(ChatFormatting.GRAY))
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
        Radius radius = null;
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
                    long parsedDuration = parseDurationToMillis(value);
                    if (parsedDuration <= 0) return ParseResult.error(INVALID_TIME_KEY, value);
                    durationMs = parsedDuration;
                    timeLabel = value;
                    break;
                case "r":
                    if (radius != null) return ParseResult.error(DUPLICATE_RADIUS_KEY);
                    radius = parseRadius(value);
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

    private static long parseDurationToMillis(String value) {
        if (value == null) return -1;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return -1;

        String lower = trimmed.toLowerCase();
        if (lower.endsWith("ms") && lower.length() > 2) {
            String numberPart = trimmed.substring(0, trimmed.length() - 2);
            try {
                long amount = Long.parseLong(numberPart);
                if (amount < 0) return -1;
                return amount;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        char unit = trimmed.charAt(trimmed.length() - 1);
        String numberPart = trimmed;
        long multiplier = 1_000L;
        if (Character.isLetter(unit)) {
            numberPart = trimmed.substring(0, trimmed.length() - 1);

            if (unit == 'M') {
                multiplier = 2_592_000_000L; // 30 days as a month approximation
            } else {
                switch (Character.toLowerCase(unit)) {
                    case 's':
                        multiplier = 1_000L;
                        break;
                    case 'm':
                        multiplier = 60_000L;
                        break;
                    case 'h':
                        multiplier = 3_600_000L;
                        break;
                    case 'd':
                        multiplier = 86_400_000L;
                        break;
                    case 'y':
                        multiplier = 31_536_000_000L;
                        break;
                    default:
                        return -1;
                }
            }
        }

        if (numberPart.isBlank()) return -1;

        try {
            long amount = Long.parseLong(numberPart);
            if (amount < 0) return -1;
            return Math.multiplyExact(amount, multiplier);
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    private static Radius parseRadius(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;

        boolean chunks = false;
        String digits = trimmed;

        char prefix = trimmed.charAt(0);
        if (prefix == 'c' || prefix == 'C') {
            chunks = true;
            digits = trimmed.substring(1);
        } else if (prefix == 'b' || prefix == 'B') {
            digits = trimmed.substring(1);
        }

        if (digits.isEmpty()) return null;

        try {
            int radius = Integer.parseInt(digits);
            if (radius <= 0) return null;
            return new Radius(radius, chunks);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Radius(int value, boolean inChunks) {
        String label() {
            return value + (inChunks ? "c" : "b");
        }
    }

    private record ParsedArgs(Optional<String> player, long durationMs, String timeLabel, Optional<Radius> radius, RollbackManager.RollbackKind kind) {
        Optional<String> radiusLabel() {
            return radius.map(Radius::label);
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
            return Component.translatable(errorKey, errorArgs);
        }
    }

    private static Optional<RollbackManager.RollbackArea> buildArea(CommandContext<CommandSourceStack> ctx, Optional<Radius> radiusOpt) {
        if (radiusOpt.isEmpty()) return Optional.empty();
        Radius radius = radiusOpt.get();

        try {
            ServerLevel level = ctx.getSource().getLevel();
            if (level == null) return Optional.empty();

            @SuppressWarnings("null")
            BlockPos center = BlockPos.containing(ctx.getSource().getPosition());
            int radiusBlocks = radius.inChunks() ? radius.value() * 16 : radius.value();
            return Optional.of(new RollbackManager.RollbackArea(level.dimension(), center, radiusBlocks));
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.warn(GriefloggerRollbackAddon.MOD_PREFIX + "Could not determine rollback area from command source", e);
            return Optional.empty();
        }
    }

    private static MutableComponent describeKindComponent(RollbackManager.RollbackKind kind) {
        return switch (kind) {
            case BLOCKS_ONLY -> Component.translatable(SCOPE_BLOCKS_KEY);
            case ITEMS_ONLY -> Component.translatable(SCOPE_ITEMS_KEY);
            default -> Component.translatable(SCOPE_BOTH_KEY);
        };
    }

    private static MutableComponent radiusDisplay(Radius radius) {
        String key = radius.inChunks() ? RADIUS_CHUNKS_VALUE_KEY : RADIUS_BLOCKS_VALUE_KEY;
        return Component.translatable(key, radius.value()).withStyle(ChatFormatting.YELLOW);
    }
}
