package com.tacticalcomms.atak.map;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.tacticalcomms.atak.model.User;
import com.tacticalcomms.atak.net.CommsClient;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Publishes the own position to the server and draws other users on the ATAK map
 * by dispatching CoT events internally (they appear as normal friendly markers).
 */
public class PositionManager {
    /** Prefix so our markers never collide with UIDs coming from a TAK server. */
    public static final String UID_PREFIX = "tc-";
    private static final long MAX_SILENCE_MS = 30000;
    private static final double MIN_MOVE_M = 5;
    private static final int STALE_MINUTES = 10;
    /** CoT convention for "unknown" altitude / error values. */
    private static final double COT_UNKNOWN = 9999999.0;

    private final CommsClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private boolean share = true;
    private int intervalSec = 5;
    private GeoPoint lastSent;
    private long lastSentAt;

    public PositionManager(CommsClient client) { this.client = client; }

    public void configure(boolean share, int intervalSec) {
        this.share = share;
        this.intervalSec = Math.max(1, intervalSec);
    }

    public void start() {
        if (running) return;
        running = true;
        lastSent = null;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { report(); } finally { handler.postDelayed(this, intervalSec * 1000L); }
        }
    };

    private void report() {
        if (!share || !client.isConnected()) return;
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        Marker self = mv.getSelfMarker();
        if (self == null) return;
        GeoPoint p = self.getPoint();
        if (p == null || !p.isValid() || (p.getLatitude() == 0 && p.getLongitude() == 0)) return;
        long now = System.currentTimeMillis();
        boolean moved = lastSent == null || lastSent.distanceTo(p) > MIN_MOVE_M;
        if (!moved && now - lastSentAt < MAX_SILENCE_MS) return;
        try {
            JSONObject m = new JSONObject().put("t", "pos")
                    .put("lat", p.getLatitude()).put("lon", p.getLongitude());
            if (!Double.isNaN(p.getAltitude())) m.put("hae", p.getAltitude());
            if (!Double.isNaN(p.getCE())) m.put("ce", p.getCE());
            double speed = self.getMetaDouble("Speed", Double.NaN);
            if (!Double.isNaN(speed)) m.put("speed", speed);
            int battery = battery(mv.getContext());
            if (battery >= 0) m.put("battery", battery);
            if (client.send(m)) {
                lastSent = p;
                lastSentAt = now;
            }
        } catch (JSONException ignored) { }
    }

    private static int battery(Context ctx) {
        if (Build.VERSION.SDK_INT < 21) return -1;
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        return bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    /** Create / move the map marker for a remote user. */
    public void showUser(User u) {
        if (!u.hasPosition()) return;
        long age = System.currentTimeMillis() - u.posTs;
        if (age > STALE_MINUTES * 60000L) return;

        CotEvent ev = new CotEvent();
        ev.setUID(UID_PREFIX + u.uid);
        ev.setType("a-f-G-U-C");
        ev.setHow("m-g");
        ev.setVersion("2.0");
        CoordinatedTime now = new CoordinatedTime();
        ev.setTime(now);
        ev.setStart(now);
        ev.setStale(now.addMinutes(STALE_MINUTES));
        ev.setPoint(new CotPoint(u.lat, u.lon,
                Double.isNaN(u.hae) ? COT_UNKNOWN : u.hae, COT_UNKNOWN, COT_UNKNOWN));

        CotDetail detail = new CotDetail("detail");
        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", u.callsign);
        detail.addChild(contact);
        CotDetail group = new CotDetail("__group");
        group.setAttribute("name", "Cyan");
        group.setAttribute("role", "Team Member");
        detail.addChild(group);
        if (u.battery >= 0) {
            CotDetail status = new CotDetail("status");
            status.setAttribute("battery", String.valueOf(u.battery));
            detail.addChild(status);
        }
        CotDetail remarks = new CotDetail("remarks");
        remarks.setInnerText("Tactical Comms user" + (u.online ? "" : " (offline)"));
        detail.addChild(remarks);
        ev.setDetail(detail);

        CotMapComponent.getInternalDispatcher().dispatch(ev);
    }

    /** Pan the map to a user. */
    public static boolean panTo(User u) {
        if (!u.hasPosition()) return false;
        MapView.getMapView().getMapController().panTo(new GeoPoint(u.lat, u.lon), true);
        return true;
    }
}
