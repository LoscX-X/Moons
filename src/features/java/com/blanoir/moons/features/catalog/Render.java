package com.blanoir.moons.features.catalog;

import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.render.*;
import com.blanoir.moons.client.ui.clickgui.ModuleGui;
import com.blanoir.moons.client.ui.hud.HudOptions;
import com.blanoir.moons.client.config.feature.HitEstimateSettings;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

/** Defines render module descriptors; ordering is owned by ModuleCatalog. */
final class Render {
    private Render() {
    }

    static ModuleRegistry.Module clickGui() {
        return module("clickgui", "ClickGUI", "Render", ModuleGui::isOpen, ModuleGui::setEnabled, () -> "");
    }

    static ModuleRegistry.Module blockAnimation() {
        return module("blockanimation", "BlockAnimation", ModuleCategories.RENDER,
                        Animations::isEnabled, Animations::setEnabled, Animations::modeName,
                        choice("mode", "Animation", "blockanimation.mode", "1.8",
                                Animations.modeOptions(), Animations::setMode),
                        bool("silentaura_only", "SilentAura only", "blockanimation.silentAuraOnly",
                                false, Animations::setSilentAuraOnly),
                        number("speed", "Swing speed", "blockanimation.swingSpeed",
                                1, .1, 2, .001, Animations::setSwingSpeed),
                        number("offset_x", "Offset X", "blockanimation.offsetX",
                                0, -5, 5, .001, Animations::setOffsetX),
                        number("offset_y", "Offset Y", "blockanimation.offsetY",
                                0, -5, 5, .001, Animations::setOffsetY),
                        number("offset_z", "Offset Z", "blockanimation.offsetZ",
                                0, -5, 5, .001, Animations::setOffsetZ),
                        number("scale", "Scale", "blockanimation.scale",
                                1, .1, 2, .001, Animations::setScale),
                        number("item_size", "Item size", "blockanimation.itemSize",
                                0, -.5, .5, .001, Animations::setItemSize),
                        number("item_rotation_x", "Item rotation X", "blockanimation.itemRotationX",
                                0, -180, 180, 1, Animations::setItemRotationX),
                        number("item_rotation_y", "Item rotation Y", "blockanimation.itemRotationY",
                                0, -180, 180, 1, Animations::setItemRotationY),
                        number("item_rotation", "Item rotation Z", "blockanimation.itemRotation",
                                0, -180, 180, 1, Animations::setItemRotation));
    }

    static ModuleRegistry.Module hud() {
        return module("hud", "Text GUI", "Render", HudOptions::isVisible, HudOptions::setVisible, () -> "",
                        bool("show_title", "Show title bar", "featurehud.showTitle", true,
                                HudOptions::setShowTitle),
                        text("title", "Custom title", HudOptions::titleText, HudOptions::setTitle)
                                .visibleWhen(cfgBool("featurehud.showTitle", true)),
                        colorText("title_color", "Title color", "featurehud.titleColor", "#ffffff",
                                HudOptions::setTitleColor)
                                .visibleWhen(cfgBool("featurehud.showTitle", true)),
                        bool("show_fps", "Show FPS", "featurehud.showFps", true,
                                HudOptions::setShowFps)
                                .visibleWhen(cfgBool("featurehud.showTitle", true)),
                        choice("font_mode", "Font", "featurehud.fontMode", "smooth",
                                HudOptions.fontModeOptions(), HudOptions::setFontMode),
                        bool("text_shadow", "Text shadow", "featurehud.textShadow", true,
                                HudOptions::setTextShadowEnabled),
                        integer("panel_opacity", "Panel opacity", "featurehud.panelOpacity", 35, 0, 100, 1,
                                HudOptions::setPanelOpacity),
                        bool("theme_background", "Use theme background", "featurehud.useThemeBackground", true,
                                HudOptions::setUseThemeBackground),
                        colorText("background_color", "Background color", "featurehud.backgroundColor", "#2d2723",
                                HudOptions::setBackgroundColor),
                        number("scale", "Scale", "featurehud.scale", .78, .5, 2, .01,
                                HudOptions::setScale),
                        integer("x", "Position X", "featurehud.x", -1, -1, 10000, 1,
                                HudOptions::setPositionX),
                        integer("y", "Position Y", "featurehud.y", 5, 0, 10000, 1,
                                HudOptions::setPositionY),
                        bool("theme_color", "Use theme color", "featurehud.useThemeColor", true,
                                HudOptions::setUseThemeColor),
                        choice("name_color_mode", "Name color mode", "featurehud.nameColorMode", "gradient",
                                HudOptions.nameColorModeOptions(), HudOptions::setNameColorMode),
                        choice("gradient_direction", "Gradient direction", "featurehud.gradientDirection",
                                "horizontal", HudOptions.gradientDirectionOptions(), HudOptions::setGradientDirection),
                        colorText("color", "Primary name color", "featurehud.color", "#c49a6c",
                                HudOptions::setColor),
                        colorText("gradient_color", "Secondary name color", "featurehud.gradientColor", "#765cff",
                                HudOptions::setGradientColor),
                        colorText("parameter_color", "Parameter color", "featurehud.parameterColor", "#ffffff",
                                HudOptions::setParameterColor),
                        number("color_speed", "Color speed", "featurehud.colorSpeed", .35, 0, 3, .01,
                                HudOptions::setColorSpeed),
                        number("color_spread", "Color row spread", "featurehud.colorSpread", .025, 0, 1, .005,
                                HudOptions::setColorSpread),
                        number("character_color_spread", "Character color spread", "featurehud.characterColorSpread",
                                .004, 0, .05, .001, HudOptions::setCharacterColorSpread),
                        integer("alpha", "Alpha", "featurehud.alpha", 100, 0, 100, 1,
                                HudOptions::setAlpha));
    }

