package com.blanoir.tempos.client.module.impl.misc;

import static com.blanoir.tempos.client.module.framework.SettingFactory.text;

import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.ClientBranding;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.EXPERIMENT,
        description = "Changes the name shown before client chat messages.")
public final class ChatPrefix extends ClientFeature {
    public static final ChatPrefix INSTANCE = new ChatPrefix();

    private ChatPrefix() {
        super("chatprefix", "ChatPrefix");
    }

    @Override
    public boolean isEnabled() {
        return ClientChat.isPrefixEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return ClientChat.setPrefixEnabled(client, value);
    }

    @Override
    protected String statusTag() {
        return ClientChat.prefix();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            text("prefix", "Client name", ClientBranding::name, ClientChat::setPrefix)
                    .withDefault(ClientBranding.DEFAULT_NAME)
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
