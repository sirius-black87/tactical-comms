package com.tacticalcomms.atak.model;

import org.json.JSONException;
import org.json.JSONObject;

/** Destination of a chat message or PTT transmission: a single user or a group. */
public class Target {
    public static final String USER = "user";
    public static final String GROUP = "group";

    public final String type;
    public final String id;

    public Target(String type, String id) { this.type = type; this.id = id; }

    public static Target user(String uid) { return new Target(USER, uid); }
    public static Target group(String gid) { return new Target(GROUP, gid); }

    public boolean isGroup() { return GROUP.equals(type); }

    /** Stable key for a conversation, e.g. "u:ANDROID-123" or "g:abcd". */
    public String key() { return (isGroup() ? "g:" : "u:") + id; }

    public static Target fromKey(String key) {
        return key.startsWith("g:") ? group(key.substring(2)) : user(key.substring(2));
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("type", type).put("id", id);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public boolean equals(Object o) {
        return o instanceof Target && ((Target) o).type.equals(type) && ((Target) o).id.equals(id);
    }

    @Override public int hashCode() { return key().hashCode(); }
}
