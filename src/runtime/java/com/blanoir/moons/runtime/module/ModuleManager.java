package com.blanoir.moons.runtime.module;

import com.blanoir.moons.api.Branding;
import com.blanoir.moons.api.ScopedResources;
import com.blanoir.moons.api.MoonsModule;
import com.blanoir.moons.runtime.RuntimeEvents;
import com.blanoir.moons.runtime.lifecycle.DefaultResourceScope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Transactional loader for built-in and externally overridden feature JARs. */
public final class ModuleManager implements AutoCloseable {
    private static final String BUILTIN_ENTRY = "META-INF/moons/modules/moons-core-features.jar";

    private final Path home;
    private final Path outerJar;
    private final Path moduleDirectory;
    private final Path cacheDirectory;
    private final RuntimeEvents events;
    private final String minecraftVersion;
    private final Map<String, LoadedModule> loaded = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Path> reloadQueue = new ConcurrentLinkedQueue<>();
    private final java.util.Set<Path> queuedReloads = ConcurrentHashMap.newKeySet();
    private WatchService watchService;
    private Thread watchThread;
    private Path builtinModule;
    private volatile boolean closed;

public ModuleManager(
            Path home,
            Path outerJar,
            RuntimeEvents events,
            String minecraftVersion
    ) {
        this.home = home;
        this.outerJar = outerJar;
        this.moduleDirectory = home.resolve("modules");
        this.cacheDirectory = home.resolve("cache/modules");
        this.events = events;
        this.minecraftVersion = java.util.Objects.requireNonNull(
                minecraftVersion, "minecraftVersion");
    }

    public synchronized void start() throws Exception {
        Files.createDirectories(moduleDirectory);
        Files.createDirectories(cacheDirectory);
        Path builtin = extractBuiltin();
        builtinModule = builtin;

        Map<String, Path> selected = new LinkedHashMap<>();
        ModuleDescriptor builtinDescriptor = ModuleDescriptor.read(builtin);
        selected.put(builtinDescriptor.id(), builtin);
        try (var files = Files.list(moduleDirectory)) {
            for (Path candidate : files.filter(ModuleManager::isJar).sorted().toList()) {
                ModuleDescriptor descriptor = ModuleDescriptor.read(candidate);
                selected.put(descriptor.id(), candidate);
            }
        }
        for (Path source : selected.values()) {
            replace(source);
        }
        startWatcher();
    }

    /** Applies watcher notifications from the Minecraft client thread. */
    public void drainReloads() {
        Path source;
        while ((source = reloadQueue.poll()) != null) {
            queuedReloads.remove(source);
            try {
                if (Files.isRegularFile(source)) replace(source);
                else removeDeletedSource(source);
            } catch (Throwable failure) {
                System.err.println(Branding.prefix() + " Module reload failed for " + source + ": " + failure);
                failure.printStackTrace(System.err);
            }
        }
    }

    public synchronized void replace(Path source) throws Exception {
        if (closed) throw new IllegalStateException("Module manager is closed");
        source = source.toAbsolutePath().normalize();
        Path cached = cacheCopy(source);
        ModuleDescriptor descriptor = ModuleDescriptor.read(cached);
        LoadedModule previous = loaded.get(descriptor.id());
        if (previous != null && previous.cachedJar.equals(cached)) {
            return;
        }
        LoadedModule candidate = loadCandidate(source, cached, descriptor);
        try {
            if (previous != null && previous.enabled) {
                previous.instance.disable();
                previous.enabled = false;
            }
            enable(candidate);
            candidate.enabled = true;
            loaded.put(candidate.descriptor.id(), candidate);
        } catch (Throwable failure) {
            closeModule(candidate);
            if (previous != null && !previous.enabled) {
                enable(previous);
                previous.enabled = true;
            }
            throw failure;
        }
        if (previous != null) closeModule(previous);
        System.out.println(Branding.prefix() + " Module active: " + candidate.descriptor.id()
                + "@" + candidate.descriptor.version());
    }

