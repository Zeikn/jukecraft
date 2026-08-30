package com.jukeraft.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * Draws a rounded rectangle (with optional border / annulus hole) using a custom shader.
 * <p>
 * Minecraft 26.x replaced the retained immediate-mode Tesselator/ShaderInstance API with a
 * declarative RenderPipeline + batched GuiRenderState system that has no hook for per-element
 * custom uniforms. Instead, each call here queues a {@link RoundedRectRenderState} into the
 * standard picture-in-picture pipeline ({@link RoundedRectPipRenderer}), which renders the
 * shape into its own private offscreen texture and blits that into the GUI batch like any
 * other textured element -- the same mechanism vanilla uses for entity portraits and skulls.
 */
public final class RoundedRectRenderer {
    static final BindGroupLayout PARAMS_LAYOUT =
            BindGroupLayout.builder().withUniform("RoundedRectParams", UniformType.UNIFORM_BUFFER).build();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("jukeraft", "pipeline/rounded_rect"))
            .withVertexShader(Identifier.fromNamespaceAndPath("jukeraft", "core/rounded_rect"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("jukeraft", "core/rounded_rect"))
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(PARAMS_LAYOUT)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .build();

    private RoundedRectRenderer() {
    }

    public static void fill(GuiGraphicsExtractor graphics, float x, float y, float width, float height, float radius, int argb) {
        fillWithBorder(graphics, x, y, width, height, radius, argb, 0f, 0);
    }

    public static void fillGradientV(GuiGraphicsExtractor graphics, float x, float y, float width, float height,
                                      float radius, int topArgb, int bottomArgb) {
        fillGradientV(graphics, x, y, width, height, radius, topArgb, bottomArgb, 0f, 0);
    }

    public static void fillGradientV(GuiGraphicsExtractor graphics, float x, float y, float width, float height,
                                      float radius, int topArgb, int bottomArgb, float borderWidth, int borderArgb) {
        draw(graphics, x, y, width, height, radius, topArgb, topArgb, bottomArgb, bottomArgb, borderWidth, borderArgb, 0f);
    }

    public static void fillWithBorder(GuiGraphicsExtractor graphics, float x, float y, float width, float height,
                                       float radius, int fillArgb, float borderWidth, int borderArgb) {
        draw(graphics, x, y, width, height, radius, fillArgb, fillArgb, fillArgb, fillArgb, borderWidth, borderArgb, 0f);
    }

    private static void draw(GuiGraphicsExtractor graphics, float x, float y, float width, float height, float radius,
                              int topLeftArgb, int topRightArgb, int bottomRightArgb, int bottomLeftArgb,
                              float borderWidth, int borderArgb, float innerRadius) {
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + width);
        int y1 = Math.round(y + height);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }

        int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        ScreenRectangle scissorArea = graphics.scissorStack.peek();
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());

        RoundedRectRenderState state = new RoundedRectRenderState(
                x0, y0, x1, y1,
                topLeftArgb, topRightArgb, bottomRightArgb, bottomLeftArgb,
                Math.min(radius, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f)), borderWidth, borderArgb, innerRadius,
                guiScale, pose, scissorArea
        );
        graphics.guiRenderState.addPicturesInPictureState(state);
    }
}
