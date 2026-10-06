package com.blanoir.moons.client.event;

import com.blanoir.moons.api.bridge.AgentBridge;
import com.blanoir.moons.client.access.*;
import com.blanoir.moons.client.command.ClientCommands;
import com.blanoir.moons.client.command.PremiumCheckCommand;
import com.blanoir.moons.client.compat.input.*;
import com.blanoir.moons.client.compat.render.EntityRenderState;
import com.blanoir.moons.client.event.combat.*;
import com.blanoir.moons.client.event.movement.*;
import com.blanoir.moons.client.event.render.LivingRenderEvent;
import com.blanoir.moons.client.manager.rotation.*;
import com.blanoir.moons.client.manager.input.CombatInputController;
import com.blanoir.moons.client.manager.lease.RotationLease;
import com.blanoir.moons.client.manager.time.TimerManager;
import com.blanoir.moons.client.module.impl.combat.*;
import com.blanoir.moons.client.module.impl.misc.*;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.module.impl.movement.*;
import com.blanoir.moons.client.module.impl.render.*;
import com.blanoir.moons.client.module.impl.render.xray.XrayTerrain;
import com.blanoir.moons.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.moons.client.render.VisualModelCapture;
import com.blanoir.moons.client.render.VisualPresentation;
import com.blanoir.moons.client.ui.render.LegacyGuiGraphics;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;
import com.blanoir.moons.runtime.RuntimeEvents;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.chunk.SetVisibility;
import net.minecraft.entity.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.world.IBlockAccess;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.*;

/** Actual 1.8.9 method boundaries, including paired restoration for temporary render state. */
public final class ClientEventDispatcher {
    private static final ThreadLocal<Deque<Float>> GAMMA = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<CameraState>> CAMERAS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> EFFECTS = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Deque<YawState>> YAW =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<PortalState>> PORTAL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private ClientEventDispatcher() {}

    public static boolean isActive(String id) {
        return true;
    }

    private static Object[] args(RuntimeEvents.MethodHook hook) {
        return hook.argument() instanceof Object[] a ? a : new Object[0];
    }

