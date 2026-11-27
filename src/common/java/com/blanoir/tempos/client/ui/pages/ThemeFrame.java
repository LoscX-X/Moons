package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.client.ui.clickgui.ThemePreferences;
import com.blanoir.tempos.client.ui.widget.Choice;
import com.blanoir.tempos.client.ui.widget.ColorPicker;
import com.blanoir.tempos.client.ui.widget.ToggleSwitch;
import com.blanoir.tempos.client.ui.widget.Scroll;
import com.blanoir.tempos.client.ui.widget.Slider;
import com.blanoir.tempos.client.ui.widget.FloatingWindow;
import com.blanoir.tempos.client.ui.widget.Button;
import com.blanoir.tempos.client.ui.widget.Widget;
import com.blanoir.tempos.client.ui.widget.WidgetCanvas;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.utils.render.ColorCodec;
import java.util.LinkedHashSet;
import java.util.List;

/** The same compact, floating appearance controls work on their own or beside the categories. */
public final class ThemeFrame extends FloatingWindow {
    private final ThemePreferences prefs;
    private final ColorPicker picker;
    private final Slider[] sliders = new Slider[ThemePreferences.Parameter.values().length];
    private final Choice mode;
    private final ToggleSwitch hudNames;
    private final Widget presets;
    public ThemeFrame(ThemePreferences prefs,Runnable dismiss,float x,float y,float w) {
        this(prefs,dismiss,x,y,w,new Scroll(false));
    }
    private ThemeFrame(ThemePreferences prefs,Runnable dismiss,float x,float y,float w,Scroll list) {
        super("Appearance","settings",list,dismiss,x,y,w,174);
        this.prefs = prefs;
        mode = new Choice("Accent",List.of("Brand","RGB"),() -> prefs.rgb() ? "RGB" : "Brand",v -> prefs.rgb("RGB".equals(v)));
        list.add(mode);
        picker = new ColorPicker("Accent color",prefs,false,true);
        picker.tooltip("Shift-click changes channel. Double-click the swatch for RGB.");
        list.add(picker);
        presets = new Presets(prefs, picker);
        list.add(presets);
        for (var p : ThemePreferences.Parameter.values()) {
            var slider = new Slider(p.label,p.min,p.max,p.step,prefs.value(p),p.max,false,(v,ignored) -> prefs.preview(p,v));
            sliders[p.ordinal()] = slider;
            list.add(slider);
        }
        hudNames=new ToggleSwitch("Use RGB for HUD names",ThemePreferences::toggleHudRgb,ThemePreferences::hudRgb,prefs::rgb);
        hudNames.tooltip("Use the ClickGUI color for module names in Text GUI.");
        list.add(hudNames);
    }
    @Override protected void tick(long now) {
        float content = headerHeight() + mode.preferredHeight() + picker.preferredHeight() + hudNames.preferredHeight() + 2;
        boolean rgb = prefs.rgb();
        presets.visible(!rgb);
        if (!rgb) content += presets.preferredHeight();
        for (Slider slider : sliders) {
            slider.visible(rgb);
            if (rgb) content += slider.preferredHeight();
        }
        if (parent != null) content = Math.min(content,Math.max(headerHeight()+20,parent.height()-y-3));
        expandedSize(content);
        if (!folded()) bounds(x,y,width,content);
    }

    private static final class Presets extends Widget {
        Presets(ThemePreferences prefs, ColorPicker picker) {
            var colors = new LinkedHashSet<>(ThemePreferences.SETTINGS_PRESETS);
            colors.addAll(ThemePreferences.PANEL_PRESETS);
            for (String hex : colors) {
                int argb = ColorCodec.parseRgbOrArgb(hex.substring(1));
                Button swatch = new Button(hex, () -> {
                    prefs.preview(argb);
                    prefs.commit(hex);
                    picker.sync();
                }) {
                    @Override protected void paint(WidgetCanvas c) {
                        float size = Math.max(0, Math.min(8,width-2));
                        float left = x+(width-size)/2, top = y+(height-size)/2;
                        c.paint.fill(left,top,size,size,size/2,argb);
                        boolean selected = hex.equalsIgnoreCase(prefs.hex());
                        if (selected || hovered() || focused()) {
                            c.paint.outline(left-1,top-1,size+2,size+2,size/2+1,1/c.paint.scale(),
                                    selected ? Theme.TEXT : Theme.MUTED);
                        }
                    }
                };
                swatch.tooltip(hex);
                add(swatch);
            }
        }
        @Override public float preferredHeight() { return 15; }
        @Override protected void layout() {
            float step = Math.max(0,Math.min(14,(width-10)/children.size()));
            for (int i=0; i<children.size(); i++) children.get(i).bounds(x+5+i*step,y,step,height);
        }
    }
}
