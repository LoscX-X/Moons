package com.blanoir.moons.ysm.internal.runtime;

import com.blanoir.moons.ysm.internal.client.animation.molang.PhysicsManager;
import com.blanoir.moons.ysm.internal.geckolib3.core.AnimatableEntity;
import com.blanoir.moons.ysm.internal.geckolib3.core.builder.*;
import com.blanoir.moons.ysm.internal.geckolib3.core.controller.*;
import com.blanoir.moons.ysm.internal.geckolib3.core.event.predicate.AnimationEvent;
import com.blanoir.moons.ysm.internal.geckolib3.core.manager.AnimationData;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.AnimationContext;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.util.StringPool;
import com.blanoir.moons.ysm.internal.geckolib3.core.molang.value.IValue;
import com.blanoir.moons.ysm.internal.geckolib3.core.processor.AnimationProcessor;
import com.blanoir.moons.ysm.internal.geckolib3.geo.animated.AnimatedGeoModel;
import com.blanoir.moons.ysm.internal.geckolib3.model.provider.data.EntityModelData;
import com.blanoir.moons.ysm.internal.molang.runtime.ExpressionEvaluator;
import com.blanoir.moons.ysm.internal.resource.bundle.AnimationMapper;
import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel;

import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local player runtime assembled with the upstream controller order and processor. */
public final class LocalRuntime extends AnimatableEntity<Object> implements AutoCloseable {
    public interface Effects {
        com.blanoir.moons.ysm.YsmSoundHandle sound(
                String name, boolean loop, float volume, float pitch);

        void particle(String name, boolean absolute, double[] parameters);
    }

    private static final Effects SILENT_EFFECTS =
            new Effects() {
                public com.blanoir.moons.ysm.YsmSoundHandle sound(
                        String n, boolean l, float v, float p) {
                    return null;
                }

                public void particle(String n, boolean a, double[] p) {}
            };
    private Effects effects = SILENT_EFFECTS;
    private boolean suppressEffects;
    private String textureName = "";

    public void suppressEffects(boolean value) {
        suppressEffects = value;
    }

    public String textureName() {
        return textureName;
    }

    public void textureName(String value) {
        textureName = value;
    }

    private final Map<String, Animation> animations = new LinkedHashMap<>();
    private final Map<String, AnimationController> controllers = new LinkedHashMap<>();
    private final Map<String, IValue> functions = new LinkedHashMap<>();
    private final Object2ReferenceOpenHashMap<String, List<IValue>> events =
            new Object2ReferenceOpenHashMap<>();
    private final String prefix;
    private final String initEvent;
    private final String updateEvent;
    private final LocalAnimationRules rules;
    private final AnimationData data = new AnimationData();
    private final PhysicsManager physics = new PhysicsManager();
    private final AnimationProcessor<Object> processor = new AnimationProcessor<>(this);
    private final AnimatedGeoModel geometry;
    private final Map<String, RuntimeBone> bones = new LinkedHashMap<>();
    private final Map<String, Double> pendingVariables = new LinkedHashMap<>();
    private final Map<String, IValue> pendingExpressions = new LinkedHashMap<>();
    private final Map<String, String> states = new LinkedHashMap<>();
    private final Map<String, Integer> types = new LinkedHashMap<>();
    private Map<String, ?> observations = Map.of();
    private String action = "idle", extra = "";
    private boolean initialized;
    private double lastTime = Double.NaN;
    private AnimationContext<?> current;
    private final Set<String> diagnostics = new LinkedHashSet<>();
    private long expressionErrorCount;

    public record ObservationView(Map<String, ?> values, Queries queries) {
        public ObservationView {
            values = Collections.unmodifiableMap(new HashMap<>(values));
        }
    }

    public interface Queries {
        Object query(String namespace, String name, List<Object> arguments);
    }

    private Queries queries =
            (namespace, name, args) -> {
                diagnostic("Unbound observation: " + namespace + "." + name);
                return 0f;
            };

    public void queries(Queries value) {
        queries = Objects.requireNonNull(value);
    }

    public Object query(String namespace, String name, List<Object> args) {
        return queries.query(namespace, name, args);
    }

    public void dispatch(
            String event,
            com.blanoir.moons.ysm.internal.molang.runtime.ExecutionContext<?> evaluator,
            com.blanoir.moons.ysm.internal.molang.runtime.Function.ArgumentCollection args) {
        var context =
                (com.blanoir.moons.ysm.internal.geckolib3.core.molang.context.IContext<?>)
                        evaluator.entity();
        for (IValue expression : events.getOrDefault(event, List.of()))
            context.callFunctionWithArgs(evaluator, expression, args);
    }