    public static void apply(RuntimeEvents.MethodHook h) {
        Minecraft mc = Minecraft.getMinecraft();
        Object[] a = args(h);
        switch (h.id()) {
            case "client.timer" -> {
                if (h.owner() instanceof net.minecraft.util.Timer timer)
                    timer.timerSpeed = TimerManager.multiplier(mc);
            }
            case "render.present" -> VisualPresentation.render();
            case "render.close" -> AgentBridge.onRendererClose(mc.entityRenderer);
            case "player.living-tick" -> {
                if (h.owner() == mc.thePlayer) {
                    if (NoJumpDelay.isEnabled()) GameAccess.clearJumpDelay(mc.thePlayer);
                    EventBus.LOCAL_PLAYER_LIVING_TICK.post(
                            new LocalPlayerLivingTickEvent(mc.thePlayer));
                }
            }
            case "movement.keyboard-input" -> keyboardInput((MovementInput) h.owner(), mc);
            case "movement.sprint" -> {
                if (h.owner() == mc.thePlayer)
                    h.value(
                            !CombatInputController.isSprintSuppressed()
                                    && !Scaffold.shouldSuppressSprint(mc)
                                    && (Boolean.TRUE.equals(h.value())
                                            || Sprint.shouldSprint(mc.thePlayer)));
            }
            case "movement.relative", "movement.jump" -> {
                if (h.owner() == mc.thePlayer) {
                    Entity e = (Entity) h.owner();
                    YAW.get().push(new YawState(e, e.rotationYaw));
                    MoveFix.State fix = MoveFix.current(mc);
                    if (fix.active()) e.rotationYaw = fix.yaw();
                }
            }
            case "movement.relative.end", "movement.jump.end" -> {
                if (h.owner() == mc.thePlayer && !YAW.get().isEmpty()) {
                    YawState saved = YAW.get().pop();
                    saved.entity.rotationYaw = saved.yaw;
                    if (h.id().equals("movement.relative.end"))
                        EventBus.PLAYER_MOVE_END.post(new PlayerMoveEndEvent(mc.thePlayer));
                }
            }
            case "combat.reach.pick" -> {
                Reach.applyNormalPick(mc);
                PickResultEvent event = new PickResultEvent(mc, mc.objectMouseOver);
                EventBus.PICK_RESULT.post(event);
                mc.objectMouseOver = event.result();
            }
            case "combat.attack" -> {
                if (h.owner() == mc.thePlayer && a[0] instanceof Entity target) {
                    KeepSprint.beginAttackSlowdown(h.owner());
                    EventBus.ATTACK_ENTITY_PRE.post(
                            new AttackEntityEvent.Pre(mc.thePlayer, target));
                }
            }
            case "combat.attack.end" -> {
                if (h.owner() == mc.thePlayer && a[0] instanceof Entity target) {
                    KeepSprint.finishAttackSlowdown(h.owner());
                    EventBus.ATTACK_ENTITY_POST.post(
                            new AttackEntityEvent.Post(mc.thePlayer, target));
                }
            }
            case "combat.block-break-start", "combat.block-break-continue" -> {
                if (h.owner() instanceof PlayerControllerMP mode && a[0] instanceof BlockPos pos) {
                    BlockBreakEvent event = new BlockBreakEvent(mode, pos);
                    EventBus.BLOCK_BREAK_PRE.post(event);
                    if (event.isCancelled()
                            || RotationLease.hasSilentRotation()
                            || SilentAura.shouldSuppressBlockBreaking()) {
                        mode.resetBlockRemoving();
                        h.value(false);
                    }
                }
            }
            case "world.block-update" -> AgentBridge.onBlockUpdateStart(h.owner(), a[0], a[1]);
            case "world.block-update.result" ->
                    AgentBridge.onBlockUpdate(
                            h.owner(), a[0], a[1], Boolean.TRUE.equals(h.value()));
            case "input.keyboard-event" -> {
                int code = Keyboard.getEventKey();
                int action =
                        Keyboard.getEventKeyState()
                                ? (Keyboard.isRepeatEvent()
                                        ? InputConstants.REPEAT
                                        : InputConstants.PRESS)
                                : InputConstants.RELEASE;
                h.value(
                        AgentBridge.onKey(
                                h.owner(), 0, action, new KeyEvent(code, code, modifiers())));
            }
            case "input.mouse-event" -> {
                boolean allowed = true;
                int wheel = Mouse.getEventDWheel(), button = Mouse.getEventButton();
                if (wheel != 0) allowed = AgentBridge.onMouseScroll(h.owner(), 0, 0, wheel / 120D);
                if (button >= 0)
                    allowed =
                            AgentBridge.onMouse(
                                            h.owner(),
                                            0,
                                            new MouseButtonInfo(button, modifiers()),
                                            Mouse.getEventButtonState()
                                                    ? InputConstants.PRESS
                                                    : InputConstants.RELEASE)
                                    && allowed;
                h.value(allowed);
            }
            case "input.mouse-motion" -> {
                if (h.owner() == mc.thePlayer) AgentBridge.onMouseMoveStart(mc.mouseHelper);
            }
            case "input.mouse-motion.end" -> {
                if (h.owner() == mc.thePlayer) AgentBridge.onMouseMoveEnd(mc.mouseHelper);
            }
            case "render.freelook.turn" -> {
                if (FreeLook.turn(
                        h.owner(), ((Number) a[0]).doubleValue(), ((Number) a[1]).doubleValue()))
                    h.value(false);
            }
            case "render.camera" -> beginCamera(mc);
            case "render.camera.end" -> endCamera();
            case "render.camera-ray" -> {
                if (Clip.isEnabled() && !CAMERAS.get().isEmpty()) h.value(false);
            }
            case "render.lightmap" -> {
                GAMMA.get().push(mc.gameSettings.gammaSetting);
                mc.gameSettings.gammaSetting =
                        FullBright.overrideBrightness(mc.gameSettings.gammaSetting);
            }
            case "render.lightmap.end" -> {
                if (!GAMMA.get().isEmpty()) mc.gameSettings.gammaSetting = GAMMA.get().pop();
            }
            case "render.effects" -> EFFECTS.set(EFFECTS.get() + 1);
            case "render.effects.end" -> EFFECTS.set(Math.max(0, EFFECTS.get() - 1));
            case "render.nausea" -> {
                EntityPlayerSP player = mc.thePlayer;
                if (player != null) {
                    PORTAL.get()
                            .push(
                                    new PortalState(
                                            player, player.timeInPortal, player.prevTimeInPortal));
                    if (AntiDebuff.suppress(Potion.confusion)
                            && player.getActivePotionEffect(Potion.confusion) != null)
                        player.timeInPortal = player.prevTimeInPortal = 0;
                }
            }
            case "render.nausea.end" -> {
                if (!PORTAL.get().isEmpty()) {
                    PortalState state = PORTAL.get().pop();
                    state.player.timeInPortal = state.current;
                    state.player.prevTimeInPortal = state.previous;
                }
            }
            case "render.antidebuff" -> {
                if (EFFECTS.get() > 0 && a.length > 0) {
                    Potion potion =
                            a[0] instanceof Potion p
                                    ? p
                                    : a[0] instanceof Integer id
                                                    && id >= 0
                                                    && id < Potion.potionTypes.length
                                            ? Potion.potionTypes[id]
                                            : null;
                    if (potion != null && AntiDebuff.suppress(potion)) h.value(false);
                }
            }
            case "render.static-fov" -> {
                if (h.value() instanceof Number n) h.value(StaticFov.apply(n.floatValue()));
            }
            case "render.scoreboard" -> {
                if (a[0] instanceof net.minecraft.scoreboard.ScoreObjective objective
                        && Scoreboard.render(new LegacyGuiGraphics(mc), objective)) h.value(false);
            }
            case "render.entity" -> {
                if (a[0] instanceof Entity entity && AntiBot.shouldHide(entity)) h.value(false);
            }
            case "render.entity-position" -> {
                if (h.value() instanceof Object[] values && values[0] instanceof Entity entity) {
                    float partial = ((Number) values[5]).floatValue();
                    EntityRenderState state =
                            new EntityRenderState(
                                    entity.lastTickPosX
                                            + (entity.posX - entity.lastTickPosX) * partial,
                                    entity.lastTickPosY
                                            + (entity.posY - entity.lastTickPosY) * partial,
                                    entity.lastTickPosZ
                                            + (entity.posZ - entity.lastTickPosZ) * partial);
                    double x = state.x, y = state.y, z = state.z;
                    AgentBridge.onRenderState(entity, state, partial);
                    values[1] = ((Number) values[1]).doubleValue() + state.x - x;
                    values[2] = ((Number) values[2]).doubleValue() + state.y - y;
                    values[3] = ((Number) values[3]).doubleValue() + state.z - z;
                }
            }
            case "render.living" -> {
                EntityLivingBase entity = (EntityLivingBase) a[0];
                AgentBridge.onRenderState(entity, Boolean.TRUE, ((Number) a[5]).floatValue());
                if (entity instanceof EntityPlayer p) ArmorHide.beginAvatar(p);
                EventBus.LIVING_RENDER_PRE.post(new LivingRenderEvent.Pre(entity));
            }
            case "render.living.end" -> {
                EntityLivingBase entity = (EntityLivingBase) a[0];
                EventBus.LIVING_RENDER_POST.post(new LivingRenderEvent.Post(entity));
                ArmorHide.endAvatar();
                AgentBridge.onRenderState(entity, Boolean.FALSE, ((Number) a[5]).floatValue());
            }
            case "render.player-nametag" -> {
                if (VisualModelCapture.active()
                        || a[0] instanceof EntityPlayer p
                                && Nametags.isEnabled()
                                && Nametags.shouldRenderPlayer(mc, p)) h.value(false);
            }
            case "render.shadow-fire" -> {
                if (VisualModelCapture.active()) h.value(false);
            }
            case "render.armor-hide", "render.armor-hide.head" -> {
                if (a[0] instanceof EntityLivingBase living
                        && !ArmorHide.shouldRenderHeadItem(living)) h.value(false);
            }
            case "render.animation" -> {
                if (Animations.applyFirstPerson(
                        ((Number) a[0]).floatValue(), ((Number) a[1]).floatValue())) h.value(false);
            }
            case "render.animation.block" -> {
                if (Animations.shouldReplaceVanilla(null)) h.value(false);
            }
            case "render.animation.render.end" -> Animations.endRender();
            case "render.scaffold-item-spoof", "render.scaffold-hud-item-spoof" ->
                    h.value(Scaffold.spoofedItem((net.minecraft.item.ItemStack) h.value()));
            case "render.animation.third-person" -> {
                if (a[6] instanceof EntityPlayer p)
                    Animations.applyThirdPerson(p, (ModelBiped) h.owner());
            }
            case "render.player-name" -> {
                if (h.owner() instanceof EntityPlayer p && h.value() instanceof IChatComponent text)
                    h.value(
                            AntiNick.applyPlayerName(
                                    p,
                                    PremiumCheckCommand.decorate(
                                            p, Nickname.replaceLocalPlayerName(p, text))));
            }
            case "render.tab-name" -> {
                if (a[0] instanceof NetworkPlayerInfo p && h.value() instanceof String text)
                    h.value(
                            AntiNick.applyTabName(
                                            p,
                                            PremiumCheckCommand.decorate(
                                                    p,
                                                    Nickname.replaceTabName(
                                                            p, new ChatComponentText(text))))
                                    .getFormattedText());
            }
            case "render.player-skin" -> {
                if (h.owner() instanceof NetworkPlayerInfo p
                        && h.value() instanceof ResourceLocation skin)
                    h.value(NicknameShuffle.skin(p, skin));
            }
            case "render.chat-name" -> {
                if (h.value() instanceof IChatComponent text)
                    h.value(Nickname.replaceOwnNameInChat(NicknameShuffle.chat(text)));
            }
            case "chat.filter" -> {
                if (a[0] instanceof S02PacketChat packet
                        && packet.getType() != 2
                        && ChatFilter.shouldHideServerSystemMessage(packet.getChatComponent()))
                    h.value(false);
            }
            case "command.client" -> {
                if (ClientCommands.handle((String) a[0])) h.value(false);
            }
            case "placement.item-ray" -> h.value(PlacementRaycast.itemRay(h.value()));
            case "render.frustum-visible" -> {
                if (a[0] instanceof AxisAlignedBB box && Clip.forceVisible(box)) h.value(true);
            }
            case "render.clip-occlusion" -> {
                if (Clip.isEnabled() || XrayTerrain.isEnabled()) {
                    SetVisibility visible = new SetVisibility();
                    visible.setAllVisible(true);
                    h.value(visible);
                }
            }
            case "xray.block" -> {
                if (XrayTerrain.beginBlock(
                        (IBlockState) a[0], (IBlockAccess) a[2], (BlockPos) a[1])) h.value(false);
            }
            case "xray.block-scope.end" -> XrayTerrain.endBackground();
            case "xray.layer" -> {
                if (h.value() instanceof EnumWorldBlockLayer layer)
                    h.value(XrayTerrain.forceTranslucentLayer(layer));
            }
            case "xray.side" -> {
                if (XrayTerrain.isEnabled()) h.value(true);
            }
            case "xray.vertex-data" -> h.value(XrayTerrain.applyAlpha((int[]) h.value()));
            case "xray.vertex-alpha" -> h.value(XrayTerrain.alpha((Integer) h.value()));
            default -> {
                /* Other runtime modules (including YSM) subscribe to their own hook IDs. */
            }
        }
    }