    public synchronized Map<String, String> modules() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        loaded.forEach((id, module) -> snapshot.put(id, module.descriptor.version()));
        return Map.copyOf(snapshot);
    }

    public synchronized boolean setEnabled(String id, boolean enabled) throws Exception {
        LoadedModule module = loaded.get(id);
        if (module == null) return false;
        if (module.enabled == enabled) return true;
        if (enabled) enable(module);
        else module.instance.disable();
        module.enabled = enabled;
        return true;
    }

    public synchronized boolean unload(String id) throws Exception {
        LoadedModule module = loaded.remove(id);
        if (module == null) return false;
        closeModule(module);
        return true;
    }

    private LoadedModule loadCandidate(
            Path source,
            Path cached,
            ModuleDescriptor descriptor
    ) throws Exception {
        if (descriptor.api() != 1) {
            throw new IOException("Unsupported API version " + descriptor.api() + " for " + descriptor.id());
        }
        if (!descriptor.supportsMinecraft(minecraftVersion)) {
            throw new IOException("Module " + descriptor.id() + " supports Minecraft "
                    + descriptor.minecraft() + ", but the runtime is " + minecraftVersion);
        }
        ClassLoader runtimeLoader = ModuleManager.class.getClassLoader();
        ModuleClassLoader loader = new ModuleClassLoader(cached.toUri().toURL(), runtimeLoader);
        DefaultResourceScope resources = new DefaultResourceScope();
        try {
            Class<?> entrypoint = Class.forName(descriptor.entrypoint(), true, loader);
            MoonsModule instance = (MoonsModule) entrypoint.getDeclaredConstructor().newInstance();
            Files.createDirectories(home.resolve("data").resolve(descriptor.id()));
            DefaultModuleContext context = new DefaultModuleContext(
                    descriptor.id(),
                    home.resolve("data").resolve(descriptor.id()),
                    resources,
                    Map.of(RuntimeEvents.class, events)
            );
            ScopedResources.run(resources, () -> instance.load(context));
            return new LoadedModule(descriptor, source, cached, loader, resources, instance);
        } catch (Throwable failure) {
            resources.close();
            loader.close();
            throw failure;
        }
    }

    private Path cacheCopy(Path source) throws Exception {
        byte[] bytes = Files.readAllBytes(source);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path target = cacheDirectory.resolve(digest + ".jar");
        if (!Files.isRegularFile(target)) {
            Path temporary = cacheDirectory.resolve(digest + ".tmp-" + ProcessHandle.current().pid());
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private Path extractBuiltin() throws IOException {
        try (JarFile jar = new JarFile(outerJar.toFile())) {
            JarEntry entry = jar.getJarEntry(BUILTIN_ENTRY);
            if (entry == null) throw new IOException("Missing built-in module payload: " + BUILTIN_ENTRY);
            Path target = cacheDirectory.resolve("builtin-core-features.jar");
            try (InputStream input = jar.getInputStream(entry)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        }
    }

    private void startWatcher() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        moduleDirectory.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
        );
        watchThread = Thread.ofPlatform()
                .name(Branding.name() + "-Module-Watcher")
                .daemon(true)
                .start(this::watchLoop);
    }

    private void watchLoop() {
        while (!closed) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path relative && isJar(relative)) {
                        Path source = moduleDirectory.resolve(relative).toAbsolutePath().normalize();
                        try {
                            // Editors commonly emit several MODIFY events. A short
                            // stability window prevents loading a partially copied JAR.
                            if (event.kind() != StandardWatchEventKinds.ENTRY_DELETE) {
                                Thread.sleep(200L);
                            }
                            if (queuedReloads.add(source)) {
                                reloadQueue.add(source);
                            }
                        } catch (Throwable failure) {
                            System.err.println(Branding.prefix() + " Module watch failed for " + source + ": " + failure);
                        }
                    }
                }
                if (!key.reset()) break;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable failure) {
                if (!closed) System.err.println(Branding.prefix() + " Module watcher failed: " + failure);
            }
        }
    }

    private static boolean isJar(Path path) {
        return path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private synchronized void removeDeletedSource(Path source) throws Exception {
        Path normalized = source.toAbsolutePath().normalize();
        LoadedModule matched = loaded.values().stream()
                .filter(module -> module.source.toAbsolutePath().normalize().equals(normalized))
                .findFirst().orElse(null);
        if (matched == null) return;
        if (matched.descriptor.id().equals("core-features") && builtinModule != null) {
            replace(builtinModule);
            return;
        }
        loaded.remove(matched.descriptor.id());
        closeModule(matched);
        System.out.println(Branding.prefix() + " Module removed: " + matched.descriptor.id());
    }

    private static void enable(LoadedModule module) throws Exception {
        ScopedResources.run(module.resources, module.instance::enable);
    }

    private static void closeModule(LoadedModule module) throws Exception {
        Exception aggregate = null;
        if (module.enabled) {
            try {
                module.instance.disable();
                module.enabled = false;
            } catch (Exception failure) {
                aggregate = failure;
            }
        }
        try {
            module.resources.close();
        } catch (Exception failure) {
            if (aggregate == null) aggregate = failure;
            else aggregate.addSuppressed(failure);
        }
        try {
            module.instance.unload();
        } catch (Exception failure) {
            if (aggregate == null) aggregate = failure;
            else aggregate.addSuppressed(failure);
        }
        module.classLoader.close();
        if (aggregate != null) throw aggregate;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (watchThread != null) watchThread.interrupt();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
        LoadedModule[] modules = loaded.values().toArray(LoadedModule[]::new);
        loaded.clear();
        reloadQueue.clear();
        queuedReloads.clear();
        for (int index = modules.length - 1; index >= 0; index--) {
            try {
                closeModule(modules[index]);
            } catch (Exception failure) {
                System.err.println(Branding.prefix() + " Module did not unload cleanly: " + failure);
            }
        }
    }
}
