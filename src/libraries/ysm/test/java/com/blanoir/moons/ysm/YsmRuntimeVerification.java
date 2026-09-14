package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel.*;
import com.blanoir.moons.ysm.internal.runtime.LocalRuntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Behavior checks for the extracted upstream runtime, without any game classes. */
final class YsmRuntimeVerification {
    static void verify(Path directory) throws Exception {
        RawYsmModel raw = fixture();
        function(raw, "setup@player_init", "v.init=(v.init??0)+1; v.base=7;");
        function(raw, "tick@player_update", "v.updates=(v.updates??0)+1;");
        function(raw, "double", "return arg[0]*2;");
        try (LocalYsmModel model = new LocalYsmModel(raw)) {
            LocalRuntime rt = model.runtime();
            model.frame(0, Map.of("ground_speed", 2d, "is_on_ground", true), "");
            near(
                    number(rt.evaluate("math.sin(90)+q.ground_speed*3")),
                    7,
                    "degrees and observation binding");
            near(number(rt.evaluate("v.absent??17")), 17, "unassigned variables retain null");
            near(number(rt.evaluate("v.test=4; return v.test*2;")), 8, "assignment and return");
            near(number(rt.evaluate("variable.test")), 4, "variable namespace alias");
            near(number(rt.evaluate("fn.double(5)")), 10, "model function arguments");
            near(rt.variableValue("v.init"), 1, "init executed once");
            model.frame(0, Map.of(), "");
            near(rt.variableValue("v.init"), 1, "same-time rendering does not reinitialize");
            near(
                    rt.variableValue("v.updates"),
                    1,
                    "same-time rendering does not repeat update events");
            model.frame(.25, Map.of("is_on_ground", true), "");
            near(rt.bone("root").scale.x, 1, "null coalescing in bone scale");
            rt.variable("v.roaming.body", .5);
            model.frame(.3, Map.of("is_on_ground", true), "");
            near(rt.variableValue("v.roaming.body"), .5, "nested authored parameter binding");
            near(number(rt.evaluate("v.roaming.body")), .5, "nested parameter visible to Molang");
            model.resetAnimation();
            model.frame(0, Map.of(), "");
            near(number(rt.evaluate("v.test??23")), 23, "reset clears scoped values");
            near(rt.variableValue("v.init"), 1, "reset reruns init exactly once");
        }
        verifyParameters(raw, directory);
        verifyEffects();
        YsmAudioVerification.verify();
        verifyControllerOrder();
        verifyPose(directory);
        verifySeek();
        verifyInterleavedRenderTimes();
        verifyFirstPersonAndSubEntities();
        verifyHeadTracking();
        verifyEquipmentRules();
        verifyWeaponAssemblyAndPredicates();
        verifyTimelineAndPhysics();
        System.out.println(
                "YSM_RUNTIME_VERIFIED bindings=null+functions+struct controllers=order events=init+update parameters=persistence effects=ownership");
    }

