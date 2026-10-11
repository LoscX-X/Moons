package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.YsmSoundBank;
import com.blanoir.moons.ysm.YsmSoundHandle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.*;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.*;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.entity.Entity;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import javax.sound.sampled.AudioFormat;

/** A local PCM stream using vanilla channels, device selection, spatialization and pause behavior. */
final class YsmGameAudio {
    private static final ConcurrentMap<Identifier, Playback> STREAMS = new ConcurrentHashMap<>();

    static Object stream(Object[] args) {
        if (args.length != 2 || !(args[0] instanceof Identifier path)) return null;
        Playback playing = STREAMS.get(path);
        return playing == null
                ? null
                : CompletableFuture.completedFuture(playing.open(Boolean.TRUE.equals(args[1])));
    }

    static YsmSoundHandle play(
            YsmSoundBank.Pcm pcm,
            boolean loop,
            float volume,
            float pitch,
            Supplier<Entity> source) {
        Playback playing = new Playback(pcm, loop, volume, pitch, source);
        STREAMS.put(playing.getSound().getPath(), playing);
        Minecraft.getInstance()
                .execute(
                        () -> {
                            if (playing.cancelled) return;
                            playing.tick();
                            if (playing.isStopped()) {
                                playing.close();
                                return;
                            }
                            Minecraft.getInstance().getSoundManager().play(playing);
                            playing.started = true;
                            if (!Minecraft.getInstance().getSoundManager().isActive(playing))
                                playing.close();
                        });
        return playing;
    }

    private static final class Playback extends AbstractTickableSoundInstance
            implements YsmSoundHandle {
        private final YsmSoundBank.Pcm pcm;
        private final Supplier<Entity> entity;
        private final WeighedSoundEvents resolved;
        private volatile boolean cancelled, started;
        private volatile PcmStream stream;

        Playback(
                YsmSoundBank.Pcm pcm,
                boolean loop,
                float volume,
                float pitch,
                Supplier<Entity> source) {
            super(
                    SoundEvent.createVariableRangeEvent(
                            Identifier.fromNamespaceAndPath(
                                    "moons",
                                    "ysm_audio/" + UUID.randomUUID().toString().replace("-", ""))),
                    SoundSource.PLAYERS,
                    RandomSource.create());
            this.pcm = pcm;
            entity = source;
            this.volume = volume;
            this.pitch = pitch;
            looping = loop;
            sound =
                    new Sound(
                            identifier,
                            ConstantFloat.of(1),
                            ConstantFloat.of(1),
                            1,
                            Sound.Type.FILE,
                            true,
                            false,
                            16);
            resolved = new WeighedSoundEvents(identifier, null);
            resolved.addSound(sound);
        }

        public WeighedSoundEvents resolve(SoundManager manager) {
            return resolved;
        }

        public void tick() {
            Entity source = entity.get();
            if (cancelled
                    || source == null
                    || source.isRemoved()
                    || source.level() != Minecraft.getInstance().level) {
                stop();
                return;
            }
            x = source.getX();
            y = source.getY();
            z = source.getZ();
        }

        synchronized AudioStream open(boolean loop) {
            PcmStream next = new PcmStream(pcm, loop, () -> STREAMS.remove(sound.getPath(), this));
            if (cancelled) next.close();
            stream = next;
            return next;
        }

        public boolean stopped() {
            boolean ended =
                    cancelled
                            || stream != null && stream.closed
                            || started && !Minecraft.getInstance().getSoundManager().isActive(this);
            if (ended) close();
            return ended;
        }

        public synchronized void close() {
            if (cancelled) return;
            cancelled = true;
            STREAMS.remove(sound.getPath(), this);
            if (stream != null) stream.close();
            Minecraft.getInstance()
                    .execute(
                            () -> {
                                stop();
                                Minecraft.getInstance().getSoundManager().stop(this);
                            });
        }
    }

    static final class PcmStream implements AudioStream {
        private final YsmSoundBank.Pcm pcm;
        private final boolean loop;
        private final Runnable onClose;
        private int offset;
        private volatile boolean closed;

        PcmStream(YsmSoundBank.Pcm pcm, boolean loop, Runnable onClose) {
            this.pcm = pcm;
            this.loop = loop;
            this.onClose = onClose;
        }

        public AudioFormat getFormat() {
            return new AudioFormat(pcm.rate(), 16, pcm.channels(), true, false);
        }

        public ByteBuffer read(int requested) {
            int length = Math.min(Math.max(requested, 0), 1024 * 1024);
            length -= length % (pcm.channels() * 2);
            if (!loop) length = Math.min(length, pcm.bytes().length - offset);
            ByteBuffer output = ByteBuffer.allocateDirect(closed ? 0 : length);
            while (!closed && output.hasRemaining() && pcm.bytes().length > 0) {
                int size = Math.min(output.remaining(), pcm.bytes().length - offset);
                output.put(pcm.bytes(), offset, size);
                offset += size;
                if (loop && offset == pcm.bytes().length) offset = 0;
            }
            return output.flip();
        }

        public synchronized void close() {
            if (closed) return;
            closed = true;
            onClose.run();
        }
    }
}
