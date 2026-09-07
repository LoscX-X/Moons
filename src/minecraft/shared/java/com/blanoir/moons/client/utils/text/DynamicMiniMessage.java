package com.blanoir.moons.client.utils.text;

import com.blanoir.moons.client.utils.render.ArgbColors;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small, safe MiniMessage-style parser shared by local dynamic text features. */
public final class DynamicMiniMessage {
    private DynamicMiniMessage() {}

    public static Component parse(String input) {
        return parse(input, System.currentTimeMillis());
    }

    public static Component parse(String input, long animationTimeMillis) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        List<Glyph> glyphs = new ArrayList<>();
        ArrayDeque<Frame> frames = new ArrayDeque<>();
        Style style = Style.EMPTY;

        for (int offset = 0; offset < input.length(); ) {
            char current = input.charAt(offset);
            if (current == '\\' && offset + 1 < input.length() && input.charAt(offset + 1) == '<') {
                glyphs.add(new Glyph('<', style));
                offset += 2;
                continue;
            }

            if (current == '<') {
                int end = input.indexOf('>', offset + 1);
                if (end >= 0) {
                    String rawTag = input.substring(offset + 1, end);
                    TagResult result = applyTag(rawTag, glyphs, frames, style, animationTimeMillis);
                    if (result.handled()) {
                        style = result.style();
                        offset = end + 1;
                        continue;
                    }
                }
            }

            int codePoint = input.codePointAt(offset);
            glyphs.add(new Glyph(codePoint, style));
            offset += Character.charCount(codePoint);
        }

