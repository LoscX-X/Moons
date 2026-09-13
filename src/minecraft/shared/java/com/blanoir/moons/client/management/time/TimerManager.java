package com.blanoir.moons.client.management.time;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.Minecraft;

/** Shared game-speed controller. Callers renew their own request and release it when finished. */
public final class TimerManager {
    private static final TimerRequests REQUESTS = new TimerRequests();
    private static Object world, connection;
    private static boolean initialized;

    private TimerManager() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.CLIENT_CONTEXT_CHANGED.register("TimerManager.context", event -> reset());
    }

    /** Default renewal window is real time, so slowed game ticks cannot hold a stale request. */
    public static boolean request(Object owner, float multiplier, int priority) {
        return request(owner, multiplier, priority, 250);
    }

    public static boolean request(Object owner, float multiplier, int priority, long holdMillis) {
        init();
        Minecraft client = Minecraft.getInstance();
        observe(client);
        if (!ClientReady.gameplay(client) || client.isPaused()) return false;
        REQUESTS.request(owner, multiplier, priority, holdMillis);
        return true;
    }

    public static void release(Object owner) {
        REQUESTS.release(owner);
    }

    public static void reset() {
        REQUESTS.reset();
        world = connection = null;
    }

    public static boolean active(Minecraft client) {
        observe(client);
        return ClientReady.gameplay(client) && !client.isPaused() && REQUESTS.active();
    }

    public static float multiplier(Minecraft client) {
        return active(client) ? REQUESTS.multiplier() : 1F;
    }

    public static float adjustTickMillis(Minecraft client, float vanillaMillis) {
        return active(client) ? REQUESTS.adjustTickMillis(vanillaMillis) : vanillaMillis;
    }

    private static void observe(Minecraft client) {
        Object nextWorld = client == null ? null : client.level;
        Object nextConnection = client == null ? null : client.getConnection();
        if (world != nextWorld || connection != nextConnection) {
            REQUESTS.reset();
            world = nextWorld;
            connection = nextConnection;
        }
    }
}
