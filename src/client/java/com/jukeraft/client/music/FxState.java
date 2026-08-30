package com.jukeraft.client.music;

public final class FxState {
    public static final int BAND_COUNT = 7;

    public final float[] eq = new float[BAND_COUNT];
    public float reverbWet = 0f;
    public float width = 1f;
}
