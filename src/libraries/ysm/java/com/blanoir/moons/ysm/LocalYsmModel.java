package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.internal.core.security.YsmCrypt;
import com.blanoir.moons.ysm.internal.resource.*;
import com.blanoir.moons.ysm.internal.resource.bundle.TextureAlphaAnalyzer;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel.*;
import com.blanoir.moons.ysm.internal.runtime.*;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared local model engine. No game, mod-loader, networking or JNI references. */
public final class LocalYsmModel implements AutoCloseable {
    public record Texture(String name, byte[] data, int format, int width, int height) {}

    /** Eight floats per vertex: xyz, uv, normal xyz. Each consecutive four vertices form a quad. */
    public static final class Mesh {
        private volatile float[] vertices;
        private final List<Pass> passes;
        private final Map<String, Matrix4f> locators, equipmentLocators, swordLocators;
        private final Set<String> hiddenLocators;
        private final int vertexCount;

        public Mesh(
                float[] vertices,
                List<Pass> passes,
                Map<String, Matrix4f> locators,
                Map<String, Matrix4f> equipmentLocators,
                Set<String> hiddenLocators,
                Map<String, Matrix4f> swordLocators) {
            this.vertices = vertices;
            this.passes = passes;
            this.locators = locators;
            this.equipmentLocators = equipmentLocators;
            this.hiddenLocators = hiddenLocators;
            this.swordLocators = swordLocators;
            int count = 0;
            for (Pass pass : passes) count += pass.vertices().length / 8;
            vertexCount = vertices == null ? count : vertices.length / 8;
        }

        /** Compatibility view for tools; renderers consume passes without merging them. */
        public float[] vertices() {
            float[] result = vertices;
            if (result == null) {
                synchronized (this) {
                    result = vertices;
                    if (result == null) {
                        result = new float[vertexCount * 8];
                        int offset = 0;
                        for (Pass pass : passes) {
                            System.arraycopy(
                                    pass.vertices(), 0, result, offset, pass.vertices().length);
                            offset += pass.vertices().length;
                        }
                        vertices = result;
                    }
                }
            }
            return result;
        }

        public List<Pass> passes() {
            return passes;
        }

        public Map<String, Matrix4f> locators() {
            return locators;
        }

        public Map<String, Matrix4f> equipmentLocators() {
            return equipmentLocators;
        }

        public Set<String> hiddenLocators() {
            return hiddenLocators;
        }

        public Map<String, Matrix4f> swordLocators() {
            return swordLocators;
        }

        public int vertexCount() {
            return vertexCount;
        }
    }

    private final RawYsmModel raw;
    private final List<RawBone> bones;
    private final int[] parents;
    private final int[] parts;
    private final int headBone;
    private final Matrix4f[] matrices;
    private final Matrix4f[] equipmentMatrices;
    private final boolean[] hiddenChildren;
    private final int[] bufferSizes = new int[8];
    private final boolean[] renderBones;
    private final Matrix4f[] geometryMatrices;
    private BakedBatch[][] batches;

    private static final class BakedBatch {
        final int material;
        final float[] vertices;
        float[] previousOutput;
        int previousOffset;

        BakedBatch(int material, float[] vertices) {
            this.material = material;
            this.vertices = vertices;
        }
    }

    public record Pass(boolean glow, boolean translucent, boolean cull, float[] vertices) {}

    private final List<RuntimeBone> runtimeBones;
    private final LocalRuntime runtime;
    private final IdentityHashMap<RawCube, Boolean> culling = new IdentityHashMap<>();
    private final Set<RawFace> invisible = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RawFace> opaque = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean translucent;
    private String preparedTexture;
    private final int vertexCount;
    private final Map<String, String> locatorAliases;
    private Map<String, YsmPose> poseOverrides = Map.of();

    public void poseOverrides(Map<String, YsmPose> poses) {
        poseOverrides = Map.copyOf(poses);
    }

