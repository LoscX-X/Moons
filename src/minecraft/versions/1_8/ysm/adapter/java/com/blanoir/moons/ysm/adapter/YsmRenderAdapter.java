package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.api.YsmStudio;
import com.blanoir.moons.ysm.LocalYsmModel;
import com.blanoir.moons.ysm.YsmSession;
import com.blanoir.moons.ysm.YsmTextureDecoder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.io.*;
import java.util.*;

import javax.imageio.ImageIO;

/** Real 1.8 player renderer; session, studio and model engine retain the shared implementation. */
final class YsmRenderAdapter implements AutoCloseable {
    private final LocalYsmModel model;
    private ResourceLocation texture;
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
        this.settings = settings;
        observations = new YsmObservations(model.runtime()::diagnostic);
        playerFrame = new YsmPlayerFrame(observations);
        YsmEffects candidate = null;
        try {
            model.runtime().queries(observations::query);
            arms = prepared.firstPerson();
            arms.runtime().queries(observations::query);
            candidate = new YsmEffects(model);
            model.runtime().effects(candidate);
            arms.runtime().effects(candidate);
            layers = new YsmEquipmentLayers(model);
            subEntities = new YsmSubEntities(model, observations);
            texture = nextTexture();
            upload(texture, prepared.texturePng());
        } catch (Throwable error) {
            if (candidate != null) candidate.close();
            throw error;
        }
        session = prepared;
        effects = candidate;
    }

    private static ResourceLocation nextTexture() {
        return new ResourceLocation(
                "moons", "ysm/" + UUID.randomUUID().toString().replace("-", ""));
    }

    static void upload(ResourceLocation id, byte[] png) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("Unsupported YSM texture");
        var texture = new DynamicTexture(image);
        try {
            Minecraft.getMinecraft().getTextureManager().loadTexture(id, texture);
        } catch (Throwable failure) {
            texture.deleteGlTexture();
            throw failure;
        }
    }

    static byte[] decodeTexture(LocalYsmModel.Texture texture) throws IOException {
        return YsmTextureDecoder.toPng(texture);
    }

    boolean submit(Object[] args) {
        if (closed
                || args.length != 6
                || !(args[0] instanceof EntityPlayerSP player)
                || player != Minecraft.getMinecraft().thePlayer
                || player.isInvisible()
                || player.isSpectator()) return false;
        float partial = ((Number) args[5]).floatValue();
        YsmRenderState state = new YsmRenderState(player, partial);
        var frame = playerFrame.sample(player);
        LocalYsmModel.Mesh mesh = session.frame(frame.seconds(), frame.queries());
        Matrix4f transform = YsmPlayerTransform.apply(player, state, settings.scale());
        var cache = YsmGlStateCache.capture();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(
                    ((Number) args[1]).doubleValue(),
                    ((Number) args[2]).doubleValue(),
                    ((Number) args[3]).doubleValue());
            if (model.properties().renderLayersFirst) layers.submit(mesh, player, transform);
            renderTypes.draw(texture, mesh, transform, state.lightCoords, state.hasRedOverlay);
            if (!model.properties().renderLayersFirst) layers.submit(mesh, player, transform);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            cache.close();
        }
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
        Minecraft.getMinecraft().getTextureManager().deleteTexture(texture);
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
        ResourceLocation next = nextTexture();
        boolean uploaded = false;
        try {
            if (!model.textures().contains(name))
                throw new IllegalArgumentException("Unknown texture");
            session.texture(name);
            upload(next, session.texturePng());
            uploaded = true;
        } catch (Throwable error) {
            if (uploaded) Minecraft.getMinecraft().getTextureManager().deleteTexture(next);
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
        ResourceLocation old = texture;
        texture = next;
        Minecraft.getMinecraft().getTextureManager().deleteTexture(old);
    }

    private Map<String, String> controllerStates() {
        Map<String, String> states = new LinkedHashMap<>(model.runtime().controllerStates());
        states.putAll(arms.runtime().controllerStates());
        return states;
    }

    YsmStudio.Snapshot studioSnapshot(String id) {
        String locale =
                Minecraft.getMinecraft()
                        .getLanguageManager()
                        .getCurrentLanguage()
                        .getLanguageCode();
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

    boolean submitFirstPerson(Object owner, Object[] args, boolean left) {
        if (closed
                || args.length != 1
                || !(args[0] instanceof EntityPlayerSP player)
                || player != Minecraft.getMinecraft().thePlayer
                || player.isInvisible()
                || player.isSpectator()) return false;
        var frame = playerFrame.sample(player);
        var queries = frame.queries();
        session.updatePose(frame.seconds(), queries);
        model.runtime().variables().forEach((name, value) -> arms.runtime().variable(name, value));
        arms.poseOverrides(session.pose().values());
        LocalYsmModel.Mesh mesh =
                arms.frame(session.time(), queries, session.firstPersonAnimation(), left ? 1 : 2);
        if (mesh.vertexCount() == 0) return false;
        Matrix4f transform =
                new Matrix4f().translate(left ? .25f : -.25f, 1.8f, 0).scale(-1, -1, 1);
        renderTypes.draw(texture, mesh, transform, player.getBrightnessForRender(1), false);
        return true;
    }
}
