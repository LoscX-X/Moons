package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.ui.theme.Theme.Floating;
import com.blanoir.tempos.client.ui.widget.Scroll;
import com.blanoir.tempos.client.ui.widget.FloatingWindow;
import java.util.ArrayList;
import java.util.Locale;

/** An independent floating list. A search never writes the temporary layout to the profile. */
final class CategoryFrame extends FloatingWindow {
    final ModuleCategory category;
    final String id, prefix;
    final ArrayList<ModuleCard> cards = new ArrayList<>();
    final Scroll list;
    boolean open, matches;
    private boolean homeFolded, searching;
    private float homeX, homeY, searchX, searchY, savedScroll;
    private boolean positionUnsaved;

    CategoryFrame(GuiModel model, ModuleCategory category, boolean initial) {
        this(model, category, initial, new Scroll(false));
    }
    private CategoryFrame(GuiModel model, ModuleCategory category, boolean initial, Scroll list) {
        super(category.label(),category.name().toLowerCase(Locale.ROOT),list,null,0,0,
                Floating.WIDTH,Floating.HEADER+Floating.ROW+Floating.BOTTOM);
        this.category = category;
        this.list = list;
        id = category.name().toLowerCase(Locale.ROOT);
        prefix = "clickgui.floating.frame." + id + ".";
        homeX = (float)Settings.getDouble(prefix + "x", Double.NaN);
        homeY = (float)Settings.getDouble(prefix + "y", Double.NaN);
        open = Settings.getBoolean(prefix + "visible", initial);
        homeFolded = Settings.getBoolean(prefix + "folded", false);
        savedScroll = (float)Settings.getDouble(prefix + "scroll", 0);
        folded(homeFolded);
        list.restore(savedScroll);
        list.onScrollFinished(value -> {
            if (!searching) { savedScroll = (float)value; Settings.setDouble(prefix + "scroll", value); }
        });
        for (var row : model.roots(category)) {
            ModuleCard card = new ModuleCard(model, row);
            cards.add(card);
            list.add(card);
        }
    }
    void filter(GuiModel model, boolean changed) {
        boolean filtering = !model.emptySearch();
        if (changed) {
            if (!searching) {
                savedScroll = list.offset();
                searchX = homeX;
                searchY = homeY;
            }
            searching = filtering;
            folded(!searching && homeFolded);
            list.restore(searching ? 0 : savedScroll);
        }
        var rows = model.roots(category);
        matches = false;
        for (int i = 0; i < cards.size(); i++) {
            boolean visible = rows.get(i).visible();
            cards.get(i).visible(visible);
            matches |= visible;
        }
    }
    void open(boolean next) {
        if (open == next) return;
        open = next;
        Settings.setBoolean(prefix + "visible", next);
    }
    void resetPosition() {
        homeX = homeY = searchX = searchY = Float.NaN;
        positionUnsaved = false;
        Settings.remove(prefix + "x");
        Settings.remove(prefix + "y");
    }
    boolean hasPosition() {
        return searching ? Float.isFinite(searchX) && Float.isFinite(searchY)
                         : Float.isFinite(homeX) && Float.isFinite(homeY);
    }
    void initialPosition(float px, float py) {
        if (searching) { searchX = px; searchY = py; }
        else { homeX = px; homeY = py; positionUnsaved = true; }
    }
    void savePosition() {
        if (!positionUnsaved) return;
        Settings.setDouble(prefix + "x", homeX);
        Settings.setDouble(prefix + "y", homeY);
        positionUnsaved = false;
    }
    void place(float screenW, float screenH) {
        float w = Math.min(screenW, Floating.WIDTH);
        float px = dragging() ? x : searching ? searchX : homeX;
        float py = dragging() ? y : searching ? searchY : homeY;
        px = Math.clamp(px, 0, Math.max(0, screenW - w));
        py = Math.clamp(py, 0, Math.max(0, screenH - Floating.HEADER));
        float rows = 0;
        for (int i = 0; i < cards.size(); i++) if (cards.get(i).visible()) rows += cards.get(i).preferredHeight();
        float h = Floating.HEADER + Math.min(rows, Math.max(0, Math.min(Floating.MAX_HEIGHT, screenH - py) - Floating.HEADER - Floating.BOTTOM)) + Floating.BOTTOM;
        expandedSize(h);
        bounds(px, py, w, folded() ? Floating.HEADER : h);
    }
    @Override protected void layout() {
        list.bounds(x + 1, y + Floating.HEADER, width - 2, height - Floating.HEADER - Floating.BOTTOM);
    }
    @Override protected void layoutChanged() {
        if (searching) { searchX = x; searchY = y; }
        else if (homeFolded != folded()) {
            homeFolded = folded();
            Settings.setBoolean(prefix + "folded", homeFolded);
        } else {
            homeX = x;
            homeY = y;
            positionUnsaved = true;
            ((FloatingDesktop)parent).rememberPositions();
        }
        relayout();
    }
}
