package com.blanoir.moons.client.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives actual socket callbacks and owner dispatch without a server or game instance. */
public final class RemoteConfigVerification {
    public static void main(String[] args) throws Exception {
        var queue = new ArrayDeque<Runnable>();
        var applied = new AtomicInteger();
        var client =
                new RemoteConfigClient(
                        queue::add,
                        () -> {
                            applied.incrementAndGet();
                            return new JsonObject();
                        });
        var first = new Socket();
        var second = new Socket();
        Method handle = method("handleMessage", WebSocket.class, long.class, String.class);
        Method stop = method("stop", boolean.class);
        try {
            activate(client, first, 1);
            String command = command("same");
            handle.invoke(client, first, 1L, command);
            handle.invoke(client, first, 1L, command);
            require(queue.size() == 1, "Duplicate pending command was admitted twice");
            queue.remove().run();
            require(
                    applied.get() == 1 && first.messages.size() == 1,
                    "Command did not produce one ack");
            handle.invoke(client, first, 1L, command);
            require(
                    queue.isEmpty() && first.messages.size() == 2,
                    "Completed command was applied again");
            require(first.messages.get(0).equals(first.messages.get(1)), "Retry ack changed");

            handle.invoke(client, first, 1L, command("stale"));
            stop.invoke(client, false);
            activate(client, second, 3);
            handle.invoke(client, second, 3L, command("stale"));
            require(queue.size() == 2, "New connection could not reuse a cancelled command id");
            queue.remove().run();
            require(
                    applied.get() == 1 && second.messages.isEmpty(),
                    "Old queued command affected new connection");
            queue.remove().run();
            require(
                    applied.get() == 2 && second.messages.size() == 1,
                    "New command was removed by old callback");

            Class<?> listenerType = Class.forName(RemoteConfigClient.class.getName() + "$Listener");
            var constructor =
                    listenerType.getDeclaredConstructor(RemoteConfigClient.class, long.class);
            constructor.setAccessible(true);
            var obsolete = (WebSocket.Listener) constructor.newInstance(client, 1L);
            obsolete.onClose(first, 1008, "obsolete auth failure");
            obsolete.onText(first, "{\"type\":\"welcome\",\"protocol\":2}", true);
            obsolete.onOpen(first);
            require(
                    (boolean) field("connected").get(client)
                            && field("socket").get(client) == second,
                    "Old socket callback changed current connection");
            require(
                    (boolean) field("desired").get(client),
                    "Old authentication callback disconnected new socket");

            for (int i = 0; i < 256; i++) handle.invoke(client, second, 3L, command("queued-" + i));
            handle.invoke(client, second, 3L, command("overflow"));
            require(queue.size() == 256, "Remote command admission was not bounded");
            JsonObject busy = JsonParser.parseString(second.messages.getLast()).getAsJsonObject();
            require(
                    !busy.get("ok").getAsBoolean()
                            && busy.get("commandId").getAsString().equals("overflow"),
                    "Full queue did not return a retryable error");
            stop.invoke(client, false);
            while (!queue.isEmpty()) queue.remove().run();
            require(applied.get() == 2, "Disconnect applied pending mutations");
            require(
                    ((java.util.Map<?, ?>) field("pendingCommands").get(client)).isEmpty(),
                    "Pending commands leaked");
            System.out.println(
                    "MOONS_REMOTE_CONFIG_VERIFIED pending-dedup completed-ack stale-dispatch stale-socket bounded-admission disconnect");
        } finally {
            stop.invoke(client, false);
            ((ScheduledExecutorService) field("scheduler").get(client)).shutdownNow();
            ((java.net.http.HttpClient) field("httpClient").get(client)).shutdownNow();
        }
    }

    private static String command(String id) {
        // Missing mutation deliberately follows the real error/ack path without changing settings.
        return "{\"type\":\"command\",\"protocol\":2,\"commandId\":\"" + id + "\"}";
    }

    private static void activate(RemoteConfigClient client, Socket socket, long generation)
            throws Exception {
        field("generation").setLong(client, generation);
        field("desired").setBoolean(client, true);
        field("connected").setBoolean(client, true);
        field("socket").set(client, socket);
    }

    private static Field field(String name) throws Exception {
        Field result = RemoteConfigClient.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        Method result = RemoteConfigClient.class.getDeclaredMethod(name, types);
        result.setAccessible(true);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Socket implements WebSocket {
        final List<String> messages = new ArrayList<>();

        public CompletableFuture<WebSocket> sendText(CharSequence text, boolean last) {
            messages.add(text.toString());
            return CompletableFuture.completedFuture(this);
        }

        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        public CompletableFuture<WebSocket> sendClose(int code, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        public void request(long n) {}

        public String getSubprotocol() {
            return "";
        }

        public boolean isOutputClosed() {
            return false;
        }

        public boolean isInputClosed() {
            return false;
        }

        public void abort() {}
    }
}
