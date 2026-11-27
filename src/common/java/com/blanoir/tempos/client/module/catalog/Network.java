package com.blanoir.tempos.client.module.catalog;

import static com.blanoir.tempos.client.module.framework.ModuleModel.module;

import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.impl.network.Disabler;

/** Defines network module descriptors; ordering is owned by ModuleCatalog. */
final class Network {
    private Network() {}

    static ModuleModel.Module disabler() {
        return module(
                        "disabler",
                        "Disabler",
                        ModuleCategory.NETWORK,
                        Disabler::isEnabled,
                        Disabler::setEnabled,
                        Disabler::statusTag)
                .withHudTag(Disabler::hudTag, "Waiting", "Held");
    }

}
