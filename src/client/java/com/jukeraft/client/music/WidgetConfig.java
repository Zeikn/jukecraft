package com.jukeraft.client.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WidgetConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("jukeraft-widget.json");

    public float x = -1;
    public float y = -1;
    public float fxX = -1;
    public float fxY = -1;
    public float searchX = -1;
    public float searchY = -1;
    public boolean compact = false;
    public boolean queueOpen = false;
    public String provider = Provider.YTM.name();

    public String ytmMode = "EXTENSION";

    public static WidgetConfig load() {
        if (!Files.exists(FILE)) {
            return new WidgetConfig();
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            WidgetConfig config = GSON.fromJson(reader, WidgetConfig.class);
            return config != null ? config : new WidgetConfig();
        } catch (IOException | RuntimeException e) {
            return new WidgetConfig();
        }
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (IOException ignored) {
        }
    }
}