    public static LocalYsmModel load(Path source) throws Exception {
        RawYsmModel raw;
        if (Files.isDirectory(source)
                || source.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (var reader = new YSMFolderDeserializer(source)) {
                raw = reader.deserialize();
            }
        } else {
            long size = Files.size(source);
            if (size > 64L * 1024 * 1024) throw new IOException("Model file exceeds 64 MiB");
            byte[] file = Files.readAllBytes(source);
            try (var reader = new YSMBinaryDeserializer(YsmCrypt.decryptYsmFile(file))) {
                raw = reader.deserializeKeepOpen();
                reader.parseYSMFooter(raw);
            }
        }
        return new LocalYsmModel(raw);
    }

    LocalYsmModel(RawYsmModel raw) {
        this(raw, false);
    }

    private LocalYsmModel(RawYsmModel raw, boolean firstPerson) {
        this(raw, firstPerson ? "fp.arm" : "player");
    }

    private LocalYsmModel(RawYsmModel raw, String domain) {
        this.raw = Objects.requireNonNull(raw);
        boolean firstPerson = domain.equals("fp.arm");
        RawGeometry geometry =
                firstPerson && raw.mainEntity.armModel != null
                        ? raw.mainEntity.armModel
                        : raw.mainEntity.mainModel;
        if (geometry == null || geometry.bones.isEmpty())
            throw new IllegalArgumentException("Model has no player geometry");
        var byName = new LinkedHashMap<String, RawBone>();
        for (RawBone bone : geometry.bones) {
            if (byName.put(bone.name, bone) != null)
                throw new IllegalArgumentException("Duplicate bone: " + bone.name);
        }
        bones = new ArrayList<>();
        var visiting = new HashSet<String>();
        var visited = new HashSet<String>();
        for (RawBone bone : byName.values()) sort(bone, byName, visiting, visited);
        parents = new int[bones.size()];
        parts = new int[bones.size()];
        matrices = new Matrix4f[bones.size()];
        equipmentMatrices = new Matrix4f[bones.size()];
        hiddenChildren = new boolean[bones.size()];
        renderBones = new boolean[bones.size()];
        geometryMatrices = new Matrix4f[bones.size()];
        var indices = new HashMap<String, Integer>();
        int vertices = 0;
        for (int i = 0; i < bones.size(); i++) {
            RawBone bone = bones.get(i);
            parents[i] = indices.getOrDefault(bone.parentName, -1);
            indices.put(bone.name, i);
            String normalized = YsmLocators.normalize(bone.name);
            parts[i] =
                    normalized.equals("leftarm")
                            ? 1
                            : normalized.equals("rightarm")
                                    ? 2
                                    : parents[i] >= 0 ? parts[parents[i]] : 0;
            matrices[i] = new Matrix4f();
            equipmentMatrices[i] = new Matrix4f();
            for (RawCube cube : bone.cubes)
                vertices = Math.addExact(vertices, Math.multiplyExact(cube.faces.size(), 4));
        }
        if (vertices > 2_000_000) throw new IllegalArgumentException("Model geometry is too large");
        vertexCount = vertices;
        locatorAliases = YsmLocators.aliases(indices.keySet());
        runtimeBones = bones.stream().map(RuntimeBone::new).toList();
        runtime = new LocalRuntime(raw, runtimeBones, domain);
        String head =
                domain.equals("player")
                        ? com.blanoir.moons.ysm.YsmLocators.find(
                                runtime.bones().keySet(), "Head", "AllHead", "VanillaHead")
                        : null;
        int headIndex = -1;
        for (int i = 0; i < bones.size(); i++) if (bones.get(i).name.equals(head)) headIndex = i;
        headBone = headIndex;
        bakeGeometry();
    }

    private void sort(
            RawBone bone, Map<String, RawBone> byName, Set<String> visiting, Set<String> visited) {
        if (visited.contains(bone.name)) return;
        if (!visiting.add(bone.name))
            throw new IllegalArgumentException("Cyclic bone hierarchy: " + bone.name);
        RawBone parent = byName.get(bone.parentName);
        if (parent != null) sort(parent, byName, visiting, visited);
        visiting.remove(bone.name);
        visited.add(bone.name);
        bones.add(bone);
    }

