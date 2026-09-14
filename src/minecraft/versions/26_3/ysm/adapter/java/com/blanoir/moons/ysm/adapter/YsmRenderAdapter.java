package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.api.YsmStudio;
import com.blanoir.moons.ysm.LocalYsmModel;
import com.blanoir.moons.ysm.YsmSession;
import com.blanoir.moons.ysm.YsmTextureDecoder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.io.*;
import java.util.*;

/** Minecraft 26.3-rc-3 rendering and observations. This class belongs to this version only. */
final class YsmRenderAdapter implements AutoCloseable {
    private final LocalYsmModel model;
    private final boolean handlesFallFlyingPitch;
    private Identifier texture;
    private final YsmSession session;
    private final LocalYsmModel arms;
    private final YsmEffects effects;
    private final YsmEquipmentLayers layers;
    private final YsmSubEntities subEntities;
    private final YsmRenderTypes renderTypes = new YsmRenderTypes();
    private final YsmObservations observations;
    private final YsmPlayerFrame playerFrame;
    private String editError = "";
    private long nextSave;
    private final YsmModule.Settings settings;
    private boolean closed;

    YsmRenderAdapter(YsmSession prepared, YsmModule.Settings settings) throws IOException {
        this.model = prepared.model();
        handlesFallFlyingPitch = model.handlesFallFlyingPitch();
        observations = new YsmObservations(model.runtime()::diagnostic);
        playerFrame = new YsmPlayerFrame(observations);
        this.settings = settings;
        YsmSession candidateSession = prepared;
        LocalYsmModel candidateArms = null;
        YsmEffects candidateEffects = null;
        Identifier candidateTexture =
                Identifier.fromNamespaceAndPath(
                        "moons", "ysm/" + UUID.randomUUID().toString().replace("-", ""));
        try {
            model.runtime().queries(observations::query);
            candidateArms = candidateSession.firstPerson();
            candidateArms.runtime().queries(observations::query);
            candidateEffects = new YsmEffects(model);
            model.runtime().effects(candidateEffects);
            candidateArms.runtime().effects(candidateEffects);
            layers = new YsmEquipmentLayers(model);
            subEntities = new YsmSubEntities(model, observations);
            upload(candidateTexture, candidateSession.texturePng());
        } catch (Throwable failure) {
            if (candidateEffects != null) candidateEffects.close();
            throw failure;
        }
        session = candidateSession;
        arms = candidateArms;
        effects = candidateEffects;
        texture = candidateTexture;
    }

    static void upload(Identifier id, byte[] png) throws IOException {
        NativeImage pixels = NativeImage.read(new ByteArrayInputStream(png));
        DynamicTexture uploaded = null;
        try {
            uploaded = new DynamicTexture(() -> "Moons YSM", pixels);
            Minecraft.getInstance().getTextureManager().register(id, uploaded);
        } catch (Throwable failure) {
            if (uploaded != null) uploaded.close();
            else pixels.close();
            throw failure;
        }
    }

    static byte[] decodeTexture(LocalYsmModel.Texture texture) throws IOException {
        return YsmTextureDecoder.toPng(texture);
    }

