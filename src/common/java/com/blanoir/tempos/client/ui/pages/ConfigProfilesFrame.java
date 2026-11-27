package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.client.config.ConfigProfiles;
import com.blanoir.tempos.client.ui.clickgui.ModuleGui;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.Button;
import com.blanoir.tempos.client.ui.widget.FloatingWindow;
import com.blanoir.tempos.client.ui.widget.Scroll;
import com.blanoir.tempos.client.ui.widget.TextField;
import com.blanoir.tempos.client.ui.widget.Widget;
import com.blanoir.tempos.client.ui.widget.WidgetCanvas;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Named local profiles. Selecting a target never changes the active settings file. */
public final class ConfigProfilesFrame extends FloatingWindow {
    @FunctionalInterface
    public interface Loader {
        void load(String name) throws IOException;
    }

    private final Content content;
    private final float maximumHeight;
    private static final float ROW = 17;

    public ConfigProfilesFrame(Loader loader, Runnable dismiss, float x, float y, float w, float h) {
        this(loader, ModuleGui::openConfigurationFile, dismiss, x, y, w, h);
    }

    ConfigProfilesFrame(Loader loader, Supplier<String> openCurrent, Runnable dismiss, float x, float y, float w, float h) {
        this(new Content(loader, openCurrent), dismiss, x, y, w, h);
    }

    private ConfigProfilesFrame(Content content, Runnable dismiss, float x, float y, float w, float h) {
        super("Profiles", null, content, dismiss, x, y, w, h);
        this.content = content;
        maximumHeight = h;
    }

    public TextField nameField() { return content.name; }

    @Override protected void layout() {
        float wanted = Math.min(maximumHeight, headerHeight()+103+Math.max(1,content.rows.size())*ROW);
        expandedSize(wanted);
        if (!folded() && wanted != height) {
            float top = tree == null ? y : Math.clamp(y,0,Math.max(0,tree.root.height()-wanted));
            bounds(x,top,width,wanted);
        }
        super.layout();
    }

    private static final class Content extends Widget {
        final Scroll list = new Scroll(false);
        final ArrayList<Button> rows = new ArrayList<>();
        final TextField name;
        final Button create, save, load, refresh, openFile, importFile, exportFile;
        final Line current = new Line(false), message = new Line(true);
        final Loader loader;
        String active, selected;
        boolean confirmOverwrite;
        boolean transferring;

        Content(Loader loader, Supplier<String> openCurrent) {
            this.loader = loader;
            active = selected = ConfigProfiles.selected();
            name = new TextField("New profile name", ignored -> cancelConfirmation(), false);
            create = action(() -> "Create", () -> !name.value().isBlank(), this::create);
            save = action(() -> confirmOverwrite ? "Confirm overwrite" : selected.equals(active) ? "Save" : "Overwrite",
                    () -> !selected.isEmpty(), this::save);
            load = action(() -> "Load", () -> !selected.isEmpty(), this::load);
            refresh = action(() -> "Refresh", () -> true, () -> refresh("Profiles refreshed"));
            openFile = action(() -> "Open file", () -> true, () -> {
                cancelConfirmation();
                String result = openCurrent.get();
                message.set(result.isEmpty() ? "" : result, result.isEmpty() ? Theme.MUTED : Theme.DANGER);
            });
            openFile.tooltip("Open the current profile in your system editor");
            create.tooltip("Copy current settings to a new profile");
            load.tooltip("Load all parameters in the selected profile");
            importFile = action(() -> "Import", () -> !transferring, () -> transfer(false));
            exportFile = action(() -> "Export", () -> !transferring, () -> transfer(true));
            add(current); add(openFile); add(name); add(create); add(save); add(load); add(refresh);
            add(importFile); add(exportFile); add(message); add(list);
            refresh("");
        }

        private Button action(Supplier<String> label, BooleanSupplier enabled, Runnable callback) {
            return new Button(label.get(), () -> { if (enabled.getAsBoolean()) callback.run(); }) {
                @Override protected boolean lightText() { return true; }
                @Override protected void paint(WidgetCanvas c) {
                    boolean available = enabled.getAsBoolean();
                    if (available && (hovered() || focused())) c.paint.fill(x,y,width,height,0,Floating.HOVER);
                    c.centeredLabel(label.get(), x+width/2, y+height/2, available ? Theme.TEXT : Theme.DIM);
                }
            };
        }

        private void cancelConfirmation() {
            if (confirmOverwrite) message.set("", Theme.MUTED);
            confirmOverwrite = false;
            repaint();
        }

        private void create() {
            cancelConfirmation();
            String target = name.value().trim();
            try {
                ConfigProfiles.create(target);
                selected = target;
                name.value("");
                refresh("Created " + target);
            } catch (IOException | RuntimeException failure) { error(failure); }
        }

