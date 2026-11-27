package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.*;
import com.blanoir.tempos.client.utils.text.NumberText;
import java.util.function.*;

/** A local model may declare a continuous range or a decimal too small for indexed steps. */
final class YsmFreeSlider extends Widget {
    private final String title;
    private final double min,max,step;
    private final DoubleSupplier source;
    private final DoubleConsumer edit;
    private final boolean releaseOnly;
    private boolean dragging,pending;
    private double value=Double.POSITIVE_INFINITY;
    private String readout="";
    YsmFreeSlider(String title,double min,double max,double step,DoubleSupplier source,DoubleConsumer edit,boolean releaseOnly) {
        this.title=title;this.min=min;this.max=max;this.step=step;this.source=source;this.edit=edit;this.releaseOnly=releaseOnly;
        sync();
    }
    void sync() { if (!dragging) display(source.getAsDouble()); }
    private void display(double next) {
        if (Double.compare(value,next)==0) return;
        value=next;
        readout=Double.isFinite(next)?NumberText.trimmedDecimal(next,3):"Model default";
        repaint();
    }
    @Override public float preferredHeight() { return 25; }
    @Override protected boolean lightText() { return true; }
    @Override protected float textSize() { return Theme.Floating.DETAIL_SIZE; }
    @Override protected void paint(WidgetCanvas c) {
        float l=x+5,r=x+width-5,mid=y+8.5f;
        float labelEnd=c.rightX(readout,r);
        c.label(readout,labelEnd,c.centeredY(readout,mid),Theme.TEXT);
        c.paint.pushClip(l,y,Math.max(0,labelEnd-l-4),15);
        c.label(title,c.leftX(title,l),c.centeredY(title,mid),Theme.Floating.MODULE);
        c.paint.popClip();
        double v=Double.isFinite(value)?value:0;
        float pos=l+(float)Math.clamp((v-min)/(max-min),0,1)*(r-l);
        c.paint.fill(l,y+19,r-l,2,1,Theme.TRACK);
        c.paint.accentFill(l,y+19,pos-l,2,1,255,1);
        c.paint.accentFill(pos-3.5f,y+16.5f,7,7,3.5f,255,1);
    }
    @Override protected boolean input(UiEvent e) {
        if (e.kind==UiEvent.Kind.PRESS && e.button==UiEvent.PRIMARY && e.y>=y+14) dragging=true;
        else if (e.kind==UiEvent.Kind.RELEASE && dragging) {
            dragging=false;if (pending) {pending=false;edit.accept(value);}return true;
        } else if (e.kind!=UiEvent.Kind.MOVE || !dragging) return false;
        double unit=Math.clamp((e.x-x-5)/Math.max(1,width-10),0,1), next=min+(max-min)*unit;
        if (step>0) next=Math.clamp(min+Math.rint((next-min)/step)*step,min,max);
        if (Double.compare(Double.isNaN(value)?Math.clamp(0,min,max):value,next)!=0) {
            display(next);if (releaseOnly) pending=true;else edit.accept(next);
        }
        return true;
    }
    @Override protected void captureLost() { dragging=false;pending=false;sync(); }
}
