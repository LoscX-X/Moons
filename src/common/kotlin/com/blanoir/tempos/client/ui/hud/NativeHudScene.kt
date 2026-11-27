package com.blanoir.tempos.client.ui.hud

import com.blanoir.tempos.client.event.EventBus
import com.blanoir.tempos.client.access.MinecraftClientAccess
import com.blanoir.tempos.client.api.ClientUiScreen
import com.blanoir.tempos.client.ui.MinecraftUiBackend
import com.blanoir.tempos.client.ui.UiScreen
import com.blanoir.tempos.client.ui.host.MinecraftScreenAccess
import com.blanoir.tempos.client.ui.host.LayerPresentation
import com.blanoir.tempos.client.config.Settings
import com.blanoir.tempos.client.ui.clickgui.ThemePreferences
import com.blanoir.tempos.client.ui.theme.Theme
import com.blanoir.tempos.client.utils.time.FrameClock
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/** One ordinary game GUI layer, submitted in HUD order through the game's graphics extractor. */
object NativeHudScene {
    private val id = Identifier.fromNamespaceAndPath("tempos","gui/hud_layer")
    private val raster = HudRasterCache()
    private val animation = FrameClock(0.0)
    private var origin = 0L
    private var target: RenderTarget? = null
    private var backend: MinecraftUiBackend? = null
    private val nameTheme = Theme()
    private var settingsRevision = -1L
    private var followNames = false

    @JvmStatic fun install() {
        EventBus.HUD_RENDER.register("tempos.hud") { event -> extractGameHud(event.graphics()) }
    }

    @JvmStatic fun extractGameHud(graphics: GuiGraphicsExtractor) {
        val client = Minecraft.getInstance()
        val screen = MinecraftScreenAccess.current(client)
        if (screen is ClientUiScreen || MinecraftClientAccess.isHudHidden(client)) {
            animation.reset()
            return
        }
        extract(graphics,false)
    }

    @JvmStatic fun extract(graphics: GuiGraphicsExtractor, editing: Boolean) {
        val client = Minecraft.getInstance()
        if (client.level == null || client.player == null) {
            NativeTextGui.reset()
            NativeHudExtras.reset()
            raster.reset()
            animation.reset()
            return
        }
        val win = client.window
        val width = win.width
        val height = win.height
        if (width < 1 || height < 1) return
        val gpu = backend ?: MinecraftUiBackend { requireNotNull(target) }.also { backend = it }
        val now = System.nanoTime()
        if (origin == 0L) origin = now
        val seconds = (now-origin)/1_000_000_000.0
        val revision = Settings.revision()
        if (settingsRevision != revision) {
            followNames = ThemePreferences.hudRgb() && Settings.getString("clickgui.theme","brand") == "rgb"
            if (followNames) ThemePreferences(nameTheme)
            settingsRevision = revision
        }
        if (followNames) nameTheme.frame(now)
        val painter = raster.painter()
        painter.begin(width,height,1f)
        NativeTextGui.draw(painter,gpu,client,editing,seconds,animation.nextDeltaSeconds(now),nameTheme.takeIf { followNames })
        NativeHudExtras.draw(painter,gpu,client,editing,seconds)
        val batch = painter.end()
        if (batch.rangeCount() == 0) { raster.reset(); return }
        val old = target
        if (old == null || old.width != width || old.height != height) {
            if (old != null) client.textureManager.release(id)
            val attachment = UiScreen.newLayer("Tempos HUD",width,height)
            UiScreen.registerLayer(id,attachment)
            target = attachment
        }
        val surface = target?.colorTextureView ?: return
        if (!raster.canReuse(batch,surface,client.level)) gpu.tryDrawCleared(batch)
        raster.remember(batch,surface,client.level)
        LayerPresentation.blit(graphics,id,batch,win.guiScaledWidth,win.guiScaledHeight,0,-1)
    }

    @JvmStatic fun close() {
        NativeTextGui.close()
        NativeHudExtras.close()
        raster.reset()
        backend?.close()
        backend = null
        if (target != null) Minecraft.getInstance().textureManager.release(id)
        target = null
        animation.reset()
        origin = 0L
        settingsRevision = -1L
    }
}
