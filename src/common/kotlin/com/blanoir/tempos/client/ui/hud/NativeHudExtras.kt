package com.blanoir.tempos.client.ui.hud

import com.blanoir.tempos.client.api.hud.HudElements
import com.blanoir.tempos.client.ui.paint.UiBackend
import com.blanoir.tempos.client.ui.paint.UiFont
import com.blanoir.tempos.client.ui.paint.UiPainter
import net.minecraft.client.Minecraft
import kotlin.math.min

internal object NativeHudExtras {
    private val icons = InventoryIconAtlas()
    private var inventoryVisible = false
    private var backendOwner: UiBackend? = null
    private var countFont: UiFont? = null
    private val counts = Array(100) { it.toString() }
    private var countScale = 0f

    @Suppress("UNUSED_PARAMETER")
    fun draw(p: UiPainter, backend: UiBackend, client: Minecraft, editing: Boolean, seconds: Double) {
        if (backend !== backendOwner) {
            close()
            backendOwner = backend
        }
        val guiScale = client.window.guiScale.coerceAtLeast(1).toFloat()
        val target = HudElements.TARGET.snapshot(editing)
        if (target.visible()) drawTarget(p,backend,target,guiScale)
        val inventory = HudElements.INVENTORY.snapshot(editing)
        if (inventory.visible()) {
            inventoryVisible = true
            icons.prepare(backend,client,inventory.items())
            drawInventory(p,backend,inventory,guiScale)
        } else if (inventoryVisible) {
            icons.close()
            inventoryVisible = false
        }
    }

    private fun drawInventory(p: UiPainter, backend: UiBackend, inv: HudElements.Inventory, gui: Float) {
        val x = inv.bounds().x().toFloat()*gui
        val y = inv.bounds().y().toFloat()*gui
        val s = inv.scale().toFloat()*gui
        if (s != countScale || countFont == null) {
            countFont?.close()
            countFont = UiFont(backend,6*s,1f)
            countScale = s
        }
        val font = countFont!!
        if (inv.background()) p.fill(x,y,inv.bounds().width().toFloat()*gui,inv.bounds().height().toFloat()*gui,3*s,0x78000000)
        val items = inv.items()
        for (slot in 0 until min(36,items.size)) {
            val stack = items[slot]
            val left = x+(2+slot%9*18)*s
            val top = y+(2+slot/9*18)*s
            if (inv.background()) p.fill(left,top,16*s,16*s,1.5f*s,0x37000000)
            if (stack.isEmpty) continue
            icons.draw(p,slot,left,top,16*s)
            if (stack.isBarVisible) {
                p.fill(left+2*s,top+13*s,13*s,2*s,0f,0xff000000.toInt())
                p.fill(left+2*s,top+13*s,stack.barWidth.coerceIn(0,13)*s,s,0f,stack.barColor or 0xff000000.toInt())
            }
            if (stack.count>1) {
                val count = counts.getOrNull(stack.count) ?: stack.count.toString()
                val tx = left+15*s-font.inkRight(count)
                val textTop = top+16*s-font.ascent()
                font.draw(p,count,tx+.75f*s,textTop+.75f*s,0xff000000.toInt())
                font.draw(p,count,tx,textTop,0xffeeeeee.toInt())
            }
        }
    }

    private fun drawTarget(p: UiPainter, backend: UiBackend, target: HudElements.Target, gui: Float) {
        NativeTargetHud.draw(p,backend,target,Minecraft.getInstance(),gui)
    }

    fun reset() {
        if(inventoryVisible) icons.close()
        inventoryVisible=false
    }
    fun close() {
        reset()
        countFont?.close(); countFont=null
        NativeTargetHud.close()
        backendOwner=null
    }
}
