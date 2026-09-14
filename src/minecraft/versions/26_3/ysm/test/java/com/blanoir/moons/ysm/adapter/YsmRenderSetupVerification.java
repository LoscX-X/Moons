package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.LocalYsmModel;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/** Executes real Minecraft render setup construction without opening a game window or GPU context. */
public final class YsmRenderSetupVerification {
    public static void main(String[] args) throws Exception {
        YsmVerticesVerification.verify();
        // Compilation cannot detect renamed private members used by reflection.
        Class.forName(YsmSubEntities.class.getName(), true, YsmSubEntities.class.getClassLoader());
        var factory = new YsmRenderTypes();
        var texture = Identifier.fromNamespaceAndPath("moons", "ysm/fixture");
        for (boolean glow : new boolean[] {false, true})
            for (boolean translucent : new boolean[] {false, true})
                for (boolean cull : new boolean[] {false, true}) {
                    var type =
                            factory.get(
                                    texture,
                                    new LocalYsmModel.Pass(glow, translucent, cull, new float[0]));
                    if (type.forceSolidModelPhase())
                        throw new AssertionError(
                                "YSM transparency must participate in the entity render phase");
                    if (type.hasBlending() != translucent)
                        throw new AssertionError(
                                "Material blending does not match its texture pass");
                    if (!glow && type.pipeline().isCull() != cull)
                        throw new AssertionError("Material backface culling");
                    if (glow && !translucent && type.sortOnUpload())
                        throw new AssertionError(
                                "Opaque emissive geometry must bypass transparent sorting");
                    if (type.format() != DefaultVertexFormat.ENTITY)
                        throw new AssertionError("Material vertex format");
                    if (translucent) {
                        var expected =
                                glow
                                        ? RenderPipelines.OIT_ENTITY_EMISSIVE
                                        : cull
                                                ? RenderPipelines.OIT_ENTITY_CULL
                                                : RenderPipelines.OIT_ENTITY;
                        if (oitPipelines(type) != expected)
                            throw new AssertionError(
                                    "Translucent entity material needs matching OIT pipelines");
                        if (!type.sortOnUpload())
                            throw new AssertionError("Transparent pass sorting");
                    }
                }
        var pass = new LocalYsmModel.Pass(false, true, true, new float[0]);
        var first = factory.get(texture, pass);
        if (factory.get(texture, pass) != first) throw new AssertionError("Material cache");
        var second = factory.get(Identifier.fromNamespaceAndPath("moons", "ysm/second"), pass);
        if (first == second) throw new AssertionError("Texture change must rebuild the material");
        if (YsmInput.scanCode(65) != 4
                || YsmInput.scanCode(256) != 41
                || YsmInput.scanCode(340) != 225
                || YsmInput.scanCode(343) != 227
                || YsmInput.scanCode(-1) != -1
                || YsmInput.scanCode(Integer.MAX_VALUE) != -1)
            throw new AssertionError("Authored GLFW key numbers must map to SDL scan codes");
        System.out.println(
                "YSM_RENDER_SETUP_VERIFIED initialization=executed materials=8 pipelines=renderpearl+oit cache=texture input=glfw-to-sdl");
    }

    private static Object oitPipelines(RenderType type) throws Exception {
        var setupField =
                java.util.Arrays.stream(RenderType.class.getDeclaredFields())
                        .filter(f -> f.getType() == RenderSetup.class)
                        .findFirst()
                        .orElseThrow();
        setupField.setAccessible(true);
        var oit = RenderSetup.class.getDeclaredField("oitPipelineSet");
        oit.setAccessible(true);
        return oit.get(setupField.get(type));
    }
}
