package com.jukeraft.client.render;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

public record RoundedRectRenderState(
        int x0, int y0, int x1, int y1,
        int topLeftArgb, int topRightArgb, int bottomRightArgb, int bottomLeftArgb,
        float radius, float borderWidth, int borderArgb, float innerRadius,
        int guiScale,
        Matrix3x2fc pose,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {
    public RoundedRectRenderState(
            final int x0, final int y0, final int x1, final int y1,
            final int topLeftArgb, final int topRightArgb, final int bottomRightArgb, final int bottomLeftArgb,
            final float radius, final float borderWidth, final int borderArgb, final float innerRadius,
            final int guiScale,
            final Matrix3x2fc pose,
            final @Nullable ScreenRectangle scissorArea
    ) {
        this(x0, y0, x1, y1, topLeftArgb, topRightArgb, bottomRightArgb, bottomLeftArgb,
                radius, borderWidth, borderArgb, innerRadius, guiScale, pose, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }

    @Override
    public float scale() {
        return 1f;
    }
}
