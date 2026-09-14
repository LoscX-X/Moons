package com.blanoir.moons.ysm;

import com.blanoir.moons.ysm.internal.resource.pojo.RawYsmModel.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Prepared on a loader thread, then exclusively owned by the render thread after publication. */
public final class YsmSession implements AutoCloseable {
    public record Parameter(String id, String group, ConfigForm form) {}

    public record Action(String id, String label, String group, double duration) {}

    private final LocalYsmModel model;
    private final LocalYsmModel firstPerson;
    private final Path preferences;
    private final YsmPoseController pose;
    private final Map<String, Parameter> parameters = new LinkedHashMap<>();
    private final Map<String, Double> overrides = new LinkedHashMap<>();
    private final List<Action> actions = new ArrayList<>();
    private String animation = "", texture = "", result = "";
    private byte[] texturePng;
    private double speed = 1, time, lastSource = Double.NaN;
    private boolean paused, dirty, textureDirty;
    private double seekTarget = Double.NaN;
    private Map<String, ?> observations = Map.of();

    public YsmSession(LocalYsmModel model, Path preferences, String animation, String texture)
            throws IOException {
        this.model = model;
        this.firstPerson = model.firstPersonModel();
        this.preferences = preferences;
        this.animation = animation;
        try {
            if (texture.isBlank()
                    && preferences != null
                    && Files.isRegularFile(texturePreferences())) {
                try (var reader = Files.newBufferedReader(texturePreferences())) {
                    String saved = new Gson().fromJson(reader, String.class);
                    if (saved != null && model.textures().contains(saved)) texture = saved;
                } catch (RuntimeException error) {
                    result = "Could not read saved texture: " + error.getMessage();
                }
            }
            this.texture = model.texture(texture).name();
            for (ExtraAnimationButton group : model.properties().extraAnimationButtons) {
                int index = 0;
                for (ConfigForm form : group.forms) {
                    String id = group.id + "/" + index++;
                    parameters.put(
                            id, new Parameter(id, Objects.toString(group.name, group.id), form));
                }
            }
            Map<String, String> labels = new LinkedHashMap<>();
            Map<String, String> groups = new LinkedHashMap<>();
            model.properties()
                    .extraAnimations
                    .forEach(
                            (id, label) -> {
                                if (!id.startsWith("#") && !label.startsWith("#")) {
                                    labels.put(id, label);
                                    groups.put(id, "Extra");
                                }
                            });
            for (ExtraAnimationClassify group : model.properties().extraAnimationClassifies)
                group.extras.forEach(
                        (id, label) -> {
                            labels.put(id, label);
                            groups.put(id, group.id);
                        });
            model.runtime()
                    .animationNames()
                    .forEach(
                            name -> {
                                var a = model.runtime().getAnimation(name);
                                double duration = a.animationLength / 20d;
                                String group =
                                        groups.getOrDefault(
                                                name,
                                                switch (model.runtime()
                                                        .animationTypes()
                                                        .getOrDefault(name, 0)) {
                                                    case 2 -> "Hands";
                                                    case 3 -> "Extra";
                                                    case 11 -> "First person";
                                                    default -> "Main";
                                                });
                                actions.add(
                                        new Action(
                                                name,
                                                labels.getOrDefault(name, name),
                                                group,
                                                Double.isFinite(duration) ? duration : 0));
                            });
            firstPerson
                    .runtime()
                    .animationNames()
                    .forEach(
                            name -> {
                                var a = firstPerson.runtime().getAnimation(name);
                                double duration = a.animationLength / 20d;
                                actions.add(
                                        new Action(
                                                "fp.arm/" + name,
                                                name,
                                                "First person",
                                                Double.isFinite(duration) ? duration : 0));
                            });
            if (preferences != null && Files.isRegularFile(preferences)) {
                try (var reader = Files.newBufferedReader(preferences)) {
                    Map<String, Double> saved =
                            new Gson()
                                    .fromJson(
                                            reader,
                                            new TypeToken<Map<String, Double>>() {}.getType());
                    if (saved != null)
                        for (var entry : saved.entrySet())
                            if (parameters.containsKey(entry.getKey())
                                    && entry.getValue() != null
                                    && Double.isFinite(entry.getValue()))
                                setParameter(entry.getKey(), entry.getValue());
                    dirty = false;
                } catch (RuntimeException error) {
                    result = "Could not read saved parameters: " + error.getMessage();
                }
            }
            prepareTexture(this.texture);
            pose =
                    new YsmPoseController(
                            model,
                            preferences == null
                                    ? null
                                    : preferences.resolveSibling(
                                            preferences.getFileName() + ".pose.json"));
        } catch (IOException | RuntimeException | Error failure) {
            firstPerson.close();
            throw failure;
        }
    }

