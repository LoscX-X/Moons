package com.blanoir.moons.nativebridge;

/** Proves that native JVMTI bytes cross JNI, are changed by Java ASM, and return to the JVM. */
public final class NativeAgentVerification {
    private NativeAgentVerification() { }

    public static void main(String[] arguments) {
        String actual = new NativeAgentVerificationTarget().message();
        String expected = "TRANSFORMED_BY_JVMTI_ASM";
        if (!expected.equals(actual)) {
            throw new AssertionError("Native transformation was not applied: " + actual);
        }
        System.out.println("MOONS_NATIVE_JVMTI_VERIFIED result=" + actual);
    }
}
