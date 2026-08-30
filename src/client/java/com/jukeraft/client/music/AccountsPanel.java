package com.jukeraft.client.music;

import com.jukeraft.client.music.direct.auth.YtAuthSession;
import com.jukeraft.client.render.RoundedRectRenderer;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class AccountsPanel {
    public static final float WIDTH = 360, HEIGHT = 460;
    private static final float RADIUS = 20f;
    private static final float PAD = 14f;
    private static final float ROW_H = 40f;
    private static final float REMOVE_W = 20f;

    private record Rect(float x, float y, float w, float h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private enum Tab { ACCOUNTS, INFO }

    private static Tab tab = Tab.ACCOUNTS;
    private static Rect closeRect, accountsPillRect, infoPillRect, listRect, addButtonRect, ytmFloatBtnRect, spotiFloatBtnRect;
    private static final java.util.List<Rect> rowRects = new java.util.ArrayList<>();
    private static final java.util.List<Rect> removeRects = new java.util.ArrayList<>();
    private static final java.util.List<Rect> retryRects = new java.util.ArrayList<>();
    private static float panelX, panelY, panelScale = 1f;
    private static float scroll;
    private static long pasteErrorUntil;

    private static final String[] INFO_LINES = {
            "Jukeraft",
            "",
            "Extension mode needs its matching",
            "browser extension actually running:",
            " - SpotiFloat is REQUIRED for Spotify",
            "   -- it's the only way to control it,",
            "   there's no Direct Connection option.",
            " - YTMFloat is OPTIONAL for YouTube",
            "   Music -- only needed if you want",
            "   Extension mode instead of Direct",
            "   Connection.",
            "",
            "Direct Connection (YTM only) searches",
            "and plays straight from YouTube -- no",
            "browser or extension needed at all.",
            "",
            "Personalized search + taste-based",
            "autoplay need a logged-in YouTube",
            "account -- auto-detected from Firefox,",
            "or paste a cookie in the Accounts tab",
            "(music.youtube.com, DevTools -> Network,",
            "copy the \"cookie\" header).",
    };

    private static final String YTMFLOAT_URL = "https://github.com/Zeikn/YTMFloat";
    private static final String SPOTIFLOAT_URL = "https://github.com/Zeikn/SpotiFloat";
    private static final float TEXT_BOOST = 1.15f;

    private AccountsPanel() {
    }

    public static void render(GuiGraphicsExtractor graphics, float mouseX, float mouseY, boolean interactive,
                               float x, float y, float scale) {
        panelX = x;
        panelY = y;
        panelScale = scale;

        Font font = Minecraft.getInstance().font;
        Provider provider = BridgeClient.getProvider();

        RoundedRectRenderer.fillGradientV(graphics, 0, 0, WIDTH, HEIGHT, RADIUS, provider.cardTop, provider.cardBottom, 1f, 0x14FFFFFF);

        float closeSize = 18f;
        closeRect = new Rect(WIDTH - closeSize - 10f, 9f, closeSize, closeSize);
        boolean closeHover = interactive && closeRect.contains(mouseX, mouseY);
        if (closeHover) {
            RoundedRectRenderer.fill(graphics, closeRect.x, closeRect.y, closeSize, closeSize, closeSize / 2, 0x1AFFFFFF);
        }
        MusicWidget.drawText(graphics, font, "x", closeRect.x + 6, closeRect.y + 4, 0x73FFFFFF);

        MusicWidget.drawTextBoosted(graphics, font, "Accounts", PAD, 12, 0xFFFFFFFF, MusicWidget.FONT_BOLD, 1.25f);

        float pillY = 34f;
        float pillH = 24f;
        float pillGap = 6f;
        float pillW = (WIDTH - PAD * 2 - pillGap) / 2f;
        accountsPillRect = new Rect(PAD, pillY, pillW, pillH);
        infoPillRect = new Rect(PAD + pillW + pillGap, pillY, pillW, pillH);
        drawPill(graphics, font, accountsPillRect, "Accounts", tab == Tab.ACCOUNTS, interactive, mouseX, mouseY, provider);
        drawPill(graphics, font, infoPillRect, "Info", tab == Tab.INFO, interactive, mouseX, mouseY, provider);

        float contentY = pillY + pillH + 10f;
        rowRects.clear();
        removeRects.clear();
        retryRects.clear();
        if (tab == Tab.INFO) {
            listRect = null;
            addButtonRect = null;
            renderInfoTab(graphics, font, mouseX, mouseY, interactive, contentY);
            return;
        }
        ytmFloatBtnRect = null;
        spotiFloatBtnRect = null;
        renderAccountsTab(graphics, font, contentY, mouseX, mouseY, interactive, provider);
    }

    private static void renderInfoTab(GuiGraphicsExtractor graphics, Font font, float mouseX, float mouseY, boolean interactive, float contentY) {
        float y = contentY;
        for (String line : INFO_LINES) {
            MusicWidget.drawTextBoosted(graphics, font, line, PAD, y, 0xFF998FB0, MusicWidget.FONT_REGULAR, TEXT_BOOST);
            y += 13f;
        }
        y += 8f;

        float btnH = 28f;
        float btnGap = 8f;
        float btnW = (WIDTH - PAD * 2 - btnGap) / 2f;
        ytmFloatBtnRect = new Rect(PAD, y, btnW, btnH);
        spotiFloatBtnRect = new Rect(PAD + btnW + btnGap, y, btnW, btnH);
        drawLinkButton(graphics, font, ytmFloatBtnRect, "YTMFloat", interactive, mouseX, mouseY);
        drawLinkButton(graphics, font, spotiFloatBtnRect, "SpotiFloat", interactive, mouseX, mouseY);
        y += btnH + 10f;

        RoundedRectRenderer.fill(graphics, PAD, y, WIDTH - PAD * 2, 1f, 0f, 0x1FFFFFFF);
        y += 8f;
        MusicWidget.drawTextBoosted(graphics, font, "Made By Noxious | 8qwh on discord", PAD, y, 0xFFFFFFFF, MusicWidget.FONT_REGULAR, TEXT_BOOST);
    }

    private static void drawLinkButton(GuiGraphicsExtractor graphics, Font font, Rect rect, String label,
                                        boolean interactive, float mouseX, float mouseY) {
        boolean hover = interactive && rect.contains(mouseX, mouseY);
        RoundedRectRenderer.fillWithBorder(graphics, rect.x, rect.y, rect.w, rect.h, 8f,
                hover ? 0x1AFFFFFF : 0x0DFFFFFF, 1f, 0x1FFFFFFF);
        float labelX = rect.x + rect.w / 2f - font.width(label) * TEXT_BOOST / 2f;
        MusicWidget.drawTextBoosted(graphics, font, label, labelX, rect.y + 8f, 0xFFFFFFFF, MusicWidget.FONT_REGULAR, TEXT_BOOST);
    }

    private static void renderAccountsTab(GuiGraphicsExtractor graphics, Font font, float contentY, float mouseX, float mouseY,
                                           boolean interactive, Provider provider) {
        float addBtnH = 28f;
        float listY = contentY;
        float listH = HEIGHT - listY - PAD - addBtnH - 8f;
        listRect = new Rect(PAD, listY, WIDTH - PAD * 2, listH);

        List<YtAuthSession.AccountInfo> accounts = YtAuthSession.getAccounts();
        if (accounts.isEmpty()) {
            MusicWidget.drawTextBoosted(graphics, font, "No accounts saved yet.", PAD, listY + 6, 0xFF998FB0, MusicWidget.FONT_REGULAR, TEXT_BOOST);
        } else {
            graphics.enableScissor(
                    (int) (panelX + listRect.x * panelScale), (int) (panelY + listRect.y * panelScale),
                    (int) (panelX + (listRect.x + listRect.w) * panelScale), (int) (panelY + (listRect.y + listRect.h) * panelScale));
            float rowY = listY - scroll;
            for (YtAuthSession.AccountInfo account : accounts) {
                Rect row = new Rect(PAD, rowY, listRect.w, ROW_H);
                rowRects.add(row);
                if (rowY + ROW_H >= listY && rowY <= listY + listH) {
                    boolean hover = interactive && row.contains(mouseX, mouseY);
                    if (account.active()) {
                        RoundedRectRenderer.fillWithBorder(graphics, row.x, rowY, row.w, ROW_H - 4f, 8f,
                                withAlpha(provider.iconActive, 0x22), 1f, withAlpha(provider.iconActive, 0x66));
                    } else if (hover) {
                        RoundedRectRenderer.fill(graphics, row.x, rowY, row.w, ROW_H - 4f, 8f, 0x14FFFFFF);
                    }

                    float avatarSize = 30f;
                    Identifier photo = account.photoUrl() != null ? ThumbnailCache.get(account.photoUrl()) : null;
                    RoundedRectRenderer.fill(graphics, row.x + 4f, rowY + 4f, avatarSize, avatarSize, avatarSize / 2, provider.thumbBg);
                    if (photo != null) {
                        graphics.blit(RenderPipelines.GUI_TEXTURED, photo, (int) (row.x + 4f), (int) (rowY + 4f), 0f, 0f, (int) avatarSize, (int) avatarSize, (int) avatarSize, (int) avatarSize);
                    } else {
                        String initial = account.name() != null && !account.name().isEmpty()
                                ? account.name().substring(0, 1).toUpperCase() : "?";
                        MusicWidget.drawText(graphics, font, initial, row.x + 4f + avatarSize / 2f - 3f, rowY + 4f + avatarSize / 2f - 4f, 0xFFFFFFFF);
                    }

                    boolean failed = account.loadFailed() && account.name() == null;
                    float retryW = failed ? 40f : 0f;
                    float textX = row.x + 4f + avatarSize + 8f;
                    int textBudget = (int) ((row.w - avatarSize - 12f - REMOVE_W - retryW - 6f) / TEXT_BOOST);
                    String name = account.name() != null
                            ? account.name()
                            : sourceLabel(account.source()) + (failed ? "" : " (loading...)");
                    MusicWidget.drawTextBoosted(graphics, font, MusicWidget.truncate(font, name, textBudget), textX, rowY + 5f,
                            account.active() ? provider.iconActive : 0xFFFFFFFF, MusicWidget.FONT_REGULAR, TEXT_BOOST);
                    String sub = failed ? "Couldn't load account info"
                            : (account.handle() != null ? account.handle() : sourceLabel(account.source()));
                    MusicWidget.drawTextBoosted(graphics, font, MusicWidget.truncate(font, sub, textBudget), textX, rowY + 19f,
                            failed ? 0xFFE0777A : 0xFF998FB0, MusicWidget.FONT_REGULAR, TEXT_BOOST);

                    if (failed) {
                        Rect retry = new Rect(row.x + row.w - REMOVE_W - retryW, rowY + (ROW_H - 4f) / 2f - 8f, retryW, 16f);
                        retryRects.add(retry);
                        boolean retryHover = interactive && retry.contains(mouseX, mouseY);
                        MusicWidget.drawTextBoosted(graphics, font, "Retry", retry.x, retry.y + 2f,
                                retryHover ? 0xFFFFFFFF : provider.iconActive, MusicWidget.FONT_REGULAR, 0.9f);
                    } else {
                        retryRects.add(new Rect(0, 0, 0, 0));
                    }

                    Rect remove = new Rect(row.x + row.w - REMOVE_W, rowY + (ROW_H - 4f) / 2f - 8f, REMOVE_W, 16f);
                    removeRects.add(remove);
                    boolean removeHover = interactive && remove.contains(mouseX, mouseY);
                    MusicWidget.drawText(graphics, font, "x", remove.x + 6f, remove.y + 2f, removeHover ? 0xFFE0777A : 0xFF73688A);
                } else {
                    removeRects.add(new Rect(0, 0, 0, 0));
                    retryRects.add(new Rect(0, 0, 0, 0));
                }
                rowY += ROW_H;
            }
            graphics.disableScissor();
        }

        float addBtnY = HEIGHT - PAD - addBtnH;
        addButtonRect = new Rect(PAD, addBtnY, WIDTH - PAD * 2, addBtnH);
        boolean addHover = interactive && addButtonRect.contains(mouseX, mouseY);
        RoundedRectRenderer.fillWithBorder(graphics, addButtonRect.x, addButtonRect.y, addButtonRect.w, addBtnH, 8f,
                addHover ? 0x1AFFFFFF : 0x0DFFFFFF, 1f, 0x1FFFFFFF);
        boolean pasteError = System.currentTimeMillis() < pasteErrorUntil;
        String label = pasteError ? "That doesn't look like a YTM cookie" : "+ Add account (paste cookie)";
        float labelX = addButtonRect.x + addButtonRect.w / 2f - font.width(label) * TEXT_BOOST / 2f;
        MusicWidget.drawTextBoosted(graphics, font, label, labelX, addButtonRect.y + 8f, pasteError ? 0xFFE0777A : 0xFFFFFFFF, MusicWidget.FONT_REGULAR, TEXT_BOOST);
    }

    private static String sourceLabel(YtAuthSession.Source source) {
        return switch (source) {
            case FIREFOX -> "Firefox";
            case CHROME -> "Chrome";
            case EDGE -> "Edge";
            case PASTED -> "Pasted cookie";
            case NONE -> "";
        };
    }

    private static void drawPill(GuiGraphicsExtractor graphics, Font font, Rect rect, String label, boolean selected,
                                  boolean interactive, float mouseX, float mouseY, Provider provider) {
        boolean hover = interactive && rect.contains(mouseX, mouseY);
        int bg = selected ? withAlpha(provider.iconActive, 0x3A) : (hover ? 0x1AFFFFFF : 0x0DFFFFFF);
        int border = selected ? withAlpha(provider.iconActive, 0x88) : 0x1FFFFFFF;
        RoundedRectRenderer.fillWithBorder(graphics, rect.x, rect.y, rect.w, rect.h, 8f, bg, 1f, border);
        int textColor = selected ? 0xFFFFFFFF : 0xFF998FB0;
        float labelX = rect.x + rect.w / 2f - font.width(label) * TEXT_BOOST / 2f;
        MusicWidget.drawTextBoosted(graphics, font, label, labelX, rect.y + 6f, textColor, MusicWidget.FONT_REGULAR, TEXT_BOOST);
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static void pasteAccount() {
        long window = Minecraft.getInstance().getWindow().handle();
        String clipboard = GLFW.glfwGetClipboardString(window);
        if (clipboard == null || !YtAuthSession.setPastedCookieHeader(clipboard)) {
            pasteErrorUntil = System.currentTimeMillis() + 4000;
        }
    }

    public static boolean mouseClicked(double absMouseX, double absMouseY) {
        double mouseX = (absMouseX - panelX) / panelScale;
        double mouseY = (absMouseY - panelY) / panelScale;

        if (closeRect != null && closeRect.contains(mouseX, mouseY)) {
            Minecraft.getInstance().gui.setScreen(null);
            return true;
        }
        if (accountsPillRect != null && accountsPillRect.contains(mouseX, mouseY)) {
            tab = Tab.ACCOUNTS;
            return true;
        }
        if (infoPillRect != null && infoPillRect.contains(mouseX, mouseY)) {
            tab = Tab.INFO;
            return true;
        }
        if (tab == Tab.INFO) {
            if (ytmFloatBtnRect != null && ytmFloatBtnRect.contains(mouseX, mouseY)) {
                Util.getPlatform().openUri(YTMFLOAT_URL);
                return true;
            }
            if (spotiFloatBtnRect != null && spotiFloatBtnRect.contains(mouseX, mouseY)) {
                Util.getPlatform().openUri(SPOTIFLOAT_URL);
                return true;
            }
        }
        if (tab == Tab.ACCOUNTS) {
            if (addButtonRect != null && addButtonRect.contains(mouseX, mouseY)) {
                pasteAccount();
                return true;
            }
            List<YtAuthSession.AccountInfo> accounts = YtAuthSession.getAccounts();
            for (int i = 0; i < removeRects.size() && i < accounts.size(); i++) {
                if (removeRects.get(i).contains(mouseX, mouseY)) {
                    YtAuthSession.removeAccount(accounts.get(i).id());
                    return true;
                }
            }
            for (int i = 0; i < retryRects.size() && i < accounts.size(); i++) {
                if (retryRects.get(i).contains(mouseX, mouseY)) {
                    YtAuthSession.retryAccountInfo(accounts.get(i).id());
                    return true;
                }
            }
            for (int i = 0; i < rowRects.size() && i < accounts.size(); i++) {
                if (rowRects.get(i).contains(mouseX, mouseY)) {
                    YtAuthSession.setActiveAccount(accounts.get(i).id());
                    return true;
                }
            }
        }

        return mouseX >= 0 && mouseX <= WIDTH && mouseY >= 0 && mouseY <= HEIGHT;
    }

    public static boolean mouseScrolled(double absMouseX, double absMouseY, double amount) {
        if (tab != Tab.ACCOUNTS || listRect == null) {
            return false;
        }
        double mouseX = (absMouseX - panelX) / panelScale;
        double mouseY = (absMouseY - panelY) / panelScale;
        if (!listRect.contains(mouseX, mouseY)) {
            return false;
        }
        int count = YtAuthSession.getAccounts().size();
        float contentH = count * ROW_H;
        float maxScroll = Math.max(0, contentH - listRect.h);
        scroll = (float) Math.max(0, Math.min(maxScroll, scroll - amount * 12));
        return true;
    }
}