        private void save() {
            if (!selected.equals(active) && !confirmOverwrite) {
                confirmOverwrite = true;
                message.set("Replace " + selected + " with current settings?", Theme.TEXT);
                return;
            }
            cancelConfirmation();
            try {
                ConfigProfiles.save(selected);
                refresh("Saved " + selected);
            } catch (IOException | RuntimeException failure) { error(failure); }
        }

        private void load() {
            cancelConfirmation();
            try {
                loader.load(selected);
                refresh("Loaded " + selected);
            } catch (IOException | RuntimeException failure) { error(failure); }
        }

        private void transfer(boolean export) {
            cancelConfirmation();
            transferring = true;
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return export ? com.blanoir.tempos.client.ui.host.FileDialogs.saveJson()
                            : com.blanoir.tempos.client.ui.host.FileDialogs.openJson();
                } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            }).whenComplete((path, failure) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                transferring = false;
                if (tree == null) return;
                if (failure != null) {
                    Throwable reason = failure.getCause() == null ? failure : failure.getCause();
                    error(new IOException(reason.getMessage(), reason));
                    return;
                }
                if (path == null) { repaint(); return; }
                try {
                    if (export) ConfigProfiles.exportParameters(path);
                    else ConfigProfiles.importParameters(path);
                    refresh(export ? "Parameters exported" : "Parameters imported");
                } catch (IOException | RuntimeException problem) { error(problem); }
            }));
        }

        private void refresh(String notice) {
            cancelConfirmation();
            active = ConfigProfiles.selected();
            current.set("Current · " + active, Theme.TEXT);
            try {
                var names = ConfigProfiles.list();
                if (!names.contains(selected)) selected = names.contains(active) ? active : "";
                for (var row : rows) list.remove(row);
                rows.clear();
                for (String profile : names) {
                    var row = new Button(profile, () -> {
                        cancelConfirmation();
                        selected = profile;
                        message.set("", Theme.MUTED);
                    }) {
                        @Override public float preferredHeight() { return ROW; }
                        @Override protected boolean lightText() { return true; }
                        @Override protected void paint(WidgetCanvas c) {
                            boolean target = profile.equals(selected);
                            if (target || hovered() || focused()) c.paint.fill(x,y,width,height,0,Floating.HOVER);
                            float mid = y+height/2, left = x+4, right = x+width-4;
                            String status = profile.equals(active) ? "Current" : target ? "Target" : "";
                            var old = c.useFont(Floating.DETAIL_SIZE, true);
                            if (!status.isEmpty()) {
                                float tx = c.rightX(status, right);
                                c.label(status, tx, c.centeredY(status, mid), Theme.MUTED);
                                right = tx-5;
                            }
                            c.restoreFont(old);
                            c.paint.pushClip(left,y,Math.max(0,right-left),height);
                            float tx = c.leftX(profile, left), ty = c.centeredY(profile, mid);
                            if (target) c.accentLabel(profile, tx, ty, 255, 1);
                            else c.label(profile, tx, ty, Floating.MODULE);
                            c.paint.popClip();
                        }
                    };
                    row.tooltip(profile);
                    rows.add(row);
                    list.add(row);
                }
                message.set(notice, Theme.MUTED);
            } catch (IOException | RuntimeException failure) { error(failure); }
        }

        private void error(Exception failure) {
            message.set(failure.getMessage() == null ? "Profile operation failed" : failure.getMessage(), Theme.DANGER);
        }

        @Override protected void layout() {
            float left = x+6, w = Math.max(0, width-12);
            float openWidth = Math.min(38,w/3);
            current.bounds(left,y+3,Math.max(0,w-openWidth-3),13);
            openFile.bounds(left+w-openWidth,y+2,openWidth,15);
            name.bounds(left,y+21,w*.69f,16);
            create.bounds(left+w*.71f,y+21,w*.29f,16);
            save.bounds(left,y+43,w*.42f,16);
            load.bounds(left+w*.42f,y+43,w*.29f,16);
            refresh.bounds(left+w*.71f,y+43,w*.29f,16);
            importFile.bounds(left,y+62,w*.49f,15);
            exportFile.bounds(left+w*.51f,y+62,w*.49f,15);
            message.bounds(left,y+81,w,12);
            list.bounds(left,y+98,w,Math.max(0,height-101));
        }
    }

    private static final class Line extends Widget {
        private final boolean small;
        private String text = "";
        private int color;
        Line(boolean small) { this.small = small; }
        void set(String value, int color) {
            this.text = value;
            this.color = color;
            tooltip(value);
            repaint();
        }
        @Override protected boolean lightText() { return true; }
        @Override protected float textSize() { return small ? Floating.DETAIL_SIZE : Floating.TEXT_SIZE; }
        @Override protected void paint(WidgetCanvas c) {
            if (text.isEmpty()) return;
            c.paint.pushClip(x,y,width,height);
            c.label(text,c.leftX(text,x),c.centeredY(text,y+height/2),color);
            c.paint.popClip();
        }
    }
}
