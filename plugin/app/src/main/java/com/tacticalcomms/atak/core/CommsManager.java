package com.tacticalcomms.atak.core;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.tacticalcomms.atak.map.PositionManager;
import com.tacticalcomms.atak.model.ChatMessage;
import com.tacticalcomms.atak.model.ChatStore;
import com.tacticalcomms.atak.model.Group;
import com.tacticalcomms.atak.model.Target;
import com.tacticalcomms.atak.model.User;
import com.tacticalcomms.atak.net.CommsClient;
import com.tacticalcomms.atak.net.FileTransfer;
import com.tacticalcomms.atak.net.Frames;
import com.tacticalcomms.atak.net.TlsPin;
import com.tacticalcomms.atak.plugin.Prefs;
import com.tacticalcomms.atak.ui.Ui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central state of the plugin: connection, user directory, groups, conversations,
 * and the PTT / call / position / file sub-systems. All public methods are main-thread only.
 */
public class CommsManager implements CommsClient.Listener, PttController.Listener {

    public interface Observer {
        void onCommsChanged();
    }

    public final CommsClient client = new CommsClient(this);
    public final PttController ptt = new PttController(client, this);
    public final CallController calls = new CallController(client);
    public final PositionManager positions = new PositionManager(client);
    public final ChatStore chats;
    public final Prefs prefs;

    private final FileTransfer files = new FileTransfer();
    private final File dir;
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();

    private CommsClient.State state = CommsClient.State.DISCONNECTED;
    private String stateDetail;
    private String openConversation;

    public CommsManager(Prefs prefs) {
        this.prefs = prefs;
        dir = FileSystemUtils.getItem("tools/tacticalcomms");
        chats = new ChatStore(new File(dir, "chats.json"));
    }

    // ------------------------------------------------------------------ lifecycle
    public void connect() {
        if (!prefs.isConfigured()) {
            Ui.toast("Set the server address first (Settings)");
            return;
        }
        MapView mv = MapView.getMapView();
        positions.configure(prefs.shareLocation(), prefs.positionIntervalSec());
        client.connect(prefs.serverUrl(), prefs.accessKey(), MapView.getDeviceUid(), mv.getDeviceCallsign(),
                new TlsPin.Store() {
                    @Override public String get() { return prefs.certFingerprint(); }

                    @Override public void learned(String fp) {
                        prefs.setCertFingerprint(TlsPin.format(fp));
                        Ui.toastLong("Tactical Comms: server certificate saved. Compare with the server log: "
                                + TlsPin.format(fp).substring(0, 23) + "…");
                    }
                });
        positions.start();
    }

    public void disconnect() {
        positions.stop();
        client.disconnect();
        ptt.reset();
        calls.reset();
    }

    public void shutdown() {
        disconnect();
        files.shutdown();
        chats.close();
    }

    // ------------------------------------------------------------------ observers
    public void addObserver(Observer o) { observers.add(o); }
    public void removeObserver(Observer o) { observers.remove(o); }

    private void changed() {
        for (Observer o : observers) o.onCommsChanged();
    }

    @Override public void onPttStateChanged() { changed(); }

    // ------------------------------------------------------------------ queries
    public CommsClient.State state() { return state; }
    public String stateDetail() { return stateDetail; }
    public String myUid() { return MapView.getDeviceUid(); }

    public User user(String uid) { return users.get(uid); }

    /** Online users first, then alphabetical. */
    public List<User> users() {
        List<User> l = new ArrayList<>(users.values());
        Collections.sort(l, new Comparator<User>() {
            @Override public int compare(User a, User b) {
                if (a.online != b.online) return a.online ? -1 : 1;
                return a.callsign.compareToIgnoreCase(b.callsign);
            }
        });
        return l;
    }

    public Collection<Group> groups() { return groups.values(); }
    public Group group(String id) { return groups.get(id); }

    public String callsignOf(String uid) {
        if (uid.equals(myUid())) return "Me";
        User u = users.get(uid);
        return u != null ? u.callsign : uid;
    }

    public String titleOf(Target t) {
        if (t.isGroup()) {
            Group g = groups.get(t.id);
            return g != null ? g.name : "(group)";
        }
        return callsignOf(t.id);
    }

    public void setOpenConversation(String key) {
        openConversation = key;
        if (key != null) chats.clearUnread(key);
    }

    // ------------------------------------------------------------------ actions
    public void sendText(Target to, String text) {
        ChatMessage m = newOutgoing(to);
        m.text = text;
        chats.add(m);
        transmit(m, null);
        changed();
    }

