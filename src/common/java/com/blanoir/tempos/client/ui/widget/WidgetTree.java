package com.blanoir.tempos.client.ui.widget;

import com.blanoir.tempos.client.ui.theme.Theme;

/** Owns stacking, pointer capture, keyboard focus and popups for a screen. */
public final class WidgetTree {
    public interface Clipboard { String get(); void set(String text); }
    public final Widget root = new Widget();
    public final Clipboard clipboard;
    public final UiEvent event = new UiEvent();
    private Widget hover, focus, capture, popup;
    private int captureButton;
    private float mouseX = -1, mouseY = -1;
    private long hoverSince, revision;
    private boolean layoutDirty = true, tipVisible;
    private String tipText="";
    private String[] tipLines={""};
    private Widget note;
    private String noteText;
    private float noteY;

    public WidgetTree(Clipboard clipboard) {
        this.clipboard = clipboard;
        root.attach(this);
    }

    public void repaint() { revision++; }
    public void relayout() { layoutDirty = true; repaint(); }
    public long revision() { return revision; }
    public Widget hovered() { return hover; }
    public Widget focused() { return focus; }
    public float pointerX() { return mouseX; }
    public float pointerY() { return mouseY; }
    public boolean wantsText() { return focus != null && focus.textInput(); }
    public boolean hasPopup() { return popup != null; }
    public void callout(Widget row,String message,float centerY) {
        note=row;noteText=message;noteY=centerY;
    }
    public void raise(Widget item) {
        if (item == null || item.tree != this || item.parent == null) return;
        var siblings = item.parent.children;
        if (siblings.getLast() == item) return;
        siblings.remove(item);
        siblings.add(item);
        repaint();
    }
    public void focus(Widget next) {
        if (next != null && (next.tree != this || !next.focusable())) next = null;
        if (focus == next) return;
        Widget previous = focus;
        focus = next;
        if (previous != null) previous.focusChanged(false);
        if (next != null) next.focusChanged(true);
        repaint();
    }
    public void popup(Widget next) {
        while (popup != null) closePopup();
        root.add(next);
        popup = next;
    }
    /** A field editor returns to its enclosing popup on Enter, Escape, or an outside click. */
    void childPopup(Widget next) {
        if (popup == null) { popup(next); return; }
        popup.add(next);
        popup = next;
    }
    public void closePopup() {
        if (popup == null) return;
        Widget old = popup;
        Widget owner = old.parent;
        popup = owner == root ? null : owner;
        owner.remove(old);
    }
    public void cancelInput() {
        if (capture != null) capture.captureLost();
        capture = null;
        focus(null);
        hover(null, 0);
        while (popup != null) closePopup();
    }
    void detach(Widget removed) {
        if (inside(hover, removed)) hover(null, 0);
        if (inside(focus, removed)) focus(null);
        if (inside(capture, removed)) {
            capture.captureLost();
            capture = null;
        }
        if (inside(popup, removed)) popup = null;
    }
    private static boolean inside(Widget item, Widget ancestor) {
        for (; item != null; item = item.parent) if (item == ancestor) return true;
        return false;
    }
    private static Widget edge(Widget node, boolean back) {
        while (back && node.visible() && !node.children.isEmpty()) node = node.children.getLast();
        return node;
    }
    private static Widget nextInTree(Widget node, Widget base, boolean back) {
        if (!back && node.visible() && !node.children.isEmpty()) return node.children.getFirst();
        while (node != base) {
            Widget parent = node.parent;
            int i = parent.children.indexOf(node) + (back ? -1 : 1);
            if (i >= 0 && i < parent.children.size()) return edge(parent.children.get(i), back);
            if (back) return parent;
            node = parent;
        }
        return null;
    }
    private boolean cycleFocus(boolean back) {
        Widget base = popup == null ? root : popup;
        Widget start = inside(focus, base) ? focus : null;
        Widget cursor = start;
        do {
            cursor = cursor == null ? edge(base, back) : nextInTree(cursor, base, back);
            if (cursor == null) {
                if (start == null) return false;
                cursor = edge(base, back);
            }
            if (cursor.visible() && cursor.focusable()) {
                focus(cursor);
                for (Widget parent = cursor.parent; parent != null; parent = parent.parent)
                    if (parent instanceof Scroll scroll) scroll.reveal(cursor);
                return true;
            }
        } while (cursor != start);
        return false;
    }
    private void hover(Widget next, long now) {
        if (hover == next) return;
        hover = next;
        hoverSince = now;
        tipVisible = false;
        repaint();
    }
    private Widget hit() { return root.hit(mouseX, mouseY); }

    public void update(int width, int height, long now) {
        root.bounds(0, 0, width, height);
        root.tickTree(now);
        if (layoutDirty) {
            root.layoutTree();
            layoutDirty = false;
            hover(hit(), now);
        }
        String text=hover==null ? "" : hover.tooltip();
        if (!text.equals(tipText)) {
            tipText=text; hoverSince=now;
            tipLines=text.indexOf('\n')<0 ? new String[]{text} : text.split("\n");
            if (tipVisible) {tipVisible=false;repaint();}
        }
        boolean show = capture == null && !tipText.isEmpty()
                && now - hoverSince >= Theme.TOOLTIP_MS * 1_000_000L;
        if (show != tipVisible) { tipVisible = show; repaint(); }
    }

