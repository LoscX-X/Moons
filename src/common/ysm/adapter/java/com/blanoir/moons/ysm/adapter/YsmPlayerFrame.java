package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.Timer;

import java.lang.reflect.Field;
import java.util.Map;

/** One game observation per animation time, shared by world, inventory and hands. */
final class YsmPlayerFrame {
    private static final Field TIMER = findTimer();

    record Frame(double seconds, Map<String, Object> queries) {}

    private final YsmObservations observations;
    private EntityPlayerSP player;
    private float age = Float.NaN;
    private Frame frame;

    YsmPlayerFrame(YsmObservations observations) {
        this.observations = observations;
    }

    Frame sample(EntityPlayerSP current) {
        float partial = partialTick(Minecraft.getMinecraft());
        float currentAge = current.ticksExisted + partial;
        if (player != current || age != currentAge || frame == null) {
            // Never use inventory preview rotations or its artificial partial tick for live
            // physics.
            var state = new YsmRenderState(current, partial);
            frame = new Frame(state.ageInTicks / 20d, observations.sample(current, state));
            player = current;
            age = currentAge;
        }
        return frame;
    }

    void reset() {
        player = null;
        age = Float.NaN;
        frame = null;
    }

    private static Field findTimer() {
        for (Field field : Minecraft.class.getDeclaredFields()) {
            if (field.getType() == Timer.class) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new IllegalStateException("Minecraft 1.8.9 timer field was not found");
    }

    private static float partialTick(Minecraft minecraft) {
        try {
            return ((Timer) TIMER.get(minecraft)).renderPartialTicks;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read Minecraft render time", e);
        }
    }
}
