package com.tacticalcomms.atak.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginLifecycle;

/** Plugin lifecycle, declared in assets/plugin.xml. */
public class TacticalCommsLifecycle extends AbstractPluginLifecycle {
    public TacticalCommsLifecycle(Context ctx) {
        super(ctx, new TacticalCommsMapComponent());
    }
}
