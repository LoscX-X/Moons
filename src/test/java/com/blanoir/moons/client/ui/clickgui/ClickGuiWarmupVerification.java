package com.blanoir.moons.client.ui.clickgui;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.utils.math.RandomMath;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real UI composition on a worker with a CPU canvas and no Minecraft instance. */
public final class ClickGuiWarmupVerification {
    public static void main(String[] args) throws Exception {
        verifyRenderPipeline();
        Path config =
                Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "moons-gui-check-" + RandomMath.uuid());
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

    /** Exercise lazy RenderPearl pipeline construction without creating a GPU device. */
    private static void verifyRenderPipeline() throws Exception {
        Class<?> surface = Class.forName("com.blanoir.moons.client.ui.compose.FinalFrameSurface");
        java.lang.reflect.Field companionField;
        try {
            companionField = surface.getDeclaredField("Companion");
        } catch (NoSuchFieldException legacyGlSurface) {
            return;
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Object companion = companionField.get(null);
        var getter = companion.getClass().getDeclaredMethod("getPipeline");
        getter.setAccessible(true);
        Object pipeline = getter.invoke(companion);
        if (pipeline == null || getter.invoke(companion) != pipeline) {
            throw new AssertionError("Final-frame pipeline was not initialized and cached");
        }
        System.out.println("FINAL_FRAME_PIPELINE_VERIFIED");
        verifyRasterFrame(surface);
    }

    private static void verifyRasterFrame(Class<?> frameClass) throws Exception {
        int width = 337, height = 257, stride = width * 4;
        var imageInfo =
                new org.jetbrains.skia.ImageInfo(
                        width,
                        height,
                        org.jetbrains.skia.ColorType.RGBA_8888,
                        org.jetbrains.skia.ColorAlphaType.PREMUL);
        var referenceBytes = org.lwjgl.system.MemoryUtil.memAlloc(stride * height);
        var uploadBytes = org.lwjgl.system.MemoryUtil.memAlloc(stride * height);
        Object frame = frameClass.getDeclaredConstructor().newInstance();
        Class<?> boundsClass = Class.forName(frameClass.getName() + "$PixelBounds");
        var boundsConstructor =
                boundsClass.getDeclaredConstructor(int.class, int.class, int.class, int.class);
        boundsConstructor.setAccessible(true);
        Object fullBounds = boundsConstructor.newInstance(0, 0, width, height);
        var packPixels = frameClass.getDeclaredMethod("packPixels", boundsClass);
        packPixels.setAccessible(true);
        Class<?> fontClass =
                Class.forName(
                        "com.blanoir.moons.client.ui.hud.TextGuiSkiaOverlay$MinecraftPixelFont");
        var companionField = fontClass.getDeclaredField("Companion");
        companionField.setAccessible(true);
        Object companion = companionField.get(null);
        var load = companion.getClass().getDeclaredMethod("load", ClassLoader.class);
        load.setAccessible(true);
        Object font = load.invoke(companion, ClickGuiWarmupVerification.class.getClassLoader());
        var draw =
                fontClass.getDeclaredMethod(
                        "draw",
                        org.jetbrains.skia.Canvas.class,
                        String.class,
                        float.class,
                        float.class,
                        float.class,
                        int.class,
                        boolean.class);
        draw.setAccessible(true);
        var closeFont = fontClass.getDeclaredMethod("close");
        closeFont.setAccessible(true);
        try (var reference =
                org.jetbrains.skia.Surface.Companion.makeRasterDirect(
                        imageInfo,
                        org.lwjgl.system.MemoryUtil.memAddress(referenceBytes),
                        stride)) {
            setField(frameClass, frame, "width", width);
            setField(frameClass, frame, "height", height);
            setField(frameClass, frame, "pixels", uploadBytes);
            setField(frameClass, frame, "content", fullBounds);
            setField(
                    frameClass,
                    frame,
                    "surface",
                    org.jetbrains.skia.Surface.Companion.makeRasterDirect(
                            imageInfo,
                            org.lwjgl.system.MemoryUtil.memAddress(uploadBytes),
                            stride));
            var rasterize =
                    frameClass.getDeclaredMethod(
                            "rasterizeFrame", kotlin.jvm.functions.Function1.class);
            rasterize.setAccessible(true);
            kotlin.jvm.functions.Function1<org.jetbrains.skia.Canvas, kotlin.Unit> paint =
                    canvas -> {
                        try {
                            float[] scales = {1f, 1.375f, 2.34f, 3f, 3.12f};
                            for (int row = 0; row < scales.length; row++) {
                                draw.invoke(
                                        font,
                                        canvas,
                                        "BlockAnimation - MC",
                                        1.25f,
                                        2.75f + row * 48,
                                        scales[row],
                                        0xff40c8ff,
                                        true);
                            }
                            return kotlin.Unit.INSTANCE;
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(failure);
                        }
                    };
            for (int run = 0; run < 2; run++) {
                reference.getCanvas().clear(0);
                paint.invoke(reference.getCanvas());
                rasterize.invoke(frame, paint);
                java.nio.ByteBuffer packed =
                        (java.nio.ByteBuffer) packPixels.invoke(frame, fullBounds);
                if (packed.remaining() != stride * height) {
                    throw new AssertionError("Full upload has an incorrect byte limit");
                }
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < stride; x++) {
                        if (referenceBytes.get(y * stride + x)
                                != packed.get((height - 1 - y) * stride + x)) {
                            throw new AssertionError(
                                    "Final-frame upload changed font pixels: row="
                                            + y
                                            + " byte="
                                            + x);
                        }
                    }
                int left = 7, top = 13, right = 101, bottom = 77;
                Object partialBounds = boundsConstructor.newInstance(left, top, right, bottom);
                java.nio.ByteBuffer partial =
                        (java.nio.ByteBuffer) packPixels.invoke(frame, partialBounds);
                int partialStride = (right - left) * 4;
                if (partial != packed || partial.remaining() != partialStride * (bottom - top)) {
                    throw new AssertionError(
                            "Partial upload must reuse memory with a tight byte limit");
                }
                for (int y = top; y < bottom; y++)
                    for (int x = 0; x < partialStride; x++) {
                        if (referenceBytes.get(y * stride + left * 4 + x)
                                != partial.get((bottom - 1 - y) * partialStride + x)) {
                            throw new AssertionError("Cropped upload changed source font pixels");
                        }
                    }
            }
            // A disappearing HUD must clear its previous pixels on the retained CPU canvas.
            rasterize.invoke(
                    frame,
                    (kotlin.jvm.functions.Function1<org.jetbrains.skia.Canvas, kotlin.Unit>)
                            canvas -> kotlin.Unit.INSTANCE);
            for (int index = 0; index < uploadBytes.capacity(); index++) {
                if (uploadBytes.get(index) != 0) {
                    throw new AssertionError("Retained canvas kept pixels from the previous HUD");
                }
            }
        } finally {
            ((AutoCloseable) frame).close();
            closeFont.invoke(font);
            org.lwjgl.system.MemoryUtil.memFree(referenceBytes);
        }
        System.out.println(
                "FINAL_FRAME_FONT_PIXELS_VERIFIED scales=1,1.375,2.34,3,3.12 frames=2 cropped=true cleared=true");
    }

    private static void setField(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