    public LocalRuntime(RawYsmModel raw, List<RuntimeBone> bones, boolean firstPerson) {
        this(raw, bones, firstPerson ? "fp.arm" : "player");
    }

    public LocalRuntime(RawYsmModel raw, List<RuntimeBone> bones, String domain) {
        this.geometry = new AnimatedGeoModel(bones);
        prefix = domain;
        initEvent = eventPrefix() + "_init";
        updateEvent = eventPrefix() + "_update";
        rules = new LocalAnimationRules(this, raw.formatVersion);
        boolean firstPerson = domain.equals("fp.arm");
        boolean subEntity = domain.equals("vehicle") || domain.equals("projectile");
        for (RuntimeBone bone : bones) this.bones.put(bone.getName(), bone);
        for (var file : raw.mainEntity.animationFiles.values()) {
            // Optional mod compatibility has intentionally not been imported.
            if (subEntity
                    || (firstPerson
                            ? file.animType == 11
                            : (file.animType == 0
                                    || file.animType == 1
                                    || file.animType == 2
                                    || file.animType == 3))) {
                var mapped =
                        AnimationMapper.buildAnimations(file, raw.properties.mergeMultilineExpr);
                mapped.forEach(
                        (name, animation) -> {
                            // Upstream assembly lets later files replace earlier definitions.
                            animations.put(name, animation);
                            types.put(name, file.animType);
                        });
            }
        }
        if (!subEntity) {
            LocalAnimationAssembly.aliases(animations, types);
            if (firstPerson) {
                Map<String, Animation> body = new LinkedHashMap<>();
                for (var file : raw.mainEntity.animationFiles.values()) {
                    if (file.animType >= 0 && file.animType <= 3)
                        AnimationMapper.buildAnimations(file, raw.properties.mergeMultilineExpr)
                                .forEach(body::put);
                }
                LocalAnimationAssembly.deriveArms(body, animations, types);
            }
        }
        for (var file : raw.mainEntity.animationControllerFiles)
            controllers.putAll(
                    AnimationMapper.buildControllers(
                            file.controllers, raw.properties.mergeMultilineExpr));
        raw.functionFiles.forEach(
                (name, file) -> {
                    String key = name.replaceFirst("\\.molang$", "");
                    String[] parts = key.split("@", 2);
                    IValue expression =
                            RuntimeBindings.parse(
                                    new String(file.data, StandardCharsets.UTF_8), true);
                    functions.put(parts[0].toLowerCase(Locale.ROOT), expression);
                    if (parts.length > 1)
                        for (String event : parts[1].split("[,;]"))
                            events.computeIfAbsent(
                                            event.trim()
                                                    .toLowerCase(Locale.ROOT)
                                                    .replace("fp_ctrl_arm_ctrl_", "fp.arm_ctrl_"),
                                            n -> new ArrayList<>())
                                    .add(expression);
                });
        if (subEntity) {
            if (domain.equals("vehicle")) parallel("pre_parallel", false);
            slots("pre_main");
            add(
                    "main",
                    () ->
                            domain.equals("projectile")
                                    ? choose(
                                            flag("is_in_water")
                                                    ? "water"
                                                    : flag("is_on_fire")
                                                            ? "fire"
                                                            : flag("is_on_ground")
                                                                    ? "ground"
                                                                    : "air")
                                    : choose(
                                            flag("is_in_water")
                                                    ? "water"
                                                    : flag("is_on_ground") ? "ground" : "fly"),
                    .1f,
                    false);
            if (domain.equals("vehicle")) {
                add(
                        "move",
                        () -> choose(number("ground_speed") > 1 ? "forward" : "idle"),
                        .1f,
                        false);
                add("ride", () -> choose(flag("has_rider") ? "has_ride" : "not_ride"), .1f, false);
            }
            slots("post_main");
            parallel("parallel", true);
            reset();
            return;
        }
        if (firstPerson) {
            slots("misc");
            parallel("parallel", true);
            for (String slot : List.of("head", "chest", "legs", "feet"))
                add("armor_" + slot, () -> rules.armor(slot), 0, false);
            add("lance", rules::firstPersonLance, 0, false);
            add("preview", () -> this.extra.isBlank() ? null : choose(this.extra), 0, false);
            reset();
            return;
        }
        parallel("pre_parallel", false);
        add("vehicle", rules::vehicle, .1f, false);
        slots("pre_main");
        add("main", () -> flag("is_riding") ? null : choose(action), .1f, false);
        slots("post_main");
        slots("pre_hold");
        add("hold_offhand", () -> rules.held("hold", "offhand"), .1f, false);
        add("hold_mainhand", () -> rules.held("hold", "mainhand"), .1f, false);
        slots("post_hold");
        slots("pre_swing");
        add(
                "swing",
                () -> flag("is_swinging") ? rules.held("swing", text("swinging_hand")) : null,
                0,
                false);
        slots("post_swing");
        slots("pre_use");
        add(
                "use",
                () -> flag("is_using_item") ? rules.held("use", text("using_hand")) : null,
                .1f,
                false);
        slots("post_use");
        add("passenger", rules::passenger, .1f, false);
        add("cap", () -> extra.isBlank() ? null : choose(extra), 0, false);
        parallel("parallel", true);
        for (String slot : List.of("head", "chest", "legs", "feet"))
            add("armor_" + slot, () -> rules.armor(slot), .1f, false);
        reset();
    }

