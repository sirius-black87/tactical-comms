package com.tacticalcomms.atak.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

/** Captures the microphone and emits 20 ms mu-law frames on a background thread. */
public class MicCapture {
    private static final String TAG = "TacticalComms.Mic";

    public interface FrameSink {
        void onFrame(byte[] ulaw, int len);
    }

    private final FrameSink sink;
    private volatile boolean running;
    private volatile boolean muted;
    private Thread thread;

    public MicCapture(FrameSink sink) { this.sink = sink; }

    public void setMuted(boolean m) { muted = m; }
    public boolean isMuted() { return muted; }

    /** @return false if the microphone could not be opened (e.g. permission missing). */
    public synchronized boolean start() {
        if (running) return true;
        final AudioRecord rec;
        try {
            int min = AudioRecord.getMinBufferSize(AudioConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, AudioConfig.FRAME_SAMPLES * 2 * 10));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot open mic", e);
            return false;
        }
        enableEffects(rec.getAudioSessionId());
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                short[] pcm = new short[AudioConfig.FRAME_SAMPLES];
                byte[] ulaw = new byte[AudioConfig.FRAME_SAMPLES];
                try {
                    rec.startRecording();
                    while (running) {
                        int got = 0;
                        while (got < pcm.length && running) {
                            int n = rec.read(pcm, got, pcm.length - got);
                            if (n <= 0) break;
                            got += n;
                        }
                        if (got < pcm.length) continue;
                        if (muted) java.util.Arrays.fill(pcm, (short) 0);
                        MuLaw.encode(pcm, pcm.length, ulaw);
                        sink.onFrame(ulaw, ulaw.length);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "capture error", e);
                } finally {
                    try { rec.stop(); } catch (Exception ignored) { }
                    rec.release();
                }
            }
        }, "TC-mic");
        thread.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        thread = null;
    }

    private static void enableEffects(int session) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Exception ignored) { }
    }
}
