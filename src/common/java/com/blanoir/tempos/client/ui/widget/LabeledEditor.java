package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme.Floating;

/** A named string parameter, with its caption above the editable value. */
public final class LabeledEditor extends Widget {
    private final SettingLabel label;
    private final TextField editor;
    public LabeledEditor(String title, TextField editor) {
        this.label = new SettingLabel(title);
        this.editor = editor;
        editor.useSettingStyle();
        add(editor);
    }
    public Widget editor() { return editor; }
    @Override public float preferredHeight() { return 25 + label.extraHeight(); }
    @Override protected boolean lightText() { return true; }
    @Override protected float textSize() { return Floating.DETAIL_SIZE; }
    @Override protected void layout() {
        editor.bounds(x+5,y+10+label.extraHeight(),width-10,14);
    }
    @Override protected void paint(WidgetCanvas c) {
        if (label.fit(c,width-10)) relayout();
        label.draw(c,x+5,y+6+label.extraHeight()/2,Floating.MODULE);
    }
}
