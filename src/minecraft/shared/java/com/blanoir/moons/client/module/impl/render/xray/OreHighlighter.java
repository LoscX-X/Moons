package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.render.world.OverlayFrustum;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Ore selection is shared; GPU pipelines and buffers belong to WorldOverlayRenderer. */
public final class OreHighlighter {
    private static final float BOX_ALPHA = .35f;
    private static final double MAX_RENDER_DISTANCE_SQ = 196.0 * 196.0;
    private static final int MAX_RENDER_BLOCKS = 12_900;
    private static final OverlayFrustum VIEW = new OverlayFrustum();
    private static final List<WorldOverlayRenderer.ColoredBox> VISIBLE = new ArrayList<>();
    private static final BooleanSetting DISPLAY_ENABLED =
            new BooleanSetting.Builder().name("xray.display.enabled").defaultValue(true).build();

    private OreHighlighter() {}

    public static void init() {
        EventBus.WORLD_RENDER.register("OreHighlighter.worldRender", OreHighlighter::render);
    }

    public static void setColor(int red, int green, int blue) {
        XrayBlockTarget.DIAMOND.setColor(red, green, blue);
    }

    public static String getRgbString() {
        return XrayBlockTarget.DIAMOND.rgbString();
    }

    public static void setDisplayEnabled(Minecraft client, boolean enabled) {
        DISPLAY_ENABLED.set(enabled);
        ClientChat.send(
                client, "Ore display " + displayStatusText() + ". Cached=" + OreCache.size() + ".");
    }

    public static boolean isDisplayEnabled() {
        return DISPLAY_ENABLED.get();
    }

    public static String displayStatusText() {
        return DISPLAY_ENABLED.get() ? "enabled" : "disabled";
    }

    public static void close() {
        VISIBLE.clear();
    }

    private static void render(WorldRenderEvent context) {
        Minecraft client = Minecraft.getInstance();
        VISIBLE.clear();
        if (!DISPLAY_ENABLED.get()
                || !OreScanner.isClientWorldReady(client)
                || OreCache.size() == 0) return;
        var renderCamera = MinecraftClientAccess.camera(client);
        Vec3 eye = renderCamera.position();
        VIEW.update(renderCamera);
        for (OreCache.CachedXrayBlock entry : OreCache.snapshotEntries()) {
            BlockPos pos = entry.pos();
            double x = pos.getX(), y = pos.getY(), z = pos.getZ();
            double dx = x + .5 - eye.x, dy = y + .5 - eye.y, dz = z + .5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ
                    || !VIEW.isVisible(x, y, z, x + 1, y + 1, z + 1)) continue;
            XrayTarget target = entry.target();
            VISIBLE.add(
                    new WorldOverlayRenderer.ColoredBox(
                            (float) x,
                            (float) y,
                            (float) z,
                            (float) (x + 1),
                            (float) (y + 1),
                            (float) (z + 1),
                            target.red() / 255f,
                            target.green() / 255f,
                            target.blue() / 255f,
                            BOX_ALPHA));
            // Cull first: off-screen ores must not consume the on-screen render budget.
            if (VISIBLE.size() >= MAX_RENDER_BLOCKS) break;
        }
        var matrices = context.poseStack();
        matrices.pushPose();
        try {
            matrices.translate(-eye.x, -eye.y, -eye.z);
            WorldOverlayRenderer.renderStyled(client, matrices, VISIBLE, "ore highlights");
        } finally {
            matrices.popPose();
            VISIBLE.clear();
        }
    }
}
