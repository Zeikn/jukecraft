package com.jukeraft.client.music.direct;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class DirectAudioPlayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-ytdirect-audio");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "jukeraft-ytdirect-audio");
        thread.setDaemon(true);
        return thread;
    });

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final int SAMPLES_PER_BUFFER = OpusPcmDecoder.SAMPLE_RATE * 2 / 25;
    private static final int BUFFER_POOL_SIZE = 4;
    private static final int PREBUFFER_COUNT = 2;

    private static final Object AL_LOCK = new Object();

    private static long device;
    private static long context;
    private static int source;
    private static int[] bufferPool;
    private static boolean threadLocalContext;
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static volatile float durationSeconds = -1f;
    private static volatile float playedSeconds;
    private static volatile float volume = 1f;
    private static volatile boolean shouldBePlaying;
    private static volatile boolean playingState;

    private static volatile boolean streamComplete;
    private static volatile String currentStreamUrl;

    private static volatile List<WebmOpusDemuxer.CuePoint> cuePoints = List.of();

    private static volatile float[] currentEq = new float[7];
    private static volatile float currentReverbWet = 0f;
    private static volatile float currentWidth = 1f;

    private DirectAudioPlayer() {
    }

    public static void setFx(float[] eqDb, float reverbWet, float width) {
        currentEq = eqDb;
        currentReverbWet = reverbWet;
        currentWidth = width;
    }

    private static void makeCurrent() {
        if (threadLocalContext) {
            EXTThreadLocalContext.alcSetThreadContext(context);
        } else {
            ALC10.alcMakeContextCurrent(context);
        }
    }

    private static void clearThreadContext() {
        if (threadLocalContext) {
            EXTThreadLocalContext.alcSetThreadContext(0);
        } else {
            ALC10.alcMakeContextCurrent(0);
        }
    }

    private static void ensureAl() {
        if (device != 0) {
            return;
        }
        device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == 0) {
            throw new IllegalStateException("Failed to open a second OpenAL device for direct playback");
        }
        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        context = ALC10.alcCreateContext(device, (java.nio.IntBuffer) null);

        threadLocalContext = alcCaps.ALC_EXT_thread_local_context;
        if (!threadLocalContext) {
            LOGGER.warn("ALC_EXT_thread_local_context unsupported; falling back to alcMakeContextCurrent "
                    + "(may intermittently conflict with Minecraft's own sound engine)");
        }
        makeCurrent();
        org.lwjgl.openal.ALCapabilities alCaps = AL.createCapabilities(alcCaps);

        AL.setCurrentProcess(alCaps);
        source = AL10.alGenSources();
        bufferPool = new int[BUFFER_POOL_SIZE];
        for (int i = 0; i < BUFFER_POOL_SIZE; i++) {
            bufferPool[i] = AL10.alGenBuffers();
        }
        LOGGER.info("Direct-connect audio device ready (thread-local context: {})", threadLocalContext);
    }

    public static void play(String streamUrl) {
        int generation = GENERATION.incrementAndGet();
        currentStreamUrl = streamUrl;
        cuePoints = List.of();
        playedSeconds = 0f;
        durationSeconds = -1f;
        shouldBePlaying = false;
        playingState = false;
        streamComplete = false;
        LOGGER.info("play() requested, generation {}", generation);
        EXECUTOR.submit(() -> {
            try {
                synchronized (AL_LOCK) {
                    ensureAl();
                    if (generation != GENERATION.get()) {
                        return;
                    }
                    makeCurrent();
                    AL10.alSourceStop(source);
                    drainQueue();
                }
                streamAndPlay(streamUrl, generation, 0L, 0f);
            } catch (Exception e) {
                LOGGER.warn("Direct-connect playback failed", e);
            }
        });
    }

    public static void seekTo(float targetSeconds) {
        String url = currentStreamUrl;
        List<WebmOpusDemuxer.CuePoint> cues = cuePoints;
        if (url == null || cues.isEmpty()) {
            return;
        }
        WebmOpusDemuxer.CuePoint best = cues.get(0);
        for (WebmOpusDemuxer.CuePoint c : cues) {
            if (c.timeSeconds() <= targetSeconds) {
                best = c;
            } else {
                break;
            }
        }
        WebmOpusDemuxer.CuePoint target = best;
        int generation = GENERATION.incrementAndGet();
        playedSeconds = (float) target.timeSeconds();
        shouldBePlaying = false;
        playingState = false;
        streamComplete = false;
        LOGGER.info("seekTo({}s) -> cue at {}s, byte offset {}", targetSeconds, target.timeSeconds(), target.byteOffset());
        EXECUTOR.submit(() -> {
            try {
                synchronized (AL_LOCK) {
                    ensureAl();
                    if (generation != GENERATION.get()) {
                        return;
                    }
                    makeCurrent();
                    AL10.alSourceStop(source);
                    drainQueue();
                }
                streamAndPlay(url, generation, target.byteOffset(), (float) target.timeSeconds());
            } catch (Exception e) {
                LOGGER.warn("Direct-connect seek failed", e);
            }
        });
    }

    private static void streamAndPlay(String streamUrl, int generation, long startByteOffset, float startSeconds)
            throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(streamUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0");
        if (startByteOffset > 0) {
            requestBuilder.header("Range", "bytes=" + startByteOffset + "-");
        }
        HttpResponse<InputStream> response = HTTP_CLIENT.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        LOGGER.info("Audio stream HTTP {} for generation {}", response.statusCode(), generation);
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " fetching audio stream");
        }

        WebmOpusDemuxer demuxer = new WebmOpusDemuxer();
        DirectFxProcessor fx = new DirectFxProcessor(OpusPcmDecoder.SAMPLE_RATE);
        Deque<Integer> freeBuffers = new ArrayDeque<>();
        for (int id : bufferPool) {
            freeBuffers.add(id);
        }
        List<Short> pending = new ArrayList<>(SAMPLES_PER_BUFFER * OpusPcmDecoder.CHANNELS);
        int queuedBeforePlay = 0;
        boolean started = false;
        int totalPackets = 0;
        playedSeconds = startSeconds;

        try (OpusPcmDecoder decoder = new OpusPcmDecoder(); InputStream in = response.body()) {
            byte[] chunk = new byte[65536];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (generation != GENERATION.get()) {
                    LOGGER.info("Aborting stale stream (generation {} superseded)", generation);
                    return;
                }
                byte[] exact = read == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, read);
                List<WebmOpusDemuxer.CuePoint> cues = demuxer.getCuePoints();
                if (!cues.isEmpty()) {
                    cuePoints = cues;
                }
                for (byte[] packet : demuxer.feed(exact)) {
                    totalPackets++;

                    short[] pcm = decoder.decode(packet);
                    fx.process(pcm, currentEq, currentReverbWet, currentWidth);
                    for (short s : pcm) {
                        pending.add(s);
                    }
                    while (pending.size() >= SAMPLES_PER_BUFFER * OpusPcmDecoder.CHANNELS) {
                        queuedBeforePlay = flushPending(pending, SAMPLES_PER_BUFFER * OpusPcmDecoder.CHANNELS,
                                freeBuffers, generation, queuedBeforePlay);
                        if (!started && queuedBeforePlay >= PREBUFFER_COUNT) {
                            synchronized (AL_LOCK) {
                                if (generation == GENERATION.get()) {
                                    makeCurrent();
                                    AL10.alSourcef(source, AL10.AL_GAIN, volume);
                                    AL10.alSourcePlay(source);
                                    shouldBePlaying = true;
                                    playingState = true;
                                    LOGGER.info("Playback started after {} prebuffered chunks", queuedBeforePlay);
                                }
                            }
                            started = true;
                        }
                    }
                }
            }
        }
        if (generation != GENERATION.get()) {
            return;
        }
        LOGGER.info("Stream fully downloaded+decoded: {} Opus packets, {}s", totalPackets, playedSeconds);

        if (!pending.isEmpty()) {
            flushPending(pending, pending.size(), freeBuffers, generation, queuedBeforePlay);
        }
        if (!started) {
            synchronized (AL_LOCK) {
                makeCurrent();
                AL10.alSourcef(source, AL10.AL_GAIN, volume);
                AL10.alSourcePlay(source);
                shouldBePlaying = true;
                playingState = true;
            }
        }

        if (startByteOffset == 0) {
            durationSeconds = playedSeconds;
        }
        streamComplete = true;
    }

    private static int flushPending(List<Short> pending, int count, Deque<Integer> freeBuffers,
                                     int generation, int queuedBeforePlay) throws InterruptedException {
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            samples[i] = pending.remove(0);
        }
        playedSeconds += (count / (float) OpusPcmDecoder.CHANNELS) / OpusPcmDecoder.SAMPLE_RATE;

        while (freeBuffers.isEmpty()) {
            if (generation != GENERATION.get()) {
                return queuedBeforePlay;
            }
            reclaimProcessedBuffers(freeBuffers);
            if (freeBuffers.isEmpty()) {
                Thread.sleep(10);
            }
        }
        int bufferId = freeBuffers.poll();

        if (generation != GENERATION.get()) {
            return queuedBeforePlay;
        }
        ByteBuffer alData = MemoryUtil.memAlloc(samples.length * 2).order(ByteOrder.nativeOrder());
        for (short s : samples) {
            alData.putShort(s);
        }
        alData.flip();
        synchronized (AL_LOCK) {
            makeCurrent();
            AL10.alBufferData(bufferId, AL10.AL_FORMAT_STEREO16, alData, OpusPcmDecoder.SAMPLE_RATE);
            MemoryUtil.memFree(alData);
            AL10.alSourceQueueBuffers(source, bufferId);

            if (shouldBePlaying && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                LOGGER.warn("Buffer underrun detected at {}s, resuming", playedSeconds);
                AL10.alSourcePlay(source);
            }
        }
        return queuedBeforePlay + 1;
    }

    private static void reclaimProcessedBuffers(Deque<Integer> freeBuffers) {
        synchronized (AL_LOCK) {
            makeCurrent();
            int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            for (int i = 0; i < processed; i++) {
                freeBuffers.add(AL10.alSourceUnqueueBuffers(source));
            }
        }
    }

    private static void drainQueue() {
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        if (queued > 0) {
            int[] ids = new int[queued];
            AL10.alSourceUnqueueBuffers(source, ids);
        }
    }

    public static void pause() {
        shouldBePlaying = false;
        playingState = false;
        synchronized (AL_LOCK) {
            if (device == 0) {
                return;
            }
            makeCurrent();
            AL10.alSourcePause(source);
        }
    }

    public static void resume() {
        shouldBePlaying = true;
        playingState = true;
        synchronized (AL_LOCK) {
            if (device == 0) {
                return;
            }
            makeCurrent();
            AL10.alSourcePlay(source);
        }
    }

    public static void stop() {
        shouldBePlaying = false;
        playingState = false;
        GENERATION.incrementAndGet();
        synchronized (AL_LOCK) {
            if (device == 0) {
                return;
            }
            makeCurrent();
            AL10.alSourceStop(source);
            drainQueue();
        }
    }

    public static void setVolume(float v) {
        volume = v;
        synchronized (AL_LOCK) {
            if (device == 0) {
                return;
            }
            makeCurrent();
            AL10.alSourcef(source, AL10.AL_GAIN, v);
        }
    }

    public static void shutdown() {
        GENERATION.incrementAndGet();
        java.util.concurrent.Future<?> task = EXECUTOR.submit(() -> {
            synchronized (AL_LOCK) {
                if (device == 0) {
                    return;
                }
                makeCurrent();
                AL10.alSourceStop(source);
                AL10.alDeleteSources(source);
                AL10.alDeleteBuffers(bufferPool);
                clearThreadContext();
                ALC10.alcDestroyContext(context);
                ALC10.alcCloseDevice(device);
                device = 0;
            }
        });
        try {
            task.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Direct-connect audio shutdown didn't complete cleanly", e);
        }

        synchronized (AL_LOCK) {
            clearThreadContext();
        }
        EXECUTOR.shutdown();
    }

    public static float getVolume() {
        return volume;
    }

    public static float getPositionSeconds() {
        return playedSeconds;
    }

    public static float getDurationSeconds() {
        return durationSeconds;
    }

    public static boolean isPlaying() {
        return playingState;
    }

    public static boolean hasFinishedPlaying() {
        if (!shouldBePlaying || device == 0 || !streamComplete) {
            return false;
        }
        synchronized (AL_LOCK) {
            if (device == 0) {
                return false;
            }
            makeCurrent();
            return AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED;
        }
    }
}
