package com.jukeraft.client.music.direct;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.opus.Opus;

import java.nio.ShortBuffer;

final class OpusPcmDecoder implements AutoCloseable {
    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;
    private static final int MAX_FRAME_SAMPLES = 5760;

    private final long decoder;

    OpusPcmDecoder() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var error = stack.mallocInt(1);
            decoder = Opus.opus_decoder_create(SAMPLE_RATE, CHANNELS, error);
            if (error.get(0) != Opus.OPUS_OK || decoder == 0) {
                throw new IllegalStateException("Failed to create Opus decoder: error " + error.get(0));
            }
        }
    }

    short[] decode(byte[] opusPacket) {
        ShortBuffer out = org.lwjgl.system.MemoryUtil.memAllocShort(MAX_FRAME_SAMPLES * CHANNELS);
        try {
            var packetBuf = org.lwjgl.system.MemoryUtil.memAlloc(opusPacket.length);
            try {
                packetBuf.put(opusPacket).flip();
                int samplesPerChannel = Opus.opus_decode(decoder, packetBuf, out, MAX_FRAME_SAMPLES, 0);
                if (samplesPerChannel < 0) {
                    throw new IllegalStateException("Opus decode error: " + samplesPerChannel);
                }
                short[] pcm = new short[samplesPerChannel * CHANNELS];
                out.limit(pcm.length);
                out.get(pcm);
                return pcm;
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(packetBuf);
            }
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(out);
        }
    }

    @Override
    public void close() {
        Opus.opus_decoder_destroy(decoder);
    }
}
