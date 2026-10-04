package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.YsmSoundBank;
import com.blanoir.moons.ysm.codecs.OpusPcmDecoder;

import paulscode.sound.SoundBuffer;
import paulscode.sound.codecs.CodecJOrbis;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import javax.sound.sampled.AudioFormat;

/** Decodes with the actual Vorbis codec bundled by Minecraft 1.8.9. */
final class YsmAudioDecoder {
    static YsmSoundBank.Pcm decode(byte[] data) throws IOException {
        String header = new String(data, 0, Math.min(data.length, 128), StandardCharsets.US_ASCII);
        if (header.contains("OpusHead"))
            return new YsmSoundBank.Pcm(OpusPcmDecoder.decode(data), 48000, 1);
        URL memory =
                new URL(
                        null,
                        "ysm-memory:/sound.ogg",
                        new URLStreamHandler() {
                            protected URLConnection openConnection(URL url) {
                                return new URLConnection(url) {
                                    public void connect() {
                                        connected = true;
                                    }

                                    public InputStream getInputStream() {
                                        return new ByteArrayInputStream(data);
                                    }

                                    public long getContentLengthLong() {
                                        return data.length;
                                    }
                                };
                            }
                        });
        CodecJOrbis codec = new CodecJOrbis();
        try {
            if (!codec.initialize(memory)) throw new IOException("Invalid Ogg Vorbis stream");
            AudioFormat format = codec.getAudioFormat();
            if (format == null
                    || format.getSampleSizeInBits() != 16
                    || format.getChannels() < 1
                    || format.getChannels() > 2
                    || !AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()))
                throw new IOException(
                        "Vorbis decoder did not produce mono/stereo signed 16-bit PCM");
            int channels = format.getChannels();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (!codec.endOfStream()) {
                SoundBuffer decoded = codec.read();
                if (decoded == null) break;
                byte[] samples = decoded.audioData;
                for (int offset = 0;
                        offset + channels * 2 <= samples.length;
                        offset += channels * 2) {
                    int sample = signed(samples, offset, format.isBigEndian());
                    if (channels == 2)
                        sample = (sample + signed(samples, offset + 2, format.isBigEndian())) / 2;
                    output.write(sample & 255);
                    output.write((sample >>> 8) & 255);
                }
                decoded.cleanup();
                if (output.size() > 128 * 1024 * 1024)
                    throw new IOException("Decoded sound exceeds 128 MiB");
            }
            if (output.size() == 0) throw new IOException("Empty Vorbis audio stream");
            return new YsmSoundBank.Pcm(output.toByteArray(), (int) format.getSampleRate(), 1);
        } finally {
            codec.cleanup();
        }
    }

    private static int signed(byte[] bytes, int offset, boolean bigEndian) {
        int lo = bytes[offset + (bigEndian ? 1 : 0)] & 255;
        int hi = bytes[offset + (bigEndian ? 0 : 1)];
        return (short) (lo | hi << 8);
    }
}
