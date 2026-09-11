package com.blanoir.moons.client.module.impl.render;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;

import org.joml.Quaternionf;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/** Purely visual replacement for the first-person attack swing. */
public final class Animations {
    private static final float PI = 3.1415927F;

    /**
     * Minecraft 1.8 scaled the item model by 0.4 inside
     * transformFirstPersonItem. Modern FIRST_PERSON_* model submission owns
     * that model scale instead. Undo the preset scale after all legacy transforms
     * so the result matches the native modern BLOCK branch.
     */
    private static final float MODERN_BLOCK_MODEL_SCALE = 2.5F;

    /** Matches the normal first-person swing window without creating item-use state. */
    private static final long ATTACK_ANIMATION_NANOS = 300_000_000L;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("blockanimation.enabled").defaultValue(false).build();
    private static final BooleanSetting COMBAT_ONLY =
            new BooleanSetting.Builder()
                    .name("blockanimation.silentAuraOnly")
                    .defaultValue(false)
                    .build();
    private static final ModeSetting<Mode> MODE = animationModes();
    private static final DoubleSetting SWING_SPEED =
            number("blockanimation.swingSpeed", 1.0, .1, 2.0);
    private static final DoubleSetting OFFSET_X = number("blockanimation.offsetX", 0, -5, 5);
    private static final DoubleSetting OFFSET_Y = number("blockanimation.offsetY", 0, -5, 5);
    private static final DoubleSetting OFFSET_Z = number("blockanimation.offsetZ", 0, -5, 5);
    private static final DoubleSetting SCALE = number("blockanimation.scale", 1, .1, 2);
    private static final DoubleSetting ITEM_SIZE = number("blockanimation.itemSize", 0, -.5, .5);
    private static final DoubleSetting ITEM_ROTATION_X =
            number("blockanimation.itemRotationX", 0, -180, 180);
    private static final DoubleSetting ITEM_ROTATION_Y =
            number("blockanimation.itemRotationY", 0, -180, 180);
    private static final DoubleSetting ITEM_ROTATION_Z =
            number("blockanimation.itemRotationZ", 0, -180, 180);

    private static float astolfoSpin;
    private static float spin;
    private static long lastSpinUpdate = System.currentTimeMillis();
    private static volatile long attackStartedNanos = Long.MIN_VALUE;

    private static BooleanSupplier combatEnabled = () -> false;
    private static Predicate<Minecraft> combatRenderCheck = client -> false;
    private static BooleanSupplier combatAttackOnly = () -> false;
    private static DoubleSupplier combatSwingProgress = () -> 0;

    /** The transformer asks separately whether vanilla swing transforms should be skipped. */
    private static final ThreadLocal<Boolean> REPLACE_CURRENT_RENDER =
            ThreadLocal.withInitial(() -> false);

    private Animations() {}

    /** Submit the already extracted hand item; 26.3 rendering no longer takes a live player. */
    public static boolean submit(Object[] args) {
        if (args.length != 11) return false;
        var state =
                (net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState)
                        args[1];
        var playerState = (net.minecraft.client.renderer.state.level.PlayerRenderState) args[0];
        if (state.isScoping || playerState.avatarRenderState == null) return false;
        var hand = (InteractionHand) args[4];
        var pose = (PoseStack) args[8];
        pose.pushPose();
        try {
            apply(pose, ((Number) args[5]).floatValue(), ((Number) args[7]).floatValue(), hand);
            if (!shouldReplaceVanilla(hand)) return false;
            var item =
                    hand == InteractionHand.MAIN_HAND
                            ? state.mainHandRenderState
                            : state.offHandRenderState;
            item.submit(
                    pose,
                    (net.minecraft.client.renderer.SubmitNodeCollector) args[9],
                    (Integer) args[10],
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    0);
            return true;
        } finally {
            endRender();
            pose.popPose();
        }
    }

    public static void bindCombatState(
            BooleanSupplier enabled,
            Predicate<Minecraft> renderCheck,
            BooleanSupplier attackOnly,
            DoubleSupplier swingProgress) {
        combatEnabled = enabled;
        combatRenderCheck = renderCheck;
        combatAttackOnly = attackOnly;
        combatSwingProgress = swingProgress;
    }

