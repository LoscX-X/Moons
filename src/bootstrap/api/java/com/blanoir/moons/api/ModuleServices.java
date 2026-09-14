package com.blanoir.moons.api;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime-owned discovery for optional modules. Contracts must belong to the shared API. */
public final class ModuleServices {
    private record Entry(Object provider, Object token) {}

    private final ConcurrentHashMap<Class<?>, Entry> providers = new ConcurrentHashMap<>();

    public <T> Optional<T> find(Class<T> contract) {
        Entry entry = providers.get(contract);
        return Optional.ofNullable(entry == null ? null : contract.cast(entry.provider()));
    }

    /** Register during enable; close during disable. An old registration cannot remove its successor. */
    public <T> AutoCloseable register(Class<T> contract, T provider) {
        Objects.requireNonNull(provider);
        if (contract.getClassLoader() != ModuleServices.class.getClassLoader()) {
            throw new IllegalArgumentException("Service contracts must belong to the shared API");
        }
        contract.cast(provider);
        Entry entry = new Entry(provider, new Object());
        if (providers.putIfAbsent(contract, entry) != null) {
            throw new IllegalStateException("Service already registered: " + contract.getName());
        }
        return () -> providers.remove(contract, entry);
    }
}
