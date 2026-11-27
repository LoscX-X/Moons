package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.api.YsmSelector;
import com.blanoir.tempos.client.ui.host.FileDialogs;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Local model selection. Applying a highlighted row is a separate user action. */
public final class YsmSelectorFrame extends FloatingWindow {
    private final Content content;
    private final Sections sections;
    public YsmSelectorFrame(Runnable dismiss, float x, float y, float w, float h) {
        this(new Sections(), dismiss, x, y, w, h);
    }
    private YsmSelectorFrame(Sections sections, Runnable dismiss, float x, float y, float w, float h) {
        super("YSM", "player", sections, dismiss, x, y, w, h);
        this.sections=sections;
        this.content=sections.models;
    }
    public TextField search() { return sections.tab==0?content.search:sections.studio.search; }

    private static final class Sections extends Widget {
        static final String[] TABS={"Models","Parameters","Actions","Pose","Debug"};
        static final String[] KEYS={"YSM","YSM Parameters","YSM Actions","YSM Pose","YSM Debug"};
        final Content models=new Content();
        final YsmStudioPane studio=new YsmStudioPane();
        final Button[] tabs=new Button[TABS.length];
        int tab=-1;
        Sections() {
            add(models);add(studio);
            String previous=Settings.getString("clickgui.settings.page","YSM");
            String saved=Settings.getString("clickgui.ysm.page","YSM");
            int start=0;
            for (int i=0;i<TABS.length;i++) {
                final int index=i;
                String label=TABS[i];
                tabs[i]=new Button(label,()->select(index,true)) {
                    @Override protected boolean lightText() { return true; }
                    @Override protected void paint(WidgetCanvas c) {
                        if (hovered() || index==tab) c.paint.fill(x,y,width,height,0,Floating.HOVER);
                        float tx=c.centeredX(label,x+width/2), ty=c.centeredY(label,y+height/2);
                        if (index==tab) c.accentLabel(label,tx,ty,255,1);
                        else c.label(label,tx,ty,Floating.LABEL);
                    }
                };
                if (KEYS[i].equals(saved)) start=i;
                add(tabs[i]);
            }
            for (int i=1;i<KEYS.length;i++) if (KEYS[i].equals(previous)) start=i;
            select(start,false);
        }
        void select(int index,boolean save) {
            if (index==tab) return;
            tab=index;
            models.visible(index==0); studio.visible(index!=0);
            if (index!=0) studio.open(TABS[index]);
            if (save) try (var batch=Settings.batch()) {
                Settings.setString("clickgui.ysm.page",KEYS[index]);
                Settings.setString("clickgui.settings.page","YSM");
            }
            relayout();
        }
        @Override protected void layout() {
            float row=16, cell=width/TABS.length;
            for (int i=0;i<TABS.length;i++) tabs[i].bounds(x+cell*i,y,cell,row);
            models.bounds(x,y+row,width,Math.max(0,height-row));
            studio.bounds(x,y+row,width,Math.max(0,height-row));
        }
    }

    private static final class Content extends Widget {
        private final Scroll list = new Scroll(false);
        private final ArrayList<ModelRow> rows = new ArrayList<>();
        private final TextField search;
        private final Button apply, refresh, vanilla;
        private final TextField directoryPath;
        private final Button browse, useFolder;
        private boolean editingDirectory;
        private volatile boolean browsing, chooserDone;
        private volatile String chosenDirectory, chooserFailure;
        private final Line active = new Line(false), message = new Line(true), count = new Line(true);
        private final Line folder = new Line(true) {
            @Override protected boolean input(UiEvent e) {
                if (snapshot == null) return false;
                if (e.kind == UiEvent.Kind.PRESS && e.button == UiEvent.PRIMARY ||
                        e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ENTER) {
                    editingDirectory = !editingDirectory;
                    directoryPath.value(snapshot.directory());
                    showDirectoryEditor();
                    if (editingDirectory) { tree.focus(directoryPath); directoryPath.selectAll(); }
                    return true;
                }
                if (e.kind == UiEvent.Kind.PRESS && e.button == UiEvent.SECONDARY) {
                    tree.clipboard.set(snapshot.directory());
                    return true;
                }
                return false;
            }
            @Override protected boolean focusable() { return true; }
        };
        private final Line empty = new Line(false);
        private YsmSelector.Snapshot snapshot;
        private List<YsmSelector.Model> models = List.of();
        private String selected = "", query = "", error = "";
        private long pollAt;

