package com.blanoir.tempos.client.module.impl.render;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.ui.clickgui.ModuleGui;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Opens the module settings.")
public final class ClickGui extends ClientFeature {
    public static final ClickGui INSTANCE = new ClickGui();

    private ClickGui() {
        super("clickgui", "ClickGUI");
    }

    @Override
    public boolean isEnabled() {
        return ModuleGui.isOpen();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return ModuleGui.setEnabled(client, value);
    }

}
