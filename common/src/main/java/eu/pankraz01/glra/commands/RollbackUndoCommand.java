package eu.pankraz01.glra.commands;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import eu.pankraz01.glra.GriefloggerRollbackAddon;
import eu.pankraz01.glra.Permissions;
import eu.pankraz01.glra.database.dao.RollbackActionLogDAO;
import eu.pankraz01.glra.database.dao.RollbackHistoryDAO;

public final class RollbackUndoCommand {
    private static final String LANG_BASE = "message.griefloggerrollbackaddon.rollback.undo.";
    private static final String DISABLED_KEY = "message.griefloggerrollbackaddon.rollback.disabled";
    private static final String NOT_INITIALIZED_KEY = "message.griefloggerrollbackaddon.rollback.not_initialized";
    private static final RollbackHistoryDAO HISTORY = new RollbackHistoryDAO();
    private static final RollbackActionLogDAO ACTION_LOG = new RollbackActionLogDAO();

    private RollbackUndoCommand() {
    }

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gl")
                .then(Commands.literal("rollback")
                        .then(Commands.literal("undo")
                                .requires(src -> Permissions.has(src, Permissions.COMMAND_ROLLBACK_UNDO, Permissions.defaultOpLevel()))
                                .executes(ctx -> execute(ctx, 1))
                                .then(Commands.argument("steps", IntegerArgumentType.integer(1))
                                        .executes(ctx -> execute(ctx, IntegerArgumentType.getInteger(ctx, "steps")))))));
    }

    @SuppressWarnings("null")
    private static int execute(CommandContext<CommandSourceStack> ctx, int steps) {
        if (!GriefloggerRollbackAddon.isEnabled()) {
            ctx.getSource().sendFailure(tr(DISABLED_KEY, "Rollback addon is disabled because no database connection was available during startup"));
            return 0;
        }

        var mgr = GriefloggerRollbackAddon.ROLLBACK_MANAGER;
        if (mgr == null) {
            ctx.getSource().sendFailure(tr(NOT_INITIALIZED_KEY, "Rollback manager not initialized"));
            return 0;
        }

        List<Long> jobIds;
        try {
            jobIds = HISTORY.loadRecentHistoryIds(steps);
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.warn(GriefloggerRollbackAddon.MOD_PREFIX + "Could not load rollback history for undo", e);
            ctx.getSource().sendFailure(tr(LANG_BASE + "history_error", "Could not load rollback history"));
            return 0;
        }

        if (jobIds.isEmpty()) {
            ctx.getSource().sendFailure(tr(LANG_BASE + "no_history", "No rollback history found"));
            return 0;
        }

        List<RollbackActionLogDAO.LoggedRollbackAction> actions;
        try {
            actions = ACTION_LOG.loadActionsForJobs(jobIds);
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.warn(GriefloggerRollbackAddon.MOD_PREFIX + "Could not load rollback actions for undo", e);
            ctx.getSource().sendFailure(tr(LANG_BASE + "actions_error", "Could not load rollback actions for undo"));
            return 0;
        }

        if (actions.isEmpty()) {
            ctx.getSource().sendFailure(tr(LANG_BASE + "no_actions", "No logged rollback actions to undo"));
            return 0;
        }

        mgr.startUndo(actions, "undo last " + jobIds.size());
        tryTrackActionBar(ctx.getSource(), mgr);
        ctx.getSource().sendSuccess(() -> successMessage(jobIds.size(), actions.size()), true);
        return 1;
    }

    private static void tryTrackActionBar(CommandSourceStack source, eu.pankraz01.glra.rollback.RollbackManager mgr) {
        try {
            ServerPlayer player = source.getPlayer();
            if (player != null) {
                mgr.trackActionBar(player);
            }
        } catch (Exception ignored) {
            // ignore if source is console or missing player
        }
    }

    private static MutableComponent successMessage(int jobs, int actions) {
        return Component.literal("Undo started")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Component.literal("\n"))
                .append(Component.literal("Jobs: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(jobs)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("Actions: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(Integer.toString(actions)).withStyle(ChatFormatting.GOLD));
    }

    private static MutableComponent tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }
}
