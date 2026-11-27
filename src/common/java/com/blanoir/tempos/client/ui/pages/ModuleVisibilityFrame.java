package com.blanoir.tempos.client.ui.pages;

import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.ui.clickgui.GuiModel;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.Button;
import com.blanoir.tempos.client.ui.widget.Scroll;
import com.blanoir.tempos.client.ui.widget.TextField;
import com.blanoir.tempos.client.ui.widget.Widget;
import com.blanoir.tempos.client.ui.widget.WidgetCanvas;
import com.blanoir.tempos.client.ui.widget.FloatingWindow;
import com.blanoir.tempos.client.ui.widget.ToggleSwitch;
import java.util.ArrayList;
import java.util.Locale;

public final class ModuleVisibilityFrame extends FloatingWindow {
    private final Content content;

    public ModuleVisibilityFrame(GuiModel model, Runnable dismiss, float x, float y, float w, float h) {
        this(new Content(model), dismiss, x, y, w, h);
    }
    private ModuleVisibilityFrame(Content content, Runnable dismiss, float x, float y, float w, float h) {
        super("Module visibility","all_modules",content,dismiss,x,y,w,h);
        this.content = content;
    }
    public TextField search() { return content.search; }

    private static final class Content extends Widget {
        final GuiModel model;
        final Scroll list = new Scroll(false);
        final ArrayList<Entry> entries = new ArrayList<>();
        final TextField search;
        final ToggleSwitch filter;
        final Button show;
        boolean hiddenOnly;
        String query = "", count = "";
        long seen = -1;
        int matches;

        Content(GuiModel model) {
            this.model = model;
            search = new TextField("Find a module", value -> {
                query = value.trim().toLowerCase(Locale.ROOT);
                list.restore(0);
                refresh();
            }, true);
            filter = new ToggleSwitch("Hidden only", () -> {
                hiddenOnly = !hiddenOnly;
                list.restore(0);
                refresh();
            }, () -> hiddenOnly);
            show = new Button("Show all", model::showAll) {
                @Override protected void paint(WidgetCanvas c) {
                    c.accentLabel("Show all",c.leftX("Show all",x+4),c.centeredY("Show all",y+height/2),255,1);
                }
            };
            add(search);
            add(filter);
            add(show);
            add(list);
            for (ModuleCategory category : ModuleCategory.ordered()) {
                for (var row : model.rows(category)) {
                    var entry = new Entry(model, row);
                    entries.add(entry);
                    list.add(entry);
                }
            }
            refresh();
        }
        private void refresh() {
            int hidden = 0;
            matches = 0;
            for (Entry entry : entries) {
                if (entry.row.hidden()) hidden++;
                boolean shown = (!hiddenOnly || entry.row.hidden()) && entry.row.matches(query);
                entry.visible(shown);
                if (shown) matches++;
            }
            count = hidden + " hidden";
            show.visible(hidden > 0);
            seen = model.revision();
            repaint();
        }
        @Override protected void tick(long now) {
            if (seen != model.revision()) refresh();
        }
        @Override protected void layout() {
            search.bounds(x + 5, y + 4, width - 10, 21);
            float filterWidth=Math.min(92,width-10);
            filter.bounds(x + 5, y + 29, filterWidth, 19);
            show.bounds(x + 7 + filterWidth, y + 29, 43, 19);
            list.bounds(x + 4, y + 53, width - 8, height - 57);
        }
        @Override protected void paint(WidgetCanvas c) {
            var previous=c.useFont(Theme.Floating.DETAIL_SIZE,false);
            c.label(count,c.rightX(count,x+width-7),c.centeredY(count,y+38.5f),Theme.MUTED);
            c.restoreFont(previous);
            if (matches == 0) c.centeredLabel(hiddenOnly ? "No matching hidden modules" : "No matches", x + width / 2, y + 66, Theme.MUTED);
        }
    }

    private static final class Entry extends Button {
        final GuiModel.Row row;
        Entry(GuiModel model, GuiModel.Row row) {
            super(row.name(), () -> model.hidden(row, !row.hidden()));
            this.row = row;
        }
        @Override protected boolean lightText() { return true; }
        @Override public float preferredHeight() { return 21; }
        @Override protected void paint(WidgetCanvas c) {
            if (hovered() || focused()) c.paint.fill(x, y, width, height, 0, Theme.Floating.HOVER);
            float mid=y+height/2, box=x+7;
            c.paint.outline(box,mid-3.5f,7,7,.5f,.75f,row.hidden()?Theme.TRACK:Theme.MUTED);
            if (!row.hidden()) {
                c.paint.line(box+1.4f,mid,box+3,mid+1.6f,.9f,Theme.TEXT);
                c.paint.line(box+3,mid+1.6f,box+5.6f,mid-1.6f,.9f,Theme.TEXT);
            }
            c.label(row.name(),c.leftX(row.name(),x+20),c.centeredY(row.name(),mid),row.hidden()?Theme.MUTED:Theme.Floating.MODULE);
        }
    }
}
