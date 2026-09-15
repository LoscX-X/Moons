package com.blanoir.moons.client.module.impl.misc.antibot;

import com.blanoir.moons.features.FeatureHooks;
import com.blanoir.moons.runtime.RuntimeEvents;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.TransientEntitySectionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/** Runs the render policy against real entity/state types with an isolated client fixture. */
final class HideBotRenderVerification {
    static void verify() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var client = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        var local = (LocalPlayer) unsafe.allocateInstance(LocalPlayer.class);
        var bot = (LocalPlayer) unsafe.allocateInstance(LocalPlayer.class);
        var replacement = (LocalPlayer) unsafe.allocateInstance(LocalPlayer.class);
        field(Entity.class, "id").setInt(bot, 7);
        field(Entity.class, "id").setInt(replacement, 7);
        field(LocalPlayer.class, "connection")
                .set(local, unsafe.allocateInstance(ClientPacketListener.class));
        client.player = local;
        client.level = (ClientLevel) unsafe.allocateInstance(ClientLevel.class);
        Entity[] visibleEntity = {bot};
        var storage = unsafe.allocateInstance(TransientEntitySectionManager.class);
        Object getter =
                Proxy.newProxyInstance(
                        LevelEntityGetter.class.getClassLoader(),
                        new Class<?>[] {LevelEntityGetter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("get") && args[0].equals(7))
                                return visibleEntity[0];
                            throw new AssertionError("Unexpected world query: " + method);
                        });
        field(TransientEntitySectionManager.class, "entityGetter").set(storage, getter);
        field(ClientLevel.class, "entityStorage").set(client.level, storage);
        Field singleton = field(Minecraft.class, "instance");
        Object previousClient = singleton.get(null);
        var state = (AntiBotState) field(AntiBot.class, "STATE").get(null);
        singleton.set(null, client);
        try {
            AntiBot.setEnabled(null, true);
            AntiBot.setHideBot(null, false);
            state.isBot(client, bot); // Synchronize the fixture world before seeding observations.
            var track = AntiBotState.class.getDeclaredMethod("track", Player.class);
            track.setAccessible(true);
            Object tracked = track.invoke(state, bot);
            var detector = (AdvancedBotDetector) field(tracked.getClass(), "detector").get(tracked);
            for (int tick = 0; tick < 3; tick++)
                detector.observe(new AdvancedBotDetector.Facts(1, 0, 0, true, true));
            require(AntiBot.isBot(bot), "Fixture is a confirmed bot");
            var avatar = new AvatarRenderState();
            avatar.id = 7;
            require(!FeatureHooks.isActive("render.antibot-hide"), "HideBot is opt-in");
            require(renderAllowed(avatar), "Filtering attacks alone keeps the model visible");
            AntiBot.setHideBot(null, true);
            require(FeatureHooks.isActive("render.antibot-hide"), "Render gate activates");
            require(!renderAllowed(avatar), "Confirmed bot cancels the whole entity submission");
            require(
                    renderAllowed(new EntityRenderState()),
                    "Non-player render states remain visible");
            require(!AntiBot.shouldHide(local), "Local player is never hidden");
            visibleEntity[0] = replacement;
            require(renderAllowed(avatar), "Entity ID reuse cannot hide a new player");
            visibleEntity[0] = bot;
            detector.observe(new AdvancedBotDetector.Facts(100, 0, 0, false, true));
            require(renderAllowed(avatar), "Recovered player is visible immediately");
            for (int tick = 0; tick < 3; tick++)
                detector.observe(new AdvancedBotDetector.Facts(1, 0, 0, true, true));
            AntiBot.setHideBot(null, false);
            require(
                    renderAllowed(avatar) && AntiBot.isBot(bot),
                    "HideBot does not clear attack filtering");
            AntiBot.setHideBot(null, true);
            state.handlePacket(client, new ClientboundRemoveEntitiesPacket(7));
            require(
                    !AntiBot.isBot(bot) && renderAllowed(avatar),
                    "Removed short-lived bots leave no verdict");
            AntiBot.setEnabled(null, false);
            require(renderAllowed(avatar), "Disabling AntiBot restores rendering");
        } finally {
            AntiBot.setEnabled(null, false);
            AntiBot.setHideBot(null, false);
            state.reset();
            singleton.set(null, previousClient);
        }
    }

    private static boolean renderAllowed(Object state) {
        var hook =
                new RuntimeEvents.MethodHook(
                        "render.antibot-hide", null, new Object[] {state}, true);
        FeatureHooks.apply(hook);
        return Boolean.TRUE.equals(hook.value());
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
