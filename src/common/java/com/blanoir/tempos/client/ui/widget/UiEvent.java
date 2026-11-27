package com.blanoir.tempos.client.ui.widget;

/** One event is borrowed from the dispatcher for the duration of input handling. */
public final class UiEvent {
    public static final int PRIMARY = 0, SECONDARY = 1, MIDDLE = 2;
    public enum Kind { MOVE, PRESS, RELEASE, SCROLL, KEY, CHARACTER }
    public enum Key { OTHER, LEFT, RIGHT, HOME, END, BACKSPACE, DELETE, ENTER, ESCAPE, TAB, SELECT_ALL, COPY, CUT, PASTE }
    public Kind kind;
    public Key key;
    public float x, y, wheel;
    public int button, codepoint, platformKey;
    public boolean shift, control, doubleClick;
    public long time;
}
