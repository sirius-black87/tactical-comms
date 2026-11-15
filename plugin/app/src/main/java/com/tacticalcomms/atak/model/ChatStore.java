package com.tacticalcomms.atak.model;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Conversation history, persisted as JSON under the ATAK tools directory. Main thread only. */
public class ChatStore {
    private static final String TAG = "TacticalComms.Chat";
    private static final int MAX_PER_CONV = 300;

    private final File file;
    private final Map<String, List<ChatMessage>> convs = new LinkedHashMap<>();
    private final Map<String, Integer> unread = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public ChatStore(File file) {
        this.file = file;
        load();
    }

    public List<ChatMessage> messages(String conv) {
        List<ChatMessage> l = convs.get(conv);
        return l == null ? Collections.<ChatMessage>emptyList() : l;
    }

    /** Conversation keys, most recent first. */
    public List<String> conversations() {
        List<String> keys = new ArrayList<>(convs.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return Long.compare(lastTs(b), lastTs(a));
            }
        });
        return keys;
    }

    public ChatMessage last(String conv) {
        List<ChatMessage> l = convs.get(conv);
        return l == null || l.isEmpty() ? null : l.get(l.size() - 1);
    }

    private long lastTs(String conv) {
        ChatMessage m = last(conv);
        return m == null ? 0 : m.ts;
    }

    public void add(ChatMessage m) {
        List<ChatMessage> l = convs.get(m.convKey);
        if (l == null) convs.put(m.convKey, l = new ArrayList<>());
        for (ChatMessage x : l) if (x.mid != null && x.mid.equals(m.mid)) return; // duplicate
        l.add(m);
        if (l.size() > MAX_PER_CONV) l.subList(0, l.size() - MAX_PER_CONV).clear();
        save();
    }

    public ChatMessage find(String mid) {
        for (List<ChatMessage> l : convs.values())
            for (ChatMessage m : l) if (mid.equals(m.mid)) return m;
        return null;
    }

    public int unread(String conv) {
        Integer n = unread.get(conv);
        return n == null ? 0 : n;
    }

    public int totalUnread() {
        int t = 0;
        for (int n : unread.values()) t += n;
        return t;
    }

    public void incUnread(String conv) { unread.put(conv, unread(conv) + 1); }
    public void clearUnread(String conv) { unread.remove(conv); }

    public void save() {
        final String data;
        try {
            JSONArray arr = new JSONArray();
            for (List<ChatMessage> l : convs.values()) for (ChatMessage m : l) arr.put(m.toJson());
            data = arr.toString();
        } catch (Exception e) {
            Log.w(TAG, "serialize failed", e);
            return;
        }
        io.execute(new Runnable() {
            @Override public void run() {
                File tmp = new File(file.getPath() + ".tmp");
                try {
                    File dir = file.getParentFile();
                    if (dir != null) //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    try (OutputStream out = new FileOutputStream(tmp)) {
                        out.write(data.getBytes("UTF-8"));
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(file);
                } catch (Exception e) {
                    Log.w(TAG, "save failed", e);
                }
            }
        });
    }

    private void load() {
        if (!file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray arr = new JSONArray(new String(b, 0, off, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ChatMessage m = ChatMessage.fromJson(o);
                List<ChatMessage> l = convs.get(m.convKey);
                if (l == null) convs.put(m.convKey, l = new ArrayList<>());
                l.add(m);
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed", e);
        }
    }

    public void close() { io.shutdown(); }
}
