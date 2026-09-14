package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.api.*;
import com.blanoir.moons.runtime.RuntimeEvents;
import com.blanoir.moons.ysm.LocalYsmModel;
import com.blanoir.moons.ysm.YsmSession;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns local selection, background loading and the Minecraft 26.3-rc-2 renderer. */
public final class YsmModule implements MoonsModule, YsmSelector, YsmStudio {
    record Settings(Path model, String texture, String animation, double scale, boolean enabled) {}

    private record Prepared(
            long generation, Settings settings, YsmSession session, Throwable error) {
        LocalYsmModel model() {
            return session == null ? null : session.model();
        }

        void close() {
            if (session != null) session.close();
        }
    }

    private record Catalog(List<Model> models, Throwable error) {}

    private record Command(String model, boolean refresh) {}

    private record Inspection(long generation, Settings settings, String signature) {}

    private final Queue<Prepared> completed = new ConcurrentLinkedQueue<>();
    private final Queue<Catalog> catalogs = new ConcurrentLinkedQueue<>();
    private final Queue<Command> commands = new ConcurrentLinkedQueue<>();
    private ExecutorService worker;
    private Future<?> loading;
    private Future<Inspection> inspection;
    private ModuleServices services;
    private AutoCloseable registration;
    private Path directory;
    private volatile boolean enabled;
    private volatile boolean unloaded;
    private volatile YsmSelector.Snapshot snapshot =
            new YsmSelector.Snapshot("", List.of(), "", "", State.VANILLA, "Original player model");
    private List<Model> models = List.of();
    private String requested = "";
    private String active = "";
    private State state = State.VANILLA;
    private String message = "Original player model";
    private volatile long generation;
    private long nextPoll;
    private String observed = "";
    private Object level;
    private YsmRenderAdapter renderer;
    private AutoCloseable studioRegistration;
    private final Queue<java.util.function.Consumer<YsmRenderAdapter>> studioCommands =
            new ConcurrentLinkedQueue<>();
    private volatile YsmStudio.Snapshot studioSnapshot;
    private volatile long studioRequestedUntil;
    private long nextStudioSnapshot;

    public void load(ModuleContext context) throws Exception {
        directory = context.dataDirectory().toAbsolutePath().normalize();
        services = context.service(ModuleServices.class);
        Files.createDirectories(directory.resolve("models"));
        Path config = directory.resolve("ysm.properties");
        if (!Files.exists(config))
            Files.writeString(
                    config,
                    "# Local rendering only. Paths are relative to this directory.\n"
                            + "enabled=true\nmodel=\ntexture=\nanimation=\nscale=1.0\n",
                    StandardOpenOption.CREATE_NEW);
        worker =
                Executors.newSingleThreadExecutor(
                        task -> {
                            Thread thread = new Thread(task, "Moons YSM model loader");
                            thread.setDaemon(true);
                            thread.setContextClassLoader(YsmModule.class.getClassLoader());
                            return thread;
                        });
        context.resources().own(this::unload);
        RuntimeEvents events = context.service(RuntimeEvents.class);
        context.resources()
                .own(
                        events.clientTick()
                                .subscribe(
                                        event -> {
                                            if (event.phase() == RuntimeEvents.Phase.END) tick();
                                        }));
        context.resources()
                .own(
                        events.methodHook()
                                .subscribe(
                                        id -> id.startsWith("render.ysm-"),
                                        hook -> {
                                            if (!enabled
                                                    || unloaded
                                                    || renderer == null
                                                    || !(hook.argument() instanceof Object[] args))
                                                return;
                                            try {
                                                if (hook.id().equals("render.ysm-capture")) {
                                                    renderer.capture(hook.owner(), args);
                                                    return;
                                                }
                                                boolean submitted =
                                                        hook.id().equals("render.ysm-player")
                                                                ? renderer.submit(args)
                                                                : hook.id()
                                                                                .equals(
                                                                                        "render.ysm-sub-entity")
                                                                        ? renderer.submitSubEntity(
                                                                                args)
                                                                        : renderer
                                                                                .submitFirstPerson(
                                                                                        hook
                                                                                                .owner(),
                                                                                        args,
                                                                                        hook.id()
                                                                                                .equals(
                                                                                                        "render.ysm-left-hand"));
                                                if (submitted) hook.value(false);
                                            } catch (Throwable failure) {
                                                closeRenderer();
                                                fail(
                                                        "Rendering failed; restored the original player model",
                                                        failure);
                                            }
                                        }));
        context.resources()
                .own(
                        events.methodHook()
                                .subscribe(
                                        id -> id.equals("audio.ysm-stream"),
                                        hook -> {
                                            if (!unloaded
                                                    && hook.argument() instanceof Object[] args) {
                                                Object stream = YsmGameAudio.stream(args);
                                                if (stream != null) hook.value(stream);
                                            }
                                        }));
        publish();
        scanModels();
    }

