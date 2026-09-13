package com.blanoir.moons.client.management.rotation;

import net.minecraft.util.Mth;

/** Nearest keyboard direction, with two degrees of hysteresis for unchanged physical input. */
public final class MovementInputCorrection {
    public record Direction(int forward, int sideways) {}

    private static final int[] FORWARD = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] SIDEWAYS = {0, -1, -1, -1, 0, 1, 1, 1};
    private int previousSector = -1, previousForward, previousSideways, previousTick;
    private String previousOwner = "";

    public Direction correct(
            float cameraYaw, float movementYaw, int forward, int sideways, String owner, int tick) {
        if (forward == 0 && sideways == 0
                || !Float.isFinite(cameraYaw)
                || !Float.isFinite(movementYaw)) {
            reset();
            return new Direction(forward, sideways);
        }
        double relative =
                Mth.wrapDegrees(
                        cameraYaw - movementYaw - Math.toDegrees(Math.atan2(sideways, forward)));
        int sector = Math.floorMod((int) Math.round(relative / 45.0), 8);
        if (previousSector >= 0
                && owner.equals(previousOwner)
                && forward == previousForward
                && sideways == previousSideways
                && tick - previousTick >= 0
                && tick - previousTick <= 1
                && Math.abs(Mth.wrapDegrees(relative - previousSector * 45.0)) <= 24.5)
            sector = previousSector;
        previousSector = sector;
        previousForward = forward;
        previousSideways = sideways;
        previousOwner = owner;
        previousTick = tick;
        return new Direction(FORWARD[sector], SIDEWAYS[sector]);
    }

    public void reset() {
        previousSector = -1;
        previousOwner = "";
    }
}