    private void parallel(String slot, boolean deprecated) {
        var entries = new TreeMap<String, String>();
        for (String key : controllers.keySet())
            if (key.startsWith(prefix + "." + slot + "_"))
                entries.put(key.substring(prefix.length() + 1), null);
        for (String event : events.keySet()) {
            String key = event.replace("_ctrl_", ".");
            if (key.startsWith(prefix + "." + slot + "_"))
                entries.put(key.substring(prefix.length() + 1), null);
        }
        for (int i = 0; i < 8; i++)
            if (animations.containsKey(slot + i) && !animations.get(slot + i).isEmpty())
                entries.put(slot + "_" + i, slot + i);
        entries.forEach((controller, animation) -> add(controller, () -> animation, 0, deprecated));
    }

    private void slots(String slot) {
        var names = new TreeSet<String>();
        for (String name : controllers.keySet())
            if (name.equals(prefix + "." + slot) || name.startsWith(prefix + "." + slot + "_"))
                names.add(name.substring(prefix.length() + 1));
        for (String name : events.keySet())
            if (name.equals(prefix + "_ctrl_" + slot)
                    || name.startsWith(prefix + "_ctrl_" + slot + "_"))
                names.add(name.substring(prefix.length() + 6));
        for (String name : names) add(name, () -> null, 0, false);
    }

    private void add(
            String name,
            java.util.function.Supplier<String> select,
            float transition,
            boolean deprecated) {
        data.addAnimationController(
                new CompositeAnimationController<>(
                        this,
                        prefix + "." + name,
                        transition,
                        (event, evaluator) -> {
                            String selected = select.get();
                            return rules.apply(prefix, name, selected, event.getController());
                        },
                        deprecated));
    }

    public String choose(String... names) {
        for (String name : names) if (animations.containsKey(name)) return name;
        return null;
    }

    public void update(double seconds, Map<String, ?> observations, String extra) {
        this.observations = observations;
        this.extra = extra == null ? "" : extra;
        this.action = selectAction();
        if (Double.isFinite(lastTime)
                && seconds == lastTime
                && initialized
                && pendingVariables.isEmpty()) return;
        if (Double.isFinite(lastTime) && seconds < lastTime) reset();
        lastTime = seconds;
        var modelData = new EntityModelData();
        modelData.rawHeadPitch = (float) number("head_x_rotation");
        modelData.rawNetHeadYaw = (float) number("head_y_rotation");
        modelData.headPitch = headPitch();
        modelData.netHeadYaw = headYaw();
        var event =
                new AnimationEvent<AnimatableEntity<Object>>(
                        this,
                        (float) number("modified_distance_moved"),
                        (float) number("modified_move_speed"),
                        (int) (seconds * 20),
                        0,
                        0,
                        number("ground_speed") > .05,
                        flag("first_person"),
                        modelData);
        event.currentTick = (float) (seconds * 20);
        current = new AnimationContext<>(observations, this, event, modelData);
        if (!initialized) {
            for (IValue value : events.getOrDefault(initEvent, List.of()))
                processor.execute(value, true, true, null);
            initialized = true;
        }
        pendingVariables.forEach(
                (name, value) ->
                        processor.execute(
                                e -> {
                                    assign(
                                            ((AnimationContext<?>) e.entity()).scopedStorage(),
                                            name,
                                            value);
                                    return value;
                                },
                                true,
                                true,
                                null));
        pendingVariables.clear();
        pendingExpressions.values().forEach(value -> processor.execute(value, true, true, null));
        pendingExpressions.clear();
        for (IValue value : events.getOrDefault(updateEvent, List.of()))
            processor.execute(value, true, true, null);
        physics.update((float) (seconds * 20));
        processor.tickAnimation(event, current, true, true);
    }

