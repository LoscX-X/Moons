package com.blanoir.tempos.client.lifecycle;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.command.PremiumCheckCommand;
import com.blanoir.tempos.client.config.ConfigProfiles;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.event.network.PacketEventAdapter;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.combat.CriticalHitTracker;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.rotation.RotationManager;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.manager.time.TimerManager;
import com.blanoir.tempos.client.module.catalog.ModuleCatalog;
import com.blanoir.tempos.client.module.framework.ModuleRegistry;
import com.blanoir.tempos.client.module.impl.combat.AutoBlock;
import com.blanoir.tempos.client.module.impl.combat.AutoClicker;
import com.blanoir.tempos.client.module.impl.combat.AutoMace;
import com.blanoir.tempos.client.module.impl.combat.AutoSpear;
import com.blanoir.tempos.client.module.impl.combat.HitSelect;
import com.blanoir.tempos.client.module.impl.combat.Misplace;
import com.blanoir.tempos.client.module.impl.combat.Reach;
import com.blanoir.tempos.client.module.impl.combat.SilentAura;
import com.blanoir.tempos.client.module.impl.combat.SprintReset;
import com.blanoir.tempos.client.module.impl.combat.TriggerBot;
import com.blanoir.tempos.client.module.impl.combat.Velocity;
import com.blanoir.tempos.client.module.impl.combat.aim.AimAssist;
import com.blanoir.tempos.client.module.impl.combat.critical.Critical;
import com.blanoir.tempos.client.module.impl.combat.cushionaura.CushionAura;
import com.blanoir.tempos.client.module.impl.combat.cushionaura.CusionRun;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraBlock;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.tempos.client.module.impl.misc.AntiNick;
import com.blanoir.tempos.client.module.impl.misc.CheckPremium;
import com.blanoir.tempos.client.module.impl.misc.FreeLook;
import com.blanoir.tempos.client.module.impl.misc.aimdata.AimCollect;
import com.blanoir.tempos.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.tempos.client.module.impl.movement.KeepSprint;
import com.blanoir.tempos.client.module.impl.movement.Strafe;
import com.blanoir.tempos.client.module.impl.network.Backtrack;
import com.blanoir.tempos.client.module.impl.network.Disabler;
import com.blanoir.tempos.client.module.impl.network.FakeLag;
import com.blanoir.tempos.client.module.impl.player.AntiLava;
import com.blanoir.tempos.client.module.impl.player.AntiWeb;
import com.blanoir.tempos.client.module.impl.player.AutoArmor;
import com.blanoir.tempos.client.module.impl.player.AutoBed;
import com.blanoir.tempos.client.module.impl.player.AutoHead;
import com.blanoir.tempos.client.module.impl.player.AutoLava;
import com.blanoir.tempos.client.module.impl.player.AutoMLG;
import com.blanoir.tempos.client.module.impl.player.AutoTotem;
import com.blanoir.tempos.client.module.impl.player.AutoWeb;
import com.blanoir.tempos.client.module.impl.player.InvClear;
import com.blanoir.tempos.client.module.impl.player.RightClick;
import com.blanoir.tempos.client.module.impl.player.autosword.AutoSword;
import com.blanoir.tempos.client.module.impl.player.blockin.BlockIn;
import com.blanoir.tempos.client.module.impl.player.blockin.BlockInRuntime;
import com.blanoir.tempos.client.module.impl.player.invmanager.InvManager;
import com.blanoir.tempos.client.module.impl.render.Caver;
import com.blanoir.tempos.client.module.impl.render.Chams;
import com.blanoir.tempos.client.module.impl.render.FullBright;
import com.blanoir.tempos.client.module.impl.render.InventorySee;
import com.blanoir.tempos.client.module.impl.render.Nametags;
import com.blanoir.tempos.client.module.impl.render.Nickname;
import com.blanoir.tempos.client.module.impl.render.NicknameShuffle;
import com.blanoir.tempos.client.module.impl.render.Scoreboard;
import com.blanoir.tempos.client.module.impl.render.TargetInfoHud;
import com.blanoir.tempos.client.module.impl.render.UhcFinder;
import com.blanoir.tempos.client.module.impl.render.xray.OreHighlighter;
import com.blanoir.tempos.client.module.impl.render.xray.OreScanner;
import com.blanoir.tempos.client.module.impl.render.xray.XrayDestroyPacketMode;
import com.blanoir.tempos.client.module.impl.render.xray.XrayDisplay;
import com.blanoir.tempos.client.module.impl.world.AutoTool;
import com.blanoir.tempos.client.module.impl.world.ChestStealer;
import com.blanoir.tempos.client.module.impl.world.FastBreak;
import com.blanoir.tempos.client.module.impl.world.FastPlace;
import com.blanoir.tempos.client.module.impl.world.Ignore;
import com.blanoir.tempos.client.module.impl.world.LightningTracker;
import com.blanoir.tempos.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.tempos.client.module.impl.world.scaffold.ScaffoldManager;
import com.blanoir.tempos.client.module.impl.world.structure.StructureLocate;
import com.blanoir.tempos.client.render.WorldOverlayRenderer;
import com.blanoir.tempos.client.render.animation.Animations;
import com.blanoir.tempos.client.ui.host.UiHost;

