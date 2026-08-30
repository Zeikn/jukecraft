package com.jukeraft.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

public final class RoundedRectRenderer {
    public static final VertexFormat VERTEX_FORMAT = DefaultVertexFormat.POSITION_TEX_COLOR;

    public static ShaderInstance SHADER;

    private RoundedRectRenderer() {
    }

    public static void fill(GuiGraphics graphics, float x, float y, float width, float height, float radius, int argb) {
        fillWithBorder(graphics, x, y, width, height, radius, argb, 0f, 0);
    }

    public static void fillGradientV(GuiGraphics graphics, float x, float y, float width, float height,
                                      float radius, int topArgb, int bottomArgb) {
        fillGradientV(graphics, x, y, width, height, radius, topArgb, bottomArgb, 0f, 0);
    }

    public static void fillGradientV(GuiGraphics graphics, float x, float y, float width, float height,
                                      float radius, int topArgb, int bottomArgb, float borderWidth, int borderArgb) {
        draw(graphics, x, y, width, height, radius, topArgb, topArgb, bottomArgb, bottomArgb, borderWidth, borderArgb);
    }

    public static void fillWithBorder(GuiGraphics graphics, float x, float y, float width, float height,
                                       float radius, int fillArgb, float borderWidth, int borderArgb) {
        draw(graphics, x, y, width, height, radius, fillArgb, fillArgb, fillArgb, fillArgb, borderWidth, borderArgb, 0f);
    }

    private static void draw(GuiGraphics graphics, float x, float y, float width, float height, float radius,
                              int topLeftArgb, int topRightArgb, int bottomRightArgb, int bottomLeftArgb,
                              float borderWidth, int borderArgb) {
        draw(graphics, x, y, width, height, radius, topLeftArgb, topRightArgb, bottomRightArgb, bottomLeftArgb,
                borderWidth, borderArgb, 0f);
    }

    private static void draw(GuiGraphics graphics, float x, float y, float width, float height, float radius,
                              int topLeftArgb, int topRightArgb, int bottomRightArgb, int bottomLeftArgb,
                              float borderWidth, int borderArgb, float innerRadius) {
        if (SHADER == null) {

            graphics.fill((int) x, (int) y, (int) (x + width), (int) (y + height), topLeftArgb);
            return;
        }

        float halfW = width / 2f;
        float halfH = height / 2f;
        float clampedRadius = Math.min(radius, Math.min(halfW, halfH));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(() -> SHADER);

        setUniformVec2(SHADER, "RectSize", halfW, halfH);
        setUniformFloat(SHADER, "Radius", clampedRadius);
        setUniformFloat(SHADER, "BorderWidth", borderWidth);
        setUniformColor(SHADER, "BorderColor", borderArgb);
        setUniformFloat(SHADER, "InnerRadius", innerRadius);

        Matrix4f pose = graphics.pose().last().pose();

        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, VERTEX_FORMAT);
        buffer.addVertex(pose, x, y, 0)
                .setUv(-halfW, -halfH)
                .setColor(argbToR(topLeftArgb), argbToG(topLeftArgb), argbToB(topLeftArgb), argbToA(topLeftArgb));
        buffer.addVertex(pose, x, y + height, 0)
                .setUv(-halfW, halfH)
                .setColor(argbToR(bottomLeftArgb), argbToG(bottomLeftArgb), argbToB(bottomLeftArgb), argbToA(bottomLeftArgb));
        buffer.addVertex(pose, x + width, y + height, 0)
                .setUv(halfW, halfH)
                .setColor(argbToR(bottomRightArgb), argbToG(bottomRightArgb), argbToB(bottomRightArgb), argbToA(bottomRightArgb));
        buffer.addVertex(pose, x + width, y, 0)
                .setUv(halfW, -halfH)
                .setColor(argbToR(topRightArgb), argbToG(topRightArgb), argbToB(topRightArgb), argbToA(topRightArgb));

        BufferUploader.drawWithShader(buffer.buildOrThrow());

        RenderSystem.disableBlend();
    }

    private static float argbToR(int argb) {
        return ((argb >> 16) & 0xFF) / 255f;
    }

    private static float argbToG(int argb) {
        return ((argb >> 8) & 0xFF) / 255f;
    }

    private static float argbToB(int argb) {
        return (argb & 0xFF) / 255f;
    }

    private static float argbToA(int argb) {
        return ((argb >>> 24) & 0xFF) / 255f;
    }

    private static void setUniformFloat(ShaderInstance shader, String name, float value) {
        var uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setUniformVec2(ShaderInstance shader, String name, float x, float y) {
        var uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(x, y);
        }
    }

    private static void setUniformColor(ShaderInstance shader, String name, int argb) {
        var uniform = shader.getUniform(name);
        if (uniform == null) {
            return;
        }
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float a = ((argb >>> 24) & 0xFF) / 255f;
        uniform.set(r, g, b, a);
    }
}
