package com.blanoir.moons.ysm.internal.runtime;

import com.blanoir.moons.ysm.internal.geckolib3.core.builder.Animation;
import com.blanoir.moons.ysm.internal.geckolib3.core.event.ParticleEventKeyFrame;
import com.blanoir.moons.ysm.internal.geckolib3.core.keyframe.BoneAnimation;
import com.blanoir.moons.ysm.internal.geckolib3.core.keyframe.event.EventKeyFrame;

import java.util.*;

/** Local portion of upstream ModelAssemblyFactory: weapon aliases and derived arm tracks. */
final class LocalAnimationAssembly {
    private static final Map<String, String> ALIASES =
            Map.ofEntries(
                    Map.entry("lance_stand", "hold_mainhand:lance"),
                    Map.entry("lance_jab", "swing:lance"),
                    Map.entry("lance_lunge", "swing:lance"),
                    Map.entry("lance_charge", "use_mainhand:lance"),
                    Map.entry("lance_riding_idle", "hold_mainhand:lance"),
                    Map.entry("lance_riding_charge", "use_mainhand:lance"),
                    Map.entry("lance_fall_flying_charge", "use_mainhand:lance"),
                    Map.entry("hold_mainhand:mace", "hold_mainhand$minecraft:mace"),
                    Map.entry("hold_offhand:mace", "hold_offhand$minecraft:mace"),
                    Map.entry("swing:mace", "swing$minecraft:mace"));
    private static final List<String> ARM_ANIMATIONS =
            List.of(
                    "hold_mainhand:lance",
                    "hold_offhand:lance",
                    "swing:lance",
                    "use_mainhand:lance",
                    "use_offhand:lance",
                    "lance_stand",
                    "lance_jab",
                    "lance_lunge",
                    "lance_charge",
                    "lance_riding_idle",
                    "lance_riding_charge",
                    "lance_fall_flying_charge");

    static void aliases(Map<String, Animation> animations, Map<String, Integer> types) {
        ALIASES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            Animation source = animations.get(entry.getValue());
                            if (source != null) {
                                animations.putIfAbsent(entry.getKey(), source);
                                types.putIfAbsent(
                                        entry.getKey(), types.getOrDefault(entry.getValue(), 2));
                            }
                        });
    }

    static void deriveArms(
            Map<String, Animation> body, Map<String, Animation> arms, Map<String, Integer> types) {
        aliases(body, new HashMap<>());
        for (String name : ARM_ANIMATIONS) {
            Animation authored = arms.get(name);
            if (authored != null && !authored.isEmpty()) continue;
            Animation derived = derive(name, body.get(name));
            if (derived != null) {
                arms.put(name, derived);
                types.put(name, 11);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Animation derive(String name, Animation source) {
        if (source == null || source.isEmpty()) return null;
        Map<String, BoneAnimation> bones = new LinkedHashMap<>();
        for (BoneAnimation bone : source.boneAnimations) {
            String target = target(bone.boneName);
            if (target == null) continue;
            if (target.equals(bone.boneName)) bones.put(target, bone);
            else
                bones.putIfAbsent(
                        target,
                        new BoneAnimation(
                                target,
                                bone.rotationKeyFrames,
                                bone.positionKeyFrames,
                                bone.scaleKeyFrames));
        }
        if (bones.isEmpty()) return null;
        // The body owns sounds, particles and timeline side effects; arm derivation copies motion
        // only.
        Animation derived =
                new Animation(
                        name,
                        source.animationLength,
                        source.loop,
                        source.unKnowData1,
                        source.unKnowData2,
                        source.blendWeight,
                        source.override,
                        bones.values().toArray(new BoneAnimation[0]),
                        new EventKeyFrame[0],
                        new ParticleEventKeyFrame[0],
                        new EventKeyFrame[0]);
        derived.sourceKey = source.sourceKey;
        derived.isFromPrimaryAssembly = source.isFromPrimaryAssembly;
        return derived;
    }

    private static String target(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        String side =
                normalized.startsWith("left")
                        ? "Left"
                        : normalized.startsWith("right") ? "Right" : null;
        if (side == null) return null;
        return switch (normalized.substring(side.length())) {
            case "arm", "upperarm", "uparm", "shoulder", "bicep" -> side + "Arm";
            case "forearm", "lowerarm", "elbow" -> side + "ForeArm";
            case "hand", "wrist", "palm" -> side + "Hand";
            case "handlocator", "item" -> side + "HandLocator";
            default -> null;
        };
    }
}
