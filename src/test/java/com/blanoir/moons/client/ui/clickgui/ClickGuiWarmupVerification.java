package com.blanoir.moons.client.ui.clickgui;

import com.blanoir.moons.client.config.Settings;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real UI composition on a worker with a CPU canvas and no Minecraft instance. */
public final class ClickGuiWarmupVerification {
    public static void main(String[] args) throws Exception {
        Path config =
                Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "moons-gui-check-" + UUID.randomUUID());
        Settings.configure(config);
        Settings.load();
        var preview = ClickGuiWarmup.class.getDeclaredMethod("renderPreview");
        preview.setAccessible(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker =
                Thread.ofPlatform()
                        .daemon()
                        .name("clickgui-verification")
                        .start(
                                () -> {
                                    try {
                                        for (int run = 0; run < 2; run++) {
                                            long start = System.nanoTime();
                                            preview.invoke(ClickGuiWarmup.INSTANCE);
                                            System.out.println(
                                                    "CLICKGUI_CPU_PREVIEW run="
                                                            + run
                                                            + " ms="
                                                            + (System.nanoTime() - start)
                                                                    / 1_000_000);
                                        }
                                    } catch (InvocationTargetException exception) {
                                        failure.set(exception.getCause());
                                    } catch (Throwable exception) {
                                        failure.set(exception);
                                    }
                                });
        worker.join(30_000);
        if (worker.isAlive()) throw new AssertionError("ClickGUI CPU warmup timed out");
        if (failure.get() != null) throw new AssertionError("CPU warmup failed", failure.get());
        if (Files.exists(config)) throw new AssertionError("Warmup wrote configuration files");
        System.out.println("CLICKGUI_WARMUP_VERIFIED");
    }
}
