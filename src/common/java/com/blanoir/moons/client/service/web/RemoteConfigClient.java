package com.blanoir.moons.client.service.web;

import com.blanoir.moons.api.ScopedResources;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.threads.ThreadFactories;
import com.blanoir.moons.client.utils.json.JsonFields;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** WebSocket bridge between the module registry and the web UI. */
public final class RemoteConfigClient {
    public static final int PROTOCOL_VERSION = 2;
    private static final Gson GSON = new Gson();
    private static final int MAX_MESSAGE_CHARS = 1_000_000;
    private static final int MAX_REMEMBERED_COMMANDS = 256;
    private static final String SOCKET_URL_KEY = "web.websocketUrl";
    private static final String UI_URL_KEY = "web.uiUrl";
    private static final RemoteConfigClient INSTANCE = new RemoteConfigClient();

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    ThreadFactories.daemon("moons-web-reconnect"));
    private final Map<String, String> completedCommands =
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_REMEMBERED_COMMANDS;
                }
            };
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final Executor clientExecutor;
    private final Supplier<JsonObject> configuration;
    private final Map<String, Long> pendingCommands = new HashMap<>();
    private ScheduledFuture<?> reconnect;
    private volatile long generation;
    private boolean resumeDesired;
    private boolean closed;

    private volatile WebSocket socket;
    private volatile boolean desired;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean requestPairing;
    private volatile int reconnectAttempt;

    private RemoteConfigClient() {
        this(task -> Minecraft.getInstance().execute(task), ModuleRegistry::snapshot);
    }

    RemoteConfigClient(Executor clientExecutor, Supplier<JsonObject> configuration) {
        this.clientExecutor = java.util.Objects.requireNonNull(clientExecutor);
        this.configuration = java.util.Objects.requireNonNull(configuration);
    }

    public static void init() {
        if (INSTANCE.initialized.compareAndSet(false, true))
            ScopedResources.own((AutoCloseable) RemoteConfigClient::shutdown);
    }

    public static void shutdown() {
        synchronized (INSTANCE) {
            INSTANCE.closed = true;
            INSTANCE.stop(false);
        }
        INSTANCE.scheduler.shutdownNow();
        INSTANCE.httpClient.shutdownNow();
    }

    public static void suspend() {
        synchronized (INSTANCE) {
            INSTANCE.resumeDesired = INSTANCE.desired;
            INSTANCE.stop(false);
        }
    }

    public static void resume() {
        synchronized (INSTANCE) {
            if (!INSTANCE.resumeDesired) return;
            INSTANCE.resumeDesired = false;
            INSTANCE.desired = true;
            INSTANCE.open();
        }
    }

    public static String hardwareId() {
        return HardwareId.get();
    }

    public static String socketUrl() {
        String override = System.getProperty("moons.web.websocketUrl", "").trim();
        if (override.isEmpty())
            override = System.getenv().getOrDefault("MOONS_WEB_SOCKET_URL", "").trim();
        return override.isEmpty() ? Settings.getString(SOCKET_URL_KEY, "").trim() : override;
    }

    public static String uiUrl() {
        String configured = Settings.getString(UI_URL_KEY, "").trim();
        if (!configured.isEmpty()) return configured;
        String socketUrl = socketUrl();
        if (socketUrl.isEmpty()) return "";
        return socketUrl
                .replaceFirst("^ws://", "http://")
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
        String scheme =
                input.getScheme() == null
                        ? ""
                        : input.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("http", "https", "ws", "wss").contains(scheme)
                || input.getHost() == null
                || input.getUserInfo() != null
                || input.getQuery() != null
                || input.getFragment() != null) {
            throw new IllegalArgumentException("仅支持不含账号、查询参数的 HTTP(S) 或 WS(S) 网址");
        }

        String path = input.getPath() == null ? "" : input.getPath().replaceAll("/+$", "");
        String basePath =
                path.endsWith("/ws/client")
                        ? path.substring(0, path.length() - "/ws/client".length())
                        : path;
        String authority = input.getRawAuthority();
        boolean secure = scheme.equals("https") || scheme.equals("wss");
        String ui =
                (secure ? "https://" : "http://")
                        + authority
                        + (basePath.isEmpty() ? "/" : basePath + "/");
        String socket = (secure ? "wss://" : "ws://") + authority + basePath + "/ws/client";
        return new Binding(socket, ui);
    }

    private synchronized void open() {
        if (closed || !desired || connected || connecting || socket != null) return;
        URI uri;
        try {
            uri = URI.create(socketUrl());
            if (!("ws".equalsIgnoreCase(uri.getScheme())
                    || "wss".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("URL must use ws:// or wss://");
            }
        } catch (RuntimeException exception) {
            notifyChat("Web connection URL is invalid: " + exception.getMessage());
            desired = false;
            return;
        }

        connecting = true;
        long attempt = ++generation;
        httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .header("X-Moons-HWID", hardwareId())
                .buildAsync(uri, new Listener(attempt))
                .whenComplete(
                        (webSocket, error) -> {
                            synchronized (this) {
                                if (attempt != generation || closed || !desired) {
                                    if (webSocket != null) webSocket.abort();
                                    return;
                                }
                                connecting = false;
                                if (error != null) {
                                    socket = null;
                                    connected = false;
                                    scheduleReconnect();
                                }
                            }
                        });
    }

    private synchronized void stop(boolean notify) {
        generation++;
        pendingCommands.clear();
        if (reconnect != null) reconnect.cancel(false);
        reconnect = null;
        desired = false;
        connected = false;
        connecting = false;
        reconnectAttempt = 0;
        WebSocket current = socket;
        socket = null;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
        if (notify) notifyChat("Web remote control disconnected.");
    }

    private synchronized void scheduleReconnect() {
        if (closed || !desired || reconnect != null && !reconnect.isDone()) return;
        long attempt = generation;
        int delay = Math.min(30, 1 << Math.min(reconnectAttempt++, 5));
        reconnect =
                scheduler.schedule(
                        () -> {
                            synchronized (this) {
                                if (attempt != generation || closed || !desired) return;
                                reconnect = null;
                                open();
                            }
                        },
                        delay,
                        TimeUnit.SECONDS);
    }

    private void sendHello(WebSocket target, long attempt) {
        clientExecutor.execute(
                () -> {
                    if (!current(target, attempt)) return;
                    JsonObject hello = new JsonObject();
                    hello.addProperty("type", "hello");
                    hello.addProperty("protocol", PROTOCOL_VERSION);
                    hello.addProperty("hwid", hardwareId());
                    hello.addProperty("deviceKey", DeviceKey.get(socketUrl()));
                    hello.add("config", configuration.get());
                    target.sendText(GSON.toJson(hello), true);
                });
    }

    private void handleMessage(WebSocket source, long attempt, String raw) {
        if (!current(source, attempt)) return;
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
            boolean pairing;
            synchronized (this) {
                if (!current(source, attempt)) return;
                connected = true;
                pairing = requestPairing;
            }
            if (pairing) {
                JsonObject request = new JsonObject();
                request.addProperty("type", "pair_request");
                request.addProperty("protocol", PROTOCOL_VERSION);
                send(source, attempt, GSON.toJson(request));
            }
            return;
        }
        if ("pairing".equals(type)) {
            synchronized (this) {
                if (!current(source, attempt)) return;
                requestPairing = false;
            }
            String code = string(message, "code");
            if (!code.isBlank())
                notifyChat("Web pairing code: " + code + " (valid for 5 minutes)", attempt);
            return;
        }
        if (!"command".equals(type)) return;
        String commandId = string(message, "commandId");
        if (commandId.isBlank()) return;

        String previous;
        synchronized (completedCommands) {
            previous = completedCommands.get(commandId);
        }
        if (previous != null) {
            send(source, attempt, previous);
            return;
        }
        synchronized (this) {
            if (!current(source, attempt) || pendingCommands.containsKey(commandId)) return;
            if (pendingCommands.size() >= MAX_REMEMBERED_COMMANDS) {
                JsonObject busy = new JsonObject();
                busy.addProperty("type", "ack");
                busy.addProperty("protocol", PROTOCOL_VERSION);
                busy.addProperty("commandId", commandId);
                busy.addProperty("ok", false);
                busy.addProperty("error", "Command queue is full; retry later");
                send(source, attempt, GSON.toJson(busy));
                return;
            }
            pendingCommands.put(commandId, attempt);
        }

        JsonObject mutation =
                message.has("mutation") && message.get("mutation").isJsonObject()
                        ? message.getAsJsonObject("mutation")
                        : null;
        clientExecutor.execute(
                () -> {
                    try {
                        if (current(source, attempt)) apply(source, attempt, commandId, mutation);
                    } finally {
                        synchronized (this) {
                            pendingCommands.remove(commandId, attempt);
                        }
                    }
                });
    }

    private void apply(WebSocket source, long attempt, String commandId, JsonObject mutation) {
        JsonObject result;
        try {
            if (mutation == null) throw new IllegalArgumentException("Missing mutation");
            String kind = string(mutation, "kind");
            String moduleId = string(mutation, "moduleId");
            result =
                    switch (kind) {
                        case "module_enabled" ->
                                ModuleRegistry.setEnabled(
                                        moduleId, mutation.get("value").getAsBoolean());
                        case "setting_value" ->
                                ModuleRegistry.setValue(
                                        moduleId,
                                        string(mutation, "settingId"),
                                        required(mutation, "value"));
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported mutation kind: " + kind);
                    };
        } catch (RuntimeException exception) {
            result = new JsonObject();
            result.addProperty("ok", false);
            result.addProperty(
                    "error",
                    exception.getMessage() == null ? "Invalid command" : exception.getMessage());
        }

        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ack");
        ack.addProperty("protocol", PROTOCOL_VERSION);
        ack.addProperty("commandId", commandId);
        ack.addProperty("ok", result.has("ok") && result.get("ok").getAsBoolean());
        if (result.has("error")) ack.add("error", result.get("error"));
        for (String field :
                java.util.List.of(
                        "persisted", "appliedRevision", "persistedRevision", "persistenceError"))
            if (result.has(field)) ack.add(field, result.get(field));
        ack.add("config", configuration.get());
        String serialized = GSON.toJson(ack);
        synchronized (completedCommands) {
            completedCommands.put(commandId, serialized);
        }
        send(source, attempt, serialized);
    }

    private synchronized boolean current(WebSocket source, long attempt) {
        return !closed && desired && generation == attempt && socket == source;
    }

    private void send(WebSocket source, long attempt, String serialized) {
        if (current(source, attempt) && connected) source.sendText(serialized, true);
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
        notifyChat(message, generation);
    }

    private void notifyChat(String message, long attempt) {
        Minecraft client = Minecraft.getInstance();
        if (client != null)
            clientExecutor.execute(
                    () -> {
                        if (!closed && generation == attempt) ClientChat.send(client, message);
                    });
    }

    private static JsonElement required(JsonObject object, String name) {
        return JsonFields.required(object, name);
    }

    private static String string(JsonObject object, String name) {
        return JsonFields.stringOr(object, name, "");
    }

    private static int integer(JsonObject object, String name) {
        return JsonFields.integerOr(object, name, -1);
    }

    private record Binding(String socketUrl, String uiUrl) {}

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private final long attempt;

        private Listener(long attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (RemoteConfigClient.this) {
                if (closed || !desired || attempt != generation) {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client disabled");
                    return;
                }
                connecting = false;
                socket = webSocket;
                connected = false;
                reconnectAttempt = 0;
            }
            webSocket.request(1);
            sendHello(webSocket, attempt);
            notifyChat("Web remote connection started.", attempt);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!current(webSocket, attempt)) return null;
            if (text.length() + data.length() > MAX_MESSAGE_CHARS) {
                text.setLength(0);
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "message too large");
                return null;
            }
            text.append(data);
            if (last) {
                String complete = text.toString();
                text.setLength(0);
                handleMessage(webSocket, attempt, complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            if (!current(webSocket, attempt)) return null;
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
            long notification;
            synchronized (RemoteConfigClient.this) {
                if (!current(webSocket, attempt)) return;
                generation++;
                pendingCommands.clear();
                socket = null;
                connected = false;
                if (statusCode == 1008 || statusCode == 4003) desired = false;
                notification = generation;
            }
            if (statusCode == 1008 || statusCode == 4003) {
                notifyChat(
                        "Web authentication failed" + (reason.isBlank() ? "." : ": " + reason),
                        notification);
            } else {
                scheduleReconnect();
            }
        }
    }
}
