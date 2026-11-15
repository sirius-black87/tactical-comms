package com.tacticalcomms.atak.audio;

/** ITU-T G.711 mu-law codec (8 bit per sample, 64 kbit/s at 8 kHz). */
public final class MuLaw {
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;
    private static final short[] DECODE = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            int u = ~i & 0xff;
            int t = ((u & 0x0f) << 3) + BIAS;
            t <<= (u & 0x70) >> 4;
            DECODE[i] = (short) ((u & 0x80) != 0 ? (BIAS - t) : (t - BIAS));
        }
    }

    private MuLaw() {}

    public static byte encode(int pcm) {
        int sign = (pcm >> 8) & 0x80;
        if (sign != 0) pcm = -pcm;
        if (pcm > CLIP) pcm = CLIP;
        pcm += BIAS;
        int exponent = 7;
        for (int mask = 0x4000; (pcm & mask) == 0 && exponent > 0; mask >>= 1) exponent--;
        int mantissa = (pcm >> (exponent + 3)) & 0x0f;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    public static void encode(short[] pcm, int n, byte[] out) {
        for (int i = 0; i < n; i++) out[i] = encode(pcm[i]);
    }

    public static void decode(byte[] in, int off, int n, short[] out) {
        for (int i = 0; i < n; i++) out[i] = DECODE[in[off + i] & 0xff];
    }
}
