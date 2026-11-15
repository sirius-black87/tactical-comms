package com.tacticalcomms.atak.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Plugin settings, stored in ATAK's shared preferences with a "tc_" prefix. */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context mapContext) {
        sp = PreferenceManager.getDefaultSharedPreferences(mapContext);
    }

    public String serverUrl() { return sp.getString("tc_server_url", ""); }
    public String accessKey() { return sp.getString("tc_access_key", ""); }
    public boolean autoConnect() { return sp.getBoolean("tc_auto_connect", true); }
    public boolean shareLocation() { return sp.getBoolean("tc_share_location", true); }
    public int positionIntervalSec() { return sp.getInt("tc_pos_interval", 5); }

    /** Pinned TLS certificate fingerprint for servers addressed by IP ("" = trust the first one seen). */
    public String certFingerprint() { return sp.getString("tc_cert_fp", ""); }
    public void setCertFingerprint(String fp) { sp.edit().putString("tc_cert_fp", fp).apply(); }

    public void save(String url, String key, String fingerprint, boolean autoConnect, boolean share, int interval) {
        sp.edit()
                .putString("tc_server_url", url.trim())
                .putString("tc_cert_fp", fingerprint.trim())
                .putString("tc_access_key", key)
                .putBoolean("tc_auto_connect", autoConnect)
                .putBoolean("tc_share_location", share)
                .putInt("tc_pos_interval", Math.max(1, interval))
                .apply();
    }

    public boolean isConfigured() { return !serverUrl().isEmpty(); }
}
