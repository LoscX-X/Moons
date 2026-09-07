package com.blanoir.moons.client.access;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.QuadInstance;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Map;

/** Centralized private Minecraft access used by the standalone feature host. */
public final class GameAccess {
    private static final VarHandle KEY_MAP;
    private static final VarHandle BOUND_KEY;
    private static final MethodHandle MOUSE_BUTTON;
    private static final MethodHandle START_ATTACK;
    private static final MethodHandle START_USE_ITEM;
    private static final VarHandle RIGHT_CLICK_DELAY;
    private static final VarHandle RENDER_NAME;
    private static final VarHandle RENDER_SETUP;
    private static final MethodHandle CREATE_RENDER_TYPE;
    private static final MethodHandle CREATE_RENDER_SETUP;
    private static final Map<String, VarHandle> SETUP_FIELDS;
    private static final VarHandle NO_JUMP_DELAY;
    private static final VarHandle MOVE_VECTOR;
    private static final VarHandle BLOCK_STATE_PREDICTION_HANDLER;
    private static final VarHandle QUAD_INSTANCE;
    private static final MethodHandle DISPATCH_INCOMING;
    private static final VarHandle ENTITY_EVENT_ID;
    private static final MethodHandle REGISTER_PIPELINE;
    private static final VarHandle DEBUG_FILLED_SNIPPET;
    private static final VarHandle TAB_HEADER;
    private static final VarHandle TAB_FOOTER;
    private static final VarHandle SUBMIT_NODE_STORAGE;

