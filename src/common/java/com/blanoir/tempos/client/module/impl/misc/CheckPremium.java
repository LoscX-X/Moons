package com.blanoir.tempos.client.module.impl.misc;

import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;

import com.blanoir.tempos.client.command.PremiumCheckCommand;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.EXPERIMENT,
        description = "Checks player account status at the configured interval.")
public final class CheckPremium extends ClientFeature {
    public static final CheckPremium INSTANCE = new CheckPremium();

    private CheckPremium() {
        super("premiumcheck", "CheckPremium");
    }

    @Override
    public boolean isEnabled() {
        return PremiumCheckCommand.isEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return PremiumCheckCommand.setEnabled(client, value);
    }

    @Override
    protected String statusTag() {
        return PremiumCheckCommand.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            integer(
                    "interval",
                    "Query interval (s)",
                    "premiumcheck.intervalSeconds",
                    60,
                    30,
                    600,
                    5,
                    PremiumCheckCommand::setIntervalSeconds)
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }

    @Override
    public void registerEvents() {
        PremiumCheckCommand.init();
    }
}
