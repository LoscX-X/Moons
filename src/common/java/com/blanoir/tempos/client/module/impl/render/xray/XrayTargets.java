package com.blanoir.tempos.client.module.impl.render.xray;

import static com.blanoir.tempos.client.module.framework.SettingFactory.color;
import static com.blanoir.tempos.client.module.framework.SettingFactory.customBool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.title;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.SettingFactory;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Selects block types to highlight and sets their colors.")
public final class XrayTargets extends ClientFeature {
    public static final XrayTargets INSTANCE = new XrayTargets();

    private XrayTargets() {
        super("xraytargets", "XrayTargets");
    }

    @Override
    public boolean isEnabled() {
        return XrayBlockTarget.anyEnabled() || CustomXrayTargets.anyEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        XrayBlockTarget.setAllEnabled(value);
        CustomXrayTargets.setAllEnabled(value);
        return 1;
    }

    @Override
    protected String statusTag() {
        return "targets";
    }

    @Override
    protected Setting[] defineSettings() {
        List<Setting> targetSettings = new ArrayList<>();
        targetSettings.add(
                SettingFactory.registryList(
                        "custom_targets",
                        "Custom blocks",
                        "block",
                        CustomXrayTargets::selectedTargets,
                        CustomXrayTargets::setTargets,
                        new com.google.gson.JsonArray()));
        for (XrayBlockTarget target : XrayBlockTarget.values()) {
            String id = target.commandName();
            targetSettings.add(
                    customBool(
                                    id + "_enabled",
                                    title(id),
                                    target::isEnabled,
                                    (client, value) -> {
                                        target.setEnabled(value);
                                        return 1;
                                    })
                            .withDefault(target.defaultEnabled()));
            targetSettings.add(
                    color(
                                    id + "_color",
                                    title(id) + " color",
                                    () ->
                                            "rgb("
                                                    + target.red()
                                                    + ", "
                                                    + target.green()
                                                    + ", "
                                                    + target.blue()
                                                    + ")",
                                    (client, rgb) -> target.setColor(rgb[0], rgb[1], rgb[2]))
                            .withDefault(target.defaultColor()));
        }
        return targetSettings.toArray(Setting[]::new);
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
