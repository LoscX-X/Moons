package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.LocalYsmModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.animal.parrot.ParrotModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ParrotRenderer;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.item.*;

import java.util.*;

/** Local equipment layers follow the upstream authored locator conventions. */
final class YsmEquipmentLayers {
    private final ElytraModel elytra;
    private final ParrotModel parrot;
    private final boolean modelWings;

    YsmEquipmentLayers(LocalYsmModel model) {
        var models = Minecraft.getInstance().getEntityModels();
        elytra = new ElytraModel(models.bakeLayer(ModelLayers.ELYTRA));
        parrot = new ParrotModel(models.bakeLayer(ModelLayers.PARROT));
        modelWings =
                model.runtime().bones().keySet().stream()
                        .anyMatch(
                                name ->
                                        Set.of("wing", "leftwing", "rightwing")
                                                .contains(name.toLowerCase(Locale.ROOT)));
    }

    void submit(
            LocalYsmModel.Mesh mesh,
            LocalPlayer player,
            AvatarRenderState state,
            PoseStack pose,
            SubmitNodeCollector collector,
            int light,
            int overlay) {
        boolean rightMain = player.getMainArm() == HumanoidArm.RIGHT;
        hand(
                mesh,
                state.rightHandItemState,
                rightMain ? player.getMainHandItem() : player.getOffhandItem(),
                "Right",
                pose,
                collector,
                light,
                overlay);
        hand(
                mesh,
                state.leftHandItemState,
                rightMain ? player.getOffhandItem() : player.getMainHandItem(),
                "Left",
                pose,
                collector,
                light,
                overlay);
        head(mesh, player, pose, collector, light);
        wings(mesh, player, state, pose, collector, light);
        shoulder(
                mesh,
                state.parrotOnLeftShoulder,
                "LeftShoulderLocator",
                state,
                pose,
                collector,
                light);
        shoulder(
                mesh,
                state.parrotOnRightShoulder,
                "RightShoulderLocator",
                state,
                pose,
                collector,
                light);
    }

    private void hand(
            LocalYsmModel.Mesh mesh,
            ItemStackRenderState item,
            ItemStack stack,
            String side,
            PoseStack pose,
            SubmitNodeCollector collector,
            int light,
            int overlay) {
        if (item.isEmpty()) return;
        String primary = side + "HandLocator";
        // Direct authored locator wins over the optional numbered locator groups.
        if (mesh.locators().containsKey(primary)) {
            String sword = side + "Sword";
            if (stack.is(net.minecraft.tags.ItemTags.SWORDS)
                    && mesh.swordLocators().containsKey(sword)) {
                pose.pushPose();
                try {
                    pose.mulPose(mesh.swordLocators().get(sword));
                    item.submit(pose, collector, light, overlay, 0);
                } finally {
                    pose.popPose();
                }
                return;
            }
            handAt(mesh, item, stack, primary, true, pose, collector, light, overlay);
            return;
        }
        for (int i = 2; i <= 8; i++)
            handAt(mesh, item, stack, primary + i, false, pose, collector, light, overlay);
    }

    private void handAt(
            LocalYsmModel.Mesh mesh,
            ItemStackRenderState item,
            ItemStack stack,
            String name,
            boolean direct,
            PoseStack pose,
            SubmitNodeCollector collector,
            int light,
            int overlay) {
        if (!visible(mesh, name)) return;
        pose.pushPose();
        try {
            pose.mulPose(mesh.locators().get(name));
            // Direct YSM anchors encode the item orientation; extra chain anchors use this offset.
            if (!direct) {
                pose.translate(0, -.0625, -.1);
                pose.mulPose(Axis.XP.rotationDegrees(-90));
                if (stack.is(Items.TRIDENT)) pose.translate(0, 0, -.0125);
                else if (stack.is(Items.MACE)) pose.translate(0, 0, .01875);
                else if (stack.getUseAnimation() == ItemUseAnimation.SPEAR)
                    pose.translate(0, -.0125, -.025);
            }
            item.submit(pose, collector, light, overlay, 0);
        } finally {
            pose.popPose();
        }
    }

    private void head(
            LocalYsmModel.Mesh mesh,
            LocalPlayer player,
            PoseStack pose,
            SubmitNodeCollector collector,
            int light) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.HEAD);
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (stack.isEmpty()
                || !visible(mesh, "Head")
                || (equippable != null && equippable.slot() == EquipmentSlot.HEAD)) return;
        pose.pushPose();
        try {
            pose.mulPose(mesh.locators().get("Head"));
            pose.scale(.625f, .625f, .625f);
            pose.translate(0, .25, 0);
            Minecraft.getInstance()
                    .getEntityRenderDispatcher()
                    .getItemInHandRenderer()
                    .renderItem(player, stack, ItemDisplayContext.HEAD, pose, collector, light);
        } finally {
            pose.popPose();
        }
    }

    private void wings(
            LocalYsmModel.Mesh mesh,
            LocalPlayer player,
            AvatarRenderState state,
            PoseStack pose,
            SubmitNodeCollector collector,
            int light) {
        if (modelWings
                || !player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                || !visible(mesh, "ElytraLocator")) return;
        Identifier texture =
                Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
        if (state.skin.elytra() != null) texture = state.skin.elytra().texturePath();
        else if (state.skin.cape() != null) texture = state.skin.cape().texturePath();
        pose.pushPose();
        try {
            pose.mulPose(mesh.equipmentLocators().get("ElytraLocator"));
            pose.mulPose(Axis.ZP.rotationDegrees(180));
            collector.submitModel(
                    elytra,
                    state,
                    pose,
                    RenderTypes.armorCutoutNoCull(texture),
                    light,
                    OverlayTexture.NO_OVERLAY,
                    -1,
                    null);
        } finally {
            pose.popPose();
        }
    }

    private void shoulder(
            LocalYsmModel.Mesh mesh,
            Parrot.Variant variant,
            String bone,
            AvatarRenderState player,
            PoseStack pose,
            SubmitNodeCollector collector,
            int light) {
        if (variant == null || !visible(mesh, bone)) return;
        ParrotRenderState state = new ParrotRenderState();
        state.variant = variant;
        state.pose = ParrotModel.Pose.ON_SHOULDER;
        state.flapAngle = player.ageInTicks + player.walkAnimationPos;
        pose.pushPose();
        try {
            pose.mulPose(mesh.locators().get(bone));
            pose.translate(0, 1.5, 0);
            pose.mulPose(Axis.ZP.rotationDegrees(180));
            collector.submitModel(
                    parrot,
                    state,
                    pose,
                    RenderTypes.entityCutout(ParrotRenderer.getVariantTexture(variant)),
                    light,
                    OverlayTexture.NO_OVERLAY,
                    -1,
                    null);
        } finally {
            pose.popPose();
        }
    }

    private static boolean visible(LocalYsmModel.Mesh mesh, String name) {
        return mesh.locators().containsKey(name) && !mesh.hiddenLocators().contains(name);
    }
}