        Content() {
            directoryPath = new TextField("Model folder path", ignored -> {}) {
                @Override protected boolean input(UiEvent e) {
                    if (e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ENTER) {
                        submitDirectory();
                        return true;
                    }
                    return super.input(e);
                }
            };
            directoryPath.tooltip("Leave empty to use the AppData models folder");
            browse = action("Browse…", () -> !browsing, this::chooseDirectory);
            useFolder = action("Use folder", () -> true, this::commitDirectory);
            search = new TextField("Search models", text -> {
                query = text.toLowerCase(Locale.ROOT);
                list.restore(0);
                filter();
            }, true);
            apply = action("Apply", () -> !selected.isEmpty(), () -> YsmSelectorBinding.select(selected));
            refresh = action("Refresh", () -> true, YsmSelectorBinding::refresh);
            vanilla = action("Use vanilla", () -> true, YsmSelectorBinding::reset);
            add(active); add(message); add(folder);
            add(directoryPath); add(browse); add(useFolder);
            add(apply); add(refresh); add(vanilla);
            add(search); add(count); add(list); add(empty);
            sync(null);
        }

        private Button action(String title, BooleanSupplier enabled, Runnable run) {
            return new Button(title, () -> {
                if (!enabled.getAsBoolean()) return;
                try { run.run(); error = ""; }
                catch (RuntimeException failure) {
                    error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                }
                pollAt = 0;
                status();
            }) {
                @Override protected void paint(WidgetCanvas c) {
                    boolean live = enabled.getAsBoolean();
                    if (live && (hovered() || focused())) c.paint.fill(x,y,width,height,0,Floating.HOVER);
                    c.centeredLabel(title,x+width/2,y+height/2,live ? Theme.TEXT : Theme.DIM);
                }
            };
        }
        private void showDirectoryEditor() {
            boolean visible = snapshot != null && editingDirectory;
            directoryPath.visible(visible); browse.visible(visible); useFolder.visible(visible);
            relayout();
        }
        private void commitDirectory() {
            YsmSelectorBinding.directory(directoryPath.value());
            editingDirectory = false;
            showDirectoryEditor();
        }
        private void submitDirectory() {
            try { commitDirectory(); error = ""; }
            catch (RuntimeException failure) {
                error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            }
            pollAt = 0;
            status();
        }
        private void chooseDirectory() {
            if (browsing) return;
            browsing = true;
            String previous = directoryPath.value();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    var selected = FileDialogs.directory(previous);
                    if (selected != null) chosenDirectory = selected.toString();
                } catch (java.io.IOException | RuntimeException failure) {
                    chooserFailure = failure.getMessage() == null ? "Could not open the folder picker" : failure.getMessage();
                } finally { browsing = false; chooserDone = true; }
            });
        }
        @Override protected void tick(long now) {
            if (chooserDone) {
                chooserDone = false;
                String chosen = chosenDirectory;
                chosenDirectory = null;
                if (chosen != null) { directoryPath.value(chosen); submitDirectory(); }
                String failure = chooserFailure;
                chooserFailure = null;
                if (failure != null) { error = failure; status(); }
                repaint();
            }
            if (now < pollAt) return;
            pollAt = now + 250_000_000L;
            var next = YsmSelectorBinding.snapshot();
            if (next != snapshot) sync(next);
        }
        private void sync(YsmSelector.Snapshot next) {
            snapshot = next;
            var items = next == null ? List.<YsmSelector.Model>of() : next.models();
            if (!items.equals(models)) {
                for (var row : rows) list.remove(row);
                rows.clear();
                for (var model : items) { var row = new ModelRow(model); rows.add(row); list.add(row); }
                models = items;
            }
            if (!contains(selected)) selected = next != null && contains(next.requested()) ? next.requested() : "";
            boolean connected = next != null;
            if (!connected) editingDirectory = false;
            showDirectoryEditor();
            for (Widget w : new Widget[]{apply, refresh, vanilla, search, list, folder, count}) w.visible(connected);
            if (!connected) error = "";
            active.set(connected ? "Active · " + (next.active().isEmpty() ? "Original player model" : next.active())
                    : "YSM is unavailable", Theme.TEXT);
            folder.set(connected ? "Model folder · " + next.directory() : "", Theme.MUTED);
            if (connected) folder.tooltip(next.directory() + "\nClick to change the folder · Right-click to copy");
            filter();
            status();
        }
        private boolean contains(String id) {
            for (int i=0; i<models.size(); i++) if (models.get(i).id().equals(id)) return true;
            return false;
        }
        private void status() {
            String text = snapshot == null ? "Load the YSM module for this game version." : snapshot.message();
            message.set(error.isEmpty() ? text : error,
                    !error.isEmpty() || snapshot != null && snapshot.state()==YsmSelector.State.ERROR ? Theme.DANGER : Theme.MUTED);
            repaint();
        }
        private void filter() {
            int matches = 0;
            for (var row : rows) {
                boolean show = row.searchName.contains(query);
                row.visible(show);
                if (show) matches++;
            }
            count.set("Local models · " + matches, Floating.MODULE);
            empty.set(models.isEmpty() ? "Add a .ysm, .zip or model folder, then select Refresh."
                    : "No models match your search.", Theme.MUTED);
            empty.visible(snapshot != null && matches == 0);
        }
        @Override protected void layout() {
            float inset = 5, w = Math.max(0,width-2*inset), l=x+inset;
            active.bounds(l,y+2,w,14); message.bounds(l,y+16,w,13);
            folder.bounds(l,y+30,w,13);
            float extra = editingDirectory ? 36 : 0;
            directoryPath.bounds(l,y+44,w,16);
            browse.bounds(l,y+62,w/2,16); useFolder.bounds(l+w/2,y+62,w/2,16);
            float third=w/3;
            apply.bounds(l,y+45+extra,third,16); refresh.bounds(l+third,y+45+extra,third,16); vanilla.bounds(l+2*third,y+45+extra,third,16);
            search.bounds(l,y+65+extra,w,16); count.bounds(l,y+84+extra,w,13);
            list.bounds(l,y+98+extra,w,Math.max(0,height-101-extra)); empty.bounds(l,y+99+extra,w,16);
        }
        private final class ModelRow extends Button {
            final YsmSelector.Model model;
            final String searchName;
            ModelRow(YsmSelector.Model model) {
                super(model.name(), () -> {
                    selected = model.id();
                    Content.this.repaint();
                });
                this.model = model;
                searchName = model.name().toLowerCase(Locale.ROOT);
                tooltip(model.name()+"\n"+model.format());
            }
            @Override public float preferredHeight() { return 23; }
            @Override protected boolean lightText() { return true; }
            @Override protected void paint(WidgetCanvas c) {
                boolean chosen = selected.equals(model.id());
                if (chosen || hovered() || focused()) c.paint.fill(x,y,width,height,0,Floating.HOVER);
                boolean applied = model.id().equals(snapshot.active());
                boolean loading = model.id().equals(snapshot.requested()) && snapshot.state()==YsmSelector.State.LOADING;
                String state = applied ? "Active" : loading ? "Loading…" : "";
                float mid = y + height/2, left=x+15, right=x+width-4;
                c.icon("player",x+3,mid-3.5f,7,chosen ? Theme.TEXT : Floating.ICON_COLOR);
                var previous = c.useFont(Floating.DETAIL_SIZE,true);
                float statusW = state.isEmpty() ? 0 : c.width(state)+5;
                if (!state.isEmpty()) c.accentLabel(state,c.rightX(state,right),c.centeredY(state,mid),255,1);
                c.paint.pushClip(left,y,Math.max(0,right-left-statusW),height);
                c.label(model.format(),c.leftX(model.format(),left),c.centeredY(model.format(),mid+4.5f),Theme.MUTED);
                c.restoreFont(previous);
                float tx=c.leftX(model.name(),left), ty=c.centeredY(model.name(),mid-4);
                if (chosen) c.accentLabel(model.name(),tx,ty,255,1);
                else c.label(model.name(),tx,ty,Floating.MODULE);
                c.paint.popClip();
            }
        }
    }
    private static class Line extends Widget {
        private final boolean small;
        private String text="";
        private int color;
        Line(boolean small) { this.small=small; }
        void set(String value,int color) {
            this.text=value;
            this.color=color;
            tooltip(value);
            repaint();
        }
        @Override protected boolean lightText() { return true; }
        @Override protected float textSize() { return small ? Floating.DETAIL_SIZE : Floating.TEXT_SIZE; }
        @Override protected void paint(WidgetCanvas c) {
            c.paint.pushClip(x,y,width,height);
            c.label(text,c.leftX(text,x),c.centeredY(text,y+height/2),color);
            c.paint.popClip();
        }
    }
}