    public void enable() {
        registration = services.register(YsmSelector.class, this);
        studioRegistration = services.register(YsmStudio.class, this);
        enabled = true;
        nextPoll = 0;
    }

    public void disable() throws Exception {
        enabled = false;
        generation++;
        if (loading != null) loading.cancel(true);
        if (inspection != null) inspection.cancel(true);
        inspection = null;
        synchronized (completed) {
            for (Prepared result : completed) result.close();
            completed.clear();
        }
        closeRenderer();
        observed = "";
        if (studioRegistration != null) {
            studioRegistration.close();
            studioRegistration = null;
        }
        if (registration != null) {
            registration.close();
            registration = null;
        }
    }

    public void unload() {
        synchronized (completed) {
            if (unloaded) return;
            unloaded = true;
        }
        try {
            disable();
        } catch (Exception failure) {
            report("Could not release selector", failure);
        }
        generation++;
        if (loading != null) loading.cancel(true);
        if (worker != null) {
            worker.shutdownNow();
            try {
                worker.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (completed) {
            for (Prepared result : completed) result.close();
            completed.clear();
            catalogs.clear();
        }
        commands.clear();
        studioCommands.clear();
        studioSnapshot = null;
        closeRenderer();
        level = null;
    }

    public YsmSelector.Snapshot snapshot() {
        return snapshot;
    }

    public void select(String modelId) {
        Objects.requireNonNull(modelId);
        if (snapshot.models().stream().noneMatch(model -> model.id().equals(modelId)))
            throw new IllegalArgumentException(
                    "Model is no longer in the local list; refresh first");
        enqueue(new Command(modelId, false));
    }

    public void refresh() {
        enqueue(new Command(null, true));
    }

    public void reset() {
        enqueue(new Command("", false));
    }

    private void enqueue(Command command) {
        if (!enabled || unloaded) throw new IllegalStateException("YSM module is unavailable");
        commands.add(command);
    }

    private void tick() {
        if (!enabled || unloaded) return;
        Object current = Minecraft.getInstance().level;
        if (current != level) {
            level = current;
            if (renderer != null) renderer.reset();
        }
        java.util.function.Consumer<YsmRenderAdapter> edit;
        while ((edit = studioCommands.poll()) != null) {
            if (renderer != null)
                try {
                    edit.accept(renderer);
                } catch (RuntimeException error) {
                    renderer.reportEditError(error);
                }
        }
        if (renderer != null) {
            renderer.tickStudio();
            long now = System.nanoTime();
            if (now < studioRequestedUntil && now >= nextStudioSnapshot) {
                studioSnapshot = renderer.studioSnapshot(active);
                nextStudioSnapshot = now + TimeUnit.MILLISECONDS.toNanos(100);
            }
        } else studioSnapshot = null;
        Command command;
        while ((command = commands.poll()) != null) {
            try {
                if (command.refresh()) scanModels();
                else saveSelection(command.model());
                generation++;
                if (loading != null) loading.cancel(true);
                if (inspection != null) inspection.cancel(true);
                inspection = null;
                observed = "";
                nextPoll = 0;
            } catch (Exception failure) {
                fail("Could not save model selection", failure);
            }
        }
        Catalog catalog;
        while ((catalog = catalogs.poll()) != null) {
            if (catalog.error() != null) fail("Could not read local models", catalog.error());
            else {
                models = catalog.models();
                publish();
            }
        }
        Prepared result;
        while ((result = completed.poll()) != null) {
            if (result.generation() != generation) {
                result.close();
                continue;
            }
            if (result.error() != null) {
                fail("Could not load local model", result.error());
                continue;
            }
            YsmRenderAdapter replacement;
            try {
                replacement = new YsmRenderAdapter(result.session(), result.settings());
            } catch (Throwable failure) {
                result.close();
                fail("Could not initialize local model rendering", failure);
                continue;
            }
            YsmRenderAdapter old = renderer;
            renderer = replacement;
            studioSnapshot = null;
            nextStudioSnapshot = 0;
            active = modelId(result.settings().model());
            state = State.ACTIVE;
            message =
                    result.model().boneCount()
                            + " bones · "
                            + result.model().animations().size()
                            + " animations";
            publish();
            if (old != null) {
                try {
                    old.close();
                } catch (Throwable failure) {
                    report("Could not release the previous local model", failure);
                }
            }
        }
        if (inspection == null) {
            if (System.nanoTime() < nextPoll) return;
            nextPoll = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            long inspectedGeneration = generation;
            inspection =
                    worker.submit(
                            () -> {
                                Settings settings = readSettings();
                                String signature =
                                        settings.model() == null
                                                ? ""
                                                : settings + ":" + fingerprint(settings.model());
                                return new Inspection(inspectedGeneration, settings, signature);
                            });
            return;
        }
        if (!inspection.isDone()) return;
        try {
            Inspection checked = inspection.get();
            if (checked.generation() != generation) return;
            Settings settings = checked.settings();
            requested = settings.model() == null ? "" : modelId(settings.model());
            if (!settings.enabled() || settings.model() == null) {
                generation++;
                if (loading != null) loading.cancel(true);
                closeRenderer();
                observed = "";
                state = State.VANILLA;
                message = "Original player model";
                publish();
                return;
            }
            String signature = checked.signature();
            if (signature.equals(observed)) return;
            observed = signature;
            long requestedGeneration = ++generation;
            if (loading != null) loading.cancel(true);
            state = State.LOADING;
            message = "Loading " + settings.model().getFileName();
            publish();
            loading =
                    worker.submit(
                            () -> {
                                Prepared prepared;
                                LocalYsmModel candidate = null;
                                YsmSession candidateSession = null;
                                try {
                                    LocalYsmModel model = LocalYsmModel.load(settings.model());
                                    candidate = model;
                                    String preferenceKey =
                                            UUID.nameUUIDFromBytes(
                                                            settings.model()
                                                                    .toAbsolutePath()
                                                                    .normalize()
                                                                    .toString()
                                                                    .getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8))
                                                    .toString();
                                    candidateSession =
                                            new YsmSession(
                                                    model,
                                                    directory
                                                            .resolve("parameters")
                                                            .resolve(preferenceKey + ".json"),
                                                    settings.animation(),
                                                    settings.texture());
                                    prepared =
                                            new Prepared(
                                                    requestedGeneration,
                                                    settings,
                                                    candidateSession,
                                                    null);
                                } catch (Throwable failure) {
                                    if (candidateSession != null) candidateSession.close();
                                    else if (candidate != null) candidate.close();
                                    prepared =
                                            new Prepared(
                                                    requestedGeneration, settings, null, failure);
                                }
                                synchronized (completed) {
                                    if (!unloaded
                                            && enabled
                                            && prepared.generation() == generation
                                            && !Thread.currentThread().isInterrupted())
                                        completed.add(prepared);
                                    else prepared.close();
                                }
                            });
        } catch (Exception failure) {
            fail("Could not read selected model", failure);
        } finally {
            inspection = null;
        }
    }

    private void scanModels() {
        worker.submit(
                () -> {
                    Catalog catalog;
                    try (var entries = Files.list(directory.resolve("models"))) {
                        List<Model> found =
                                entries.filter(path -> Files.isDirectory(path) || isModelFile(path))
                                        .sorted(
                                                Comparator.comparing(
                                                        path ->
                                                                path.getFileName()
                                                                        .toString()
                                                                        .toLowerCase(Locale.ROOT)))
                                        .limit(4096)
                                        .map(
                                                path ->
                                                        new Model(
                                                                modelId(path),
                                                                path.getFileName().toString(),
                                                                Files.isDirectory(path)
                                                                        ? "Folder"
                                                                        : isZip(path)
                                                                                ? "ZIP"
                                                                                : "YSM"))
                                        .toList();
                        catalog = new Catalog(found, null);
                    } catch (Exception failure) {
                        catalog = new Catalog(List.of(), failure);
                    }
                    synchronized (completed) {
                        if (!unloaded) catalogs.add(catalog);
                    }
                });
    }

    private static boolean isZip(Path path) {
        return path.toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static boolean isModelFile(Path path) {
        return Files.isRegularFile(path)
                && (isZip(path) || path.toString().toLowerCase(Locale.ROOT).endsWith(".ysm"));
    }

    private String modelId(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return (absolute.startsWith(directory) ? directory.relativize(absolute) : absolute)
                .toString()
                .replace('\\', '/');
    }

    private Properties properties() throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(directory.resolve("ysm.properties"))) {
            properties.load(reader);
        }
        return properties;
    }

