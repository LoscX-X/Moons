package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme.Floating;

/** Shared flat panel and title controls for the native ClickGUI and its settings pages. */
public class FloatingWindow extends Window {
    private final String heading, symbol;
    private final boolean dismissible;
    public FloatingWindow(String title,String icon,Widget body,Runnable dismiss,float x,float y,float w,float h) {
        super(title,body,x,y,w,h);
        heading=title; symbol=icon; dismissible=dismiss!=null;
        onDismiss(dismiss);
    }
    @Override protected float headerHeight() { return Floating.HEADER; }
    public static void surface(WidgetCanvas c,float x,float y,float w,float h) {
        c.cover(x,y,w,h,Floating.RADIUS);
        c.paint.shadow(x,y,w,h,Floating.RADIUS,Floating.SHADOW_BLUR,Floating.SHADOW);
        c.paint.fill(x,y,w,h,Floating.RADIUS,Floating.WINDOW);
        c.paint.outline(x,y,w,h,Floating.RADIUS,1/c.paint.scale(),Floating.BORDER);
    }
    @Override protected void paintFrame(WidgetCanvas c) { surface(c,x,y,width,height); }
    @Override protected void paintTitle(WidgetCanvas c) {
        float mid=y+Floating.HEADER/2;
        float titleMid=mid+1;
        if(symbol!=null) c.icon(symbol,x+6,titleMid-Floating.ICON/2,Floating.ICON,Floating.ICON_COLOR);
        float left=x+(symbol!=null ? 6+Floating.ICON+4 : 7);
        c.paint.pushClip(left,y,Math.max(0,x+width-left-(dismissible?35:18)),Floating.HEADER);
        c.label(heading,c.leftX(heading,left),c.centeredY(heading,titleMid),Floating.MODULE);
        c.paint.popClip();
        paintFold(c,x+width-(dismissible?27:9),mid);
        if(dismissible) c.centeredLabel("×",x+width-9,mid,Floating.ICON_COLOR);
    }
}