    private String eventPrefix() {
        return prefix.equals("fp.arm") ? "player" : prefix;
    }

    private String selectAction() {
        var snapshot =
                new com.blanoir.moons.ysm.internal.client.animation.PlayerActionSnapshot(
                        flag("is_dead"),
                        flag("is_riptide"),
                        flag("is_sleeping"),
                        flag("is_swimming"),
                        flag("swimming_pose"),
                        flag("on_ladder"),
                        flag("is_flying"),
                        flag("is_fall_flying"),
                        flag("is_in_water"),
                        !observations.containsKey("is_on_ground") || flag("is_on_ground"),
                        flag("is_sneaking"),
                        flag("is_sprinting"),
                        number("hurt_time") > 0,
                        number("ground_speed") > .05,
                        (float) number("vertical_speed"),
                        flag("is_riding"));
        return com.blanoir.moons.ysm.internal.client.animation.ControllerActionResolver
                .resolveState(snapshot)
                .animationName();
    }

    public Object observation(String name) {
        return observations.getOrDefault(name, null);
    }

    public double number(String name) {
        Object v = observations.get(name);
        return v instanceof Number n ? n.doubleValue() : Boolean.TRUE.equals(v) ? 1 : 0;
    }

    /** Upstream model-space degrees, shared by Molang and automatic head tracking. */
    public float headPitch() {
        return -(float) number("head_x_rotation");
    }

    public float headYaw() {
        double yaw = number("head_y_rotation") % 360;
        if (yaw >= 180) yaw -= 360;
        if (yaw < -180) yaw += 360;
        return -(float) Math.clamp(yaw, -85, 85);
    }

    public boolean flag(String name) {
        return number(name) != 0;
    }

    public String text(String name) {
        Object v = observations.get(name);
        return v instanceof String s ? s : "";
    }

    public String action() {
        return action;
    }

    public String extra() {
        return extra;
    }

    public double time() {
        return Double.isFinite(lastTime) ? lastTime : 0;
    }

    public RuntimeBone bone(String name) {
        return bones.get(name);
    }

    public Map<String, RuntimeBone> bones() {
        return Collections.unmodifiableMap(bones);
    }

    public Set<String> animationNames() {
        return Collections.unmodifiableSet(animations.keySet());
    }

    public Map<String, Integer> animationTypes() {
        return Collections.unmodifiableMap(types);
    }

    public Effects effects() {
        return suppressEffects ? SILENT_EFFECTS : effects;
    }

    public void effects(Effects effects) {
        this.effects = Objects.requireNonNull(effects);
    }

    public void variable(String name, double value) {
        String path = name.replaceFirst("(?i)^(v|variable)\\.", "").toLowerCase(Locale.ROOT);
        if (!path.matches("[a-z0-9_]+(\\.[a-z0-9_]+)*") || !Double.isFinite(value))
            throw new IllegalArgumentException("Invalid model variable");
        Double previous = variableValue(path);
        if (previous != null && Double.compare(previous, value) == 0) return;
        pendingVariables.put(path, value);
    }

    private void assign(
            com.blanoir.moons.ysm.internal.geckolib3.core.molang.storage.IScopedVariableStorage
                    storage,
            String path,
            Object value) {
        String[] parts = path.split("\\.");
        int root = StringPool.computeIfAbsent(parts[0]);
        if (parts.length == 1) {
            storage.setScoped(root, value);
            return;
        }
        Object current = storage.getScoped(root);
        if (!(current instanceof com.blanoir.moons.ysm.internal.molang.runtime.Struct)) {
            current = new com.blanoir.moons.ysm.internal.molang.runtime.HashMapStruct();
            storage.setScoped(root, current);
        }
        var struct = (com.blanoir.moons.ysm.internal.molang.runtime.Struct) current;
        for (int i = 1; i < parts.length - 1; i++) {
            int key = StringPool.computeIfAbsent(parts[i]);
            Object child = struct.getProperty(key);
            if (!(child instanceof com.blanoir.moons.ysm.internal.molang.runtime.Struct)) {
                child = new com.blanoir.moons.ysm.internal.molang.runtime.HashMapStruct();
                struct.putProperty(key, child);
            }
            struct = (com.blanoir.moons.ysm.internal.molang.runtime.Struct) child;
        }
        struct.putProperty(StringPool.computeIfAbsent(parts[parts.length - 1]), value);
    }

