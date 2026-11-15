package com.tacticalcomms.atak.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;
import com.atakmap.util.Disposable;
import com.tacticalcomms.atak.R;

/** Toolbar button, declared in assets/plugin.xml. Opens the drop-down. */
public class TacticalCommsTool extends AbstractPluginTool implements Disposable {
    public TacticalCommsTool(Context context) {
        super(context,
                context.getString(R.string.app_name),
                context.getString(R.string.app_name),
                context.getResources().getDrawable(R.drawable.ic_launcher),
                TacticalCommsDropDown.SHOW_PLUGIN);
    }

    @Override
    public void dispose() { }
}
