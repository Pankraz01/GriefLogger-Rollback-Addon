package eu.pankraz01.glra.commands.web;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.concurrent.CompletableFuture;
import java.util.Arrays;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import eu.pankraz01.glra.GriefloggerRollbackAddon;
import eu.pankraz01.glra.Permissions;
import eu.pankraz01.glra.database.dao.WebTokenDAO;

/**
 * Handles /gl web token ... commands.
 */
public final class WebCommandToken {
    private static final WebTokenDAO TOKENS = new WebTokenDAO();
    private static final String LANG_WEB_BASE = "message.griefloggerrollbackaddon.web.";
    private static final String CREATED_TITLE = LANG_WEB_BASE + "token.created.title";
    private static final String CREATED_PLAYER = LANG_WEB_BASE + "token.created.player";
    private static final String CREATED_TOKEN = LANG_WEB_BASE + "token.created.token";
    private static final String CREATED_COPY = LANG_WEB_BASE + "token.created.copy";
    private static final String REMOVED = LANG_WEB_BASE + "token.removed";
    private static final String NOT_FOUND = LANG_WEB_BASE + "token.not_found";
    private static final String LIST_TITLE = LANG_WEB_BASE + "token.list.title";
    private static final String LIST_EMPTY = LANG_WEB_BASE + "token.list.empty";
    private static final String LIST_COPY_HINT = LANG_WEB_BASE + "token.list.copy_hint";
    private static final String DISABLED = "message.griefloggerrollbackaddon.rollback.disabled";

    private WebCommandToken() {}

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gl")
                .then(Commands.literal("web")
                        .requires(src -> Permissions.has(src, Permissions.COMMAND_WEB_TOKEN, Permissions.defaultOpLevel()))
                        .then(Commands.literal("token")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(WebCommandToken::suggestOnlinePlayers)
                                                .executes(ctx -> addToken(ctx, StringArgumentType.getString(ctx, "player")))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(WebCommandToken::suggestOnlinePlayers)
                                                .executes(ctx -> removeToken(ctx, StringArgumentType.getString(ctx, "player")))))
                                .then(Commands.literal("list")
                                        .executes(ctx -> listTokens(ctx, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> listTokens(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))));
    }

    @SuppressWarnings("null")
    private static int addToken(CommandContext<CommandSourceStack> ctx, String playerName) {
        if (!GriefloggerRollbackAddon.isEnabled()) {
            ctx.getSource().sendFailure(Component.translatableWithFallback(DISABLED, "Rollback addon is disabled because no database connection was available during startup"));
            return 0;
        }
        try {
            String token = TOKENS.createOrReplace(playerName);
            var msg = tr(CREATED_TITLE, "Web token created").copy();
            msg.setStyle(msg.getStyle().applyFormat(ChatFormatting.GOLD).applyFormat(ChatFormatting.BOLD));
            var playerLabel = tr(CREATED_PLAYER, "Player: ").copy();
            playerLabel.setStyle(playerLabel.getStyle().applyFormat(ChatFormatting.GRAY));
            var playerComp = Component.literal(playerName);
            playerComp.setStyle(playerComp.getStyle().applyFormat(ChatFormatting.AQUA));
            var tokenLabel = tr(CREATED_TOKEN, "Token: ").copy();
            tokenLabel.setStyle(tokenLabel.getStyle().applyFormat(ChatFormatting.GRAY));
            var tokenComp = Component.literal(token).setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
            var copyHint = tr(CREATED_COPY, "[copy]").copy();
            copyHint.setStyle(copyHint.getStyle().applyFormat(ChatFormatting.DARK_GRAY));

            msg.append(Component.literal("\n"))
               .append(playerLabel)
               .append(playerComp)
               .append(Component.literal("\n"))
               .append(tokenLabel)
               .append(tokenComp)
               .append(Component.literal("  "))
               .append(copyHint);
            Component finalMsg = msg;
            ctx.getSource().sendSuccess(() -> finalMsg, false);
            return 1;
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.error(GriefloggerRollbackAddon.MOD_PREFIX + "Failed to create token for " + playerName, e);
            ctx.getSource().sendFailure(Component.literal("Could not create token for " + playerName));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int removeToken(CommandContext<CommandSourceStack> ctx, String playerName) {
        if (!GriefloggerRollbackAddon.isEnabled()) {
            ctx.getSource().sendFailure(Component.translatableWithFallback(DISABLED, "Rollback addon is disabled because no database connection was available during startup"));
            return 0;
        }
        try {
            boolean removed = TOKENS.remove(playerName);
            if (removed) {
                ctx.getSource().sendSuccess(() -> tr(REMOVED, "Removed token for %s", playerName), false);
                return 1;
            }
            ctx.getSource().sendFailure(tr(NOT_FOUND, "No token found for %s", playerName));
            return 0;
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.error(GriefloggerRollbackAddon.MOD_PREFIX + "Failed to remove token for " + playerName, e);
            ctx.getSource().sendFailure(Component.literal("Could not remove token for " + playerName));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int listTokens(CommandContext<CommandSourceStack> ctx, int page) {
        if (!GriefloggerRollbackAddon.isEnabled()) {
            ctx.getSource().sendFailure(Component.translatableWithFallback(DISABLED, "Rollback addon is disabled because no database connection was available during startup"));
            return 0;
        }
        final int pageSize = 7;
        int offset = (page - 1) * pageSize;
        try {
            var tokens = TOKENS.listTokens(offset, pageSize + 1);
            boolean hasNext = tokens.size() > pageSize;
            if (hasNext) {
                tokens = tokens.subList(0, pageSize);
            }

            var header = tr(LIST_TITLE, "Web tokens (page %s)", page).copy();
            header.withStyle(s -> s.applyFormat(ChatFormatting.GOLD));
            header.withStyle(s -> s.applyFormat(ChatFormatting.BOLD));

            var body = Component.empty();
            if (tokens.isEmpty()) {
                body.append(Component.literal("\n").append(tr(LIST_EMPTY, "No tokens available.")).withStyle(s -> s.applyFormat(ChatFormatting.GRAY)));
            } else {
                for (var t : tokens) {
                    String name = t.username() != null ? t.username() : ("#" + t.userId());
                    body.append(Component.literal("\n* "))
                            .append(Component.literal(name).withStyle(s -> s.applyFormat(ChatFormatting.AQUA)))
                            .append(Component.literal(" - ").withStyle(s -> s.applyFormat(ChatFormatting.DARK_GRAY)))
                            .append(Component.literal(t.token()).withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)))
                            .append(Component.literal(" [copy]").withStyle(s -> s.applyFormat(ChatFormatting.GRAY)));
                }
            }

            var nav = Component.empty();
            if (page > 1) {
                nav.append(Component.literal("\n<<")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true)));
            }
            if (hasNext) {
                if (page > 1) nav.append(Component.literal("  ").withStyle(s -> s.applyFormat(ChatFormatting.DARK_GRAY)));
                nav.append(Component.literal(">>")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true)));
            }

            var result = Component.empty().append(header).append(body).append(nav);
            ctx.getSource().sendSuccess(() -> result, false);
            return 1;
        } catch (Exception e) {
            GriefloggerRollbackAddon.LOGGER.error(GriefloggerRollbackAddon.MOD_PREFIX + "Failed to list tokens", e);
            ctx.getSource().sendFailure(Component.literal("Could not list tokens"));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        var server = ctx.getSource().getServer();
        if (server == null) {
            return builder.buildFuture();
        }
        var names = server.getPlayerList().getPlayerNamesArray();
        return SharedSuggestionProvider.suggest(Arrays.asList(names), builder);
    }

    private static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }
}
