package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.YsmWeaponQueries;

import net.minecraft.block.BlockLadder;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;

import java.util.Map;

/** 1.8 observations; unsupported poses and item mechanics have explicit neutral values. */
final class YsmAdditionalObservations {
    static boolean openAir(Entity e) {
        BlockPos p = new BlockPos(e);
        return e.worldObj != null
                && e.worldObj.canSeeSky(p)
                && e.worldObj.getPrecipitationHeight(p).getY() <= p.getY();
    }

    static String dimension(Entity e) {
        return switch (e.dimension) {
            case -1 -> "minecraft:the_nether";
            case 1 -> "minecraft:the_end";
            case 0 -> "minecraft:overworld";
            default -> "dimension:" + e.dimension;
        };
    }

    static int ladderFacing(EntityLivingBase e) {
        var state = e.worldObj.getBlockState(new BlockPos(e));
        return state.getBlock() instanceof BlockLadder
                ? state.getValue(BlockLadder.FACING).getHorizontalIndex()
                : 0;
    }

    static void entity(
            Map<String, Object> q,
            Entity e,
            float partial,
            com.blanoir.moons.ysm.YsmMotionTracker.Frame frame) {
        var level = e.worldObj;
        q.put("weather", level.isThundering() ? 2 : level.isRaining() ? 1 : 0);
        q.put("dimension_name", dimension(e));
        q.put("fps", Minecraft.getDebugFPS());
        q.put("is_passenger", e.ridingEntity != null);
        q.put("is_sleep", e instanceof EntityPlayer p && p.isPlayerSleeping());
        q.put("is_sneak", e.onGround && e.isSneaking());
        q.put("is_open_air", openAir(e));
        q.put("eye_in_water", e.isInsideOfMaterial(Material.water));
        q.put("frozen_ticks", 0);
        q.put("air_supply", e.getAir());
        q.put("rendering_in_inventory", false);
        q.put("rendering_in_paperdoll", false);
        q.put("ysm.modified_move_speed", e.distanceWalkedModified * 20);
        q.put("modified_distance_moved", e.distanceWalkedModified);
        double yaw = e.prevRotationYaw + (e.rotationYaw - e.prevRotationYaw) * partial;
        double angle = Math.atan2(frame.z(), frame.x()) - Math.toRadians(90 + yaw);
        boolean moving = Math.hypot(frame.x(), frame.z()) >= 1e-4;
        q.put("input_vertical", moving ? Math.cos(angle) : 0);
        q.put("input_horizontal", moving ? Math.sin(angle) : 0);
        q.put("swim_amount", 0);
        q.put("swimming_pose", false);
        q.put("is_swimming", false);
        q.put("item_use_normalized", e instanceof EntityPlayer p && p.isUsingItem() ? 1 : 0);
        q.put("is_holding_right", e instanceof EntityLivingBase l && l.getHeldItem() != null);
        q.put("is_holding_left", false);
        boat(q, e, partial);
    }

    static void living(Map<String, Object> q, EntityLivingBase e, float age, float partial) {
        q.put("has_helmet", q.get("has_head"));
        q.put("has_chest_plate", q.get("has_chest"));
        q.put("has_leggings", q.get("has_legs"));
        q.put("has_boots", q.get("has_feet"));
        q.put("has_elytra", false);
        q.put("is_riptide", false);
        q.put("armor_value", e.getTotalArmorValue());
        q.put("is_baby", e.isChild());
        q.put("is_fall_flying", false);
        q.put("left_handed", false);
        float blink = (age + Math.abs(e.getUniqueID().getLeastSignificantBits() % 10)) % 90;
        q.put(
                "is_close_eyes",
                e instanceof EntityPlayer p && p.isPlayerSleeping() || blink > 85 && blink < 90);
        q.put("on_ladder", e.isOnLadder());
        q.put("is_climbing", e.isOnLadder());
        q.put("ladder_facing", ladderFacing(e));
        q.put("arrow_count", e.getArrowCountInEntity());
        q.put("stinger_count", 0);
        q.put("is_maid", false);
        q.put("food_level", e instanceof EntityPlayer p ? p.getFoodStats().getFoodLevel() : 20);
        q.put("is_fishing", e instanceof EntityPlayer p && p.fishEntity != null);
        q.put("xxa", e.moveStrafing);
        q.put("yya", 0);
        q.put("zza", e.moveForward);
        q.put("mainhand_charged_crossbow", false);
        q.put("offhand_charged_crossbow", false);
        q.put("item_is_charged", false);
        q.put("swinging", e.isSwingInProgress);
        q.put("swinging_arm", 0);
        q.put("elytra_rot_x", 0);
        q.put("elytra_rot_y", 0);
        q.put("elytra_rot_z", 0);
        YsmWeaponQueries.write(q, YsmWeaponState.get(e, partial));
    }

    static void boat(Map<String, Object> q, Entity entity, float partial) {
        // The 1.8 boat has no paddles, rowing animation, chest, or raft variants.
        q.put("boat_left_paddle", false);
        q.put("boat_right_paddle", false);
        q.put("boat_left_rowing_time", 0);
        q.put("boat_right_rowing_time", 0);
        q.put("boat_is_raft", false);
        q.put("boat_is_chest", false);
        q.put("boat_body_offset_y", 0);
        q.put("boat_body_offset_z", 0);
        q.put("boat_chest_passenger_offset", 0);
        q.put("boat_paddle_scale", 1);
    }
}