    static {
        try {
            MethodHandles.Lookup keyLookup =
                    MethodHandles.privateLookupIn(KeyMapping.class, MethodHandles.lookup());
            KEY_MAP = keyLookup.findStaticVarHandle(KeyMapping.class, "MAP", Map.class);
            BOUND_KEY = keyLookup.findVarHandle(KeyMapping.class, "key", InputConstants.Key.class);

            MethodHandles.Lookup mouseLookup =
                    MethodHandles.privateLookupIn(MouseHandler.class, MethodHandles.lookup());
            MOUSE_BUTTON =
                    mouseLookup.findVirtual(
                            MouseHandler.class,
                            "onButton",
                            java.lang.invoke.MethodType.methodType(
                                    void.class, long.class, MouseButtonInfo.class, int.class));

            START_ATTACK =
                    MethodHandles.privateLookupIn(Minecraft.class, MethodHandles.lookup())
                            .findVirtual(
                                    Minecraft.class,
                                    "startAttack",
                                    java.lang.invoke.MethodType.methodType(boolean.class));
            START_USE_ITEM =
                    MethodHandles.privateLookupIn(Minecraft.class, MethodHandles.lookup())
                            .findVirtual(
                                    Minecraft.class,
                                    "startUseItem",
                                    java.lang.invoke.MethodType.methodType(void.class));
            RIGHT_CLICK_DELAY =
                    MethodHandles.privateLookupIn(Minecraft.class, MethodHandles.lookup())
                            .findVarHandle(Minecraft.class, "rightClickDelay", int.class);

            MethodHandles.Lookup renderLookup =
                    MethodHandles.privateLookupIn(RenderType.class, MethodHandles.lookup());
            RENDER_NAME = renderLookup.findVarHandle(RenderType.class, "name", String.class);
            RENDER_SETUP = renderLookup.findVarHandle(RenderType.class, "state", RenderSetup.class);
            CREATE_RENDER_TYPE =
                    renderLookup.findStatic(
                            RenderType.class,
                            "create",
                            java.lang.invoke.MethodType.methodType(
                                    RenderType.class, String.class, RenderSetup.class));

            MethodHandles.Lookup setupLookup =
                    MethodHandles.privateLookupIn(RenderSetup.class, MethodHandles.lookup());
            CREATE_RENDER_SETUP =
                    setupLookup.findConstructor(
                            RenderSetup.class,
                            java.lang.invoke.MethodType.methodType(
                                    void.class,
                                    RenderPipeline.class,
                                    Map.class,
                                    boolean.class,
                                    boolean.class,
                                    LayeringTransform.class,
                                    OutputTarget.class,
                                    TextureTransform.class,
                                    RenderSetup.OutlineProperty.class,
                                    boolean.class,
                                    boolean.class,
                                    int.class));
            SETUP_FIELDS =
                    Map.ofEntries(
                            Map.entry(
                                    "pipeline",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class, "pipeline", RenderPipeline.class)),
                            Map.entry(
                                    "textures",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class, "textures", Map.class)),
                            Map.entry(
                                    "useLightmap",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class, "useLightmap", boolean.class)),
                            Map.entry(
                                    "useOverlay",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class, "useOverlay", boolean.class)),
                            Map.entry(
                                    "layeringTransform",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class,
                                            "layeringTransform",
                                            LayeringTransform.class)),
                            Map.entry(
                                    "textureTransform",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class,
                                            "textureTransform",
                                            TextureTransform.class)),
                            Map.entry(
                                    "outlineProperty",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class,
                                            "outlineProperty",
                                            RenderSetup.OutlineProperty.class)),
                            Map.entry(
                                    "affectsCrumbling",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class, "affectsCrumbling", boolean.class)),
                            Map.entry(
                                    "sortOnUpload",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class, "sortOnUpload", boolean.class)),
                            Map.entry(
                                    "bufferSize",
                                    setupLookup.findVarHandle(
                                            RenderSetup.class, "bufferSize", int.class)));

            NO_JUMP_DELAY =
                    MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup())
                            .findVarHandle(LivingEntity.class, "noJumpDelay", int.class);
            MOVE_VECTOR =
                    MethodHandles.privateLookupIn(ClientInput.class, MethodHandles.lookup())
                            .findVarHandle(ClientInput.class, "moveVector", Vec2.class);
            BLOCK_STATE_PREDICTION_HANDLER =
                    MethodHandles.privateLookupIn(ClientLevel.class, MethodHandles.lookup())
                            .findVarHandle(
                                    ClientLevel.class,
                                    "blockStatePredictionHandler",
                                    BlockStatePredictionHandler.class);
            QUAD_INSTANCE =
                    MethodHandles.privateLookupIn(ModelBlockRenderer.class, MethodHandles.lookup())
                            .findVarHandle(
                                    ModelBlockRenderer.class, "quadInstance", QuadInstance.class);
            DISPATCH_INCOMING =
                    MethodHandles.privateLookupIn(Connection.class, MethodHandles.lookup())
                            .findStatic(
                                    Connection.class,
                                    "genericsFtw",
                                    java.lang.invoke.MethodType.methodType(
                                            void.class, Packet.class, PacketListener.class));
            ENTITY_EVENT_ID =
                    MethodHandles.privateLookupIn(
                                    ClientboundEntityEventPacket.class, MethodHandles.lookup())
                            .findVarHandle(
                                    ClientboundEntityEventPacket.class, "entityId", int.class);
            MethodHandles.Lookup pipelinesLookup =
                    MethodHandles.privateLookupIn(RenderPipelines.class, MethodHandles.lookup());
            REGISTER_PIPELINE =
                    pipelinesLookup.findStatic(
                            RenderPipelines.class,
                            "register",
                            java.lang.invoke.MethodType.methodType(
                                    RenderPipeline.class, RenderPipeline.class));
            DEBUG_FILLED_SNIPPET =
                    pipelinesLookup.findStaticVarHandle(
                            RenderPipelines.class,
                            "DEBUG_FILLED_SNIPPET",
                            RenderPipeline.Snippet.class);
            MethodHandles.Lookup tabLookup =
                    MethodHandles.privateLookupIn(PlayerTabOverlay.class, MethodHandles.lookup());
            TAB_HEADER = tabLookup.findVarHandle(PlayerTabOverlay.class, "header", Component.class);
            TAB_FOOTER = tabLookup.findVarHandle(PlayerTabOverlay.class, "footer", Component.class);
            SUBMIT_NODE_STORAGE =
                    MethodHandles.privateLookupIn(LevelRenderer.class, MethodHandles.lookup())
                            .findVarHandle(
                                    LevelRenderer.class,
                                    "submitNodeStorage",
                                    SubmitNodeStorage.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private GameAccess() {}

    @SuppressWarnings("unchecked")
    public static Map<InputConstants.Key, List<KeyMapping>> keyMappings() {
        return (Map<InputConstants.Key, List<KeyMapping>>) KEY_MAP.get();
    }

    public static InputConstants.Key boundKey(KeyMapping mapping) {
        return (InputConstants.Key) BOUND_KEY.get(mapping);
    }

    public static void invokeMouseButton(
            MouseHandler handler, long window, MouseButtonInfo button, int action) {
        try {
            MOUSE_BUTTON.invokeExact(handler, window, button, action);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to invoke Minecraft mouse input", failure);
        }
    }

    public static void invokeStartAttack(Minecraft client) {
        try {
            START_ATTACK.invoke(client);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to invoke Minecraft attack", failure);
        }
    }

    /** Runs one vanilla right-click immediately in the current update phase. */
    public static void invokeStartUseItem(Minecraft client) {
        try {
            START_USE_ITEM.invokeExact(client);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to invoke Minecraft use item", failure);
        }
    }

    public static int rightClickDelay(Minecraft client) {
        return (int) RIGHT_CLICK_DELAY.get(client);
    }

    public static void rightClickDelay(Minecraft client, int ticks) {
        RIGHT_CLICK_DELAY.set(client, Math.max(0, ticks));
    }

    public static String renderTypeName(RenderType renderType) {
        return (String) RENDER_NAME.get(renderType);
    }

    @SuppressWarnings("unchecked")
    public static RenderType copyRenderType(
            RenderType base, String name, OutputTarget outputTarget) {
        try {
            RenderSetup setup = (RenderSetup) RENDER_SETUP.get(base);
            RenderSetup replacement =
                    (RenderSetup)
                            CREATE_RENDER_SETUP.invoke(
                                    field(setup, "pipeline"),
                                    field(setup, "textures"),
                                    field(setup, "useLightmap"),
                                    field(setup, "useOverlay"),
                                    field(setup, "layeringTransform"),
                                    outputTarget,
                                    field(setup, "textureTransform"),
                                    field(setup, "outlineProperty"),
                                    field(setup, "affectsCrumbling"),
                                    field(setup, "sortOnUpload"),
                                    field(setup, "bufferSize"));
            return (RenderType) CREATE_RENDER_TYPE.invoke(name, replacement);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to copy Minecraft render type", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(RenderSetup setup, String name) {
        return (T) SETUP_FIELDS.get(name).get(setup);
    }

    public static void clearJumpDelay(LivingEntity entity) {
        NO_JUMP_DELAY.set(entity, 0);
    }

    /** Version bridge for the vanilla Player.attack weapon damage source. */
    public static DamageSource weaponDamageSource(ItemStack weapon, Player attacker) {
        return weapon.getDamageSource(
                attacker, () -> attacker.damageSources().playerAttack(attacker));
    }

    public static void moveVector(ClientInput input, Vec2 movement) {
        MOVE_VECTOR.set(input, movement);
    }

    /** Runs the packet send inside one vanilla prediction scope, matching vanilla ordering. */
    public static void withPredictionSequence(
            ClientLevel level, java.util.function.IntConsumer sender) {
        BlockStatePredictionHandler handler =
                (BlockStatePredictionHandler) BLOCK_STATE_PREDICTION_HANDLER.get(level);
        try (BlockStatePredictionHandler prediction = handler.startPredicting()) {
            sender.accept(prediction.currentSequence());
        }
    }

    public static QuadInstance quadInstance(ModelBlockRenderer renderer) {
        return (QuadInstance) QUAD_INSTANCE.get(renderer);
    }

    public static void dispatchIncoming(Packet<?> packet, PacketListener listener) {
        try {
            DISPATCH_INCOMING.invoke(packet, listener);
        } catch (Throwable failure) {
            if (!failure.getClass()
                    .getName()
                    .equals("net.minecraft.server.RunningOnDifferentThreadException")) {
                throw new IllegalStateException("Unable to dispatch bundled packet", failure);
            }
        }
    }

    public static int entityEventId(ClientboundEntityEventPacket packet) {
        return (int) ENTITY_EVENT_ID.get(packet);
    }

    public static RenderPipeline registerPipeline(RenderPipeline pipeline) {
        try {
            return (RenderPipeline) REGISTER_PIPELINE.invokeExact(pipeline);
        } catch (Throwable failure) {
            throw new IllegalStateException(
                    "Unable to register Minecraft render pipeline", failure);
        }
    }

    public static RenderPipeline.Snippet debugFilledSnippet() {
        return (RenderPipeline.Snippet) DEBUG_FILLED_SNIPPET.get();
    }

    public static Component tabHeader(PlayerTabOverlay overlay) {
        return (Component) TAB_HEADER.get(overlay);
    }

    public static Component tabFooter(PlayerTabOverlay overlay) {
        return (Component) TAB_FOOTER.get(overlay);
    }

    public static SubmitNodeCollector submitNodeCollector(LevelRenderer renderer) {
        return (SubmitNodeCollector) SUBMIT_NODE_STORAGE.get(renderer);
    }
}
