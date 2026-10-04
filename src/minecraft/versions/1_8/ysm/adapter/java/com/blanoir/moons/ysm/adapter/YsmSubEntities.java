package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.*;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.item.EntityExpBottle;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.projectile.*;
import net.minecraft.util.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.*;

/** Per-entity sessions for local projectiles and mounts; vanilla boats and carts stay vanilla. */
final class YsmSubEntities implements AutoCloseable {
    private final LocalYsmModel parent;
    private final YsmObservations ownerObservations;
    private final Map<Entity, Instance> instances = new IdentityHashMap<>();
    private final Set<String> failed = new HashSet<>();
    private static final java.lang.reflect.Field ARROW_GROUND =
            field(EntityArrow.class, "inGround", "field_70254_i", "i");
    private static final java.lang.reflect.Field ARROW_TIME =
            field(EntityArrow.class, "ticksInGround", "field_70252_j", "ar");
    private static final java.lang.reflect.Field FISH_BITING =
            field(EntityFishHook.class, "ticksCatchable", "field_146045_ax", "av");

    private static java.lang.reflect.Field field(Class<?> type, String... names) {
        for (String name : names)
            try {
                var f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (ReflectiveOperationException ignored) {
            }
        throw new ExceptionInInitializerError(
                "Missing 1.8 field on " + type.getName() + ": " + Arrays.toString(names));
    }

    YsmSubEntities(LocalYsmModel model, YsmObservations observations) {
        parent = model;
        ownerObservations = observations;
    }

    void capture(Object owner, Object[] args) {
        if (owner instanceof Entity e) prepare(e);
    }

    private Instance prepare(Entity e) {
        var player = Minecraft.getMinecraft().thePlayer;
        if (player == null || e == player) return null;
        Entity owner =
                e instanceof EntityArrow a
                        ? a.shootingEntity
                        : e instanceof EntityThrowable t
                                ? t.getThrower()
                                : e instanceof EntityFishHook f
                                        ? f.angler
                                        : e instanceof EntityFireball f ? f.shootingEntity : null;
        boolean projectile = owner != null;
        Entity mount = player.ridingEntity;
        while (mount != null && mount.ridingEntity != null) mount = mount.ridingEntity;
        if ((projectile ? owner != player : mount != e)
                || e instanceof EntityBoat
                || e instanceof EntityMinecart) return null;
        String type =
                e instanceof EntityFishHook ? "fishing_bobber" : EntityList.getEntityString(e);
        if (type == null) return null;
        String id = parent.matchingSubEntity(projectile, entityId(type));
        if (id == null || failed.contains((projectile ? "p:" : "v:") + id)) return null;
        Instance instance = instances.get(e);
        if (instance != null) return instance;
        if (instances.size() >= 64) prune();
        if (instances.size() >= 64) return null;
        try {
            instance = new Instance(e, projectile, id);
            instances.put(e, instance);
            return instance;
        } catch (Exception error) {
            failed.add((projectile ? "p:" : "v:") + id);
            parent.runtime().diagnostic("Sub-entity " + id + ": " + error);
            return null;
        }
    }

    private static String entityId(String value) {
        return "minecraft:"
                + switch (value) {
                    case "Arrow" -> "arrow";
                    case "Snowball" -> "snowball";
                    case "ThrownEnderpearl" -> "ender_pearl";
                    case "ThrownPotion" -> "potion";
                    case "ThrownExpBottle" -> "experience_bottle";
                    case "Fireball" -> "fireball";
                    case "SmallFireball" -> "small_fireball";
                    case "EntityHorse" -> "horse";
                    case "Pig" -> "pig";
                    default -> value.toLowerCase(Locale.ROOT);
                };
    }

    boolean submit(Object[] args) {
        if (args.length != 7 || !(args[0] instanceof Entity e) || e.isInvisible() || e.isDead)
            return false;
        Instance instance = prepare(e);
        if (instance == null) return false;
        float partial = ((Number) args[5]).floatValue();
        YsmRenderState state = new YsmRenderState(e, partial);
        Map<String, Object> q =
                new HashMap<>(YsmEntityObservations.sample(e, state, partial, instance.motion));
        instance.snapshot = q;
        boolean ground = e.onGround;
        try {
            if (e instanceof EntityArrow arrow) {
                ground |= ARROW_GROUND.getBoolean(arrow);
                q.put("on_ground_time", ARROW_TIME.getInt(arrow));
                q.put("shoot_item_id", "minecraft:bow");
                q.put("is_spectral_arrow", false);
            }
            if (e instanceof EntityFishHook hook) {
                q.put(
                        "hooked_in",
                        hook.caughtEntity == null
                                ? ""
                                : entityId(
                                        Objects.toString(
                                                EntityList.getEntityString(hook.caughtEntity),
                                                "")));
                q.put("is_biting", FISH_BITING.getInt(hook) > 0);
            }
        } catch (IllegalAccessException error) {
            instance.model.runtime().diagnostic(error.toString());
        }
        q.put("is_on_ground", ground);
        q.put("in_ground", ground);
        if (e instanceof EntityThrowable) {
            q.put(
                    "throwable_item",
                    e instanceof EntitySnowball
                            ? "minecraft:snowball"
                            : e instanceof EntityEgg
                                    ? "minecraft:egg"
                                    : e instanceof EntityEnderPearl
                                            ? "minecraft:ender_pearl"
                                            : e instanceof EntityPotion
                                                    ? "minecraft:potion"
                                                    : e instanceof EntityExpBottle
                                                            ? "minecraft:experience_bottle"
                                                            : "");
        }
        if (instance.projectile) q.put("projectile_owner", ownerObservations.view(partial));
        q.put("is_alive", !e.isDead);
        q.put("is_dead", e.isDead);
        q.put("has_rider", e.riddenByEntity != null);
        q.put("is_riding", e.ridingEntity != null);
        q.put("ground_speed", Math.hypot(e.motionX, e.motionZ) * 20);
        q.put("vertical_speed", e.motionY * 20);
        q.put("body_y_rotation", state.bodyRot);
        q.put("body_x_rotation", state.xRot);
        q.put("head_x_rotation", state.xRot);
        q.put("head_y_rotation", state.yRot);
        q.put("modified_distance_moved", e.distanceWalkedModified);
        q.put("modified_move_speed", state.walkAnimationSpeed);
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
        Matrix4f transform = new Matrix4f();
        if (instance.projectile)
            transform
                    .rotateY(
                            (float)
                                    Math.toRadians(
                                            YsmRenderState.angle(
                                                            partial,
                                                            e.prevRotationYaw,
                                                            e.rotationYaw)
                                                    - 90))
                    .rotateZ((float) Math.toRadians(state.xRot));
        else transform.rotateY((float) Math.toRadians(180 - state.bodyRot));
        transform.scale(.7f);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(
                    ((Number) args[1]).doubleValue(),
                    ((Number) args[2]).doubleValue(),
                    ((Number) args[3]).doubleValue());
            instance.types.draw(
                    instance.texture, mesh, transform, state.lightCoords, state.hasRedOverlay);
        } finally {
            GL11.glPopMatrix();
        }
        return true;
    }

