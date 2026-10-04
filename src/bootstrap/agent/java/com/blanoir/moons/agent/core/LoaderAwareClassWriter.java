package com.blanoir.moons.agent.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Resolves frame merges from class resources without loading classes inside the transform callback. */
final class LoaderAwareClassWriter extends ClassWriter {
    private final ClassLoader loader;
    private final Map<String, Hierarchy> hierarchies = new HashMap<>();

    private record Hierarchy(String parent, String[] interfaces, boolean isInterface) {}

    LoaderAwareClassWriter(ClassReader reader, int flags, ClassLoader loader) {
        super(reader, flags);
        this.loader = loader;
        hierarchies.put(reader.getClassName(), hierarchy(reader));
    }

    @Override
    protected String getCommonSuperClass(String firstName, String secondName) {
        if (firstName.equals(secondName)) return firstName;
        if (loader == null) return "java/lang/Object";
        try {
            if (isAssignable(firstName, secondName)) return firstName;
            if (isAssignable(secondName, firstName)) return secondName;
            if (firstName.startsWith("[") && secondName.startsWith("[")) {
                String first = component(firstName);
                String second = component(secondName);
                if (primitive(first) || primitive(second)) return "java/lang/Object";
                String common = getCommonSuperClass(first, second);
                return "[" + (common.startsWith("[") ? common : "L" + common + ";");
            }
            if (firstName.startsWith("[")
                    || secondName.startsWith("[")
                    || hierarchy(firstName).isInterface()
                    || hierarchy(secondName).isInterface()) return "java/lang/Object";
            String parent = hierarchy(firstName).parent();
            while (parent != null) {
                if (isAssignable(parent, secondName)) return parent;
                parent = hierarchy(parent).parent();
            }
        } catch (IOException unavailable) {
            // Host-generated helpers may have no class resource. Never load them here:
            // a nested target load would be skipped by the native recursion guard.
        }
        return "java/lang/Object";
    }

    private boolean isAssignable(String target, String source) throws IOException {
        if (target.equals(source) || target.equals("java/lang/Object")) return true;
        if (source.startsWith("[")) {
            if (!target.startsWith("["))
                return target.equals("java/lang/Cloneable")
                        || target.equals("java/io/Serializable");
            String first = component(target);
            String second = component(source);
            if (primitive(first) || primitive(second)) return first.equals(second);
            return isAssignable(first, second);
        }
        if (target.startsWith("[")) return false;
        var pending = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        pending.add(source);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (name.equals(target)) return true;
            if (!visited.add(name)) continue;
            Hierarchy info = hierarchy(name);
            if (info.parent() != null) pending.addLast(info.parent());
            for (String implemented : info.interfaces()) pending.addLast(implemented);
        }
        return false;
    }

    private Hierarchy hierarchy(String name) throws IOException {
        Hierarchy cached = hierarchies.get(name);
        if (cached != null) return cached;
        try (var input = loader.getResourceAsStream(name + ".class")) {
            if (input == null) throw new IOException("Missing class resource: " + name);
            Hierarchy info = hierarchy(new ClassReader(input));
            hierarchies.put(name, info);
            return info;
        }
    }

    private static Hierarchy hierarchy(ClassReader reader) {
        return new Hierarchy(
                reader.getSuperName(),
                reader.getInterfaces(),
                (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
    }

    private static String component(String array) {
        String descriptor = array.substring(1);
        return descriptor.startsWith("L")
                ? descriptor.substring(1, descriptor.length() - 1)
                : descriptor;
    }

    private static boolean primitive(String name) {
        return name.length() == 1 && "ZBCSIJFD".indexOf(name.charAt(0)) >= 0;
    }
}