    boolean submit(Object[] arguments) {
        if (closed
                || arguments.length != 4
                || !(arguments[0] instanceof AvatarRenderState state)
                || !(arguments[1] instanceof PoseStack pose)
                || !(arguments[2] instanceof SubmitNodeCollector collector)) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || state.id != player.getId() || state.isInvisible || state.isSpectator)
            return false;
        var frame = playerFrame.sample(player);
        LocalYsmModel.Mesh mesh = session.frame(frame.seconds(), frame.queries());
        int light = state.lightCoords;
        int overlay = OverlayTexture.pack(0, state.hasRedOverlay);
        pose.pushPose();
        try {
            YsmPlayerTransform.apply(player, state, pose, settings.scale(), handlesFallFlyingPitch);
            // Capture only the immutable vertex snapshot; the render queue never retains the model
            // engine.
            if (model.properties().renderLayersFirst)
                layers.submit(mesh, player, state, pose, collector, light, overlay);
            for (LocalYsmModel.Pass pass : mesh.passes()) {
                float[] vertices = pass.vertices();
                collector.submitCustomGeometry(
                        pose,
                        renderTypes.get(texture, pass),
                        (transform, consumer) ->
                                YsmVertices.emit(
                                        transform,
                                        consumer,
                                        vertices,
                                        overlay,
                                        pass.glow() ? 0xF000F0 : light));
            }
            if (!model.properties().renderLayersFirst)
                layers.submit(mesh, player, state, pose, collector, light, overlay);
        } finally {
            pose.popPose();
        }
        if (arguments[3]
                        instanceof
                        net.minecraft.client.renderer.state.level.CameraRenderState camera
                && state.nameTag != null
                && state.nameTagAttachment != null)
            collector.submitNameTag(
                    pose,
                    state.nameTagAttachment,
                    0,
                    state.nameTag,
                    !state.isDiscrete,
                    state.lightCoords,
                    camera);
        return true;
    }

    void capture(Object owner, Object[] args) {
        subEntities.capture(owner, args);
    }

    boolean submitSubEntity(Object[] args) {
        return !closed && subEntities.submit(args);
    }

    void reset() {
        playerFrame.reset();
        subEntities.close();
        effects.stopAll();
        session.resetWorld();
        arms.resetAnimation();
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            session.save();
        } catch (IOException error) {
            reportEditError(error);
        }
        subEntities.close();
        effects.close();
        session.close();
        Minecraft.getInstance().getTextureManager().release(texture);
    }

    YsmSession session() {
        return session;
    }

    void reportEditError(Throwable error) {
        editError = Objects.toString(error.getMessage(), error.toString());
    }

    void tickStudio() {
        subEntities.prune();
        if (System.nanoTime() > nextSave) {
            nextSave = System.nanoTime() + 1_000_000_000L;
            try {
                session.save();
            } catch (IOException error) {
                reportEditError(error);
            }
        }
    }

    void changeTexture(String name) {
        String previous = session.texture();
        if (previous.equals(name)) return;
        Identifier next =
                Identifier.fromNamespaceAndPath(
                        "moons", "ysm/" + UUID.randomUUID().toString().replace("-", ""));
        boolean uploaded = false;
        try {
            if (!model.textures().contains(name))
                throw new IllegalArgumentException("Unknown texture");
            session.texture(name);
            upload(next, session.texturePng());
            uploaded = true;
        } catch (Throwable error) {
            if (uploaded) Minecraft.getInstance().getTextureManager().release(next);
            try {
                session.texture(previous);
            } catch (Throwable rollback) {
                error.addSuppressed(rollback);
            }
            if (error instanceof IOException io) throw new UncheckedIOException(io);
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException(error);
        }
        Identifier old = texture;
        texture = next;
        Minecraft.getInstance().getTextureManager().release(old);
    }

    private Map<String, String> controllerStates() {
        Map<String, String> states = new LinkedHashMap<>(model.runtime().controllerStates());
        states.putAll(arms.runtime().controllerStates());
        return states;
    }

    YsmStudio.Snapshot studioSnapshot(String id) {
        String locale = Minecraft.getInstance().getLanguageManager().getSelected();
        var params =
                session.parameters().stream()
                        .map(
                                p -> {
                                    var f = p.form();
                                    int split = p.id().lastIndexOf('/');
                                    String group = p.id().substring(0, split);
                                    String key = "properties.extra_animation_buttons." + group;
                                    String formKey =
                                            key + ".config_forms." + p.id().substring(split + 1);
                                    Map<String, String> labels = new LinkedHashMap<>();
                                    int i = 0;
                                    for (String label : f.labels.keySet()) {
                                        labels.put(
                                                Integer.toString(i),
                                                model.translate(
                                                        locale, formKey + ".labels." + i, label));
                                        i++;
                                    }
                                    return new YsmStudio.Parameter(
                                            p.id(),
                                            model.translate(locale, key + ".name", p.group()),
                                            model.translate(locale, formKey + ".title", f.title),
                                            model.translate(
                                                    locale,
                                                    formKey + ".description",
                                                    f.description),
                                            f.type,
                                            f.min,
                                            f.max,
                                            f.step,
                                            labels);
                                })
                        .toList();
        var actions =
                session.actions().stream()
                        .map(
                                a ->
                                        new YsmStudio.Animation(
                                                a.id(),
                                                        model.translate(
                                                                locale,
                                                                (a.group().equals("Extra")
                                                                                ? "properties.extra_animation."
                                                                                : "properties.extra_animation_classify."
                                                                                        + a.group()
                                                                                        + ".")
                                                                        + a.id(),
                                                                a.label()),
                                                a.group(), a.duration()))
                        .toList();
        Map<String, YsmStudio.BonePose> poses = new LinkedHashMap<>();
        session.pose()
                .values()
                .forEach(
                        (bone, p) ->
                                poses.put(
                                        bone,
                                        new YsmStudio.BonePose(
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
        return new YsmStudio.Snapshot(
                id,
                params,
                session.values(),
                actions,
                session.animation(),
                session.time(),
                session.speed(),
                session.paused(),
                List.copyOf(model.textures()),
                session.texture(),
                List.copyOf(model.runtime().bones().keySet()),
                controllerStates(),
                model.runtime().variables(),
                model.runtime().diagnostics(),
                editError.isEmpty() ? session.result() : editError,
                poses);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    boolean submitFirstPerson(Object owner, Object[] args, boolean left) {
        if (closed
                || args.length != 5
                || !(args[0] instanceof PoseStack pose)
                || !(args[1] instanceof SubmitNodeCollector collector)
                || !(owner
                        instanceof
                        net.minecraft.client.renderer.entity.player.AvatarRenderer))
            return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isInvisible() || player.isSpectator()) return false;
        var frame = playerFrame.sample(player);
        var queries = frame.queries();
        // Keep third-person state and authored parameters current even when no body pass is
        // rendered.
        session.updatePose(frame.seconds(), queries);
        model.runtime().variables().forEach((name, value) -> arms.runtime().variable(name, value));
        arms.poseOverrides(session.pose().values());
        LocalYsmModel.Mesh mesh =
                arms.frame(session.time(), queries, session.firstPersonAnimation(), left ? 1 : 2);
        if (mesh.vertexCount() == 0) return false;
        int light = ((Number) args[2]).intValue();
        pose.pushPose();
        try {
            pose.translate(left ? .25 : -.25, 1.8, 0);
            pose.scale(-1, -1, 1);
            for (LocalYsmModel.Pass pass : mesh.passes()) {
                float[] vertices = pass.vertices();
                collector.submitCustomGeometry(
                        pose,
                        renderTypes.get(texture, pass),
                        (transform, consumer) ->
                                YsmVertices.emit(
                                        transform,
                                        consumer,
                                        vertices,
                                        OverlayTexture.NO_OVERLAY,
                                        pass.glow() ? 0xF000F0 : light));
            }
        } finally {
            pose.popPose();
        }
        return true;
    }
}
