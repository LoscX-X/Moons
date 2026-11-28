/*
 * Ordinary movement equations adapted from GrimAC's PredictionEngine/PredictionEngineNormal,
 * BlockProperties, JumpPower and ModernInputTransformer. GPL-3.0-or-later.
 * See META-INF/tempos/third-party/GrimAC.txt and GPL-3.0.txt in the client resources.
 */
package com.blanoir.tempos.client.module.impl.movement.strafe;

import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.module.impl.movement.Sprint;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Body;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Collision;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Direction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Ground/air input physics against native collision shapes, without changing the live entity. */
public final class MovementSimulation {
    public record Parameters(
            double groundAcceleration, double airAcceleration, double groundDrag,
            double airDrag, double verticalDrag, double gravity, double jumpPower,
            double speedFactor, int levitation, boolean sprinting, boolean sprintAvailable) {}

    private MovementSimulation() {}

    public static Parameters sample(Minecraft client, LivingEntity entity) {
        var block = client.level.getBlockState(entity.getOnPos()).getBlock();
        double friction = block.getFriction();
        boolean modernFriction = BuiltInRegistries.ATTRIBUTE
                .get(Identifier.withDefaultNamespace("friction_modifier")).isPresent();
        if (modernFriction) friction = modifiedDrag(friction, attribute(entity, "friction_modifier", 1));
        double airModifier = attribute(entity, "air_drag_modifier", 1);
        double acceleration = modernFriction && friction <= 0.6 ? entity.getSpeed()
                : entity.getSpeed() * (0.21600002F / (friction * friction * friction));
        double gravity = entity.isNoGravity() ? 0 : entity.getAttributeValue(Attributes.GRAVITY);
        if (entity.getDeltaMovement().y <= 0 && entity.hasEffect(MobEffects.SLOW_FALLING))
            gravity = Math.min(gravity, 0.01);
        var jumpBoost = entity.getEffect(MobEffects.JUMP_BOOST);
        double jump = entity.getAttributeValue(Attributes.JUMP_STRENGTH) * block.getJumpFactor()
                + (jumpBoost == null ? 0 : 0.1F * (jumpBoost.getAmplifier() + 1));
        var levitation = entity.getEffect(MobEffects.LEVITATION);
        double speedFactor = block.getSpeedFactor();
        double efficiency = entity.getAttributeValue(Attributes.MOVEMENT_EFFICIENCY);
        speedFactor += efficiency * (1 - speedFactor);
        double airDrag = modifiedDrag(0.91F, airModifier);
        return new Parameters(acceleration, entity.isSprinting() ? 0.025999999F : 0.02F,
                friction * airDrag, airDrag, modifiedDrag(0.98F, airModifier), gravity,
                jump, speedFactor, levitation == null ? -1 : levitation.getAmplifier(), entity.isSprinting(),
                !CombatInputController.isSprintSuppressed()
                        && (entity.isSprinting() || entity instanceof LocalPlayer player && Sprint.INSTANCE.canSprint(player)));
    }

    private static double attribute(LivingEntity entity, String name, double fallback) {
        var attribute = BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(name));
        return attribute.isPresent() && entity.getAttributes().hasAttribute(attribute.get())
                ? entity.getAttributeValue(attribute.get()) : fallback;
    }

    public static double modifiedDrag(double base, double modifier) {
        return Math.clamp(1 - (1 - base) * modifier, 0, 1);
    }

    public static Body step(Body body, Direction input, float yaw, boolean jump,
            Parameters physics, Collision collision) {
        // Since 1.21.5 the horizontal threshold applies to X/Z together, not per axis.
        boolean stopped = body.velocity().horizontalDistanceSqr() < 9.0E-6;
        Vec3 velocity = new Vec3(stopped ? 0 : body.velocity().x, small(body.velocity().y),
                stopped ? 0 : body.velocity().z);
        boolean sprinting = physics.sprintAvailable() && input.forward() > 0;
        double acceleration = body.grounded() ? physics.groundAcceleration() : physics.airAcceleration();
        if (physics.sprinting() != sprinting) acceleration *= sprinting ? 1.3 : 1 / 1.3;
        if (jump && body.grounded() && physics.jumpPower() > 1.0E-5) {
            velocity = new Vec3(velocity.x, Math.max(velocity.y, physics.jumpPower()), velocity.z);
            if (sprinting) velocity = velocity.add(new Direction(1, 0).world(yaw).scale(0.2));
        }
        // Modern vanilla expands normalized diagonal input to the unit square, capped at length 1.
        double inputScale = input.forward() != 0 && input.sideways() != 0 ? 1 : 0.98F;
        velocity = velocity.add(input.world(yaw).scale(acceleration * inputScale));
        Vec3 requested = body.grounded() && velocity.y <= 0
                ? new Vec3(velocity.x, -Math.max(physics.gravity(), 1.0E-5), velocity.z) : velocity;
        Vec3 travel = collision.resolve(true, body.box(), requested);
        boolean blockedX = Math.abs(travel.x - requested.x) > 1.0E-7;
        boolean blockedY = Math.abs(travel.y - requested.y) > 1.0E-7;
        boolean blockedZ = Math.abs(travel.z - requested.z) > 1.0E-7;
        double vertical = blockedY ? 0 : velocity.y;
        vertical = physics.levitation() >= 0
                ? vertical + (0.05 * (physics.levitation() + 1) - vertical) * 0.2
                : vertical - physics.gravity();
        // Travel uses the previous on-ground state when choosing this tick's friction.
        double drag = body.grounded() ? physics.groundDrag() : physics.airDrag();
        Vec3 nextVelocity = new Vec3(
                blockedX ? 0 : velocity.x * physics.speedFactor() * drag,
                vertical * physics.verticalDrag(),
                blockedZ ? 0 : velocity.z * physics.speedFactor() * drag);
        return new Body(body.box().move(travel), body.eye().add(travel), nextVelocity,
                blockedY && requested.y < 0);
    }

    private static double small(double velocity) {
        return Math.abs(velocity) < 0.003 ? 0 : velocity;
    }
}
