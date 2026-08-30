package com.jukeraft.client.music;

import com.jukeraft.client.music.direct.YtDirectService;
import com.jukeraft.client.music.direct.auth.YtAuthSession;
import com.jukeraft.client.render.IconTextures;
import com.jukeraft.client.render.RoundedRectRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.CompletableFuture;

final class SearchPanel {
    static final float WIDTH = 280, HEIGHT = 320;
    private static final float RADIUS = 20f;
    private static final float PAD = 12f;
    private static final float SEARCH_BAR_H = 30f;
    private static final float RESULT_H = 40f;

    private record Rect(float x, float y, float w, float h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private static Rect bodyRect, closeRect, searchBarRect, resultsRect, extensionPillRect, directPillRect, authActionRect;
    private static float panelX, panelY, panelScale = 1f;

    private static final StringBuilder query = new StringBuilder();
    private static boolean focused;
    private static long caretBlinkStartNanos;
    private static long pasteErrorUntil;

    private static volatile List<YtDirectService.Result> results = List.of();
    private static volatile boolean searching;
    private static CompletableFuture<List<YtDirectService.Result>> pendingSearch;
    private static float scroll;

    private SearchPanel() {
    }

    static void render(GuiGraphicsExtractor graphics, float mouseX, float mouseY, boolean interactive,
                        float x, float y, float scale) {
        panelX = x;
        panelY = y;
        panelScale = scale;

        Font font = Minecraft.getInstance().font;
        Provider provider = BridgeClient.getProvider();

        RoundedRectRenderer.fillGradientV(graphics, 0, 0, WIDTH, HEIGHT, RADIUS, provider.cardTop, provider.cardBottom, 1f, 0x14FFFFFF);
        bodyRect = new Rect(0, 0, WIDTH, HEIGHT);

        float closeSize = 18f;
        closeRect = new Rect(WIDTH - closeSize - 10f, 8f, closeSize, closeSize);
        boolean closeHover = interactive && closeRect.contains(mouseX, mouseY);
        if (closeHover) {
            RoundedRectRenderer.fill(graphics, closeRect.x, closeRect.y, closeSize, closeSize, closeSize / 2, 0x1AFFFFFF);
        }
        MusicWidget.drawText(graphics, font, "x", closeRect.x + 6, closeRect.y + 4, 0x73FFFFFF);

        MusicWidget.drawTextBoosted(graphics, font, "YouTube Music", PAD, 12, 0xFFFFFFFF, MusicWidget.FONT_BOLD, 1.1f);

        boolean direct = MusicWidget.isYtmDirectModeSelected();
        float pillY = 32f;
        float pillH = 22f;
        float pillGap = 6f;
        float pillW = (WIDTH - PAD * 2 - pillGap) / 2f;
        extensionPillRect = new Rect(PAD, pillY, pillW, pillH);
        directPillRect = new Rect(PAD + pillW + pillGap, pillY, pillW, pillH);
        drawModePill(graphics, font, extensionPillRect, "Extension", !direct, interactive, mouseX, mouseY, provider);
        drawModePill(graphics, font, directPillRect, "Direct Connection", direct, interactive, mouseX, mouseY, provider);

        if (!direct) {

            searchBarRect = null;
            resultsRect = null;
            authActionRect = null;
            String[] lines = {
                    "Using the browser extension / companion app,",
                    "same as before.",
                    "",
                    "Switch to Direct Connection to search and play",
                    "YouTube Music straight from this menu -- no",
                    "browser needed."
            };
            float ty = pillY + pillH + 16f;
            for (String line : lines) {
                MusicWidget.drawText(graphics, font, line, PAD, ty, 0xFF998FB0);
                ty += 12f;
            }
            return;
        }

        float authY = pillY + pillH + 8f;
        float authH = 16f;
        YtAuthSession.Status authStatus = YtAuthSession.getStatus();
        String authText;
        String actionLabel = null;
        boolean pasteError = System.currentTimeMillis() < pasteErrorUntil;
        if (pasteError) {
            authText = "That doesn't look like a YTM cookie";
        } else if (authStatus == YtAuthSession.Status.LOGGED_IN) {
            String accountName = YtAuthSession.getAccountName();
            authText = accountName != null
                    ? "Logged in as " + accountName
                    : "Logged in (" + sourceLabel(YtAuthSession.getSource()) + ")";
            actionLabel = "Log out";
        } else if (authStatus == YtAuthSession.Status.CHECKING) {
            authText = "Checking for an existing YouTube login...";
        } else {
            authText = "Not logged in";
            actionLabel = "Paste cookie";
        }
        MusicWidget.drawText(graphics, font, authText, PAD, authY + 3f, pasteError ? 0xFFE0777A : 0xFF998FB0);
        if (actionLabel != null) {
            float actionW = font.width(actionLabel);
            authActionRect = new Rect(WIDTH - PAD - actionW - 6f, authY, actionW + 6f, authH);
            boolean actionHover = interactive && authActionRect.contains(mouseX, mouseY);
            MusicWidget.drawText(graphics, font, actionLabel, authActionRect.x + 3f, authY + 3f,
                    actionHover ? 0xFFFFFFFF : provider.iconActive);
        } else {
            authActionRect = null;
        }

        float barY = authY + authH + 6f;
        searchBarRect = new Rect(PAD, barY, WIDTH - PAD * 2, SEARCH_BAR_H);
        RoundedRectRenderer.fillWithBorder(graphics, searchBarRect.x, searchBarRect.y, searchBarRect.w, searchBarRect.h, 8f,
                0x14FFFFFF, 1f, focused ? withAlpha(provider.iconActive, 0x66) : 0x1FFFFFFF);
        IconTextures.drawCentered(graphics, "search", searchBarRect.x + 16f, searchBarRect.y + SEARCH_BAR_H / 2, 13f, 0xFF998FB0);

        String displayed = query.length() == 0 && !focused ? "Search for a song..." : query.toString();
        int color = query.length() == 0 && !focused ? 0xFF73688A : 0xFFFFFFFF;
        MusicWidget.drawText(graphics, font, displayed, searchBarRect.x + 28f, searchBarRect.y + 10f, color);
        if (focused && ((System.nanoTime() - caretBlinkStartNanos) / 400_000_000L) % 2 == 0) {
            int caretX = (int) (searchBarRect.x + 28f + font.width(query.toString()) + 1);
            graphics.fill(caretX, (int) searchBarRect.y + 8, caretX + 1, (int) searchBarRect.y + 22, 0xFFFFFFFF);
        }

        float listY = barY + SEARCH_BAR_H + 8f;
        float listH = HEIGHT - listY - PAD;
        resultsRect = new Rect(PAD, listY, WIDTH - PAD * 2, listH);

        if (searching) {
            MusicWidget.drawText(graphics, font, "Searching...", PAD, listY + 6, 0xFF998FB0);
            return;
        }
        List<YtDirectService.Result> shown = results;
        if (shown.isEmpty()) {
            MusicWidget.drawText(graphics, font, query.length() == 0 ? "Type a song and press Enter" : "No results", PAD, listY + 6, 0xFF998FB0);
            return;
        }

        graphics.enableScissor(
                (int) (panelX + resultsRect.x * panelScale), (int) (panelY + resultsRect.y * panelScale),
                (int) (panelX + (resultsRect.x + resultsRect.w) * panelScale), (int) (panelY + (resultsRect.y + resultsRect.h) * panelScale));
        float rowY = listY - scroll;
        for (int i = 0; i < shown.size(); i++) {
            YtDirectService.Result r = shown.get(i);
            if (rowY + RESULT_H >= listY && rowY <= listY + listH) {
                boolean hover = interactive && new Rect(PAD, rowY, resultsRect.w, RESULT_H).contains(mouseX, mouseY);
                boolean isCurrent = DirectPlaybackManager.isActive() && r.videoId().equals(DirectPlaybackManager.getCurrentVideoId());
                if (hover) {
                    RoundedRectRenderer.fill(graphics, PAD, rowY, resultsRect.w, RESULT_H, 6f, 0x14FFFFFF);
                }
                Identifier thumb = ThumbnailCache.get(r.thumbnailUrl());
                RoundedRectRenderer.fill(graphics, PAD + 3f, rowY + 3f, 32f, 32f, 6f, provider.thumbBg);
                if (thumb != null) {
                    graphics.blit(RenderPipelines.GUI_TEXTURED, thumb, (int) (PAD + 3f), (int) (rowY + 3f), 0f, 0f, 32, 32, 32, 32);
                }
                float textX = PAD + 3f + 32f + 8f;
                int textBudget = (int) (resultsRect.w - 32f - 11f - 34f);
                int titleColor = isCurrent ? provider.iconActive : 0xFFFFFFFF;
                MusicWidget.drawText(graphics, font, MusicWidget.truncate(font, r.title(), textBudget), textX, rowY + 4f, titleColor);
                MusicWidget.drawText(graphics, font, MusicWidget.truncate(font, MusicWidget.orDefault(r.artist(), ""), textBudget), textX, rowY + 17f, 0xFF998FB0);
                MusicWidget.drawText(graphics, font, MusicWidget.formatTime(r.durationSeconds()), PAD + resultsRect.w - 30f, rowY + 4f, 0xFF998FB0);
            }
            rowY += RESULT_H;
        }
        graphics.disableScissor();
    }

    private static void drawModePill(GuiGraphicsExtractor graphics, Font font, Rect rect, String label, boolean selected,
                                      boolean interactive, float mouseX, float mouseY, Provider provider) {
        boolean hover = interactive && rect.contains(mouseX, mouseY);
        int bg = selected ? withAlpha(provider.iconActive, 0x3A) : (hover ? 0x1AFFFFFF : 0x0DFFFFFF);
        int border = selected ? withAlpha(provider.iconActive, 0x88) : 0x1FFFFFFF;
        RoundedRectRenderer.fillWithBorder(graphics, rect.x, rect.y, rect.w, rect.h, 8f, bg, 1f, border);
        int textColor = selected ? 0xFFFFFFFF : 0xFF998FB0;
        float labelX = rect.x + rect.w / 2f - font.width(label) / 2f;
        MusicWidget.drawText(graphics, font, label, labelX, rect.y + 7f, textColor);
    }

    private static void runSearch() {
        String q = query.toString().trim();
        if (q.isEmpty()) {
            return;
        }
        searching = true;
        pendingSearch = DirectPlaybackManager.search(q);
        pendingSearch.thenAccept(list -> {
            results = list;
            searching = false;
            scroll = 0;
        }).exceptionally(e -> {
            searching = false;
            results = List.of();
            return null;
        });
    }

    private static String sourceLabel(YtAuthSession.Source source) {
        return switch (source) {
            case FIREFOX -> "Firefox";
            case CHROME -> "Chrome";
            case EDGE -> "Edge";
            case PASTED -> "pasted cookie";
            case NONE -> "";
        };
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    static boolean mouseClicked(double absMouseX, double absMouseY) {
        double mouseX = (absMouseX - panelX) / panelScale;
        double mouseY = (absMouseY - panelY) / panelScale;

        if (closeRect != null && closeRect.contains(mouseX, mouseY)) {
            MusicWidget.closeSearch();
            focused = false;
            return true;
        }
        if (extensionPillRect != null && extensionPillRect.contains(mouseX, mouseY)) {
            MusicWidget.setYtmDirectMode(false);
            focused = false;
            return true;
        }
        if (directPillRect != null && directPillRect.contains(mouseX, mouseY)) {
            MusicWidget.setYtmDirectMode(true);
            return true;
        }
        if (authActionRect != null && authActionRect.contains(mouseX, mouseY)) {
            if (YtAuthSession.getStatus() == YtAuthSession.Status.LOGGED_IN) {
                YtAuthSession.logout();
            } else {
                long window = Minecraft.getInstance().getWindow().handle();
                String clipboard = GLFW.glfwGetClipboardString(window);
                if (clipboard == null || !YtAuthSession.setPastedCookieHeader(clipboard)) {
                    pasteErrorUntil = System.currentTimeMillis() + 4000;
                }
            }
            return true;
        }
        if (searchBarRect != null && searchBarRect.contains(mouseX, mouseY)) {
            focused = true;
            caretBlinkStartNanos = System.nanoTime();
            return true;
        }
        focused = false;
        if (resultsRect != null && resultsRect.contains(mouseX, mouseY) && !results.isEmpty()) {
            float listY = resultsRect.y;
            float relativeY = (float) (mouseY - listY) + scroll;
            int index = (int) (relativeY / RESULT_H);
            if (index >= 0 && index < results.size()) {
                DirectPlaybackManager.playFromResults(results, index);
            }
            return true;
        }
        if (bodyRect != null && bodyRect.contains(mouseX, mouseY)) {
            MusicWidget.startDraggingSearch(absMouseX, absMouseY);
            return true;
        }
        return false;
    }

    static boolean mouseScrolled(double absMouseX, double absMouseY, double amount) {
        if (results.isEmpty() || resultsRect == null) {
            return false;
        }
        double mouseX = (absMouseX - panelX) / panelScale;
        double mouseY = (absMouseY - panelY) / panelScale;
        if (!resultsRect.contains(mouseX, mouseY)) {
            return false;
        }
        float contentH = results.size() * RESULT_H;
        float maxScroll = Math.max(0, contentH - resultsRect.h);
        scroll = (float) Math.max(0, Math.min(maxScroll, scroll - amount * 12));
        return true;
    }

    static boolean keyPressed(int key, int modifiers) {
        if (!focused) {
            return false;
        }
        final int GLFW_KEY_BACKSPACE = 259;
        final int GLFW_KEY_ENTER = 257;
        final int GLFW_KEY_KP_ENTER = 335;
        final int GLFW_KEY_SPACE = 32;
        final int GLFW_KEY_ESCAPE = 256;
        final int GLFW_MOD_SHIFT = 0x0001;

        if (key == GLFW_KEY_ESCAPE) {
            focused = false;
            return true;
        }
        if (key == GLFW_KEY_BACKSPACE) {
            if (query.length() > 0) {
                query.deleteCharAt(query.length() - 1);
            }
            return true;
        }
        if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
            runSearch();
            return true;
        }
        if (query.length() >= 80) {
            return true;
        }
        boolean shift = (modifiers & GLFW_MOD_SHIFT) != 0;
        if (key >= 'A' && key <= 'Z') {
            query.append(shift ? (char) key : Character.toLowerCase((char) key));
            return true;
        }
        if (key >= '0' && key <= '9') {
            query.append((char) key);
            return true;
        }
        if (key == GLFW_KEY_SPACE) {
            query.append(' ');
            return true;
        }
        char extra = switch (key) {
            case 44 -> ',';
            case 45 -> '-';
            case 46 -> '.';
            case 47 -> '/';
            case 39 -> '\'';
            default -> 0;
        };
        if (extra != 0) {
            query.append(extra);
            return true;
        }
        return true;
    }
}
