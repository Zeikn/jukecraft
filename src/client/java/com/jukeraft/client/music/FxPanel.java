package com.jukeraft.client.music;

import com.google.gson.JsonObject;
import com.jukeraft.client.render.RoundedRectRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.LinkedHashMap;
import java.util.Map;

final class FxPanel {
    static final float WIDTH = 264, HEIGHT = 256;
    private static final float RADIUS = 20f;
    private static final float PAD_X = 16f, PAD_TOP = 14f;
    private static final float TEXT_BOOST = 1.2f;

    private static final String[] BAND_LABELS = {"60", "150", "400", "1K", "2.4K", "6K", "15K"};
    private static final Map<String, float[]> PRESETS = new LinkedHashMap<>();
    private static final Map<String, String> PRESET_LABELS = new LinkedHashMap<>();

    static {
        PRESETS.put("flat", new float[]{0, 0, 0, 0, 0, 0, 0});
        PRESETS.put("bassBoost", new float[]{6, 5, 3, 0, 0, 0, 0});
        PRESETS.put("trebleBoost", new float[]{0, 0, 0, 0, 2, 4, 6});
        PRESETS.put("vocal", new float[]{-2, -1, 2, 4, 3, 1, 0});
        PRESETS.put("lofi", new float[]{3, 2, 0, -2, -4, -6, -8});
        PRESET_LABELS.put("flat", "Flat");
        PRESET_LABELS.put("bassBoost", "Bass");
        PRESET_LABELS.put("trebleBoost", "Treble");
        PRESET_LABELS.put("vocal", "Vocal");
        PRESET_LABELS.put("lofi", "Lo-Fi");
    }

