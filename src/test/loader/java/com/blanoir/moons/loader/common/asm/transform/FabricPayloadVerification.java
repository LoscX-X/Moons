package com.blanoir.moons.loader.common.asm.transform;

import org.objectweb.asm.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Loads production payloads against the installed Fabric namespace without starting a client. */
public final class FabricPayloadVerification {
    public static void main(String[] args) throws Exception {
        var lines = Files.readAllLines(Path.of(args[0]));
        var urls =
                lines.stream()
                        .map(
                                path -> {
                                    try {
                                        return Path.of(path).toUri().toURL();
                                    } catch (MalformedURLException error) {
                                        throw new IllegalArgumentException(error);
                                    }
                                })
                        .toArray(URL[]::new);
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            var access = loader.loadClass("com.blanoir.moons.client.access.GameAccess");
            var capability = loader.loadClass("com.blanoir.moons.client.access.GameCapability");
            for (var value : capability.getEnumConstants()) {
                var status = access.getMethod("capability", capability).invoke(null, value);
                if (!(boolean) status.getClass().getMethod("available").invoke(status))
                    throw new AssertionError(
                            "Fabric capability " + value,
                            (Throwable) status.getClass().getMethod("failure").invoke(status));
            }
            int classes = 0;
            var references = new LinkedHashSet<Reference>();
            for (int index = 1; index <= 2; index++) {
                try (var jar = new JarFile(lines.get(index))) {
                    for (var entry : Collections.list(jar.entries())) {
                        if (!entry.getName().endsWith(".class")
                                || entry.getName().equals("module-info.class")) continue;
                        var type =
                                Class.forName(
                                        entry.getName().replace('/', '.').replace(".class", ""),
                                        false,
                                        loader);
                        type.getDeclaredMethods();
                        type.getDeclaredFields();
                        type.getDeclaredConstructors();
                        try (var input = jar.getInputStream(entry)) {
                            new ClassReader(input.readAllBytes())
                                    .accept(
                                            new ClassVisitor(Opcodes.ASM9) {
                                                @Override
                                                public MethodVisitor visitMethod(
                                                        int access,
                                                        String name,
                                                        String desc,
                                                        String signature,
                                                        String[] exceptions) {
                                                    return new MethodVisitor(Opcodes.ASM9) {
                                                        void add(
                                                                String owner,
                                                                String member,
                                                                String descriptor,
                                                                boolean field) {
                                                            if (owner.startsWith("net/minecraft/")
                                                                    || owner.startsWith(
                                                                            "com/mojang/"))
                                                                references.add(
                                                                        new Reference(
                                                                                owner,
                                                                                member,
                                                                                descriptor,
                                                                                field));
                                                        }

                                                        @Override
                                                        public void visitMethodInsn(
                                                                int opcode,
                                                                String owner,
                                                                String name,
                                                                String desc,
                                                                boolean itf) {
                                                            add(owner, name, desc, false);
                                                        }

                                                        @Override
                                                        public void visitFieldInsn(
                                                                int opcode,
                                                                String owner,
                                                                String name,
                                                                String desc) {
                                                            add(owner, name, desc, true);
                                                        }

                                                        @Override
                                                        public void visitInvokeDynamicInsn(
                                                                String name,
                                                                String desc,
                                                                Handle bootstrap,
                                                                Object... args) {
                                                            for (var value : args)
                                                                if (value instanceof Handle handle)
                                                                    add(
                                                                            handle.getOwner(),
                                                                            handle.getName(),
                                                                            handle.getDesc(),
                                                                            handle.getTag()
                                                                                    <= Opcodes
                                                                                            .H_PUTSTATIC);
                                                        }
                                                    };
                                                }
                                            },
                                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        }
                        classes++;
                    }
                }
            }
            for (var reference : references) {
                var owner = Class.forName(reference.owner().replace('/', '.'), false, loader);
                if (!exists(owner, reference, new HashSet<>()))
                    throw new AssertionError("Unresolved production reference: " + reference);
            }
            for (var name :
                    List.of(
                            "com.blanoir.moons.ysm.adapter.YsmSubEntities",
                            "com.blanoir.moons.ysm.adapter.YsmRenderTypes",
                            "com.blanoir.moons.ysm.adapter.YsmOpaqueEmissive"))
                Class.forName(name, true, loader);
            // Loading an overriding Screen method alone does not prove it still overrides after
            // remapping.
            var screen =
                    loader.loadClass("com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen");
            var names = loader.loadClass("com.blanoir.moons.api.mapping.GameNames");
            var namedBase = "net/minecraft/client/gui/screens/Screen";
            var baseName =
                    (String) names.getMethod("runtimeType", String.class).invoke(null, namedBase);
            var base = loader.loadClass(baseName.replace('/', '.'));
            for (var method : base.getDeclaredMethods()) {
                var named =
                        (String)
                                names.getMethod(
                                                "member",
                                                String.class,
                                                String.class,
                                                String.class,
                                                boolean.class,
                                                boolean.class)
                                        .invoke(
                                                null,
                                                baseName,
                                                method.getName(),
                                                java.lang.invoke.MethodType.methodType(
                                                                method.getReturnType(),
                                                                method.getParameterTypes())
                                                        .descriptorString(),
                                                false,
                                                false);
                if (Set.of("render", "renderBackground", "keyPressed", "mouseClicked")
                        .contains(named))
                    screen.getDeclaredMethod(method.getName(), method.getParameterTypes());
            }
            System.out.println(
                    "MOONS_FABRIC_PAYLOAD_VERIFIED Java="
                            + Runtime.version().feature()
                            + " classes="
                            + classes
                            + " game-references="
                            + references.size()
                            + " private-members=resolved Screen-overrides=resolved YSM-reflection=resolved");
        }
    }

    private record Reference(String owner, String name, String desc, boolean field) {}

    private static boolean exists(Class<?> owner, Reference ref, Set<Class<?>> seen) {
        if (owner == null || !seen.add(owner)) return false;
        if (ref.field()) {
            for (var field : owner.getDeclaredFields())
                if (field.getName().equals(ref.name())
                        && Type.getDescriptor(field.getType()).equals(ref.desc())) return true;
        } else if (ref.name().equals("<init>")) {
            for (var constructor : owner.getDeclaredConstructors())
                if (Type.getConstructorDescriptor(constructor).equals(ref.desc())) return true;
            return false;
        } else {
            for (var method : owner.getDeclaredMethods())
                if (method.getName().equals(ref.name())
                        && Type.getMethodDescriptor(method).equals(ref.desc())) return true;
        }
        if (exists(owner.getSuperclass(), ref, seen)) return true;
        // JVM interface method resolution also considers public Object methods.
        if (owner.isInterface() && !ref.field() && exists(Object.class, ref, seen)) return true;
        for (var implemented : owner.getInterfaces())
            if (exists(implemented, ref, seen)) return true;
        return false;
    }
}
