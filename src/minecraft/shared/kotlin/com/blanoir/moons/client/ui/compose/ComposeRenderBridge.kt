package com.blanoir.moons.client.ui.compose

import com.blanoir.moons.client.ui.MinecraftScreenAccess
import com.blanoir.moons.client.ui.clickgui.ModuleGui
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen
import com.blanoir.moons.client.ui.hud.TextGuiSkiaOverlay
import net.minecraft.client.Minecraft

/** Draws Compose through the selected Minecraft version's final-frame surface. */
object ComposeRenderBridge {
    @JvmStatic
    fun renderCurrentScreen() {
        val screen = MinecraftScreenAccess.current(Minecraft.getInstance()) as? MoonsComposeScreen
        TextGuiSkiaOverlay.renderFrame(screen?.isHudLayoutEditing() == true)
        screen?.renderComposeFrame()
    }

    @JvmStatic
    fun close() {
        ModuleGui.close()
        TextGuiSkiaOverlay.close()
    }
}