    static ModuleRegistry.Module scoreboard() {
        return module("scoreboardchanger", "Scoreboard", ModuleCategories.RENDER,
                        ScoreboardChanger::isEnabled, ScoreboardChanger::setEnabled, () -> "",
                        choice("mode", "Mode", "scoreboardchanger.mode", "last_line",
                                ScoreboardChanger.modeOptions(), ScoreboardChanger::setMode),
                        text("last_line", "Last line", "scoreboardchanger.lastLine",
                                "<wave:#55c8ff:#b675ff:0.5>Moons Client</wave>",
                                ScoreboardChanger::setLastLine)
                                .visibleWhen(() -> !ScoreboardChanger.customMode()),
                        text("custom_lines", "Custom lines", "scoreboardchanger.customLines",
                                "<#55c8ff>Moons Client</#55c8ff>|<wave:#55c8ff:#b675ff:0.5>play.example.net</wave>",
                                ScoreboardChanger::setCustomLines)
                                .visibleWhen(ScoreboardChanger::customMode),
                        bool("replace_title", "Replace title", "scoreboardchanger.replaceTitle", false,
                                ScoreboardChanger::setReplaceTitle),
                        text("title", "Custom title", "scoreboardchanger.title",
                                "<gradient:#55c8ff:#b675ff>Moons</gradient>", ScoreboardChanger::setTitle)
                                .visibleWhen(cfgBool("scoreboardchanger.replaceTitle", false)),
                        bool("show_scores", "Show scores", "scoreboardchanger.showScores", true,
                                ScoreboardChanger::setShowScores)
                                .visibleWhen(() -> !ScoreboardChanger.customMode()));
    }

    static ModuleRegistry.Module inventory() {
        return module("inventorysee", "InventorySee", ModuleCategories.RENDER,
                        InventorySee::isEnabled, InventorySee::setEnabled, () -> "",
                        number("scale", "Scale", "inventorysee.scale", 1, .25, 2, .05,
                                InventorySee::setScale));
    }

    static ModuleRegistry.Module targetInfo() {
        return module("targetinfo", "TargetInfo", ModuleCategories.RENDER,
                        TargetInfoHud::isEnabled, TargetInfoHud::setEnabled, HitEstimateSettings::statusText,
                        number("scale", "Scale", "targetinfo.scale", 1, .25, 2, .05,
                                TargetInfoHud::setScale),
                        customChoice("hit_estimate", "Hit estimate", HitEstimateSettings::mode,
                                HitEstimateSettings.modeOptions(), HitEstimateSettings::setMode),
                        customChoice("critical_source", "Critical percent source",
                                HitEstimateSettings::criticalSource,
                                HitEstimateSettings.criticalSourceOptions(),
                                HitEstimateSettings::setCriticalSource)
                                .visibleWhen(HitEstimateSettings::criticalEstimate),
                        number("critical_percent", "Critical percent",
                                "hitestimate.customCriticalPercent", 50, 0, 100, 1,
                                HitEstimateSettings::setCustomCriticalPercent)
                                .visibleWhen(() -> HitEstimateSettings.criticalEstimate()
                                        && !HitEstimateSettings.recordedSource()));
    }

