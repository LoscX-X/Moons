package com.blanoir.moons.features.catalog;

import com.blanoir.moons.client.module.framework.ModuleRegistry;

/** Defines module initialization and presentation order. */
public final class ModuleCatalog {
    private ModuleCatalog() {}

    public static void register() {
        Player.initialize();
        ModuleRegistry.add(Combat.autoClicker());
        ModuleRegistry.add(Combat.reach());
        ModuleRegistry.add(Combat.sprintReset());
        ModuleRegistry.add(Combat.silentAura());
        ModuleRegistry.add(Combat.aimAssist());
        ModuleRegistry.add(Combat.triggerBot());
        ModuleRegistry.add(Combat.critical());
        ModuleRegistry.add(Combat.jumpReset());
        ModuleRegistry.add(Network.backtrack());
        ModuleRegistry.add(Movement.keepSprint());
        ModuleRegistry.add(Player.autoWeb());
        ModuleRegistry.add(Player.autoLava());
        ModuleRegistry.add(Player.autoBed());
        ModuleRegistry.add(Player.antiLava());
        ModuleRegistry.add(Player.antiWeb());
        ModuleRegistry.add(Player.autoSword());
        ModuleRegistry.add(Movement.sprint());
        ModuleRegistry.add(Movement.noSlow());
        ModuleRegistry.add(Movement.noJumpDelay());
        ModuleRegistry.add(World.fastPlace());
        ModuleRegistry.add(Player.autoTotem());
        ModuleRegistry.add(Player.autoHead());
        ModuleRegistry.add(Player.noFall());
        ModuleRegistry.add(Network.fakeLag());
        ModuleRegistry.add(Render.clickGui());
        ModuleRegistry.add(Render.blockAnimation());
        ModuleRegistry.add(Render.hud());
        ModuleRegistry.add(Render.scoreboard());
        ModuleRegistry.add(Render.inventory());
        ModuleRegistry.add(Render.targetInfo());
        ModuleRegistry.add(Render.nametags());
        ModuleRegistry.add(Render.chams());
        ModuleRegistry.add(Render.fullBright());
        ModuleRegistry.add(Render.caver());
        ModuleRegistry.add(Render.clip());
        ModuleRegistry.add(Render.uhcFinder());
        ModuleRegistry.add(Xray.display());
        ModuleRegistry.add(Xray.xray());
        ModuleRegistry.add(Xray.targets());
        ModuleRegistry.add(World.autoTool());
        ModuleRegistry.add(World.scaffold());
        ModuleRegistry.add(World.fastBreak());
        ModuleRegistry.add(World.chestStealer());
        ModuleRegistry.add(World.lightningTracker());
        ModuleRegistry.add(Misc.chatFilter());
        ModuleRegistry.add(Misc.chatPrefix());
        ModuleRegistry.add(Render.trim());
        ModuleRegistry.add(Render.offlinePlayerDetect());
        ModuleRegistry.add(Misc.premiumCheck());
        ModuleRegistry.add(Misc.staticFov());
        ModuleRegistry.add(Misc.armorHide());
        ModuleRegistry.add(Misc.antiNick());
        ModuleRegistry.add(Misc.antiBot());
    }
}