    void prune() {
        var it = instances.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().isDead || e.getKey().worldObj != Minecraft.getMinecraft().theWorld) {
                e.getValue().close();
                it.remove();
            }
        }
    }

    public void close() {
        instances.values().forEach(Instance::close);
        instances.clear();
        failed.clear();
    }

    private final class Instance implements AutoCloseable {
        final Entity entity;
        final boolean projectile;
        final LocalYsmModel model;
        final YsmEffects effects;
        final ResourceLocation texture;
        final YsmMotionTracker motion = new YsmMotionTracker();
        final YsmRenderTypes types = new YsmRenderTypes();
        Map<String, Object> snapshot = Map.of();

        Instance(Entity e, boolean projectile, String id) throws Exception {
            entity = e;
            this.projectile = projectile;
            model = parent.subEntity(projectile, id);
            effects = new YsmEffects(model, () -> e);
            texture =
                    new ResourceLocation(
                            "moons", "ysm/" + UUID.randomUUID().toString().replace("-", ""));
            try {
                model.prepareTexture("");
                model.runtime().effects(effects);
                model.runtime()
                        .queries(
                                (namespace, name, args) ->
                                        YsmEntityQueries.query(
                                                e,
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
            Minecraft.getMinecraft().getTextureManager().deleteTexture(texture);
        }
    }
}
