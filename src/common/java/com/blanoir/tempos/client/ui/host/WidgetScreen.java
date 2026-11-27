package com.blanoir.tempos.client.ui.host;

import com.blanoir.tempos.client.api.ClientUiScreen;
import com.blanoir.tempos.client.ui.paint.UiBackend;
import com.blanoir.tempos.client.ui.paint.UiBatch;
import com.blanoir.tempos.client.ui.paint.UiFont;
import com.blanoir.tempos.client.ui.paint.UiPainter;
import com.blanoir.tempos.client.ui.theme.Theme;
import com.blanoir.tempos.client.ui.widget.UiEvent;
import com.blanoir.tempos.client.ui.widget.WidgetCanvas;
import com.blanoir.tempos.client.ui.widget.WidgetTree;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.util.Arrays;

/** An ordinary Minecraft Screen. Unchanged windows reuse their GPU texture on the next GUI frame. */
public abstract class WidgetScreen extends Screen implements WidgetCanvas.NativeText, ClientUiScreen {
    protected static final Identifier LAYER = Identifier.fromNamespaceAndPath("tempos", "gui/widget_layer");
    protected final WidgetTree tree;
    protected final Theme theme;
    private final UiPainter paint = new UiPainter();
    protected RenderTarget layer;
    private UiBackend backend;
    private UiFont face;
    private WidgetCanvas canvas;
    private Label[] nativeLabels = new Label[16];
    private int nativeCount, hostScale;
    private float scale;
    private long paintedRevision = -1;
    private long paintedTheme;
    private UiBatch retained;
    private boolean textFocus, registered;
    private int ranges, vertices;
    private long generateNanos, submitNanos;
    private static final boolean SHOW_STATS = Boolean.getBoolean("tempos.ui.metrics");

    private static final class Label {
        String text;
        ItemStack item;
        int color, left, top, right, bottom;
        float x, y, fontScale;
        boolean accent;
    }

    protected WidgetScreen(String title, WidgetTree tree, Theme theme) {
        super(Component.literal(title));
        this.tree = tree;
        this.theme = theme;
    }
    protected abstract RenderTarget createTarget(int width, int height);
    protected abstract void registerLayer(RenderTarget target);
    protected abstract void clearTarget(RenderTarget target);
    protected abstract UiBackend createBackend();
    protected abstract void textFocus(boolean focused);
    protected abstract boolean shiftDown();
    protected boolean unhandled(UiEvent event) { return false; }
    protected boolean acceptsTypeAhead() { return false; }
    protected void beforeFrame() {}
    protected float fontSize() { return Theme.FONT_SIZE; }
    protected float contentScale() { return 1; }
    protected final int contentWidth() { return (int)(width / contentScale()); }
    protected final int contentHeight() { return (int)(height / contentScale()); }
    protected float opacity(long now) { return 1; }
    public static WidgetTree.Clipboard clipboard(Minecraft client) {
        return new WidgetTree.Clipboard() {
            public String get() { return client.keyboardHandler.getClipboard(); }
            public void set(String text) { client.keyboardHandler.setClipboard(text); }
        };
    }
    public final int geometryDraws() { return ranges; }
    public final int vertexCount() { return vertices; }
    public final int atlasPages() { return face == null ? 0 : face.pageCount(); }
    public final long generationNanos() { return generateNanos; }
    public final long submissionNanos() { return submitNanos; }

