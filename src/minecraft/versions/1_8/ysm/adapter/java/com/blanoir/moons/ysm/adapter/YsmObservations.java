package com.blanoir.moons.ysm.adapter;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.MovingObjectPosition;

import java.util.*;

/** Player observations backed by real 1.8.9 state; portable query names remain stable. */
final class YsmObservations {
    private final java.util.function.Consumer<String> diagnostic;
    private EntityPlayerSP player;
    private double lastAge = Double.NaN;
    private float lastPitch, lastHeadYaw, lastBodyYaw;
    private final com.blanoir.moons.ysm.YsmMotionTracker motion =
            new com.blanoir.moons.ysm.YsmMotionTracker();
    private Map<String, Object> snapshot = Map.of();

    YsmObservations(java.util.function.Consumer<String> diagnostic) {
        this.diagnostic = diagnostic;
    }

    Map<String, Object> sample(EntityPlayerSP p, YsmRenderState s) {
        if (player == p
                && lastAge == s.ageInTicks
                && lastPitch == s.xRot
                && lastHeadYaw == s.yRot
                && lastBodyYaw == s.bodyRot) return snapshot;
        float partial = s.ageInTicks - (float) Math.floor(s.ageInTicks);
        Map<String, Object> q = YsmEntityObservations.sample(p, s, partial, motion);
        var mc = Minecraft.getMinecraft();
        q.put("entity_type", "player");
        q.put("is_local_player", true);
        q.put(
                "is_jumping",
                !p.capabilities.isFlying
                        && p.ridingEntity == null
                        && !p.onGround
                        && !p.isInWater());
        q.put("is_flying", p.capabilities.isFlying);
        q.put("player_level", p.experienceLevel);
        q.put("nametag_distance", 64);
        q.put("swim_speed", 1);
        q.put("step_height_addition", 0);
        q.put("first_person_mod_hide", false);
        q.put(
                "has_cape",
                p.getLocationCape() != null
                        && !p.isInvisible()
                        && p.isWearing(EnumPlayerModelParts.CAPE));
        q.put("cape_flap_amount", capeFlap(p, partial));
        q.put("has_left_shoulder_parrot", false);
        q.put("has_right_shoulder_parrot", false);
        q.put("left_shoulder_parrot_variant", "empty");
        q.put("right_shoulder_parrot_variant", "empty");
        q.put("vehicle_is_boat", p.ridingEntity instanceof EntityBoat);
        q.put("vehicle_is_minecart", p.ridingEntity instanceof EntityMinecart);
        q.put("vehicle_is_living", p.ridingEntity instanceof EntityLivingBase);
        q.put("sleep_rotation", p.isPlayerSleeping() ? p.getBedOrientationInDegrees() : 0);
        q.put(
                "attack_damage",
                p.getEntityAttribute(SharedMonsterAttributes.attackDamage).getAttributeValue());
        q.put("attack_speed", 0); // Attack cooldown attributes do not exist in this version.
        q.put("attack_knockback", EnchantmentHelper.getKnockbackModifier(p));
        q.put(
                "movement_speed",
                p.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue());
        q.put(
                "knockback_resistance",
                p.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                        .getAttributeValue());
        q.put("luck", 0);
        q.put("entity_gravity", 0.08);
        q.put(
                "block_reach",
                mc.playerController == null ? 4.5 : mc.playerController.getBlockReachDistance());
        q.put(
                "entity_reach",
                mc.playerController != null && mc.playerController.extendedReach() ? 6 : 3);
        q.put("in_shield_block_cooldown", false);
        MovingObjectPosition hit = mc.objectMouseOver;
        boolean entity =
                hit != null
                        && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                        && hit.entityHit != null;
        boolean block =
                hit != null
                        && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                        && p.worldObj != null;
        q.put("hit_target_type", entity ? "entity" : block ? "block" : "");
        q.put(
                "hit_target_id",
                entity
                        ? YsmEntityQueries.entityId(hit.entityHit).toString()
                        : block
                                ? Block.blockRegistry
                                        .getNameForObject(
                                                p.worldObj
                                                        .getBlockState(hit.getBlockPos())
                                                        .getBlock())
                                        .toString()
                                : "");
        player = p;
        lastAge = s.ageInTicks;
        lastPitch = s.xRot;
        lastHeadYaw = s.yRot;
        lastBodyYaw = s.bodyRot;
        snapshot = Collections.unmodifiableMap(q);
        return snapshot;
    }

    // Vanilla LayerCape geometry: interpolate the chasing position and movement-driven pitch.
    private static double capeFlap(EntityPlayerSP p, float t) {
        double dx =
                p.prevChasingPosX
                        + (p.chasingPosX - p.prevChasingPosX) * t
                        - (p.prevPosX + (p.posX - p.prevPosX) * t);
        double dy =
                p.prevChasingPosY
                        + (p.chasingPosY - p.prevChasingPosY) * t
                        - (p.prevPosY + (p.posY - p.prevPosY) * t);
        double dz =
                p.prevChasingPosZ
                        + (p.chasingPosZ - p.prevChasingPosZ) * t
                        - (p.prevPosZ + (p.posZ - p.prevPosZ) * t);
        double yaw =
                Math.toRadians(
                        p.prevRenderYawOffset + (p.renderYawOffset - p.prevRenderYawOffset) * t);
        double flap = Math.clamp(dy * 10, -6, 32),
                lean = Math.max(0, (dx * Math.sin(yaw) - dz * Math.cos(yaw)) * 100);
        double walk =
                p.prevDistanceWalkedModified
                        + (p.distanceWalkedModified - p.prevDistanceWalkedModified) * t;
        flap += Math.sin(walk * 6) * 32 * (p.prevCameraYaw + (p.cameraYaw - p.prevCameraYaw) * t);
        if (p.isSneaking()) flap += 25;
        return Math.clamp((6 + flap + lean / 2) / 108, 0, 1);
    }

    com.blanoir.moons.ysm.internal.runtime.LocalRuntime.ObservationView view(float partial) {
        var p = Minecraft.getMinecraft().thePlayer;
        if (p == null) return null;
        return new com.blanoir.moons.ysm.internal.runtime.LocalRuntime.ObservationView(
                sample(p, new YsmRenderState(p, partial)), this::query);
    }

    static String category(net.minecraft.item.ItemStack stack) {
        return YsmEquipmentObservations.category(stack);
    }

    Object query(String namespace, String name, List<Object> args) {
        return YsmEntityQueries.query(player, snapshot, diagnostic, namespace, name, args);
    }
}
