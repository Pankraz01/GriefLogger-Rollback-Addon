package eu.pankraz01.glra.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;

import eu.pankraz01.glra.Permissions;

/**
 * Command for reloading the common config.
 */
public final class ConfigCommand {
    private static final String LANG_BASE = "message.griefloggerrollbackaddon.config.";
    private static final String RELOAD_OK = LANG_BASE + "reload.ok";
    private static final String RELOAD_FAIL = LANG_BASE + "reload.fail";

    private ConfigCommand() {}

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gl")
                .then(Commands.literal("config")
                        .requires(src -> Permissions.has(src, Permissions.COMMAND_CONFIG_RELOAD, Permissions.defaultOpLevel()))
                        .then(Commands.literal("reload")
                                .executes(ConfigCommand::reload))));
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        try {
            ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());
            ctx.getSource().sendSuccess(() -> Component.translatableWithFallback(RELOAD_OK, "Config reloaded"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.translatableWithFallback(RELOAD_FAIL, "Config reload failed: %s", e.getMessage() == null ? "unknown" : e.getMessage()));
            return 0;
        }
    }
}
