package com.tacticalcomms.atak.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.tacticalcomms.atak.core.CommsManager;
import com.tacticalcomms.atak.core.PttController;
import com.tacticalcomms.atak.map.PositionManager;
import com.tacticalcomms.atak.model.ChatMessage;
import com.tacticalcomms.atak.model.Group;
import com.tacticalcomms.atak.model.Target;
import com.tacticalcomms.atak.model.User;
import com.tacticalcomms.atak.net.CommsClient;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.tacticalcomms.atak.ui.Views.*;

/** The plugin's side pane: status bar, tabs (Contacts / Groups / Chats) and the chat screen. */
public class MainPane implements CommsManager.Observer {
    private static final int TAB_CONTACTS = 0, TAB_GROUPS = 1, TAB_CHATS = 2;

    private final Context ctx;
    private final CommsManager mgr;
    private final LinearLayout root;
    private final TextView statusText;
    private final TextView pttBanner;
    private final Button connectBtn;
    private final Button[] tabs = new Button[3];
    private final FrameLayout content;
    private final ScrollView listScroll;
    private final LinearLayout list;
    private final ChatScreen chat;

    private int tab = TAB_CONTACTS;
    private boolean dirty;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);

    public MainPane(Context pluginContext, CommsManager mgr) {
        this.ctx = pluginContext;
        this.mgr = mgr;

        root = column(ctx);
        root.setBackgroundColor(BG);
        int p = dp(ctx, 8);
        root.setPadding(p, p, p, p);

        // status bar
        LinearLayout status = row(ctx);
        statusText = text(ctx, "", 13, MUTED);
        status.addView(statusText, weight(1));
        connectBtn = button(ctx, "Connect", ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (MainPane.this.mgr.state() == CommsClient.State.DISCONNECTED
                        || MainPane.this.mgr.state() == CommsClient.State.AUTH_FAILED) {
                    MainPane.this.mgr.connect();
                } else {
                    MainPane.this.mgr.disconnect();
                }
            }
        });
        status.addView(connectBtn, margins(ctx, 4, 0, 0, 0));
        status.addView(button(ctx, "⚙", CARD, new View.OnClickListener() {
            @Override public void onClick(View v) { Dialogs.settings(ctx, MainPane.this.mgr); }
        }), margins(ctx, 4, 0, 0, 0));
        root.addView(status);

        pttBanner = text(ctx, "", 14, TEXT);
        pttBanner.setBackground(rounded(DANGER, dp(ctx, 6)));
        pttBanner.setPadding(p, p / 2, p, p / 2);
        pttBanner.setVisibility(View.GONE);
        root.addView(pttBanner, margins(ctx, 0, 6, 0, 0));

        // tabs
        LinearLayout tabRow = row(ctx);
        String[] labels = {"Contacts", "Groups", "Chats"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            tabs[i] = button(ctx, labels[i], CARD, new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); }
            });
            LinearLayout.LayoutParams lp = weight(1);
            lp.setMargins(i == 0 ? 0 : dp(ctx, 4), dp(ctx, 8), 0, dp(ctx, 8));
            tabRow.addView(tabs[i], lp);
        }
        root.addView(tabRow);

        content = new FrameLayout(ctx);
        listScroll = new ScrollView(ctx);
        list = column(ctx);
        listScroll.addView(list);
        content.addView(listScroll);
        chat = new ChatScreen();
        chat.view.setVisibility(View.GONE);
        content.addView(chat.view);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        mgr.addObserver(this);
        render();
    }

    public View getView() { return root; }

    public void dispose() { mgr.removeObserver(this); }

    // ------------------------------------------------------------------ rendering
    private final Runnable renderNow = new Runnable() {
        @Override public void run() { render(); }
    };

    @Override public void onCommsChanged() {
        Ui.cancel(renderNow);
        Ui.later(renderNow, 250);  // coalesce bursts (e.g. many position updates)
    }

    private void selectTab(int i) {
        tab = i;
        chat.close();
        render();
    }

    private void render() {
        renderStatus();
        int unread = mgr.chats.totalUnread();
        tabs[TAB_CHATS].setText(unread > 0 ? "Chats (" + unread + ")" : "Chats");
        for (int i = 0; i < 3; i++) tabs[i].setBackground(rounded(i == tab ? ACCENT : CARD, dp(ctx, 6)));

        if (chat.isOpen()) {
            chat.refresh();
            return;
        }
        // Never rebuild the list under a finger that is holding a PTT button.
        if (mgr.ptt.isTalking()) {
            dirty = true;
            return;
        }
        dirty = false;
        list.removeAllViews();
        switch (tab) {
            case TAB_CONTACTS: renderContacts(); break;
            case TAB_GROUPS: renderGroups(); break;
            default: renderChats(); break;
        }
    }

    private void renderStatus() {
        CommsClient.State s = mgr.state();
        String txt;
        switch (s) {
            case CONNECTED:
                txt = "● Connected as " + MapView.getMapView().getDeviceCallsign();
                statusText.setTextColor(ONLINE);
                connectBtn.setText("Disconnect");
                break;
            case CONNECTING:
                txt = "◌ Connecting…";
                statusText.setTextColor(MUTED);
                connectBtn.setText("Cancel");
                break;
            case AUTH_FAILED:
                txt = "✖ Wrong access key";
                statusText.setTextColor(DANGER);
                connectBtn.setText("Connect");
                break;
            default:
                txt = "○ Offline" + (mgr.stateDetail() != null ? " – " + mgr.stateDetail() : "");
                statusText.setTextColor(MUTED);
                connectBtn.setText("Connect");
                break;
        }
        statusText.setText(txt);

        PttController.Incoming in = mgr.ptt.currentIncoming();
        if (mgr.ptt.isTalking()) {
            pttBanner.setText("🎙 Transmitting to " + mgr.titleOf(mgr.ptt.talkingTo()));
            pttBanner.setVisibility(View.VISIBLE);
        } else if (in != null) {
            String where = in.target.isGroup() ? " (" + mgr.titleOf(in.target) + ")" : "";
            pttBanner.setText("🔊 " + in.callsign + where + " is talking");
            pttBanner.setVisibility(View.VISIBLE);
        } else {
            pttBanner.setVisibility(View.GONE);
        }
    }

    private void empty(String msg) {
        TextView t = text(ctx, msg, 14, MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(ctx, 24), 0, 0);
        list.addView(t);
    }

    private void renderContacts() {
        List<User> users = mgr.users();
        if (users.isEmpty()) {
            empty(mgr.state() == CommsClient.State.CONNECTED
                    ? "No other users yet.\nEveryone who installs the plugin and connects to\nthe same server will appear here."
                    : "Connect to the server to see other users.");
            return;
        }
        for (final User u : users) {
            LinearLayout card = card(ctx);
            LinearLayout head = row(ctx);
            TextView name = text(ctx, (u.online ? "● " : "○ ") + u.callsign, 16, TEXT);
            head.addView(name, weight(1));
            card.addView(head);
            name.setTextColor(u.online ? TEXT : MUTED);
            card.addView(text(ctx, describe(u), 12, MUTED));

            final Target t = Target.user(u.uid);
            LinearLayout actions = row(ctx);
            actions.setPadding(0, dp(ctx, 6), 0, 0);
            actions.addView(button(ctx, "💬 Chat", ACCENT_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) { chat.open(t); }
            }), btnLp());
            actions.addView(button(ctx, "📍 Map", ACCENT_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!PositionManager.panTo(u)) Ui.toast(u.callsign + " has not shared a position");
                }
            }), btnLp());
            actions.addView(button(ctx, "📞 Call", ACCENT_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) { mgr.calls.call(u.uid, u.callsign); }
            }), btnLp());
            actions.addView(button(ctx, "📎", ACCENT_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) { pickAndSend(t); }
            }), btnLp());
            card.addView(actions);
            card.addView(pttButton(t, "🎙 Hold to talk"), fullWidth(6));
            list.addView(card);
        }
    }

    private String describe(User u) {
        StringBuilder sb = new StringBuilder(u.online ? "online" : "offline");
        if (u.hasPosition()) {
            Marker self = MapView.getMapView().getSelfMarker();
            if (self != null && self.getPoint() != null && self.getPoint().isValid()) {
                double d = self.getPoint().distanceTo(new GeoPoint(u.lat, u.lon));
                sb.append(" · ").append(d < 1000 ? Math.round(d) + " m" : String.format(Locale.US, "%.1f km", d / 1000));
            }
        }
        if (u.battery >= 0) sb.append(" · 🔋").append(u.battery).append('%');
        return sb.toString();
    }

    private void renderGroups() {
        list.addView(button(ctx, "＋ New group", ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { Dialogs.groupEditor(ctx, mgr, null); }
        }), fullWidth(0));
        if (mgr.groups().isEmpty()) {
            empty("No groups yet.\nCreate one and pick its members – no server admin needed.");
            return;
        }
        for (final Group g : mgr.groups()) {
            LinearLayout card = card(ctx);
            card.setLayoutParams(fullWidth(8));
            card.addView(text(ctx, "👥 " + g.name, 16, TEXT));
            int online = 0;
            StringBuilder names = new StringBuilder();
            for (String m : g.members) {
                User u = mgr.user(m);
                if (m.equals(mgr.myUid()) || (u != null && u.online)) online++;
                if (names.length() > 0) names.append(", ");
                names.append(mgr.callsignOf(m));
            }
            card.addView(text(ctx, g.members.size() + " members, " + online + " online", 12, MUTED));
            TextView membersTv = text(ctx, names, 12, MUTED);
            membersTv.setEllipsize(TextUtils.TruncateAt.END);
            membersTv.setMaxLines(2);
            card.addView(membersTv);

            final Target t = Target.group(g.id);
            LinearLayout actions = row(ctx);
            actions.setPadding(0, dp(ctx, 6), 0, 0);
            actions.addView(button(ctx, "💬 Chat", ACCENT_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) { chat.open(t); }
            }), btnLp());
            actions.addView(button(ctx, "📎 File", ACCENT_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) { pickAndSend(t); }
            }), btnLp());
            actions.addView(button(ctx, "⚙ Manage", ACCENT_DIM, new View.OnClickListener() {
                @Override public void onClick(View v) { Dialogs.groupEditor(ctx, mgr, g); }
            }), btnLp());
            card.addView(actions);
            card.addView(pttButton(t, "🎙 Hold to talk to group"), fullWidth(6));
            list.addView(card);
        }
    }

    private void renderChats() {
        List<String> convs = mgr.chats.conversations();
        if (convs.isEmpty()) {
            empty("No conversations yet.");
            return;
        }
        for (final String key : convs) {
            final Target t = Target.fromKey(key);
            ChatMessage last = mgr.chats.last(key);
            LinearLayout card = card(ctx);
            LinearLayout head = row(ctx);
            head.addView(text(ctx, (t.isGroup() ? "👥 " : "👤 ") + mgr.titleOf(t), 15, TEXT), weight(1));
            int n = mgr.chats.unread(key);
            if (n > 0) {
                TextView badge = text(ctx, " " + n + " ", 12, TEXT);
                badge.setBackground(rounded(DANGER, dp(ctx, 10)));
                head.addView(badge);
            }
            card.addView(head);
            if (last != null) {
                TextView prev = text(ctx, (last.outgoing ? "Me: " : last.callsign + ": ")
                        + (last.hasFile() ? "📎 " + last.fileName : last.text), 12, MUTED);
                prev.setSingleLine(true);
                prev.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(prev);
            }
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { chat.open(t); }
            });
            list.addView(card);
        }
    }

    // ------------------------------------------------------------------ widgets
    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = weight(1);
        lp.setMargins(0, 0, dp(ctx, 4), 0);
        return lp;
    }

    private LinearLayout.LayoutParams fullWidth(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, topDp), 0, 0);
        return lp;
    }

    /** A button that transmits while it is held down. */
    @SuppressLint("ClickableViewAccessibility")
    private Button pttButton(final Target t, final String label) {
        final Button b = button(ctx, label, ACCENT, null);
        b.setMinHeight(dp(ctx, 48));
        b.setTextSize(15);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        b.setBackground(rounded(DANGER, dp(ctx, 6)));
                        b.setText("● TRANSMITTING – release to stop");
                        mgr.ptt.startTalking(t);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        b.setBackground(rounded(ACCENT, dp(ctx, 6)));
                        b.setText(label);
                        mgr.ptt.stopTalking();
                        if (dirty) render();
                        return true;
                    default:
                        return true;
                }
            }
        });
        return b;
    }

    private void pickAndSend(final Target t) {
        FilePicker.show(ctx, new FilePicker.Callback() {
            @Override public void onFile(File f) {
                mgr.sendFile(t, f);
                Ui.toast("Sending " + f.getName() + " to " + mgr.titleOf(t));
            }
        });
    }

    // ------------------------------------------------------------------ chat screen
    private class ChatScreen {
        final LinearLayout view = column(ctx);
        final TextView title = text(ctx, "", 16, TEXT);
        final ScrollView scroll = new ScrollView(ctx);
        final LinearLayout messages = column(ctx);
        final EditText input = new EditText(ctx);
        final LinearLayout pttHolder = column(ctx);
        Target target;

        ChatScreen() {
            LinearLayout head = row(ctx);
            head.addView(button(ctx, "←", CARD, new View.OnClickListener() {
                @Override public void onClick(View v) { close(); render(); }
            }));
            title.setPadding(dp(ctx, 8), 0, 0, 0);
            head.addView(title, weight(1));
            view.addView(head);

            scroll.addView(messages);
            view.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            LinearLayout inputRow = row(ctx);
            inputRow.addView(button(ctx, "📎", CARD, new View.OnClickListener() {
                @Override public void onClick(View v) { if (target != null) pickAndSend(target); }
            }));
            input.setHint("Message");
            input.setTextColor(TEXT);
            input.setHintTextColor(MUTED);
            input.setMaxLines(4);
            inputRow.addView(input, weight(1));
            inputRow.addView(button(ctx, "Send", ACCENT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String s = input.getText().toString().trim();
                    if (s.isEmpty() || target == null) return;
                    mgr.sendText(target, s);
                    input.setText("");
                }
            }));
            view.addView(inputRow, fullWidth(4));
            view.addView(pttHolder, fullWidth(4));
        }

        boolean isOpen() { return target != null; }

        void open(Target t) {
            target = t;
            mgr.setOpenConversation(t.key());
            pttHolder.removeAllViews();
            pttHolder.addView(pttButton(t, "🎙 Hold to talk"), fullWidth(0));
            listScroll.setVisibility(View.GONE);
            view.setVisibility(View.VISIBLE);
            render();
        }

        void close() {
            target = null;
            mgr.setOpenConversation(null);
            view.setVisibility(View.GONE);
            listScroll.setVisibility(View.VISIBLE);
        }

        void refresh() {
            if (target == null) return;
            mgr.chats.clearUnread(target.key());
            title.setText((target.isGroup() ? "👥 " : "👤 ") + mgr.titleOf(target));
            messages.removeAllViews();
            for (final ChatMessage m : mgr.chats.messages(target.key())) messages.addView(bubble(m));
            scroll.post(new Runnable() {
                @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
            });
        }

        private View bubble(final ChatMessage m) {
            LinearLayout b = column(ctx);
            int p = dp(ctx, 8);
            b.setPadding(p, p / 2, p, p / 2);
            b.setBackground(rounded(m.outgoing ? OUT_BUBBLE : CARD, dp(ctx, 8)));
            String st = "";
            if (m.outgoing) {
                st = m.status == ChatMessage.SENDING ? "  ◷"
                        : m.status == ChatMessage.FAILED ? "  ⚠ failed – tap to retry" : "  ✓";
            }
            b.addView(text(ctx, (m.outgoing ? "Me" : m.callsign) + "  " + timeFmt.format(new Date(m.ts)) + st, 11, MUTED));
            if (m.text != null && !m.text.isEmpty()) {
                TextView body = text(ctx, m.text, 14, TEXT);
                body.setTextIsSelectable(true);
                b.addView(body);
            }
            if (m.hasFile()) {
                b.addView(text(ctx, "📎 " + m.fileName + " (" + Ui.formatSize(m.fileSize) + ")", 14, TEXT));
                if (m.localPath != null) {
                    b.addView(text(ctx, m.outgoing ? "sent from " + m.localPath : "saved to " + m.localPath, 11, MUTED));
                } else if (!m.outgoing && m.fileId != null) {
                    b.addView(button(ctx, "⬇ Download", ACCENT, new View.OnClickListener() {
                        @Override public void onClick(View v) { mgr.download(m); }
                    }), margins(ctx, 0, 4, 0, 0));
                }
            }
            if (m.outgoing && m.status == ChatMessage.FAILED) {
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { mgr.retry(m); }
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = m.outgoing ? Gravity.END : Gravity.START;
            lp.setMargins(m.outgoing ? dp(ctx, 40) : 0, dp(ctx, 3), m.outgoing ? 0 : dp(ctx, 40), dp(ctx, 3));
            b.setLayoutParams(lp);
            return b;
        }
    }
}
