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
import net.minecraft.server.level.ServerLevel;

import eu.pankraz01.glra.GriefloggerRollbackAddon;
import eu.pankraz01.glra.rollback.RollbackManager;

public final class RollbackCommand {
    private static final String USAGE = "Usage: /gl rollback u:<user> t:<time (s|m|h|d|M|y)> r:<radius|c<chunks>>";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gl")
                .then(Commands.literal("rollback")
                        .executes(RollbackCommand::showUsage)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, StringArgumentType.getString(ctx, "args"))))));
    }

    private static int showUsage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.literal(USAGE));
        return 0;
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, String rawArgs) {
        if (!GriefloggerRollbackAddon.isEnabled()) {
            ctx.getSource().sendFailure(Component.literal("Rollback addon is disabled because no database connection was available during startup"));
            return 0;
        }

        ParseResult result = parseArgs(rawArgs);
        if (!result.success()) {
            ctx.getSource().sendFailure(Component.literal(result.error()));
            return 0;
        }

        ParsedArgs args = result.args();

        long since = Instant.now().toEpochMilli() - args.durationMs();
        if (since < 0) {
            since = 0;
        }

        var mgr = GriefloggerRollbackAddon.ROLLBACK_MANAGER;
        if (mgr == null) {
            ctx.getSource().sendFailure(Component.literal("Rollback manager not initialized"));
            return 0;
        }

        var area = buildArea(ctx, args.radius());

        mgr.startRollback(since, args.player(), area);
        Supplier<Component> msg = () -> Component.literal(buildSuccessMessage(args));
        ctx.getSource().sendSuccess(msg, true);
        return 1;
    }

    private static String buildSuccessMessage(ParsedArgs args) {
        StringBuilder sb = new StringBuilder("Rollback job started for last ").append(args.timeLabel());
        args.player().ifPresent(u -> sb.append(", player=").append(u));
        args.radius().ifPresent(r -> sb.append(", radius=").append(r.value()).append(r.inChunks() ? " chunks" : " blocks"));
        return sb.toString();
    }

    private static ParseResult parseArgs(String raw) {
        Optional<String> player = Optional.empty();
        Long durationMs = null;
        String timeLabel = null;
        Radius radius = null;

        for (String token : raw.split("\\s+")) {
            if (token.isBlank()) continue;

            int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) {
                return ParseResult.error("Invalid argument: " + token);
            }

            String key = token.substring(0, colon).toLowerCase();
            String value = token.substring(colon + 1);

            switch (key) {
                case "u":
                    if (player.isPresent()) return ParseResult.error("Duplicate user argument");
                    player = Optional.of(value);
                    break;
                case "t":
                    if (durationMs != null) return ParseResult.error("Duplicate time argument");
                    long parsedDuration = parseDurationToMillis(value);
                    if (parsedDuration <= 0) return ParseResult.error("Invalid time value: " + value);
                    durationMs = parsedDuration;
                    timeLabel = value;
                    break;
                case "r":
                    if (radius != null) return ParseResult.error("Duplicate radius argument");
                    radius = parseRadius(value);
                    if (radius == null) return ParseResult.error("Invalid radius value: " + value);
                    break;
                default:
                    return ParseResult.error("Unknown argument: " + key);
            }
        }

        if (durationMs == null) {
            return ParseResult.error("Missing time argument (t:)");
        }

        return ParseResult.success(new ParsedArgs(player, durationMs, timeLabel == null ? "provided time" : timeLabel, Optional.ofNullable(radius)));
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

    private record Radius(int value, boolean inChunks) {}

    private record ParsedArgs(Optional<String> player, long durationMs, String timeLabel, Optional<Radius> radius) {}

    private record ParseResult(ParsedArgs args, String error) {
        static ParseResult success(ParsedArgs args) {
            return new ParseResult(args, null);
        }

        static ParseResult error(String msg) {
            return new ParseResult(null, msg);
        }

        boolean success() {
            return error == null && args != null;
        }
    }

    private static Optional<RollbackManager.RollbackArea> buildArea(CommandContext<CommandSourceStack> ctx, Optional<Radius> radiusOpt) {
        if (radiusOpt.isEmpty()) return Optional.empty();
        Radius radius = radiusOpt.get();

        try {
            ServerLevel level = ctx.getSource().getLevel();
            if (level == null) return Optional.empty();

            BlockPos center = BlockPos.containing(ctx.getSource().getPosition());
            int radiusBlocks = radius.inChunks() ? radius.value() * 16 : radius.value();
            return Optional.of(new RollbackManager.RollbackArea(level.dimension(), center, radiusBlocks));
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.warn(GriefloggerRollbackAddon.MOD_PREFIX + "Could not determine rollback area from command source", e);
            return Optional.empty();
        }
    }
}
