package com.blanoir.moons.loader.common.asm.hooks;

import static com.blanoir.moons.loader.common.asm.hooks.HookInstructions.call;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Backend-independent presentation boundary for 26.2's GLFW renderer. */
public final class VersionTransformer {
    private VersionTransformer() {}

    public static boolean load(MethodNode method, String id) {
        if (!id.equals("render.present")) return false;
        for (AbstractInsnNode node : method.instructions.toArray()) {
            if (node instanceof MethodInsnNode invoke
                    && invoke.owner.equals("com/mojang/blaze3d/systems/GpuSurface")
                    && invoke.name.equals("blitFromTexture")
                    && invoke.desc.equals(
                            "(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V")) {
                // Replace only the display blit's view. The main target remains the
                // original screenshot source on both OpenGL and Vulkan.
                int source = method.maxLocals++;
                InsnList out = new InsnList();
                out.add(new VarInsnNode(Opcodes.ASTORE, source));
                out.add(new LdcInsnNode(id));
                out.add(new VarInsnNode(Opcodes.ALOAD, 0));
                out.add(new InsnNode(Opcodes.ACONST_NULL));
                out.add(new VarInsnNode(Opcodes.ALOAD, source));
                out.add(
                        call(
                                "onObjectValue",
                                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                out.add(
                        new TypeInsnNode(
                                Opcodes.CHECKCAST, "com/mojang/blaze3d/textures/GpuTextureView"));
                method.instructions.insertBefore(node, out);
                return true;
            }
        }
        return false;
    }
}