    private static void verifyHeadTracking() throws Exception {
        var raw = fixture();
        var head = new RawBone();
        head.name = "Head";
        head.parentName = "root";
        raw.mainEntity.mainModel.bones.add(head);
        var child = new RawBone();
        child.name = "headTip";
        child.parentName = "Head";
        child.pivot = new float[] {0, 16, 0};
        raw.mainEntity.mainModel.bones.add(child);
        raw.mainEntity
                .animationFiles
                .get("main")
                .animations
                .put("parallel0", animation("parallel0", "Head", 10, 0, 0));
        try (var body = new LocalYsmModel(raw);
                var arms = body.firstPersonModel()) {
            body.frame(0, Map.of(), "");
            float authored = body.runtime().bone("Head").rotation.x;
            for (float pitch : new float[] {45, -45, 0, 45}) {
                var queries =
                        Map.<String, Object>of("head_x_rotation", pitch, "head_y_rotation", 20f);
                var mesh = body.frame(0, queries, "");
                var expected =
                        new org.joml.Matrix4f()
                                .rotateY((float) Math.toRadians(-20))
                                .rotateX(authored - (float) Math.toRadians(pitch));
                if (!mesh.locators().get("Head").equals(expected, 1e-5f)
                        || !mesh.equipmentLocators().get("Head").equals(expected, 1e-5f))
                    throw new AssertionError(
                            "Head tracking must add pitch/yaw after authored animation, without accumulating");
                near(
                        body.runtime().bone("Head").rotation.x,
                        authored,
                        "tracking leaves authored pose intact");
                var tip = expected.transformPosition(0, 1, 0, new org.joml.Vector3f());
                body.updatePose(0, queries, "");
                near(
                        body.runtime().bone("headTip").absolutePivot.y,
                        tip.y * 16,
                        "pose-only head child pivot");
                near(
                        body.runtime().bone("headTip").absolutePivot.z,
                        tip.z * 16,
                        "pose-only head child pitch");
                var fp = arms.frame(0, queries, "");
                var neutral = arms.frame(0, Map.of(), "");
                if (!fp.locators().get("Head").equals(neutral.locators().get("Head"), 1e-5f))
                    throw new AssertionError(
                            "View-space first-person model must not receive body head tracking twice");
            }
        }
        raw.mainEntity.animationFiles.get("main").animations.clear();
        try (var body = new LocalYsmModel(raw)) {
            for (float bodyYaw : new float[] {0, 90, -120})
                for (float headYaw : new float[] {-45, 0, 45})
                    for (float pitch : new float[] {-60, 0, 60}) {
                        var mesh =
                                body.frame(
                                        0,
                                        Map.of(
                                                "head_x_rotation",
                                                pitch,
                                                "head_y_rotation",
                                                headYaw),
                                        "");
                        // A face points along local -Z. Apply the standing player's world
                        // orientation.
                        var world =
                                new org.joml.Matrix4f()
                                        .rotateY((float) Math.toRadians(180 - bodyYaw))
                                        .mul(mesh.locators().get("Head"));
                        var facing = world.transformDirection(0, 0, -1, new org.joml.Vector3f());
                        double yaw = Math.toRadians(bodyYaw + headYaw),
                                elevation = Math.toRadians(pitch);
                        // Minecraft view direction: positive pitch looks down, positive yaw turns
                        // toward -X.
                        near(
                                facing.x,
                                -Math.sin(yaw) * Math.cos(elevation),
                                "head faces camera left/right");
                        near(facing.y, -Math.sin(elevation), "head faces camera up/down");
                        near(
                                facing.z,
                                Math.cos(yaw) * Math.cos(elevation),
                                "head follows body and relative yaw");
                    }
        }
        System.out.println(
                "YSM_HEAD_TRACKING_VERIFIED direction=up+down+left+right body-yaw=3 authored=preserved first-person=isolated");
    }