    public LocalYsmModel.Mesh frame(double sourceTime, Map<String, ?> values) {
        return frame(sourceTime, values, true);
    }

    public void updatePose(double sourceTime, Map<String, ?> values) {
        frame(sourceTime, values, false);
    }

    private LocalYsmModel.Mesh frame(double sourceTime, Map<String, ?> values, boolean geometry) {
        // Different views can submit interpolations of the same game tick out of order.
        // Keep both the current pose and the latest timestamp; moving lastSource backwards
        // would count the same interval again on the next pass. Actual world resets below
        // and explicit studio seeks still reset the controllers and their scoped variables.
        if (Double.isFinite(lastSource)
                && sourceTime < lastSource
                && lastSource - sourceTime <= .05 + 1e-6)
            return render(observations, geometry);
        observations = values;
        if (Double.isFinite(lastSource) && sourceTime >= lastSource && !paused && !seeking())
            time += Math.min(sourceTime - lastSource, 1) * speed;
        else if (Double.isFinite(lastSource) && sourceTime < lastSource) resetWorld();
        lastSource = sourceTime;
        if (seeking()) {
            long deadline = System.nanoTime() + 8_000_000L;
            model.runtime().suppressEffects(true);
            firstPerson.runtime().suppressEffects(true);
            try {
                advance(time, values);
                for (int i = 0; i < 64 && time < seekTarget; i++) {
                    time = Math.min(seekTarget, time + .05);
                    advance(time, values);
                    if (System.nanoTime() >= deadline) break;
                }
                if (time >= seekTarget) seekTarget = Double.NaN;
                return render(values, geometry);
            } finally {
                model.runtime().suppressEffects(false);
                firstPerson.runtime().suppressEffects(false);
            }
        }
        return render(values, geometry);
    }

    private LocalYsmModel.Mesh render(Map<String, ?> values, boolean geometry) {
        if (geometry) return model.frame(time, values, bodyAnimation());
        model.updatePose(time, values, bodyAnimation());
        return null;
    }

    private void advance(double at, Map<String, ?> values) {
        model.runtime().update(at, values, bodyAnimation());
        model.runtime()
                .variables()
                .forEach((name, value) -> firstPerson.runtime().variable(name, value));
        firstPerson.runtime().update(at, values, firstPersonAnimation());
    }

    private String bodyAnimation() {
        return animation.startsWith("fp.arm/") ? "" : animation;
    }

    public String firstPersonAnimation() {
        return animation.startsWith("fp.arm/") ? animation.substring(7) : "";
    }

    public LocalYsmModel firstPerson() {
        return firstPerson;
    }

    public void setParameter(String id, double value) {
        Parameter parameter = parameters.get(id);
        if (parameter == null || !Double.isFinite(value))
            throw new IllegalArgumentException("Invalid model parameter");
        ConfigForm form = parameter.form();
        if (form.type.equals("checkbox")) value = value == 0 ? 0 : 1;
        else if (form.type.equals("radio")) {
            if (value != Math.rint(value) || value < 0 || value >= form.labels.size())
                throw new IllegalArgumentException("Unknown option");
        } else {
            value = Math.max(form.min, Math.min(form.max, value));
            if (form.step > 0)
                value =
                        Math.clamp(
                                form.min + Math.rint((value - form.min) / form.step) * form.step,
                                form.min,
                                form.max);
        }
        apply(parameter, value);
        overrides.put(id, value);
        dirty = true;
    }

    private void apply(Parameter parameter, double value) {
        ConfigForm form = parameter.form();
        if (form.type.equals("radio")) {
            model.runtime()
                    .queueExpression(
                            parameter.id(), new ArrayList<>(form.labels.values()).get((int) value));
            return;
        }
        String expression = form.defaultValue;
        if (expression == null || expression.isBlank())
            throw new IllegalArgumentException("Missing parameter expression");
        if (expression.matches("(?i)(v|variable)\\.[a-z0-9_]+(\\.[a-z0-9_]+)*"))
            model.runtime().variable(expression, value);
        else model.runtime().queueExpression(parameter.id(), expression + "=" + value + ";");
    }

