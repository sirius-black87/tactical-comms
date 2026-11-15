package com.tacticalcomms.atak.model;

import org.json.JSONException;
import org.json.JSONObject;

/** One chat line, optionally carrying a file attachment. */
public class ChatMessage {
    public static final int SENDING = 0, SENT = 1, FAILED = 2, RECEIVED = 3;

    public String mid;
    public String convKey;
    public String from;
    public String callsign;
    public String text = "";
    public long ts;
    public boolean outgoing;
    public int status;

    // attachment
    public String fileId, fileName, fileMime;
    public long fileSize;
    public String localPath;  // set once uploaded (outgoing) / downloaded (incoming)

    public boolean hasFile() { return fileName != null; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject()
                .put("mid", mid).put("conv", convKey).put("from", from).put("callsign", callsign)
                .put("text", text).put("ts", ts).put("out", outgoing).put("status", status);
        if (hasFile()) {
            o.put("fileId", fileId).put("fileName", fileName).put("fileMime", fileMime)
                    .put("fileSize", fileSize).put("localPath", localPath);
        }
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) {
        ChatMessage m = new ChatMessage();
        m.mid = o.optString("mid");
        m.convKey = o.optString("conv");
        m.from = o.optString("from");
        m.callsign = o.optString("callsign");
        m.text = o.optString("text");
        m.ts = o.optLong("ts");
        m.outgoing = o.optBoolean("out");
        m.status = o.optInt("status");
        if (m.status == SENDING) m.status = FAILED; // app restarted mid-send
        if (o.has("fileName")) {
            m.fileId = o.optString("fileId", null);
            m.fileName = o.optString("fileName");
            m.fileMime = o.optString("fileMime");
            m.fileSize = o.optLong("fileSize");
            m.localPath = o.optString("localPath", null);
        }
        return m;
    }
}
