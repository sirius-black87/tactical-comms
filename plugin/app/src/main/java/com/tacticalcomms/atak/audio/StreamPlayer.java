package com.tacticalcomms.atak.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays a stream of mu-law frames with a small jitter buffer.
 * Thread safe: {@link #feed} may be called from the socket thread.
 */
public class StreamPlayer {
    private static final int PREBUFFER_FRAMES = 3;   // 60 ms
    private static final int MAX_QUEUE_FRAMES = 25;  // 500 ms; older audio is dropped

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AudioTrack track;
    private volatile boolean running = true;
    private volatile boolean finishing;

    /** @param voiceCall true for calls (earpiece/voice routing), false for PTT (speaker). */
    public StreamPlayer(boolean voiceCall) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(voiceCall ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(AudioConfig.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        int min = AudioTrack.getMinBufferSize(AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(attrs, fmt, Math.max(min, AudioConfig.FRAME_SAMPLES * 2 * 8),
                AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "TC-play");
        t.start();
    }

    public void feed(byte[] buf, int off, int len) {
        if (!running || finishing) return;
        byte[] copy = new byte[len];
        System.arraycopy(buf, off, copy, 0, len);
        queue.offer(copy);
        while (queue.size() > MAX_QUEUE_FRAMES) queue.poll();
    }

    /** Play what is queued, then release. */
    public void finish() { finishing = true; }

    /** Stop immediately. */
    public void stop() { running = false; }

    private void loop() {
        short[] pcm = new short[AudioConfig.FRAME_SAMPLES * 4];
        boolean started = false;
        try {
            while (running) {
                if (!started) {
                    if (queue.size() < PREBUFFER_FRAMES && !finishing) {
                        Thread.sleep(10);
                        continue;
                    }
                    track.play();
                    started = true;
                }
                byte[] f = queue.poll(200, TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (finishing) break;
                    continue;  // underrun: AudioTrack plays silence
                }
                if (pcm.length < f.length) pcm = new short[f.length];
                MuLaw.decode(f, 0, f.length, pcm);
                track.write(pcm, 0, f.length);
            }
            if (started && running) Thread.sleep(150); // let the tail drain
        } catch (InterruptedException ignored) {
        } finally {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
        }
    }
}
