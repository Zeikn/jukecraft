package com.jukeraft.client.gui;

import com.jukeraft.client.music.AccountsPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class DraggableMenuScreen extends Screen {
    private float panelX, panelY;

    public DraggableMenuScreen() {
        super(Component.translatable("gui.jukeraft.menu.title"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        float scale = (float) (1.0 / Minecraft.getInstance().getWindow().getGuiScale());
        panelX = (width - AccountsPanel.WIDTH * scale) / 2f;
        panelY = (height - AccountsPanel.HEIGHT * scale) / 2f;
        float localMouseX = (mouseX - panelX) / scale;
        float localMouseY = (mouseY - panelY) / scale;
        graphics.pose().pushMatrix();
        graphics.pose().translate(panelX, panelY);
        graphics.pose().scale(scale, scale);
        AccountsPanel.render(graphics, localMouseX, localMouseY, true, panelX, panelY, scale);
        graphics.pose().popMatrix();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0 && AccountsPanel.mouseClicked(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (AccountsPanel.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
