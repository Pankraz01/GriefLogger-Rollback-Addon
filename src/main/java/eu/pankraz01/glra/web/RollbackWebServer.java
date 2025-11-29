package eu.pankraz01.glra.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import eu.pankraz01.glra.Config;
import eu.pankraz01.glra.rollback.RollbackInputParser;
import eu.pankraz01.glra.rollback.RollbackManager;
import eu.pankraz01.glra.Messages;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import eu.pankraz01.glra.database.dao.ActionDAO;
import eu.pankraz01.glra.database.dao.WebTokenDAO;
import eu.pankraz01.glra.database.dao.RollbackHistoryDAO;
import eu.pankraz01.glra.database.dao.RollbackActionLogDAO;
import eu.pankraz01.glra.database.dao.UnauthorizedAccessLogDAO;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import eu.pankraz01.glra.Permissions;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;

/**
 * Minimal HTTP server that exposes a simple web UI and API endpoint to trigger rollbacks.
 */
public final class RollbackWebServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROLLBACK_WINDOW_LABEL_KEY = "message.griefloggerrollbackaddon.rollback.window.label";
    private static final String ROLLBACK_PLAYER_LABEL_KEY = "message.griefloggerrollbackaddon.rollback.player.label";
    private static final String ROLLBACK_RADIUS_LABEL_KEY = "message.griefloggerrollbackaddon.rollback.radius.label";
    private static final String ROLLBACK_SCOPE_LABEL_KEY = "message.griefloggerrollbackaddon.rollback.scope.label";
    private static final String ROLLBACK_TRIGGER_KEY = "message.griefloggerrollbackaddon.rollback.triggered_by";
    private static final String WEB_BROADCAST_STARTED_KEY = "message.griefloggerrollbackaddon.web.broadcast.started";
    private static final String WEB_BROADCAST_BLOCKED_KEY = "message.griefloggerrollbackaddon.web.broadcast.blocked";
    private static final String WEB_IP_LABEL_KEY = "message.griefloggerrollbackaddon.web.broadcast.ip";
    private static final String WEB_PATH_LABEL_KEY = "message.griefloggerrollbackaddon.web.broadcast.path";
    private static final String WEB_METHOD_LABEL_KEY = "message.griefloggerrollbackaddon.web.broadcast.method";
    private static final String WEB_REASON_LABEL_KEY = "message.griefloggerrollbackaddon.web.broadcast.reason";

    private final RollbackManager rollbackManager;
    private final MinecraftServer server;
    private final ResourceKey<Level> defaultLevel;
    private final String authToken;
    private final boolean requireToken;
    private final ActionDAO actionDAO = new ActionDAO();
    private final WebTokenDAO tokenDAO = new WebTokenDAO();
    private final RollbackHistoryDAO historyDAO = new RollbackHistoryDAO();
    private final RollbackActionLogDAO actionLogDAO = new RollbackActionLogDAO();
    private final UnauthorizedAccessLogDAO unauthorizedLogDAO = new UnauthorizedAccessLogDAO();

    private HttpServer httpServer;
    private ExecutorService executor;

    public RollbackWebServer(RollbackManager rollbackManager, MinecraftServer server, ResourceKey<Level> defaultLevel, String authToken, boolean requireToken) {
        this.rollbackManager = rollbackManager;
        this.server = server;
        this.defaultLevel = defaultLevel;
        this.authToken = authToken == null ? "" : authToken.trim();
        this.requireToken = requireToken;
    }

    public synchronized void start(String bindAddress, int port) throws IOException {
        if (httpServer != null) return;
        InetAddress address = InetAddress.getByName(bindAddress);
        InetSocketAddress socket = new InetSocketAddress(address, port);

        httpServer = HttpServer.create(socket, 0);
        httpServer.createContext("/", this::handleRoot);
        httpServer.createContext("/api/rollback", this::handleRollback);
        httpServer.createContext("/api/players", this::handlePlayers);
        httpServer.createContext("/api/dimensions", this::handleDimensions);
        httpServer.createContext("/api/lang", this::handleLang);
        httpServer.createContext("/web/rollback.css", this::handleCss);

        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "glra-web");
            t.setDaemon(true);
            return t;
        });
        httpServer.setExecutor(executor);
        httpServer.start();

        LOGGER.info("[griefloggerrollbackaddon] Web UI/API listening on {}:{}", bindAddress, port);
    }

    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleLang(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"status\":\"error\",\"message\":\"Use GET\"}");
            return;
        }

        String lang = exchange.getRequestURI().getQuery();
        String requested = null;
        if (lang != null && lang.contains("lang=")) {
            int idx = lang.indexOf("lang=");
            requested = lang.substring(idx + 5);
        }
        String chosen = chooseLanguage(requested, exchange.getRequestHeaders().getFirst("Accept-Language"));

        Map<String, String> translations = loadTranslations(chosen);
        if (translations.isEmpty() && !chosen.equals("en_us")) {
            translations = loadTranslations("en_us");
            chosen = "en_us";
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"ok\",\"lang\":\"").append(escapeJson(chosen)).append("\",\"messages\":{");
        boolean first = true;
        for (var entry : translations.entrySet()) {
            if (!first) json.append(',');
            json.append('"').append(escapeJson(entry.getKey())).append('"')
                .append(':')
                .append('"').append(escapeJson(entry.getValue())).append('"');
            first = false;
        }
        json.append("}}");
        sendJson(exchange, 200, json.toString());
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "Method Not Allowed");
            return;
        }

        if (exchange.getRequestURI().getPath().endsWith(".css")) {
            handleCss(exchange);
            return;
        }

        String html = loadHtml();
        if (html == null) {
            sendPlain(exchange, 500, "Web UI not available (missing resource)");
            return;
        }
        sendHtml(exchange, html);
    }

    private void handleRollback(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"status\":\"error\",\"message\":\"Use POST\"}");
            return;
        }

        FormData form = readForm(exchange);

        AuthResult auth = authorize(exchange, form);
        if (!auth.allowed()) {
            sendJson(exchange, 401, "{\"status\":\"error\",\"message\":\"Unauthorized\"}");
            return;
        }

        String timeRaw = trimToNull(form.params().get("time"));
        if (timeRaw == null) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Missing time\"}");
            return;
        }

        long durationMs = RollbackInputParser.parseDurationToMillis(timeRaw);
        if (durationMs <= 0) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"Invalid time format\"}");
            return;
        }

        Optional<String> player = Optional.ofNullable(trimToNull(form.params().get("player")));

        boolean includeBlocks = form.params().containsKey("blocks");
        boolean includeItems = form.params().containsKey("items");
        if (!includeBlocks && !includeItems) {
            includeBlocks = true;
            includeItems = true;
        }
        RollbackManager.RollbackKind kind = includeBlocks && includeItems
                ? RollbackManager.RollbackKind.BOTH
                : includeBlocks ? RollbackManager.RollbackKind.BLOCKS_ONLY : RollbackManager.RollbackKind.ITEMS_ONLY;

        RadiusResult radius = parseRadius(form.params());
        if (!radius.valid()) {
            sendJson(exchange, 400, "{\"status\":\"error\",\"message\":\"" + escapeJson(radius.error()) + "\"}");
            return;
        }

        if (rollbackManager.hasRunningJob()) {
            sendJson(exchange, 409, "{\"status\":\"error\",\"message\":\"A rollback is already running\"}");
            return;
        }

        long historyId = -1;
        try {
            historyId = historyDAO.recordAndReturnId(auth.userId().orElse(null), auth.username().orElse("web"), "web", timeRaw, durationMs, player, radius.label(), kind);
        } catch (Exception e) {
            LOGGER.warn("Failed to record rollback history for web request", e);
        }
        rollbackManager.startRollback(System.currentTimeMillis() - durationMs, timeRaw, player, radius.area(), radius.label(), kind, historyId);
        notifyRollbackStarted(auth, timeRaw, player, radius, kind);
        StringBuilder ok = new StringBuilder();
        ok.append("{\"status\":\"ok\",\"message\":\"Rollback started\"");
        ok.append(",\"time\":\"").append(escapeJson(timeRaw)).append("\"");
        player.ifPresent(p -> ok.append(",\"player\":\"").append(escapeJson(p)).append("\""));
        ok.append(",\"scope\":\"").append(kind.describe()).append("\"");
        radius.label().ifPresent(r -> ok.append(",\"radius\":\"").append(escapeJson(r)).append("\""));
        ok.append("}");
        sendJson(exchange, 200, ok.toString());
    }

    private void handleCss(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "Method Not Allowed");
            return;
        }
        String css = loadResourceAsString("web/rollback.css");
        if (css == null) {
            sendPlain(exchange, 404, "Not Found");
            return;
        }
        send(exchange, "text/css; charset=utf-8", css, 200);
    }

    private void handlePlayers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"status\":\"error\",\"message\":\"Use GET\"}");
            return;
        }

        AuthResult auth = authorize(exchange, FormData.empty(), false); // Don't spam logs while UI loads without token
        if (!auth.allowed()) {
            sendJson(exchange, 401, "{\"status\":\"error\",\"message\":\"Unauthorized\"}");
            return;
        }

        try {
            var players = actionDAO.loadAllUsernames();
            String json = "{\"status\":\"ok\",\"players\":" + toJsonArray(players) + "}";
            sendJson(exchange, 200, json);
        } catch (Exception e) {
            LOGGER.error("Failed to load player list for web UI", e);
            sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Could not load players\"}");
        }
    }

    private void handleDimensions(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"status\":\"error\",\"message\":\"Use GET\"}");
            return;
        }

        AuthResult auth = authorize(exchange, FormData.empty(), false); // Don't spam logs while UI loads without token
        if (!auth.allowed()) {
            sendJson(exchange, 401, "{\"status\":\"error\",\"message\":\"Unauthorized\"}");
            return;
        }

        try {
            var dims = actionDAO.loadAllDimensions();
            String json = "{\"status\":\"ok\",\"dimensions\":" + toJsonArray(dims) + "}";
            sendJson(exchange, 200, json);
        } catch (Exception e) {
            LOGGER.error("Failed to load dimensions for web UI", e);
            sendJson(exchange, 500, "{\"status\":\"error\",\"message\":\"Could not load dimensions\"}");
        }
    }

    private AuthResult authorize(HttpExchange exchange, FormData form) {
        return authorize(exchange, form, true);
    }

    private AuthResult authorize(HttpExchange exchange, FormData form, boolean logMissingToken) {
        Headers headers = exchange.getRequestHeaders();
        String provided = firstNonBlank(headers.getFirst("X-Auth-Token"), headers.getFirst("Authorization"));
        if (provided == null || provided.isBlank()) {
            provided = trimToNull(form.params().get("token"));
        }
        if (provided == null) {
            if (requireToken || !authToken.isEmpty()) {
                if (logMissingToken) {
                    logUnauthorized(exchange, form, "missing token");
                    notifyUnauthorized(exchange, "missing token");
                }
                return new AuthResult(false, Optional.empty(), Optional.empty());
            }
            return new AuthResult(true, Optional.empty(), Optional.empty());
        }
        try {
            Optional<WebTokenDAO.TokenOwner> user = tokenDAO.findUserByToken(provided);
            if (user.isPresent()) {
                var owner = user.get();
                return new AuthResult(true, Optional.of(owner.userId()), Optional.ofNullable(owner.username()));
            }
        } catch (Exception e) {
            LOGGER.warn("Token lookup failed", e);
        }

        if (!authToken.isEmpty() && authToken.equals(provided)) {
            return new AuthResult(true, Optional.empty(), Optional.empty());
        }
        logUnauthorized(exchange, form, "invalid token");
        notifyUnauthorized(exchange, "invalid token");
        return new AuthResult(false, Optional.empty(), Optional.empty());
    }

    private void logUnauthorized(HttpExchange exchange, FormData form, String reason) {
        if (!Config.LOG_UNAUTHORIZED_WEB_ACCESS.get()) return;
        try {
            String query = Config.LOG_UNAUTHORIZED_WEB_QUERY.get() ? safe(exchange.getRequestURI().getRawQuery()) : null;
            String headers = Config.LOG_UNAUTHORIZED_WEB_HEADERS.get() ? headersToString(exchange.getRequestHeaders()) : null;
            String body = Config.LOG_UNAUTHORIZED_WEB_BODY.get() ? form.rawBody() : null;
            unauthorizedLogDAO.log(
                    System.currentTimeMillis(),
                    remoteIp(exchange),
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    query,
                    headers,
                    body,
                    exchange.getRequestHeaders().getFirst("User-Agent"),
                    exchange.getRequestHeaders().getFirst("Referer"),
                    reason
            );
        } catch (Exception e) {
            LOGGER.warn("Failed to log unauthorized web access", e);
        }
    }

    private void notifyRollbackStarted(AuthResult auth, String timeRaw, Optional<String> player, RadiusResult radius, RollbackManager.RollbackKind kind) {
        if (server == null) return;

        String actor = auth.username().orElse("Web API");
        String playerLabel = player.orElse("all");
        String radiusLabel = radius.label().orElse("global");
        String scope = kind.describe();

        MutableComponent header = Messages.header(WEB_BROADCAST_STARTED_KEY, "Web rollback started", ChatFormatting.GREEN);
        MutableComponent body = Component.empty()
                .append(Messages.labelValue(ROLLBACK_WINDOW_LABEL_KEY, "Window: ", Component.literal(timeRaw), ChatFormatting.WHITE));

        if (!playerLabel.isBlank()) {
            body = body.append(Messages.separator()).append(Messages.labelValue(ROLLBACK_PLAYER_LABEL_KEY, "Player: ", Component.literal(playerLabel), ChatFormatting.AQUA));
        }
        if (!radiusLabel.isBlank()) {
            body = body.append(Messages.separator()).append(Messages.labelValue(ROLLBACK_RADIUS_LABEL_KEY, "Radius: ", Component.literal(radiusLabel), ChatFormatting.YELLOW));
        }
        body = body.append(Messages.separator()).append(Messages.labelValue(ROLLBACK_SCOPE_LABEL_KEY, "Scope: ", Component.literal(scope), ChatFormatting.GOLD));
        body = body.append(Messages.separator()).append(Messages.labelValue(ROLLBACK_TRIGGER_KEY, "Triggered by: ", Component.literal(actor), ChatFormatting.GREEN));

        MutableComponent message = Component.empty().append(header).append(Component.literal("\n")).append(body);
        broadcast(message, Permissions.NOTIFY_WEB_ROLLBACK);
    }

    private void notifyUnauthorized(HttpExchange exchange, String reason) {
        if (server == null || exchange == null) return;

        String ip = remoteIp(exchange);
        MutableComponent header = Messages.header(WEB_BROADCAST_BLOCKED_KEY, "Web access blocked", ChatFormatting.RED);
        MutableComponent body = Component.empty()
                .append(Messages.labelValue(WEB_IP_LABEL_KEY, "IP: ", Component.literal(ip == null ? "unknown" : ip), ChatFormatting.WHITE))
                .append(Messages.separator())
                .append(Messages.labelValue(WEB_PATH_LABEL_KEY, "Path: ", Component.literal(exchange.getRequestURI().getPath()), ChatFormatting.AQUA))
                .append(Messages.separator())
                .append(Messages.labelValue(WEB_METHOD_LABEL_KEY, "Method: ", Component.literal(exchange.getRequestMethod()), ChatFormatting.YELLOW))
                .append(Messages.separator())
                .append(Messages.labelValue(WEB_REASON_LABEL_KEY, "Reason: ", Component.literal(reason), ChatFormatting.GOLD));

        MutableComponent message = Component.empty().append(header).append(Component.literal("\n")).append(body);
        broadcast(message, Permissions.NOTIFY_WEB_UNAUTHORIZED);
    }

    private void broadcast(Component message, PermissionNode<Boolean> permission) {
        if (server == null || permission == null || message == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Permissions.has(player, permission, Permissions.defaultOpLevel())) {
                player.sendSystemMessage(message);
            }
        }
        try {
            server.sendSystemMessage(message);
        } catch (Exception ignored) {
            // Dedicated servers can always log; ignore if not available.
        }
    }

    private String remoteIp(HttpExchange exchange) {
        try {
            return exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null
                    ? exchange.getRemoteAddress().getAddress().getHostAddress()
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String headersToString(Headers headers) {
        StringBuilder sb = new StringBuilder();
        headers.forEach((key, values) -> {
            sb.append(key).append(": ");
            sb.append(String.join(",", values));
            sb.append("\n");
        });
        return sb.toString();
    }

    private String safe(String raw) {
        return raw == null ? null : raw;
    }

    private RadiusResult parseRadius(Map<String, String> form) {
        String radiusRaw = trimToNull(form.get("radius"));
        if (radiusRaw == null) {
            return RadiusResult.ok(Optional.empty(), Optional.empty());
        }

        int radiusValue;
        try {
            radiusValue = Integer.parseInt(radiusRaw);
        } catch (NumberFormatException e) {
            return RadiusResult.error("Radius muss eine Zahl sein");
        }
        if (radiusValue <= 0) {
            return RadiusResult.error("Radius muss groesser 0 sein");
        }

        boolean chunks = "chunks".equalsIgnoreCase(form.getOrDefault("radiusUnit", "blocks"));
        int centerX = parseInt(form.get("centerX"));
        int centerZ = parseInt(form.get("centerZ"));
        if (centerX == Integer.MIN_VALUE || centerZ == Integer.MIN_VALUE) {
            return RadiusResult.error("Center X/Z wird fuer den Radius benoetigt");
        }
        int centerY = parseOptionalInt(form.get("centerY"), 64);

        String dimensionRaw = trimToNull(form.get("dimension"));
        ResourceKey<Level> levelKey = parseDimension(dimensionRaw);

        int radiusBlocks = radiusValue * (chunks ? 16 : 1);
        String label = radiusValue + (chunks ? "c" : "b") + " @" + centerX + "," + centerZ;
        BlockPos center = new BlockPos(centerX, centerY, centerZ);
        return RadiusResult.ok(Optional.of(new RollbackManager.RollbackArea(levelKey, center, radiusBlocks)), Optional.of(label));
    }

    private FormData readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new HashMap<>();
        if (body.isEmpty()) return new FormData(result, "");
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            key = URLDecoder.decode(key, StandardCharsets.UTF_8);
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return new FormData(result, body);
    }

    private ResourceKey<Level> parseDimension(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultLevel;
        }
        try {
            return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(raw.trim()));
        } catch (Exception e) {
            LOGGER.warn("Invalid dimension '{}', falling back to {}", raw, defaultLevel.location());
            return defaultLevel;
        }
    }

    private void sendHtml(HttpExchange exchange, String html) throws IOException {
        send(exchange, "text/html; charset=utf-8", html);
    }

    private void sendPlain(HttpExchange exchange, int status, String text) throws IOException {
        send(exchange, "text/plain; charset=utf-8", text, status);
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, "application/json; charset=utf-8", json, status);
    }

    private void send(HttpExchange exchange, String contentType, String body) throws IOException {
        send(exchange, contentType, body, 200);
    }

    private void send(HttpExchange exchange, String contentType, String body, int status) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private String loadHtml() {
        try (var in = getClass().getClassLoader().getResourceAsStream("web/rollback.html")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to load rollback web UI HTML", e);
            return null;
        }
    }

    private String loadResourceAsString(String path) {
        try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to load resource {}", path, e);
            return null;
        }
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private int parseInt(String value) {
        try {
            return value == null ? Integer.MIN_VALUE : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private int parseOptionalInt(String value, int def) {
        int parsed = parseInt(value);
        return parsed == Integer.MIN_VALUE ? def : parsed;
    }

    private String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Map<String, String> loadTranslations(String langCode) {
        String normalized = langCode == null ? "en_us" : langCode.toLowerCase();
        String path = "assets/griefloggerrollbackaddon/lang/" + normalized + ".json";
        try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) return Map.of();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseFlatStrings(json, "web.");
        } catch (IOException e) {
            LOGGER.warn("Failed to load translations for {}", langCode, e);
            return Map.of();
        }
    }

    private Map<String, String> parseFlatStrings(String json, String prefix) {
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            Map<String, String> result = new java.util.HashMap<>();
            for (var entry : obj.entrySet()) {
                if (entry.getKey().startsWith(prefix) && entry.getValue().isJsonPrimitive()) {
                    result.put(entry.getKey().substring(prefix.length()), entry.getValue().getAsString());
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("Could not parse translation json", e);
            return Map.of();
        }
    }

    private String chooseLanguage(String requested, String acceptLanguage) {
        String candidate = normalizeLang(requested);
        if (candidate != null) return candidate;

        if (acceptLanguage != null) {
            // simple detection: prefer de if present, else en
            String lower = acceptLanguage.toLowerCase();
            if (lower.contains("de")) return "de_de";
        }
        return "en_us";
    }

    private String normalizeLang(String value) {
        if (value == null || value.isBlank()) return null;
        String lower = value.toLowerCase().replace('-', '_');
        if (lower.startsWith("de")) return "de_de";
        if (lower.startsWith("en")) return "en_us";
        return lower;
    }

    private String toJsonArray(Iterable<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(v)).append('"');
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private record RadiusResult(Optional<RollbackManager.RollbackArea> area, Optional<String> label, String error) {
        static RadiusResult ok(Optional<RollbackManager.RollbackArea> area, Optional<String> label) {
            return new RadiusResult(area, label, null);
        }

        static RadiusResult error(String message) {
            return new RadiusResult(Optional.empty(), Optional.empty(), message);
        }

        boolean valid() {
            return error == null;
        }
    }

    private record AuthResult(boolean allowed, Optional<Integer> userId, Optional<String> username) {
    }

    private record FormData(Map<String, String> params, String rawBody) {
        static FormData empty() {
            return new FormData(Map.of(), "");
        }
    }
}

