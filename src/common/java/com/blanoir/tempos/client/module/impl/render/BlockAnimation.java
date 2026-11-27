package com.blanoir.tempos.client.module.impl.render;

import static com.blanoir.tempos.client.module.framework.SettingFactory.bool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.choice;
import static com.blanoir.tempos.client.module.framework.SettingFactory.number;

import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.render.animation.Animations;

import net.minecraft.client.Minecraft;

@ModuleInfo(
        category = ModuleCategory.RENDER,
        description = "Customizes first-person swing and blocking animations.")
public final class BlockAnimation extends ClientFeature {
    public static final BlockAnimation INSTANCE = new BlockAnimation();

    private BlockAnimation() {
        super("blockanimation", "BlockAnimation");
    }

    @Override
    public boolean isEnabled() {
        return Animations.isEnabled();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        return Animations.setEnabled(client, value);
    }

    @Override
    protected String statusTag() {
        return Animations.modeName();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            choice(
                    "mode",
                    "Animation",
                    "blockanimation.mode",
                    "1.8",
                    Animations.modeOptions(),
                    Animations::setMode),
            bool(
                    "silentaura_only",
                    "Combat blocking only",
                    "blockanimation.silentAuraOnly",
                    false,
                    Animations::setSilentAuraOnly),
            number(
                    "speed",
                    "Swing speed",
                    "blockanimation.swingSpeed",
                    1,
                    .1,
                    2,
                    .001,
                    Animations::setSwingSpeed),
            number(
                    "offset_x",
                    "Offset X",
                    "blockanimation.offsetX",
                    0,
                    -5,
                    5,
                    .001,
                    Animations::setOffsetX),
            number(
                    "offset_y",
                    "Offset Y",
                    "blockanimation.offsetY",
                    0,
                    -5,
                    5,
                    .001,
                    Animations::setOffsetY),
            number(
                    "offset_z",
                    "Offset Z",
                    "blockanimation.offsetZ",
                    0,
                    -5,
                    5,
                    .001,
                    Animations::setOffsetZ),
            number("scale", "Scale", "blockanimation.scale", 1, .1, 2, .001, Animations::setScale),
            number(
                    "item_size",
                    "Item size",
                    "blockanimation.itemSize",
                    0,
                    -.5,
                    .5,
                    .001,
                    Animations::setItemSize),
            number(
                    "item_rotation_x",
                    "Item rotation X",
                    "blockanimation.itemRotationX",
                    0,
                    -180,
                    180,
                    1,
                    Animations::setItemRotationX),
            number(
                    "item_rotation_y",
                    "Item rotation Y",
                    "blockanimation.itemRotationY",
                    0,
                    -180,
                    180,
                    1,
                    Animations::setItemRotationY),
            number(
                    "ITEM_ROTATION_Z",
                    "Item rotation Z",
                    "blockanimation.itemRotationZ",
                    0,
                    -180,
                    180,
                    1,
                    Animations::setItemRotationZ)
        };
    }
}
