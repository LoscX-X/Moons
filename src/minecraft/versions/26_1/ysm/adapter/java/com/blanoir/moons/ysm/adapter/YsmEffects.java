package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.*;
import com.blanoir.moons.ysm.internal.runtime.LocalRuntime;
import com.mojang.brigadier.StringReader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Minecraft-facing effects owned by one local model; no packets or global registrations. */
final class YsmEffects implements LocalRuntime.Effects, AutoCloseable {
    private final LocalRuntime runtime;
    private final java.util.function.Supplier<net.minecraft.world.entity.Entity> source;
    private final YsmSoundBank sounds;
    private final Set<YsmSoundHandle> vanillaSounds = new HashSet<>();
    private final Map<String, ParticleOptions> particles = new LinkedHashMap<>();
    private Object level;
    private boolean closed;

    YsmEffects(LocalYsmModel model) {
        this(model, () -> Minecraft.getInstance().player);
    }

    YsmEffects(
            LocalYsmModel model,
            java.util.function.Supplier<net.minecraft.world.entity.Entity> source) {
        this.source = source;
        runtime = model.runtime();
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        model.sounds().forEach((name, file) -> encoded.put(name, file.data));
        sounds =
                new YsmSoundBank(
                        encoded,
                        YsmAudioDecoder::decode,
                        (pcm, loop, volume, pitch) ->
                                YsmGameAudio.play(pcm, loop, volume, pitch, source),
                        runtime::diagnostic);
    }

    public YsmSoundHandle sound(String name, boolean loop, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (closed || mc.player == null || !Float.isFinite(volume) || !Float.isFinite(pitch))
            return null;
        if (!name.contains(":")) return sounds.play(name, loop, volume, pitch);
        Identifier id = Identifier.tryParse(name);
        if (id == null) {
            runtime.diagnostic("Invalid sound: " + name);
            return null;
        }
        var sound =
                new SimpleSoundInstance(
                        id,
                        SoundSource.PLAYERS,
                        Math.clamp(volume, 0, 16),
                        Math.clamp(pitch, .05f, 4f),
                        RandomSource.create(),
                        loop,
                        0,
                        SoundInstance.Attenuation.NONE,
                        0,
                        0,
                        0,
                        true);
        var manager = mc.getSoundManager();
        manager.play(sound);
        YsmSoundHandle handle =
                new YsmSoundHandle() {
                    private boolean stopped;

                    public boolean stopped() {
                        return stopped || !manager.isActive(sound);
                    }

                    public void close() {
                        if (!stopped) {
                            stopped = true;
                            manager.stop(sound);
                        }
                    }
                };
        vanillaSounds.removeIf(YsmSoundHandle::stopped);
        vanillaSounds.add(handle);
        return handle;
    }

    public void particle(String name, boolean absolute, double[] args) {
        Minecraft mc = Minecraft.getInstance();
        var player = source.get();
        if (closed || player == null || mc.level == null || name.isBlank()) return;
        if (level != mc.level) {
            level = mc.level;
            particles.clear();
        }
        try {
            ParticleOptions options = particles.get(name);
            if (options == null) {
                options =
                        ParticleArgument.readParticle(
                                new StringReader(name), mc.level.registryAccess());
                if (particles.size() >= 256) particles.remove(particles.keySet().iterator().next());
                particles.put(name, options);
            }
            for (double value : args) if (!Double.isFinite(value)) return;
            Vec3 offset = new Vec3(arg(args, 0, 0), arg(args, 1, 0), arg(args, 2, 0));
            double dx = arg(args, 3, 0),
                    dy = arg(args, 4, 0),
                    dz = arg(args, 5, 0),
                    speed = arg(args, 6, 0);
            int count = (int) Math.clamp(arg(args, 7, 0), 0, 4096);
            int lifetime = (int) Math.clamp(arg(args, 8, 20), 1, 12000);
            var random = player.getRandom();
            for (int i = 0; i < Math.max(1, count); i++) {
                Vec3 position =
                        count == 0
                                ? offset
                                : offset.add(
                                        random.nextGaussian() * dx,
                                        random.nextGaussian() * dy,
                                        random.nextGaussian() * dz);
                if (!absolute)
                    position =
                            position.yRot(
                                    -(count == 0
                                                            && player
                                                                    instanceof
                                                                    net.minecraft.world.entity
                                                                                    .LivingEntity
                                                                            living
                                                    ? living.yBodyRot
                                                    : player.getYRot())
                                            * (float) Math.PI
                                            / 180);
                position = position.add(player.position());
                double vx = count == 0 ? speed * dx : random.nextGaussian() * speed;
                double vy = count == 0 ? speed * dy : random.nextGaussian() * speed;
                double vz = count == 0 ? speed * dz : random.nextGaussian() * speed;
                var particle =
                        mc.particleEngine.createParticle(
                                options, position.x, position.y, position.z, vx, vy, vz);
                if (particle != null) particle.setLifetime(lifetime);
            }
        } catch (Exception error) {
            runtime.diagnostic("Particle " + name + ": " + error.getMessage());
        }
    }

    private static double arg(double[] args, int index, double fallback) {
        return index < args.length ? args[index] : fallback;
    }

    void stopAll() {
        sounds.stopAll();
        vanillaSounds.forEach(YsmSoundHandle::close);
        vanillaSounds.clear();
    }

    public void close() {
        if (closed) return;
        closed = true;
        stopAll();
        sounds.close();
        particles.clear();
    }
}