    private static void verifyWeaponAssemblyAndPredicates() throws Exception {
        RawYsmModel raw = fixture();
        var file = raw.mainEntity.animationFiles.get("main");
        var armBone = new RawBone();
        armBone.name = "RightArm";
        armBone.parentName = "root";
        raw.mainEntity.mainModel.bones.add(armBone);
        var hold = animation("hold_mainhand:lance", "right_upper_arm", 25, 0, 0);
        hold.boneAnimations.add(animation("", "root", 80, 0, 0).boneAnimations.getFirst());
        file.animations.put(hold.name, hold);
        file.animations.put("elytra_fly", animation("elytra_fly", "RightArm", 20, 0, 0));
        try (var body = new LocalYsmModel(raw);
                var arms = body.firstPersonModel()) {
            if (body.handlesFallFlyingPitch())
                throw new AssertionError("Arm rotation is not root flight pitch");
            var derived = arms.runtime().getAnimation("lance_stand");
            if (derived == null
                    || derived.boneAnimations.size() != 1
                    || !derived.boneAnimations.getFirst().boneName.equals("RightArm"))
                throw new AssertionError(
                        "Weapon arm alias must remap arm bones and exclude body tracks");
            Map<String, Object> values =
                    new HashMap<>(
                            Map.of(
                                    "mainhand_item",
                                    "minecraft:trident",
                                    "mainhand_category",
                                    "trident",
                                    "mainhand_use",
                                    "spear",
                                    "offhand_item",
                                    "minecraft:air",
                                    "head_item",
                                    "minecraft:iron_helmet",
                                    "head_tags",
                                    List.of("minecraft:head_armor"),
                                    "is_swinging",
                                    true,
                                    "swinging_hand",
                                    "mainhand",
                                    "passenger_id",
                                    "minecraft:parrot",
                                    "passenger_tags",
                                    List.of("example:birds")));
            body.frame(0, values, "");
            for (String expression :
                    List.of(
                            "ctrl.hold('mainhand', ':spear')",
                            "ctrl.hold('offhand', 'empty')",
                            "ctrl.swing('mainhand', ':trident')",
                            "ctrl.armor('head', '#minecraft:head_armor')",
                            "ctrl.ride('passenger', '#example:birds')"))
                if (!Boolean.TRUE.equals(body.runtime().evaluate(expression)))
                    throw new AssertionError("Local controller predicate failed: " + expression);
            for (String expression :
                    List.of(
                            "ctrl.hold('mainhand', ':lance')",
                            "ctrl.swing('offhand', 'empty')",
                            "ctrl.armor('mainhand', '$minecraft:trident')",
                            "ctrl.ride('vehicle', '$minecraft:parrot')"))
                if (!Boolean.FALSE.equals(body.runtime().evaluate(expression)))
                    throw new AssertionError(
                            "Local controller predicate unexpectedly matched: " + expression);
            values.put(
                    "projectile_owner",
                    new LocalRuntime.ObservationView(values, (namespace, name, arguments) -> 0f));
            body.frame(.05, values, "");
            if (!Boolean.TRUE.equals(
                    body.runtime()
                            .evaluate("ysm.projectile_owner->ctrl.hold('mainhand', ':trident')")))
                throw new AssertionError("Owner arrow expression must use owner predicates");
        }
        file.animations.put("elytra_fly", animation("elytra_fly", "root", 90, 0, 0));
        try (var body = new LocalYsmModel(raw)) {
            if (!body.handlesFallFlyingPitch())
                throw new AssertionError("Authored root flight pitch must suppress vanilla pitch");
        }
    }

    private static void verifyEquipmentRules() throws Exception {
        RawYsmModel raw = fixture();
        var file = raw.mainEntity.animationFiles.get("main");
        file.animations.clear();
        for (var entry :
                Map.of(
                                "ride",
                                20f,
                                "head$minecraft:iron_helmet",
                                40f,
                                "hold_mainhand:trident",
                                60f,
                                "hold_mainhand$same",
                                80f)
                        .entrySet())
            file.animations.put(
                    entry.getKey(), animation(entry.getKey(), "root", 0, entry.getValue(), 0));
        try (var model = new LocalYsmModel(raw)) {
            var state =
                    Map.<String, Object>of(
                            "is_riding",
                            true,
                            "vehicle_is_living",
                            true,
                            "vehicle_id",
                            "minecraft:horse");
            model.frame(0, state, "");
            model.frame(.25, state, "");
            near(
                    model.runtime().bone("root").rotation.y,
                    -Math.toRadians(20),
                    "riding must not apply both vehicle and main animation");
            model.resetAnimation();
            state = Map.of("has_head", true, "head_item", "minecraft:iron_helmet");
            model.frame(0, state, "");
            model.frame(.25, state, "");
            near(
                    model.runtime().bone("root").rotation.y,
                    -Math.toRadians(40),
                    "upstream armor animation name");
            model.resetAnimation();
            state = Map.of("mainhand_category", "trident", "mainhand_item", "same");
            model.frame(0, state, "");
            model.frame(.25, state, "");
            near(
                    model.runtime().bone("root").rotation.y,
                    -Math.toRadians(60),
                    "special weapon category takes priority over item ID");
        }
        raw = fixture();
        var hand = new RawBone();
        hand.name = "right_hand_locator";
        var sword = new RawBone();
        sword.name = "right_sword2";
        raw.mainEntity.mainModel.bones.addAll(List.of(hand, sword));
        try (var model = new LocalYsmModel(raw)) {
            model.poseOverrides(
                    Map.of("right_sword2", new YsmPose(0, 0, 0, 0, 0, 0, 0, 0, 0, false)));
            var mesh = model.frame(0, Map.of(), "");
            if (!mesh.locators().containsKey("RightHandLocator")
                    || !mesh.swordLocators().containsKey("RightSword"))
                throw new AssertionError("Normalized locator names and numbered sword anchors");
            near(
                    mesh.swordLocators().get("RightSword").m00(),
                    1,
                    "sword attachment ignores authored hidden final scale");
        }
    }

