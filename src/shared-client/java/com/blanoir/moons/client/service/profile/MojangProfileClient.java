package com.blanoir.moons.client.service.profile;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

public final class MojangProfileClient implements AutoCloseable {
    private static final URI BULK_PROFILE_LOOKUP_URI = URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname");
    private static final int MAX_NAMES_PER_REQUEST = 10;
    private static final long BATCH_INTERVAL_MILLIS = 750L;

    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10L))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "moons-profile-lookup");
        thread.setDaemon(true);
        return thread;
    });

    public CompletableFuture<Map<String, MojangProfile>> lookupAsyncBatched(Collection<String> names) {
        List<String> uniqueNames = deduplicateNames(names);

        return CompletableFuture.supplyAsync(() -> {
            Map<String, MojangProfile> profiles = new LinkedHashMap<>();

            for (int start = 0; start < uniqueNames.size(); start += MAX_NAMES_PER_REQUEST) {
                int end = Math.min(start + MAX_NAMES_PER_REQUEST, uniqueNames.size());
                profiles.putAll(lookupBatch(uniqueNames.subList(start, end)));

                if (end < uniqueNames.size()) {
                    sleepBetweenBatches();
                }
            }

            return profiles;
        }, executor);
    }

    public CompletableFuture<MojangProfile> lookupByUuidAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> lookupByUuid(uuid), executor);
    }

    private MojangProfile lookupByUuid(UUID uuid) {
        if (uuid == null) return null;
        URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                + uuid.toString().replace("-", ""));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15L))
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204 || response.statusCode() == 404) return null;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Mojang API HTTP " + response.statusCode());
            }
            JsonObject object = gson.fromJson(response.body(), JsonObject.class);
            String id = stringMember(object, "id");
            String name = stringMember(object, "name");
            return id == null || name == null ? null : new MojangProfile(id, name);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CompletionException(exception);
        }
    }

    private Map<String, MojangProfile> lookupBatch(List<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }

        String requestBody = gson.toJson(names);
        HttpRequest request = HttpRequest.newBuilder(BULK_PROFILE_LOOKUP_URI)
                .timeout(Duration.ofSeconds(15L))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Mojang API HTTP " + response.statusCode());
            }

            JsonArray array = gson.fromJson(response.body(), JsonArray.class);
            Map<String, MojangProfile> profiles = new LinkedHashMap<>();

            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject object = element.getAsJsonObject();
                String id = stringMember(object, "id");
                String name = stringMember(object, "name");

                if (id != null && name != null) {
                    profiles.put(name.toLowerCase(Locale.ROOT), new MojangProfile(id, name));
                }
            }

            return profiles;
        } catch (IOException ex) {
            throw new CompletionException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        }
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

    private static String stringMember(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    public record MojangProfile(String id, String name) {
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