    /**
     * Replays the legacy renderer's visual transform order: the selected preset owns
     * transformFirstPersonItem and its custom matrices, then the common 1.8
     * doBlockTransformations suffix is applied. No use state or packets exist.
     */
    public static void apply(
            PoseStack pose, float vanillaSwingProgress, float equipProgress, Object renderedHand) {
        float swingProgress = visualSwingProgress(vanillaSwingProgress);
        boolean replace = shouldAnimate(renderedHand, swingProgress);
        REPLACE_CURRENT_RENDER.set(replace);
        if (!replace || pose == null) {
            return;
        }

        // ItemInHandRenderer raises equipProgress while its high-version hand
        // state settles. Feeding that value into the old first-person transform
        // lowers the sword instead of producing a block-hit. AutoBlock is
        // a held visual pose, so keep the item fully equipped and let only the
        // swing curve move it.
        if (combatBlocking(Minecraft.getInstance())) {
            equipProgress = 0.0F;
        }

        // AutoBlock works on its own; Animations adds the configured visual preset.
        if (!ENABLED.get()) {
            firstPerson(pose, equipProgress, swingProgress);
            block(pose);
            rotate(pose, -45.0F, 0.0F, 1.0F, 0.0F);
            pose.scale(
                    MODERN_BLOCK_MODEL_SCALE, MODERN_BLOCK_MODEL_SCALE, MODERN_BLOCK_MODEL_SCALE);
            return;
        }

        float progress = Math.clamp(swingProgress * (float) SWING_SPEED.get(), 0.0F, 1.0F);
        float sine = sinSqrt(progress);
        float sqrtSwing = (float) Math.sqrt(progress);
        float sine1 = (float) Math.sin(progress * progress * PI);

        pose.translate(OFFSET_X.get(), OFFSET_Y.get(), OFFSET_Z.get());
        float scale = (float) (SCALE.get() * (1.0 + ITEM_SIZE.get()));
        pose.scale(scale, scale, scale);

        Mode mode = MODE.get();
        switch (mode) {
            case VANILLA -> {
                pose.translate(0.0, 0.05, -0.1);
                firstPerson(pose, equipProgress, progress);
            }
            case EXHIBITION -> {
                pose.translate(0.0, -0.1, 0.0);
                firstPerson(pose, equipProgress / 2.0F, 0.0F);
                pose.translate(0.1, 0.4, -0.1);
                rotate(pose, -sine * 30.0F, sine / 2.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 50.0F, 0.8F, sine / 2.0F, 0.0F);
            }
            case ETB -> {
                pose.translate(0.0, -0.1, 0.0);
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.1, 0.4, -0.1);
                rotate(pose, -sine * 35.0F, -8.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 70.0F, 1.5F, -0.4F, 0.0F);
            }
            case SIGMA -> {
                firstPerson(pose, equipProgress * 0.5F, 0.0F);
                rotate(pose, -sine * 27.5F, -8.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 45.0F, 1.0F, sine / 2.0F, 0.0F);
                pose.translate(-0.1, 0.3, 0.1);
            }
            case DORTWARE -> {
                float alt = (float) Math.sin(sqrtSwing * PI - 3.0F);
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine * 10.0F, 0.0F, 15.0F, 200.0F);
                rotate(pose, -sine * 10.0F, 300.0F, sine / 2.0F, 1.0F);
                pose.translate(3.4, 0.3, -0.4);
                pose.translate(-2.1, -0.2, 0.1);
                rotate(pose, alt * 13.0F, -10.0F, -1.4F, -10.0F);
            }
            case PLAIN -> {
                pose.translate(0.0, 0.05, 0.0);
                firstPerson(pose, equipProgress, 0.0F);
            }
            case SPIN -> {
                rotate(pose, spin, 0.0F, 0.0F, -0.1F);
                firstPerson(pose, equipProgress, 0.0F);
                spin = -(System.currentTimeMillis() / 2L % 360L);
            }
            case AVATAR -> avatar(pose, progress);
            case SWONG -> {
                firstPerson(pose, equipProgress / 2.0F, 0.0F);
                rotate(pose, -sine * 20.0F, sine / 2.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 30.0F, 1.0F, sine / 2.0F, 0.0F);
            }
            case SWANG -> {
                firstPerson(pose, equipProgress / 2.0F, progress);
                rotate(pose, sine * 15.0F, -sine, 0.0F, 9.0F);
                rotate(pose, sine * 40.0F, 1.0F, -sine / 2.0F, 0.0F);
            }
            case SWANK -> {
                firstPerson(pose, equipProgress / 2.0F, progress);
                rotate(pose, sine * 30.0F, -sine, 0.0F, 9.0F);
                rotate(pose, sine * 40.0F, 1.0F, -sine, 0.0F);
            }
            case STYLES -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(-0.05, 0.2, 0.0);
                rotate(pose, -sine * 35.0F, -8.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 70.0F, 1.0F, -0.4F, 0.0F);
            }
            case NUDGE -> {
                pose.translate(-0.1, 0.09, 0.0);
                firstPerson(pose, 0.0F, 1.0F);
                float ns1 = (float) Math.sin(sqrtSwing * 3.0F);
                float ns2 = (float) Math.sin(sqrtSwing * 4.9415927F);
                rotate(pose, -ns1 * 60.0F, -90.0F, -ns2, 10.0F);
                rotate(pose, -ns1 * 110.0F, 15.0F, ns2, 0.0F);
            }
            case PUNCH -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.1, 0.2, 0.3);
                rotate(pose, -sine * 30.0F, -5.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 10.0F, 1.0F, -0.4F, -0.5F);
            }
            case JIGSAW -> {
                pose.translate(0.56, -0.42, -0.72);
                pose.translate(0.1F * sine, 0.0F, -0.22F * sine);
                pose.translate(0.0F, sine1 * -0.15F, 0.0F);
                rotate(pose, sine1 * 45.0F, 0.0F, 1.0F, 0.0F);
                rotate(pose, sine1 * -20.0F, 0.0F, 1.0F, 0.0F);
                rotate(pose, sine * -20.0F, 0.0F, 0.0F, 1.0F);
                rotate(pose, sine * -80.0F, 1.0F, 0.0F, 0.0F);
            }
            case SLIDE -> {
                pose.translate(-0.1, 0.15, 0.0);
                firstPerson(pose, 0.0F, 0.0F);
                float slide = (float) Math.sin(sqrtSwing * 2.9415927F);
                pose.translate(-0.05, 0.0, 0.35);
                rotate(pose, -slide * 30.0F, -15.0F, slide, 10.0F);
                rotate(pose, -slide * 70.0F, 5.0F, -slide, 0.0F);
            }
            case SWING -> {
                firstPerson(pose, equipProgress, 0.0F);
                block(pose);
            }
            case OLD -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.08, -0.14, -0.05);
                pose.translate(-0.35, 0.2, 0.0);
                block(pose);
            }
            case PUSH -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine * 20.0F, sine / 2.0F, 1.0F, 4.0F);
                rotate(pose, -sine * 30.0F, 1.0F, sine / 3.0F, 0.0F);
            }
            case DASH -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine * 22.0F, sine / 2.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 50.0F, 0.8F, sine / 2.0F, 0.0F);
            }
            case SLASH -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.08, 0.08, 0.0);
                rotate(pose, -sine * 70.0F, 5.0F, 13.0F, 50.0F);
            }
            case SCALE -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.84, -0.77, -1.1);
                pose.translate(0.56, -0.52, -0.71999997);
                rotate(pose, 45.0F, 0.0F, 1.0F, 0.0F);
            }
            case SWONK -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.0, 0.03, 0.0);
                rotate(pose, sine * 15.0F, sine / 2.0F, 1.0F, 4.0F);
                rotate(pose, -sine * 7.5F, 1.0F, sine / 3.0F, 0.0F);
            }
            case STELLA -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(-0.5, 0.3, -0.2);
                rotate(pose, 32.0F, 0.0F, 1.0F, 0.0F);
                rotate(pose, -70.0F, 1.0F, 0.0F, 0.0F);
                rotate(pose, 40.0F, 0.0F, 1.0F, 0.0F);
                block(pose);
            }
            case SMALL -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(-0.01, 0.03, -0.24);
                block(pose);
            }
            case EDIT -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(-0.04, 0.06, 0.0);
                rotate(pose, sine * 8.0F, -sine, 0.0F, 2.0F);
                rotate(pose, sine * 22.0F, 1.0F, -sine / 3.0F, 0.0F);
            }
            case RHYS -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.0, 0.19, 0.0);
                pose.translate(0.41, -0.25, -0.5555557);
                rotate(pose, 35.0F, 0.0F, 1.5F, 0.0F);
                float slow = (float) Math.sin(progress * progress / 64.0F * PI);
                rotate(pose, slow * -5.0F, 0.0F, 0.0F, 0.0F);
                rotate(pose, sine * -12.0F, 0.0F, 0.0F, 1.0F);
                rotate(pose, sine * -65.0F, 1.0F, 0.0F, 0.0F);
            }
            case STAB -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(-0.25, 0.45, 0.8);
                pose.translate(0.6, 0.3, -0.6F - sine * 0.7F);
                rotate(pose, 6090.0F, 0.0F, 0.0F, 0.1F);
                rotate(pose, 6085.0F, 0.0F, 0.1F, 0.0F);
                rotate(pose, 6110.0F, 0.1F, 0.0F, 0.0F);
            }
            case FLOAT -> {
                firstPerson(pose, equipProgress, 0.0F);
                float quadratic = (float) Math.sin(progress * progress * PI);
                rotate(pose, -quadratic * 20.0F, quadratic / 2.0F, 0.0F, 9.0F);
                rotate(pose, -quadratic * 30.0F, 1.0F, quadratic / 2.0F, 0.0F);
            }
            case REMIX -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine * 25.0F, 0.5F, 0.0F, 1.0F);
            }
            case XIV -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine1 * 20.0F, 0.0F, 1.0F, 0.0F);
                rotate(pose, -sine * 20.0F, 0.0F, 0.0F, 1.0F);
                rotate(pose, -sine * 80.0F, 1.0F, 0.0F, 0.0F);
            }
            case WINTER -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.0, -0.16, 0.0);
                pose.translate(-0.35, 0.1, 0.0);
                pose.translate(-0.05, -0.1, 0.1);
                block(pose);
            }
            case YAMATO -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine * 100.0F, -9.0F, 5.0F, 9.0F);
            }
            case SLIDE_SWING -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.56, -0.52, -0.72);
                rotate(pose, 45.0F, 0.0F, 1.0F, 0.0F);
                rotate(pose, sine * -80.0F, 1.0F, 0.0F, 0.0F);
                pose.scale(0.4F, 0.4F, 0.4F);
                block(pose);
            }
            case SMALL_PUSH -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.56, -0.52, -0.72);
                rotate(pose, 45.0F, 0.0F, 1.0F, 0.0F);
                rotate(pose, sine1 * -10.0F, 1.0F, 1.0F, 1.0F);
                rotate(pose, sine * -10.0F, 1.0F, 1.0F, 1.0F);
                rotate(pose, sine * -10.0F, 1.0F, 1.0F, 1.0F);
                pose.scale(0.4F, 0.4F, 0.4F);
                block(pose);
            }
            case REVERSE -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.0, 0.1, -0.12);
                pose.translate(0.08, -0.1, -0.3);
                block(pose);
            }
            case INVENT -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine * 30.0F, -8.0F, -0.2F, 9.0F);
            }
            case LEAKED -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.08, 0.02, 0.0);
                rotate(pose, -sine * 41.0F, 1.1F, 0.8F, -0.3F);
            }
            case AQUA -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine * 8.5F, sine / 2.0F, 1.0F, 4.0F);
                rotate(pose, -sine * 6.0F, 1.0F, sine / 3.0F, 0.0F);
            }
            case ASTRO -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, sine * 50.0F / 9.0F, -sine, 0.0F, 90.0F);
                rotate(pose, sine * 50.0F, 200.0F, -sine / 2.0F, 0.0F);
            }
            case FADEAWAY -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine1 * 45.0F, 0.0F, 0.0F, 1.0F);
            }
            case ASTOLFO -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, -sine * 29.0F, sine / 2.0F, 1.0F, 0.5F);
                rotate(pose, -sine * 43.0F, 1.0F, sine / 3.0F, 0.0F);
            }
            case ASTOLFO_SPIN -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, astolfoSpin, 0.0F, 0.0F, -0.1F);
                updateAstolfoSpin();
                block(pose);
            }
            case MOON -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(-0.08, 0.12, 0.0);
                rotate(pose, -sine * 32.5F, sine / 2.0F, 1.0F, 4.0F);
                rotate(pose, -sine * 60.0F, 1.0F, sine / 3.0F, 0.0F);
            }
            case MOON_PUSH -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(-0.2, 0.45, 0.25);
                rotate(pose, -sine * 20.0F, -5.0F, -5.0F, 9.0F);
            }
            case SMOOTH -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.14, -0.1, -0.24);
                pose.translate(-0.36, 0.25, -0.06);
                rotate(pose, -sine * 35.0F, -8.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 70.0F, 1.0F, 0.4F, 0.0F);
            }
            case TAP1 -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.56, -0.52, -0.71999997);
                rotate(pose, 45.0F, 0.0F, 1.0F, 0.0F);
                rotate(
                        pose,
                        (progress * 0.8F - progress * progress * 0.8F) * -90.0F,
                        0.0F,
                        1.0F,
                        0.0F);
                pose.scale(0.37F, 0.37F, 0.37F);
            }
            case TAP2 -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.0, -0.1, 0.0);
                pose.translate(0.56, -0.42, -0.71999997);
                rotate(pose, 30.0F, 0.0F, 1.0F, 0.0F);
                rotate(pose, sine * -30.0F, 0.0F, 1.0F, 0.0F);
                pose.scale(0.4F, 0.4F, 0.4F);
            }
            case SIGMA3 -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.02, 0.02, 0.0);
                pose.translate(0.4, -0.06, -0.46);
                rotate(pose, sine * 12.5F, -sine, 0.0F, 9.0F);
                rotate(pose, sine * 15.0F, 1.0F, -sine / 2.0F, 0.0F);
            }
            case SIGMA4 -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(-0.6, 0.2, 0.11);
                rotate(pose, -sine * 27.5F, -8.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 45.0F, 1.0F, sine / 2.0F, 0.0F);
                block(pose);
                pose.translate(-0.08, -1.25, 1.25);
            }
            case MYAU_1_8 -> firstPerson(pose, equipProgress, 0.0F);
            case MYAU_SLIDE -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.08, -0.11, -0.07);
                pose.translate(-0.4, 0.28, 0.0);
                rotate(pose, -sine * 35.0F, -8.0F, 0.0F, 9.0F);
                rotate(pose, -sine * 70.0F, 1.0F, -0.4F, 0.0F);
            }
            case MYAU_SWANK -> {
                firstPerson(pose, equipProgress, 0.0F);
                rotate(pose, sine * 15.0F, -sine, 0.0F, 9.0F);
                rotate(pose, sine * 40.0F, 1.0F, -sine / 2.0F, 0.0F);
            }
            case MYAU_SWANG -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.0, 0.03, 0.0);
                rotate(pose, -sine * 37.0F, sine / 2.0F, 1.0F, 4.0F);
                rotate(pose, -sine * 52.0F, 1.0F, sine / 3.0F, 0.0F);
            }
            case MYAU_AVATAR -> {
                firstPerson(pose, equipProgress, 0.0F);
                avatar(pose, progress);
                block(pose);
            }
            case MYAU_JIGSAW -> {
                firstPerson(pose, equipProgress, 0.0F);
                pose.translate(0.0, -0.18, -0.1);
                pose.translate(-0.5, 0.0, 0.0);
                block(pose);
            }
        }

        // Apply the shared blocking transform after every preset, matching
        // the legacy renderer's final doBlockTransformations invocation.
        block(pose);

        // Coordinate-system bridge for modern ItemInHandRenderer. At zero
        // swing this converts firstPerson + doBlockTransformations exactly to
        // the native BLOCK transform used by 26.1/26.2:
        // X=-102.25, Y=side*13.365, Z=side*78.05. It is visual only.
        rotate(pose, -45.0F, 0.0F, 1.0F, 0.0F);
        pose.scale(MODERN_BLOCK_MODEL_SCALE, MODERN_BLOCK_MODEL_SCALE, MODERN_BLOCK_MODEL_SCALE);

        // User rotation is deliberately applied after the selected preset so
        // it rotates the rendered item itself instead of changing the preset's
        // translation axes. X tilts vertically, Y turns it sideways and the
        // Z rotation provides the screen-plane Z roll. This
        // is render-only and never changes combat yaw.
        rotate(pose, (float) ITEM_ROTATION_X.get(), 1.0F, 0.0F, 0.0F);
        rotate(pose, (float) ITEM_ROTATION_Y.get(), 0.0F, 1.0F, 0.0F);
        rotate(pose, (float) ITEM_ROTATION_Z.get(), 0.0F, 0.0F, 1.0F);
    }

    public static boolean shouldReplaceVanilla(Object renderedHand) {
        return Boolean.TRUE.equals(REPLACE_CURRENT_RENDER.get())
                && renderedHand == InteractionHand.MAIN_HAND;
    }

    /** Applies the visual-only block arm pose to the local third-person model. */
    public static void applyThirdPerson(Avatar avatar, AvatarRenderState state) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer == null
                || avatar == null
                || state == null
                || avatar.getId() != currentPlayer.getId()
                || state.getMainHandItemStack().isEmpty()
                || !state.getMainHandItemStack().is(ItemTags.SWORDS)) {
            return;
        }

        boolean active =
                combatBlocking(client)
                        || !combatAttackOnly.getAsBoolean() && ENABLED.get() && !COMBAT_ONLY.get();
        if (!active) return;

        if (state.mainArm == HumanoidArm.LEFT) {
            state.leftArmPose = HumanoidModel.ArmPose.BLOCK;
        } else {
            state.rightArmPose = HumanoidModel.ArmPose.BLOCK;
        }
    }

    /** Called by the common attack entry for both physical and SilentAura attacks. */
    public static void onAttack() {
        if (renderingEnabled()) attackStartedNanos = System.nanoTime();
    }

    /** Ends the transformer-scoped render decision so it cannot leak into the next hand. */
    public static void endRender() {
        REPLACE_CURRENT_RENDER.remove();
    }

    private static float visualSwingProgress(float vanillaSwingProgress) {
        if (combatAttackOnly.getAsBoolean()) return (float) combatSwingProgress.getAsDouble();
        long started = attackStartedNanos;
        long elapsed = started == Long.MIN_VALUE ? -1L : System.nanoTime() - started;
        float explicit =
                elapsed >= 0L && elapsed < ATTACK_ANIMATION_NANOS
                        ? (float) elapsed / (float) ATTACK_ANIMATION_NANOS
                        : 0.0F;
        return Math.max(Math.clamp(vanillaSwingProgress, 0.0F, 1.0F), explicit);
    }

    private static boolean shouldAnimate(Object renderedHand, float swingProgress) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        boolean mainHandSword =
                currentPlayer != null && currentPlayer.getMainHandItem().is(ItemTags.SWORDS);
        return renderedHand == InteractionHand.MAIN_HAND
                && currentPlayer != null
                && (combatBlocking(client)
                        || !combatAttackOnly.getAsBoolean()
                                && ENABLED.get()
                                && !COMBAT_ONLY.get()
                                && (mainHandSword || swingProgress > 0.0001F));
    }

    private static boolean combatBlocking(Minecraft client) {
        return combatEnabled.getAsBoolean() && combatRenderCheck.test(client);
    }

    public static boolean renderingEnabled() {
        return ENABLED.get() || combatEnabled.getAsBoolean();
    }

    private static void firstPerson(PoseStack pose, float equipProgress, float swingProgress) {
        pose.translate(0.56, -0.52, -0.72);
        pose.translate(0.0, equipProgress * -0.6F, 0.0);
        rotate(pose, 45.0F, 0.0F, 1.0F, 0.0F);
        float sine1 = (float) Math.sin(swingProgress * swingProgress * PI);
        float sine = sinSqrt(swingProgress);
        rotate(pose, sine1 * -20.0F, 0.0F, 1.0F, 0.0F);
        rotate(pose, sine * -20.0F, 0.0F, 0.0F, 1.0F);
        rotate(pose, sine * -80.0F, 1.0F, 0.0F, 0.0F);
        pose.scale(0.4F, 0.4F, 0.4F);
    }

    private static void block(PoseStack pose) {
        pose.translate(-0.5, 0.2, 0.0);
        rotate(pose, 30.0F, 0.0F, 1.0F, 0.0F);
        rotate(pose, -80.0F, 1.0F, 0.0F, 0.0F);
        rotate(pose, 60.0F, 0.0F, 1.0F, 0.0F);
    }

    private static void avatar(PoseStack pose, float progress) {
        float sine1 = (float) Math.sin(progress * progress * PI);
        float sine = sinSqrt(progress);
        pose.translate(0.56, -0.52, -0.72);
        rotate(pose, 45.0F, 0.0F, 1.0F, 0.0F);
        rotate(pose, sine1 * -20.0F, 0.0F, 1.0F, 0.0F);
        rotate(pose, sine * -20.0F, 0.0F, 0.0F, 1.0F);
        rotate(pose, sine * -40.0F, 1.0F, 0.0F, 0.0F);
        pose.scale(0.4F, 0.4F, 0.4F);
    }

    private static float sinSqrt(float progress) {
        return (float) Math.sin(Math.sqrt(progress) * PI);
    }

    private static void rotate(PoseStack pose, float degrees, float x, float y, float z) {
        float lengthSquared = x * x + y * y + z * z;
        if (degrees == 0.0F || lengthSquared < 1.0E-8F) {
            return;
        }
        float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
        pose.rotate(
                new Quaternionf()
                        .rotationAxis(
                                (float) Math.toRadians(degrees),
                                x * inverseLength,
                                y * inverseLength,
                                z * inverseLength));
    }

    private static void updateAstolfoSpin() {
        long now = System.currentTimeMillis();
        astolfoSpin += (now - lastSpinUpdate) * 360.0F / 850.0F;
        lastSpinUpdate = now;
        if (astolfoSpin > 360.0F) {
            astolfoSpin = 0.0F;
        }
    }

    public static String modeName() {
        return MODE.serialized();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        ClientChat.send(client, "BlockAnimation " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setSilentAuraOnly(Minecraft ignoredClient, boolean value) {
        COMBAT_ONLY.set(value);
        return 1;
    }

    public static int setMode(Minecraft ignoredClient, String value) {
        MODE.deserialize(value);
        return 1;
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static int setSwingSpeed(Minecraft ignoredClient, double value) {
        SWING_SPEED.set(value);
        return 1;
    }

    public static int setOffsetX(Minecraft ignoredClient, double value) {
        OFFSET_X.set(value);
        return 1;
    }

    public static int setOffsetY(Minecraft ignoredClient, double value) {
        OFFSET_Y.set(value);
        return 1;
    }

    public static int setOffsetZ(Minecraft ignoredClient, double value) {
        OFFSET_Z.set(value);
        return 1;
    }

    public static int setScale(Minecraft ignoredClient, double value) {
        SCALE.set(value);
        return 1;
    }

    public static int setItemSize(Minecraft ignoredClient, double value) {
        ITEM_SIZE.set(value);
        return 1;
    }

    public static int setItemRotationX(Minecraft ignoredClient, double value) {
        ITEM_ROTATION_X.set(value);
        return 1;
    }

    public static int setItemRotationY(Minecraft ignoredClient, double value) {
        ITEM_ROTATION_Y.set(value);
        return 1;
    }

    public static int setItemRotationZ(Minecraft ignoredClient, double value) {
        ITEM_ROTATION_Z.set(value);
        return 1;
    }

    private static ModeSetting<Mode> animationModes() {
        ModeSetting.Builder<Mode> modes =
                new ModeSetting.Builder<Mode>()
                        .name("blockanimation.mode")
                        .defaultValue(Mode.MYAU_1_8);
        for (Mode mode : Mode.values()) {
            if (mode == Mode.MYAU_1_8) {
                modes.option(mode, "1.8");
            } else {
                modes.option(mode, mode.configName());
            }
        }
        return modes.build();
    }

    private static DoubleSetting number(String name, double value, double min, double max) {
        return new DoubleSetting.Builder().name(name).defaultValue(value).range(min, max).build();
    }

    private enum Mode {
        VANILLA,
        EXHIBITION,
        ETB,
        SIGMA,
        DORTWARE,
        PLAIN,
        SPIN,
        AVATAR,
        SWONG,
        SWANG,
        SWANK,
        STYLES,
        NUDGE,
        PUNCH,
        JIGSAW,
        SLIDE,
        SWING,
        OLD,
        PUSH,
        DASH,
        SLASH,
        SCALE,
        SWONK,
        STELLA,
        SMALL,
        EDIT,
        RHYS,
        STAB,
        FLOAT,
        REMIX,
        XIV,
        WINTER,
        YAMATO,
        SLIDE_SWING,
        SMALL_PUSH,
        REVERSE,
        INVENT,
        LEAKED,
        AQUA,
        ASTRO,
        FADEAWAY,
        ASTOLFO,
        ASTOLFO_SPIN,
        MOON,
        MOON_PUSH,
        SMOOTH,
        TAP1,
        TAP2,
        SIGMA3,
        SIGMA4,
        MYAU_1_8,
        MYAU_SLIDE,
        MYAU_SWANK,
        MYAU_SWANG,
        MYAU_AVATAR,
        MYAU_JIGSAW;

        String configName() {
            if (name().startsWith("MYAU_")) {
                return "myau-" + name().substring(5).toLowerCase(Locale.ROOT).replace('_', '-');
            }
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}
