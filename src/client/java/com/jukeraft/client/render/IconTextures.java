package com.jukeraft.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class IconTextures {
    private IconTextures() {
    }

    private static ResourceLocation icon(String name) {
        return ResourceLocation.fromNamespaceAndPath("jukeraft", "textures/gui/icons/" + name + ".png");
    }

    public static void drawCentered(GuiGraphics graphics, String name, float cx, float cy, float size, int argb) {
        draw(graphics, name, cx - size / 2, cy - size / 2, size, argb);
    }

    public static void drawCenteredRotated(GuiGraphics graphics, String name, float cx, float cy, float size, int argb, float degrees) {
        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(degrees));
        draw(graphics, name, -size / 2, -size / 2, size, argb);
        graphics.pose().popPose();
    }

    public static void draw(GuiGraphics graphics, String name, float x, float y, float size, int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float a = ((argb >>> 24) & 0xFF) / 255f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderTexture(0, icon(name));
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(pose, x, y, 0).setUv(0f, 0f).setColor(r, g, b, a);
        buffer.addVertex(pose, x, y + size, 0).setUv(0f, 1f).setColor(r, g, b, a);
        buffer.addVertex(pose, x + size, y + size, 0).setUv(1f, 1f).setColor(r, g, b, a);
        buffer.addVertex(pose, x + size, y, 0).setUv(1f, 0f).setColor(r, g, b, a);
        BufferUploader.drawWithShader(buffer.buildOrThrow());

        RenderSystem.disableBlend();
    }
}
