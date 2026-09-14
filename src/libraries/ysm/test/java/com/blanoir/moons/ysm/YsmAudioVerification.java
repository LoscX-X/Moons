package com.blanoir.moons.ysm;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks decode reuse, independent playback ownership and cancellation during asynchronous decode. */
final class YsmAudioVerification {
    static void verify() throws Exception {
        AtomicInteger decodes = new AtomicInteger();
        List<Handle> handles = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(2);
        List<String> errors = new CopyOnWriteArrayList<>();
        var pcm = new YsmSoundBank.Pcm(new byte[1920], 48000, 1);
        try (var bank =
                new YsmSoundBank(
                        Map.of("test.ogg", new byte[] {1}),
                        bytes -> {
                            decodes.incrementAndGet();
                            return pcm;
                        },
                        (audio, loop, volume, pitch) -> {
                            Handle handle = new Handle();
                            handles.add(handle);
                            ready.countDown();
                            return handle;
                        },
                        errors::add)) {
            var a = bank.play("test", true, 1, 1);
            var b = bank.play("test", false, 1, 1);
            if (!ready.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Audio worker timed out");
            if (decodes.get() != 1 || !errors.isEmpty())
                throw new AssertionError("PCM cache reuse: " + errors);
            a.close();
            if (!handles.getFirst().closed || handles.getLast().closed)
                throw new AssertionError("Playback handles must be independent");
            bank.stopAll();
            if (!b.stopped() || !handles.getLast().closed)
                throw new AssertionError("Unload must close audio output");
        }
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                decoded = new CountDownLatch(1);
        try (var bank =
                new YsmSoundBank(
                        Map.of("test", new byte[] {1}),
                        bytes -> {
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            decoded.countDown();
                            return pcm;
                        },
                        (audio, loop, volume, pitch) -> {
                            throw new AssertionError("Cancelled decode opened output");
                        },
                        errors::add)) {
            var pending = bank.play("test", false, 1, 1);
            if (!entered.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Decode did not start");
            pending.close();
            release.countDown();
            if (!decoded.await(5, TimeUnit.SECONDS) || !pending.stopped())
                throw new AssertionError("Decode cancellation");
        }
        System.out.println(
                "YSM_AUDIO_VERIFIED cache=reused playback=isolated cancel=pending+active");
    }

    private static final class Handle implements YsmSoundHandle {
        volatile boolean closed;

        public boolean stopped() {
            return closed;
        }

        public void close() {
            closed = true;
        }
    }
}
