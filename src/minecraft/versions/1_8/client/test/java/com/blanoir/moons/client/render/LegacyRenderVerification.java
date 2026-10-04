package com.blanoir.moons.client.render;

import com.blanoir.moons.client.utils.plugin.PluginModelIndex;
import com.blanoir.moons.client.utils.text.DynamicMiniMessage;
import com.blanoir.moons.client.utils.text.RgbChatStyle;
import com.google.gson.JsonParser;

import net.minecraft.block.BlockStone;
import net.minecraft.client.renderer.block.model.ModelBlockDefinition;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;

import java.io.StringReader;
import java.util.*;

/** Pure real-jar checks for old blockstate resolution and styled Unicode text. */
public final class LegacyRenderVerification {
    public static void main(String[] args) {
        Bootstrap.register();
        String normal =
                "{\"variants\":{\"normal\":{\"model\":\"custom:ore\"},\"inventory\":{\"model\":\"inventory_only\"}}}";
        var nativeModel =
                ModelBlockDefinition.parseFromReader(new StringReader(normal))
                        .getVariants("normal")
                        .getVariants()
                        .getFirst()
                        .getModelLocation();
        var models =
                PluginModelIndex.modelsForState(
                        JsonParser.parseString(normal).getAsJsonObject(),
                        Blocks.stone.getDefaultState());
        equal(
                Set.of(nativeModel),
                models,
                "Native 1.8 block prefix and excluded inventory variant");
        String weighted =
                "{\"variants\":{\"variant=stone\":[{\"model\":\"stone\"},{\"model\":\"pack:custom/ore\",\"weight\":2}],\"variant=granite\":{\"model\":\"granite\"}}}";
        equal(
                Set.of(
                        new ResourceLocation("minecraft:block/stone"),
                        new ResourceLocation("pack:block/custom/ore")),
                PluginModelIndex.modelsForState(
                        JsonParser.parseString(weighted).getAsJsonObject(),
                        Blocks.stone.getDefaultState()),
                "Weighted model alternatives");
        equal(
                Set.of(new ResourceLocation("minecraft:block/granite")),
                PluginModelIndex.modelsForState(
                        JsonParser.parseString(weighted).getAsJsonObject(),
                        Blocks.stone
                                .getDefaultState()
                                .withProperty(BlockStone.VARIANT, BlockStone.EnumType.GRANITE)),
                "Actual property variant");
        IChatComponent text =
                DynamicMiniMessage.parse("<gradient:#ff0000:#00ff00>A中😀Z</gradient>", 1000);
        equal("A中😀Z", text.getUnformattedText(), "Unicode text intact");
        List<Integer> colors = new ArrayList<>();
        for (IChatComponent part : text)
            if (!part.getUnformattedTextForChat().isEmpty()) {
                if (!(part.getChatStyle() instanceof RgbChatStyle style))
                    throw new AssertionError("RGB style lost");
                colors.add(style.rgb());
                if (!part.getChatStyle().createDeepCopy().equals(style))
                    throw new AssertionError("RGB copy lost");
            }
        if (colors.size() < 2 || colors.getFirst() != 0xff0000 || colors.getLast() != 0x00ff00)
            throw new AssertionError("Gradient endpoints: " + colors);
        equal("<tag>", DynamicMiniMessage.parse("\\<tag>").getUnformattedText(), "Escaped markup");
        equal(
                "Bold plain",
                DynamicMiniMessage.parse("<bold>Bold</bold> plain").getUnformattedText(),
                "Nested style closes");
        System.out.println(
                "LEGACY_RENDER_VERIFIED vanilla-model-paths weighted-state-variants inventory-exclusion unicode-rgb-style");
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(label + ": " + actual + " != " + expected);
    }
}
