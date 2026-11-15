package com.tacticalcomms.atak.plugin;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.tacticalcomms.atak.R;
import com.tacticalcomms.atak.core.CommsManager;

/** Created by ATAK when the plugin loads; owns the comms manager and the drop-down. */
public class TacticalCommsMapComponent extends DropDownMapComponent {

    private CommsManager manager;
    private TacticalCommsDropDown dropDown;

    @Override
    public void onCreate(final Context context, Intent intent, final MapView view) {
        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);

        manager = new CommsManager(new Prefs(view.getContext()));
        dropDown = new TacticalCommsDropDown(view, context, manager);
        DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(TacticalCommsDropDown.SHOW_PLUGIN);
        registerDropDownReceiver(dropDown, filter);

        requestMicPermission(view.getContext());
        if (manager.prefs.isConfigured() && manager.prefs.autoConnect()) manager.connect();
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        if (manager != null) manager.shutdown();
        manager = null;
        super.onDestroyImpl(context, view);
    }

    /** PTT and calls need the microphone; ATAK owns the permission, so ask through its activity. */
    private static void requestMicPermission(Context mapCtx) {
        if (Build.VERSION.SDK_INT < 23) return;
        if (mapCtx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return;
        if (mapCtx instanceof Activity) {
            ((Activity) mapCtx).requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0x7C01);
        }
    }
}
