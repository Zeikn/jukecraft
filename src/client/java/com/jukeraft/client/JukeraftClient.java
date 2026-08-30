package com.jukeraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.jukeraft.client.gui.DraggableMenuScreen;
import com.jukeraft.client.render.RoundedRectRenderer;
import com.jukeraft.client.music.Provider;
import com.jukeraft.client.music.WidgetConfig;
import com.jukeraft.client.music.BridgeClient;
import com.jukeraft.client.music.MusicWidget;
import com.jukeraft.client.music.direct.DirectAudioPlayer;
import com.jukeraft.client.music.direct.auth.YtAuthSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public final class JukeraftClient implements ClientModInitializer {
    public static final String MOD_ID = "jukeraft";

    private static KeyMapping openMenuKey;

    @Override
    public void onInitializeClient() {
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.jukeraft.open_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "category.jukeraft.general"
        ));

        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "rounded_rect"),
                RoundedRectRenderer.VERTEX_FORMAT,
                shader -> RoundedRectRenderer.SHADER = shader
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.consumeClick()) {
                if (!MusicWidget.isVisible()) {
                    MusicWidget.setVisible(true);
                } else if (client.screen == null) {
                    client.setScreen(new DraggableMenuScreen());
                } else if (client.screen instanceof DraggableMenuScreen) {
                    client.setScreen(null);
                }
            }
        });

        HudRenderCallback.EVENT.register((graphics, tickCounter) -> {
            if (Minecraft.getInstance().screen == null) {
                MusicWidget.render(graphics, -1, -1, false);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
                    MusicWidget.render(graphics, mouseX, mouseY, true));

            ScreenMouseEvents.allowMouseClick(screen).register((s, mouseX, mouseY, button) ->
                    !MusicWidget.mouseClicked(mouseX, mouseY, button));

            ScreenMouseEvents.allowMouseRelease(screen).register((s, mouseX, mouseY, button) ->
                    !MusicWidget.mouseReleased(mouseX, mouseY, button));

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount) ->
                    !MusicWidget.mouseScrolled(mouseX, mouseY, verticalAmount));

            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) ->
                    !MusicWidget.keyPressed(key, modifiers));

            ScreenEvents.remove(screen).register(s -> MusicWidget.onScreenClosed());
        });

        Provider initialProvider = Provider.fromName(WidgetConfig.load().provider, Provider.YTM);
        BridgeClient.start(initialProvider);
        YtAuthSession.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            BridgeClient.stop();
            DirectAudioPlayer.shutdown();
        });
    }
}
