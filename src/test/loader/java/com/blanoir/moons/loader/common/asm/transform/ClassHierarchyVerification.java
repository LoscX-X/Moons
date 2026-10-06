package com.blanoir.moons.loader.common.asm.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.InputStream;

/** Frame resolution must not recursively load target classes or collapse reference arrays. */
final class ClassHierarchyVerification {
    private interface Marker {}

    private interface ChildMarker extends Marker {}

    private static class Parent implements Marker {}

    private static class First extends Parent {}

    private static class Second extends Parent {}

    static void verify() throws Exception {
        ClassLoader resources = ClassHierarchyVerification.class.getClassLoader();
        ClassLoader resourceOnly =
                new ClassLoader(null) {
                    @Override
                    public InputStream getResourceAsStream(String name) {
                        return resources.getResourceAsStream(name);
                    }

                    @Override
                    protected Class<?> loadClass(String name, boolean resolve) {
                        throw new AssertionError("Frame computation loaded a class: " + name);
                    }
                };
        var writer =
                new LoaderAwareClassWriter(
                        new ClassReader(internal(First.class)),
                        ClassWriter.COMPUTE_FRAMES,
                        resourceOnly);
        expect(writer, internal(First.class), internal(Second.class), internal(Parent.class));
        expect(writer, internal(Parent.class), internal(First.class), internal(Parent.class));
        expect(writer, internal(Marker.class), internal(First.class), internal(Marker.class));
        expect(writer, internal(Marker.class), internal(ChildMarker.class), internal(Marker.class));
        expect(writer, internal(First.class), internal(ChildMarker.class), "java/lang/Object");
        expect(writer, "[Ljava/lang/String;", "[Ljava/lang/Integer;", "[Ljava/lang/Object;");
        expect(writer, "[[Ljava/lang/String;", "[[I", "[Ljava/lang/Object;");
        expect(writer, "[I", "[J", "java/lang/Object");
        expect(writer, "[I", "java/lang/Cloneable", "java/lang/Cloneable");
        expect(writer, "[[I", "java/io/Serializable", "java/io/Serializable");
        expect(writer, "[Ljava/lang/Object;", "[[I", "[Ljava/lang/Object;");
        expect(writer, "missing/generated/First", "missing/generated/Second", "java/lang/Object");
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static void expect(
            LoaderAwareClassWriter writer, String first, String second, String expected) {
        String actual = writer.getCommonSuperClass(first, second);
        if (!actual.equals(expected))
            throw new AssertionError(
                    "Frame merge "
                            + first
                            + " + "
                            + second
                            + ": expected="
                            + expected
                            + ", actual="
                            + actual);
        String reverse = writer.getCommonSuperClass(second, first);
        if (!reverse.equals(expected))
            throw new AssertionError("Asymmetric frame merge: " + reverse);
    }
}