    public void sendFile(final Target to, File f) {
        if (client.getToken() == null) {
            Ui.toast("Not connected");
            return;
        }
        final ChatMessage m = newOutgoing(to);
        m.fileName = f.getName();
        m.fileSize = f.length();
        m.localPath = f.getAbsolutePath();
        chats.add(m);
        changed();
        upload(m, f);
    }

    private void upload(final ChatMessage m, File f) {
        files.upload(client.httpBase(), client.getToken(), client.getTls(), f, new FileTransfer.Callback() {
            @Override public void onProgress(long done, long total) { }

            @Override public void onDone(final JSONObject r, File file) {
                Ui.run(new Runnable() {
                    @Override public void run() {
                        m.fileId = r.optString("id");
                        m.fileMime = r.optString("mime");
                        try {
                            transmit(m, new JSONObject().put("id", m.fileId).put("kind", "file"));
                        } catch (JSONException ignored) { }
                        changed();
                    }
                });
            }

            @Override public void onError(final String error) {
                Ui.run(new Runnable() {
                    @Override public void run() {
                        m.status = ChatMessage.FAILED;
                        chats.save();
                        Ui.toast(error);
                        changed();
                    }
                });
            }
        });
    }

    public void download(final ChatMessage m) {
        if (client.getToken() == null) {
            Ui.toast("Not connected");
            return;
        }
        File recvDir = new File(dir, "received");
        File dest = uniqueFile(recvDir, sanitize(m.fileName));
        Ui.toast("Downloading " + m.fileName + "…");
        files.download(client.httpBase(), client.getToken(), client.getTls(), m.fileId, dest, new FileTransfer.Callback() {
            @Override public void onProgress(long done, long total) { }

            @Override public void onDone(JSONObject r, final File file) {
                Ui.run(new Runnable() {
                    @Override public void run() {
                        m.localPath = file.getAbsolutePath();
                        chats.save();
                        Ui.toastLong("Saved: " + file.getAbsolutePath());
                        changed();
                    }
                });
            }

            @Override public void onError(String error) { Ui.toast(error); }
        });
    }

    public void createGroup(String name, List<String> members) {
        try {
            client.send(new JSONObject().put("t", "group_create").put("name", name)
                    .put("members", new JSONArray(members)));
        } catch (JSONException ignored) { }
    }

    public void updateGroup(String gid, String newName, List<String> add, List<String> remove) {
        try {
            JSONObject m = new JSONObject().put("t", "group_update").put("gid", gid);
            if (newName != null) m.put("name", newName);
            if (add != null && !add.isEmpty()) m.put("add", new JSONArray(add));
            if (remove != null && !remove.isEmpty()) m.put("remove", new JSONArray(remove));
            client.send(m);
        } catch (JSONException ignored) { }
    }

    public void leaveGroup(String gid) {
        try {
            client.send(new JSONObject().put("t", "group_leave").put("gid", gid));
        } catch (JSONException ignored) { }
    }

    private ChatMessage newOutgoing(Target to) {
        ChatMessage m = new ChatMessage();
        m.mid = UUID.randomUUID().toString();
        m.convKey = to.key();
        m.from = myUid();
        m.callsign = "Me";
        m.ts = System.currentTimeMillis();
        m.outgoing = true;
        m.status = ChatMessage.SENDING;
        return m;
    }

    private void transmit(ChatMessage m, JSONObject file) {
        try {
            JSONObject j = new JSONObject().put("t", "chat").put("mid", m.mid)
                    .put("to", Target.fromKey(m.convKey).toJson()).put("text", m.text);
            if (file != null) j.put("file", file);
            if (!client.send(j)) {
                m.status = ChatMessage.FAILED;
                chats.save();
            }
        } catch (JSONException ignored) { }
    }

    public void retry(ChatMessage m) {
        m.status = ChatMessage.SENDING;
        if (m.hasFile() && m.fileId == null) {
            // upload never completed: upload again
            File f = m.localPath != null ? new File(m.localPath) : null;
            if (f == null || !f.exists() || client.getToken() == null) {
                m.status = ChatMessage.FAILED;
                Ui.toast(client.getToken() == null ? "Not connected" : "File no longer exists");
            } else {
                upload(m, f);
            }
            changed();
            return;
        }
        JSONObject file = null;
        if (m.fileId != null) {
            try { file = new JSONObject().put("id", m.fileId).put("kind", "file"); } catch (JSONException ignored) { }
        }
        transmit(m, file);
        changed();
    }

    // ------------------------------------------------------------------ CommsClient.Listener
    @Override public void onStateChanged(CommsClient.State s, String detail) {
        state = s;
        stateDetail = detail;
        if (s != CommsClient.State.CONNECTED) {
            ptt.reset();
            calls.reset();
            for (User u : users.values()) u.online = false;
        }
        if (s == CommsClient.State.AUTH_FAILED) Ui.toastLong("Tactical Comms: wrong access key");
        changed();
    }

