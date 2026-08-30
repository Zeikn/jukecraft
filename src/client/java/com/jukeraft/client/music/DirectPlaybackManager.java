package com.jukeraft.client.music;

import com.jukeraft.client.music.direct.DirectAudioPlayer;
import com.jukeraft.client.music.direct.YtDirectService;
import com.jukeraft.client.music.direct.YtRadioService;
import com.jukeraft.client.music.direct.auth.YtAuthSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class DirectPlaybackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-ytdirect-controls");
    private static volatile boolean active;
    private static volatile List<YtDirectService.Result> queue = List.of();
    private static volatile int currentIndex = -1;
    private static volatile boolean loading;
    private static volatile boolean autoplayPending;

    private static volatile boolean radioMode;

    private DirectPlaybackManager() {
    }

    static boolean isActive() {
        return active;
    }

    static boolean isLoading() {
        return loading;
    }

    static void deactivate() {
        active = false;
        DirectAudioPlayer.stop();
    }

    static CompletableFuture<List<YtDirectService.Result>> search(String query) {
        return YtDirectService.search(query);
    }

    static void playFromResults(List<YtDirectService.Result> results, int index) {
        radioMode = false;
        startTrack(results, index);
    }

    private static void startTrack(List<YtDirectService.Result> results, int index) {
        if (index < 0 || index >= results.size()) {
            return;
        }
        LOGGER.info("startTrack: index {} of {} -> {}", index, results.size(), results.get(index).title());
        queue = results;
        currentIndex = index;
        active = true;
        loading = true;
        YtDirectService.resolveAudioStreamUrl(results.get(index).videoId())
                .thenAccept(url -> {
                    loading = false;
                    DirectAudioPlayer.play(url);
                    DirectAudioPlayer.setVolume(DirectAudioPlayer.getVolume());
                })
                .exceptionally(e -> {
                    loading = false;
                    LOGGER.warn("resolveAudioStreamUrl failed", e);
                    return null;
                });
    }

    static void togglePlayPause() {
        boolean playing = DirectAudioPlayer.isPlaying();
        LOGGER.info("togglePlayPause: currently playing={}, index={}", playing, currentIndex);
        if (playing) {
            DirectAudioPlayer.pause();
        } else {
            DirectAudioPlayer.resume();
        }
    }

    static void playIndexInCurrentQueue(int displayedIndex) {
        startTrack(queue, radioMode ? currentIndex + displayedIndex : currentIndex);
    }

    static void next() {
        LOGGER.info("next(): currentIndex={}, queueSize={}", currentIndex, queue.size());
        if (!queue.isEmpty() && currentIndex + 1 < queue.size()) {
            startTrack(queue, currentIndex + 1);
        }
    }

    static void previous() {
        LOGGER.info("previous(): currentIndex={}, queueSize={}", currentIndex, queue.size());
        if (!queue.isEmpty() && currentIndex - 1 >= 0) {
            startTrack(queue, currentIndex - 1);
        }
    }

    static void seekTo(double seconds) {
        DirectAudioPlayer.seekTo((float) seconds);
    }

    static void setVolume(double v) {
        DirectAudioPlayer.setVolume((float) v);
    }

    static double getVolume() {
        return DirectAudioPlayer.getVolume();
    }

    static String getCurrentVideoId() {
        return currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex).videoId() : "";
    }

    static void syncFx() {
        FxState fx = BridgeClient.getFxState();
        DirectAudioPlayer.setFx(fx.eq.clone(), fx.reverbWet, fx.width);
    }

    private static void checkAutoAdvance() {
        if (!active || loading || autoplayPending || !DirectAudioPlayer.hasFinishedPlaying()) {
            return;
        }
        if (radioMode && currentIndex + 1 < queue.size()) {
            startTrack(queue, currentIndex + 1);
        } else {
            autoplayRadio();
        }
    }

    private static void autoplayRadio() {
        if (currentIndex < 0 || currentIndex >= queue.size() || !YtAuthSession.isLoggedIn()) {
            return;
        }
        autoplayPending = true;
        List<YtDirectService.Result> base = queue;
        String seedVideoId = base.get(currentIndex).videoId();
        LOGGER.info("Autoplay: fetching radio queue seeded by {}", seedVideoId);
        YtRadioService.fetchRadioQueue(seedVideoId).thenAccept(radioTracks -> {
            autoplayPending = false;
            Set<String> existingIds = new HashSet<>();
            for (YtDirectService.Result r : base) {
                existingIds.add(r.videoId());
            }
            List<YtDirectService.Result> merged = new ArrayList<>(base);
            for (YtDirectService.Result r : radioTracks) {
                if (existingIds.add(r.videoId())) {
                    merged.add(r);
                }
            }
            if (merged.size() > base.size()) {
                LOGGER.info("Autoplay: adding {} radio tracks to the queue", merged.size() - base.size());
                radioMode = true;
                startTrack(merged, base.size());
            } else {
                LOGGER.warn("Autoplay: radio queue fetch returned nothing new");
            }
        });
    }

    static PlaybackState toPlaybackState() {
        syncFx();
        checkAutoAdvance();
        if (currentIndex < 0 || currentIndex >= queue.size()) {
            return new PlaybackState("", "", "", false, 0, 0, getVolume(), "off", false, List.of());
        }
        YtDirectService.Result current = queue.get(currentIndex);

        List<YtDirectService.Result> upcoming = radioMode ? queue.subList(currentIndex, queue.size()) : List.of(current);
        List<PlaybackState.QueueItem> items = new ArrayList<>();
        for (int i = 0; i < upcoming.size(); i++) {
            YtDirectService.Result r = upcoming.get(i);
            items.add(new PlaybackState.QueueItem(i, r.title(), r.artist(), r.thumbnailUrl(), i == 0));
        }
        float streamedDuration = DirectAudioPlayer.getDurationSeconds();
        double duration = streamedDuration >= 0 ? streamedDuration : current.durationSeconds();
        return new PlaybackState(
                loading ? "Loading…" : current.title(),
                current.artist(),
                current.thumbnailUrl(),
                DirectAudioPlayer.isPlaying(),
                DirectAudioPlayer.getPositionSeconds(),
                duration,
                getVolume(),
                "off",
                false,
                items
        );
    }
}
