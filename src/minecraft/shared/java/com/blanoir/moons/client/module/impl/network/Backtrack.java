package com.blanoir.moons.client.module.impl.network;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.module.impl.network.backtrack.BacktrackConfig;
import com.blanoir.moons.client.module.impl.network.backtrack.BacktrackRuntime;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** Module entry points; tracking, replay and rendering belong to the Backtrack runtime. */
public final class Backtrack {
    private static final BacktrackConfig CONFIG = new BacktrackConfig();
    private static final BacktrackRuntime RUNTIME = new BacktrackRuntime(CONFIG);

    private Backtrack() {}

    public static void initPacketListeners() {
        EventBus.PACKET_RECEIVE_BUNDLE.register(
                "Backtrack.bundleExpansion",
                event -> {
                    if (isEnabled()) event.requestExpansion();
                });
        EventBus.PACKET_RECEIVE_PRE.register(
                "Backtrack.packetReceive",
                event -> {
                    if (!event.bundleExpansionRequested()
                            && handleIncomingPacket(event.packet(), event.listener())) {
                        event.cancel();
                    }
                });
    }

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("Backtrack.context", event -> RUNTIME.discard());
        EventBus.PACKET_SEND_POST.register(
                "Backtrack.attackSent",
                event -> {
                    if (isEnabled() && event.packet() instanceof ServerboundAttackPacket attack) {
                        Minecraft client = Minecraft.getInstance();
                        var connection = client.getConnection();
                        var level = client.level;
                        client.execute(
                                () -> {
                                    if (connection != null
                                            && connection == client.getConnection()
                                            && level != null
                                            && level == client.level) {
                                        onAttack(level.getEntity(attack.entityId()));
                                    }
                                });
                    }
                });
        EventBus.TICK.register("Backtrack.tick", event -> RUNTIME.tick(event.client()));
        EventBus.FRAME.register("Backtrack.frame", RUNTIME::frame);
        EventBus.WORLD_RENDER.register("Backtrack.worldRender", RUNTIME::renderEsp);
    }

    public static boolean isEnabled() {
        return CONFIG.enabled();
    }

    public static boolean handleIncomingPacket(Packet<?> packet, PacketListener listener) {
        return RUNTIME.handleIncomingPacket(packet, listener);
    }

    /** Called after vanilla dispatches either a manual or an automatic attack. */
    public static void onAttack(Entity entity) {
        RUNTIME.attack(Minecraft.getInstance(), entity);
    }

    public static boolean isLagging() {
        return isEnabled() && RUNTIME.isLagging();
    }

    public static String hudStats() {
        return RUNTIME.hudStats();
    }

    public static void renderModel(
            PoseStack poses, LevelRenderState state, SubmitNodeCollector collector) {
        RUNTIME.renderModel(poses, state, collector);
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        RUNTIME.release();
        CONFIG.setEnabled(enabled);
        ClientChat.send(client, "Backtrack " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setDelay(Minecraft client, String value) {
        int result = CONFIG.setDelay(client, value);
        if (result != 0) RUNTIME.release();
        return result;
    }

    public static int setRange(Minecraft client, String value) {
        int result = CONFIG.setRange(client, value);
        if (result != 0) RUNTIME.release();
        return result;
    }

    public static int delayMillis() {
        return CONFIG.delayMillis();
    }

    public static int minDelayMillis() {
        return CONFIG.minDelayMillis();
    }

    public static String targetModeName() {
        return CONFIG.targetModeName();
    }

    public static List<String> targetModeOptions() {
        return CONFIG.targetModeOptions();
    }

    public static int setTargetMode(Minecraft client, String value) {
        if (!CONFIG.targetModeOptions().contains(value)) return 0;
        RUNTIME.release();
        return CONFIG.setTargetMode(client, value);
    }

    public static double maxRange() {
        return CONFIG.maxRange();
    }

    public static int setEsp(Minecraft client, String value) {
        return CONFIG.setEsp(client, value);
    }

    public static List<String> espModeOptions() {
        return CONFIG.espModeOptions();
    }
}
