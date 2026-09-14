package com.blanoir.moons.ysm.internal.client.animation;

/** Upstream pure action selection; the version adapter supplies the entity snapshot. */
public final class ControllerActionResolver {
    public static final String DEATH = PlayerActionState.DEATH.animationName();
    public static final String RIPTIDE = PlayerActionState.RIPTIDE.animationName();
    public static final String SLEEP = PlayerActionState.SLEEP.animationName();
    public static final String SWIM = PlayerActionState.SWIM.animationName();
    public static final String CLIMB = PlayerActionState.CLIMB.animationName();
    public static final String CLIMBING = PlayerActionState.CLIMBING.animationName();
    public static final String LADDER_UP = PlayerActionState.LADDER_UP.animationName();
    public static final String LADDER_STILLNESS =
            PlayerActionState.LADDER_STILLNESS.animationName();
    public static final String LADDER_DOWN = PlayerActionState.LADDER_DOWN.animationName();
    public static final String FLY = PlayerActionState.FLY.animationName();
    public static final String ELYTRA_FLY = PlayerActionState.ELYTRA_FLY.animationName();
    public static final String SWIM_STAND = PlayerActionState.SWIM_STAND.animationName();
    public static final String ATTACKED = PlayerActionState.ATTACKED.animationName();
    public static final String JUMP = PlayerActionState.JUMP.animationName();
    public static final String SNEAK = PlayerActionState.SNEAK.animationName();
    public static final String SNEAKING = PlayerActionState.SNEAKING.animationName();
    public static final String RUN = PlayerActionState.RUN.animationName();
    public static final String WALK = PlayerActionState.WALK.animationName();
    public static final String IDLE = PlayerActionState.IDLE.animationName();

    public static final float MIN_MOVEMENT_SPEED = 0.05f;
    private static final float LADDER_STILLNESS_SPEED = 0.01f;

    public static PlayerActionState resolveState(PlayerActionSnapshot state) {
        if (state.riding()) {
            return PlayerActionState.RIDE;
        }
        if (state.deadOrDying()) {
            return PlayerActionState.DEATH;
        }
        if (state.riptide()) {
            return PlayerActionState.RIPTIDE;
        }
        if (state.sleeping()) {
            return PlayerActionState.SLEEP;
        }
        if (state.swimming()) {
            return PlayerActionState.SWIM;
        }
        if (state.swimmingPose() && state.moving()) {
            return PlayerActionState.CLIMB;
        }
        if (state.swimmingPose()) {
            return PlayerActionState.CLIMBING;
        }
        if (state.onClimbable()) {
            if (state.verticalSpeed() > LADDER_STILLNESS_SPEED) {
                return PlayerActionState.LADDER_UP;
            }
            if (state.verticalSpeed() < -LADDER_STILLNESS_SPEED) {
                return PlayerActionState.LADDER_DOWN;
            }
            return PlayerActionState.LADDER_STILLNESS;
        }
        if (state.elytraFlying()) {
            return PlayerActionState.ELYTRA_FLY;
        }
        if (state.flying()) {
            return PlayerActionState.FLY;
        }
        if (state.inWater() && !state.onGround()) {
            return PlayerActionState.SWIM_STAND;
        }
        if (state.attacked()) {
            return PlayerActionState.ATTACKED;
        }
        if (!state.onGround() && !state.inWater()) {
            return PlayerActionState.JUMP;
        }
        if (state.onGround() && state.crouching() && state.moving()) {
            return PlayerActionState.SNEAK;
        }
        if (state.onGround() && state.crouching()) {
            return PlayerActionState.SNEAKING;
        }
        if (state.onGround() && state.sprinting() && state.moving()) {
            return PlayerActionState.RUN;
        }
        if (state.onGround() && state.moving()) {
            return PlayerActionState.WALK;
        }
        return PlayerActionState.IDLE;
    }
}
