package com.tacticalcomms.atak.net;

import java.nio.charset.StandardCharsets;

/**
 * Binary WebSocket frame layout (see docs/PROTOCOL.md):
 * <pre>
 *   byte 0      kind (1 = PTT, 2 = call)
 *   byte 1      N = length of stream id
 *   bytes 2..   stream id (ASCII, N bytes)
 *   rest        audio payload (G.711 mu-law, 8 kHz mono, 20 ms = 160 bytes)
 * </pre>
 */
public final class Frames {
    public static final int KIND_PTT = 1;
    public static final int KIND_CALL = 2;

    private Frames() {}

    public static byte[] build(int kind, String id, byte[] payload, int len) {
        byte[] idb = id.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[2 + idb.length + len];
        out[0] = (byte) kind;
        out[1] = (byte) idb.length;
        System.arraycopy(idb, 0, out, 2, idb.length);
        System.arraycopy(payload, 0, out, 2 + idb.length, len);
        return out;
    }
}