    private static void keyboardInput(MovementInput input, Minecraft mc) {
        if (mc.thePlayer != null && mc.thePlayer.movementInput == input) {
            AgentBridge.onMoveInput(mc.thePlayer);
            InputSnapshot original = GameAccess.inputSnapshot(input),
                    filtered = Scaffold.filterMovementInput(mc, original);
            MoveFix.State fix = MoveFix.capture(mc);
            InputSnapshot corrected = MoveFix.correctInput(filtered, mc.thePlayer.rotationYaw, fix);
            if (filtered != original || fix.active()) GameAccess.inputSnapshot(input, corrected);
            if (!CombatInputController.isSprintSuppressed()
                    && !Scaffold.shouldSuppressSprint(mc)
                    && Sprint.shouldSprint(mc.thePlayer)) mc.thePlayer.setSprinting(true);
        }
        EventBus.MOVEMENT_INPUT_UPDATED.post(
                new MovementInputUpdatedEvent((MovementInputFromOptions) input));
    }

    private static int modifiers() {
        return (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)
                        ? 1
                        : 0)
                | (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                                || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
                        ? 2
                        : 0)
                | (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)
                        ? 4
                        : 0);
    }

    private static void beginCamera(Minecraft mc) {
        Entity e = mc.getRenderViewEntity();
        if (e == null) return;
        CAMERAS.get()
                .push(
                        new CameraState(
                                e,
                                e.rotationYaw,
                                e.prevRotationYaw,
                                e.rotationPitch,
                                e.prevRotationPitch));
        float yaw = FreeLook.cameraAngle(mc.entityRenderer, 0, e.rotationYaw),
                pitch = FreeLook.cameraAngle(mc.entityRenderer, 1, e.rotationPitch);
        if (yaw != e.rotationYaw || pitch != e.rotationPitch) {
            e.rotationYaw = e.prevRotationYaw = yaw;
            e.rotationPitch = e.prevRotationPitch = pitch;
        }
    }

    private static void endCamera() {
        if (CAMERAS.get().isEmpty()) return;
        CameraState c = CAMERAS.get().pop();
        c.entity.rotationYaw = c.yaw;
        c.entity.prevRotationYaw = c.previousYaw;
        c.entity.rotationPitch = c.pitch;
        c.entity.prevRotationPitch = c.previousPitch;
    }

    private record CameraState(
            Entity entity, float yaw, float previousYaw, float pitch, float previousPitch) {}

    private record YawState(Entity entity, float yaw) {}

    private record PortalState(EntityPlayerSP player, float current, float previous) {}
}
