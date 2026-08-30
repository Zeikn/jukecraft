package com.jukeraft.client.music.direct;

final class DirectFxProcessor {
    private static final int[] EQ_BANDS = {60, 150, 400, 1000, 2400, 6000, 15000};
    private static final float EQ_Q = 1f;
    private static final float REVERB_FIXED_GAIN = 0.015f;

    private final Biquad[] eqLeft = new Biquad[EQ_BANDS.length];
    private final Biquad[] eqRight = new Biquad[EQ_BANDS.length];
    private final float[] lastEqDb = new float[EQ_BANDS.length];

    private final CombFilter[] combsL;
    private final CombFilter[] combsR;
    private final AllpassFilter[] allpassL;
    private final AllpassFilter[] allpassR;

    private final int sampleRate;

    DirectFxProcessor(int sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < EQ_BANDS.length; i++) {
            eqLeft[i] = new Biquad();
            eqRight[i] = new Biquad();
            updateBand(i, 0f);
        }

        int[] combTunings = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        int[] allpassTunings = {556, 441, 341, 225};
        float scale = sampleRate / 44100f;
        int stereoSpread = 23;

        combsL = new CombFilter[combTunings.length];
        combsR = new CombFilter[combTunings.length];
        for (int i = 0; i < combTunings.length; i++) {
            combsL[i] = new CombFilter((int) (combTunings[i] * scale));
            combsR[i] = new CombFilter((int) (combTunings[i] * scale) + stereoSpread);
        }
        allpassL = new AllpassFilter[allpassTunings.length];
        allpassR = new AllpassFilter[allpassTunings.length];
        for (int i = 0; i < allpassTunings.length; i++) {
            allpassL[i] = new AllpassFilter((int) (allpassTunings[i] * scale));
            allpassR[i] = new AllpassFilter((int) (allpassTunings[i] * scale) + stereoSpread);
        }
    }

    void process(short[] pcm, float[] eqDb, float reverbWet, float width) {
        for (int i = 0; i < EQ_BANDS.length; i++) {
            if (eqDb[i] != lastEqDb[i]) {
                updateBand(i, eqDb[i]);
                lastEqDb[i] = eqDb[i];
            }
        }

        for (int i = 0; i + 1 < pcm.length; i += 2) {
            float l = pcm[i] / 32768f;
            float r = pcm[i + 1] / 32768f;

            for (int b = 0; b < EQ_BANDS.length; b++) {
                l = eqLeft[b].process(l);
                r = eqRight[b].process(r);
            }

            if (reverbWet > 0.0005f) {

                float combInputL = l * REVERB_FIXED_GAIN;
                float combInputR = r * REVERB_FIXED_GAIN;
                float wetL = 0f, wetR = 0f;
                for (CombFilter c : combsL) {
                    wetL += c.process(combInputL);
                }
                for (CombFilter c : combsR) {
                    wetR += c.process(combInputR);
                }
                for (AllpassFilter a : allpassL) {
                    wetL = a.process(wetL);
                }
                for (AllpassFilter a : allpassR) {
                    wetR = a.process(wetR);
                }
                l = l + wetL * reverbWet;
                r = r + wetR * reverbWet;
            }

            if (Math.abs(width - 1f) > 0.001f) {
                float mid = (l + r) * 0.5f;
                float side = (l - r) * 0.5f * width;
                l = mid + side;
                r = mid - side;
            }

            pcm[i] = clampToShort(l);
            pcm[i + 1] = clampToShort(r);
        }
    }

    private void updateBand(int index, float gainDb) {
        eqLeft[index].setPeaking(sampleRate, EQ_BANDS[index], EQ_Q, gainDb);
        eqRight[index].setPeaking(sampleRate, EQ_BANDS[index], EQ_Q, gainDb);
    }

    private static short clampToShort(float sample) {
        float v = sample * 32768f;
        if (v > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (v < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) v;
    }

    private static final class Biquad {
        private float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        void setPeaking(int sampleRate, float freq, float q, float gainDb) {
            float a = (float) Math.pow(10, gainDb / 40.0);
            float w0 = (float) (2 * Math.PI * freq / sampleRate);
            float cosW0 = (float) Math.cos(w0);
            float sinW0 = (float) Math.sin(w0);
            float alpha = sinW0 / (2 * q);

            float b0u = 1 + alpha * a;
            float b1u = -2 * cosW0;
            float b2u = 1 - alpha * a;
            float a0u = 1 + alpha / a;
            float a1u = -2 * cosW0;
            float a2u = 1 - alpha / a;

            b0 = b0u / a0u;
            b1 = b1u / a0u;
            b2 = b2u / a0u;
            a1 = a1u / a0u;
            a2 = a2u / a0u;
        }

        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    private static final class CombFilter {
        private static final float FEEDBACK = 0.84f;
        private static final float DAMPING = 0.2f;

        private final float[] buffer;
        private int index;
        private float filterStore;

        CombFilter(int delaySamples) {
            buffer = new float[Math.max(1, delaySamples)];
        }

        float process(float input) {
            float output = buffer[index];
            filterStore = output * (1 - DAMPING) + filterStore * DAMPING;
            buffer[index] = input + filterStore * FEEDBACK;
            index = (index + 1) % buffer.length;
            return output;
        }
    }

    private static final class AllpassFilter {
        private static final float FEEDBACK = 0.5f;

        private final float[] buffer;
        private int index;

        AllpassFilter(int delaySamples) {
            buffer = new float[Math.max(1, delaySamples)];
        }

        float process(float input) {
            float bufOut = buffer[index];
            float output = -input + bufOut;
            buffer[index] = input + bufOut * FEEDBACK;
            index = (index + 1) % buffer.length;
            return output;
        }
    }
}
