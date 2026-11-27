package com.blanoir.tempos.client.module.impl.render;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.cfgBool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.choice;
import static com.blanoir.tempos.client.module.framework.SettingFactory.colorText;
import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;
import static com.blanoir.tempos.client.module.framework.SettingFactory.number;
import static com.blanoir.tempos.client.module.framework.SettingFactory.text;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.ui.hud.HudOptions;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Configures the title, enabled-module list, colors, and other HUD text.")
public final class TextGui extends ClientFeature {
    public static final TextGui INSTANCE = new TextGui();

    private TextGui() {
        super("hud", "Text GUI");
    }

    @Override
    public boolean isEnabled() {
        return HudOptions.isVisible();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return HudOptions.setVisible(client, value);
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            bool(
                    "show_title",
                    "Show title bar",
                    "featurehud.showTitle",
                    true,
                    HudOptions::setShowTitle),
            text("title", "Custom title", HudOptions::titleText, HudOptions::setTitle)
                    .withDefault(com.blanoir.tempos.client.config.ClientBranding.DEFAULT_NAME)
                    .visibleWhen(cfgBool("featurehud.showTitle", true)),
            colorText(
                            "title_color",
                            "Title color",
                            "featurehud.titleColor",
                            "#ffffff",
                            HudOptions::setTitleColor)
                    .visibleWhen(cfgBool("featurehud.showTitle", true)),
            bool("show_fps", "Show FPS", "featurehud.showFps", true, HudOptions::setShowFps)
                    .visibleWhen(cfgBool("featurehud.showTitle", true)),
            choice(
                    "font_mode",
                    "Font",
                    "featurehud.fontMode",
                    "smooth",
                    HudOptions.fontModeOptions(),
                    HudOptions::setFontMode),
            bool(
                    "text_shadow",
                    "Text shadow",
                    "featurehud.textShadow",
                    true,
                    HudOptions::setTextShadowEnabled),
            integer(
                    "panel_opacity",
                    "Panel opacity",
                    "featurehud.panelOpacity",
                    35,
                    0,
                    100,
                    1,
                    HudOptions::setPanelOpacity),
            bool(
                    "theme_background",
                    "Use theme background",
                    "featurehud.useThemeBackground",
                    true,
                    HudOptions::setUseThemeBackground),
            colorText(
                    "background_color",
                    "Background color",
                    "featurehud.backgroundColor",
                    "#2d2723",
                    HudOptions::setBackgroundColor),
            number("scale", "Scale", "featurehud.scale", .78, .5, 2, .01, HudOptions::setScale),
            integer("x", "Position X", "featurehud.x", -1, -1, 10000, 1, HudOptions::setPositionX),
            integer("y", "Position Y", "featurehud.y", 5, 0, 10000, 1, HudOptions::setPositionY),
            bool(
                    "theme_color",
                    "Use theme color",
                    "featurehud.useThemeColor",
                    true,
                    HudOptions::setUseThemeColor),
            choice(
                    "name_color_mode",
                    "Name color mode",
                    "featurehud.nameColorMode",
                    "gradient",
                    HudOptions.nameColorModeOptions(),
                    HudOptions::setNameColorMode),
            choice(
                    "gradient_direction",
                    "Gradient direction",
                    "featurehud.gradientDirection",
                    "horizontal",
                    HudOptions.gradientDirectionOptions(),
                    HudOptions::setGradientDirection),
            colorText(
                    "color",
                    "Primary name color",
                    "featurehud.color",
                    "#c49a6c",
                    HudOptions::setColor),
            colorText(
                    "gradient_color",
                    "Secondary name color",
                    "featurehud.gradientColor",
                    "#765cff",
                    HudOptions::setGradientColor),
            colorText(
                    "parameter_color",
                    "Parameter color",
                    "featurehud.parameterColor",
                    "#969696",
                    HudOptions::setParameterColor),
            number(
                    "color_speed",
                    "Color speed",
                    "featurehud.colorSpeed",
                    .35,
                    0,
                    3,
                    .01,
                    HudOptions::setColorSpeed),
            number(
                    "color_spread",
                    "Color row spread",
                    "featurehud.colorSpread",
                    .025,
                    0,
                    1,
                    .005,
                    HudOptions::setColorSpread),
            number(
                    "character_color_spread",
                    "Character color spread",
                    "featurehud.characterColorSpread",
                    .004,
                    0,
                    .05,
                    .001,
                    HudOptions::setCharacterColorSpread),
            integer("alpha", "Alpha", "featurehud.alpha", 100, 0, 100, 1, HudOptions::setAlpha)
        };
    }

    @Override
    public ModuleModel.Module toDescriptor() {
        return super.toDescriptor().withDefaultEnabled(true);
    }
}
