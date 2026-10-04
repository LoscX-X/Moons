package com.blanoir.moons.client.ui.clickgui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blanoir.moons.client.config.ClientBranding
import com.blanoir.moons.client.config.Settings
import com.blanoir.moons.client.module.framework.ModuleKeybinds
import com.blanoir.moons.client.module.impl.render.xray.PluginBlockPreviews
import com.blanoir.moons.client.ui.MinecraftScreenAccess
import com.blanoir.moons.client.ui.compose.FinalFrameSurface
import com.blanoir.moons.client.ui.compose.LwjglComposeEvents
import com.blanoir.moons.client.ui.hud.HudLayoutController
import com.blanoir.moons.client.utils.render.NativeItemIcons

import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseEvent as AwtMouseEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse




internal val ClickGuiRevision = mutableIntStateOf(0)

/** Retains every Compose page while delegating lifecycle and input to the 1.8 GuiScreen. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class MoonsComposeScreen : GuiScreen() {
    private var composeScene: ComposeScene? = null
    private val frameSurface = FinalFrameSurface()
    private var currentScale = 1f
    private var currentUiDensity = 1.4f
    private var bindingModuleId by mutableStateOf<String?>(null)
    private var revision by ClickGuiRevision
    private val hudLayoutController = HudLayoutController()
    private var hudLayoutEditing by mutableStateOf(false)
    private var hudPointerCaptured = false
    private var hudPointerX = 0.0
    private var hudPointerY = 0.0
    private var nextRegistrySyncNanos = 0L

    override fun initGui() { Keyboard.enableRepeatEvents(true) }
    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        hudPointerX=mouseX.toDouble(); hudPointerY=mouseY.toDouble()
    }
    fun renderComposeFrame() {
        val now=System.nanoTime()
        if(now>=nextRegistrySyncNanos){ nextRegistrySyncNanos=now+50_000_000L; revision++ }
        val client=Minecraft.getMinecraft()
        val frameWidth=client.displayWidth
        val frameHeight=client.displayHeight
        if(frameWidth<=0 || frameHeight<=0)return
        currentScale=ScaledResolution(client).scaleFactor.coerceAtLeast(1).toFloat()
        currentUiDensity=(frameHeight/1080f*1.4f).coerceIn(1.2f,2f)
        val scene=composeScene ?: CanvasLayersComposeScene(density=Density(currentUiDensity),invalidate={}).also {
            composeScene=it; it.setContent { ClickGuiContent() }
        }
        scene.density=Density(currentUiDensity); scene.size=IntSize(frameWidth,frameHeight)
        NativeItemIcons.prepareFrame(); PluginBlockPreviews.prepareFrame()
        frameSurface.render(frameWidth,frameHeight){canvas ->
            if(hudLayoutEditing){
                canvas.save()
                try{ canvas.scale(currentScale,currentScale); hudLayoutController.render(canvas,hudPointerX,hudPointerY,width,height) }
                finally { canvas.restore() }
            }
            scene.render(canvas.asComposeCanvas(),now)
        }
    }
    fun dispose(){ composeScene?.close(); composeScene=null; frameSurface.close(); NativeItemIcons.clear(); PluginBlockPreviews.clear() }
    override fun onGuiClosed(){
        Keyboard.enableRepeatEvents(false); hudLayoutController.mouseReleased(); hudPointerCaptured=false
        composeScene?.cancelPointerInput(); composeScene?.focusManager?.releaseFocus()
        bindingModuleId=null; hudLayoutEditing=false; nextRegistrySyncNanos=0
        NativeItemIcons.clear(); PluginBlockPreviews.clear()
        super.onGuiClosed()
    }
    fun onClose(){ MinecraftScreenAccess.set(Minecraft.getMinecraft(),null) }
    override fun doesGuiPauseGame()=false
    fun isHudLayoutEditing()=hudLayoutEditing
    fun isBindingKey()=bindingModuleId!=null
    private fun composeOffset(x:Double,y:Double)=Offset((x*currentScale).toFloat(),(y*currentScale).toFloat())
    private fun pointer(type:PointerEventType,x:Double,y:Double,button:Int=-1){
        val p=composeOffset(x,y)
        composeScene?.sendPointerEvent(type,position=p,type=PointerType.Mouse,
            button=if(button>=0)PointerButton(button)else null,
            nativeEvent=LwjglComposeEvents.mouse(p.x.toInt(),p.y.toInt(),LwjglComposeEvents.modifiers(0),button,
                if(type==PointerEventType.Press)AwtMouseEvent.MOUSE_PRESSED else if(type==PointerEventType.Release)AwtMouseEvent.MOUSE_RELEASED else AwtMouseEvent.MOUSE_MOVED))
    }
    override fun handleMouseInput(){
        super.handleMouseInput()
        val x=Mouse.getEventX().toDouble()*width/Minecraft.getMinecraft().displayWidth
        val y=height-Mouse.getEventY().toDouble()*height/Minecraft.getMinecraft().displayHeight-1
        hudPointerX=x; hudPointerY=y
        if(!hudPointerCaptured)pointer(PointerEventType.Move,x,y)
        val wheel=Mouse.getEventDWheel()
        if(wheel!=0){
            val amount=Integer.signum(wheel).toDouble()
            if(hudLayoutEditing && hudLayoutController.mouseScrolled(x,y,amount,width,height))return
            val p=composeOffset(x,y)
            composeScene?.sendPointerEvent(PointerEventType.Scroll,position=p,scrollDelta=Offset(0f,(-amount*currentScale).toFloat()),
                nativeEvent=LwjglComposeEvents.wheel(p.x.toInt(),p.y.toInt(),amount,LwjglComposeEvents.modifiers(0)))
        }
    }
    override fun mouseClicked(x:Int,y:Int,button:Int){
        bindingModuleId?.let {
            if(ModuleKeybinds.bind(it,ModuleKeybinds.fromMouseButton(button))){bindingModuleId=null;revision++};return
        }
        if(ModuleKeybinds.isGuiKey(ModuleKeybinds.fromMouseButton(button)))return
        if(hudLayoutEditing && hudLayoutController.mouseClicked(button,x.toDouble(),y.toDouble())){hudPointerCaptured=true;return}
        pointer(PointerEventType.Press,x.toDouble(),y.toDouble(),button)
    }
    override fun mouseClickMove(x:Int,y:Int,button:Int,time:Long){
        if(hudLayoutEditing&&hudPointerCaptured)hudLayoutController.mouseDragged(x.toDouble(),y.toDouble(),width,height)
        else pointer(PointerEventType.Move,x.toDouble(),y.toDouble(),button)
    }
    override fun mouseReleased(x:Int,y:Int,button:Int){
        if(hudPointerCaptured){hudLayoutController.mouseReleased();hudPointerCaptured=false}
        else pointer(PointerEventType.Release,x.toDouble(),y.toDouble(),button)
    }
    override fun handleKeyboardInput(){
        val code=Keyboard.getEventKey()
        val pressed=Keyboard.getEventKeyState()
        if(!pressed){composeScene?.sendKeyEvent(LwjglComposeEvents.key(0,AwtKeyEvent.KEY_RELEASED,code));return}
        keyTyped(Keyboard.getEventCharacter(),code)
    }
    override fun keyTyped(character:Char,keyCode:Int){
        if(hudLayoutEditing){
            if(keyCode==Keyboard.KEY_ESCAPE){hudLayoutController.mouseReleased();hudPointerCaptured=false;hudLayoutEditing=false;return}
            if(keyCode==Keyboard.KEY_R&&hudLayoutController.resetSelected())return
        }
        bindingModuleId?.let{ id ->
            when(keyCode){
                Keyboard.KEY_ESCAPE -> bindingModuleId=null
                Keyboard.KEY_BACK,Keyboard.KEY_DELETE -> {ModuleKeybinds.unbind(id);bindingModuleId=null;revision++}
                else -> if(ModuleKeybinds.bind(id,ModuleKeybinds.fromKeyCode(keyCode))){bindingModuleId=null;revision++}
            };return
        }
        if(ModuleKeybinds.isGuiKey(ModuleKeybinds.fromKeyCode(keyCode)))return
        if(keyCode==Keyboard.KEY_ESCAPE){onClose();return}
        composeScene?.sendKeyEvent(LwjglComposeEvents.key(0,AwtKeyEvent.KEY_PRESSED,keyCode))
        if(character.code>=32&&character.code!=127)composeScene?.sendKeyEvent(LwjglComposeEvents.key(0,AwtKeyEvent.KEY_TYPED,Keyboard.KEY_NONE,character.code))
    }
    @Composable
    private fun ClickGuiContent() {
        revision
        if (hudLayoutEditing) {
            HudLayoutOverlay {
                hudLayoutController.mouseReleased()
                hudPointerCaptured = false
                hudLayoutEditing = false
            }
            return
        }
        MoonsClickGui(
            bindingModuleId = bindingModuleId,
            onBindingModuleChange = { bindingModuleId = it },
            onMutated = { revision++ },
            onEditHudLayout = {
                bindingModuleId = null
                hudLayoutEditing = true
            },
            onClose = ::onClose,
        )
    }

    @Composable
    private fun HudLayoutOverlay(onDone: () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .shadow(14.dp, RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .background(PanelStyle.panel)
                    .height(32.dp)
                    .padding(start = 11.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "HUD layout",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "drag · corner/right click resize · R reset",
                    color = Color(0xFF8D8D91),
                    fontSize = 7.sp,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(5.dp))
                        .background(guiThemeColor())
                        .clickable(onClick = onDone)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Done",
                        color = Color(0xFF17130D),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    private fun guiThemeColor(): Color =
        runCatching {
                val raw = Settings.getString("clickgui.theme.color", "#b29a65").removePrefix("#")
                Color((0xFF000000L or raw.toLong(16)).toInt())
            }
            .getOrDefault(Color(0xFFB29A65))
}
