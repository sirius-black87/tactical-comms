package com.tacticalcomms.atak.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket connection to the Tactical Comms server with automatic reconnect.
 * JSON messages are delivered on the main thread; binary audio frames are
 * delivered directly on the socket thread (to keep latency low).
 */
public class CommsClient {
    private static final String TAG = "TacticalComms.Client";

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, AUTH_FAILED }

    public interface Listener {
        void onStateChanged(State state, String detail);
        /** Main thread. */
        void onMessage(JSONObject msg);
        /** Socket thread. */
        void onAudioFrame(int kind, String streamId, byte[] buf, int offset, int length);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private WebSocketClient ws;
    private volatile State state = State.DISCONNECTED;
    private boolean wanted;
    private int attempt;
    private String url, key, uid, callsign;
    private volatile String token;

    public CommsClient(Listener listener) { this.listener = listener; }

    public State getState() { return state; }
    public String getToken() { return token; }
    public String getUid() { return uid; }

    /** http(s)://host:port derived from the ws(s):// URL, for the file API. */
    public String httpBase() {
        if (url == null) return null;
        URI u = URI.create(url);
        String scheme = "wss".equals(u.getScheme()) ? "https" : "http";
        return scheme + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    public synchronized void connect(String url, String key, String uid, String callsign) {
        this.url = normalize(url);
        this.key = key;
        this.uid = uid;
        this.callsign = callsign;
        wanted = true;
        attempt = 0;
        main.removeCallbacks(reconnect);
        open();
    }

    public synchronized void disconnect() {
        wanted = false;
        main.removeCallbacks(reconnect);
        if (ws != null) {
            ws.close();
            ws = null;
        }
        setState(State.DISCONNECTED, null);
    }

    public boolean isConnected() { return state == State.CONNECTED; }

    public boolean send(JSONObject msg) {
        WebSocketClient c = ws;
        if (c == null || !c.isOpen()) return false;
        try {
            c.send(msg.toString());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "send failed", e);
            return false;
        }
    }

    public boolean sendFrame(int kind, String id, byte[] payload, int len) {
        WebSocketClient c = ws;
        if (c == null || !c.isOpen()) return false;
        try {
            c.send(Frames.build(kind, id, payload, len));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ internals

    private static String normalize(String u) {
        u = u.trim();
        if (!u.contains("://")) u = "wss://" + u;
        if (u.startsWith("https://")) u = "wss://" + u.substring(8);
        if (u.startsWith("http://")) u = "ws://" + u.substring(7);
        URI p = URI.create(u);
        if (p.getPath() == null || p.getPath().isEmpty() || "/".equals(p.getPath())) {
            u = u.replaceAll("/+$", "") + "/ws";
        }
        return u;
    }

    private final Runnable reconnect = new Runnable() {
        @Override public void run() {
            synchronized (CommsClient.this) {
                if (wanted) open();
            }
        }
    };

    private void scheduleReconnect() {
        if (!wanted) return;
        long delay = Math.min(30000, 2000L << Math.min(attempt, 4));
        attempt++;
        main.postDelayed(reconnect, delay);
    }

    private void open() {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) { }
        }
        setState(State.CONNECTING, url);
        final WebSocketClient c;
        try {
            c = new WebSocketClient(URI.create(url)) {
                @Override public void onOpen(ServerHandshake h) {
                    try {
                        send(new JSONObject().put("t", "hello").put("uid", uid)
                                .put("callsign", callsign).put("key", key).toString());
                    } catch (JSONException ignored) { }
                }

                @Override public void onMessage(String text) {
                    final JSONObject m;
                    try {
                        m = new JSONObject(text);
                    } catch (JSONException e) {
                        return;
                    }
                    if ("welcome".equals(m.optString("t"))) {
                        token = m.optString("token");
                        attempt = 0;
                        setState(State.CONNECTED, null);
                    }
                    main.post(new Runnable() {
                        @Override public void run() { listener.onMessage(m); }
                    });
                }

                @Override public void onMessage(ByteBuffer bytes) {
                    byte[] b = new byte[bytes.remaining()];
                    bytes.get(b);
                    if (b.length < 3) return;
                    int n = b[1] & 0xff;
                    if (b.length < 2 + n) return;
                    String id = new String(b, 2, n, StandardCharsets.US_ASCII);
                    listener.onAudioFrame(b[0], id, b, 2 + n, b.length - 2 - n);
                }

                @Override public void onClose(int code, String reason, boolean remote) {
                    Log.i(TAG, "closed " + code + " " + reason);
                    synchronized (CommsClient.this) {
                        if (ws != this) return;
                        ws = null;
                        token = null;
                        if (code == 4003) {
                            wanted = false;
                            setState(State.AUTH_FAILED, reason);
                        } else if (code == 4004) {
                            wanted = false;
                            setState(State.DISCONNECTED, "logged in from another device");
                        } else {
                            setState(wanted ? State.CONNECTING : State.DISCONNECTED, reason);
                            scheduleReconnect();
                        }
                    }
                }

                @Override public void onError(Exception ex) {
                    Log.w(TAG, "socket error: " + ex);
                }
            };
        } catch (IllegalArgumentException e) {
            setState(State.DISCONNECTED, "invalid server URL");
            wanted = false;
            return;
        }
        c.setConnectionLostTimeout(30);
        ws = c;
        c.connect();
    }

    private void setState(final State s, final String detail) {
        state = s;
        main.post(new Runnable() {
            @Override public void run() { listener.onStateChanged(s, detail); }
        });
    }
}