    private static void verifyTimelineAndPhysics() {
        RawYsmModel raw = fixture();
        var a = raw.mainEntity.animationFiles.get("main").animations.get("parallel0");
        RawTimelineEvent event = new RawTimelineEvent();
        event.timestamp = .1f;
        event.events.add("v.timeline=(v.timeline??0)+1; ysm.defer('layer',42);");
        a.timelineEvents.add(event);
        function(raw, "layers@defer", "v.deferred=arg[0];");
        a.boneAnimations.getFirst().rotation.getFirst().postData =
                new Object[] {"ysm.second_order('spring',v.input??0,2,1,1)", 0f, 0f};
        try (var model = new LocalYsmModel(raw)) {
            model.frame(0, Map.of(), "");
            model.frame(.25, Map.of(), "");
            near(model.runtime().variableValue("timeline"), 1, "timeline crosses event once");
            if (model.runtime().variableValue("deferred") != null)
                throw new AssertionError(
                        "Upstream defer waits until animation end or loop boundary");
            model.runtime().variable("input", 45);
            for (int i = 6; i < 35; i++) model.frame(i * .05, Map.of(), "");
            near(
                    model.runtime().variableValue("deferred"),
                    42,
                    "defer retains call arguments at loop boundary");
            double moving = model.runtime().bone("root").rotation.x;
            if (!Double.isFinite(moving) || Math.abs(moving) < .01)
                throw new AssertionError("Second order physics must move toward changed target");
            model.resetAnimation();
            model.frame(0, Map.of(), "");
            near(
                    model.runtime().bone("root").rotation.x,
                    0,
                    "physics resets on world/model change");
        }
    }

    private static void verifyInterleavedRenderTimes() throws Exception {
        RawYsmModel raw = fixture();
        function(raw, "tick@player_update", "v.updates=(v.updates??0)+1;");
        try (var session = new YsmSession(new LocalYsmModel(raw), null, "", "")) {
            Map<String, Object> world = Map.of("head_y_rotation", 0d);
            Map<String, Object> preview = Map.of("head_y_rotation", 90d);
            session.frame(5, world);
            session.frame(5.05, world);
            session.evaluate("v.keep=37;");
            // The inventory can render tick+1 before another pass renders tick+partial.
            double before = session.time();
            double updates = number(session.model().runtime().variableValue("updates"));
            var mesh = session.frame(5.05, world);
            var repeated = session.frame(5.01, preview);
            near(session.time(), before, "older inventory/world pass keeps animation time");
            near(session.model().runtime().variableValue("keep"), 37, "render ordering does not reset variables");
            near(session.model().runtime().variableValue("updates"), updates, "older render does not replay updates");
            if (!Arrays.equals(mesh.vertices(), repeated.vertices()))
                throw new AssertionError("An older render must preserve the current pose");
            session.frame(5.025, preview);
            session.updatePose(5.04, preview);
            session.frame(5.05, world);
            near(session.time(), before, "repeated partial ticks do not accumulate extra time");
            session.frame(5.075, world);
            near(session.time(), before + .025, "animation continues from the latest source time");
            session.playback(1, true);
            before = session.time();
            session.frame(5.1, world);
            session.frame(5.06, preview);
            near(session.time(), before, "paused animation survives interleaved render times");
            session.playback(1, false);
            session.frame(5.125, world);
            near(session.time(), before + .025, "resume does not catch up paused or repeated time");
            session.frame(0, world);
            near(session.time(), 0, "a real world clock reset still resets animation");
            if (session.model().runtime().variableValue("keep") != null)
                throw new AssertionError("A real world reset must clear animation variables");
        }
    }

