package com.blanoir.tempos.client.module.impl.combat.cushionaura;

import com.blanoir.tempos.client.access.CushionAccess;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.catalog.ModuleCatalog;
import com.blanoir.tempos.client.module.framework.ModuleKeybinds;
import com.blanoir.tempos.client.module.framework.ModuleRegistry;
import com.blanoir.tempos.client.module.impl.combat.TriggerBot;
import com.blanoir.tempos.client.module.impl.combat.cushionaura.CushionPlacement.Plan;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.google.gson.JsonPrimitive;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Real planner ordering, runtime settings/ownership and tick/ack policy without a game window. */
public final class CusionRunVerification {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path home = Path.of(System.getProperty("tempos.home"));
        Files.createDirectories(home);
        Settings.configure(Files.createTempDirectory(home, "cusion-run-"));
        route();
        timing();
        settingsAndSuspension();
        disabledBuild();
        System.out.println(
                "TEMPOS_CUSION_RUN_VERIFIED checks="
                        + checks
                        + " supported="
                        + CushionAccess.supported()
                        + " available=" + CusionRun.available());
    }

    private static Plan seat(double x, double y, double z, int id) {
        return new Plan(null, new Vec3(x, y, z), -1, id);
    }

    private static void route() {
        Vec3 origin = new Vec3(.5, 1, .5), eye = origin.add(0, 1.62, 0);
        var east = new CusionRunPlacement.Course(origin, eye, new Vec3(1, 0, 0));
        Plan near = seat(1.5, 1, .5, 1), far = seat(3.5, 1, .5, 2);
        Plan blocked = seat(4.5, 1, .5, 3), behind = seat(-3.5, 1, .5, 4);
        Plan sideways = seat(5.5, 1, 2.5, 5);
        List<Plan> candidates = List.of(near, sideways, behind, far, blocked);
        AtomicInteger validations = new AtomicInteger();
        Plan chosen =
                CusionRunPlacement.farthest(
                        east,
                        candidates,
                        plan -> {
                            validations.incrementAndGet();
                            return plan != blocked;
                        });
        require(
                chosen == far,
                "Choose the farthest valid forward spot, not the first/nearest or unreachable farthest");
        require(
                validations.get() == 2,
                "Reject wrong directions before expensive native checks and stop at the best usable candidate");
        var west = new CusionRunPlacement.Course(origin, eye, new Vec3(-1, 0, 0));
        require(
                CusionRunPlacement.farthest(west, candidates, plan -> true) == behind,
                "A camera reversal changes the destination without an enemy target");
        var diagonal = new CusionRunPlacement.Course(origin, eye, new Vec3(1, 0, 1).normalize());
        Plan across = seat(2.5, 1, 2.5, 6);
        require(
                CusionRunPlacement.farthest(diagonal, List.of(far, across), plan -> true) == across,
                "Diagonal camera direction follows the diagonal grid corridor");
        var up = new CusionRunPlacement.Course(origin, eye, new Vec3(1, 2, 0).normalize());
        require(
                CusionRunPlacement.farthest(up, List.of(near), plan -> true) == null,
                "A spot behind the vertical camera direction is not selected");
        require(
                !Double.isFinite(east.progress(origin.add(.2, 0, 0))),
                "Do not repeatedly mount almost the same position");
        AtomicInteger placements = new AtomicInteger();
        require(
                CushionPlacement.preferExisting(
                                        () -> near,
                                        () -> {
                                            placements.incrementAndGet();
                                            return far;
                                        })
                                == near
                        && placements.get() == 0,
                "Existing seats bypass inventory and new placement even when a new spot is farther");
        require(
                CushionPlacement.preferExisting(() -> null, () -> far) == far,
                "Only lack of a usable seat permits a new placement");
    }

    private static void timing() throws Exception {
        var module = CusionRun.INSTANCE;
        var stateField = CusionRun.class.getDeclaredField("cycle");
        stateField.setAccessible(true);
        CushionCycle cycle = (CushionCycle) stateField.get(module);
        var tick = CusionRun.class.getDeclaredField("tickNumber");
        tick.setAccessible(true);
        long now = 1_000_000_000L;
        cycle.enter(CushionCycle.Phase.PLACE, now, 10);
        tick.setInt(module, 10);
        for (int frame = 0; frame < 120; frame++)
            require(
                    !module.placementReady(true),
                    "Extra frame callbacks cannot spend the one-tick placement delay");
        tick.setInt(module, 11);
        require(!module.placementReady(false), "Delay alone cannot replace a sent rotation packet");
        require(
                module.placementReady(true),
                "Default placement becomes eligible after exactly one tick and sent rotation");
        cycle.enter(CushionCycle.Phase.WAIT_ENTITY, now, 11);
        require(
                !module.placementReady(true),
                "A used item cannot place again while awaiting a real entity");
        cycle.enter(CushionCycle.Phase.WAIT_RIDE, now, 12);
        require(
                !cycle.acknowledgeRide(true, false, now, 12),
                "Local mount prediction cannot begin travel");
        require(
                !cycle.acknowledgeRide(false, true, now, 12),
                "Server packet must also be applied to the expected local vehicle");
        require(
                cycle.acknowledgeRide(true, true, now, 18),
                "Delayed confirmed mount completes when both observations agree");
        cycle.enter(CushionCycle.Phase.DISMOUNT, now, 18);
        require(
                !cycle.acknowledgeDismount(false, true, now),
                "Local exit alone cannot authorize searching from a predicted exit");
        require(
                !cycle.acknowledgeDismount(true, false, now),
                "Remote exit alone cannot skip local dismount");
        require(
                cycle.acknowledgeDismount(false, false, now),
                "Confirmed exit allows a fresh camera-directed search");
        module.shutdown(null);
        require(
                cycle.phase() == CushionCycle.Phase.SEARCH && !module.placementReady(true),
                "Cleanup clears pending placement state");
    }

    private static void settingsAndSuspension() throws Exception {
        var feature = CusionRun.INSTANCE;
        var module = feature.toDescriptor();
        require(
                module.category() == ModuleCategory.EXPERIMENT && !module.defaultEnabled(),
                "Experiment category, opt-in by default");
        require(
                CusionRunSettings.TURN.get() == 1 && CusionRunSettings.PLACE_DELAY.get() == 1,
                "Rotation and placement wait both default to one tick");
        var turn =
                module.settings().stream()
                        .filter(setting -> setting.id().equals("turn"))
                        .findFirst()
                        .orElseThrow();
        turn.apply().apply(null, new JsonPrimitive(2));
        require(
                CusionRunSettings.TURN.get() == 2,
                "Actual GUI setter reaches the run's rotation setting");
        turn.apply().apply(null, new JsonPrimitive(1));
        int auraTurn = CushionSettings.PLACE_TURN.get(),
                auraInterval = CushionSettings.INTERVAL.get();
        if (CusionRun.available()) {
            SilentAuraConfig.enabled(true);
            var synthetic = CombatInputController.class.getDeclaredField("syntheticAttackDown");
            synthetic.setAccessible(true);
            synthetic.setBoolean(null, true);
            feature.setEnabled(null, true);
            require(
                    !synthetic.getBoolean(null),
                    "Enabling the run releases an earlier owned automatic press");
            require(
                    feature.isEnabled() && CombatModuleCoordinator.automationSuspended(),
                    "Run immediately owns the automation suspension");
            require(
                    SilentAuraConfig.enabled() && !SilentAuraRuntime.enabled(),
                    "Saved Aura toggle stays on while runtime execution is suspended");
            feature.setEnabled(null, false);
            require(
                    !CombatModuleCoordinator.automationSuspended() && SilentAuraRuntime.enabled(),
                    "Disabling the run restores the saved Aura execution eligibility");
            SilentAuraConfig.enabled(false);
            TriggerBot.INSTANCE.setEnabled(null, true);
            feature.setEnabled(null, true);
            require(TriggerBot.INSTANCE.isEnabled(), "TriggerBot's saved toggle is not rewritten");
            feature.setEnabled(null, false);
            require(TriggerBot.INSTANCE.isEnabled(), "TriggerBot stays enabled after the run");
            TriggerBot.INSTANCE.setEnabled(null, false);
            if (CushionAura.available()) {
                CushionAura.INSTANCE.setEnabled(null, true);
                feature.setEnabled(null, true);
                require(
                        CushionAura.INSTANCE.isEnabled() && !SilentAuraRuntime.enabled(),
                        "CushionAura also keeps its toggle without driving combat");
                feature.setEnabled(null, false);
                require(
                        SilentAuraRuntime.enabled(),
                        "CushionAura's ordinary execution eligibility returns");
                CushionAura.INSTANCE.setEnabled(null, false);
            }
        } else {
            require(
                    feature.setEnabled(null, true) == 0
                            && !feature.isEnabled()
                            && !CombatModuleCoordinator.automationSuspended(),
                    "Unavailable cushion travel cannot activate or suppress combat");
        }
        require(
                CushionSettings.PLACE_TURN.get() == auraTurn
                        && CushionSettings.INTERVAL.get() == auraInterval,
                "Run settings and toggles never alter the preexisting cushion timings");
        Settings.flush();
        require(
                Files.readString(Settings.file()).contains("cusionrun.turnTicks"),
                "Run parameters persist through the real settings profile");
    }

    private static void disabledBuild() {
        require(!CusionRun.available(), "CusionRun is temporarily unavailable in this release");
        Settings.setBoolean("cusionrun.enabled", true);
        Settings.setString("keybind.cusionrun", "key.keyboard.f9");
        try {
            require(!CusionRun.INSTANCE.isEnabled() && CusionRun.INSTANCE.setEnabled(null, true) == 0,
                    "Old enabled profiles and direct calls cannot activate CusionRun");
            require(!CombatModuleCoordinator.automationSuspended(),
                    "Stored CusionRun state cannot suppress TriggerBot or other automatic combat");
            ModuleRegistry.installCatalog(ModuleCatalog::register);
            require(ModuleRegistry.modules().stream().noneMatch(m -> m.id().equals("cusionrun")),
                    "No CusionRun entry remains in menus, HUD or commands");
            require(ModuleKeybinds.modulesFor(ModuleKeybinds.getBoundKey("cusionrun")).stream()
                            .noneMatch(m -> m.id().equals("cusionrun")),
                    "Stored CusionRun bindings cannot activate it");
            var events = List.of(EventBus.CLIENT_CONTEXT_CHANGED, EventBus.TICK, EventBus.FRAME,
                    EventBus.PLAYER_UPDATE, EventBus.PACKET_RECEIVE_APPLY, EventBus.MOVEMENT_INPUT_UPDATED);
            int before = events.stream().mapToInt(event -> event.listenerCount()).sum();
            CusionRun.INSTANCE.registerEvents();
            require(events.stream().mapToInt(event -> event.listenerCount()).sum() == before,
                    "Disabled CusionRun installs no movement or placement callbacks");
            TriggerBot.INSTANCE.setEnabled(null, true);
            require(TriggerBot.INSTANCE.isEnabled() && !CombatModuleCoordinator.automationSuspended(),
                    "TriggerBot remains enabled and free of the old run's execution gate");
            for (boolean ordinaryAura : new boolean[] {false, true}) {
                SilentAuraConfig.enabled(ordinaryAura);
                require(SilentAuraRuntime.enabled() == ordinaryAura,
                        "Stored CusionRun state does not change SilentAura runtime eligibility");
            }
        } finally {
            TriggerBot.INSTANCE.setEnabled(null, false);
            SilentAuraConfig.enabled(false);
            Settings.remove("cusionrun.enabled");
            Settings.remove("keybind.cusionrun");
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
