package com.tacticalcomms.atak.model;

import org.json.JSONObject;

/** A user known to the server (online or offline). */
public class User {
    public final String uid;
    public String callsign;
    public boolean online;
    public long lastSeen;
    public double lat = Double.NaN, lon = Double.NaN, hae = Double.NaN;
    public int battery = -1;
    public long posTs;

    public User(String uid) { this.uid = uid; }

    public boolean hasPosition() { return !Double.isNaN(lat) && !Double.isNaN(lon); }

    public static User fromJson(JSONObject o) {
        User u = new User(o.optString("uid"));
        u.callsign = o.optString("callsign", u.uid);
        u.online = o.optBoolean("online");
        u.lastSeen = o.optLong("lastSeen");
        JSONObject p = o.optJSONObject("pos");
        if (p != null) u.applyPosition(p);
        return u;
    }

    public void applyPosition(JSONObject p) {
        lat = p.optDouble("lat", Double.NaN);
        lon = p.optDouble("lon", Double.NaN);
        hae = p.optDouble("hae", Double.NaN);
        battery = p.optInt("battery", battery);
        posTs = p.optLong("ts", System.currentTimeMillis());
    }
}
