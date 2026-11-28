package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.catalog.ModuleCatalog;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleKeybinds;
import com.blanoir.tempos.client.module.framework.ModuleRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.SharedConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.server.Bootstrap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real UI model, catalog and input bindings, without a game window. */
public final class XrayGroupVerification {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path base = Path.of(System.getProperty("tempos.home"));
        Files.createDirectories(base);
        Settings.configure(Files.createTempDirectory(base, "config-"));
        ModuleRegistry.installCatalog(ModuleCatalog::register);
        GuiModel model = new GuiModel();
        List<GuiModel.Row> roots;
        try { roots = (List<GuiModel.Row>) GuiModel.class.getMethod("roots", ModuleCategory.class).invoke(model, ModuleCategory.RENDER); }
        catch (NoSuchMethodException oldFlatUi) { roots = model.rows(ModuleCategory.RENDER); }
        require(roots.stream().filter(r -> List.of("xray", "xraydisplay", "xraytargets").contains(r.id())).count() == 1,
                "XrayDisplay and XrayTargets still appear as separate top-level functions");
        var rows = model.rows(ModuleCategory.RENDER);
        var parent = rows.stream().filter(r -> r.id().equals("xray")).findFirst().orElseThrow();
        var display = rows.stream().filter(r -> r.id().equals("xraydisplay")).findFirst().orElseThrow();
        var targets = rows.stream().filter(r -> r.id().equals("xraytargets")).findFirst().orElseThrow();
        var children = (List<GuiModel.Row>) parent.getClass().getMethod("children").invoke(parent);
        require(children.equals(List.of(display, targets)), "Xray child order/identity changed");
        model.expand(parent);
        model.expand(display);
        require(model.expanded(parent) && model.expanded(display), "Expanding child collapsed parent");
        model.beginBinding(display);
        model.acceptBinding(new KeyEvent(InputConstants.KEY_LCONTROL, 0, 0));
        model.beginBinding(targets);
        model.acceptBinding(new KeyEvent(InputConstants.KEY_RCONTROL, 0, 0));
        require(ModuleKeybinds.getBoundKey("xraydisplay").getValue() == InputConstants.KEY_LCONTROL, "Display binding not independent");
        require(ModuleKeybinds.getBoundKey("xraytargets").getValue() == InputConstants.KEY_RCONTROL, "Targets binding not independent");
        require(ModuleKeybinds.modulesFor(ModuleKeybinds.getBoundKey("xraydisplay")).stream().allMatch(m -> m.id().equals("xraydisplay")), "Display key also bound parent");
        model.search("default color");
        require(parent.visible() && display.visible() && model.expanded(parent), "Child search is inaccessible behind parent");
        require(!targets.visible(), "Unrelated child appears in filtered search");
        require(ModuleRegistry.modules().stream().anyMatch(m -> m.id().equals("xraydisplay")), "Legacy module identity removed");
        System.out.println("TEMPOS_XRAY_GROUP_VERIFIED one-root nested-controls independent-bindings child-search legacy-identities");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
