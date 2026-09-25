package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel.*;

import java.util.*;

/** Upstream assembly and query contracts, including overlapping author definitions. */
final class YsmPortParityVerification {
    static void verify() {
        var raw = new RawYsmModel();
        raw.formatVersion = 32;
        raw.mainEntity.mainModel = new RawGeometry();
        var root = new RawBone();
        root.name = "RightArm";
        raw.mainEntity.mainModel.bones.add(root);
        raw.mainEntity.animationFiles.put("main", animations(1, 1));
        raw.mainEntity.animationFiles.put("extra", animations(3, 2));
        try (var model = new LocalYsmModel(raw);
                var arms = model.firstPersonModel()) {
            equal(
                    40f,
                    model.runtime().getAnimation("idle").animationLength,
                    "Later body definition");
            equal(3, model.runtime().animationTypes().get("idle"), "Overridden animation category");
            equal(
                    40f,
                    arms.runtime().getAnimation("lance_stand").animationLength,
                    "Derived arms use final body definition");
            model.frame(
                    0,
                    Map.of(
                            "is_riding",
                            true,
                            "vehicle_id",
                            "minecraft:pig",
                            "vehicle_tags",
                            List.of("minecraft:boat"),
                            "head_item",
                            "minecraft:iron_helmet",
                            "head_tags",
                            List.of("minecraft:head_armor"),
                            "mainhand_item",
                            "minecraft:stone"),
                    "");
            for (String expression :
                    List.of(
                            "ctrl.ride('vehicle','$pig')",
                            "ctrl.ride('vehicle','$minecraft:pig')",
                            "ctrl.ride('vehicle','$:pig')",
                            "ctrl.ride('vehicle','#boat')",
                            "ctrl.armor('head','#head_armor')"))
                equal(true, model.runtime().evaluate(expression), expression);
            for (String expression :
                    List.of(
                            "ctrl.ride('vehicle','$other:pig')",
                            "ctrl.ride('vehicle','#other:boat')",
                            "ctrl.ride('vehicle','#Invalid Tag')",
                            "ctrl.armor('head','#bad:tag:extra')",
                            "ctrl.ride('vehicle','$MineCraft:pig')",
                            "ctrl.ride('vehicle','$pig:invalid:extra')",
                            "ctrl.ride('passenger','$pig')",
                            // Upstream hold/armor IDs are literal; only their tags use identifier
                            // parsing.
                            "ctrl.hold('mainhand','$stone')"))
                equal(false, model.runtime().evaluate(expression), expression);
        }
        raw.mainEntity.animationFiles.put("first_person_a", animations(11, 3));
        raw.mainEntity.animationFiles.put("first_person_b", animations(11, 4));
        try (var model = new LocalYsmModel(raw);
                var arms = model.firstPersonModel()) {
            equal(
                    40f,
                    model.runtime().getAnimation("idle").animationLength,
                    "Body excludes first-person definitions");
            equal(
                    80f,
                    arms.runtime().getAnimation("idle").animationLength,
                    "Later authored arm definition");
            equal(
                    80f,
                    arms.runtime().getAnimation("lance_stand").animationLength,
                    "Authored arms override derivation");
        }
        try (var model = new LocalYsmModel(raw)) {
            model.frame(1, Map.of("head_x_rotation", -22.55f, "walk_distance", .5f), "");
            near(.5, model.runtime().evaluate("ysm.map_angle"), "Map uses model pitch");
            near(
                    Math.cos(Math.toRadians(103.2)) * 20,
                    model.runtime().evaluate("ysm.tcos0"),
                    "Walk cosine uses playback time");
            model.frame(2, Map.of("head_x_rotation", 60f), "");
            near(1, model.runtime().evaluate("ysm.map_angle"), "Map clamps downward pitch");
            // The debug list is bounded, but validation must still see errors after it fills.
            for (int i = 0; i < 110; i++) model.runtime().diagnostic("unbound test " + i);
            equal(0L, model.runtime().expressionErrorCount(), "Healthy model");
            model.runtime().evaluate("1 + (");
            if (model.runtime().expressionErrorCount() <= 0)
                throw new AssertionError("Expression errors hidden by diagnostic cap");
            equal(100, model.runtime().diagnostics().size(), "Bounded diagnostic list");
        }
        System.out.println(
                "YSM_PORT_PARITY_VERIFIED animation-overrides=body+arms+types identifiers=default+explicit+invalid");
    }

    private static RawAnimationFile animations(int type, float length) {
        var file = new RawAnimationFile();
        file.animType = type;
        for (String name : List.of("idle", "hold_mainhand:lance")) {
            var animation = new RawAnimation();
            animation.name = name;
            animation.length = length;
            animation.loopMode = 1;
            var bone = new RawBoneAnimation();
            bone.boneName = "RightArm";
            var frame = new RawKeyframe();
            frame.postData = new Object[] {0f, 0f, 0f};
            bone.rotation.add(frame);
            animation.boneAnimations.add(bone);
            file.animations.put(name, animation);
        }
        return file;
    }

    private static void near(double expected, Object actual, String label) {
        if (!(actual instanceof Number n) || Math.abs(n.doubleValue() - expected) > 1e-5)
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }
}
