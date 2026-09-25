package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.LightLayer;

import java.util.*;

/** Non-player entities expose their own world and living state, never the owner's player state. */
final class YsmEntityObservations {
    static Map<String, Object> sample(
            Entity entity,
            EntityRenderState state,
            float partial,
            com.blanoir.moons.ysm.YsmMotionTracker motion) {
        Map<String, Object> q = new HashMap<>();
        var level = entity.level();
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.mainCamera();
        var movement = entity.getDeltaMovement();
        var frame =
                motion.sample(
                        entity,
                        state.ageInTicks,
                        state.x,
                        state.y,
                        state.z,
                        entity.getYRot(partial));
        double dt = frame.seconds();
        q.put("is_local_player", false);
        q.put("is_player", false);
        q.put("is_alive", entity.isAlive());
        q.put("entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        q.put("life_time", state.ageInTicks / 20d);
        q.put("delta_time", dt);
        q.put("time_delta", dt);
        q.put("ground_speed", movement.horizontalDistance() * 20);
        q.put("ground_speed2", movement.horizontalDistance() * 20);
        q.put("vertical_speed", movement.y * 20);
        q.put("delta_movement_length", movement.length());
        q.put("delta_x", movement.x);
        q.put("delta_y", movement.y);
        q.put("delta_z", movement.z);
        q.put("query.head_x_rotation", 0);
        q.put("query.head_y_rotation", entity.getXRot(partial));
        q.put(
                "yaw_speed",
                20 * net.minecraft.util.Mth.wrapDegrees(entity.getYRot() - entity.yRotO));
        q.put("cardinal_facing_2d", entity.getDirection().get3DDataValue());
        q.put("distance_from_camera", Math.sqrt(entity.distanceToSqr(camera.position())));
        q.put("eye_target_x_rotation", entity.getViewXRot(partial));
        q.put("eye_target_y_rotation", entity.getViewYRot(partial));
        q.put("walk_distance", entity.moveDist);
        q.put("is_in_water_or_rain", entity.isInWaterOrRain());
        q.put("is_in_lava", entity.isInLava());
        q.put("is_sneaking", entity.onGround() && entity.getPose() == Pose.CROUCHING);
        q.put("is_sprinting", entity.isSprinting());
        q.put("is_spectator", entity.isSpectator());
        q.put("is_swimming", entity.isSwimming());
        q.put("is_invisible", entity.isInvisible());
        q.put("is_first_person", mc.options.getCameraType().isFirstPerson());
        q.put("first_person", q.get("is_first_person"));
        q.put("person_view", mc.options.getCameraType().ordinal());
        long clock = level.getDefaultClockTime();
        q.put("time_stamp", clock);
        q.put("time_of_day", ((clock + 6000d) / 24000) % 1);
        q.put("moon_phase", Math.floorMod(clock / 24000, 8));
        q.put("actor_count", mc.level == null ? 0 : mc.level.getEntityCount());
        q.put("is_raining", level.isRaining());
        q.put("is_thundering", level.isThundering());
        q.put("sky_light", level.getBrightness(LightLayer.SKY, entity.blockPosition()));
        q.put("block_light", level.getBrightness(LightLayer.BLOCK, entity.blockPosition()));
        YsmAdditionalObservations.entity(q, entity, partial, frame);
        if (entity.getVehicle() != null) related(q, "vehicle", entity.getVehicle());
        if (entity.getFirstPassenger() != null) related(q, "passenger", entity.getFirstPassenger());
        if (entity instanceof LivingEntity living) {
            q.put("health", living.getHealth());
            q.put("max_health", living.getMaxHealth());
            q.put("hurt_time", living.hurtTime);
            q.put("death_time", living.deathTime);
            q.put("is_playing_dead", living.isDeadOrDying());
            q.put("is_sleeping", living.isSleeping());
            q.put("is_using_item", living.isUsingItem());
            q.put("is_swinging", living.isSwinging());
            q.put("query.swing_time", YsmWeaponState.swingTicks(living, 1) / 20d);
            q.put("swing_time", YsmWeaponState.swingTicks(living, 1));
            q.put("attack_time", living.getSwingAnimation(partial));
            q.put(
                    "using_hand",
                    living.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND
                            ? "offhand"
                            : "mainhand");
            q.put(
                    "swinging_hand",
                    YsmWeaponState.swingHand(living) == net.minecraft.world.InteractionHand.OFF_HAND
                            ? "offhand"
                            : "mainhand");
            q.put("is_eating", living.getUseItem().getUseAnimation() == ItemUseAnimation.EAT);
            q.put("item_in_use_duration", living.getTicksUsingItem() / 20d);
            q.put("item_remaining_use_duration", living.getUseItemRemainingTicks() / 20d);
            q.put(
                    "item_max_use_duration",
                    living.getUseItem().isEmpty()
                            ? 0
                            : living.getUseItem().getUseDuration(living) / 20d);
            q.put(
                    "query.head_x_rotation",
                    net.minecraft.util.Mth.wrapDegrees(living.yHeadRot - living.yBodyRot));
            q.put("equipment_count", YsmEquipmentObservations.sample(q, living));
            YsmAdditionalObservations.living(q, living, state.ageInTicks, partial);
        }
        return q;
    }

    private static void related(Map<String, Object> q, String role, Entity entity) {
        if (!entity.isAlive()) return;
        q.put(role + "_id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        q.put(
                role + "_tags",
                entity.getType()
                        .builtInRegistryHolder()
                        .tags()
                        .map(t -> t.location().toString())
                        .toList());
    }
}
