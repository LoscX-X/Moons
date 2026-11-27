package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;

/** A row owns its input and retains expanded controls while the category scrolls. */
final class ModuleCard extends Widget {
    static final float ROW_HEIGHT = Floating.ROW;
    private final GuiModel model;
    private final GuiModel.Row row;
    private final Anim fill = new Anim(0), open = new Anim(0);
    private static final class Field {
        final Setting setting;
        JsonElement value;
        boolean visible;
        Field(Setting setting) { this.setting = setting; read(); }
        void read() { value = setting.value().get(); visible = setting.isVisible(); }
    }
    private final ArrayList<Field> fields = new ArrayList<>();
    private float enabled, extra, controlsHeight;
    private boolean built, expanded, displayingFields;
    private long observed = -1, settingsVersion = -1;
    private static final int ACTION_WIDTH = 14;

    ModuleCard(GuiModel model, GuiModel.Row row) {
        this.model = model; this.row = row;
        tooltip(row.description());
        enabled = row.enabled() ? 1 : 0;
        fill.snap(enabled);
    }
    @Override protected boolean focusable() { return true; }
    @Override protected boolean lightText() { return true; }
    @Override protected boolean clipChildren() { return extra > 0; }
    @Override public float preferredHeight() { return ROW_HEIGHT + extra; }
    @Override protected void tick(long now) {
        if (observed != model.revision()) {
            observed = model.revision();
            boolean wasExpanded = expanded;
            expanded = model.expanded(row);
            if (expanded) {
                if (!built) build();
                if (settingsVersion != Settings.revision()) syncFields();
                for (int i = 0; i < children.size(); i++) {
                    Widget child = children.get(i);
                    child.visible(available(i));
                    if (!wasExpanded && child instanceof ModulePreview preview) preview.refreshOnOpen();
                }
                displayingFields = true;
                controlsHeight = measureFields();
            }
            open.to(expanded ? controlsHeight : 0, now, Theme.EXPAND_MS);
            fill.to(row.enabled() ? 1 : 0, now, Theme.ENABLE_MS);
            repaint();
        }
        float next = open.value(now);
        if (next != extra) { extra = next; relayout(); }
        if (!expanded && extra == 0 && displayingFields) {
            displayingFields = false;
            for (int i = 0; i < children.size(); i++) children.get(i).visible(false);
        }
        next = fill.value(now);
        if (next != enabled) { enabled = next; repaint(); }
    }
    private boolean available(int i) { return fields.get(i) == null || fields.get(i).visible; }
    private void syncFields() {
        settingsVersion = Settings.revision();
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (field == null) continue;
            JsonElement previous = field.value;
            field.read();
            if (field.value.equals(previous)) continue;
            Widget w = children.get(i);
            if (w instanceof LabeledEditor labeled) w = labeled.editor();
            if (w instanceof ColorPicker picker) picker.sync();
            else if (w instanceof EntityListEditor list) list.sync(field.value.getAsJsonArray());
            else if (w instanceof TextField text && tree.focused() != w) text.value(field.value.getAsString());
            else if (w instanceof Slider slider) {
                JsonElement v = field.value;
                if (v.isJsonArray()) slider.sync(v.getAsJsonArray().get(0).getAsDouble(), v.getAsJsonArray().get(1).getAsDouble());
                else slider.sync(v.getAsDouble(), v.getAsDouble());
            }
        }
    }
    private float measureFields() {
        float result = 0;
        for (int i = 0; i < children.size(); i++) if (available(i)) result += children.get(i).preferredHeight();
        return result;
    }
    private void build() {
        built = true;
        if (row.hasControls()) for (Setting setting : row.settings()) {
            Field field = new Field(setting);
            Widget editor = control(field);
            if (editor != null) { fields.add(field); add(editor); }
        }
        ModulePreview preview = row.createPreview();
        if (preview != null) { fields.add(null); add(preview); }
        if (children.isEmpty()) {
            fields.add(null);
            String text = row.hasControls() ? "No settings" : "Controls unavailable";
            add(new Widget() {
                @Override public float preferredHeight() { return Theme.LINE; }
                @Override protected void paint(WidgetCanvas c) { c.centeredLabel(text, x + width / 2, y + height / 2, Theme.DIM); }
            });
        }
    }
    private Widget control(Field field) {
        Setting s = field.setting;
        JsonElement value = field.value;
        return switch (s.type()) {
            case "boolean" -> new ToggleSwitch(s.name(), () -> model.set(row,s,new JsonPrimitive(!s.value().get().getAsBoolean())),
                    () -> field.value.getAsBoolean());
            case "choice" -> new Choice(s.name(), s.options(), () -> field.value.getAsString(),
                    next -> model.set(row,s,new JsonPrimitive(next)));
            case "entity_list", "mob_list", "item_list", "block_list" -> new EntityListEditor(s.name(), s.type(), () -> s.value().get().getAsJsonArray(),
                    next -> model.set(row,s,next));
            case "integer", "number", "range" -> {
                boolean range = s.type().equals("range");
                double low = range ? value.getAsJsonArray().get(0).getAsDouble() : value.getAsDouble();
                double high = range ? value.getAsJsonArray().get(1).getAsDouble() : low;
                double step = s.step() == null ? (s.type().equals("integer") ? 1 : .01) : s.step();
                if (s.min() == null || s.max() == null || !(step > 0)) yield null;
                yield new Slider(s.name(), s.min(), s.max(), step, low, high, range, (a, b) -> {
                    if (range) { JsonArray bounds = new JsonArray(2); bounds.add(a); bounds.add(b); model.set(row,s,bounds); }
                    else if (s.type().equals("integer")) model.set(row,s,new JsonPrimitive(Math.round(a)));
                    else model.set(row,s,new JsonPrimitive(a));
                });
            }
            case "text" -> {
                TextField input = new TextField(s.name(), ignored -> {}) {
                    private void save() {
                        if (!value().equals(s.value().get().getAsString())) model.set(row,s,new JsonPrimitive(value()));
                    }
                    @Override protected void focusChanged(boolean on) { super.focusChanged(on); if (!on) save(); }
                    @Override protected boolean input(UiEvent e) {
                        if (e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ENTER) { save(); return true; }
                        return super.input(e);
                    }
                };
                input.value(value.getAsString());
                yield new LabeledEditor(s.name(), input);
            }
            case "color" -> {
                yield new ColorPicker(s.name(), new ColorPicker.Binding() {
                    public String hex() { return field.value.getAsString(); }
                    public void preview(int color) {}
                    public void commit(String hex) { model.set(row,s,new JsonPrimitive(hex)); }
                }, false, false);
            }
            default -> null;
        };
    }
    @Override protected void layout() {
        float nextY = y + ROW_HEIGHT;
        for (int i = 0; i < children.size(); i++) {
            Widget w = children.get(i);
            if (!w.visible()) continue;
            w.bounds(x + 3, nextY, width - 6, w.preferredHeight());
            nextY += w.preferredHeight();
        }
        if (built && model.expanded(row)) {
            float next = measureFields();
            if (controlsHeight != next) { controlsHeight = next; open.to(next, System.nanoTime(), Theme.EXPAND_MS); }
        }
    }
    private boolean showKey() {
        return row.hasControls() && (!row.binding().isEmpty() || hovered() || focused() || model.expanded(row)
                || model.binding(row) || !model.feedback(row).isEmpty());
    }
    private boolean onKey(float px,float py) {
        float end=x+width-ACTION_WIDTH;
        return showKey() && px>=end-Math.max(10,keyWidth)-1 && px<end && Math.abs(py-y-ROW_HEIGHT/2)<=7;
    }
    @Override public String tooltip() {
        if (tree==null || !onKey(tree.pointerX(),tree.pointerY())) return super.tooltip();
        if (model.binding(row)) return "Click to clear binding";
        if (!row.supportsMode()) return "Click to bind";
        return row.hold() ? "Click to bind\nShift-click to change mode\nHold" : "Click to bind\nShift-click to change mode\nToggle";
    }
    @Override protected void paint(WidgetCanvas c) {
        if (extra > 0) c.paint.fill(x, y + ROW_HEIGHT, width, extra, 0, Floating.SETTINGS);
        if (hovered() || expanded) c.paint.fill(x, y, width, ROW_HEIGHT, 0, Floating.HOVER);
        if (enabled > 0) {
            c.paint.accentFill(x + 1, y + 4, 1, ROW_HEIGHT - 8, 0, (int)(255 * enabled), 1);
        }
        float end = x + width - ACTION_WIDTH;
        float middle = y + ROW_HEIGHT / 2;
        String name = row.name();
        String feedback = model.feedback(row);
        float nameX = x + 6;
        String key = model.binding(row) ? "×" : row.binding();
        keyWidth = 0;
        if (showKey()) {
            var previous = c.useFont(Floating.DETAIL_SIZE,false);
            keyWidth = key.length() <= 1 ? 10 : Math.min(50, c.width(key) + 5);
            float size = keyWidth;
            c.paint.fill(end - size, middle - 5, size, 10, 1, Floating.KEY);
            if (model.binding(row) || !feedback.isEmpty()) c.paint.accentOutline(end-size,middle-5,size,10,1,1,255,1);
            if (row.hold()) c.paint.accentFill(end-size,middle+4,size,1,0,225,1);
            if (key.isEmpty()) {
                c.paint.outline(end-8, middle-2.5f, 6, 5, 1, .75f, Floating.ICON_COLOR);
                c.paint.fill(end-6.5f, middle+1, 3, .7f, 0, Floating.ICON_COLOR);
            } else c.centeredLabel(c.width(key) <= size ? key : "…",end - size / 2,middle,Theme.MUTED);
            c.restoreFont(previous);
        }
        float room = Math.max(0,end - keyWidth - 3 - nameX);
        boolean clipName = c.width(name) > room;
        if (clipName) c.paint.pushClip(nameX, y, room, ROW_HEIGHT);
        float nameY = c.centeredY(name,middle);
        float textX = c.leftX(name,nameX);
        if (enabled > .5) c.accentLabel(name,textX,nameY,255,1);
        else c.label(name,textX,nameY,Floating.MODULE);
        if (clipName) c.paint.popClip();
        for (int i = -1; i <= 1; i++)
            c.paint.fill(x + width - 7.5f,middle - .6f + 2.7f*i,1.2f,1.2f,.6f,Floating.ICON_COLOR);
        if (!feedback.isEmpty()) tree.callout(this,feedback,middle);
    }
    @Override protected boolean input(UiEvent e) {
        if (e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ENTER) { model.toggle(row); return true; }
        if (e.kind != UiEvent.Kind.PRESS || e.y >= y + ROW_HEIGHT) return false;
        if (e.button == UiEvent.SECONDARY || e.button == UiEvent.PRIMARY && e.x >= x + width - ACTION_WIDTH) {
            model.expand(row); return true;
        }
        if (e.button != UiEvent.PRIMARY) return false;
        if (onKey(e.x,e.y)) {
            if (e.shift && row.supportsMode()) model.switchMode(row);
            else model.beginBinding(row);
        }
        else model.toggle(row);
        return true;
    }
    private float keyWidth;
}
