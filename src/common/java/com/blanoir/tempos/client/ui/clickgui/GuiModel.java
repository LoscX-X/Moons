package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.widget.ModulePreview;
import com.blanoir.tempos.client.input.InputKeys;
import com.blanoir.tempos.client.input.BindActivationMode;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleKeybinds;
import com.blanoir.tempos.client.module.framework.ModuleModel.Module;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.ModuleRegistry;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** Shared module titles, search, expansion and binding state for the native configuration screens. */
public final class GuiModel {
    public static final class Row {
        private final Module module;
        private final String terms, hiddenKey;
        private Row parent;
        private final ArrayList<Row> children = new ArrayList<>();
        private boolean visible, enabled, hidden, hold;
        private String binding = "";
        private Row(Module module) {
            this.module = module;
            hiddenKey = "clickgui.hidden." + module.id();
            var text = new StringBuilder(module.name()).append('\n').append(module.id()).append('\n').append(module.category().label());
            for (Setting setting : module.settings()) text.append('\n').append(setting.name());
            terms = text.toString().toLowerCase(Locale.ROOT);
        }
        public String id() { return module.id(); }
        public String name() {
            if (parent != null && id().equals("xraydisplay")) return "Display";
            if (parent != null && id().equals("xraytargets")) return "Targets";
            return module.name();
        }
        public List<Row> children() { return List.copyOf(children); }
        public String description() { return module.description(); }
        public ModuleCategory category() { return module.category(); }
        public List<Setting> settings() { return module.settings(); }
        ModulePreview createPreview() {
            var factory = ModulePreview.factory(module);
            return factory == null ? null : factory.get();
        }
        public boolean hasControls() { return true; }
        public boolean visible() { return visible; }
        public boolean hidden() { return hidden; }
        public boolean matches(String lowercaseQuery) {
            if (terms.contains(lowercaseQuery)) return true;
            return children.stream().anyMatch(child -> !Settings.getBoolean(child.hiddenKey, false) && child.matches(lowercaseQuery));
        }
        public boolean enabled() { return enabled; }
        public String binding() { return binding; }
        public boolean hold() { return hold; }
        public boolean supportsMode() { return hasControls() && !"clickgui".equals(id()); }
    }
    private final EnumMap<ModuleCategory, List<Row>> categories = new EnumMap<>(ModuleCategory.class);
    private final EnumMap<ModuleCategory, Row> expanded = new EnumMap<>(ModuleCategory.class);
    private final java.util.Set<Row> expandedChildren = new java.util.HashSet<>();
    private final ArrayList<Row> rows = new ArrayList<>();
    private final boolean[] watched = new boolean[ModuleCategory.ordered().size()];
    private String query = "";
    private long revision, settingsRevision = -1, poll;
    private Row binding, feedbackRow;
    private String feedback = "";
    private long feedbackUntil;

