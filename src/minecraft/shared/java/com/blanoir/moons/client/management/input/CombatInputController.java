package com.blanoir.moons.client.management.input;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.event.EventBus;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

import java.util.EnumSet;

/**
 * Arbitrates synthetic combat input.  A key is restored only after every module
 * that currently owns it has released it, preventing Predict, WTap and
 * JumpReset from undoing each other's input state.
 */
public final class CombatInputController {
    public enum Owner {
        PREDICT_CRITICAL,
        SILENT_AURA,
        JUMP_RESET,
        JUMP_FLY,
        AUTO_MLG,
        AUTO_WEB,
        AUTO_LAVA,
        AUTO_BED,
        ANTI_LAVA,
        ANTI_WEB,
        SPRINT_RESET
    }

    private static final EnumSet<Owner> forwardSuppressors = EnumSet.noneOf(Owner.class);
    private static final EnumSet<Owner> sprintSuppressors = EnumSet.noneOf(Owner.class);
    private static final EnumSet<Owner> attackSuppressors = EnumSet.noneOf(Owner.class);
    private static final EnumSet<Owner> jumpForcers = EnumSet.noneOf(Owner.class);
    private static boolean initialized;
    private static boolean syntheticAttackDown;
    private static int syntheticAttackTicks;
    private static Entity pendingAttackTarget;
    private static boolean invokingTargetAttack;
    private static long completedTargetAttacks;

