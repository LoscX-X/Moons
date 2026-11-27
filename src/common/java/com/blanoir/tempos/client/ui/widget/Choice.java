package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Dropdown with a scrollable, front-most option list. */
public final class Choice extends Widget {
    private static final float OPTION_HEIGHT = 12;
    private final List<String> options;
    private final Supplier<String> value;
    private final Consumer<String> change;
    private final String label;
    private SettingLabel caption;
    private String displayed;
    private Scroll openList;
    private final Anim hover = new Anim(0);
    private float amount;
    public Choice(String label, List<String> options, Supplier<String> value, Consumer<String> change) {
        this.label = label;
        this.caption = new SettingLabel(label);
        this.options = List.copyOf(options);
        this.value = value;
        this.change = change;
    }
    @Override protected boolean focusable() { return true; }
    @Override protected boolean lightText() { return true; }
    @Override protected float textSize() { return Theme.Floating.FIELD_SIZE; }
    @Override public float preferredHeight() { return 20 + caption.extraHeight(); }
    @Override protected void tick(long now) {
        String current = value.get();
        if (!current.equals(displayed)) {
            displayed = current;
            caption = new SettingLabel(label.isEmpty() ? current : label + " - " + current);
            relayout();
        }
        if (openList != null && openList.tree == null) openList = null;
        hover.to(hovered() || focused() || openList != null ? 1 : 0, now, Theme.HOVER_MS);
        float next=hover.value(now);
        if(next!=amount){amount=next;repaint();}
    }
    @Override protected void paint(WidgetCanvas c) {
        float left=x+5, top=y+3, w=width-10, h=height-6;
        float middle=y+height/2;
        if (caption.fit(c,width-28)) relayout();
        c.paint.fill(left,top,w,h,2,Theme.FIELD);
        c.paint.outline(left,top,w,h,2,.75f,Theme.BORDER);
        if(amount>0) c.paint.accentOutline(left,top,w,h,2,.75f,(int)(125*amount),1);
        c.paint.pushClip(left+4,top,Math.max(0,width-26),h);
        caption.draw(c,left+5,middle+.625f,Theme.TEXT);
        c.paint.popClip();
        float direction=openList==null ? 1 : -1;
        c.paint.line(x+width-13,middle-direction,x+width-11,middle+.6f*direction,.8f,Theme.MUTED);
        c.paint.line(x+width-11,middle+.6f*direction,x+width-9,middle-direction,.8f,Theme.MUTED);
    }
    @Override protected boolean input(UiEvent e) {
        if (!(e.kind == UiEvent.Kind.PRESS && e.button == 0 || e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ENTER)) return false;
        if (options.isEmpty()) return true;
        Scroll list = new Scroll(false) {
            @Override protected void paint(WidgetCanvas c) {
                c.cover(x, y, width, height, 3);
                c.paint.fill(x, y, width, height, 3, Theme.Floating.WINDOW);
                c.paint.outline(x,y,width,height,3,1/c.paint.scale(),Theme.BORDER);
            }
        };
        for (String option : options) {
            list.add(new Button(option, () -> { change.accept(option); tree.closePopup(); repaint(); }) {
                @Override protected boolean lightText() { return true; }
                @Override protected float textSize() { return Theme.Floating.FIELD_SIZE; }
                @Override public float preferredHeight() { return OPTION_HEIGHT; }
                @Override protected void paint(WidgetCanvas c) {
                    boolean active=option.equals(value.get());
                    if (hovered() || active) c.paint.fill(x+2,y+1,width-4,height-2,1,Theme.Floating.HOVER);
                    float tx=c.leftX(option,x+5), ty=c.centeredY(option,y+height/2);
                    c.paint.pushClip(x+3,y,Math.max(0,width-6),height);
                    if (active) c.accentLabel(option,tx,ty,255,1);
                    else c.label(option,tx,ty,Theme.MUTED);
                    c.paint.popClip();
                }
            });
        }
        float h = Math.min(tree.root.height, Math.min(options.size(), 7) * OPTION_HEIGHT);
        float top = y + height + h <= tree.root.height ? y + height : Math.max(0, y - h);
        list.bounds(x+5, top, width-10, h);
        int activeIndex=options.indexOf(value.get());
        if (activeIndex>=0) list.restore(Math.max(0,(activeIndex+1)*OPTION_HEIGHT-h));
        tree.popup(list);
        openList=list;
        return true;
    }
}
