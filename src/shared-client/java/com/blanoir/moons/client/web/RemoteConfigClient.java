package com.blanoir.moons.client.web;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.chat.ClientChat;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** WebSocket bridge between the module registry and the web UI. */
public final class RemoteConfigClient {
    public static final int PROTOCOL_VERSION = 2;
    private static final Gson GSON = new Gson();
    private static final int MAX_MESSAGE_CHARS = 1_000_000;
    private static final int MAX_REMEMBERED_COMMANDS = 256;
    private static final String SOCKET_URL_KEY = "web.websocketUrl";
    private static final String UI_URL_KEY = "web.uiUrl";
    private static final RemoteConfigClient INSTANCE = new RemoteConfigClient();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "moons-web-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> completedCommands = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_REMEMBERED_COMMANDS;
        }
    };
    private final AtomicBoolean initialized = new AtomicBoolean();

    private volatile WebSocket socket;
    private volatile boolean desired;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean requestPairing;
    private volatile int reconnectAttempt;

    private RemoteConfigClient() {
    }

    public static void init() {
        INSTANCE.initialized.compareAndSet(false, true);
    }

    public static void shutdown() {
        INSTANCE.stop(false);
        INSTANCE.scheduler.shutdownNow();
    }

    public static String hardwareId() {
        return HardwareId.get();
    }

    public static String socketUrl() {
        String override = System.getProperty("moons.web.websocketUrl", "").trim();
        if (override.isEmpty()) override = System.getenv().getOrDefault("MOONS_WEB_SOCKET_URL", "").trim();
        return override.isEmpty() ? Settings.getString(SOCKET_URL_KEY, "").trim() : override;
    }

    public static String uiUrl() {
        String configured = Settings.getString(UI_URL_KEY, "").trim();
        if (!configured.isEmpty()) return configured;
        String socketUrl = socketUrl();
        if (socketUrl.isEmpty()) return "";
        return socketUrl.replaceFirst("^ws://", "http://")
                .replaceFirst("^wss://", "https://")
                .replaceFirst("/ws/client(?:\\?.*)?$", "/");
    }

    public static synchronized void connect() {
        if (!hasBinding()) return;
        INSTANCE.desired = true;
        INSTANCE.open();
    }

    public static synchronized void autoConnect() {
        if (hasBinding()) connect();
    }

    public static synchronized void bind(String rawUrl) {
        Binding binding = parseBinding(rawUrl);
        INSTANCE.stop(false);
        Settings.setString(SOCKET_URL_KEY, binding.socketUrl());
        Settings.setString(UI_URL_KEY, binding.uiUrl());
        DeviceKey.get(binding.socketUrl());
        INSTANCE.requestPairing = true;
        connect();
    }

    public static synchronized void unbind() {
        INSTANCE.stop(false);
        Settings.remove(SOCKET_URL_KEY);
        Settings.remove(UI_URL_KEY);
    }

    public static boolean hasBinding() {
        return !socketUrl().isBlank();
    }

    public static synchronized void disconnect() {
        INSTANCE.stop(true);
    }

    public static boolean isConnected() {
        return INSTANCE.connected;
    }

    public static synchronized boolean requestPairing() {
        if (!hasBinding()) return false;
        INSTANCE.requestPairing = true;
        if (INSTANCE.connected) INSTANCE.sendPairRequest();
        else connect();
        return true;
    }

    private static Binding parseBinding(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("网址不能为空");
        if (!value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) value = "https://" + value;

        URI input;
        try {
            input = URI.create(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("网址格式无效");
        }
        String scheme = input.getScheme() == null ? "" : input.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("http", "https", "ws", "wss").contains(scheme)
                || input.getHost() == null || input.getUserInfo() != null
                || input.getQuery() != null || input.getFragment() != null) {
            throw new IllegalArgumentException("仅支持不含账号、查询参数的 HTTP(S) 或 WS(S) 网址");
        }

        String path = input.getPath() == null ? "" : input.getPath().replaceAll("/+$", "");
        String basePath = path.endsWith("/ws/client")
                ? path.substring(0, path.length() - "/ws/client".length()) : path;
        String authority = input.getRawAuthority();
        boolean secure = scheme.equals("https") || scheme.equals("wss");
        String ui = (secure ? "https://" : "http://") + authority + (basePath.isEmpty() ? "/" : basePath + "/");
        String socket = (secure ? "wss://" : "ws://") + authority + basePath + "/ws/client";
        return new Binding(socket, ui);
    }

    private synchronized void open() {
        if (!desired || connected || connecting || socket != null) return;
        URI uri;
        try {
            uri = URI.create(socketUrl());
            if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("URL must use ws:// or wss://");
            }
        } catch (RuntimeException exception) {
            notifyChat("Web connection URL is invalid: " + exception.getMessage());
            desired = false;
            return;
        }

        connecting = true;
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .header("X-Moons-HWID", hardwareId())
                .buildAsync(uri, new Listener())
                .whenComplete((webSocket, error) -> {
                    connecting = false;
                    if (error != null) {
                        synchronized (this) {
                            socket = null;
                            connected = false;
                        }
                        scheduleReconnect();
                    }
                });
    }

    private synchronized void stop(boolean notify) {
        desired = false;
        connected = false;
        connecting = false;
        reconnectAttempt = 0;
        WebSocket current = socket;
        socket = null;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
        if (notify) notifyChat("Web remote control disconnected.");
    }

    private void scheduleReconnect() {
        if (!desired) return;
        int delay = Math.min(30, 1 << Math.min(reconnectAttempt++, 5));
        scheduler.schedule(this::open, delay, TimeUnit.SECONDS);
    }

    private void sendHello(WebSocket target) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("protocol", PROTOCOL_VERSION);
            hello.addProperty("hwid", hardwareId());
            hello.addProperty("deviceKey", DeviceKey.get(socketUrl()));
            hello.add("config", ModuleRegistry.snapshot());
            target.sendText(GSON.toJson(hello), true);
        });
    }

    private void handleMessage(String raw) {
        if (raw.length() > MAX_MESSAGE_CHARS) return;
        JsonObject message;
        try {
            message = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return;
        }
        String type = string(message, "type");
        if (("welcome".equals(type) || "pairing".equals(type) || "command".equals(type))
                && integer(message, "protocol") != PROTOCOL_VERSION) return;
        if ("welcome".equals(type)) {
            connected = true;
            if (requestPairing) sendPairRequest();
            return;
        }
        if ("pairing".equals(type)) {
            requestPairing = false;
            String code = string(message, "code");
            if (!code.isBlank()) notifyChat("Web pairing code: " + code + " (valid for 5 minutes)");
            return;
        }
        if (!"command".equals(type)) return;
        String commandId = string(message, "commandId");
        if (commandId.isBlank()) return;

        synchronized (completedCommands) {
            String previous = completedCommands.get(commandId);
            if (previous != null) {
                send(previous);
                return;
            }
        }

        JsonObject mutation = message.has("mutation") && message.get("mutation").isJsonObject()
                ? message.getAsJsonObject("mutation") : null;
        Minecraft.getInstance().execute(() -> apply(commandId, mutation));
    }

    private void apply(String commandId, JsonObject mutation) {
        JsonObject result;
        try {
            if (mutation == null) throw new IllegalArgumentException("Missing mutation");
            String kind = string(mutation, "kind");
            String moduleId = string(mutation, "moduleId");
            result = switch (kind) {
                case "module_enabled" -> ModuleRegistry.setEnabled(
                        moduleId, mutation.get("value").getAsBoolean());
                case "setting_value" -> ModuleRegistry.setValue(
                        moduleId, string(mutation, "settingId"), required(mutation, "value"));
                default -> throw new IllegalArgumentException("Unsupported mutation kind: " + kind);
            };
        } catch (RuntimeException exception) {
            result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty("error", exception.getMessage() == null ? "Invalid command" : exception.getMessage());
        }

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ack");
        ack.addProperty("protocol", PROTOCOL_VERSION);
        ack.addProperty("commandId", commandId);
        ack.addProperty("ok", result.has("ok") && result.get("ok").getAsBoolean());
        if (result.has("error")) ack.add("error", result.get("error"));
        ack.add("config", ModuleRegistry.snapshot());
        String serialized = GSON.toJson(ack);
        synchronized (completedCommands) {
            completedCommands.put(commandId, serialized);
        }
        send(serialized);
    }

    private void send(String serialized) {
        WebSocket current = socket;
        if (current != null && connected) current.sendText(serialized, true);
    }

    private void sendPairRequest() {
        JsonObject request = new JsonObject();
        request.addProperty("type", "pair_request");
        request.addProperty("protocol", PROTOCOL_VERSION);
        send(GSON.toJson(request));
    }

    private void notifyChat(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) client.execute(() -> ClientChat.send(client, message));
    }

    private static JsonElement required(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return object.get(name);
    }

    private static String string(JsonObject object, String name) {
        try {
            return object.has(name) ? object.get(name).getAsString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String name) {
        try {
            return object.has(name) ? object.get(name).getAsInt() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private record Binding(String socketUrl, String uiUrl) {
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (RemoteConfigClient.this) {
                connecting = false;
                if (!desired) {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client disabled");
                    return;
                }
                socket = webSocket;
                connected = false;
                reconnectAttempt = 0;
            }
            webSocket.request(1);
            sendHello(webSocket);
            notifyChat("Web remote connection started.");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (text.length() + data.length() > MAX_MESSAGE_CHARS) {
                text.setLength(0);
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "message too large");
                return null;
            }
            text.append(data);
            if (last) {
                String complete = text.toString();
                text.setLength(0);
                handleMessage(complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed(webSocket, statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed(webSocket, -1, "");
        }

        private void closed(WebSocket webSocket, int statusCode, String reason) {
            synchronized (RemoteConfigClient.this) {
                if (socket == webSocket) socket = null;
                connected = false;
                if (statusCode == 1008 || statusCode == 4003) desired = false;
            }
            if (statusCode == 1008 || statusCode == 4003) {
                notifyChat("Web authentication failed" + (reason.isBlank() ? "." : ": " + reason));
            } else {
                scheduleReconnect();
            }
        }
    }
}
