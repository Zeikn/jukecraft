package com.jukeraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.jukeraft.client.gui.DraggableMenuScreen;
import com.jukeraft.client.music.Provider;
import com.jukeraft.client.music.WidgetConfig;
import com.jukeraft.client.music.BridgeClient;
import com.jukeraft.client.music.MusicWidget;
import com.jukeraft.client.music.direct.DirectAudioPlayer;
import com.jukeraft.client.music.direct.auth.YtAuthSession;
import com.jukeraft.client.render.RoundedRectPipRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class JukeraftClient implements ClientModInitializer {
    public static final String MOD_ID = "jukeraft";

    private static KeyMapping openMenuKey;

    @Override
    public void onInitializeClient() {
        PictureInPictureRendererRegistry.register(context -> new RoundedRectPipRenderer());

        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.jukeraft.open_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "general"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.consumeClick()) {
                if (!MusicWidget.isVisible()) {
                    MusicWidget.setVisible(true);
                } else if (client.gui.screen() == null) {
                    client.gui.setScreen(new DraggableMenuScreen());
                } else if (client.gui.screen() instanceof DraggableMenuScreen) {
                    client.gui.setScreen(null);
                }
            }
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "music_widget"), (graphics, deltaTracker) -> {
            if (Minecraft.getInstance().gui.screen() == null) {
                MusicWidget.render(graphics, -1, -1, false);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickDelta) ->
                    MusicWidget.render(graphics, mouseX, mouseY, true));

            ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                    !MusicWidget.mouseClicked(event.x(), event.y(), event.button()));

            ScreenMouseEvents.allowMouseRelease(screen).register((s, event) ->
                    !MusicWidget.mouseReleased(event.x(), event.y(), event.button()));

            ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontalAmount, verticalAmount) ->
                    !MusicWidget.mouseScrolled(mouseX, mouseY, verticalAmount));

            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) ->
                    !MusicWidget.keyPressed(event.key(), event.modifiers()));

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
