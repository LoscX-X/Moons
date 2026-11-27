package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.utils.render.ColorCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Registry-list controls use each module's existing values and validation callbacks. */
public final class EntityListEditor extends Widget {
    private static final String DEFAULT_COLOR = "#00dcff";
    private final String name;
    private final String type;
    private final boolean colored;
    private final Supplier<JsonArray> read;
    private final Consumer<JsonArray> write;
    private String count;
    private Editor editor;

    public EntityListEditor(String name, Supplier<JsonArray> read, Consumer<JsonArray> write) {
        this(name, "entity_list", read, write);
    }

    public EntityListEditor(String name, String type, Supplier<JsonArray> read, Consumer<JsonArray> write) {
        this.name = name;
        this.type = type;
        this.colored = type.equals("entity_list") || type.equals("block_list");
        this.read = read;
        this.write = write;
        sync(read.get());
    }

    public void sync(JsonArray value) {
        count = Integer.toString(value.size());
        if (editor != null && editor.tree != null) editor.refresh(value);
        repaint();
    }

    @Override public float preferredHeight() { return 20; }
    @Override protected boolean focusable() { return true; }
    @Override protected boolean lightText() { return true; }
    @Override protected float textSize() { return Floating.FIELD_SIZE; }
    @Override protected void tick(long now) { if (editor != null && editor.tree == null) editor = null; }
    @Override protected void paint(WidgetCanvas c) {
        float middle = y + height / 2;
        c.paint.fill(x+5,y+3,width-10,height-6,2,Theme.FIELD);
        c.paint.outline(x+5,y+3,width-10,height-6,2,.75f,Theme.BORDER);
        c.paint.pushClip(x+9,y,Math.max(0,width-28-c.width(count)),height);
        c.label(name,c.leftX(name,x+10),c.centeredY(name,middle),Theme.TEXT);
        c.paint.popClip();
        c.label(count,c.rightX(count,x+width-18),c.centeredY(count,middle),Theme.MUTED);
        c.paint.line(x+width-13,middle-2,x+width-11,middle,.8f,Theme.MUTED);
        c.paint.line(x+width-11,middle,x+width-13,middle+2,.8f,Theme.MUTED);
    }
    @Override protected boolean input(UiEvent event) {
        if (!(event.kind == UiEvent.Kind.PRESS && event.button == UiEvent.PRIMARY
                || event.kind == UiEvent.Kind.KEY && event.key == UiEvent.Key.ENTER)) return false;
        editor = new Editor();
        float w = Math.min(176, tree.root.width()), h = Math.min(178, tree.root.height());
        float right = x + width + Floating.GAP;
        float left = right + w <= tree.root.width() ? right : Math.max(0, x - w - Floating.GAP);
        float top = Math.clamp(y - Floating.HEADER, 0, tree.root.height() - h);
        tree.popup(new FloatingWindow(name,null,editor,tree::closePopup,left,top,w,h));
        editor.refresh(read.get());
        tree.focus(editor.search);
        return true;
    }

    private record Entry(String id, String name, String search) {}
    private Entry entry(Identifier key) {
            String id = key.toString();
            String name = switch (type) {
                case "item_list" -> BuiltInRegistries.ITEM.getOptional(key)
                        .map(item -> new ItemStack(item).getHoverName().getString()).orElse(id);
                case "block_list" -> BuiltInRegistries.BLOCK.getOptional(key)
                        .map(block -> block.getName().getString()).orElse(id);
                default -> BuiltInRegistries.ENTITY_TYPE.getOptional(key)
                        .map(entity -> entity.getDescription().getString()).orElse(id);
            };
            return new Entry(id,name,(id+" "+name).toLowerCase(Locale.ROOT));
    }

    private final class Editor extends Widget {
        final Scroll rows = new Scroll(false);
        final TextField search = new TextField("Name or namespace:id", query -> {
            if (this.clearing) return;
            browse = true;
            rows.restore(0);
            refresh(read.get());
        },true);
        final Button selectedTab = action("Selected", () -> show(false), () -> !this.browse);
        final Button browseTab = action("Browse", () -> show(true), () -> this.browse);
        final Button remove = action("Remove", () -> change(null,true), null);
        final ColorPicker color;
        final ToggleSwitch enabled;
        final ArrayList<Entry> catalog = new ArrayList<>();
        boolean browse, clearing, revealSelection, activeEnabled = true;
        EntryRow editedRow;
        String editing = "", activeColor = DEFAULT_COLOR;

