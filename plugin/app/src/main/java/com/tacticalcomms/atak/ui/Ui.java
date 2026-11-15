package com.tacticalcomms.atak.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;

/** Small helpers for posting to the UI thread from anywhere in the plugin. */
public final class Ui {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {}

    /** The ATAK activity context (needed for dialogs; the plugin context cannot host windows). */
    public static Context mapContext() { return MapView.getMapView().getContext(); }

    public static void run(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    public static void later(Runnable r, long ms) { MAIN.postDelayed(r, ms); }
    public static void cancel(Runnable r) { MAIN.removeCallbacks(r); }

    public static void toast(final String text) {
        run(new Runnable() {
            @Override public void run() {
                Toast.makeText(mapContext(), text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public static void toastLong(final String text) {
        run(new Runnable() {
            @Override public void run() {
                Toast.makeText(mapContext(), text, Toast.LENGTH_LONG).show();
            }
        });
    }

    public static String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }
}