    static ModuleRegistry.Module nametags() {
        return module("nametags", "Nametags", "Render", Nametags::isEnabled, Nametags::setEnabled, () -> "",
                        number("range", "Range", "nametags.range", 128, 8, 256, 1, Nametags::setRange),
                        number("scale", "Scale", "nametags.scale", 1, .5, 4, .05, Nametags::setScale),
                        bool("show_distance", "Show distance", "nametags.distance", true, Nametags::setShowDistance),
                        bool("safe_mode", "Safe mode", "nametags.safeMode", false, Nametags::setSafeMode),
                        customChoice("hit_estimate", "Hit estimate", HitEstimateSettings::mode,
                                HitEstimateSettings.modeOptions(), HitEstimateSettings::setMode),
                        customChoice("critical_source", "Critical percent source",
                                HitEstimateSettings::criticalSource,
                                HitEstimateSettings.criticalSourceOptions(),
                                HitEstimateSettings::setCriticalSource)
                                .visibleWhen(HitEstimateSettings::criticalEstimate),
                        number("critical_percent", "Critical percent",
                                "hitestimate.customCriticalPercent", 50, 0, 100, 1,
                                HitEstimateSettings::setCustomCriticalPercent)
                                .visibleWhen(() -> HitEstimateSettings.criticalEstimate()
                                        && !HitEstimateSettings.recordedSource()),
                        bool("highlighter", "Highlighter", "chams.players.highlighter.enabled", true, Nametags::setHighlighterEnabled),
                        bool("chams", "Chams", "chams.players.enabled", true, Nametags::setChamsEnabled));
    }

    static ModuleRegistry.Module chams() {
        return module("chams", "Chams", "Render", () -> Chams.isHighlighterEnabled() || Chams.isChamsEnabled(),
                        (client, value) -> { Chams.setHighlighterEnabled(value); Chams.setChamsEnabled(value); return 1; }, () -> "",
                        bool("highlighter", "Highlighter", "chams.players.highlighter.enabled", true,
                                (client, value) -> { Chams.setHighlighterEnabled(value); return 1; }),
                        bool("through_walls", "Through walls", "chams.players.enabled", true,
                                (client, value) -> { Chams.setChamsEnabled(value); return 1; }));
    }

    static ModuleRegistry.Module fullBright() {
        return module("fullbright", "FullBright", "Render", FullBright::isEnabled, FullBright::setEnabled,
                        FullBright::modeText,
                        choice("mode", "Mode", "fullbright.mode", "gamma", FullBright.modeOptions(), FullBright::setMode),
                        integer("brightness", "Brightness", "fullbright.brightness", 15, 1, 15, 1, FullBright::setBrightness).visibleWhen(FullBright::gammaMode));
    }

    static ModuleRegistry.Module caver() {
        return module("caver", "Caver", "Render", Caver::isEnabled,
                        (client, value) -> { Caver.setEnabled(client, value); return 1; }, Caver::statusText);
    }

    static ModuleRegistry.Module clip() {
        return module("clip", "Clip", "Render", Clip::isEnabled, Clip::setEnabled, Clip::statusText);
    }

    static ModuleRegistry.Module uhcFinder() {
        return module("uhcfinder", "UhcFinder", "Render", UhcFinder::isEnabled, UhcFinder::setEnabled, () -> "",
                        number("range", "Range", "uhcfinder.range", 256, 8, 1024, 8, UhcFinder::setRange));
    }

    static ModuleRegistry.Module trimChanger() {
        return module("trimchanger", "TrimChanger", ModuleCategories.EXPERIMENT,
                        TrimChanger::isEnabled, TrimChanger::setEnabled, TrimChanger::hudTag,
                        customChoice("trim", "Hoplite trim", TrimChanger::patternId,
                                TrimChanger.patternOptions(), TrimChanger::setPattern),
                        customChoice("material", "Trim material", TrimChanger::materialId,
                                TrimChanger.materialOptions(), TrimChanger::setMaterial),
                        customBool("override", "Override other trims", TrimChanger::overrideOtherTrims,
                                TrimChanger::setOverrideOtherTrims));
    }

    static ModuleRegistry.Module offlinePlayerDetect() {
        return module("offlineplayerdetect", "DetectOfflinePlayer", ModuleCategories.MISC, OfflinePlayerDetect::isEnabled,
                        OfflinePlayerDetect::setEnabled, () -> "Hoplite");
    }
}
