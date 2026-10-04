package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.YsmSoundBank;
import com.blanoir.moons.ysm.YsmSoundHandle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.entity.Entity;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** PCM sources on the game's existing OpenAL device, following its listener and master gain. */
final class YsmGameAudio {
    private static final Set<Playback> PLAYING = ConcurrentHashMap.newKeySet();

    private YsmGameAudio() {}

    static YsmSoundHandle play(
            YsmSoundBank.Pcm pcm,
            boolean loop,
            float volume,
            float pitch,
            Supplier<Entity> source) {
        Playback playing = new Playback(pcm, loop, volume, pitch, source);
        PLAYING.add(playing);
        Minecraft.getMinecraft().addScheduledTask(playing::start);
        return playing;
    }

    /** Called per render frame so audio pauses even while world ticks are suspended. */
    static void tick() {
        for (Playback playback : PLAYING) playback.update();
    }

    private static final class Playback implements YsmSoundHandle {
        final YsmSoundBank.Pcm pcm;
        final boolean loop;
        final float volume, pitch;
        final Supplier<Entity> entity;
        volatile boolean closed;
        int buffer, source;
        boolean paused;
        Object context;

        Playback(
                YsmSoundBank.Pcm pcm,
                boolean loop,
                float volume,
                float pitch,
                Supplier<Entity> entity) {
            if (pcm.channels() != 1
                    || pcm.bytes().length == 0
                    || pcm.bytes().length % 2 != 0
                    || pcm.rate() <= 0)
                throw new IllegalArgumentException(
                        "YSM positional audio requires nonempty mono PCM16");
            this.pcm = pcm;
            this.loop = loop;
            this.volume = Math.clamp(volume, 0, 16);
            this.pitch = Math.clamp(pitch, .05f, 4f);
            this.entity = entity;
        }

        void start() {
            if (closed) return;
            if (!AL.isCreated()) {
                close();
                return;
            }
            try {
                context = AL.getContext();
                buffer = AL10.alGenBuffers();
                ByteBuffer data = BufferUtils.createByteBuffer(pcm.bytes().length);
                data.put(pcm.bytes()).flip();
                AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, data, pcm.rate());
                source = AL10.alGenSources();
                if (buffer == 0 || source == 0)
                    throw new IllegalStateException("OpenAL source unavailable");
                AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
                AL10.alSourcei(source, AL10.AL_LOOPING, loop ? AL10.AL_TRUE : AL10.AL_FALSE);
                AL10.alSourcef(source, AL10.AL_PITCH, pitch);
                AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 1);
                AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, Math.max(16, volume * 16));
                // The shared vanilla listener already carries MASTER volume.
                if (!position()) {
                    close();
                    return;
                }
                AL10.alSourcePlay(source);
                update();
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }

        boolean position() {
            Minecraft client = Minecraft.getMinecraft();
            Entity owner = entity.get();
            if (owner == null || owner.isDead || owner.worldObj != client.theWorld) return false;
            AL10.alSource3f(
                    source,
                    AL10.AL_POSITION,
                    (float) owner.posX,
                    (float) owner.posY,
                    (float) owner.posZ);
            AL10.alSourcef(
                    source,
                    AL10.AL_GAIN,
                    volume * client.gameSettings.getSoundLevel(SoundCategory.PLAYERS));
            return true;
        }

        void update() {
            if (closed || source == 0) return;
            if (!AL.isCreated() || AL.getContext() != context || !position()) {
                close();
                return;
            }
            if (!paused && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                close();
                return;
            }
            boolean pause = Minecraft.getMinecraft().isGamePaused();
            if (pause != paused) {
                paused = pause;
                if (pause) AL10.alSourcePause(source);
                else AL10.alSourcePlay(source);
            }
            if (!paused && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED)
                close();
        }

        public boolean stopped() {
            return closed;
        }

        public void close() {
            if (closed) return;
            closed = true;
            PLAYING.remove(this);
            Minecraft.getMinecraft()
                    .addScheduledTask(
                            () -> {
                                if (AL.isCreated() && AL.getContext() == context) {
                                    if (source != 0) {
                                        AL10.alSourceStop(source);
                                        AL10.alDeleteSources(source);
                                    }
                                    if (buffer != 0) AL10.alDeleteBuffers(buffer);
                                }
                                source = buffer = 0;
                            });
        }
    }
}
