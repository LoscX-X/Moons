package com.blanoir.tempos.client.module.impl.player.blockin;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.PlacementSettings;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.EXPERIMENT,
        description = "Builds an enclosure around you with the selected roof and floor options.")
public final class BlockIn extends ClientFeature {
    public static final BlockIn INSTANCE = new BlockIn();

    private BlockIn() {
        super("blockin", "BlockIn");
    }

    @Override
    public boolean isEnabled() {
        return BlockInConfig.enabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return BlockInRuntime.setEnabled(client, value);
    }

    @Override
    protected String statusTag() {
        return BlockInRuntime.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return PlacementSettings.append("blockin", BlockInConfig.settings());
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withHudTag("");
    }

    @Override
    public void registerEvents() {
        BlockInRuntime.init();
    }
}
