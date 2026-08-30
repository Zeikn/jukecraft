package com.jukeraft.client.music;

import com.google.gson.JsonObject;
import com.jukeraft.client.render.IconTextures;
import com.jukeraft.client.render.RoundedRectRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MusicWidget {
    private static final float CARD_W = 300, CARD_H = 150;
    private static final float COMPACT_W = 144, COMPACT_H = 114;
    private static final float RADIUS = 20f;
    private static final float PAD_X = 16f, PAD_TOP = 14f, PAD_BOTTOM = 12f;
    private static final float GAP = 10f;
    private static final float MARGIN = 12f;

    private static final float THUMB = 42f, THUMB_RADIUS = 9f;
    private static final float ICON_BTN = 26f, ICON_BTN_SMALL = 20f, PLAY_BTN = 36f;
    private static final float TIME_LABEL_W = 22f;
    private static final float VOLUME_W = 40f;
    private static final float QUEUE_ITEM_H = 32f, QUEUE_PANEL_H = 150f;

    private static final float TEXT_BOOST = 1.25f;

    static final Identifier FONT_REGULAR = Identifier.fromNamespaceAndPath("minecraft", "default");
    static final Identifier FONT_BOLD = FONT_REGULAR;

    private static int COL_CARD_TOP;
    private static int COL_CARD_BOTTOM;
    private static int COL_THUMB_BG;
    private static int COL_ARTIST;
    private static int COL_ICON;
    private static int COL_ICON_ACTIVE;
    private static int COL_PLAY_TOP;
    private static int COL_PLAY_BOTTOM;
    private static int COL_PLAY_ICON;
    private static int COL_SLIDER_THUMB;
    private static Provider themedProvider;

    private static final int COL_BORDER = 0x14FFFFFF;
    private static final int COL_TITLE = 0xFFFFFFFF;
    private static final int COL_ICON_HOVER_BG = 0x1AFFFFFF;
    private static final int COL_SLIDER_TRACK = 0x26FFFFFF;
    private static final int COL_QUEUE_HOVER = 0x14FFFFFF;
    private static final int COL_QUEUE_ARTIST = 0xFF998FB0;
    private static final int COL_CLOSE_ICON = 0x73FFFFFF;

    private static void refreshTheme() {
        Provider p = BridgeClient.getProvider();
        if (p == themedProvider) {
            return;
        }
        themedProvider = p;
        COL_CARD_TOP = p.cardTop;
        COL_CARD_BOTTOM = p.cardBottom;
        COL_THUMB_BG = p.thumbBg;
        COL_ARTIST = p.secondary;
        COL_ICON = p.icon;
        COL_ICON_ACTIVE = p.iconActive;
        COL_PLAY_TOP = p.playTop;
        COL_PLAY_BOTTOM = p.playBottom;
        COL_PLAY_ICON = p.playIcon;
        COL_SLIDER_THUMB = p.sliderThumb;
    }

    private enum Drag { NONE, MAIN, FX, SEARCH, SEEK, VOLUME, REVERB, WIDTH }

    private static final WidgetConfig CONFIG = WidgetConfig.load();
    private static float mainX = CONFIG.x, mainY = CONFIG.y;
    private static float fxX = CONFIG.fxX, fxY = CONFIG.fxY;
    private static float searchX = CONFIG.searchX, searchY = CONFIG.searchY;
    private static boolean compact = CONFIG.compact;
    private static boolean queueOpen = CONFIG.queueOpen;
    private static boolean fxOpen = false;
    private static boolean searchOpen = false;
    private static boolean ytmDirectMode = "DIRECT".equals(CONFIG.ytmMode);
    private static boolean visible = true;

    private static Drag dragging = Drag.NONE;
    private static float dragOffsetX, dragOffsetY;
    private static int draggingEqBand = -1;
    private static double previewSeekFraction = -1;
    private static float queueScroll = 0f;

    private static float scale = 1f;

    private static float queueAnimValue = 0f;
    private static long queueAnimLastNanos = 0L;
    private static float fxAnimValue = 0f;
    private static float searchAnimValue = 0f;
    private static float renderY = 0f;

    private static final Map<String, Zone> ZONES = new LinkedHashMap<>();

    private MusicWidget() {
    }

    private record Zone(float x, float y, float w, float h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    public static void setVisible(boolean value) {
        visible = value;
    }

    public static boolean isVisible() {
        return visible;
    }

    public static void onScreenClosed() {
        dragging = Drag.NONE;
        draggingEqBand = -1;
        previewSeekFraction = -1;
    }

    private static void ensurePosition(int screenW, int screenH) {
        if (mainX < 0 || mainY < 0) {
            mainX = screenW - CARD_W * scale - MARGIN * scale;
            mainY = screenH - CARD_H * scale - MARGIN * scale;
        }
        if (fxX < 0 || fxY < 0) {
            fxX = mainX - FxPanel.WIDTH * scale - MARGIN * scale;
            fxY = mainY - (FxPanel.HEIGHT - CARD_H) * scale;
        }
        if (searchX < 0 || searchY < 0) {
            searchX = mainX - SearchPanel.WIDTH * scale - MARGIN * scale;
            searchY = mainY - (SearchPanel.HEIGHT - CARD_H) * scale - (FxPanel.HEIGHT - CARD_H) * scale - MARGIN * scale;
        }
    }

    private static void persist() {
        CONFIG.x = mainX;
        CONFIG.y = mainY;
        CONFIG.fxX = fxX;
        CONFIG.fxY = fxY;
        CONFIG.searchX = searchX;
        CONFIG.searchY = searchY;
        CONFIG.compact = compact;
        CONFIG.queueOpen = queueOpen;
        CONFIG.ytmMode = ytmDirectMode ? "DIRECT" : "EXTENSION";
        CONFIG.save();
    }

    static boolean usingDirect() {
        return BridgeClient.getProvider() == Provider.YTM && ytmDirectMode;
    }

    static boolean isYtmDirectModeSelected() {
        return ytmDirectMode;
    }

    static void setYtmDirectMode(boolean value) {
        ytmDirectMode = value;
        if (!value) {
            DirectPlaybackManager.deactivate();
        }
        persist();
    }

    public static void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean interactive) {
        if (!visible) {
            return;
        }
        refreshTheme();
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        scale = (float) (1.0 / mc.getWindow().getGuiScale());
        ensurePosition(screenW, screenH);

        float width = compact ? COMPACT_W : CARD_W;
        float baseHeight = compact ? COMPACT_H : CARD_H;

        long now = System.nanoTime();
        if (queueAnimLastNanos == 0L) {
            queueAnimLastNanos = now;
        }
        float dt = Math.min(0.1f, (now - queueAnimLastNanos) / 1_000_000_000f);
        queueAnimLastNanos = now;
        float queueTarget = (queueOpen && !compact) ? 1f : 0f;
        float animSpeed = 1f / 0.22f;
        if (queueAnimValue < queueTarget) {
            queueAnimValue = Math.min(queueTarget, queueAnimValue + dt * animSpeed);
        } else if (queueAnimValue > queueTarget) {
            queueAnimValue = Math.max(queueTarget, queueAnimValue - dt * animSpeed);
        }
        float queueEase = 1f - (1f - queueAnimValue) * (1f - queueAnimValue);
        float queueExtra = queueEase * QUEUE_PANEL_H;
        float height = baseHeight + queueExtra;

        float fxTarget = fxOpen ? 1f : 0f;
        if (fxAnimValue < fxTarget) {
            fxAnimValue = Math.min(fxTarget, fxAnimValue + dt * animSpeed);
        } else if (fxAnimValue > fxTarget) {
            fxAnimValue = Math.max(fxTarget, fxAnimValue - dt * animSpeed);
        }
        float fxEase = 1f - (1f - fxAnimValue) * (1f - fxAnimValue);

        float searchTarget = searchOpen ? 1f : 0f;
        if (searchAnimValue < searchTarget) {
            searchAnimValue = Math.min(searchTarget, searchAnimValue + dt * animSpeed);
        } else if (searchAnimValue > searchTarget) {
            searchAnimValue = Math.max(searchTarget, searchAnimValue - dt * animSpeed);
        }
        float searchEase = 1f - (1f - searchAnimValue) * (1f - searchAnimValue);

        mainX = Math.max(0, Math.min(screenW - width * scale, mainX));
        mainY = Math.max(0, Math.min(screenH - baseHeight * scale, mainY));

        if (interactive) {
            applyDrag(mouseX, mouseY, screenW, screenH);
        }

        renderY = mainY;
        float expandedFootprintH = height * scale;
        if (mainY + expandedFootprintH > screenH) {
            renderY = screenH - expandedFootprintH;
        }

        ZONES.clear();

        PlaybackState state = usingDirect() ? DirectPlaybackManager.toPlaybackState() : BridgeClient.getState();
        float localMouseX = (mouseX - mainX) / scale;
        float localMouseY = (mouseY - renderY) / scale;

        graphics.pose().pushMatrix();
        graphics.pose().translate(mainX, renderY);
        graphics.pose().scale(scale, scale);
        renderCard(graphics, state, localMouseX, localMouseY, interactive, width, height, queueExtra);
        graphics.pose().popMatrix();

        if (fxAnimValue > 0.001f) {
            fxX = Math.max(0, Math.min(screenW - FxPanel.WIDTH * scale, fxX));
            fxY = Math.max(0, Math.min(screenH - FxPanel.HEIGHT * scale, fxY));
            float fxLocalMouseX = (mouseX - fxX) / scale;
            float fxLocalMouseY = (mouseY - fxY) / scale;

            float popScale = 0.85f + 0.15f * fxEase;
            float anchorLocalX = FxPanel.WIDTH;
            float anchorLocalY = FxPanel.HEIGHT / 2f;

            graphics.pose().pushMatrix();
            graphics.pose().translate(fxX, fxY);
            graphics.pose().scale(scale, scale);
            graphics.pose().translate(anchorLocalX, anchorLocalY);
            graphics.pose().scale(popScale, popScale);
            graphics.pose().translate(-anchorLocalX, -anchorLocalY);
            FxPanel.render(graphics, fxLocalMouseX, fxLocalMouseY, interactive, fxX, fxY, scale);
            graphics.pose().popMatrix();
        }

        if (searchAnimValue > 0.001f) {
            searchX = Math.max(0, Math.min(screenW - SearchPanel.WIDTH * scale, searchX));
            searchY = Math.max(0, Math.min(screenH - SearchPanel.HEIGHT * scale, searchY));
            float searchLocalMouseX = (mouseX - searchX) / scale;
            float searchLocalMouseY = (mouseY - searchY) / scale;

            float popScale = 0.85f + 0.15f * searchEase;
            float anchorLocalX = SearchPanel.WIDTH;
            float anchorLocalY = SearchPanel.HEIGHT / 2f;

            graphics.pose().pushMatrix();
            graphics.pose().translate(searchX, searchY);
            graphics.pose().scale(scale, scale);
            graphics.pose().translate(anchorLocalX, anchorLocalY);
            graphics.pose().scale(popScale, popScale);
            graphics.pose().translate(-anchorLocalX, -anchorLocalY);
            SearchPanel.render(graphics, searchLocalMouseX, searchLocalMouseY, interactive, searchX, searchY, scale);
            graphics.pose().popMatrix();
        }
    }

    private static void applyDrag(int mouseX, int mouseY, int screenW, int screenH) {
        switch (dragging) {
            case MAIN -> {
                mainX = mouseX - dragOffsetX;
                mainY = mouseY - dragOffsetY;
            }
            case FX -> {
                fxX = mouseX - dragOffsetX;
                fxY = mouseY - dragOffsetY;
            }
            case SEARCH -> {
                searchX = mouseX - dragOffsetX;
                searchY = mouseY - dragOffsetY;
            }
            case SEEK -> {
                Zone z = ZONES.get("seek");
                if (z != null) {
                    previewSeekFraction = clamp01((mouseX - z.x) / z.w);
                }
            }
            case VOLUME -> {
                Zone z = ZONES.get("volume");
                if (z != null) {
                    double fraction = clamp01((mouseX - z.x) / z.w);
                    if (usingDirect()) {
                        DirectPlaybackManager.setVolume(fraction);
                    } else {
                        JsonObject payload = new JsonObject();
                        payload.addProperty("value", fraction);
                        BridgeClient.sendCommand("volume", payload);
                    }
                }
            }
            default -> {
            }
        }
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static void renderCard(GuiGraphicsExtractor graphics, PlaybackState state, float mouseX, float mouseY,
                                    boolean interactive, float width, float height, float queueExtra) {
        Font font = Minecraft.getInstance().font;

        RoundedRectRenderer.fillGradientV(graphics, 0, 0, width, height, RADIUS,
                COL_CARD_TOP, COL_CARD_BOTTOM, 1f, COL_BORDER);
        zone("main-body", 0, 0, width, height);

        float contentX = PAD_X;
        float contentRight = width - PAD_X;
        float cursorY = PAD_TOP;

        float closeSize = 18f;
        float closeX = width - closeSize - 10f;
        float closeY = 8f;
        boolean closeHover = interactive && new Zone(closeX, closeY, closeSize, closeSize).contains(mouseX, mouseY);
        if (closeHover) {
            RoundedRectRenderer.fill(graphics, closeX, closeY, closeSize, closeSize, closeSize / 2, COL_ICON_HOVER_BG);
        }
        IconTextures.drawCentered(graphics, "close", closeX + closeSize / 2, closeY + closeSize / 2, 14f, COL_CLOSE_ICON);
        zone("close", closeX, closeY, closeSize, closeSize);

        boolean noTrack = state == null;
        Identifier thumb = noTrack ? null : ThumbnailCache.get(state.thumbnailUrl());
        RoundedRectRenderer.fill(graphics, contentX, cursorY, THUMB, THUMB, THUMB_RADIUS, COL_THUMB_BG);
        if (thumb != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, thumb, (int) contentX, (int) cursorY, 0f, 0f, (int) THUMB, (int) THUMB, (int) THUMB, (int) THUMB);
        }

        float metaX = contentX + THUMB + 10f;
        boolean showMeta = !compact;
        Provider current = BridgeClient.getProvider();
        boolean showSearch = current == Provider.YTM;
        int rowButtonCount = showSearch ? 4 : 3;
        float rightControlsW = showMeta ? (ICON_BTN * rowButtonCount + 6f * (rowButtonCount - 1)) : 0f;
        float metaW = contentRight - metaX - rightControlsW - (showMeta ? 8f : 0f);

        if (showMeta) {
            String title = noTrack ? "Not playing" : orDefault(state.title(), "Unknown title");
            String artist = noTrack ? "Nothing connected — open YouTube Music" : orDefault(state.artist(), "");
            int metaBudget = (int) (metaW / TEXT_BOOST);
            drawTextBoosted(graphics, font, truncate(font, title, metaBudget, FONT_BOLD), metaX, cursorY + 6, COL_TITLE, FONT_BOLD, TEXT_BOOST);
            drawTextBoosted(graphics, font, truncate(font, artist, metaBudget, FONT_REGULAR), metaX, cursorY + 19, COL_ARTIST, FONT_REGULAR, TEXT_BOOST);

            float btnY = cursorY + (THUMB - ICON_BTN) / 2;
            float cursorBtnX = contentRight - ICON_BTN;
            float compactBtnX = cursorBtnX;
            cursorBtnX -= ICON_BTN + 6f;
            float fxBtnX = cursorBtnX;
            cursorBtnX -= ICON_BTN + 6f;
            float providerBtnX = cursorBtnX;
            cursorBtnX -= ICON_BTN + 6f;
            float searchBtnX = cursorBtnX;

            if (showSearch) {
                iconButton(graphics, "search-toggle", searchBtnX, btnY, ICON_BTN, mouseX, mouseY, interactive, searchOpen);
                IconTextures.drawCentered(graphics, "search", searchBtnX + ICON_BTN / 2, btnY + ICON_BTN / 2, 15f, searchOpen ? COL_ICON_ACTIVE : COL_ICON);
            }

            iconButton(graphics, "provider-toggle", providerBtnX, btnY, ICON_BTN, mouseX, mouseY, interactive, false);
            IconTextures.drawCentered(graphics, current.logoIcon, providerBtnX + ICON_BTN / 2, btnY + ICON_BTN / 2, 16f, 0xFFFFFFFF);

            iconButton(graphics, "fx-toggle", fxBtnX, btnY, ICON_BTN, mouseX, mouseY, interactive, fxOpen);
            IconTextures.drawCentered(graphics, "eq", fxBtnX + ICON_BTN / 2, btnY + ICON_BTN / 2, 17f, fxOpen ? COL_ICON_ACTIVE : COL_ICON);
            iconButton(graphics, "compact-toggle", compactBtnX, btnY, ICON_BTN, mouseX, mouseY, interactive, false);
            IconTextures.drawCentered(graphics, "compact", compactBtnX + ICON_BTN / 2, btnY + ICON_BTN / 2, 17f, COL_ICON);
        } else {
            float compactBtnX = contentRight - ICON_BTN;
            float btnY = cursorY + (THUMB - ICON_BTN) / 2;
            iconButton(graphics, "compact-toggle", compactBtnX, btnY, ICON_BTN, mouseX, mouseY, interactive, false);
            IconTextures.drawCentered(graphics, "compact", compactBtnX + ICON_BTN / 2, btnY + ICON_BTN / 2, 17f, COL_ICON);
        }

        cursorY += THUMB + GAP;

        if (!compact) {
            cursorY = renderSeekRow(graphics, font, state, contentX, contentRight, cursorY);
            cursorY += GAP;
        }

        cursorY = renderControlsRow(graphics, state, contentX, contentRight, cursorY, mouseX, mouseY, interactive);

        if (!compact && queueExtra > 1f) {
            renderQueuePanel(graphics, font, state, contentX, contentRight, cursorY + GAP, mouseX, mouseY, interactive, queueExtra);
        }
    }

    private static float renderSeekRow(GuiGraphicsExtractor graphics, Font font, PlaybackState state, float left, float right, float y) {
        double duration = state == null ? 0 : state.duration();
        double currentTime = state == null ? 0 : state.currentTime();
        double fraction = duration > 0 ? currentTime / duration : 0;
        if (dragging == Drag.SEEK && previewSeekFraction >= 0) {
            fraction = previewSeekFraction;
            currentTime = fraction * duration;
        }

        float trackX = left + TIME_LABEL_W + 6f;
        float trackW = right - TIME_LABEL_W - 6f - trackX;
        float trackY = y + 5f;

        drawText(graphics, font, formatTime(currentTime), left, y, COL_ARTIST);
        drawText(graphics, font, formatTime(duration), right - TIME_LABEL_W, y, COL_ARTIST);

        RoundedRectRenderer.fill(graphics, trackX, trackY, trackW, 3f, 1.5f, COL_SLIDER_TRACK);
        float thumbX = trackX + (float) (fraction * trackW);
        RoundedRectRenderer.fill(graphics, thumbX - 4f, trackY - 2.5f, 8f, 8f, 4f, COL_SLIDER_THUMB);
        zone("seek", trackX, y - 4f, trackW, 16f);

        return y + 10f;
    }

    private static float renderControlsRow(GuiGraphicsExtractor graphics, PlaybackState state, float left, float right, float y,
                                            float mouseX, float mouseY, boolean interactive) {
        boolean isPlaying = state != null && state.isPlaying();
        String repeatMode = state == null ? "off" : orDefault(state.repeatMode(), "off");
        float rowH = PLAY_BTN;

        if (compact) {
            float totalW = ICON_BTN * 2 + PLAY_BTN + 12f;
            float cx = left + ((right - left) - totalW) / 2f;
            iconButton(graphics, "prev", cx, y + (rowH - ICON_BTN) / 2, ICON_BTN, mouseX, mouseY, interactive, false);
            IconTextures.drawCentered(graphics, "prev", cx + ICON_BTN / 2, y + rowH / 2, 17f, COL_ICON);
            cx += ICON_BTN + 6f;
            playButton(graphics, cx, y, mouseX, mouseY, interactive, isPlaying);
            cx += PLAY_BTN + 6f;
            iconButton(graphics, "next", cx, y + (rowH - ICON_BTN) / 2, ICON_BTN, mouseX, mouseY, interactive, false);
            IconTextures.drawCentered(graphics, "next", cx + ICON_BTN / 2, y + rowH / 2, 17f, COL_ICON);
            return y + rowH;
        }

        boolean shuffleActive = state != null && state.shuffleActive();
        float x = left;
        iconButtonSmall(graphics, "shuffle", x, y + (rowH - ICON_BTN_SMALL) / 2, mouseX, mouseY, interactive, shuffleActive);
        IconTextures.drawCentered(graphics, "shuffle", x + ICON_BTN_SMALL / 2, y + rowH / 2, 15f, shuffleActive ? COL_ICON_ACTIVE : COL_ICON);
        x += ICON_BTN_SMALL + 4f;

        iconButton(graphics, "prev", x, y + (rowH - ICON_BTN) / 2, ICON_BTN, mouseX, mouseY, interactive, false);
        IconTextures.drawCentered(graphics, "prev", x + ICON_BTN / 2, y + rowH / 2, 17f, COL_ICON);
        x += ICON_BTN + 4f;

        playButton(graphics, x, y, mouseX, mouseY, interactive, isPlaying);
        x += PLAY_BTN + 4f;

        iconButton(graphics, "next", x, y + (rowH - ICON_BTN) / 2, ICON_BTN, mouseX, mouseY, interactive, false);
        IconTextures.drawCentered(graphics, "next", x + ICON_BTN / 2, y + rowH / 2, 17f, COL_ICON);
        x += ICON_BTN + 4f;

        boolean repeatActive = !"off".equals(repeatMode);
        iconButtonSmall(graphics, "repeat", x, y + (rowH - ICON_BTN_SMALL) / 2, mouseX, mouseY, interactive, repeatActive);
        IconTextures.drawCentered(graphics, "repeat", x + ICON_BTN_SMALL / 2, y + rowH / 2, 15f, repeatActive ? COL_ICON_ACTIVE : COL_ICON);
        x += ICON_BTN_SMALL + 6f;

        float queueBtnX = right - ICON_BTN;
        float volumeX = queueBtnX - VOLUME_W - 6f;
        float volIconX = volumeX - 16f;

        IconTextures.drawCentered(graphics, "volume", volIconX + 7f, y + rowH / 2, 14f, COL_ARTIST);

        double volume = state == null ? 1 : state.volume();
        float trackY = y + rowH / 2 - 1.5f;
        RoundedRectRenderer.fill(graphics, volumeX, trackY, VOLUME_W, 3f, 1.5f, COL_SLIDER_TRACK);
        float volThumbX = volumeX + (float) (clamp01(volume) * VOLUME_W);
        RoundedRectRenderer.fill(graphics, volThumbX - 4f, trackY - 2.5f, 8f, 8f, 4f, COL_SLIDER_THUMB);
        zone("volume", volumeX, y, VOLUME_W, rowH);

        iconButton(graphics, "queue-toggle", queueBtnX, y + (rowH - ICON_BTN) / 2, ICON_BTN, mouseX, mouseY, interactive, queueOpen);
        IconTextures.drawCenteredRotated(graphics, "chevron", queueBtnX + ICON_BTN / 2, y + rowH / 2, 17f,
                queueOpen ? COL_ICON_ACTIVE : COL_ICON, queueOpen ? 180f : 0f);

        return y + rowH;
    }

    private static void renderQueuePanel(GuiGraphicsExtractor graphics, Font font, PlaybackState state, float left, float right,
                                          float y, float mouseX, float mouseY, boolean interactive, float panelH) {
        List<PlaybackState.QueueItem> queue = state == null ? List.of() : state.queue();
        zone("queue-panel", left, y, right - left, panelH);

        if (queue.isEmpty()) {
            drawText(graphics, font, "Queue unavailable", left, y + 6, COL_ARTIST);
            return;
        }

        graphics.enableScissor(
                (int) (mainX + left * scale), (int) (renderY + y * scale),
                (int) (mainX + right * scale), (int) (renderY + (y + panelH) * scale));
        float rowY = y - queueScroll;
        for (PlaybackState.QueueItem item : queue) {
            if (rowY + QUEUE_ITEM_H >= y && rowY <= y + panelH) {
                boolean hover = interactive && new Zone(left, rowY, right - left, QUEUE_ITEM_H).contains(mouseX, mouseY);
                if (hover) {
                    RoundedRectRenderer.fill(graphics, left, rowY, right - left, QUEUE_ITEM_H, 6f, COL_QUEUE_HOVER);
                }
                Identifier thumb = ThumbnailCache.get(item.thumbnailUrl());
                RoundedRectRenderer.fill(graphics, left + 3f, rowY + 3f, 26f, 26f, 5f, COL_THUMB_BG);
                if (thumb != null) {
                    graphics.blit(RenderPipelines.GUI_TEXTURED, thumb, (int) (left + 3f), (int) (rowY + 3f), 0f, 0f, 26, 26, 26, 26);
                }
                int titleColor = item.isCurrent() ? COL_ICON_ACTIVE : COL_TITLE;
                float textX = left + 3f + 26f + 8f;
                int textBudget = (int) (right - textX);
                drawText(graphics, font, truncate(font, orDefault(item.title(), "Unknown"), textBudget), textX, rowY + 4f, titleColor);
                drawText(graphics, font, truncate(font, orDefault(item.artist(), ""), textBudget), textX, rowY + 16f, COL_QUEUE_ARTIST);
            }
            rowY += QUEUE_ITEM_H;
        }
        graphics.disableScissor();
    }

    static void zone(String localId, float localX, float localY, float w, float h) {
        ZONES.put(localId, new Zone(mainX + localX * scale, renderY + localY * scale, w * scale, h * scale));
    }

    static void drawText(GuiGraphicsExtractor graphics, Font font, String text, float localX, float localY, int color) {
        drawText(graphics, font, text, localX, localY, color, FONT_REGULAR);
    }

    static void drawText(GuiGraphicsExtractor graphics, Font font, String text, float localX, float localY, int color, Identifier fontId) {
        Component styled = Component.literal(text).setStyle(Style.EMPTY.withFont(new FontDescription.Resource(fontId)));
        graphics.text(font, styled, (int) localX, (int) localY, color, false);
    }

    static int textWidth(Font font, String text, Identifier fontId) {
        return font.width(Component.literal(text).setStyle(Style.EMPTY.withFont(new FontDescription.Resource(fontId))));
    }

    static void drawTextBoosted(GuiGraphicsExtractor graphics, Font font, String text, float localX, float localY, int color, Identifier fontId, float boost) {
        Component styled = Component.literal(text).setStyle(Style.EMPTY.withFont(new FontDescription.Resource(fontId)));
        graphics.pose().pushMatrix();
        graphics.pose().translate(localX, localY);
        graphics.pose().scale(boost, boost);
        graphics.text(font, styled, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    static void iconButton(GuiGraphicsExtractor graphics, String id, float x, float y, float size,
                            float mouseX, float mouseY, boolean interactive, boolean active) {
        boolean hover = interactive && new Zone(x, y, size, size).contains(mouseX, mouseY);
        if (hover) {
            RoundedRectRenderer.fill(graphics, x, y, size, size, size / 2, COL_ICON_HOVER_BG);
        } else if (active) {
            RoundedRectRenderer.fill(graphics, x, y, size, size, size / 2, 0x22C9ADFF);
        }
        zone(id, x, y, size, size);
    }

    private static void iconButtonSmall(GuiGraphicsExtractor graphics, String id, float x, float y,
                                         float mouseX, float mouseY, boolean interactive, boolean active) {
        iconButton(graphics, id, x, y, ICON_BTN_SMALL, mouseX, mouseY, interactive, active);
    }

    private static void playButton(GuiGraphicsExtractor graphics, float x, float y, float mouseX, float mouseY,
                                    boolean interactive, boolean isPlaying) {
        boolean hover = interactive && new Zone(x, y, PLAY_BTN, PLAY_BTN).contains(mouseX, mouseY);
        RoundedRectRenderer.fillGradientV(graphics, x, y, PLAY_BTN, PLAY_BTN, PLAY_BTN / 2,
                hover ? brighten(COL_PLAY_TOP) : COL_PLAY_TOP, hover ? brighten(COL_PLAY_BOTTOM) : COL_PLAY_BOTTOM);
        float cx = x + PLAY_BTN / 2;
        float cy = y + PLAY_BTN / 2;
        IconTextures.drawCentered(graphics, isPlaying ? "pause" : "play", cx, cy, 19f, COL_PLAY_ICON);
        zone("play-pause", x, y, PLAY_BTN, PLAY_BTN);
    }

    private static int brighten(int argb) {
        int a = argb >>> 24;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 20);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 20);
        int b = Math.min(255, (argb & 0xFF) + 20);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static String formatTime(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) seconds = 0;
        long total = (long) seconds;
        long m = total / 60;
        long s = total % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    static String orDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    static String truncate(Font font, String text, int maxWidth) {
        return truncate(font, text, maxWidth, FONT_REGULAR);
    }

    static String truncate(Font font, String text, int maxWidth, Identifier fontId) {
        Style style = Style.EMPTY.withFont(new FontDescription.Resource(fontId));
        if (maxWidth <= 0 || font.width(Component.literal(text).setStyle(style)) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = font.width(Component.literal(ellipsis).setStyle(style));
        StringBuilder sb = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int cw = font.width(Component.literal(ch).setStyle(style));
            if (width + cw + ellipsisWidth > maxWidth) break;
            sb.append(text.charAt(i));
            width += cw;
        }
        return sb + ellipsis;
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) {
            return false;
        }

        if (fxOpen && FxPanel.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        if (searchOpen && SearchPanel.mouseClicked(mouseX, mouseY)) {
            return true;
        }

        Zone close = ZONES.get("close");
        if (close != null && close.contains(mouseX, mouseY)) {
            visible = false;
            return true;
        }
        Zone searchToggle = ZONES.get("search-toggle");
        if (searchToggle != null && searchToggle.contains(mouseX, mouseY)) {
            searchOpen = !searchOpen;
            persist();
            return true;
        }
        Zone providerToggle = ZONES.get("provider-toggle");
        if (providerToggle != null && providerToggle.contains(mouseX, mouseY)) {
            Provider next = BridgeClient.getProvider().other();
            if (next != Provider.YTM && ytmDirectMode) {
                ytmDirectMode = false;
                DirectPlaybackManager.deactivate();
                searchOpen = false;
            }
            BridgeClient.switchProvider(next);
            CONFIG.provider = next.name();
            CONFIG.save();
            return true;
        }
        Zone fxToggle = ZONES.get("fx-toggle");
        if (fxToggle != null && fxToggle.contains(mouseX, mouseY)) {
            fxOpen = !fxOpen;
            persist();
            return true;
        }
        Zone compactToggle = ZONES.get("compact-toggle");
        if (compactToggle != null && compactToggle.contains(mouseX, mouseY)) {
            compact = !compact;
            persist();
            return true;
        }
        Zone playPause = ZONES.get("play-pause");
        if (playPause != null && playPause.contains(mouseX, mouseY)) {
            if (usingDirect()) {
                DirectPlaybackManager.togglePlayPause();
            } else {
                BridgeClient.sendCommand("toggle-play");
            }
            return true;
        }
        Zone prev = ZONES.get("prev");
        if (prev != null && prev.contains(mouseX, mouseY)) {
            if (usingDirect()) {
                DirectPlaybackManager.previous();
            } else {
                BridgeClient.sendCommand("previous");
            }
            return true;
        }
        Zone next = ZONES.get("next");
        if (next != null && next.contains(mouseX, mouseY)) {
            if (usingDirect()) {
                DirectPlaybackManager.next();
            } else {
                BridgeClient.sendCommand("next");
            }
            return true;
        }
        Zone shuffle = ZONES.get("shuffle");
        if (shuffle != null && shuffle.contains(mouseX, mouseY) && !usingDirect()) {
            BridgeClient.sendCommand("toggle-shuffle");
            return true;
        }
        Zone repeat = ZONES.get("repeat");
        if (repeat != null && repeat.contains(mouseX, mouseY) && !usingDirect()) {
            BridgeClient.sendCommand("toggle-repeat");
            return true;
        }
        Zone queueToggle = ZONES.get("queue-toggle");
        if (queueToggle != null && queueToggle.contains(mouseX, mouseY)) {
            queueOpen = !queueOpen;
            persist();
            return true;
        }
        Zone seek = ZONES.get("seek");
        if (seek != null && seek.contains(mouseX, mouseY)) {
            dragging = Drag.SEEK;
            previewSeekFraction = clamp01((mouseX - seek.x) / seek.w);
            return true;
        }
        Zone volume = ZONES.get("volume");
        if (volume != null && volume.contains(mouseX, mouseY)) {
            dragging = Drag.VOLUME;
            double fraction = clamp01((mouseX - volume.x) / volume.w);
            if (usingDirect()) {
                DirectPlaybackManager.setVolume(fraction);
            } else {
                JsonObject payload = new JsonObject();
                payload.addProperty("value", fraction);
                BridgeClient.sendCommand("volume", payload);
            }
            return true;
        }
        Zone queuePanel = ZONES.get("queue-panel");
        if (queuePanel != null && queuePanel.contains(mouseX, mouseY)) {
            PlaybackState state = usingDirect() ? DirectPlaybackManager.toPlaybackState() : BridgeClient.getState();
            if (state != null && !state.queue().isEmpty()) {
                float relativeY = (float) (mouseY - queuePanel.y) + queueScroll;
                int index = (int) (relativeY / QUEUE_ITEM_H);
                if (index >= 0 && index < state.queue().size()) {
                    if (usingDirect()) {
                        DirectPlaybackManager.playIndexInCurrentQueue(index);
                    } else {
                        JsonObject payload = new JsonObject();
                        payload.addProperty("index", state.queue().get(index).index());
                        BridgeClient.sendCommand("queue-jump", payload);
                    }
                }
            }
            return true;
        }
        Zone mainBody = ZONES.get("main-body");
        if (mainBody != null && mainBody.contains(mouseX, mouseY)) {
            dragging = Drag.MAIN;
            dragOffsetX = (float) (mouseX - mainX);
            dragOffsetY = (float) (mouseY - renderY);
            return true;
        }
        return false;
    }

    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        boolean wasDragging = dragging != Drag.NONE || draggingEqBand >= 0;

        if (dragging == Drag.SEEK && previewSeekFraction >= 0) {
            PlaybackState state = usingDirect() ? DirectPlaybackManager.toPlaybackState() : BridgeClient.getState();
            double duration = state == null ? 0 : state.duration();
            if (usingDirect()) {
                DirectPlaybackManager.seekTo(previewSeekFraction * duration);
            } else {
                JsonObject payload = new JsonObject();
                payload.addProperty("time", previewSeekFraction * duration);
                BridgeClient.sendCommand("seek", payload);
            }
        }
        if (dragging == Drag.MAIN || dragging == Drag.FX || dragging == Drag.SEARCH) {
            persist();
        }

        dragging = Drag.NONE;
        draggingEqBand = -1;
        previewSeekFraction = -1;
        return wasDragging;
    }

    public static boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (searchOpen && SearchPanel.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        Zone queuePanel = ZONES.get("queue-panel");
        if (queuePanel != null && queuePanel.contains(mouseX, mouseY)) {
            PlaybackState state = usingDirect() ? DirectPlaybackManager.toPlaybackState() : BridgeClient.getState();
            int count = state == null ? 0 : state.queue().size();
            float contentH = count * QUEUE_ITEM_H;
            float maxScroll = Math.max(0, contentH - QUEUE_PANEL_H);
            queueScroll = (float) Math.max(0, Math.min(maxScroll, queueScroll - verticalAmount * 12));
            return true;
        }
        return false;
    }

    static boolean isDraggingEqBand(int index) {
        return draggingEqBand == index;
    }

    static void startDraggingEqBand(int index) {
        draggingEqBand = index;
        dragging = Drag.NONE;
    }

    static void startDraggingReverb() {
        dragging = Drag.REVERB;
    }

    static void startDraggingWidth() {
        dragging = Drag.WIDTH;
    }

    static void startDraggingFx(double mouseX, double mouseY) {
        dragging = Drag.FX;
        dragOffsetX = (float) (mouseX - fxX);
        dragOffsetY = (float) (mouseY - fxY);
    }

    static boolean isDraggingReverb() {
        return dragging == Drag.REVERB;
    }

    static boolean isDraggingWidth() {
        return dragging == Drag.WIDTH;
    }

    static void closeFx() {
        fxOpen = false;
    }

    static void persistFx() {
        persist();
    }

    static void startDraggingSearch(double mouseX, double mouseY) {
        dragging = Drag.SEARCH;
        dragOffsetX = (float) (mouseX - searchX);
        dragOffsetY = (float) (mouseY - searchY);
    }

    static void closeSearch() {
        searchOpen = false;
    }

    public static boolean keyPressed(int key, int modifiers) {
        return searchOpen && SearchPanel.keyPressed(key, modifiers);
    }
}
