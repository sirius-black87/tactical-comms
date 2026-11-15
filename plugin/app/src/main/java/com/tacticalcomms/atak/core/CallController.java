package com.tacticalcomms.atak.core;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.SystemClock;

import com.tacticalcomms.atak.audio.MicCapture;
import com.tacticalcomms.atak.audio.StreamPlayer;
import com.tacticalcomms.atak.net.CommsClient;
import com.tacticalcomms.atak.net.Frames;
import com.tacticalcomms.atak.ui.Ui;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/** One-to-one full-duplex voice calls relayed through the server. */
public class CallController implements MicCapture.FrameSink {

    public enum State { IDLE, OUTGOING, INCOMING, ACTIVE }

    private final CommsClient client;
    private final MicCapture mic = new MicCapture(this);
    private State state = State.IDLE;
    private volatile String cid;
    private String peerName;
    private volatile StreamPlayer player;
    private Ringtone ringtone;
    private AlertDialog dialog;
    private long startedAt;
    private boolean speaker;
    private int savedMode = AudioManager.MODE_NORMAL;

    CallController(CommsClient client) { this.client = client; }

    public State getState() { return state; }
    public boolean isBusy() { return state != State.IDLE; }

    public void call(String uid, String callsign) {
        if (state != State.IDLE) {
            Ui.toast("Already in a call");
            return;
        }
        if (!client.isConnected()) {
            Ui.toast("Not connected");
            return;
        }
        cid = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        peerName = callsign;
        state = State.OUTGOING;
        send("call_invite", "to", uid);
        showDialog("Calling " + callsign + "…", null, "Cancel");
    }

    // ------------------------------------------------------------------ server events
    void onMessage(JSONObject m) {
        String t = m.optString("t");
        String id = m.optString("cid");
        switch (t) {
            case "call_invite":
                if (state != State.IDLE) {
                    sendFor(id, "call_reject", "reason", "busy");
                    return;
                }
                cid = id;
                peerName = m.optString("callsign");
                state = State.INCOMING;
                startRinging();
                showDialog("Incoming call from " + peerName, "Answer", "Decline");
                break;
            case "call_ringing":
                if (id.equals(cid) && dialog != null) dialog.setMessage("Ringing " + peerName + "…");
                break;
            case "call_accepted":
                if (id.equals(cid) && state == State.OUTGOING) startMedia();
                break;
            case "call_end":
                if (id.equals(cid)) {
                    String reason = m.optString("reason");
                    endLocal();
                    Ui.toast("Call ended" + (reason.isEmpty() ? "" : " (" + reason.replace('_', ' ') + ")"));
                }
                break;
            default:
                break;
        }
    }

    void onAudio(String id, byte[] buf, int off, int len) {
        StreamPlayer p = player;
        if (p != null && id.equals(cid)) p.feed(buf, off, len);
    }

    @Override public void onFrame(byte[] ulaw, int len) {
        String id = cid;
        if (id != null && state == State.ACTIVE) client.sendFrame(Frames.KIND_CALL, id, ulaw, len);
    }

    // ------------------------------------------------------------------ actions
    private void answer() {
        if (state != State.INCOMING) return;
        stopRinging();
        send("call_accept", null, null);
        startMedia();
    }

    public void hangup() {
        if (state == State.IDLE) return;
        if (state == State.INCOMING) send("call_reject", "reason", "rejected");
        else send("call_end", null, null);
        endLocal();
    }

    private void startMedia() {
        AudioManager am = audioManager();
        savedMode = am.getMode();
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        am.setSpeakerphoneOn(speaker);
        player = new StreamPlayer(true);
        state = State.ACTIVE;
        startedAt = SystemClock.elapsedRealtime();
        if (!mic.start()) Ui.toast("Microphone unavailable - you can only listen");
        showDialog("In call with " + peerName, null, "Hang up");
        tickTimer();
    }

    private void endLocal() {
        stopRinging();
        mic.stop();
        mic.setMuted(false);
        StreamPlayer p = player;
        player = null;
        if (p != null) p.stop();
        if (state == State.ACTIVE) {
            AudioManager am = audioManager();
            am.setSpeakerphoneOn(false);
            am.setMode(savedMode);
        }
        state = State.IDLE;
        cid = null;
        if (dialog != null) {
            AlertDialog d = dialog;
            dialog = null;
            d.dismiss();
        }
    }

    void reset() {
        if (state != State.IDLE) endLocal();
    }

    // ------------------------------------------------------------------ UI
    private void showDialog(String message, String positive, String negative) {
        if (dialog != null) {
            AlertDialog d = dialog;
            dialog = null;
            d.dismiss();
        }
        Context ctx = Ui.mapContext();
        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle("Tactical Comms - voice call")
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton(negative, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { hangup(); }
                });
        if (positive != null) {
            b.setPositiveButton(positive, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { answer(); }
            });
        }
        if (state == State.ACTIVE) {
            b.setNeutralButton("Speaker / Mute", null);
        }
        final AlertDialog d = b.create();
        dialog = d;
        d.show();
        if (state == State.ACTIVE) {
            // keep the dialog open on the neutral button and cycle: earpiece -> speaker -> muted
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { cycleAudioMode(); }
            });
        }
    }

    private void cycleAudioMode() {
        AudioManager am = audioManager();
        if (!speaker && !mic.isMuted()) {
            speaker = true;
        } else if (speaker && !mic.isMuted()) {
            mic.setMuted(true);
        } else {
            speaker = false;
            mic.setMuted(false);
        }
        am.setSpeakerphoneOn(speaker);
        Ui.toast((speaker ? "Speaker" : "Earpiece") + (mic.isMuted() ? ", mic muted" : ""));
    }

    private void tickTimer() {
        Ui.later(new Runnable() {
            @Override public void run() {
                if (state != State.ACTIVE || dialog == null) return;
                long s = (SystemClock.elapsedRealtime() - startedAt) / 1000;
                dialog.setMessage(String.format(Locale.US, "In call with %s  %02d:%02d%s%s",
                        peerName, s / 60, s % 60, speaker ? "  [speaker]" : "", mic.isMuted() ? "  [muted]" : ""));
                Ui.later(this, 1000);
            }
        }, 1000);
    }

    private void startRinging() {
        try {
            ringtone = RingtoneManager.getRingtone(Ui.mapContext(),
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) { }
    }

    private void stopRinging() {
        if (ringtone != null) {
            try { ringtone.stop(); } catch (Exception ignored) { }
            ringtone = null;
        }
    }

    private AudioManager audioManager() {
        return (AudioManager) Ui.mapContext().getSystemService(Context.AUDIO_SERVICE);
    }

    private void send(String type, String k, String v) { sendFor(cid, type, k, v); }

    private void sendFor(String id, String type, String k, String v) {
        try {
            JSONObject m = new JSONObject().put("t", type).put("cid", id);
            if (k != null) m.put(k, v);
            client.send(m);
        } catch (JSONException ignored) { }
    }
}