import net.minecraft.client.Minecraft;

/** Starts and stops feature listeners in their established runtime order. */
final class ClientLifecycle {
    private static boolean transientReleased;

    private ClientLifecycle() {}

    static void initialize() {
        Settings.load();
        com.blanoir.tempos.client.ui.hud.NativeHudScene.install();
        ModuleRegistry.installCatalog(ModuleCatalog::register);
        RotationManager.init();
        HotbarLease.init();
        FreeLook.INSTANCE.registerEvents();
        Ignore.INSTANCE.registerEvents();

        AutoMLG.INSTANCE.registerEvents();
        OreScanner.init();
        StructureLocate.INSTANCE.registerEvents();
        XrayDisplay.INSTANCE.registerEvents();
        XrayDestroyPacketMode.init();
        Nickname.init();
        CheckPremium.INSTANCE.registerEvents();
        CombatInputController.init();
        HitSelect.INSTANCE.registerEvents();
        AntiBot.INSTANCE.registerEvents();
        AntiNick.INSTANCE.setShuffledNamesCheck(() -> NicknameShuffle.isEnabled());
        AntiNick.INSTANCE.registerEvents();
        AimAssist.INSTANCE.registerEvents();
        Velocity.INSTANCE.registerJumpResetEvents();
        Velocity.INSTANCE.registerEvents();
        Critical.INSTANCE.registerEvents();
        Reach.INSTANCE.registerEvents();
        TriggerBot.INSTANCE.registerEvents();
        AutoClicker.INSTANCE.registerEvents();
        RightClick.INSTANCE.registerEvents();
        AutoSpear.INSTANCE.registerEvents();
        AutoMace.INSTANCE.registerEvents();
        SprintReset.INSTANCE.registerEvents();
        Strafe.INSTANCE.registerEvents();
        SilentAura.INSTANCE.registerEvents();
        CushionAura.INSTANCE.registerEvents();
        CusionRun.INSTANCE.registerEvents();
        Animations.bindCombatState(
                () -> AutoBlock.INSTANCE.isEnabled() || SilentAuraBlock.isEnabled(),
                client ->
                        SilentAuraBlock.controls(client)
                                ? SilentAuraBlock.shouldRenderBlock(client)
                                : AutoBlock.INSTANCE.shouldRenderBlock(client),
                () ->
                        SilentAuraBlock.controls(Minecraft.getInstance())
                                ? SilentAuraBlock.attackAnimationOnly()
                                : AutoBlock.INSTANCE.attackAnimationOnly(),
                () ->
                        SilentAuraBlock.controls(Minecraft.getInstance())
                                ? SilentAuraBlock.animationProgress()
                                : AutoBlock.INSTANCE.animationProgress(),
                () ->
                        SilentAuraBlock.controls(Minecraft.getInstance())
                                && SilentAuraBlock.attackAnimationOnly());
        AutoBlock.INSTANCE.registerEvents();
        SilentAuraBlock.init();
        CombatModuleCoordinator.reconcileConfiguredState(Minecraft.getInstance());
        Scoreboard.INSTANCE.registerEvents();
        InventorySee.INSTANCE.registerEvents();
        TargetInfoHud.INSTANCE.registerEvents();
        Nametags.INSTANCE.registerEvents();
        Minecraft client = Minecraft.getInstance();
        FreeLook.INSTANCE.reset(client);
        if (Caver.isEnabled() && client.level != null) {
            MinecraftClientAccess.rebuildLevelRenderer(client);
        }
        FullBright.INSTANCE.registerEvents();
        UhcFinder.INSTANCE.registerEvents();
        ChestStealer.INSTANCE.registerEvents();
        Scaffold.INSTANCE.registerEvents();
        FastPlace.INSTANCE.registerEvents();
        Backtrack.INSTANCE.registerEvents();
        Misplace.INSTANCE.setNetworkDelayCheck(
                () -> Backtrack.INSTANCE.isEnabled() || FakeLag.INSTANCE.isEnabled());
        Misplace.INSTANCE.registerEvents();
        KeepSprint.INSTANCE.registerEvents();
        AutoTotem.INSTANCE.registerEvents();
        InvManager.INSTANCE.registerEvents();
        AutoArmor.INSTANCE.registerEvents();
        InvClear.INSTANCE.registerEvents();
        TimerManager.init();
        AutoTool.INSTANCE.registerEvents();
        AutoSword.INSTANCE.registerEvents();
        AutoLava.INSTANCE.registerEvents();
        AutoHead.INSTANCE.setScaffoldCheck(() -> Scaffold.INSTANCE.isEnabled());
        AutoHead.INSTANCE.registerEvents();
        AutoBed.INSTANCE.registerEvents();
        initializePacketListeners();
        SilentPacketRotation.init();
        AntiWeb.INSTANCE.registerEvents();
        AntiLava.INSTANCE.registerEvents();
        AutoWeb.INSTANCE.registerEvents();
        BlockIn.INSTANCE.registerEvents();
        FakeLag.INSTANCE.registerEvents();
        ConfigProfiles.initialize();
    }

