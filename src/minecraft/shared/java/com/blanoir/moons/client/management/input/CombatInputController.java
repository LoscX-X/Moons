package com.blanoir.moons.client.management.input;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.compat.input.InputConstants;
import com.blanoir.moons.client.event.EventBus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;

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
        BLOCK_IN,
        BLOCKING_USE,
        AUTO_BLOCK,
        ANTI_LAVA,
        ANTI_WEB,
        SPRINT_RESET
    }

    private static final EnumSet<Owner> forwardSuppressors = EnumSet.noneOf(Owner.class);
    private static final EnumSet<Owner> sprintSuppressors = EnumSet.noneOf(Owner.class);
    private static final EnumSet<Owner> attackSuppressors = EnumSet.noneOf(Owner.class);
    private static final EnumSet<Owner> jumpForcers = EnumSet.noneOf(Owner.class);
    private static final EnumSet<Owner> useForcers = EnumSet.noneOf(Owner.class);
    private static boolean initialized;
    private static boolean syntheticAttackDown;
    private static int syntheticAttackTicks;
    private static MovingObjectPosition pendingAttackTarget;
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
                    if (invokingTargetAttack
                            && event.attacker() == Minecraft.getMinecraft().thePlayer)
                        completedTargetAttacks++;
                });
    }

    public static void suppressForward(Minecraft client, Owner owner) {
        forwardSuppressors.add(owner);
        if (valid(client)) {
            KeyBinding.setKeyBindState(client.gameSettings.keyBindForward.getKeyCode(), false);
        }
    }

    public static void releaseForward(Minecraft client, Owner owner) {
        forwardSuppressors.remove(owner);
        if (valid(client) && forwardSuppressors.isEmpty()) {
            restorePhysicalState(client, client.gameSettings.keyBindForward);
        }
    }

    public static void suppressSprint(Minecraft client, Owner owner) {
        sprintSuppressors.add(owner);
        if (valid(client)) {
            KeyBinding.setKeyBindState(client.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }

    public static void releaseSprint(Minecraft client, Owner owner) {
        sprintSuppressors.remove(owner);
        if (valid(client) && sprintSuppressors.isEmpty()) {
            restorePhysicalState(client, client.gameSettings.keyBindSprint);
        }
    }

    /**  sprint implementations honor higher-priority combat timing. */
    public static boolean isSprintSuppressed() {
        return !sprintSuppressors.isEmpty();
    }

    public static void suppressAttack(Minecraft client, Owner owner) {
        attackSuppressors.add(owner);
        if (valid(client)) {
            KeyBinding.setKeyBindState(client.gameSettings.keyBindAttack.getKeyCode(), false);
        }
    }

    public static void releaseAttack(Minecraft client, Owner owner) {
        attackSuppressors.remove(owner);
        if (valid(client) && attackSuppressors.isEmpty()) {
            restorePhysicalState(client, client.gameSettings.keyBindAttack);
        }
    }

    public static void forceJump(Minecraft client, Owner owner) {
        jumpForcers.add(owner);
        if (valid(client)) {
            KeyBinding.setKeyBindState(client.gameSettings.keyBindJump.getKeyCode(), true);
        }
    }

    public static void releaseJump(Minecraft client, Owner owner) {
        jumpForcers.remove(owner);
        if (valid(client) && jumpForcers.isEmpty()) {
            restorePhysicalState(client, client.gameSettings.keyBindJump);
        }
    }

    /** Maintains a native held-use action without submitting another use click. */
    public static void holdUse(Minecraft client, Owner owner) {
        useForcers.add(owner);
        if (valid(client))
            KeyBinding.setKeyBindState(client.gameSettings.keyBindUseItem.getKeyCode(), true);
    }

    public static void releaseUse(Minecraft client, Owner owner) {
        if (!useForcers.remove(owner) || !useForcers.isEmpty()) return;
        if (client != null && client.gameSettings != null) {
            KeyBinding.setKeyBindState(client.gameSettings.keyBindUseItem.getKeyCode(), false);
            if (valid(client)) restorePhysicalState(client, client.gameSettings.keyBindUseItem);
        }
    }

    /**
     * Presses the jump key through the same callback path as a real keyboard or
     * mouse event instead of flipping the KeyMapping state synthetically.
     */
    public static void pressJumpPhysical(Minecraft client, Owner owner) {
        jumpForcers.add(owner);
        if (valid(client)) {
            pressPhysicalKey(client, client.gameSettings.keyBindJump);
        }
    }

    public static void releaseJumpPhysical(Minecraft client, Owner owner) {
        jumpForcers.remove(owner);
        if (valid(client) && jumpForcers.isEmpty()) {
            KeyBinding mapping = client.gameSettings.keyBindJump;
            InputConstants.Key key = GameAccess.boundKey(mapping);
            if (isInvalidKey(key)) {
                KeyBinding.setKeyBindState(mapping.getKeyCode(), false);
                return;
            }
            KeyBinding.setKeyBindState(mapping.getKeyCode(), isPhysicallyDown(client, mapping));
        }
    }

    public static void releaseAll(Minecraft client, Owner owner) {
        releaseForward(client, owner);
        releaseSprint(client, owner);
        releaseAttack(client, owner);
        releaseJump(client, owner);
        releaseUse(client, owner);
    }

    /** Context loss/unload clears synthetic state without generating a new input callback. */
    public static void reset(Minecraft client) {
        boolean heldUse = !useForcers.isEmpty();
        useForcers.clear();
        forwardSuppressors.clear();
        sprintSuppressors.clear();
        attackSuppressors.clear();
        jumpForcers.clear();
        syntheticAttackDown = false;
        syntheticAttackTicks = 0;
        pendingAttackTarget = null;
        invokingTargetAttack = false;
        if (client == null || client.gameSettings == null) return;
        KeyBinding.setKeyBindState(client.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(client.gameSettings.keyBindSprint.getKeyCode(), false);
        KeyBinding.setKeyBindState(client.gameSettings.keyBindAttack.getKeyCode(), false);
        KeyBinding.setKeyBindState(client.gameSettings.keyBindJump.getKeyCode(), false);
        if (heldUse)
            KeyBinding.setKeyBindState(client.gameSettings.keyBindUseItem.getKeyCode(), false);
        if (valid(client)) {
            restorePhysicalState(client, client.gameSettings.keyBindForward);
            restorePhysicalState(client, client.gameSettings.keyBindSprint);
            restorePhysicalState(client, client.gameSettings.keyBindAttack);
            restorePhysicalState(client, client.gameSettings.keyBindJump);
            if (heldUse) restorePhysicalState(client, client.gameSettings.keyBindUseItem);
        }
    }

    public static boolean isPhysicallyDown(Minecraft client, KeyBinding mapping) {
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
    public static boolean isDown(Minecraft client, KeyBinding mapping) {
        return valid(client) && mapping != null && mapping.isKeyDown();
    }

    public static void click(Minecraft client, KeyBinding mapping) {
        if (!valid(client) || mapping == null) {
            return;
        }
        if (mapping == client.gameSettings.keyBindAttack) {
            pendingAttackTarget = null;
            pressAttack(client, mapping);
            return;
        }
        InputConstants.Key key = GameAccess.boundKey(mapping);
        if (isInvalidKey(key)) {
            return;
        }
        KeyBinding.onTick(mapping.getKeyCode());
    }

    private static boolean pressAttack(Minecraft client, KeyBinding mapping) {
        if (!pressPhysicalKey(client, mapping)) {
            return false;
        }
        syntheticAttackDown = true;
        syntheticAttackTicks = 1;
        return true;
    }

    /** Feeds a press through the vanilla mouse/keyboard callback like a real event. */
    private static boolean pressPhysicalKey(Minecraft client, KeyBinding mapping) {
        if (!valid(client) || mapping == null) {
            return false;
        }
        InputConstants.Key key = GameAccess.boundKey(mapping);
        if (isInvalidKey(key)) {
            return false;
        }

        KeyBinding.setKeyBindState(mapping.getKeyCode(), true);
        KeyBinding.onTick(mapping.getKeyCode());
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
     * The automatic attacker calls this before LocalPlayer movement, using the
     * committed rotation for that tick. Vanilla retains attack/swing packet order.
     */
    public static boolean attackTargetNow(
            Minecraft client, Entity target, boolean forceTargetOverride) {
        return target != null
                && attackTargetNow(client, new MovingObjectPosition(target), forceTargetOverride);
    }

    /** Preserve the validated contact point for vanilla's weapon-specific AttackRange check. */
    public static boolean attackTargetNow(
            Minecraft client, MovingObjectPosition targetHit, boolean forceTargetOverride) {
        Entity target = targetHit == null ? null : targetHit.entityHit;
        if (!valid(client) || target == null || !target.isEntityAlive()) return false;
        boolean cameraAlreadyTargetsEntity =
                client.objectMouseOver != null && client.objectMouseOver.entityHit == target;
        pendingAttackTarget = forceTargetOverride || !cameraAlreadyTargetsEntity ? targetHit : null;
        if (!pressAttack(client, client.gameSettings.keyBindAttack)) {
            pendingAttackTarget = null;
            return false;
        }
        while (client.gameSettings.keyBindAttack.isPressed()) {
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

    /** The target that startAttack will consume, available to cancellable PRE listeners. */
    public static Entity attackInputTarget(Minecraft client) {
        Entity target = pendingAttackTarget == null ? null : pendingAttackTarget.entityHit;
        if (target == null && client != null && client.objectMouseOver != null)
            target = client.objectMouseOver.entityHit;
        return client != null
                        && client.theWorld != null
                        && target != null
                        && client.theWorld.getEntityByID(target.getEntityId()) == target
                ? target
                : null;
    }

    /** Consumed by Minecraft.startAttack; stale or cross-world targets are rejected. */
    public static MovingObjectPosition consumePendingAttackHit(Minecraft client) {
        var currentLevel = client == null ? null : client.theWorld;
        MovingObjectPosition hit = pendingAttackTarget;
        Entity target = hit == null ? null : hit.entityHit;
        pendingAttackTarget = null;
        if (client == null
                || currentLevel == null
                || !valid(client)
                || target == null
                || !target.isEntityAlive()
                || target == client.thePlayer
                || currentLevel.getEntityByID(target.getEntityId()) != target) {
            return null;
        }
        return hit;
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

        KeyBinding mapping = client.gameSettings.keyBindAttack;
        InputConstants.Key key = GameAccess.boundKey(mapping);
        if (isInvalidKey(key)) {
            KeyBinding.setKeyBindState(mapping.getKeyCode(), false);
            return;
        }
        if (isPhysicallyDown(client, mapping)) {
            return;
        }

        KeyBinding.setKeyBindState(mapping.getKeyCode(), false);
    }

    private static void restorePhysicalState(Minecraft client, KeyBinding mapping) {

        KeyBinding.setKeyBindState(mapping.getKeyCode(), isPhysicallyDown(client, mapping));
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
                && client.thePlayer != null
                && client.theWorld != null
                && client.playerController != null;
    }
}