    public Map<String, Double> values() {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Parameter p : parameters.values()) {
            Double value = model.runtime().variableValue(p.form().defaultValue);
            if (value == null
                    && p.form().defaultValue != null
                    && !p.form().defaultValue.isBlank()) {
                Object evaluated = model.runtime().evaluate(p.form().defaultValue);
                if (evaluated instanceof Number n && Double.isFinite(n.doubleValue()))
                    value = n.doubleValue();
                else if (evaluated instanceof Boolean b) value = b ? 1d : 0d;
            }
            if (value == null) value = overrides.get(p.id());
            if (value != null) result.put(p.id(), value);
        }
        return result;
    }

    public void play(String name) {
        if (!name.isEmpty() && actions.stream().noneMatch(a -> a.id().equals(name)))
            throw new IllegalArgumentException("Unknown animation: " + name);
        animation = name;
        seekTarget = Double.NaN;
        time = 0;
        lastSource = Double.NaN;
        model.resetAnimation();
        firstPerson.resetAnimation();
        overrides.forEach((id, value) -> apply(parameters.get(id), value));
    }

    public void playback(double speed, boolean paused) {
        if (!Double.isFinite(speed) || speed < .05 || speed > 4)
            throw new IllegalArgumentException("Speed must be between 0.05 and 4");
        this.speed = speed;
        this.paused = paused;
    }

    public void seek(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 300)
            throw new IllegalArgumentException("Seek must be between 0 and 300 seconds");
        resetWorld();
        seekTarget = seconds;
    }

    public boolean seeking() {
        return Double.isFinite(seekTarget);
    }

    public void resetParameters() {
        overrides.clear();
        dirty = true;
        resetWorld();
    }

    public void resetWorld() {
        seekTarget = Double.NaN;
        model.resetAnimation();
        firstPerson.resetAnimation();
        time = 0;
        lastSource = Double.NaN;
        overrides.forEach((id, value) -> apply(parameters.get(id), value));
    }

    public void evaluate(String expression) {
        if (expression.length() > 16384)
            throw new IllegalArgumentException("Expression exceeds 16 KiB");
        try {
            result = Objects.toString(model.runtime().evaluate(expression), "null");
        } catch (RuntimeException error) {
            result = error.toString();
        }
    }

    public void save() throws IOException {
        pose.save();
        if (textureDirty && preferences != null) {
            Files.createDirectories(preferences.getParent());
            Path target = texturePreferences(),
                    temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, new Gson().toJson(texture));
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            textureDirty = false;
        }
        if (!dirty || preferences == null) return;
        Files.createDirectories(preferences.getParent());
        Path temp = preferences.resolveSibling(preferences.getFileName() + ".tmp");
        Files.writeString(temp, new Gson().toJson(overrides));
        try {
            Files.move(
                    temp,
                    preferences,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, preferences, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    private Path texturePreferences() {
        return preferences.resolveSibling(preferences.getFileName() + ".texture.json");
    }

    public void texture(String name) throws IOException {
        if (!model.textures().contains(name)) throw new IllegalArgumentException("Unknown texture");
        if (texture.equals(name)) return;
        try {
            prepareTexture(name);
        } catch (IOException | RuntimeException failure) {
            try {
                prepareTexture(texture);
            } catch (IOException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        texture = name;
        textureDirty = true;
    }

    private void prepareTexture(String name) throws IOException {
        var pixels = YsmTextureDecoder.decode(model.texture(name));
        try {
            byte[] png = YsmTextureDecoder.toPng(pixels);
            model.prepareTexture(name, pixels);
            firstPerson.prepareTexture(name, pixels);
            texturePng = png;
        } finally {
            pixels.flush();
        }
    }

    /** Already encoded during preparation; the adapter only needs to upload it. */
    public byte[] texturePng() {
        return texturePng;
    }

    public LocalYsmModel model() {
        return model;
    }

    public YsmPoseController pose() {
        return pose;
    }

    public List<Parameter> parameters() {
        return List.copyOf(parameters.values());
    }

    public List<Action> actions() {
        return List.copyOf(actions);
    }

    public String animation() {
        return animation;
    }

    public String texture() {
        return texture;
    }

    public String result() {
        return seeking()
                ? String.format(Locale.ROOT, "Rebuilding timeline %.2f / %.2f s", time, seekTarget)
                : result;
    }

    public double time() {
        return time;
    }

    public double speed() {
        return speed;
    }

    public boolean paused() {
        return paused;
    }

    public void close() {
        firstPerson.close();
        model.close();
    }
}