    private void ensureLayer(int w, int h, int s) {
        float nextScale = s * contentScale();
        if (layer != null && layer.width == w && layer.height == h && hostScale == s && scale == nextScale) return;
        releaseLayer();
        hostScale = s;
        scale = nextScale;
        try {
            layer = createTarget(w, h);
            registerLayer(layer);
            registered = true;
            backend = createBackend();
            face = new UiFont(backend, fontSize(), scale);
            canvas = new WidgetCanvas(paint, theme, face, this);
        } catch (RuntimeException | Error failure) {
            releaseLayer();
            throw failure;
        }
        tree.relayout();
    }
    private void releaseLayer() {
        if (face != null) face.close();
        if (backend != null) backend.close();
        if (layer != null) {
            if (registered) Minecraft.getInstance().getTextureManager().release(LAYER);
            else layer.destroyBuffers();
        }
        registered = false;
        face = null;
        backend = null;
        layer = null;
        canvas = null;
        clearNativeContent();
        paintedRevision = -1;
        retained = null;
    }
    @Override public int width(String text) { return Minecraft.getInstance().font.width(text); }
    @Override public int lineHeight() { return Minecraft.getInstance().font.lineHeight; }
    private Label appendLabel() {
        if (nativeCount == nativeLabels.length) nativeLabels = Arrays.copyOf(nativeLabels, nativeCount * 2);
        Label label = nativeLabels[nativeCount];
        if (label == null) nativeLabels[nativeCount] = label = new Label();
        nativeCount++;
        return label;
    }
    @Override public void label(String text, float x, float y, int color, boolean accent, int l, int t, int r, int b, int textWidth) {
        scaledLabel(text,x,y,color,accent,l,t,r,b,textWidth,1);
    }
    @Override public void scaledLabel(String text, float x, float y, int color, boolean accent, int l, int t, int r, int b, int textWidth, float fontScale) {
        Label label = appendLabel();
        label.text = text;
        label.item = null;
        label.x = x;
        label.y = y;
        label.fontScale = fontScale;
        label.color = color;
        label.accent = accent;
        label.left = Math.max((int)Math.floor(x) - 2, (int)Math.ceil(l / scale));
        label.top = Math.max((int)Math.floor(y) - 2, (int)Math.ceil(t / scale));
        label.right = Math.min((int)Math.ceil(x + textWidth * fontScale) + 2, (int)(r / scale));
        label.bottom = Math.min((int)Math.ceil(y + lineHeight() * fontScale) + 2, (int)(b / scale));
    }
    @Override public void item(ItemStack stack, float x, float y, float size, int l, int t, int r, int b) {
        Label entry = appendLabel();
        entry.text = null;
        entry.item = stack;
        entry.x = x;
        entry.y = y;
        entry.fontScale = size / 16;
        entry.left = Math.max((int)Math.floor(x),(int)Math.ceil(l/scale));
        entry.top = Math.max((int)Math.floor(y),(int)Math.ceil(t/scale));
        entry.right = Math.min((int)Math.ceil(x+size),(int)(r/scale));
        entry.bottom = Math.min((int)Math.ceil(y+size),(int)(b/scale));
    }
    @Override public void cover(int left, int top, int right, int bottom) {
        if (left >= right || top >= bottom) return;
        int count = nativeCount;
        for (int i = 0; i < count; i++) {
            Label label = nativeLabels[i];
            if (label.text == null && label.item == null) continue;
            int l = Math.max(left, label.left), t = Math.max(top, label.top);
            int r = Math.min(right, label.right), b = Math.min(bottom, label.bottom);
            if (l >= r || t >= b) continue;
            fragment(label, label.left, label.top, label.right, t);
            fragment(label, label.left, b, label.right, label.bottom);
            fragment(label, label.left, t, l, b);
            fragment(label, r, t, label.right, b);
            label.text = null;
            label.item = null;
        }
    }
    private void fragment(Label source, int l, int t, int r, int b) {
        if (r <= l || b <= t) return;
        Label part = appendLabel();
        part.text = source.text;
        part.item = source.item;
        part.x = source.x; part.y = source.y; part.color = source.color; part.fontScale = source.fontScale;
        part.accent = source.accent;
        part.left = l; part.top = t; part.right = r; part.bottom = b;
    }
    public void extractBackground(GuiGraphicsExtractor g, int x, int y, float delta) {}
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        var window = Minecraft.getInstance().getWindow();
        int w = window.getWidth(), h = window.getHeight();
        if (w < 1 || h < 1) return;
        ensureLayer(w, h, window.getGuiScale());
        beforeFrame();
        long now = System.nanoTime();
        theme.frame(now);
        tree.update(contentWidth(), contentHeight(), now);
        syncFocus();
        ranges = 0;
        generateNanos = submitNanos = 0;
        boolean regenerate = paintedRevision != tree.revision() || paintedTheme != theme.revision();
        if (regenerate) {
            long start = System.nanoTime();
            retained = recordWidgets(w, h);
            generateNanos = System.nanoTime() - start;
            paintedRevision = tree.revision();
            paintedTheme = theme.revision();
            vertices = retained.vertexCount();
        }
        if (regenerate || retained.accent() != null) {
            long start = System.nanoTime();
            if (!backend.tryDrawCleared(retained)) {
                clearTarget(layer);
                backend.draw(retained);
            }
            submitNanos = System.nanoTime() - start;
            ranges = retained.rangeCount();
        }
        // GUI coordinates are logical pixels; the render attachment uses physical texels.
        int fade = Math.round(255 * opacity(now));
        float contentScale = contentScale();
        int shift = Math.round((255 - fade) * 4 / 255f * contentScale);
        // Both RGB and alpha must fade together on the premultiplied presentation quad.
        LayerPresentation.blit(g, LAYER, retained, width, height, shift, fade * 0x01010101);
        for (int i = 0; i < nativeCount; i++) {
            Label label = nativeLabels[i];
            if (label.text == null && label.item == null || label.left >= label.right || label.top >= label.bottom) continue;
            g.enableScissor((int)Math.floor(label.left*contentScale),(int)Math.floor(label.top*contentScale)+shift,
                    (int)Math.ceil(label.right*contentScale),(int)Math.ceil(label.bottom*contentScale)+shift);
            int color = label.accent ? theme.clock().unpackArgb(label.color) : label.color;
            if (fade < 255) color = ((color >>> 24) * fade / 255) << 24 | color & 0xffffff;
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(label.x*contentScale,label.y*contentScale+shift);
            pose.scale(label.fontScale*contentScale,label.fontScale*contentScale);
            if (label.item != null) g.item(label.item,0,0);
            else g.text(Minecraft.getInstance().font, label.text, 0, 0, color, false);
            pose.popMatrix();
            g.disableScissor();
        }
        if (SHOW_STATS) {
            String metrics = "UI  " + ranges + " draws + surface  /  " + vertices + " vertices  /  "
                    + atlasPages() + " atlas  /  " + nativeCount + " native labels  /  "
                    + generateNanos / 1000 + " + " + submitNanos / 1000 + " µs";
            g.text(Minecraft.getInstance().font, metrics, 4, height - 12, Theme.TEXT, true);
        }
    }
    private void clearNativeContent() {
        for (int i = 0; i < nativeCount; i++) {
            nativeLabels[i].text = null;
            nativeLabels[i].item = null;
        }
        nativeCount = 0;
    }
    protected UiBatch recordWidgets(int w, int h) {
        clearNativeContent();
        paint.begin(w, h, scale, theme);
        tree.paint(canvas);
        return paint.end();
    }
    private void syncFocus() {
        boolean next = tree.wantsText() || acceptsTypeAhead();
        if (textFocus == next) return;
        textFocus = next;
        textFocus(textFocus);
    }
    private boolean dispatch() {
        boolean handled = tree.dispatch() || unhandled(tree.event);
        syncFocus();
        return handled;
    }
    private UiEvent start(UiEvent.Kind kind) {
        UiEvent event = tree.event;
        event.kind = kind;
        event.time = System.nanoTime();
        event.shift = event.control = event.doubleClick = false;
        event.wheel = 0;
        return event;
    }
    private boolean pointer(UiEvent.Kind kind, MouseButtonEvent b, boolean twice) {
        UiEvent e = start(kind);
        e.x = (float)b.x()/contentScale(); e.y = (float)b.y()/contentScale();
        int button = b.button();
        if (button == InputConstants.MOUSE_BUTTON_LEFT) e.button = UiEvent.PRIMARY;
        else if (button == InputConstants.MOUSE_BUTTON_RIGHT) e.button = UiEvent.SECONDARY;
        else if (button == InputConstants.MOUSE_BUTTON_MIDDLE) e.button = UiEvent.MIDDLE;
        else e.button = -1;
        e.shift = b.hasShiftDown(); e.control = b.hasControlDown(); e.doubleClick = twice;
        return dispatch();
    }
    @Override public boolean mouseClicked(MouseButtonEvent b, boolean twice) { return pointer(UiEvent.Kind.PRESS, b, twice); }
    @Override public boolean mouseReleased(MouseButtonEvent b) { return pointer(UiEvent.Kind.RELEASE, b, false); }
    @Override public boolean mouseDragged(MouseButtonEvent b, double dx, double dy) { return pointer(UiEvent.Kind.MOVE, b, false); }
    @Override public void mouseMoved(double x, double y) {
        UiEvent e = start(UiEvent.Kind.MOVE);
        e.x = (float)x/contentScale(); e.y = (float)y/contentScale();
        dispatch();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        UiEvent e = start(UiEvent.Kind.SCROLL);
        e.x = (float)x/contentScale(); e.y = (float)y/contentScale(); e.wheel = (float)vertical; e.shift = shiftDown();
        return dispatch();
    }
    @Override public boolean keyPressed(KeyEvent k) {
        UiEvent e = start(UiEvent.Kind.KEY);
        e.platformKey = k.key(); e.shift = k.hasShiftDown(); e.control = k.hasControlDown();
        if (k.isSelectAll()) e.key = UiEvent.Key.SELECT_ALL;
        else if (k.isCopy()) e.key = UiEvent.Key.COPY;
        else if (k.isCut()) e.key = UiEvent.Key.CUT;
        else if (k.isPaste()) e.key = UiEvent.Key.PASTE;
        else e.key = key(k.key());
        if (dispatch()) return true;
        if (e.key == UiEvent.Key.ESCAPE) { onClose(); return true; }
        return false;
    }
    private static UiEvent.Key key(int k) {
        if (k == InputConstants.KEY_LEFT) return UiEvent.Key.LEFT;
        if (k == InputConstants.KEY_RIGHT) return UiEvent.Key.RIGHT;
        if (k == InputConstants.KEY_HOME) return UiEvent.Key.HOME;
        if (k == InputConstants.KEY_END) return UiEvent.Key.END;
        if (k == InputConstants.KEY_BACKSPACE) return UiEvent.Key.BACKSPACE;
        if (k == InputConstants.KEY_DELETE) return UiEvent.Key.DELETE;
        if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) return UiEvent.Key.ENTER;
        if (k == InputConstants.KEY_ESCAPE) return UiEvent.Key.ESCAPE;
        if (k == InputConstants.KEY_TAB) return UiEvent.Key.TAB;
        return UiEvent.Key.OTHER;
    }
    @Override public boolean charTyped(CharacterEvent c) {
        UiEvent e = start(UiEvent.Kind.CHARACTER);
        e.codepoint = c.codepoint();
        return dispatch();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void removed() {
        tree.cancelInput();
        if (textFocus) {
            textFocus = false;
            textFocus(false);
        }
        releaseLayer();
        super.removed();
    }
}
