package com.blanoir.tempos.client.ui.widget;

import java.util.ArrayList;

/** Retains measured word breaks until a control changes width or gets a new font atlas. */
final class SettingLabel {
    private final String title;
    private String[] lines;
    private WidgetCanvas metrics;
    private float available, lineHeight = 9;

    SettingLabel(String title) { this.title = title; lines = new String[]{title}; }
    float extraHeight() { return (lines.length - 1) * lineHeight; }
    boolean fit(WidgetCanvas c, float room) {
        room = Math.max(1, room);
        if (metrics == c && room == available) return false;
        metrics = c;
        available = room;
        float previous = extraHeight();
        lineHeight = (float)Math.ceil(Math.max(c.fontSize(), c.usesNative(title) ? c.nativeLineHeight() : 0));
        if (c.width(title) <= room) {
            lines = new String[]{title};
        } else {
            var result = new ArrayList<String>();
            int start = 0;
            while (start < title.length()) {
                int end = start, space = -1;
                while (end < title.length()) {
                    int after = title.offsetByCodePoints(end, 1);
                    if (end > start && c.width(title.substring(start, after)) > room) break;
                    if (title.charAt(end) == ' ') space = end;
                    end = after;
                }
                if (end < title.length() && space > start) end = space;
                result.add(title.substring(start, end).stripTrailing());
                start = end;
                while (start < title.length() && title.charAt(start) == ' ') start++;
            }
            lines = result.toArray(String[]::new);
        }
        return previous != extraHeight();
    }
    void draw(WidgetCanvas c, float left, float middle, int color) {
        float center = middle - extraHeight() / 2;
        for (String line : lines) {
            c.label(line,c.leftX(line,left),c.centeredY(line,center),color);
            center += lineHeight;
        }
    }
}
