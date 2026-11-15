package com.tacticalcomms.atak.audio;

import android.media.AudioManager;
import android.media.ToneGenerator;

/** Short radio-style chirps for PTT start / end / denied. */
public final class Tones {
    private static ToneGenerator gen;

    private Tones() {}

    private static synchronized ToneGenerator gen() {
        if (gen == null) {
            try { gen = new ToneGenerator(AudioManager.STREAM_MUSIC, 70); } catch (RuntimeException ignored) { }
        }
        return gen;
    }

    public static void talkPermit() { play(ToneGenerator.TONE_PROP_BEEP, 80); }
    public static void incoming() { play(ToneGenerator.TONE_PROP_ACK, 120); }
    public static void end() { play(ToneGenerator.TONE_PROP_PROMPT, 100); }
    public static void denied() { play(ToneGenerator.TONE_PROP_NACK, 250); }

    private static void play(int tone, int ms) {
        ToneGenerator g = gen();
        if (g != null) g.startTone(tone, ms);
    }

    public static synchronized void release() {
        if (gen != null) { gen.release(); gen = null; }
    }
}
