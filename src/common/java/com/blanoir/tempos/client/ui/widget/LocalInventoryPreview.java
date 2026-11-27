package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.module.impl.player.AutoArmor;
import com.blanoir.tempos.client.module.impl.player.InvClear;
import com.blanoir.tempos.client.module.impl.player.invmanager.InventoryArmor;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;

/** Read-only rows derived from the same local inventory views as the module's existing preview. */
public abstract class LocalInventoryPreview extends ModulePreview {
    private ItemStack[] contents = new ItemStack[0];
    private Object player, level, menu;
    private long nextCheck, settingsVersion = -1;
    private boolean refresh = true;

    public static LocalInventoryPreview armor() {
        return new LocalInventoryPreview() {
            @Override void read(Minecraft client) {
                var armor = AutoArmor.INSTANCE;
                line(ItemStack.EMPTY,"Armor preview",armor.statusText(),"",false);
                for (var part : armor.preview(client)) {
                    String next = part.candidate().isEmpty() ? "" : "Next: "+part.candidate().getHoverName().getString();
                    line(part.item(),InventoryArmor.label(part.index()),part.status(),next,true);
                }
            }
        };
    }

    public static LocalInventoryPreview cleanup() {
        return new LocalInventoryPreview() {
            @Override void read(Minecraft client) {
                var drops = InvClear.INSTANCE.preview(client);
                line(ItemStack.EMPTY,"Cleanup preview",drops.size()+(drops.size()==1 ? " stack" : " stacks"),"",false);
                for (int i = 0; i < Math.min(8,drops.size()); i++) {
                    var drop = drops.get(i);
                    line(drop.item(),drop.item().getHoverName().getString()+" ×"+drop.item().getCount(),
                            "Slot "+(drop.source()+1)+" · "+drop.reason(),"",true);
                }
                int more = drops.size() - 8;
                if (more > 0) line(ItemStack.EMPTY,"+"+more+" more "+(more==1 ? "stack" : "stacks"),"","",false);
            }
        };
    }

    abstract void read(Minecraft client);
    final void line(ItemStack stack,String title,String detail,String next,boolean itemRow) {
        add(new Line(stack,title,detail,next,itemRow));
    }

    @Override public void refreshOnOpen() { refresh = true; }
    @Override protected void tick(long now) {
        if (!refresh && now < nextCheck) return;
        nextCheck = now + 150_000_000L;
        Minecraft client = Minecraft.getInstance();
        var inventory = client.player == null ? null : client.player.getInventory();
        var currentMenu = client.player == null ? null : client.player.containerMenu;
        long version = Settings.revision();
        boolean changed = refresh || player != client.player || level != client.level
                || menu != currentMenu || version != settingsVersion;
        player = client.player;
        level = client.level;
        menu = currentMenu;
        settingsVersion = version;
        refresh = false;
        int slots = inventory == null ? 0 : inventory.getContainerSize();
        if (contents.length != slots) {
            contents = new ItemStack[slots];
            changed = true;
        }
        for (int i = 0; i < slots; i++) {
            ItemStack item = inventory.getItem(i);
            if (contents[i] == null || !ItemStack.matches(contents[i],item)) {
                contents[i] = item.copy();
                changed = true;
            }
        }
        if (!changed) return;
        while (!children.isEmpty()) remove(children.getLast());
        read(client);
        relayout();
    }
    @Override public float preferredHeight() {
        float h=4;
        for(int i=0;i<children.size();i++) h+=children.get(i).preferredHeight();
        return h;
    }
    @Override protected void layout() {
        float top=y+2;
        for(int i=0;i<children.size();i++){
            Widget row=children.get(i);
            row.bounds(x,top,width,row.preferredHeight());
            top+=row.preferredHeight();
        }
    }

    private static final class Line extends Widget {
        private static final float LINE=8.5f;
        private final ItemStack stack;
        private final SettingLabel[] labels;
        private final boolean itemRow;

        Line(ItemStack stack,String title,String detail,String next,boolean itemRow) {
            this.stack=stack.copy(); this.itemRow=itemRow;
            var lines = new ArrayList<SettingLabel>(3);
            lines.add(new SettingLabel(title));
            if (!detail.isEmpty()) lines.add(new SettingLabel(detail));
            if (!next.isEmpty()) lines.add(new SettingLabel(next));
            labels = lines.toArray(SettingLabel[]::new);
            if(!stack.isEmpty()) tooltip(stack.getHoverName().getString());
        }
        @Override protected boolean lightText(){return true;}
        @Override protected float textSize(){return Floating.FIELD_SIZE;}
        @Override public float preferredHeight(){
            float h=5+LINE*labels.length;
            for(var label:labels) h+=label.extraHeight();
            return h;
        }
        @Override protected void paint(WidgetCanvas c){
            float left=x+(itemRow ? 19 : 5), room=Math.max(1,x+width-left-5);
            if(!stack.isEmpty()) c.item(stack,x+4,y+4,12);
            float top=y+2.5f;
            for(int i=0;i<labels.length;i++){
                var label=labels[i];
                if(label.fit(c,room)) relayout();
                float lineHeight=LINE+label.extraHeight();
                label.draw(c,left,top+lineHeight/2,i==0 ? Floating.MODULE : Floating.LABEL);
                top+=lineHeight;
            }
        }
    }
}
