package com.jukeraft.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * Renders a {@link RoundedRectRenderState} into its own private offscreen texture (via the
 * standard {@link PictureInPictureRenderer} machinery), which is then blitted into the GUI
 * batch like any other textured element. This is necessary because the batched GUI render
 * pass has no hook for custom per-element shader uniforms, and the alternative -- drawing
 * immediately during {@code extractRenderState} -- runs before the world is submitted this
 * frame and gets drawn over.
 */
public final class RoundedRectPipRenderer extends PictureInPictureRenderer<RoundedRectRenderState> {
    private static final Projection PROJECTION = new Projection();
    private static ProjectionMatrixBuffer projectionBuffer;

    @Override
    public Class<RoundedRectRenderState> getRenderStateClass() {
        return RoundedRectRenderState.class;
    }

    @Override
    protected void renderToTexture(final RoundedRectRenderState state, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector) {
        int texWidth = (state.x1() - state.x0()) * state.guiScale();
        int texHeight = (state.y1() - state.y0()) * state.guiScale();
        float halfW = texWidth / 2f;
        float halfH = texHeight / 2f;
        float clampedRadius = Math.min(state.radius() * state.guiScale(), Math.min(halfW, halfH));
        float borderWidth = state.borderWidth() * state.guiScale();
        float innerRadius = state.innerRadius() * state.guiScale();

        if (projectionBuffer == null) {
            projectionBuffer = new ProjectionMatrixBuffer("jukeraft_rounded_rect");
        }
        PROJECTION.setupOrtho(-1000.0F, 1000.0F, texWidth, texHeight, true);
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(PROJECTION), ProjectionType.ORTHOGRAPHIC);

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());

        com.mojang.blaze3d.systems.CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        GpuBufferSlice paramsSlice;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float br = ((state.borderArgb() >> 16) & 0xFF) / 255f;
            float bg = ((state.borderArgb() >> 8) & 0xFF) / 255f;
            float bb = (state.borderArgb() & 0xFF) / 255f;
            float ba = ((state.borderArgb() >>> 24) & 0xFF) / 255f;
            ByteBuffer data = Std140Builder.onStack(stack, 48)
                    .putVec4(halfW, halfH, clampedRadius, borderWidth)
                    .putVec4(br, bg, bb, ba)
                    .putVec4(innerRadius, 0f, 0f, 0f)
                    .get();
            paramsSlice = encoder.transientMemory().uploadGpu(data, 256, GpuBuffer.USAGE_UNIFORM);
        }

        GpuBufferSlice vertexSlice;
        Matrix3x2fc identity = RoundedRectRenderState.IDENTITY_POSE;
        try (ByteBufferBuilder byteBufferBuilder = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize() * 4)) {
            BufferBuilder bufferBuilder = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            bufferBuilder.addVertexWith2DPose(identity, 0, 0).setUv(-halfW, -halfH).setColor(state.topLeftArgb());
            bufferBuilder.addVertexWith2DPose(identity, 0, texHeight).setUv(-halfW, halfH).setColor(state.bottomLeftArgb());
            bufferBuilder.addVertexWith2DPose(identity, texWidth, texHeight).setUv(halfW, halfH).setColor(state.bottomRightArgb());
            bufferBuilder.addVertexWith2DPose(identity, texWidth, 0).setUv(halfW, -halfH).setColor(state.topRightArgb());
            try (MeshData meshData = bufferBuilder.buildOrThrow()) {
                vertexSlice = encoder.transientMemory().uploadGpu(meshData.vertexBuffer(), 4, GpuBuffer.USAGE_VERTEX);
            }
        }

        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);

        try (RenderPass renderPass = encoder.createRenderPass(
                () -> "Jukeraft Rounded Rect",
                RenderSystem.outputColorTextureOverride,
                java.util.Optional.empty(),
                RenderSystem.outputDepthTextureOverride,
                java.util.OptionalDouble.empty())) {
            renderPass.setPipeline(RoundedRectRenderer.PIPELINE);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setUniform("RoundedRectParams", paramsSlice);
            renderPass.setVertexBuffer(0, vertexSlice);
            renderPass.setIndexBuffer(indices.getBuffer(6), indices.type());
            renderPass.disableScissor();
            renderPass.drawIndexed(6, 1, 0, 0, 0);
        }
    }

    @Override
    protected String getTextureLabel() {
        return "jukeraft rounded rect";
    }
}
