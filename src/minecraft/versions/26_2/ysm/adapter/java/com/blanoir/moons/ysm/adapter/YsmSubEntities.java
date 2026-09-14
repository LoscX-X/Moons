package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import java.util.*;

/** Local ownership replaces upstream capabilities; states and effects are scoped to each entity. */
final class YsmSubEntities implements AutoCloseable {
    private final LocalYsmModel parent;
    private final YsmObservations ownerObservations;
    private final Map<Entity, Instance> instances = new IdentityHashMap<>();
    private final Map<EntityRenderState, Captured> captured = new WeakHashMap<>();
    private final Set<String> failed = new HashSet<>();

    private record Captured(Instance instance, float partial) {}

    private static final java.lang.reflect.Field GROUND_TIME =
            field(AbstractArrow.class, "inGroundTime");
    private static final java.lang.reflect.Field BITING =
            field(net.minecraft.world.entity.projectile.FishingHook.class, "biting");

    private static java.lang.reflect.Field field(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static final java.lang.reflect.Method IN_GROUND = arrowGroundMethod();

    private static java.lang.reflect.Method arrowGroundMethod() {
        try {
            var method = AbstractArrow.class.getDeclaredMethod("isInGround");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    YsmSubEntities(LocalYsmModel parent, YsmObservations observations) {
        this.parent = parent;
        ownerObservations = observations;
    }

    void capture(Object owner, Object[] args) {
        if (!(owner instanceof Entity entity)
                || args.length != 2
                || !(args[0] instanceof EntityRenderState state)) return;
        captured.remove(state);
        var player = Minecraft.getInstance().player;
        if (player == null || entity == player) return;
        boolean projectile = entity instanceof Projectile;
        boolean owned =
                projectile
                        ? ((Projectile) entity).getOwner() == player
                        : player.isPassenger() && player.getRootVehicle() == entity;
        // The 26.1.2 upstream deliberately keeps vanilla boat and minecart renderers.
        if (!owned || entity instanceof AbstractBoat || entity instanceof AbstractMinecart) return;
        String id =
                parent.matchingSubEntity(
                        projectile,
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        if (id == null || failed.contains((projectile ? "p:" : "v:") + id)) return;
        Instance instance = instances.get(entity);
        if (instance == null) {
            if (instances.size() >= 64) prune();
            if (instances.size() >= 64) return;
            try {
                instance = new Instance(entity, projectile, id);
                instances.put(entity, instance);
            } catch (Exception error) {
                failed.add((projectile ? "p:" : "v:") + id);
                parent.runtime().diagnostic("Sub-entity " + id + ": " + error);
                return;
            }
        }
        captured.put(state, new Captured(instance, ((Number) args[1]).floatValue()));
    }

    boolean submit(Object[] args) {
        if (args.length != 7 || !(args[0] instanceof EntityRenderState state)) return false;
        Captured capture = captured.get(state);
        if (capture == null || state.isInvisible) return false;
        Instance instance = capture.instance;
        if (instance.entity.isRemoved()) return false;
        var pose = (PoseStack) args[5];
        var collector = (SubmitNodeCollector) args[6];
        var entity = instance.entity;
        float partial = capture.partial;
        Map<String, Object> q =
                YsmEntityObservations.sample(entity, state, partial, instance.lastAge);
        instance.lastAge = state.ageInTicks;
        instance.snapshot = q;
        q.put("is_in_water", entity.isInWater());
        q.put("is_on_fire", entity.isOnFire());
        boolean ground = entity.onGround();
        if (entity instanceof AbstractArrow)
            try {
                ground |= (boolean) IN_GROUND.invoke(entity);
            } catch (ReflectiveOperationException e) {
                instance.model.runtime().diagnostic(e.toString());
            }
        q.put("is_on_ground", ground);
        q.put("in_ground", ground);
        if (entity instanceof AbstractArrow arrow) {
            try {
                q.put("on_ground_time", GROUND_TIME.getInt(arrow));
            } catch (ReflectiveOperationException error) {
                instance.model.runtime().diagnostic(error.toString());
            }
            var weapon = arrow.getWeaponItem();
            q.put(
                    "shoot_item_id",
                    weapon == null
                            ? ""
                            : BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString());
            q.put(
                    "is_spectral_arrow",
                    arrow instanceof net.minecraft.world.entity.projectile.arrow.SpectralArrow);
        }
        if (entity
                instanceof
                net.minecraft.world.entity.projectile.throwableitemprojectile
                                .ThrowableItemProjectile
                        thrown)
            q.put(
                    "throwable_item",
                    BuiltInRegistries.ITEM.getKey(thrown.getItem().getItem()).toString());
        if (entity instanceof net.minecraft.world.entity.projectile.FishingHook hook) {
            q.put(
                    "hooked_in",
                    hook.getHookedIn() == null
                            ? ""
                            : BuiltInRegistries.ENTITY_TYPE
                                    .getKey(hook.getHookedIn().getType())
                                    .toString());
            try {
                q.put("is_biting", BITING.getBoolean(hook));
            } catch (ReflectiveOperationException error) {
                instance.model.runtime().diagnostic(error.toString());
            }
        }
        if (instance.projectile) q.put("projectile_owner", ownerObservations.view(partial));
        q.put("is_alive", entity.isAlive());
        q.put("is_dead", !entity.isAlive());
        q.put("has_rider", !entity.getPassengers().isEmpty());
        q.put("is_riding", entity.isPassenger());
        q.put("ground_speed", entity.getDeltaMovement().horizontalDistance() * 20);
        q.put("vertical_speed", entity.getDeltaMovement().y * 20);
        q.put("body_y_rotation", entity.getYRot(partial));
        q.put("body_x_rotation", entity.getXRot(partial));
        q.put("head_x_rotation", entity.getXRot(partial));
        q.put("head_y_rotation", 0);
        q.put(
                "modified_distance_moved",
                entity instanceof LivingEntity living ? living.walkAnimation.position(partial) : 0);
        q.put(
                "modified_move_speed",
                entity instanceof LivingEntity living ? living.walkAnimation.speed(partial) : 0);
        q.put("age", state.ageInTicks);
        q.put("position_x", state.x);
        q.put("position_y", state.y);
        q.put("position_z", state.z);
        parent.runtime()
                .variables()
                .forEach(
                        (name, value) -> {
                            if (name.startsWith("roaming."))
                                instance.model.runtime().variable(name, value);
                        });
        var mesh = instance.model.frame(state.ageInTicks / 20d, q, "");
        pose.pushPose();
        try {
            pose.translate(
                    ((Number) args[2]).doubleValue(),
                    ((Number) args[3]).doubleValue(),
                    ((Number) args[4]).doubleValue());
            if (instance.projectile) {
                pose.mulPose(Axis.YP.rotationDegrees(entity.getYRot(partial) - 90));
                pose.mulPose(
                        Axis.ZP.rotationDegrees(
                                entity.getXRot(partial)
                                        + (entity instanceof ThrownTrident ? 90 : 0)));
            } else {
                float yaw =
                        entity instanceof LivingEntity living
                                ? net.minecraft.util.Mth.rotLerp(
                                        partial, living.yBodyRotO, living.yBodyRot)
                                : entity.getYRot(partial);
                pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
            }
            pose.scale(.7f, .7f, .7f);
            for (var pass : mesh.passes()) {
                float[] vertices = pass.vertices();
                collector.submitCustomGeometry(
                        pose,
                        instance.types.get(instance.texture, pass),
                        (transform, consumer) ->
                                YsmVertices.emit(
                                        transform,
                                        consumer,
                                        vertices,
                                        OverlayTexture.NO_OVERLAY,
                                        pass.glow() ? 0xF000F0 : state.lightCoords));
            }
        } finally {
            pose.popPose();
        }
        return true;
    }

    void prune() {
        var it = instances.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getKey().isRemoved()
                    || entry.getKey().level() != Minecraft.getInstance().level) {
                entry.getValue().close();
                it.remove();
            }
        }
    }

    public void close() {
        instances.values().forEach(Instance::close);
        instances.clear();
        captured.clear();
        failed.clear();
    }

    private final class Instance implements AutoCloseable {
        final Entity entity;
        Map<String, Object> snapshot = Map.of();
        float lastAge = Float.NaN;
        final boolean projectile;
        final LocalYsmModel model;
        final YsmEffects effects;
        final Identifier texture;
        final YsmRenderTypes types = new YsmRenderTypes();

        Instance(Entity entity, boolean projectile, String id) throws Exception {
            this.entity = entity;
            this.projectile = projectile;
            model = parent.subEntity(projectile, id);
            effects = new YsmEffects(model, () -> entity);
            texture =
                    Identifier.fromNamespaceAndPath(
                            "moons", "ysm/" + UUID.randomUUID().toString().replace("-", ""));
            try {
                model.prepareTexture("");
                model.runtime().effects(effects);
                model.runtime()
                        .queries(
                                (namespace, name, args) ->
                                        YsmEntityQueries.query(
                                                entity,
                                                snapshot,
                                                model.runtime()::diagnostic,
                                                namespace,
                                                name,
                                                args));
                YsmRenderAdapter.upload(texture, YsmRenderAdapter.decodeTexture(model.texture("")));
            } catch (Throwable error) {
                effects.close();
                model.close();
                throw error;
            }
        }

        public void close() {
            effects.close();
            model.close();
            Minecraft.getInstance().getTextureManager().release(texture);
        }
    }
}
