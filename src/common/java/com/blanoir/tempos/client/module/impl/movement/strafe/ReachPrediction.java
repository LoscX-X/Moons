/*
 * Interpolation bounds adapted from GrimAC ReachInterpolationData.
 * Copyright (C) 2021 DefineOutside. GPL-3.0-or-later.
 */
package com.blanoir.tempos.client.module.impl.movement.strafe;

import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Body;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Keeps the visible and last received positions as bounds when judging a movement candidate. */
public final class ReachPrediction {
    public record Distances(double outgoing, double incoming) {}

    private ReachPrediction() {}

    public static Distances distances(Body player, Body target, Vec3 interpolationDelta) {
        // Distance to a translated box is convex, so the worst outgoing distance is at an endpoint.
        double outgoing = Math.sqrt(Math.max(target.box().distanceToSqr(player.eye()),
                target.box().move(interpolationDelta).distanceToSqr(player.eye())));
        // Like Grim's endpoint union, this contains every intermediate eye position. For incoming
        // risk its lower distance bound is conservative, including diagonal interpolation.
        AABB eyes = new AABB(target.eye(), target.eye().add(interpolationDelta));
        AABB body = player.box();
        double x = gap(eyes.minX, eyes.maxX, body.minX, body.maxX);
        double y = gap(eyes.minY, eyes.maxY, body.minY, body.maxY);
        double z = gap(eyes.minZ, eyes.maxZ, body.minZ, body.maxZ);
        return new Distances(outgoing, Math.sqrt(x * x + y * y + z * z));
    }

    private static double gap(double minA, double maxA, double minB, double maxB) {
        return Math.max(0, Math.max(minA - maxB, minB - maxA));
    }
}
