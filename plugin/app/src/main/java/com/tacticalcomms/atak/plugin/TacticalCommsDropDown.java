package com.tacticalcomms.atak.plugin;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.tacticalcomms.atak.core.CommsManager;
import com.tacticalcomms.atak.ui.MainPane;
import com.tacticalcomms.atak.ui.Ui;

/** The side panel that hosts {@link MainPane}. */
public class TacticalCommsDropDown extends DropDownReceiver implements OnStateListener {
    public static final String SHOW_PLUGIN = "com.tacticalcomms.atak.SHOW_PLUGIN";

    private final MainPane pane;
    private final CommsManager manager;

    public TacticalCommsDropDown(MapView mapView, Context pluginContext, CommsManager manager) {
        super(mapView);
        this.manager = manager;
        this.pane = new MainPane(pluginContext, manager);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SHOW_PLUGIN.equals(intent.getAction())) return;
        if (!isVisible()) {
            showDropDown(pane.getView(), HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
        }
        if (!manager.prefs.isConfigured()) Ui.toastLong("Tap ⚙ to enter your server address and access key");
    }

    @Override
    protected void disposeImpl() {
        pane.dispose();
    }

    @Override public void onDropDownSelectionRemoved() { }
    @Override public void onDropDownVisible(boolean v) { }
    @Override public void onDropDownSizeChanged(double width, double height) { }
    @Override public void onDropDownClose() { }
}
