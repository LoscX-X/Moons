package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.YsmWeaponQueries;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;

/** Common YSM entity queries used by projectile and mount runtimes across game versions. */
final class YsmAdditionalObservations {
    static boolean openAir(Entity entity) {
        var level = entity.level();
        var pos = entity.blockPosition();
        return level.canSeeSky(pos)
                && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY();
    }

    static void entity(
            Map<String, Object> q,
            Entity entity,
            float partial,
            com.blanoir.moons.ysm.YsmMotionTracker.Frame frame) {
        var level = entity.level();
        q.put("weather", level.isThundering() ? 2 : level.isRaining() ? 1 : 0);
        q.put("dimension_name", level.dimension().identifier().toString());
        q.put("fps", Minecraft.getInstance().getFps());
        q.put("is_passenger", entity.isPassenger());
        q.put("is_sleep", entity.getPose() == Pose.SLEEPING);
        q.put("is_sneak", entity.onGround() && entity.getPose() == Pose.CROUCHING);
        q.put("is_open_air", openAir(entity));
        q.put("eye_in_water", entity.isUnderWater());
        q.put("frozen_ticks", entity.getTicksFrozen());
        q.put("air_supply", entity.getAirSupply());
        q.put("rendering_in_inventory", false);
        q.put("rendering_in_paperdoll", false);
        q.put("ysm.modified_move_speed", entity.moveDist * 20);
        q.put("modified_distance_moved", entity.moveDist);
        double angle =
                Math.atan2(frame.z(), frame.x()) - Math.toRadians(90 + entity.getViewYRot(partial));
        boolean moving = Math.hypot(frame.x(), frame.z()) >= 1e-4;
        q.put("input_vertical", moving ? Math.cos(angle) : 0);
        q.put("input_horizontal", moving ? Math.sin(angle) : 0);
        q.put("swim_amount", entity instanceof LivingEntity && entity.isSwimming() ? 1 : 0);
        q.put("item_use_normalized", entity instanceof LivingEntity le && le.isUsingItem() ? 1 : 0);
        q.put(
                "is_holding_right",
                entity instanceof LivingEntity le && !le.getMainHandItem().isEmpty());
        q.put(
                "is_holding_left",
                entity instanceof LivingEntity le && !le.getOffhandItem().isEmpty());
        boat(q, entity, partial);
    }

    static void living(Map<String, Object> q, LivingEntity living, float age, float partial) {
        q.put("has_helmet", q.get("has_head"));
        q.put("has_chest_plate", q.get("has_chest"));
        q.put("has_leggings", q.get("has_legs"));
        q.put("has_boots", q.get("has_feet"));
        q.put("has_elytra", living.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
        q.put("is_riptide", living.isAutoSpinAttack());
        q.put("armor_value", living.getArmorValue());
        q.put("is_baby", living.isBaby());
        q.put("is_fall_flying", living.isFallFlying());
        q.put("left_handed", living.getMainArm() == HumanoidArm.LEFT);
        float blink = (age + Math.abs(living.getUUID().getLeastSignificantBits()) % 10) % 90;
        q.put("is_close_eyes", living.isSleeping() || blink > 85 && blink < 90);
        q.put("on_ladder", living.onClimbable());
        q.put("is_climbing", living.onClimbable());
        q.put(
                "ladder_facing",
                living.getLastClimbablePos()
                        .flatMap(
                                pos ->
                                        living.level()
                                                .getBlockState(pos)
                                                .getOptionalValue(
                                                        HorizontalDirectionalBlock.FACING))
                        .map(net.minecraft.core.Direction::get2DDataValue)
                        .orElse(0));
        q.put("arrow_count", living.getArrowCount());
        q.put("stinger_count", living.getStingerCount());
        q.put("is_maid", false);
        q.put(
                "food_level",
                living instanceof Player player ? player.getFoodData().getFoodLevel() : 20);
        q.put("is_fishing", living instanceof Player player && player.fishing != null);
        q.put("xxa", living.xxa);
        q.put("yya", living.yya);
        q.put("zza", living.zza);
        q.put("mainhand_charged_crossbow", CrossbowItem.isCharged(living.getMainHandItem()));
        q.put("offhand_charged_crossbow", CrossbowItem.isCharged(living.getOffhandItem()));
        q.put("swinging", q.get("is_swinging"));
        q.put("swinging_arm", "offhand".equals(q.get("swinging_hand")) ? 1 : 0);
        q.put("elytra_rot_x", Math.toDegrees(living.elytraAnimationState.getRotX(partial)));
        q.put("elytra_rot_y", Math.toDegrees(living.elytraAnimationState.getRotY(partial)));
        q.put("elytra_rot_z", Math.toDegrees(living.elytraAnimationState.getRotZ(partial)));
        YsmWeaponQueries.write(q, YsmWeaponState.get(living, partial));
    }

    static void boat(Map<String, Object> q, Entity entity, float partial) {
        var boat =
                entity instanceof AbstractBoat b
                        ? b
                        : entity.getVehicle() instanceof AbstractBoat b ? b : null;
        boolean raft = boat instanceof Raft || boat instanceof ChestRaft;
        boolean chest = boat instanceof AbstractChestBoat;
        q.put("boat_left_paddle", boat != null && boat.getPaddleState(0));
        q.put("boat_right_paddle", boat != null && boat.getPaddleState(1));
        q.put("boat_left_rowing_time", boat == null ? 0 : -boat.getRowingTime(0, partial));
        q.put("boat_right_rowing_time", boat == null ? 0 : -boat.getRowingTime(1, partial));
        q.put("boat_is_raft", raft);
        q.put("boat_is_chest", chest);
        q.put("boat_body_offset_y", raft ? -((.8888889f - 1f / 3) * .5625f * 16 + 1) : 0);
        q.put("boat_body_offset_z", chest ? -2.4f : 0);
        q.put("boat_chest_passenger_offset", chest ? 2.4f : 0);
        q.put("boat_paddle_scale", 1);
    }
}
