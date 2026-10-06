package com.blanoir.moons.client.input;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.input.KeyInputEvent;
import com.blanoir.moons.client.event.input.MouseButtonEvent;
import com.blanoir.moons.client.event.input.MouseMotionEvent;
import com.blanoir.moons.client.event.input.MouseScrollEvent;
import com.blanoir.moons.client.module.framework.ModuleKeybinds;
import com.blanoir.moons.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.moons.client.ui.clickgui.ModuleGui;
import com.blanoir.moons.client.ui.clickgui.MoonsComposeScreen;
import com.blanoir.moons.runtime.RuntimeEvents;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Owns input routing and lifetime; raw cancellation runs before binding policy. */
public final class InputEventDispatcher implements AutoCloseable {
    private final KeyBindingInputState bindingInputs =
            new KeyBindingInputState(ModuleKeybinds::dispatchRelease);
    private final InputFocusState focus = new InputFocusState();
    private final Map<Object, MouseCapture> mouseCaptures =
            Collections.synchronizedMap(new WeakHashMap<>());

    public void poll() {
        Minecraft client = Minecraft.getInstance();
        updateFocus(client);
        ModuleKeybinds.reconcileHeldBindings();
        Object screen = MinecraftClientAccess.screen(client);
        bindingInputs.poll(
                ModuleKeybinds.boundKeys(),
                client.isWindowActive(),
                key -> PhysicalInput.isDown(client, key),
                key -> routeBindingPress(client, key, screen == null));
    }

    public void key(RuntimeEvents.Key event) {
        updateFocus(Minecraft.getInstance());
        if (!(event.event() instanceof KeyEvent keyEvent)) return;
        InputConstants.Key key = InputKeys.fromEvent(keyEvent);
        if (event.action() == InputConstants.RELEASE) bindingInputs.release(key);
        if (event.handler() instanceof KeyboardHandler handler) {
            KeyInputEvent input =
                    new KeyInputEvent(handler, event.window(), event.action(), keyEvent);
            EventBus.KEY_INPUT.post(input);
            if (input.isCancelled()) {
                if (event.action() == InputConstants.PRESS) bindingInputs.suppress(key);
                event.control().cancel();
                return;
            }
        }
        if (event.action() != InputConstants.PRESS) {
            if (event.action() == InputConstants.REPEAT && bindingInputs.consumed(key)) {
                event.control().cancel();
            }
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null
                && client.level != null
                && MinecraftClientAccess.screen(client) == null) {
            for (int slot = 0; slot < client.options.keyHotbarSlots.length; slot++) {
                if (client.options.keyHotbarSlots[slot].matches(keyEvent)
                        && Scaffold.handleHotbarSwap(slot, 0)) {
                    bindingInputs.suppress(key);
                    event.control().cancel();
                    return;
                }
            }
        }
        if (focus.focused()
                && bindingInputs.press(key, pressed -> routeBindingPress(client, pressed, true))) {
            event.control().cancel();
        }
    }

    public void mouse(RuntimeEvents.Mouse event) {
        updateFocus(Minecraft.getInstance());
        if (!(event.button() instanceof MouseButtonInfo button)) return;
        InputConstants.Key key = InputKeys.fromMouseButton(button.button());
        if (event.action() == InputConstants.RELEASE) bindingInputs.release(key);
        if (event.handler() instanceof MouseHandler handler) {
            MouseButtonEvent input =
                    new MouseButtonEvent(handler, event.window(), button, event.action());
            EventBus.MOUSE_BUTTON.post(input);
            if (input.isCancelled()) {
                if (event.action() == InputConstants.PRESS) bindingInputs.suppress(key);
                event.control().cancel();
                return;
            }
        }
        if (event.action() != InputConstants.PRESS) return;
        Minecraft client = Minecraft.getInstance();
        if (focus.focused()
                && bindingInputs.press(key, pressed -> routeBindingPress(client, pressed, true))) {
            event.control().cancel();
        }
    }

    private boolean routeBindingPress(
            Minecraft client, InputConstants.Key key, boolean allowGameplayBindings) {
        if (!client.isWindowActive() || client.player == null || client.level == null) return false;
        Object screen = MinecraftClientAccess.screen(client);
        if (screen != null && (!(screen instanceof MoonsComposeScreen gui) || gui.isBindingKey()))
            return false;
        ModuleKeybinds.Dispatch dispatch =
                ModuleKeybinds.dispatchPress(key, allowGameplayBindings && screen == null);
        if (dispatch == ModuleKeybinds.Dispatch.GUI) {
            ModuleGui.toggle(client);
        }
        return dispatch.consumed();
    }

    public void mouseScroll(RuntimeEvents.MouseScroll event) {
        if (event.handler() instanceof MouseHandler handler) {
            MouseScrollEvent input =
                    new MouseScrollEvent(handler, event.window(), event.xOffset(), event.yOffset());
            EventBus.MOUSE_SCROLL.post(input);
            if (input.isCancelled()) {
                event.control().cancel();
                return;
            }
        }
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client.player;
        if (currentPlayer == null
                || client.level == null
                || currentPlayer.isSpectator()
                || MinecraftClientAccess.screen(client) != null
                || event.yOffset() == 0.0D) return;
        int offset = event.yOffset() > 0.0D ? 1 : -1;
        if (Scaffold.handleHotbarSwap(-1, offset)) event.control().cancel();
    }

    public void mouseMove(RuntimeEvents.MouseMove event) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (event.phase() == RuntimeEvents.Phase.START) {
            if (event.handler() instanceof MouseHandler handler) {
                EventBus.MOUSE_MOTION_PRE.post(new MouseMotionEvent.Pre(handler));
            }
            if (player != null) {
                MouseCapture capture =
                        mouseCaptures.computeIfAbsent(
                                event.handler(), ignored -> new MouseCapture());
                capture.yaw = player.getYRot();
                capture.pitch = player.getXRot();
                capture.active = true;
            }
            return;
        }
        MouseCapture capture = mouseCaptures.get(event.handler());
        if (capture != null && capture.active) {
            capture.active = false;
            if (player != null) {
                MouseMotionTracker.recordFrame(
                        System.nanoTime(),
                        Mth.wrapDegrees(player.getYRot() - capture.yaw),
                        player.getXRot() - capture.pitch);
            }
        }
        if (event.handler() instanceof MouseHandler handler) {
            EventBus.MOUSE_MOTION_POST.post(new MouseMotionEvent.Post(handler));
        }
    }

    private void updateFocus(Minecraft client) {
        if (focus.update(client.getWindow().handle(), client.isWindowActive())) resetTracked();
        if (!focus.focused()
                || client.player == null
                || client.level == null
                || MinecraftClientAccess.screen(client) != null)
            ModuleKeybinds.releaseHeldBindings();
    }

    private void resetTracked() {
        bindingInputs.reset();
        ModuleKeybinds.releaseHeldBindings();
        mouseCaptures.clear();
        MouseMotionTracker.reset();
    }

    public void reset() {
        resetTracked();
        focus.reset();
    }

    @Override
    public void close() {
        reset();
    }

    private static final class MouseCapture {
        float yaw;
        float pitch;
        boolean active;
    }
}
