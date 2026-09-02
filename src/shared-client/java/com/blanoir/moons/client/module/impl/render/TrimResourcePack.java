package com.blanoir.moons.client.module.impl.render;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Makes the hot-loaded trim assets visible to Minecraft's ResourceManager. */
final class TrimResourcePack {
    private static final String PACK_DIRECTORY = "moons-trims";
    private static final String EXPECTED_PACK_ID = "file/" + PACK_DIRECTORY;
    private static final List<String> PATTERNS = List.of(
            "aquatic", "dragon", "easter", "end", "flame", "frost", "graphite", "heart",
            "infected", "ironclad", "lightning", "mummy", "necrotic", "nether_heart", "ocean",
            "ominous", "pumpkin", "radioactive", "redstone", "runic", "spiral", "warden",
            "wither", "zombie"
    );
    private static boolean reloadScheduled;

    private TrimResourcePack() {
    }

    static synchronized void installAndLoad(Minecraft client) {
        if (client == null || reloadScheduled) return;
        try {
            Path packRoot = client.getResourcePackDirectory().resolve(PACK_DIRECTORY);
            copyResource("/pack.mcmeta", packRoot.resolve("pack.mcmeta"));
            copyResource(
                    "/assets/minecraft/atlases/armor_trims.json",
                    packRoot.resolve("assets/minecraft/atlases/armor_trims.json")
            );
            for (String pattern : PATTERNS) {
                copyTrimTexture(packRoot, "humanoid", pattern);
                copyTrimTexture(packRoot, "humanoid_leggings", pattern);
            }
            reloadScheduled = true;
            client.execute(() -> enablePack(client));
        } catch (IOException exception) {
            System.err.println("[client] Failed to install Hoplite trim resources: " + exception.getMessage());
        }
    }

    private static void copyTrimTexture(Path packRoot, String layer, String pattern) throws IOException {
        String relative = "assets/civilization/textures/trims/entity/" + layer + "/" + pattern + "_trim.png";
        copyResource("/" + relative, packRoot.resolve(relative));
    }

    private static void copyResource(String resource, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream input = TrimResourcePack.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing bundled resource " + resource);
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void enablePack(Minecraft client) {
        PackRepository repository = client.getResourcePackRepository();
        repository.reload();
        String packId = repository.getAvailableIds().stream()
                .filter(id -> id.equals(EXPECTED_PACK_ID) || id.endsWith("/" + PACK_DIRECTORY))
                .findFirst()
                .orElse(null);
        if (packId == null) {
            reloadScheduled = false;
            System.err.println("[client] Installed Hoplite trim pack was not discovered");
            return;
        }
        if (!repository.getSelectedIds().contains(packId)) {
            repository.addPack(packId);
            client.options.updateResourcePacks(repository);
            client.options.save();
        }
        client.reloadResourcePacks().whenComplete((unused, error) -> {
            if (error != null) {
                reloadScheduled = false;
                System.err.println("[client] Failed to reload Hoplite trim resources: " + error.getMessage());
            }
        });
    }
}
