package com.blanoir.tempos.client.module.impl.movement;

import static com.blanoir.tempos.client.module.framework.SettingFactory.choice;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.MOVEMENT,
        description = "Adjusts movement input when the aiming direction differs from the camera.")
public final class MoveFix extends ClientFeature {
    public static final MoveFix INSTANCE = new MoveFix();

    private MoveFix() {
        super("movefix", "MoveFix");
    }

    @Override
    public boolean isEnabled() {
        return com.blanoir.tempos.client.manager.rotation.MoveFix.enabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return com.blanoir.tempos.client.manager.rotation.MoveFix.setEnabled(client, value);
    }

    @Override
    protected String statusTag() {
        return com.blanoir.tempos.client.manager.rotation.MoveFix.algorithm();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            choice(
                    "algorithm",
                    "Algorithm",
                    "movefix.algorithm",
                    "legacy",
                    com.blanoir.tempos.client.manager.rotation.MoveFix.algorithmOptions(),
                    com.blanoir.tempos.client.manager.rotation.MoveFix::setAlgorithm)
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
