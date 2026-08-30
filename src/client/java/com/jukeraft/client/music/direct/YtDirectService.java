package com.jukeraft.client.music.direct;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class YtDirectService {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "jukeraft-ytdirect");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean initialized;

    private YtDirectService() {
    }

    private static synchronized void ensureInit() {
        if (!initialized) {
            NewPipe.init(new NewPipeDownloader());
            initialized = true;
        }
    }

    public record Result(String videoId, String title, String artist, String thumbnailUrl, long durationSeconds) {
    }

    public static CompletableFuture<List<Result>> search(String query) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInit();
            try {
                SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(
                        query, Collections.singletonList(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "");
                extractor.fetchPage();
                SearchInfo info = SearchInfo.getInfo(extractor);
                List<Result> results = new ArrayList<>();
                for (InfoItem item : info.getRelatedItems()) {
                    if (!(item instanceof StreamInfoItem stream)) {
                        continue;
                    }
                    String videoId = extractVideoId(stream.getUrl());
                    if (videoId == null) {
                        continue;
                    }
                    String thumb = stream.getThumbnails().isEmpty() ? "" : stream.getThumbnails()
                            .get(stream.getThumbnails().size() - 1).getUrl();
                    results.add(new Result(videoId, stream.getName(), stream.getUploaderName(), thumb, stream.getDuration()));
                }
                return results;
            } catch (Exception e) {
                throw new RuntimeException("YouTube Music search failed", e);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<String> resolveAudioStreamUrl(String videoId) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInit();
            try {
                StreamInfo info = StreamInfo.getInfo(
                        ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoId);
                AudioStream best = null;
                for (AudioStream audio : info.getAudioStreams()) {
                    if (audio.getFormat() != MediaFormat.WEBMA_OPUS) {
                        continue;
                    }
                    if (best == null || audio.getAverageBitrate() > best.getAverageBitrate()) {
                        best = audio;
                    }
                }
                if (best == null) {
                    throw new RuntimeException("No Opus/WebM audio stream available for " + videoId);
                }
                return best.getContent();
            } catch (Exception e) {
                throw new RuntimeException("Stream extraction failed for " + videoId, e);
            }
        }, EXECUTOR);
    }

    private static String extractVideoId(String watchUrl) {
        int idx = watchUrl.indexOf("v=");
        if (idx < 0) {
            return null;
        }
        String rest = watchUrl.substring(idx + 2);
        int amp = rest.indexOf('&');
        return amp < 0 ? rest : rest.substring(0, amp);
    }
}