    public GuiModel() {
        for (ModuleCategory c : ModuleCategory.ordered()) categories.put(c, new ArrayList<>());
        for (Module module : ModuleRegistry.modules()) {
            Row row = new Row(module);
            rows.add(row);
            categories.get(module.category()).add(row);
        }
        Row xray = rows.stream().filter(row -> row.id().equals("xray")).findFirst().orElse(null);
        if (xray != null) for (String id : List.of("xraydisplay", "xraytargets")) {
            Row child = rows.stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
            if (child != null) { child.parent = xray; xray.children.add(child); }
        }
        refresh(true);
    }
    public List<Row> rows(ModuleCategory category) { return categories.get(category); }
    public List<Row> roots(ModuleCategory category) { return rows(category).stream().filter(row -> row.parent == null).toList(); }
    public boolean emptySearch() { return query.isEmpty(); }
    String query() { return query; }
    public long revision() { return revision; }
    void watch(ModuleCategory category, boolean value) {
        if (watched[category.ordinal()] == value) return;
        watched[category.ordinal()] = value;
        poll = 0;
    }
    public void search(String text) {
        String next = text.trim().toLowerCase(Locale.ROOT);
        if (next.equals(query)) return;
        expanded.clear();
        expandedChildren.clear();
        cancelCapture();
        query = next;
        refresh(true);
    }
    public void update(long now) {
        if(feedbackRow!=null && now>=feedbackUntil) {feedbackRow=null;feedback="";revision++;}
        if (settingsRevision != Settings.revision()) refresh(true);
        else if (now >= poll) refresh(false);
        else return;
        poll = now + 200_000_000L;
    }
    private void refresh(boolean settings) {
        settingsRevision = Settings.revision();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!settings && (!watched[r.category().ordinal()] || !r.visible)) continue;
            boolean on;
            try { on = r.module.enabled().getAsBoolean(); }
            catch (RuntimeException unavailable) { on = false; }
            if (r.enabled != on) { r.enabled = on; revision++; }
            if (!settings) continue;
            r.hidden = Settings.getBoolean(r.hiddenKey, false);
            boolean visible = !r.hidden && (r.parent == null || !Settings.getBoolean(r.parent.hiddenKey, false)) && r.matches(query);
            if (visible != r.visible) { r.visible = visible; revision++; }
            if (!r.hasControls()) continue;
            var key = ModuleKeybinds.getBoundKey(r.id());
            r.binding = InputKeys.label(key);
            r.hold = r.supportsMode() && ModuleKeybinds.activationMode(r.id()) == BindActivationMode.HOLD;
        }
        if (settings && !query.isEmpty()) for (Row r : rows) {
            if (r.parent != null && r.visible && !r.parent.terms.contains(query)) expanded.put(r.category(), r.parent);
        }
        if (binding != null && !binding.visible) cancelCapture();
        if (settings) revision++;
    }
    public boolean expanded(Row r) { return r.parent == null ? expanded.get(r.category()) == r : expandedChildren.contains(r); }
    public void hidden(Row row, boolean value) {
        if (value) {
            if (row.parent == null && expanded(row)) expanded.remove(row.category());
            expandedChildren.remove(row);
        }
        if (value) Settings.setBoolean(row.hiddenKey, true);
        else Settings.remove(row.hiddenKey);
        refresh(true);
    }
    public void showAll() {
        try (var batch = Settings.batch()) {
            for (Row row : rows) Settings.remove(row.hiddenKey);
        }
        refresh(true);
    }
    public void expand(Row r) {
        if (r.parent != null) {
            if (!expandedChildren.remove(r)) { expandedChildren.add(r); expanded.put(r.category(), r.parent); }
        } else if (expanded(r)) expanded.remove(r.category());
        else expanded.put(r.category(), r);
        revision++;
    }
    public void toggle(Row r) {
        if (!r.hasControls()) return;
        if ("clickgui".equals(r.id())) {
            notify(r, r.binding.isEmpty() ? "Bind a key first" : "Use the bound key");
            return;
        }
        ModuleRegistry.setEnabled(r.id(), !r.enabled);
        refresh(true);
    }
    public void set(Row r, Setting setting, JsonElement value) {
        if (!r.hasControls() || !r.settings().contains(setting)) return;
        ModuleRegistry.setValue(r.id(), setting.id(), value);
        refresh(true);
    }
    public boolean binding(Row row) { return binding == row; }
    public boolean capturing() { return binding!=null; }
    public String feedback(Row row) { return row==feedbackRow ? feedback : ""; }
    private void notify(Row row,String text) {
        feedbackRow=row; feedback=text; feedbackUntil=System.nanoTime()+2_000_000_000L; revision++;
    }
    void cancelCapture() {
        if(binding!=null) { binding=null;feedbackRow=null;feedback="";revision++; }
    }
    void switchMode(Row row) {
        if(!row.supportsMode()) return;
        cancelCapture();
        ModuleKeybinds.setActivationMode(row.id(),row.hold ? BindActivationMode.TOGGLE : BindActivationMode.HOLD);
        refresh(true);
    }
    public void beginBinding(Row row) {
        if (!row.hasControls()) return;
        if (binding==row) { clearBinding();return; }
        binding = row;
        notify(row,"Press a key to bind");
    }
    private void clearBinding() {
        Row target=binding;
        ModuleKeybinds.unbind(target.id());
        binding=null;
        refresh(true);
        notify(target,"Binding cleared");
    }
    public boolean acceptBinding(KeyEvent event) {
        if (binding == null) return false;
        int k = event.key();
        if (k == InputConstants.KEY_ESCAPE || k == InputConstants.KEY_DELETE || k == InputConstants.KEY_BACKSPACE) {
            clearBinding();
            return true;
        }
        Row target=binding;
        if (!ModuleKeybinds.bind(target.id(), InputKeys.fromEvent(event))) return true;
        binding = null;
        refresh(true);
        notify(target,"Bound to "+target.binding);
        return true;
    }
}
