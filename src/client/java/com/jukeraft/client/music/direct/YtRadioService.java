package com.jukeraft.client.music.direct;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jukeraft.client.music.direct.auth.YtAuthSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class YtRadioService {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-ytdirect-radio");
    private static final String ORIGIN = "https://music.youtube.com";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private YtRadioService() {
    }

    public static CompletableFuture<List<YtDirectService.Result>> fetchRadioQueue(String seedVideoId) {
        if (!seedVideoId.matches("[A-Za-z0-9_-]{11}")) {
            return CompletableFuture.completedFuture(List.of());
        }
        String cookieHeader = YtAuthSession.getCookieHeader();
        String authorization = YtAuthSession.getSapisidHashAuthorization(ORIGIN);
        if (cookieHeader == null || authorization == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        String body = "{\"context\":{\"client\":{\"hl\":\"en\",\"clientName\":\"WEB_REMIX\","
                + "\"clientVersion\":\"1.20240101.01.00\",\"platform\":\"DESKTOP\"}},"
                + "\"videoId\":\"" + seedVideoId + "\",\"playlistId\":\"RDAMVM" + seedVideoId + "\","
                + "\"isAudioOnly\":true,\"tunerSettingValue\":\"AUTOMIX_SETTING_NORMAL\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(ORIGIN + "/youtubei/v1/next?prettyPrint=false"))
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
                        throw new RuntimeException("radio queue HTTP " + response.statusCode());
                    }
                    return parse(response.body());
                })
                .exceptionally(e -> {
                    LOGGER.warn("Failed to fetch radio queue for {}", seedVideoId, e);
                    return List.of();
                });
    }

    private static List<YtDirectService.Result> parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray contents = root.getAsJsonObject("contents")
                .getAsJsonObject("singleColumnMusicWatchNextResultsRenderer")
                .getAsJsonObject("tabbedRenderer")
                .getAsJsonObject("watchNextTabbedResultsRenderer")
                .getAsJsonArray("tabs").get(0).getAsJsonObject()
                .getAsJsonObject("tabRenderer")
                .getAsJsonObject("content")
                .getAsJsonObject("musicQueueRenderer")
                .getAsJsonObject("content")
                .getAsJsonObject("playlistPanelRenderer")
                .getAsJsonArray("contents");

        List<YtDirectService.Result> out = new ArrayList<>();
        for (JsonElement el : contents) {
            JsonObject video = el.getAsJsonObject().getAsJsonObject("playlistPanelVideoRenderer");
            if (video == null) {
                continue;
            }
            String videoId = getString(video, "videoId");
            String title = firstRunText(video.getAsJsonObject("title"));
            if (videoId == null || title == null) {
                continue;
            }
            String artist = firstRunText(video.getAsJsonObject("shortBylineText"));
            String thumb = lastThumbnailUrl(video.getAsJsonObject("thumbnail"));
            long duration = parseLength(firstRunText(video.getAsJsonObject("lengthText")));
            out.add(new YtDirectService.Result(videoId, title, artist != null ? artist : "",
                    thumb != null ? thumb : "", duration));
        }
        return out;
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : null;
    }

    private static String firstRunText(JsonObject field) {
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

    private static String lastThumbnailUrl(JsonObject thumbnail) {
        if (thumbnail == null) {
            return null;
        }
        JsonArray thumbs = thumbnail.getAsJsonArray("thumbnails");
        if (thumbs == null || thumbs.isEmpty()) {
            return null;
        }
        return thumbs.get(thumbs.size() - 1).getAsJsonObject().get("url").getAsString();
    }

    private static long parseLength(String text) {
        if (text == null) {
            return 0;
        }
        long seconds = 0;
        for (String part : text.split(":")) {
            try {
                seconds = seconds * 60 + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return seconds;
    }
}
