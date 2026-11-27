package com.blanoir.tempos.loader.asm;

import com.blanoir.tempos.loader.mapping.VersionMappings;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Path;
import java.util.jar.JarFile;

/** Verifies the actual layer forwarded by both supported Sodium world boundaries. */
public final class SodiumWorldHookVerification {
    private static final String OWNER = "net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer";
    private static final String HOOK = "optional.sodium.world-render";
    private static final String OLD = "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
            + "DDDLcom/mojang/blaze3d/textures/GpuSampler;)V";
    private static final String PEARL = "(Lcom/mojang/renderpearl/api/commands/RenderPass;"
            + "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;"
            + "DDDLcom/mojang/renderpearl/api/textures/GpuSampler;"
            + "Lnet/minecraft/client/renderer/oit/OitStage;)V";

    private SodiumWorldHookVerification() {}

    public static void verifyFixtures() throws Exception {
        verify(fixture(OLD), OLD, 1);
        verify(fixture(PEARL), PEARL, 2);
        System.out.println("TEMPOS_SODIUM_WORLD_ARGUMENTS_VERIFIED old=arg0/local1 renderpearl=arg1/local2 start+end idempotent=true");
    }

    public static void main(String[] arguments) throws Exception {
        verifyFixtures();
        try (var jar = new JarFile(Path.of(arguments[0]).toFile());
                var input = jar.getInputStream(jar.getJarEntry(OWNER + ".class"))) {
            verify(input.readAllBytes(), PEARL, 2);
        }
        System.out.println("TEMPOS_ACTUAL_SODIUM_WORLD_VERIFIED class=SodiumWorldRenderer descriptor=renderpearl layer=arg1/local2 bytecode=checked scope=no-GPU");
    }

    private static byte[] fixture(String descriptor) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "drawChunkLayer", descriptor, null, null);
        method.visitCode();method.visitInsn(Opcodes.RETURN);method.visitMaxs(0, 0);method.visitEnd();writer.visitEnd();
        return writer.toByteArray();
    }

    private static void verify(byte[] original, String descriptor, int layerLocal) throws Exception {
        var transformer = new TransformCoordinator(VersionMappings.create());
        byte[] transformed = transformer.transform(null, OWNER, original);
        if (transformed == null) throw new AssertionError("Unhooked Sodium world entrypoint: " + descriptor);
        var node = new ClassNode();
        new ClassReader(transformed).accept(new CheckClassAdapter(node, false), 0);
        MethodNode boundary = null;
        for (var method : node.methods) {
            if (method.instructions.size() != 0) new Analyzer<>(new BasicInterpreter()).analyze(OWNER, method);
            if (method.name.equals("drawChunkLayer") && method.desc.equals(descriptor)) boundary = method;
        }
        if (boundary == null) throw new AssertionError("Missing requested world entrypoint");
        int starts = 0, ends = 0, returns = 0;
        for (var instruction : boundary.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) returns++;
            if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("onVoidHook")) continue;
            var argument = call.getPrevious();var receiver = argument.getPrevious();var id = receiver.getPrevious();
            if (!(id instanceof LdcInsnNode constant) || !(constant.cst instanceof String name) || !name.startsWith(HOOK)) continue;
            if (!(argument instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != layerLocal)
                throw new AssertionError("Incorrect layer local for " + name + ": expected " + layerLocal);
            if (!(receiver instanceof VarInsnNode ownerLoad) || ownerLoad.getOpcode() != Opcodes.ALOAD || ownerLoad.var != 0)
                throw new AssertionError("Incorrect Sodium world receiver");
            if (name.equals(HOOK)) starts++;else if (name.equals(HOOK + ".end")) ends++;
        }
        if (starts != 1 || ends != returns || returns == 0)
            throw new AssertionError("Missing or duplicate boundary: start=" + starts + " end=" + ends + " returns=" + returns);
        if (transformer.transform(null, OWNER, transformed) != null)
            throw new AssertionError("Retransformation duplicated Sodium hooks");
    }
}