    private static void verifySeek() throws Exception {
        RawYsmModel raw = fixture();
        function(
                raw,
                "tick@player_update",
                "v.updates=(v.updates??0)+1; ysm.play_sound(0,'tick',2); ysm.particle('minecraft:flame');");
        try (var session = new YsmSession(new LocalYsmModel(raw), null, "", "")) {
            int[] effects = {0};
            session.model()
                    .runtime()
                    .effects(
                            new LocalRuntime.Effects() {
                                public YsmSoundHandle sound(String n, boolean l, float v, float p) {
                                    effects[0]++;
                                    return new FakeSound();
                                }

                                public void particle(String n, boolean a, double[] p) {
                                    effects[0]++;
                                }
                            });
            session.frame(0, Map.of());
            int before = effects[0];
            session.seek(30);
            if (!session.seeking()) throw new AssertionError("Seek must queue reconstruction");
            session.frame(.1, Map.of());
            if (!session.seeking()) throw new AssertionError("Long seek must span frames");
            for (int frames = 0; session.seeking() && frames < 2000; frames++)
                session.frame(.1, Map.of());
            if (session.seeking()) throw new AssertionError("Seek never completed");
            near(session.time(), 30, "seek target");
            if (effects[0] != before)
                throw new AssertionError("Seek must not replay old audio or particles");
            near(
                    session.model().runtime().variableValue("updates"),
                    601,
                    "chronological updates during seek");
            session.playback(1, true);
            session.frame(.2, Map.of());
            near(session.time(), 30, "paused seek remains at requested time");
            session.playback(1, false);
            session.frame(.25, Map.of());
            if (effects[0] <= before) throw new AssertionError("Effects resume after seek");
        }
    }

    private static void verifyFirstPersonAndSubEntities() throws Exception {
        RawYsmModel raw = fixture();
        function(raw, "hands@fp.arm_ctrl_misc", "v.fp=(v.fp??0)+1; return ctrl.state_stop;");
        try (var body = new LocalYsmModel(raw);
                var arms = body.firstPersonModel()) {
            body.prepareTexture("");
            body.frame(0, Map.of(), "");
            if (!"default".equals(body.runtime().evaluate("ysm.texture_name")))
                throw new AssertionError("Texture name query");
            body.runtime().variable("v.roaming.body", .75);
            body.frame(.1, Map.of(), "");
            body.runtime()
                    .variables()
                    .forEach((name, value) -> arms.runtime().variable(name, value));
            arms.frame(0, Map.of(), "");
            near(
                    arms.runtime().variableValue("v.roaming.body"),
                    .75,
                    "nested body parameter reaches first person");
            near(
                    arms.runtime().variableValue("v.fp"),
                    1,
                    "fp.arm_ctrl_misc event uses upstream name");
        }
        RawSubEntity sub = new RawSubEntity();
        sub.identifier = "arrow";
        sub.matchIds = new String[] {"minecraft:arrow"};
        sub.model = raw.mainEntity.mainModel;
        sub.textures = raw.mainEntity.textures;
        RawAnimationFile file = new RawAnimationFile();
        file.animType = 5;
        file.animations.put("air", animation("air", "root", 0, 20, 0));
        file.animations.put("ground", animation("ground", "root", 0, 60, 0));
        sub.animationFiles.put("main", file);
        raw.projectiles.put("arrow", sub);
        try (var body = new LocalYsmModel(raw);
                var a = body.subEntity(true, "arrow");
                var b = body.subEntity(true, "arrow")) {
            if (!"arrow".equals(body.matchingSubEntity(true, "minecraft:arrow")))
                throw new AssertionError("Entity id mapping");
            a.frame(0, Map.of(), "");
            a.frame(.25, Map.of(), "");
            b.frame(0, Map.of("is_on_ground", true), "");
            b.frame(.25, Map.of("is_on_ground", true), "");
            near(
                    a.runtime().bone("root").rotation.y,
                    -Math.toRadians(20),
                    "projectile air animation");
            near(
                    b.runtime().bone("root").rotation.y,
                    -Math.toRadians(60),
                    "projectile instances isolate controllers");
        }
    }

    private static void verifyPose(Path directory) throws Exception {
        Path file = directory.resolve("pose.json");
        try (LocalYsmModel model = new LocalYsmModel(fixture())) {
            YsmPoseController pose = new YsmPoseController(model, file);
            var before = model.frame(0, Map.of(), "").locators().get("root");
            pose.set("root", new YsmPose(16, 0, 0, 0, 0, 0, 2, 1, 1, false));
            var after = model.frame(0, Map.of(), "").locators().get("root");
            near(
                    after.m30() - before.m30(),
                    -1,
                    "pose uses model pixel units and Bedrock X convention");
            near(after.m00(), 2, "pose scale multiplies the animation");
            near(
                    model.frame(.2, Map.of(), "").locators().get("root").m00(),
                    2,
                    "pose does not accumulate across frames");
            pose.save();
        }
        try (LocalYsmModel model = new LocalYsmModel(fixture())) {
            YsmPoseController pose = new YsmPoseController(model, file);
            near(
                    model.frame(0, Map.of(), "").locators().get("root").m30(),
                    -1,
                    "pose survives recreation");
            pose.reset();
            near(
                    model.frame(0, Map.of(), "").locators().get("root").m30(),
                    0,
                    "pose reset restores authored transform");
        }
    }

