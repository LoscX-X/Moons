package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import java.util.Map;

/** One game observation per animation time, shared by the world, inventory and hands. */
final class YsmPlayerFrame {
    record Frame(double seconds, Map<String, Object> queries) {}

    private final YsmObservations observations;
    private LocalPlayer player;
    private float age = Float.NaN;
    private Frame frame;

    YsmPlayerFrame(YsmObservations observations) {
        this.observations = observations;
    }

    Frame sample(LocalPlayer current) {
        Minecraft minecraft = Minecraft.getInstance();
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float currentAge = current.tickCount + partial;
        if (player != current || age != currentAge || frame == null) {
            // InventoryScreen extracts at partial=1 and replaces the render state's rotations
            // to follow the cursor. Neither that future time nor those preview rotations may
            // drive the live animation/physics session shared with the world renderer.
            AvatarRenderState state =
                    (AvatarRenderState)
                            minecraft.getEntityRenderDispatcher()
                                    .getRenderer(current)
                                    .createRenderState(current, partial);
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
}
