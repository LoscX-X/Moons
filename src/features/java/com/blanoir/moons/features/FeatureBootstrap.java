package com.blanoir.moons.features;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.command.PremiumCheckCommand;
import com.blanoir.moons.client.event.PacketEventRouter;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.module.impl.combat.CombatModuleCoordinator;
import com.blanoir.moons.client.module.impl.combat.SilentAura;
import com.blanoir.moons.client.module.impl.combat.TriggerBot;
import com.blanoir.moons.client.module.impl.combat.Reach;
import com.blanoir.moons.client.module.impl.combat.Velocity;
import com.blanoir.moons.client.module.impl.combat.AutoClicker;
import com.blanoir.moons.client.module.impl.combat.SprintReset;
import com.blanoir.moons.client.module.impl.combat.aim.AimAssist;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.movement.JumpReset;
import com.blanoir.moons.client.module.impl.movement.KeepSprint;
import com.blanoir.moons.client.module.impl.movement.NoSlow;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.module.impl.misc.AntiNick;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.module.impl.network.FakeLag;
import com.blanoir.moons.client.module.impl.network.LowHealthFakeLag;
import com.blanoir.moons.client.module.impl.network.RandomFakeLag;
import com.blanoir.moons.client.module.impl.player.AntiLava;
import com.blanoir.moons.client.module.impl.player.AntiWeb;
import com.blanoir.moons.client.module.impl.player.AutoLava;
import com.blanoir.moons.client.module.impl.player.AutoSword;
import com.blanoir.moons.client.module.impl.player.AutoTotem;
import com.blanoir.moons.client.module.impl.player.AutoWeb;
import com.blanoir.moons.client.module.impl.player.NoFall;
import com.blanoir.moons.client.module.impl.render.Caver;
import com.blanoir.moons.client.module.impl.render.Chams;
import com.blanoir.moons.client.module.impl.render.FullBright;
import com.blanoir.moons.client.module.impl.render.InventorySee;
import com.blanoir.moons.client.module.impl.render.Nametags;
import com.blanoir.moons.client.module.impl.render.Nickname;
import com.blanoir.moons.client.module.impl.render.ScoreboardChanger;
import com.blanoir.moons.client.module.impl.render.TargetInfoHud;
import com.blanoir.moons.client.module.impl.render.Trim;
import com.blanoir.moons.client.module.impl.render.UhcFinder;
import com.blanoir.moons.client.module.impl.render.xray.OreHighlighter;
import com.blanoir.moons.client.module.impl.render.xray.OreScanner;
import com.blanoir.moons.client.module.impl.render.xray.XrayDestroyPacketMode;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.rotation.RotationHistory;
import com.blanoir.moons.client.module.impl.world.AutoTool;
import com.blanoir.moons.client.module.impl.world.ChestStealer;
import com.blanoir.moons.client.module.world.FastPlace;
import com.blanoir.moons.client.module.impl.world.Scaffold;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.web.RemoteConfigClient;
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen;
import com.blanoir.moons.client.ui.clickgui.ClickGuiWarmup;
import com.blanoir.moons.client.ui.compose.ComposeRenderBridge;
import com.blanoir.moons.features.catalog.ModuleCatalog;
import net.minecraft.client.Minecraft;

/** Starts and stops feature listeners in their established runtime order. */
final class FeatureBootstrap {
    private FeatureBootstrap() { }

    static void initialize() {
        Settings.load();
        ModuleRegistry.installCatalog(ModuleCatalog::register);
        RotationHistory.init();

        NoFall.init();
        OreScanner.init();
        OreHighlighter.init();
        XrayDestroyPacketMode.init();
        Nickname.init();
        PremiumCheckCommand.init();
        CombatInputController.init();
        AntiBot.init();
        AntiNick.init();
        AimAssist.init();
        JumpReset.init();
        Velocity.init();
        Critical.init();
        Reach.init();
        TriggerBot.init();
        AutoClicker.init();
        SprintReset.init();
        SilentAura.init();
        CombatModuleCoordinator.reconcileConfiguredState(Minecraft.getInstance());
        ScoreboardChanger.init();
        InventorySee.init();
        TargetInfoHud.init();
        NoSlow.init();
        Nametags.init();
        Minecraft client = Minecraft.getInstance();
        if (Caver.isEnabled() && client.level != null) {
            MinecraftClientAccess.rebuildLevelRenderer(client);
        }
        FullBright.init();
        UhcFinder.init();
        ChestStealer.init();
        Scaffold.init();
        FastPlace.init();
        Backtrack.init();
        KeepSprint.init();
        AutoTotem.init();
        AutoTool.init();
        AutoSword.init();
        AutoLava.init();
        PacketEventRouter.init();
        Velocity.initIncomingTail();
        SilentPacketRotation.init();
        AntiWeb.init();
        AntiLava.init();
        AutoWeb.init();
        LowHealthFakeLag.init();
        RandomFakeLag.init();
        FakeLag.init();
        RemoteConfigClient.init();
        RemoteConfigClient.autoConnect();
        ClickGuiWarmup.start();
    }

    static void shutdown() {
        RotationHistory.reset();
        Minecraft client = Minecraft.getInstance();
        NoSlow.shutdown();
        Velocity.shutdown();
        if (MinecraftClientAccess.screen(client) instanceof MoonsComposeScreen) {
            MinecraftClientAccess.setScreen(client, null);
        }
        ComposeRenderBridge.close();
        RemoteConfigClient.shutdown();
        PremiumCheckCommand.shutdown();
        AntiNick.shutdown();
        OreScanner.shutdown();
        OreHighlighter.close();
        Chams.close();
        Trim.close();
        com.blanoir.moons.client.module.impl.network.backtrack.BacktrackRenderer.close();
        WorldOverlayRenderer.close();
    }
}
