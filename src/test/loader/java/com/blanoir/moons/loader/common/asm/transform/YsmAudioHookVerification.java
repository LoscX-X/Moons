package com.blanoir.moons.loader.common.asm.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

/** 1.8 uses the game's existing OpenAL device, so verify that actual adapter instead of a 26.x stream hook. */
public final class YsmAudioHookVerification {
    public static void main(String[] args) throws Exception {
        verify();
    }

    static void verify() throws Exception {
        String base = "com/blanoir/moons/ysm/adapter/";
        ClassNode audio = read(base + "YsmGameAudio"),
                playback = read(base + "YsmGameAudio$Playback");
        ClassNode decoder = read(base + "YsmAudioDecoder");
        Set<String> calls = calls(playback), decode = calls(decoder);
        for (String required :
                List.of(
                        "org/lwjgl/openal/AL.isCreated",
                        "org/lwjgl/openal/AL.getContext",
                        "org/lwjgl/openal/AL10.alGenBuffers",
                        "org/lwjgl/openal/AL10.alBufferData",
                        "org/lwjgl/openal/AL10.alGenSources",
                        "org/lwjgl/openal/AL10.alSourcePlay",
                        "org/lwjgl/openal/AL10.alSourcePause",
                        "org/lwjgl/openal/AL10.alSourceStop",
                        "org/lwjgl/openal/AL10.alDeleteSources",
                        "org/lwjgl/openal/AL10.alDeleteBuffers",
                        "net/minecraft/client/Minecraft.addScheduledTask",
                        "net/minecraft/client/Minecraft.isGamePaused",
                        "net/minecraft/client/settings/GameSettings.getSoundLevel"))
            if (!calls.contains(required))
                throw new AssertionError("Missing legacy audio lifecycle operation " + required);
        if (calls.contains("org/lwjgl/openal/AL.create")
                || calls.contains("org/lwjgl/openal/AL.destroy"))
            throw new AssertionError("Adapter must not replace vanilla's device");
        if (!calls(audio).contains("net/minecraft/client/Minecraft.addScheduledTask"))
            throw new AssertionError("Playback initialization must use the game thread");
        for (String required :
                List.of(
                        "paulscode/sound/codecs/CodecJOrbis.initialize",
                        "paulscode/sound/codecs/CodecJOrbis.read",
                        "paulscode/sound/codecs/CodecJOrbis.cleanup",
                        "com/blanoir/moons/ysm/codecs/OpusPcmDecoder.decode"))
            if (!decode.contains(required))
                throw new AssertionError("Missing native decoder lifecycle " + required);
        Class<?> pcm = Class.forName("com.blanoir.moons.ysm.YsmSoundBank$Pcm");
        var pcmConstructor = pcm.getConstructor(byte[].class, int.class, int.class);
        Class<?> output = Class.forName("com.blanoir.moons.ysm.adapter.YsmGameAudio$Playback");
        var constructor =
                output.getDeclaredConstructor(
                        pcm, boolean.class, float.class, float.class, Supplier.class);
        constructor.setAccessible(true);
        Object sample = pcmConstructor.newInstance(new byte[4], 48000, 1);
        Object handle =
                constructor.newInstance(sample, true, 32f, 10f, (Supplier<Object>) () -> null);
        equal(16f, field(output, handle, "volume"), "Bounded gain");
        equal(4f, field(output, handle, "pitch"), "Bounded pitch");
        equal(true, field(output, handle, "loop"), "Loop flag");
        equal(sample, field(output, handle, "pcm"), "PCM ownership");
        for (Object bad :
                List.of(
                        pcmConstructor.newInstance(new byte[4], 48000, 2),
                        pcmConstructor.newInstance(new byte[0], 48000, 1),
                        pcmConstructor.newInstance(new byte[3], 48000, 1),
                        pcmConstructor.newInstance(new byte[4], 0, 1))) {
            try {
                constructor.newInstance(bad, false, 1f, 1f, (Supplier<Object>) () -> null);
                throw new AssertionError("Invalid PCM accepted");
            } catch (InvocationTargetException error) {
                if (!(error.getCause() instanceof IllegalArgumentException)) throw error;
            }
        }
        System.out.println(
                "YSM_LEGACY_AUDIO_VERIFIED actual-adapter=existing-device+game-thread+pause+release codecs=vorbis+opus PCM=mono16+validation+gain+pitch");
    }

    private static Object field(Class<?> type, Object instance, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(label);
    }

    private static ClassNode read(String name) throws Exception {
        try (var in =
                YsmAudioHookVerification.class
                        .getClassLoader()
                        .getResourceAsStream(name + ".class")) {
            if (in == null) throw new AssertionError("Missing actual adapter " + name);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static Set<String> calls(ClassNode node) {
        Set<String> result = new HashSet<>();
        for (MethodNode method : node.methods)
            for (AbstractInsnNode ins : method.instructions)
                if (ins instanceof MethodInsnNode call) result.add(call.owner + "." + call.name);
        return result;
    }
}
