package com.blanoir.tempos.client.module.impl.player.autosword;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Selects your configured sword slot and can switch back afterward.")
public final class AutoSword extends ClientFeature {
    public static final AutoSword INSTANCE = new AutoSword();

    private AutoSword() {
        super("autosword", "AutoSword");
    }

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("autosword.enabled", false);
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return com.blanoir.tempos.client.module.impl.player.AutoSword.setEnabled(client, value);
    }

    @Override
    protected String statusTag() {
        return com.blanoir.tempos.client.module.impl.player.AutoSword.statusText();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            integer(
                    "slot",
                    "Sword slot",
                    "autosword.slot",
                    0,
                    0,
                    8,
                    1,
                    com.blanoir.tempos.client.module.impl.player.AutoSword::setSlot),
            bool(
                    "switch_back",
                    "Switch back",
                    "autosword.switchback",
                    false,
                    com.blanoir.tempos.client.module.impl.player.AutoSword::setSwitchBack),
            integer(
                    "switch_delay",
                    "Switch-back delay",
                    "autosword.switchbackdelay",
                    20,
                    1,
                    100,
                    1,
                    com.blanoir.tempos.client.module.impl.player.AutoSword::setSwitchBackDelay)
        };
    }

    @Override
    public void registerEvents() {
        com.blanoir.tempos.client.module.impl.player.AutoSword.init();
    }
}
