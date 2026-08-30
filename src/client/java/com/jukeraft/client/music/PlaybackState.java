package com.jukeraft.client.music;

import java.util.List;

public record PlaybackState(
        String title,
        String artist,
        String thumbnailUrl,
        boolean isPlaying,
        double currentTime,
        double duration,
        double volume,
        String repeatMode,
        boolean shuffleActive,
        List<QueueItem> queue
) {
    public record QueueItem(
            int index,
            String title,
            String artist,
            String thumbnailUrl,
            boolean isCurrent
    ) {
    }
}
