package com.jukeraft.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class IconTextures {
    private IconTextures() {
    }

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("jukeraft", "textures/gui/icons/" + name + ".png");
    }

    public static void drawCentered(GuiGraphicsExtractor graphics, String name, float cx, float cy, float size, int argb) {
        draw(graphics, name, cx - size / 2, cy - size / 2, size, argb);
    }

    public static void drawCenteredRotated(GuiGraphicsExtractor graphics, String name, float cx, float cy, float size, int argb, float degrees) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy);
        graphics.pose().rotate((float) Math.toRadians(degrees));
        draw(graphics, name, -size / 2, -size / 2, size, argb);
        graphics.pose().popMatrix();
    }

    public static void draw(GuiGraphicsExtractor graphics, String name, float x, float y, float size, int argb) {
        int isize = Math.round(size);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                icon(name),
                Math.round(x),
                Math.round(y),
                0f,
                0f,
                isize,
                isize,
                isize,
                isize,
                argb
        );
    }
}