    public void paint(WidgetCanvas c) {
        note=null;
        for (int i = 0; i < root.children.size(); i++) {
            root.children.get(i).paintTree(c);
            c.flushText();
        }
        var previous = c.useFont(Theme.Floating.DETAIL_SIZE,false);
        if (note!=null && popup==null && root.hit(note.x+note.width/2,noteY)==note) {
            paintCallout(c);
        } else if (tipVisible && hover != null) {
            float textWidth=0;
            for (int i=0;i<tipLines.length;i++) textWidth=Math.max(textWidth,c.width(tipLines[i]));
            float w=Math.min(root.width,textWidth+2*Theme.PAD), h=Math.min(root.height,8+tipLines.length*11);
            float x = mouseX + 9, y = mouseY + 12;
            if (x + w > root.width) x = Math.max(0, mouseX - w - 5);
            if (y+h>root.height) y=Math.max(0,mouseY-h-3);
            c.cover(x,y,w,h,1.5f);
            c.paint.fill(x,y,w,h,1.5f,Theme.HEADER);
            c.paint.outline(x,y,w,h,1.5f,1/c.paint.scale(),Theme.BORDER);
            c.paint.pushClip(x,y,w,h);
            for(int i=0;i<tipLines.length;i++) {
                String line=tipLines[i];
                c.label(line,c.leftX(line,x+Theme.PAD),c.centeredY(line,y+9.5f+11*i),i==2 && event.shift ? Theme.DANGER : Theme.TEXT);
            }
            c.paint.popClip();
            c.flushText();
        }
        c.restoreFont(previous);
    }
    private void paintCallout(WidgetCanvas c) {
        Widget frame=note;
        while(frame.parent!=null && !(frame instanceof Window)) frame=frame.parent;
        float w=Math.min(root.width,c.width(noteText)+10), h=15;
        float left=frame.x-w-5, right=frame.x+frame.width+5;
        boolean onLeft=left>=0, onRight=right+w<=root.width;
        if(onLeft && onRight && windowAt(left+w/2,noteY) && !windowAt(right+w/2,noteY)) onLeft=false;
        float x=onLeft ? left : onRight ? right : Math.clamp(frame.x,0,Math.max(0,root.width-w));
        float y=Math.clamp(noteY-h/2-(onLeft || onRight ? 0 : h+4),0,Math.max(0,root.height-h));
        c.cover(x,y,w,h,1.5f);
        c.paint.fill(x,y,w,h,1.5f,Theme.Floating.TITLE_BAR);
        if(onLeft || onRight) for(int i=0;i<3;i++)
            c.paint.fill(onLeft?x+w+i:x-1-i,y+h/2-2.5f+i,1,5-i*2,0,Theme.Floating.TITLE_BAR);
        c.paint.pushClip(x,y,w,h);
        c.centeredLabel(noteText,x+w/2,y+h/2,Theme.TEXT);
        c.paint.popClip();
        c.flushText();
    }
    private boolean windowAt(float px,float py) {
        for(Widget w=root.hit(px,py);w!=null;w=w.parent) if(w instanceof Window)return true;
        return false;
    }

    private static Widget dispatch(Widget to, UiEvent e) {
        for (; to != null; to = to.parent) if (to.input(e)) return to;
        return null;
    }

    /** The platform fills the borrowed event before dispatch. */
    public boolean dispatch() {
        UiEvent e = event;
        if (e.kind == UiEvent.Kind.KEY || e.kind == UiEvent.Kind.CHARACTER) {
            if (e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.TAB) return cycleFocus(e.shift);
            if (e.kind == UiEvent.Kind.KEY && e.key == UiEvent.Key.ESCAPE && popup != null) {
                closePopup();
                return true;
            }
            return dispatch(focus, e) != null;
        }
        boolean moved = mouseX != e.x || mouseY != e.y;
        mouseX = e.x;
        mouseY = e.y;
        Widget hit = hit();
        hover(hit, e.time);
        if (e.kind == UiEvent.Kind.PRESS) {
            if (popup != null && !inside(hit, popup)) { closePopup(); return true; }
            Widget nextFocus = hit;
            while (nextFocus != null && !nextFocus.focusable()) nextFocus = nextFocus.parent;
            focus(nextFocus);
            if (hit != null && popup == null) {
                Widget top = hit;
                while (!(top instanceof Window) && top.parent != null && top.parent != root) top = top.parent;
                raise(top);
            }
            Widget handled = dispatch(hit, e);
            if (capture == null && handled != null && handled.tree == this) {
                capture = handled;
                captureButton = e.button;
            }
            return handled != null;
        }
        if (e.kind == UiEvent.Kind.RELEASE && capture != null && e.button == captureButton) {
            Widget owner = capture;
            capture = null;
            dispatch(owner, e);
            return true;
        }
        if (e.kind == UiEvent.Kind.MOVE && tipVisible && moved) repaint();
        return dispatch(e.kind == UiEvent.Kind.MOVE && capture != null ? capture : hit, e) != null;
    }
}
