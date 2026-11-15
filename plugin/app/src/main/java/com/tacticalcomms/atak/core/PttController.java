package com.tacticalcomms.atak.core;

import com.tacticalcomms.atak.audio.MicCapture;
import com.tacticalcomms.atak.audio.StreamPlayer;
import com.tacticalcomms.atak.audio.Tones;
import com.tacticalcomms.atak.model.Target;
import com.tacticalcomms.atak.net.CommsClient;
import com.tacticalcomms.atak.net.Frames;
import com.tacticalcomms.atak.ui.Ui;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live push-to-talk. While the button is held the microphone streams to the
 * server, which relays it to the target user or every online group member.
 * The server grants one talker at a time per user-pair / group ("floor").
 */
public class PttController implements MicCapture.FrameSink {

    public interface Listener {
        void onPttStateChanged();
    }

    /** A transmission currently being received. */
    public static class Incoming {
        public final String sid, from, callsign;
        public final Target target;
        final StreamPlayer player = new StreamPlayer(false);

        Incoming(String sid, String from, String callsign, Target target) {
            this.sid = sid; this.from = from; this.callsign = callsign; this.target = target;
        }
    }

    private final CommsClient client;
    private final MicCapture mic = new MicCapture(this);
    private final Listener listener;
    private final Map<String, Incoming> incoming = new ConcurrentHashMap<>();

    private volatile String sid;
    private volatile boolean granted;
    private Target talkingTo;
    private final List<byte[]> pending = new ArrayList<>();

    PttController(CommsClient client, Listener listener) {
        this.client = client;
        this.listener = listener;
    }

    public boolean isTalking() { return sid != null; }
    public Target talkingTo() { return talkingTo; }

    /** First active incoming transmission, or null. */
    public Incoming currentIncoming() {
        for (Incoming i : incoming.values()) return i;
        return null;
    }

    /** Called on button press. */
    public void startTalking(Target t) {
        if (sid != null) return;
        if (!client.isConnected()) {
            Ui.toast("Not connected");
            return;
        }
        String id = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        granted = false;
        synchronized (pending) { pending.clear(); }
        try {
            client.send(new JSONObject().put("t", "ptt_start").put("sid", id).put("to", t.toJson()));
        } catch (JSONException ignored) { }
        sid = id;
        talkingTo = t;
        if (!mic.start()) {
            Ui.toast("Microphone unavailable - check ATAK's microphone permission");
            stopTalking();
            return;
        }
        listener.onPttStateChanged();
    }

    /** Called on button release. */
    public void stopTalking() {
        String id = sid;
        if (id == null) return;
        mic.stop();
        sid = null;
        talkingTo = null;
        try {
            client.send(new JSONObject().put("t", "ptt_end").put("sid", id));
        } catch (JSONException ignored) { }
        if (granted) Tones.end();
        granted = false;
        listener.onPttStateChanged();
    }

    // ------------------------------------------------------------------ mic thread
    @Override public void onFrame(byte[] ulaw, int len) {
        String id = sid;
        if (id == null) return;
        if (granted) {
            client.sendFrame(Frames.KIND_PTT, id, ulaw, len);
            return;
        }
        synchronized (pending) {  // buffer until the server grants the floor (~1 RTT)
            if (pending.size() < 50) pending.add(ulaw.clone());
        }
    }

    // ------------------------------------------------------------------ server events (main thread)
    void onMessage(JSONObject m) {
        String t = m.optString("t");
        String id = m.optString("sid");
        switch (t) {
            case "ptt_granted":
                if (!id.equals(sid)) return;
                synchronized (pending) {
                    for (byte[] f : pending) client.sendFrame(Frames.KIND_PTT, id, f, f.length);
                    pending.clear();
                    granted = true;
                }
                Tones.talkPermit();
                if (m.optInt("listeners") == 0) Ui.toast("Nobody is online to hear you");
                break;
            case "ptt_denied":
                if (!id.equals(sid)) return;
                Tones.denied();
                mic.stop();
                sid = null;
                talkingTo = null;
                Ui.toast("Channel busy - someone else is talking");
                listener.onPttStateChanged();
                break;
            case "ptt_start": {
                JSONObject to = m.optJSONObject("to");
                Target target = to != null && Target.GROUP.equals(to.optString("type"))
                        ? Target.group(to.optString("id"))
                        : Target.user(m.optString("from"));
                incoming.put(id, new Incoming(id, m.optString("from"), m.optString("callsign"), target));
                Tones.incoming();
                listener.onPttStateChanged();
                break;
            }
            case "ptt_end": {
                if (id.equals(sid)) {  // our transmission was cut (timeout)
                    mic.stop();
                    sid = null;
                    talkingTo = null;
                    Ui.toast("Transmission ended: " + m.optString("reason"));
                }
                Incoming in = incoming.remove(id);
                if (in != null) {
                    in.player.finish();
                    Tones.end();
                }
                listener.onPttStateChanged();
                break;
            }
            default:
                break;
        }
    }

    /** Socket thread. */
    void onAudio(String id, byte[] buf, int off, int len) {
        Incoming in = incoming.get(id);
        if (in != null) in.player.feed(buf, off, len);
    }

    void reset() {
        mic.stop();
        sid = null;
        talkingTo = null;
        for (Incoming in : incoming.values()) in.player.stop();
        incoming.clear();
    }
}
