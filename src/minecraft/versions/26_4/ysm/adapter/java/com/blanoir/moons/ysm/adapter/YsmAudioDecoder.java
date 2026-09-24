package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.YsmSoundBank;
import com.blanoir.moons.ysm.codecs.OpusPcmDecoder;

import org.lwjgl.stb.*;
import org.lwjgl.system.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

final class YsmAudioDecoder {
    static YsmSoundBank.Pcm decode(byte[] data) throws IOException {
        String header = new String(data, 0, Math.min(data.length, 128), StandardCharsets.US_ASCII);
        if (header.contains("OpusHead"))
            return new YsmSoundBank.Pcm(OpusPcmDecoder.decode(data), 48000, 1);
        ByteBuffer input = MemoryUtil.memAlloc(data.length).put(data).flip();
        long handle = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_memory(input, error, null);
            if (handle == 0) throw new IOException("Invalid Ogg Vorbis stream: " + error.get(0));
            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(handle, info);
            int channels = info.channels(), rate = info.sample_rate();
            if (channels < 1 || channels > 2)
                throw new IOException("Vorbis supports mono/stereo only");
            ShortBuffer samples = stack.mallocShort(4096 * channels);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int frames;
            while ((frames =
                            STBVorbis.stb_vorbis_get_samples_short_interleaved(
                                    handle, channels, samples))
                    > 0) {
                for (int i = 0; i < frames; i++) {
                    int value = samples.get(i * channels);
                    if (channels == 2) value = (value + samples.get(i * 2 + 1)) / 2;
                    out.write(value & 255);
                    out.write((value >>> 8) & 255);
                }
                if (out.size() > 128 * 1024 * 1024)
                    throw new IOException("Decoded sound exceeds 128 MiB");
            }
            return new YsmSoundBank.Pcm(out.toByteArray(), rate, 1);
        } finally {
            if (handle != 0) STBVorbis.stb_vorbis_close(handle);
            MemoryUtil.memFree(input);
        }
    }
}