        Editor() {
            color = new ColorPicker("Color",new ColorPicker.Binding() {
                public String hex() { return activeColor; }
                public void preview(int argb) {}
                public void commit(String hex) { change(hex,false); }
            },false,false);
            enabled = new ToggleSwitch("Enabled", () -> {
                JsonArray next = read.get().deepCopy();
                int i = index(next, editing);
                if (i < 0) return;
                next.get(i).getAsJsonObject().addProperty("enabled", !activeEnabled);
                write.accept(next);
                sync(read.get());
            }, () -> activeEnabled);
            search.useSettingStyle();
            add(search); add(selectedTab); add(browseTab); add(rows); add(color); add(enabled); add(remove);
        }
        private void clearQuery() {
            clearing = true;
            search.value("");
            clearing = false;
        }
        private Button action(String label,Runnable click,BooleanSupplier selected) {
            return new Button(label,click,selected) {
                @Override protected boolean lightText() { return true; }
                @Override protected float textSize() { return Floating.FIELD_SIZE; }
            };
        }
        void show(boolean next) {
            browse = next;
            if (!next) clearQuery();
            rows.restore(0);
            refresh(read.get());
        }
        private int index(JsonArray value, String id) {
            for (int i = 0; i < value.size(); i++)
                if (id.equals(value.get(i).getAsJsonObject().get("id").getAsString())) return i;
            return -1;
        }
        private void change(String hex, boolean delete) {
            JsonArray next = read.get().deepCopy();
            int i = index(next,editing);
            if (i < 0) { refresh(next); return; }
            if (delete) { next.remove(i); editing = ""; }
            else next.get(i).getAsJsonObject().addProperty("color",hex);
            write.accept(next);
            sync(read.get());
        }
        private void choose(Entry entry) {
            JsonArray actual = read.get();
            int i = index(actual,entry.id());
            editing = entry.id();
            if (i < 0 && browse) {
                JsonArray next = actual.deepCopy();
                next.add(SettingFactory.entry(editing,colored ? DEFAULT_COLOR : null));
                write.accept(next);
                actual = read.get();
            }
            browse = false;
            clearQuery();
            revealSelection = true;
            sync(actual);
        }
        private void refresh(JsonArray value) {
            while (!rows.children.isEmpty()) rows.remove(rows.children.getLast());
            editedRow = null;
            HashSet<String> selected = new HashSet<>();
            boolean found = false;
            for (var element : value) {
                JsonObject item = element.getAsJsonObject();
                String id = item.get("id").getAsString(), hex = colored ? item.get("color").getAsString() : DEFAULT_COLOR;
                selected.add(id);
                if (id.equals(editing)) {
                    activeColor = hex;
                    activeEnabled = !colored || item.get("enabled").getAsBoolean();
                    found = true;
                }
                if (!browse) {
                    EntryRow row = new EntryRow(entry(Identifier.parse(id)),hex);
                    rows.add(row);
                    if (id.equals(editing)) editedRow = row;
                }
            }
            if (!found) editing = "";
            if (browse) {
                if (catalog.isEmpty()) {
                    var keys = switch (type) {
                        case "item_list" -> BuiltInRegistries.ITEM.keySet();
                        case "block_list" -> BuiltInRegistries.BLOCK.keySet();
                        default -> BuiltInRegistries.ENTITY_TYPE.keySet();
                    };
                    for (var key : keys) {
                        if (type.equals("mob_list") && key.toString().equals("minecraft:player")) continue;
                        catalog.add(entry(key));
                    }
                    catalog.sort(Comparator.comparing(Entry::name).thenComparing(Entry::id));
                }
                String query = search.value().strip().toLowerCase(Locale.ROOT);
                for (Entry entry : catalog) {
                    if (!selected.contains(entry.id()) && entry.search().contains(query)) rows.add(new EntryRow(entry,null));
                }
            }
            if (rows.children.isEmpty()) rows.add(new Widget() {
                final String text = browse ? "No matches" : "Selected: 0";
                @Override public float preferredHeight() { return 24; }
                @Override protected void paint(WidgetCanvas c) { c.centeredLabel(text,x+width/2,y+height/2,Theme.MUTED); }
            });
            color.visible(found && !browse && colored);
            enabled.visible(found && !browse && colored);
            remove.visible(found && !browse);
            if (color.visible()) color.sync();
            relayout();
        }
        @Override protected boolean clipChildren() { return true; }
        @Override protected void layout() {
            float left = x+5, w = Math.max(0,width-10);
            float tab = (w-3)/2;
            search.bounds(left,y+4,w,14);
            selectedTab.bounds(left,y+21,tab,14);
            browseTab.bounds(left+tab+3,y+21,tab,14);
            float bottom = y+height-4;
            if (remove.visible()) {
                remove.bounds(left,bottom-13,w,13);
                bottom -= 15;
                if (enabled.visible()) {
                    bottom -= enabled.preferredHeight();
                    enabled.bounds(left,bottom,w,enabled.preferredHeight());
                }
                if (color.visible()) {
                    bottom -= color.preferredHeight() + 2;
                    color.bounds(left,bottom,w,color.preferredHeight());
                }
            }
            float listHeight = Math.max(0,bottom-y-38);
            boolean resized = rows.height() != listHeight;
            rows.bounds(left,y+38,w,listHeight);
            if ((revealSelection || resized) && editedRow != null) {
                rows.layout();
                rows.reveal(editedRow);
            }
            revealSelection = false;
        }

        private final class EntryRow extends Button {
            final Entry entry;
            final boolean available;
            final int tint;
            EntryRow(Entry entry,String hex) {
                super(entry.name(),() -> choose(entry));
                this.entry = entry;
                available = hex == null;
                tint = available ? 0 : ColorCodec.parseRgbOrArgb(hex.substring(1));
                tooltip(entry.id());
            }
            @Override public float preferredHeight() { return 23; }
            @Override protected boolean lightText() { return true; }
            @Override protected float textSize() { return Floating.FIELD_SIZE; }
            @Override protected void paint(WidgetCanvas c) {
                if (hovered() || focused() || entry.id().equals(editing))
                    c.paint.fill(x,y,width,height,1,Floating.HOVER);
                if (available) c.centeredLabel("+",x+7,y+height/2,Theme.MUTED);
                else c.paint.fill(x+4,y+height/2-2.5f,5,5,1,tint);
                c.paint.pushClip(x+13,y,Math.max(0,width-16),height);
                c.label(entry.name(),c.leftX(entry.name(),x+14),c.centeredY(entry.name(),y+7.5f),Theme.TEXT);
                var previous = c.useFont(Floating.DETAIL_SIZE,false);
                c.label(entry.id(),c.leftX(entry.id(),x+14),c.centeredY(entry.id(),y+16),Theme.MUTED);
                c.restoreFont(previous);
                c.paint.popClip();
            }
        }
    }
}