    private static void verifyParameters(RawYsmModel raw, Path directory) throws Exception {
        ConfigForm form = new ConfigForm();
        form.type = "range";
        form.title = "Body";
        form.min = .1f;
        form.max = 2;
        form.step = .1f;
        form.defaultValue = "v.roaming.body";
        ConfigForm radio = new ConfigForm();
        radio.type = "radio";
        radio.defaultValue = "v.face==7 ? 1 : 0";
        radio.labels.put("Third", "v.face=3; v.roaming.hat=0;");
        radio.labels.put("Seventh", "v.face=7; v.roaming.hat=1;");
        var group = new ExtraAnimationButton();
        group.id = "body";
        group.name = "Body";
        group.forms.addAll(List.of(form, radio));
        raw.properties.extraAnimationButtons.add(group);
        Path preferences = directory.resolve("runtime-parameters.json");
        try (YsmSession session = new YsmSession(new LocalYsmModel(raw), preferences, "", "")) {
            session.frame(0, Map.of());
            if (session.values().containsKey("body/0"))
                throw new AssertionError("Unset form must not overwrite the authored default");
            session.setParameter("body/0", .46);
            session.setParameter("body/1", 1);
            session.frame(.1, Map.of());
            near(session.values().get("body/0"), .5, "parameter snapping");
            near(
                    session.values().get("body/1"),
                    1,
                    "radio selection evaluates authored read expression");
            near(
                    session.model().runtime().variableValue("v.face"),
                    7,
                    "radio executes authored expression");
            near(
                    session.model().runtime().variableValue("v.roaming.hat"),
                    1,
                    "radio may set several model variables");
            session.save();
        }
        try (YsmSession session = new YsmSession(new LocalYsmModel(raw), preferences, "", "")) {
            session.frame(0, Map.of());
            near(session.values().get("body/0"), .5, "restored nested parameter");
            session.resetParameters();
            session.frame(0, Map.of());
            if (session.values().containsKey("body/0"))
                throw new AssertionError("Reset restores undefined author defaults");
        }
    }

    private static void verifyEffects() {
        try (LocalYsmModel model = new LocalYsmModel(fixture())) {
            LocalRuntime rt = model.runtime();
            List<FakeSound> sounds = new ArrayList<>();
            int[] particles = {0};
            rt.effects(
                    new LocalRuntime.Effects() {
                        public YsmSoundHandle sound(
                                String name, boolean loop, float volume, float pitch) {
                            FakeSound sound = new FakeSound();
                            sounds.add(sound);
                            return sound;
                        }

                        public void particle(String name, boolean absolute, double[] args) {
                            particles[0]++;
                        }
                    });
            model.frame(0, Map.of(), "");
            rt.evaluate("ysm.play_sound(0,'one',2); ysm.play_sound(0,'one',2);");
            if (sounds.size() != 2) throw new AssertionError("Anonymous sounds support polyphony");
            rt.evaluate("ysm.play_sound(5,'one',2); ysm.play_sound(5,'one',2);");
            if (sounds.size() != 3)
                throw new AssertionError("Named sound refuses implicit replacement");
            rt.evaluate("ysm.play_sound(5,'one',3); ysm.particle('minecraft:flame',0,1,0);");
            if (sounds.size() != 4 || !sounds.get(2).stopped || particles[0] != 1)
                throw new AssertionError("Explicit replacement and particles");
            model.resetAnimation();
            if (sounds.stream().anyMatch(s -> !s.stopped))
                throw new AssertionError("World reset must release sound handles");
        }
    }

