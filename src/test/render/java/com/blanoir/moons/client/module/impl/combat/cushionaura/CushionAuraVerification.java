package com.blanoir.moons.client.module.impl.combat.cushionaura;

import com.blanoir.moons.client.access.CushionAccess;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.moons.loader.common.mapping.CommonMappings;
import com.google.gson.JsonPrimitive;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Confirmation, native version boundary, packet-path coverage and real settings without a game. */
public final class CushionAuraVerification {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Standalone bootstrap has no server component binding. Only item type/count is tested
        // here.
        BuiltInRegistries.ITEM
                .listElements()
                .forEach(
                        holder ->
                                holder.bindComponents(
                                        DataComponentMap.builder()
                                                .set(DataComponents.MAX_STACK_SIZE, 64)
                                                .build()));
        Path home = Path.of(System.getProperty("moons.home"));
        Files.createDirectories(home);
        Settings.configure(Files.createTempDirectory(home, "cushion-aura-"));
        confirmations();
        tickTiming();
        geometry();
        reuseAndScoring();
        versions();
        settings();
        passengerRotationPath();
        System.out.println(
                "MOONS_CUSHION_AURA_VERIFIED checks="
                        + checks
                        + " supported="
                        + CushionAura.supported());
    }

    private static void confirmations() {
        CushionCycle cycle = new CushionCycle();
        long now = 1_000_000_000L;
        for (var phase : CushionCycle.Phase.values()) {
            cycle.enter(phase, now);
            require(!cycle.mayAttack(false, false), "No local/remote mount: " + phase);
            require(
                    !cycle.mayAttack(true, false),
                    "Local prediction cannot authorize attacks: " + phase);
            require(
                    !cycle.mayAttack(false, true),
                    "A stale remote mount cannot authorize attacks: " + phase);
            require(
                    cycle.mayAttack(true, true) == (phase == CushionCycle.Phase.SEATED),
                    "Only seated attacks: " + phase);
        }
        cycle.enter(CushionCycle.Phase.WAIT_RIDE, now);
        require(!cycle.acknowledgeRide(true, false, now), "Wait for server passengers");
        require(!cycle.acknowledgeRide(false, true, now), "Wait for applied local mount");
        require(
                cycle.acknowledgeRide(true, true, now),
                "Matching server and local state enters seated");
        require(!cycle.elapsed(now + 2_499_999_999L, 2500), "Do not relocate before interval");
        require(cycle.elapsed(now + 2_500_000_000L, 2500), "Relocate at interval");
        cycle.enter(CushionCycle.Phase.DISMOUNT, now);
        require(
                !cycle.acknowledgeDismount(false, true, now),
                "Local exit alone cannot start another placement");
        require(
                !cycle.acknowledgeDismount(true, false, now),
                "Remote exit alone cannot start another placement");
        require(
                cycle.acknowledgeDismount(false, false, now),
                "Confirmed exit returns to fresh search");
        require(!cycle.mayAttack(true, true), "Reset/search cannot inherit a prior attack grant");
        cycle.enter(CushionCycle.Phase.WAIT_ENTITY, now);
        require(
                !cycle.acknowledgeRide(true, true, now),
                "A delayed mount cannot skip placement phases");
        require(cycle.elapsed(now + 2_500_000_000L, 2500), "Unconfirmed actions time out");
    }

    private static void geometry() {
        Vec3 target = new Vec3(10.5, 64, -20.5);
        Vec3 seat = target.add(2, 0, 0);
        require(
                CushionPlacement.aroundTarget(seat, target, 2, null),
                "Target-centered radius includes boundary");
        require(
                !CushionPlacement.aroundTarget(seat, Vec3.ZERO, 2, null),
                "Center is target, not local origin");
        require(
                !CushionPlacement.aroundTarget(target.add(.5, 0, 0), target, 2, null),
                "Avoid target's body");
        require(
                !CushionPlacement.aroundTarget(target.add(2.01, 0, 0), target, 2, null),
                "No radius overflow");
        require(
                !CushionPlacement.aroundTarget(target.add(2, 0, 2), target, 2, null),
                "Use a circle rather than its enclosing square");
        require(
                !CushionPlacement.aroundTarget(seat, target, 2, seat),
                "Relocation must leave the prior column");
        require(
                !CushionPlacement.aroundTarget(seat.add(0, 1, 0), target, 2, seat),
                "Height alone does not count as relocation");
        require(
                CushionPlacement.aroundTarget(target.add(-2, 0, 0), target, 2, seat),
                "Accept a different reachable side");
    }

    private static void tickTiming() {
        CushionCycle cycle = new CushionCycle();
        long now = 1_000_000_000L;
        int tick = 100;
        require(CushionCycle.ticksElapsed(tick, tick, 0), "Zero mount delay adds no tick");
        require(
                !CushionCycle.ticksElapsed(tick, tick, 1),
                "One mount tick cannot run in placement tick");
        require(CushionCycle.ticksElapsed(tick + 1, tick, 1), "One mount tick opens next tick");
        require(
                CushionCycle.ticksElapsed(tick + 8, tick, 1),
                "Late spawn does not add another delay");
        cycle.enter(CushionCycle.Phase.WAIT_RIDE, now, tick);
        cycle.recordAttack();
        require(
                cycle.acknowledgeRide(true, true, now, tick),
                "Seat confirmation starts tick clock");
        require(cycle.elapsedTicks(tick, 0), "Zero attack delay opens on confirmed seat");
        require(!cycle.elapsedTicks(tick, 1), "One attack delay waits a tick");
        require(cycle.elapsedTicks(tick + 1, 1), "One attack delay opens next tick");
        require(
                !cycle.mayRelocate(tick + 20, 0, true),
                "An attack before seating cannot grant relocation");
        require(
                cycle.mayRelocate(tick, 0, false),
                "Optional attack wait permits immediate relocation");
        for (int frame = 0; frame < 240; frame++) {
            require(!cycle.mayRelocate(tick, 1, false), "Render frames do not advance tick delay");
        }
        require(cycle.mayRelocate(tick + 1, 1, false), "Interval advances on actual tick");
        cycle.recordAttack();
        require(cycle.mayRelocate(tick, 0, true), "Sent attack unlocks zero interval");
        require(!cycle.mayRelocate(tick, 1, true), "Sent attack does not bypass chosen interval");
        require(cycle.mayRelocate(tick + 1, 1, true), "Attack and interval both satisfied");
        cycle.enter(CushionCycle.Phase.DISMOUNT, now, tick + 1);
        require(!cycle.mayRelocate(tick + 2, 0, false), "Cannot relocate twice during exit");
        cycle.enter(CushionCycle.Phase.WAIT_RIDE, now, tick + 2);
        cycle.acknowledgeRide(true, true, now, tick + 2);
        require(!cycle.mayRelocate(tick + 3, 0, true), "Next seat needs its own attack");
        require(
                cycle.elapsed(now + 2_500_000_000L, 2500),
                "Timeout remains monotonic even with no ticks");
    }

    private static void versions() {
        boolean nativeCushions =
                BuiltInRegistries.ITEM.containsKey(Identifier.parse("minecraft:white_cushion"));
        require(
                CushionAura.supported() == nativeCushions,
                "Only cushion-capable versions expose the module");
        require(!CushionAccess.isItem(ItemStack.EMPTY), "Empty stack cannot supply a seat");
        require(
                !CushionAccess.isItem(new ItemStack(Items.STONE)),
                "Ordinary blocks cannot supply a seat");
        require(!CushionAccess.isCushion(null), "Missing entity cannot confirm placement");
        if (!nativeCushions) {
            require(
                    CushionAccess.passengerOffset().equals(Vec3.ZERO),
                    "Old versions have no cushion attachment");
            require(
                    CushionAura.setEnabled(null, true) == 0 && !CushionAura.isEnabled(),
                    "Older versions reject activation");
            return;
        }
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:cushion"));
        require(
                CushionAccess.passengerOffset()
                        .equals(
                                type.getDimensions()
                                        .attachments()
                                        .getClamped(EntityAttachment.PASSENGER, 0, 0)),
                "Prospective seats use native passenger attachment instead of bounding-box height");
        for (String color :
                new String[] {
                    "white",
                    "orange",
                    "magenta",
                    "light_blue",
                    "yellow",
                    "lime",
                    "pink",
                    "gray",
                    "light_gray",
                    "cyan",
                    "purple",
                    "blue",
                    "brown",
                    "green",
                    "red",
                    "black"
                }) {
            var item =
                    BuiltInRegistries.ITEM.getValue(
                            Identifier.parse("minecraft:" + color + "_cushion"));
            require(
                    item != null && CushionAccess.isItem(new ItemStack(item)),
                    "Recognize native cushion " + color);
        }
    }

    private static void reuseAndScoring() {
        var seat = new CushionPlacement.Plan(null, new Vec3(2, 0, 0), -1, 42);
        var placement = new CushionPlacement.Plan(null, new Vec3(-2, 0, 0), 3);
        require(
                seat.existing() && !placement.existing(),
                "Reuse and placement have distinct plans");
        require(
                CushionPlacement.preferExisting(
                                () -> seat,
                                () -> {
                                    throw new AssertionError(
                                            "Existing seat must not request inventory or placement");
                                })
                        == seat,
                "Reuse works without any cushion item");
        var calls = new AtomicInteger();
        require(
                CushionPlacement.preferExisting(
                                        () -> null,
                                        () -> {
                                            calls.incrementAndGet();
                                            return placement;
                                        })
                                == placement
                        && calls.get() == 1,
                "No suitable seat invokes placement once");
        require(
                CushionPlacement.preferExisting(() -> null, () -> null) == null,
                "Neither seats nor material leaves the cycle waiting");
        require(
                !CushionPlacement.aroundTarget(seat.position(), Vec3.ZERO, 3, seat.position()),
                "Reuse cannot choose the seat just left");
        require(
                CushionPlacement.aroundTarget(seat.position(), Vec3.ZERO, 3, placement.position()),
                "Two existing seats can be reused repeatedly");

        AABB target = new AABB(-.3, 0, -.3, .3, 1.8, .3);
        Vec3 eye = new Vec3(2.5, 1.6, 0);
        var world = new SeatWorld();
        Predicate<Vec3> visible =
                point ->
                        world.clip(
                                                new ClipContext(
                                                        eye,
                                                        point,
                                                        ClipContext.Block.OUTLINE,
                                                        ClipContext.Fluid.NONE,
                                                        CollisionContext.empty()))
                                        .getType()
                                == HitResult.Type.MISS;
        require(
                CushionPlacement.attackSamples(eye, target, 3, visible) == 4,
                "Clear seat has multiple real attack points");
        world.blocks.put(new BlockPos(1, 0, -1), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        int partial = CushionPlacement.attackSamples(eye, target, 3, visible);
        require(partial > 0 && partial < 4, "Low cover retains reachable upper attack points");
        world.blocks.put(new BlockPos(1, 1, -1), Blocks.STONE.defaultBlockState());
        world.blocks.put(new BlockPos(1, 1, 0), Blocks.STONE.defaultBlockState());
        require(
                CushionPlacement.attackSamples(eye, target, 3, visible) == 0,
                "A reachable cushion behind an attack wall is rejected");
        require(
                CushionPlacement.attackSamples(new Vec3(6, 1.6, 0), target, 3, point -> true) == 0,
                "Visibility never extends attack reach");
        double interior =
                CushionPlacement.positionScore(
                        new Vec3(1.5, 0, 0), Vec3.ZERO, Vec3.ZERO, null, .75, 3, 1.5, 3, 4);
        double edge =
                CushionPlacement.positionScore(
                        new Vec3(3, 0, 0), Vec3.ZERO, Vec3.ZERO, null, .75, 3, 2.95, 3, 4);
        require(interior < edge, "Prefer useful reach margin over the outer radius");
        double obscured =
                CushionPlacement.positionScore(
                        new Vec3(1.5, 0, 0), Vec3.ZERO, Vec3.ZERO, null, .75, 3, 1.5, 3, 1);
        require(interior < obscured, "Prefer more clear attack points");
        double elevated =
                CushionPlacement.positionScore(
                        new Vec3(1.5, 1, 0), Vec3.ZERO, Vec3.ZERO, null, .75, 3, 1.5, 3, 4);
        require(interior < elevated, "Avoid unnecessary elevation changes");
        double sameSide =
                CushionPlacement.positionScore(
                        new Vec3(2, 0, 0),
                        Vec3.ZERO,
                        Vec3.ZERO,
                        new Vec3(1, 0, 0),
                        .75,
                        3,
                        2,
                        3,
                        4);
        double otherSide =
                CushionPlacement.positionScore(
                        new Vec3(-2, 0, 0),
                        Vec3.ZERO,
                        Vec3.ZERO,
                        new Vec3(1, 0, 0),
                        .75,
                        3,
                        2,
                        3,
                        4);
        require(otherSide < sameSide, "Equivalent candidates favor a change of attack angle");
        require(
                !Double.isFinite(
                        CushionPlacement.positionScore(
                                new Vec3(2, 0, 0), Vec3.ZERO, Vec3.ZERO, null, .75, 3, 2, 3, 0)),
                "Invisible points cannot win ranking");
    }

    private static final class SeatWorld implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        public int getHeight() {
            return 256;
        }

        public int getMinY() {
            return 0;
        }
    }

    private static void settings() throws Exception {
        var factory =
                Class.forName("com.blanoir.moons.client.module.catalog.Combat")
                        .getDeclaredMethod("cushionAura");
        factory.setAccessible(true);
        var module = (ModuleRegistry.Module) factory.invoke(null);
        require(!module.defaultEnabled(), "New combat automation starts disabled");
        var interval = option(module, "interval");
        var radius = option(module, "radius");
        require(
                interval.value().get().getAsInt() == 1 && radius.value().get().getAsDouble() == 2,
                "Defaults match descriptors");
        interval.apply().apply(null, new JsonPrimitive(3));
        radius.apply().apply(null, new JsonPrimitive(1.7));
        require(
                interval.value().get().getAsInt() == 3 && radius.value().get().getAsDouble() == 1.7,
                "GUI setters reach runtime settings");
        require(
                module.settings().stream().filter(setting -> !setting.id().equals("hide")).count()
                        == 11,
                "All timing and geometry controls are exposed");
        for (String id :
                new String[] {
                    "interval", "mount_delay", "attack_delay", "place_turn", "mount_turn", "retry"
                }) {
            var setting = option(module, id);
            setting.apply().apply(null, new JsonPrimitive(0));
            require(setting.value().get().getAsInt() == 0, "Accept zero ticks: " + id);
            setting.apply().apply(null, new JsonPrimitive(1));
            require(setting.value().get().getAsInt() == 1, "Accept one tick: " + id);
            boolean rejected = false;
            try {
                setting.apply().apply(null, new JsonPrimitive(-1));
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            require(
                    rejected && setting.value().get().getAsInt() == 1,
                    "Reject negative ticks without changing saved value: " + id);
        }
        var wait = option(module, "attack_before_move");
        require(wait.value().get().getAsBoolean(), "Attack once before moving by default");
        wait.apply().apply(null, new JsonPrimitive(false));
        require(!CushionSettings.ATTACK_BEFORE_MOVE.get(), "Independent movement-only option");
        var timeout = option(module, "timeout");
        timeout.apply().apply(null, new JsonPrimitive(800));
        require(CushionSettings.TIMEOUT.get() == 800, "Confirmation timeout setter");
        option(module, "target_distance").apply().apply(null, new JsonPrimitive(1.5));
        option(module, "seat_distance").apply().apply(null, new JsonPrimitive(2));
        require(
                !CushionPlacement.aroundTarget(new Vec3(1, 0, 0), Vec3.ZERO, 3, null),
                "Configured minimum target distance affects planner");
        require(
                !CushionPlacement.aroundTarget(new Vec3(2, 0, 0), Vec3.ZERO, 3, new Vec3(1, 0, 0)),
                "Configured minimum relocation distance affects planner");
        if (CushionAura.supported()) {
            for (boolean previous : new boolean[] {false, true}) {
                SilentAuraConfig.enabled(previous);
                CushionAura.setEnabled(null, true);
                require(
                        SilentAuraRuntime.enabled() && SilentAuraConfig.enabled() == previous,
                        "Temporary owner preserves saved aura toggle");
                CushionAura.setEnabled(null, false);
                require(
                        SilentAuraConfig.enabled() == previous
                                && SilentAuraRuntime.enabled() == previous,
                        "Release restores ordinary aura ownership");
            }
            SilentAuraConfig.enabled(false);
        }
        Settings.flush();
        String json = Files.readString(Settings.file());
        require(
                json.contains("cushionaura.intervalTicks")
                        && json.contains("cushionaura.mountDelayTicks")
                        && json.contains("cushionaura.attackBeforeMove")
                        && json.contains("cushionaura.radius"),
                "Persist to actual profile");
    }

    private static void passengerRotationPath() throws Exception {
        if (!CushionAura.supported()) return;
        String owner = "net/minecraft/client/player/LocalPlayer";
        var target =
                CommonMappings.create().targetsForClass(owner).stream()
                        .filter(value -> value.id().equals("player.position"))
                        .findFirst()
                        .orElseThrow();
        ClassNode node = new ClassNode();
        try (var input =
                CushionAuraVerification.class
                        .getClassLoader()
                        .getResourceAsStream(owner + ".class")) {
            new ClassReader(input).accept(node, 0);
        }
        var method =
                node.methods.stream()
                        .filter(
                                value ->
                                        target.methodNames().contains(value.name)
                                                && target.descriptors().contains(value.desc))
                        .findFirst()
                        .orElseThrow();
        boolean walking = false, mounted = false;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                walking |= call.owner.equals(owner) && call.name.equals("sendPosition");
                mounted |=
                        call.owner.equals(
                                        "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$Rot")
                                && call.name.equals("<init>");
            }
        }
        require(
                walking && mounted,
                "One rotation hook must cover both walking and passenger movement sends");
    }

    private static ModuleRegistry.Setting option(ModuleRegistry.Module module, String id) {
        return module.settings().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
