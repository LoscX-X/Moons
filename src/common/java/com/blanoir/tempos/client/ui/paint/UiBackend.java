package com.blanoir.tempos.client.ui.paint;

/** Implemented by each Minecraft graphics API on its render thread. */
public interface UiBackend extends AutoCloseable {
    UiTexture createTexture(int width, int height);
    /** Read-only view of a game-managed texture; closing the UI never releases its owner. */
    default UiTexture borrowGameTexture(net.minecraft.resources.Identifier id) { return null; }
    void draw(UiBatch batch);
    /** Returns false without drawing when a backend needs a separate clear operation. */
    default boolean tryDrawCleared(UiBatch batch) { return false; }
    @Override void close();
}
