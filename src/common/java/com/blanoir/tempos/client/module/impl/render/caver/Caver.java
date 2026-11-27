package com.blanoir.tempos.client.module.impl.render.caver;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;

import net.minecraft.client.Minecraft;

@ModuleInfo(category = ModuleCategory.RENDER, description = "Renders terrain with reduced opacity.")
public final class Caver extends ClientFeature {
    public static final Caver INSTANCE = new Caver();

    private Caver() {
        super("caver", "Caver");
    }

    @Override
    public boolean isEnabled() {
        return com.blanoir.tempos.client.module.impl.render.Caver.isEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        com.blanoir.tempos.client.module.impl.render.Caver.setEnabled(client, value);
        return 1;
    }

    @Override
    protected String statusTag() {
        return com.blanoir.tempos.client.module.impl.render.Caver.statusText();
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