        // Loose MiniMessage semantics: an unclosed gradient runs to the end.
        for (Frame frame : frames) {
            if (frame.gradient() != null) {
                applyGradient(glyphs, frame.startIndex(), frame.gradient());
            }
            if (frame.animation() != null) {
                applyAnimation(glyphs, frame.startIndex(), frame.animation(), animationTimeMillis);
            }
        }
        return build(glyphs);
    }

    private static TagResult applyTag(
            String raw,
            List<Glyph> glyphs,
            ArrayDeque<Frame> frames,
            Style current,
            long animationTimeMillis) {
        String tag = raw.trim().toLowerCase(Locale.ROOT);
        if (tag.isEmpty()) {
            return TagResult.unhandled(current);
        }
        if ("reset".equals(tag)) {
            frames.clear();
            return TagResult.handled(Style.EMPTY);
        }

        if (tag.startsWith("/")) {
            String closing = canonicalTag(tag.substring(1));
            Frame frame = frames.peek();
            if (frame == null || !frame.name().equals(closing)) {
                return TagResult.unhandled(current);
            }
            frames.pop();
            if (frame.gradient() != null) {
                applyGradient(glyphs, frame.startIndex(), frame.gradient());
            }
            if (frame.animation() != null) {
                applyAnimation(glyphs, frame.startIndex(), frame.animation(), animationTimeMillis);
            }
            return TagResult.handled(frame.previousStyle());
        }

        Animation animation = parseAnimation(tag);
        if (animation != null) {
            frames.push(
                    new Frame(animation.type().tagName(), current, glyphs.size(), null, animation));
            return TagResult.handled(current);
        }

        Gradient gradient = parseGradient(tag);
        if (gradient != null) {
            frames.push(new Frame("gradient", current, glyphs.size(), gradient, null));
            return TagResult.handled(current);
        }

        Style fontStyle = applyFont(tag, current);
        if (fontStyle != null) {
            frames.push(new Frame("font", current, glyphs.size(), null, null));
            return TagResult.handled(fontStyle);
        }

        Style decorated = applyDecoration(tag, current);
        if (decorated != null) {
            frames.push(new Frame(canonicalTag(tag), current, glyphs.size(), null, null));
            return TagResult.handled(decorated);
        }

        TextColor color = parseColorTag(tag);
        if (color != null) {
            frames.push(new Frame(colorFrameName(tag), current, glyphs.size(), null, null));
            return TagResult.handled(current.withColor(color));
        }
        return TagResult.unhandled(current);
    }

    private static Style applyDecoration(String tag, Style style) {
        return switch (canonicalTag(tag)) {
            case "bold" -> style.withBold(true);
            case "italic" -> style.withItalic(true);
            case "underlined" -> style.withUnderlined(true);
            case "strikethrough" -> style.withStrikethrough(true);
            case "obfuscated" -> style.withObfuscated(true);
            default -> null;
        };
    }

    private static Style applyFont(String tag, Style style) {
        if (!tag.startsWith("font:")) {
            return null;
        }
        Identifier id = Identifier.tryParse(tag.substring("font:".length()).trim());
        return id == null ? null : style.withFont(new FontDescription.Resource(id));
    }

    private static String canonicalTag(String tag) {
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "b" -> "bold";
            case "i", "em" -> "italic";
            case "u", "underline" -> "underlined";
            case "st" -> "strikethrough";
            case "obf" -> "obfuscated";
            default -> normalized.startsWith("gradient:") ? "gradient" : normalized;
        };
    }

    private static String colorFrameName(String tag) {
        return tag.startsWith("color:") || tag.startsWith("colour:") || tag.startsWith("c:")
                ? "color"
                : canonicalTag(tag);
    }

    private static TextColor parseColorTag(String tag) {
        String value = tag;
        if (tag.startsWith("color:") || tag.startsWith("colour:")) {
            value = tag.substring(tag.indexOf(':') + 1).trim();
        } else if (tag.startsWith("c:")) {
            value = tag.substring(2).trim();
        }
        if (value.startsWith("#") && value.length() == 7) {
            try {
                return TextColor.fromRgb(Integer.parseInt(value.substring(1), 16));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value.startsWith("0x") && value.length() == 8) {
            try {
                return TextColor.fromRgb(Integer.parseInt(value.substring(2), 16));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        String named = value.replace("grey", "gray");
        try {
            ChatFormatting formatting = ChatFormatting.valueOf(named.toUpperCase(Locale.ROOT));
            return formatting.ordinal() <= ChatFormatting.WHITE.ordinal()
                    ? TextColor.fromLegacyFormat(formatting)
                    : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Gradient parseGradient(String tag) {
        if (!tag.startsWith("gradient:")) {
            return null;
        }
        String[] parts = tag.substring("gradient:".length()).split(":");
        List<Integer> colors = new ArrayList<>();
        for (String part : parts) {
            TextColor color = parseColorTag(part.trim());
            if (color == null) {
                return null;
            }
            colors.add(color.getValue());
        }
        if (colors.size() < 2) {
            return null;
        }
        int[] values = new int[colors.size()];
        for (int index = 0; index < colors.size(); index++) {
            values[index] = colors.get(index);
        }
        return new Gradient(values);
    }

    private static Animation parseAnimation(String tag) {
        String[] parts = tag.split(":");
        AnimationType type = AnimationType.fromTag(parts[0]);
        if (type == null) {
            return null;
        }

        if (type.usesBuiltInPalette()) {
            double speed = parts.length >= 2 ? parseSpeed(parts[1], 1.0D) : 1.0D;
            return new Animation(type, 0, 0, speed);
        }
        if (parts.length < 3) {
            return null;
        }
        TextColor first = parseColorTag(parts[1]);
        TextColor second = parseColorTag(parts[2]);
        if (first == null || second == null) {
            return null;
        }
        double speed = parts.length >= 4 ? parseSpeed(parts[3], 1.0D) : 1.0D;
        return new Animation(type, first.getValue(), second.getValue(), speed);
    }

    private static double parseSpeed(String raw, double fallback) {
        try {
            return Math.max(0.05D, Math.min(8.0D, Double.parseDouble(raw)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void applyAnimation(
            List<Glyph> glyphs, int start, Animation animation, long timeMillis) {
        int count = glyphs.size() - start;
        if (count <= 0) {
            return;
        }
        double seconds = timeMillis / 1000.0D;
        for (int index = 0; index < count; index++) {
            int color =
                    switch (animation.type()) {
                        case RAINBOW ->
                                ArgbColors.rainbowRgb(
                                        seconds * animation.speed()
                                                + (double) index / Math.max(6, count));
                        case PULSE ->
                                ArgbColors.interpolateRgb(
                                        new int[] {animation.firstColor(), animation.secondColor()},
                                        (Math.sin(seconds * animation.speed() * Math.PI * 2.0D)
                                                        + 1.0D)
                                                * 0.5D);
                        case WAVE ->
                                ArgbColors.interpolateRgb(
                                        new int[] {animation.firstColor(), animation.secondColor()},
                                        (Math.sin(
                                                                seconds
                                                                                * animation.speed()
                                                                                * Math.PI
                                                                                * 2.0D
                                                                        - index * 0.72D)
                                                        + 1.0D)
                                                * 0.5D);
                        case SHINE -> {
                            double travel = seconds * animation.speed();
                            double center = (travel - Math.floor(travel)) * (count + 4.0D) - 2.0D;
                            double distance = index - center;
                            double strength = Math.exp(-(distance * distance) / 1.35D);
                            yield ArgbColors.interpolateRgb(
                                    new int[] {animation.firstColor(), animation.secondColor()},
                                    strength);
                        }
                        case AURORA ->
                                ArgbColors.interpolateRgb(
                                        new int[] {0x59FFC5, 0x55C8FF, 0xB675FF, 0x59FFC5},
                                        positiveWavePosition(
                                                seconds * animation.speed() * 0.32D
                                                        + (double) index / Math.max(4, count)));
                        case FIRE -> {
                            double flicker =
                                    (Math.sin(seconds * animation.speed() * 10.0D + index * 1.71D)
                                                    + Math.sin(
                                                                    seconds
                                                                                    * animation
                                                                                            .speed()
                                                                                    * 17.0D
                                                                            - index * 0.83D)
                                                            * 0.45D
                                                    + 1.45D)
                                            / 2.9D;
                            yield ArgbColors.interpolateRgb(
                                    new int[] {0xC81D11, 0xFF7200, 0xFFE66D}, flicker);
                        }
                        case SPARKLE -> {
                            long sparkleTick =
                                    (long) Math.floor(seconds * animation.speed() * 12.0D);
                            long hash =
                                    (index + 1L) * 0x9E3779B97F4A7C15L
                                            ^ sparkleTick * 0xC2B2AE3D27D4EB4FL;
                            double strength = Long.remainderUnsigned(hash, 9L) == 0L ? 1.0D : 0.12D;
                            yield ArgbColors.interpolateRgb(
                                    new int[] {animation.firstColor(), animation.secondColor()},
                                    strength);
                        }
                        case CHASE -> {
                            double travel = seconds * animation.speed();
                            double center = (travel - Math.floor(travel)) * Math.max(1, count);
                            double direct = Math.abs(index - center);
                            double wrapped = Math.min(direct, Math.max(1, count) - direct);
                            double strength = Math.exp(-(wrapped * wrapped) / 0.72D);
                            yield ArgbColors.interpolateRgb(
                                    new int[] {animation.firstColor(), animation.secondColor()},
                                    strength);
                        }
                    };
            Glyph glyph = glyphs.get(start + index);
            glyphs.set(start + index, new Glyph(glyph.codePoint(), glyph.style().withColor(color)));
        }
    }

    private static double positiveWavePosition(double position) {
        double wrapped = position - Math.floor(position);
        return wrapped <= 0.5D ? wrapped * 2.0D : (1.0D - wrapped) * 2.0D;
    }

    private static void applyGradient(List<Glyph> glyphs, int start, Gradient gradient) {
        int count = glyphs.size() - start;
        if (count <= 0) {
            return;
        }
        for (int index = 0; index < count; index++) {
            double position = count == 1 ? 0.0D : (double) index / (count - 1);
            int color = ArgbColors.interpolateRgb(gradient.colors(), position);
            Glyph glyph = glyphs.get(start + index);
            glyphs.set(start + index, new Glyph(glyph.codePoint(), glyph.style().withColor(color)));
        }
    }

    private static Component build(List<Glyph> glyphs) {
        MutableComponent result = Component.empty();
        if (glyphs.isEmpty()) {
            return result;
        }
        StringBuilder text = new StringBuilder();
        Style style = glyphs.getFirst().style();
        for (Glyph glyph : glyphs) {
            if (!glyph.style().equals(style)) {
                result.append(Component.literal(text.toString()).setStyle(style));
                text.setLength(0);
                style = glyph.style();
            }
            text.appendCodePoint(glyph.codePoint());
        }
        if (!text.isEmpty()) {
            result.append(Component.literal(text.toString()).setStyle(style));
        }
        return result;
    }

    private record Glyph(int codePoint, Style style) {}

    private record Frame(
            String name,
            Style previousStyle,
            int startIndex,
            Gradient gradient,
            Animation animation) {}

    private record Gradient(int[] colors) {}

    private record Animation(AnimationType type, int firstColor, int secondColor, double speed) {}

    private enum AnimationType {
        RAINBOW("rainbow"),
        PULSE("pulse"),
        WAVE("wave"),
        SHINE("shine"),
        AURORA("aurora"),
        FIRE("fire"),
        SPARKLE("sparkle"),
        CHASE("chase");

        private final String tagName;

        AnimationType(String tagName) {
            this.tagName = tagName;
        }

        String tagName() {
            return tagName;
        }

        boolean usesBuiltInPalette() {
            return this == RAINBOW || this == AURORA || this == FIRE;
        }

        static AnimationType fromTag(String tag) {
            for (AnimationType type : values()) {
                if (type.tagName.equals(tag)) {
                    return type;
                }
            }
            return null;
        }
    }

    private record TagResult(boolean handled, Style style) {
        static TagResult handled(Style style) {
            return new TagResult(true, style);
        }

        static TagResult unhandled(Style style) {
            return new TagResult(false, style);
        }
    }
}
