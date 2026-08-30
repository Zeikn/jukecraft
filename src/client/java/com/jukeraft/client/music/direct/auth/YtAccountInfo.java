package com.jukeraft.client.music.direct.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

final class YtAccountInfo {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-ytdirect-auth");
    private static final String ORIGIN = "https://music.youtube.com";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    record Account(String name, String handle, String photoUrl) {
    }

    private YtAccountInfo() {
    }

    static CompletableFuture<Account> fetch(String cookieHeader, String authorization) {
        String body = "{\"context\":{\"client\":{\"hl\":\"en\",\"clientName\":\"WEB_REMIX\","
                + "\"clientVersion\":\"1.20240101.01.00\",\"platform\":\"DESKTOP\"},\"user\":{}}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(ORIGIN + "/youtubei/v1/account/account_menu?prettyPrint=false"))
                .header("Content-Type", "application/json")
                .header("Cookie", cookieHeader)
                .header("Authorization", authorization)
                .header("X-Origin", ORIGIN)
                .header("X-Goog-AuthUser", "0")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new RuntimeException("account_menu HTTP " + response.statusCode());
                    }
                    return parse(response.body());
                })
                .exceptionally(e -> {
                    LOGGER.warn("Failed to fetch account info", e);
                    return null;
                });
    }

    private static Account parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray actions = root.getAsJsonArray("actions");
        JsonObject header = null;
        if (actions != null) {
            for (JsonElement action : actions) {
                JsonObject menuHeader = getObj(getObj(getObj(getObj(
                        action.getAsJsonObject(), "openPopupAction"), "popup"), "multiPageMenuRenderer"), "header");
                if (menuHeader == null) {
                    continue;
                }

                header = menuHeader.has("activeAccountHeaderRenderer")
                        ? menuHeader.getAsJsonObject("activeAccountHeaderRenderer")
                        : menuHeader.getAsJsonObject("simpleHeaderRenderer");
                if (header != null) {
                    break;
                }
            }
        }
        if (header == null) {

            LOGGER.warn("account_menu response had an unexpected shape: {}",
                    json.length() > 2000 ? json.substring(0, 2000) + "...(truncated)" : json);
            throw new RuntimeException("account_menu response had an unexpected shape");
        }
        String name = firstRunText(header, "accountName");
        String handle = firstRunText(header, "channelHandle");
        String photoUrl = null;
        JsonObject photo = header.getAsJsonObject("accountPhoto");
        if (photo != null) {
            JsonArray thumbs = photo.getAsJsonArray("thumbnails");
            if (thumbs != null && !thumbs.isEmpty()) {
                photoUrl = thumbs.get(thumbs.size() - 1).getAsJsonObject().get("url").getAsString();
            }
        }
        return new Account(name, handle, photoUrl);
    }

    private static JsonObject getObj(JsonObject parent, String key) {
        return parent != null ? parent.getAsJsonObject(key) : null;
    }

    private static String firstRunText(JsonObject parent, String key) {
        JsonObject field = parent.getAsJsonObject(key);
        if (field == null) {
            return null;
        }
        JsonArray runs = field.getAsJsonArray("runs");
        if (runs == null || runs.isEmpty()) {
            return null;
        }
        JsonElement text = runs.get(0).getAsJsonObject().get("text");
        return text != null ? text.getAsString() : null;
    }
}
