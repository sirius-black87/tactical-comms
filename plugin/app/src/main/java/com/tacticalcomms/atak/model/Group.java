package com.tacticalcomms.atak.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A user-created talk group. */
public class Group {
    public final String id;
    public String name;
    public String owner;
    public final List<String> members = new ArrayList<>();

    public Group(String id) { this.id = id; }

    public static Group fromJson(JSONObject o) {
        Group g = new Group(o.optString("id"));
        g.name = o.optString("name");
        g.owner = o.optString("owner");
        JSONArray m = o.optJSONArray("members");
        if (m != null) for (int i = 0; i < m.length(); i++) g.members.add(m.optString(i));
        return g;
    }
}
