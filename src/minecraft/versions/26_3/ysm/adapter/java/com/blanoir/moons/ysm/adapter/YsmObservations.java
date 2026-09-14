package com.blanoir.moons.ysm.adapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.*;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.*;

import java.util.*;

/** Minecraft 26.3-rc-3 observations for the portable Molang bindings. */
final class YsmObservations {
    private final java.util.function.Consumer<String> diagnostic;

    YsmObservations(java.util.function.Consumer<String> diagnostic) {
        this.diagnostic = diagnostic;
    }

    private LocalPlayer player;
    private double lastAge = Double.NaN;
    private float lastPitch, lastHeadYaw, lastBodyYaw;
    private final com.blanoir.moons.ysm.YsmMotionTracker motion =
            new com.blanoir.moons.ysm.YsmMotionTracker();
    private Map<String, Object> snapshot = Map.of();

    Map<String, Object> sample(LocalPlayer p, AvatarRenderState s) {
        if (player == p
                && lastAge == s.ageInTicks
                && lastPitch == s.xRot
                && lastHeadYaw == s.yRot
                && lastBodyYaw == s.bodyRot) return snapshot;
        Map<String, Object> q = new HashMap<>();
        var frame = motion.sample(p, s.ageInTicks, s.x, s.y, s.z, s.bodyRot);
        double dt = frame.seconds();
        double dx = frame.x(), dy = frame.y(), dz = frame.z();
        double ground = frame.groundSpeed();
        if (ground < 1e-4)
            ground =
                    Math.max(
                            Math.abs(s.walkAnimationSpeed),
                            p.getDeltaMovement().horizontalDistance() * 20);
        double vertical =
                dt > 0 && Math.abs(dy) > 1e-4
                        ? dy / dt
                        : Math.abs(p.getDeltaMovement().y) > .1
                                ? p.getDeltaMovement().y * 20
                                : (p.getY() - p.yo) * 20;
        q.put("life_time", s.ageInTicks / 20d);
        q.put("time_delta", dt);
        q.put("delta_time", dt);
        q.put("ground_speed", ground);
        q.put("ground_speed2", ground);
        q.put("vertical_speed", vertical);
        q.put("yaw_speed", frame.yawSpeed());
        q.put("modified_move_speed", s.walkAnimationSpeed);
        q.put("modified_distance_moved", s.walkAnimationPos);
        q.put("head_x_rotation", s.xRot);
        q.put("head_y_rotation", s.yRot);
        q.put("body_x_rotation", p.getXRot());
        q.put("body_y_rotation", s.bodyRot);
        q.put("query.head_x_rotation", s.yRot);
        q.put("query.head_y_rotation", s.xRot);
        q.put("is_sneaking", p.onGround() && p.getPose() == Pose.CROUCHING);
        q.put("is_sneak", p.isCrouching() && p.onGround());
        q.put("is_sprinting", p.isSprinting());
        q.put("is_swimming", p.isSwimming());
        q.put("swimming_pose", p.getPose() == Pose.SWIMMING);
        q.put("swim_amount", p.isSwimming() ? 1 : 0);
        q.put("is_in_water", p.isInWater());
        q.put("is_in_water_or_rain", p.isInWaterOrRain());
        q.put("is_in_lava", p.isInLava());
        q.put("eye_in_water", p.isUnderWater());
        q.put("is_on_ground", p.onGround());
        q.put(
                "is_jumping",
                !p.getAbilities().flying && !p.isPassenger() && !p.onGround() && !p.isInWater());
        q.put("on_ladder", p.onClimbable());
        q.put(
                "ladder_facing",
                p.getLastClimbablePos()
                        .flatMap(
                                pos ->
                                        p.level()
                                                .getBlockState(pos)
                                                .getOptionalValue(
                                                        net.minecraft.world.level.block
                                                                .HorizontalDirectionalBlock.FACING))
                        .map(net.minecraft.core.Direction::get2DDataValue)
                        .orElse(0));
        q.put("nametag_distance", 64);
        q.put("swim_speed", 1);
        q.put("step_height_addition", 0);
        q.put("is_maid", false);
        q.put("first_person_mod_hide", false);
        q.put("is_climbing", p.onClimbable());
        q.put("is_riding", p.getVehicle() != null && p.getVehicle().isAlive());
        q.put("left_handed", p.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT);
        q.put("is_passenger", p.isPassenger());
        q.put("is_fall_flying", p.isFallFlying());
        q.put("is_flying", p.getAbilities().flying);
        q.put("is_sleeping", p.isSleeping());
        q.put("is_sleep", p.isSleeping());
        q.put("is_dead", !p.isAlive());
        q.put("is_alive", p.isAlive());
        q.put("is_riptide", p.isAutoSpinAttack());
        q.put("is_using_item", p.isUsingItem());
        q.put(
                "using_hand",
                p.getUsedItemHand() == InteractionHand.MAIN_HAND ? "mainhand" : "offhand");
        q.put("is_swinging", p.isSwinging());
        q.put("swinging", p.isSwinging());
        q.put("swing_time", YsmWeaponState.swingTicks(p, 1));
        q.put("attack_time", s.swingAnimation);
        q.put(
                "swinging_hand",
                YsmWeaponState.swingHand(p) == InteractionHand.OFF_HAND ? "offhand" : "mainhand");
        q.put("swinging_arm", YsmWeaponState.swingHand(p) == InteractionHand.OFF_HAND ? 1 : 0);
        q.put("health", p.getHealth());
        q.put("max_health", p.getMaxHealth());
        q.put("hurt_time", p.hurtTime);
        q.put("death_time", p.deathTime);
        q.put("food_level", p.getFoodData().getFoodLevel());
        q.put("armor_value", p.getArmorValue());
        q.put("air_supply", p.getAirSupply());
        q.put("frozen_ticks", p.getTicksFrozen());
        q.put("is_burning", p.isOnFire());
        q.put("is_invisible", p.isInvisible());
        q.put("is_baby", p.isBaby());
        q.put("is_player", true);
        q.put("entity_type", "player");
        q.put("is_on_fire", p.isOnFire());
        q.put("is_spectator", p.isSpectator());
        q.put("is_playing_dead", p.isDeadOrDying());
        q.put("is_eating", p.getUseItem().getUseAnimation() == ItemUseAnimation.EAT);
        q.put("item_in_use_duration", p.getTicksUsingItem() / 20d);
        q.put("item_remaining_use_duration", p.getUseItemRemainingTicks() / 20d);
        q.put(
                "item_max_use_duration",
                p.getUseItem().isEmpty() ? 0 : p.getUseItem().getUseDuration(p) / 20d);
        q.put("query.swing_time", YsmWeaponState.swingTicks(p, 1) / 20d);
        q.put("has_rider", p.isVehicle());
        q.put("player_level", p.experienceLevel);
        q.put("walk_distance", p.moveDist);
        q.put("modified_distance_moved", p.moveDist);
        q.put("ysm.modified_move_speed", p.moveDist * 20);
        q.put("cardinal_facing_2d", p.getDirection().get3DDataValue());
        q.put("eye_target_x_rotation", p.getXRot());
        q.put("eye_target_y_rotation", p.getYRot());
        q.put(
                "distance_from_camera",
                Math.sqrt(
                        p.distanceToSqr(
                                Minecraft.getInstance().gameRenderer.mainCamera().position())));
        q.put("arrow_count", p.getArrowCount());
        q.put("stinger_count", p.getStingerCount());
        q.put("is_fishing", p.fishing != null);
        q.put("xxa", p.xxa);
        q.put("yya", p.yya);
        q.put("zza", p.zza);
        q.put("delta_movement_length", p.getDeltaMovement().length());
        q.put("position_x", p.getX());
        q.put("position_y", p.getY());
        q.put("position_z", p.getZ());
        q.put("delta_x", p.getDeltaMovement().x);
        q.put("delta_y", p.getDeltaMovement().y);
        q.put("delta_z", p.getDeltaMovement().z);
        q.put("person_view", Minecraft.getInstance().options.getCameraType().ordinal());
        q.put("first_person", Minecraft.getInstance().options.getCameraType().isFirstPerson());
        q.put("is_first_person", q.get("first_person"));
        q.put(
                "has_cape",
                p.getSkin().cape() != null
                        && !p.isInvisible()
                        && p.isModelPartShown(
                                net.minecraft.world.entity.player.PlayerModelPart.CAPE));
        float blink = (s.ageInTicks + (Math.abs(p.getUUID().getLeastSignificantBits()) % 10)) % 90;
        q.put("is_close_eyes", p.isSleeping() || blink > 85 && blink < 90);
        q.put("rendering_in_inventory", false);
        q.put("rendering_in_paperdoll", false);
        q.put("is_local_player", true);
        q.put("fps", Minecraft.getInstance().getFps());
        q.put("is_holding_right", !p.getMainHandItem().isEmpty());
        q.put("is_holding_left", !p.getOffhandItem().isEmpty());
        q.put("item_use_normalized", p.isUsingItem() ? 1 : 0);
        double inputAngle = Math.atan2(dz, dx) - Math.toRadians(90 + p.getYRot());
        q.put("input_vertical", dt > 0 && Math.hypot(dx, dz) >= 1e-4 ? Math.cos(inputAngle) : 0);
        q.put("input_horizontal", dt > 0 && Math.hypot(dx, dz) >= 1e-4 ? Math.sin(inputAngle) : 0);
        q.put("cape_flap_amount", Math.clamp((6 + s.capeFlap + s.capeLean / 2) / 108d, 0, 1));
        q.put("has_left_shoulder_parrot", p.getShoulderParrotLeft().isPresent());
        q.put("has_right_shoulder_parrot", p.getShoulderParrotRight().isPresent());
        q.put(
                "left_shoulder_parrot_variant",
                p.getShoulderParrotLeft()
                        .map(v -> v.name().toLowerCase(Locale.ROOT))
                        .orElse("empty"));
        q.put(
                "right_shoulder_parrot_variant",
                p.getShoulderParrotRight()
                        .map(v -> v.name().toLowerCase(Locale.ROOT))
                        .orElse("empty"));
        com.blanoir.moons.ysm.YsmWeaponQueries.write(
                q, YsmWeaponState.get(p, s.ageInTicks - (float) Math.floor(s.ageInTicks)));
        var boat =
                p.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat b
                        ? b
                        : null;
        boolean raft =
                boat instanceof net.minecraft.world.entity.vehicle.boat.Raft
                        || boat instanceof net.minecraft.world.entity.vehicle.boat.ChestRaft;
        boolean chest = boat instanceof net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
        q.put("boat_left_paddle", boat != null && boat.getPaddleState(0));
        q.put("boat_right_paddle", boat != null && boat.getPaddleState(1));
        q.put(
                "boat_left_rowing_time",
                boat == null
                        ? 0
                        : -boat.getRowingTime(0, s.ageInTicks - (float) Math.floor(s.ageInTicks)));
        q.put(
                "boat_right_rowing_time",
                boat == null
                        ? 0
                        : -boat.getRowingTime(1, s.ageInTicks - (float) Math.floor(s.ageInTicks)));
        q.put("boat_is_raft", raft);
        q.put("boat_is_chest", chest);
        q.put("boat_body_offset_y", raft ? -((.8888889f - 1f / 3) * .5625f * 16 + 1) : 0);
        q.put("boat_body_offset_z", chest ? -2.4f : 0);
        q.put("boat_chest_passenger_offset", chest ? 2.4f : 0);
        q.put("boat_paddle_scale", 1);
        q.put("map_angle", Math.clamp(1 - s.xRot / 45.1, 0, 1));
        if (p.getVehicle() != null) {
            q.put(
                    "vehicle_is_boat",
                    p.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat);
            q.put(
                    "vehicle_is_minecart",
                    p.getVehicle()
                            instanceof
                            net.minecraft.world.entity.vehicle.minecart.AbstractMinecart);
            q.put(
                    "vehicle_is_living",
                    p.getVehicle() instanceof net.minecraft.world.entity.LivingEntity);
            q.put(
                    "vehicle_tags",
                    p.getVehicle()
                            .getType()
                            .builtInRegistryHolder()
                            .tags()
                            .map(tag -> tag.location().toString())
                            .toList());
            q.put(
                    "vehicle_id",
                    BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).toString());
            q.put(
                    "vehicle_type",
                    BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).getPath());
        }
        if (!p.getPassengers().isEmpty()) {
            q.put(
                    "passenger_tags",
                    p.getPassengers()
                            .getFirst()
                            .getType()
                            .builtInRegistryHolder()
                            .tags()
                            .map(t -> t.location().toString())
                            .toList());
            q.put(
                    "passenger_id",
                    BuiltInRegistries.ENTITY_TYPE
                            .getKey(p.getPassengers().getFirst().getType())
                            .toString());
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String name = slot.getName();
            ItemStack stack = p.getItemBySlot(slot);
            q.put("has_" + name, !stack.isEmpty());
            q.put(name + "_item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            q.put(name + "_category", category(stack));
            q.put(name + "_use", stack.getUseAnimation().name().toLowerCase(Locale.ROOT));
            q.put(
                    name + "_tags",
                    BuiltInRegistries.ITEM
                            .wrapAsHolder(stack.getItem())
                            .tags()
                            .map(tag -> tag.location().toString())
                            .toList());
        }
        q.put(
                "equipment_count",
                Arrays.stream(EquipmentSlot.values())
                        .filter(slot -> slot.isArmor() && !p.getItemBySlot(slot).isEmpty())
                        .count());
        q.put("has_helmet", q.get("has_head"));
        q.put("has_chest_plate", q.get("has_chest"));
        q.put("has_leggings", q.get("has_legs"));
        q.put("has_boots", q.get("has_feet"));
        q.put("has_elytra", p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA));
        q.put("mainhand_charged_crossbow", CrossbowItem.isCharged(p.getMainHandItem()));
        q.put("offhand_charged_crossbow", CrossbowItem.isCharged(p.getOffhandItem()));
        q.put("item_is_charged", q.get("offhand_charged_crossbow"));
        q.put("sleep_rotation", 0);
        q.put(
                "tcos0",
                Math.cos(s.ageInTicks / 20d * 103.2 * Math.PI / 180)
                        * Math.min(1, p.moveDist * 4)
                        * 20);
        q.put(
                "attack_damage",
                p.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
        q.put(
                "attack_speed",
                p.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED));
        q.put(
                "attack_knockback",
                p.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK));
        q.put(
                "movement_speed",
                p.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
        q.put(
                "knockback_resistance",
                p.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE));
        q.put(
                "luck",
                p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.LUCK));
        q.put(
                "entity_gravity",
                p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY));
        q.put("block_reach", p.blockInteractionRange());
        q.put("entity_reach", p.entityInteractionRange());
        q.put(
                "in_shield_block_cooldown",
                p.getCooldowns().isOnCooldown(Items.SHIELD.getDefaultInstance()));
        q.put(
                "elytra_rot_x",
                Math.toDegrees(
                        p.elytraAnimationState.getRotX(
                                s.ageInTicks - (float) Math.floor(s.ageInTicks))));
        q.put(
                "elytra_rot_y",
                Math.toDegrees(
                        p.elytraAnimationState.getRotY(
                                s.ageInTicks - (float) Math.floor(s.ageInTicks))));
        q.put(
                "elytra_rot_z",
                Math.toDegrees(
                        p.elytraAnimationState.getRotZ(
                                s.ageInTicks - (float) Math.floor(s.ageInTicks))));
        var level = Minecraft.getInstance().level;
        if (level != null) {
            q.put("time_of_day", Math.floorMod(level.getDefaultClockTime() + 6000, 24000) / 24000d);
            q.put("day", level.getDefaultClockTime() / 24000d);
            q.put("moon_phase", Math.floorMod(level.getDefaultClockTime() / 24000, 8));
            q.put("time_stamp", level.getDefaultClockTime());
            q.put("actor_count", level.getEntityCount());
            q.put("weather", level.isThundering() ? 2 : level.isRaining() ? 1 : 0);
            q.put("dimension_name", level.dimension().identifier().toString());
            q.put("block_light", level.getBrightness(LightLayer.BLOCK, p.blockPosition()));
            q.put("sky_light", level.getBrightness(LightLayer.SKY, p.blockPosition()));
            q.put("is_open_air", level.canSeeSky(p.blockPosition()));
        }
        HitResult hit = Minecraft.getInstance().hitResult;
        q.put(
                "hit_target_type",
                hit instanceof EntityHitResult
                        ? "entity"
                        : hit instanceof BlockHitResult && hit.getType() != HitResult.Type.MISS
                                ? "block"
                                : "");
        q.put(
                "hit_target_id",
                hit instanceof EntityHitResult e
                        ? BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString()
                        : hit instanceof BlockHitResult b && level != null
                                ? BuiltInRegistries.BLOCK
                                        .getKey(level.getBlockState(b.getBlockPos()).getBlock())
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

    com.blanoir.moons.ysm.internal.runtime.LocalRuntime.ObservationView view(float partial) {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        var extracted =
                Minecraft.getInstance().getEntityRenderDispatcher().extractEntity(player, partial);
        if (!(extracted instanceof AvatarRenderState state)) return null;
        return new com.blanoir.moons.ysm.internal.runtime.LocalRuntime.ObservationView(
                sample(player, state), this::query);
    }

    private static double wrap(double angle) {
        angle %= 360;
        return angle >= 180 ? angle - 360 : angle < -180 ? angle + 360 : angle;
    }

    static String category(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        if (stack.is(Items.CROSSBOW))
            return CrossbowItem.isCharged(stack) ? "charged_crossbow" : "crossbow";
        if (stack.is(Items.TRIDENT)) return "trident";
        if (stack.getUseAnimation() == ItemUseAnimation.SPEAR) return "lance";
        if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION))
            return "throwable_potion";
        for (String type : List.of("sword", "axe", "pickaxe", "shovel", "hoe"))
            if (stack.is(
                    TagKey.create(
                            Registries.ITEM,
                            Identifier.withDefaultNamespace(
                                    type.equals("sword") ? "swords" : type + "s")))) return type;
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return item;
    }

    Object query(String namespace, String name, List<Object> args) {
        return YsmEntityQueries.query(player, snapshot, diagnostic, namespace, name, args);
    }
}
