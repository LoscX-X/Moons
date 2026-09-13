package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** D: Point-to-angle, angular-distance and continuous-yaw geometry only.
 * No tracking history. Convenience overloads read the camera; numeric overloads
 * take explicit inputs. Existing callers retain their output and scheduling responsibilities. */
public final class AimSolverD {
    private AimSolverD() {}

    public static Rotation rotationTo(Vec3 from, Vec3 to) {
        return MathUtils.rotationTo(from, to);
    }

    public static double angleFromView(Minecraft client, Vec3 point) {
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) return Double.POSITIVE_INFINITY;
        Rotation rotation = rotationTo(player.getEyePosition(), point);
        return angleBetween(player.getYRot(), player.getXRot(), rotation);
    }

    public static double angleBetween(float yaw, float pitch, Rotation target) {
        return MathUtils.angularDistance(yaw, pitch, target);
    }

    public static double viewAngle(Vec3 eye, Vec3 look, Vec3 point) {
        return MathUtils.viewAngle(eye, look, point);
    }

    /** Changes only whole turns, preserving the nearest continuous numeric yaw domain. */
    public static float continuousYaw(float reference, float cameraYaw) {
        if (!Float.isFinite(reference) || !Float.isFinite(cameraYaw)) return cameraYaw;
        double turns = Math.floor(((double) cameraYaw - reference + 180.0D) / 360.0D);
        return (float) (cameraYaw - turns * 360.0D);
    }
}
