package com.blanoir.moons.ysm.codecs;

import com.blanoir.moons.ysm.codecs.internal.concentus.OpusDecoder;
import com.blanoir.moons.ysm.codecs.internal.gagravarr.ogg.OggFile;
import com.blanoir.moons.ysm.codecs.internal.gagravarr.opus.OpusFile;

import java.io.*;

/** Pure Java Ogg/Opus decoder. Returns 48 kHz, signed 16-bit little-endian mono PCM. */
public final class OpusPcmDecoder {
    public static byte[] decode(byte[] encoded) throws IOException {
        try (var ogg = new OggFile(new ByteArrayInputStream(encoded));
                var opus = new OpusFile(ogg)) {
            int channels = opus.getInfo().getNumChannels();
            if (channels < 1 || channels > 2)
                throw new IOException("Opus supports mono/stereo only");
            var decoder = new OpusDecoder(48000, channels);
            var output = new ByteArrayOutputStream();
            short[] pcm = new short[5760 * channels];
            int preSkip = opus.getInfo().getPreSkip(), skip = preSkip;
            long finalGranule = -1;
            // Ogg stores this field as a signed Q7.8 number; the container reader exposes uint16.
            double gain = Math.pow(10, (short) opus.getInfo().getOutputGain() / 256d / 20d);
            var packet = opus.getNextAudioPacket();
            while (packet != null) {
                finalGranule = packet.getGranulePosition();
                byte[] data = packet.getData();
                int frames = decoder.decode(data, 0, data.length, pcm, 0, 5760, false);
                for (int frame = 0; frame < frames; frame++) {
                    if (skip > 0) {
                        skip--;
                        continue;
                    }
                    int sample = pcm[frame * channels];
                    if (channels == 2) sample = (sample + pcm[frame * 2 + 1]) / 2;
                    sample = (int) Math.clamp(sample * gain, -32768, 32767);
                    output.write(sample & 255);
                    output.write((sample >>> 8) & 255);
                }
                if (output.size() > 128 * 1024 * 1024)
                    throw new IOException("Decoded sound exceeds 128 MiB");
                packet = opus.getNextAudioPacket();
            }
            byte[] result = output.toByteArray();
            if (finalGranule >= 0) {
                long bytes = Math.max(0, finalGranule - preSkip) * 2;
                if (bytes < result.length) result = java.util.Arrays.copyOf(result, (int) bytes);
            }
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Opus decode failed", e);
        }
    }
}
