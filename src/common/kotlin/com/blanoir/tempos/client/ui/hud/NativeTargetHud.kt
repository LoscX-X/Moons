package com.blanoir.tempos.client.ui.hud

import com.blanoir.tempos.client.api.hud.HudElements
import com.blanoir.tempos.client.ui.paint.UiBackend
import com.blanoir.tempos.client.ui.paint.UiFont
import com.blanoir.tempos.client.ui.paint.UiPainter
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import kotlin.math.min

/** A compact player face, name, health bar and the feature's existing hit readout. */
internal object NativeTargetHud {
    private var font: UiFont? = null
    private var owner: UiBackend? = null
    private var fontScale = 0f
    private var pixelFont = false
    private var fullName: String? = null
    private var shownName = ""

    fun draw(p: UiPainter, backend: UiBackend, target: HudElements.Target, client: Minecraft, gui: Float) {
        if (!target.visible()) return
        val s = HudElements.TARGET.scale().toFloat()*gui
        val pixel = HudConfig.FONT_MODE.get()==HudConfig.HudFontMode.MINECRAFT
        if (owner !== backend || s != fontScale || pixel != pixelFont || font == null) {
            close()
            owner = backend
            font = UiFont.hud(backend,s*8/9,pixel)
            fontScale = s
            pixelFont = pixel
        }
        val label = font!!
        if (target.name() != fullName) {
            fullName = target.name()
            shownName = fit(label,target.name(),83*s)
        }
        val x = (target.bounds().x().toFloat()+target.slide())*gui
        val y = target.bounds().y().toFloat()*gui
        val alpha = target.alpha().coerceIn(0,255)
        fun color(rgb: Int, limit: Int = 255) = (min(alpha,limit) shl 24) or (rgb and 0xffffff)
        val shadow = TemposHud.isTextShadowEnabled()
        p.fill(x,y,TargetHudStyle.WIDTH*s,TargetHudStyle.HEIGHT*s,3*s,color(0x141414,196))
        val faceX = x+TargetHudStyle.PADDING*s
        val faceY = y+8*s
        val size = TargetHudStyle.FACE*s
        p.fill(faceX,faceY,size,size,0f,color(0x303030))
        val id = target.playerId() ?: client.player?.uuid
        val skin = id?.let { client.connection?.getPlayerInfo(it)?.skin ?: DefaultPlayerSkin.get(it) }
            ?: DefaultPlayerSkin.getDefaultSkin()
        val texture = backend.borrowGameTexture(skin.body().texturePath())
        if (texture != null) {
            // Both face and hat come directly from the game's current skin texture.
            p.image(texture,faceX,faceY,size,size,8/64f,8/64f,16/64f,16/64f,color(0xffffff))
            p.image(texture,faceX,faceY,size,size,40/64f,8/64f,48/64f,16/64f,color(0xffffff))
        }
        val textX = x+29*s
        label.hudText(p,shownName,textX,y+7*s,10*s,color(0xf2f2f2),shadow)
        label.hudText(p,target.healthText(),textX,y+18*s,8*s,color(0xc4c4c4),shadow)
        val barX = x+8*s
        val barY = y+TargetHudStyle.BAR_TOP*s
        val barWidth = (TargetHudStyle.WIDTH-16)*s
        p.fill(barX,barY,barWidth,3*s,1*s,color(0x000000,100))
        val fill = target.healthRatio().coerceIn(0.0,1.0).toFloat()*barWidth
        if (fill>0) p.fill(barX,barY,fill,3*s,1*s,color(target.healthColor()))
        p.pushClip(barX,y+36*s,barWidth,10*s)
        label.hudText(p,target.hitText(),barX,y+36*s,10*s,color(0xd2d2d2),shadow)
        p.popClip()
    }

    private fun fit(font: UiFont, text: String, width: Float): String {
        if (font.width(text)<=width) return text
        var end = text.length
        while (end>0 && font.width(text.substring(0,end)+"...")>width)
            end=text.offsetByCodePoints(end,-1)
        return text.substring(0,end)+"..."
    }

    fun close() {
        font?.close()
        font = null
        owner = null
        fullName = null
    }
}