    public String name() {
        return raw.metadata.name;
    }

    public int boneCount() {
        return bones.size();
    }

    public Set<String> animations() {
        return runtime.animationNames();
    }

    public Texture texture(String selected) {
        var textures = raw.mainEntity.textures;
        RawTexture texture = textures.get(selected);
        if (texture == null) texture = textures.get(raw.properties.defaultTexture);
        if (texture == null)
            texture =
                    textures.values().stream()
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalArgumentException("Model has no texture"));
        if (texture.data == null || texture.data.length == 0)
            throw new IllegalArgumentException("Empty model texture");
        return new Texture(
                texture.name, texture.data, texture.imageFormat, texture.width, texture.height);
    }

    public LocalYsmModel firstPersonModel() {
        return new LocalYsmModel(raw, true);
    }

    /** Sub-entity definitions share immutable assets; every instance owns its controller state. */
    public Set<String> subEntityIds(boolean projectile) {
        return Collections.unmodifiableSet((projectile ? raw.projectiles : raw.vehicles).keySet());
    }

    public String matchingSubEntity(boolean projectile, String entityId) {
        for (var entry : (projectile ? raw.projectiles : raw.vehicles).entrySet()) {
            RawSubEntity sub = entry.getValue();
            if (sub.matchIds != null && Arrays.asList(sub.matchIds).contains(entityId))
                return entry.getKey();
        }
        return null;
    }

    public LocalYsmModel subEntity(boolean projectile, String id) {
        RawSubEntity sub = (projectile ? raw.projectiles : raw.vehicles).get(id);
        if (sub == null) throw new IllegalArgumentException("Unknown sub-entity: " + id);
        RawYsmModel view = new RawYsmModel();
        view.metadata = raw.metadata;
        view.properties = raw.properties;
        view.soundFiles = raw.soundFiles;
        view.functionFiles = raw.functionFiles;
        view.languageFiles = raw.languageFiles;
        view.mainEntity.mainModel = sub.model;
        view.mainEntity.animationFiles = sub.animationFiles;
        view.mainEntity.animationControllerFiles = sub.animationControllerFiles;
        view.mainEntity.textures = sub.textures.isEmpty() ? raw.mainEntity.textures : sub.textures;
        return new LocalYsmModel(view, projectile ? "projectile" : "vehicle");
    }

    public String translate(String locale, String key, String fallback) {
        RawLanguageFile language = raw.languageFiles.get(locale.toLowerCase(Locale.ROOT));
        if (language != null && language.data.containsKey(key)) return language.data.get(key);
        language = raw.languageFiles.get("en_us");
        return language != null
                ? language.data.getOrDefault(key, Objects.toString(fallback, ""))
                : Objects.toString(fallback, "");
    }

    public boolean handlesFallFlyingPitch() {
        var animation = runtime.getAnimation("elytra_fly");
        if (animation == null) return false;
        for (var track : animation.boneAnimations) {
            if (track.rotationKeyFrames.isEmpty()) continue;
            for (int i = 0; i < bones.size(); i++)
                if (parents[i] < 0 && bones.get(i).name.equals(track.boneName)) return true;
        }
        return false;
    }

    public LocalRuntime runtime() {
        return runtime;
    }

    public RawProperties properties() {
        return raw.properties;
    }

    public Set<String> textures() {
        return Collections.unmodifiableSet(raw.mainEntity.textures.keySet());
    }

    public Map<String, RawDataFile> sounds() {
        return Collections.unmodifiableMap(raw.soundFiles);
    }

    public void resetAnimation() {
        runtime.reset();
    }

    public void close() {
        runtime.close();
    }

    public void prepareTexture(String selected) throws IOException {
        Texture texture = texture(selected);
        if (texture.name().equals(preparedTexture)) return;
        var pixels = YsmTextureDecoder.decode(texture);
        try {
            prepareTexture(texture.name(), pixels);
        } finally {
            pixels.flush();
        }
    }

    void prepareTexture(String selected, java.awt.image.BufferedImage pixels) {
        Texture texture = texture(selected);
        if (texture.name().equals(preparedTexture)) return;
        var scanner = new TextureAlphaAnalyzer(new java.awt.image.BufferedImage[] {pixels}, 1);
        invisible.clear();
        culling.clear();
        opaque.clear();
        for (RawBone bone : bones) {
            boolean force = raw.properties.allCutout;
            for (RawCube cube : bone.cubes) {
                List<RawFace> visible = new ArrayList<>();
                boolean transparent = false;
                for (RawFace face : cube.faces) {
                    int state = scanner.scan(face);
                    if (state == TextureAlphaAnalyzer.STATE_INVISIBLE) {
                        invisible.add(face);
                        continue;
                    }
                    transparent |= state == TextureAlphaAnalyzer.STATE_TRANSLUCENT;
                    // YSM DynamicTexture uses nearest sampling in every supported game version.
                    if (state == TextureAlphaAnalyzer.STATE_OPAQUE) opaque.add(face);
                    visible.add(face);
                    Vector3f e1 =
                            new Vector3f(face.positions[1]).sub(new Vector3f(face.positions[0]));
                    Vector3f e2 =
                            new Vector3f(face.positions[2]).sub(new Vector3f(face.positions[1]));
                    if (e1.cross(e2).dot(new Vector3f(face.normal)) < 0) force = true;
                }
                boolean flat = !visible.isEmpty();
                if (flat) {
                    RawFace base = visible.getFirst();
                    for (RawFace face : visible)
                        for (float[] vertex : face.positions)
                            if (Math.abs(
                                            new Vector3f(vertex)
                                                    .sub(new Vector3f(base.positions[0]))
                                                    .dot(new Vector3f(base.normal)))
                                    > 1e-3f) flat = false;
                }
                culling.put(
                        cube,
                        force
                                || (!transparent
                                        && ((flat && visible.size() > 1) || visible.size() >= 5)));
            }
        }
        translucent = scanner.getResults()[0] && !raw.properties.allCutout;
        bakeGeometry();
        preparedTexture = texture.name();
        runtime.textureName(preparedTexture);
    }

    /** Visibility and material routing depend on the texture, not the current animation frame. */
    private void bakeGeometry() {
        batches = new BakedBatch[bones.size()][];
        Arrays.fill(geometryMatrices, null);
        for (int b = 0; b < bones.size(); b++) {
            RawBone bone = bones.get(b);
            var groups = new TreeMap<Integer, List<RawFace>>();
            boolean glow = bone.name.startsWith("ysmGlow");
            for (RawCube cube : bone.cubes) {
                int base = (glow ? 4 : 0) | (culling.getOrDefault(cube, true) ? 1 : 0);
                for (RawFace face : cube.faces) {
                    if (invisible.contains(face)) continue;
                    boolean blend = (glow || translucent) && !opaque.contains(face);
                    groups.computeIfAbsent(base | (blend ? 2 : 0), unused -> new ArrayList<>())
                            .add(face);
                }
            }
            var baked = new ArrayList<BakedBatch>(groups.size());
            for (var entry : groups.entrySet()) {
                float[] vertices = new float[entry.getValue().size() * 32];
                int offset = 0;
                for (RawFace face : entry.getValue())
                    for (int v = 0; v < 4; v++) {
                        vertices[offset++] = face.positions[v][0];
                        vertices[offset++] = face.positions[v][1];
                        vertices[offset++] = face.positions[v][2];
                        vertices[offset++] = face.u[v];
                        vertices[offset++] = face.v[v];
                        vertices[offset++] = face.normal[0];
                        vertices[offset++] = face.normal[1];
                        vertices[offset++] = face.normal[2];
                    }
                baked.add(new BakedBatch(entry.getKey(), vertices));
            }
            batches[b] = baked.toArray(BakedBatch[]::new);
        }
    }

    public Mesh frame(double seconds, Map<String, ?> queries, String requestedAnimation) {
        return frame(seconds, queries, requestedAnimation, 0);
    }

    public Mesh frame(
            double seconds, Map<String, ?> queries, String requestedAnimation, int partMask) {
        return frame(seconds, queries, requestedAnimation, partMask, true);
    }

    /** Advance body controllers and absolute pivots without tessellating an unseen body. */
    public void updatePose(double seconds, Map<String, ?> queries, String requestedAnimation) {
        frame(seconds, queries, requestedAnimation, 0, false);
    }

    private Mesh frame(
            double seconds,
            Map<String, ?> queries,
            String requestedAnimation,
            int partMask,
            boolean geometry) {
        runtime.update(seconds, queries, requestedAnimation);
        var locators = new LinkedHashMap<String, Matrix4f>();
        var equipmentLocators = new LinkedHashMap<String, Matrix4f>();
        var hiddenLocators = new HashSet<String>();
        var swordLocators = new LinkedHashMap<String, Matrix4f>();
        Arrays.fill(bufferSizes, 0);
        for (int i = 0; i < bones.size(); i++) {
            RawBone bone = bones.get(i);
            RuntimeBone pose = runtimeBones.get(i);
            YsmPose adjustment = poseOverrides.getOrDefault(bone.name, YsmPose.IDENTITY);
            float px = pose.position.x + (float) adjustment.x(),
                    py = pose.position.y + (float) adjustment.y(),
                    pz = pose.position.z + (float) adjustment.z();
            float rx = pose.rotation.x + (float) Math.toRadians(adjustment.pitch()),
                    ry = pose.rotation.y + (float) Math.toRadians(adjustment.yaw()),
                    rz = pose.rotation.z + (float) Math.toRadians(adjustment.roll());
            // LivingAnimatable adds view tracking after animation evaluation. Keep that
            // offset in this frame's matrices so repeated draws never accumulate rotation.
            if (i == headBone) {
                // Geometry faces -Z with +Y up; Minecraft pitch/yaw use the opposite signs.
                rx -= (float) Math.toRadians(runtime.number("head_x_rotation"));
                ry -= (float) Math.toRadians(runtime.number("head_y_rotation"));
            }
            float sx = pose.scale.x * (float) adjustment.scaleX(),
                    sy = pose.scale.y * (float) adjustment.scaleY(),
                    sz = pose.scale.z * (float) adjustment.scaleZ();
            Matrix4f matrix = matrices[i];
            if (parents[i] < 0) matrix.identity();
            else matrix.set(matrices[parents[i]]);
            matrix.translate(
                            (bone.pivot[0] - px) / 16f,
                            (bone.pivot[1] + py) / 16f,
                            (bone.pivot[2] + pz) / 16f)
                    .rotateZ(rz)
                    .rotateY(ry)
                    .rotateX(rx);
            boolean inherited = parents[i] >= 0 && hiddenChildren[parents[i]];
            boolean zero = sx == 0 || sy == 0 || sz == 0;
            if (geometry && !inherited && !adjustment.hidden())
                swordLocators.put(
                        bone.name,
                        new Matrix4f(matrix).scale(zero ? 1 : sx, zero ? 1 : sy, zero ? 1 : sz));
            matrix.scale(sx, sy, sz);
            pose.absolutePivot.set(-matrix.m30() * 16, matrix.m31() * 16, matrix.m32() * 16);
            if (geometry) locators.put(bone.name, new Matrix4f(matrix));
            matrix.translate(-bone.pivot[0] / 16f, -bone.pivot[1] / 16f, -bone.pivot[2] / 16f);
            hiddenChildren[i] =
                    inherited || pose.childBonesAreHiddenToo() || zero || adjustment.hidden();
            if (!geometry) continue;
            Matrix4f equipment = equipmentMatrices[i];
            if (parents[i] < 0) equipment.identity();
            else equipment.set(equipmentMatrices[parents[i]]);
            equipment
                    .translate(
                            (bone.pivot[0] - px) / 16f,
                            (bone.pivot[1] + py) / 16f,
                            (bone.pivot[2] + pz) / 16f)
                    .rotateZ(rz)
                    .rotateY(ry)
                    .rotateX(rx);
            equipmentLocators.put(bone.name, new Matrix4f(equipment));
            equipment.translate(-bone.pivot[0] / 16f, -bone.pivot[1] / 16f, -bone.pivot[2] / 16f);
            if (inherited || pose.isHidden() || zero || adjustment.hidden())
                hiddenLocators.add(bone.name);
            renderBones[i] =
                    !inherited
                            && !pose.isHidden()
                            && !zero
                            && !adjustment.hidden()
                            && (partMask == 0 || parts[i] == partMask);
            for (BakedBatch batch : batches[i])
                if (renderBones[i]) bufferSizes[batch.material] += batch.vertices.length;
                else batch.previousOutput = null;
        }
        if (!geometry) return null;
        var passes = new ArrayList<Pass>(8);
        float[][] output = new float[8][];
        for (int i = 0; i < output.length; i++)
            if (bufferSizes[i] != 0) {
                output[i] = new float[bufferSizes[i]];
                passes.add(new Pass((i & 4) != 0, (i & 2) != 0, (i & 1) != 0, output[i]));
            }
        Arrays.fill(bufferSizes, 0);
        Vector3f position = new Vector3f(), normal = new Vector3f();
        Matrix3f normalMatrix = new Matrix3f();
        for (int b = 0; b < bones.size(); b++) {
            if (!renderBones[b] || batches[b].length == 0) continue;
            Matrix4f matrix = matrices[b];
            boolean unchanged = matrix.equals(geometryMatrices[b]);
            if (!unchanged) matrix.normal(normalMatrix);
            for (BakedBatch batch : batches[b]) {
                float[] vertices = output[batch.material], source = batch.vertices;
                int offset = bufferSizes[batch.material];
                if (unchanged && batch.previousOutput != null) {
                    System.arraycopy(
                            batch.previousOutput,
                            batch.previousOffset,
                            vertices,
                            offset,
                            source.length);
                } else {
                    // A hidden batch may be restored without changing its bone matrix.
                    if (unchanged) matrix.normal(normalMatrix);
                    for (int face = 0; face < source.length; face += 32) {
                        normal.set(source[face + 5], source[face + 6], source[face + 7])
                                .mul(normalMatrix);
                        if (normal.isFinite() && normal.lengthSquared() > 1e-12f)
                            normal.normalize();
                        else normal.set(0, 1, 0);
                        for (int v = face; v < face + 32; v += 8) {
                            position.set(source[v], source[v + 1], source[v + 2])
                                    .mulPosition(matrix);
                            if (!position.isFinite())
                                throw new IllegalArgumentException("Non-finite model vertex");
                            vertices[offset++] = position.x;
                            vertices[offset++] = position.y;
                            vertices[offset++] = position.z;
                            vertices[offset++] = source[v + 3];
                            vertices[offset++] = source[v + 4];
                            vertices[offset++] = normal.x;
                            vertices[offset++] = normal.y;
                            vertices[offset++] = normal.z;
                        }
                    }
                }
                batch.previousOutput = vertices;
                batch.previousOffset = bufferSizes[batch.material];
                bufferSizes[batch.material] += source.length;
            }
            if (geometryMatrices[b] == null) geometryMatrices[b] = new Matrix4f(matrix);
            else geometryMatrices[b].set(matrix);
        }
        float[] all = passes.size() == 1 ? passes.getFirst().vertices() : null;
        locatorAliases.forEach(
                (alias, name) -> {
                    locators.putIfAbsent(alias, locators.get(name));
                    equipmentLocators.putIfAbsent(alias, equipmentLocators.get(name));
                    if (hiddenLocators.contains(name)) hiddenLocators.add(alias);
                    if (swordLocators.containsKey(name))
                        swordLocators.putIfAbsent(alias, swordLocators.get(name));
                });
        return new Mesh(
                all,
                List.copyOf(passes),
                Collections.unmodifiableMap(locators),
                Collections.unmodifiableMap(equipmentLocators),
                Set.copyOf(hiddenLocators),
                Collections.unmodifiableMap(swordLocators));
    }
}
