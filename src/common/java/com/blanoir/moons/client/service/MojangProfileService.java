package com.blanoir.moons.client.service;

import com.blanoir.moons.client.api.profile.MojangProfile;
import com.blanoir.moons.client.api.profile.MojangProfileApi;
import com.blanoir.moons.client.threads.TaskScope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns batched profile queries, request pacing and the cancellable lookup lifetime. */
public final class MojangProfileService implements AutoCloseable {
    private static final int MAX_NAMES_PER_REQUEST = 10;
    private static final long BATCH_INTERVAL_MILLIS = 750L;
    private final MojangProfileApi api = new MojangProfileApi();
    private final TaskScope tasks =
            TaskScope.serial("profile lookup", "moons-profile-lookup", Integer.MAX_VALUE);

    public CompletableFuture<Map<String, MojangProfile>> lookupAsyncBatched(
            Collection<String> names) {
        List<String> uniqueNames = deduplicateNames(names);

        return tasks.submit(
                () -> {
                    Map<String, MojangProfile> profiles = new LinkedHashMap<>();

                    for (int start = 0;
                            start < uniqueNames.size();
                            start += MAX_NAMES_PER_REQUEST) {
                        int end = Math.min(start + MAX_NAMES_PER_REQUEST, uniqueNames.size());
                        profiles.putAll(api.lookupBatch(uniqueNames.subList(start, end)));

                        if (end < uniqueNames.size()) {
                            sleepBetweenBatches();
                        }
                    }

                    return profiles;
                });
    }

    public CompletableFuture<MojangProfile> lookupByUuidAsync(UUID uuid) {
        return tasks.submit(() -> api.lookupByUuid(uuid));
    }

    public TaskScope.Token lifetime() {
        return tasks.token();
    }

    public void cancelPending() {
        tasks.cancelOutstanding();
    }

    private static void sleepBetweenBatches() {
        try {
            Thread.sleep(BATCH_INTERVAL_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        }
    }

    private static List<String> deduplicateNames(Collection<String> names) {
        Map<String, String> unique = new LinkedHashMap<>();

        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }

            String trimmed = name.trim();
            unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }

        return new ArrayList<>(unique.values());
    }

    @Override
    public void close() {
        tasks.close();
    }
}
