package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.LocalYsmModel;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.rendertype.OutputTarget;
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
                    if (type.outputTarget() != OutputTarget.MAIN_TARGET)
                        throw new AssertionError("Body material must target the world framebuffer");
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
                    if (!glow && translucent && cull) {
                        var expected =
                                Identifier.fromNamespaceAndPath(
                                        "moons", "pipeline/ysm_translucent_cull");
                        if (!type.pipeline().getLocation().equals(expected))
                            throw new AssertionError("YSM pipeline must retain its own namespace");
                        if (!type.sortOnUpload())
                            throw new AssertionError("Transparent pass sorting");
                    }
                }
        var pass = new LocalYsmModel.Pass(false, true, true, new float[0]);
        var first = factory.get(texture, pass);
        if (factory.get(texture, pass) != first) throw new AssertionError("Material cache");
        var second = factory.get(Identifier.fromNamespaceAndPath("moons", "ysm/second"), pass);
        if (first == second) throw new AssertionError("Texture change must rebuild the material");
        System.out.println(
                "YSM_RENDER_SETUP_VERIFIED initialization=executed materials=8 namespace=moons target=world cache=texture");
    }
}