    static void start() {
        transientReleased = false;
    }

    static void resume() {
        transientReleased = false;
    }

    static void suspend() {
        CleanupSequence.run(
                "core suspension",
                PremiumCheckCommand::suspend,
                AntiNick.INSTANCE::suspend,
                StructureLocate.INSTANCE::suspend,
                OreScanner::suspend,
                () -> releaseTransientState(Minecraft.getInstance()));
    }

    private static void initializePacketListeners() {
        // This block stays between Backtrack and SilentPacketRotation registration.
        // SEND: AutoSpear release filter -> Disabler -> attack snapshot -> lag modes -> FastBreak.
        // RECEIVE: bundle intent -> Velocity -> lag modes -> Backtrack.
        // APPLY: scoreboard -> damage confirmation -> JumpReset -> lightning.
        PacketEventAdapter.initPacketListeners();
        AimCollect.INSTANCE.registerEvents();
        AutoSpear.INSTANCE.initPacketListeners();
        Disabler.initPacketListeners();
        Scoreboard.INSTANCE.initPacketListeners();
        CriticalHitTracker.initPacketListeners();
        Velocity.INSTANCE.initPacketListeners();
        FakeLag.INSTANCE.initPacketListeners();
        FastBreak.INSTANCE.initPacketListeners();
        Backtrack.INSTANCE.initPacketListeners();
        Velocity.INSTANCE.registerJumpResetPacketListeners();
        LightningTracker.INSTANCE.initPacketListeners();
    }

    /** Panic stops features while the core stays active and can acquire new state afterward. */
    public static void panic(Minecraft client) {
        releaseTransientState(client);
        transientReleased = false;
    }

    private static void releaseTransientState(Minecraft client) {
        CleanupSequence.run(
                "core transient state",
                Backtrack.INSTANCE::suspend,
                () -> AimCollect.INSTANCE.shutdown(),
                () -> Disabler.discardPending(),
                () -> FakeLag.INSTANCE.discardPending(),
                () -> CushionAura.INSTANCE.shutdown(client),
                () -> CusionRun.INSTANCE.shutdown(client),
                () -> RotationManager.reset(),
                () -> FreeLook.INSTANCE.reset(client),
                () -> AutoBlock.INSTANCE.suspend(client),
                () -> AutoSpear.INSTANCE.suspend(client),
                () -> AutoMace.INSTANCE.reset(client),
                () -> Strafe.INSTANCE.reset(),
                () -> AutoClicker.INSTANCE.reset(client),
                () -> RightClick.INSTANCE.reset(),
                () -> InvManager.INSTANCE.reset(),
                () -> AutoArmor.INSTANCE.reset(),
                () -> InvClear.INSTANCE.reset(),
                () -> TimerManager.reset(),
                SilentAuraBlock::suspend,
                () -> SilentAuraRuntime.reset(client),
                () -> AutoMLG.INSTANCE.shutdown(client),
                () -> AutoBed.INSTANCE.shutdown(client),
                () -> AutoWeb.INSTANCE.shutdown(client),
                () -> BlockInRuntime.shutdown(client),
                () -> AutoLava.INSTANCE.shutdown(client),
                () -> AntiLava.INSTANCE.shutdown(client),
                () -> AntiWeb.INSTANCE.shutdown(client),
                () -> ScaffoldManager.shutdown(client),
                () -> SilentPacketRotation.discard(),
                () -> HotbarLease.resetAll(client),
                () -> CombatInputController.reset(client),
                () -> UiHost.close(client));
        transientReleased = true;
    }

    static void shutdown() {
        Settings.flush();
        Minecraft client = Minecraft.getInstance();
        CleanupSequence.run(
                "core feature shutdown",
                () -> {
                    if (!transientReleased) releaseTransientState(client);
                },
                () -> com.blanoir.tempos.client.render.VisualPresentation.close(),
                () -> com.blanoir.tempos.client.render.VisualRenderTargets.close(),
                () -> com.blanoir.tempos.client.ui.hud.NativeHudScene.close(),
                () -> com.blanoir.tempos.client.ui.render.SmoothGui.close(),
                () -> PremiumCheckCommand.shutdown(),
                () -> AntiNick.INSTANCE.shutdown(),
                () -> NicknameShuffle.reset(),
                () -> OreScanner.shutdown(),
                () -> StructureLocate.INSTANCE.shutdown(),
                () -> com.blanoir.tempos.client.render.StructureLabelRenderer.close(),
                () -> OreHighlighter.close(),
                () -> Chams.close(),
                () ->
                        com.blanoir.tempos.client.module.impl.network.backtrack.BacktrackRenderer
                                .close(),
                () -> WorldOverlayRenderer.close());
    }
}
