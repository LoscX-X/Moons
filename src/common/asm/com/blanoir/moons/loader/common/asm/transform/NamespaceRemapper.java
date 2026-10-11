package com.blanoir.moons.loader.common.asm.transform;

import com.blanoir.moons.api.mapping.GameNames;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.io.*;

/** Translates only bytecode names; no game class is loaded during a transform callback. */
public final class NamespaceRemapper extends Remapper {
    private final boolean toRuntime;

    public NamespaceRemapper(boolean toRuntime) {
        super(Opcodes.ASM9);
        this.toRuntime = toRuntime;
    }

    @Override
    public String map(String name) {
        return toRuntime ? GameNames.runtimeType(name) : GameNames.namedType(name);
    }

    @Override
    public String mapFieldName(String owner, String name, String desc) {
        return GameNames.member(owner, name, desc, true, toRuntime);
    }

    @Override
    public String mapMethodName(String owner, String name, String desc) {
        return GameNames.member(owner, name, desc, false, toRuntime);
    }

    public static byte[] translate(byte[] bytes, boolean toRuntime) {
        var writer = new ClassWriter(0);
        new ClassReader(bytes)
                .accept(new ClassRemapper(writer, new NamespaceRemapper(toRuntime)), 0);
        return writer.toByteArray();
    }

    static ClassLoader namedResources(ClassLoader gameLoader) {
        if (gameLoader == null) return null;
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (!name.endsWith(".class")) return gameLoader.getResourceAsStream(name);
                var owner = name.substring(0, name.length() - 6);
                var runtime = GameNames.runtimeType(owner);
                var input = gameLoader.getResourceAsStream(runtime + ".class");
                if (input == null) return null;
                try (input) {
                    return new ByteArrayInputStream(translate(input.readAllBytes(), false));
                } catch (IOException error) {
                    return null;
                }
            }
        };
    }
}