    private void saveSelection(String model) throws IOException {
        Properties properties = properties();
        properties.setProperty("model", model);
        properties.setProperty("enabled", "true");
        // A new model starts with its own default texture and automatic animation.
        properties.setProperty("texture", "");
        properties.setProperty("animation", "");
        Path temporary = directory.resolve("ysm.properties.tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Moons local YSM selection");
            }
            try {
                Files.move(
                        temporary,
                        directory.resolve("ysm.properties"),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        directory.resolve("ysm.properties"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Settings readSettings() throws IOException {
        Properties properties = properties();
        String model = properties.getProperty("model", "").trim();
        boolean renderingEnabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
        if (model.isEmpty() || !renderingEnabled) {
            return new Settings(null, "", "", 1.0, renderingEnabled);
        }
        double scale = Double.parseDouble(properties.getProperty("scale", "1"));
        if (!Double.isFinite(scale) || scale < 0.01 || scale > 10)
            throw new IOException("scale must be between 0.01 and 10");
        return new Settings(
                model.isEmpty() ? null : directory.resolve(model).normalize(),
                properties.getProperty("texture", "").trim(),
                properties.getProperty("animation", "").trim(),
                scale,
                renderingEnabled);
    }

    private static long fingerprint(Path path) throws IOException {
        if (!Files.isDirectory(path))
            return Files.getLastModifiedTime(path).toMillis() ^ Files.size(path);
        long hash = 1;
        try (var walk = Files.walk(path)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().limit(16385).toList())
                hash =
                        31 * hash
                                + file.hashCode()
                                + Files.size(file)
                                + Files.getLastModifiedTime(file).toMillis();
        }
        return hash;
    }

    private void closeRenderer() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        active = "";
        studioSnapshot = null;
        nextStudioSnapshot = 0;
    }

    private void publish() {
        snapshot =
                new YsmSelector.Snapshot(
                        directory.resolve("models").toString(),
                        models,
                        requested,
                        active,
                        state,
                        message);
    }

    private void fail(String description, Throwable failure) {
        Throwable cause = failure;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (seen.add(cause) && cause.getCause() != null && !seen.contains(cause.getCause()))
            cause = cause.getCause();
        String detail =
                description
                        + ": "
                        + Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
        if (!detail.equals(message)) report(description, failure);
        state = State.ERROR;
        message = detail;
        publish();
    }

    private static void report(String message, Throwable failure) {
        System.getLogger("moons.ysm").log(System.Logger.Level.WARNING, message, failure);
    }

    public YsmStudio.Snapshot studioSnapshot() {
        studioRequestedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        return studioSnapshot;
    }

    private void edit(java.util.function.Consumer<YsmRenderAdapter> action) {
        if (!enabled || unloaded || studioSnapshot == null)
            throw new IllegalStateException("Select a model first");
        if (studioCommands.size() > 512) throw new IllegalStateException("Too many pending edits");
        studioCommands.add(action);
    }

    public void setParameter(String id, double value) {
        edit(r -> r.session().setParameter(id, value));
    }

    public void play(String animation) {
        edit(r -> r.session().play(animation));
    }

    public void playback(double speed, boolean paused) {
        edit(r -> r.session().playback(speed, paused));
    }

    public void seek(double seconds) {
        edit(r -> r.session().seek(seconds));
    }

    public void texture(String name) {
        edit(r -> r.changeTexture(name));
    }

    public void evaluate(String expression) {
        edit(r -> r.session().evaluate(expression));
    }

    public void resetParameters() {
        edit(r -> r.session().resetParameters());
    }

    public void pose(String bone, YsmStudio.BonePose p) {
        edit(
                r ->
                        r.session()
                                .pose()
                                .set(
                                        bone,
                                        p == null
                                                ? null
                                                : new com.blanoir.moons.ysm.YsmPose(
                                                        p.x(),
                                                        p.y(),
                                                        p.z(),
                                                        p.pitch(),
                                                        p.yaw(),
                                                        p.roll(),
                                                        p.scaleX(),
                                                        p.scaleY(),
                                                        p.scaleZ(),
                                                        p.hidden())));
    }

    public void resetPose() {
        edit(r -> r.session().pose().reset());
    }
}