    @Override public void onAudioFrame(int kind, String id, byte[] buf, int off, int len) {
        if (kind == Frames.KIND_PTT) ptt.onAudio(id, buf, off, len);
        else if (kind == Frames.KIND_CALL) calls.onAudio(id, buf, off, len);
    }

    @Override public void onMessage(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "welcome": {
                users.clear();
                JSONArray ua = m.optJSONArray("users");
                if (ua != null) for (int i = 0; i < ua.length(); i++) putUser(User.fromJson(ua.optJSONObject(i)));
                groups.clear();
                JSONArray ga = m.optJSONArray("groups");
                if (ga != null) for (int i = 0; i < ga.length(); i++) {
                    Group g = Group.fromJson(ga.optJSONObject(i));
                    groups.put(g.id, g);
                }
                for (User u : users.values()) positions.showUser(u);
                break;
            }
            case "users": {
                JSONArray ua = m.optJSONArray("users");
                if (ua != null) for (int i = 0; i < ua.length(); i++) putUser(User.fromJson(ua.optJSONObject(i)));
                break;
            }
            case "presence": {
                User u = users.get(m.optString("uid"));
                if (u == null) putUser(u = new User(m.optString("uid")));
                u.callsign = m.optString("callsign", u.uid);
                u.online = m.optBoolean("online");
                u.lastSeen = System.currentTimeMillis();
                break;
            }
            case "pos": {
                String uid = m.optString("uid");
                User u = users.get(uid);
                if (u == null) putUser(u = new User(uid));
                u.callsign = m.optString("callsign", u.uid);
                u.online = true;
                u.applyPosition(m);
                positions.showUser(u);
                break;
            }
            case "chat":
                onChat(m);
                break;
            case "ack": {
                ChatMessage cm = chats.find(m.optString("mid"));
                if (cm != null) {
                    cm.status = ChatMessage.SENT;
                    chats.save();
                }
                break;
            }
            case "group": {
                JSONObject go = m.optJSONObject("group");
                if (go != null) {
                    Group g = Group.fromJson(go);
                    if (!groups.containsKey(g.id)) Ui.toast("Group: " + g.name);
                    groups.put(g.id, g);
                }
                break;
            }
            case "group_removed": {
                Group g = groups.remove(m.optString("gid"));
                if (g != null) Ui.toast("You are no longer in group " + g.name);
                break;
            }
            case "error": {
                ChatMessage cm = chats.find(m.optString("ref"));
                if (cm != null) {
                    cm.status = ChatMessage.FAILED;
                    chats.save();
                }
                Ui.toast("Server: " + m.optString("message", m.optString("code")));
                break;
            }
            case "ptt_granted": case "ptt_denied": case "ptt_start": case "ptt_end":
                ptt.onMessage(m);
                return; // ptt notifies itself
            case "call_invite": case "call_ringing": case "call_accepted": case "call_end":
                calls.onMessage(m);
                break;
            default:
                return;
        }
        changed();
    }

    private void putUser(User u) {
        if (u.uid.isEmpty() || u.uid.equals(myUid())) return;
        users.put(u.uid, u);
    }

    private void onChat(JSONObject j) {
        ChatMessage m = new ChatMessage();
        m.mid = j.optString("mid");
        m.from = j.optString("from");
        m.callsign = j.optString("callsign");
        m.text = j.optString("text");
        m.ts = j.optLong("ts", System.currentTimeMillis());
        m.status = ChatMessage.RECEIVED;
        JSONObject to = j.optJSONObject("to");
        boolean group = to != null && Target.GROUP.equals(to.optString("type"));
        m.convKey = group ? Target.group(to.optString("id")).key() : Target.user(m.from).key();
        JSONObject f = j.optJSONObject("file");
        if (f != null) {
            m.fileId = f.optString("id");
            m.fileName = f.optString("name");
            m.fileSize = f.optLong("size");
            m.fileMime = f.optString("mime");
        }
        chats.add(m);
        if (!m.convKey.equals(openConversation)) {
            chats.incUnread(m.convKey);
            String where = group ? " @ " + titleOf(Target.fromKey(m.convKey)) : "";
            Ui.toast(m.callsign + where + ": " + (m.hasFile() ? "📎 " + m.fileName : m.text));
        }
    }

    // ------------------------------------------------------------------ helpers
    private static String sanitize(String name) {
        String n = name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return n.isEmpty() ? "file" : n;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }
}
