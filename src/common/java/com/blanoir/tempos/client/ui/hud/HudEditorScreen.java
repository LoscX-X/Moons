package com.blanoir.tempos.client.ui.hud;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.api.hud.Bounds;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.UiScreen;
import com.blanoir.tempos.client.ui.clickgui.ThemePreferences;
import com.blanoir.tempos.client.ui.host.UiHost;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

/** The HUD remains in the game. Only the return control, guides and selection handles are overlaid. */
public final class HudEditorScreen extends UiScreen {
    private final Screen previous;
    private final HudLayoutController controls = new HudLayoutController();
    private final Workspace workspace = new Workspace();
    private final Button back;

    public static HudEditorScreen create(Minecraft client,Screen previous) {
        return new HudEditorScreen(new WidgetTree(clipboard(client)),new Theme(),previous);
    }
    private HudEditorScreen(WidgetTree tree,Theme theme,Screen previous) {
        super("HUD editor",tree,theme);
        new ThemePreferences(theme);
        this.previous=previous;
        back=new Button("Back to ClickGUI",this::onClose) {
            @Override protected void paint(WidgetCanvas c) {
                FloatingWindow.surface(c,x,y,width,height);
                c.icon("all_modules",x+7,y+(height-7)/2,7,Floating.ICON_COLOR);
                c.label("Back to ClickGUI",c.leftX("Back to ClickGUI",x+19),c.centeredY("Back to ClickGUI",y+height/2),hovered()?Floating.BRAND:Floating.MODULE);
            }
        };
        workspace.add(back);
        tree.root.add(workspace);
    }
    @Override protected float fontSize() { return Floating.TEXT_SIZE; }
    @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta) {
        NativeHudScene.extract(graphics,true);
        super.extractRenderState(graphics,mouseX,mouseY,delta);
    }
    @Override protected void beforeFrame() {
        workspace.bounds(0,0,width,height);
        back.bounds(Math.max(0,(width-94)/2f),7,Math.min(width,94),Floating.HEADER);
    }
    @Override public boolean keyPressed(KeyEvent k) {
        if (k.key()==InputConstants.KEY_R && controls.resetSelected()) { tree.repaint();return true; }
        return super.keyPressed(k);
    }
    @Override public void onClose() {
        controls.mouseReleased();
        Minecraft client = Minecraft.getInstance();
        if (previous == null) UiHost.open(client, UiHost.Page.CLICKGUI);
        else MinecraftClientAccess.setScreen(client, previous);
    }
    @Override public void removed() { controls.mouseReleased();super.removed(); }

    private final class Workspace extends Widget {
        private final Bounds[] boxes=new Bounds[HudEditorRegistry.targets().size()];
        private long settings=-1;
        private float layoutWidth,layoutHeight;
        private int pointed=-1;
        @Override protected void tick(long now) {
            boolean refresh=settings!=Settings.revision() || width!=layoutWidth || height!=layoutHeight;
            settings=Settings.revision();layoutWidth=width;layoutHeight=height;
            int next=-1;
            var targets=HudEditorRegistry.targets();
            for(int i=0;i<boxes.length;i++) {
                // The module list can resize during play. The other two frames use the profile and viewport.
                Bounds box = i==0 || refresh || boxes[i]==null ? targets.get(i).currentBounds() : boxes[i];
                if(!box.equals(boxes[i])) { boxes[i]=box;repaint(); }
                if(hovered() && box.contains(tree.pointerX(),tree.pointerY())) next=i;
            }
            if(next!=pointed) { pointed=next;repaint(); }
        }
        @Override protected void paint(WidgetCanvas c) {
            float px=1/c.paint.scale();
            if(controls.guideX()) c.paint.accentFill(width/2,0,px,height,0,155,1);
            if(controls.guideY()) c.paint.accentFill(0,height/2,width,px,0,155,1);
            var targets=HudEditorRegistry.targets();
            for(int i=0;i<boxes.length;i++) {
                Bounds b=boxes[i];
                if(b==null || b.width()<=0 || b.height()<=0) continue;
                float l=(float)b.x()-1,t=(float)b.y()-1,w=(float)b.width()+2,h=(float)b.height()+2;
                boolean selected = targets.get(i)==controls.selected() || i==pointed;
                if(selected) c.paint.accentOutline(l,t,w,h,0,px,225,1);
                else c.paint.outline(l,t,w,h,0,px,0x88999999);
                c.paint.fill(l+w-6,t+h-2,5,1,0,Floating.ICON_COLOR);
                c.paint.fill(l+w-2,t+h-6,1,5,0,Floating.ICON_COLOR);
            }
        }
        @Override protected boolean input(UiEvent e) {
            boolean used=switch(e.kind) {
                case PRESS -> (e.button==UiEvent.PRIMARY || e.button==UiEvent.SECONDARY) &&
                        controls.mouseClicked(e.button==UiEvent.PRIMARY ? InputConstants.MOUSE_BUTTON_LEFT : InputConstants.MOUSE_BUTTON_RIGHT,e.x,e.y);
                case MOVE -> controls.mouseDragged(e.x,e.y,(int)width,(int)height);
                case RELEASE -> { controls.mouseReleased();yield true; }
                case SCROLL -> controls.mouseScrolled(e.x,e.y,e.wheel,(int)width,(int)height);
                default -> false;
            };
            if(used) repaint();
            return used;
        }
        @Override protected void captureLost() { controls.mouseReleased();repaint(); }
    }
}
