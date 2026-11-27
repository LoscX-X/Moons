package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.*;
import java.util.function.BooleanSupplier;

/** Left-aligned action or copyable readout in a model editor list. */
final class YsmStudioRow extends Widget {
    private String title, detail;
    private final int color;
    private final Runnable action;
    private final BooleanSupplier chosen;
    private final boolean copy;
    YsmStudioRow(String title,String detail,int color,Runnable action,BooleanSupplier chosen,boolean copy) {
        this.color=color; this.action=action; this.chosen=chosen; this.copy=copy;
        text(title,detail);
    }
    void text(String label,String sub) {
        if (label.equals(title) && sub.equals(detail)) return;
        boolean resize=detail==null || detail.isEmpty()!=sub.isEmpty();
        title=label; detail=sub;
        tooltip(sub.isEmpty()?label:label+"\n"+sub);
        if (resize) relayout(); else repaint();
    }
    @Override public float preferredHeight() { return detail.isEmpty()?15:23; }
    @Override protected boolean lightText() { return true; }
    @Override protected boolean focusable() { return action!=null || copy; }
    @Override protected boolean input(UiEvent e) {
        if (!focusable() || !(e.kind==UiEvent.Kind.PRESS && e.button==UiEvent.PRIMARY ||
                e.kind==UiEvent.Kind.KEY && (e.key==UiEvent.Key.ENTER || copy && e.key==UiEvent.Key.COPY))) return false;
        if (action!=null) action.run();
        else tree.clipboard.set(detail.isEmpty()?title:title+"\n"+detail);
        return true;
    }
    @Override protected void paint(WidgetCanvas c) {
        boolean active=chosen!=null && chosen.getAsBoolean();
        if (active || focusable() && (hovered() || focused())) c.paint.fill(x,y,width,height,0,Floating.HOVER);
        float tx=x+5, mid=y+height/2;
        c.paint.pushClip(tx,y,Math.max(0,width-10),height);
        float labelX=c.leftX(title,tx), labelY=c.centeredY(title,mid-(detail.isEmpty()?0:4.5f));
        if (active) c.accentLabel(title,labelX,labelY,255,1);
        else c.label(title,labelX,labelY,color);
        if (!detail.isEmpty()) {
            var face=c.useFont(Floating.DETAIL_SIZE,true);
            c.label(detail,c.leftX(detail,tx),c.centeredY(detail,mid+4.5f),Theme.MUTED);
            c.restoreFont(face);
        }
        c.paint.popClip();
    }
}
