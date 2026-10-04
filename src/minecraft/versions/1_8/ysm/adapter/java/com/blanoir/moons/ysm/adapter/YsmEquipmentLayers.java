package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.LocalYsmModel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.item.*;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Single held item and head item follow all authored locator groups. */
final class YsmEquipmentLayers {
    YsmEquipmentLayers(LocalYsmModel model) {}

    void submit(LocalYsmModel.Mesh mesh, EntityPlayerSP player, Matrix4f pose) {
        ItemStack hand = player.getHeldItem();
        if (hand != null) {
            String primary = "RightHandLocator";
            if (mesh.locators().containsKey(primary)) {
                if (hand.getItem() instanceof ItemSword
                        && mesh.swordLocators().containsKey("RightSword"))
                    render(
                            player,
                            hand,
                            new Matrix4f(pose).mul(mesh.swordLocators().get("RightSword")),
                            ItemCameraTransforms.TransformType.THIRD_PERSON);
                else handAt(mesh, player, hand, primary, true, pose);
            } else
                for (int i = 2; i <= 8; i++) handAt(mesh, player, hand, primary + i, false, pose);
        }
        ItemStack head = player.getCurrentArmor(3);
        if (head != null && !(head.getItem() instanceof ItemArmor) && visible(mesh, "Head"))
            render(
                    player,
                    head,
                    new Matrix4f(pose)
                            .mul(mesh.locators().get("Head"))
                            .scale(.625f)
                            .translate(0, .25f, 0),
                    ItemCameraTransforms.TransformType.HEAD);
    }

    private void handAt(
            LocalYsmModel.Mesh mesh,
            EntityPlayerSP player,
            ItemStack stack,
            String name,
            boolean direct,
            Matrix4f pose) {
        if (!visible(mesh, name)) return;
        Matrix4f transform = new Matrix4f(pose).mul(mesh.locators().get(name));
        if (!direct) transform.translate(0, -.0625f, -.1f).rotateX((float) -Math.PI / 2);
        render(player, stack, transform, ItemCameraTransforms.TransformType.THIRD_PERSON);
    }

    private static void render(
            EntityPlayerSP player,
            ItemStack stack,
            Matrix4f transform,
            ItemCameraTransforms.TransformType type) {
        GL11.glPushMatrix();
        try {
            GL11.glMultMatrix(transform.get(BufferUtils.createFloatBuffer(16)));
            Minecraft.getMinecraft().getItemRenderer().renderItem(player, stack, type);
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static boolean visible(LocalYsmModel.Mesh mesh, String name) {
        return mesh.locators().containsKey(name) && !mesh.hiddenLocators().contains(name);
    }
}
