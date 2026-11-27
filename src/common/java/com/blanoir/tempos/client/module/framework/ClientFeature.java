package com.blanoir.tempos.client.module.framework;

import com.blanoir.tempos.client.module.framework.ModuleModel.Module;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** A player-facing feature. Catalog presentation and listener startup have independent orders. */
public abstract class ClientFeature {
    private final String id;
    private final String name;
    private final ModuleInfo info;
    private final List<SubFeature<?>> children = new ArrayList<>();

    protected ClientFeature(String id, String name) {
        this.id = id;
        this.name = name;
        this.info = getClass().getAnnotation(ModuleInfo.class);
        if (info == null) {
            throw new IllegalStateException(getClass().getName() + " is missing @ModuleInfo");
        }
    }

    public abstract boolean isEnabled();

    /** Applies the feature's existing toggle, including its status result and side effects. */
    public abstract int setEnabled(Minecraft client, boolean enabled);

    protected String statusTag() {
        return "";
    }

    protected Setting[] defineSettings() {
        return new Setting[0];
    }

    /** Called at the feature's position in the startup sequence. */
    public void registerEvents() {
        children.forEach(SubFeature::registerEvents);
    }

    protected final <S extends SubFeature<?>> S sub(S child) {
        if (child.parent() != this) throw new IllegalArgumentException("Wrong feature parent");
        children.add(child);
        return child;
    }

    public Module toDescriptor() {
        return ModuleModel.module(
                        id,
                        name,
                        info.category(),
                        this::isEnabled,
                        this::setEnabled,
                        this::statusTag,
                        defineSettings())
                .withDescription(info.description());
    }
}
