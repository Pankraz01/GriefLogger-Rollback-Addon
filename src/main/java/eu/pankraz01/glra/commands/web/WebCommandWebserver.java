package eu.pankraz01.glra.commands.web;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import eu.pankraz01.glra.GriefloggerRollbackAddon;
import eu.pankraz01.glra.Permissions;

/**
 * Handles /gl web start|stop commands for the web server lifecycle.
 */
public final class WebCommandWebserver {
    private static final String LANG_BASE = "message.griefloggerrollbackaddon.web.server.";
    private static final String STARTED = LANG_BASE + "started";
    private static final String STOPPED = LANG_BASE + "stopped";
    private static final String ALREADY_RUNNING = LANG_BASE + "already_running";
    private static final String NOT_RUNNING = LANG_BASE + "not_running";
    private static final String DISABLED = LANG_BASE + "disabled";
    private static final String TOKEN_MISSING = LANG_BASE + "token_missing";
    private static final String MANAGER_NULL = LANG_BASE + "manager_null";
    private static final String ADDON_DISABLED = LANG_BASE + "addon_disabled";
    private static final String START_FAILED = LANG_BASE + "start_failed";
    private static final String STOP_FAILED = LANG_BASE + "stop_failed";

    private WebCommandWebserver() {}

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gl")
                .then(Commands.literal("web")
                        .requires(src -> Permissions.has(src, Permissions.COMMAND_WEB_SERVER, Permissions.defaultOpLevel()))
                        .then(Commands.literal("start").executes(WebCommandWebserver::start))
                        .then(Commands.literal("stop").executes(WebCommandWebserver::stop))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        if (GriefloggerRollbackAddon.INSTANCE == null) {
            ctx.getSource().sendFailure(Component.literal("Addon not initialized"));
            return 0;
        }
        GriefloggerRollbackAddon.StartResult result = GriefloggerRollbackAddon.INSTANCE.startWebServerCommand(ctx.getSource().getServer());
        if (result.success()) {
            ctx.getSource().sendSuccess(() -> status(STARTED, "GLRA web server started", ChatFormatting.GREEN), true);
            return 1;
        }
        ctx.getSource().sendFailure(mapReason(result));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        if (GriefloggerRollbackAddon.INSTANCE == null) {
            ctx.getSource().sendFailure(Component.literal("Addon not initialized"));
            return 0;
        }
        GriefloggerRollbackAddon.StopResult result = GriefloggerRollbackAddon.INSTANCE.stopWebServerCommand();
        if (result.success()) {
            ctx.getSource().sendSuccess(() -> status(STOPPED, "GLRA web server stopped", ChatFormatting.RED), true);
            return 1;
        }
        ctx.getSource().sendFailure(mapReason(result));
        return 0;
    }

    private static Component mapReason(GriefloggerRollbackAddon.StartResult res) {
        return switch (res.reason()) {
            case "web.disabled" -> Component.translatableWithFallback(DISABLED, "Web UI/API disabled via config");
            case "web.token_missing" -> Component.translatableWithFallback(TOKEN_MISSING, "Cannot start: requireApiToken=true but webApiToken is empty");
            case "web.manager_null" -> Component.translatableWithFallback(MANAGER_NULL, "Cannot start: rollback manager not initialized");
            case "addon.disabled" -> Component.translatableWithFallback(ADDON_DISABLED, "Addon disabled");
            case "web.already_running" -> Component.translatableWithFallback(ALREADY_RUNNING, "Web server already running");
            case "web.start_failed" -> Component.translatableWithFallback(START_FAILED, "Failed to start web server: %s", arg(res.args()));
            default -> Component.translatableWithFallback(START_FAILED, "Failed to start web server");
        };
    }

    private static Component mapReason(GriefloggerRollbackAddon.StopResult res) {
        return switch (res.reason()) {
            case "web.not_running" -> Component.translatableWithFallback(NOT_RUNNING, "Web server is not running");
            case "web.stop_failed" -> Component.translatableWithFallback(STOP_FAILED, "Failed to stop web server: %s", arg(res.args()));
            default -> Component.translatableWithFallback(STOP_FAILED, "Failed to stop web server");
        };
    }

    private static Component status(String key, String fallback, ChatFormatting color) {
        var prefix = Component.literal("[GLRA] ").withStyle(ChatFormatting.GOLD);
        var body = Component.translatableWithFallback(key, fallback).copy().withStyle(color, ChatFormatting.BOLD);
        return Component.empty().append(prefix).append(body);
    }

    private static Object arg(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return "unknown";
        return args[0];
    }
}