    public Map<String, Double> variables() {
        Map<String, Double> result = new TreeMap<>();
        if (current != null)
            processor.forEachPropertyName(
                    name -> {
                        Object v =
                                current.scopedStorage().getScoped(StringPool.computeIfAbsent(name));
                        flattenVariables(result, name, v, 0);
                    });
        return result;
    }

    private static void flattenVariables(
            Map<String, Double> result, String path, Object value, int depth) {
        if (value instanceof Number n) result.put(path, n.doubleValue());
        else if (value instanceof Boolean b) result.put(path, b ? 1d : 0d);
        else if (value instanceof com.blanoir.moons.ysm.internal.molang.runtime.HashMapStruct struct
                && depth < 8)
            struct.forEach(
                    (name, child) -> flattenVariables(result, path + "." + name, child, depth + 1));
    }

    public Double variableValue(String expression) {
        if (current == null || expression == null) return null;
        String path = expression.replaceFirst("(?i)^(v|variable)\\.", "").toLowerCase(Locale.ROOT);
        if (pendingVariables.containsKey(path)) return pendingVariables.get(path);
        String[] parts = path.split("\\.");
        Object value = current.scopedStorage().getScoped(StringPool.computeIfAbsent(parts[0]));
        for (int i = 1; i < parts.length && value != null; i++)
            value =
                    value instanceof com.blanoir.moons.ysm.internal.molang.runtime.Struct s
                            ? s.getProperty(StringPool.computeIfAbsent(parts[i]))
                            : null;
        return value instanceof Number n
                ? n.doubleValue()
                : value instanceof Boolean b ? b ? 1d : 0d : null;
    }

    public Map<String, String> controllerStates() {
        Map<String, String> result = new LinkedHashMap<>();
        for (var c : data.getAnimationControllers())
            result.put(c.getName(), c.getCurrentAnimation());
        return result;
    }

    public synchronized void diagnostic(String message) {
        if (message.startsWith("Expression ")) expressionErrorCount++;
        if (diagnostics.size() < 100) diagnostics.add(message);
    }

    /** Total errors, including repetitions and errors beyond the UI diagnostic limit. */
    public synchronized long expressionErrorCount() {
        return expressionErrorCount;
    }

    public synchronized List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public void queueExpression(String key, String script) {
        if (script == null || script.length() > 16384)
            throw new IllegalArgumentException("Invalid parameter expression");
        pendingExpressions.put(key, RuntimeBindings.parse(script, true));
    }

    public Object evaluate(String script) {
        if (current == null) return null;
        current.setIsClientSide(true);
        try {
            return RuntimeBindings.parse(script, true)
                    .evalUnsafe(ExpressionEvaluator.evaluator(current));
        } finally {
            current.setIsClientSide(false);
        }
    }

    public void reset() {
        rules.reset();
        pendingVariables.clear();
        pendingExpressions.clear();
        for (var controller : data.getAnimationControllers()) controller.reset();
        for (var bone : bones.values()) {
            bone.rotation.set(bone.getInitialRotation());
            bone.position.zero();
            bone.scale.set(1);
            bone.setHidden(false, false);
        }
        processor.initBones(geometry, events);
        physics.clear();
        initialized = false;
        lastTime = Double.NaN;
        states.clear();
        current = null;
    }

    @Override
    public void close() {
        data.clear();
        processor.reset();
        physics.clear();
    }

    @Override
    public Animation getAnimation(String name) {
        return animations.get(name);
    }

    @Override
    public AnimationController getAnimationEntries(String name) {
        return controllers.get(name);
    }

    @Override
    public AnimationData getAnimationData() {
        return data;
    }

    @Override
    public PhysicsManager getPhysicsManager() {
        return physics;
    }

    @Override
    public IValue resolveExpression(String name) {
        return functions.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<IValue> getRenderLayers() {
        return events.getOrDefault("defer", List.of());
    }

    @Override
    public void setAnimationState(
            String name, com.blanoir.moons.ysm.internal.geckolib3.core.enums.AnimationState state) {
        states.put(name, state.name());
    }

    @Override
    public boolean isSwinging() {
        return flag("is_swinging");
    }

    @Override
    public LocalRuntime runtime() {
        return this;
    }
}