    private record Rect(float x, float y, float w, float h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private static Rect bodyRect, closeRect, resetRect, reverbRect, widthRect;
    private static final Rect[] bandRects = new Rect[FxState.BAND_COUNT];
    private static final Map<String, Rect> presetRects = new LinkedHashMap<>();

    private static float panelX, panelY, panelScale = 1f;

    private FxPanel() {
    }

    static void render(GuiGraphicsExtractor graphics, float mouseX, float mouseY, boolean interactive,
                        float x, float y, float scale) {
        panelX = x;
        panelY = y;
        panelScale = scale;

        FxState fx = BridgeClient.getFxState();
        Font font = Minecraft.getInstance().font;
        Provider provider = BridgeClient.getProvider();

        if (interactive) {
            applyOngoingDrag(mouseX, mouseY, fx);
        }

        RoundedRectRenderer.fillGradientV(graphics, 0, 0, WIDTH, HEIGHT, RADIUS, provider.cardTop, provider.cardBottom, 1f, 0x14FFFFFF);
        bodyRect = new Rect(0, 0, WIDTH, HEIGHT);

        float closeSize = 18f;
        closeRect = new Rect(WIDTH - closeSize - 10f, 8f, closeSize, closeSize);
        boolean closeHover = interactive && closeRect.contains(mouseX, mouseY);
        if (closeHover) {
            RoundedRectRenderer.fill(graphics, closeRect.x, closeRect.y, closeSize, closeSize, closeSize / 2, 0x1AFFFFFF);
        }
        MusicWidget.drawText(graphics, font, "x", closeRect.x + 6, closeRect.y + 4, 0x73FFFFFF);

        MusicWidget.drawTextBoosted(graphics, font, "Audio Effects", PAD_X, PAD_TOP - 1, 0xFFFFFFFF, MusicWidget.FONT_BOLD, TEXT_BOOST);
        MusicWidget.drawTextBoosted(graphics, font, "Equalizer", PAD_X, PAD_TOP + 16, 0xFFB0A8C0, MusicWidget.FONT_REGULAR, TEXT_BOOST);

        float bandsTop = PAD_TOP + 28;
        float bandsHeight = 90;
        float bandsWidth = WIDTH - PAD_X * 2;
        float bandW = bandsWidth / FxState.BAND_COUNT;

        for (int i = 0; i < FxState.BAND_COUNT; i++) {
            float bx = PAD_X + bandW * i;
            float sliderX = bx + bandW / 2f - 2f;
            Rect rect = new Rect(bx, bandsTop, bandW, bandsHeight);
            bandRects[i] = rect;

            RoundedRectRenderer.fill(graphics, sliderX, bandsTop, 4f, bandsHeight, 2f, 0x26FFFFFF);
            float value = fx.eq[i];
            float fraction = (value + 12f) / 24f;
            float thumbY = bandsTop + (1f - fraction) * bandsHeight;
            RoundedRectRenderer.fill(graphics, sliderX - 4f, thumbY - 4f, 12f, 8f, 4f, provider.sliderThumb);

            String label = BAND_LABELS[i];
            float labelX = bx + bandW / 2f - MusicWidget.textWidth(font, label, MusicWidget.FONT_REGULAR) * TEXT_BOOST / 2f;
            MusicWidget.drawTextBoosted(graphics, font, label, labelX, bandsTop + bandsHeight + 4, 0xFFB0A8C0, MusicWidget.FONT_REGULAR, TEXT_BOOST);
        }

        float presetsY = bandsTop + bandsHeight + 18;
        float presetW = bandsWidth / PRESETS.size() - 3f;
        float px = PAD_X;
        presetRects.clear();
        for (Map.Entry<String, float[]> preset : PRESETS.entrySet()) {
            Rect rect = new Rect(px, presetsY, presetW, 16f);
            presetRects.put(preset.getKey(), rect);
            boolean hover = interactive && rect.contains(mouseX, mouseY);
            RoundedRectRenderer.fillWithBorder(graphics, rect.x, rect.y, rect.w, rect.h, 6f,
                    hover ? withAlpha(provider.iconActive, 0x38) : 0x0DFFFFFF, 1f, 0x1FFFFFFF);
            String label = PRESET_LABELS.get(preset.getKey());
            float labelX = rect.x + rect.w / 2f - MusicWidget.textWidth(font, label, MusicWidget.FONT_REGULAR) * TEXT_BOOST / 2f;
            MusicWidget.drawTextBoosted(graphics, font, label, labelX, rect.y + 4, 0xFFE8E2F2, MusicWidget.FONT_REGULAR, TEXT_BOOST);
            px += presetW + 3.5f;
        }

        float reverbY = presetsY + 28;
        reverbRect = drawFxSlider(graphics, font, PAD_X, reverbY, bandsWidth, "Reverb", fx.reverbWet, 0, 1);

        float widthY = reverbY + 20;
        widthRect = drawFxSlider(graphics, font, PAD_X, widthY, bandsWidth, "Stereo width", fx.width, 0, 2);

        float resetY = widthY + 26;
        resetRect = new Rect(WIDTH / 2f - 30f, resetY, 60f, 16f);
        boolean resetHover = interactive && resetRect.contains(mouseX, mouseY);
        RoundedRectRenderer.fillWithBorder(graphics, resetRect.x, resetRect.y, resetRect.w, resetRect.h, 8f,
                resetHover ? 0x26FFFFFF : 0x0DFFFFFF, 1f, 0x1FFFFFFF);
        float resetLabelX = resetRect.x + resetRect.w / 2f - MusicWidget.textWidth(font, "Reset", MusicWidget.FONT_REGULAR) * TEXT_BOOST / 2f;
        MusicWidget.drawTextBoosted(graphics, font, "Reset", resetLabelX, resetRect.y + 4, 0xFFE8E2F2, MusicWidget.FONT_REGULAR, TEXT_BOOST);
    }

    private static Rect drawFxSlider(GuiGraphicsExtractor graphics, Font font, float x, float y, float width, String label,
                                      float value, float min, float max) {
        MusicWidget.drawTextBoosted(graphics, font, label, x, y + 2, 0xFFD0C8E0, MusicWidget.FONT_REGULAR, TEXT_BOOST);

        float labelW = 86;
        float valueW = 30;
        float trackX = x + labelW;
        float trackW = width - labelW - valueW;
        RoundedRectRenderer.fill(graphics, trackX, y + 4, trackW, 3f, 1.5f, 0x26FFFFFF);
        float fraction = (value - min) / (max - min);
        float thumbX = trackX + fraction * trackW;
        RoundedRectRenderer.fill(graphics, thumbX - 4f, y + 1, 8f, 8f, 4f, BridgeClient.getProvider().sliderThumb);
        int pct = Math.round((value - min) / (max - min) * (max == 1 ? 100 : 200));
        String valueText = pct + "%";
        float valueX = x + width - MusicWidget.textWidth(font, valueText, MusicWidget.FONT_REGULAR) * TEXT_BOOST;
        MusicWidget.drawTextBoosted(graphics, font, valueText, valueX, y + 2, 0xFFB0A8C0, MusicWidget.FONT_REGULAR, TEXT_BOOST);
        return new Rect(trackX, y - 2, trackW, 12f);
    }

    private static void applyOngoingDrag(float mouseX, float mouseY, FxState fx) {
        for (int i = 0; i < FxState.BAND_COUNT; i++) {
            if (MusicWidget.isDraggingEqBand(i)) {
                updateEqBandFromMouse(i, mouseY);
            }
        }
        if (MusicWidget.isDraggingReverb() && reverbRect != null) {
            float fraction = clamp01((mouseX - reverbRect.x) / reverbRect.w);
            fx.reverbWet = fraction;
            sendFloat("reverb-wet", fraction);
        }
        if (MusicWidget.isDraggingWidth() && widthRect != null) {
            float fraction = clamp01((mouseX - widthRect.x) / widthRect.w) * 2f;
            fx.width = fraction;
            sendFloat("stereo-width", fraction);
        }
    }

    private static void updateEqBandFromMouse(int index, float mouseY) {
        Rect rect = bandRects[index];
        if (rect == null) return;
        float fraction = clamp01((mouseY - rect.y) / rect.h);
        float value = 12f - fraction * 24f;
        BridgeClient.getFxState().eq[index] = value;

        if (MusicWidget.usingDirect()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("index", index);
        payload.addProperty("gainDb", value);
        BridgeClient.sendCommand("eq-band", payload);
    }

    private static void sendFloat(String command, float value) {
        if (MusicWidget.usingDirect()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("value", value);
        BridgeClient.sendCommand(command, payload);
    }

    private static float clamp01(double v) {
        return (float) Math.max(0, Math.min(1, v));
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    static boolean mouseClicked(double absMouseX, double absMouseY) {
        double mouseX = (absMouseX - panelX) / panelScale;
        double mouseY = (absMouseY - panelY) / panelScale;

        if (closeRect != null && closeRect.contains(mouseX, mouseY)) {
            MusicWidget.closeFx();
            MusicWidget.persistFx();
            return true;
        }
        for (int i = 0; i < bandRects.length; i++) {
            if (bandRects[i] != null && bandRects[i].contains(mouseX, mouseY)) {
                MusicWidget.startDraggingEqBand(i);
                updateEqBandFromMouse(i, (float) mouseY);
                return true;
            }
        }
        for (Map.Entry<String, Rect> entry : presetRects.entrySet()) {
            if (entry.getValue().contains(mouseX, mouseY)) {
                float[] preset = PRESETS.get(entry.getKey());
                System.arraycopy(preset, 0, BridgeClient.getFxState().eq, 0, preset.length);
                if (!MusicWidget.usingDirect()) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("name", entry.getKey());
                    BridgeClient.sendCommand("eq-preset", payload);
                }
                return true;
            }
        }
        if (reverbRect != null && reverbRect.contains(mouseX, mouseY)) {
            MusicWidget.startDraggingReverb();
            float fraction = clamp01((mouseX - reverbRect.x) / reverbRect.w);
            BridgeClient.getFxState().reverbWet = fraction;
            sendFloat("reverb-wet", fraction);
            return true;
        }
        if (widthRect != null && widthRect.contains(mouseX, mouseY)) {
            MusicWidget.startDraggingWidth();
            float fraction = clamp01((mouseX - widthRect.x) / widthRect.w) * 2f;
            BridgeClient.getFxState().width = fraction;
            sendFloat("stereo-width", fraction);
            return true;
        }
        if (resetRect != null && resetRect.contains(mouseX, mouseY)) {
            FxState fx = BridgeClient.getFxState();
            for (int i = 0; i < fx.eq.length; i++) fx.eq[i] = 0;
            fx.reverbWet = 0;
            fx.width = 1;
            if (!MusicWidget.usingDirect()) {
                BridgeClient.sendCommand("fx-reset");
            }
            return true;
        }
        if (bodyRect != null && bodyRect.contains(mouseX, mouseY)) {
            MusicWidget.startDraggingFx(absMouseX, absMouseY);
            return true;
        }
        return false;
    }
}