    private static void verifyControllerOrder() {
        RawYsmModel raw = fixture();
        RawAnimationFile file = raw.mainEntity.animationFiles.get("main");
        file.animations.put("idle", animation("idle", "root", 0, 10, 0));
        file.animations.put("parallel0", animation("parallel0", "root", 0, 20, 0));
        try (LocalYsmModel model = new LocalYsmModel(raw)) {
            model.frame(0, Map.of(), "");
            model.frame(.25, Map.of(), "");
            near(
                    model.runtime().bone("root").rotation.y,
                    -Math.toRadians(30),
                    "legacy parallel rotation adds after main");
        }
        file.animations.put("swing:sword", animation("swing:sword", "root", 0, 40, 0));
        try (LocalYsmModel model = new LocalYsmModel(raw)) {
            Map<String, Object> state =
                    Map.of(
                            "is_swinging",
                            true,
                            "swinging_hand",
                            "mainhand",
                            "mainhand_category",
                            "sword",
                            "mainhand_item",
                            "minecraft:iron_sword");
            model.frame(0, state, "");
            model.frame(.2, state, "");
            String animation = model.runtime().controllerStates().get("player.swing");
            if (!animation.contains("swing:sword"))
                throw new AssertionError(
                        "Mainhand swing uses upstream animation names: " + animation);
        }
        file.animations.put("override", animation("override", "root", 0, 60, 0));
        RawAnimationControllerFile controllers = new RawAnimationControllerFile();
        RawAnimationController controller = new RawAnimationController();
        controller.animationName = "player.parallel_0";
        controller.initialState = "active";
        RawControllerState state = new RawControllerState();
        state.name = "active";
        state.animations.put("override", "1");
        controller.states.add(state);
        controllers.controllers.put("player.parallel_0", controller);
        raw.mainEntity.animationControllerFiles.add(controllers);
        try (LocalYsmModel model = new LocalYsmModel(raw)) {
            model.frame(0, Map.of(), "");
            model.frame(.25, Map.of(), "");
            var names = model.runtime().controllerStates();
            if (names.containsKey("player.parallel0") || !names.containsKey("player.parallel_0"))
                throw new AssertionError(
                        "Parallel fallback and custom controller must share one slot");
            near(
                    model.runtime().bone("root").rotation.y,
                    -Math.toRadians(60),
                    "authored controller replaces its linked parallel animation");
        }
    }

    private static RawYsmModel fixture() {
        RawYsmModel raw = new RawYsmModel();
        raw.mainEntity.mainModel = new RawGeometry();
        RawBone bone = new RawBone();
        bone.name = "root";
        raw.mainEntity.mainModel.bones.add(bone);
        RawTexture texture = new RawTexture();
        texture.name = "default";
        texture.imageFormat = -1;
        texture.width = 1;
        texture.height = 1;
        texture.data = new byte[] {-1, -1, -1, -1};
        raw.mainEntity.textures.put("default", texture);
        RawAnimationFile file = new RawAnimationFile();
        file.animType = 1;
        RawAnimation parallel = animation("parallel0", "root", 0, 0, 0);
        RawKeyframe scale = new RawKeyframe();
        scale.postData = new Object[] {"v.visible??1", "v.visible??1", "v.visible??1"};
        parallel.boneAnimations.getFirst().scale.add(scale);
        file.animations.put("parallel0", parallel);
        raw.mainEntity.animationFiles.put("main", file);
        return raw;
    }

    private static RawAnimation animation(String name, String bone, float x, float y, float z) {
        RawAnimation animation = new RawAnimation();
        animation.name = name;
        animation.loopMode = 1;
        animation.length = 1;
        RawBoneAnimation track = new RawBoneAnimation();
        track.boneName = bone;
        RawKeyframe key = new RawKeyframe();
        key.postData = new Object[] {x, y, z};
        track.rotation.add(key);
        animation.boneAnimations.add(track);
        return animation;
    }

    private static void function(RawYsmModel raw, String name, String source) {
        raw.functionFiles.put(
                name + ".molang", new RawDataFile("", source.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class FakeSound implements YsmSoundHandle {
        boolean stopped;

        public boolean stopped() {
            return stopped;
        }

        public void close() {
            stopped = true;
        }
    }

    private static double number(Object value) {
        if (!(value instanceof Number n)) throw new AssertionError("Expected number, got " + value);
        return n.doubleValue();
    }

    private static void near(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 1e-4)
            throw new AssertionError(message + ": actual=" + actual + " expected=" + expected);
    }
}
