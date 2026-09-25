package com.blanoir.moons.ysm.internal.runtime;

import com.blanoir.moons.ysm.internal.client.animation.molang.*;
import com.blanoir.moons.ysm.internal.client.animation.molang.functions.ctrl.*;
import com.blanoir.moons.ysm.internal.client.animation.molang.functions.ysm.*;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.binding.variable.*;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.builtin.MathBinding;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.*;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.value.*;
import com.blanoir.moons.ysm.internal.molang.MolangEngine;
import com.blanoir.moons.ysm.internal.molang.runtime.*;
import com.blanoir.moons.ysm.internal.molang.runtime.binding.*;

import java.util.*;

/** Molang bindings whose instance state always belongs to one parsed model expression. */
public final class RuntimeBindings {
    private static final Set<String> CONTROL_ACTIONS =
            Set.of(
                    "death",
                    "riptide",
                    "sleep",
                    "swim",
                    "climb",
                    "climbing",
                    "ladder_up",
                    "ladder_down",
                    "ladder_stillness",
                    "fly",
                    "elytra_fly",
                    "swim_stand",
                    "attacked",
                    "jump",
                    "sneak",
                    "sneaking",
                    "run",
                    "walk",
                    "idle");

    public static IValue parse(String source, boolean script) {
        var scoped = new ScopedVariableBinding();
        var controller = new ControllerVariableBinding();
        var temp = new TempVariableRegistry();
        var fn = new FnBinding();
        ObjectBinding root =
                name ->
                        switch (name.toLowerCase(Locale.ROOT)) {
                            case "math" -> MathBinding.INSTANCE;
                            case "v", "variable" -> scoped;
                            case "c", "context" -> controller;
                            case "t", "temp" -> temp;
                            case "fn" -> fn;
                            case "arg", "args" -> new ArgsVariable();
                            case "q", "query" ->
                                    (ObjectBinding) key -> new Observation("query", key);
                            case "ysm" -> (ObjectBinding) RuntimeBindings::ysm;
                            case "ctrl" -> (ObjectBinding) RuntimeBindings::ctrl;
                            case "loop" -> StandardBindings.LOOP_FUNC;
                            case "for_each" -> StandardBindings.FOR_EACH_FUNC;
                            default -> null;
                        };
        try {
            String text = script ? stripComments(source) : source;
            return new MolangValue(MolangEngine.fromCustomBinding(root).parse(text), script);
        } catch (Exception failure) {
            // Keep the expression and error reviewable through the model's diagnostics page.
            return evaluator -> {
                runtime(evaluator)
                        .diagnostic("Expression parse: " + failure.getMessage() + " · " + source);
                return 0f;
            };
        }
    }

    private static LocalRuntime runtime(ExecutionContext<?> e) {
        return ((IContext<?>) e.entity()).geoInstance().runtime();
    }

    private static Object ysm(String name) {
        return switch (name) {
            case "first_order" -> new FirstOrderFunction();
            case "second_order" -> new SecondOrderFunction();
            case "bone_rot" -> new BoneRotation();
            case "bone_pos" -> new BonePosition();
            case "bone_scale" -> new BoneScale();
            case "bone_pivot_abs" -> new BonePivotAbs();
            case "defer" -> new Defer();
            case "particle", "abs_particle" ->
                    (Function)
                            (e, a) -> {
                                if (!((IContext<?>) e.entity()).isClientSide() || a.size() == 0)
                                    return false;
                                double[] parameters = new double[Math.min(9, a.size() - 1)];
                                for (int i = 0; i < parameters.length; i++)
                                    parameters[i] = a.getAsDouble(e, i + 1);
                                runtime(e)
                                        .effects()
                                        .particle(
                                                a.getAsString(e, 0),
                                                name.equals("abs_particle"),
                                                parameters);
                                return true;
                            };
            case "play_sound", "stop_sound", "stop_all_sounds" ->
                    (Function) (e, a) -> sound(name, e, a);
            // Local-only sync executes its local event and never constructs a packet.
            case "sync" ->
                    (Function)
                            (e, a) -> {
                                runtime(e).dispatch("sync", e, a);
                                return null;
                            };
            default -> new Observation("ysm", name);
        };
    }

    private static Object sound(
            String operation, ExecutionContext<?> e, Function.ArgumentCollection a) {
        IContext<?> ctx = (IContext<?>) e.entity();
        if (!ctx.isClientSide()) return false;
        int flags = operation.equals("play_sound") && a.size() > 2 ? a.getAsInt(e, 2) : 0;
        boolean global =
                operation.equals("play_sound")
                        ? (flags & 2) != 0
                        : a.size() > (operation.equals("stop_sound") ? 1 : 0)
                                && a.getAsBoolean(e, a.size() - 1);
        var manager = ctx.getAudioPlayerManager(global);
        if (manager == null) return false;
        if (operation.equals("stop_all_sounds")) {
            manager.stopAll();
            return true;
        }
        if (a.size() < 1) return false;
        Object value = a.getValue(e, 0);
        int key = value instanceof Number n ? -n.intValue() : ValueConversions.asStringId(value);
        if (operation.equals("stop_sound")) return manager.stopSound(key);
        if (a.size() < 2 || flags < 0 || flags > 7) return false;
        return manager.play(
                ctx.geoInstance(),
                key,
                a.getAsString(e, 1),
                (flags & 4) != 0,
                a.size() > 3 ? a.getAsFloat(e, 3) : 1,
                a.size() > 4 ? a.getAsFloat(e, 4) : 1,
                (flags & 1) != 0);
    }

