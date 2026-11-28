package com.blanoir.tempos.client.module.impl.render.xray;

import static com.blanoir.tempos.client.module.framework.SettingFactory.color;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Sets how selected blocks are highlighted.")
public final class XrayDisplay extends ClientFeature {
    public static final XrayDisplay INSTANCE = new XrayDisplay();

    private XrayDisplay() {
        super("xraydisplay", "XrayDisplay");
    }

    @Override
    public boolean isEnabled() {
        return OreHighlighter.isDisplayEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        OreHighlighter.setDisplayEnabled(client, value);
        return 1;
    }

    @Override
    protected String statusTag() {
        return OreHighlighter.displayStatusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            com.blanoir.tempos.client.module.framework.SettingFactory.customBool(
                    "merge_connected", "Outer contour", OreHighlighter::mergeConnected,
                    OreHighlighter::setMergeConnected).withDefault(true),
            color(
                            "color",
                            "Default color",
                            OreHighlighter::getRgbString,
                            (client, rgb) -> OreHighlighter.setColor(rgb[0], rgb[1], rgb[2]))
                    .withDefault("#00dcff")
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }

    @Override
    public void registerEvents() {
        OreHighlighter.init();
    }
}
