package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumSkyBlock;

import java.util.*;

/** Each entity exposes its own 1.8 world, equipment and movement, including remote players. */
final class YsmEntityObservations {
    static Map<String, Object> sample(
            Entity e,
            YsmRenderState s,
            float partial,
            com.blanoir.moons.ysm.YsmMotionTracker motion) {
        Map<String, Object> q = new HashMap<>();
        var level = e.worldObj;
        var mc = Minecraft.getMinecraft();
        var camera = mc.getRenderViewEntity();
        var frame = motion.sample(e, s.ageInTicks, s.x, s.y, s.z, s.bodyRot);
        q.put("is_local_player", e == mc.thePlayer);
        q.put("is_player", e instanceof EntityPlayer);
        q.put("is_alive", e.isEntityAlive());
        q.put("is_dead", !e.isEntityAlive());
        q.put("entity_type", YsmEntityQueries.entityId(e).toString());
        q.put("life_time", s.ageInTicks / 20d);
        q.put("delta_time", frame.seconds());
        q.put("time_delta", frame.seconds());
        double ground = Math.hypot(e.motionX, e.motionZ) * 20;
        double vertical = e.motionY * 20;
        if (e == mc.thePlayer) {
            // Preserve frame-time movement sampling and the original quiet-frame fallbacks.
            ground = frame.groundSpeed();
            if (ground < 1e-4) {
                ground =
                        Math.max(
                                Math.abs(s.walkAnimationSpeed),
                                Math.hypot(e.motionX, e.motionZ) * 20);
            }
            vertical =
                    frame.seconds() > 0 && Math.abs(frame.y()) > 1e-4
                            ? frame.y() / frame.seconds()
                            : Math.abs(e.motionY) > .1
                                    ? e.motionY * 20
                                    : (e.posY - e.prevPosY) * 20;
        }
        q.put("ground_speed", ground);
        q.put("ground_speed2", ground);
        q.put("vertical_speed", vertical);
        q.put(
                "delta_movement_length",
                Math.sqrt(e.motionX * e.motionX + e.motionY * e.motionY + e.motionZ * e.motionZ));
        q.put("delta_x", e.motionX);
        q.put("delta_y", e.motionY);
        q.put("delta_z", e.motionZ);
        q.put("position_x", e.posX);
        q.put("position_y", e.posY);
        q.put("position_z", e.posZ);
        q.put("query.head_x_rotation", s.yRot);
        q.put("query.head_y_rotation", s.xRot);
        q.put("head_x_rotation", s.xRot);
        q.put("head_y_rotation", s.yRot);
        q.put("body_x_rotation", e.rotationPitch);
        q.put("body_y_rotation", s.bodyRot);
        q.put("yaw_speed", frame.yawSpeed());
        q.put("cardinal_facing_2d", e.getHorizontalFacing().getIndex());
        q.put("distance_from_camera", camera == null ? 0 : e.getDistanceToEntity(camera));
        q.put("eye_target_x_rotation", s.xRot);
        q.put(
                "eye_target_y_rotation",
                YsmRenderState.angle(partial, e.prevRotationYaw, e.rotationYaw));
        q.put("walk_distance", e.distanceWalkedModified);
        q.put("modified_move_speed", s.walkAnimationSpeed);
        q.put("is_in_water", e.isInWater());
        q.put("is_in_water_or_rain", e.isWet());
        q.put("is_in_lava", e.isInLava());
        q.put("is_sneaking", e.onGround && e.isSneaking());
        q.put("is_sprinting", e.isSprinting());
        q.put("is_on_ground", e.onGround);
        q.put("is_spectator", e instanceof EntityPlayer p && p.isSpectator());
        q.put("is_invisible", e.isInvisible());
        q.put("is_burning", e.isBurning());
        q.put("is_on_fire", e.isBurning());
        q.put("is_first_person", mc.gameSettings.thirdPersonView == 0);
        q.put("first_person", q.get("is_first_person"));
        q.put("person_view", mc.gameSettings.thirdPersonView);
        q.put("has_rider", e.riddenByEntity != null);
        q.put("is_riding", e.ridingEntity != null && e.ridingEntity.isEntityAlive());
        long clock = level.getWorldTime();
        q.put("time_stamp", clock);
        q.put("day", clock / 24000d);
        q.put("time_of_day", Math.floorMod(clock + 6000, 24000) / 24000d);
        q.put("moon_phase", Math.floorMod(clock / 24000, 8));
        q.put("actor_count", level.loadedEntityList.size());
        q.put("is_raining", level.isRaining());
        q.put("is_thundering", level.isThundering());
        BlockPos pos = new BlockPos(e);
        q.put("sky_light", level.getLightFor(EnumSkyBlock.SKY, pos));
        q.put("block_light", level.getLightFor(EnumSkyBlock.BLOCK, pos));
        YsmAdditionalObservations.entity(q, e, partial, frame);
        if (e.ridingEntity != null) related(q, "vehicle", e.ridingEntity);
        if (e.riddenByEntity != null) related(q, "passenger", e.riddenByEntity);
        if (e instanceof EntityLivingBase l) {
            EntityPlayer p = l instanceof EntityPlayer player ? player : null;
            var using = p == null ? null : p.getItemInUse();
            q.put("health", l.getHealth());
            q.put("max_health", l.getMaxHealth());
            q.put("hurt_time", l.hurtTime);
            q.put("death_time", l.deathTime);
            q.put("is_playing_dead", l.getHealth() <= 0);
            q.put("is_sleeping", p != null && p.isPlayerSleeping());
            q.put("is_using_item", p != null && p.isUsingItem());
            q.put("is_swinging", l.isSwingInProgress);
            q.put("query.swing_time", l.swingProgressInt / 20d);
            q.put("swing_time", l.swingProgressInt);
            q.put("attack_time", s.attackTime);
            q.put("using_hand", "mainhand");
            q.put("swinging_hand", "mainhand");
            q.put("is_eating", using != null && using.getItemUseAction() == EnumAction.EAT);
            q.put("item_in_use_duration", p == null ? 0 : p.getItemInUseDuration() / 20d);
            q.put("item_remaining_use_duration", p == null ? 0 : p.getItemInUseCount() / 20d);
            q.put("item_max_use_duration", using == null ? 0 : using.getMaxItemUseDuration() / 20d);
            q.put("equipment_count", YsmEquipmentObservations.sample(q, l));
            YsmAdditionalObservations.living(q, l, s.ageInTicks, partial);
        }
        return q;
    }

    private static void related(Map<String, Object> q, String role, Entity e) {
        if (!e.isEntityAlive()) return;
        q.put(role + "_id", YsmEntityQueries.entityId(e).toString());
        q.put(role + "_type", YsmEntityQueries.entityId(e).getResourcePath());
        q.put(role + "_tags", List.of());
    }
}
