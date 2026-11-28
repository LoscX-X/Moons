package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import java.util.function.BooleanSupplier;

/** A setting switches from anywhere on its row. Its value is reflected by the thumb as well as color. */
public final class ToggleSwitch extends Button {
    private final BooleanSupplier value, available;
    private final SettingLabel title;
    private final Anim hover = new Anim(0), enabled, thumb;
    private float over, on, position;

    public ToggleSwitch(String label, Runnable toggle, BooleanSupplier value) {
        this(label,toggle,value,() -> true);
    }
    public ToggleSwitch(String label, Runnable toggle, BooleanSupplier value, BooleanSupplier available) {
        super(label,toggle);
        this.value = value;
        this.available = available;
        this.title = new SettingLabel(label);
        position = on = value.getAsBoolean() ? 1 : 0;
        enabled = new Anim(on); thumb = new Anim(on);
    }
    @Override protected boolean lightText() { return true; }
    @Override protected boolean focusable() { return available.getAsBoolean(); }
    @Override protected boolean input(UiEvent event) { return available.getAsBoolean() && super.input(event); }
    @Override public float preferredHeight() { return 14 + title.extraHeight(); }
    @Override protected void tick(long now) {
        float target = value.getAsBoolean() ? 1 : 0;
        hover.to(hovered() || focused() ? 1 : 0,now,Theme.HOVER_MS);
        enabled.to(target,now,Theme.HOVER_MS); thumb.to(target,now,Theme.HOVER_MS);
        float h=hover.value(now), a=enabled.value(now), p=thumb.value(now);
        if (over!=h || a!=on || p!=position) { over=h; on=a; position=p; repaint(); }
    }
    @Override protected void paint(WidgetCanvas c) {
        float left=x+5, right=x+width-5, middle=y+height/2;
        if (title.fit(c, right-17-left)) relayout();
        boolean active=available.getAsBoolean();
        title.draw(c,left,middle,!active ? Floating.GROUP_TITLE : over>.5f ? Floating.LABEL_HI : Floating.MODULE);
        float sx=right-12.5f;
        c.paint.fill(sx,middle-3.5f,12.5f,7,3.5f,0xff373638);
        if (active && on>0) c.paint.accentFill(sx,middle-3.5f,12.5f,7,3.5f,(int)(on*225),.92f);
        if (active && over>0) c.paint.fill(sx,middle-3.5f,12.5f,7,3.5f,((int)(over*26)<<24)|0xffffff);
        c.paint.fill(sx+1.5f+5.5f*position,middle-2,4,4,2,!active ? Floating.GROUP_TITLE : on>.5f ? 0xff262529 : 0xffbab9bd);
    }
}
