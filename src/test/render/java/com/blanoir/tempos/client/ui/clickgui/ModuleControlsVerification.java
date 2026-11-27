package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.module.catalog.ModuleCatalog;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleRegistry;
import com.blanoir.tempos.client.config.Settings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Builds the real catalog and each module's production controls, without a game window. */
public final class ModuleControlsVerification {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Settings.configure(Path.of(System.getProperty("tempos.home"), "config"));
        ModuleRegistry.installCatalog(ModuleCatalog::register);
        GuiModel model = new GuiModel();
        var build = ModuleCard.class.getDeclaredMethod("build");
        build.setAccessible(true);
        var fields = ModuleCard.class.getDeclaredField("fields");
        fields.setAccessible(true);
        int modules = 0, controls = 0;
        for (var category : ModuleCategory.ordered()) {
            for (var row : model.rows(category)) {
                if (!row.hasControls()) throw new AssertionError("Unavailable controls: " + row.id());
                var card = new ModuleCard(model, row);
                build.invoke(card);
                long bound = ((List<?>) fields.get(card)).stream().filter(Objects::nonNull).count();
                if (bound != row.settings().size())
                    throw new AssertionError("Missing setting controls: " + row.id() + " expected="
                            + row.settings().size() + " actual=" + bound);
                modules++;
                controls += bound;
            }
        }
        System.out.println("TEMPOS_MODULE_CONTROLS_VERIFIED modules=" + modules + " controls=" + controls);
    }
}