    private CombatInputController() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "CombatInputController.context", event -> reset(event.client()));
        EventBus.TICK.register(
                "CombatInputController.tickSyntheticAttack",
                event -> tickSyntheticAttack(event.client()));
        EventBus.ATTACK_ENTITY_POST.register(
                "CombatInputController.attackCompleted",
                event -> {
                    if (invokingTargetAttack && event.attacker() == Minecraft.getInstance().player)
                        completedTargetAttacks++;
                });
    }

    public static void suppressForward(Minecraft client, Owner owner) {
        forwardSuppressors.add(owner);
        if (valid(client)) {
            client.options.keyUp.setDown(false);
        }
    }

    public static void releaseForward(Minecraft client, Owner owner) {
        forwardSuppressors.remove(owner);
        if (valid(client) && forwardSuppressors.isEmpty()) {
            restorePhysicalState(client, client.options.keyUp);
        }
    }

    public static void suppressSprint(Minecraft client, Owner owner) {
        sprintSuppressors.add(owner);
        if (valid(client)) {
            client.options.keySprint.setDown(false);
        }
    }

    public static void releaseSprint(Minecraft client, Owner owner) {
        sprintSuppressors.remove(owner);
        if (valid(client) && sprintSuppressors.isEmpty()) {
            restorePhysicalState(client, client.options.keySprint);
        }
    }

    /**  sprint implementations honor higher-priority combat timing. */
    public static boolean isSprintSuppressed() {
        return !sprintSuppressors.isEmpty();
    }

    public static void suppressAttack(Minecraft client, Owner owner) {
        attackSuppressors.add(owner);
        if (valid(client)) {
            client.options.keyAttack.setDown(false);
        }
    }

    public static void releaseAttack(Minecraft client, Owner owner) {
        attackSuppressors.remove(owner);
        if (valid(client) && attackSuppressors.isEmpty()) {
            restorePhysicalState(client, client.options.keyAttack);
        }
    }

    public static void forceJump(Minecraft client, Owner owner) {
        jumpForcers.add(owner);
        if (valid(client)) {
            client.options.keyJump.setDown(true);
        }
    }

    public static void releaseJump(Minecraft client, Owner owner) {
        jumpForcers.remove(owner);
        if (valid(client) && jumpForcers.isEmpty()) {
            restorePhysicalState(client, client.options.keyJump);
        }
    }

    /**
     * Presses the jump key through the same callback path as a real keyboard or
     * mouse event instead of flipping the KeyMapping state synthetically.
     */
    public static void pressJumpPhysical(Minecraft client, Owner owner) {
        jumpForcers.add(owner);
        if (valid(client)) {
            pressPhysicalKey(client, client.options.keyJump);
        }
    }

    public static void releaseJumpPhysical(Minecraft client, Owner owner) {
        jumpForcers.remove(owner);
        if (valid(client) && jumpForcers.isEmpty()) {
            KeyMapping mapping = client.options.keyJump;
            InputConstants.Key key = GameAccess.boundKey(mapping);
            if (isInvalidKey(key)) {
                mapping.setDown(false);
                return;
            }
            if (key.getType() == InputConstants.Type.MOUSE) {
                // A real press must never be cancelled by the synthetic release.
                if (!isPhysicallyDown(client, mapping)) {
                    GameAccess.invokeMouseButton(
                            client.mouseHandler,
                            client.getWindow().handle(),
                            new MouseButtonInfo(key.getValue(), 0),
                            InputConstants.RELEASE);
                }
            } else {
                KeyMapping.set(key, isPhysicallyDown(client, mapping));
            }
        }
    }

    public static void releaseAll(Minecraft client, Owner owner) {
        releaseForward(client, owner);
        releaseSprint(client, owner);
        releaseAttack(client, owner);
        releaseJump(client, owner);
    }

    /** Context loss/unload clears synthetic state without generating a new input callback. */
    public static void reset(Minecraft client) {
        forwardSuppressors.clear();
        sprintSuppressors.clear();
        attackSuppressors.clear();
        jumpForcers.clear();
        syntheticAttackDown = false;
        syntheticAttackTicks = 0;
        pendingAttackTarget = null;
        invokingTargetAttack = false;
        if (client == null || client.options == null) return;
        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyAttack.setDown(false);
        client.options.keyJump.setDown(false);
        if (valid(client)) {
            restorePhysicalState(client, client.options.keyUp);
            restorePhysicalState(client, client.options.keySprint);
            restorePhysicalState(client, client.options.keyAttack);
            restorePhysicalState(client, client.options.keyJump);
        }
    }

    public static boolean isPhysicallyDown(Minecraft client, KeyMapping mapping) {
        if (!valid(client) || mapping == null) {
            return false;
        }
        InputConstants.Key key = GameAccess.boundKey(mapping);
        if (isInvalidKey(key)) {
            return false;
        }
        return MinecraftClientAccess.isHardwareKeyDown(client, key);
    }

    /**
     * Reads a vanilla key-mapping state, but only while the game is actually
     * playable.  Outside gameplay (GUI open, no world/player) the mapping may
     * hold a stale W/Space state, so callers must not treat it as input.
     */
    public static boolean isDown(Minecraft client, KeyMapping mapping) {
        return valid(client) && mapping != null && mapping.isDown();
    }

    public static void click(Minecraft client, KeyMapping mapping) {
        if (!valid(client) || mapping == null) {
            return;
        }
        if (mapping == client.options.keyAttack) {
            pendingAttackTarget = null;
            pressAttack(client, mapping);
            return;
        }
        InputConstants.Key key = GameAccess.boundKey(mapping);
        if (isInvalidKey(key)) {
            return;
        }
        KeyMapping.click(key);
    }

    private static boolean pressAttack(Minecraft client, KeyMapping mapping) {
        if (!pressPhysicalKey(client, mapping)) {
            return false;
        }
        syntheticAttackDown = true;
        syntheticAttackTicks = 1;
        return true;
    }

    /** Feeds a press through the vanilla mouse/keyboard callback like a real event. */
    private static boolean pressPhysicalKey(Minecraft client, KeyMapping mapping) {
        if (!valid(client) || mapping == null) {
            return false;
        }
        InputConstants.Key key = GameAccess.boundKey(mapping);
        if (isInvalidKey(key)) {
            return false;
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            GameAccess.invokeMouseButton(
                    client.mouseHandler,
                    client.getWindow().handle(),
                    new MouseButtonInfo(key.getValue(), 0),
                    InputConstants.PRESS);
        } else {
            KeyMapping.set(key, true);
            KeyMapping.click(key);
        }
        return true;
    }

    /** Uses one real mouse-input submission path for TriggerBot and SilentAura. */
    public static void attackTarget(Minecraft client, Entity target) {
        attackTarget(client, target, false);
    }

    /**
     * Dispatches the attack on the current tick, immediately after the caller
     * validated the target against the rotation the server already holds.
     * Queuing the click for the next tick's input stage would let the server
     * validate the interact against a stale rotation packet.
     */
    public static void attackTarget(Minecraft client, Entity target, boolean forceTargetOverride) {
        attackTargetNow(client, target, forceTargetOverride);
    }

    /**
     * Submits a visible input press and invokes vanilla startAttack immediately.
     * Used after the matching silent rotation packet has actually been sent, so
     * a moving target is not validated against the previous tick's direction.
     */
    public static boolean attackTargetNow(
            Minecraft client, Entity target, boolean forceTargetOverride) {
        if (!valid(client) || target == null || !target.isAlive()) return false;
        boolean cameraAlreadyTargetsEntity =
                client.hitResult instanceof EntityHitResult hit && hit.getEntity() == target;
        pendingAttackTarget = forceTargetOverride || !cameraAlreadyTargetsEntity ? target : null;
        if (!pressAttack(client, client.options.keyAttack)) {
            pendingAttackTarget = null;
            return false;
        }
        while (client.options.keyAttack.consumeClick()) {
            // startAttack is invoked below; do not leave a duplicate click queued.
        }
        // startAttack's boolean describes block breaking. Observe the entity
        // attack itself so Legacy clicks also work when charge was already zero.
        long completedBefore = completedTargetAttacks;
        boolean previousInvocation = invokingTargetAttack;
        invokingTargetAttack = true;
        try {
            GameAccess.invokeStartAttack(client);
            return completedTargetAttacks != completedBefore;
        } finally {
            invokingTargetAttack = previousInvocation;
            // The ATTACK action hook normally consumes this at method HEAD.
            // Never allow a failed/short-circuited invocation to leak the
            // forced entity into a later physical click.
            pendingAttackTarget = null;
        }
    }

    /** Only the synchronous, target-validated attack may bypass the manual input gate. */
    public static boolean isInvokingTargetAttack() {
        return invokingTargetAttack;
    }

    /** Consumed by Minecraft.startAttack; stale or cross-world targets are rejected. */
    public static EntityHitResult consumePendingAttackHit(Minecraft client) {
        var currentLevel = client == null ? null : client.level;
        Entity target = pendingAttackTarget;
        pendingAttackTarget = null;
        if (client == null
                || currentLevel == null
                || !valid(client)
                || target == null
                || !target.isAlive()
                || target == client.player
                || currentLevel.getEntity(target.getId()) != target) {
            return null;
        }
        return new EntityHitResult(target);
    }

    private static void tickSyntheticAttack(Minecraft client) {
        if (!syntheticAttackDown || --syntheticAttackTicks > 0) {
            return;
        }

        syntheticAttackDown = false;
        syntheticAttackTicks = 0;
        // startAttack normally consumes this during the same client tick. If a
        // screen or another handler swallowed the click, never leak its target
        // override into a later physical attack.
        pendingAttackTarget = null;
        if (client == null) {
            return;
        }

        KeyMapping mapping = client.options.keyAttack;
        InputConstants.Key key = GameAccess.boundKey(mapping);
        if (isInvalidKey(key)) {
            mapping.setDown(false);
            return;
        }
        if (isPhysicallyDown(client, mapping)) {
            return;
        }

        if (key.getType() == InputConstants.Type.MOUSE) {
            GameAccess.invokeMouseButton(
                    client.mouseHandler,
                    client.getWindow().handle(),
                    new MouseButtonInfo(key.getValue(), 0),
                    InputConstants.RELEASE);
        } else {
            KeyMapping.set(key, false);
        }
    }

    private static void restorePhysicalState(Minecraft client, KeyMapping mapping) {
        if (mapping == client.options.keySprint && client.options.toggleSprint().get()) {
            // Vanilla toggle sprint runs on a ToggleKeyMapping: setDown(true)
            // flips the toggle and setDown(false) is ignored. Restoring here
            // would cancel the user's toggled sprint, so leave it to vanilla's
            // screen-open handling instead.
            return;
        }

        mapping.setDown(isPhysicallyDown(client, mapping));
    }

    private static boolean isInvalidKey(InputConstants.Key key) {
        return key == null || key == InputConstants.UNKNOWN || key.getValue() < 0;
    }

    private static boolean valid(Minecraft client) {
        // Synthetic input and physical-key detection must only run during real
        // gameplay: no GUI, and the player/world/game mode are present.  Outside
        // that context the key mappings can hold stale W/Space state, and
        // pressing/releasing them would leak input into menus or the title screen.
        return client != null
                && MinecraftClientAccess.screen(client) == null
                && client.player != null
                && client.level != null
                && client.gameMode != null;
    }
}
