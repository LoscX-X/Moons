package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Extends manual entity picking with a configurable per-tick chance. */
public final class Reach {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("reach.enabled").defaultValue(false).build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("reach.range")
                    .defaultValue(3.1D)
                    .range(3.0D, 6.0D)
                    .build();

    private static final DoubleSetting CHANCE =
            new DoubleSetting.Builder()
                    .name("reach.normal.chance")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private static boolean initialized;
    private static boolean expanding = true;

    private Reach() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        EventBus.TICK.register(
                "Reach.sampleExpansionChance",
                event -> {
                    expanding = RandomMath.chancePercent(CHANCE.get());
                });
    }

    /** Uses Range for entity picking and Range + 0.5 for the bounding ray/block query. */
    public static void applyNormalPick(Minecraft client) {
        if (!ClientReady.gameplay(client) || !ENABLED.get() || !expanding) {
            return;
        }
        double entityRange = RANGE.get();
        Vec3 start = client.thePlayer.getPositionEyes(1.0F);
        Vec3 look = client.thePlayer.getLook(1.0F);
        MovingObjectPosition extendedHit =
                RaytraceUtils.findEntity(
                        client,
                        start,
                        start.add(VecMath.scale(look, entityRange + 0.5D)),
                        entityRange,
                        false,
                        entity ->
                                entity.canBeCollidedWith()
                                        && !(entity
                                                        instanceof
                                                        net.minecraft.entity.player.EntityPlayer
                                                                spectator
                                                && spectator.isSpectator()));
        if (extendedHit != null) {
            client.objectMouseOver = extendedHit;
            client.pointedEntity = extendedHit.entityHit;
        }
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String statusTag() {
        return format(RANGE.get());
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        return showStatus(client);
    }

    public static int setRange(Minecraft client, double value) {
        RANGE.set(value);
        return showStatus(client);
    }

    public static int setChance(Minecraft client, double value) {
        CHANCE.set(value);
        return showStatus(client);
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Reach: "
                        + (ENABLED.get() ? "enabled" : "disabled")
                        + ", range: "
                        + format(RANGE.get())
                        + ", chance: "
                        + format(CHANCE.get())
                        + "%. Usage: .moons reach <enable|disable|range 3-6|chance 0-100>.");
        return 1;
    }

    private static String format(double value) {
        return Math.rint(value) == value
                ? Long.toString(Math.round(value))
                : Double.toString(value);
    }
}
