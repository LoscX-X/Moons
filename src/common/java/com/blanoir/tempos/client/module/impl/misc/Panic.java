package com.blanoir.tempos.client.module.impl.misc;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.lifecycle.ClientModule;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Module;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.ModuleRegistry;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** OpenVape's one-shot disable-all action with optional second-press restoration. */
@ModuleInfo(
        category = ModuleCategory.MISC,
        description = "Disables enabled modules. Re-enable can restore them on a second press.")
public final class Panic extends ClientFeature {
    public static final Panic INSTANCE = new Panic();
    private final BooleanSetting reEnable =
            new BooleanSetting.Builder().name("panic.reEnable").defaultValue(false).build();
    private List<Module> disabled = List.of();
    private long stoppedRevision;

    private Panic() {
        super("panic", "Panic");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        if (!value) {
            disabled = List.of();
            return 1;
        }
        List<Module> modules =
                ModuleRegistry.modules().stream()
                        .filter(
                                module ->
                                        !module.id().equals("panic")
                                                && !module.id().equals("clickgui"))
                        .toList();
        boolean restore =
                reEnable.get()
                        && !disabled.isEmpty()
                        && stoppedRevision == ModuleRegistry.activationRevision()
                        && modules.stream().noneMatch(module -> module.enabled().getAsBoolean());
        List<Module> targets =
                restore
                        ? disabled
                        : modules.stream()
                                .filter(module -> module.enabled().getAsBoolean())
                                .toList();
        disabled = List.of();
        var changed = new ArrayList<Module>();
        try (var batch = Settings.batch()) {
            ClientChat.withoutNoticesOnCurrentThread(
                    () -> {
                        for (Module module : targets) {
                            ModuleRegistry.setEnabled(module.id(), restore);
                            if (module.enabled().getAsBoolean() == restore) changed.add(module);
                        }
                    });
            if (!restore && client != null) ClientModule.releaseFeatureState(client);
            if (!restore && reEnable.get()) disabled = List.copyOf(changed);
            stoppedRevision = ModuleRegistry.activationRevision();
        }
        ClientChat.send(
                client,
                "Panic " + (restore ? "restored " : "disabled ") + changed.size() + " modules.");
        return 1;
    }

    private int setReEnable(Minecraft client, boolean value) {
        reEnable.set(value);
        if (!value) disabled = List.of();
        return 1;
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {reEnable.describeStored("re_enable", "Re-enable", this::setReEnable)};
    }
}