    private static Object ctrl(String name) {
        return switch (name) {
            case "state_continue" -> 2;
            case "state_stop" -> 3;
            case "state_pause" -> 4;
            case "state_bypass" -> 5;
            case "loop" -> 10;
            case "play_once" -> 11;
            case "hold_on_last_frame" -> 12;
            case "set_animation" -> new SetAnimation();
            case "set_beginning_transition_length" -> new SetTransitionSpeed();
            case "reset" -> new Reset();
            case "indicate_reload" -> new IndicateReload();
            default -> new Observation("ctrl", name);
        };
    }

    private record Observation(String namespace, String name, String qualifiedName)
            implements Variable, Function {
        private Observation(String namespace, String name) {
            this(namespace, name, namespace + "." + name);
        }

        @Override
        public Object evaluate(ExecutionContext<?> e) {
            return evaluate(e, Function.EMPTY_ARGUMENT);
        }

        @Override
        public Object evaluate(ExecutionContext<?> e, ArgumentCollection args) {
            IContext<?> ctx = (IContext<?>) e.entity();
            LocalRuntime rt = ctx.geoInstance().runtime();
            if (namespace.equals("ctrl") && LocalControlQueries.supports(name)) {
                List<Object> arguments = new ArrayList<>(args.size());
                for (int i = 0; i < args.size(); i++) arguments.add(args.getValue(e, i));
                return LocalControlQueries.query(
                        name,
                        arguments,
                        ctx.entity() instanceof LocalRuntime.ObservationView view
                                ? view.values()::get
                                : rt::observation);
            }
            if (namespace.equals("ysm")) {
                if (name.equals("map_angle"))
                    return Math.clamp(1 - ctx.data().headPitch / 45.1f, 0, 1);
                if (name.equals("tcos0")) {
                    Object distance =
                            ctx.entity() instanceof LocalRuntime.ObservationView view
                                    ? view.values().get("walk_distance")
                                    : rt.observation("walk_distance");
                    double speed = distance instanceof Number n ? n.doubleValue() : 0;
                    return Math.cos(rt.time() * 103.2 * Math.PI / 180)
                            * Math.min(1, speed * 4)
                            * 20;
                }
            }
            if (ctx.entity() instanceof LocalRuntime.ObservationView view) {
                if (args.size() == 0) {
                    if (view.values().containsKey(qualifiedName))
                        return view.values().get(qualifiedName);
                    if (view.values().containsKey(name)) return view.values().get(name);
                }
                List<Object> values = new ArrayList<>(args.size());
                for (int i = 0; i < args.size(); i++) values.add(args.getValue(e, i));
                return view.queries().query(namespace, name, values);
            }
            if (namespace.equals("ysm") && name.equals("projectile_owner")) {
                Object owner = rt.observation("projectile_owner");
                return owner instanceof LocalRuntime.ObservationView view
                        ? ctx.createChild(view)
                        : null;
            }
            if (namespace.equals("query")) {
                // Upstream QueryBinding exposes yaw as x and pitch as y. Player
                // observations contain raw game angles; these queries use model space.
                if (name.equals("head_x_rotation") && rt.observation("head_y_rotation") != null)
                    return ctx.data().netHeadYaw;
                if (name.equals("head_y_rotation") && rt.observation("head_x_rotation") != null)
                    return ctx.data().headPitch;
                if (name.equals("life_time")) return rt.time();
                if (name.equals("anim_time"))
                    return ctx.animationControllerContext() == null
                            ? 0
                            : ctx.animationControllerContext().animTime();
                if (name.equals("all_animations_finished"))
                    return ctx.getPlaybackFlags() != null && ctx.getPlaybackFlags().isPaused();
                if (name.equals("any_animation_finished"))
                    return ctx.getPlaybackFlags() != null && ctx.getPlaybackFlags().isStopped();
                if (name.equals("is_local_player"))
                    return !Boolean.FALSE.equals(rt.observation("is_local_player"));
            }
            if (namespace.equals("ysm")) {
                if (name.equals("head_pitch") || name.equals("target_x_rotation"))
                    return ctx.data().headPitch;
                if (name.equals("head_yaw") || name.equals("target_y_rotation"))
                    return ctx.data().netHeadYaw;
                if (name.equals("get_root_locator_offset")) return 0f;
                if (name.equals("texture_name")) return rt.textureName();
            }
            if (namespace.equals("ctrl")) {
                if (name.equals("playing_extra_animation")) return !rt.extra().isEmpty();
                if (CONTROL_ACTIONS.contains(name)) return rt.action().equals(name);
            }
            Object value = rt.observation(qualifiedName);
            if (value == null) value = rt.observation(name);
            if (value != null && args.size() == 0) return value;
            List<Object> values = new ArrayList<>(args.size());
            for (int i = 0; i < args.size(); i++) values.add(args.getValue(e, i));
            return rt.query(namespace, name, values);
        }
    }

    private static String stripComments(String source) {
        StringBuilder result = new StringBuilder();
        boolean quoted = false, line = false, block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) {
                if (c == '\n' || c == '\r') {
                    line = false;
                    result.append('\n');
                }
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                }
                continue;
            }
            if (c == '\'') quoted = !quoted;
            if (!quoted && c == '/' && next == '/') {
                line = true;
                i++;
                continue;
            }
            if (!quoted && c == '/' && next == '*') {
                block = true;
                i++;
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }
}
