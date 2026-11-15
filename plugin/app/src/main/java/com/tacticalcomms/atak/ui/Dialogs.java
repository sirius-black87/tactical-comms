package com.tacticalcomms.atak.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tacticalcomms.atak.core.CommsManager;
import com.tacticalcomms.atak.model.Group;
import com.tacticalcomms.atak.model.User;
import com.tacticalcomms.atak.net.CommsClient;
import com.tacticalcomms.atak.plugin.Prefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tacticalcomms.atak.ui.Views.*;

final class Dialogs {
    private Dialogs() {}

    private static EditText field(Context c, String hint, String value) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    private static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setPadding(0, dp(c, 10), 0, 0);
        return t;
    }

    static void settings(Context ctx, final CommsManager mgr) {
        final Prefs prefs = mgr.prefs;
        LinearLayout l = column(ctx);
        int p = dp(ctx, 16);
        l.setPadding(p, p / 2, p, 0);

        l.addView(label(ctx, "Server address"));
        final EditText url = field(ctx, "wss://comms.example.com", prefs.serverUrl());
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        l.addView(url);

        l.addView(label(ctx, "Access key"));
        final EditText key = field(ctx, "shared key from the server admin", prefs.accessKey());
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        l.addView(key);

        l.addView(label(ctx, "Position update interval (seconds)"));
        final EditText interval = field(ctx, "5", String.valueOf(prefs.positionIntervalSec()));
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        l.addView(interval);

        final CheckBox share = new CheckBox(ctx);
        share.setText("Share my location with other users");
        share.setChecked(prefs.shareLocation());
        l.addView(share);

        final CheckBox auto = new CheckBox(ctx);
        auto.setText("Connect automatically when ATAK starts");
        auto.setChecked(prefs.autoConnect());
        l.addView(auto);

        new AlertDialog.Builder(Ui.mapContext())
                .setTitle("Tactical Comms – settings")
                .setView(l)
                .setPositiveButton("Save & connect", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int iv;
                        try { iv = Integer.parseInt(interval.getText().toString().trim()); } catch (NumberFormatException e) { iv = 5; }
                        prefs.save(url.getText().toString(), key.getText().toString(),
                                auto.isChecked(), share.isChecked(), iv);
                        if (mgr.state() != CommsClient.State.DISCONNECTED) mgr.disconnect();
                        mgr.connect();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Create a new group (group == null) or manage an existing one. */
    static void groupEditor(Context ctx, final CommsManager mgr, final Group group) {
        if (mgr.state() != CommsClient.State.CONNECTED) {
            Ui.toast("Not connected");
            return;
        }
        final boolean owner = group == null || mgr.myUid().equals(group.owner);
        LinearLayout l = column(ctx);
        int p = dp(ctx, 16);
        l.setPadding(p, p / 2, p, 0);

        l.addView(label(ctx, "Group name"));
        final EditText name = field(ctx, "e.g. Team Alpha", group == null ? "" : group.name);
        name.setEnabled(owner);
        l.addView(name);

        l.addView(label(ctx, owner ? "Members" : "Members (only the owner can remove members)"));
        LinearLayout boxes = column(ctx);
        final Map<String, CheckBox> checks = new LinkedHashMap<>();
        for (User u : mgr.users()) {
            CheckBox cb = new CheckBox(ctx);
            cb.setText(u.callsign + (u.online ? "" : " (offline)"));
            boolean member = group != null && group.members.contains(u.uid);
            cb.setChecked(member);
            if (member && !owner) cb.setEnabled(false);
            checks.put(u.uid, cb);
            boxes.addView(cb);
        }
        if (checks.isEmpty()) boxes.addView(label(ctx, "No other users known yet."));
        ScrollView sv = new ScrollView(ctx);
        sv.addView(boxes);
        l.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 260)));

        AlertDialog.Builder b = new AlertDialog.Builder(Ui.mapContext())
                .setTitle(group == null ? "New group" : "Manage group")
                .setView(l)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(group == null ? "Create" : "Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        List<String> selected = new ArrayList<>();
                        for (Map.Entry<String, CheckBox> e : checks.entrySet())
                            if (e.getValue().isChecked()) selected.add(e.getKey());
                        if (group == null) {
                            if (n.isEmpty()) { Ui.toast("Enter a group name"); return; }
                            mgr.createGroup(n, selected);
                            return;
                        }
                        List<String> add = new ArrayList<>(), remove = new ArrayList<>();
                        for (String uid : selected) if (!group.members.contains(uid)) add.add(uid);
                        if (owner) {
                            for (String uid : group.members)
                                if (!uid.equals(mgr.myUid()) && checks.containsKey(uid) && !selected.contains(uid)) remove.add(uid);
                        }
                        String rename = owner && !n.isEmpty() && !n.equals(group.name) ? n : null;
                        if (rename != null || !add.isEmpty() || !remove.isEmpty())
                            mgr.updateGroup(group.id, rename, add, remove);
                    }
                });
        if (group != null) {
            b.setNeutralButton("Leave group", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { mgr.leaveGroup(group.id); }
            });
        }
        b.show();
    }

}
