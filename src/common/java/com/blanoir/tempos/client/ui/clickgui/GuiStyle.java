package com.blanoir.tempos.client.ui.clickgui;

import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.Widget;

public interface GuiStyle {
    String id();
    Widget build(GuiModel model, Theme theme);
}
